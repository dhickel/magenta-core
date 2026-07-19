package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class OutputArtifactServiceAttributionTest {

    @TempDir
    Path tempDir;

    @Test
    void materializePreservesAttributionContext() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("data"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs"));
        service.materialize(
            "run-1",
            "plan-1",
            "summary",
            PlanFieldType.STRING,
            "done",
            outputDir,
            new OutputArtifactContext(
                "agent-1", "job-1", "assignment-1", "job-run-1",
                "project-1", "agent", "agent-1", "agent/agent-1", "TASK_RUN")
        );

        RunOutputArtifact artifact = repository.findArtifactsByRunId("run-1").get(0);
        assertThat(artifact.agentId()).isEqualTo("agent-1");
        assertThat(artifact.jobId()).isEqualTo("job-1");
        assertThat(artifact.jobAssignmentId()).isEqualTo("assignment-1");
        assertThat(artifact.jobRunId()).isEqualTo("job-run-1");
        assertThat(artifact.projectId()).isEqualTo("project-1");
        assertThat(artifact.workspaceIdentity()).isEqualTo("agent/agent-1");
        assertThat(artifact.runType()).isEqualTo("TASK_RUN");
        assertThat(service.query(OutputArtifactQuery.of("agent-1", null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, "job-1", null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, "project-1", null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, null, null, null, null, null, "agent/agent-1", null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, "assignment-1", null, null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, null, "job-run-1", null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, null, null, null, null, null, "plan-1", null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, null, null, null, null, "run-1", null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(service.query(OutputArtifactQuery.of(null, null, null, null, null, null, null, null, "TASK_RUN", null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
    }

    @Test
    void projectScopedJobOutputUsesAssignmentPathAndJobRunAttribution() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("project-job-output"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );
        Path projectRoot = directoryService.projectWorkspaceRoot("project-1");
        Path outputDir = directoryService.outputsDir(projectRoot);

        service.materialize(
            "job-run-1",
            "job-1",
            "summary",
            PlanFieldType.STRING,
            "done",
            outputDir,
            new OutputArtifactContext(
                "agent-1", "job-1", "assignment-1", "job-run-1",
                "project-1", null, null, "workspace-1", "JOB_RUN")
        );

        RunOutputArtifact artifact = repository.findArtifactsByRunId("job-run-1").getFirst();
        assertThat(artifact.filePath())
            .startsWith("projects/project-1/outputs/");
        assertThat(Path.of(artifact.filePath()).isAbsolute()).isFalse();
        assertThat(artifact.jobId()).isEqualTo("job-1");
        assertThat(artifact.jobAssignmentId()).isEqualTo("assignment-1");
        assertThat(artifact.jobRunId()).isEqualTo("job-run-1");
        assertThat(artifact.projectId()).isEqualTo("project-1");
    }

    @Test
    void materializeUsesUniqueFileNamesForDuplicateOutputNames() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("collision-root"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );
        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-collision"));

        RunOutputArtifact first = service.materialize(
            "run-collision", "plan-collision", "summary", PlanFieldType.STRING, "first", outputDir,
            OutputArtifactContext.EMPTY);
        RunOutputArtifact second = service.materialize(
            "run-collision", "plan-collision", "summary", PlanFieldType.STRING, "second", outputDir,
            OutputArtifactContext.EMPTY);

        assertThat(first.fileName()).isEqualTo("summary.txt");
        assertThat(second.fileName()).isEqualTo("summary-2.txt");
        assertThat(Files.readString(dataRoot.resolve(first.filePath()))).isEqualTo("first");
        assertThat(Files.readString(dataRoot.resolve(second.filePath()))).isEqualTo("second");
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 03: Filesystem path resolution
    // ════════════════════════════════════════════════════════════════

    @Test
    void dataRootScopedFilePathResolvesToRunOutputDirectory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("root"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        // Output dir must be under dataRoot for absolute path resolution
        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs"));
        Files.writeString(outputDir.resolve("workspace-result.txt"), "workspace output");

        // Materialize with absolute path under data root
        RunOutputArtifact artifact = service.materialize(
            "run-1",
            "plan-1",
            "result",
            PlanFieldType.FILE_PATH,
            outputDir.resolve("workspace-result.txt").toString(),
            outputDir,
            OutputArtifactContext.EMPTY
        );

        assertThat(artifact.fileName()).isEqualTo("workspace-result.txt");
        assertThat(artifact.artifactType()).isEqualTo("file_path");
        assertThat(artifact.filePath()).isEqualTo("outputs/workspace-result.txt");
        assertThat(Files.exists(dataRoot.resolve(artifact.filePath()))).isTrue();
    }

    @Test
    void relativePathResolvesRelativeToOutputDirectory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-run-scoped"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("my-task-run-123"));
        Files.writeString(outputDir.resolve("result.json"), "{\"ok\":true}");

        // Materialize with bare filename relative to output dir
        RunOutputArtifact artifact = service.materialize(
            "run-123",
            "plan-123",
            "result",
            PlanFieldType.FILE_PATH,
            "result.json",
            outputDir,
            OutputArtifactContext.EMPTY
        );

        assertThat(artifact.fileName()).isEqualTo("result.json");
        assertThat(artifact.filePath()).isEqualTo("my-task-run-123/result.json");
        assertThat(dataRoot.resolve(artifact.filePath())).isEqualTo(outputDir.resolve("result.json"));
    }

    @Test
    void bareFilenameResolvesRelativeToOutputDirectory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("root2"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs2"));
        Files.writeString(outputDir.resolve("hello.txt"), "hello world");

        // Materialize with bare filename (model might report just "hello.txt")
        RunOutputArtifact artifact = service.materialize(
            "run-2",
            "plan-2",
            "greeting",
            PlanFieldType.FILE_PATH,
            "hello.txt",
            outputDir,
            OutputArtifactContext.EMPTY
        );

        assertThat(artifact.fileName()).isEqualTo("hello.txt");
        assertThat(artifact.filePath()).isEqualTo("outputs2/hello.txt");
        assertThat(Files.exists(dataRoot.resolve(artifact.filePath()))).isTrue();
    }

    @Test
    void copiesValidFilePathInsideDataRootIntoOutputDirectory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("copy-root"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path sourceDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/runs/run-copy"));
        Path outputDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/outputs/run-copy"));
        Path source = sourceDir.resolve("result.txt");
        Files.writeString(source, "safe output");

        RunOutputArtifact artifact = service.materialize(
            "run-copy",
            "plan-copy",
            "result",
            PlanFieldType.FILE_PATH,
            source.toString(),
            outputDir,
            OutputArtifactContext.EMPTY
        );

        Path artifactPath = Path.of(artifact.filePath());
        assertThat(artifact.fileName()).isEqualTo("result.txt");
        assertThat(artifact.filePath()).isEqualTo("workspace/agent-1/outputs/run-copy/result.txt");
        assertThat(dataRoot.resolve(artifactPath)).isEqualTo(outputDir.resolve("result.txt"));
        assertThat(Files.readString(dataRoot.resolve(artifactPath))).isEqualTo("safe output");
        assertThat(dataRoot.resolve(artifactPath)).isNotEqualTo(source);
    }

    @Test
    void rejectsAbsoluteFilePathOutsideDataRoot() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root3"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs3"));

        assertThatThrownBy(() ->
            service.materialize(
                "run-3",
                "plan-3",
                "bad",
                PlanFieldType.FILE_PATH,
                "/etc/passwd",
                outputDir,
                OutputArtifactContext.EMPTY
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void rejectsFilePathSymlinkEscapingDataRoot() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("symlink-root"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-symlink"));
        Path outside = Files.writeString(tempDir.resolve("outside-secret.txt"), "secret");
        Path symlink = outputDir.resolve("outside-link.txt");
        Files.createSymbolicLink(symlink, outside);

        assertThatThrownBy(() ->
            service.materialize(
                "run-symlink",
                "plan-symlink",
                "bad",
                PlanFieldType.FILE_PATH,
                symlink.toString(),
                outputDir,
                OutputArtifactContext.EMPTY
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");

        assertThat(repository.findArtifactsByRunId("run-symlink")).isEmpty();
    }

    @Test
    void rejectsBrokenFilePathSymlinkWithClearFailure() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("broken-root"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-broken"));
        Path symlink = outputDir.resolve("missing-link.txt");
        Files.createSymbolicLink(symlink, tempDir.resolve("missing-target.txt"));

        assertThatThrownBy(() ->
            service.materialize(
                "run-broken",
                "plan-broken",
                "broken",
                PlanFieldType.FILE_PATH,
                symlink.toString(),
                outputDir,
                OutputArtifactContext.EMPTY
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("broken symlink");

        assertThat(repository.findArtifactsByRunId("run-broken")).isEmpty();
    }

    @Test
    void rejectsMissingFilePathWithClearFailure() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        Path dataRoot = Files.createDirectories(tempDir.resolve("missing-root"));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-missing"));
        Path missing = outputDir.resolve("missing.txt");

        assertThatThrownBy(() ->
            service.materialize(
                "run-missing",
                "plan-missing",
                "missing",
                PlanFieldType.FILE_PATH,
                missing.toString(),
                outputDir,
                OutputArtifactContext.EMPTY
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");

        assertThat(repository.findArtifactsByRunId("run-missing")).isEmpty();
    }

    @Test
    void discoversLooseArtifactsInOutputDirectory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root4"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs4"));
        // Create loose files that weren't registered as outputs
        Files.writeString(outputDir.resolve("notes.md"), "# Notes");
        Files.writeString(outputDir.resolve("data.json"), "{\"key\": 1}");

        int discovered = service.discoverLooseArtifacts(
            "run-4", "plan-4", outputDir, OutputArtifactContext.EMPTY);

        assertThat(discovered).isEqualTo(2);

        List<RunOutputArtifact> artifacts = repository.findArtifactsByRunId("run-4");
        assertThat(artifacts).hasSize(2);
        assertThat(artifacts).extracting(RunOutputArtifact::fileName)
            .containsExactlyInAnyOrder("notes.md", "data.json");
        assertThat(artifacts).extracting(RunOutputArtifact::filePath)
            .containsExactlyInAnyOrder("outputs4/notes.md", "outputs4/data.json");
    }

    @Test
    void looseArtifactDiscoveryCurrentlyScansOnlyDirectOutputFiles() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-shallow"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-shallow"));
        Files.writeString(outputDir.resolve("direct.log"), "direct output");
        Files.writeString(outputDir.resolve("unknown.bin"), "binary-ish output");
        Path nested = Files.createDirectories(outputDir.resolve("nested"));
        Files.writeString(nested.resolve("nested.txt"), "nested output");

        int discovered = service.discoverLooseArtifacts(
            "run-shallow", "plan-shallow", outputDir, OutputArtifactContext.EMPTY);

        assertThat(discovered).isEqualTo(2);
        List<RunOutputArtifact> artifacts = repository.findArtifactsByRunId("run-shallow");
        assertThat(artifacts).extracting(RunOutputArtifact::fileName)
            .containsExactlyInAnyOrder("direct.log", "unknown.bin");
        assertThat(artifacts).extracting(RunOutputArtifact::artifactType)
            .containsExactlyInAnyOrder("text", "file_path");
        assertThat(artifacts).extracting(RunOutputArtifact::filePath)
            .containsExactlyInAnyOrder("outputs-shallow/direct.log", "outputs-shallow/unknown.bin");
    }

    @Test
    void looseArtifactDiscoveryCanBeDisabledByCompatibilityPolicy() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-disabled"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules(),
            false
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-disabled"));
        Files.writeString(outputDir.resolve("loose.txt"), "compat output");

        int discovered = service.discoverLooseArtifacts(
            "run-disabled", "plan-disabled", outputDir, OutputArtifactContext.EMPTY);

        assertThat(discovered).isZero();
        assertThat(repository.findArtifactsByRunId("run-disabled")).isEmpty();
    }

    @Test
    void looseArtifactDiscoverySkipsSymlinkEscapes() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-loose-symlink"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-loose-symlink"));
        Files.writeString(outputDir.resolve("safe.txt"), "safe output");
        Files.createSymbolicLink(outputDir.resolve("outside.txt"), Files.writeString(tempDir.resolve("outside.txt"), "outside"));

        int discovered = service.discoverLooseArtifacts(
            "run-loose-symlink", "plan-loose-symlink", outputDir, OutputArtifactContext.EMPTY);

        assertThat(discovered).isEqualTo(1);
        assertThat(repository.findArtifactsByRunId("run-loose-symlink"))
            .extracting(RunOutputArtifact::fileName)
            .containsExactly("safe.txt");
    }

    @Test
    void publishExistingFileCopiesAndRegistersExplicitOutput() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-publish"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path runDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/runs/run-publish"));
        Path source = Files.writeString(runDir.resolve("report.md"), "# report");
        Path outputDir = Files.createDirectories(dataRoot.resolve("projects/project-1/outputs/run-publish"));

        RunOutputArtifact artifact = service.publishExistingFile(
            "run-publish",
            "task-1",
            "report",
            null,
            source,
            outputDir,
            new OutputArtifactContext("agent-1", null, "project-1", null, null, "workspace-1", "TASK_RUN")
        );

        assertThat(artifact.outputName()).isEqualTo("report");
        assertThat(artifact.artifactType()).isEqualTo("user_message");
        assertThat(artifact.projectId()).isEqualTo("project-1");
        assertThat(artifact.filePath()).isEqualTo("projects/project-1/outputs/run-publish/report.md");
        assertThat(Files.readString(outputDir.resolve("report.md"))).isEqualTo("# report");
    }

    @Test
    void publishDirectoryContentsCopiesTempFilesAndRegistersArtifacts() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-publish-temp"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path sourceDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/runs/run-temp"));
        Files.writeString(sourceDir.resolve("report.md"), "# report");
        Path nested = Files.createDirectories(sourceDir.resolve("nested"));
        Files.writeString(nested.resolve("data.json"), "{\"ok\":true}");
        Path outputDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/outputs/run-temp"));

        List<RunOutputArtifact> artifacts = service.publishDirectoryContents(
            "run-temp",
            "task-1",
            sourceDir,
            outputDir,
            new OutputArtifactContext("agent-1", null, null, null, null, null, null, "workspace-1", "TASK_RUN")
        );

        assertThat(artifacts).hasSize(2);
        assertThat(repository.findArtifactsByRunId("run-temp"))
            .extracting(RunOutputArtifact::outputName)
            .containsExactlyInAnyOrder("copied_temp/report.md", "copied_temp/nested/data.json");
        assertThat(repository.findArtifactsByRunId("run-temp"))
            .extracting(RunOutputArtifact::artifactType)
            .containsExactlyInAnyOrder("user_message", "json");
        assertThat(Files.readString(outputDir.resolve("copied-temp/report.md"))).isEqualTo("# report");
        assertThat(Files.readString(outputDir.resolve("copied-temp/nested/data.json"))).isEqualTo("{\"ok\":true}");
    }

    @Test
    void publishDirectoryContentsSkipsSymlinkedFilesAndProjectLinks() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-publish-temp-symlink"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );

        Path sourceDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/runs/run-temp-symlink"));
        Files.writeString(sourceDir.resolve("safe.txt"), "safe");
        Files.createSymbolicLink(sourceDir.resolve("outside-link.txt"), Files.writeString(tempDir.resolve("outside.txt"), "outside"));
        Path projectWorkspace = Files.createDirectories(dataRoot.resolve("projects/project-1"));
        Files.writeString(projectWorkspace.resolve("project-data.txt"), "project");
        Files.createDirectories(sourceDir.resolve("projects"));
        Files.createSymbolicLink(sourceDir.resolve("projects/project-1"), projectWorkspace);
        Path outputDir = Files.createDirectories(dataRoot.resolve("projects/project-1/outputs/run-temp-symlink"));

        List<RunOutputArtifact> artifacts = service.publishDirectoryContents(
            "run-temp-symlink",
            "task-1",
            sourceDir,
            outputDir,
            new OutputArtifactContext("agent-1", null, null, null, "project-1", null, null, "workspace-1", "TASK_RUN")
        );

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.getFirst().outputName()).isEqualTo("copied_temp/safe.txt");
        assertThat(Files.exists(outputDir.resolve("copied-temp/outside-link.txt"))).isFalse();
        assertThat(Files.exists(outputDir.resolve("copied-temp/projects/project-1/project-data.txt"))).isFalse();
    }

    @Test
    void publishDirectoryContentsRejectsEscapingSourceAndDestinationPaths() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve("root-publish-temp-reject"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactService service = new OutputArtifactService(
            repository,
            directoryService,
            new ObjectMapper().findAndRegisterModules()
        );
        Path sourceDir = Files.createDirectories(dataRoot.resolve("workspace/agent-1/runs/run-temp-reject"));
        Files.writeString(sourceDir.resolve("safe.txt"), "safe");
        Path outputDir = Files.createDirectories(dataRoot.resolve("outputs-temp-reject"));
        Files.createSymbolicLink(outputDir.resolve("copied-temp"), tempDir.resolve("outside-dest"));

        assertThatThrownBy(() -> service.publishDirectoryContents(
            "run-temp-reject",
            "task-1",
            tempDir.resolve("outside-source"),
            outputDir,
            OutputArtifactContext.EMPTY
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");

        assertThatThrownBy(() -> service.publishDirectoryContents(
            "run-temp-reject",
            "task-1",
            sourceDir,
            outputDir,
            OutputArtifactContext.EMPTY
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be a symlink");
        assertThat(repository.findArtifactsByRunId("run-temp-reject")).isEmpty();
    }
}
