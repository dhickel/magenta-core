package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class OutputArtifactPathSemanticsTest {

    @TempDir
    Path tempDir;

    @Test
    void materializedTextJsonUserMessageAndFilePathArtifactsPersistRelativePaths() throws Exception {
        Fixture fixture = fixture("materialized");
        Path outputDir = Files.createDirectories(fixture.dataRoot().resolve("outputs-direct"));
        Path source = Files.writeString(
            Files.createDirectories(fixture.dataRoot().resolve("runtime/task-runs/run-1")).resolve("source.txt"),
            "copied content"
        );

        List<RunOutputArtifact> artifacts = List.of(
            fixture.service().materialize("run-1", "plan-1", "summary", PlanFieldType.STRING, "done", outputDir),
            fixture.service().materialize("run-1", "plan-1", "payload", PlanFieldType.JSON, "{\"ok\":true}", outputDir),
            fixture.service().materialize("run-1", "plan-1", "message", PlanFieldType.USER_MESSAGE, "hello", outputDir),
            fixture.service().materialize("run-1", "plan-1", "source", PlanFieldType.FILE_PATH, source.toString(), outputDir)
        );

        assertThat(artifacts).extracting(RunOutputArtifact::filePath)
            .containsExactly(
                "outputs-direct/summary.txt",
                "outputs-direct/payload.json",
                "outputs-direct/message.md",
                "outputs-direct/source.txt"
            );
        artifacts.forEach(artifact -> assertStoredRelative(artifact.filePath(), fixture.dataRoot()));
        assertThat(Files.readString(fixture.dataRoot().resolve("outputs-direct/source.txt")))
            .isEqualTo("copied content");
    }

    @Test
    void loadContentReadsRelativeArtifactRows() throws Exception {
        Fixture fixture = fixture("relative-load");
        Path outputDir = Files.createDirectories(fixture.dataRoot().resolve("outputs"));
        RunOutputArtifact artifact = fixture.service().materialize(
            "run-1", "plan-1", "summary", PlanFieldType.STRING, "relative content", outputDir);

        assertThat(artifact.filePath()).isEqualTo("outputs/summary.txt");
        assertThat(fixture.service().loadContent(artifact.id(), 10_000)).isEqualTo("relative content");
    }

    @Test
    void loadContentReadsLegacyAbsoluteCurrentRootRows() throws Exception {
        Fixture fixture = fixture("legacy-current");
        Path legacyFile = Files.writeString(
            Files.createDirectories(fixture.dataRoot().resolve("legacy")).resolve("current.txt"),
            "legacy content"
        );
        RunOutputArtifact artifact = fixture.repository().saveArtifact(artifact("legacy-current", legacyFile.toString()));

        assertThat(fixture.service().loadContent(artifact.id(), 10_000)).isEqualTo("legacy content");
    }

    @Test
    void loadContentRejectsStaleAbsoluteOldRootRowsWithoutCreatingOldRootDirectories() throws Exception {
        Fixture fixture = fixture("legacy-stale");
        Path oldRoot = tempDir.resolve("old-root/root");
        Path staleFile = oldRoot.resolve("outputs/old.txt").toAbsolutePath();
        RunOutputArtifact artifact = fixture.repository().saveArtifact(artifact("legacy-stale", staleFile.toString()));

        assertThatThrownBy(() -> fixture.service().loadContent(artifact.id(), 10_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stale or outside current data root");
        assertThat(Files.notExists(oldRoot)).isTrue();
    }

    private Fixture fixture(String name) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(
            new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
        );
        OutputArtifactTestSchema.initialize(jdbc);
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve(name).resolve("data"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new RootRelativePathService(directoryService),
            new ObjectMapper().findAndRegisterModules()
        );
        return new Fixture(repository, service, directoryService.dataRoot());
    }

    private RunOutputArtifact artifact(String id, String filePath) {
        return new RunOutputArtifact(
            id,
            "run-" + id,
            "plan-" + id,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "summary",
            "text",
            "summary.txt",
            filePath,
            null,
            Instant.now()
        );
    }

    private void assertStoredRelative(String value, Path dataRoot) {
        assertThat(value).isNotBlank();
        assertThat(Path.of(value).isAbsolute()).isFalse();
        assertThat(value).doesNotContain(dataRoot.toString());
        assertThat(value).doesNotContain("\\");
    }

    private record Fixture(
        OutputArtifactRepository repository,
        OutputArtifactService service,
        Path dataRoot
    ) {
    }
}
