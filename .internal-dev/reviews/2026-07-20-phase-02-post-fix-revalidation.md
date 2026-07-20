# Phase 02 Post-Fix Revalidation

## Scope

Final post-fix validation of `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-02-run-lifecycle-fencing.md`, focused on the two previously reported blockers, the full Maven suite, bounded startup, secret-free `WorkAssignment` serialization, and `OrchestrationRunnerService` writer-claim closure.

## Findings

1. **PASS — full Maven suite.** `mvn test` completed successfully with 671 tests, 0 failures, 0 errors, and 0 skipped. Surefire XML totals independently matched 671/0/0/0 across 91 reports.
2. **PASS — assignment lease secrets are excluded from JSON.** `WorkAssignment.leaseOwner` and `WorkAssignment.leaseFence` are annotated with `@JsonIgnore`. A runtime Jackson serialization probe populated both with sentinel secret values and confirmed neither property name nor value appeared in JSON.
3. **PASS — bounded fresh-root startup.** `timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0 --magenta.root.path=<fresh-temp-root>"` reached `Started Magenta2Application in 4.138 seconds`; timeout exit 124 then triggered graceful shutdown.
4. **PASS — the specific project-membership early return now closes the claim.** Membership validation is inside the main execution `try`, and its failure path transitions the assignment to `FAILED` then calls `finishWriterForResult` before returning.
5. **BLOCKER — writer acquisition is still not enclosed by one complete ownership scope.** In `OrchestrationRunnerService.executeWithLease`, the recovery branch acquires a writer at lines 242-243 and then performs checkpoint mutation at lines 244-249. Any runtime failure there is caught at lines 252-255 and returns a failed assignment without finishing or releasing the acquired writer. After normal acquisition, heartbeat scheduling and task-context/output-location construction at lines 259-271 still occur before the main `try` starts at line 273. An exception from scheduling or `WorkspaceDirectoryService.resolveRunOutput` exits with a current claim and no completion/failure/release. The phase contract requires writer claims to be closed at every post-acquisition exit point.
6. The stale-capability catch intentionally avoids ownership mutation and was not treated as the blocker; the concrete blocker is current writer claims leaking from ordinary post-acquisition setup failures.

## Risk Assessment

A failed recovery checkpoint save, rejected heartbeat schedule, or output-location/context setup failure can leave `writer_claim_generations.is_current` active after the runner exits. That violates the single-current-writer and deterministic recovery contract and can block or distort resume/recovery behavior.

## Recommendations

- Establish one outer ownership scope immediately after writer acquisition (including recovery acquisition), covering checkpoint persistence, heartbeat creation, output-location/context construction, membership validation, and dispatch.
- Track whether the writer was successfully terminalized; in failure cleanup, close the still-current owned claim with the appropriate failed/interrupted/released transition without touching stale ownership.
- Add injected-failure tests for recovery checkpoint persistence, heartbeat scheduling, and run-output/context setup after writer acquisition.
- Re-run the 671-test suite, serialization probe, and bounded startup after repair.

## Follow-ups

Dependent phases remain blocked until all post-acquisition setup exits demonstrably close or safely invalidate the writer claim.

**VERDICT: BLOCKED**
