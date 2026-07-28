package io.mindspice.magenta2.api.web;

import java.util.List;

import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    // ════════════════════════════════════════════════════════════════
    //  Projects
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/api/projects")
    public List<Project> list() {
        return projectService.listProjects();
    }

    @PostMapping("/api/projects")
    public Project create(@RequestBody CreateProjectRequest request) {
        try {
            return projectService.createProject(
                request.effectiveName(), request.effectiveDescription(),
                request.ownerAgentId(), request.gitRepoUrl()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/api/projects/{projectId}")
    public Project get(@PathVariable String projectId) {
        try {
            return projectService.getProject(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/api/projects/{projectId}")
    public Project update(@PathVariable String projectId,
                           @RequestBody UpdateProjectRequest request) {
        try {
            return projectService.updateProject(
                projectId, request.effectiveName(), request.effectiveDescription(),
                request.gitRepoUrl(), request.promptProfile(),
                request.model(), request.settingsOverrideJson()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/api/projects/{projectId}")
    public void delete(@PathVariable String projectId) {
        try {
            projectService.deleteProject(projectId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Agents
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/projects/{projectId}/agents")
    public ProjectAgentMembership addAgent(@PathVariable String projectId,
                                            @RequestBody AddAgentRequest request) {
        try {
            return projectService.addAgent(projectId, request.agentId(), request.role());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/api/projects/{projectId}/agents")
    public List<ProjectAgentMembership> listAgents(@PathVariable String projectId) {
        return projectService.listMembers(projectId);
    }

    @DeleteMapping("/api/projects/{projectId}/agents/{agentId}")
    public void removeAgent(@PathVariable String projectId,
                             @PathVariable String agentId) {
        try {
            projectService.removeAgent(projectId, agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Network
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/api/projects/{projectId}/network")
    public Object network(@PathVariable String projectId) {
        var members = projectService.listMembers(projectId);
        return new NetworkResponse(projectId, members);
    }

    // ════════════════════════════════════════════════════════════════
    //  Events
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/api/projects/{projectId}/events")
    public List<ProjectEvent> events(@PathVariable String projectId) {
        return projectService.listEvents(projectId);
    }

    // ── DTOs ──

    public record CreateProjectRequest(
        String name,
        String description,
        String title,
        String summary,
        String ownerAgentId,
        String gitRepoUrl
    ) {
        String effectiveName() {
            return name != null ? name : title;
        }

        String effectiveDescription() {
            return description != null ? description : summary;
        }
    }

    public record UpdateProjectRequest(
        String name,
        String description,
        String title,
        String summary,
        String gitRepoUrl,
        String promptProfile,
        String model,
        String settingsOverrideJson
    ) {
        String effectiveName() {
            return name != null ? name : title;
        }

        String effectiveDescription() {
            return description != null ? description : summary;
        }
    }

    public record AddAgentRequest(String agentId, String role) {}

    public record NetworkResponse(
        String projectId,
        List<ProjectAgentMembership> members
    ) {}
}
