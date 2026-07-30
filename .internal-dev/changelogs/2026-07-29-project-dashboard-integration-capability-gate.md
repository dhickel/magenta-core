# Project Dashboard Integration Capability Gate

## Date

2026-07-29

## Change Summary

Closed a project-attributed submission bypass by enforcing the existing trusted-deployment capability at the shared eligibility gate.

## Files

- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectOperationsAccessService.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentAuthorityFoundationTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerLifecycleIntegrationTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/PublicApiRouteBindingTest.java`

## Behavioral Impact

Project-attributed submissions now fail closed unless trusted deployment is explicitly enabled, then still require an active project and current member executor.

## Specification Impact

None. This restores the existing Phase 01 service contract that every sensitive project entry point enforces the server-owned capability decision.

## Risks

Integration fixtures that intentionally submit project work must explicitly enable the capability.

## Follow-up Items

None.
