package io.mindspice.magenta2.ai.chat.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.mindspice.magenta2.ai.chat.model.ChatFileSummary;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.ChatConversationExistenceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileException;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatFileServiceTest {

    private static final String CONVERSATION_ID = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path tempDir;

    @Test
    void countsZeroWhenDirectoryIsEmpty() throws Exception {
        ChatFileService service = service();

        assertThat(service.countFiles(CONVERSATION_ID)).isZero();
        assertThat(service.listFiles(CONVERSATION_ID).files()).isEmpty();
    }

    @Test
    void countsAndListsNestedRegularFilesInStableOrder() throws Exception {
        ChatFileService service = service();
        Path root = chatWorkspaceRoot();
        Files.writeString(root.resolve("summary.md"), "# Summary");
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/data.json"), "{\"ok\":true}");
        Files.createDirectories(root.resolve("empty-dir"));

        var listing = service.listFiles(CONVERSATION_ID);

        assertThat(service.countFiles(CONVERSATION_ID)).isEqualTo(2);
        assertThat(listing.count()).isEqualTo(2);
        assertThat(listing.truncated()).isFalse();
        assertThat(listing.files())
            .extracting(ChatFileSummary::relativePath)
            .containsExactly("nested/data.json", "summary.md");
        assertThat(listing.files())
            .extracting(ChatFileSummary::formatLabel)
            .containsExactly("JSON", "Markdown");
    }

    @Test
    void listsCountsAndDownloadsSeededCopiedChatFile() throws Exception {
        Path newDataRoot = Files.createDirectories(tempDir.resolve("new-magenta-root/root"));
        ChatFileService service = service(newDataRoot);
        Path seeded = Files.createDirectories(
                newDataRoot.resolve("workspace/chat/" + CONVERSATION_ID + "/nested"))
            .resolve("seeded.md");
        Files.writeString(seeded, "# Seeded");

        var listing = service.listFiles(CONVERSATION_ID);

        assertThat(service.countFiles(CONVERSATION_ID)).isEqualTo(1);
        assertThat(listing.count()).isEqualTo(1);
        assertThat(listing.files())
            .singleElement()
            .satisfies(file -> {
                assertThat(file.relativePath()).isEqualTo("nested/seeded.md");
                assertThat(file.relativePath()).doesNotContain(newDataRoot.toString());
            });
        Path download = service.resolveDownload(CONVERSATION_ID, "nested/seeded.md");
        assertThat(download).isEqualTo(seeded.toRealPath());
        assertThat(Files.readString(download)).isEqualTo("# Seeded");
    }

    @Test
    void labelsExtensionlessFilesAsFile() throws Exception {
        ChatFileService service = service();
        Files.writeString(chatWorkspaceRoot().resolve("README"), "hello");

        List<ChatFileSummary> files = service.listFiles(CONVERSATION_ID).files();

        assertThat(files).hasSize(1);
        assertThat(files.get(0).extension()).isEmpty();
        assertThat(files.get(0).formatLabel()).isEqualTo("file");
    }

    @Test
    void rejectsTraversalForDownloadResolution() throws Exception {
        ChatFileService service = service();
        // Ensure the physical chat workspace directory exists so the
        // traversal/escape check fires before the missing-directory check
        chatWorkspaceRoot();
        Files.writeString(tempDir.resolve("outside.txt"), "outside");

        assertThatThrownBy(() -> service.resolveDownload(CONVERSATION_ID, "../outside.txt"))
            .isInstanceOf(WorkspaceFileException.class)
            .hasMessageContaining("invalid");
    }

    @Test
    void resolvesNestedDownloadInsideChatWorkspace() throws Exception {
        ChatFileService service = service();
        Path root = chatWorkspaceRoot();
        Files.createDirectories(root.resolve("nested"));
        Path expected = root.resolve("nested/data.csv");
        Files.writeString(expected, "a,b");

        assertThat(service.resolveDownload(CONVERSATION_ID, "nested/data.csv")).isEqualTo(expected.toRealPath());
        assertThatThrownBy(() -> service.resolveDownload(CONVERSATION_ID, "nested\\data.csv"))
            .isInstanceOf(WorkspaceFileException.class);
        assertThatThrownBy(() -> service.resolveDownload(CONVERSATION_ID, "nested"))
            .isInstanceOf(WorkspaceFileException.class)
            .extracting(e -> ((WorkspaceFileException) e).reason())
            .isEqualTo(WorkspaceFileException.Reason.NOT_FOUND);
    }

    @Test
    void ignoresSymlinksAndRejectsSymlinkDownloads() throws Exception {
        ChatFileService service = service();
        Path root = chatWorkspaceRoot();
        Path outside = Files.writeString(tempDir.resolve("outside-secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("linked.txt"), outside);

        assertThat(service.countFiles(CONVERSATION_ID)).isZero();
        assertThat(service.listFiles(CONVERSATION_ID).files()).isEmpty();
        assertThatThrownBy(() -> service.resolveDownload(CONVERSATION_ID, "linked.txt"))
            .isInstanceOf(WorkspaceFileException.class);
    }

    @Test
    void listingIsBoundedAtFiveHundredWithStableTruncationMetadata() throws Exception {
        ChatFileService service = service();
        Path root = chatWorkspaceRoot();
        for (int i = 0; i < 501; i++) {
            Files.writeString(root.resolve("file-%03d.txt".formatted(i)), "x");
        }

        var listing = service.listFiles(CONVERSATION_ID);

        assertThat(listing.count()).isEqualTo(501);
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.files()).hasSize(ChatFileService.MAX_LIST_FILES);
        assertThat(listing.files().getFirst().relativePath()).isEqualTo("file-000.txt");
        assertThat(listing.files().getLast().relativePath()).isEqualTo("file-499.txt");
    }

    @Test
    void missingConversationReturnsNotFound() throws Exception {
        // Create a service where only CONVERSATION_ID exists
        var chatExistenceRepo = WorkspaceDirectoryServiceTestSupport.chatExistenceRepositoryWith(CONVERSATION_ID);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(
                tempDir,
                WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true),
                chatExistenceRepo);
        ChatFileService service = new ChatFileService(dirService);

        assertThatThrownBy(() -> service.countFiles("nonexistent-conv"))
            .isInstanceOf(WorkspaceResolutionException.class);
    }

    @Test
    void absentChatRootReturnsEmpty() throws Exception {
        // Create service where the conversation EXISTS (repository says yes)
        // but the physical directory does not exist
        var dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        var jdbc = new JdbcTemplate(dataSource);
        var objMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // Pre-populate chat memory with the conversation so existence check passes
        var chatMemory = new ChatMemoryRepository(jdbc, objMapper) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                return CONVERSATION_ID.equals(conversationId);
            }
        };
        var chatSession = new ChatSessionMetadataRepository(jdbc) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                return false;
            }
        };
        var chatExistenceRepo = new ChatConversationExistenceRepository(chatMemory, chatSession, jdbc);

        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(
                tempDir,
                WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true),
                chatExistenceRepo);

        ChatFileService service = new ChatFileService(dirService);

        // The conversation exists but the physical directory does not
        assertThat(service.countFiles(CONVERSATION_ID)).isZero();
        assertThat(service.listFiles(CONVERSATION_ID).files()).isEmpty();
    }

    private ChatFileService service() throws Exception {
        return service(Files.createDirectories(tempDir.resolve("data")));
    }

    private ChatFileService service(Path dataRoot) throws Exception {
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        return new ChatFileService(dirService);
    }

    /**
     * Phase 02: chat files are stored under workspace/chat/{conversationId},
     * not chats/{conversationId}/files.
     */
    private Path chatWorkspaceRoot() throws Exception {
        Path root = tempDir.resolve("data/workspace/chat/" + CONVERSATION_ID);
        return Files.createDirectories(root);
    }
}
