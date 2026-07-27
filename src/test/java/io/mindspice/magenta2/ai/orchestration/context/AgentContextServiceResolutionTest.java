package io.mindspice.magenta2.ai.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.junit.jupiter.api.Test;

class AgentContextServiceResolutionTest {

    @Test
    void resolutionDistinguishesUnavailableStaleAndNonReadyBindings() {
        AgentContextRepository contexts = mock(AgentContextRepository.class);
        AgentMaterializationRepository materializations = mock(AgentMaterializationRepository.class);
        AgentMaterializationService materializationService = mock(AgentMaterializationService.class);
        AgentContextService service = new AgentContextService(contexts, materializations, materializationService);
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1",
                Path.of("/tmp/magenta/workspace/agent/agent-1"));
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        ContextBinding binding = ContextBinding.forInsert("agent-1", "binding-1",
                SelectionKind.MATERIALIZATION, key, 2, null, Instant.now());

        when(contexts.findByBindingKey("missing")).thenReturn(Optional.empty());
        assertThat(service.resolveBinding(descriptor, "missing").outcome())
                .isEqualTo(ContextOutcome.UNAVAILABLE);

        when(contexts.findByBindingKey("binding-1")).thenReturn(Optional.of(binding));
        when(materializations.findByAgentAndKey("agent-1", key)).thenReturn(Optional.of(
                new MaterializationRecord("agent-1", key, "project", "project-1", 3,
                        MaterializationState.READY, "workspace/agent/agent-1/projects/" + key.serialized(),
                        null, Instant.now(), Instant.now())));
        assertThat(service.resolveBinding(descriptor, "binding-1").outcome())
                .isEqualTo(ContextOutcome.STALE);

        when(materializations.findByAgentAndKey("agent-1", key)).thenReturn(Optional.of(
                new MaterializationRecord("agent-1", key, "project", "project-1", 2,
                        MaterializationState.FAILED, null, "failed", Instant.now(), Instant.now())));
        assertThat(service.resolveBinding(descriptor, "binding-1").outcome())
                .isEqualTo(ContextOutcome.NON_READY);
    }
}
