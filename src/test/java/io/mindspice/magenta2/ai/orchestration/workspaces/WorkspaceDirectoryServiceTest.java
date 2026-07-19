package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class WorkspaceDirectoryServiceTest {

    @TempDir
    Path tempDir;

    private WorkspaceDirectoryService service;
    private AgentProfileRepository agentRepo;
    private ChatConversationExistenceRepository chatRepo;

    @BeforeEach
    void setUp() throws Exception {
        agentRepo = WorkspaceDirectoryServiceTestSupport.agentProfileRepositoryWith("agent-1", "agent-2");
        chatRepo = WorkspaceDirectoryServiceTestSupport.chatExistenceRepositoryWith("conv-1", "conv-2");
        service = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir, agentRepo, chatRepo);
    }

    // ── resolveForRead ──

    @Test
    void resolveForReadAgentReturnsDescriptorWithoutCreatingRoot() throws Exception {
        var descriptor = service.resolveForRead(WorkspaceKind.AGENT, "agent-1");

        assertThat(descriptor.kind()).isEqualTo(WorkspaceKind.AGENT);
        assertThat(descriptor.key()).isEqualTo("agent-1");
        assertThat(descriptor.identity()).isEqualTo("agent/agent-1");
        assertThat(descriptor.root()).isEqualTo(
                tempDir.resolve("workspace/agent/agent-1").toAbsolutePath().normalize());
        // Physical directory must NOT be created
        assertThat(Files.exists(descriptor.root())).isFalse();
    }

    @Test
    void resolveForReadChatReturnsDescriptorWithoutCreatingRoot() throws Exception {
        var descriptor = service.resolveForRead(WorkspaceKind.CHAT, "conv-1");

        assertThat(descriptor.kind()).isEqualTo(WorkspaceKind.CHAT);
        assertThat(descriptor.identity()).isEqualTo("chat/conv-1");
        assertThat(Files.exists(descriptor.root())).isFalse();
    }

    @Test
    void resolveForReadUserRequiresExistingDirectory() throws Exception {
        // Create the user workspace first
        Files.createDirectories(tempDir.resolve("workspace/user"));
        Files.createDirectory(tempDir.resolve("workspace/user/alice"));

        var descriptor = service.resolveForRead(WorkspaceKind.USER, "alice");

        assertThat(descriptor.kind()).isEqualTo(WorkspaceKind.USER);
        assertThat(descriptor.identity()).isEqualTo("user/alice");
    }

    @Test
    void resolveForReadMissingUserThrowsNotFound() {
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.USER, "alice"))
            .isInstanceOf(WorkspaceResolutionException.class)
            .hasMessageContaining("User workspace not found");
    }

    @Test
    void resolveForReadMissingAgentThrowsNotFound() {
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.AGENT, "nonexistent"))
            .isInstanceOf(WorkspaceResolutionException.class)
            .hasMessageContaining("Agent not found");
    }

    @Test
    void resolveForReadMissingChatThrowsNotFound() {
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.CHAT, "nonexistent"))
            .isInstanceOf(WorkspaceResolutionException.class)
            .hasMessageContaining("Chat conversation not found");
    }

    // ── resolveForWrite ──

    @Test
    void resolveForWriteAgentCreatesMissingRoot() throws Exception {
        var descriptor = service.resolveForWrite(WorkspaceKind.AGENT, "agent-1");

        assertThat(Files.isDirectory(descriptor.root(), LinkOption.NOFOLLOW_LINKS)).isTrue();
        // No starter file
        assertThat(Files.exists(descriptor.root().resolve("AGENTS.md"))).isFalse();
        // No outputs directory
        assertThat(Files.exists(descriptor.root().resolve("outputs"))).isFalse();
    }

    @Test
    void resolveForWriteChatCreatesMissingRoot() throws Exception {
        var descriptor = service.resolveForWrite(WorkspaceKind.CHAT, "conv-1");

        assertThat(Files.isDirectory(descriptor.root(), LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.exists(descriptor.root().resolve("AGENTS.md"))).isFalse();
    }

    @Test
    void resolveForWriteUserRequiresExistingDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/user"));
        Files.createDirectory(tempDir.resolve("workspace/user/alice"));

        var descriptor = service.resolveForWrite(WorkspaceKind.USER, "alice");

        assertThat(descriptor.identity()).isEqualTo("user/alice");
    }

    @Test
    void resolveForWriteMissingUserThrowsNotFound() {
        assertThatThrownBy(() -> service.resolveForWrite(WorkspaceKind.USER, "alice"))
            .isInstanceOf(WorkspaceResolutionException.class);
    }

    // ── createUserWorkspace ──

    @Test
    void createUserWorkspaceCreatesRootAndParents() throws Exception {
        var descriptor = service.createUserWorkspace("alice");

        assertThat(descriptor.kind()).isEqualTo(WorkspaceKind.USER);
        assertThat(descriptor.key()).isEqualTo("alice");
        assertThat(descriptor.identity()).isEqualTo("user/alice");
        assertThat(Files.isDirectory(descriptor.root(), LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isDirectory(
                tempDir.resolve("workspace/user"), LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void createUserWorkspaceRejectsDuplicate() throws Exception {
        service.createUserWorkspace("alice");

        assertThatThrownBy(() -> service.createUserWorkspace("alice"))
            .isInstanceOf(WorkspaceResolutionException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void createUserWorkspaceRejectsInvalidNames() {
        assertThatThrownBy(() -> service.createUserWorkspace("a/b"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUserWorkspace(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUserWorkspace(".."))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── listUserWorkspaces ──

    @Test
    void listUserWorkspacesReturnsSortedDescriptors() throws Exception {
        service.createUserWorkspace("bob");
        service.createUserWorkspace("alice");
        service.createUserWorkspace("charlie");

        List<WorkspaceDescriptor> list = service.listUserWorkspaces();

        assertThat(list).hasSize(3);
        assertThat(list).extracting(WorkspaceDescriptor::key)
            .containsExactly("alice", "bob", "charlie");
    }

    @Test
    void listUserWorkspacesFiltersNonDirectoriesAndSymlinks() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/user"));
        service.createUserWorkspace("real-dir");
        Files.createFile(tempDir.resolve("workspace/user/not-a-dir"));
        Files.createDirectory(tempDir.resolve("workspace/user/   "));
        Path outside = Files.createDirectory(tempDir.resolve("listed-outside"));
        Files.createSymbolicLink(tempDir.resolve("workspace/user/linked-dir"), outside);

        List<WorkspaceDescriptor> list = service.listUserWorkspaces();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).key()).isEqualTo("real-dir");
    }

    @Test
    void listUserWorkspacesEmptyWhenParentMissing() {
        List<WorkspaceDescriptor> list = service.listUserWorkspaces();

        assertThat(list).isEmpty();
    }

    // ── outputs ──

    @Test
    void outputsCreatesLazyOutputsDirectoryForAgent() throws Exception {
        var descriptor = service.resolveForWrite(WorkspaceKind.AGENT, "agent-1");

        Path outputs = service.outputs(descriptor);

        assertThat(Files.isDirectory(outputs, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(outputs).isEqualTo(descriptor.root().resolve("outputs"));
    }

    @Test
    void outputsRequiresExistingUserDeclaration() throws Exception {
        // Create the user workspace first
        Files.createDirectories(tempDir.resolve("workspace/user"));
        Files.createDirectory(tempDir.resolve("workspace/user/alice"));
        var descriptor = service.resolveForRead(WorkspaceKind.USER, "alice");

        Path outputs = service.outputs(descriptor);

        assertThat(Files.isDirectory(outputs, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void outputsRejectsUserDeletedAfterResolution() throws Exception {
        var descriptor = service.createUserWorkspace("alice");
        Files.delete(descriptor.root());

        assertThatThrownBy(() -> service.outputs(descriptor))
            .isInstanceOf(WorkspaceResolutionException.class);
    }

    @Test
    void outputsRevalidatesAgentExistenceAfterResolution() throws Exception {
        AtomicBoolean exists = new AtomicBoolean(true);
        AgentProfileRepository mutableRepository = new AgentProfileRepository(
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                "jdbc:sqlite::memory:?foreign_keys=true", true)),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public boolean existsById(String id) {
                return exists.get();
            }
        };
        WorkspaceDirectoryService mutableService = WorkspaceDirectoryServiceTestSupport.directoryService(
                tempDir, mutableRepository, chatRepo);
        var descriptor = mutableService.resolveForRead(WorkspaceKind.AGENT, "agent-1");
        exists.set(false);

        assertThatThrownBy(() -> mutableService.outputs(descriptor))
            .isInstanceOf(WorkspaceResolutionException.class);
        assertThat(Files.exists(descriptor.root())).isFalse();
    }

    // ── Key validation ──

    @Test
    void resolveRejectsInvalidKeys() {
        for (String invalid : invalidSegments()) {
            assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.AGENT, invalid))
                .as("invalid agent key %s", invalid)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void resolveRejectsBlankKeys() {
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.AGENT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.CHAT, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.USER, "   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Confinement ──

    @Test
    void resolveForReadRejectsTraversalKeys() {
        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.AGENT, "../escape"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confinedRelativeRejectsTraversalAndBackslashes() {
        var descriptor = service.resolveForWrite(WorkspaceKind.CHAT, "conv-1");

        assertThatThrownBy(() -> service.confinedRelative(descriptor, "../outside"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confinedRelative(descriptor, "/absolute"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confinedRelative(descriptor, "nested\\escape"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confinedRelative(descriptor, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.confinedRelative(descriptor, "notes..md"))
            .isEqualTo(descriptor.root().resolve("notes..md"));
    }

    @Test
    void creationRejectsSymlinkedStructuralParent() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.createDirectory(tempDir.resolve("workspace"));
        Files.createSymbolicLink(tempDir.resolve("workspace/agent"), outside);

        assertThatThrownBy(() -> service.resolveForWrite(WorkspaceKind.AGENT, "agent-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(outside.resolve("agent-1"))).isFalse();
    }

    @Test
    void existingRootMustBeDirectNonSymlinkDirectory() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside-root"));
        Files.createDirectories(tempDir.resolve("workspace/chat"));
        Files.createSymbolicLink(tempDir.resolve("workspace/chat/conv-1"), outside);

        assertThatThrownBy(() -> service.resolveForRead(WorkspaceKind.CHAT, "conv-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void existingRootFileCollisionIsRejected() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent"));
        Files.createFile(tempDir.resolve("workspace/agent/agent-1"));

        assertThatThrownBy(() -> service.resolveForWrite(WorkspaceKind.AGENT, "agent-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outputsCreatesOnlyRootAndReservedChild() throws Exception {
        var descriptor = service.resolveForRead(WorkspaceKind.CHAT, "conv-1");

        Path outputs = service.outputs(descriptor);

        assertThat(outputs).isEqualTo(descriptor.root().resolve("outputs"));
        try (var entries = Files.walk(descriptor.root())) {
            assertThat(entries.map(descriptor.root()::relativize).map(Path::toString).toList())
                .containsExactlyInAnyOrder("", "outputs");
        }
    }

    // ── dataRoot ──

    @Test
    void dataRootReturnsConfiguredRoot() throws Exception {
        assertThat(service.dataRoot()).isEqualTo(tempDir.toRealPath());
    }

    private static String[] invalidSegments() {
        return new String[] {
            "a/b",
            "a\\b",
            "/abs",
            "%2e%2e",
            "a%2fb"
        };
    }
}
