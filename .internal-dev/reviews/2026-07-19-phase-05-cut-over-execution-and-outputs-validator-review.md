# Scope

Post-phase validation and in-scope repair for `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/phase-05-cut-over-execution-and-outputs.md` against the actual working tree.

# Findings

- **BLOCKING:** The phase implementation is incomplete across plan, workflow, job, tool, output, and web paths. A final stale-contract scan still finds old execution-routing names in 47 active files (400 matches when Phase 06-retained definitions are included).
- **BLOCKING:** `mvn test` still fails: 905 tests, 22 failures, 9 errors after validator repairs.
- **BLOCKING:** `WorkflowRepository` and `JobRepository` still create/read/write `workspace_id`; workflow/job models still carry old path and generic workspace fields. Repository initialization can therefore recreate obsolete columns on a fresh database even though `schema.sql` now declares the new fields.
- **BLOCKING:** `PlanService` and stale tests still exercise run/temp staging, output-directory persistence, project roots, Work Areas, and alias behavior removed by the Phase 05 contract.
- **BLOCKING:** `OrchestrationController` still contains active generic workspace selectors, compatibility workspace labels, and `workspaceId` template handling.
- **BLOCKING:** Output query/model compatibility overloads and runtime context compatibility constructors remain, contrary to the no-dual-contract criterion.
- **BLOCKING:** Several focused tests still assert superseded Work Area/project/run/temp behavior instead of the descriptor-first contract.
- Bounded clean Spring startup succeeds, but startup success is not sufficient because repository bootstrapping silently restores obsolete columns.

Validator repairs completed:

- Updated fresh `schema.sql` shapes for plan runs, workflow runs, work assignments, orchestration jobs, output artifacts, job definitions, and job runs to use `workspace_type`, `workspace_key`, and `workspace_identity`; removed fresh run/temp/output path and persistent-job-workspace columns in the changed tables.
- Corrected `PlanRepository` reads/writes and warm-schema additions to use typed workspace columns and stop requiring removed path columns.
- Corrected task/workflow stream context mapping to preserve `workspaceType` and `workspaceKey`.
- Updated the public route binding fixture and schema/output attribution assertions to the typed workspace contract.

# Risk Assessment

High. Phase 06 must remain blocked. Advancing would delete generic layers while active Phase 05 consumers still depend on them. The currently passing bounded startup masks schema drift because repository constructors add obsolete columns dynamically.

# Recommendations

1. Complete the workflow and job model/repository cutover as one coherent edit, including warm-schema deterministic agent derivation and ambiguous-row exclusion.
2. Remove plan run/temp staging and output-directory persistence from `PlanRun`/`PlanService`; publish only through the selected descriptor's lazy `outputs/`.
3. Remove all active `workspaceId`, Work Area, output-route, project-root, filesystem-lease, and compatibility selector consumers from Phase 05-owned code.
4. Rewrite or delete superseded-contract tests and add focused typed-selection, resume, confinement, direct-publication, and existing-schema instrumentation coverage.
5. Re-run `mvn test`, bounded startup, filesystem inspection, and stale-reference searches before a new validator gate.

# Follow-ups

- Phase 05 verdict remains BLOCKED.
- Phase 06 remains blocked.
- Current Git HEAD at validation: `eddde09f2f48d040330e403b2aba76e75be57b2d`.
