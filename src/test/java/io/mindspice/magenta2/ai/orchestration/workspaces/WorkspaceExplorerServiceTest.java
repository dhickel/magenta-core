package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceExplorerServiceTest {
    @TempDir
    Path tempDir;

    private WorkspaceExplorerService explorer() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        WorkspaceFileActionLogRepository actionLogRepo = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceFileMetadataRepository metadataRepo = new WorkspaceFileMetadataRepository(jdbc);
        WorkspaceFileMetadataService metadataService = new WorkspaceFileMetadataService(metadataRepo, actionLogRepo);
        return new WorkspaceExplorerService(metadataService, actionLogRepo);
    }

    @Test
    void descriptorBoundaryReadsMissingAgentRootAsEmptyAndCreatesLazily() {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", tempDir.resolve("agent-1"));

        // Missing agent root: synthetic areas only (.skills is hidden)
        var listing = explorer.list(descriptor, path("."));
        assertThat(listing.entries()).hasSize(7);
        assertThat(listing.entries().stream().allMatch(e -> e.directory())).isTrue();
        assertThat(descriptor.root()).doesNotExist();

        // Create within personal area home/
        explorer.createDirectory(descriptor, path("home/notes"));
        explorer.createMarkdownFile(descriptor, path("home/notes"), "readme.md");
        explorer.saveText(descriptor, path("home/notes/readme.md"), "# Hello\n");

        assertThat(descriptor.root()).isDirectory();
        assertThat(explorer.preview(descriptor, path("home/notes/readme.md")).content()).isEqualTo("# Hello\n");
        assertThat(explorer.inspect(descriptor, path("home/notes/readme.md")).viewerKind())
            .isEqualTo(WorkspaceExplorerService.ViewerKind.MARKDOWN);
    }

    @Test
    void fullOperationBoundaryWorksForAgentChatAndDeclaredUserDescriptors() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        for (WorkspaceKind kind : WorkspaceKind.values()) {
            Path root = tempDir.resolve(kind.token());
            if (kind == WorkspaceKind.USER) {
                Files.createDirectories(root);
            }
            WorkspaceDescriptor descriptor = descriptor(kind, kind.token() + "-1", root);

            // AGENT: create within personal area home/
            String base = kind == WorkspaceKind.AGENT ? "home" : "";
            String notesRel = mkRel(base, "notes");
            String archiveRel = mkRel(base, "archive");

            explorer.createDirectory(descriptor, path(notesRel));
            explorer.createTextFile(descriptor, path(notesRel), "a.txt");
            explorer.saveText(descriptor, path(notesRel + "/a.txt"), kind.token());
            explorer.createDirectory(descriptor, path(archiveRel));
            explorer.copy(descriptor, path(notesRel + "/a.txt"), path(archiveRel), null);
            explorer.rename(descriptor, path(archiveRel + "/a.txt"), "b.txt");
            explorer.move(descriptor, path(archiveRel + "/b.txt"), path(notesRel), "b.txt");

            assertThat(explorer.list(descriptor, path(notesRel)).entries()).hasSize(2);
            assertThat(explorer.inspect(descriptor, path(notesRel + "/b.txt")).regularFile()).isTrue();
            assertThat(explorer.preview(descriptor, path(notesRel + "/b.txt")).content()).isEqualTo(kind.token());
            assertThat(explorer.download(descriptor, path(notesRel + "/b.txt")).bytes())
                .isEqualTo(kind.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            explorer.delete(descriptor, path(notesRel + "/b.txt"), WorkspaceExplorerService.DeleteStep.FILE_CONFIRM);
            assertThat(explorer.recentActions(descriptor, 20)).isNotEmpty();
        }
    }

    @Test
    void missingUserRootIsNotImplicitlyCreated() {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.USER, "declared", tempDir.resolve("declared"));

        assertThatThrownBy(() -> explorer.createTextFile(descriptor, path("."), "note.txt"))
            .isInstanceOf(WorkspaceFileException.class)
            .hasMessageContaining("does not exist");
        assertThat(descriptor.root()).doesNotExist();
    }

    @Test
    void rejectsTraversalRootAndReservedOutputsMutationsButAllowsOutputsReads() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor missing = descriptor(WorkspaceKind.AGENT, "missing", tempDir.resolve("missing"));
        assertThatThrownBy(() -> explorer.createDirectory(missing, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("outputs");
        // Non-existent intermediate parent under personal area is denied
        assertThatThrownBy(() -> explorer.createDirectory(missing, path("home/nonexistent/child")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("parent");
        assertThatThrownBy(() -> explorer.createTextFile(missing, path("."), "invalid.md"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining(".txt");
        // Root may have been lazily provisioned by earlier create attempts;
        // this is acceptable behavior — the failed mutations didn't corrupt state.

        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Files.createDirectories(descriptor.root().resolve("outputs"));
        Files.writeString(descriptor.root().resolve("outputs/result.txt"), "result");

        assertThat(explorer.preview(descriptor, path("outputs/result.txt")).content()).isEqualTo("result");
        assertThatThrownBy(() -> explorer.normalizeRelativePath("../outside"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("escapes workspace");
        assertThatThrownBy(() -> explorer.createDirectory(descriptor, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.saveText(descriptor, path("outputs/result.txt"), "changed"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.delete(descriptor, path("."), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");
        assertThat(Files.readString(descriptor.root().resolve("outputs/result.txt"))).isEqualTo("result");
    }

    @Test
    void agentReservedAndUnknownTreesRemainReadableButCannotBeDeleted() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1",
            Files.createDirectories(tempDir.resolve("agent-1")));
        for (String path : List.of("projects/p-1", "repos/r-1", "legacy/content")) {
            Path target = descriptor.root().resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, path);
            assertThat(explorer.preview(descriptor, this.path(path)).content()).isEqualTo(path);
            assertThatThrownBy(() -> explorer.delete(descriptor, this.path(path),
                WorkspaceExplorerService.DeleteStep.FILE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");
            assertThat(target).exists();
        }
    }

    @Test
    void rejectsSymlinkComponentsAndSymbolicRecursiveTrees() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path homeDir = Files.createDirectories(descriptor.root().resolve("home"));
        Path tree = Files.createDirectories(homeDir.resolve("tree"));
        try {
            Files.createSymbolicLink(tree.resolve("escape"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> explorer.list(descriptor, path("home/tree/escape")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("home/tree"), path("home"), "tree-copy"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("symbolic links");
        assertThat(homeDir.resolve("tree-copy")).doesNotExist();
    }

    @Test
    void enforcesPreviewEditClassificationAndDownloadLimits() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        // Files at root are unknown children; browsable but not writable
        Path markdown = descriptor.root().resolve("large.md");
        try (var file = new java.io.RandomAccessFile(markdown.toFile(), "rw")) {
            file.setLength(6L * 1024 * 1024);
        }
        WorkspaceExplorerService.FilePreview warning = explorer.preview(descriptor, path("large.md"));
        assertThat(warning.requiresWarning()).isTrue();
        assertThat(warning.content()).isNull();

        Path download = descriptor.root().resolve("download.txt");
        try (var file = new java.io.RandomAccessFile(download.toFile(), "rw")) {
            file.setLength(11L * 1024 * 1024);
        }
        assertThatThrownBy(() -> explorer.download(descriptor, path("download.txt")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("too large");

        Path edit = descriptor.root().resolve("edit.txt");
        try (var file = new java.io.RandomAccessFile(edit.toFile(), "rw")) {
            file.setLength(26L * 1024 * 1024);
        }
        assertThat(explorer.preview(descriptor, path("edit.txt")).kind()).isEqualTo("too_large");
    }

    @Test
    void handlesCollisionsDescendantsAndRecursiveDeleteConfirmation() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        // Create within personal area home/
        explorer.createDirectory(descriptor, path("home/parent"));
        explorer.createDirectory(descriptor, path("home/parent/child"));
        explorer.createTextFile(descriptor, path("home/parent"), "a.txt");
        explorer.createTextFile(descriptor, path("home/parent"), "b.txt");

        assertThatThrownBy(() -> explorer.rename(descriptor, path("home/parent/a.txt"), "b.txt"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> explorer.move(descriptor, path("home/parent"), path("home/parent/child"), null))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("descendant");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("."), path("home/parent"), "root-copy"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("root");

        WorkspaceExplorerService.DeletePreflight preflight = explorer.deletePreflight(
            descriptor, path("home/parent"), WorkspaceExplorerService.DeleteStep.INTENT);
        assertThat(preflight.executable()).isFalse();
        assertThat(preflight.requiredStep()).isEqualTo(WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.delete(descriptor, path("home/parent"), WorkspaceExplorerService.DeleteStep.INTENT))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("confirmation");
        assertThat(explorer.delete(descriptor, path("home/parent"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM).deletedCount())
            .isEqualTo(4);
    }

    @Test
    void labelsTagsAndActionsUseCanonicalIdentity() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createTextFile(descriptor, path("home"), "note.txt");

        WorkspaceFileLabel tag = explorer.ensureTag("test-tag", "Test Tag");
        assertThat(tag.slug()).isEqualTo("test-tag");

        explorer.addLabel(descriptor, "home/note.txt", "test-tag");
        assertThat(explorer.labels(descriptor, "home/note.txt")).hasSize(1);
        assertThat(explorer.labels(descriptor, "home/note.txt").getFirst().label().slug()).isEqualTo("test-tag");

        assertThat(explorer.recentActions(descriptor, 10)).isNotEmpty();
        assertThat(explorer.recentActions(descriptor, 10).getFirst().workspaceIdentity())
            .isEqualTo(descriptor.identity());
    }

    @Test
    void metadataFollowsMoveAndCopy() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createDirectory(descriptor, path("home/src"));
        explorer.createTextFile(descriptor, path("home/src"), "a.txt");
        explorer.addLabel(descriptor, "home/src/a.txt", "note");

        explorer.rename(descriptor, path("home/src/a.txt"), "b.txt");
        assertThat(explorer.labels(descriptor, "home/src/b.txt")).hasSize(1);

        explorer.createDirectory(descriptor, path("home/dst"));
        explorer.copy(descriptor, path("home/src/b.txt"), path("home/dst"), null);
        assertThat(explorer.labels(descriptor, "home/dst/b.txt")).hasSize(1);
        assertThat(explorer.labels(descriptor, "home/dst/b.txt").getFirst().label().slug()).isEqualTo("note");

        explorer.delete(descriptor, path("home/dst"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.labels(descriptor, "home/dst/b.txt"))
            .isInstanceOf(WorkspaceFileException.class)
            .hasMessageContaining("does not exist");
    }

    private static String mkRel(String base, String name) {
        return base.isEmpty() ? name : base + "/" + name;
    }

    @Test
    void materializationReadsAreConfinedToTrustedLocationAndNeverExposeAgentSiblings() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path agentRoot = tempDir.resolve("agent-materialized");
        Path materializationRoot = agentRoot.resolve("projects/materialized-key");
        Files.createDirectories(materializationRoot);
        Files.writeString(materializationRoot.resolve("brief.txt"), "project only");
        Files.writeString(agentRoot.resolve("private.txt"), "agent private");

        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", agentRoot);
        var key = io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey
            .forProject("agent-1", "project-1");
        MaterializationLocation location = new MaterializationLocation(
            descriptor, key, 3, "workspace/agent/agent-1/projects/materialized-key", materializationRoot);

        assertThat(explorer.list(location, path(".")).entries())
            .extracting(WorkspaceExplorerService.Entry::name)
            .containsExactly("brief.txt");
        assertThat(explorer.preview(location, path("brief.txt")).content()).isEqualTo("project only");
        assertThat(explorer.inspect(location, path("brief.txt")).name()).isEqualTo("brief.txt");
        assertThatThrownBy(() -> explorer.list(location, explorer.normalizeRelativePath("../")))
            .isInstanceOf(WorkspaceFileException.class);
        assertThat(agentRoot.resolve("private.txt")).exists();
    }

    private WorkspaceExplorerService.RelativePath path(String value) {
        return new WorkspaceExplorerService.RelativePath(value != null && !value.isBlank() ? value : ".");
    }

    private WorkspaceDescriptor descriptor(WorkspaceKind kind, String key, Path root) {
        return new WorkspaceDescriptor(kind, key, root.toAbsolutePath().normalize());
    }

    // ── Reused-thread cleanup regressions ──

    @Test
    void reusedThreadCleansUpAfterSuccess() throws Exception {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reuse-success");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = executor.submit(() -> {
                WorkspaceExplorerService explorer = explorer();
                Path root = Files.createDirectories(tempDir.resolve("agent-success"));
                WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-s", root);

                // AGENT descriptor mutation — must succeed
                explorer.createDirectory(descriptor, path("home/notes"));
                assertThat(Files.isDirectory(root.resolve("home/notes"))).isTrue();

                // Root-bridge mutation in an unknown-child area.
                // Under AGENT classification this would be denied;
                // kind-agnostic classification must allow it, proving
                // no stale AGENT kind leaked.
                Path bridgeRoot = Files.createDirectories(tempDir.resolve("bridge-success"));
                Files.createDirectories(bridgeRoot.resolve("misc"));
                explorer.createDirectory(bridgeRoot, path("misc/sub"));
                assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();

                return null;
            });
            future.get(10, TimeUnit.SECONDS);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void reusedThreadCleansUpAfterValidationRejection() throws Exception {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reuse-validation");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = executor.submit(() -> {
                WorkspaceExplorerService explorer = explorer();
                Path root = Files.createDirectories(tempDir.resolve("agent-reject"));
                Files.createDirectories(root.resolve("outputs"));
                WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-r", root);

                // AGENT descriptor mutation on reserved outputs — must be rejected
                assertThatThrownBy(() ->
                    explorer.createDirectory(descriptor, path("outputs/new")))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");

                // Root-bridge mutation must succeed on the same worker;
                // stale AGENT kind would deny unknown-child areas.
                Path bridgeRoot = Files.createDirectories(tempDir.resolve("bridge-reject"));
                Files.createDirectories(bridgeRoot.resolve("misc"));
                explorer.createDirectory(bridgeRoot, path("misc/sub"));
                assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();

                return null;
            });
            future.get(10, TimeUnit.SECONDS);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void reusedThreadCleansUpAfterPrimitiveFailure() throws Exception {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reuse-primitive");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = executor.submit(() -> {
                // ConfinedPathMutationService whose hook throws once on CREATE_DIRECTORY
                AtomicBoolean thrown = new AtomicBoolean(false);
                ConfinedPathMutationService throwingMutations = new ConfinedPathMutationService(
                    (primitive, root, rel) -> {
                        if (primitive == ConfinedPathMutationService.Primitive.CREATE_DIRECTORY
                                && thrown.compareAndSet(false, true)) {
                            throw new IOException("injected primitive failure");
                        }
                    }
                );

                JdbcTemplate jdbc = new JdbcTemplate(
                    new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
                var actionLogRepo = new WorkspaceFileActionLogRepository(jdbc);
                var metadataRepo = new WorkspaceFileMetadataRepository(jdbc);
                var metadataService = new WorkspaceFileMetadataService(metadataRepo, actionLogRepo);
                WorkspaceExplorerService explorer = new WorkspaceExplorerService(
                    metadataService, actionLogRepo, null, throwingMutations);

                Path root = Files.createDirectories(tempDir.resolve("agent-primfail"));
                WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-pf", root);

                // AGENT descriptor mutation — primitive hook throws after wrapper entry
                assertThatThrownBy(() -> explorer.createDirectory(descriptor, path("home/notes")))
                    .isInstanceOf(IllegalStateException.class);

                // Root-bridge must still operate under kind-agnostic policy
                Path bridgeRoot = Files.createDirectories(tempDir.resolve("bridge-primfail"));
                Files.createDirectories(bridgeRoot.resolve("misc"));
                explorer.createDirectory(bridgeRoot, path("misc/sub"));
                assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();

                return null;
            });
            future.get(10, TimeUnit.SECONDS);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void reusedThreadCleansUpAfterMetadataFailure() throws Exception {
        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reuse-metadata");
            t.setDaemon(true);
            return t;
        });
        try {
            var future = executor.submit(() -> {
                JdbcTemplate jdbc = new JdbcTemplate(
                    new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
                var actionLogRepo = new WorkspaceFileActionLogRepository(jdbc);
                var metadataRepo = new WorkspaceFileMetadataRepository(jdbc);

                // Metadata service that throws on onMove to simulate
                // post-primitive metadata failure
                var failingMetadata = new WorkspaceFileMetadataService(metadataRepo, actionLogRepo) {
                    @Override
                    public void onMove(WorkspaceDescriptor d, String s, String t) {
                        throw new RuntimeException("injected metadata failure");
                    }
                };

                WorkspaceExplorerService explorer = new WorkspaceExplorerService(
                    failingMetadata, actionLogRepo);

                Path root = Files.createDirectories(tempDir.resolve("agent-metafail"));
                Files.createDirectories(root.resolve("home"));
                Files.writeString(root.resolve("home/a.txt"), "hello");
                WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-mf", root);

                // Rename succeeds on filesystem but fails on metadata
                assertThatThrownBy(() -> explorer.rename(descriptor, path("home/a.txt"), "b.txt"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("injected metadata failure");

                // Root-bridge must still operate under kind-agnostic policy
                Path bridgeRoot = Files.createDirectories(tempDir.resolve("bridge-metafail"));
                Files.createDirectories(bridgeRoot.resolve("misc"));
                explorer.createDirectory(bridgeRoot, path("misc/sub"));
                assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();

                return null;
            });
            future.get(10, TimeUnit.SECONDS);
        } finally {
            shutdownExecutor(executor);
        }
    }

    // ── Adversarial cross-kind isolation ──

    @Test
    void agentSuccessThenChatSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        assertThat(Files.isDirectory(agentRoot.resolve("home/notes"))).isTrue();

        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk", chatRoot);
        explorer.createDirectory(chatDesc, path("chat-dir"));
        assertThat(Files.isDirectory(chatRoot.resolve("chat-dir"))).isTrue();

        // Bridge canary: unknown-child path that AGENT would deny
        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-1"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void chatSuccessThenAgentSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat2"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk2", chatRoot);
        explorer.createDirectory(chatDesc, path("chat-dir"));
        assertThat(Files.isDirectory(chatRoot.resolve("chat-dir"))).isTrue();

        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent2"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk2", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        assertThat(Files.isDirectory(agentRoot.resolve("home/notes"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-2"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void agentSuccessThenUserSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent3"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk3", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        assertThat(Files.isDirectory(agentRoot.resolve("home/notes"))).isTrue();

        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user3"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk3", userRoot);
        explorer.createDirectory(userDesc, path("user-dir"));
        assertThat(Files.isDirectory(userRoot.resolve("user-dir"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-3"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void userSuccessThenAgentSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user4"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk4", userRoot);
        explorer.createDirectory(userDesc, path("user-dir"));
        assertThat(Files.isDirectory(userRoot.resolve("user-dir"))).isTrue();

        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent4"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk4", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        assertThat(Files.isDirectory(agentRoot.resolve("home/notes"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-4"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void chatSuccessThenUserSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat5"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk5", chatRoot);
        explorer.createDirectory(chatDesc, path("chat-dir"));
        assertThat(Files.isDirectory(chatRoot.resolve("chat-dir"))).isTrue();

        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user5"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk5", userRoot);
        explorer.createDirectory(userDesc, path("user-dir"));
        assertThat(Files.isDirectory(userRoot.resolve("user-dir"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-5"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void userSuccessThenChatSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user6"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk6", userRoot);
        explorer.createDirectory(userDesc, path("user-dir"));
        assertThat(Files.isDirectory(userRoot.resolve("user-dir"))).isTrue();

        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat6"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk6", chatRoot);
        explorer.createDirectory(chatDesc, path("chat-dir"));
        assertThat(Files.isDirectory(chatRoot.resolve("chat-dir"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-6"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void agentRejectionThenChatSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent7"));
        Files.createDirectories(agentRoot.resolve("outputs"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk7", agentRoot);
        assertThatThrownBy(() -> explorer.createDirectory(agentDesc, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");

        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat7"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk7", chatRoot);
        explorer.createDirectory(chatDesc, path("chat-dir"));
        assertThat(Files.isDirectory(chatRoot.resolve("chat-dir"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-7"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void chatRejectionThenAgentSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat8"));
        Files.createDirectories(chatRoot.resolve("outputs"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk8", chatRoot);
        assertThatThrownBy(() -> explorer.createDirectory(chatDesc, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");

        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent8"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk8", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        assertThat(Files.isDirectory(agentRoot.resolve("home/notes"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-8"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void agentRejectionThenUserSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent9"));
        Files.createDirectories(agentRoot.resolve("outputs"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk9", agentRoot);
        assertThatThrownBy(() -> explorer.createDirectory(agentDesc, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");

        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user9"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk9", userRoot);
        explorer.createDirectory(userDesc, path("user-dir"));
        assertThat(Files.isDirectory(userRoot.resolve("user-dir"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-9"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void userRejectionThenAgentSuccessBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user10"));
        Files.createDirectories(userRoot.resolve("outputs"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk10", userRoot);
        assertThatThrownBy(() -> explorer.createDirectory(userDesc, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");

        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent10"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk10", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        assertThat(Files.isDirectory(agentRoot.resolve("home/notes"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-10"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void agentChatUserInterleavedSameThreadBridgeCanaryClean() throws Exception {
        WorkspaceExplorerService explorer = explorer();

        Path agentRoot = Files.createDirectories(tempDir.resolve("xkind-agent11"));
        WorkspaceDescriptor agentDesc = descriptor(WorkspaceKind.AGENT, "agent-xk11", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));

        Path chatRoot = Files.createDirectories(tempDir.resolve("xkind-chat11"));
        WorkspaceDescriptor chatDesc = descriptor(WorkspaceKind.CHAT, "chat-xk11", chatRoot);
        explorer.createDirectory(chatDesc, path("chat-dir"));

        Path userRoot = Files.createDirectories(tempDir.resolve("xkind-user11"));
        WorkspaceDescriptor userDesc = descriptor(WorkspaceKind.USER, "user-xk11", userRoot);
        explorer.createDirectory(userDesc, path("user-dir"));

        // Second round in different order
        explorer.createTextFile(agentDesc, path("home/notes"), "a.txt");
        explorer.createTextFile(chatDesc, path("chat-dir"), "b.txt");
        explorer.createTextFile(userDesc, path("user-dir"), "c.txt");

        assertThat(Files.isRegularFile(agentRoot.resolve("home/notes/a.txt"))).isTrue();
        assertThat(Files.isRegularFile(chatRoot.resolve("chat-dir/b.txt"))).isTrue();
        assertThat(Files.isRegularFile(userRoot.resolve("user-dir/c.txt"))).isTrue();

        Path bridgeRoot = Files.createDirectories(tempDir.resolve("xkind-bridge-11"));
        Files.createDirectories(bridgeRoot.resolve("misc"));
        explorer.createDirectory(bridgeRoot, path("misc/sub"));
        assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();
    }

    @Test
    void concurrentCrossKindThreadsIsolated() throws Exception {
        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "xkind-concurrent");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                int idx = i;
                futures.add(executor.submit(() -> {
                    WorkspaceExplorerService explorer = explorer();
                    WorkspaceKind kind = WorkspaceKind.values()[idx % WorkspaceKind.values().length];
                    Path root;
                    try {
                        root = Files.createDirectories(tempDir.resolve("xkind-conc-" + idx));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    WorkspaceDescriptor desc = descriptor(kind, kind.token() + "-conc-" + idx, root);

                    String base = kind == WorkspaceKind.AGENT ? "home" : "";
                    String dirRel = base.isEmpty() ? "dir-" + idx : base + "/dir-" + idx;
                    explorer.createDirectory(desc, path(dirRel));

                    // Bridge canary on same thread
                    Path bridgeRoot;
                    try {
                        bridgeRoot = Files.createDirectories(
                            tempDir.resolve("xkind-bridge-conc-" + idx));
                        Files.createDirectories(bridgeRoot.resolve("misc"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    explorer.createDirectory(bridgeRoot, path("misc/sub"));
                    assertThat(Files.isDirectory(bridgeRoot.resolve("misc/sub"))).isTrue();

                    return null;
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            shutdownExecutor(executor);
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while shutting down test executor", exception);
        }
    }
}
