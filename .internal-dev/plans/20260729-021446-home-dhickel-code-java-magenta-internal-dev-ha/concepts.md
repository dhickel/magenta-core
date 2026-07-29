## Architecture

- Preserve Spring Boot, Spring AI, SQLite, SimplyPages, and HTMX ownership: controllers map transport, services own use cases, and repositories own persistence.
- `WorkspacePathLayout` remains the sole structural-path authority. `WorkspaceDirectoryService` remains the sole constructor of trusted `WorkspaceDescriptor` and related trusted workspace values. No phase infers workspace identity from a `Path`.
- A Spring-managed system-chat preparation collaborator owns one transactional preparation boundary for profile, binding, origin, and owner-link facts. Inference orchestration suspends any ambient transaction; remote model work runs only after preparation commits and outside a transaction.
- `ArtifactExtractionRepository` owns the extraction-document and external-content FTS DML transaction. Extraction, authorization, file reads, and digest verification remain outside that transaction. Ordered migrations, `schema.sql`, and runtime FTS probing remain unchanged.
- `OutputArtifactService` remains create-only and write-focused. Canonical artifact access remains scoped, authorization-first, and integrity-checked. Legacy compatibility reads remain separately named, bounded, root-confined, and regular-file checked.
- HTTP status meaning is typed and endpoint-local where practical. `GlobalExceptionHandler` does not broadly reinterpret Java exception classes as client conflicts.
- Preserve the blocking runtime workspace-scoping drift in `.internal-dev/specifications/api.md`; this work is not an API/security pass and does not close `DRIFT-20260720-01`.

## Conceptual Approach

1. Keep unsafe state within its owning operation: workspace mutation kind within one top-level mutation, preparation writes within their transaction, extraction/index persistence within one repository transaction, and artifact bytes behind authorization-first access.
2. Run Phase 01, Phase 02, Phase 03, Phase 04, and Phase 09 as independent bounded corrections. Phase 05 follows Phase 03; Phase 06 follows Phase 04 and Phase 05; Phase 07 follows Phases 02, 04, 05, and 06; Phase 08 follows Phase 07; Phase 10 follows every prior phase PASS gate.
3. Create the Phase 06 bounded optional-injection and catch inventory before Phase 06 production edits. Extend the same frozen inventory for Phase 07 before Phase 07 production edits. Every in-scope retained provider, fallback, null path, or quiet catch requires a stated capability-gate, cycle, or expected-control-flow rationale and focused evidence. Do not expand this into a repository-wide cleanup.
4. Add behavioral tests in the owning phase. Spring-proxied transaction tests and production-wiring tests use a Spring context rather than direct construction. Asynchronous tests synchronize on the event under assertion, not elapsed time.
5. Preserve Phase 04’s typed status contract while later wiring/observability work touches shared controllers or advice. Preserve response and HTMX fragment shapes while applying the accepted status corrections.
6. Complete atomic extraction and FTS synchronization before migrating deprecated artifact callers. Complete artifact caller migration before execution wiring removes optional/deprecated seams that those callers use.
7. Run trusted-context and `WORKSPACE.md` confinement tests independently of closeout. Integrate evidence only when every phase gate passes.

## Features

1. **Mutation context isolation:** each descriptor-backed workspace mutation sets mutation kind only for that operation and clears it on success, validation rejection, primitive failure, metadata/action failure, and unexpected failure. A reused thread cannot inherit prior authorization state.
2. **System-chat transaction seam:** profile, binding, origin, and owner-link preparation commits atomically before inference. Model invocation has no active transaction, including when the caller supplied an ambient transaction. Fixed profile, callback allowlist, bounded reads/context, proposal-only mutation, redaction, owner checks, fail-closed behavior, and unconditional profile-holder cleanup remain intact.
3. **Atomic artifact extraction indexing:** on FTS-capable deployments, extraction documents and their external-content FTS rows commit together or roll back together for insert, update, and scoped rebuild removal. FTS-unavailable search remains explicitly unavailable and never falls back to scanning.
4. **Truthful HTTP errors:** agent-context and chat-file paths distinguish validation, scoped absence, conflict, size bound, generic failure, and typed temporary unavailability as safe `400`, `404`, `409`, `413`, `500`, and `503` outcomes. Unexpected `IllegalStateException` is not globally mapped to `409`.
5. **Required production wiring:** active, reachable execution, workspace, chat, planning, tool, skill, audit, and web-host collaborators are required in the production context. Explicit specification-backed capability gates and proven cycle seams remain deliberate and verified; test-only convenience construction does not weaken production wiring.
6. **Observable exception handling:** every catch in the handoff’s bounded inventory has an explicit disposition: typed propagation, bounded fallback, safe contextual logging, or documented expected control flow. Expected parse misses, capability probes, stale/idempotent cleanup, and client disconnects remain quiet only with a precise tested reason. Operational failures are not silently swallowed.
7. **Artifact API deprecation cleanup:** production callers no longer invoke deprecated `OutputArtifactService` compatibility methods. Canonical access stays scoped and integrity-checked; retained legacy reads remain use-case-named, bounded, and confined. Deprecated shims remain only for verified test or non-production consumers.
8. **Deterministic asynchronous tests:** `WorkflowRunnerTest` and `AgentJobServiceTest` use bounded event-driven latches, futures, callbacks, barriers, or synchronous assertions with diagnostic failures, resource cleanup, and interruption preservation. They contain no fixed sleeps, sleep polling, unbounded waits, or elapsed-time thresholds.
9. **Plan-completion regression suite:** direct tests prove deterministic evidence preflight, artifact collection and rejection, clean validator input, strict response parsing, safe feedback, and complete/incomplete transitions. Planning-validator aliases may be resolved only through the planning-validator path; an unresolved or invalid planning model fails closed, and no execution model is selected.
10. **Trusted-context regression suite:** direct tests prove terminal outcomes, AGENT/USER-only execution authority, CHAT rejection, materialization’s generation-bound read-only role, canonical provenance, output bounds, and confined optional `WORKSPACE.md` resolution. Only proven absence yields empty guidance; integrity, encoding, confinement, or I/O failure is terminal and never falls through to another root.

## Constraints

- Do not add or remove routes, alter payload fields or fragment shapes, alter schema, modify applied SQL resources or ordered migrations, change UI/layout, or change JavaScript. The sole accepted API behavior change is the handoff’s documented truthful error-status correction with safe existing response bodies/fragments.
- Do not hold a SQLite transaction across model, provider, or network work; add FTS fallback scans; weaken descriptor confinement, reserved-tree rules, lifecycle/writer fencing, integrity verification, authorization-before-byte-open checks, or create-only/no-replace publication.
- Preserve fixed system-chat authority, prompt, callback allowlist, bounded context/reads, proposal-only mutation, agent scoping, redaction, and fail-closed behavior.
- Do not decompose `OrchestrationController`, `PlanService`, `ChatService`, or the broader planning package. Add only narrow seams required for the handoff’s transaction, persistence, test, or typed-failure boundary.
- Logs, error responses, and durable records omit credentials, tokens, model payloads, raw untrusted content, absolute paths, claim owners, and fence values.
- Follow the internal-development workflow: use a dedicated branch, preserve unrelated work, commit each completed PASS-gated phase with its required records, archive finalized plan artifacts, and create the required final changelog and review records.

## Assumptions

- The handoff’s ten features are the complete contract even when source locations, current test totals, or living records differ. Code/specification disagreement is recorded and routed rather than silently resolved by scope expansion.
- Existing typed outcomes and exceptions are preferred. A new narrowly scoped type is allowed only where it expresses current domain meaning without text-based classification.
- FTS5 availability varies by runtime. Capability-unavailable assertions run in every environment; FTS transaction rollback assertions run where the runtime supports FTS5.
- The current Maven baseline must be established rather than assumed. GitHub issue #41 is the sole accepted pre-existing test exception only when the exact known ordering failure is reproduced and evidenced; any other failure follows the project defect workflow.
- Browser-visible interaction behavior is not authorized to change, so no Playwright work is expected. An unexpected browser-visible change is scope drift and requires the repository Playwright subagent workflow before sign-off.

## Cross-phase Guidance

- At each phase start, read the authoritative handoff, applicable package guides, task-relevant living specifications, and phase-relevant knowledge. Preserve the architecture, service-graph, services, API, workspace, and trusted-context contracts.
- Phase 01 keeps `heldMutationKind` lifecycle state inside one wrapper around the whole mutation, including validation and post-primitive logging. It never restores a prior value, and package-private bridges gain no descriptor authority.
- Phase 02 lets persistence failure escape its transactional collaborator so rollback occurs. Safe conversion of model/provider failure happens outside that transaction. `NOT_SUPPORTED` inference orchestration suspends ambient transactions, while the preparation collaborator supplies the required transactional boundary.
- Phase 03 deletes external-content FTS tokens with the old indexed values and stable row id before document replacement and new-token insertion. Rebuild deletion uses the same atomic removal rule. DML failure escapes the repository transaction; later safe classification must not conceal partial state.
- Phase 04 introduces narrow absence and unavailable meaning in the owning service/controller path, removes blanket illegal-state conflict mapping, and retains only concrete conflict mapping. Phase 07 must not reinstate broad mappings or null-based failure semantics.
- Phase 05 authorizes metadata before opening bytes. It migrates callers by use case, does not route reads through publication, and keeps compatibility reads bounded and confined without claiming canonical integrity where legacy facts do not establish it.
- Phase 06 records the initial bounded injection/catch inventory before its edits, makes active collaborators mandatory, verifies retained providers at startup, and replaces only targeted silent or broad operational paths. Phase 07 expands that inventory before its edits and applies the same rules to chat/web paths without controller decomposition.
- Phase 08 asserts through `PlanCompletionValidator`, not Spring AI internals. Validator input contains approved plan facts, evidence, referenced artifact facts, prior feedback, and proposed final message; broad chat history and unrelated conversation content are absent. No configuration failure, artifact failure, parse failure, or validator/provider failure can mark the plan complete.
- Phase 09 treats materialization as supplemental read-only context. Trusted search candidates and real paths are validated against the selected authority root; a malformed available result, link, confinement failure, invalid encoding, or I/O failure is terminal rather than optional absence.
- If a correction requires a schema change, route or payload shape change, UI or JavaScript work, a broad subsystem refactor, or a change to the blocking runtime workspace-scoping drift, stop that path and request scope approval.

## Final Validation Criteria

- Focused tests establish every handoff feature and its unsafe negative case, including reused-thread state leakage, preparation rollback without inference, transaction-free model invocation under ambient transaction, FTS partial-write rollback, authorization before byte opening, rejected-plan non-completion, and terminal trusted-context failure without fallback.
- Spring-proxied integration tests prove preparation persistence is visible from a separate connection after commit, preparation rollback leaves no durable partial state, and model invocation has no transaction.
- Real SQLite tests prove extraction/FTS insert, update, stable-id synchronization, rebuild removal, injected rollback, and truthful FTS-unavailable behavior without a scan fallback.
- MockMvc/controller tests cover safe `400`, `404`, `409`, `413`, `500`, and `503` outcomes for JSON and relevant fragments, with no sensitive paths or infrastructure details in bodies.
- Production-context tests prove active required dependencies fail startup when absent and intentional capability gates retain their documented unavailable outcomes. Retained provider seams are verified.
- Static inventories reconcile every targeted optional injection and catch; production source has no deprecated `OutputArtifactService` invocation; the two named asynchronous test targets have no fixed sleeps or sleep polling.
- Run all focused suites, the complete Maven suite against the established baseline, configured compile/package checks, and bounded ephemeral-port Spring Boot startup. Isolate only the exact GitHub issue #41 ordering failure if it occurs; route every other failure through the project workflow.
- Review the final diff and Git status for schema/API/UI drift, public-surface growth, unscoped artifact access, weakened confinement, placeholders, unrelated edits, and secret leakage. Validate documentation links and required record templates.
- Complete required documentation, living specification, decision, knowledge, inventory, changelog, review, archive, and commit records only when all phase PASS gates and final integration validation pass.
- `.internal-dev/specifications/api.md` continues to record `DRIFT-20260720-01` as separately blocking. Closeout explicitly states that this bounded correction set is not an API/security pass.
