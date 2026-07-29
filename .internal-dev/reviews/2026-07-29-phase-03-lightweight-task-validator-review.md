# Phase 03 Lightweight Task Validator Review

## Scope

Post-phase review and repair of Lightweight Project Tasks: task/Plan separation, assignment provenance, attempt lineage, migration safety, REST DTO redaction, and project-console HTMX fragments.

## Findings

- Repaired the submitted assignment model so executable source is `PLAN/<planId>` and immutable submission origin is `WORK_TASK/<taskId>`. `WORK_TASK` is no longer a second executable assignment type.
- Repaired operation-key handling so create, edit, bind, promote, submit, and retry use separate persisted operation types. Public mutations require operation keys. Submit/retry replays now resolve their persisted attempt result rather than incorrectly returning a later designated attempt.
- Added a persisted designated-attempt pointer, atomically updated with append, and made status/retry resolution use that pointer.
- Restored the already-applied immutable migration 015 bytes after startup exposed checksum drift. Added migration 016 as the additive corrective upgrade for origin, designated attempt, operation-table shape, active-origin index, and promoted-Plan deletion guard.
- Repaired fresh startup: fresh schema initialization marks migrations applied and therefore skips the migration-016 trigger-body path. The runner now performs the idempotent v16 repair during final latest-version reconciliation, before validation, with a fresh-through-016 regression test.
- Repaired post-promotion Task edits so they remain independent of Plan edits.
- Repaired project fragment host targeting and operation-key form fields; standard mutations remain HTMX-only and SimplyPages-built.

## Risk Assessment

Migration 016 occupies the numeric slot previously reserved by the future Phase 04 plan. Phase 04 must choose its next migration number in its own approved implementation update; migration 015 itself remains checksum-stable.

`mvn test` passed (1,158 tests), and a bounded application startup using a new temporary SQLite root reached `Started Magenta2Application` after fresh schema initialization. Focused browser/Playwright evidence was not produced: this validator environment has no subagent execution tool, and project policy prohibits inline Playwright execution.

## Recommendations

- Run the required subagent-owned Playwright desktop/mobile pass against the Tasks tab before declaring browser validation complete.
- Renumber the future Phase 04 migration when that phase is authorized, preserving the applied 016 checksum.

## Follow-ups

- Phase 04 migration sequencing review.
- Focused Tasks-tab Playwright validation and screenshots.
