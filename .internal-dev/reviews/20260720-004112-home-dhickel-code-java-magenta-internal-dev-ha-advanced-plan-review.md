# Advanced Plan Corrective Review Summary

## Scope

### decomposition.md

Reviewed the ten-phase advanced plan against the authoritative handoff, the large-scope phase budget, one-agent phase ownership, deterministic plan metadata rules, the fixed model routes, the no-replace publication contract, and the mandatory post-phase review-and-repair PASS gate.

### concepts.md

Reviewed the supplied `concepts.md` against the authoritative handoff, current workspace/service/API/web specifications, and the repository’s fixed ten-phase file set. The review focused on architecture boundaries, phase dependencies, constraints, repository-verification fallbacks, and final validation. No project files or phase files were changed.

### orchestration.md

Reviewed the fixed ten-phase orchestration against the authoritative handoff and corrected concepts for scope classification, phase budget, dependency order, execution waves, model assignments, and mandatory review-and-repair gates.

### phase-01-contract-path-migration-foundation.md

Reviewed only `phase-01-contract-path-migration-foundation.md` against `concepts.md` and `orchestration.md`, including metadata, scope boundaries, dependency ordering, single-implementer feasibility, implementation sequence, validation, invariants, edge cases, and exit criteria.

### phase-02-run-lifecycle-fencing.md

Reviewed only `phase-02-run-lifecycle-fencing.md` against `concepts.md` and `orchestration.md`, including metadata, dependency/wave placement, one-agent executability, ordered implementation guidance, validation, invariants, edge cases, and exit criteria. No other phase or plan-file-set entry is changed.

### phase-03-reserved-tree-confinement.md

Reviewed only `phase-03-reserved-tree-confinement.md` against `concepts.md` and `orchestration.md`, including metadata, phase ordering, dependency readiness, one-agent executability, confinement guidance, adversarial validation, and completion gates. The other nine phase files and the orchestration file remain unchanged.

### phase-04-artifact-publication-reconciliation.md

Reviewed only `phase-04-artifact-publication-reconciliation.md` against `concepts.md` and `orchestration.md`, including metadata, dependency ordering, wave placement, one-agent executability, implementation sequence, invariants, failure recovery, validation, and exit criteria.

### phase-05-artifact-access-search-retention.md

Reviewed only `phase-05-artifact-access-search-retention.md` against `concepts.md` and `orchestration.md`, covering phase metadata, scope boundaries, dependency ordering, one-agent executability, wave consistency, implementation guidance, validation, and exit criteria.

### phase-06-scoped-skill-resolution.md

Reviewed only `phase-06-scoped-skill-resolution.md` against `concepts.md` and `orchestration.md`, covering metadata, scope boundaries, dependency ordering, one-agent executability, wave consistency, implementation guidance, validation, and exit criteria.

### phase-07-skill-trust-snapshots-packages.md

Reviewed only `phase-07-skill-trust-snapshots-packages.md` against `concepts.md` and `orchestration.md`. The review covers metadata, dependency ordering, single-agent executability, phase-wave consistency, implementation guidance, validation, and exit criteria. No other phase or plan-file membership is changed.

### phase-08-runtime-summaries-measurements.md

Reviewed only `phase-08-runtime-summaries-measurements.md` against `concepts.md` and `orchestration.md`. The review covered phase metadata, scope boundaries, dependency ordering, single-agent executability, wave consistency, implementation guidance, validation, invariants, edge cases, and exit criteria.

### phase-09-schedule-reaction-hardening.md

Reviewed only `phase-09-schedule-reaction-hardening.md` against `concepts.md` and `orchestration.md`, covering metadata, dependency order, single-agent executability, schedule/reaction semantics, implementation guidance, validation, and exit criteria.

### phase-10-operational-ui-integration-closeout.md

Reviewed only `phase-10-operational-ui-integration-closeout.md` against `concepts.md` and the Phase Ledger, execution waves, model assignments, validation gate, and final-integration contract in `orchestration.md`. The review covers metadata validity, scope boundaries, dependency ordering, one-agent executability, implementation guidance, validation, and exit criteria.

## Findings

### decomposition.md

- The ten-phase count is within the declared large-scope budget and each backend domain has a coherent implementation boundary.
- The prior metadata was not machine-consistent: phase goals and dependency lines drifted from the ledger, phase files lacked literal write-target markers, and several orchestration targets were class names rather than canonical project-relative paths.
- Concentrating all domain UI in the closeout phase made that phase context-heavy and forced one agent to rediscover artifact, skill, runtime, and automation contracts after their backend phases.
- Artifact, Skills, runtime-health, and schedule/reaction fragments are most executable when owned by their corresponding domain phase. The final phase should compose those tested fragments into the Files/run experience and own integration, documentation, records, and repository-wide validation.
- Parallelizing the skill branch with artifact work is not safe in this plan because both evolve shared SQLite schema and migration state. Sequential waves preserve migration ordering and avoid shared-state overlap.

### concepts.md

- **High — Run identity was treated as already proven.** The statement that `plan_runs.id`, `workflow_runs.id`, and `job_runs.id` are always the task-run ID removed the handoff’s required cardinality check. Native IDs should be reused only where inspection proves a one-to-one execution identity; otherwise the implementation must persist a distinct task-run ID and explicit linkage.
- **High — Retry semantics were too broad.** “Retry creates a new run” did not distinguish an operator-requested run retry from a bounded internal step or tool retry. The corrected concept makes resume reuse the run, operator retry/replay create lineage-linked runs, and internal retries become explicit attempts within the owning run without writable aliasing.
- **High — Trust sequencing needed a closed disclosure gate.** Scope discovery precedes immutable trust snapshots in the phase order. Without an explicit cross-phase rule, newly discovered workspace candidates could expose untrusted descriptions before the trust phase. The corrected concept requires discovered workspace/project candidates to remain pending and model-invisible until approved snapshots exist.
- **High — Publication and access sequencing needed an authorization boundary.** Publication precedes full artifact access/search work. The corrected concept prevents Phase 04 from exposing new broad byte, preview, search, or lineage routes before Phase 05 authorization-first access is complete, while requiring integrity suppression from the first publication path.
- **Medium — Migration ownership was phrased as though the runner already exists.** The referenced `SchemaMigrationRunner.java` is not present in the inspected tree. The corrected concept makes creation of that sole ordered owner an explicit Phase 01 action and requires repository-local DDL retirement rather than assuming the owner is already active.
- **Medium — Several implementation choices exceeded the handoff’s verification fallbacks.** The exact native-run mapping, a fixed six-field cron dialect, and an already-proven immutable-ID shape were stated as facts. The corrected concept retains deterministic behavior but makes repository inspection and explicit contract locking the gate where the handoff requires it.
- **Medium — Final validation omitted several sign-off details.** The corrected criteria now explicitly cover task-definition rename/recreate non-aliasing, operation-key digest conflicts, migration interruption/restart, byte-open authorization recheck, simulation’s lack of tools/artifacts/firing rows, unavailable-history wording, and blocker handling without unit-only substitution.
- **Low — Cross-phase security dependencies were implicit.** The corrected guidance now states that Phase 03 gates executable skill authorization in Phase 07, Phase 04 gates artifact access work in Phase 05, and Phase 08 supplies facts consumed by Phase 09 and the final UI composition.

### orchestration.md

- `large` is the correct classification because the work spans persistence migrations, filesystem confinement, run lifecycle, artifacts, skills, runtime observability, scheduler/reaction behavior, APIs, and operational UI integration.
- Ten phases satisfy the planner's large-scope budget of 6–10 phases without changing the fixed phase set.
- The dependency graph is sound: contracts and migration ownership precede lifecycle fencing; fencing precedes reserved-tree enforcement; artifact and skill branches begin only after confinement; runtime summaries consume lifecycle, artifact, and trusted-skill facts; schedule/reaction hardening consumes runtime facts; integration follows all contributing branches.
- The original ten-wave schedule was dependency-safe but unnecessarily serialized two independent branches. After Phase 03 passes, Phase 04 and Phase 06 have disjoint declared targets and can share a wave. After that wave passes, Phase 05 and Phase 07 likewise have disjoint declared targets and can share the next wave. The remaining phases stay ordered by their dependencies.
- The implementation tuple, validation tuple, and exactly-one-implementer rule already match the required contract.
- The post-phase validator review-and-repair PASS gate, dependency barrier, and final integration validation already match the required contract.

### phase-01-contract-path-migration-foundation.md

- The supplied goal now matches the phase ledger exactly, and the dependency is represented as the single required value `none`.
- The supplied phase had one `**Write Targets**:` line, but its implementation guidance did not explicitly require repository inspection of native-run cardinality before locking task-run identity and schema choices.
- The original task-path-key rule did not fully define canonical byte encoding or require fixed test vectors, leaving delimiter, Unicode, normalization, and cross-implementation ambiguity.
- Migration coverage needed explicit handling for concurrent startup, interrupted application, incompatible partial legacy state, resource discovery order, and fresh-schema equivalence.
- Trusted path guidance needed a sharper distinction between persisted root-relative identity and runtime absolute confined paths, plus construction and link-race invariants.
- The phase is correctly scheduled alone in wave 01 with no dependencies. It is executable by one implementation agent; mandated test and validator subagents are validation gates rather than additional implementers.
- Downstream lifecycle and publication behavior remains appropriately out of scope. The write capability remains non-authorizing until Phase 02.

### phase-02-run-lifecycle-fencing.md

- The phase metadata is corrected to match the ledger exactly: the Goal is `idempotent run operations, attempts, links, assignment fences, and writer claims`; Dependencies contains only `phase-01-contract-path-migration-foundation.md`; and In Scope contains exactly one `**Write Targets**:` line with the ledger targets in ledger order.
- The phase remains correctly scheduled alone in wave 02 after Phase 01 and before all consumers of run claims, confinement, artifacts, runtime summaries, and automation.
- The work is executable by exactly one implementation agent. The sequence starts by consuming Phase 01’s locked cardinality/path/migration contracts, then establishes persistence, transactional lifecycle behavior, submission integration, context propagation, runner fencing, native-family linkage, and responses before validation.
- The original eleven-step ordering is retained while ambiguity is removed around native ID reuse, operation digesting, attempt semantics, separate fence domains, trusted location propagation, filesystem/database recovery, and workflow approval resume.
- Validation now explicitly covers same-key replay, conflicting digests, concurrent resume, stale assignment and writer capabilities, restart boundaries, linkage/cardinality, workspace relocation, chat rejection, historical rows, and real execution.
- Exit criteria now require operation-result stability, one active attempt writer, atomic mutation-boundary fencing, explicit native/assignment lineage, root-relative confined locations, and validator PASS.

### phase-03-reserved-tree-confinement.md

- The corrected Goal now exactly matches the Phase Ledger: `structural protections and truthful fail-closed executable boundaries`.
- Dependencies now contain exactly `phase-02-run-lifecycle-fencing.md` on one line, consistent with wave 03 and the required writer-claim prerequisite.
- In Scope contains exactly one `**Write Targets**:` line, with the four ledger targets in ledger order.
- The nine implementation steps retain their original order and responsibilities while making the policy/mutation-boundary split explicit: lexical classification cannot be treated as filesystem confinement, and authorization must be checked at the write primitive rather than cached at request entry.
- The phase is executable by one implementation agent after Phase 02 passes. Repository-wide mutation-route discovery is an inventory task within the phase, not parallel implementation work. A discovered bypass that cannot be closed through the declared targets is an explicit scope conflict rather than permission to make undeclared edits or leave a route unguarded.
- Process confinement is now evaluated against concrete safety properties: write isolation, stale-process termination, and revocation before writer handoff. Capability checks alone cannot justify shell or executable-resource enablement.
- Validation covers all corrected edge cases: traversal and encoded separators, symlink and parent swaps, hard links, cross-boundary operations, stale fences and processes, provider limitations, and every discovered mutation route.
- Exit criteria require both structural safety and truthful fail-closed behavior, followed by the mandated validator PASS.

### phase-04-artifact-publication-reconciliation.md

- The phase metadata must be literal, not paraphrased: the goal now exactly matches the ledger, the dependency is the sole line under Dependencies, and In Scope contains one write-target metadata line matching the ledger.
- Wave 04 placement is valid. Phase 03 supplies reserved-tree confinement and writer-boundary prerequisites; Phase 04 does not overlap Phase 06 ownership; Phase 05 and Phase 08 correctly wait for publication facts.
- The original sequence was directionally correct but underspecified the durable transition boundaries, fence revalidation, canonical request digest, concurrent name reservation, fallback promotion recovery, and safe handling of unexplained filesystem state.
- Caller cutover and required tests are supporting edits beyond the four primary ledger targets. The corrected phase makes those edits explicit while retaining the ledger’s exact target metadata and keeping ownership with one implementation agent.
- Search, preview, broad metadata, download, lineage presentation, and retention remain closed. Phase 04 adds only publication-owned integrity gating and compatibility facts needed for later reconciliation.

### phase-05-artifact-access-search-retention.md

- The Goal now exactly matches the Phase Ledger goal: `authorization-first artifact lifecycle plus its focused HTMX surface`.
- Dependencies contain exactly `phase-04-artifact-publication-reconciliation.md` on one line, preserving the wave-05 prerequisite.
- In Scope contains exactly one `**Write Targets**:` line and matches the Phase Ledger targets.
- The phase remains executable by one implementation agent and does not overlap Phase 07's wave-05 write targets.
- The ordered implementation steps remain complete and coherent: schema, scoped authorization, byte handles, preview safety, FTS capability, indexing, authorized search, lineage, retention, endpoints, fragments, and tests.
- Authorization-before-observation, byte-open reauthorization, digest binding, create/recovery safety, preservation-first retention, and HTMX-first rendering remain explicit invariants.
- The validation and exit criteria cover both FTS capability branches, inference resistance, malformed and hostile content, lineage cycles, retention pins, interrupted operations, browser behavior, startup, and the broader suite.

### phase-06-scoped-skill-resolution.md

- The corrected goal must match the phase ledger verbatim: `deterministic scoped candidates and exact-version assignment`.
- The dependency is correctly limited to Phase 03. This permits Phase 06 to run beside Phase 04 in wave 04 and preserves Phase 07’s dependency on both confinement and scoped resolution.
- The phase is executable by one implementation agent when candidate discovery, persistence, overlays, and resolution remain a single bounded skill-domain change. Trust, snapshots, activation, package ingestion, executable authorization, and UI must remain in Phase 07.
- The original sequence was structurally sound but needed sharper boundaries between control-plane candidate data and model-visible skill disclosure. Discovery, malformed-file diagnostics, conflicts, assignments, and candidate metadata must not enter prompts or activation during this phase.
- Exact assignment needed a more explicit immutable identity and stale-binding rule. Logical name, scope, root authority, provenance, format version, and digest identify a candidate version; a digest change creates a different version and cannot silently retarget an assignment.
- Root handling needed explicit confinement guidance: obtain roots from trusted workspace authorities, never infer identity from paths, do not create roots during discovery, deduplicate existing physical roots without broadening the allowed root set, and keep project discovery disabled absent an established authority.
- Legacy conversion, malformed candidates, collision handling, unavailable versions, and equal-specificity conflicts require deterministic diagnostics rather than overwrite, fallback, or data loss.

### phase-07-skill-trust-snapshots-packages.md

- The goal must exactly match the phase ledger: `immutable trusted activation and its control-plane UI`.
- Dependencies must be a single line containing exactly `phase-03-reserved-tree-confinement.md, phase-06-scoped-skill-resolution.md`.
- `In Scope` must contain exactly one `**Write Targets**:` line, matching the ledger-owned paths.
- Wave 05 placement is consistent: Phase 03 supplies the confinement verdict and Phase 06 supplies exact candidate resolution. Phase 07 can complete safely even when confinement is unavailable by keeping executable authorization disabled.
- The original sequence was directionally correct but needed sharper transaction and identity rules for approval races, immutable snapshot contents, idempotent operations, package installation, activation after revocation, controlled edits, and executable-resource checks.
- The phase is executable by one implementation agent when work remains sequential: schema and repositories, trust/snapshot behavior, activation, package ingestion, executable gating, web fragments, then validation.
- Validation needed explicit fault, idempotency, archive collision, immutable-source, stale-fence, and disabled-confinement coverage in addition to browser checks.

### phase-08-runtime-summaries-measurements.md

- The corrected Goal exactly matches the Phase Ledger: `truthful runtime facts, health/audit APIs, and focused UI`.
- Dependencies are on one line and exactly match the Phase Ledger. They establish run/attempt facts, artifact publication facts, and trusted skill snapshot facts before runtime aggregation begins. Phase 08 remains correctly isolated in wave 06 and supplies facts required by phases 09 and 10.
- In Scope contains exactly one `**Write Targets**:` line, with targets in ledger order.
- The phase is executable by one implementation agent. Its ordered sequence is coherent: persistence contract, status and elapsed semantics, call-boundary recording, derived summaries, bounded audit and health reads, APIs, fragments, then tests.
- Native status authority, repeated measurements, unavailable legacy facts, denominator exclusions, lineage semantics, explicit producer attribution, and secret/path/content redaction are preserved.
- Guidance now makes invocation identity, idempotent completion, recorder failure behavior, active-interval derivation, audit authorization, pagination, and rebuildability explicit without introducing generic telemetry.
- Validation covers migration equivalence and recovery, mapping, repeated and incomplete measurements, resumes and waiting gaps, empty denominators, audit authorization/composition, HTMX fragments, real execution, startup, Maven, and browser review.

### phase-09-schedule-reaction-hardening.md

- The goal must exactly match the phase ledger: `fenced automation semantics and focused management UI`.
- Dependencies must be represented on one line and contain exactly `phase-02-run-lifecycle-fencing.md, phase-08-runtime-summaries-measurements.md`.
- In Scope must contain exactly one `**Write Targets**:` line matching the ledger targets.
- The phase is correctly placed after lifecycle fencing and runtime summaries: it consumes fenced run operations and factual runtime links without taking ownership of those domains.
- The ordered implementation sequence is coherent for one agent: schema and typed boundaries precede claims, firing/dispatch state machines, pure evaluation, APIs, fragments, and tests.
- The corrected phase preserves the required clock, misfire, overlap, retry, causation, deduplication, suppression, simulation, and append-only audit semantics.
- Validation needed explicit fault-boundary coverage, operation-key conflict behavior, authorization/error fragment states, and proof that stale owners cannot mutate after claim expiry.

### phase-10-operational-ui-integration-closeout.md

- The goal must match the ledger verbatim: `compose the final cross-domain surface and complete repository closeout`. The corrected phase uses that exact value.
- The dependency metadata must be one line containing exactly the four ledger dependencies in ledger order. The corrected phase does so.
- `In Scope` must contain exactly one `**Write Targets**:` line. The corrected phase retains one line and matches the ledger targets.
- The phase is correctly placed alone in wave 08 after Phases 05, 07, 08, and 09. It composes their focused fragments rather than reopening their domain implementations.
- The work is executable by one implementation agent: the edit order moves from repository/UI inspection, through composition and styling, into contracts and records, then integrated validation and final review. No parallel implementer is required.
- The ordered steps preserve the required ownership invariants: `WorkspaceExplorerService` remains filesystem-focused, domain fragments retain their owners, requests are scoped before queries, sensitive fences and absolute paths stay hidden, and prior publication/confinement contracts remain intact.
- The guidance is sufficiently concrete about fragment hosts, HTMX/OOB behavior, truthful unavailable and legacy states, documentation reconciliation, and validation without prescribing speculative APIs.
- Validation covers migrations, startup, real execution, recovery, security-sensitive behavior, focused and full tests, browser behavior, screenshots, visual critique, validator repair, and final integration. It does not substitute unit evidence for a blocked real-execution dependency.
- Exit criteria align with the corrected concepts: allowed repository-verification fallbacks must fail closed and be stated truthfully; completion still requires all applicable checks and both PASS gates.

## Risk Assessment

### decomposition.md

- The plan remains large and touches persistence, filesystem security, execution, prompts, and UI. Strict dependency barriers are necessary.
- Process confinement and SQLite FTS5 remain repository-verification risks with explicit fail-closed or unavailable behavior; neither may be represented as implemented capability without evidence.
- Filesystem and SQLite operations remain non-atomic. Create-only publication, persisted operation identities, recoverable states, and reconciliation are required throughout.
- The final integration phase still has broad validation and documentation responsibilities, but moving focused UI implementation into domain phases removes the largest avoidable context burden.

### concepts.md

The original concepts were directionally aligned with the handoff, especially on the closed workspace model, root-relative persistence, reserved-tree policy, artifact integrity, scoped skills, and HTMX composition. The principal risk was not architectural drift but premature certainty at repository-verification points and unsafe intermediate states between phases. Left unchanged, those gaps could create duplicate run identities, expose untrusted skill metadata, publish artifacts before access controls are complete, or claim migration and confinement guarantees that the implementation had not established.

The corrected concepts reduce those risks by making identity/cardinality findings explicit contract gates, separating retry classes, closing disclosure and access between dependent phases, and strengthening final evidence requirements.

### orchestration.md

The corrected parallel waves are safe only while each phase remains within its declared targets and does not introduce a shared migration resource, generated artifact, lockfile, or other mutable global state. The execution harness must block rather than silently reschedule a wave if actual write sets reveal overlap. Dependency completion must mean a validator-confirmed PASS, not implementer completion alone.

### phase-01-contract-path-migration-foundation.md

- **High:** Reusing a native run ID without proven one-to-one cardinality could alias assignments, workflow children, job work items, retries, or filesystem locations.
- **High:** Ambiguous task-key encoding could create unstable historical paths or collisions across source kinds and identity representations.
- **High:** Duplicate schema ownership or weak migration concurrency behavior could leave fresh and upgraded databases structurally different.
- **High:** Public constructors or caller-built paths could bypass workspace identity and confinement guarantees required by later phases.
- **Medium:** Treating a Phase 01 write capability as authorization would create an unfenced mutation path before Phase 02.
- **Medium:** Over-broad contract edits could prematurely claim lifecycle, publication, or confinement behavior not yet implemented.

### phase-02-run-lifecycle-fencing.md

Risk remains high because this phase changes submission, persistence, lease ownership, asynchronous execution context, and recovery semantics across several native execution families. The principal failure modes are accidental identity aliasing, split-brain writers, stale workers mutating state or files, partially persisted reservation/provisioning, and compatibility submissions generating a new operation key on retry. These risks are bounded by Phase 01’s locked identity/cardinality contract, transactional uniqueness and compare-and-set operations, independent monotonic fences, trusted capabilities, persisted compatibility keys, and fault/restart tests.

No schedule conflict remains. The phase does not pull forward artifact publication, reserved-tree enforcement, skill behavior, or scheduler/reaction work.

### phase-03-reserved-tree-confinement.md

- **High — filesystem race safety:** Normalize/real-path checks followed by ordinary path-based mutation remain vulnerable to component replacement. Protected mutations require relative-handle primitives with verified parent identity or a fail-closed provider posture.
- **High — subprocess revocation:** A valid writer fence at process launch does not prevent writes after revocation. Shell behavior is unsafe unless the process boundary can constrain writable mounts/handles and terminate stale process trees before ownership changes.
- **High — route completeness:** Any upload, import, copy, move, controller, or tool path that bypasses the central decision point defeats the policy. The route inventory and route-by-classification tests are completion evidence, not optional documentation.
- **Medium — hard-link behavior:** Link-count support varies by provider. Unsupported detection must not be represented as proven protection; fallback behavior must be explicit and fail closed wherever aliasing could reach protected bytes.
- **Medium — scope discovery:** A bypass requiring edits outside the declared ledger targets would conflict with phase metadata. The phase must stop for an approved orchestration revision instead of silently broadening scope.

### phase-04-artifact-publication-reconciliation.md

- Highest risk is violating create-only placement in a non-atomic fallback or during crash recovery. Recovery must never turn an ambiguous existing final path into an overwrite.
- A stale writer can corrupt an otherwise valid reservation unless the owner and monotonic writer fence are checked at every mutation boundary, including resumed reconciliation.
- Filesystem/database divergence cannot always be repaired automatically. Unexplained bytes, changed file identity, or uncertain fallback ownership must remain recorded and inaccessible rather than being adopted or deleted.
- Request idempotency can alias distinct publications unless its digest uses a versioned canonical encoding and its uniqueness scope includes the effective workspace and publication owner.
- Fresh-database equivalence and removal of duplicate DDL remain migration-contract obligations; repository-local schema ownership must be followed rather than recreated in artifact repositories.

### phase-05-artifact-access-search-retention.md

Residual risk is concentrated at consistency boundaries: authorization changes between metadata lookup and byte open, filesystem/database divergence during reads or deletion, FTS capability differences, and retention races with active claims or downloads. The phase addresses these with operation-scoped handles, digest/state revalidation, bounded registrations, explicit unavailable states, idempotent deletion, and reconciliation tests. No dependency or scheduling conflict remains.

### phase-06-scoped-skill-resolution.md

The primary risk is premature disclosure or activation of untrusted candidate-derived content through `AgentSkillCatalogService`. Other material risks are path escape during scanning, nondeterministic root or assignment precedence, accidental project-root invention, silent rebinding after content changes, destructive legacy conversion, and migration/schema drift. The corrected phase makes each condition fail closed or surface explicit control-plane data.

### phase-07-skill-trust-snapshots-packages.md

- **High:** Any candidate-derived name, description, body, hint, or resource metadata reaching prompts before snapshot approval violates the central trust boundary.
- **High:** Activating from live files, or permitting edits to mutate a snapshot, breaks reproducibility and revocation semantics.
- **High:** Treating instruction approval, `allowed-tools`, assignment, or package provenance as executable authority can expose host execution and reserved trees.
- **High:** Archive extraction and final installation can introduce traversal, link, collision, replacement, or filesystem/database divergence unless bounded and reconciled.
- **Medium:** Ambiguous activation dedupe can cause a revoked snapshot to activate in a retry/replay or prevent a legitimate same-run resume.
- **Medium:** HTMX fragments can leak pending metadata through counts, labels, errors, or conflict details even if the model catalog is filtered correctly.

### phase-08-runtime-summaries-measurements.md

- **High:** Call-boundary instrumentation can alter execution behavior if measurement writes throw, duplicate completion, or lose correlation during retries. Reserved invocation identity, idempotent completion, and non-masking recorder failure handling mitigate this.
- **High:** Health becomes misleading if active, legacy, or incomplete records enter terminal denominators. Explicit category counts and denominator assertions are required.
- **High:** Audit composition can leak paths, prompts, snapshot bodies, or unauthorized linked facts. Scope authorization must precede joins and serialization.
- **Medium:** Active elapsed can be overstated by including waiting or abandoned claim gaps. Persisted attempt intervals and explicit recovery terminals are the only accepted basis.
- **Medium:** Supporting migration resources and fresh-schema equivalence must change consistently even though the ledger names the primary Java/web targets. The phase explicitly requires and validates both.
- **Medium:** Tool-call capture depends on the actual centralized execution boundary. Scattered per-tool instrumentation would create inconsistent semantics and exceed the intended phase shape.

### phase-09-schedule-reaction-hardening.md

Risk is high around duplicate external work if claim fencing or operation-key reuse is checked only before enqueue rather than at each guarded transition. DST ambiguity, restart recovery, and definition revisions can also create duplicate or misleading audit unless identities include revision and slot policy. Simulation risks accidental mutation if it shares orchestration services instead of only pure evaluators. The UI can misstate outcomes if it infers completion from enqueue success rather than linked runtime facts.

### phase-10-operational-ui-integration-closeout.md

- **Integration drift:** independently built fragments may disagree on host IDs, scope parameters, pagination, or refresh behavior. Stable host contracts and focused composition tests reduce this risk.
- **Boundary erosion:** adding persistence-backed run data to Files could tempt repository access from explorer code. The corrected phase explicitly requires a separate adapter/controller and independent fragments.
- **Misleading status presentation:** legacy, unavailable, simulation, integrity-failed, and partially reconciled records can be overstated. Explicit states and backend-to-UI assertion checks are mandatory.
- **Closeout breadth:** documentation and internal-record updates span several domains. The ordered documentation and final-diff steps make this bounded closeout work rather than feature expansion.
- **Validation dependency:** real execution or Playwright can be blocked by infrastructure. Repository blocker policy applies; unit-only evidence cannot produce PASS.

## Recommendations

### decomposition.md

- Keep all ten phases and their existing domain ownership boundaries.
- Move each focused HTMX fragment into its owning domain phase: artifacts in Phase 05, Skills in Phase 07, runtime health/audit in Phase 08, and schedules/reactions in Phase 09.
- Keep Phase 10 focused on cross-domain run/Files composition, navigation, documentation, internal closeout, and integrated validation.
- Use the ledger wording verbatim in every phase Goal, dependency line, and write-target marker.
- Keep execution waves sequential because schema and migration ownership is shared across domain phases.

### concepts.md

- Adopt the corrected `concepts.md` as the shared plan contract without changing the ten phase filenames.
- In Phase 01, record the inspected execution-identity mapping and introduce the sole ordered migration owner before any schema-bearing phase proceeds.
- Treat model disclosure, activation, executable authorization, preview, download, search, and lineage as closed until their respective trust or authorization gates pass.
- Require each phase validator to review dependency invariants from prior phases, not only the files changed in the current phase.
- Keep final sign-off evidence explicit for migration, concurrency, confinement, authorization, recovery, real execution, startup, and browser behavior.

### orchestration.md

Use the corrected eight-wave schedule. Preserve the declared dependency edges, exact model tuples, one implementer per phase, mandatory validator repair authority, and PASS-before-dependent barrier. Validate actual write sets before launching each parallel wave.

### phase-01-contract-path-migration-foundation.md

- Use the corrected phase as submitted: inspect cardinality first, record the resulting identity matrix, then lock schema and canonical key vectors.
- Keep migration ownership singular and prove fresh, upgrade, restart, checksum, partial-state, and concurrent-start behavior.
- Make trusted location construction inaccessible outside `WorkspaceDirectoryService`; persist only workspace identity and root-relative paths.
- Preserve the phase boundary: establish identities and primitives without enabling run execution, publication, or writer authorization.

### phase-02-run-lifecycle-fencing.md

- Treat Phase 01’s cardinality decision and canonical digest encoding as immutable inputs; stop and escalate rather than guessing if either is absent or contradicted by code.
- Keep native run tables authoritative for status and use companion lifecycle records only for reservation, attempts, operations, links, locations, and claims.
- Require the expected owner and fence at each guarded state or filesystem mutation boundary; checking only at acquisition or startup is insufficient.
- Make compatibility-generated operation keys durable before dispatch so transport retries cannot reserve multiple runs.
- Use injected transition-boundary failures and restart tests to prove recovery from database/filesystem divergence without manufacturing historical facts.

### phase-03-reserved-tree-confinement.md

- Keep classification pure and structural, using only `WorkspacePathLayout` grammar and normalized workspace-relative paths; perform link/race checks separately at the mutation boundary.
- Require a fresh repository-backed owner-and-fence validation for each write primitive. Do not cache successful authorization across operations, retries, or subprocess lifetime.
- Prefer race-resistant relative operations through a verified directory handle. If the deployed provider cannot prove the needed property, deny the affected mutation rather than downgrade to repeated path checks and call it confinement.
- Treat process confinement as a measured capability with a disabled outcome. Enable shell behavior only after tests prove protected-tree denial and revocation against stale process trees.
- Preserve an auditable mutation-route matrix mapping every discovered route to classifications, authority type, and focused tests.

### phase-04-artifact-publication-reconciliation.md

- Implement the corrected ordered steps without splitting publication ownership across agents.
- Treat reservation, staged-byte verification, final placement, publication commit, integrity failure, and cleanup as explicit persisted boundaries with fault injection after each boundary.
- Reuse Phase 03 confined child-location primitives and Phase 02 live writer capabilities; do not accept or reconstruct trusted identity from raw paths.
- Prefer safe unresolved reconciliation over destructive guessing whenever ownership or file identity cannot be proved.
- Keep all byte-opening paths closed on non-published or integrity-failed records, without introducing Phase 05 access surfaces.

### phase-05-artifact-access-search-retention.md

Execute the steps in their stated order. Keep authorization predicates inside repository queries rather than filtering unrestricted results, and route migration 005 through the ordered migration owner established in Phase 01 while preserving fresh-schema equivalence. Treat Playwright screenshots and visual critique, bounded startup, Maven coverage, and validator PASS as mandatory completion evidence.

### phase-06-scoped-skill-resolution.md

Execute the corrected ordered steps without coupling candidate resolution to prompt assembly or activation. Treat the migration resource and fresh-schema parity as required companion edits under the migration owner established in Phase 01. Require deterministic comparator and identity test vectors, negative prompt-leakage tests, confinement tests, migration restart/idempotency coverage, focused tests, the broader Maven suite, bounded startup, and the required validator PASS.

### phase-07-skill-trust-snapshots-packages.md

- Keep the corrected metadata literal and parser-friendly.
- Make approval a digest-checked immutable snapshot operation, not a mutable candidate status flip.
- Use operation keys plus request digests for trust, revocation, ingestion, and installation; same-key/different-input reuse must conflict.
- Treat package acceptance as permission to expose validated package contents as candidates, not as instruction trust or executable authorization.
- Record activation before returning snapshot content, and permit post-revocation rehydration only for an activation already tied to the same canonical run.
- Gate executable resources on the recorded Phase 03 capability and every exact snapshot, resource, run, actor, writer-fence, and process-policy check.
- Validate model/tool-schema leakage separately from operator-only control-plane rendering.

### phase-08-runtime-summaries-measurements.md

- Treat measurements as typed invocation records with nullable provider/process facts and explicit completeness, not arbitrary event payloads.
- Reserve a stable invocation row before an external call and complete it idempotently afterward; preserve an incomplete record if execution stops between transitions.
- Keep native repositories authoritative and summaries deterministic projections rebuildable without changing native status.
- Apply workspace/run authorization before health, history, measurement, or audit queries reveal counts or linked data; bound every expansion.
- Instrument only shared model and tool execution boundaries already used by the runtime. Do not add speculative transport, telemetry, or per-tool frameworks.
- Require validator review-and-repair to reconcile evidence with every exit criterion before PASS.

### phase-09-schedule-reaction-hardening.md

Implement the persisted state machines before routes and fragments. Keep one transactionally enforced claim/fence authority, revision-qualified uniqueness, request digests, and append-only attempts/suppressions. Pass immutable inputs and a supplied `Clock` to pure evaluators; never inject mutation-capable collaborators into simulation. Render audit from persisted firing/dispatch facts and Phase 08 summaries, with explicit legacy, unavailable, active, suppressed, exhausted, and failed states.

### phase-10-operational-ui-integration-closeout.md

- Use the corrected phase unchanged as the Phase 10 handoff.
- Keep fragment ownership and scope inputs explicit at each host boundary; do not introduce a composite repository or expand `WorkspaceExplorerService`.
- Record actual FTS5, project-root, and process-confinement outcomes before making UI or documentation claims.
- Treat validator review-and-repair and final integration as distinct required gates after implementation validation.

## Follow-ups

### decomposition.md

- The implementer for each phase must inspect actual package guides and repository state before editing.
- Every phase must receive an independent openai-codex/gpt-5.6-sol:medium review-and-repair verdict of PASS before dependents start.
- Final integration must verify real execution, recovery, startup, migration, security, and Playwright evidence before completion.

### concepts.md

- Phase 01 must resolve and document native-run cardinality, immutable definition identity, migration ownership, and the canonical `taskPathKey` encoding from repository evidence.
- Phase 03 must record whether a real subprocess confinement boundary exists; Phase 07 must consume that result without weakening it.
- Phase 04 must leave new artifact access capabilities closed for Phase 05 unless authorization-first access is implemented in the same guarded path.
- Phase 06 must keep pending candidates and all untrusted derived metadata out of model-visible catalogs until Phase 07 establishes approved immutable snapshots.
- Final validation must report any blocked real-execution dependency under repository blocker policy and must not replace it with unit-only evidence.

### orchestration.md

No phase-set changes are required. If phase-level review later expands targets into overlapping paths for either parallel wave, correct the affected phase targets or orchestration before execution; do not bypass the wave-safety contract.

### phase-01-contract-path-migration-foundation.md

- Phase 02 must consume the inspected identity matrix when implementing reservation, attempts, lineage, assignment fences, and writer-fence validation.
- Phase 03 must enforce reserved-tree mutation policy and race-safe process/filesystem confinement before executable behavior is enabled.
- Phase 04 must use only trusted run-local output locations and validated writer capabilities for publication.

### phase-02-run-lifecycle-fencing.md

The implementer must complete package-guide review, focused and broader validation through the required test subagent, repository startup and real execution checks, internal-development closeout, and the post-phase `openai-codex/gpt-5.6-sol:medium` review-and-repair gate. Phase 03 must not start until that gate reports PASS.

### phase-03-reserved-tree-confinement.md

- Phase 04 may rely on this phase only after reserved output/staging mutations are publication-only and ordinary routes are denied.
- Phase 06 may discover workspace skill candidates, but `.skills` remains hidden from ordinary browsing and mutation.
- Phase 07 must consume the truthful confinement result: executable authorization remains unavailable when this phase records no proven revocable process boundary.
- Any out-of-target route or provider limitation that blocks the exit criteria requires the repository’s escalation and plan-revision workflow before dependent phases begin.

### phase-04-artifact-publication-reconciliation.md

- Phase 05 should consume the integrity and reconciliation facts established here while adding authorization-first metadata and byte access, search, preview, and retention.
- Phase 08 may depend on publication facts only after this phase’s validator review-and-repair gate reports PASS.
- No orchestration ledger, phase dependency, wave, model assignment, or other phase file requires correction for this review.

### phase-05-artifact-access-search-retention.md

No change to another phase or to the plan file set is required. Phase 10 may consume this phase's reusable artifact fragments only after this phase passes its validator gate.

### phase-06-scoped-skill-resolution.md

Phase 07 must consume exact resolved candidate identities to create approved immutable snapshots and must remain the only cutover point for candidate-derived model disclosure and activation. No other phase or plan file requires correction in this review.

### phase-07-skill-trust-snapshots-packages.md

- Phase 08 may consume only persisted activation and resource-execution facts; it must not infer trust or execution from candidate discovery.
- Phase 10 should compose the Skills surface without moving `.skills` into the Files browser or weakening the Phase 07 disclosure boundary.
- Any unavailable Phase 03 confinement capability remains a truthful disabled state, not a deferred or partially enabled execution path.

### phase-08-runtime-summaries-measurements.md

- Phase 09 may consume only persisted runtime facts and correlation identifiers established here for firing and reaction links.
- Phase 10 may compose these focused fragments into the cross-domain operational surface; Phase 08 must not absorb that integration.
- Any absence of a centralized tool boundary or mismatch in native status cardinality must be resolved within existing service boundaries or escalated under repository policy rather than bypassed with guessed facts.

### phase-09-schedule-reaction-hardening.md

The phase is ready for its single implementation agent once dependencies have PASS. Its validator should reject completion unless focused tests, broader Maven validation, bounded startup, Playwright screenshots and visual critique, restart/fault tests, and the final diff review all pass without unresolved blockers.

### phase-10-operational-ui-integration-closeout.md

- No other phase or plan-set file requires correction for this review.
- The Phase 10 implementer should archive only completed records required by project workflow and preserve unrelated records and edits.
- If integrated execution or browser validation is blocked, stop under repository blocker policy and obtain the required decision rather than marking the phase complete.
