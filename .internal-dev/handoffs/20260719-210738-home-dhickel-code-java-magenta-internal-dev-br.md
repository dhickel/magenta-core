# High-Level Handoff: Skills, Task-Run Storage, and Operational Observability

## Context

Magenta currently has a closed workspace model (`AGENT`, `CHAT`, `USER`), centralized structural path authority in `WorkspacePathLayout`, trusted descriptor construction in `WorkspaceDirectoryService`, flat workspace output publication, and a single global Agent Skills repository. The Files surface is descriptor-first and HTMX-based, while output metadata, runtime status, schedules, and skill assignments are primarily database-backed.

The desired product direction connects three capabilities without collapsing them into one subsystem:

- durable task-run identity and isolated run storage;
- scoped, provenance-aware skills using the existing catalog-and-activation lifecycle;
- trustworthy artifact, runtime, schedule, and reaction visibility.

Task-run storage is the shared foundation, but skills, artifact search, runtime summaries, and schedule/reaction management remain independently bounded capabilities with separate acceptance criteria. Filesystem state and SQLite state cannot be assumed to change atomically, so every cross-boundary workflow must be idempotent, recoverable, and reconcilable.

## Objective

Deliver an operational model in which:

- each task execution has a stable, confined filesystem location inside its effective workspace;
- published outputs are isolated by run, indexed with accurate attribution, safely previewable, searchable, and governed by explicit lifecycle rules;
- agents discover skills from approved scopes through one normalized pipeline, with deterministic resolution, content-versioned trust, progressive disclosure, and reproducible activation;
- users can inspect task runs, outputs, runtime health, schedules, and reactions through dense SimplyPages/HTMX surfaces;
- resume, replay, restart, concurrent access, malformed content, trust changes, authorization changes, and interrupted file/database operations have explicit behavior;
- the existing three-kind workspace contract, descriptor authority, and root-relative persistence rules remain intact.

## Targets

### Workspace and run storage

- `io.mindspice.magenta2.ai.orchestration.workspaces`
  - `WorkspacePathLayout`
  - `WorkspaceDirectoryService`
  - `WorkspaceDescriptor` and `WorkspaceKind` consumers, without adding a workspace kind
  - a sibling reserved-directory policy
  - a trusted task-run location/capability type if needed to prevent arbitrary destination paths
- Execution callers that select workspaces or publish outputs, including:
  - `WorkflowRunner`
  - `OrchestrationRunnerService`
  - `PlanService`
  - compatibility paths in `TaskService`
- Task/run persistence used by execution, resume, retry, replay, writer claims, and recovery.

### Output artifacts and Files experience

- `OutputArtifactService`, `OutputArtifactRepository`, and `RunOutputArtifact`
- `WorkspaceExplorerService` and reusable Files fragments
- `OutputController` and separate run/output HTMX fragments
- artifact preview, search, reconciliation, integrity, and retention services
- legacy flat-output query paths.

### Skills

- `WorkspacePathLayout` and `WorkspaceDirectoryService` for confined `.skills/` roots
- `io.mindspice.magenta2.ai.skills`, especially:
  - `AgentSkillRepositoryService`
  - `AgentSkillCatalogService`
  - `AgentSkillRuntimeCatalogService`
  - `AgentSkillAgentContextResolver`
  - `AgentSkillAssignmentService`
  - `AgentSkillActivationService`
  - `AgentSkillPromptCatalogAssembler`
- a scope-discovery boundary and a richer `SkillResolutionContext`
- Skills controllers/fragments for provenance, trust, assignment, approval, revocation, and controlled editing
- `ToolAccessPolicy` and any shell/file capability checks affected by skill execution.

### Runtime, schedules, and reactions

- orchestration status and run-summary persistence
- typed repeated measurements where a run can contain multiple model or tool calls
- agent/run health and history fragments
- schedule and reaction services, DTOs, controllers, audit persistence, and HTMX management surfaces.

### Contracts and documentation

- affected architecture, service, API, web, and SimplyPages specifications
- user and technical documentation for paths, run behavior, skills, output search/preview, schedules, reactions, and retention
- focused knowledge and changelog records required by the project workflow.

## Features

### Stable task-run locations

Use the following logical layout for task-capable workspaces:

```text
<workspace-root>/
  tasks/
    <taskPathKey>/
      <taskRunId>/
        outputs/
        ...working files...
```

`taskPathKey` is an immutable, filesystem-safe key derived from stable task identity, not a mutable display name. A readable slug may accompany the stable ID, but the stable ID remains authoritative. Task display names live in persisted metadata and UI labels; renaming a task never renames historical directories.

The unique persisted run-location identity is:

```text
(workspaceKind, workspaceKey, rootRelativeRunPath)
```

Absolute paths are never persisted. `taskRunId` is filesystem-safe and immutable. If a generic orchestration run ID differs from the task-run ID, both are persisted with explicit linkage rather than treated as interchangeable. Database uniqueness constraints enforce the run identity, location identity, and replay/idempotency keys used by creation commands.

Resume reuses the same task-run location. A writable run requires an exclusive persisted writer claim with a fencing token; claim heartbeat/expiry and abandoned-claim recovery are explicit, and stale writers are rejected even if they continue running. A filesystem lock may supplement but does not replace the persisted fenced claim. Replay creates a new task-run ID and records parent-run lineage. Retry/attempt semantics must be explicit in persistence and UI; they must not silently alias an existing writable run. Repeated creation, resume, retry, or replay commands with the same operation key resolve to the same recorded result.

Task-run nesting applies to `AGENT` and `USER` workspaces because both may be effective execution workspaces. `CHAT` is not task-selectable and keeps conversation output publication at `workspace/chat/<id>/outputs/`. Chat-generated outputs are labeled as non-task artifacts and are not presented as task runs.

### Run creation, publication, and reconciliation

`WorkspacePathLayout` remains the only component that defines structural names and path grammar. `WorkspaceDirectoryService` remains the only descriptor authority and the boundary that provisions confined run locations.

Artifact publication uses a trusted run-output destination obtained from the workspace service, not an arbitrary caller-constructed `Path`. `OutputArtifactService` remains responsible for artifact materialization and persistence, while structural path construction remains outside it. The boundary may be represented by a typed run-output location or by mandatory validation against descriptor and run identity.

Publication must provide:

- a stable publication operation key and database uniqueness constraint so retries cannot create duplicate logical artifacts;
- staging within the confined destination;
- atomic promotion where the filesystem supports it, with an explicit recoverable fallback where it does not;
- deterministic collision handling;
- immutable published bytes from application-managed routes;
- digest and size recording;
- explicit states for staged, published, failed, integrity-failed, and deleting content;
- cleanup of abandoned staging files;
- reconciliation for file-without-row, row-without-file, digest mismatch, stale search index, and interrupted deletion cases.

If an external process mutates published bytes, digest verification marks the artifact integrity-failed. Unsafe preview and download are suppressed until reconciliation or an authorized disposition; the system does not continue to describe the bytes as immutable.

Legacy root-level `outputs/` content remains readable and queryable in place. It receives no new task-run artifacts and is not automatically moved. Legacy records without task attribution are shown honestly as legacy/non-task outputs.

### Reserved-directory behavior

Centralize structural classification in a sibling policy that consumes names owned by `WorkspacePathLayout`:

- `outputs/` at workspace or run scope: visible, publication-owned, application-managed mutation rejected;
- `tasks/`, task-key directories, and run-ID directories: visible, structurally protected from rename/delete;
- ordinary run working files: browsable and mutable under normal confinement rules and the active writer claim;
- `.skills/`: hidden from Files by default and exclusively managed through the Skills control plane.

The policy must be enforced across application-managed explorer, upload, archive extraction, import, copy/move, and file-tool routes. Shell or subprocess access is not made safe by UI checks: reserved-tree protection requires sandbox or capability restrictions at that boundary. Symlink traversal, parent replacement, hard-link behavior where relevant, and check/use races must be covered by confinement design and tests.

`WorkspaceDirectoryService.resolve()` continues to return the containing `AGENT`, `CHAT`, or `USER` descriptor for paths below `tasks/`; task runs do not become workspace identities.

### Run-aware Files experience

Keep `WorkspaceExplorerService` filesystem-focused. Compose persistence-backed run and artifact metadata through separate reusable HTMX fragments.

The Files surface provides:

- task grouping with display label and stable identity;
- run status, timestamps, attempt/replay relationship, artifact count, integrity state, and recent outputs;
- pagination or bounded expansion for large run collections;
- clear legacy-output treatment;
- artifact search and safe preview without coupling raw explorer operations to artifact persistence;
- useful empty, unavailable, missing-file, integrity-failed, and partially reconciled states.

### Normalized scoped skills

Support workspace-authored flat `.skills/*.md` files as lightweight skill packages without introducing an always-loaded prompt path. Optional constrained frontmatter supplies metadata; filename and body provide safe fallbacks. Flat files are adapted into the existing parsed skill model and continue through catalog disclosure, `activate_skill`, and activation deduplication.

The global Magenta repository may retain package-format `skill-slug/SKILL.md` skills with optional `scripts/`, `references/`, and `assets/`. Flat workspace skills do not imply package-resource support.

Every candidate has a stable identity containing at least:

```text
(logicalKey, scope/root identity, provenance, content digest, format/version)
```

Discovery and assignment remain separate:

- filesystem presence determines available candidates;
- DB-backed assignment overlays determine enabled/disabled intent at agent, project, job, task/workflow, and chat/session scopes;
- assignments that authorize execution bind to a provenance-qualified candidate version, not only a logical name.

Supported discovery roots are limited to roots with authoritative ownership:

1. effective non-chat execution workspace `.skills/`;
2. bound agent workspace `.skills/` when physically distinct;
3. an authoritative project workspace `.skills/`, only when an existing trusted project filesystem-root contract is found;
4. global Magenta `skills/`.

The same physical root is scanned once, including when effective workspace, agent, or project resolution converges on one root. Service-account home-directory scanning is excluded because it is not a tenant-safe substitute for a Magenta user workspace.

No `.skills/` discovery occurs in chat workspaces or task-run directories. Task-level behavior is expressed through assignment overlays against cataloged candidates.

### Skill trust, shadowing, and reproducibility

Trust is content-versioned, provenance-bound, and separate for instructions and executable resources.

- Global skills are operator-managed but still subject to assignment.
- Agent and effective-workspace skills are discovered as pending until approved through the Skills control plane. Conditionally supported project-workspace skills follow the same rule.
- Out-of-band byte or metadata changes produce a new digest and return the candidate to pending review.
- Untrusted content, including untrusted descriptions and activation hints, is not included in model-visible catalog text.
- A higher-precedence untrusted candidate does not silently execute and does not silently fall back to a different lower-precedence implementation. The logical-key conflict is surfaced for explicit resolution.
- Chat/task/job/project overlays cannot elevate untrusted content.
- Activation uses an immutable approved snapshot and records exact candidate identity, digest, scope, and trust decision with the run.
- Resume uses the recorded snapshot. A revoked snapshot remains available for audit but cannot be reactivated without an authorized decision.

Script authorization is independent of instruction approval and skill activation. Executable resources require explicit version-specific authorization, confined execution, resource limits, actor/run audit data, and a capability policy that does not allow skill instructions to grant themselves shell or file authority. If runtime confinement is unavailable, instruction content may still be reviewed and approved as an auditable control-plane action, but executable-resource authorization remains disabled and the UI must not claim complete runtime protection.

Package ingestion stages outside active roots, rejects traversal/symlinks/archive bombs/oversized content, validates metadata and package-tree digests, records source and signature evidence, and installs atomically only after approval. Provenance evidence never implies automatic trust.

### Artifact indexing, preview, lineage, and retention

`RunOutputArtifact` remains the metadata index. Persist run/workspace attribution, root-relative file path, content digest, size, media type, publication state, and task/run grouping fields needed for authorized lookup.

Search and preview behavior includes:

- bounded extraction for an allowlist of text formats;
- verified media detection rather than filename-only trust;
- strict byte, line, parser, and processing limits;
- safe Markdown rendering with raw HTML, active content, remote tracking, and unsafe links disabled;
- binary and unsupported content as metadata/download-only;
- authorization filtering before counts, snippets, result rows, previews, lineage, or download links are produced;
- authorization revalidation when artifact bytes are opened or streamed, using a bounded handle or equivalent mechanism that cannot outlive the authorized operation;
- SQLite FTS5 only when supported by the deployed stack, with tested migrations, resumable backfill, synchronization, and rebuild/reconciliation paths.

Run-level lineage is authoritative. Artifact parent edges are recorded only when producers explicitly identify consumed artifact IDs. Cross-workspace edges require authorization, cycle prevention, and retention pinning.

Retention acts on completed run directories, artifact rows, preview/search state, and lineage references as one recoverable workflow. The safe default is retain-until-explicit-policy. Policy precedence is deterministic: task policy may request earlier deletion or longer preservation than a workspace default, but active, resumed, claimed, downloading, referenced, legally pinned, or reconciliation-pending runs are protected regardless of policy. Deletion uses an idempotent operation key and tombstoned state so interrupted deletion can resume without making missing content appear available.

### Runtime observability

Define outcome and elapsed-runtime semantics for success, failure, cancellation, timeout, interruption, partial completion, retry, resume, replay, and abandoned execution. Health denominators exclude active and unavailable records and expose unavailable data rather than fabricating values.

Compute trustworthy summaries from existing typed statuses and timestamps. Persist facts that do not exist, such as final process exit data or aggregate token use, in typed run summaries. Where a run has multiple model or tool calls, use repeated typed measurements rather than lossy single-value columns. A generic JSON event stream is excluded unless a demonstrated debugging query cannot be answered by statuses, summaries, or typed measurements.

Per-run audit includes workspace selection, exact activated skill versions, trust decisions, writer-claim identity, schedule/reaction causation, publication outcomes, and correlation IDs. Skill-to-output attribution is recorded only when the producer can state it reliably.

### Schedule and reaction management

Provide typed, timezone-aware schedule and reaction contracts plus SimplyPages/HTMX list, editor, enable/disable, preview, simulation, and audit fragments.

Runtime behavior must define:

- a single scheduler ownership/claim model compatible with the deployed topology;
- idempotent firing keys backed by uniqueness constraints and duplicate suppression;
- restart recovery and explicit misfire behavior;
- cron dialect, timezone, and daylight-saving overlap/gap semantics;
- retry, cancellation, and concurrency rules;
- side-effect-free preview/simulation through a capability-free evaluator;
- simulation output as a typed explanation that invokes no tools, publishes no artifacts, and creates no firing record that can be mistaken for execution;
- append-only-by-contract firing audit with actor, correlation, assignment/run, and output links;
- reaction causation chains, deduplication, depth/rate limits, and loop prevention.

The UI must not imply that simulation executed real tools or that unavailable firing history is complete.

## Settled Decisions

1. `WorkspaceKind` remains closed to `AGENT`, `CHAT`, and `USER`; task runs are child resources.
2. `WorkspacePathLayout` owns `tasks`, `.skills`, and `outputs` structural names and all path grammar.
3. `WorkspaceDirectoryService` remains the sole trusted descriptor and confined run-location authority.
4. Structural task directories use immutable stable identity, not mutable display names.
5. `AGENT` and `USER` are task-capable effective workspaces; `CHAT` is not.
6. Resume reuses a run; replay creates a run with lineage; concurrent mutation requires an exclusive persisted fenced writer claim.
7. Persist root-relative locations with workspace identity; never persist absolute workspace paths.
8. Legacy flat outputs stay in place, remain queryable, and receive no new task-run publications.
9. Reserved-directory policy is centralized, but it does not replace shell/subprocess confinement.
10. Flat workspace Markdown skills are normalized into the existing progressive-disclosure pipeline; no parallel always-loaded prompt path is introduced.
11. Skill discovery, assignment, trust, executable authorization, and activation are distinct layers.
12. Skill authorization binds exact provenance and digest; logical-name-only enablement is insufficient for execution.
13. Agent-authored workspace content is not inherently trusted. Approval is digest-bound and invalidated by change.
14. Untrusted shadowing produces an explicit conflict, not silent execution or fallback.
15. Chat and task-run `.skills/` discovery is excluded.
16. Service-account home scanning is excluded; project roots require an existing authoritative root contract.
17. Script authority is separate from instruction trust and activation.
18. Explorer remains filesystem-focused; run/artifact metadata is composed through separate fragments.
19. Search and preview are authorization-first, bounded, and reconcilable; authorization is rechecked at byte access.
20. Run summaries and typed repeated measurements are preferred over speculative generic event storage.
21. Artifact-level lineage is opt-in from explicit producer declarations; empty placeholder lineage fields are not added.
22. Retention defaults to preservation until an explicit policy exists and never overrides active, claimed, referenced, or reconciliation safety pins.
23. Schedule/reaction UX does not define the runtime contract; idempotency, recovery, clock semantics, and loop prevention are required backend behavior.
24. HTMX is the default transport for all management surfaces; JavaScript is limited to interactions that are demonstrably simpler and remains narrowly scoped.
25. Run creation, artifact publication, deletion, and scheduler firing use persisted idempotency identities and database uniqueness constraints; in-memory deduplication is insufficient.
26. Published-byte integrity failures are explicit and cannot be presented as healthy immutable artifacts.

## Constraints

- Preserve all unrelated edits and existing compatibility contracts.
- Do not hand-concatenate workspace, run, output, or skill structural paths outside the path authority.
- Do not reconstruct workspace identity from `Path`.
- Do not persist absolute workspace paths.
- Do not add `TASK_RUN` or any other workspace kind.
- Do not auto-create empty skill repositories during read/discovery.
- Do not expose untrusted skill-derived metadata to prompts.
- Do not let activation grant tools or script execution implicitly.
- Do not claim `.skills/` or published-output protection if shell/subprocess routes can bypass it; either enforce the boundary or keep affected executable behavior disabled and report the limitation accurately.
- Do not treat lexical normalization alone as confinement; account for real-path and link/race behavior.
- Do not use arbitrary producer-provided media types as proof of safe previewability.
- Do not expose unauthorized search snippets, counts, lineage, download metadata, or bytes.
- Do not move legacy output files automatically.
- Do not claim filesystem/SQLite atomicity; use idempotent state transitions, uniqueness constraints, and reconciliation.
- Do not add speculative event stores, generalized workflow frameworks, or unused schema placeholders.
- Do not place ordinary CRUD transport in custom JavaScript.
- Follow SimplyPages component reuse, slot-key safety, dashboard density, mobile stacking, and visual review expectations.
- Follow package-local `AGENTS.md` guidance for every changed production package.

## Scope

### Included

- canonical task/run identity and confined `AGENT`/`USER` task-run storage;
- fenced writer claims and idempotent run creation, resume, replay, publication, recovery, reconciliation, and safe retention foundations;
- legacy output compatibility;
- centralized reserved-directory behavior and run-aware Files composition;
- flat workspace Markdown skill authoring through the existing activation pipeline;
- authoritative skill roots, scope resolution, assignment overlays, provenance, trust, snapshots, audit, and controlled editing;
- bounded package ingestion and separately authorized executable resources where package skills are supported;
- authorized artifact indexing, search, preview, explicit lineage, integrity handling, and downloads;
- truthful runtime summaries and health/history presentation;
- robust schedule and reaction management and firing audit;
- focused backend, migration, startup, Playwright, security, concurrency, and recovery validation;
- affected specifications, docs, internal records, and API documentation.

### Excluded

- new workspace kinds or independent task-run descriptors;
- automatic migration of legacy flat output files;
- chat-workspace or task-run skill repositories;
- service-account home-directory skill discovery;
- invented project filesystem roots;
- always-loading every workspace skill into prompts;
- automatic trust based only on agent ownership or filesystem location;
- implicit shell/script authority from skill activation;
- artifact lineage inferred without producer evidence;
- a universal JSON telemetry stream without a concrete query requiring it;
- rich binary extraction, archive preview, or unsafe embedded-content rendering;
- distributed scheduler infrastructure beyond the topology Magenta actually deploys;
- unrelated dashboard redesigns or workspace-model changes.

## Assumptions

- `WorkspacePathLayout`, `WorkspaceDirectoryService`, and the closed three-kind model remain governing contracts.
- `USER` execution remains supported by the effective workspace selection contract; if code contradicts this, execution selection must be corrected or the product contract explicitly revised rather than silently publishing differently.
- Task and task-run records provide immutable stable IDs; if they do not, an immutable key must be introduced before structural directories are created.
- SQLite remains the persistence engine and deployed builds can be checked for FTS5 support. If FTS5 is unavailable, search must remain capability-disabled or use an approved SQLite-compatible fallback without changing authorization or extraction rules.
- Application-managed file routes can be centralized under reserved-directory policy. Shell/subprocess protection may require an existing sandbox boundary; executable resources remain disabled where that guarantee cannot be established.
- The existing Markdown renderer can be configured or wrapped for safe artifact preview; its safety must be verified rather than assumed.
- Schedule execution is owned by the current Magenta deployment topology. Multi-node behavior is not inferred without an explicit deployment contract.
- Existing output records may be incomplete and must support honest legacy/unavailable states.
- Persistence can enforce uniqueness for run identity, writer claims or claim generations, publication operation keys, and scheduler firing keys.

## Recommended Direction

Treat run identity/storage and skill-resolution security as foundation contracts rather than incidental implementation details.

Use stable IDs as filesystem authority and human-readable labels as metadata. Introduce a trusted run-location boundary from workspace resolution through publication so path construction, confinement, and run attribution cannot drift among callers. Pair every cross-filesystem/database action with explicit state, persisted idempotency, and reconciliation behavior. Fence writable runs so stale processes cannot continue mutating a recovered run.

Build one skill pipeline. Normalize flat workspace Markdown into the existing catalog/activation model, make candidate identity version- and provenance-specific, and keep model-visible content behind trust. Preserve assignment overlays for user intent while preventing assignments from elevating untrusted candidates. Snapshot activated content for reproducibility and record it with run audit data.

Keep service boundaries narrow: explorer for files, artifact services for publication/indexing, run services for lifecycle, skill services for managed content, and scheduler/reaction services for firing semantics. Compose richer browser surfaces from reusable HTMX fragments rather than creating repository dependencies in the filesystem explorer.

Use observability to report recorded facts. Favor typed outcomes, summaries, repeated measurements, and causation links over broad event blobs. Make retention, search, preview, byte access, and audits authorization-aware and recoverable from partial failures.

## Validation

### Path, identity, and lifecycle

- Verify path grammar, stable IDs, Unicode/case/reserved-name handling, maximum lengths, and duplicate display names.
- Verify `AGENT` and `USER` task-run locations and rejection of task execution in `CHAT`.
- Verify rename/delete/recreate behavior does not alias historical directories.
- Verify resume reuses the location, replay creates lineage, repeated commands are idempotent, and concurrent resume cannot produce uncontrolled writers.
- Verify writer-claim fencing rejects stale holders after claim recovery or reassignment.
- Verify lazy records/directories, interrupted creation, interrupted publication, filename collisions, and reconciliation in both divergence directions.
- Verify root relocation works from persisted workspace identity plus root-relative path.
- Verify legacy flat outputs remain readable/queryable and receive no new task-run publications.

### Confinement and adversarial files

- Test traversal, absolute paths, malformed segments, symlink swaps, parent replacement, hard links where supported, archive traversal, oversized archives, duplicate archive paths, and publication source mutation.
- Test every explorer/upload/import/copy/move/file-tool route against `outputs/`, `tasks/`, and `.skills/` policy.
- Validate the actual shell/subprocess boundary; do not sign off control-plane-only mutation protection if shell access bypasses it.
- Verify external published-byte mutation produces an integrity failure and blocks unsafe preview/download.

### Skills

- Test flat Markdown parsing limits, encoding, constrained frontmatter, duplicate keys, unknown fields, malformed files, case/Unicode collisions, file count/size bounds, and cache invalidation.
- Test deterministic root precedence, conditional project-root handling, duplicate physical-root suppression, pending trust, approval, digest change, revocation, and untrusted shadow conflicts.
- Test that untrusted metadata never reaches model-visible catalog text.
- Test assignment precedence and authorization across agent/project/job/task/chat targets without trust elevation.
- Test activation reads the exact approved snapshot, run audit records it, and resume/revocation behavior matches the contract.
- Test package traversal, symlink, archive-bomb, signature/provenance, and executable-resource authorization cases.

### Artifacts, search, preview, and retention

- Test authorization before search counts/snippets/results, preview, lineage, and download, then test authorization again when bytes are opened or streamed.
- Test extraction limits, malformed Unicode, huge lines, incorrect media metadata, unsafe Markdown/HTML/URLs/remote images, and binary fallback.
- Verify FTS migration, interrupted backfill, synchronization, rebuild, and missing-file reconciliation.
- Test published-byte digest mismatch, integrity-failed presentation, and authorized disposition.
- Test publication idempotency and uniqueness under concurrent retries.
- Test retention policy precedence against active claims, resume, downloads, references, legal pins, lineage, deletion interruption, and recovery.

### Runtime, schedules, and reactions

- Test status and elapsed-runtime semantics for all terminal and non-terminal outcomes, retries, resumes, replays, and legacy unavailable data.
- Verify multi-model and repeated tool-call measurements are not flattened into misleading single values.
- Test scheduler restart recovery, duplicate firing claims, persisted idempotency, misfire policy, cancellation, concurrency, timezone handling, and daylight-saving transitions.
- Test side-effect-free simulation, typed simulation explanations, absence of execution-like firing records, and reaction deduplication, causation chains, depth/rate limits, and loop prevention.

### UI and integration

- Run focused service and repository integration tests, schema migration tests, application-context startup, and the applicable broader automated suite.
- Run Playwright validation through the required testing subagent for Files, Skills, outputs, runtime health, schedules, and reactions while the application is running.
- Capture desktop and mobile screenshots and critique hierarchy, density, spacing, overflow, first-viewport usefulness, control affordance, empty/error states, and consistency with the existing operational console.
- Verify HTMX owns standard interactions and any JavaScript is narrowly justified.
- Perform an integrated review across effective workspace selection, projects/jobs/tasks, assignments, resume/replay, artifacts, skills, schedules, and reactions.

Sign-off requires passing focused behavior, security, concurrency, recovery, migration, startup, and UI checks. A blocked real-execution dependency must be reported and handled under the project’s blocker policy rather than replaced by unit-only evidence.

## Open Questions

No product decision is left intentionally ambiguous. The following are repository-verification questions with settled fallback behavior:

1. **Generic run ID versus task-run ID:** inspect current cardinality. If distinct, persist and expose both with explicit linkage; do not collapse them.
2. **Authoritative project filesystem roots:** enable the conditional project-local skill root only if an existing trusted root authority is found; otherwise keep it excluded without changing assignment scopes.
3. **Shell/subprocess reserved-tree enforcement:** if the current runtime cannot prevent writes to `.skills/` and published outputs, keep executable resources disabled, preserve digest-based integrity detection, and do not represent instruction approval as a complete runtime security boundary.
4. **FTS5 deployment support:** verify the actual JDBC/native runtime. If unavailable, keep full-text capability disabled or use an explicitly approved bounded fallback.
5. **Scheduler topology:** bind claim/recovery behavior to the deployment topology found in the repository and configuration; do not invent distributed coordination.

Any code/specification conflict discovered during verification must be escalated as a contract conflict rather than silently resolved by implementation preference.

## Sign-off

This handoff authorizes design and implementation only within the stated scope and settled decisions. It does not authorize changes to the workspace-kind model, unsafe compatibility shortcuts, speculative telemetry, implicit skill trust, or bypasses around path and mutation authorities.

Completion means the requested behavior is implemented end to end, affected contracts and documentation are updated, required internal records are closed out, all mandated validation is executed, the final diff is reviewed for scope and accidental API growth, and the repository is committed according to project policy. No feature is complete while a known defect, unresolved security boundary, missing real-execution check, or required validation remains.

**Explicit sign-off: the corrected handoff is approved as the governing implementation and validation contract, subject only to the repository-verification fallbacks stated above.**
