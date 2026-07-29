package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDispatch;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimulation;
import io.mindspice.magenta2.ai.orchestration.runtime.EventType;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ReactionActionType;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleMode;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleFiring;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimulation;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationEventService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Project-scoped automation controller: bounded schedule and reaction CRUD,
 * fire/dispatch audit, simulation, and trusted project-id attribution.
 *
 * <p>All mutations require an active project and a current project member
 * as the explicit executor ({@code agentId}).</p>
 */
@Controller
public class ProjectAutomationController {
    private static final Logger log = LoggerFactory.getLogger(ProjectAutomationController.class);

    private final ProjectService projectService;
    private final ScheduleService scheduleService;
    private final EventReactionService reactionService;
    private final OrchestrationEventService eventService;
    private final ProjectAutomationFragments fragments;

    public ProjectAutomationController(ProjectService projectService,
                                        ScheduleService scheduleService,
                                        EventReactionService reactionService,
                                        OrchestrationEventService eventService,
                                        ProjectAutomationFragments fragments) {
        this.projectService = projectService;
        this.scheduleService = scheduleService;
        this.reactionService = reactionService;
        this.eventService = eventService;
        this.fragments = fragments;
    }

    // ════════════════════════════════════════════════════════════════
    //  Schedules Tab
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/schedules")
    @ResponseBody
    public String schedulesTab(@PathVariable String projectId, HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            if (project.archived()) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return error("Project is archived.");
            }
            return fragments.schedulesTabShell(projectId);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/schedules/_list")
    @ResponseBody
    public String scheduleList(@PathVariable String projectId, HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            List<ScheduleDefinition> defs = scheduleService.definitionsByProject(projectId);
            return fragments.scheduleList(projectId, defs);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/schedules/_detail/{scheduleId}")
    @ResponseBody
    public String scheduleDetail(@PathVariable String projectId,
                                  @PathVariable String scheduleId,
                                  @RequestParam(defaultValue = "1") int revision,
                                  HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            ScheduleDefinition def = scheduleService.definitionByRevision(
                scheduleOwner(scheduleId, projectId), scheduleId, revision);
            List<ScheduleFiring> firings = scheduleService.firings(scheduleId, revision, 0, 25);
            return fragments.scheduleDetail(projectId, def, firings);
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/schedules/_create")
    @ResponseBody
    public String scheduleCreateForm(@PathVariable String projectId, HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            return fragments.scheduleCreateForm(projectId, Map.of(), null);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/schedules")
    @ResponseBody
    public String createSchedule(@PathVariable String projectId,
                                  @RequestParam Map<String, String> params,
                                  HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            if (project.archived()) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return fragments.scheduleCreateForm(projectId, params, "Project is archived.");
            }
            String agentId = requireParam(params, "agentId");
            String modeStr = params.getOrDefault("mode", "CRON").trim();
            ScheduleMode mode;
            try {
                mode = ScheduleMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return fragments.scheduleCreateForm(projectId, params, "Invalid mode: " + modeStr);
            }

            String assignmentJson = params.getOrDefault("assignmentTemplate", "{}");
            Map<String, Object> assignmentTemplate;
            try {
                assignmentTemplate = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(assignmentJson, Map.class);
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return fragments.scheduleCreateForm(projectId, params,
                    "Invalid assignment template JSON: " + e.getMessage());
            }

            Instant fireAt = null;
            if (mode == ScheduleMode.ONE_TIME) {
                String fireAtStr = requireParam(params, "fireAt");
                try {
                    fireAt = Instant.parse(fireAtStr.trim());
                } catch (Exception e) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return fragments.scheduleCreateForm(projectId, params,
                        "Invalid fireAt: " + e.getMessage());
                }
            }

            ScheduleDefinition input = new ScheduleDefinition(
                null, agentId, 0, mode,
                params.get("cronExpression"),
                params.getOrDefault("timezone", "UTC"),
                fireAt,
                true, false,
                projectId,
                cleanNullable(params.get("jobId")),
                assignmentTemplate,
                null, null, null, null);

            ScheduleDefinition created = scheduleService.saveForProject(projectId, agentId, input);
            List<ScheduleDefinition> defs = scheduleService.definitionsByProject(projectId);
            List<ScheduleFiring> firings = scheduleService.firings(
                created.id(), created.revision(), 0, 25);
            return fragments.scheduleList(projectId, defs)
                + fragments.scheduleDetail(projectId, created, firings);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return fragments.scheduleCreateForm(projectId, params, e.getMessage());
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return fragments.scheduleCreateForm(projectId, params, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Schedule creation failed for project {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.scheduleCreateForm(projectId, params,
                "Schedule creation is temporarily unavailable.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/schedules/{scheduleId}/enable")
    @ResponseBody
    public String enableSchedule(@PathVariable String projectId,
                                  @PathVariable String scheduleId,
                                  @RequestParam Map<String, String> params,
                                  HttpServletResponse response) {
        try {
            String agentId = scheduleOwner(scheduleId, projectId);
            boolean enabled = "true".equals(params.get("enabled"));
            ScheduleDefinition updated = scheduleService.enableForProject(
                projectId, agentId, scheduleId, enabled);
            List<ScheduleFiring> firings = scheduleService.firings(
                scheduleId, updated.revision(), 0, 25);
            return fragments.scheduleDetail(projectId, updated, firings);
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Not found.");
        }
    }

    @DeleteMapping("/projects/_detail/{projectId}/schedules/{scheduleId}")
    @ResponseBody
    public String deleteSchedule(@PathVariable String projectId,
                                  @PathVariable String scheduleId,
                                  HttpServletResponse response) {
        try {
            String agentId = scheduleOwner(scheduleId, projectId);
            scheduleService.deleteForProject(projectId, agentId, scheduleId);
            List<ScheduleDefinition> defs = scheduleService.definitionsByProject(projectId);
            return fragments.scheduleList(projectId, defs)
                + new io.mindspice.simplypages.components.Div()
                    .withId("project-schedules-detail").render();
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Not found.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/schedules/_simulate")
    @ResponseBody
    public String simulateSchedule(@PathVariable String projectId,
                                    @RequestParam Map<String, String> params,
                                    HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            if (project.archived()) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return error("Project is archived.");
            }
            String modeStr = params.getOrDefault("mode", "CRON").trim();
            ScheduleMode mode;
            try {
                mode = ScheduleMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return new io.mindspice.simplypages.components.Div()
                    .withClass("orch-error").withInnerText("Invalid mode: " + modeStr).render();
            }

            Instant fireAt = null;
            if (mode == ScheduleMode.ONE_TIME && StringUtils.hasText(params.get("fireAt"))) {
                fireAt = Instant.parse(params.get("fireAt").trim());
            }

            ScheduleSimulation sim = scheduleService.simulate(new ScheduleSimRequest(
                params.get("cronExpression"),
                params.getOrDefault("timezone", "UTC"),
                mode, fireAt,
                true, null, null));

            return fragments.scheduleSimulationResult(sim);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return new io.mindspice.simplypages.components.Div()
                .withClass("orch-error").withInnerText(e.getMessage()).render();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Events Tab
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/events")
    @ResponseBody
    public String eventsTab(@PathVariable String projectId, HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            if (project.archived()) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return error("Project is archived.");
            }
            return fragments.eventsTabShell(projectId);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/events/_list")
    @ResponseBody
    public String reactionList(@PathVariable String projectId, HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            List<ReactionDefinition> defs = reactionService.definitionsByProject(projectId);
            return fragments.reactionList(projectId, defs);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/events/_detail/{reactionId}")
    @ResponseBody
    public String reactionDetail(@PathVariable String projectId,
                                  @PathVariable String reactionId,
                                  @RequestParam(defaultValue = "1") int revision,
                                  HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            ReactionDefinition def = reactionService.definitionByRevision(
                reactionOwner(reactionId, projectId), reactionId, revision);
            List<ReactionDispatch> dispatches = reactionService.dispatches(
                reactionId, revision, 0, 25);
            return fragments.reactionDetail(projectId, def, dispatches);
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/events/_create")
    @ResponseBody
    public String reactionCreateForm(@PathVariable String projectId, HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            return fragments.reactionCreateForm(projectId, Map.of(), null);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Project not found.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/events")
    @ResponseBody
    public String createReaction(@PathVariable String projectId,
                                  @RequestParam Map<String, String> params,
                                  HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            if (project.archived()) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return fragments.reactionCreateForm(projectId, params, "Project is archived.");
            }
            String agentId = requireParam(params, "agentId");
            String eventTypeStr = requireParam(params, "eventType");
            EventType eventType;
            try {
                eventType = EventType.valueOf(eventTypeStr.trim());
            } catch (IllegalArgumentException e) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return fragments.reactionCreateForm(projectId, params,
                    "Invalid event type: " + eventTypeStr);
            }

            Map<String, Object> filter = Map.of();
            if (StringUtils.hasText(params.get("filterJson"))) {
                try {
                    filter = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(params.get("filterJson"), Map.class);
                } catch (Exception e) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return fragments.reactionCreateForm(projectId, params,
                        "Invalid filter JSON: " + e.getMessage());
                }
            }

            String assignmentJson = params.getOrDefault("assignmentTemplate", "{}");
            Map<String, Object> assignmentTemplate;
            try {
                assignmentTemplate = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(assignmentJson, Map.class);
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return fragments.reactionCreateForm(projectId, params,
                    "Invalid assignment template JSON: " + e.getMessage());
            }

            ReactionDefinition input = new ReactionDefinition(
                null, agentId, 0,
                eventType, filter, ReactionActionType.ENQUEUE_ASSIGNMENT,
                assignmentTemplate, true, false,
                projectId, null, null);

            ReactionDefinition created = reactionService.saveForProject(
                projectId, agentId, input);
            List<ReactionDefinition> defs = reactionService.definitionsByProject(projectId);
            List<ReactionDispatch> dispatches = reactionService.dispatches(
                created.id(), created.revision(), 0, 25);
            return fragments.reactionList(projectId, defs)
                + fragments.reactionDetail(projectId, created, dispatches);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return fragments.reactionCreateForm(projectId, params, e.getMessage());
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return fragments.reactionCreateForm(projectId, params, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Reaction creation failed for project {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.reactionCreateForm(projectId, params,
                "Reaction creation is temporarily unavailable.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/events/{reactionId}/enable")
    @ResponseBody
    public String enableReaction(@PathVariable String projectId,
                                  @PathVariable String reactionId,
                                  @RequestParam Map<String, String> params,
                                  HttpServletResponse response) {
        try {
            String agentId = reactionOwner(reactionId, projectId);
            boolean enabled = "true".equals(params.get("enabled"));
            ReactionDefinition updated = reactionService.enableForProject(
                projectId, agentId, reactionId, enabled);
            List<ReactionDispatch> dispatches = reactionService.dispatches(
                reactionId, updated.revision(), 0, 25);
            return fragments.reactionDetail(projectId, updated, dispatches);
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Not found.");
        }
    }

    @DeleteMapping("/projects/_detail/{projectId}/events/{reactionId}")
    @ResponseBody
    public String deleteReaction(@PathVariable String projectId,
                                  @PathVariable String reactionId,
                                  HttpServletResponse response) {
        try {
            String agentId = reactionOwner(reactionId, projectId);
            reactionService.deleteForProject(projectId, agentId, reactionId);
            List<ReactionDefinition> defs = reactionService.definitionsByProject(projectId);
            return fragments.reactionList(projectId, defs)
                + new io.mindspice.simplypages.components.Div()
                    .withId("project-events-detail").render();
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return error(e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return error("Not found.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/events/_simulate")
    @ResponseBody
    public String simulateReaction(@PathVariable String projectId,
                                    @RequestParam Map<String, String> params,
                                    HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            if (project.archived()) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return error("Project is archived.");
            }
            String eventTypeStr = params.getOrDefault("eventType", "SCHEDULE_DUE").trim();
            Map<String, Object> filter = Map.of();
            if (StringUtils.hasText(params.get("filterJson"))) {
                filter = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(params.get("filterJson"), Map.class);
            }

            ReactionSimulation sim = reactionService.simulate(new ReactionSimRequest(
                eventTypeStr, filter, Map.of(),
                null, null, null,
                projectId, projectId,
                null));

            return fragments.reactionSimulationResult(sim);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return new io.mindspice.simplypages.components.Div()
                .withClass("orch-error").withInnerText(e.getMessage()).render();
        }
    }

    // ── Helpers ──

    private String scheduleOwner(String scheduleId, String projectId) {
        List<ScheduleDefinition> defs = scheduleService.definitionsByProject(projectId);
        return defs.stream()
            .filter(d -> d.id().equals(scheduleId))
            .findFirst()
            .map(ScheduleDefinition::agentId)
            .orElseThrow(() -> new IllegalStateException("schedule not in project"));
    }

    private String reactionOwner(String reactionId, String projectId) {
        List<ReactionDefinition> defs = reactionService.definitionsByProject(projectId);
        return defs.stream()
            .filter(d -> d.id().equals(reactionId))
            .findFirst()
            .map(ReactionDefinition::agentId)
            .orElseThrow(() -> new IllegalStateException("reaction not in project"));
    }

    private String requireParam(Map<String, String> params, String key) {
        String value = params.get(key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private String cleanNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String error(String message) {
        return new io.mindspice.simplypages.components.Div()
            .withClass("orch-error").withInnerText(message).render();
    }
}
