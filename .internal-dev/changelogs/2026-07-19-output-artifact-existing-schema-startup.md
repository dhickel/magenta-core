# Date

2026-07-19

# Change Summary

Prevented Spring SQL initialization from creating the new `run_output_artifacts.workspace_identity` index before an existing legacy table has received that column. `OutputArtifactRepository`, which owns artifact schema compatibility, now remains responsible for creating the index after adding missing columns. Updated the schema migration test to verify that ownership boundary.

# Files

- `src/main/resources/schema.sql`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepositorySchemaMigrationTest.java`

# Behavioral Impact

Existing databases with a legacy `run_output_artifacts` table can start cleanly; fresh databases still receive the workspace-identity index when `OutputArtifactRepository` initializes.

# Specification Impact

Specification Impact: none. The repair aligns startup ordering with the existing intended contract that `OutputArtifactRepository` owns artifact schema and compatibility handling.

# Risks

Phase 05 remains blocked by broader stale execution and output contracts documented in the final revalidation review.

# Follow-up Items

Complete the remaining Phase 05 job, workflow, output, project, and web cutover before Phase 06.

# Git Commit

`eddde09f2f48d040330e403b2aba76e75be57b2d` (working-tree repair is not committed because the phase gate remains blocked and the branch contains extensive pre-existing uncommitted phase work).

## Git Commit

eddde09f2f48d040330e403b2aba76e75be57b2d
