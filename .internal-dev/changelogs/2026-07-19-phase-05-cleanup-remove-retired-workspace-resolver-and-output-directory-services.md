# Phase 05 Cleanup: Remove Retired Workspace Resolver and Output Directory Services

## Date

2026-07-19

## Git Commit

0cb4aa81fea4f2380f8481953f9bd3533a7dfe8d

## Change Summary

Removed the previously identified injections and call sites for retired `EffectiveWorkspaceResolver`, `OutputDirectoryService`, and deprecated workspace-ID-based API surfaces. Final revalidation also corrected the chat-plan package guide and removed stale unused legacy imports. This cleanup does not complete Phase 05: separate active tool, runtime-context, output-publication, and retired-service test contracts remain documented in `.internal-dev/reviews/2026-07-19-phase-05-final-revalidation.md`.

### Removed from production code
- **PlanService**: Removed unused `EffectiveWorkspaceResolver` and `OutputDirectoryService` field injections, constructor parameters, and imports. Collapsed the 7-arg constructor chain (which only existed to thread `EffectiveWorkspaceResolver`) into the existing 6-arg chain.
- **JobService**: Removed unused `EffectiveWorkspaceResolver`, `OutputDirectoryService`, and `WorkspaceService` field injections, constructor parameters, and imports. Collapsed the constructor chain: removed the 5-arg `EffectiveWorkspaceResolver` overload; kept a 5-arg `OrchestrationRuntimeRepository` overload for test use.
- **OrchestrationController**: Replaced `workspaceService.agentWorkspace(agent.id(), null)` in `agentWorkspaceHealth()` with `workspaceDirectoryServiceRef.getIfAvailable().agentWorkspace(agent.id())`.
- **OutputArtifactQuery**: Removed two `@Deprecated` static factory methods that accepted a single `workspaceId` parameter (8-arg and 11-arg overloads). All callers already used the 10-arg or 13-arg non-deprecated signatures.
- **WorkAssignment**: Removed three `@Deprecated` constructors that accepted a single `workspaceId` parameter. All test callers updated to use the non-deprecated constructors with explicit `workspaceType`/`workspaceKey`/`workspaceIdentity`.
- **WorkflowRunner.TaskNodeExecutor**: Removed the `String workspacePath` parameter from the functional interface and its call site. Test lambdas updated to 3-parameter signatures.

### Updated in documentation
- **ai/chat/plan/AGENTS.md**: Replaced reference to retired `workspaceId` compatibility metadata with `workspaceType`/`workspaceKey`/`workspaceIdentity`.

### Updated in tests
- **OrchestrationRuntimeTest**: Removed `EffectiveWorkspaceResolver` instantiation and import. Updated 30+ `WorkAssignment` constructor calls from deprecated single-`workspaceId` to explicit triple. Changed `JobService` constructor calls from `EffectiveWorkspaceResolver` to `(OrchestrationRuntimeRepository) null`.
- **AgentOrchestrationControllerTest**: Updated deprecated `WorkAssignment` constructor calls.
- **OrchestrationControllerTest**: Updated deprecated `WorkAssignment` constructor calls.
- **TaskStreamSupportTest**: Updated deprecated `WorkAssignment` constructor calls.
- **OutputArtifactServiceAttributionTest**: Updated deprecated 8-arg and 11-arg `OutputArtifactQuery.of()` calls to 10-arg and 13-arg non-deprecated signatures.
- **WorkflowRunnerTest**: Updated lambda implementations to 3-parameter `TaskNodeExecutor` interface. Removed unused `EffectiveWorkspaceResolver` import.
- **PlanServiceTest**: Removed unused `EffectiveWorkspaceResolver` import.

## Files

### Production (7 files)
- `src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/WorkAssignment.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunner.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactQuery.java`
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md`

### Tests (7 files)
- `src/test/java/io/mindspice/magenta2/ai/chat/plan/PlanServiceTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/OrchestrationRuntimeTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunnerTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactServiceAttributionTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/AgentOrchestrationControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/TaskStreamSupportTest.java`

## Behavioral Impact

None. All removed services were already unused in their respective production classes (field injection only, never called). The deprecated API surfaces were already unused by production callers; test callers were migrated to the non-deprecated equivalents. All 876 tests pass.

## Specification Impact

Specification Impact: none — this is a cleanup of deprecated/retired internal API surfaces with no behavioral change. The workspace identity model (workspaceType/workspaceKey/workspaceIdentity) was already the active specification; this change only removes the deprecated compatibility shims.

## Risks

The edits in this changelog are low-risk because the removed paths were unused injections, deprecated constructors/methods without production callers, or migrated test usage. Phase 05 overall remains blocked, however: final stale-reference review found active multi-root tool/runtime contracts, retired output discovery/promotion APIs, and tests that still consume retired output-routing services.

## Follow-up Items

- Complete the Phase 05 descriptor-only runtime/tool cutover.
- Remove retired loose discovery, copied-temp, and run-local output promotion behavior and tests.
- Remove or rewrite remaining tests of `OutputDirectoryService` and `EffectiveWorkspaceResolver`.
- Repeat final revalidation; see `.internal-dev/reviews/2026-07-19-phase-05-final-revalidation.md`.
