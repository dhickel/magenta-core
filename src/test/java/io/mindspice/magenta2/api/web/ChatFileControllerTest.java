package io.mindspice.magenta2.api.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.mindspice.magenta2.ai.chat.service.ChatFileService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatFileControllerTest {

    private static final String CONVERSATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MISSING_CONVERSATION_ID = "00000000-0000-0000-0000-000000000002";

    @TempDir
    Path tempDir;

    @Test
    void listsDescriptorsWithoutAbsolutePaths() throws Exception {
        ChatFileController controller = controller(List.of(CONVERSATION_ID));
        Path root = chatFilesRoot();
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/report.txt"), "report");

        var listing = controller.files(CONVERSATION_ID);

        assertThat(listing.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(listing.count()).isEqualTo(1);
        assertThat(listing.files()).hasSize(1);
        assertThat(listing.files().get(0).relativePath()).isEqualTo("nested/report.txt");
        assertThat(listing.files().get(0).relativePath()).doesNotContain(tempDir.toString());
        assertThat(Path.of(listing.files().get(0).relativePath()).isAbsolute()).isFalse();
    }

    @Test
    void downloadReturnsAttachmentWithExpectedBody() throws Exception {
        ChatFileController controller = controller(List.of(CONVERSATION_ID));
        Files.writeString(chatFilesRoot().resolve("report.md"), "# Report");

        var response = controller.download(CONVERSATION_ID, "report.md");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("report.md");
        InputStreamResource body = (InputStreamResource) response.getBody();
        assertThat(new String(body.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("# Report");
    }

    @Test
    void missingConversationReturnsNotFound() throws Exception {
        ChatFileController controller = controller(List.of(CONVERSATION_ID));

        assertThatThrownBy(() -> controller.files(MISSING_CONVERSATION_ID))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void traversalDownloadIsRejected() throws Exception {
        ChatFileController controller = controller(List.of(CONVERSATION_ID));
        Files.createDirectories(chatFilesRoot());
        Files.writeString(tempDir.resolve("outside.txt"), "outside");

        var response = controller.download(CONVERSATION_ID, "../outside.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("escape");
    }

    private ChatFileController controller(List<String> conversationIds) throws Exception {
        Path dataRoot = Files.createDirectories(tempDir.resolve("data"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        return new ChatFileController(new StubChatService(conversationIds), new ChatFileService(directoryService));
    }

    /**
     * Phase 02: chat files are stored under workspace/chat/{conversationId}.
     */
    private Path chatFilesRoot() throws Exception {
        return Files.createDirectories(tempDir.resolve("data/workspace/chat/" + CONVERSATION_ID));
    }

    private static final class StubChatService extends ChatService {
        private final List<String> conversationIds;

        private StubChatService(List<String> conversationIds) {
            super(null, null, null, null, null);
            this.conversationIds = conversationIds;
        }

        @Override
        public boolean conversationExists(String conversationId) {
            return conversationIds.contains(conversationId);
        }
    }
}
