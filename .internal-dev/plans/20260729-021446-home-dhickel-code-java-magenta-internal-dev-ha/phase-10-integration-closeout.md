## Context
This phase closes the bounded correction set only from evidence produced by the completed dependency phases. It integrates records and validation evidence; it does not introduce behavior, expand APIs, or repair the separately blocking runtime workspace-scoping drift.

## Goal
integrate evidence and complete durable closeout

## In Scope
**Write Targets**: docs/end-user/chat.md, docs/technical/api-reference.md, docs/technical/services.md, docs/technical/workspaces-tools-outputs.md, docs/api/artifacts.md, .internal-dev/specifications/architecture.md, .internal-dev/specifications/service-graph.md, .internal-dev/specifications/services.md, .internal-dev/specifications/api.md, .internal-dev/specifications/decisions.md, .internal-dev/knowledge/code-quality-correctness-boundaries.md, .internal-dev/knowledge/plan-completion-validator-boundary.md, .internal-dev/changelogs/2026-07-29-code-quality-sprint.md, .internal-dev/plans/code-quality-sprint/implementation-inventory.md, .internal-dev/plans/.archive/code-quality-sprint/implementation-inventory.md

- Reconcile evidence, update only the listed durable documentation and records, finalize the bounded inventory, and complete the integration validation and commit handoff.
- Treat the active implementation inventory and its listed archive destination as one move operation. No phase-plan file is a write target or archive candidate in this phase.

## Out of Scope
- Production-code, test, schema, migration, route, payload, fragment, UI, JavaScript, and runtime workspace-scoping changes.
- New behavior, speculative cleanup, broad source-inventory cleanup, API/security-pass claims, and edits to any `phase-*.md` plan artifact.

## Dependencies
phase-01-workspace-mutation-scope.md, phase-02-system-chat-transaction-seam.md, phase-03-atomic-artifact-extraction.md, phase-04-truthful-http-errors.md, phase-05-artifact-api-cutover.md, phase-06-execution-wiring-and-observability.md, phase-07-chat-web-wiring-and-observability.md, phase-08-plan-completion-regressions.md, phase-09-trusted-context-boundaries.md

## Constraints
- Begin only with a recorded PASS from every dependency’s validator gate. A missing, failed, or contradictory dependency record is a blocker, not an inference.
- Preserve the corrected concepts: descriptor-only workspace identity; committed system-chat preparation before transaction-free inference; repository-owned atomic extraction/FTS DML; authorization-first canonical artifact access; typed endpoint-local HTTP statuses; required production collaborators; observable bounded catches; fail-closed plan completion; and terminal trusted-context failures.
- Do not alter ordered migrations, `schema.sql`, runtime FTS probing, response bodies, payload fields, HTMX fragment shapes, routes, or browser-visible behavior. FTS unavailability remains unavailable without a scan fallback.
- Durable records omit credentials, tokens, model payloads, raw untrusted content, absolute paths, claim owners, and fence values.
- `DRIFT-20260720-01` remains visibly blocking in `.internal-dev/specifications/api.md`; state explicitly that this correction set is not an API/security pass.
- Use exclusive internal-development creation for new knowledge and changelog records. Never overwrite an existing archive destination; stop and route an archive collision through the project workflow.

## Implementation Steps
1. Verify the prerequisite PASS records, current branch state, and current Maven baseline. Build a feature-to-evidence matrix for all ten handoff features and every final-validation unsafe case. For each row, record the owning phase, focused test or inventory evidence, result, and any environment condition. Reconcile code, specifications, prior records, and test results; record a contradiction as a scoped defect or drift instead of selecting a source silently.
2. Update the listed end-user, API, and technical documents plus the architecture, service-graph, services, API, and decisions specifications from verified matrix evidence only. Document the transaction seam, extraction/FTS atomicity and unavailable result, artifact-access boundary, typed status outcomes, required-wiring/catch dispositions, plan-completion validator boundary, and trusted-context confinement only where the evidence establishes them. Preserve existing route, payload, and fragment contracts and retain the separate blocking drift.
3. Create the correctness-boundary knowledge record and changelog using their required templates, and update plan-completion-validator knowledge with direct validator-boundary and safe-failure lessons. The changelog includes `Specification Impact`, changed-record paths, validation evidence, risks, and follow-ups without claiming unverified code or API changes.
4. Finalize the active implementation inventory with the exact retained optional-provider, fallback, null-path, and catch dispositions from Phases 06 and 07, including each capability-gate, proven cycle seam, expected-control-flow rationale, and focused evidence. Move that single finalized inventory to `.internal-dev/plans/.archive/code-quality-sprint/implementation-inventory.md`; do not copy it, overwrite an archive record, or archive/edit any phase file.
5. Execute and record the integration checks: every phase-focused suite; Spring-context transaction and required-wiring tests; real SQLite extraction/FTS tests, with FTS rollback assertions only where FTS5 is available and unavailable assertions everywhere; MockMvc status/fragment tests; plan-completion and trusted-context direct suites; static reconciliations for deprecated production artifact calls, bounded optional/null paths, catch dispositions, and prohibited sleep/polling in the two named asynchronous tests; complete Maven tests; configured compile/package checks; bounded ephemeral-port Spring Boot startup; documentation-link/template checks; Git status; and final diff review. Confirm the matrix includes reused-thread cleanup, preparation rollback and transaction-free inference under an ambient transaction, extraction rollback/stable-id synchronization, authorization before byte open, rejected-plan non-completion, and terminal trusted-context failure without fallback.
6. Classify every failed check. Isolate GitHub issue #41 only when the exact known ordering failure is reproduced and evidenced. Route every other failure to its owning invariant through the project workflow; do not mask it with a broader exception, reduced check, or documentation claim. If a required validation dependency is unavailable, leave closeout blocked and obtain the required user decision.
7. Submit the completed Phase 10 evidence for its validator review-and-repair gate. Once that gate records PASS, orchestration runs the mandated final integration validation because all phases then have PASS. On that validator’s PASS, commit only the completed closeout records on the established branch, preserving unrelated work and recorded evidence. A failed final integration result returns the demonstrated defect to its owning phase; do not create the closeout commit or claim completion.

## Required Guides
- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md

## Technical Guidance
- The evidence matrix is the integration source for durable claims; a prior PASS does not replace rechecking final cross-phase invariants.
- Preserve endpoint-local typed failures: safe `400`, `404`, `409`, `413`, `500`, and `503` behavior must not be converted into a global `IllegalStateException` conflict mapping or null-based fallback.
- Production-wiring evidence must distinguish required active collaborators from explicitly specified capability gates and proven cycle seams. Catch evidence must identify propagation, bounded fallback, safe contextual logging, or tested expected control flow.
- Artifact evidence must distinguish canonical scoped, authorization-first, integrity-checked reads from separately named bounded legacy compatibility reads. No record may represent compatibility facts as canonical integrity proof.
- Validator evidence asserts through `PlanCompletionValidator`, uses only approved plan facts, evidence, referenced artifact facts, prior feedback, and proposed final message, and proves planning-validator-only fail-closed resolution.
- Trusted-context evidence treats malformed available guidance, links, confinement failure, invalid encoding, and I/O failure as terminal; only proven absence produces empty guidance.

## Validation
- Every dependency has a recorded PASS, and the feature-to-evidence matrix covers all ten features, their unsafe negative cases, and conditional FTS coverage.
- Focused, integration, static, full-suite, compile/package, startup, documentation, status, and diff checks from Step 5 pass. Only the exact evidenced GitHub issue #41 ordering failure may remain isolated.
- Final review confirms no schema/API/UI drift, public-surface growth, unscoped artifact access, weakened confinement, placeholders, secrets, archive collision, or unrelated edits.
- The Phase 10 validator review-and-repair gate passes; orchestration’s final integration validator then passes.

## Exit Criteria
- The evidence matrix establishes every handoff feature and final validation criterion, or records a routed blocker; no contradiction is silently resolved.
- The listed documentation, specifications, decisions, knowledge, changelog, and finalized inventory archive are complete and truthful. No phase-plan file has changed.
- `.internal-dev/specifications/api.md` visibly retains `DRIFT-20260720-01` as blocking and states that this bounded correction set is not an API/security pass.
- Phase 10’s validator gate and the orchestration final integration validation both report PASS, and the closeout-record commit is created only following those PASS results.
