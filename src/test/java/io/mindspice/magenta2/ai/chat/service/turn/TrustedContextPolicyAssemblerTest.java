package io.mindspice.magenta2.ai.chat.service.turn;

import java.nio.file.Path;
import java.time.Instant;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.BindingResolution;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedContextPolicyAssemblerTest {

    @TempDir
    Path tempDir;

    // ── Fixture helpers ──

    private WorkspaceDescriptor agentDescriptor() {
        return new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1",
                tempDir.resolve("workspace/agent/agent-1"));
    }

    private WorkspaceDescriptor userDescriptor() {
        return new WorkspaceDescriptor(WorkspaceKind.USER, "alice",
                tempDir.resolve("workspace/user/alice"));
    }

    private WorkspaceDescriptor chatDescriptor() {
        return new WorkspaceDescriptor(WorkspaceKind.CHAT, "chat-1",
                tempDir.resolve("workspace/chat/chat-1"));
    }

    private ContextBinding personalBinding(String agentId, PersonalSelection selection) {
        return ContextBinding.forInsert(agentId, "binding-key-1",
                SelectionKind.PERSONAL, null, null,
                selection, Instant.now());
    }

    private ContextBinding materializationBinding(String agentId, MaterializationKey key, int generation) {
        return ContextBinding.forInsert(agentId, "binding-key-2",
                SelectionKind.MATERIALIZATION, key, generation,
                null, Instant.now());
    }

    private MaterializationLocation materializationLocation(WorkspaceDescriptor agentDesc,
                                                            MaterializationKey key, int generation) {
        return WorkspaceDirectoryServiceTestSupport.materializationLocation(agentDesc, key, generation);
    }

    private BindingResolution available(ContextBinding binding, MaterializationLocation location) {
        return new BindingResolution(ContextOutcome.AVAILABLE, binding, location);
    }

    private BindingResolution unavailable() {
        return new BindingResolution(ContextOutcome.UNAVAILABLE, null, null);
    }

    private BindingResolution stale(ContextBinding binding) {
        return new BindingResolution(ContextOutcome.STALE, binding, null);
    }

    private BindingResolution nonReady(ContextBinding binding) {
        return new BindingResolution(ContextOutcome.NON_READY, binding, null);
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHAT rejection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void chatDescriptorIsRejectedAtConstruction() {
        BindingResolution resolution = available(personalBinding("agent-1", PersonalSelection.HOME), null);

        assertThatThrownBy(() -> TrustedContextPolicyAssembler.create(chatDescriptor(), resolution))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task-capable execution descriptor");
    }

    // ── AGENT authority ──

    @Test
    void agentDescriptorWithPersonalBindingProducesPolicyWithAgentIdentity() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.policyText()).contains("Agent: agent-1");
        assertThat(result.policyText()).contains("Execution workspace: agent/agent-1");
        assertThat(result.policyText()).contains("Binding: personal `HOME`");
        assertThat(result.hasPersonalSelection()).isTrue();
        assertThat(result.hasMaterialization()).isFalse();
        assertThat(result.personalSelection()).isEqualTo(PersonalSelection.HOME);
    }

    @Test
    void agentDescriptorWithMaterializationBindingProducesPolicyWithMaterializationProvenance() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        MaterializationLocation location = materializationLocation(agent, key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.policyText()).contains("Binding: materialization `" + key.serialized() + "`");
        assertThat(result.policyText()).contains("generation 2");
        assertThat(result.policyText()).contains("Materialization root verified at generation 2");
        assertThat(result.hasMaterialization()).isTrue();
        assertThat(result.hasPersonalSelection()).isFalse();
        assertThat(result.materializationLocation()).isEqualTo(location);
    }

    // ── USER authority ──

    @Test
    void userDescriptorProducesPolicyWithUserIdentity() {
        WorkspaceDescriptor user = userDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(user, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.policyText()).contains("Execution workspace: user/alice");
        // Write authority comes from execution descriptor, not the binding agent
        assertThat(result.policyText()).contains("Root: user/alice");
    }

    // ── Terminal UNAVAILABLE ──

    @Test
    void unavailableResolutionProducesTerminalResultWithNoPolicyText() {
        WorkspaceDescriptor agent = agentDescriptor();
        BindingResolution resolution = unavailable();

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.outcome()).isEqualTo(ContextOutcome.UNAVAILABLE);
        assertThat(result.policyText()).isNull();
        assertThat(result.binding()).isNull();
        assertThat(result.materializationLocation()).isNull();
        assertThat(result.executionDescriptor()).isNull();
    }

    // ── Terminal STALE ──

    @Test
    void staleResolutionProducesTerminalResultWithNoPartialPolicy() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        BindingResolution resolution = stale(binding);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.outcome()).isEqualTo(ContextOutcome.STALE);
        assertThat(result.policyText()).isNull();
        assertThat(result.binding()).isNull();
        assertThat(result.materializationLocation()).isNull();
        assertThat(result.executionDescriptor()).isNull();
    }

    // ── Terminal NON_READY ──

    @Test
    void nonReadyResolutionProducesTerminalResult() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        BindingResolution resolution = nonReady(binding);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.outcome()).isEqualTo(ContextOutcome.NON_READY);
        assertThat(result.policyText()).isNull();
    }

    // ── Missing binding in AVAILABLE ──

    @Test
    void availableOutcomeWithNullBindingDefaultsToTerminalUnavailable() {
        WorkspaceDescriptor agent = agentDescriptor();
        BindingResolution resolution = new BindingResolution(ContextOutcome.AVAILABLE, null, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.outcome()).isEqualTo(ContextOutcome.UNAVAILABLE);
    }

    // ── Revoked context ──

    @Test
    void revokedBindingProducesNonReadyTerminal() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 3);
        BindingResolution resolution = nonReady(binding);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.outcome()).isEqualTo(ContextOutcome.NON_READY);
    }

    // ── Stale/missing binding ──

    @Test
    void staleBindingProducesTerminal() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        BindingResolution resolution = stale(binding);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.outcome()).isEqualTo(ContextOutcome.STALE);
    }

    // ── Authority derives only from execution descriptor ──

    @Test
    void personalSelectionCannotWidenWritePaths() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-2", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("Agent: agent-2");
        assertThat(result.policyText()).contains("Execution workspace: agent/agent-1");
        assertThat(result.policyText()).contains("Root: agent/agent-1");
    }

    @Test
    void materializationBindingCannotSupplyExecutionAuthority() {
        WorkspaceDescriptor user = userDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        MaterializationLocation location = materializationLocation(agentDescriptor(), key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(user, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("Execution workspace: user/alice");
        assertThat(result.policyText()).contains("Root: user/alice");
        assertThat(result.policyText()).contains("Supplemental Read-Only Context");
        assertThat(result.policyText()).contains("do not grant write or tool authority");
    }

    // ── Read-only materialization semantics ──

    @Test
    void materializationPolicyDeclaresReadOnlyContext() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        MaterializationLocation location = materializationLocation(agent, key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("Supplemental Read-Only Context");
        assertThat(result.policyText()).contains("available for read-only reference");
    }

    // ── Identity without absolute roots ──

    @Test
    void policyTextContainsNoAbsolutePathDisclosure() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        MaterializationLocation location = materializationLocation(agent, key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        String text = result.policyText();
        assertThat(text).contains("agent/agent-1");
        // The absolute tempDir path must not appear in the policy text
        assertThat(text).doesNotContain(tempDir.toString());
    }

    // ── Output bound (truncation) ──

    @Test
    void policyTruncatesAtMaximumLength() {
        String longAgentId = "a".repeat(3000);
        WorkspaceDescriptor longAgent = new WorkspaceDescriptor(WorkspaceKind.AGENT, longAgentId,
                tempDir.resolve("workspace/agent/" + longAgentId));
        ContextBinding binding = ContextBinding.forInsert(longAgentId, "binding-key-1",
                SelectionKind.PERSONAL, null, null,
                PersonalSelection.HOME, Instant.now());
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(longAgent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.policyText()).hasSizeLessThanOrEqualTo(4096);
        assertThat(result.policyText()).contains("[Trusted policy truncated");
    }

    // ── Terminal result constructor validation ──

    @Test
    void terminalResultRejectsAvailableOutcome() {
        assertThatThrownBy(() -> TrustedContextPolicyAssembler.TrustedPolicyResult.terminal(ContextOutcome.AVAILABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-AVAILABLE");
    }

    @Test
    void terminalAcceptsAllNonAvailableOutcomes() {
        for (ContextOutcome outcome : new ContextOutcome[]{
                ContextOutcome.UNAVAILABLE, ContextOutcome.STALE, ContextOutcome.NON_READY}) {
            TrustedContextPolicyAssembler.TrustedPolicyResult result =
                    TrustedContextPolicyAssembler.TrustedPolicyResult.terminal(outcome);
            assertThat(result.isTerminal()).isTrue();
            assertThat(result.outcome()).isEqualTo(outcome);
            assertThat(result.policyText()).isNull();
        }
    }

    // ── Provenance: personal selections ──

    @Test
    void personalProvenanceHOME() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("personal `HOME`");
    }

    @Test
    void personalProvenanceSCRIPTS() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.SCRIPTS);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("personal `SCRIPTS`");
    }

    @Test
    void personalProvenanceAPPS() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.APPS);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("personal `APPS`");
    }

    // ── Null constructor guards ──

    @Test
    void nullExecutionDescriptorThrows() {
        BindingResolution resolution = available(personalBinding("agent-1", PersonalSelection.HOME), null);

        assertThatThrownBy(() -> TrustedContextPolicyAssembler.create(null, resolution))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("executionDescriptor");
    }

    @Test
    void nullBindingResolutionThrows() {
        assertThatThrownBy(() -> TrustedContextPolicyAssembler.create(agentDescriptor(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bindingResolution");
    }

    // ── Materialization integrity ──

    @Test
    void materializationBindingAndLocationGenerationMismatchIsTerminal() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 3);
        MaterializationLocation location = materializationLocation(agent, key, 5);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.STALE);
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.policyText()).isNull();
        assertThat(result.binding()).isNull();
        assertThat(result.materializationLocation()).isNull();
        assertThat(result.executionDescriptor()).isNull();
    }

    // ── Generation safety wording ──

    @Test
    void includesGenerationSafetyWording() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);
        TrustedContextPolicyAssembler.TrustedPolicyResult result = assembler.assemble();

        assertThat(result.policyText()).contains("### Generation Safety");
        assertThat(result.policyText()).contains("generation-bound and immutable");
        assertThat(result.policyText()).contains("terminates the turn");
    }

    // ── Assembler getters ──

    @Test
    void assemblerExposesBindingResolutionAndExecutionDescriptor() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler assembler = TrustedContextPolicyAssembler.create(agent, resolution);

        assertThat(assembler.executionDescriptor()).isEqualTo(agent);
        assertThat(assembler.bindingResolution()).isEqualTo(resolution);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Materialization integrity: agent mismatch
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void materializationBindingAgentIdMismatchWithLocationAgentDescriptorIsTerminal() {
        // Binding is for agent-1 but location's agent descriptor is agent-2
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);

        WorkspaceDescriptor otherAgent = new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-2",
                tempDir.resolve("workspace/agent/agent-2"));
        MaterializationLocation location = materializationLocation(otherAgent, key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.STALE);
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.policyText()).isNull();
    }

    @Test
    void materializationBindingKeyMismatchWithLocationKeyIsTerminal() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey bindingKey = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationKey locationKey = MaterializationKey.forProject("agent-1", "project-2");
        ContextBinding binding = materializationBinding("agent-1", bindingKey, 2);
        MaterializationLocation location = materializationLocation(agent, locationKey, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.STALE);
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.policyText()).isNull();
    }

    @Test
    void personalBindingWithNonNullLocationIsTerminal() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        MaterializationLocation location = materializationLocation(agent, key, 2);
        // Inconsistent: personal binding should have null location
        BindingResolution resolution = new BindingResolution(ContextOutcome.AVAILABLE, binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.STALE);
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.policyText()).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Materialization kind token in policy
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void materializationPolicyIncludesKindToken() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        MaterializationLocation location = materializationLocation(agent, key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.policyText()).contains("Materialization kind: PROJECT");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Authorized write paths always reference execution descriptor
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void userDescriptorWritePathsReferenceUserIdentityNotBindingAgent() {
        WorkspaceDescriptor user = userDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.SCRIPTS);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(user, resolution).assemble();

        assertThat(result.policyText()).contains("Execution workspace: user/alice");
        assertThat(result.policyText()).contains("Root: user/alice");
        // Agent identity appears as binding identity, not as write authority
        assertThat(result.policyText()).contains("Agent: agent-1");
        assertThat(result.policyText()).contains("personal `SCRIPTS`");
    }

    @Test
    void authorizedWritePathsSectionAlwaysReferencesExecutionDescriptor() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-2", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        // Authorized write path uses execution descriptor, not binding agent
        assertThat(result.policyText()).contains("Root: agent/agent-1");
        assertThat(result.policyText()).doesNotContain("Root: agent/agent-2");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Truncation boundary tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void shortPolicyIsNotTruncated() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.policyText()).doesNotContain("[Trusted policy truncated");
        assertThat(result.policyText()).hasSizeLessThan(4096);
    }

    @Test
    void policyOverMaxLengthIsTruncatedAndContainsSuffix() {
        // Build a policy well over 4096 chars by using a long agent ID.
        // The agent ID appears in the template text at multiple locations,
        // so use a very long ID to guarantee overflow.
        String longAgentId = "agent-" + "z".repeat(5000);
        WorkspaceDescriptor longAgent = new WorkspaceDescriptor(WorkspaceKind.AGENT, longAgentId,
                tempDir.resolve("workspace/agent/" + longAgentId));
        ContextBinding longBinding = ContextBinding.forInsert(longAgentId, "bk-3",
                SelectionKind.PERSONAL, null, null, PersonalSelection.HOME, Instant.now());
        BindingResolution longResolution = available(longBinding, null);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(longAgent, longResolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.policyText()).contains("[Trusted policy truncated");
        assertThat(result.policyText()).hasSizeLessThanOrEqualTo(4096);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Result type: hasMaterialization / hasPersonalSelection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void hasMaterializationFalseForPersonalBinding() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.hasMaterialization()).isFalse();
        assertThat(result.hasPersonalSelection()).isTrue();
        assertThat(result.personalSelection()).isEqualTo(PersonalSelection.HOME);
    }

    @Test
    void hasPersonalSelectionFalseForMaterializationBinding() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 2);
        MaterializationLocation location = materializationLocation(agent, key, 2);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.hasMaterialization()).isTrue();
        assertThat(result.hasPersonalSelection()).isFalse();
        assertThat(result.personalSelection()).isNull();
    }

    @Test
    void terminalResultHasNeitherMaterializationNorPersonalSelection() {
        TrustedContextPolicyAssembler.TrustedPolicyResult terminal =
                TrustedContextPolicyAssembler.TrustedPolicyResult.terminal(ContextOutcome.STALE);

        assertThat(terminal.hasMaterialization()).isFalse();
        assertThat(terminal.hasPersonalSelection()).isFalse();
        assertThat(terminal.personalSelection()).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Available result carries correct fields
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void availableResultCarriesBindingLocationAndDescriptor() {
        WorkspaceDescriptor agent = agentDescriptor();
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = materializationBinding("agent-1", key, 3);
        MaterializationLocation location = materializationLocation(agent, key, 3);
        BindingResolution resolution = available(binding, location);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.outcome()).isEqualTo(ContextOutcome.AVAILABLE);
        assertThat(result.isTerminal()).isFalse();
        assertThat(result.binding()).isEqualTo(binding);
        assertThat(result.materializationLocation()).isEqualTo(location);
        assertThat(result.executionDescriptor()).isEqualTo(agent);
        assertThat(result.policyText()).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Protected reserved descendants wording
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void policyMentionsReservedDescendantsAreProtected() {
        WorkspaceDescriptor agent = agentDescriptor();
        ContextBinding binding = personalBinding("agent-1", PersonalSelection.HOME);
        BindingResolution resolution = available(binding, null);

        TrustedContextPolicyAssembler.TrustedPolicyResult result =
                TrustedContextPolicyAssembler.create(agent, resolution).assemble();

        assertThat(result.policyText()).contains("protected from ordinary file operations");
        assertThat(result.policyText()).contains("outputs/").contains("tasks/").contains(".skills/");
    }
}
