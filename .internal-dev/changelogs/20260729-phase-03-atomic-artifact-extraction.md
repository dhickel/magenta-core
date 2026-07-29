# Phase 03 Atomic Artifact Extraction

## Date

2026-07-29

## Change Summary

Added transactional extraction-document and external-content FTS synchronization, including atomic scoped removal and focused real-SQLite rollback coverage.

## Files

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactExtractionRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactSearchService.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactAccessSearchRetentionTest.java`
- `.internal-dev/reviews/20260729-phase-03-atomic-artifact-extraction-review.md`

## Behavioral Impact

FTS-capable extraction document and index updates/removals now commit together or roll back together. FTS-unavailable deployments retain extraction documents and return unavailable search without fallback scanning.

## Specification Impact

Specification Impact: none. This implements the existing artifact search and repository ownership contracts without changing service, API, web, or product behavior.

## Risks

The known direct extraction-document deletion in `TaskRunRetentionService` remains explicitly out of scope for Phase 03.

## Follow-up Items

Authorize a dedicated retention-deletion follow-up before moving that remaining direct DML into the repository boundary.
