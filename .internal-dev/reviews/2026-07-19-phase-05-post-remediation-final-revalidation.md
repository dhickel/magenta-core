# Scope

Final post-remediation re-validation of Phase 05 — Cut Over Execution and Outputs at Git HEAD `af32ef79a9337a1f925f91d8d00466c61a075095`, against the phase contract, concepts, remediation handoff, and prior blocked validator review.

# Findings

- **PASS:** `mvn test` completed with 876 tests, 0 failures, 0 errors, and 0 skipped.
- **PASS:** Bounded fresh-root startup reached `Started Magenta2Application`; timeout exit 124 was expected. The fresh root contained only `magenta.sqlite` and an empty `root/`. No UUID workspace root and no eager `outputs/` were created.
- **PASS:** Bounded startup against the same existing root reached `Started Magenta2Application` and left the filesystem tree unchanged.
- **PASS:** A representative existing-schema root containing obsolete `selected_work_area_id`, `workspace_path`, `output_dir`, and `workspace_id` columns plus a sentinel row started cleanly. The obsolete columns and sentinel remained intact.
- **PASS:** Fresh Phase 05-owned tables (`work_assignments`, `orchestration_jobs`, `plan_runs`, `workflow_runs`, `job_definitions`, `job_runs`, and `run_output_artifacts`) contain typed workspace columns and none of the searched retired routing columns.
- **BLOCKING:** Phase 05 active code still consumes Phase 06 deletion targets. `PlanService` injects `EffectiveWorkspaceResolver` and `OutputDirectoryService`; `JobService` injects those services plus `WorkspaceService`; `OrchestrationController` injects `WorkspaceService` and `WorkAreaService`, and calls `workspaceService.agentWorkspace(...)`.
- **BLOCKING:** The retained deletion-target classes are still active Spring providers (`EffectiveWorkspaceResolver`, `OutputDirectoryService`, `WorkspaceService`, `WorkspaceLeaseService`, `WorkAreaService`, and `WorkspaceRepository`), rather than dormant definitions with no providers/consumers.
- **BLOCKING:** `WorkspaceRepository` still exposes three backward-compatible output-artifact delegates. This violates independent artifact ownership and the explicit no-compatibility-overload remediation constraint.
- **BLOCKING:** `OutputArtifactQuery` retains deprecated `workspaceId` overloads, and `WorkAssignment` retains deprecated single-`workspaceId` constructors. These are active dual contracts forbidden by the phase contract.
- **BLOCKING:** `WorkflowRunner.TaskNodeExecutor` still accepts a host `workspacePath` string and is invoked with `descriptorRoot(run).toString()`, violating the path-free/single-descriptor execution contract.
- **BLOCKING:** `src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md` still states that project id selects a durable project workspace, `workspaceId` is compatibility metadata, and execution uses `runs/<runId>/outputs/`. This package guidance directly contradicts the governing Phase 05 contract.
- **BLOCKING:** Phase-owned tests still instantiate `EffectiveWorkspaceResolver` and `OutputDirectoryService`, so the passing suite continues to preserve retired behavior rather than proving there are no active consumers.

# Risk Assessment

High. Phase 06 remains unsafe because deletion of obsolete workspace/output-routing services and compatibility APIs would break active production consumers and tests. Startup and fresh schema behavior improved, but the required vertical contract and deletion safety are not complete.

# Recommendations

1. Remove all Phase 05 production consumers and Spring providers for `EffectiveWorkspaceResolver`, `OutputDirectoryService`, generic `WorkspaceService`, Work Area routing, and filesystem lease services.
2. Remove the output-artifact delegates from `WorkspaceRepository`; retain artifact behavior only in `OutputArtifactRepository`.
3. Remove deprecated `workspaceId` constructors/overloads from `WorkAssignment` and `OutputArtifactQuery`, updating all callers and tests to explicit type/key/identity.
4. Replace `WorkflowRunner.TaskNodeExecutor` host-path transport with descriptor-bound/path-free execution context.
5. Rewrite remaining tests that instantiate retired services and update the chat-plan package guide to the typed descriptor/direct lazy publication contract.
6. Repeat the complete suite, fresh/existing startup and filesystem inspection, classified stale scan, and final diff review.

# Follow-ups

- **Verdict: BLOCKED.**
- Phase 06 remains blocked.
- No production defects were edited during validation because the remaining findings form a broad, coupled vertical-cutover remediation; partial removal would leave the Spring graph and tests inconsistent.
