package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.time.Instant;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.GenerationLifecycleState;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.GenerationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.RootKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationGenerationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for generation record invariants, location construction,
 * and lifecycle state guards introduced in Phase 05.
 */
class ProjectMaterializationGenerationTest {

    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    // ── GenerationRecord invariants ──

    @Test
    void validGenerationRecord() {
        var record = new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64),
                1, RootKind.GENERATION_V2, null,
                GenerationLifecycleState.PROVISIONING, 1, 3,
                "[\"src\",\"docs\"]", null, NOW, NOW);

        assertThat(record.agentId()).isEqualTo("agent-1");
        assertThat(record.generation()).isEqualTo(1);
        assertThat(record.lifecycleState()).isEqualTo(GenerationLifecycleState.PROVISIONING);
        assertThat(record.lifecycleVersion()).isEqualTo(1);
        assertThat(record.headKey()).isEqualTo("agent-1/v1-PROJECT-" + "a".repeat(64));
    }

    @Test
    void rejectNullAgentId() {
        assertThatThrownBy(() -> new GenerationRecord(
                null, "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectBlankAgentId() {
        assertThatThrownBy(() -> new GenerationRecord(
                "", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectZeroGeneration() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 0, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation must be >= 1");
    }

    @Test
    void rejectNegativeGeneration() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), -1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation must be >= 1");
    }

    @Test
    void rejectNullRootKind() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, null,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectNullLifecycleState() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, null, 1, 1, "[]", null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectZeroLifecycleVersion() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 0, 1,
                "[]", null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycleVersion must be >= 1");
    }

    @Test
    void rejectNullTemplateDirectoriesSnapshot() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                null, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectNullCreatedAt() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectNullUpdatedAt() {
        assertThatThrownBy(() -> new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.PROVISIONING, 1, 1,
                "[]", null, NOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void headKeyComposition() {
        var record = new GenerationRecord(
                "agent-x", "v1-PROJECT-abcd1234", 5, RootKind.GENERATION_V2,
                null, GenerationLifecycleState.READY, 2, 1,
                "[\"src\"]", null, NOW, NOW);
        assertThat(record.headKey()).isEqualTo("agent-x/v1-PROJECT-abcd1234");
    }

    @Test
    void legacyV1RecordAcceptsNullTemplateRevision() {
        var record = new GenerationRecord(
                "agent-1", "v1-PROJECT-" + "a".repeat(64), 1, RootKind.LEGACY_V1,
                "legacy/path", GenerationLifecycleState.READY, 1, null,
                "[\"legacy\"]", null, NOW, NOW);
        assertThat(record.templateRevision()).isNull();
        assertThat(record.rootLocation()).isEqualTo("legacy/path");
    }

    // ── MaterializationGenerationLocation construction guard ──

    @Test
    void generationLocationRequiresAgentDescriptor() {
        var userDescriptor = new WorkspaceDescriptor(
                WorkspaceKind.USER, "user1", Path.of("/tmp/data-root/workspace/user/user1"));
        assertThatThrownBy(() -> new MaterializationGenerationLocation(
                userDescriptor, "v1-PROJECT-abcd", 1, "workspace/agent/x/materializations/v2/key/gen-1",
                Path.of("/tmp/data-root/workspace/agent/x/materializations/v2/key/gen-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGENT descriptor");
    }

    @Test
    void generationLocationRejectsBlankProjectKey() {
        var agentDescriptor = new WorkspaceDescriptor(
                WorkspaceKind.AGENT, "agent1",
                Path.of("/tmp/data-root/workspace/agent/agent1"));
        assertThatThrownBy(() -> new MaterializationGenerationLocation(
                agentDescriptor, "", 1, "rel/path", agentDescriptor.root().resolve("sub")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void generationLocationRejectsUnconfinedPath() {
        var agentDescriptor = new WorkspaceDescriptor(
                WorkspaceKind.AGENT, "agent1",
                Path.of("/tmp/data-root/workspace/agent/agent1"));
        assertThatThrownBy(() -> new MaterializationGenerationLocation(
                agentDescriptor, "v1-PROJECT-abcd", 1, "rel/path",
                Path.of("/tmp/outside/workspace/agent/agent1/sub")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("underneath the agent root");
    }
}
