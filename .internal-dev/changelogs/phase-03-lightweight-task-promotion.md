## Date

2026-07-29

## Change Summary

Implemented Subphase B of Phase 03 (Lightweight Project Tasks): guarded task definition commands and operation-keyed explicit plan promotion. Added archive gating, pin enforcement, preferred-agent validation through Phase 01 contracts, and operation-key idempotency on all mutation commands (CREATE, EDIT, BIND, PROMOTE). The promotion flow now creates a minimal TASK_TEMPLATE PlanDefinition through PlanService in one guarded transaction rather than linking to a pre-existing plan id.

## Files

- `WorkTaskService.java`: enhanced create/edit with guard validations; added editOperation, bindProjectOperation, promoteOperation, submissionPreCheck; wired AgentProfileService, ProjectOperationsAccessService, and PlanService as optional dependencies; added archive guard (requireProjectNotArchived) on all mutation paths.
- `WorkTaskRepository.java`: added findCompletedOperation (returns taskId + resultJson), added findOperationDigest for digest conflict detection; added CompletedOperation record.
- `WorkTaskServiceTest.java`: 45 tests covering pin validation, preferred agent membership/eligibility, archive gating on create/edit/promote/bind/recordAttempt, operation-key idempotency for EDIT/BIND/PROMOTE, promoteOperation Plan creation and idempotency, submissionPreCheck, and independent task↔plan edits.

## Behavioral Impact

- **Create**: validates pin requires project binding; validates preferred agent is a project member (bound) or eligible/non-disabled (unbound); rejects creation for archived projects.
- **Edit**: archive guard rejects edits on tasks bound to archived projects; pin and preferred-agent are validated identically to create.
- **Bind project**: target project must be active (not archived); binding is immutable once attempts exist.
- **Promotion (promoteOperation)**: creates a minimal APPROVED TASK_TEMPLATE PlanDefinition through PlanService; sets promotedPlanId immutably; operation-keyed idempotency with digest conflict detection.
- **Promotion (promote, backward-compatible)**: now includes archive guard.
- **Record attempt**: now includes archive guard (rejects attempts on archived-project tasks).
- **Submission pre-check**: new submissionPreCheck method verifies task is promoted, Plan exists and is APPROVED, and project is not archived.
- **Idempotency**: EDIT, BIND, and PROMOTE operations use operation-key + canonical-digest pattern matching the CREATE pattern; same key/digest replays, different digest conflicts.
- **Independent edits**: tasks cannot be edited after promotion; plan edits (via PlanService) never touch task fields.

## Specification Impact

None — this is implementation of already-specified Phase 03 Subphase B contracts.

## Risks

- The `promote` method (direct-link, backward-compatible) bypasses Plan creation. Callers should migrate to `promoteOperation` for full behavior. The direct-link path does not validate the pre-existing plan id.
- `submissionPreCheck` depends on `planService.getTask()` which may throw if the plan has been deleted. Callers in Subphase C should handle this gracefully.
- `PlanService.saveTask()` is called within the promotion transaction. If `PlanService` later gains side effects, those would participate in the task promotion transaction.
- The `AgentProfileService` and `ProjectOperationsAccessService` are constructor-injected as `@Autowired(required = false)`. If unavailable, guard validations are silently skipped (not fail-closed). This is intentional for testability; production wiring should always provide them.

## Follow-up Items

- Subphase C: task submission, assignment creation from tasks, and full submission pre-check integration.
- The `promote` backward-compatible method should eventually be removed once all callers use `promoteOperation`.
