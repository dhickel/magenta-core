# Phase 06 Project Automation and Events Review

## Scope

Post-phase validation of revision heads, project attribution, CRON/ONE_TIME behavior, trusted event matching, simulation purity, migration/fresh-schema equivalence, and HTMX-only project automation fragments.

## Findings

- Repaired the fresh-schema/migration mismatch: `schema.sql` now declares the v19 composite revision keys, pointer/mode/project columns, and indexes. The runner conditionally adds `orchestration_events.project_id` before migration 019 so Spring-initialized fresh schemas do not fail with a duplicate-column error.
- Repaired revision history mutation: schedule/reaction updates and deletes append revisions; delete produces a disabled tombstone revision and advances the sole `current_revision` pointer.
- Repaired project-attribution bypasses: non-project APIs reject client project attribution, project creation generates ids server-side, project dispatch/poll rechecks current active membership, and automation templates cannot override executor/project/workspace/lifecycle attribution.
- Repaired mode and event defects: modes reject incompatible field combinations, one-time terminal slots do not repeat across later revisions, schedule-due events retain the trusted project id, and reaction simulation no longer queries persistence.
- Project automation uses HTMX component fragments and introduces no JavaScript transport or project-specific scheduler/event engine.

## Risk Assessment

Migration 019 intentionally rebuilds schedule/reaction tables and remains destructive for development-phase definitions/audits. No data-preservation claim is made. Multi-process scheduler topology remains outside this phase.

## Recommendations

Retain the migration resource unchanged after it is applied. Keep project-scoped automation mutations behind server-derived project/executor facts and preserve the pure simulation boundary.

## Follow-ups

- Phase 08 owns broader documentation integration.
- Focused Playwright evidence was not produced in this validator session because no delegated browser/subagent workflow is available in the current harness.
