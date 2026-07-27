# Advanced Plan Corrective Review Summary

## Scope

### decomposition.md

Reviewed the complete ten-phase establishment plan against the handoff, phase ledger contract, ownership boundaries, and required validator metadata.

### concepts.md

Reviewed the shared concepts and the ten fixed phase handoffs against the authoritative workspace-establishment handoff, with focus on architecture, inter-phase contracts, hard constraints, and completion evidence. No project or phase file was changed.

### orchestration.md

Reviewed the fixed ten-phase orchestration against the authoritative handoff, corrected concepts, and planner orchestration contract.

### phase-01-agent-workspace-policy.md

Medium corrective review of `phase-01-agent-workspace-policy.md` against the supplied concepts and orchestration ledger. The ledger metadata is internally aligned: the phase has no dependency, is the first execution wave, and gates the materialization phase. Its declared production targets are sufficient for the policy boundary; validation may use focused test coverage without broadening product ownership.

### phase-02-materialization-context-persistence.md

Reviewed only `phase-02-materialization-context-persistence.md` against the supplied shared concepts and the Phase Ledger. The ledger metadata is retained: its goal, sole dependency, and ordered write-target list are correct and must remain canonical. The phase belongs after Phase 01 and is the sole prerequisite for Phases 03 and 04; it contains no dependency on later Git, provider, prompt, or web work.

### phase-03-immutable-binding-lifecycle.md

Reviewed only `phase-03-immutable-binding-lifecycle.md` against the supplied shared concepts and the orchestration ledger. The corrected phase retains the ledger goal, sole dependency, and canonical write-target order. It remains in orchestration wave 03 alongside Phase 04; Phase 05 must consume the completed Phase 03 binding and branch facts rather than recreate them.

### phase-04-repository-control-qualification.md

Reviewed Phase 04 against the shared concepts and Phase Ledger.

### phase-05-managed-git-local.md

Medium-depth corrective review of `phase-05-managed-git-local.md` against `concepts.md`, the Phase Ledger, and the Phase 03/04 dependency contracts. The ledger metadata already matches the required Phase 05 goal, dependency order, and canonical write-target order.

### phase-06-secure-remote-provider.md

Reviewed only `phase-06-secure-remote-provider.md` against `concepts.md`, the Phase Ledger, the execution waves, the Phase 05 handoff, migration ownership, and the current configuration seam. No other phase is changed.

The ledger metadata is preserved: its goal, sole dependency, and ordered target list are authoritative. Phase 06 may proceed only after Phase 05 has recorded PASS. It shares its execution wave with Phase 07 and does not add API or web work reserved for Phase 09; Phase 09 remains downstream of Phases 06–08.

### phase-07-prompt-skills-context.md

Reviewed only `phase-07-prompt-skills-context.md` against the corrected shared concepts and the Phase Ledger. The corrected phase retains the ledger’s goal, dependencies, and ordered write targets verbatim. It is positioned in execution wave 05 after Phase 03 and Phase 05 PASS; it has no dependency on the parallel remote-provider phase. Phase 08 remains blocked on its PASS, and Phase 09 remains blocked on Phases 06–08.

One implementation agent can own the listed production changes and focused evidence, provided it consumes the immutable binding and materialization contracts already delivered by its dependencies rather than adding a new persistence or workspace-identity path.

### phase-08-restricted-system-chat.md

Reviewed only `phase-08-restricted-system-chat.md` against the phase ledger, its direct dependency contracts, and the corrected shared concepts. The ledger metadata is retained verbatim in the corrected phase.

### phase-09-api-agent-console.md

Medium corrective review of `phase-09-api-agent-console.md` against the supplied concepts, its Phase Ledger entry, and the Phase 06–08 dependency contracts.

### phase-10-documentation-integration.md

Corrective review of `phase-10-documentation-integration.md` against the Phase Ledger, its direct dependency `phase-09-api-agent-console.md`, the orchestration gate, and the supplied shared concepts. The ledger metadata already matches and must remain verbatim.

## Findings

### decomposition.md

The draft had correct domain separation but phase metadata was not machine-aligned: goals differed from ledger goals, dependency sections used list formatting, and no phase declared its ledger write targets. Repository-control qualification depends on durable materialization identity, not immutable lifecycle callers; managed local Git needs both lifecycle branch identity and the qualified control boundary.

### concepts.md

1. **Corrected — operation identity was over-broad.** The prior statement that every state-changing call needs both a canonical request digest and operation key expands the handoff. Versioned default updates and internal compare-and-set transitions require their stated expected-version or expected-generation guard; externally retriable submissions and named operations require operation-key/digest convergence. The corrected concepts separate those cases.

2. **Corrected — primary execution workspace semantics needed to remain explicit.** Context is supplemental to the existing paired `workspaceType`/`workspaceKey` selection. Omission still selects the bound agent, an explicit agent must match it, an existing USER workspace may override it, and CHAT remains non-task-selectable. Project identity remains membership metadata, never a root or descriptor.

3. **Corrected — materialization authority needed tighter wording.** Materialization identity and captured generation are authoritative; root-relative locations are verified assertions only. `WorkspacePathLayout` supplies grammar and `WorkspaceDirectoryService` constructs trusted descriptor and workspace-child location values. Repository control storage is application-owned outside every descriptor and cannot become a hidden workspace kind.

4. **Cross-phase defect — phase 04 declares a layout-owned `repository-control/<materialization-id>/<generation>/` factory but does not list `WorkspacePathLayout` among its write targets.** Without a declared structural-authority owner, implementation can drift toward local path construction or an undeclared second grammar. This is a blocking plan-ownership inconsistency, not permission for a direct-path workaround.

5. **Corrected — validation needed the handoff's authority and failure semantics.** Qualification must prove that neither JGit nor any caller can write control bytes or worktree content through an unbrokered path. Remote validation must cover every redirect, DNS answer, and connected peer. Completion evidence must also retain unchanged CHAT, USER, task-run, output-publication, and skill behavior.

### orchestration.md

- `large` is correct: this is a cross-package security, persistence, lifecycle, Git/provider, prompt, system-chat, API, UI, and documentation change.
- Ten fixed phases satisfy the large-scope budget of 6–10 phases.
- Corrected Phase 04’s ledger targets to declare `WorkspacePathLayout`, which owns the required application-control-storage structural factory. The subsequent Phase 04 review must synchronize its `Write Targets` metadata.
- Dependencies are acyclic and place every prerequisite in an earlier wave. The declared parallel pairs have disjoint ledger targets and no shared migration or schema writer.
- The fixed implementation tuple, one-implementer rule, Terra review-and-repair PASS gate, dependency barrier, and final Terra integration gate match the required contract.

### phase-01-agent-workspace-policy.md

1. **High — materialization authority was introduced before its owner exists.** The original steps say that `projects` and `repos` descendants require materialization authority, then permit exact materialization content. Phase 02 owns materialization identity and authority; Phase 05 owns managed Git. Phase 01 must reserve both areas and deny ordinary access, not define, accept, or simulate either authority.
2. **High — the file-tool fallback was underspecified.** Removing only a “mutable data-root fallback” is insufficient unless the write path rejects missing trusted descriptor/run scope before resolving a caller path. A raw host path, data-root-relative path, or current-context string is not trusted workspace authority.
3. **High — policy decisions must distinguish the AGENT root, canonical area roots, descendants, unknown root children, and nested Git control markers.** A generic ordinary-content classification would continue to make root children writable. The corrected phase makes those distinctions explicit and preserves CHAT and USER branches.
4. **Medium — provisioning and synthetic navigation need an ownership boundary.** Listing absent conceptual areas must not create them, and a generic agent-root write resolver must not create a root for a denied target. Only an approved personal-content write may provision its canonical chain; `projects`, `repos`, `.skills`, `tasks`, and `outputs` remain owner-provisioned.
5. **Medium — the writer inventory could cause phase scope creep.** The phase may inspect production writers and prove that declared ordinary routes are covered, but it must not reroute later owner-only writers or create Git/control abstractions. A discovered external ordinary writer is a failed exit condition and requires ledger/ownership escalation.
6. **Medium — validation must exercise the held-handle boundary, not only lexical policy.** Copy, move, rename, delete, and overwrite require independent source/destination authorization immediately at their primitives; unsupported providers must return the typed unavailable failure rather than use a path-based fallback.

### phase-02-materialization-context-persistence.md

- **Critical — trusted location authority is not presently authorized by the ledger.** Shared concepts require `WorkspacePathLayout` to own structural grammar and `WorkspaceDirectoryService` alone to construct trusted workspace-child locations. Phase 02 names `MaterializationLocation` but cannot alter either authority. It is executable only if Phase 01's PASS evidence proves a public, typed materialization-location factory and `projects/<stable-key>` grammar already exist. Otherwise the phase would either hand-build paths or give `AgentMaterializationService` a forbidden constructor.
- **Critical — required test and package-guide writes are absent from the ledger targets.** The original phase requires focused tests and package-guide updates, but neither test paths nor guide paths are write targets. A one-agent phase cannot both obey its target boundary and add the mandatory evidence. The corrected phase preserves the exact target metadata and makes this an orchestration prerequisite rather than silently broadening scope.
- **High — the original data model leaves materialization identity, personal selection, and repository readiness under-specified.** A key needs an exact binary frame, a repository needs a server-owned immutable source identity, and `HOME`, `SCRIPTS`, and `APPS` must be constrained selection values rather than aliases or root access. Repository records may be `PROVISIONING` but cannot receive a trusted path or become `READY` in this phase.
- **High — lifecycle and retry protections were incomplete.** Expected-generation CAS must cover every materialization state transition, expected-version CAS must cover defaults, bindings must be insert-only, and stateful registration/link operations need operation-key/request-digest convergence in addition to those guards. Filesystem creation must remain outside the database transaction and reconcile an interrupted `PROVISIONING` record without deleting bytes.
- **High — owner links need a precise boundary.** The phase may persist typed immutable links for conversation, assignment, and task-run owners, but it must not adopt those owners or infer them from paths, names, or timestamps. Phase 03 creates and attaches those links transactionally with its owner records.
- **Medium — migration validation needs a version-010 branch.** Extending generic discovery is insufficient. `SchemaMigrationRunner` must validate the 010 tables, columns, constraints, and indexes on both fresh and upgraded schemas while retaining immutable 001–009 checksum behavior.

### phase-03-immutable-binding-lifecycle.md

- The original phase correctly separates immutable captured context from the execution workspace descriptor, but did not state that `workspaceType`/`workspaceKey` selection remains authoritative and that materialization is supplemental.
- Its operation-key rule was underspecified. The corrected phase defines first-submission capture and existing-operation convergence so default changes cannot affect a retry and contradictory reuse conflicts without creating a second binding, branch identity, assignment, or run.
- The original allowed an ambiguity between a server-generated branch identity and a user-controlled branch name. The corrected phase permits only a dependency-owned opaque identity; Phase 03 records it but performs no Git operation.
- The original said to add structured selection to assignment requests although `AssignmentRequest.java` is not a ledger write target. The correction uses the already-authoritative workspace pair and requires Phase 02 to expose a typed context-capture input. It forbids smuggling context selection through `input`, checkpoints, paths, or branch names.
- The phase requires durable assignment/run/lineage binding links, while their record and repository files are not ledger targets. This is executable only if Phase 02's `AgentContextRepository` provides transactional owner-link operations for assignment, run, workflow-child, and job-work-item owners. Otherwise the ledger lacks necessary targets and must be corrected rather than storing the ID in mutable JSON.
- The original validation mentioned repository claims even though that capability belongs to Phase 05. The correction tests only the independent assignment lease and writer fence here and leaves repository-claim conjunction to Phase 05.

### phase-04-repository-control-qualification.md

- The original omitted `WorkspacePathLayout.java` despite requiring its control-location factory; the corrected target line restores it in ledger order.
- It required package-guide edits outside ledger targets; the corrected phase permits guide reading only.
- Phase 02 is the correct direct dependency. Phase 04 remains independent of Phase 03, while Phase 05 correctly requires both PASS outcomes.
- Qualification must be runtime-specific and fail closed; a hidden `.git` path or successful compilation is not qualification.
- Qualification requires adversarial automated tests, but no test-source targets are in the ledger. This is an orchestration defect, not permission to weaken the gate.

### phase-05-managed-git-local.md

- The original provisioning step says to register a materialization. That would let Git create or retarget context that Phases 02–03 make versioned and immutable. Phase 05 must consume the captured binding, materialization id, generation, and `WorkBranchIdentity`; it must not register, select, or rewrite them.
- Local Git needs an explicit boundary at every operation: Phase 04’s qualified typed control adapter is the sole route to control bytes, while Phase 01’s trusted repository-worktree location and confined primitives are the sole route to worktree bytes. The original text did not state the no-path/no-porcelain rule strongly enough at the Phase 05 call sites.
- Claim, lease, writer-fence, cancellation, generation, and expected-head checks must be conjunctive for executable mutation and rechecked immediately at the confined primitive/ref CAS. A claim alone must not authorize a mutation. Read-only status/diff still requires a live captured binding and exact materialization generation, but no claim.
- Staging and branch semantics needed sharper persistence rules: stage entries are exact reread path/mode/digest facts; branch refs derive only from the server-generated captured `WorkBranchIdentity`; commit publication is object creation plus expected-head ref CAS. Caller branch names, titles, default/protected branch movement, and hidden index files remain unavailable.
- Reconciliation must repair durable facts only after verification. It may release verified stale claims and mark a typed reconciled/non-ready result, but it must never reset, overwrite, delete, or silently discard worktree bytes.
- The ledger lists production targets only, while the phase requires focused tests. Under the shared “change only declared targets” rule, concrete test-source targets need orchestration-level clarification before an implementer writes them. The corrected phase preserves the mandatory ledger targets and makes validator PASS contingent on the required evidence rather than treating untested behavior as complete.

### phase-06-secure-remote-provider.md

- The original phase correctly confines provider work to GitHub/GHE HTTPS, but did not make the immutable provider repository ID and captured materialization generation the sole remote-binding inputs. A URL, title, repository basename, project URL, or display value must never select a remote.
- Endpoint validation needed an explicit connection-binding rule. Validating DNS before a normal client connection does not prevent rebinding; the transport must connect only to a validated address while retaining the approved DNS hostname for TLS/SNI, then verify the connected peer. Redirects must repeat that complete check.
- The migration guidance lacked enough persistence invariants for an externally retried remote mutation. Provider operation rows need operation type/key/digest convergence, captured generation/branch/head, non-secret result state, and reconciliation rather than a retry that can duplicate a push or PR.
- The original merge text was directionally correct but did not explicitly prohibit an outbound merge request in the current posture. `MergeAuthorizationService` must return `UNAVAILABLE_NO_VERIFIED_HUMAN` before issuing that request.
- The configuration scope is not executable as ledgered. `GitProviderConfig.java` alone cannot bind the existing JSON/YAML user configuration: the current `AiConfig`, `ExternalAiConfigLoader`, and `AiUserConfigConfiguration` neither contain nor carry provider configuration, and none is a Phase 06 write target. A second config source or literal-token fallback would violate the configuration and secret contracts.
- The original validation mentioned migrations generally but needed the Phase-appropriate 009-to-013 upgrade/fresh-schema equivalence, immutable 001–009 verification, and 013 schema-validation extension in `SchemaMigrationRunner`.

### phase-07-prompt-skills-context.md

- **High — silent trusted-resolution failure:** `PromptContextAssembler` currently catches resolver and I/O failures and renders no `AGENTS.md` context. That treats integrity and confinement failures as optional absence. The corrected phase makes only a genuinely absent optional file empty; binding, descriptor, materialization, active-target, decoding, link, or confinement failure terminates assembly before model or tool invocation.
- **High — authority is reconstructed from generic identifiers:** `SkillResolutionContext` carries `ProjectRootAuthority`, discovery derives a `project/<id>` root identity, and `SkillTrustService` reconstructs a descriptor by parsing `authoritativeRootId`. This conflicts with the closed workspace model and the requirement that project membership is metadata only. The corrected phase replaces that authority with a trusted, generation-bound materialization authority and prohibits root reconstruction from strings or paths.
- **Medium — prompt ordering is replaceable:** PLAN and TASK prompts currently replace the default prompt. The corrected phase establishes one deterministic composition pipeline in every mode: immutable Magenta policy, trusted captured binding facts, mode/task intent, labelled untrusted guidance, then approved immutable skill snapshots.
- **Medium — resolver provenance and bounds are incomplete:** The existing resolver takes a host-path-bearing runtime context, reads unbounded bytes, and exposes source-relative labels. The corrected phase requires descriptor/materialization-derived resolution, strict regular-file and UTF-8 checks, per-layer and aggregate bounds, and path-free truncation markers. It preserves root-to-leaf layering and closest-file conflict precedence.
- **Medium — scope wording exceeded the ledger:** The original phase asked to update package guides and tests although neither is a ledger write target. The corrected phase removes package-guide edits from implementation scope, keeps the canonical target list unchanged, and makes focused test evidence a validation obligation rather than an unlisted product/documentation deliverable.
- **Medium — terminal turn behavior must use an existing invocation boundary:** `ChatService` is not a Phase 07 write target. The implementation must use the existing turn-assembly failure path so a terminal trusted-resolution result prevents invocation; it must not add a catch-and-continue fallback. If that boundary cannot carry the terminal result without changing an unlisted component, stop and escalate the ledger scope conflict rather than weaken the rule.

### phase-08-restricted-system-chat.md

- The ledger dependency pair is sufficient and wave-consistent: Phase 03 supplies immutable conversation/binding lifecycle behavior, and Phase 07 supplies trusted prompt and immutable-snapshot policy. Phase 08 must require their PASS evidence and must not recreate either domain.
- The original steps named required edits to `RequestResolver`, `SystemChatProfile`, `SystemChatAuthorizationService`, and a read service that are not ledger write targets. It also required package-guide updates outside the ledger. That made the phase internally inconsistent with its declared one-agent scope.
- A public-request rejection only in `RequestResolver` would be fragile because an alternate caller could bypass it. The authoritative public-chat gate belongs in the Phase-08 `ChatService` target; only `SystemChatService` may enter the fixed internal profile path.
- The proposal flow needed an explicit stale-target revalidation and operation-key/digest convergence at confirmation, not only proposal creation. Otherwise a valid model suggestion could become a privileged lifecycle mutation after target state or ownership changes.
- Runtime settings must choose only enablement and a configured model. A configurable system prompt, tool list, read scope, or compatibility fallback would violate the fixed deny-by-default profile.
- The ledger lists no test or package-guide paths despite requiring focused validation and package guides. The corrected phase does not add undeclared paths; if the write-target policy is literal for test sources or package guides, the orchestration ledger needs a separate correction before implementation.

### phase-09-api-agent-console.md

- **Wave and dependency alignment is sound.** Phase 09 is in wave 07 and names all three required predecessors: remote/provider, prompt/skills, and restricted system chat. It must not start until each predecessor has validator PASS.
- **The original phase under-specifies the HTTP contract.** It does not distinguish read routes from mutation routes, enumerate the required compare-and-set/idempotency facts by operation, or prevent caller-supplied branch, path, endpoint, or control-store data. The corrected phase makes the agent-scoped route families and mutation facts explicit while leaving service authorization authoritative.
- **The Files step would breach owner boundaries if implemented as ordinary explorer navigation.** `.skills`, `repos`, `projects`, `tasks`, and `outputs` have dedicated owners. The corrected phase renders them as conceptual, non-materializing entries and routes each to its owning surface; only permitted personal areas use explorer operations.
- **System chat needs an explicit separation from ordinary chat.** The current dashboard wording points “System Chat” to normal `/chat`; that cannot become a system-chat entry point. The corrected phase removes that conflation, gives system chat dedicated agent-scoped routes, and forbids a caller-supplied INTERNAL surface or conversation identity.
- **The required non-2xx HTMX behavior is not executable under the current target list.** The repository has no `htmx:beforeSwap` handler for marked operational error fragments. HTMX will not swap the required 409/503 error fragments, while the only listed new JavaScript asset is constrained to SSE. Do not downgrade statuses to 200 or add a general HTMX handler to `system-chat.js`; the ledger needs an authorized operational error-handler target.
- **The ledger also omits test-source targets although the phase requires new controller and fragment coverage.** One implementer can own those tests, but target authorization must explicitly allow the companion test updates. The corrected phase makes this an admission gate rather than silently writing outside the ledger.
- **The fixed SimplyPages guide paths in the original phase are stale in this checkout.** The corrected phase defers to the repository’s SimplyPages documentation location and requires an explicit blocker if the applicable docs/demo cannot be located.

### phase-10-documentation-integration.md

1. The phase’s declared target set is documentation and one changelog only, but its prose authorizes package-guide, knowledge, API/migration-record, and integration-test writes. Those are out of scope and make the phase non-deterministic for its single implementer.
2. “Add integration coverage” conflicts with the target set. Phase 10 may execute and assess existing integration coverage; a missing test or implementation repair must reopen the owning earlier phase rather than cause an unlisted source/test edit.
3. The closeout instruction to archive workflow artifacts conflicts with the requested immutable plan-file set. The phase must not move, edit, or archive any phase plan. Its closeout is limited to the declared documentation/changelog targets and the required commit.
4. The validation list is broad but does not make the gate explicit: evidence must show each completed dependency has PASS, and final integration validation must pass. A failed, unavailable, or contradictory check is not documentation drift to normalize.
5. The documentation guidance needs explicit fail-closed distinctions and edge cases: closed workspace kinds; data-root-relative assertions rather than persisted absolute paths; project metadata never as workspace identity; lazy AGENT/CHAT roots; immutable bindings and default/generation CAS; unavailable qualified Git and merge; provider cache as display-only; and secret-free rendering/logging/errors.

## Risk Assessment

### decomposition.md

Keeping qualification behind lifecycle unnecessarily serialized independent work and obscured the true prerequisite for local Git. The corrected waves run only disjoint target sets in parallel. Git remains fail-closed, publication remains no-replace, and no ownership boundary or model route changes.

### concepts.md

- **High:** Leaving the phase-04 target gap unresolved weakens the sole-structural-authority rule and risks an unconfined repository-control path.
- **High:** Treating versioned default updates as operation-keyed public operations creates unnecessary API surface and can obscure the required stale-version conflict behavior.
- **High:** Any fallback from qualified DFS/control storage to path-backed JGit, shell Git, or a `.git` worktree entry violates the handoff.
- **Medium:** Omitting the primary workspace-selection rules permits project context to be mistaken for filesystem identity or permits a CHAT execution path.
- **Medium:** Treating cached provider observations as authorization, or rendering raw transport failures, can enable stale authorization or sensitive-data disclosure.

### orchestration.md

Parallel waves remain safe only while actual write sets stay within declared targets and do not introduce shared mutable artifacts. Any discovered overlap blocks that wave; it must not be silently rescheduled.

### phase-01-agent-workspace-policy.md

The primary risk is an AGENT-root escape that creates arbitrary sibling trees or reaches `.git`/equivalent control data. Secondary risks are TOCTOU mutation after a lexical preflight, accidental creation during browsing, and a file-tool no-context write reaching the data root. Reserving `projects` and `repos` now avoids premature materialization or Git authority, while preserving later phases’ ownership boundaries.

### phase-02-materialization-context-persistence.md

Proceeding without the Phase 01 factory proof risks a new path authority, raw `Path` reconstruction, and escape from the closed workspace model. Proceeding without ledger-authorized tests risks an unsupported PASS claim. Repository readiness is particularly sensitive: exposing a generic ready transition would let an unqualified caller claim managed-Git success. The corrected phase therefore makes unavailable, stale, non-ready, revoked, failed, dirty, blocked, corrupt, and missing facts terminal non-mutating outcomes.

### phase-03-immutable-binding-lifecycle.md

The main risk is a false implementation that stores a mutable default, a host path, or an untyped checkpoint value in place of an immutable binding. That would permit retargeting after a default change and defeat generation checks.

There is also a dependency-interface risk: Phase 03 cannot create durable owner links with its declared targets unless Phase 02 supplies that persistence boundary. Treat missing dependency evidence as a blocking orchestration defect, not an invitation to expand scope or add hidden persistence.

### phase-04-repository-control-qualification.md

- **Critical:** Public JGit DFS APIs may be unable to meet the boundary. Any doubt is `UNAVAILABLE`, with no fallback.
- **High:** Generation, root-assertion, or checkout race errors can escape the control/worktree boundary.
- **High:** Migration 011 can diverge between fresh and upgraded databases.

### phase-05-managed-git-local.md

High integrity and confinement risk. A weak qualification guard, a path-backed JGit call, an unfenced checkout, or a non-CAS ref update could expose control data, permit split-brain writes, or lose user work. The corrected phase fails closed and treats an unavailable Phase 04 capability as a valid, visible outcome.

### phase-06-secure-remote-provider.md

**High — configuration binding:** Without an approved ledger expansion, a configured remote cannot be reached through the established user configuration. Silently editing excluded configuration files, duplicating parsing, or accepting a literal credential would create contract drift and secret exposure.

**High — SSRF and DNS rebinding:** Redirect-only or pre-connect DNS checks can reach a disallowed peer. A qualified, pinned transport is required for both smart-HTTP and REST; unavailable is safer than a default JGit or HTTP-client transport.

**High — remote ambiguity:** Network failure after a remote side effect must become `BLOCKED` or `RECONCILED`, not an automatic retry. A contradictory operation-key reuse, stale materialization, changed head, cancellation, missing claim, or stale executable capability must have no mutation effect.

**Medium — provider fact staleness:** Cached observations are display-only. Push, PR creation, and any future merge decision require fresh provider facts at their own boundary; missing, truncated, or failed facts deny or return unavailable.

### phase-07-prompt-skills-context.md

The phase is security-sensitive: a fallback from invalid guidance to empty guidance, a path-derived authority, or a source reread against a changed generation could disclose or approve instructions outside the captured context. The main integration risk is dependency drift: Phase 03 must expose an immutable binding usable by prompt assembly, and Phase 05 must expose secret-free typed repository/capability facts without leaking a worktree or control-store path. The corrected phase contains these risks by failing closed, retaining typed unavailable states, and preserving snapshot-only disclosure and activation.

### phase-08-restricted-system-chat.md

The uncorrected phase has high authorization risk: it can admit an INTERNAL surface through normal chat, inherit normal tools/settings, or confirm a stale proposal. The corrected phase reduces this by using a server-created profile, positive tool registration, repeated scope checks, and ordinary lifecycle authorization. Residual risk is concentrated in the dependency contracts and the missing test/package-guide target ambiguity.

### phase-09-api-agent-console.md

- **High:** Rendering reserved areas through the generic explorer could expose repository control data, create absent directories, or grant writes outside the captured context.
- **High:** A generic or caller-selectable system-chat route could bypass the fixed profile and proposal-only authority.
- **High:** Returning 409/503 without a permitted HTMX error-swap mechanism makes conflict and unavailable states invisible in the console.
- **Medium:** Unbounded Git request fields or direct DTO exposure could leak provider, claim, path, or control-store details.
- **Medium:** Replacing `#agent-tab-panel` or another broad parent from delayed responses would let one domain repaint unrelated operational hosts.

### phase-10-documentation-integration.md

Medium. The original scope leak could create undocumented source changes, rewrite protected plan artifacts, or publish claims not supported by completed phases. The principal contract risk is documenting an unavailable or blocked capability as available, especially repository qualification, provider operations, merge, skills, and system chat.

## Recommendations

### decomposition.md

Retain all ten cohesive phases. Run immutable-binding lifecycle and repository-control qualification together after persistence; run remote-provider and prompt/skill work together only after local Git passes. Treat the ledger as the canonical source for every phase goal, dependency line, and write-target marker.

### concepts.md

- Adopt the corrected `concepts.md` as the shared cross-phase contract.
- Resolve the phase-04 declared-target inconsistency through plan governance while preserving the fixed phase sequence and scope; do not introduce a second layout authority or local path assembly.
- Apply operation-key/digest idempotency to externally retriable submissions and named stateful operations. Apply expected-version or expected-generation CAS to defaults and lifecycle transitions that the handoff defines that way.
- Keep all materialization, repository, prompt, system-chat, API, and fragment paths derived from trusted identity and revalidated at the consuming boundary.
- Make the final gate reject any unbrokered control/worktree write, secret-bearing or path-bearing output, mutable binding retarget, unsupported capability presented as success, or regression in retained workspace and lifecycle contracts.

### orchestration.md

Use the corrected orchestration below. Require checkpointed validator `PASS` evidence before releasing any dependent phase, and rerun the Phase 04 component review to align its write-target marker.

### phase-01-agent-workspace-policy.md

Adopt the corrected phase below. Keep the ledger goal, dependency, and write-target metadata verbatim. Do not add repository-control storage factories in this phase: Phase 04 owns that structural control boundary even though `WorkspacePathLayout` remains the sole layout authority. Do not introduce materialization capability types or a repository writer in Phase 01; reserve those areas until their owning phases provide verified contexts.

The corrected phase is executable by one implementation agent because each edit is confined to the ledger targets, the inventory has an explicit stop condition, and later-domain behavior is denied rather than partially implemented.

### phase-02-materialization-context-persistence.md

1. Re-run the orchestration review before implementation to add the `WorkspacePathLayout`/`WorkspaceDirectoryService` targets unless Phase 01 PASS evidence proves the required typed factory, plus the focused test and package-guide paths required by the phase. Keep this phase's ledger-derived metadata unchanged until that review does so.
2. Require Phase 01 evidence that only owner-approved materialization provisioning can create `projects/<stable-key>`, that `repos` remains Git-owned, and that `MaterializationLocation` can have a package-private constructor callable only by `WorkspaceDirectoryService`.
3. Use the corrected phase below: it fixes the framing, persistence matrix, CAS/idempotency semantics, repository-ready boundary, trusted resolution sequence, and explicit validation gate while preserving the eight ordered edits.

### phase-03-immutable-binding-lifecycle.md

- Require Phase 02 PASS evidence to identify the typed capture, immutable binding lookup, owner-link, expected-default-revision CAS, generation/state validation, and opaque work-branch identity APIs used by this phase.
- If those APIs do not support assignment/run/child owner links, rerun the orchestration review and add the actual record/repository/context targets to the ledger. Keep the current ledger metadata unchanged until that review does so.
- Preserve the shared-concepts rule that the canonical digest includes immutable binding and work-branch facts. On repeat operations, load the existing operation first and compare retry intent to its captured facts; never resolve current defaults first.
- Keep branch creation, repository claims, worktree access, and all Git mutation out of this phase. Phase 05 is the first consumer that may use the captured branch identity.

### phase-04-repository-control-qualification.md

- Use the corrected phase and preserve its exact nine ordered steps.
- Re-run orchestration review to add test targets if new qualification tests must be authored; add a guide target only if persistent API notes are required.
- Do not allow Phase 05 to simulate local Git when Phase 04 is unavailable.

### phase-05-managed-git-local.md

Use the corrected phase below without changing its ledger metadata. Start only with PASS evidence for both dependencies; keep Phase 06 blocked on this phase’s validator PASS. Have the orchestration owner clarify test write targets if the declared-target rule is literal; do not silently broaden Phase 05 scope.

### phase-06-secure-remote-provider.md

1. Re-run the orchestration review to add `AiConfig.java`, `ExternalAiConfigLoader.java`, and `AiUserConfigConfiguration.java` to the Phase 06 ledger targets, with their JSON/YAML tests. Keep the three mandatory copied metadata fields unchanged until that review changes the ledger.
2. Use one typed remote binding keyed by configured immutable provider repository ID plus captured materialization/generation. Store neither a remote URL nor a resolved endpoint in persistence, audit, DTOs, exceptions, logs, or rendered output.
3. Require the Phase 04 qualified-control evidence and Phase 05 PASS as concrete inputs. If qualified JGit transport, server-side secret resolution, or peer-pinned connection support is absent, expose typed unavailable outcomes and do not substitute native Git, shell Git, default redirects, path-backed JGit, or a second HTTP stack.
4. Retain the corrected eight ordered edit steps below. They give one implementation agent an inward sequence while keeping Phase 07 and public adapters outside the phase.

### phase-07-prompt-skills-context.md

Adopt the corrected phase below without changing the Phase Ledger. Have the implementation agent first confirm PASS evidence for Phases 03 and 05 and inspect their public typed outcomes. Reuse existing persistence for candidate, snapshot, decision, and binding facts; do not introduce migration, generic project-root, path-string, or API work. Require the validator to prove that a terminal assembly result reaches no model or tool call and that unavailable Git capability is represented truthfully rather than mistaken for a binding failure.

### phase-08-restricted-system-chat.md

Use the corrected phase as the Phase-08 contract. Keep `SystemChatProfile` as an immutable type within the declared `ChatInvocationProfile` target and keep proposal confirmation in `SystemChatActionProposalService`, rather than introducing undeclared source files. Require the public `ChatService` gate regardless of `RequestResolver` behavior. If tests or package guides must be written under the ledger's target policy, correct the ledger and rerun orchestration review; do not silently edit undeclared paths.

### phase-09-api-agent-console.md

- Re-run the orchestration review to authorize the existing operational HTMX error-handler asset and focused web test companions. Keep `system-chat.js` SSE-only. Until then, treat the phase as blocked rather than weakening the stated status contract.
- Preserve the ledger metadata verbatim. Interpret “versioned” as expected-version and expected-generation concurrency contracts; do not invent an ungoverned route-version scheme.
- Require dependency PASS evidence and concrete Phase 06–08 service outcome records before route design. If their public contracts do not supply a required fact, escalate instead of reconstructing it in web code.
- Use independent, stable hosts and same-domain OOB updates only for the affected domain. Let Phase 10 document the finalized route and payload contract after Phase 09 validation.

### phase-10-documentation-integration.md

Keep the ledger Goal, Dependencies, and Write Targets verbatim. Limit edits to those targets. Preserve the nine ordered steps, but change unlisted writes into evidence review and owning-phase reopen actions. Require documentation to distinguish READY from PROVISIONING, BUSY, DIRTY, BLOCKED, STALE, FAILED, RECONCILED, and UNAVAILABLE where exposed. Record only code-verified behavior; route intended-versus-observed disagreement to the affected specification and owning phase. Do not state that a capability exists merely because configuration or display cache exists.

## Follow-ups

### decomposition.md

Use the mandatory post-phase review-and-repair PASS gate before opening each dependent wave. Final integration remains required after all phase PASS results.

### concepts.md

- Record an explicit resolution for phase 04's `WorkspacePathLayout` ownership before implementation reaches repository-control storage.
- Preserve the listed phase files unchanged unless their owner formally resolves that declared-target inconsistency.
- Use the phase validators and final integration validator to verify the corrected operation-identity split, workspace-selection rules, and full security evidence.

### orchestration.md

- Phase 04 must retain `WorkspacePathLayout` as the sole structural owner of repository-control storage paths.
- All later component reviews must preserve the ledger metadata verbatim.

### phase-01-agent-workspace-policy.md

- The phase validator must record PASS only after focused adversarial tests and the production-writer scan prove no ordinary AGENT bypass.
- Phase 02 may add materialization-specific authority only after this phase’s reservation and ordinary-writer denial are present.
- Phase 04 must introduce any application-owned repository-control layout grammar; Phase 05 must remain the first phase to enable managed repository mutation.

### phase-02-materialization-context-persistence.md

- Before Phase 03 or Phase 04 consumes this domain, record validator PASS evidence for the Phase 01 factory precondition, migration 010 integrity, focused persistence/location tests, the applicable Maven suite, bounded application startup, and the forbidden path/absolute-path scans.
- Phase 03 owns conversation, assignment, run, resume, retry, replay, workflow-child, and job-work-item capture/adoption. Phase 04 and Phase 05 own qualified repository-control capability and any repository-ready transition. No later phase may compensate for a missing Phase 02 invariant.

### phase-03-immutable-binding-lifecycle.md

- Phase 02 validator evidence must establish the prerequisite context-owner persistence API.
- Phase 05 must consume, not regenerate, the Phase 03 binding and work-branch identity.
- Phase 07 and Phase 08 must use the captured binding as trusted context and must not resolve mutable defaults for an existing conversation or run.

### phase-04-repository-control-qualification.md

- Phase 05 consumes only the qualified capability and adds its own claims, fences, leases, branch, and lifecycle controls.
- Phase 10 reconciles the final capability decision in its controlled documentation targets.

### phase-05-managed-git-local.md

- Phase 06 may consume only Phase 05’s typed local-Git outcomes and captured repository identity; it must not introduce an alternate local backend.
- Phase 07 may consume repository guidance only through the captured, generation-checked repository context.
- The post-phase validator must record qualification state, migration evidence, confinement/static-scan evidence, focused tests, Maven validation, bounded startup, and PASS before dependent work proceeds.

### phase-06-secure-remote-provider.md

- The ledger target expansion is a blocking orchestration correction; do not implement an alternative configuration path under the existing target set.
- The Phase 06 validator must repair in-scope defects and record PASS before Phase 09 consumes remote/PR/merge state.
- Phase 10 should document only capabilities that passed this phase; unavailable merge remains an intentional state, not deferred simulated behavior.

### phase-07-prompt-skills-context.md

- The implementation agent records focused test evidence for all corrected invariants and leaves executable skills and package ingestion unavailable.
- The post-phase validator repairs in-scope defects and records PASS before Phase 08 consumes prompt or skill behavior.
- If dependency contracts cannot supply generation-bound authority or a terminal turn-assembly outcome through the listed targets, record the exact missing contract and request an orchestration review; do not alter this phase’s metadata or add an unsafe compatibility path.

### phase-08-restricted-system-chat.md

- Record PASS evidence for Phases 03 and 07 before Phase 08 is eligible.
- Have the required validator verify the public-chat gate, fixed registry, proposal confirmation races, migration-014 invariants, redaction, focused tests, bounded startup, and scope-only diff.
- Phase 09 remains gated on Phase-08 validator PASS.

### phase-09-api-agent-console.md

- The orchestration owner must resolve the target-authorization gap before implementation begins.
- Phase 10 must reconcile the approved route matrix, host IDs, status mapping, system-chat restriction, and Files-area ownership in the API, web, SimplyPages, and security documentation.
- The phase validator must reject any restored generic workspace, project-root browser, path-backed repository browser, merge mutation, ordinary-chat fallback, unapproved JavaScript transport, or sensitive-field disclosure.

### phase-10-documentation-integration.md

If Phase 10 finds an implementation, test, migration, or package-guide/knowledge gap, create the appropriate tracking record and reopen the owning phase; do not repair it here. If the ledger itself needs a different goal, dependency, or target set, rerun the orchestration review before changing phase metadata.
