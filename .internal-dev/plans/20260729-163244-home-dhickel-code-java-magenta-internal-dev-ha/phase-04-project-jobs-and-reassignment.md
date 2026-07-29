# Phase 04 — Project Jobs and Reassignment

## Context

`WorkAssignment` remains the authority for submitted-work identity, immutable attribution, queue state, priority, lifecycle, lease, and ownership. Public Job behavior uses runtime Job definitions/runs only when the Phase 01 authority evidence confirms that boundary. A reusable definition never mirrors an individual run. Reassignment revoke-and-clones an eligible unstarted assignment and never mutates its immutable agent attribution.

## Goal

establish Job field authority and atomic queued reassignment; dependency is migration/shared-console sequencing, not Work Task behavior

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobDefinition.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobRun.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobReassignmentService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/TaskRunLifecycleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java, src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java, src/main/resources/db/migrations/016-job-reassignment-operations.sql, src/main/resources/schema.sql

Enforce singular public Job authority, native-run linkage, future-only preferred-agent behavior, atomic queued reassignment, and bounded project-console controls through the existing seam. Add focused test coverage without expanding canonical production targets.

## Out of Scope

Job-table consolidation; `ai.agent.job.AgentJob` migration; running-work transfer; automatic destination selection; in-place agent mutation; predecessor workspace/artifact copying; new fragment, CSS, or JavaScript files; generic Job APIs; Task-definition changes. If migration discovery requires an unlisted production edit, escalate the ledger conflict.

## Dependencies

phase-03-lightweight-project-tasks.md

## Constraints

- `owner_agent_id` is the sole persisted preferred executor; contradictory aliases reject, and preferences never infer fallback execution.
- Assignment owns assignment fields, JobRun owns native identity/state/result, and JobDefinition owns reusable definition fields. No whole-row assignment update or cross-owner write is allowed.
- Reassignment requires a trusted server-bound caller, active authorized project, eligible current destination member, and queued/nonterminal/unleased/no-JobRun source at its expected version.
- Operation key/digest bind server-defined actor, action, scope, source, destination, expected version, and canonical semantics. Same binding/key/digest replays; changed semantics conflict.
- Source attribution and predecessor workspace, artifacts, outputs, lease, checkpoint, result, and cancellation state are never copied or rewritten.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/agent/job/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- When the store is ready: `.internal-dev/knowledge/run-lifecycle-idempotency-and-fencing.md` and `.internal-dev/knowledge/stuck-running-assignment-recovery.md`

## Implementation Steps

1. **Establish Job authority.** Inspect the Phase 01 matrix, repository SQL, Job service, runner paths, and `AgentJob` boundary. If evidence contradicts runtime `JobDefinition`/`JobRun` through `JobService`/`JobRepository` as the sole public model, stop the affected capability for escalation. Remove runner writes to definition running/terminal state. Test field writers. Add definition-only revision/CAS behavior; parse aliases at the boundary, reject contradictions, persist only `owner_agent_id`, and apply it only to later submissions.
2. **Make links and reads structural.** Enforce one JobRun per assignment, including concurrent allocation. Run/detail/lineage queries begin with mandatory server-owned `ProjectScope` and immutable assignment attribution. Definition views require a proven persisted definition-project relation. Use bounded order/cursors and canonical scoped re-resolution. DTOs exclude raw input/output, evidence, unrestricted errors, paths, lease/fence/checkpoint data, and secrets.
3. **Add guarded revocation/fencing.** Implement only a narrow expected-version revoke for queued, nonterminal, unleased sources with no JobRun and proven writer quiescence. Preserve source attribution and audit. `TaskRunLifecycleService` and `OrchestrationRunnerService` reject revoked or stale-fenced work before JobRun allocation and before native state/result commit. If quiescence cannot be proven, deny reassignment for that state.
4. **Add migration 016.** Add operation persistence and fresh-schema equivalent for trusted idempotency identity/digest, scope, expected version, source/destination, successor, lineage, terminal outcome, and bounded failure class. Add unique/foreign-key/index protections for one successful successor and unambiguous lineage. Verify existing migration discovery, checksum, fresh/warm, partial-structure, and metadata-equivalence coverage; do not modify unlisted migration machinery.
5. **Implement revoke-and-clone.** `JobReassignmentService` first resolves replay by trusted binding/digest. In one transaction, gate/revalidate caller, project, source, and destination; CAS-revoke; capture a newly validated destination workspace/context; submit a fresh successor with source `JOB/<id>` and origin `REASSIGNMENT/<operation>`; persist lineage and terminal outcome. Commit all together. A response-loss replay returns the committed result; pre-commit failure leaves no partial reassignment. Never implement `withAgent` or transfer leased/running/native work.
6. **Apply verified Task provenance conditionally.** When a predecessor proves Work Task origin, invoke the Phase 03 published transactional participant in that same transaction to append/designate the successor attempt. Preserve predecessor attempt and historic project binding; do not alter Job source or copy Task state. Without provenance, do not query/mutate Tasks. If the participant cannot join atomically, stop this conditional capability rather than add a Task writer.
7. **Use the existing console seam.** Extend only `ProjectConsoleController` and existing rendering infrastructure for authorized bounded definition/run list/detail, future-only preferred-agent update, and reassignment controls. Use HTMX and isolated existing Job hosts; add no JavaScript. Pass only server-computed scope, IDs, expected versions, action availability, redacted summaries, and exact-link safe counts. Re-resolve every action and render disabled archive, membership, capability, conflict, stale, unavailable, and no-destination states safely.
8. **Prove boundaries.** Test transactional failure injection, replay, digest conflict, no multiple successor, no committed revoked source without resolution, expected-version conflict, old-worker fencing, unique JobRun link, and predecessor retention. Cover leased/running/terminal/native-run, archive/gate/cross-scope/member/destination/alias failures. Verify definition state is not run-written, preference is future-only, and Task/non-Task lineage follows step 6.

## Technical Guidance

`JobService` is the public definition writer after the evidence gate; `JobRepository` persists Job facts; assignment/lifecycle/runner services retain their own fields. `JobReassignmentService` coordinates them transactionally but is not a generic assignment or Task writer. Use exact durable IDs: source identifies Job definition, origin identifies reassignment, and optional Task provenance remains separate. Inspect relevant SimplyPages docs/demos before console work; if unavailable, stop UI edits rather than using raw markup or broad JavaScript.

## Validation

After all steps complete, run focused repository/service/lifecycle/runner/reassignment/controller tests with transaction failure injection and concurrent allocation/reassignment cases; migration fresh/warm/checksum/partial-structure/metadata-equivalence checks; applicable Maven/static checks; and enabled/disabled bounded startup. Use the required Playwright subagent workflow on the running app for Job detail, preferred-agent update, reassignment, replay/conflict/error/empty/disabled states, HTMX isolation, deep links, keyboard/focus/ARIA, desktop/mobile screenshots, and visual review of alignment, density, hierarchy, wrapping, overflow, and first viewport. Confirm no new JavaScript transport. Post-phase validator review-and-repair must PASS.

## Exit Criteria

- Public Job authority is singular and `AgentJob` is excluded.
- Definition, JobRun, and assignment state have disjoint writers; assignment-to-JobRun linkage is unique.
- Scoped bounded reads/actions are redacted and canonically re-resolved.
- Preferred-agent persistence is `owner_agent_id` only and affects only later submissions.
- Reassignment is gated, expected-version fenced, idempotent, atomic or explicitly recoverable through its durable operation, lineage-complete, and cannot commit a revoked predecessor without resolution.
- Ineligible lifecycle, scope, archive, membership, gate, and destination cases fail closed; predecessor effects remain historical.
- Verified Task provenance is atomically linked; absent provenance has no Task mutation.
- Required focused, migration, broader, startup, browser, and validator checks PASS.
