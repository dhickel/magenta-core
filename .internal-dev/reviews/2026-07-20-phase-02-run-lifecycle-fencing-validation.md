# Phase 02 Run Lifecycle and Fencing Revalidation

## Scope
Revalidated `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-02-run-lifecycle-fencing.md` after the claimed repair pass. Inspected the actual runner, lifecycle, plan, workflow, job, assignment, context, migration, and test paths; repaired the Phase-02-caused `SchemaMigrationRunnerTest` fixture drift; ran focused tests, the complete Maven suite, and bounded fresh startup.

## Findings

### Blocking
1. **Runner reservations violate the Phase 01 native-ID cardinality contract and fail open.** `OrchestrationRunnerService.executeWithLease` reserves `leased.id()` as both task-run and native-run identity before a native plan/workflow/job run exists. `work_assignments.id` must remain separate. The runner catches every lifecycle failure and continues unfenced (`OrchestrationRunnerService.java:210-234`). Downstream plan/workflow/job services then allocate different native run IDs and reserve again, producing competing lifecycle ownership rather than one reserved canonical native identity.
2. **Writer fencing is not connected to actual filesystem or terminal mutation boundaries.** Production has one writer acquisition call but no production call to `verifyWriterClaimCurrent`, `completeAttempt`, `failAttempt`, or `releaseWriterClaim`. The runner does not provision the persisted run root. File/shell/output mutation services do not verify the capability. A successful execution therefore leaves a current writer claim indefinitely; stale writers are not stopped at actual mutation boundaries.
3. **Trusted execution context is still incomplete and host-path-authoritative.** `OrchestrationTaskContext` added lifecycle fields but still has `hostDurableWorkspacePath`, lacks the required containing `WorkspaceDescriptor`, derives a host path from `TaskRunLocation`, and receives the attempt number converted to a string rather than a persisted attempt identity. `runOutputLocation` is always null in the runner.
4. **Resume/retry/replay are not integrated in production.** There are no production call sites for `TaskRunLifecycleService.resume`, `retry`, or `replay`. Workflow approval resume only updates the native `workflow_runs` row and marks the inbox message handled (`WorkflowRunner.prepareResume`); it does not create a lifecycle attempt or higher writer fence. No public operator retry/replay path returns lineage-linked lifecycle results.
5. **Operation-key ownership is incorrect despite no longer embedding generated run IDs.** Plan/workflow/job keys are deterministic hashes of definition and inputs. This makes two intentional submissions with identical inputs converge forever instead of giving each submission its own persisted key while allowing retries of that submission to converge. The runner uses a different assignment-derived key, so one execution can reserve twice. Public submission payload/results do not own, persist, or return the required stable operation key and lifecycle IDs.
6. **Workflow-child and job-work-item links are not written.** Production reservation calls do not pass `workflowChildId` or `jobWorkItemId`; repository search found no production lifecycle link writer beyond the generic insert helper. Child native runs are not proven to have explicit sibling lifecycle locations.
7. **Assignment fencing remains incomplete.** `AssignmentService.save` is private and normal runner saves now compare the carried fence, but unfenced repository mutation APIs remain public. The operator force-interrupt path still calls `forceInterruptAssignment` without expected owner/fence. Heartbeat zero-row results are ignored, so a stale runner is not told to stop. Lease takeover/recovery and all cancellation/release paths are not end-to-end fenced as required.
8. **The six claimed “real execution tests” are lifecycle-service fixtures, not real runner executions.** `TaskRunLifecycleExecutionTest` uses one `SingleConnectionDataSource`, manually creates directories with `Files.createDirectories`, writes without capability verification, and never runs `OrchestrationRunnerService`, plan/workflow/job execution, public submission, restart recovery, data-root relocation, or real concurrent SQLite connections. Its “concurrent reservations” test is sequential. Required failure-injection and real AGENT/USER initial/resume/retry/replay campaigns remain absent.
9. **Lifecycle concurrency/recovery semantics are not demonstrated.** Same-key operations that encounter an existing `RESERVED` row throw `IllegalStateException` instead of waiting/loading a completed result or exposing a recoverable in-progress outcome. There is no crash-boundary reconciliation for reservation/provision/claim, and startup recovery does not reconcile current writer claims.
10. **Phase closeout is incomplete.** No phase commit exists and the plan remains active. The living service/API contracts do not describe an activated Phase 02 lifecycle result contract, which is appropriate while the implementation remains blocked.

### Repaired During Revalidation
- The five `SchemaMigrationRunnerTest` failures were introduced by Phase 02 test-fixture drift, not pre-existing Phase 01 failures. Tests still supplied only migration 001 while `schema.sql` represented the migration-002 end state, and assertions/inserts expected v1 tables and columns.
- Updated `SchemaMigrationRunnerTest` to run migrations 001+002, assert both bookkeeping rows, assert replacement of `task_run_writer_claims` by `writer_claim_generations`, use scoped operation columns, and compare migration-002-owned tables/indexes in fresh-versus-upgraded metadata.
- Focused `SchemaMigrationRunnerTest`: PASS, 9 tests.
- Full Maven suite after repair: PASS, 669 tests, 0 failures/errors/skips.

## Risk Assessment
**Critical / phase-blocking.** The lifecycle primitives and broad suite pass, but actual execution can continue after lifecycle failure, aliases assignment IDs as native run IDs, does not provision or guard run-file mutations, never closes writer claims, and does not implement resume/retry/replay or child linkage. Passing service-level fixtures do not protect against duplicate native runs, split-brain writers, stale filesystem mutation, or unrecoverable crash boundaries.

## Recommendations
- Do not start dependent phases or archive/commit Phase 02 as complete.
- Establish one submission-owned persisted operation key and reserve the canonical native run identity before dispatch; remove duplicate reservation from nested runner/native services.
- Make lifecycle failure fail closed. Provision the persisted location and acquire the writer only after the assignment lease; verify both capabilities at every mutation; terminally complete/fail/release claims.
- Carry `WorkspaceDescriptor`, a real attempt identity, `TaskRunLocation`, `RunOutputLocation`, and capability; remove host-path authority.
- Integrate workflow approval resume and operator retry/replay with lifecycle commands, and write explicit workflow-child/job-work-item links.
- Remove or fence every legacy mutation path and make heartbeat/checkpoint zero-row outcomes stop the stale worker.
- Replace the six fixture tests with real runner/native-family, independent-connection concurrency, relocation, restart/fault-injection, and stale-file-writer tests.

## Validation
- `mvn -q -Dtest=SchemaMigrationRunnerTest test`: PASS (9 tests).
- Focused lifecycle/runtime/job/workflow/runner/controller suite: PASS.
- `mvn -q test`: PASS (669 tests).
- Fresh bounded Spring startup with an isolated Magenta root: PASS (`Started Magenta2Application` in 3.374 seconds; process then ended by the 30-second bound).
- Required real AGENT/USER resume/retry/replay, crash-boundary, relocation, stale-filesystem, workflow-child, and job-work-item campaigns: NOT PRESENT / NOT RUN.

## Corrective Revalidation
The senior repair removed assignment/native aliasing and duplicate nested reservation. `AssignmentService` now reserves a generated native run id and persisted operation key before dispatch; `PlanService`, `WorkflowRunner`, and `JobService` consume the matching trusted context id. Runner lifecycle setup provisions the recorded root, acquires a writer, carries the containing descriptor and run-output location, verifies assignment/writer capabilities, and closes the claim for every outcome. Setup and stale capability failures stop execution.

Workflow approval resume opens a new attempt. Operator retry/replay routes allocate new lineage-linked assignments/runs. Workflow task nodes and job work items use typed `task_run_links`, sibling roots, and child writer claims. Force interruption and stale-lease recovery revoke linked writers. File, shell, output, checkpoint, cancellation, and terminal paths verify current capabilities.

Validation evidence:
- Focused lifecycle, migration, fencing, workflow, job, tool, output, and controller suites: PASS.
- `OrchestrationRunnerLifecycleIntegrationTest`: PASS with actual Spring wiring, assignment lease, submission reservation, empty native job execution, root provisioning, terminal writer closure, and native status truth.
- Full Maven suite: PASS, 671 tests, 0 failures/errors/skips.
- Bounded isolated startup: PASS (`Started Magenta2Application` in 4.205 seconds; timeout exit 124 after the successful bound).
- Final diff/check: PASS; no assignment-id/native-id alias, fail-open lifecycle catch, deterministic submission key, current writer leak, absolute-path response, or public force-interrupt mutation remains.

## Follow-ups
- Phase 03 owns process confinement after shell preflight.
- Phase 04 owns run-local artifact publication.

## Verdict
**VERDICT: PASS**
