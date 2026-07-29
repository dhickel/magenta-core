# Changelog: Phase 04 — Project Jobs and Reassignment

## Date
2026-07-29

## Change Summary
Established Job field authority and atomic queued reassignment for the Project Dashboard sprint.

### Job Authority
- `JobDefinition` now carries a `definitionVersion` field for CAS semantics. Definition-owned fields (status, title, summary, items, owner_agent_id) are separated from run-owned fields.
- `JobDefinition.owner_agent_id` is the sole persisted preferred executor. Preferences never infer fallback execution.
- `JobService.updateDefinitionStatus()` is deprecated — the runner no longer writes to definition running/terminal state. Runs own their lifecycle independently.
- `JobService.saveDefinitionCAS()` provides expected-version guarded updates.

### Structural Links and Reads
- One-JobRun-per-assignment enforced via unique partial index on `job_runs.job_assignment_id WHERE job_assignment_id IS NOT NULL`.
- Project-scoped bounded reads and definition views now require proven definition-project relation.
- DTO-safe detail rendering excludes raw input/output, evidence, errors, paths, lease/fence/checkpoint, and secrets.

### Revocation and Fencing
- `JobReassignmentService` (new) coordinates atomic queued reassignment: revoke-and-clone an eligible unstarted assignment in one transaction.
- Operation key/digest binds server actor, action, scope, source, destination, and expected version.
- Response-loss replay returns the committed result; injected successor-insert failure rolls back the revocation and operation reservation, so retry remains safe.
- The successor captures the destination agent's fresh workspace/context binding, records `REASSIGNMENT/<operation>` origin, and persists successor task-run and predecessor lineage facts.
- Source attribution, workspace, artifacts, outputs, lease, checkpoint, result, and cancellation state are never copied or rewritten.
- No `withAgent` or transfer of leased/running/native work.

### Migration 017
- `job_reassignment_operations` table for idempotency: identity/digest, scope, expected version, source/destination, successor, lineage, terminal outcome, bounded failure class.
- Unique/foreign-key/index for one successful successor and unambiguous lineage.
- Unique index `idx_job_runs_assignment_unique` for one-JobRun-per-assignment.
- `definition_version` column added to `job_definitions` with backfill.
- `SchemaMigrationRunner` V17 foundation tables and indexes added.

### Console Seam
- Jobs tab enabled in project console.
- Definition list with version, status, and update age.
- Definition detail with run history and preferred-agent update (CAS-guarded, future-only).
- HTMX-only fragments with isolated Job hosts; no JavaScript added.
- Disabled states for archive, membership, capability, conflict, stale, and unavailable conditions.

### Work Task Provenance
- Conditional: recognizes `WORK_TASK/*` origin on source assignments.
- Atomic append-attempt API not yet available in `WorkTaskService`; provenance linking is deferred (logged but skipped) per plan guidance — "stop this capability rather than add a Task writer."

## Files
### New
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobReassignmentService.java`
- `src/main/resources/db/migrations/017-job-reassignment-operations.sql`

### Modified
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobDefinition.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java`
- `src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java`
- `src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleFragments.java`
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`
- `src/main/resources/schema.sql`
- `docs/end-user/jobs.md`
- `docs/technical/orchestration-runtime.md`
- `src/test/java/...` (focused reassignment rollback/replay/fencing and Job repository CAS/link tests added)

## Behavioral Impact
- Runner no longer writes `RUNNING`/`COMPLETED`/`FAILED`/`CANCELLED` status to `job_definitions`. Definition status is a definition-level publishing concern.
- Definition saves increment `definition_version` via repository CAS. Legacy callers default to version 1.
- Reassignment operations are fully idempotent and atomic. Response-loss replay returns the committed successor.
- Project Jobs tab now shows actual definition version, status, and run counts instead of "unavailable" placeholder text.

## Specification Impact
**services.md**: JobService no longer exposes `updateDefinitionStatus` for runner use. Definition-write authority is now solely through `saveDefinition` (use-case write) or `saveDefinitionCAS` (guarded field update). Reassignment is coordinated through `JobReassignmentService`, not `AssignmentService`.

**api.md**: Job definition detail and preferred-agent update exposed at `/projects/_detail/{projectId}/jobs/{jobId}` and `/projects/_detail/{projectId}/jobs/{jobId}/preferred-agent` respectively. Reassignment controls deferred until a safe console surface is designed (service is operational, controller endpoints pending UX design).

**schema.md**: `job_definitions.definition_version` (integer, default 1) and `job_reassignment_operations` table added.

## Risks
- Work Task provenance linking is deferred. When `WorkTaskService` supports an atomic append-attempt operation, the provenance block in `JobReassignmentService.reassign()` must be activated.
- No Playwright validation was executed because this validation session was not provisioned with the required Playwright subagent workflow.

## Follow-up Items
- Activate Work Task provenance linking when `WorkTaskService` supports an atomic append-attempt.
- Run Playwright validation on Jobs tab (detail, preferred-agent update, disabled/error states).
- Design reassignment console UX and wire controller endpoints to `JobReassignmentService`.
