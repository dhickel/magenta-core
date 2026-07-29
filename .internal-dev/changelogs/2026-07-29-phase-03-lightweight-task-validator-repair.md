# Phase 03 Lightweight Task Validator Repair

## Date

2026-07-29

## Change Summary

Repaired Phase 03 lightweight-task persistence, provenance, operation idempotency, designation, and HTMX host handling after post-phase review.

## Files

- `WorkTaskService`, `WorkTaskRepository`, `WorkTaskDefinition`, `WorkTaskAttempt`
- Assignment request/record/service/repository persistence
- Project task controller and SimplyPages fragments
- `schema.sql`, migration runner, and migration `016-lightweight-work-task-integrity.sql`
- Task and project-console tests
- `SchemaMigrationRunnerTest` fresh-through-016 regression coverage

## Behavioral Impact

Task submissions now preserve the Plan as executable source and Work Task as separate immutable origin. Attempts append and atomically become the designated display/retry attempt. Task and Plan edits remain independent after promotion. All public Task mutations require operation keys and project task controls fail closed when trusted deployment capability is disabled. Submit/retry operation replays return their exact persisted attempt even after later retries. Fresh schemas now install migration 016's SQLite promoted-Plan delete guard before its integrity validation, so clean application startup does not fail.

## Specification Impact

None. This repairs the accepted Phase 03 contract. Migration 016 is an additive correction because migration 015 was already applied and checksum-protected.

## Risks

Phase 04's planned migration number must be updated before that work starts. Browser validation remains pending because the required subagent workflow was unavailable in this session; no focused Playwright evidence was produced.

## Follow-up Items

Run the required focused Playwright Tasks-tab validation and screenshots; resolve Phase 04 migration sequencing before Phase 04 implementation.
