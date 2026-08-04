package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceMdResolverTest {

    @TempDir
    Path tempDir;

    // ── Fixture helpers ──

    private WorkspaceDescriptor agentDescriptor() {
        return new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1",
                tempDir.resolve("workspace/agent/agent-1"));
    }

    private MaterializationLocation materializationLocation(WorkspaceDescriptor agentDesc,
                                                            MaterializationKey key, int generation) {
        Path confined = agentDesc.root().resolve("projects/" + key.serialized());
        String relative = "workspace/agent/" + agentDesc.key() + "/projects/" + key.serialized() + "/";
        return new MaterializationLocation(agentDesc, key, generation, relative,
                confined.toAbsolutePath().normalize());
    }

    private void createDir(Path path) throws IOException {
        Files.createDirectories(path);
    }

    private void writeWorkspaceMd(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("WORKSPACE.md"), content, StandardCharsets.UTF_8);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Proven absence
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void absentRootReturnsAbsentForMaterialization() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isFalse();
        assertThat(result.hasContent()).isFalse();
        assertThat(result.content()).isNull();
    }

    @Test
    void absentFileReturnsAbsentFromExistingRoot() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isFalse();
        assertThat(result.hasContent()).isFalse();
    }

    @Test
    void absentFileReturnsAbsentFromPersonalArea() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path homeDir = agent.root().resolve("home");
        createDir(homeDir);

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.HOME);

        assertThat(result.present()).isFalse();
        assertThat(result.hasContent()).isFalse();
    }

    @Test
    void absentPersonalRootReturnsAbsent() {
        WorkspaceDescriptor agent = agentDescriptor();
        // Root directory does not exist

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.HOME);

        assertThat(result.present()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Non-empty and empty guidance
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void nonEmptyGuidanceFromMaterialization() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        writeWorkspaceMd(location.confinedPath(), "# Project Guidance\n\nDo the thing.");

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isTrue();
        assertThat(result.hasContent()).isTrue();
        assertThat(result.content()).contains("# Project Guidance");
        assertThat(result.content()).contains("Do the thing.");
        assertThat(result.provenanceLabel()).contains("materialization " + key.serialized());
        assertThat(result.provenanceLabel()).contains("generation 1");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void emptyFileGuidanceReturnsPresentWithEmptyContent() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        writeWorkspaceMd(location.confinedPath(), "");

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isTrue();
        assertThat(result.hasContent()).isFalse(); // empty text
        assertThat(result.content()).isEqualTo("");
    }

    @Test
    void personalAreaGuidanceWithCorrectProvenance() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path homeDir = agent.root().resolve("home");
        writeWorkspaceMd(homeDir, "Home area guidance");

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.HOME);

        assertThat(result.present()).isTrue();
        assertThat(result.content()).isEqualTo("Home area guidance");
        assertThat(result.provenanceLabel()).contains("personal area home");
    }

    @Test
    void scriptsAreaProducesCorrectProvenance() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path scriptsDir = agent.root().resolve("scripts");
        writeWorkspaceMd(scriptsDir, "Scripts guidance");

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.SCRIPTS);

        assertThat(result.present()).isTrue();
        assertThat(result.provenanceLabel()).contains("personal area scripts");
    }

    @Test
    void appsAreaProducesCorrectProvenance() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path appsDir = agent.root().resolve("apps");
        writeWorkspaceMd(appsDir, "Apps guidance");

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.APPS);

        assertThat(result.present()).isTrue();
        assertThat(result.provenanceLabel()).contains("personal area apps");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Truncation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void contentTruncatedAtMaxPromptChars() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);

        // 10,000 chars (above 8192 max)
        String largeContent = "A".repeat(10000);
        writeWorkspaceMd(location.confinedPath(), largeContent);

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isTrue();
        assertThat(result.truncated()).isTrue();
        assertThat(result.content()).hasSize(8192);
        assertThat(result.content()).isEqualTo("A".repeat(8192));
        assertThat(result.hasContent()).isTrue();
    }

    @Test
    void contentExactlyAtLimitNotTruncated() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);

        String exactContent = "B".repeat(8192);
        writeWorkspaceMd(location.confinedPath(), exactContent);

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(result.content()).hasSize(8192);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Hard byte-limit rejection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void fileExceedingMaxBytesIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        // 65,537 bytes (exceeds 64KB limit)
        byte[] largeBytes = new byte[65537];
        for (int i = 0; i < largeBytes.length; i++) {
            largeBytes[i] = (byte) 'X';
        }
        Files.write(location.confinedPath().resolve("WORKSPACE.md"), largeBytes);

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("exceeds maximum size")
                .hasMessageContaining("65536");
    }

    @Test
    void fileExactlyAtMaxBytesIsAccepted() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        byte[] exactBytes = new byte[65536];
        for (int i = 0; i < exactBytes.length; i++) {
            exactBytes[i] = (byte) 'Y';
        }
        Files.write(location.confinedPath().resolve("WORKSPACE.md"), exactBytes);

        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.present()).isTrue();
        assertThat(result.truncated()).isTrue(); // 65536 chars > 8192 prompt limit
        assertThat(result.content()).hasSize(8192);
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTF-8 validation failure
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void invalidUtf8BytesAreTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        // Write bytes that are invalid UTF-8 (0xFF is never valid in UTF-8)
        byte[] invalidUtf8 = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
        Files.write(location.confinedPath().resolve("WORKSPACE.md"), invalidUtf8);

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("not valid UTF-8");
    }

    @Test
    void invalidUtf8FromPersonalAreaIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path homeDir = agent.root().resolve("home");
        createDir(homeDir);

        byte[] invalidUtf8 = new byte[]{(byte) 0xC0, (byte) 0x80}; // overlong encoding, invalid
        Files.write(homeDir.resolve("WORKSPACE.md"), invalidUtf8);

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.HOME))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("not valid UTF-8");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Null-byte rejection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void contentWithNullByteIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        // Valid UTF-8 that contains embedded NUL
        byte[] withNull = "Hello\u0000World".getBytes(StandardCharsets.UTF_8);
        Files.write(location.confinedPath().resolve("WORKSPACE.md"), withNull);

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("null bytes");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Link rejection at root
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void symlinkedSearchRootIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);

        // Create the actual directory and a symlink pointing to it
        Path realDir = Files.createDirectories(tempDir.resolve("real-project"));
        Path linkPath = location.confinedPath();
        Files.createDirectories(linkPath.getParent());
        try {
            Files.createSymbolicLink(linkPath, realDir);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "symlink support unavailable");
        }

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("symbolic link");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Link rejection at file
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void symlinkedWorkspaceMdIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        Path realFile = tempDir.resolve("real-workspace.md");
        Files.writeString(realFile, "real content");
        Path linkPath = location.confinedPath().resolve("WORKSPACE.md");
        try {
            Files.createSymbolicLink(linkPath, realFile);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "symlink support unavailable");
        }

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("symbolic link");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Link rejection at intermediate component
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void symlinkedIntermediateComponentIsTerminal() throws Exception {
        // Create a scenario where a parent directory in the search root path is
        // a symlink. The rejectLinkedComponents walk should catch this.
        WorkspaceDescriptor agent = agentDescriptor();
        Files.createDirectories(agent.root());

        // Create an external target and symlink the 'projects' dir to it
        Path outsideTarget = Files.createDirectories(tempDir.resolve("outside-target"));
        Path projectsLink = agent.root().resolve("projects");
        try {
            Files.createSymbolicLink(projectsLink, outsideTarget);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "symlink support unavailable");
        }

        // Build a location whose confined path is under the symlinked projects/
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        Path confined = agent.root().resolve("projects/" + key.serialized());
        MaterializationLocation location = new MaterializationLocation(agent, key, 1,
                "workspace/agent/agent-1/projects/" + key.serialized() + "/",
                confined.toAbsolutePath().normalize());
        createDir(location.confinedPath());

        // The 'projects' component is a symlink — rejectLinkedComponents must catch it
        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void symlinkedAuthorityBoundaryIsTerminal() throws Exception {
        // If the agent root itself is a symlink, rejectLinkedComponents
        // must catch it on the first iteration.
        Path realAgentRoot = Files.createDirectories(tempDir.resolve("real-agent-root"));
        Path linkAgentRoot = tempDir.resolve("workspace/agent/agent-link");
        Files.createDirectories(linkAgentRoot.getParent());
        try {
            Files.createSymbolicLink(linkAgentRoot, realAgentRoot);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "symlink support unavailable");
        }

        WorkspaceDescriptor agent = new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-link", linkAgentRoot);
        MaterializationKey key = MaterializationKey.forProject("agent-link", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());
        writeWorkspaceMd(location.confinedPath(), "content");

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("symbolic link");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Candidate/realpath escape
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void realpathEscapeIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        // Create WORKSPACE.md as a symlink escaping outside
        Path outsideFile = tempDir.resolve("outside-content.md");
        Files.writeString(outsideFile, "outside");
        Path mdLink = location.confinedPath().resolve("WORKSPACE.md");
        try {
            Files.createSymbolicLink(mdLink, outsideFile);
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "symlink support unavailable");
        }

        // The symlink check on workspaceMd catches this before realpath
        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void realpathFailureIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);

        Path searchRoot = location.confinedPath();
        createDir(searchRoot);
        Path mdPath = searchRoot.resolve("WORKSPACE.md");
        Files.writeString(mdPath, "content");

        // Remove execute permission on the search root directory to make
        // path resolution fail. On Linux this prevents stat-ing children,
        // causing Files.exists(workspaceMd) to fail indeterminately.
        try {
            Files.setPosixFilePermissions(searchRoot, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions not supported");
        }

        try {
            WorkspaceMdResolver.resolveFromMaterialization(location);
            // If we reach here, the OS allowed the operation (e.g. running as root).
            assumeTrue(false, "I/O check succeeded despite no permissions (likely running as root)");
        } catch (WorkspaceMdResolver.WorkspaceMdException e) {
            // The terminal failure may report the realpath or the exists check
            // failing first — both are valid I/O-failure outcomes.
            assertThat(e.getMessage())
                    .containsAnyOf("real path", "Failed to determine", "Failed to read");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Non-regular file
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void directoryNamedWorkspaceMdIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        // Create a directory named WORKSPACE.md
        Files.createDirectories(location.confinedPath().resolve("WORKSPACE.md"));

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("not a regular file");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Read / I/O failure
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void unreadableFileIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        createDir(location.confinedPath());

        Path mdPath = location.confinedPath().resolve("WORKSPACE.md");
        Files.writeString(mdPath, "secret content");

        try {
            Files.setPosixFilePermissions(mdPath, PosixFilePermissions.fromString("-w-------"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions not supported");
        }

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("Failed to read");
    }

    // ═══════════════════════════════════════════════════════════════════
    // No alternate-root consultation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void failureDoesNotConsultAlternateRoot() {
        // This test verifies that after a terminal failure on one resolution path,
        // no alternate personal or materialization root is consulted.
        // Since WorkspaceMdResolver has static methods, each call is independent.
        // The key invariant is that resolveFromMaterialization fails terminal and
        // does not internally fall back to resolveFromPersonalArea or resolveFromRoot.

        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 1);
        // Don't create the directory, but we also need to test that invalid UTF-8
        // on materialization doesn't cause it to try personal areas.

        // Create root dir but with invalid UTF-8
        try {
            createDir(location.confinedPath());
            byte[] invalidUtf8 = new byte[]{(byte) 0xFF, (byte) 0xFE};
            Files.write(location.confinedPath().resolve("WORKSPACE.md"), invalidUtf8);

            assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(location))
                    .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                    .hasMessageContaining("not valid UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Personal area validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void personalAreaRequiresAgentDescriptor() {
        WorkspaceDescriptor user = new WorkspaceDescriptor(WorkspaceKind.USER, "alice",
                tempDir.resolve("workspace/user/alice"));

        assertThatThrownBy(() ->
                WorkspaceMdResolver.resolveFromPersonalArea(user, PersonalSelection.HOME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENT descriptor");
    }

    @Test
    void personalAreaRejectsChatDescriptor() {
        WorkspaceDescriptor chat = new WorkspaceDescriptor(WorkspaceKind.CHAT, "chat-1",
                tempDir.resolve("workspace/chat/chat-1"));

        assertThatThrownBy(() ->
                WorkspaceMdResolver.resolveFromPersonalArea(chat, PersonalSelection.HOME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENT descriptor");
    }

    @Test
    void materializationRequiresNonNullLocation() {
        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromMaterialization(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void personalAreaRequiresNonNullDescriptor() {
        assertThatThrownBy(() ->
                WorkspaceMdResolver.resolveFromPersonalArea(null, PersonalSelection.HOME))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void personalAreaRequiresNonNullSelection() {
        assertThatThrownBy(() ->
                WorkspaceMdResolver.resolveFromPersonalArea(agentDescriptor(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ═══════════════════════════════════════════════════════════════════
    // result type assertions
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void absentResultHasNoContentOrLabel() {
        WorkspaceMdResolver.WorkspaceMdResult result = WorkspaceMdResolver.WorkspaceMdResult.absent();

        assertThat(result.present()).isFalse();
        assertThat(result.hasContent()).isFalse();
        assertThat(result.content()).isNull();
        assertThat(result.provenanceLabel()).isNull();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void presentResultRejectsNullContent() {
        assertThatThrownBy(() -> WorkspaceMdResolver.WorkspaceMdResult.present(null, "label", false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void presentResultRejectsNullLabel() {
        assertThatThrownBy(() -> WorkspaceMdResolver.WorkspaceMdResult.present("content", null, false))
                .isInstanceOf(NullPointerException.class);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Whitespace-only content
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void whitespaceOnlyContentIsPresentButHasNoTextContent() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path homeDir = agent.root().resolve("home");
        writeWorkspaceMd(homeDir, "   \n  \n  ");

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.HOME);

        assertThat(result.present()).isTrue();
        // hasContent uses StringUtils.hasText which rejects whitespace-only
        assertThat(result.hasContent()).isFalse();
        assertThat(result.content()).isEqualTo("   \n  \n  ");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Max byte boundary for personal area
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void personalAreaFileExceedingMaxBytesIsTerminal() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path homeDir = agent.root().resolve("home");
        createDir(homeDir);

        byte[] largeBytes = new byte[65537];
        for (int i = 0; i < largeBytes.length; i++) {
            largeBytes[i] = (byte) 'Z';
        }
        Files.write(homeDir.resolve("WORKSPACE.md"), largeBytes);

        assertThatThrownBy(() -> WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.HOME))
                .isInstanceOf(WorkspaceMdResolver.WorkspaceMdException.class)
                .hasMessageContaining("exceeds maximum size");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Provenance label completeness
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void materializationProvenanceLabelIncludesGeneration() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 7);
        writeWorkspaceMd(location.confinedPath(), "guided content");

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromMaterialization(location);

        assertThat(result.provenanceLabel()).contains("generation 7");
        assertThat(result.provenanceLabel()).contains("materialization " + key.serialized());
    }

    @Test
    void personalAreaProvenanceLabelExcludesGeneration() throws Exception {
        WorkspaceDescriptor agent = agentDescriptor();
        Path appsDir = agent.root().resolve("apps");
        writeWorkspaceMd(appsDir, "apps guidance");

        WorkspaceMdResolver.WorkspaceMdResult result =
                WorkspaceMdResolver.resolveFromPersonalArea(agent, PersonalSelection.APPS);

        assertThat(result.provenanceLabel()).doesNotContain("generation");
        assertThat(result.provenanceLabel()).contains("personal area apps");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Exception type
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void workspaceMdExceptionIsRuntime() {
        WorkspaceMdResolver.WorkspaceMdException ex =
                new WorkspaceMdResolver.WorkspaceMdException("test message");

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("test message");
    }

    @Test
    void workspaceMdExceptionWithCause() {
        IOException cause = new IOException("disk full");
        WorkspaceMdResolver.WorkspaceMdException ex =
                new WorkspaceMdResolver.WorkspaceMdException("wrapped", cause);

        assertThat(ex.getCause()).isEqualTo(cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
    }
}
