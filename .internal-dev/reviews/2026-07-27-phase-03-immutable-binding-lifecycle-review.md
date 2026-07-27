# Phase 03 Immutable Binding Lifecycle Review

## Scope

Validated `.internal-dev/plans/20260727-195154-home-dhickel-code-java-magenta-internal-dev-ha/phase-03-immutable-binding-lifecycle.md` against the declared write targets and Phase 02 context-binding contracts.

## Findings

1. **Blocking — binding capture is optional.** `AssignmentService.captureAssignmentContextBinding()` returns when no default exists and catches capture/link failures; the assignment is already persisted at that point. `RequestResolver` originally skipped capture unless `contextCapture` was nonblank. Therefore new owners can exist without a durable binding.
2. **Blocking — lifecycle idempotency does not include binding facts or converge before mutation.** `AssignmentService.create()` persists an assignment and captures a binding before `TaskRunLifecycleService.reserve()` discovers an existing operation key. The losing assignment is then deleted, leaving an immutable capture/owner-link orphan. Its canonical digest omits the binding facts required by the phase contract.
3. **Blocking — runner does not validate owner links or captured state/generation.** `OrchestrationRunnerService.executeWithLease()` reads `contextBindingKey` from mutable assignment checkpoint JSON, provisions before acquiring a writer claim, and never verifies matching assignment/task-run owner links or `AgentContextService.BindingResolution`.
4. **Blocking — lineage/child links are incomplete.** Retry/replay read the mutable checkpoint before considering the durable owner link, silently continue when source binding data is absent, and assume every source binding is materialization-backed. Workflow and job children only copy a string in `OrchestrationTaskContext`; they create neither their own binding owner link nor a `TASK_RUN` binding link.
5. **Blocking — required focused coverage is absent.** No tests cover concurrent first chat turns, default-CAS/default-change behavior, stale/revoked/non-ready binding failure without filesystem mutation, retry compatibility after default change, assignment/run owner-link matching, or child owner links.
6. **Fixed during review — undeclared competing persistence removed.** The implementation added `context_binding_key` by repository-local `ALTER TABLE` in `ChatSessionMetadataRepository`. This conflicts with the Phase 02 migration-owner contract and duplicates the authoritative `binding_owner_links` model. The validator removed that column/API and made conversation lookup use `CONVERSATION` owner links.

## Risk Assessment

High. The current code can execute a run with no immutable association or with a stale/revoked materialization. Retrying an operation key can leave orphan immutable records, and mutable checkpoint JSON is treated as binding authority.

## Recommendations

Rework assignment submission as one transactional operation-key decision: inspect the existing operation first; otherwise capture/link the binding, reserve the run, and persist the assignment/result atomically. Make missing or invalid binding state fail closed. At runner startup require matching assignment and task-run owner links, resolve the binding with typed state/generation validation, acquire the writer fence before provisioning, and source context only from the owner link. Add durable owner links for workflow-child and job-work-item records and the required race/stale/lineage tests.

## Follow-ups

- Validation: focused legacy lifecycle tests pass; `mvn test` passes (895 tests); bounded Spring startup reached `Started` before timeout.
- **Verdict: BLOCKED.**
