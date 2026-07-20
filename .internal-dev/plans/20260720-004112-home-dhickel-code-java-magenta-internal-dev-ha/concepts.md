# Task-Run Storage, Scoped Skills, and Operational Observability

## Architecture

- Preserve the closed `WorkspaceKind` set: `AGENT`, `CHAT`, and `USER`. A task run is a child resource inside an effective `AGENT` or `USER` workspace, never a workspace kind or independently constructed workspace descriptor.
- `WorkspacePathLayout` owns the structural grammar for `tasks/`, `.skills/`, `outputs/`, and publication staging. `WorkspaceDirectoryService` remains the sole trusted `WorkspaceDescriptor` constructor and the authority for confined child locations.
- Map each native execution family to one canonical `taskRunId`. Reuse `plan_runs.id`, `workflow_runs.id`, or `job_runs.id` only where repository inspection proves one-to-one cardinality. Where identities differ, persist a distinct task-run ID and explicit native-run, assignment, workflow-child, job-run, and job-work-item links. `work_assignments.id` remains a separate assignment identity.
- Persist filesystem locations as `(workspaceKind, workspaceKey, rootRelativePath)`. Never persist absolute workspace, run, artifact, staging, package, or skill paths, and never infer workspace identity from `Path`.
- Treat SQLite and filesystem state as separate consistency domains. Run creation, publication, deletion, scheduler firing, and package installation use persisted operation keys, request digests, explicit states, uniqueness constraints, compensation, and reconciliation.
- Keep service boundaries narrow: workspace services own descriptors, confined locations, and reserved-path policy; run services own run identity, attempts, claims, and lineage; artifact services own publication, access, indexing, preview, integrity, reconciliation, and retention; skill services own discovery, assignment resolution, trust, snapshots, packages, and activation; runtime services own summaries and typed measurements; scheduler/reaction services own firing and causation semantics.
- Keep `WorkspaceExplorerService` filesystem-focused. Persistence-backed run, artifact, health, skill, schedule, and reaction data is rendered through separate reusable HTMX fragments.

## Conceptual Approach

1. **`phase-01-contract-path-migration-foundation.md`:** supersede the flat task-output contract, inspect and lock native-run cardinality, define canonical task/run keys, introduce the sole ordered migration owner, and establish trusted task-run child-location and write-capability types.
2. **`phase-02-run-lifecycle-fencing.md`:** reserve run identity before execution, create explicit attempts, issue monotonic writer capabilities, and persist lineage. Resume reuses a run/location; operator retry and replay create lineage-linked runs; bounded internal retries remain recorded attempts within the owning run.
3. **`phase-03-reserved-tree-confinement.md`:** centralize reserved-tree classification across every application mutation path, test link and race attacks, and establish a real subprocess boundary or keep affected workspace-bound executable behavior disabled.
4. **`phase-04-artifact-publication-reconciliation.md`:** publish through trusted run-local output locations with create-only final placement, persisted idempotency, integrity facts, fault recovery, and reconciliation for filesystem/database divergence. Do not open broad new artifact access before authorization-first access exists.
5. **`phase-05-artifact-access-search-retention.md`:** add authorization-first artifact metadata and byte access, safe download and preview, bounded search, explicit lineage, recoverable retention, and focused HTMX fragments.
6. **`phase-06-scoped-skill-resolution.md`:** normalize global package skills and flat workspace skills into provenance- and digest-qualified candidates, resolve deterministic scope precedence and exact-version assignment overlays, and keep pending or untrusted candidate-derived metadata out of model-visible disclosure.
7. **`phase-07-skill-trust-snapshots-packages.md`:** cut disclosure and activation to approved immutable snapshots, add revocation, controlled package ingestion, and separately gated executable authorization, then expose the Skills control plane.
8. **`phase-08-runtime-summaries-measurements.md`:** derive truthful summaries from native run facts and repeated typed measurements, then expose bounded health, history, and audit fragments without a speculative generic telemetry stream.
9. **`phase-09-schedule-reaction-hardening.md`:** harden schedules and reactions with claims, revision-qualified identities, explicit clock semantics, pure simulation, append-only-by-contract audit, causation, deduplication, and loop controls.
10. **`phase-10-operational-ui-integration-closeout.md`:** compose the cross-domain run/Files experience and complete migrations, specifications, docs, internal records, startup, real execution, security, recovery, browser, and final-diff validation.

## Features

- Stable `tasks/<taskPathKey>/<taskRunId>/` locations for task-capable `AGENT` and `USER` workspaces.
- Idempotent initial creation, resume, operator retry, replay, typed lineage, explicit attempts, fenced assignment leases, and fenced filesystem writer claims.
- Visible but structurally protected task trees; publication-owned output trees; hidden Skills control-plane roots.
- Run-isolated artifacts with staging, create-only promotion, deterministic collision handling, digests, integrity states, reconciliation, legacy compatibility, safe download, and recoverable lifecycle state.
- Bounded extraction, capability-probed SQLite FTS5, safe Markdown preview, authorization-filtered search, explicit producer lineage, pins, and retain-until-policy deletion.
- Scoped skills from the effective non-chat workspace, the distinct bound-agent workspace, a conditional authoritative project root, and the global repository; no chat, task-run directory, or service-account home scanning.
- Exact candidate assignment, digest-bound trust, immutable approved snapshots, reproducible activation, revocation, bounded package ingestion, and executable authorization separate from instruction approval.
- Run outcomes, active and wall-elapsed semantics, repeated model/tool measurements, truthful health denominators, and explicit unavailable legacy facts.
- A versioned and documented cron dialect, IANA timezones, restart misfire recovery, idempotent firing, reaction causation/deduplication/rate limits, and side-effect-free simulation.
- HTMX run, artifact, skill, runtime, schedule, and reaction surfaces with bounded expansion, legacy/unavailable/error states, desktop/mobile layouts, and no broad JavaScript transport.

## Constraints

- Do not add a workspace kind, task-run workspace descriptor, project filesystem root, generic workspace identity, or path-derived identity.
- `taskPathKey` is a versioned filesystem-safe digest of immutable definition identity. Phase 01 must lock its canonical input encoding and test vectors after inspecting actual definition identities. Display names and readable slugs are metadata and never rename historical paths.
- Assignment lease fences and run writer fences are separate monotonic capabilities. Every guarded transition and filesystem/publication mutation must verify the expected owner and fence token at the mutation boundary.
- Reusing an operation key with the same request digest returns the recorded result; reusing it with different input is a conflict.
- Resume reuses the same run ID and location through a new fenced attempt. Operator retry and replay create new run IDs with typed parent lineage. Internal bounded retries are explicit attempts or child-call measurements and never alias an uncontrolled writer.
- Chat remains non-task-selectable and publishes non-task artifacts under `workspace/chat/<id>/outputs/`.
- Legacy workspace-root `outputs/` files remain in place, queryable as legacy/non-task, and never receive new task-run artifacts.
- Final artifact placement is create-only. Publication must never replace an existing final file, including fallback paths where atomic move is unsupported.
- Integrity failure suppresses preview and download until reconciliation or an authorized disposition.
- Search authorization runs before counts, snippets, rows, lineage, metadata, and links, then is rechecked when bytes are opened or streamed through a bounded operation-scoped handle. The local/open-alpha HTTP posture must not be represented as multi-tenant authentication.
- Project skill discovery remains disabled unless repository inspection finds an existing authoritative project-root contract. Assignment overlays do not create one.
- Untrusted skill descriptions, hints, bodies, and resource metadata never reach model-visible prompts. Assignment, scope, provenance, ownership, or signature evidence cannot elevate trust.
- Skill executable authorization is distinct from instruction approval and activation. If Phase 03 cannot prove process confinement, executable-resource authorization and affected workspace-bound shell/script behavior remain disabled, and the UI states that limitation accurately.
- FTS5 is enabled only after probing the deployed SQLite runtime. Unsupported deployments expose an unavailable capability unless an explicitly approved bounded SQLite-compatible fallback is added without weakening authorization or extraction controls.
- Retention defaults to preservation and cannot override active attempts, claims, downloads, references, lineage pins, legal pins, or unresolved reconciliation.
- Schedule simulation uses a capability-free evaluator: it invokes no tools, publishes no artifacts, performs no external mutation, and creates no firing row that could be mistaken for execution.
- UI CRUD and partial refreshes use HTMX. JavaScript is allowed only for a narrowly justified interaction where it is demonstrably simpler.
- Preserve unrelated edits and compatibility outside the signed handoff. Each phase follows package guidance, internal-development closeout, validator review-and-repair, and phase commit rules.

## Assumptions

- The signed handoff supersedes specifications and package guidance that forbid task-run directories or require flat task-output publication. Phase 01 updates those contracts before downstream implementation relies on them.
- Native plan, workflow, job, assignment, and child-run cardinalities must be inspected rather than assumed. A native run ID is reused as `taskRunId` only when it is immutable, filesystem-safe after validation, and one-to-one with the intended run location; otherwise an explicit linked task-run identity is persisted.
- Native run tables remain status truth. Companion tables add location, operation, attempt, writer claim, linkage, summary, measurement, and audit facts rather than replacing native records.
- No authoritative project filesystem root is assumed. Project-local skill discovery stays disabled unless repository evidence proves an existing trusted root authority.
- Current host process execution is not assumed to protect reserved trees. The safe default is fail-closed affected shell/script behavior unless Phase 03 establishes and tests a real boundary.
- Model responses may omit token usage and process execution may omit final exit facts. Measurements preserve null/unavailable values rather than manufacturing zeroes.
- Global package skills are operator-managed, but trust remains provenance- and digest-qualified. Workspace and conditional project candidates remain pending and model-invisible until approved.
- The deployed scheduler topology is inspected and documented before claim semantics are finalized. No distributed coordination mechanism is added beyond the topology actually supported.

## Cross-phase Guidance

- Create one dedicated implementation branch before Phase 01. Commit a phase only after its required tests and validator review-and-repair gate pass.
- Phase 01 introduces `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java` as the sole ordered migration owner unless repository inspection finds an equivalent authoritative owner. Keep `schema.sql` equivalent for fresh databases, migrate existing databases through immutable ordered resources, and remove duplicate repository DDL for migrated structures.
- Record migration name and SHA-256. Applied-resource checksum drift fails closed; corrections use a new migration. Test interruption, restart, partial legacy state, and concurrent startup behavior.
- Preserve native run status ownership and normalize status only in read models. Retry/replay are lineage modes, resume is an attempt mode, and none is itself a terminal outcome.
- Carry trusted `TaskRunLocation` and `TaskRunWriteCapability` values through `OrchestrationTaskContext`; arbitrary run/output `Path` values must not cross execution boundaries. Existing host-path fields are removed or narrowed only after all consumers move to trusted values.
- Phase 02 run claims are prerequisites for mutable run working files and publication. Phase 03 reserved-tree and process-confinement findings are prerequisites for Phase 07 executable skill authorization.
- Phase 04 must enforce integrity and create-only publication immediately, but new preview, download, search, lineage, and broad metadata surfaces remain closed until Phase 05 authorization-first access is active.
- Phase 06 may discover and resolve candidates, but pending/untrusted candidate-derived content remains absent from model-visible catalogs. Phase 07 approved immutable snapshots are the only source for disclosure and activation.
- Phase 08 summaries and measurements are the factual source for Phase 09 firing/reaction links and Phase 10 health/history presentation; UI fragments must not infer unavailable facts.
- For each filesystem/database state machine, add fault-injection tests at every committed transition boundary and prove reconciliation converges or reports a safe unresolved state.
- Keep old rows honest: no inferred task attribution, historical directory creation, guessed digest, synthetic health value, automatic file movement, or fabricated schedule/reaction history.
- Testing, including Playwright, uses a `gpt-5.3-codex` subagent with medium reasoning under repository policy. The required `openai-codex/gpt-5.6-sol` validator at medium reasoning reviews evidence, repairs defects, and decides PASS for each phase.
- Before changing a package, read its nearest `AGENTS.md`. UI phases inspect the named SimplyPages documentation and demos and use reusable, slot-safe, HTMX-first rendering.
- Out-of-scope defects are logged under `.internal-dev/bugs/` and mirrored to GitHub as required; they do not silently expand a phase.
- A blocked real-execution dependency follows repository blocker policy. It is reported explicitly and is not replaced by unit-only evidence or marked complete without the required decision.

## Final Validation Criteria

- Fresh and representative existing databases migrate deterministically; reruns are no-ops; checksum drift fails; interrupted and concurrent migration paths recover safely; `schema.sql` matches migrated end state; FTS-supported and unavailable paths are tested.
- `AGENT` and `USER` task executions create only canonical run paths; `CHAT` selection is rejected; root relocation succeeds from workspace identity plus root-relative path; no service reconstructs identity from `Path`.
- Native/task-run linkage matches the inspected cardinality contract. Duplicate display names, rename/delete/recreate of definitions, Unicode/case/reserved names, maximum lengths, and canonical key test vectors cannot alias historical paths.
- Concurrent create/resume, same-key/same-digest replay, same-key/different-digest conflict, stale assignment owners, stale writer fences, restart recovery, internal retries, operator retries, resumes, and replays match the contract.
- Traversal, symlink swaps, parent replacement, hard links where supported, archive traversal, duplicate archive paths, oversized content, stale subprocesses, and every explorer/upload/import/copy/move/file-tool/shell mutation route cannot bypass reserved-tree policy.
- Publication retries cannot duplicate logical artifacts; final placement never replaces existing bytes; staged/published/failed/integrity-failed/deleting states reconcile after injected failures; file-without-row, row-without-file, stale index, interrupted deletion, and external mutation cases converge or remain safely unresolved.
- Unauthorized artifact scopes cannot observe counts, snippets, rows, lineage, metadata, links, or bytes. Authorization is rechecked at byte open/stream time. Preview limits, malformed Unicode, huge lines, false extensions, unsafe active content, remote tracking, and unsupported binaries fail safely.
- Skill root convergence, deterministic precedence, assignment specificity, stale exact-version binding, pending trust, digest changes, revocation, shadow conflicts, immutable snapshots, resume semantics, package traversal/symlink/archive/size attacks, and executable gating pass. No untrusted derived metadata reaches prompts.
- Run summaries cover all outcomes, retries, resumes, replays, repeated model/tool calls, and unavailable legacy data without misleading aggregation. Active and unavailable records are excluded from health denominators as defined.
- Scheduler tests cover competing pollers, claim fencing, restart misfires, documented cron dialect, timezone and daylight-saving behavior, retries, cancellation, overlap, and revision-qualified firing uniqueness. Reaction tests cover deduplication, causation depth/rate limits, and loops.
- Simulation invokes no tools, publishes no artifacts, performs no external mutation, and creates no execution-like firing record. The UI distinguishes simulation and unavailable history from executed or complete history.
- Focused service/repository/controller tests, migration tests, the broader Maven suite, bounded Spring startup, and real execution/recovery checks pass.
- Playwright covers Files/runs, outputs/search/preview, Skills/trust, runtime health/history, schedules, and reactions on desktop and mobile with screenshots and explicit visual critique. Standard interactions remain HTMX-first, and any JavaScript has a recorded narrow justification.
- Final diff review finds no placeholders, speculative APIs, absolute persisted paths, path-derived identity, accidental workspace kinds, moved legacy files, untrusted prompt leakage, duplicate schema ownership, weakened confinement, or unreported validation blockers.
