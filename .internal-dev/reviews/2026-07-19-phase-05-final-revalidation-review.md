# Scope

Final re-validation of Phase 05 — Cut Over Execution and Outputs against `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/phase-05-cut-over-execution-and-outputs.md`, including the full test suite, bounded existing- and fresh-root startup, filesystem inspection, stale-contract searches, and diff review.

# Findings

- **PASS:** `mvn test` completes with 891 tests, 0 failures, 0 errors, and 0 skipped after the startup compatibility repair.
- **PASS:** Existing-root and fresh-root bounded Spring startup both reach `Started Magenta2Application`; timeout exit 124 is expected after successful startup.
- **BLOCKING:** The Phase 05 typed descriptor cutover is still incomplete. A targeted phase-owned production scan finds 651 stale-contract matches in 48 files.
- **BLOCKING:** Job models, repository, and service still use `workspaceId`, `workspace_path`, `output_dir`, Work Area/output-route inputs, `EffectiveWorkspace`, and `OutputDirectoryService`. `JobRepository.ensureTables()` actively recreates obsolete columns.
- **BLOCKING:** Workflow run/model/repository/runner still persist and consume `workspace_path`, `output_dir`, and `workspace_id`, use run staging/output routing, and do not persist the exact typed selection required for WAITING resume.
- **BLOCKING:** Output persistence is not independent: `WorkspaceRepository` retains deprecated artifact delegates and `OutputArtifactService` retains a legacy `WorkspaceRepository` constructor. `OutputArtifactService` also retains loose discovery and copied-temp publication paths.
- **BLOCKING:** `OrchestrationController` retains active `workspaceId`, `outputRouteType`, generic workspace/Work Area selectors, project-root browsing, and filesystem lease views.
- **BLOCKING:** Active plan, workflow, job, project, web, and tool consumers remain for Phase 06 deletion targets including `EffectiveWorkspace`, `OutputDirectoryService`, `WorkspaceService`, `WorkspaceLeaseService`, and Work Area services.
- **BLOCKING:** Fresh-root filesystem inspection created `root/workspace/<uuid>/outputs`, an obsolete generic workspace root with eager outputs creation, rather than only a selected `workspace/agent|user/<key>` root and lazy `outputs/`.
- **BLOCKING:** Existing focused tests still assert stale contracts, including workflow `workspaceId` behavior and legacy absolute workflow paths; passing test count therefore does not establish the Phase 05 contract.

# Risk Assessment

High. Phase 06 must remain blocked because deleting obsolete workspace layers would break active job, workflow, project, output, and web consumers. The passing suite and clean startup mask active schema mutation and legacy filesystem behavior.

# Recommendations

1. Complete the job model/repository/service cutover to `workspaceType`, `workspaceKey`, and `workspaceIdentity`; remove persisted host paths and output routing.
2. Complete workflow run/repository/runner cutover, including exact typed WAITING selection and descriptor re-resolution on resume.
3. Remove artifact delegates and compatibility constructors from generic workspace persistence; delete loose discovery/copied-temp active paths.
4. Remove all active Phase 06 target consumers and old selectors from `OrchestrationController` and related submissions.
5. Add fresh-root execution/filesystem tests proving no generic UUID root and no eager `outputs/`, then repeat all validation.

# Follow-ups

- Verdict: BLOCKED.
- Phase 06 remains blocked.
- Current Git HEAD: `eddde09f2f48d040330e403b2aba76e75be57b2d`.
- Re-validator repaired existing-schema startup by moving creation of the new workspace-identity artifact index behind `OutputArtifactRepository` schema compatibility handling.
