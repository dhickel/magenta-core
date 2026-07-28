package io.mindspice.magenta2.ai.chat.service.turn;

import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.BindingResolution;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;

import org.springframework.util.StringUtils;

/**
 * Derives immutable trusted policy facts from the Phase 03 binding resolution
 * and independently selected execution descriptor.
 *
 * <p>This is the sole new source of trusted prompt facts. Allowed write/tool
 * paths derive only from the execution descriptor and current lifecycle
 * capability; a materialization remains supplemental read-only context.
 *
 * <p>Missing, stale, revoked, non-ready, mismatched, or otherwise unresolvable
 * trusted context produces a terminal result that must propagate through
 * prompt assembly without catch-and-continue behavior.
 */
public class TrustedContextPolicyAssembler {

    private static final int POLICY_MAX_CHARS = 4096;

    private final WorkspaceDescriptor executionDescriptor;
    private final BindingResolution bindingResolution;

    private TrustedContextPolicyAssembler(WorkspaceDescriptor executionDescriptor,
                                          BindingResolution bindingResolution) {
        this.executionDescriptor = Objects.requireNonNull(executionDescriptor,
                "executionDescriptor is required");
        this.bindingResolution = Objects.requireNonNull(bindingResolution,
                "bindingResolution is required");
        if (executionDescriptor.kind() == WorkspaceKind.CHAT) {
            throw new IllegalArgumentException(
                    "Trusted policy assembly requires a task-capable execution descriptor");
        }
    }

    /**
     * Create an assembler from a resolved binding and an independently
     * selected execution descriptor. The execution descriptor must be
     * the one chosen by the existing selection rules (paired type/key
     * or bound-agent default), never derived from the materialization.
     */
    public static TrustedContextPolicyAssembler create(
            WorkspaceDescriptor executionDescriptor,
            BindingResolution bindingResolution) {
        return new TrustedContextPolicyAssembler(executionDescriptor, bindingResolution);
    }

    /**
     * Build the trusted policy result. Terminal outcomes carry no policy
     * text; callers must propagate them before model or tool use.
     */
    public TrustedPolicyResult assemble() {
        if (bindingResolution.outcome() != ContextOutcome.AVAILABLE) {
            return TrustedPolicyResult.terminal(bindingResolution.outcome());
        }

        ContextBinding binding = bindingResolution.binding();
        MaterializationLocation location = bindingResolution.location();

        if (binding == null) {
            return TrustedPolicyResult.terminal(ContextOutcome.UNAVAILABLE);
        }

        StringBuilder policy = new StringBuilder();
        policy.append("## Magenta Trusted Context Policy\n\n");

        // Bounded identity — agent, workspace, binding
        policy.append("### Identity\n");
        policy.append("- Agent: ").append(binding.agentId()).append("\n");
        policy.append("- Execution workspace: ").append(executionDescriptor.identity()).append("\n");

        if (binding.selectionKind() == SelectionKind.MATERIALIZATION) {
            policy.append("- Binding: materialization `")
                    .append(binding.materializationKey().serialized())
                    .append("` generation ").append(binding.materializationGeneration()).append("\n");
            policy.append("- Materialization kind: ")
                    .append(binding.materializationKey().kindToken()).append("\n");
            if (location != null) {
                policy.append("- Materialization root verified at generation ")
                        .append(location.generation()).append("\n");
            }
        } else {
            policy.append("- Binding: personal `").append(binding.personalSelection().name())
                    .append("`\n");
        }

        // Allowed write/tool paths from execution descriptor only
        policy.append("\n### Authorized Write Paths\n");
        policy.append("- Root: ").append(executionDescriptor.identity()).append("\n");
        policy.append("- All writes are confined beneath the execution workspace root\n");
        policy.append("- Reserved descendants (`outputs/`, `tasks/`, `.skills/`) ")
                .append("are protected from ordinary file operations\n");

        // Supplemental materialization (read-only context)
        if (binding.selectionKind() == SelectionKind.MATERIALIZATION && location != null) {
            policy.append("\n### Supplemental Read-Only Context\n");
            policy.append("- Materialization projects directory is available for read-only reference\n");
            policy.append("- Materialization facts do not grant write or tool authority\n");
        }

        // Workflow facts
        policy.append("\n### Generation Safety\n");
        policy.append("- All captured context is generation-bound and immutable\n");
        policy.append("- Stale, revoked, or non-ready context terminates the turn before model use\n");

        String policyText = policy.toString();
        if (policyText.length() > POLICY_MAX_CHARS) {
            policyText = policyText.substring(0, POLICY_MAX_CHARS - 40)
                    + "\n\n[Trusted policy truncated — maximum length reached]";
        }

        return TrustedPolicyResult.available(policyText, binding,
                bindingResolution.outcome(), location, executionDescriptor);
    }

    /**
     * Returns the binding resolution for downstream consumers that need
     * to know whether a materialization or personal area is active.
     */
    public BindingResolution bindingResolution() {
        return bindingResolution;
    }

    /**
     * Returns the independently selected execution descriptor.
     */
    public WorkspaceDescriptor executionDescriptor() {
        return executionDescriptor;
    }

    // ── Result type ──

    /**
     * Typed terminal result for trusted policy assembly.
     *
     * <p>When {@code outcome} is not {@code AVAILABLE}, {@code policyText}
     * is null and callers must terminate the turn. When available,
     * {@code policyText} contains the bounded, path-safe policy block.
     */
    public record TrustedPolicyResult(
            ContextOutcome outcome,
            String policyText,
            ContextBinding binding,
            MaterializationLocation materializationLocation,
            WorkspaceDescriptor executionDescriptor) {

        static TrustedPolicyResult available(String policyText, ContextBinding binding,
                                             ContextOutcome outcome,
                                             MaterializationLocation location,
                                             WorkspaceDescriptor executionDescriptor) {
            return new TrustedPolicyResult(ContextOutcome.AVAILABLE,
                    Objects.requireNonNull(policyText, "policyText"),
                    Objects.requireNonNull(binding, "binding"), location,
                    Objects.requireNonNull(executionDescriptor, "executionDescriptor"));
        }

        static TrustedPolicyResult terminal(ContextOutcome outcome) {
            if (outcome == ContextOutcome.AVAILABLE) {
                throw new IllegalArgumentException("Terminal result requires a non-AVAILABLE outcome");
            }
            return new TrustedPolicyResult(outcome, null, null, null, null);
        }

        public boolean isTerminal() {
            return outcome != ContextOutcome.AVAILABLE;
        }

        /**
         * Whether the trusted context includes a materialization binding
         * with a verified ready location.
         */
        public boolean hasMaterialization() {
            return outcome == ContextOutcome.AVAILABLE
                    && binding != null
                    && binding.selectionKind() == SelectionKind.MATERIALIZATION
                    && materializationLocation != null;
        }

        /**
         * Whether the trusted context uses a personal selection binding.
         */
        public boolean hasPersonalSelection() {
            return outcome == ContextOutcome.AVAILABLE
                    && binding != null
                    && binding.selectionKind() == SelectionKind.PERSONAL
                    && binding.personalSelection() != null;
        }

        public PersonalSelection personalSelection() {
            return hasPersonalSelection() ? binding.personalSelection() : null;
        }

    }
}
