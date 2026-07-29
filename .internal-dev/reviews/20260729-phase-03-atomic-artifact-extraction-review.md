# Phase 03 Atomic Artifact Extraction Review

## Scope

Validated `ArtifactExtractionRepository` synchronization and its `ArtifactSearchService` integration: document/FTS insert, stable-id update, scoped removal, rollback, and FTS-unavailable behavior.

## Findings

- PASS: extraction-document and external-content FTS DML are owned by `ArtifactExtractionRepository` and run in transactional synchronization/removal methods.
- PASS: updates delete FTS tokens with the prior stable id and prior indexed values before replacing the document.
- Fixed: persistence faults were previously caught by the broad extraction-work catch and returned as a failed extraction result. Persistence now propagates after rollback; file/extraction failures remain separately represented and persisted.
- PASS: a file-backed SQLite fixture with a transaction proxy and separate verification connection proves insert/update/removal rollback state.
- PASS: a probe-failing JDBC fixture proves unavailable search remains unavailable while extraction documents persist without FTS rows or a search fallback scan.

## Risk Assessment

Low for this phase. FTS-specific assertions ran on the available SQLite FTS5 runtime. `TaskRunRetentionService` direct extraction-document deletion remains the known, explicitly out-of-scope follow-up recorded in the plan and advanced plan review.

## Recommendations

Proceed to dependent Phase 05. Keep all future extraction/FTS mutations in `ArtifactExtractionRepository`; do not restore ordinary FTS `DELETE` DML or log-and-continue persistence handling.

## Follow-ups

- Separately authorize the existing `TaskRunRetentionService` extraction-document deletion cleanup.
