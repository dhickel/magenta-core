# Phase 02 Final Independent Validation

## Scope

Independent validation of `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-02-run-lifecycle-fencing.md` at commit `3b7012cb655a3bc49e7c3e332625909c358da699` after senior-agent repair.

## Findings

1. **BLOCKER — assignment API responses expose lifecycle lease secrets.** `AgentOrchestrationController` and other public controllers return `WorkAssignment` directly. `WorkAssignment` has serializable `leaseOwner` and `leaseFence` record components with no response projection or Jackson exclusion. This conflicts with the phase/API contract forbidding exposure of lease owners and fence values.
2. **BLOCKER — a post-claim runner failure path leaks the writer claim.** `OrchestrationRunnerService.executeWithLease` acquires the writer claim, then performs project-membership validation before entering the `try` that calls `finishWriterForResult`. If membership is rejected (including membership changing after submission), the assignment is failed but the writer attempt/claim remains current. Exceptions during context construction in this same post-acquisition/pre-`try` region can likewise bypass writer closure.
3. Full `mvn test` passed: 671 tests, 0 failures, 0 errors, 0 skipped.
4. Bounded startup against a fresh temporary Magenta root reached `Started Magenta2Application` and shut down at the 30-second timeout. Default-root startup failed before readiness because the local existing database has incompatible `schema_migrations` constraints; this is environmental/existing-root evidence that should be reconciled separately and was not used as the primary Phase 02 blocker.
5. Runner lifecycle provisioning uses `TaskRunLifecycleService.provisionLocation`, which resolves/provisions through `WorkspaceDirectoryService`. The normal terminal path completes, waits, fails, interrupts, or releases current claims. `OrchestrationTaskContext` carries `WorkspaceDescriptor`, `TaskRunLocation`, `RunOutputLocation`, and `TaskRunWriteCapability` trusted types.
6. Operation uniqueness is scoped by `(operation_type, operation_key)`, generated submission keys are unique, and resume/retry/replay/child key namespaces are distinct in observed call sites. Same-key digest conflict handling is implemented transactionally.
7. Diff scope is large but aligned with Phase 02 lifecycle integration, affected mutation boundaries, schema, tests, specifications, and docs; no unrelated feature scope creep was identified.

## Risk Assessment

The API leak discloses active lease owner/fence material contrary to an explicit security boundary. The post-claim early-return path can leave a current writer claim after assignment failure, preventing safe resume and violating single-current-writer lifecycle closure. Both violate Phase 02 exit criteria.

## Recommendations

- Introduce secret-free assignment response DTOs/projections for every public route, or explicitly suppress internal lease fields at serialization boundaries, and add MockMvc/ObjectMapper negative assertions for `leaseOwner`, `leaseFence`, capability/token/path fields.
- Move all work after writer acquisition inside one `try/finally` ownership scope. Ensure every return and exception closes the claim with the appropriate attempt state; add a runner integration test for post-acquisition project-membership rejection and context/setup failure.
- Re-run all 671 tests and fresh bounded startup after repair. Separately reconcile the default local database migration-table incompatibility.

## Follow-ups

Dependent phases remain blocked until both blockers are repaired and independently revalidated.

**VERDICT: BLOCKED**
