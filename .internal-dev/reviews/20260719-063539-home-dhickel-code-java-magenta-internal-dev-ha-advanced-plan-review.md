# Advanced Plan Corrective Review Summary

## Scope

### concepts.md

Reviewed the supplied `concepts.md` against the authoritative handoff, focusing on architecture, phase sequencing, constraints, and final validation. The named eight-phase file set remains unchanged.

### orchestration.md

Reviewed scope classification, phase-count budget, dependency topology, execution waves, write-target ownership, fixed model tuples, and mandatory review-and-repair PASS gates against the authoritative handoff and corrected concepts.

### phase-01-lock-superseding-contracts.md

Reviewed only `phase-01-lock-superseding-contracts.md` against `concepts.md` and `orchestration.md`. It remains the dependency-free wave-01 contract phase, targets only the seven listed living specifications, and is executable by one implementation agent before the medium validator gate.

### phase-02-build-workspace-identity-foundation.md

Reviewed only Phase 02 against the corrected concepts, orchestration ledger/gates, relevant package guidance, and current workspace/chat construction paths. Checked scope, dependencies, single-agent execution, sequence consistency, guidance, validation, and exit criteria. No other phase is revised.

### phase-03-remove-avatar-product.md

Corrective review of `phase-03-remove-avatar-product.md` only against `concepts.md`, `orchestration.md`, current Avatar/explorer code, and applicable project guidance. It covers scope, dependencies, single-implementer feasibility, phase ordering, implementation direction, validation, and exit criteria. No other phase or plan-file membership is changed.

### phase-04-cut-over-explorer-and-api.md

Reviewed only `phase-04-cut-over-explorer-and-api.md` against `concepts.md` and `orchestration.md`. The review covered phase boundaries, dependency sequencing, one-agent executability, implementation order, filesystem and persistence invariants, API behavior, validation, and exit criteria.

### phase-05-cut-over-execution-and-outputs.md

Reviewed only `phase-05-cut-over-execution-and-outputs.md` against the corrected concepts and orchestration. The review covers scope, dependency and wave placement, single-implementer execution, ordered edits, invariants, edge cases, validation, and exit criteria. No other phase is changed.

### phase-06-retire-obsolete-workspace-layers.md

Reviewed only `phase-06-retire-obsolete-workspace-layers.md` against `concepts.md` and `orchestration.md`. The review covers phase scope, the Phase 05 dependency, single-agent executability, wave/gate consistency, ordered implementation guidance, validation, and exit criteria. No other phase or plan-file membership is changed.

### phase-07-mount-agent-files-tab.md

Reviewed only `phase-07-mount-agent-files-tab.md` against `concepts.md` and `orchestration.md`. The review covers phase boundaries, dependency readiness, single-agent execution, orchestration consistency, implementation guidance, validation, invariants, edge cases, and exit criteria.

### phase-08-close-documentation-and-records.md

Reviewed only `phase-08-close-documentation-and-records.md` against `concepts.md`, `orchestration.md`, and the applicable documentation/internal-development guides. The review covers scope, dependency order, single-implementer feasibility, orchestration sequencing, implementation instructions, validation coverage, and exit criteria. No other phase or plan-file membership is changed.

## Findings

### concepts.md

- **High — generic workspace retirement was stated too absolutely.** The handoff requires discovery of any demonstrated non-Work-Area use before deleting generic `Workspace`, link, and lease records. The concepts declared all such records deleted without preserving that evidence gate. The corrected concepts make canonical path identity mandatory, extract output-artifact persistence from `WorkspaceRepository`, and permit retention only when a concrete unrelated product responsibility is proven.
- **High — the Avatar removal phase needs an explicit explorer-preservation gate.** The explorer is coupled to Avatar presentation and routes in observed code. Removing Avatar without first isolating neutral explorer services, fragments, styles, and HTMX targets risks deleting required behavior or leaving the phase uncompilable. The corrected cross-phase guidance requires neutral extraction within the fixed phase ordering before Avatar-only dependencies are removed.
- **High — active persistence identity needed a clearer contract.** The original text mixed `workspaceType`/`workspaceKey`, canonical identity, and legacy `workspaceId` without stating where each belongs. The correction makes type/key the request and active persistence pair, makes canonical identity internally derived for filesystem metadata, actions, and artifact queries, and forbids client-supplied canonical identities or roots.
- **Medium — output confinement was underspecified.** “Real-path source confinement” did not identify the selected workspace as the confinement boundary. The correction requires source and destination checks against the effective descriptor root, keeps publication as the only writer beneath reserved `outputs/`, and preserves durable attribution without run-owned directories.
- **Medium — schema compatibility needed sharper separation.** Fresh-schema removal and existing-schema tolerance were present but did not explicitly prohibit startup backfills through obsolete Work Area or generic workspace tables. The correction permits deterministic compatibility from authoritative `agent_id` only when needed and forbids guessed mappings, destructive cleanup, or legacy-table dependence.
- **Medium — final validation did not fully mirror the handoff’s static-search and contract coverage.** The correction adds active-reference searches for Avatar and Work Area symbols, route and payload removals, canonical metadata/action schemas, output repository separation, runtime `AGENTS.md` layering without starter generation, user workspace collection behavior, and absence of raw-root acceptance.
- **Low — API collection semantics were implicit.** The correction states `GET` and `POST /api/workspace/user` while retaining the single item route family under `/api/workspace/{type}/{id}`.

### orchestration.md

- The `large` classification is correct: the fixed eight-phase set is within the six-to-ten-phase budget and spans architecture, persistence, APIs, execution, tools, UI, schema, tests, and documentation.
- The sequential dependency chain is valid and conservative. Every dependency is in an earlier wave, and each dependent phase is blocked until its dependency passes.
- The model and gate lines already match the required fixed contracts exactly.
- Phase 03's prior ledger understated its contract. It covered only selected Avatar callers and styling despite requiring complete Avatar removal and extraction of reusable explorer service and fragment ownership.
- Phase 05's prior ledger omitted significant members of the required execution vertical, including request controllers, runtime context, file and shell tools, layered `AGENTS.md` resolution, and associated tests.
- Phase 06 incorrectly owned tool cutover work that the corrected concepts assign to Phase 05. Phase 06 must consume an already-cut-over system and retire obsolete Work Area, generic workspace, project-root, output-directory, link, alias, and filesystem-lease layers.
- Phase 07's prior ledger did not cover the existing selector components and tests that must change for reusable user-workspace creation and execution selection.
- Phase 08's prior ledger was too narrow for the required repository-wide reconciliation of current docs, package guides, living specifications, decisions, knowledge, plan records, and changelog material.

### phase-01-lock-superseding-contracts.md

- The original preserved the correct seven-file boundary and decision → architecture → service graph → services → API → web → SimplyPages → cross-check edit order.
- It omitted the active-consumer inventory promised by the ledger and corrected concepts, risking missed Avatar, Work Area, generic workspace, explorer, execution, output, schema, documentation, and test consumers.
- It did not require an evidence-backed disposition for generic workspace persistence or extraction of mixed output persistence before deletion.
- Descriptor construction, key validation, entity/declaration checks, execution-selection rules, output confinement, and fresh-versus-existing schema behavior needed stronger owning-spec guidance.
- Its stale-reference check wrongly implied every remaining Avatar or Work Area term must be superseded history; active removal and route-absence contracts legitimately retain those terms.
- It did not connect PASS to branch/commit sequencing or block phase 02 before PASS.
- Validation did not verify inventory ownership, generic-persistence disposition, or preservation of the eight-file plan set.

### phase-02-build-workspace-identity-foundation.md

- The scope omitted `AgentProfileRepository`, `ChatMemoryRepository`, and `ChatSessionMetadataRepository` despite the orchestration ledger assigning them to Phase 02. It also failed to account for production and test call sites that directly construct `WorkspaceDirectoryService`.
- Replacing the current constructor as written would not compile. Retaining an `AiConfig`-only fallback would leave descriptor resolution without authoritative existence checks. The corrected phase requires one validated production graph, a collaborator-backed test fixture, and bounded call-site adaptation.
- A public record cannot provide the intended package-controlled canonical constructor. The descriptor should be an immutable final class, with a gate proving only `WorkspaceDirectoryService` constructs it.
- Chat existence handling was brittle. The corrected phase adds narrow existence methods to the existing repositories and checks for the optional saved-plan table before querying it; arbitrary SQL failures are not swallowed.
- “404-class” and “409-class” failures lacked stable service semantics. The corrected phase defines transport-neutral not-found and conflict reasons for later HTTP mapping.
- Confinement guidance needed existing-ancestor, symlink-root, non-directory, race, and user-parent handling. Lexical confinement alone is insufficient.
- `ChatFileService` currently masks missing conversations and unrelated failures as empty. Only an absent physical root for an authoritative chat is empty.
- Inspecting a shared data root after the broad suite cannot attribute side effects because legacy tests intentionally create old trees. Isolated focused roots are required.
- The dependency and wave order are correct: Phase 01 must pass first, and Phase 03 cannot start before Phase 02 passes.

### phase-03-remove-avatar-product.md

1. **Blocking:** the phase deletes the only explorer renderer without creating the neutral handoff required by the concepts. The ledger targets both `WorkAreaExplorerFragments` and the not-yet-present `WorkspaceExplorerFragments`, but the phase only copies CSS and deletes the former. This loses list, inspector, viewer/editor, modal, OOB, tag, and delete-confirmation behavior needed by Phases 04 and 07.
2. **Blocking:** reusable service behavior is not moved. `WorkAreaExplorerService` owns confinement, limits, UTF-8 handling, symbolic-tree rejection, collision checks, delete preflight, and result models. Phase 03 must make `WorkspaceExplorerService` the reusable filesystem owner while retaining only a thin transitional Work Area adapter until Phase 04.
3. **High:** CSS-only neutralization leaves Avatar coupling in fragment IDs/classes, `/avatar/_work-areas/*` routes, and Work Area models. The neutral handoff must use workspace-neutral presentation and server-derived type/key routes without activating Phase 04 endpoints early.
4. **High:** deleting `AvatarDashboardControllerTest` discards concentrated explorer presentation coverage. Reusable assertions must move to neutral fragment tests; service extraction also needs focused security/limit tests.
5. **High:** `ChatSessionMetadataRepository.findSurface` is defensive, but `ChatPendingMessageRepository` directly calls `ChatSessionSurface.valueOf`. Historical `AVATAR` pending rows can fail reads after enum removal. Persisted unknown values must be non-fatal without weakening JSON request validation or rewriting rows.
6. **High:** the startup/sentinel step is vague and deletes its likely test owner. A retained context test must prove a fresh root does not create `avatar.sqlite`, a pre-existing sentinel remains byte-for-byte unchanged, and no Avatar datasource/JDBC/schema/package bean remains.
7. **Medium:** moved explorer CSS currently depends on Avatar ancestors, utilities, and variables. The phase must remove those dependencies and verify neutral fragments emit matching classes.
8. **Medium:** searches alone do not prove `/avatar` is unmapped or the datasource is absent. Route-registry, context, fresh-root, sentinel, and focused browser checks are needed.
9. **Pass with correction:** dependency placement is sound. Phase 03 follows Phase 02 and gates Phase 04. The corrected sequence is executable by one implementation agent; the separate medium validator remains the orchestration gate, not a second implementer.

### phase-04-cut-over-explorer-and-api.md

1. **Blocking — ownership at the Phase 03 boundary was inconsistent.** The phase said to replace `WorkAreaExplorerService` with a new neutral service even though Phase 03 already owns extraction of reusable explorer behavior into `WorkspaceExplorerService`. Phase 04 must consume that postcondition, finish the descriptor cutover, and remove any temporary Work Area adapter without repeating Phase 03.
2. **High — reserved `outputs/` behavior was overbroad.** “Reject explorer mutations” could incorrectly reject a read-only copy source. The actual invariant is that explorer operations may read `outputs/`, but may never create, overwrite, rename, move, copy, or delete a path in that subtree. Copying a file out is allowed only if the source is read safely and the destination is outside `outputs/`.
3. **High — lazy-root behavior needed operation-level rules.** Invalid or unsuccessful requests must not materialize agent/chat roots. Root listing and inspection must model a missing root as empty, while nested reads and existing-target mutations return not found. Only successful create operations may lazily create the root, and they may not create unrequested parent directories.
4. **High — persistence cutover omitted repository self-initialization and SQL prefix safety.** Updating only `schema.sql` would leave repository `ensureSchema()` paths capable of recreating obsolete tables. Canonical subtree queries also need escaped `LIKE` patterns so `%`, `_`, and the escape character in valid file names cannot affect unrelated rows.
5. **High — web-boundary validation was incomplete.** Encoded traversal syntax must be rejected in addition to encoded separators. The decoded type/key still requires closed-enum parsing and `PlainPathSegmentValidator`; the descriptor must then be resolved once and reused.
6. **Medium — the route contract was ambiguous.** The original phase did not explicitly route `inspect`, and it risked retaining the old `DELETE .../files` compatibility handler. The corrected phase lists the complete typed operation family and removes the duplicate compatibility route.
7. **Medium — records and supporting types were under-scoped.** `WorkspaceFileLabelAssignment`, `WorkspaceFileActionType`, and canonical response summaries must lose Work Area, generic workspace, owner, root, and host `Path` fields where applicable. These are necessary edits, not a new phase or broader product surface.
8. **Medium — status and response rules needed sharper guidance.** Invalid identity/path/policy requests map to `400`, missing entities or files to `404`, and collisions to `409`; unexpected IO remains an internal failure and must not disclose host paths. User workspace creation/listing must be path-free and use the lifecycle established in Phase 02.
9. **Pass — dependency and execution shape are otherwise sound.** Phase 04 depends only on Phase 03, does not require Phase 05 execution cutover or Phase 07 UI mounting, and is executable by one implementation agent with a coherent service/repository/controller/test sequence.

### phase-05-cut-over-execution-and-outputs.md

- Phase 05 correctly depends on Phase 04 and must PASS before Phase 06 removes obsolete layers.
- The original scope omitted explicit file-tool, shell-tool, `AgentsMdResolver`, prompt, and runtime-context ownership required by the orchestration ledger’s end-to-end cutover.
- Artifact extraction must include all `run_output_artifacts` schema compatibility, mapping, writes, queries, and deletion behavior before Phase 06 can delete mixed workspace persistence.
- Compatibility cannot perform a generic backfill. It may derive `agent/<agentId>` only from authoritative agent attribution on the same row and must never inspect Work Area or generic workspace routing.
- A temporary `EffectiveWorkspace` wrapper must contain only one already-resolved descriptor; it cannot remain a second resolver or carry project/run/scratch/output routing.
- Publication supports normalized relative names beneath `outputs/`, not only leaf names. It must prevent symlink escape, overwrite races, and run/job/project directory synthesis.
- Tool binding, active-path confinement, reserved-`outputs` protection, and layered `AGENTS.md` behavior were absent from the ordered steps and validation.
- Temporary raw workspace inputs would duplicate Phase 07. Phase 05 should remove old routing controls and let omission select the bound agent until Phase 07 mounts the reusable selector.
- Ambiguous historical execution rows need an explicit non-executable disposition; they must not be leased, resumed, or assigned a guessed root.
- The phase remains executable by exactly one implementation agent when followed as one ordered vertical cutover. Splitting ownership would increase schema/model/runtime drift and conflict with orchestration.

### phase-06-retire-obsolete-workspace-layers.md

- **Dependency and orchestration alignment:** The phase correctly depends only on Phase 05 and remains wave 06 with exactly one implementer. Its work is serially executable because the deletion order follows the completed explorer and execution cutovers. The corrected phase makes Phase 05 PASS and the Phase 01 persistence disposition explicit preconditions.
- **Incorrect deletion:** The submitted phase deleted `OutputPublicationTarget`, although Phase 05 establishes it as part of the active output-publication contract and the orchestration ledger does not assign its retirement to Phase 06. The corrected phase retains it and removes only superseded output-directory routing types.
- **Generic persistence was too unconditional:** The submitted phase required deletion of all generic workspace persistence without consulting Phase 01’s demonstrated-consumer disposition. The shared contract permits retention only for a documented unrelated responsibility that cannot select, resolve, alias, link, lease, or own a filesystem root. The corrected scope encodes that narrow conditional and otherwise requires deletion.
- **Scope omissions:** `ProjectRepository` appears in the orchestration ledger but not the submitted phase’s update list. Alias, migration, and obsolete helper classes discovered through the Phase 01 inventory were also not explicitly governed. The corrected phase includes `ProjectRepository` and makes inventory-backed deletion authoritative without inventing unknown files.
- **Reserved output invariant was incomplete:** The submitted tool step treated `outputs/` as a direct child but did not state that publication is its only writer. The corrected step preserves inspection where allowed while rejecting file-tool mutations and shell working targets beneath the reserved subtree; it does not weaken Phase 05 output controls.
- **Warm-schema proof needed stronger boundaries:** Omitting obsolete DDL from fresh `schema.sql` does not prove active repositories ignore old structures. The corrected validation uses populated sentinels and repository/integration assertions to prove no active read, write, backfill, or fallback dependency while issuing no destructive DDL.
- **Status behavior needed a non-creating source model:** Output counts and last activity must come from active output/action repositories, not scans or obsolete workspace tables. Missing agent roots must be reported without creation. The corrected guidance states these sources and preserves lazy-root semantics.
- **Project and lease distinctions:** The submitted phase correctly protected assignment queue leases, but the corrected phase also requires preservation of project records, membership, events, attribution, network gating, and active assignment/job guards while removing only filesystem allocation, links, and leases.
- **Exit criteria were under-specified:** The corrected gate now covers conditional generic persistence, no legacy filesystem mutation, no active obsolete-schema access, reserved-output enforcement, constructor-graph validity, focused tests, full tests, and bounded fresh/warm startup.

### phase-07-mount-agent-files-tab.md

- The dependency on Phase 06 and placement in wave 07 are correct. Phase 07 can be executed by one implementation agent after the typed explorer/API, execution-selection contract, and obsolete-layer retirement have passed their gates.
- The original scope did not match the phase ledger: it added `WorkspaceController`, three extra test files, and omitted the ledger’s selector package targets. The corrected phase uses only the targets assigned to Phase 07. User-workspace API behavior remains owned by Phase 04; Phase 07 adds only operational-console fragment handling needed to refresh reusable selectors.
- The original controller guidance said each route resolves a descriptor, which could place existence and resolution policy in the controller. The corrected guidance requires `WorkspaceDirectoryService` to construct the descriptor and keeps roots and confinement logic out of the controller.
- The selection-control guidance was under-specified for paired-field semantics. The corrected phase requires either both `workspaceType` and `workspaceKey` or neither, represents bound-agent fallback by omitting both fields, rejects chat, and uses HTMX fragment replacement rather than JavaScript to keep type and key coherent.
- The explorer guidance omitted several contract-critical edge cases. The corrected phase explicitly preserves missing-agent behavior, lazy empty roots, reserved `outputs/` mutation rejection, root-mutation rejection, recursive-delete confirmation, path encoding, safe rendering, collision and limit errors, symlink/traversal rejection, and coherent OOB updates.
- The original validation named five tests outside the orchestration target set and did not explicitly prove paired selection behavior or reserved-output enforcement through the UI. The corrected validation aligns with the ledger and adds those assertions.
- The original exit criteria were directionally correct but did not require all-or-none selection fields, lazy empty-root behavior, visible controlled errors, or absence of duplicate modal hosts. These are now explicit.

### phase-08-close-documentation-and-records.md

1. **Package-guide scope does not match the orchestration ledger.** The phase omits `src/main/java/io/mindspice/magenta2/ai/AGENTS.md` and `src/main/java/io/mindspice/magenta2/api/AGENTS.md`, while adding four chat package guides not named as Phase 08 targets. The corrected phase uses the exact guide targets from the ledger and relies on the active-reference audit to report any out-of-scope guide drift rather than silently broadening edits.
2. **Phase validation and orchestration-level final integration are conflated.** The phase must run a complete phase-level integration-readiness campaign and obtain its validator PASS. The orchestration’s separate Final Integration occurs only once every phase has PASS; Phase 08 cannot claim that later orchestration gate itself.
3. **Failure repair crosses phase ownership.** The instruction to repair failures in prior implementation files makes the documentation phase open-ended and violates the one-agent target boundary. Failures attributable to Phases 02–07 must reopen the owning phase, be repaired there, and regain PASS before Phase 08 resumes. Phase 08 may repair only its own documentation and record defects.
4. **Current-plan archival is sequenced too early if treated as a Phase 08 edit.** The active eight-file plan must retain its exact membership through phase validation. Phase 08 may archive already-finalized or superseded records and prepare the active plan’s closeout state, but the orchestration owner archives the active plan directory only when final integration has passed.
5. **Validation is under-specified relative to the corrected concepts.** The original list does not explicitly cover descriptor lifecycle, execution-selection edge cases, tool confinement, output attribution, explorer preservation, metadata isolation, generic-layer retirement, warm-schema non-use, HTMX transport, and all required absence checks. The corrected phase adds a traceable validation matrix without duplicating implementation detail.
6. **Specification reconciliation needs a drift guard.** Phase 01 owns the superseding intended contract. Phase 08 may refresh observed anchors, status, and evidence, but it must not silently alter accepted architecture or decisions to match defective code.
7. **The changelog hash instruction needs an executable interpretation.** A changelog cannot contain the hash of the commit that contains itself. The corrected phase records `git rev-parse HEAD` at changelog creation as the validated implementation baseline and labels it accordingly; it does not guess a future commit hash or leave a placeholder.
8. **The phase remains executable by one implementation agent once boundaries are corrected.** Documentation, specifications, knowledge routing, changelog creation, audits, and local validation are coherent for one implementer. Delegated Playwright and the post-phase validator are validation roles, not additional implementation ownership.

## Risk Assessment

### concepts.md

- The greatest implementation risk is hidden coupling between Avatar UI code and the explorer. A deletion-first interpretation can remove reusable explorer behavior or leave stale Avatar assets and selectors.
- The greatest data risk is allowing active repositories to depend on obsolete `workspace_id` or `work_area_id` columns merely because existing databases still contain them.
- The greatest execution risk is partial cutover, where submissions use type/key but runners, tools, output publication, or resumable records continue resolving legacy roots.
- The greatest security risk is validating only lexical paths. Workspace keys, relative paths, symlink components, recursive trees, source real paths, destination confinement, and reserved-output boundaries all require independent checks.
- Conditional retention of a generic persistence component could accidentally preserve obsolete filesystem identity. Any retained component must have a documented non-filesystem responsibility and must not resolve roots, links, or leases.

### orchestration.md

The corrected schedule has no parallel-write risk because every phase occupies its own wave. The principal risk was scope leakage from incomplete write targets: it could have left active Avatar references, split one execution contract across Phases 05 and 06, or prevented complete records closeout. The corrected ledger assigns those surfaces to their owning phases while preserving all eight phase boundaries.

### phase-01-lock-superseding-contracts.md

- **High:** Incomplete inventory can leave active constructor, schema, resource, test, or documentation consumers unowned.
- **High:** Ambiguous generic persistence can preserve a second filesystem identity path or delete output persistence prematurely.
- **Medium:** Under-specified identity and selection rules can create conflicting downstream contracts.
- **Medium:** Over-broad term searches can erase required active removal statements or historical decisions.
- **Low:** The corrected seven-file scope is coherent for one agent when inspection remains targeted.

### phase-02-build-workspace-identity-foundation.md

- **High:** an unchecked constructor or externally constructible descriptor creates an untrusted identity path.
- **High:** incomplete constructor-call-site adaptation prevents compilation.
- **High:** symlink ancestors or roots can escape the application-owned tree.
- **Medium:** broad exception swallowing obscures identity and filesystem defects.
- **Medium:** optional-table assumptions make repository tests or startup brittle.
- **Low:** call-site fixture changes are broad but mechanical and bounded.

### phase-03-remove-avatar-product.md

- **Critical:** explorer behavior could be deleted and later reconstructed incorrectly.
- **High:** stale persisted `AVATAR` values can break reads.
- **High:** residual wiring could open or create user-owned `avatar.sqlite`.
- **High:** extraction mistakes could weaken traversal, symlink, root, reserved-`outputs`, collision, UTF-8, size, or recursive-delete protections.
- **Medium:** dormant neutral CSS could remain unusable due to deleted Avatar ancestry.
- **Low:** no phase reorder or plan-file-set change is required.

### phase-04-cut-over-explorer-and-api.md

The uncorrected phase had high risk of duplicate explorer ownership, accidental writes beneath reserved outputs, obsolete-schema recreation, and route compatibility drift. The corrected phase reduces these risks by making the Phase 03 handoff explicit, defining one descriptor-resolution path, separating canonical identity from normalized file paths, and requiring fresh/warm schema evidence. Residual risk is concentrated in filesystem race handling and filesystem/metadata consistency during subtree mutations; focused service and repository tests are required at the gate.

### phase-05-cut-over-execution-and-outputs.md

- **High:** Partial cutover can produce different roots across submission, resume, tools, and publication.
- **High:** Legacy-column reads can silently reactivate Work Area or generic workspace routing.
- **High:** Incorrect real-path, destination, or collision handling can escape confinement or overwrite files.
- **High:** File or shell tools can bypass publication ownership of `outputs/` unless the boundary enforces it.
- **Medium:** Deleting obsolete classes here overlaps Phase 06; leaving active callers blocks Phase 06.
- **Medium:** Incomplete waiting snapshots can resume under a different workspace.
- **Medium:** Transitional raw selectors can expose unsupported choices and duplicate Phase 07.

### phase-06-retire-obsolete-workspace-layers.md

**Medium.** The phase is deletion-heavy and crosses persistence, Spring wiring, project behavior, agent lifecycle, tools, prompts, selectors, schema, and tests. The principal risks are deleting active output persistence, retaining a hidden second root authority, confusing assignment queue coordination with filesystem leasing, mutating legacy schema or trees, and weakening `outputs/` protection. The ordered steps and validation matrix keep the work feasible for one implementation agent without parallel ownership.

### phase-07-mount-agent-files-tab.md

- **High:** Incorrect HTMX response composition can duplicate the modal host, leave stale inspector state, or swap successful fragments on an error status.
- **High:** A selector that independently submits type and key can create partial or mismatched execution selection and violate the Phase 05 contract.
- **High:** UI mutation forms can accidentally expose writes beneath reserved `outputs/` even when backend enforcement exists; both rendering and controller tests must cover the rejection.
- **Medium:** Reusing Avatar-era markup, selectors, or route builders can retain branding, unsafe IDs, or inconsistent path encoding.
- **Medium:** A missing physical agent workspace can be mistaken for a missing agent. The Files tab must render an empty workspace for an existing agent and return not-found only when the agent record is absent.
- **Medium:** User-workspace creation and selector refresh can accidentally duplicate the collection API or add a new administration surface. The operational route must remain a thin HTMX adapter over the established service contract.

### phase-08-close-documentation-and-records.md

- **High:** Treating Phase 08 as authority to repair prior implementation can bypass dependency gates and conceal regressions in phases already marked PASS.
- **High:** Archiving the active plan before orchestration-level final integration can remove the controlling plan while unresolved integration failures remain.
- **Medium:** Incomplete active-reference classification can either leave stale current guidance or wrongly delete legitimate historical and warm-schema evidence.
- **Medium:** Broad specification rewrites can erase Phase 01’s accepted contract instead of recording implementation drift.
- **Medium:** Documentation may overstate successful behavior if validation evidence is not linked to exact routes, schema modes, workspace kinds, and browser checks.
- **Low:** The closeout workload is broad, but its files and ordered operations are deterministic once prior phase evidence is available.

## Recommendations

### concepts.md

- Use the corrected concepts as the shared contract for all eight fixed phases.
- Make each phase gate verify compilation, focused tests, Spring wiring where changed, and stale-reference searches limited to that phase’s ownership.
- Require the execution phase to cut request DTOs, persistence, runtime context, tools, output publication, and read models as one coherent identity path before obsolete layers are retired.
- Require explicit evidence for retaining any generic workspace class or table; absent such evidence, remove it after artifact persistence is separated.
- Treat historical `.internal-dev` records and inert legacy database/filesystem artifacts as allowed history, while excluding them from active static-search failures and runtime behavior.

### orchestration.md

Use the corrected orchestration unchanged. Enforce one DeepSeek implementer for each phase, then one GPT validator with repair authority. Record PASS before releasing any dependent phase, and retain the final GPT integration gate after all phase PASS verdicts.

### phase-01-lock-superseding-contracts.md

Use the corrected phase. Preserve the ordered edits, establish evidence in step 1, record concrete consumers and later-phase ownership in step 3, classify stale references, and require PASS before commit or dependent execution.

### phase-02-build-workspace-identity-foundation.md

- Execute the corrected ordered steps without an unchecked compatibility constructor.
- Use repository existence boundaries; never infer entity existence from a directory.
- Keep legacy layout methods temporarily for later phases, but prohibit them from new descriptor and chat-file paths.
- Treat the user root as its declaration and expose stable service failure reasons.
- Require focused tests, broad tests, bounded startup, isolated filesystem assertions, source searches, and validator PASS.

### phase-03-remove-avatar-product.md

- Use the corrected phase supplied with this review.
- Extract service algorithms, renderer, styles, and regression tests before deleting Avatar ownership.
- Keep `WorkAreaExplorerService` only as a Phase 04 transitional adapter; add no public raw-root API, compatibility endpoint, or duplicate confinement implementation.
- Make all relevant persisted surface readers defensive while keeping unsupported client input invalid.
- Anchor startup/data-safety assertions in retained tests and require the validator to repair until all gates pass.

### phase-04-cut-over-explorer-and-api.md

Use the corrected phase as the implementation contract. Keep its ordered steps intact, update constructor wiring in the same revision as each boundary change, and reject any implementation that leaves Work Area overloads, raw-root inputs, duplicate route families, obsolete repository DDL, or client-supplied canonical identity active.

### phase-05-cut-over-execution-and-outputs.md

- Use the corrected ordered steps as the implementation contract under one implementer.
- Resolve once at submission, persist type/key and derived identity, and pass the descriptor through runtime, tools, instructions, and publication.
- Restrict existing-schema handling to new columns and deterministic agent attribution; prove obsolete structures are untouched.
- Complete artifact persistence extraction while leaving final obsolete-class deletion to Phase 06.
- Enforce publication-only writes beneath `outputs/`, with validation before lazy creation and atomic non-overwriting collision handling.
- Remove old UI controls without adding temporary raw selectors.
- Require fresh/existing-schema, resume, tool, publication, controller, full-suite, startup, filesystem, and stale-reference checks before PASS.

### phase-06-retire-obsolete-workspace-layers.md

- Execute the corrected 14 steps in order; do not delete a type until its active callers have been removed or moved.
- Treat the Phase 01 active-consumer inventory and persistence disposition as the deletion checklist. Any contradiction blocks deletion and must be reconciled with the superseding contract rather than hidden behind a shim.
- Keep `WorkspaceDescriptor` as the value crossing service boundaries and `WorkspacePathLayout` as the only structural path authority.
- Retain `OutputPublicationTarget` and `OutputArtifactRepository`; remove only obsolete generic workspace-backed output routing.
- Validate fresh and populated existing databases separately, with assertions that obsolete data remains unchanged and unobserved by active repositories.
- Require the configured medium validator review-and-repair gate to PASS before Phase 07 can begin.

### phase-07-mount-agent-files-tab.md

- Execute the corrected ordered steps without expanding the phase target set.
- Centralize fragment URLs in a small render-only route-prefix helper and keep descriptor construction, confinement, and mutation policy in existing services.
- Model selection rendering from an immutable view value containing the bound agent, sorted declared users, and current valid type/key pair; do not encode canonical identity or host paths into option values.
- Return controlled error fragments with the corresponding HTTP status and explicit HTMX targets; test success and failure swaps separately.
- Treat screenshots as a validation input: repair density, overflow, wrapping, modal sizing, and mobile stacking before requesting the validator gate.

### phase-08-close-documentation-and-records.md

- Use the orchestration ledger’s exact Phase 08 package-guide targets.
- Keep the thirteen ordered edit steps, but make ownership and gate behavior explicit.
- Treat code as observed truth and the corrected concepts plus Phase 01 specifications as intended truth; record mismatches as drift or defects.
- Run a complete phase-level readiness campaign, then obtain the medium-reasoning validator PASS. Leave the orchestration’s post-all-phases Final Integration to the orchestration owner.
- Preserve the active plan’s exact eight-file membership. Archive it only through orchestration closeout once final integration passes.
- Record the changelog’s Git Commit value as the current validated implementation baseline, with no placeholder or predicted hash.

## Follow-ups

### concepts.md

- Phase 01 should record the superseding specification and decision entries and the evidence-based disposition of generic workspace records, links, and filesystem leases.
- Phase 03 should prove all preserved explorer behavior has neutral ownership before deleting Avatar-only controllers, components, assets, tools, and tests.
- Phase 05 should prove every execution and output consumer uses one resolved descriptor and that no active fallback consults Work Area or generic workspace identity.
- Phase 08 should run the complete automated, startup, schema, API, static-search, diff, and delegated Playwright gates stated in the corrected concepts.

### orchestration.md

Before execution, validate the complete flat plan set with the planner validator. During each gate, compare actual changed files with the corrected target areas and reject unexplained scope expansion, stale old-contract references, placeholders, compatibility paths, or incomplete tests and records.

### phase-01-lock-superseding-contracts.md

- Phase 02 consumes the locked identity, descriptor, existence, and lifecycle contracts.
- Phases 03–08 use the inventory as their cutover checklist.
- No other phase file is edited during Phase 01.

### phase-02-build-workspace-identity-foundation.md

- Reconcile the orchestration target ledger with the corrected constructor-call-site and workflow-record scope before execution; do not change phase order or filenames.
- Phase 04 owns web decoding, typed routes, and status mapping.
- Phase 06 owns deletion of retained legacy layout and workspace layers.
- Phase 08 owns repository-wide documentation reconciliation; Phase 02 updates only directly affected package guidance and required records.

### phase-03-remove-avatar-product.md

- Phase 04 consumes the neutral handoff, removes transitional Work Area entry points, and re-keys metadata/actions.
- Phase 07 mounts the neutral renderer without reviving Avatar or Work Area presentation.
- Phase 08 reconciles current docs/package guides and performs repository-wide active-reference validation. Historical records and user-owned Avatar files remain untouched.

### phase-04-cut-over-explorer-and-api.md

No additional phase or plan file is required. The post-phase validator must confirm the Phase 03 dependency postcondition, focused tests, fresh/warm schema behavior, route enumeration, full tests, application startup, stale-reference searches, and final diff scope before reporting PASS.

### phase-05-cut-over-execution-and-outputs.md

- Phase 06 starts only after no active consumer uses obsolete workspace, Work Area, project-root, output-directory, link, alias, or filesystem-lease behavior.
- Phase 07 owns reusable user-workspace creation and execution-selection controls.
- Phase 08 owns final repository-wide documentation and record reconciliation. No additional phase file is needed.

### phase-06-retire-obsolete-workspace-layers.md

- Phase 07 may mount the Files tab and workspace-selection controls only after this phase passes; this phase must remove old controls without implementing their replacement.
- Phase 08 remains responsible for repository-wide documentation and record reconciliation and final integration validation. Phase 06 still records its own required phase closeout and commit evidence.
- No additional phase files, compatibility endpoints, migration utilities, or replacement workspace abstractions are warranted.

### phase-07-mount-agent-files-tab.md

- Phase 08 should perform repository-wide active-reference searches, documentation reconciliation, record closeout, and final integration validation. Phase 07 should not absorb those responsibilities.
- If implementation inspection shows that a required selector form is owned outside the Phase 07 target set, stop and revise the orchestration contract rather than editing an unlisted file.

### phase-08-close-documentation-and-records.md

- The orchestration owner must run the declared Final Integration with `openai-codex/gpt-5.6-sol:medium` once all eight phases report PASS.
- Any Phase 08 validation failure owned by an earlier phase must reopen that phase and invalidate dependent PASS state until repair and revalidation complete.
- The orchestration owner must archive the finalized active plan directory without changing its internal eight-file set, then include that move in the applicable closeout commit.
