package io.mindspice.magenta2.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactAccessContext;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactPreviewService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ConfinedPathMutationService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactTestSchema;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class OutputControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void detailReturnsNotFoundForMissingArtifact() throws Exception {
        Services services = services();
        OutputController controller = new OutputController(
                services.outputArtifactService(), services.artifactRepository(),
                services.searchService(), services.previewService(),
                services.retentionService());

        ResponseEntity<?> response = controller.detail("nonexistent", "agent", "agent-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailReturnsArtifactWhenAuthorized() throws Exception {
        Services services = services();
        OutputController controller = new OutputController(
                services.outputArtifactService(), services.artifactRepository(),
                services.searchService(), services.previewService(),
                services.retentionService());

        // Publish a chat artifact
        PublicationRecord record = services.outputArtifactService().publishChatBytes(
                "conv-1", "op-1", "test.md", "text/markdown",
                "# Hello".getBytes());

        ResponseEntity<?> response = controller.detail(
                record.artifactId(), "chat", "conv-1");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void detailRejectsWrongWorkspace() throws Exception {
        Services services = services();
        OutputController controller = new OutputController(
                services.outputArtifactService(), services.artifactRepository(),
                services.searchService(), services.previewService(),
                services.retentionService());

        PublicationRecord record = services.outputArtifactService().publishChatBytes(
                "conv-1", "op-2", "test.md", "text/markdown",
                "# Hello".getBytes());

        // Wrong workspace
        ResponseEntity<?> response = controller.detail(
                record.artifactId(), "agent", "wrong-agent");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void searchRequiresQuery() throws Exception {
        Services services = services();
        OutputController controller = new OutputController(
                services.outputArtifactService(), services.artifactRepository(),
                services.searchService(), services.previewService(),
                services.retentionService());

        ResponseEntity<?> response = controller.search(
                "", "agent", "agent-1", null, 0, 25);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listByWorkspaceReturnsAuthorizedArtifacts() throws Exception {
        Services services = services();
        OutputController controller = new OutputController(
                services.outputArtifactService(), services.artifactRepository(),
                services.searchService(), services.previewService(),
                services.retentionService());

        PublicationRecord record = services.outputArtifactService().publishChatBytes(
                "conv-1", "op-3", "list-test.md", "text/markdown",
                "# Test".getBytes());

        ResponseEntity<?> response = controller.listByWorkspace(
                "chat", "conv-1", null, 0, 50);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private Services services() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactTestSchema.initialize(jdbcTemplate);
        Path dataRoot = Files.createDirectories(tempDir.resolve("data"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        OutputArtifactRepository artifactRepository = new OutputArtifactRepository(jdbcTemplate);
        RootRelativePathService rootRelativePathService = new RootRelativePathService(directoryService);
        ConfinedPathMutationService mutations = new ConfinedPathMutationService();
        OutputArtifactService outputArtifactService = new OutputArtifactService(
                artifactRepository, directoryService, rootRelativePathService, mutations, mapper);

        // Create chat root so chat publication works
        directoryService.resolveForWrite(
                io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.CHAT, "conv-1");
        directoryService.outputs(directoryService.resolveForRead(
                io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.CHAT, "conv-1"));

        ArtifactSearchService searchService = new ArtifactSearchService(
                jdbcTemplate, artifactRepository, rootRelativePathService,
                directoryService, mutations);
        ArtifactPreviewService previewService = new ArtifactPreviewService(
                artifactRepository, rootRelativePathService);
        TaskRunRetentionService retentionService = new TaskRunRetentionService(
                jdbcTemplate, artifactRepository, rootRelativePathService,
                directoryService, mutations);

        return new Services(outputArtifactService, artifactRepository,
                searchService, previewService, retentionService);
    }

    private record Services(
            OutputArtifactService outputArtifactService,
            OutputArtifactRepository artifactRepository,
            ArtifactSearchService searchService,
            ArtifactPreviewService previewService,
            TaskRunRetentionService retentionService) {
    }
}
