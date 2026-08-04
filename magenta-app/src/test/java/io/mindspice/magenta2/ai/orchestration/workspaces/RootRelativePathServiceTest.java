package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RootRelativePathServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void storePersistsDataRootChildAsSlashSeparatedRelativePath() throws Exception {
        TestContext context = context();
        Path file = context.dataRoot().resolve("agents/a/workspace/outputs/report.txt");

        String stored = context.service().store(file);

        assertThat(stored).isEqualTo("agents/a/workspace/outputs/report.txt");
        assertThat(stored).doesNotContain(context.dataRoot().toString());
        assertThat(context.service().resolve(stored))
            .isEqualTo(context.dataRoot().resolve("agents/a/workspace/outputs/report.txt"));
    }

    @Test
    void storeNormalizesRedundantPathSegments() throws Exception {
        TestContext context = context();
        Path path = context.dataRoot().resolve("agents/a/workspace/../workspace/out.txt");

        assertThat(context.service().store(path)).isEqualTo("agents/a/workspace/out.txt");
    }

    @Test
    void storeRejectsOutsideRootPaths() throws Exception {
        TestContext context = context();
        Path outside = tempDir.resolve("outside.txt");

        assertThatThrownBy(() -> context.service().store(outside))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void resolveMapsRelativeStoredPathWithoutRequiringExistence() throws Exception {
        TestContext context = context();

        Path resolved = context.service().resolve("runtime/task-runs/run-1");

        assertThat(resolved).isEqualTo(context.dataRoot().resolve("runtime/task-runs/run-1"));
        assertThat(Files.exists(resolved)).isFalse();
    }

    @Test
    void resolveNormalizesWindowsSeparatorsInStoredStrings() throws Exception {
        TestContext context = context();

        Path resolved = context.service().resolve("agents\\a\\workspace\\out.txt");

        assertThat(resolved).isEqualTo(context.dataRoot().resolve("agents/a/workspace/out.txt"));
    }

    @Test
    void resolveAcceptsCurrentRootAbsoluteCompatibilityValues() throws Exception {
        TestContext context = context();
        Path file = context.dataRoot().resolve("legacy/current.txt");

        assertThat(context.service().resolve(file.toString())).isEqualTo(file);
    }

    @Test
    void resolveRejectsStaleOldRootAbsoluteValues() throws Exception {
        TestContext context = context();
        Path oldRoot = tempDir.resolve("old-root/root");
        Path stale = oldRoot.resolve("outputs/old.txt");

        assertThatThrownBy(() -> context.service().resolve(stale.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stale")
            .hasMessageContaining("outside current data root");
        assertThat(Files.exists(oldRoot)).isFalse();
    }

    @Test
    void displayResolvesMissingRelativeRowsWithoutCreatingPaths() throws Exception {
        TestContext context = context();
        Path missing = context.dataRoot().resolve("outputs/missing.txt");

        String display = context.service().display("outputs/missing.txt");

        assertThat(display).isEqualTo(missing.toString());
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void existingFileAndDirectoryHelpersVerifyExistenceTypeAndConfinement() throws Exception {
        TestContext context = context();
        Path file = context.dataRoot().resolve("outputs/a.txt");
        Path directory = context.dataRoot().resolve("outputs/dir");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        Files.createDirectories(directory);

        assertThat(context.service().resolveExistingFile("outputs/a.txt")).isEqualTo(file.toRealPath());
        assertThat(context.service().resolveExistingDirectory("outputs/dir")).isEqualTo(directory.toRealPath());
        assertThatThrownBy(() -> context.service().resolveExistingFile("outputs/dir"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("regular file");
        assertThatThrownBy(() -> context.service().resolveExistingDirectory("outputs/a.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("directory");
        assertThatThrownBy(() -> context.service().resolveExistingFile("outputs/missing.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void resolveRejectsRelativeTraversalValues() throws Exception {
        TestContext context = context();

        assertThatThrownBy(() -> context.service().resolve("../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes current data root");
        assertThatThrownBy(() -> context.service().resolve("agents/a/../../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes current data root");
    }

    private TestContext context() throws Exception {
        Path configuredDataRoot = tempDir.resolve("data");
        WorkspaceDirectoryService directories = WorkspaceDirectoryServiceTestSupport.directoryService(configuredDataRoot);
        return new TestContext(directories.dataRoot(), new RootRelativePathService(directories));
    }

    private record TestContext(Path dataRoot, RootRelativePathService service) {
    }
}
