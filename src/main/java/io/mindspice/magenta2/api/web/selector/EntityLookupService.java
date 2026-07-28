package io.mindspice.magenta2.api.web.selector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRun;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowDefinition;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EntityLookupService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final AgentProfileService agentProfileService;
    private final PlanService planService;
    private final WorkflowService workflowService;
    private final JobService jobService;
    private final ProjectService projectService;
    private final ChatService chatService;

    public EntityLookupService(
        AgentProfileService agentProfileService,
        PlanService planService,
        WorkflowService workflowService,
        JobService jobService,
        ProjectService projectService,
        ChatService chatService
    ) {
        this.agentProfileService = agentProfileService;
        this.planService = planService;
        this.workflowService = workflowService;
        this.jobService = jobService;
        this.projectService = projectService;
        this.chatService = chatService;
    }

    public List<EntityOption> search(EntityKind kind, SelectorQuery query) {
        List<EntityOption> options = switch (kind) {
            case AGENT -> agents(query);
            case PLAN, TASK -> plans(kind);
            case WORKFLOW -> workflows();
            case JOB -> jobs(query.context());
            case PROJECT -> projects();
            case MODEL -> models();
            case RUN -> runs(query.context());
            case TARGET -> targets();
        };
        return filterAndLimit(kind, options, query);
    }

    public EntityValidation validate(EntityKind kind, String id, boolean required) {
        if (!StringUtils.hasText(id)) {
            return new EntityValidation(kind.wireName(), "", !required, null, required ? "Required" : "");
        }
        String cleanId = id.trim();
        EntityOption option = findById(kind, cleanId);
        if (option == null) {
            return new EntityValidation(kind.wireName(), cleanId, false, null, "Not found");
        }
        return new EntityValidation(kind.wireName(), cleanId, true, option.label(), "Selected");
    }

    public EntityOption currentOption(EntityKind kind, String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        EntityOption found = findById(kind, id.trim());
        if (found != null) {
            return found;
        }
        return new EntityOption(kind.wireName(), id.trim(), id.trim(), "Current value is not in the database", "missing", false);
    }

    private EntityOption findById(EntityKind kind, String id) {
        try {
            return switch (kind) {
                case AGENT -> option(agentProfileService.get(id));
                case PLAN, TASK -> option(kind, planService.getTask(id));
                case WORKFLOW -> option(workflowService.getDefinition(id));
                case JOB -> option(jobService.getDefinition(id));
                case PROJECT -> option(projectService.getProject(id));
                case MODEL -> chatService.availableModels().stream()
                    .filter(model -> model.equals(id))
                    .findFirst()
                    .map(model -> new EntityOption("model", model, model, "Configured model", "model", true))
                    .or(() -> chatService.availableModelOptions().stream()
                        .filter(model -> model.key().equals(id))
                        .findFirst()
                        .map(this::modelOption))
                    .orElse(null);
                case RUN -> findRun(id);
                case TARGET -> findTarget(id);
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<EntityOption> agents(SelectorQuery query) {
        String current = query.current();
        return agentProfileService.list().stream()
            .filter(agent -> agent.status() != AgentProfileStatus.DISABLED
                || query.includeUnavailable()
                || (StringUtils.hasText(current) && current.equals(agent.id())))
            .map(this::option)
            .toList();
    }

    private List<EntityOption> plans(EntityKind kind) {
        return planService.listTasks().stream()
            .map(plan -> option(kind, plan))
            .toList();
    }

    private List<EntityOption> workflows() {
        return workflowService.listDefinitions().stream()
            .map(this::option)
            .toList();
    }

    private List<EntityOption> jobs(Map<String, String> context) {
        return jobService.listDefinitions(
                blankToNull(context.get("agentId")),
                blankToNull(context.get("projectId")),
                blankToNull(context.get("status")))
            .stream()
            .map(this::option)
            .toList();
    }

    private List<EntityOption> projects() {
        return projectService.listProjects().stream()
            .map(this::option)
            .toList();
    }

    private List<EntityOption> models() {
        return chatService.availableModelOptions().stream()
            .map(this::modelOption)
            .toList();
    }

    private List<EntityOption> runs(Map<String, String> context) {
        String jobId = context.get("jobId");
        if (!StringUtils.hasText(jobId)) {
            return List.of();
        }
        return jobService.listRuns(jobId.trim()).stream()
            .map(this::option)
            .toList();
    }

    private List<EntityOption> targets() {
        List<EntityOption> options = new ArrayList<>();
        options.addAll(plans(EntityKind.TASK).stream()
            .map(option -> new EntityOption("target", option.id(), option.label(), "Task: " + option.detail(), option.status(), option.available()))
            .toList());
        options.addAll(workflows().stream()
            .map(option -> new EntityOption("target", option.id(), option.label(), "Workflow: " + option.detail(), option.status(), option.available()))
            .toList());
        options.addAll(jobs(Map.of()).stream()
            .map(option -> new EntityOption("target", option.id(), option.label(), "Job: " + option.detail(), option.status(), option.available()))
            .toList());
        return options;
    }

    private EntityOption findTarget(String id) {
        EntityOption found = findById(EntityKind.TASK, id);
        if (found != null) {
            return new EntityOption("target", found.id(), found.label(), "Task: " + found.detail(), found.status(), found.available());
        }
        found = findById(EntityKind.WORKFLOW, id);
        if (found != null) {
            return new EntityOption("target", found.id(), found.label(), "Workflow: " + found.detail(), found.status(), found.available());
        }
        found = findById(EntityKind.JOB, id);
        if (found != null) {
            return new EntityOption("target", found.id(), found.label(), "Job: " + found.detail(), found.status(), found.available());
        }
        return null;
    }

    private EntityOption findRun(String id) {
        try {
            return option(jobService.getRun(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<EntityOption> filterAndLimit(EntityKind kind, List<EntityOption> options, SelectorQuery query) {
        String q = query.q() == null ? "" : query.q().trim().toLowerCase(Locale.ROOT);
        int limit = query.limit() <= 0 ? DEFAULT_LIMIT : Math.min(query.limit(), MAX_LIMIT);
        List<EntityOption> filtered = options.stream()
            .filter(option -> option.available() || query.includeUnavailable())
            .filter(option -> !StringUtils.hasText(q) || matches(option, q))
            .sorted(Comparator.comparing(EntityOption::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EntityOption::id, String.CASE_INSENSITIVE_ORDER))
            .limit(limit)
            .toList();
        if (!StringUtils.hasText(query.current()) || filtered.stream().anyMatch(o -> o.id().equals(query.current()))) {
            return filtered;
        }
        EntityOption current = currentOption(kind, query.current());
        if (current == null) {
            return filtered;
        }
        List<EntityOption> withCurrent = new ArrayList<>();
        withCurrent.add(current);
        withCurrent.addAll(filtered);
        return withCurrent;
    }

    private boolean matches(EntityOption option, String q) {
        return contains(option.id(), q)
            || contains(option.label(), q)
            || contains(option.detail(), q)
            || contains(option.status(), q);
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private EntityOption option(AgentProfile agent) {
        String status = agent.status() == null ? "unknown" : agent.status().name();
        return new EntityOption("agent", agent.id(), first(agent.name(), agent.id()),
            "Model " + first(agent.defaultModel(), "default"), status, agent.status() != AgentProfileStatus.DISABLED);
    }

    private EntityOption option(EntityKind kind, PlanDefinition plan) {
        String status = plan.status() == null ? "unknown" : plan.status().name();
        return new EntityOption(kind.wireName(), plan.id(), first(plan.title(), plan.id()),
            first(plan.summary(), plan.kind() == null ? "Plan" : plan.kind().name()), status, true);
    }

    private EntityOption option(WorkflowDefinition workflow) {
        return new EntityOption("workflow", workflow.id(), first(workflow.title(), workflow.id()),
            first(workflow.summary(), workflow.nodes().size() + " nodes"), "workflow", true);
    }

    private EntityOption option(JobDefinition job) {
        return new EntityOption("job", job.id(), first(job.title(), job.id()),
            first(job.summary(), job.items().size() + " items"), first(job.status(), "unknown"), true);
    }

    private EntityOption option(Project project) {
        return new EntityOption("project", project.id(), first(project.name(), project.id()),
            first(project.description(), "Shared workspace context"), "project", true);
    }

    private EntityOption option(JobRun run) {
        return new EntityOption("run", run.id(), run.id(), "Job " + run.jobId(),
            run.status() == null ? "unknown" : run.status().name(), true);
    }

    private EntityOption modelOption(ChatService.ModelOption model) {
        return new EntityOption("model", model.key(), model.label(), "Configured model", "model", true);
    }

    private String first(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
