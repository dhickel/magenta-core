package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.WorkTypeProfile;
import io.mindspice.magenta2.ai.chat.plan.WorkTypeProfileService;
import io.mindspice.magenta2.ai.chat.service.ResolvedChatRequest;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdLayer;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdResolution;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdResolver;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspacePathLayout;
import io.mindspice.magenta2.ai.skills.AgentSkillPromptCatalogAssembler;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Composes the system prompt and turn instructions by merging the default agent
 * prompt with mode-specific runtime instructions from PlanService/TaskService.
 */
public class PromptContextAssembler {

    private final AiConfig aiConfig;
    private final RuntimeSettingsService runtimeSettingsService;
    private final PlanService planService;
    private final TaskService taskService;
    private final WorkTypeProfileService workTypeProfileService;
    private final AgentsMdResolver agentsMdResolver;
    private final AgentSkillPromptCatalogAssembler skillPromptCatalogAssembler;

    public PromptContextAssembler(
        AiConfig aiConfig,
        RuntimeSettingsService runtimeSettingsService,
        PlanService planService,
        TaskService taskService,
        WorkTypeProfileService workTypeProfileService,
        AgentsMdResolver agentsMdResolver
    ) {
        this(
            aiConfig,
            runtimeSettingsService,
            planService,
            taskService,
            workTypeProfileService,
            agentsMdResolver,
            null
        );
    }

    public PromptContextAssembler(
        AiConfig aiConfig,
        RuntimeSettingsService runtimeSettingsService,
        PlanService planService,
        TaskService taskService,
        WorkTypeProfileService workTypeProfileService,
        AgentsMdResolver agentsMdResolver,
        AgentSkillPromptCatalogAssembler skillPromptCatalogAssembler
    ) {
        this.aiConfig = aiConfig;
        this.runtimeSettingsService = runtimeSettingsService;
        this.planService = planService;
        this.taskService = taskService;
        this.workTypeProfileService = workTypeProfileService;
        this.agentsMdResolver = agentsMdResolver;
        this.skillPromptCatalogAssembler = skillPromptCatalogAssembler;
    }

    /**
     * Assembles the full turn context: resolves interaction mode, builds system prompt,
     * and constructs the ordered instruction list (system message + user message).
     */
    public void assemble(TurnContext ctx) {
        ResolvedChatRequest request = ctx.resolvedRequest();
        PlanMode mode = ctx.interactionMode();
        String systemPrompt = mergeModePrompt(mode, request.conversationId());
        ctx.systemPrompt(systemPrompt);
        ctx.turnInstructions(assembleTurnInstructions(request, systemPrompt));
    }

    /**
     * Composes the effective system prompt by merging the default agent prompt
     * with mode-specific runtime instructions from plan/task services.
     * Key invariant: PLAN and TASK mode prompts completely replace the default system prompt.
     * Worktype profile text is appended after mode-specific instructions.
     */
    public String mergeModePrompt(PlanMode mode, String conversationId) {
        String systemPrompt = defaultSystemPrompt();
        String runtimePrompt = planService == null ? "" : planService.runtimeInstructions(conversationId);
        String result;
        if (mode == PlanMode.PLAN) {
            result = runtimePrompt;
        } else if (mode == PlanMode.TASK) {
            result = taskService == null ? "" : taskService.runtimeInstructions(conversationId);
        } else if (mode == PlanMode.EXECUTE_TASK) {
            String taskRuntimePrompt = taskService == null ? "" : taskService.runtimeInstructions(conversationId);
            if (!StringUtils.hasText(systemPrompt)) {
                result = taskRuntimePrompt;
            } else {
                result = systemPrompt + "\n\n" + taskRuntimePrompt;
            }
        } else if (!StringUtils.hasText(runtimePrompt)) {
            result = systemPrompt;
        } else if (!StringUtils.hasText(systemPrompt)) {
            result = runtimePrompt;
        } else {
            result = systemPrompt + "\n\n" + runtimePrompt;
        }
        // Append worktype profile text after mode-specific instructions
        String worktypeAppend = workTypeProfileAppend(conversationId);
        if (StringUtils.hasText(worktypeAppend) && StringUtils.hasText(result)) {
            result = result.stripTrailing() + "\n\n" + worktypeAppend;
        }
        String agentsMdAppend = agentsMdPromptAppend();
        if (StringUtils.hasText(agentsMdAppend) && StringUtils.hasText(result)) {
            result = result.stripTrailing() + "\n\n" + agentsMdAppend;
        } else if (StringUtils.hasText(agentsMdAppend)) {
            result = agentsMdAppend;
        }
        String skillCatalogAppend = skillPromptCatalogAppend(conversationId, mode);
        if (StringUtils.hasText(skillCatalogAppend) && StringUtils.hasText(result)) {
            result = result.stripTrailing() + "\n\n" + skillCatalogAppend;
        } else if (StringUtils.hasText(skillCatalogAppend)) {
            result = skillCatalogAppend;
        }
        return result;
    }

    /**
     * Looks up the active plan or task draft for a conversation and returns
     * the worktype profile prompt append text.
     */
    private String workTypeProfileAppend(String conversationId) {
        if (planService == null || workTypeProfileService == null) {
            return "";
        }
        // Check active session plan first
        PlanDefinition plan = planService.activePlan(conversationId).orElse(null);
        if (plan == null) {
            // Check task draft
            plan = planService.activeDraft(conversationId).orElse(null);
        }
        if (plan != null) {
            return workTypeProfileService.getSystemPromptAppendForPlan(plan.promptProfile());
        }
        return "";
    }

    private String agentsMdPromptAppend() {
        if (agentsMdResolver == null) {
            return "";
        }
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context == null || !context.hasAgentContext()) {
            return "";
        }
        try {
            Optional<AgentsMdResolution> resolved = agentsMdResolver.resolveForContext(context, runtimeActivePath(context));
            if (resolved.isEmpty()) {
                return "";
            }
            AgentsMdResolution resolution = resolved.orElseThrow();
            if (!resolution.hasLayers()) {
                return "";
            }
            return formatAgentsMdPromptBlock(resolution);
        } catch (RuntimeException | IOException ignored) {
            return "";
        }
    }

    private String skillPromptCatalogAppend(String conversationId, PlanMode mode) {
        if (skillPromptCatalogAssembler == null) {
            return "";
        }
        if (mode == PlanMode.PLAN || mode == PlanMode.TASK) {
            return "";
        }
        return skillPromptCatalogAssembler.promptAppend(conversationId);
    }

    private String runtimeActivePath(OrchestrationTaskContext context) {
        if (StringUtils.hasText(context.activeRuntimePath())) {
            return context.activeRuntimePath();
        }
        String workspaceRootText = context.hostDurableWorkspacePath();
        if (!StringUtils.hasText(workspaceRootText)) {
            return WorkspacePathLayout.WORKSPACE;
        }
        try {
            Path workspaceRoot = Path.of(workspaceRootText).normalize();
            Path activeWorkspacePath = workspaceRoot;
            if (!activeWorkspacePath.startsWith(workspaceRoot)) {
                return WorkspacePathLayout.WORKSPACE;
            }
            String relative = workspaceRoot.relativize(activeWorkspacePath).toString().replace('\\', '/');
            if (!StringUtils.hasText(relative) || ".".equals(relative)) {
                return WorkspacePathLayout.WORKSPACE;
            }
            return WorkspacePathLayout.WORKSPACE + "/" + relative;
        } catch (RuntimeException ignored) {
            return WorkspacePathLayout.WORKSPACE;
        }
    }

    private String formatAgentsMdPromptBlock(AgentsMdResolution resolution) {
        StringBuilder builder = new StringBuilder();
        builder.append("## Runtime AGENTS.md Context\n\n");
        builder.append("Explicit user prompts and current task instructions override all AGENTS.md guidance.\n");
        builder.append("Layers are ordered from bound root to closest path. ");
        builder.append("The closest layer wins only on conflicts, and non-conflicting ancestor guidance remains active.\n\n");
        for (int i = 0; i < resolution.layers().size(); i++) {
            AgentsMdLayer layer = resolution.layers().get(i);
            String source = layer.isRootLayer() ? "AGENTS.md" : layer.relativeDirectory() + "/AGENTS.md";
            String label = layer.isRootLayer() ? "root" : layer.relativeDirectory();
            builder.append("### Layer ").append(i + 1).append(" — ").append(label).append("\n");
            builder.append("Source: ").append(source).append("\n\n");
            builder.append(layer.content().strip()).append("\n\n");
        }
        return builder.toString().stripTrailing();
    }

    /**
     * Builds the ordered instruction list: system message (if present) + user message.
     */
    public List<Message> assembleTurnInstructions(ResolvedChatRequest request, String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(request.message()));
        return messages;
    }

    public String defaultSystemPrompt() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.defaultSystemPrompt();
        }
        if (aiConfig == null || !StringUtils.hasText(aiConfig.defaultAgent()) || aiConfig.agents() == null) {
            return null;
        }
        AgentConfig defaultAgent = aiConfig.agents().get(aiConfig.defaultAgent());
        return defaultAgent == null ? null : defaultAgent.systemPrompt();
    }
}
