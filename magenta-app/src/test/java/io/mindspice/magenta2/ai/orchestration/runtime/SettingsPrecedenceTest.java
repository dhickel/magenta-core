package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsPrecedenceTest {

    @Test
    void runOverrideWinsOverEverything() {
        String model = SettingsPrecedence.resolveModel(
            "run-model",
            item("item-model"), job("job-model"),
            project("project-model"),
            "agent-model", "runtime-model"
        );
        assertThat(model).isEqualTo("run-model");
    }

    @Test
    void jobItemOverrideWinsOverJobDefault() {
        String model = SettingsPrecedence.resolveModel(
            null,
            item("item-model"), job("job-model"),
            project(null),
            "agent-model", "runtime-model"
        );
        assertThat(model).isEqualTo("item-model");
    }

    @Test
    void jobDefaultWinsOverProject() {
        String model = SettingsPrecedence.resolveModel(
            null,
            null, job("job-model"),
            project("project-model"),
            "agent-model", "runtime-model"
        );
        assertThat(model).isEqualTo("job-model");
    }

    @Test
    void projectDefaultWinsOverAgent() {
        String model = SettingsPrecedence.resolveModel(
            null,
            null, job(null),
            project("project-model"),
            "agent-model", "runtime-model"
        );
        assertThat(model).isEqualTo("project-model");
    }

    @Test
    void agentDefaultWinsOverRuntime() {
        String model = SettingsPrecedence.resolveModel(
            null,
            null, job(null),
            project(null),
            "agent-model", "runtime-model"
        );
        assertThat(model).isEqualTo("agent-model");
    }

    @Test
    void runtimeModelIsLastResort() {
        String model = SettingsPrecedence.resolveModel(
            null, null, job(null), project(null),
            null, "runtime-model"
        );
        assertThat(model).isEqualTo("runtime-model");
    }

    @Test
    void resolvePromptProfileFollowsPrecedence() {
        String profile = SettingsPrecedence.resolvePromptProfile(
            null,
            null, jobWithProfile("coding"), project(null), null
        );
        assertThat(profile).isEqualTo("coding");
    }

    @Test
    void resolveSettingsOverrideFollowsChain() {
        String json = SettingsPrecedence.resolveSettingsOverride(
            null, null, jobWithSettings("{\"temp\":0.7}"),
            null, project(null), null
        );
        assertThat(json).isEqualTo("{\"temp\":0.7}");
    }

    @Test
    void resolveExecutionModelWithPlanModel() {
        String model = SettingsPrecedence.resolveExecutionModel(
            null, null, null,
            "plan-exec-model",
            null, null, "runtime-model"
        );
        assertThat(model).isEqualTo("plan-exec-model");
    }

    // ── Helpers ──

    private JobWorkItem item(String model) {
        return new JobWorkItem("k", JobWorkItemType.PLAN, "plan-1", null,
            Map.of(), 0, model, null);
    }

    private JobDefinition job(String model) {
        return new JobDefinition("j1", "t", "s", List.of(),
            null, model, null, null, null);
    }

    private JobDefinition jobWithProfile(String promptProfile) {
        return new JobDefinition("j1", "t", "s", List.of(),
            promptProfile, null, null, null, null);
    }

    private JobDefinition jobWithSettings(String settings) {
        return new JobDefinition("j1", "t", "s", List.of(),
            null, null, settings, null, null);
    }

    private Project project(String model) {
        return new Project("p1", "n", "d", "a1", null,
            null, model, null, false, null, null);
    }
}
