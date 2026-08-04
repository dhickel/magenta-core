package io.mindspice.magenta2.api.web;

import java.io.IOException;

import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOutputService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationRecord;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Project-scoped publication byte adapters.
 *
 * <p>Every route proves the project-to-publication durable link before it
 * reads bytes. Workspace-scoped artifact routes remain separate and are not
 * used by the project console.
 */
@Controller
public final class ProjectOutputController {
    private final ProjectOutputService projectOutputService;
    private final OutputArtifactService outputArtifactService;
    private final ProjectService projectService;

    public ProjectOutputController(ProjectOutputService projectOutputService,
                                   OutputArtifactService outputArtifactService,
                                   ProjectService projectService) {
        this.projectOutputService = projectOutputService;
        this.outputArtifactService = outputArtifactService;
        this.projectService = projectService;
    }

    @GetMapping("/projects/_detail/{projectId}/outputs/{artifactId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String projectId,
                                          @PathVariable String artifactId) {
        PublicationRecord publication = authorize(projectId, artifactId);
        return ResponseEntity.ok()
                .contentType(mediaType(publication))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(publication.logicalName()).build().toString())
                .body(readBytes(publication));
    }

    @GetMapping("/projects/_detail/{projectId}/outputs/{artifactId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String projectId,
                                           @PathVariable String artifactId) {
        PublicationRecord publication = authorize(projectId, artifactId);
        return ResponseEntity.ok()
                .contentType(mediaType(publication))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(publication.logicalName()).build().toString())
                .body(readBytes(publication));
    }

    private PublicationRecord authorize(String projectId, String artifactId) {
        try {
            projectService.requireActiveProject(projectId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output not found");
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project output access is unavailable");
        }
        return projectOutputService.authorizePublication(projectId, artifactId)
                .map(ProjectOutputService.AuthorizedPublication::publication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Output not found"));
    }

    private byte[] readBytes(PublicationRecord publication) {
        try {
            return outputArtifactService.readPublishedBytes(publication.artifactId());
        } catch (IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output is not available");
        }
    }

    private static MediaType mediaType(PublicationRecord publication) {
        try {
            return publication.mediaType() == null ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(publication.mediaType());
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
