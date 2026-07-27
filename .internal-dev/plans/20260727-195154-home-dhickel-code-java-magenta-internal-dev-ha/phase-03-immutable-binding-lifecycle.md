# Phase 03 — Immutable Binding Lifecycle

## Context

Phase 02 persists versioned defaults, project materializations, immutable context bindings, root-relative assertions, and owner-link primitives. This phase adopts those facts at conversation and executable-work boundaries. A binding supplements the already selected workspace descriptor; it never becomes a workspace identity or path authority.

## Goal
capture and carry immutable context through conversations, assignments, runs, and lineage

## In Scope
**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/model/ChatRequest.java, src/main/java/io/mindspice/magenta2/ai/chat/service/RequestResolver.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/TaskRunLifecycleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunner.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java

## Out of Scope

Materialization creation, controllers, agent-console fragments, system-chat profile, and changes to the phase ledger. `CHAT` remains non-executable. No path, root, canonical workspace identity, materialization location, or other mutable context fact is accepted from a caller.

## Dependencies
phase-02-materialization-context-persistence.md

## Required Guides

- `AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/knowledge/run-lifecycle-idempotency-and-fencing.md`
- `.internal-dev/knowledge/task-workflow-schema-and-run-snapshots.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`

## Execution Preconditions

- Start only with Phase 02 validator PASS evidence. That evidence must show typed APIs for default resolution with expected-revision CAS, immutable binding capture and lookup, materialization state/generation validation, and transactional owner links.
- The Phase 02 owner-link API must link conversation, assignment, native task run, workflow child, and job work item to a binding without reconstructing identity from a path or writing mutable JSON. If it cannot, stop and request an orchestration-ledger correction; do not add undeclared persistence or checkpoint substitutes.
- One implementation agent owns every declared edit and the focused validation.

## Constraints

- Execution selection remains the existing paired `workspaceType` and `workspaceKey` rule: omission selects the bound agent, explicit `AGENT` must match it, existing `USER` may override it, and `CHAT` is rejected. Project id remains attribution and membership metadata only.
- A binding is insert-only. Default changes affect only new captures; they never retarget a conversation, assignment, run, resume, child, or existing idempotent operation. State or generation revalidation may reject use but never rewrites captured facts.
- Binding capture, owner-link creation, lifecycle reservation, and operation-result persistence are transactional where their consistency requires it. Filesystem provisioning occurs outside those transactions and only after a committed reservation.
- The canonical operation digest contains immutable binding facts plus the logical source, selection, lineage, and normalized input facts required by the operation. It excludes paths, roots, display values, timestamps, credentials, and transport ordering. Same operation type/key and compatible request converge; contradictory reuse conflicts without mutation.
- Assignment leases and task-run writer fences remain separate current capabilities. This phase verifies their conjunction where executable work starts.
- Missing, stale, revoked, non-ready, generation-mismatched, or ambiguous legacy context is a typed fail-closed outcome. Ambiguous legacy assignments and runs remain historical and non-resumable.

## Implementation Steps

1. Add a backward-compatible typed context-capture selection to new-chat requests only; it may name an allowed Phase 02 context/default selector and expected default revision, but never a path, root, canonical identity, materialization location, or caller-supplied context fact. Preserve existing `ChatRequest` construction with compatibility constructors. For assignments, consume the existing authoritative workspace pair and the dependency-owned typed capture API; do not alter `AssignmentRequest`, encode selection in `input`, or infer it from project metadata.
2. Resolve the agent identity server-side. For a first conversation, use the Phase 02 unique conversation/binding owner-link transaction rather than check-then-insert: capture the binding, create the conversation metadata/owner link, and return the single winner. For later turns, load that binding and reject any conflicting explicit capture selection; never resolve current defaults for an already bound conversation.
3. Replace path-derived chat setup with the resolved binding's trusted facts while retaining `workspace/chat/<conversationId>/` only for CHAT-local file behavior. The materialization binding is supplemental context and must not turn a chat root into an execution workspace or replace the selected execution descriptor.
4. At assignment submission, capture and persist an assignment owner link to one binding. Validate membership, the caller's applicable expected default revision, materialization readiness, and captured generation before queue dispatch. The durable link—not a checkpoint map, path, or current default—supplies the `contextBindingId` association for later lifecycle work.
5. Make operation-key convergence, binding capture, assignment insert, lifecycle reservation, and typed owner links one transactional decision. For a new key, resolve/capture once, construct the canonical digest, and persist the operation result. For an existing key, load its stored binding facts first, verify compatible retry intent against them, and return the recorded assignment/run; a different operation type, selection, source, lineage, or normalized input conflicts. Never create filesystem state in either transaction.
6. Link every native task-run reservation and lineage record to its exact assignment binding through the Phase 02 owner-link model. Runtime obtains materialization facts from that binding separately from the selected `WorkspaceDescriptor`; a binding must not replace `workspaceType`/`workspaceKey`, canonical descriptor identity, or trusted run-location construction.
7. On runner startup, require matching assignment and run binding links, revalidate binding state and generation, and resolve/provision only the recorded trusted task-run location. Acquire the current assignment lease and writer fence before model or tool work, recheck cancellation and capability state at mutation boundaries, and fail closed without provisioning when validation or acquisition fails.
8. Carry the immutable binding and selected descriptor as distinct typed facts in orchestration, plan, workflow, and job execution contexts. Remove authorization dependence on durable host workspace paths. File-tool calls receive only captured, descriptor-confined scope and current lifecycle capability; they do not receive a root reconstructed from a binding or a materialization location string.
9. Resume reuses the exact binding. Retry and replay copy the source binding's immutable source facts into a new binding and create explicit lineage links without consulting current defaults. Workflow children and job work items create explicit child owner links from their parent binding and likewise never read current defaults. Reject legacy source rows that lack one unambiguous binding/run association.

## Technical Guidance

Use a dependency-owned context boundary; lifecycle code must not recreate its persistence semantics. The transactional shape is:

```text
existing = findOperation(operationType, operationKey)
if existing exists:
    verifyCompatibleRetryIntent(request, existing.binding)
    return existing.recordedResult

binding = captureImmutableBinding(resolvedServerFacts, expectedDefaultRevision)
digest = canonicalDigest(binding, source, selection, lineage, normalizedInput)
persist(binding, ownerLinks, reservation, operationResult(digest))
```

The real implementation must make the lookup/insert race-safe with the dependency-owned unique operation key and must return the winning persisted result. It must not compare a retry against newly resolved defaults or treat a persisted root-relative assertion as a filesystem authority.

## Validation

- Add focused coverage for concurrent first turns: exactly one conversation binding/owner link wins, compatible repetition returns it, and conflicting explicit selection is rejected.
- Cover default CAS races and changes after capture. Existing conversations, assignments, runs, resumes, and children must retain their binding; stale revisions, non-ready materializations, revoked bindings, and stale generations cause no assignment, lifecycle, or filesystem mutation.
- Cover operation type/key convergence and contradiction with the stored binding, including retries after a default changes. Assert no duplicate binding, owner link, assignment, native run, or task-run reservation.
- Cover assignment-to-run matching, restart, cancellation, lease-plus-writer-fence acquisition, and runner failure before provisioning/model/tool work when a link, state, generation, lease, writer fence, or cancellation check is invalid.
- Cover resume reuse, retry/replay new binding, workflow-child and job-work-item owner links, and rejection of legacy rows lacking an unambiguous binding/run relationship.
- Run affected chat, runtime, plan, workflow, and job tests; the applicable Maven suite; bounded application startup; and focused static scans for caller-supplied paths/roots, path-derived authorization, and mutable-default rereads for existing owners. Record validator PASS.

## Exit Criteria

Every new conversation, assignment, native run, workflow child, and job work item has an immutable, durable binding association; resume, retry, replay, and idempotent repeats follow the specified reuse or new-snapshot rule. Runtime uses captured bindings plus the independently selected descriptor, never defaults or caller paths. Focused race, stale-state, lineage, lifecycle, and compatibility tests pass, application startup passes, and the post-phase validator records PASS without undeclared persistence.
