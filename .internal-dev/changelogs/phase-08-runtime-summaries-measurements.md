## Date

2026-07-20

## Git Commit

01c4e347261b6d6d9e00709d5d9166ecf7d80f91

## Change Summary

Implemented truthful runtime summaries and typed invocation measurements layered on Phase 02/04/07 foundation. Added rebuildable summary cache, model/tool measurement repository, health aggregates, audit composition, scope-authorized REST APIs, and HTMX-first UI fragments.

## Files

### New
- `src/main/resources/db/migrations/007-runtime-summaries-measurements.sql`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunMeasurementRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunSummaryService.java`
- `src/main/java/io/mindspice/magenta2/api/web/RuntimeHealthFragments.java`

### Modified
- `src/main/resources/schema.sql`
- `src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java`
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`

## Behavioral Impact

- Model and tool calls are now instrumented at the shared execution boundary: one measurement row reserved before, completed after
- Runtime summary cache is rebuildable from source facts; run_status is normalized to 8-way read model
- Health reads expose explicit numerator/denominator, median/P95 elapsed, and separate active/unavailable counts
- Audit composition crosses skills, artifacts, and schedules through exact persisted identifiers only
- New REST API endpoints under /api/runtime for summaries, history, health, measurements, and audit
- HTMX fragments for health panels, bounded history, measurements, and audit expansion

## Specification Impact

- New tables run_summaries and run_measurements added to schema.sql and migration 007
- RuntimeController expanded with scope-authorized endpoints
- RunSummaryService and RunMeasurementRepository added to service graph

## Risks

- Measurement instrumentation is non-blocking; recorder failures are logged and leave incomplete state. A high rate of recorder failures could lead to incomplete measurement data.
- Summary rebuild performance depends on measurement count; very large runs may need pagination in future.

## Follow-up Items

- Phase 09 consumes summary/measurement facts for schedule/reaction causation links
- Phase 10 integrates health/history fragments into the operational UI
