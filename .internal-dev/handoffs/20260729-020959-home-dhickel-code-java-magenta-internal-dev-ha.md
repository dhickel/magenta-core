# Code Quality Sprint — High-Level Implementation Handoff

## Context

Magenta has accumulated targeted correctness and maintainability risks during sustained feature delivery. Four independent audits converged on ten bounded issues that should be corrected before broad decomposition work.

The current code and living specifications confirm the principal risks:

- `WorkspaceExplorerService` stores mutation kind in `heldMutationKind` without a complete lifecycle boundary.
- `SystemChatService.invoke` is transactional across a remote model call.
- `ArtifactSearchService` mutates extraction-document and FTS state through separate statements without atomic persistence.
- `AgentContextController.agentExists` turns every runtime failure into “not found,” `ChatFileController` maps every `IOException` to 404, and `GlobalExceptionHandler` maps every `IllegalStateException` to 409.
- Production paths contain many `@Autowired(required = false)` dependencies and null-degradation branches despite active specifications requiring those capabilities.
- Production code contains empty or effectively silent catches, including repository schema compatibility paths, cleanup paths, fragment lookups, and transport handling.
- `OutputArtifactService` exposes deprecated compatibility methods still used by production controllers, services, and tools.
- `AgentJobServiceTest` and `WorkflowRunnerTest` rely on sleeps or polling sleeps.
- `PlanCompletionService`, `TrustedContextPolicyAssembler`, and `WorkspaceMdResolver` lack direct focused tests.

There is also a known contract conflict to preserve explicitly: `.internal-dev/specifications/api.md` marks workspace scoping for runtime run reads and related schedule/reaction reads as a blocking API/security drift. That work is not one of the accepted ten targets. It remains a separately tracked blocker, and completion of this handoff must not be represented as an API/security pass.

## Objective

Deliver a bounded correctness and quality improvement set that:

- prevents cross-operation state leakage and avoidable SQLite lock retention;
- preserves relational and FTS consistency;
- returns truthful HTTP statuses for absence, validation, conflicts, and infrastructure failures;
- fails fast when active production capabilities are not wired;
- makes operational failures observable without creating noisy or unsafe logs;
- removes production dependence on deprecated artifact APIs;
- makes asynchronous tests deterministic;
- adds direct regression coverage around plan completion and trusted-context boundaries;
- preserves existing product behavior, UI composition, persistence schema, and public payload shapes except for the accepted error-status corrections.

## Targets

### Production targets

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java`
  - Mutation-kind context lifecycle and reserved-tree authorization.
- `src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatService.java`
  - Transaction ownership around profile preparation and model invocation.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactSearchService.java`
  - Extraction-document and FTS synchronization.
- `src/main/java/io/mindspice/magenta2/api/web/AgentContextController.java`
- `src/main/java/io/mindspice/magenta2/api/web/ChatFileController.java`
- `src/main/java/io/mindspice/magenta2/api/web/GlobalExceptionHandler.java`
  - Typed and local HTTP error mapping.
- Active Spring-wired services/controllers containing `@Autowired(required = false)`, especially execution, workspace, plan completion, system-chat, and active web-host dependencies.
- Production empty/silent catches in the following bounded inventory: `WorkflowRepository`, `OrchestrationRunnerService`, system-chat services/controllers, operational fragments, and agent/project lookups.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java` and all production callers of its deprecated methods.

### Test targets

- `src/test/java/io/mindspice/magenta2/ai/agent/job/AgentJobServiceTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunnerTest.java`
- New direct tests for:
  - `PlanCompletionService`
  - `TrustedContextPolicyAssembler`
  - `WorkspaceMdResolver`
- Focused service, repository, controller, and application-context tests for every changed behavior.

### Contract and documentation targets

Implementation closeout must update affected API/technical documentation and the relevant living specifications if observed behavior changes or drift is clarified. It must also complete the project’s `.internal-dev` changelog, knowledge, bug, plan/review, archive, and commit workflow as applicable.

## Features

1. **Mutation context isolation**
   - Every workspace mutation begins with the correct workspace kind and ends with no thread-local mutation state, including success, validation rejection, primitive failure, and unexpected exception paths.
   - A mutation executed on a reused thread cannot inherit authorization context from an earlier operation.

2. **Short system-chat database transactions**
   - Profile/binding/conversation persistence completes atomically before remote inference.
   - No Spring transaction remains active while `ChatClient.call()` or equivalent remote model work is executing.
   - The fixed profile, tool restrictions, owner link, redaction, and fail-closed behavior remain unchanged.

3. **Atomic artifact extraction indexing**
   - On FTS-capable deployments, the extraction document and corresponding FTS row are updated as one database unit: both commit or both roll back.
   - FTS failures are not swallowed while leaving stale or missing index rows behind.
   - The existing “FTS unavailable” capability behavior remains supported without introducing a fallback scan.

4. **Truthful HTTP error mapping**
   - Agent absence or wrong scope maps to 404; context infrastructure unavailability maps to 503 rather than a false 404.
   - Chat-file validation maps to 400, an actually missing file maps to 404, size limits retain the applicable bounded-request status, and generic read/infrastructure failures do not masquerade as absence.
   - Known lifecycle/state conflicts map to 409 through typed or endpoint-local handling.
   - Unexpected `IllegalStateException` is not globally reclassified as a client conflict.
   - Error bodies/fragments remain useful and do not disclose sensitive paths or internals.

5. **Required production wiring**
   - Dependencies needed by active, reachable production contracts are mandatory in the production application context.
   - Optionality remains only for explicit capability gates or genuinely optional integrations, represented deliberately rather than by incidental null behavior.
   - Unit-test convenience constructors may remain where useful, but they must not weaken production Spring wiring.

6. **Observable exception handling**
   - Each catch in the bounded targeted inventory has an explicit disposition: typed propagation, bounded fallback, contextual logging, or documented expected control flow.
   - Empty catches that hide operational, persistence, cleanup, or rendering failures in that inventory are removed or replaced with the selected explicit disposition.
   - Expected parse misses, capability probes, stale/idempotent cleanup, and client disconnects may remain non-fatal, but their intent must be explicit and testable.
   - Logs must avoid secrets, model payloads, raw untrusted content, tokens, and absolute filesystem paths.

7. **Artifact API deprecation cleanup**
   - Production callers migrate to the canonical scoped artifact publication, access, preview, search, or repository boundary appropriate to the use case.
   - There are no production call sites to deprecated `OutputArtifactService` methods at sign-off.
   - Compatibility shims may remain deprecated only when required by retained compatibility tests or non-production callers; broad unsafe methods are not un-deprecated merely to silence warnings.
   - Legacy/non-task artifact behavior explicitly retained by the specifications continues to work.

8. **Deterministic asynchronous tests**
   - Fixed sleeps and sleep-based polling are replaced with latches, controlled executors, futures, callbacks, or bounded condition synchronization tied to the event being asserted.
   - Tests fail with a meaningful unmet-condition message rather than passing or failing based on machine speed.
   - Interrupted status is preserved where interruption is part of the exercised path.

9. **Direct plan-completion regression suite**
   - Cover deterministic evidence preflight, artifact collection/deduplication, readable and rejected artifact paths, clean validator request composition, strict response parsing, malformed/failed validator responses, fail-closed model resolution, validation feedback, and completed/incomplete status transitions.
   - Assert that approved plan facts, evidence, artifacts, prior feedback, and proposed final message are present while broad chat history is absent.
   - Assert use of the planning validator model path with no execution-model fallback.

10. **Direct trusted-context regression suite**
    - `TrustedContextPolicyAssembler`: cover AGENT/USER execution descriptors, CHAT rejection, all non-available terminal outcomes, personal and materialization bindings, generation/location facts, write authority derived only from the execution descriptor, read-only materialization semantics, and bounded output.
    - `WorkspaceMdResolver`: cover absent roots/files, materialization and personal-area resolution, provenance, truncation, oversize content, invalid UTF-8, null bytes, symlinked roots/files, non-regular files, confinement failure, and I/O failure.
    - Prove that absent optional guidance is empty, while integrity or I/O failures are terminal and never fall through to another root.

## Settled Decisions

- Correctness, transaction safety, and truthful failures take precedence over structural cleanup.
- The ten listed features are the complete implementation contract for this handoff; each must be independently covered by focused tests.
- `heldMutationKind` must have strict operation scoping and unconditional cleanup. Eliminating ambient thread-local state is preferred when it remains a local change; otherwise use one explicit `try/finally` lifecycle around each top-level mutation.
- The system-chat remote model call must execute outside a database transaction. Transactional preparation may remain, but it must commit before inference begins.
- FTS and extraction-document writes are atomic whenever FTS is active. Logging and continuing after one side fails is not acceptable.
- HTTP statuses will be based on typed domain meaning, not broad exception class or message text.
- Active, reachable production dependencies fail at application startup when absent. Optional integrations must be visibly and intentionally capability-gated.
- The implementation must first classify the targeted optional-injection sites and targeted silent catches. This is a bounded inventory, not authorization for a repository-wide cleanup campaign.
- Silent catches are assessed by semantics, not mechanically converted to warnings. Expected control-flow exceptions remain quiet only when explicitly justified; unexpected operational failures are propagated or logged with context.
- Production callers will migrate off deprecated artifact methods. The deprecated surface will not be normalized as the preferred API.
- Tests are added with each fix, not postponed into a separate coverage-only pass.
- No user-facing feature, UI redesign, schema migration, new route, new payload field, or general-purpose abstraction is introduced.
- The known workspace-scoping API/security drift remains out of scope and must remain visibly tracked as blocking work.

## Constraints

- Preserve the existing Spring Boot, Spring AI, SQLite, SimplyPages, and HTMX architecture.
- Preserve controller/service/repository ownership: controllers map transport, services own use cases, and repositories own persistence details.
- Do not hand-construct workspace roots, reconstruct identity from `Path`, or weaken descriptor, confinement, reserved-tree, lifecycle-fence, or authorization-first artifact contracts.
- Do not hold SQLite transactions across network/model calls.
- Do not add repository-local schema ownership or alter ordered migration resources.
- Do not change schema, route shapes, response payload shapes, UI layout, or JavaScript behavior.
- Limit API behavior changes to the documented error-status corrections in this handoff.
- Preserve fixed system-chat authority, callback allowlist, bounded reads, proposal-only mutation model, and secret-free output.
- Preserve FTS5 runtime probing and the no-fallback-search policy.
- Preserve legacy/non-task artifact behavior that remains an active compatibility contract.
- Maintain the current test baseline. The authoritative input reports 913 tests and one known pre-existing `ChatControllerTest` ordering flake tracked by GitHub issue #41; implementation must verify the current baseline rather than assume the count is unchanged.
- Follow all repository and package `AGENTS.md` instructions, including documentation, `.internal-dev` closeout, final diff review, and commit requirements.

## Scope

### In scope

- The ten features above.
- An implementation-start classification of only the targeted optional-injection sites and silent catches, with the selected disposition recorded in the relevant implementation records.
- Small supporting refactors required to create correct transaction, exception, dependency, and test seams.
- Focused tests for success, failure, concurrency/reuse, rollback, missing-bean, and security-boundary behavior.
- Status-code documentation and specification alignment for the accepted mappings.
- Removal of production use of deprecated artifact methods.

### Out of scope

- Workspace-scoping changes to runtime run endpoints, schedule firing-attempt reads, or reaction root-event reads; these remain a blocking tracked drift.
- Decomposition of `OrchestrationController`, `PlanService`, `ChatService`, or the wider `ai.chat.plan` package.
- Broad controller/service/repository boundary restoration.
- Centralizing all repository `ensureSchema()` methods or introducing Flyway/Liquibase.
- Repository-wide optional-injection or exception-catch cleanup beyond the bounded target inventory.
- JaCoCo setup, magic-role constant cleanup, configuration-properties consolidation, logger naming cleanup, broad dead-code removal, or unused-import campaigns.
- New features, routes, payload fields, schema migrations, UI changes, visual redesign, or generalized frameworks.
- Broad performance tuning unrelated to the identified transaction and synchronization defects.

## Assumptions

- The authoritative findings describe the intended work even where source line counts or test totals have changed.
- Existing typed domain exceptions/outcomes should be reused where they express the required status meaning; narrowly scoped new domain exceptions are acceptable when needed to avoid message-based mapping.
- “Production-required” is determined from active specifications, active routes, enabled application configuration, and actual runtime call paths—not from whether a test currently constructs a service with null.
- FTS-disabled operation is a supported capability state; partial synchronization on an FTS-enabled database is a defect.
- Expected SSE disconnects and numeric parse probes are not automatically defects, but unexplained swallowed operational exceptions in the bounded target inventory are.
- No Playwright pass is required because this handoff contains no UI/layout changes. If implementation unexpectedly changes browser-visible interaction behavior, that change is scope drift and requires the repository’s Playwright subagent workflow before sign-off.
- GitHub issue #41 is the only accepted pre-existing test exception unless current baseline evidence proves another unrelated failure and it is routed through the project workflow.

## Recommended Direction

- Establish focused regression tests around each observed defect before or alongside its correction, especially thread reuse, transaction activity at model invocation, atomic rollback, and HTTP status classification.
- Establish the bounded optional-injection and silent-catch inventories before edits. For each item, record whether it is production-required, an explicit capability gate, expected control flow, typed fallback, propagation, or contextual logging; do not expand the inventory without an approved scope revision.
- Keep transaction boundaries at persistence orchestration seams. Separate profile preparation from remote inference and extraction computation from the atomic database write without broad service decomposition.
- Prefer typed outcomes/exceptions and endpoint-local handlers over expanding `GlobalExceptionHandler` with more broad Java exception mappings.
- Tighten only production-required wiring in this scope; preserve explicit capability gates and avoid an unrelated constructor-cleanup campaign.
- Migrate artifact callers by use case rather than replacing one deprecated call with another wrapper: scoped reads use authorization-first artifact services, publication uses trusted publication APIs, and persistence queries stay behind the owning repository/service boundary.
- Use deterministic synchronization primitives already available in the JDK or project test stack; do not add a new test dependency unless repository evidence shows it is necessary.
- Keep new tests close to the boundary under test and include negative assertions proving the old unsafe side effect did not occur.

## Validation

### Focused automated validation

- Workspace mutation tests prove `heldMutationKind` is cleared after successful and failing operations on a reused thread and that reserved-tree policy uses the current descriptor only.
- System-chat tests prove profile persistence remains correct and no transaction is active at the remote model invocation boundary.
- Artifact extraction tests inject failures between document and FTS mutations and prove rollback leaves no partial state; success proves row-id/content synchronization.
- MockMvc/controller tests verify the status matrix for missing agents/files, invalid requests, conflicts, context unavailability, generic I/O failure, and unexpected illegal state.
- Application-context tests prove required production beans fail fast when absent and explicit optional capabilities still behave as configured.
- Exception-handling tests cover each bounded-inventory operational fallback/propagation path and verify no sensitive details enter responses or logs where practical.
- Compilation/static search confirms zero production invocations of deprecated `OutputArtifactService` methods.
- Async tests run repeatedly without fixed sleeps and assert completion through explicit synchronization.
- Direct suites cover all plan-completion and trusted-context cases listed under Features.

### Broader validation

- Run the complete Maven test suite and compare against the established baseline.
- If GitHub #41 reproduces, capture the exact failure and demonstrate that it is the known ordering issue; do not hide other failures behind that exception.
- Run the applicable lint, type/compile, and packaging checks configured by the repository.
- Run a bounded Spring Boot startup smoke test with an ephemeral port to validate tightened dependency wiring and transaction configuration.
- Review the final diff for unintended API/schema/UI changes, unrelated refactors, placeholders, new optional-null paths, unscoped artifact access, or weakened confinement.
- Verify documentation and living specifications match the corrected error semantics and that the separate runtime API scoping drift remains open. Do not represent this validation as an API/security pass.

### Acceptance criteria

- All ten features are implemented and covered by focused tests.
- The targeted optional-injection and silent-catch inventories have explicit, bounded dispositions consistent with this handoff.
- No transaction spans the system-chat model call.
- No workspace mutation leaks thread-local kind state.
- No FTS-enabled extraction update can commit partially.
- HTTP mappings distinguish absence, validation, conflict, and unavailability as specified.
- Active, reachable production wiring fails fast instead of selecting null behavior.
- No unexplained operational exception is silently swallowed in the targeted inventory.
- No production caller uses deprecated `OutputArtifactService` methods.
- Targeted asynchronous tests contain no fixed sleeps or sleep polling.
- Full suite and bounded startup pass, subject only to a precisely reproduced GitHub #41 exception.

## Open Questions

No blocking product questions remain. The following are implementation-time inventory checks, not invitations to broaden scope:

- Confirm the current count and exact call-site list for deprecated artifact methods before migration; retain a deprecated shim only when a real compatibility consumer remains.
- Confirm each targeted optional injection site against active configuration and route reachability; do not convert genuinely disabled capabilities into mandatory beans.
- Confirm each targeted silent catch’s semantics and chosen disposition; do not alter unrelated catches under this handoff.
- Confirm the current full-suite baseline and whether GitHub #41 still reproduces under the repository’s configured Java/Surefire setup.
- If a target cannot be corrected without schema, route-shape, UI, or large subsystem changes, stop that path and escalate the scope conflict rather than silently expanding this handoff.

## Sign-off

Sign-off requires evidence for every acceptance criterion, a passing bounded application startup, full-suite results with any GitHub #41 exception isolated, updated project documentation and `.internal-dev` records, and a final scope review.

This handoff signs off only the bounded code-quality corrections. It does not close or waive the blocking runtime API workspace-scoping drift, does not represent the work as an API/security pass, and does not authorize the excluded structural refactors.
