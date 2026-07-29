# Advanced Plan Corrective Review Summary

## Scope

### decomposition.md

Reviewed the ten-phase code-quality sprint against the authoritative handoff, living workspace/service/API contracts, and the failed plan validator feedback. The review is limited to plan decomposition and artifact-schema correction; it does not authorize implementation or change the handoff scope.

### concepts.md

Reviewed the shared concepts and cross-phase plan against the authoritative code-quality handoff, the listed corrected phase set, and the applicable architecture, service, API, workflow, and targeted knowledge records. No project files or phase files were changed.

### orchestration.md

Reviewed the fixed ten-phase orchestration against the authoritative handoff, corrected concepts, phase-budget contract, and machine-readable orchestration schema.

### phase-01-workspace-mutation-scope.md

Reviewed only `phase-01-workspace-mutation-scope.md` against the corrected concepts and the orchestration ledger. Its ledger metadata is correct: no dependencies, the three listed write targets, and the canonical goal. It is an independent wave-01 correction, executable by one implementation agent; its PASS gate is required before Phase 10 integration.

### phase-02-system-chat-transaction-seam.md

Reviewed only `phase-02-system-chat-transaction-seam.md` against the phase ledger, corrected concepts, current system-chat code, and the applicable architecture, service, API, and package contracts.

The ledger metadata is correct: the phase has no dependencies, belongs in execution wave 01, and is a prerequisite only where the ledger declares it, including Phase 07 and final integration. Its four write targets are sufficient for one implementation agent; no controller, route, schema, migration, UI, or JavaScript change is needed.

### phase-03-atomic-artifact-extraction.md

Reviewed Phase 03 only against `concepts.md` and the orchestration ledger. Its metadata now matches the ledger verbatim: independent dependency, the three prescribed write targets, and the extraction/FTS atomicity goal. It belongs in orchestration wave-01 and Phase 05 remains correctly gated on its PASS.

### phase-04-truthful-http-errors.md

Review level: medium. Reviewed only `phase-04-truthful-http-errors.md` against the supplied concepts and orchestration ledger. Phase 04 is an independent wave-01 phase with no prerequisite PASS gate; its later shared-file consumers are Phase 06 and Phase 07.

### phase-05-artifact-api-cutover.md

Reviewed only `phase-05-artifact-api-cutover.md` against the supplied shared concepts and Phase Ledger. The phase is correctly bounded to the Phase 03 artifact-indexing result and to migration of production callers away from deprecated `OutputArtifactService` methods; it is not an API, schema, authorization-model, or workspace-identity redesign.

### phase-06-execution-wiring-and-observability.md

Reviewed only `phase-06-execution-wiring-and-observability.md` against the supplied concepts and orchestration ledger. It is Wave 03 work, starts only after Phase 04 and Phase 05 PASS, and remains executable by one implementation agent followed by the required validator gate.

### phase-07-chat-web-wiring-and-observability.md

Reviewed `phase-07-chat-web-wiring-and-observability.md` against the corrected concepts, its four prerequisite PASS gates, and the Phase Ledger. The ledger metadata, dependency set, write-target order, one-implementer constraint, and wave placement are consistent: Phase 07 starts only after Phases 02, 04, 05, and 06 pass; Phase 08 remains its sole dependent phase.

### phase-08-plan-completion-regressions.md

Reviewed only `phase-08-plan-completion-regressions.md` against the supplied shared concepts and orchestration ledger. Its ledger dependency is Phase 07; Phase 10 remains its sole downstream integration gate. The three ledger write targets permit one agent to complete the production correction and direct regression suite without reopening Phase 07 wiring or changing routes, payloads, schema, UI, or other phases.

### phase-09-trusted-context-boundaries.md

Reviewed only `phase-09-trusted-context-boundaries.md`. It remains an independent wave-01 correction with no phase dependencies and only the four ledger-authorized source/test targets. It correctly excludes API/security-drift closure, UI, schema, routes, and prompt redesign.

### phase-10-integration-closeout.md

Medium corrective review of `phase-10-integration-closeout.md` against the Phase Ledger, execution waves, validation gate, final-integration rule, and corrected shared concepts. The ledger metadata is already aligned: its goal, complete dependency set, and ordered write-target list match the required canonical values.

## Findings

### decomposition.md

- The failed artifact used prose-oriented phase metadata where the validator requires exact goal text, literal dependency values, one unquoted canonical `**Write Targets**:` line, and a `Technical Guidance` section in every phase.
- The prior final phase coupled direct trusted-context repair with documentation, archival, full-suite evidence, and commits. That exceeds a cohesive one-agent implementation unit and delays a security-boundary regression suite unnecessarily.
- Deterministic test repair is small but belongs with the production wiring seams that change those tests: workflow synchronization with execution wiring, and agent-job synchronization with chat/web wiring.
- The corrected decomposition keeps ten phases: it folds the two deterministic test files into their owning wiring phases, makes trusted-context guidance confinement an independent phase, and reserves the last phase for integration and durable closeout.

### concepts.md

- **Material:** the supplied concepts states that all ten handoff features are covered, but its Features section has nine bullets because required production wiring and observable exception handling are merged. The handoff makes them independent acceptance targets with separate inventories, dispositions, and validation.
- **Material:** the supplied constraints prohibit all API change without explicitly preserving the handoff’s sole allowed API behavior change: truthful error-status corrections. This can cause either an incorrect rejection of Phase 04 or unauthorized route/payload work.
- **Material:** final validation omits the required current-baseline comparison and the narrow handling of the known GitHub issue #41 ordering flake. A full-suite result cannot be treated as accepted without isolating that exact exception.
- **Important:** cross-phase guidance should explicitly freeze the bounded optional-injection and catch inventory in Phase 06 before its edits, require Phase 07 to extend it before its edits, and prevent either phase from becoming a repository-wide cleanup.
- **Important:** Phase 07 revisits `GlobalExceptionHandler`; shared guidance must require it to preserve Phase 04’s removal of blanket `IllegalStateException -> 409` mapping and its safe status/body contract.
- **Important:** Phase 08’s phrase “preserved planning model fallback” is ambiguous against the handoff. Planning aliases may remain compatible only within planning-validator resolution; failure to resolve a planning validator model must fail closed and must never select an execution model.
- **Important:** final validation should name the handoff’s required negative evidence: rollback without model invocation, no stale mutation state on a reused thread, no partial FTS state, authorization before byte opening, and no durable side effect on rejected paths.

### orchestration.md

- No correction is required.
- `large` is correct: the fixed set has ten phases, within the large-scope budget of six through ten phases.
- Ledger dependencies match the corrected conceptual approach: Phases 01, 02, 03, 04, and 09 are independent; Phase 05 follows 03; Phase 06 follows 04 and 05; Phase 07 follows 02, 04, 05, and 06; Phase 08 follows 07; and Phase 10 follows all prior phase PASS gates.
- Every wave places dependencies in an earlier wave. Parallel Wave 01 entries have disjoint declared targets and independent bounded corrections.
- Model assignments, one-implementer rule, post-phase review-and-repair PASS gate, dependency barrier, and final integration model exactly match the required contract.

### phase-01-workspace-mutation-scope.md

- The phase correctly isolates a security-relevant `ThreadLocal` lifetime problem without changing workspace identity, path layout, routes, schema, or reserved-tree policy.
- The current service confirms the defect: ordinary validation and source/target validation set `heldMutationKind`, while no enclosing `finally` clears it. A failure before or after a primitive can therefore leave kind-specific authorization on a reused thread.
- The original phase needed sharper coverage of all descriptor mutation entry points, especially execute-delete, and of the package-private root bridges. The corrected phase requires the wrapper to begin before validation and to contain provisioning, primitive work, metadata dispatch, and action logging.
- Immediate primitive reauthorization remains necessary. Rename, move, and copy require independent source and destination checks; copy also reauthorizes each held-handle read/write or directory-create primitive.
- The existing target tests can contain the complete correction. No extra production target, dependency, API change, or cross-phase edit is required.

### phase-02-system-chat-transaction-seam.md

1. **Atomic unavailable path required correction.** The prior ordering could capture a binding before discovering an incompatible existing conversation origin or owner link, then return unavailable and commit that new binding. The corrected phase requires conflict preflight before writes and rollback for any post-write revalidation failure, so preparation never leaves a partial durable fact set.
2. **Holder cleanup needed an all-return-path requirement.** The current `finally` starts after early unavailable returns. The corrected phase places holder cleanup around the complete invocation path and tests success, model failure, and unavailable preparation.
3. **The transaction seam is otherwise correctly scoped.** A separate Spring-managed preparation collaborator is the narrow boundary needed to prevent self-invocation from defeating transactions. `NOT_SUPPORTED` on invocation orchestration is required to suspend an ambient transaction before preparation delegation and remote inference.
4. **Validation needed executable transaction evidence.** Mock-only checks cannot prove proxy interception, commit visibility, or ambient-transaction suspension. The corrected phase specifies a compact Spring-proxied test context with a real transaction manager, an independent database connection, a non-network model callback, and an injected post-write failure.

### phase-03-atomic-artifact-extraction.md

- `ArtifactExtractionRepository` is absent. `ArtifactSearchService` currently owns document and FTS DML, uses ordinary FTS deletion, and logs FTS write failures before continuing; this can leave document and index state divergent.
- External-content FTS requires the special delete command with the old row id, text, and language values. `delete from artifact_extraction_fts where rowid = ?` is not the required synchronization operation.
- The current plan did not require a Spring-proxied repository transaction or a real rollback test. Direct construction and the existing single-connection fixture cannot prove the required boundary.
- Current focused coverage does not exercise extraction insert/update/rebuild atomicity; its unavailable assertion is tautological.
- Adding the repository collaborator changes the Spring constructor path while an out-of-scope controller test directly constructs `ArtifactSearchService`. The corrected phase preserves that test-only construction signature and requires all atomicity tests to use the injected Spring-managed repository.
- `TaskRunRetentionService` still directly deletes extraction documents. It is outside this ledger entry's targets and the corrected phase does not claim to repair retention deletion. That remaining direct DML conflicts with the broader repository-ownership concept and needs separately authorized routing.

### phase-04-truthful-http-errors.md

- The phase metadata already matches the ledger and must remain verbatim in the corrected phase.
- The original `find` step correctly separates normal profile absence from failure, but did not explicitly prohibit broad runtime-to-empty conversion at the service boundary.
- The original chat-file mapping order placed generic I/O before typed unavailability. A typed unavailable exception that is an I/O subtype could therefore be incorrectly returned as `500`; the corrected order is most-specific first.
- The original plan named missing roots/files/non-regular targets as absence but did not require distinguishing those cases from access, decoding, confinement, or other I/O failures. The corrected phase retains existing confinement and validation behavior and permits only proven absence to become `404`.
- Removing the broad `IllegalStateException` conflict handler is necessary, but concrete conflict mappings and `ResponseStatusException` status preservation need explicit regression coverage so the generic fallback does not alter established safe response shapes.
- All edits are within the ledger targets and can be completed by one agent. No source edits from Phase 01, 02, 03, 05, or 09 are required. Phase 06 and Phase 07 must preserve this typed contract when they later touch `AgentProfileService` or `GlobalExceptionHandler`.

### phase-05-artifact-api-cutover.md

- The original phase had the correct ledger metadata, target set, and Phase 03 dependency, but did not make the required Phase 03 PASS gate explicit before caller migration.
- Its instruction to record an inventory in the implementation inventory conflicted with the ledger's Phase 05 write-target boundary; that inventory is a Phase 06/07 target. The corrected phase keeps discovery evidence within the phase's validation record and does not authorize an extra file edit.
- “Compatibility service” was too broad. The corrected phase limits it to named present callers, keeps `OutputArtifactService` create-only and write-focused, and forbids turning compatibility reads into a general artifact API.
- Authorization-before-byte-open, canonical integrity checking, legacy root confinement, regular-file checks, size bounds, and the distinction between canonical and legacy integrity claims needed concrete migration and negative-test direction.
- The original validation named broad suites but did not require an actionable deprecated-call inventory/reconciliation or prove that authorization occurs before a byte opener is reached.

### phase-06-execution-wiring-and-observability.md

- The supplied phase metadata already matches the ledger verbatim: goal, dependencies, and ordered write targets.
- The phase correctly identifies the main work, but its injection inventory lacks a required production-versus-test classification, concrete retained-provider proof, and a startup-verification rule for every retained seam.
- The current bounded sources contain broader gaps than the phase states: active optional/null paths occur in agent, assignment, job, runner, workflow, output, and reconciliation paths. The corrected phase makes the inventory authoritative without expanding beyond ledger targets.
- `WorkflowRepository` currently swallows every compatibility `ALTER` failure. The phase must require schema inspection first and propagate all failures except an explicitly established absent-column compatibility case.
- Catch guidance was too general for writer release, descriptor resolution, stale recovery, attribution lookup/backfill, and reconciliation. The corrected phase requires a disposition and preservation of the primary failure without logging paths, fences, owners, content, or credentials.
- The test requirement was incomplete: `WorkflowRunnerTest` contains fixed sleeps, sleep polling, and an elapsed-time assertion. The corrected phase requires event-based completion/waiting evidence and a barrier-backed fan-out proof.

### phase-07-chat-web-wiring-and-observability.md

- **Correction applied — inventory boundary.** The prior phase said to extend a “frozen” inventory without defining the Phase 06 handoff or the required disposition evidence. The corrected phase requires a Phase 07-only extension before production edits and reconciles every retained provider, fallback, null path, and catch to an exact rationale and focused test.
- **Correction applied — required wiring versus valid gates.** Broad conversion to required injection could either erase a proven cycle seam or preserve optional production behavior by accident. The corrected phase makes direct required injection the default, permits a provider only for an evidenced cycle, and requires startup verification plus required active-path retrieval.
- **Correction applied — status and observability precision.** The former catch guidance could continue treating failed agent lookup, failed proposal reload, malformed fragment input, and infrastructure loss as absence or empty rendering. The corrected phase distinguishes scoped absence, validation, concrete conflict/staleness, capability unavailability, expected disconnect, and operational failure while preserving existing JSON and HTMX bodies.
- **Correction applied — predecessor invariants.** The phase now explicitly preserves Phase 02’s committed preparation/transaction-free inference seam and Phase 04’s endpoint-local typed status contract. It must not restore global `IllegalStateException`-to-`409` mapping or null-based failure semantics.
- **Correction applied — deterministic test handoff.** The fixed-wait repair now requires an event owned by the test double, `finally` completion and executor cleanup, post-event durable-job assertions, diagnostic bounded waiting, and interruption preservation. It does not rely on elapsed-time assertions.
- **Scope boundary retained.** `PlanCompletionService` and `PlanSaveTools` are changed only enough to make active production wiring mandatory; Phase 08 exclusively owns completion-validation behavior and regression expansion.

### phase-08-plan-completion-regressions.md

- Metadata, dependency ordering, write-target order, and the Phase 08/Phase 10 gate relationship are retained in the corrected phase.
- The prior plan did not fully direct the implementer to close the observed lexical-only artifact check, symlink escape, absolute-path/error-message disclosure, and sentinel-string failure classification risks.
- The evidence preflight needed explicit failure rules for absent required criteria, malformed or duplicate labels, deviations, unmet criteria, and unknown labels; each must skip the validator but still persist safe feedback.
- “Strict parsing” needed concrete type, key, criterion-set, duplicate, fenced-payload, and raw-response handling rules. A response must not complete a plan merely because a matching criterion can be found among extra or duplicate entries.
- Model guidance needed to reconcile the retained planning-model fallback with the shared contract: only configured planning aliases or compatible persisted planning values may resolve; execution and default models are never candidates.
- Provider/runtime failures must be converted at the service boundary into generic fail-closed feedback. Phase 07’s required production wiring must not be weakened by optional injection or null fallback.

### phase-09-trusted-context-boundaries.md

The original phase had the correct ledger metadata, bounded scope, and core security intent. Its implementation sequence needed tighter outcome distinctions and test-to-repair guidance: terminal assembler outcomes must suppress all partial trusted-context text; resolver absence must be proven rather than inferred from `Files.notExists`; and every malformed or failed available candidate must terminate resolution before any alternate-root lookup. The corrected phase preserves the ordered test-first sequence, makes these assertions explicit, and limits a test seam to package-private, class-local I/O injection only when necessary to prove an otherwise non-portable failure.

### phase-10-integration-closeout.md

- The phase correctly confines behavioral work to prior phases, but “archive finalized plan material” exceeds its listed targets and conflicts with the instruction not to change the phase-plan file set. Only the listed implementation inventory may be archived here.
- The original exit criterion makes final integration validation a prerequisite for Phase 10 PASS, while orchestration schedules that validation only once every phase, including Phase 10, has PASSed. This is a gate cycle.
- “All named focused suites” and the static checks are not reconciled to the ten features and their unsafe cases. The corrected phase needs an evidence matrix that distinguishes test evidence, conditional FTS evidence, production-context evidence, and source-inventory evidence.
- Documentation and durable records need an observed-evidence rule: they may state only verified behavior, preserve response/fragment shapes, and retain `DRIFT-20260720-01` as a separately blocking API/security drift.
- The phase needs an explicit clean-worktree and archive collision rule so finalization neither absorbs unrelated edits nor overwrites an existing archive record.

## Risk Assessment

### decomposition.md

- Phase 07 remains intentionally broad because the handoff freezes a bounded chat/web injection and catch inventory. Its target list and catch classifications constrain it from becoming repository-wide cleanup.
- Phase 10 has no production behavior target. It may repair only integration defects demonstrated by final validation; otherwise it records and validates completed work.
- The known blocking runtime workspace-scoping API/security drift remains explicitly out of scope and must not be represented as resolved.

### concepts.md

- **High:** Combining wiring and catch handling can leave active null degradation or silent operational failures unclassified while still claiming feature completion.
- **High:** Ambiguous model fallback can silently route completion validation through an execution model, violating the clean, fail-closed validator boundary.
- **Medium:** An unqualified API freeze can block required truthful statuses; a broad interpretation can authorize route or payload drift.
- **Medium:** Accepting an unexplained full-suite failure as issue #41 can conceal a new regression.
- **Medium:** Reopening global exception advice in Phase 07 can regress the typed, endpoint-local semantics established in Phase 04.

### orchestration.md

- Phase 07 remains broad but is bounded by its frozen injection/catch inventory, declared targets, and predecessor PASS gates.
- Phase 10 may repair only integration defects evidenced by final validation; it must not broaden into an API/security or workspace-scoping correction.
- `DRIFT-20260720-01` remains separately blocking and outside this plan.

### phase-01-workspace-mutation-scope.md

- High: stale mutation kind can incorrectly apply AGENT reserved-area policy to a later operation or a root bridge on the same worker thread.
- Medium: an apparently successful happy-path test will miss leakage caused by validation rejection, primitive failure, or post-primitive metadata/action failure.
- Low: the correction is local if root bridges remain descriptor-free and the existing confined primitive and reserved-path policy are not changed.

### phase-02-system-chat-transaction-seam.md

- **High if uncorrected:** a rejected origin/owner race can leave an orphaned system-chat binding, and an ambient transaction can cover remote model work.
- **Medium:** a test fixture that reuses the transaction-bound connection can falsely report visibility. The integration test must query through an independently acquired connection.
- **Low after correction:** the collaborator is internal and limited to existing preparation facts. Existing fixed-profile authority, callbacks, bounded reads, proposal-only mutation, redaction, and endpoint shapes remain unchanged.

### phase-03-atomic-artifact-extraction.md

Medium. The corrected bounded work can be completed by one agent without overlap with another phase and without schema, API, UI, or migration changes. The principal implementation risk is external-content FTS delete ordering: deleting with replacement values, deleting documents before tokens, swallowing repository failures, or using a non-proxied test path would produce a false atomicity result. The retention-path ownership discrepancy remains a separate risk and must not be hidden by this phase's PASS claim.

### phase-04-truthful-http-errors.md

Medium. `GlobalExceptionHandler` is shared transport infrastructure, so handler precedence or an overly broad fallback can change unrelated endpoints. File-system failures are easy to over-classify as missing, which would conceal operational failures as `404`. The focused MockMvc matrix must assert both status and safe established body/fragment shape.

### phase-05-artifact-api-cutover.md

- A generic compatibility facade could preserve broad unscoped access under a new name, bypass canonical authorization, or invite new callers.
- Migrating UI, job, runner, and tool callers can silently alter ordering, limits, DTO fields, or HTMX fragment data unless existing behavior is asserted at each boundary.
- Resolving legacy paths from caller-supplied paths or reconstructing workspace identity from a `Path` would violate the trusted workspace boundary. Legacy reads also cannot claim canonical digest integrity when their persisted facts do not support it.
- A repository-level attribution backfill change must not reuse publication behavior or cause output creation/replacement. Phase 03's atomic extraction/indexing guarantee must remain intact.

### phase-06-execution-wiring-and-observability.md

Making collaborators mandatory can reveal real construction cycles. Retaining an unverified provider would merely defer that failure to runtime; removing a genuine cycle seam would prevent startup. Broad catch removal can also change recovery semantics, so lifecycle fencing, create-only publication, and reconciliation states must be tested as explicit invariants.

### phase-07-chat-web-wiring-and-observability.md

- Phase 07 has many declared targets, but a single agent can execute it because the inventory is the pre-edit decision record and the work follows one ordered wiring/catch/test sequence.
- System-chat catch changes can accidentally disclose scope, paths, model input, or proposal details. The corrected phase requires safe, typed outcomes and redacted contextual logs.
- Reclassifying an operational exception as a conflict would violate Phase 04; reclassifying an expected client disconnect as an operational warning would add noise. Focused controller and fragment tests are required for both boundaries.
- Retained providers are a cycle-risk exception, not a general optionality mechanism. Missing verification would leave a late runtime failure.

### phase-08-plan-completion-regressions.md

Risk is medium. Completion validation consumes untrusted evidence, artifact bytes, and model output, so a permissive parser, path check, routing fallback, or error disclosure could falsely complete a plan or expose filesystem details. The corrected phase contains these risks within `PlanCompletionService`, preserves the narrow validator boundary, and makes every unsafe outcome directly testable.

### phase-09-trusted-context-boundaries.md

Medium. This phase protects execution authority and trusted guidance provenance. Incorrect fallback, link handling, generation/location matching, or path-derived identity could expose an unintended root or turn an integrity failure into permissive empty guidance. The bounded targets and direct adversarial tests contain the risk.

### phase-10-integration-closeout.md

High if the gate cycle remains: closeout could be marked complete without the orchestration-required final integration validator, or validation could be blocked indefinitely. High if archive scope is left broad because it can alter plan artifacts outside the ledger. Medium if evidence is summarized without feature-level reconciliation, because conditional FTS behavior, transaction boundaries, authorization-before-open, and fail-closed trusted-context behavior can be claimed without direct proof.

## Recommendations

### decomposition.md

- Freeze the exact ledger goals, literal dependencies, canonical target lines, and wave assignments below before implementation begins.
- Require the validator PASS gate after every phase and before any dependent phase starts.
- Keep new seams narrow: the system-chat preparation collaborator and extraction repository are transaction/persistence boundaries, not general framework layers.

### concepts.md

- Adopt the corrected `concepts.md` as the shared implementation contract.
- Treat the ten features as separately evidenced targets, especially required wiring and observable exception dispositions.
- Keep Phase 04’s allowed change limited to documented statuses and safe bodies/fragments; preserve all route and payload shapes.
- Require the phase inventories to record each targeted site’s active-contract classification, selected disposition, test evidence, and retained exception rationale.
- Resolve Phase 08 model-routing language to planning-validator aliases only, with fail-closed resolution and no execution-model fallback.
- Require Phase 10 to compare the current suite baseline, isolate only the exact issue #41 ordering failure, and route every other failure through the project workflow.

### orchestration.md

- Freeze the ledger, waves, and exact machine-readable orchestration sections unchanged.
- Enforce a checkpointed PASS after each validator review-and-repair before releasing any dependent phase.
- Preserve the declared target boundaries and route any required schema, route, payload, UI, JavaScript, broad-refactor, or workspace-scoping change as scope conflict.

### phase-01-workspace-mutation-scope.md

- Use one private `try`/`finally` wrapper per top-level descriptor-backed mutation; validate the descriptor and kind before setting state and always call `remove()`, never restore a prior value.
- Make policy validation side-effect-free. Keep the primitive hook as the immediate reauthorization boundary and make a missing held kind reachable only from existing descriptor-free root bridges.
- Prove cleanup with a single reused executor thread and use a kind-sensitive root-bridge operation after every tested exit path. Cover AGENT and USER distinctions for `home` and unknown children, plus universal denial for `outputs`, `tasks`, and `.skills`.

### phase-02-system-chat-transaction-seam.md

Adopt the corrected phase exactly as submitted. It retains the ledger goal, dependencies, and canonical write-target order; adds no ledger change.

Keep expected disabled, blank, and already-mismatched states as unavailable without writes. Let persistence exceptions and the narrow post-write rollback signal escape the transactional collaborator; convert them to the existing unavailable result only in the outer service. Do not use `REQUIRES_NEW`, transaction templates around inference, controller edits, or a broad exception cleanup.

### phase-03-atomic-artifact-extraction.md

- Use a narrow Spring-managed `ArtifactExtractionRepository` as the sole owner of Phase 03 extraction-document and FTS DML. Pass the already-probed capability as an input; do not move probing, authorization, file reads, digest checks, or search into it.
- Require old-token deletion, document replacement, and new-token insertion in the stated order inside one repository transaction. Let DML failures escape; do not convert them into a successful extraction result.
- Prove rollback with a file-backed SQLite datasource, a Spring transaction manager, and a proxied repository bean. Gate FTS assertions by real capability, while testing unavailable behavior in every environment.
- Keep Phase 03's PASS limited to insert, update, and scoped rebuild removal. If the retention path is to be brought under the same ownership contract, re-review the orchestration ledger before expanding targets.

### phase-04-truthful-http-errors.md

- Keep absence and temporary-unavailability meanings narrow and typed at the owning service/controller boundary; never classify by exception message or blanket Java exception type.
- Apply controller handling in specificity order: validation, absence, size bound, typed unavailable, then generic I/O/failure. Preserve the existing endpoint body and HTMX fragment constructors rather than introducing response DTOs.
- Make generic unexpected illegal state resolve through the safe `500` path, while retaining only known domain conflict types as `409`.
- Test every required status across the affected JSON and relevant fragment paths, including sensitive-string non-disclosure and the distinction between missing files and generic I/O failures.

### phase-05-artifact-api-cutover.md

- Use the corrected phase artifact verbatim: it preserves the seven ordered edit steps while making the dependency gate, one-agent boundary, use-case limits, access order, and evidence requirements testable.
- Keep the ledger goal verbatim even though it is intentionally narrower than the supporting migration work. Re-run the orchestration review before changing ledger metadata.
- Treat a remaining production invocation of any deprecated `OutputArtifactService` member, an unbounded/new compatibility use case, or a failure of the post-phase validator repair gate as a non-PASS outcome.

### phase-06-execution-wiring-and-observability.md

Use the corrected phase below. Freeze the bounded inventory before source edits; make direct production injection the default; retain a provider only with a recorded, proven cycle or specified capability gate; verify each retained provider after singleton creation; and use narrowly typed, safe failure handling.

### phase-07-chat-web-wiring-and-observability.md

- Use the Phase 06 inventory as the immutable baseline; add only Phase 07 target rows and do not turn this work into a repository-wide cleanup.
- Complete the inventory before source edits and keep its per-site rationale, active-path behavior, and test reference current through validation.
- Preserve controller and fragment body shapes while correcting only the accepted statuses and unavailable facts. Do not add routes, payload fields, UI changes, JavaScript, migrations, or a controller decomposition.
- Do not change the ledger goal. It correctly states this phase’s bounded outcome.

### phase-08-plan-completion-regressions.md

Use the corrected phase unchanged. Keep artifact authorization/confinement work limited to validator input collection, retain `PlanCompletionValidator` as the test seam rather than testing Spring AI internals, and require the Phase 07 PASS gate before implementation. Do not use this phase to address the separately blocking API/security drift.

### phase-09-trusted-context-boundaries.md

Execute in wave-01 as an independent, one-agent phase and require the orchestration validator review-and-repair PASS gate before Phase 10 may consume its evidence. Keep the ledger goal verbatim; it is consistent with the corrected shared concepts. Keep repairs local to demonstrated gaps in the assembler or resolver and do not use this phase to address `DRIFT-20260720-01`.

### phase-10-integration-closeout.md

- Retain the ledger metadata verbatim and require every dependency’s validator PASS record before Phase 10 work.
- Keep the original seven-step structure, but make its final step two-stage: the Phase 10 validator gate PASS precedes orchestration’s final integration validation; the closeout commit follows that external PASS.
- Limit archiving to `.internal-dev/plans/code-quality-sprint/implementation-inventory.md` moved to its listed archive target. Do not move, edit, or create any `phase-*.md` artifact.
- Use a feature-to-evidence matrix and route contradictions or any non-#41 failure to the owning invariant and project defect workflow rather than documenting an assumed resolution.

## Follow-ups

### decomposition.md

- At implementation start, create the bounded optional-injection/catch inventory before changing the Phase 06 or Phase 07 sites.
- At final integration, isolate GitHub issue #41 only if its known ordering failure reproduces with exact evidence.

### concepts.md

- Use the corrected concepts without changing the listed phase-file set.
- Ensure Phase 06 and Phase 07 record the bounded inventory in the shared implementation record before their corresponding edits.
- Ensure Phase 10 reconciles evidence for every handoff feature, updates the required records, and keeps `DRIFT-20260720-01` visibly blocking without claiming an API/security pass.

### orchestration.md

- Begin Phase 06 and Phase 07 with the required bounded implementation inventory.
- Retain exact evidence if the GitHub issue #41 ordering failure is isolated during final integration.

### phase-01-workspace-mutation-scope.md

- The phase validator must verify the corrected static inventory and focused regression evidence before PASS.
- If implementation exposes a policy or primitive defect beyond state lifetime, record it as out-of-scope rather than changing reserved-tree semantics in this phase.

### phase-02-system-chat-transaction-seam.md

- Require the phase validator review-and-repair PASS gate before any dependent phase starts.
- Phase 07 must preserve this seam and fixed-profile invariants when it changes chat/web wiring.
- Final integration must retain the separately blocking `DRIFT-20260720-01`; this phase is not an API/security correction.

### phase-03-atomic-artifact-extraction.md

- Phase 05 must not start until the Phase 03 validator review-and-repair gate passes.
- Route the remaining `TaskRunRetentionService` direct extraction-document deletion to an authorized follow-up; do not modify its target list in this phase.
- Phase 10 should retain the broader final check for unscoped access, schema/API/UI drift, and the separately blocking `DRIFT-20260720-01`.

### phase-04-truthful-http-errors.md

- Phase 06 must retain the Phase 04 `AgentProfileService` status contract while making required production wiring observable.
- Phase 07 must not restore broad illegal-state conflict handling or null/absence-based error semantics in shared web advice.
- The post-phase validator review-and-repair gate must PASS before Phase 06 or Phase 07 begins.

### phase-05-artifact-api-cutover.md

- Phase 06 may start only after this phase's validator review-and-repair gate passes; its bounded injection/catch inventory must not be pre-edited by this phase.
- Phase 07 must retain this phase's canonical artifact access and Phase 04 status behavior while it touches shared web/controller callers.
- Phase 10 integrates durable records and final evidence after every phase gate passes; this phase must not perform those closeout edits.

### phase-06-execution-wiring-and-observability.md

Phase 07 must extend, not replace, the Phase 06 inventory before its own edits and must preserve Phase 04’s typed HTTP-status contract. Phase 10 may integrate records only after this phase and all other prerequisite PASS gates; it must continue to record `DRIFT-20260720-01` as outside this correction set.

### phase-07-chat-web-wiring-and-observability.md

- Require PASS evidence for all four dependencies before implementation begins.
- Hand Phase 08 a mandatory, production-wired `PlanCompletionService` without changing its completion semantics.
- Run the orchestration validator review-and-repair gate after the phase evidence is complete.

### phase-08-plan-completion-regressions.md

- The Phase 08 validator review-and-repair gate must PASS before Phase 10 consumes its evidence.
- Phase 10 should integrate only Phase 08 PASS evidence and retain the explicit statement that `DRIFT-20260720-01` remains outside this correction set.

### phase-09-trusted-context-boundaries.md

Phase 10 should integrate this phase's PASS evidence with the other phase gates and retain the separate API drift record. No phase-file-set changes are required.

### phase-10-integration-closeout.md

- Orchestration must invoke its final integration validator once Phase 10’s own validator gate records PASS, then return the result to the Phase 10 implementation agent for the final commit.
- If the exact known GitHub issue #41 ordering failure is not reproduced with evidence, it is not an exception. Any unavailable required validation dependency remains blocked and requires user direction.
