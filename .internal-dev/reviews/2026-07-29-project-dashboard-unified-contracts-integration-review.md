# Project Dashboard and Unified Work Contracts Integration Review

## Scope

Reviewed cross-phase assignment authority, agent/project Queue overlap, Work Task → Plan → Assignment lineage, project scope checks, HTMX host isolation, and fresh-runtime wiring for the completed dashboard sprint.

## Findings

- Repaired a submission capability bypass: `ProjectOperationsAccessService.requireEligibleSubmission` now requires the server-owned trusted-deployment capability before accepting a project-attributed submission.
- Agent and project queues consume the shared `AssignmentQueryService` under sealed scopes. Project transcripts use durable assignment-conversation links only.
- Work Tasks submit a promoted Plan as `PLAN/<id>` and retain `WORK_TASK/<id>` as immutable origin; retries append attempts through assignment lineage.
- No whole-row assignment upsert remains. The sole assignment write outside `OrchestrationRuntimeRepository` is the intentional job-reassignment guarded CAS revoke, which atomically revoke-and-clones instead of retargeting attribution.
- Project fragment responses retain domain hosts; project tabs target `#project-tab-panel`, while Queue, Tasks, Files, Chat, and automation use their own subordinate hosts/OOB targets.

## Risk Assessment

The repaired capability gate is fail-closed. Existing integration tests that intentionally submit project work now explicitly enable that capability in setup. Known deferred issues remain outside this review: migration 017 warm-DB checksum drift, chat durable turn protocol/proposal workflow, and Files-tab 503 HTMX behavior.

## Recommendations

Keep project-attributed submissions routed through `requireEligibleSubmission`; do not recreate membership-only checks at callers.

## Follow-ups

None for the repaired integration path.
