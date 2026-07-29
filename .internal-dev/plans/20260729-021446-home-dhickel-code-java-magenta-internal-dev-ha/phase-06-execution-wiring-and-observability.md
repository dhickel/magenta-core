## Context

Phase 04 has established the truthful HTTP-error boundary, and Phase 05 has removed production dependence on deprecated artifact reads. This phase hardens the active execution/workspace composition that now consumes those boundaries: production collaborators must be present, intentional indirection must be proven at startup, operational failures must not disappear, and workflow-runner tests must synchronize on observed events rather than polling or elapsed time.

## Goal

require execution collaborators and make workflow-runner tests deterministic

## In Scope

**Write Targets**: .internal-dev/plans/code-quality-sprint/implementation-inventory.md, src/main/java/io/mindspice/magenta2/core/config/RequiredProductionWiringConfiguration.java, src/main/java/io/mindspice/magenta2/ai/orchestration/agents/AgentProfileService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/TaskRunLifecycleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunner.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactReconciliationService.java, src/test/java/io/mindspice/magenta2/RequiredProductionWiringTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepositoryTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerLifecycleIntegrationTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunnerTest.java

- Replace production-only degradation in the listed execution, workspace, workflow, project, assignment, job, profile, output, and reconciliation paths.
- Add only the narrow production-wiring verifier and focused tests required by this phase.
- Treat existing non-Spring convenience constructors as test-only only when no production bean definition can select them; they must not supply a production fallback or redefine production behavior.
- Preserve Phase 04 endpoint-local typed statuses and Phase 05 canonical artifact access, authorization-before-byte-open, integrity checks, and separately named compatibility reads.

## Out of Scope

- Chat/web wiring and its inventory expansion; repository-wide optional-injection or catch cleanup; controller decomposition; route, payload, fragment, UI, JavaScript, schema-resource, ordered-migration, FTS-probing, scheduler-design, or cycle-redesign work.
- Changes to workspace identity, descriptor construction, path authority, task-run lifecycle semantics, create-only publication, reconciliation state meanings, or `DRIFT-20260720-01`.
- New test libraries or production abstractions beyond the narrow verifier and exact retained seams.

## Dependencies

phase-04-truthful-http-errors.md, phase-05-artifact-api-cutover.md

## Constraints

- Production-active collaborators are mandatory. Missing required collaborators must fail context creation rather than silently skipping execution, workspace, lifecycle, attribution, audit, or publication behavior.
- A provider is permitted only for a documented, currently proven construction cycle or an explicit specification-backed capability gate. Provider injection itself is not proof. Retained active paths retrieve the required object and cannot interpret absence as success, empty data, or no-op behavior.
- The inventory is bounded to the listed production sources and tests. Every retained provider, fallback, nullable branch, and catch in that inventory has a source anchor, classification, disposition, rationale, focused evidence, and completion status.
- Preserve test-only direct construction where needed, but do not annotate it as a production path or allow it to conceal a missing production bean.
- `WorkflowRepository` retains only its existing compatibility responsibility. It must inspect schema state before a compatibility alteration, keep ordered migrations and `schema.sql` unchanged, and propagate genuine DDL/data failures.
- Do not swallow an operational failure. Expected parse misses, stale/idempotent cleanup, capability probes, and explicitly unavailable legacy facts remain quiet only when their exact expected condition is recorded and tested. Logs and durable errors omit paths, raw content, credentials, owners, and fence values.
- `WorkflowRunnerTest` contains no fixed sleep, sleep polling, elapsed-time assertion, or unbounded wait. Every asynchronous assertion uses a bounded event-driven primitive with diagnostic failure, `finally` release/cleanup, and interruption preservation.

## Implementation Steps

1. Exclusively create `.internal-dev/plans/code-quality-sprint/implementation-inventory.md` before production edits. Record every optional injection, `ObjectProvider`, `getIfAvailable`, null fallback, nullable active dependency branch, and catch in the listed production targets. For each record, capture source anchor; caller/use case; production or test-only reachability; classification (`required`, proven cycle seam, specification-backed capability gate, expected control flow, typed propagation, or safe contextual logging); precise replacement/disposition; rationale; focused test; and status. Freeze this Phase 06 inventory, including the existing workflow repository compatibility catches and runner/reconciliation cleanup paths; do not add unlisted sites.

2. Convert named active execution/workspace collaborators to required production constructor dependencies and remove reachable null skips. Cover `AgentProfileService`, `AssignmentService`, `ProjectService`, `TaskRunLifecycleService`, `JobService`, `OrchestrationRunnerService`, `WorkflowRunner`, `OutputArtifactService`, and `ArtifactReconciliationService`. Use direct required dependencies where the graph permits it. Preserve descriptor-first execution, AGENT/USER selection, CHAT rejection, immutable binding checks, current writer checks, create-only output publication, and no-publication-without-capability behavior. Do not reconstruct workspace identity from a path or restore deprecated artifact calls.

3. Retain providers only at proven cycles, add a fixed verifier in `RequiredProductionWiringConfiguration` for those exact seams, and test missing/complete contexts. The inventory must name the cycle edge and the active operation that requires it. The verifier runs after singleton creation, requires each retained provider to resolve, and fails with an actionable bean/collaborator identity when it does not. Active code obtains the retained collaborator at its use boundary and propagates inability to obtain it as a domain-appropriate failure. Do not use a provider to avoid ordinary dependency construction or to preserve a nullable fallback.

4. In `WorkflowRepository`, inspect columns before compatibility alterations, propagate genuine DDL/data failures, and limit quiet optional-column handling to the documented missing-column case. Replace blanket `ALTER` catches with an inspected column set and issue only the alterations actually needed by the legacy table shape. Keep `create table if not exists` behavior and existing compatibility columns intact; do not add or modify SQL resources. Keep result-set optional-column handling only where a verified legacy projection can omit the column, and distinguish that expected `SQLException` from query, mapping, and data corruption failures.

5. In the runner, give writer release, stale cleanup, workspace resolution, lookup, and attribution-backfill catches explicit typed propagation or safe contextual logging that preserves the primary failure. A descriptor failure must retain its typed meaning until the existing assignment-failure boundary rather than returning an unclassified null. A stale lease/writer remains expected control flow only after its current-state check; release/cleanup failure cannot replace the primary lifecycle failure and must be safely recorded. Job/project lookup, attribution backfill, publication, and reconciliation paths may not convert repository or filesystem faults into absent metadata. Per-record reconciliation may transition only to the established safe unresolved/failed state after recording a secret-free contextual failure; task-owned publication state remains non-promotable without its current capability.

6. Replace workflow test sleeps/polling with direct synchronous assertions or bounded latches/futures. For ordinary execution, use the synchronous runner API and assert the persisted terminal or waiting state directly. For fan-out, make workers signal readiness, release them from a `finally` block, and assert overlap from the barrier/event evidence rather than elapsed time. Ensure worker interruptions restore the interrupt flag and turn failed waits/futures into diagnostic assertion failures. Remove the polling helpers and every `Thread.sleep`, fixed-delay retry loop, and timing threshold from `WorkflowRunnerTest`.

7. Complete inventory dispositions without adding unlisted sites. Re-scan the bounded sources for optional injection, providers, `getIfAvailable`, null degradation, empty/broad catches, and deprecated `OutputArtifactService` invocations. Reconcile every match to the inventory, verify that retained seams are the exact startup-checked seams, and record the completed evidence and any deliberately unchanged expected-control-flow path.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md
- src/main/java/io/mindspice/magenta2/core/AGENTS.md

## Technical Guidance

- Treat direct constructor injection as the production contract. A retained `ObjectProvider` must be named in the inventory with its exact dependency cycle or capability gate, verified after singleton initialization, and resolved with required semantics at an active use site.
- Keep the startup verifier narrow: it validates only the inventory-approved retained seams. It must not instantiate unrelated optional features, introduce a service locator, or convert a real cycle into broad lazy wiring.
- Compatibility DDL is idempotent through inspected schema state, not exception swallowing. Expected missing-result-column handling is narrower than “any `SQLException`”; DDL, update, query, JSON, and persistence failures propagate.
- When cleanup follows a primary failure, preserve the primary exception/result and attach or safely log cleanup failure without exposing paths, content, owners, or fences. When no primary failure exists, propagate the operational failure unless the inventory identifies a tested idempotent/stale outcome.
- Reconciliation may continue across independently persisted candidates, but each candidate’s catch must make one explicit safe-state decision. It must never treat a task-run record as publishable from recorded owner/fence facts alone.
- Use latches, futures, callbacks, barriers, or synchronous APIs to prove the asserted event. Bounded waits are diagnostic guards, not behavior assertions; no test asserts completion order from wall-clock measurement.

## Validation

- Run `RequiredProductionWiringTest` in Spring contexts that prove: a direct required dependency is absent and context creation fails; each inventory-approved provider seam resolves in the complete production graph; and the verifier rejects a missing retained provider. Confirm test-only constructors do not weaken those assertions.
- Run `WorkflowRepositoryTest` for a legacy table requiring each compatibility column, an already-current table requiring none, and an injected non-missing-column DDL/data failure that propagates. Verify no applied SQL resource or ordered migration changed.
- Run `OrchestrationRunnerLifecycleIntegrationTest` and focused assignment/job/lifecycle/publication/reconciliation/agent-profile tests. Cover task-run setup failure, stale writer/lease handling, immutable binding validation, writer closure, required workspace resolution, and attribution/reconciliation failure without a silent no-op or unsafe publication.
- Run `WorkflowRunnerTest` repeatedly with synchronous and barrier/latch assertions. Prove terminal completion, waiting/resume behavior, context propagation, standalone no-publication behavior, and real fan-out overlap. Verify the source has no fixed sleeps, sleep polling, elapsed-time threshold, or unbounded wait.
- Re-scan every inventoried production file for `@Autowired(required = false)`, `ObjectProvider`, `getIfAvailable`, nullable active-dependency fallbacks, empty catches, broad operational catches, and deprecated `OutputArtifactService` calls. Reconcile every result against the completed inventory.
- Run the affected Maven suites, the complete Maven suite against the established baseline, configured compile/package checks, and bounded ephemeral-port Spring Boot startup. Isolate only the exact known GitHub issue #41 ordering failure if reproduced; route every other failure through the project workflow.
- Review the diff and Git status for API/schema/UI drift, public-surface growth, weakened descriptor confinement or writer fencing, accidental provider expansion, sensitive logs, placeholders, and unrelated changes. No browser-visible behavior is authorized; unexpected browser-visible drift requires the repository Playwright subagent workflow.

## Exit Criteria

- The frozen inventory reconciles every bounded optional injection, provider, fallback, and catch with a tested disposition.
- Active production execution/workspace collaborators fail fast when absent. Every retained provider has a documented proven cycle/capability rationale, passes the post-singleton verifier, and is required at active use.
- `WorkflowRepository` performs inspected compatibility DDL only, and genuine DDL/data failures are observable.
- Runner, output, and reconciliation operational failures have explicit safe dispositions that preserve lifecycle, descriptor, integrity, authorization, and create-only publication invariants.
- `WorkflowRunnerTest` proves its asynchronous behavior through event-driven synchronization and contains no sleep/polling or elapsed-time assertions.
- Focused tests, production-context checks, source scans, broader validation, startup, diff review, and the post-phase validator review-and-repair gate all report PASS.
