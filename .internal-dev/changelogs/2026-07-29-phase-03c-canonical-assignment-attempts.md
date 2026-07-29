## Date
2026-07-29

## Change Summary
Subphase C of Phase 03 (Lightweight Project Tasks): Canonical Assignment Attempts and Transport Surface.

- Added `WORK_TASK` to `AssignmentType` enum
- Extended `AssignmentService` to handle `WORK_TASK` assignments (lifecycle, source derivation, lineage)
- Extended `OrchestrationRunnerService` to route `WORK_TASK` to `runTask`
- Extended `OrchestrationController` to support `WORK_TASK` in submission UI and entity resolution
- Added `WorkTaskService.submit()`: validates executor, project/member state, executable Plan; submits via `AssignmentService.create()` with `WORK_TASK` type, `sourceDefinitionId="WORK_TASK/<taskId>"`, immutable project attribution, and copied task priority
- Added `WorkTaskService.submitOperation()`: operation-key idempotency with canonical-digest for submit
- Added `WorkTaskService.retry()`: validates terminal state of latest attempt; creates lineage successor via `AssignmentService.retry()`; appends new attempt with predecessor linkage; never mutates predecessor
- Added `WorkTaskService.retryOperation()`: operation-key idempotency for retry
- Added `WorkTaskRepository.findActiveAttempt()` and `countActiveAttempts()` for active-assignment detection
- Created `WorkTaskController` at `/api/work/tasks` with global REST endpoints:
  - `GET /api/work/tasks` — list all tasks
  - `GET /api/work/tasks/{taskId}` — task detail with attempts
  - `POST /api/work/tasks` — create task
  - `PUT /api/work/tasks/{taskId}` — edit task (CAS)
  - `POST /api/work/tasks/{taskId}/promote` — promote to Plan
  - `POST /api/work/tasks/{taskId}/submit` — submit for execution
  - `POST /api/work/tasks/{taskId}/retry` — retry after terminal
- Added DTOs: `WorkTaskCreateRequest`, `WorkTaskEditRequest`, `PromoteRequest`, `WorkTaskSubmitRequest`, `RetryRequest`, `WorkTaskSummary`, `WorkTaskDetail`, `WorkTaskAttemptSummary`
- Added 16 new unit tests covering submit validation, submit/retry through stub AssignmentService, operation idempotency, active attempt queries

## Files
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentType.java` — added WORK_TASK
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java` — WORK_TASK handling
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentTemplateParser.java` — WORK_TASK validation
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java` — WORK_TASK routing
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java` — WORK_TASK UI support
- `src/main/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskService.java` — submit, retry, operation idempotency
- `src/main/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskRepository.java` — active attempt queries
- `src/main/java/io/mindspice/magenta2/api/web/WorkTaskController.java` — NEW REST controller with DTOs
- `src/test/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskServiceTest.java` — 16 new tests + StubAssignmentService

## Behavioral Impact
- Work tasks can now be submitted for execution through the canonical `AssignmentService` workflow
- Submissions use `WORK_TASK` assignment type with `sourceDefinitionId="WORK_TASK/<taskId>"` for the active-origin uniqueness guard
- `/api/work/tasks` is an additive surface; existing `/api/tasks` unchanged
- Project-scoped task endpoints (Phase 03D) are anticipated but not yet mounted
- Submit idempotency: duplicate operations within the same task + agent + runDisplayName digest replay the recorded attempt
- Retry only allowed when the designated attempt's assignment is terminal

## Specification Impact
Additive work within existing Phase 03 subphase. No spec drift introduced. Project-scoped fragments deferred to Phase 03D.

## Risks
- `WORK_TASK` assignment type is new; all existing switch/if-else chains on `AssignmentType` have been updated
- The `work_task_operations` table uses `'SUBMIT'` as operation_type for both submit and retry; the digest payload prefixes distinguish them
- StubAssignmentService in tests uses manual DB inserts; real integration test coverage for the full submission lifecycle (through real AssignmentService with AgentContextService) should follow in Phase 03D

## Follow-up Items
- Phase 03D: Project-scoped task fragments under `/api/projects/{projectId}/tasks`
- Integration test using fully-wired AssignmentService for submit/retry lifecycle
