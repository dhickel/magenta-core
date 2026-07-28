# Phase 05+07 Test Failure Remediation

## Date

2026-07-20

## Git Commit

01c4e347261b6d6d9e00709d5d9166ecf7d80f91

## Change Summary

Reconciled Phase 05 artifact lineage, scoped artifact API binding, and ordered migration tests with the Phase 05+07 implementation. Corrected lineage cycle detection uncovered by the repaired fixture and removed duplicate migration-005 DDL from the fresh schema.

## Files

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactSearchService.java`
- `src/main/resources/schema.sql`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactAccessSearchRetentionTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/PublicApiRouteBindingTest.java`
- `src/test/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunnerTest.java`

## Behavioral Impact

Lineage insertion now rejects an edge when its prospective child is already an ancestor of its prospective parent. Public route coverage uses the authorized `/api/artifacts` workspace-scoped collection. Fresh and migrated schema equivalence now covers all six ordered migrations, including retention policy ownership.

## Specification Impact

None. The changes align tests and implementation with the existing scoped artifact API, migration ownership, and acyclic lineage contracts without changing intended product behavior.

## Risks

Low. The lineage argument-order correction is narrowly covered by cycle and duplicate-edge tests. Schema DDL was synchronized with migration 005 and exercised through fresh-schema and migrated-schema tests.

## Follow-up Items

None. `mvn test` passes 860 tests with zero failures and zero errors; bounded Spring Boot startup also succeeds.
