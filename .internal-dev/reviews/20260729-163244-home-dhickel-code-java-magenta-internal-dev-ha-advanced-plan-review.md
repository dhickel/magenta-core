# Advanced Plan Corrective Review Summary

## Scope

### decomposition.md

Reviewed the eight-phase large plan against the authoritative handoff, the fixed model routes, flat artifact contract, no-replace output semantics, and mandatory review-and-repair PASS gate.

### concepts.md

Reviewed the supplied shared concepts against the authoritative handoff and the eight immutable corrected phase files. The review addresses contract authority, phase seams, constraints, and acceptance evidence; it does not alter phase scope or implementation files.

### orchestration.md

Reviewed the fixed eight-phase Project Dashboard and Unified Work Contracts orchestration against the authoritative handoff and corrected concepts. The review covers the large-scope budget, dependency graph, wave order, required model routing, one-implementer rule, practical phase boundaries, and mandatory review-repair gate.

### phase-01-authority-and-assignment-foundation.md

Reviewed only `phase-01-authority-and-assignment-foundation.md` against `concepts.md` and the Phase Ledger. Its ledger metadata, dependency-free `wave-01` placement, and exactly-one-implementer model are consistent. The corrected phase remains one implementation phase, with mandatory contiguous A–D subphases; none is independently complete before phase-level validation and exit criteria.

### phase-02-project-queue-and-console-seams.md

Reviewed only `phase-02-project-queue-and-console-seams.md` against the supplied shared concepts and the Phase Ledger. Its ledger metadata is internally aligned: Phase 02 depends only on Phase 01, owns the six listed console/UI targets, and has the canonical goal `deliver the grouped shell and Queue proof over the shared projection`.

The phase is a bounded adapter and rendering seam over Phase 01 services, not a new assignment, project, workspace, transcript, or authorization authority. It reasonably fits one implementation-agent session without subdivision. The corrected phase retains eight contiguous implementation steps and requires one agent to complete all of them before the single phase-level validation and exit gate.

### phase-03-lightweight-project-tasks.md

Reviewed only `phase-03-lightweight-project-tasks.md` against the supplied concepts and Phase Ledger. The corrected phase preserves Wave 03, its sole dependency, and the ledger metadata verbatim. It consumes Phase 01 authority/capability proof and the Phase 02 console seam rather than recreating either.

The original combines persistence, transactional provenance, Plan promotion, submission, APIs, HTMX, migration proof, and browser validation. It is too broad as one undifferentiated sequence. The correction retains one implementation agent but divides the same ordered work into four contiguous mandatory subphases with one phase-level gate.

### phase-04-project-jobs-and-reassignment.md

Review covers only Phase 04 against the supplied concepts and ledger. It remains wave 04 after Phase 03 and preserves the ledger metadata verbatim. The cohesive Job-authority, migration, reassignment, and existing-console work is reasonably executable by one agent within the assumed session bound; no subphases are warranted.

### phase-05-materialization-templates-files-outputs.md

Reviewed only `phase-05-materialization-templates-files-outputs.md` against `concepts.md` and the Phase 05 ledger entry in `orchestration.md`. The corrected phase preserves the ledger-owned goal, dependency, and canonical write-target order. It keeps project association as attribution rather than a workspace kind, preserves canonical publication ownership, and does not alter another phase or the plan set.

### phase-06-project-automation-and-events.md

Reviewed only `phase-06-project-automation-and-events.md` against the shared concepts, Phase Ledger, Phase 05 dependency, Phase 07 handoff, and observed schedule/reaction seams.

### phase-07-project-chat.md

Phase 07 is correctly confined to restricted project chat over existing chat metadata and `ai_chat_memory`. It must not create a project workspace, transcript store, ordinary-chat capability, or direct execution/control path. The canonical ledger metadata is retained verbatim in the corrected phase.

The work is coherent for exactly one implementation agent: its migration, metadata boundary, profile, turn protocol, proposal boundary, web surface, and SSE client are tightly coupled. It reasonably fits an unsplit agent session under the stated practical limit, so its ordered steps remain implementation steps rather than independently completable subphases.

### phase-08-console-integration-and-closeout.md

Reviewed only `phase-08-console-integration-and-closeout.md` against the Phase Ledger, its direct dependency, orchestration wave/gate rules, and the supplied shared concepts. The phase is an integration and closeout phase, not an authority-creation phase.

## Findings

### decomposition.md

- The draft's phase boundaries are coherent and remain within the large-plan budget.
- The draft failed only because its machine-readable orchestration and phase metadata drifted from the planner contract: non-canonical target spelling, non-literal gate/integration sections, and phase files missing literal metadata and required headings.
- Phase 01, 05, 06, and 07 are broad but cohesive; their corrected steps are organized as contiguous lettered subphases where sequencing materially reduces one-agent context pressure. Their phase-level validator runs only after all listed subphases complete.
- No ownership boundary, model route, or publication rule requires a phase split or merge.

### concepts.md

1. **Authority wording conflicted with the Job boundary.** `WorkAssignment` is the canonical queue, assignment-lifecycle, lease, priority, and assignment-ownership record, but `JobRun` remains authoritative for native run state and result. Calling `WorkAssignment` the only execution-ownership authority obscured that split. The corrected concepts distinguish canonical assignment lifecycle from native-run authority and retain the required field-level matrix.

2. **Several assertions were presented as settled despite handoff evidence gates.** Public Job write ownership, exact immutable assignment fields beyond the handoff minimum, legacy materialization import safety, lifecycle revocation support, unbound-agent eligibility, and chat-memory behavior require repository verification. The corrected concepts retain the intended direction but make capability enablement conditional on the documented evidence.

3. **Archive and membership rules needed an operational race boundary.** The prior text blocked archive for active work and enabled automation but did not fully reconcile the integration phase's provisioning and active-turn/proposal guards. The corrected concepts require an atomic archive gate that prevents new operations and rejects archive or member removal while active dependencies would be stranded, while preserving immutable historical attribution and gated historical reads.

4. **Reassignment needed recovery semantics, not only revoke-and-clone language.** A source must not be left revoked without a durable successor outcome. The corrected concepts require a persisted operation and either one atomic transition or recoverable terminal resolution; successor creation requires an unleased source with no native `JobRun` and a fresh destination context/workspace binding.

5. **Cross-phase sequencing must not turn optional integration into hidden coupling.** The Job phase may integrate Task provenance when it exists, but Job authority and queued reassignment cannot rely on Task behavior for correctness. Shared contracts now require every cross-domain link to be optional, explicit, and independently validated.

6. **Sensitive-read and idempotency scope needed sharper qualification.** The project-operation gate is not caller authentication. It must be enforced through one capability decision at each service entry point, with controllers mapping the outcome, and disabled capabilities must remain unavailable. Operation keys require a server-defined actor/caller binding; where no trustworthy actor exists, the sensitive capability remains disabled rather than treating a client value as identity.

7. **Final validation omitted some handoff-specific proof obligations.** The corrected criteria add capability-disabled behavior, native-run boundary proof, durable reassignment recovery, archive/member-removal races, exact durable output-link parity, generic-chat denial, migration failure/drift cases, and UI accessibility/host-isolation evidence.

### orchestration.md

- `**Size**: large` is the correct classification: eight fixed phases satisfy the large budget of six through ten phases.
- The ledger contains each required phase exactly once and preserves migration order `014` through `019`.
- The sequential dependency chain is intentional and safe. It establishes assignment authority before projection UI, retains migration and shared-console sequencing before later domain additions, and prevents later surfaces from relying on unverified authority, materialization, automation, or chat seams.
- Phase 04 remains semantically independent of Task provenance despite following Phase 03; its ordering is for migration and shared-console sequencing, not a dual-domain runtime dependency.
- One phase per wave is appropriate because the phases share authority, migrations, console seams, and integration boundaries. No dependent phase can begin before the preceding validator has returned PASS.
- The fixed implementation route is exactly one `deepseek/deepseek-v4-pro:max` implementer per phase. The mandatory validator route is exactly `openai-codex/gpt-5.6-terra:high`.
- The post-phase review-and-repair gate is correctly expressed as an authoritative PASS barrier, and final integration follows only after all phases PASS.
- The phases remain cohesive implementation units. Any phase plan whose concrete implementation exceeds a single agent session must use internal lettered subphases without changing the fixed phase set; its validator runs only after all of that parent phase's lettered subphases complete.

### phase-01-authority-and-assignment-foundation.md

- The phase correctly establishes the prerequisite authority boundary before Queue, Tasks, Jobs, materialization, automation, chat, or console work.
- It needed explicit evidence gates for public Job authority, caller binding, lifecycle writer quiescence, and migration-runner compatibility. Contradiction must stop the capability, not create a second writer or optional scope.
- Archive/member-removal must protect present active assignment dependencies and leave later phases responsible for their own persisted dependencies without speculative generic machinery.
- Pagination needed a strict distinction between frozen ordered membership and live, scoped canonical re-resolution.
- Operation-key semantics needed server-bound actor, route scope, resource, expected revision, and canonical request semantics. Cancellation remains delegated to the established lifecycle.

### phase-02-project-queue-and-console-seams.md

- The original phase correctly positioned Queue as a proof that `AgentScope` and `ProjectScope` project the same canonical `WorkAssignment` rows. It needed stronger direction that every count, list, inspector, transcript, diagnostic, and control resolves a server-created scope before use and never reconstructs scope from route/query/body data.
- The original grouped-tab requirement risked implying that later-domain routes were implemented now. The corrected phase makes the registry authoritative for final navigation structure while limiting non-owned tabs to explicit controlled unavailable fragments; it does not create placeholder domain APIs or controls.
- Archive semantics required clarification. Historical Queue access remains subject to Phase 01 capability/access decisions, while archive state disables controls. The phase must not treat archive as permission to bypass attribution checks or as a reason to hide authorized historical records.
- The original route guidance did not fully distinguish exact-link reads from inference. The corrected phase permits transcript and diagnostics only through Phase 01’s exact persisted assignment links and authorized redacted readers. Missing links render an unavailable/absent state; names, timestamps, runtime maps, filesystem data, and historical membership are never fallback keys.
- Priority and cancellation are presentation adapters only. The corrected plan makes operation key, expected version, scope, capability, lifecycle, and Phase 01 fencing checks mandatory at the service boundary, and limits successful HTMX refreshes to Queue-owned hosts.
- `Queue documentation` was mentioned in scope but no documentation path exists in the canonical Phase 02 write targets; the corrected phase removes that scope drift. Phase 08 owns the listed documentation integration.
- The ledger does not list test source paths even though the phase requires focused tests. The plan preserves the required test work as validation/implementation coverage. If the ledger’s `targets` field is meant to prohibit all test-file edits rather than enumerate primary production targets, that is a planning conflict that must be corrected by rerunning orchestration before implementation.

### phase-03-lightweight-project-tasks.md

- The ledger goal, dependency, and ordered targets are correct and copied verbatim.
- A Work Task is a definition and submission origin, never a Plan alias or execution family. The linked Plan is executable source; `WORK_TASK/<task-id>` is immutable origin; visible state joins the designated attempt to canonical `WorkAssignment.status`.
- The original active-attempt requirement lacked a concrete persistence mechanism. The correction requires a migration-level uniqueness guard over canonical Work Assignment origin and Phase 01-proven nonterminal statuses, plus transactional attempt/designation creation.
- Promotion needed defined replay semantics. The correction records one immutable Task-to-Plan link and its operation outcome: identical replay returns it, changed semantics conflict, and a later request cannot create another Plan.
- Binding, pin, preferred-agent, priority-copying, archive, capability, scope, and no-fallback rules now have explicit command-time guards.
- `/api/work/tasks` is distinct from compatibility `/api/tasks`; Task routes require server-owned scope, redacted DTOs, expected revisions, and server-bound operation keys/digests.
- Migration proof must include fresh and `014`-to-`015` warm paths, checksum/partial-shape rejection, and metadata equivalence. The ledger omits test, runner, and documentation paths despite required proof and later documentation integration.

### phase-04-project-jobs-and-reassignment.md

- Public Job authority needed an explicit evidence gate and exclusion of internal `ai.agent.job.AgentJob`.
- “Rollback incomplete state” was ambiguous; a committed revocation must include successor, lineage, and recorded resolution.
- Trusted caller-bound idempotency, runner fencing, and archive/member/capability enforcement were underspecified.
- Task provenance must use the Phase 03 transactional seam only when verified; it cannot create a second writer or make ordinary reassignment depend on Tasks.
- The ledger permits only the existing `ProjectConsoleController` UI seam, not new fragments, CSS, or JavaScript.
- The old docs exit criterion exceeded the canonical write targets. Migration discovery changes outside the ledger also require escalation.

### phase-05-materialization-templates-files-outputs.md

- The original phase had the correct ledger metadata, dependency on Phase 04, and placement as the sole Wave 05 implementation unit. Its broad A–D steps, however, combined migration evidence, immutable persistence, hardened provisioning, Files authorization, Output authorization, UI work, and adversarial proof too densely for reliable one-agent ownership.
- The corrected phase divides that continuous work into six lettered subphases. They remain one Phase 05 implementation unit: all A–F work must finish before the single phase-level validation and exit criteria apply. No subphase has an independent acceptance or validation gate.
- The original exit criterion required documentation even though the Phase 05 ledger has no documentation write target and Phase 08 owns the listed documentation targets. The correction retains implementation-facing handoff facts but assigns published documentation integration to Phase 08.
- Legacy import was stated too optimistically. A `LEGACY_V1` generation is valid only after repository inspection proves an exact, byte-preserving historical mapping. A guessed root, moved bytes, or a fallback to a current generation would violate the shared materialization contract.
- Output authorization needed an explicit common resolver contract: list, metadata, preview, and download must make the same durable-link authorization decision, must not derive access from project text or scans, and must not replace the existing publication owner.
- The corrected UI guidance confines standard interactions to existing SimplyPages/HTMX patterns. There is no new JavaScript target or justification for JavaScript in this phase.

### phase-06-project-automation-and-events.md

- Ledger metadata is retained verbatim.
- The revision defect is concrete: definition `id` is a primary key despite append-revision writers. Updates cannot append, delete mutates history, and poll/dispatch do not select a current head. Migration 018 must rebuild definitions and dependent audit foreign keys.
- Original steps combined migration, modes, authorization, recovery, UI, and proof too broadly. Corrected contiguous A–F subphases remain one implementer’s phase and all complete before one validation gate.
- Trusted project attribution, every-service capability enforcement, no executor fallback, exact-snapshot recovery, scoped audit, and secret-free DTOs were missing or insufficiently explicit.
- Phase 06 documentation work conflicts with ledger targets; Phase 08 owns it.
- Sequencing is consistent: Phase 05 PASS precedes Phase 06, Phase 07 waits for Phase 06 PASS, and migration 018 requires contiguous 014–017 and runner discovery evidence.

### phase-07-project-chat.md

- The original phase incorrectly claimed documentation/specification updates although Phase 08 owns those write targets and integration closeout.
- Generic-route exclusion needs repository-enforced origin filtering and an evidence check for bypassing generic lookup paths; controller-only denial is insufficient.
- Conversation identity needs an exact immutable tuple and owner link, not merely project metadata. Turn and proposal operations must bind server-derived actor/context, route scope, canonical semantics, and expected target revision where applicable.
- Serialized idempotent turns require a durable, fenced per-conversation claim with recovery semantics. A database transaction cannot cover provider inference; only final message append and operation finalization may be atomic.
- The exact bound materialization generation must be revalidated as `READY`; it must never be substituted with a current generation or project-derived workspace.
- Queue proposals must remain reviewable intent. Confirmation must re-resolve the scoped canonical assignment and invoke `AssignmentControlService`; model output must never perform a control directly.
- The Phase 06 dependency is consistent with the ledger and migration sequence. It is an orchestration prerequisite: Phase 07 starts only after Phase 06 passes, and Phase 08 remains the sole dependent integration phase.

### phase-08-console-integration-and-closeout.md

- The supplied phase already matches the ledger goal, dependency, and canonical write-target order.
- Wave 08 may start only after Phase 07 has PASS; the ledger's linear waves make all earlier phase passes transitive prerequisites. Completion still requires the post-phase validator review-and-repair PASS, followed by the orchestration final-integration gate.
- The scope combines navigation integration, host isolation, cross-domain security and lineage evidence, UI review, migration/startup/build checks, browser coverage, documentation, and conditional development-record closeout. Although its code edits are concentrated in the console seam and documentation, the verification breadth makes an unsplit implementation sequence error-prone for one agent.
- The corrected phase therefore uses contiguous lettered subphases. They are implementation and audit work, not independent delivery gates: every subphase must be complete before the single phase-level Validation and Exit Criteria apply.
- The original instruction to repair an owning service conflicts with the ledger's closed write targets when such a repair would touch a domain-owned file outside this phase. The corrected guidance preserves authority: no page-level workaround is allowed, and a demonstrated out-of-target contract defect is escalated through the governing workflow rather than silently patched outside scope.

## Risk Assessment

### decomposition.md

The primary implementation risks remain assignment writer authority, migration upgrade safety, historical generation resolution, automation revision repair, and project-chat turn serialization. Sequential waves correctly protect shared migrations, schema, console seams, and cross-domain contracts.

### concepts.md

- **High:** Enabling mutation or sensitive read paths without a real capability boundary would expose project-scoped data and controls merely by route scope.
- **High:** Whole-row assignment persistence, dual Job writers, or reassignment without durable recovery can corrupt attribution, lifecycle, or lineage.
- **High:** Treating project identity as workspace identity, a request field, a filename, or an event payload would bypass the closed workspace and trusted-attribution contracts.
- **Medium:** Rebuilt revision schemas and generation migrations can lose historical reachability unless fresh, supported-warm, partial-structure, checksum, and metadata-equivalence cases are proven.
- **Medium:** Snapshot pagination, HTMX host replacement, and SSE panel replacement can produce stale or cross-scope rendering unless scope, identity, and target boundaries are checked at every fetch and mutation.

### orchestration.md

The principal risks are authority drift in Phase 01, migration/schema equivalence drift across the ordered migrations, and shared console integration across later phases. The existing serial waves and PASS barriers contain these risks. Advancing a later phase after a non-PASS review, adding a parallel authority, or treating current membership as historical attribution would violate the handoff and concepts.

### phase-01-authority-and-assignment-foundation.md

This is a high-coupling foundation, but is reasonably executable by one agent within the assumed session bound when performed as the corrected contiguous A–D subphases. Main risks are hidden whole-row writes, contradictory Job evidence, unsupported migration registration assumptions, and fencing that cannot prove safe control behavior. Each is an evidence gate: stop and escalate the affected contract rather than adding dual authority, compatibility writes, inferred scope, or page-local exceptions.

The ledger does not enumerate test fixtures, migration-runner registration, or documentation files although validation and repository workflow may require them. The corrected phase preserves the canonical target line verbatim and confines supporting work to established locations; it does not authorize implicit `.internal-dev` initialization.

### phase-02-project-queue-and-console-seams.md

- **High — authority or disclosure regression:** Direct repository reads, raw assignment models, inferred transcript lookup, or client-derived scope would violate the Phase 01 authority and redaction contract. All adapters must consume authorized Phase 01 DTOs and scoped services only.
- **High — false functional ownership:** Rendering future tabs as seemingly active controls would create unsupported behavior and misleading navigation. Non-owned tabs need a controlled unavailable fragment with no mutation route or client-side substitute.
- **Medium — host replacement regression:** A broad HTMX target, OOB swap outside the Queue domain, or shell replacement on cursor conflict can erase tab state or unrelated console content. List, inspector, transcript, and status hosts must remain isolated and stable.
- **Medium — semantic mismatch between agent and project Queue:** Separate query or DTO paths can drift in identity, ordering, redaction, cursor semantics, or lifecycle labels. Both views must invoke the same Phase 01 summary/projection contract with only the sealed scope varying.
- **Medium — validation-target ambiguity:** New controller/integration coverage may require test paths absent from the ledger. Treat an exhaustive interpretation of the target list as an orchestration defect, not permission to omit tests.
- **Low — JavaScript expansion:** `projects.js` may support presentation and history only. Fetching data, deciding authorization, submitting controls, or replacing HTMX behavior would exceed the phase and shared constraints.

### phase-03-lightweight-project-tasks.md

- **High:** an application-only active-attempt check or copied Task status permits races or a second lifecycle authority. The uniqueness condition must use only the Phase 01-proven schema/status vocabulary.
- **High:** compatibility Plan creation or bidirectional synchronization violates singular executable-source authority. Existing public Plan creation/execution validation must be evidenced first.
- **High:** caller-supplied project identity, inferred membership, or unscoped attempt links permit cross-project disclosure/submission. Service entry points need capability and canonical-scope enforcement.
- **Medium:** the fixed target list may not include every required test or migration-runner edit. Do not silently omit proof or create a parallel production path; escalate a true target conflict.
- **Medium:** the Tasks host must extend Phase 02 with isolated HTMX fragments, no raw-markup fallback, and no new JavaScript transport. SimplyPages docs/demo availability remains a UI prerequisite.

### phase-04-project-jobs-and-reassignment.md

The main risk is a runner racing revocation. Expected-version CAS, no-lease/no-JobRun eligibility, and decisive-boundary fencing are mandatory. Dual authority risks definition state being changed by runs or assignment state being derived from JobRun. Scoped detail/lineage views risk disclosure unless they use server-owned scope, canonical re-resolution, and redacted exact-link data.

### phase-05-materialization-templates-files-outputs.md

- **High — historical materialization integrity:** mutable generation data, V2 roots under a legacy root, lifecycle changes that alter generation identity, or fall-forward resolution would expose the wrong historical context.
- **High — filesystem confinement:** ordinary path normalization alone does not establish no-follow confinement. Template validation, provisioning, verification, and read paths must all reject symlinks and escape attempts without creating directories on reads.
- **High — output disclosure:** a partial relation check can expose a publication belonging to another project, reassignment predecessor, or unrelated run. The shared resolver must be the only authorization path for every output representation.
- **Medium — migration safety:** migration `017` must preserve the migration runner’s checksum, fresh-install, supported-upgrade, and metadata-equivalence contracts; constructor DDL cannot recreate superseded structures.
- **Medium — scope coupling:** Phase 05 consumes Phase 04’s durable assignment/run/lineage contracts but must not make Task provenance mandatory. It must represent an absent optional origin as absent and preserve the public Job/reassignment ownership boundaries.

### phase-06-project-automation-and-events.md

- **High:** parent-table rebuilds without child foreign-key rebuilds can orphan or reinterpret audit history.
- **High:** payload-derived project identity, controller-only gates, or unscoped audits can disclose or dispatch across projects.
- **High:** non-durable one-time identity or recovery through a newer revision can duplicate work after restart.
- **Medium:** legacy fragments, CSS/JS, docs, repositories, and migration-runner code are outside ledger targets unless evidence requires a ledger correction.

### phase-07-project-chat.md

High-risk boundaries are metadata immutability, generic-chat isolation, concurrent/retried provider calls, stale membership/archive/generation state, and unsafe streamed text. They are addressed by narrow repository APIs, short transactional checks around durable state only, per-conversation fencing, revalidation before commit, exact-link authorization, redacted bounded DTOs, and inert client rendering.

If repository evidence shows that the existing metadata model or generic routes cannot enforce the required origin/tuple boundary through the listed targets, the capability must remain disabled and the contract escalated. Do not add a parallel metadata or transcript authority.

### phase-08-console-integration-and-closeout.md

- A registry that is not the sole source of navigation facts can produce deep-link, active-state, availability, or host-ID drift.
- Broad HTMX targets, duplicate IDs, or cross-domain OOB swaps can corrupt a neighboring tab while appearing correct in isolated testing.
- Aggregate dashboard facts can leak scope or falsely imply an absent record is zero if they are inferred from filesystem data or non-exact joins.
- End-to-end happy paths alone do not prove archive/member-removal guards, capability-disabled behavior, stale revisions, leases/fences, generation bindings, durable output links, or generic-chat exclusion.
- Documentation or internal-record updates can falsely state a settled contract if they are written without passed evidence. The inactive store must not be initialized or repaired as part of closeout.

## Recommendations

### decomposition.md

Freeze the corrected eight-phase set. Treat each ledger entry as the sole metadata authority: phase Goal, Dependencies, and Write Targets must remain byte-for-byte aligned with it. Preserve the exact structured orchestration sections during any later revision.

### concepts.md

- Adopt the corrected concepts as the shared contract and retain the handoff's unresolved items as explicit evidence gates.
- Require the Phase 01 authority matrix to name each field, writer, repository method, lifecycle precondition, and consumer before enabling later controls.
- Treat service-level capability, scope, archive, membership, version, lease, fence, and generation checks as mandatory composition points; controllers and fragments must not reimplement weaker variants.
- Preserve independent domain ownership across Queue, Tasks, Jobs, materializations, automation, and chat. Use durable links for integration, not duplicated state or inferred joins.
- Require final validation to exercise enabled and disabled capability states and live UI behavior, including visual and accessibility review.

### orchestration.md

Retain the fixed phase ledger, linear waves, exact model assignments, and exact PASS-gate wording. Keep Phase 04's Job authority isolated from optional Task provenance, and require phase plans to apply internal lettered subphases only when needed for one-session executability.

### phase-01-authority-and-assignment-foundation.md

Adopt the corrected phase unchanged. Require the authority matrix to name each field, writer/service, repository method, predicate, guard, and consumer before mutation is enabled. Treat `AgentJob` as excluded from project authority unless evidence requires a bounded read adapter; it must never create dual writes or a union authority.

### phase-02-project-queue-and-console-seams.md

- Approve the corrected phase for one-agent execution after Phase 01 has PASS status. Do not split it: extraction, registry, adapters, rendering, controls, and focused proof form one coherent seam and are safely ordered within a single session.
- Require the implementer to inspect the delivered Phase 01 public contracts before writing adapters. If a required redacted summary, exact-link reader, sealed scope constructor, or control result is absent, stop that affected capability and escalate the contract; do not add a controller-local authority or permissive fallback.
- Keep the ledger goal and canonical target line unchanged. If tests cannot be edited under the target policy, rerun the orchestration review to add the necessary test targets; do not waive the validation requirement.
- Require the orchestration validator to verify the final diff for whole-row assignment writes, direct repository bypasses, scope widening, raw/sensitive DTO fields, non-exact transcript lookup, broad HTMX swaps, unjustified JavaScript, placeholders, and edits outside this phase’s seam.

### phase-03-lightweight-project-tasks.md

- Use corrected subphases A–D as one phase; none independently completes or releases Phase 04.
- Confirm Phase 01 exposes origin fields, terminal vocabulary, caller binding, capability service, assignment transaction, project/member guards, and Plan seam before implementation. Contradictory evidence stops the affected capability.
- Keep promotion narrow: one minimal `TASK_TEMPLATE` Plan via the proven Plan writer; submission checks Plan executability; Task and Plan edits never synchronize.
- Keep Phase 04's ledger dependency unchanged. It is sequencing/migration-console ordering, not permission for Task behavior to become a Job prerequisite.
- Retain exact ledger targets. Raise any required target-list conflict through orchestration; do not alter the ledger or another phase from this review.

### phase-04-project-jobs-and-reassignment.md

Adopt the corrected contiguous eight-step phase. Complete every step before the single phase-level validation and exit criteria. Verify the Phase 01 matrix and Phase 03 atomic Task-attempt participant before enabling their affected paths; contradictory evidence stops that path for escalation, never creates compatibility authority or inferred provenance.

### phase-05-materialization-templates-files-outputs.md

- Execute the corrected A–F sequence as one implementation phase after Phase 04 has passed. It is reasonably one-agent executable once divided into the stated contiguous work units; do not split it into independently accepted phases.
- Treat repository evidence for the legacy mapping, materialization state ownership, publication relations, and migration baseline as capability gates. Contradictory evidence stops the affected capability and requires contract escalation rather than a compatibility write, inferred location, or parallel authority.
- Keep `WorkspacePathLayout` as the sole structural-path authority and `WorkspaceDirectoryService` as the only constructor of trusted workspace descriptors. Project identity must never be reconstructed from a path or promoted to a workspace type.
- Require the orchestration-designated post-phase validator to review and repair the completed phase before Phase 06 begins. Its browser validation must be delegated as required by the project workflow and must include changed-surface screenshots and a desktop/mobile visual critique.

### phase-06-project-automation-and-events.md

1. Use corrected A–F. It reasonably fits one agent under the assumed session bound only with these contiguous boundaries; no new orchestration phase is needed.
2. Treat runner support, Phase 01 access/caller binding, project/member guards, and assignment origin as evidence gates. If absent, stop for ledger correction rather than adding authority or unlisted edits.
3. Transactionally insert revision, CAS head, and reserve idempotency; recover only from recorded snapshots.
4. Keep the ledger goal verbatim; only renewed orchestration review may change it.

### phase-07-project-chat.md

Use the ledger goal verbatim even though its wording is terse; it accurately matches the phase. Keep the canonical dependency and target list unchanged. Complete all ordered implementation steps before applying the single phase-level validation and exit gate. Do not add documentation, navigation consolidation, or broad console integration here; those belong to Phase 08.

### phase-08-console-integration-and-closeout.md

- Keep the ledger metadata verbatim and retain Phase 08 in wave 08 with one implementation agent.
- Require the ordered A–F subphases to finish before one consolidated validation pass; do not accept a subphase as independently complete.
- Restrict edits to the ledger targets. Escalate any defect requiring a new authority, lifecycle rule, service contract, schema change, or an out-of-target domain repair; do not mask it in controller, fragment, CSS, JavaScript, or documentation code.
- Update `.internal-dev` records only when the store is already ready and only with validated facts. If the store remains unavailable, do not create or repair it implicitly.

## Follow-ups

### decomposition.md

Before execution, validate the published flat plan directory with the deterministic planner validator. During implementation, stop and escalate if repository evidence contradicts the authority matrix or closed workspace model.

### concepts.md

- Record repository evidence for the authority matrix, Job write owner and legacy-read adapter, schema/index feasibility, unbound preferred-agent eligibility, archive/member-removal mechanisms, and running-writer quiescence.
- Record the server-defined caller binding used by operation keys and every sensitive capability that remains unavailable without it.
- Record migration compatibility boundaries, legacy-generation import evidence, and the precise durable-link graph used by transcripts, outputs, task attempts, native runs, and reassignment lineage.
- Escalate any contradiction between those findings and the shared contract instead of broadening a phase or introducing a compatibility authority.

### orchestration.md

None. The corrected orchestration is ready for submission under the fixed phase set.

### phase-01-authority-and-assignment-foundation.md

- Phase 02 consumes only sealed scopes and redacted `AssignmentQueryService` projections.
- Later phases add their own archive/member-removal guards before enabling their persisted active dependencies.
- Phase 04 completes public Job authority and reassignment only after this matrix and fencing proof pass.
- Complete project records only through an available workflow; do not create or repair `.internal-dev` implicitly.

### phase-02-project-queue-and-console-seams.md

- Phase 03 may begin only after this phase’s post-phase validator review-and-repair PASS; it must consume the established console seam rather than redefine Queue scope or shell ownership.
- Phase 08 remains responsible for cross-domain console consolidation and the documentation targets in the ledger. Phase 02 must leave later-domain registry entries controlled and non-authoritative.
- Record any evidence that Phase 01 cannot supply the required scoped/redacted/exact-link seam as a contract escalation before dependent work proceeds.

### phase-03-lightweight-project-tasks.md

- Require orchestration post-phase validator review-and-repair PASS after the complete A–D validation set.
- Phase 04 starts only after that PASS.
- The workflow reports no initialized internal-development store; do not initialize one implicitly.

### phase-04-project-jobs-and-reassignment.md

- Post-phase validator PASS is required before Phase 05.
- Phase 05 consumes reassignment history only through durable relations.
- Any migration-runner, public-Job-owner, or Task-seam contradiction requires orchestration/specification review before metadata changes.

### phase-05-materialization-templates-files-outputs.md

- Phase 06 may consume only passed Phase 05 durable materialization and output contracts; it must not add a second materialization or output authority.
- Phase 07 must bind project-chat conversations to the exact ready generation resolved by this phase, not a current logical head.
- Phase 08 owns integration of end-user and technical documentation targets. It should document only the behavior actually proven by this phase’s validation.

### phase-06-project-automation-and-events.md

- Record evidence and closeout only if the internal-development store is ready.
- Phase 07 consumes only passed Phase 06 contracts.
- Phase 08 owns documentation integration. No other phase file changes in this review.

### phase-07-project-chat.md

Phase 08 should integrate only the passed project-chat surface and perform the planned documentation/specification and final console audit. Any newly discovered reusable constraint should be recorded through the established internal-development workflow only when that store is available; it must not trigger implicit store initialization.

### phase-08-console-integration-and-closeout.md

- No plan-file or ledger change is required. If evidence invalidates the ledger goal, targets, or a passed predecessor contract, re-run the applicable orchestration review before changing canonical metadata.
