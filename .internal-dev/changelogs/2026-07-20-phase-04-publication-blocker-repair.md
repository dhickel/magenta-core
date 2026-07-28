# Phase 04 Publication Blocker Repair

## Date

2026-07-20

## Git Commit

0dc7f866326c358a246b1f7585b15955f9f14103

## Change Summary

Repaired migration sequencing/validation and completed the run-isolated publication blockers. Migration 003 preserves legacy rows without task attribution or absolute publication paths. Publication staging is persisted before writes, collision reservations are transactional, task publication commits require matching writer owner/fence, reconciliation uses confined create-only operations, and byte reads verify the exact bytes returned. Active workflow output production no longer calls deprecated arbitrary-path materialization.

## Files

- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`
- `src/main/resources/db/migrations/003-artifact-publication.sql`
- `src/main/resources/schema.sql`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactReconciliationService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunner.java`
- focused migration/publication/workflow tests and technical documentation

## Behavioral Impact

Task outputs require trusted run-local locations and live writer capabilities. Standalone workflows without publication authority no longer write legacy workspace-root output artifacts. Chat publication remains typed and confined. Legacy artifact query compatibility remains, but publication DDL is migration-owned only.

## Specification Impact

Updated `architecture.md` and `services.md` to make ordered migration ownership, writer-guarded run publication, restart-safe staging, and current-integrity byte reads explicit.

## Risks

Existing databases with malformed non-empty migration bookkeeping or incompatible partial task-run structures still fail closed rather than being guessed or silently blessed.

## Follow-up Items

Phase 05 may build authorized artifact access over verified `PUBLISHED` records. Phase 07 owns trusted skill snapshot activation.
