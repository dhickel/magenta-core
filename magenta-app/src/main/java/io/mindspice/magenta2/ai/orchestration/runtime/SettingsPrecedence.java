package io.mindspice.magenta2.ai.orchestration.runtime;

import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import org.springframework.util.StringUtils;

/**
 * Resolves model, prompt profile, and settings overrides according to the
 * documented precedence order:
 *
 * <ol>
 *   <li>run override (highest priority)</li>
 *   <li>workflow node or job item override</li>
 *   <li>workflow or job default</li>
 *   <li>project default</li>
 *   <li>agent default</li>
 *   <li>runtime default (lowest priority)</li>
 * </ol>
 */
public final class SettingsPrecedence {

    private SettingsPrecedence() {}

    /**
     * Resolves the effective model for a plan run within a job item context.
     */
    public static String resolveModel(
        String runOverride,
        JobWorkItem jobItem,
        JobDefinition job,
        Project project,
        String agentDefault,
        String runtimeDefault
    ) {
        if (StringUtils.hasText(runOverride)) return runOverride;
        if (jobItem != null && StringUtils.hasText(jobItem.modelOverride())) return jobItem.modelOverride();
        if (job != null && StringUtils.hasText(job.model())) return job.model();
        if (project != null && StringUtils.hasText(project.model())) return project.model();
        if (StringUtils.hasText(agentDefault)) return agentDefault;
        return runtimeDefault;
    }

    /**
     * Resolves the effective model for a workflow node run context.
     * Use the plan/run-level override if set, else job default, etc.
     */
    public static String resolveModel(
        String runOverride,
        String nodeOverride,
        String jobDefault,
        String projectDefault,
        String agentDefault,
        String runtimeDefault
    ) {
        if (StringUtils.hasText(runOverride)) return runOverride;
        if (StringUtils.hasText(nodeOverride)) return nodeOverride;
        if (StringUtils.hasText(jobDefault)) return jobDefault;
        if (StringUtils.hasText(projectDefault)) return projectDefault;
        if (StringUtils.hasText(agentDefault)) return agentDefault;
        return runtimeDefault;
    }

    /**
     * Resolves the effective prompt profile.
     */
    public static String resolvePromptProfile(
        String runOverride,
        JobWorkItem jobItem,
        JobDefinition job,
        Project project,
        String agentDefault
    ) {
        if (StringUtils.hasText(runOverride)) return runOverride;
        if (jobItem != null && StringUtils.hasText(jobItem.modelOverride())) return jobItem.modelOverride();
        if (job != null && StringUtils.hasText(job.promptProfile())) return job.promptProfile();
        if (project != null && StringUtils.hasText(project.promptProfile())) return project.promptProfile();
        return agentDefault;
    }

    /**
     * Resolves settings override JSON. The first non-null value wins.
     */
    public static String resolveSettingsOverride(
        String runOverride,
        JobWorkItem jobItem,
        JobDefinition job,
        PlanDefinition plan,
        Project project,
        String agentDefault
    ) {
        if (StringUtils.hasText(runOverride)) return runOverride;
        if (jobItem != null && StringUtils.hasText(jobItem.modelOverride())) return jobItem.modelOverride();
        if (job != null && StringUtils.hasText(job.settingsOverrideJson())) return job.settingsOverrideJson();
        if (plan != null && StringUtils.hasText(plan.settingsOverrideJson())) return plan.settingsOverrideJson();
        if (project != null && StringUtils.hasText(project.settingsOverrideJson())) return project.settingsOverrideJson();
        return agentDefault;
    }

    /**
     * Resolves the effective model for a plan execution, factoring in
     * the plan's own execution model as the lowest non-runtime tier.
     */
    public static String resolveExecutionModel(
        String runOverride,
        String itemOverride,
        String jobDefault,
        String planExecutionModel,
        String projectDefault,
        String agentDefault,
        String runtimeDefault
    ) {
        if (StringUtils.hasText(runOverride)) return runOverride;
        if (StringUtils.hasText(itemOverride)) return itemOverride;
        if (StringUtils.hasText(jobDefault)) return jobDefault;
        if (StringUtils.hasText(planExecutionModel)) return planExecutionModel;
        if (StringUtils.hasText(projectDefault)) return projectDefault;
        if (StringUtils.hasText(agentDefault)) return agentDefault;
        return runtimeDefault;
    }
}
