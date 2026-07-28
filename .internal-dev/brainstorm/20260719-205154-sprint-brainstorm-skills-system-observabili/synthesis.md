## Prompt

Synthesize the supplied brainstorming on Magenta’s agent skills, observability/quality, and task-run output structure into one concise architectural direction. Select the strongest ideas, reconcile disagreements, identify concrete code surfaces and boundaries, and preserve this as brainstorming rather than an accepted implementation plan.

## Source

- `workspace-skill-architecture/findings.md`
- `runtime-output-observability/findings.md`
- `product-integration-delivery/findings.md`
- `workspace-skill-architecture/cross-review.md`
- `runtime-output-observability/cross-review.md`
- `product-integration-delivery/cross-review.md`

## Findings

### Unifying direction

Treat the work as one operational chain:

**task-run identity and paths (C) → scoped skills (A) → artifact/runtime visibility (B)**.

`WorkspacePathLayout` remains the sole structural path authority, `WorkspaceDirectoryService` remains the sole descriptor authority, and `WorkspaceKind` remains closed to `AGENT`, `CHAT`, and `USER`. Task runs and skill repositories are children of a workspace, never new workspace identities. Validation (B4) should run continuously with each increment; the integrated UX/service review (B5) should close each major capability and the overall effort rather than being postponed as a detached cleanup phase.

### Cluster C — Task-run output structure

**Targets**

- `io.mindspice.magenta2.ai.orchestration.workspaces`
  - `WorkspacePathLayout`: add `TASKS`, `taskRunRoot(...)`, and `taskRunOutputs(...)`.
  - `WorkspaceDirectoryService`: confined, lazy task-run directory provisioning.
  - `OutputArtifactService`: retain its generic destination-directory contract; clarify task-run usage rather than adding path construction.
  - `OutputArtifactRepository` / `RunOutputArtifact`: persist enough task/run attribution for lookup and grouping.
  - `WorkspaceExplorerService` and its fragments: structural-directory policy and run-aware browsing.
- Output-path callers, especially `WorkflowRunner`, `OrchestrationRunnerService`, `PlanService`, and compatibility paths in `TaskService`.
- Task/run persistence used by resume and replay.

**Recommended shape**

```text
<effective-task-workspace>/
  tasks/
    <taskPathKey>/
      <taskRunId>/
        outputs/       # final published artifacts only
        ...            # run working files
```

The default effective workspace produces the required path:

```text
workspace/agent/<id>/tasks/<taskName>/<taskRunId>/outputs/
```

Use the task name as a validated, immutable **path key captured by the run**, while allowing a separate mutable display name. Historical directories are never renamed when a task’s display name changes. Persist the root-relative run directory (or the captured task key plus run ID), never an absolute path. Resume of the same run reuses the same run directory; replay as a new run receives a new run ID and can record parent-run lineage.

Task nesting should apply to every non-chat workspace that can actually be selected as the effective task execution workspace: `AGENT` and, under the current execution-selection contract, `USER`. `CHAT` remains flat at `workspace/chat/<id>/outputs/`. If product intent is that user workspaces cannot host task execution, that restriction should be made explicit at execution selection rather than silently publishing user-workspace task outputs differently.

`OutputArtifactService.materialize(...)` and `publishExistingFile(...)` already accept a destination `Path`; callers should obtain that path from `WorkspaceDirectoryService.taskRunOutputs(...)`. Avoid a convenience method that makes `OutputArtifactService` another structural path authority.

Old agent-root `outputs/` content remains readable and queryable in place but receives no new task-run artifacts. No automatic migration should move files or invalidate stored root-relative paths.

`WorkspaceDirectoryService.resolve()` continues to return the containing workspace descriptor for paths inside a run. It must not infer a new identity from `tasks/...`; run context remains separate from workspace identity.

**Files-tab policy**

Centralize structural behavior in a sibling `ReservedDirectoryPolicy` rather than scattering string checks:

- root or run `outputs/`: visible, publication-owned, mutation rejected;
- `tasks/`, task-key directories, and run-ID directories: visible and protected from rename/delete;
- ordinary working files below a run root: browsable and mutable under normal confinement rules;
- `.skills/`: hidden by default and managed through the Skills surface.

The Files tab should augment raw navigation with an HTMX run view showing task label, run status, timestamps, artifact count, and recent outputs. Keep `WorkspaceExplorerService` filesystem-focused; compose artifact metadata through a separate output/run fragment rather than coupling the explorer directly to `OutputArtifactRepository`.

### Cluster A — Agent skills

**Targets**

- `WorkspacePathLayout`: add the reserved `SKILLS_DIR = ".skills"` name and descriptor-relative factories.
- `WorkspaceDirectoryService`: confined skill-root resolution without auto-creating an empty repository.
- `io.mindspice.magenta2.ai.skills`
  - evolve `AgentSkillRepositoryService` and `AgentSkillCatalogService` into provenance-aware, multi-root discovery;
  - add an `AgentSkillScopeDiscoveryService` (or equivalent aggregation boundary);
  - enrich `AgentSkillAgentContextResolver` into a `SkillResolutionContext` carrying agent identity, effective workspace, execution scope, and ordered roots;
  - merge candidates in `AgentSkillRuntimeCatalogService`;
  - preserve `AgentSkillActivationService`, `AgentSkillPromptCatalogAssembler`, and `ToolAccessPolicy` as the progressive-disclosure path;
  - extend `AgentSkillAssignmentService` rather than replacing it.
- Skills browser/controller for scope, provenance, trust state, and controlled editing.
- Prompt and tool context only where needed to pass `SkillResolutionContext`; avoid directly appending every skill body in `PromptContextAssembler`.

**Format reconciliation**

Honor the directive that workspace skills are `.md` files without creating a second always-loaded skill system. Treat each `.skills/*.md` file as a lightweight skill package: optional frontmatter supplies metadata; the filename supplies the fallback key; the body is cataloged and disclosed through the existing `activate_skill` lifecycle. Internally adapt it to the existing parsed skill model. This preserves low token use and activation deduplication while keeping authoring simple.

The global repository may retain its existing directory-per-skill `SKILL.md` format. A scope resolver normalizes both formats into one runtime model with provenance. Rich resources (`scripts/`, `references/`, `assets/`) remain available only to package-format skills until a real need justifies a flat-file companion convention.

**Discovery and assignment are separate layers**

Candidate collision precedence, highest first:

1. effective execution workspace `.skills/`;
2. project-local `.<client>/skills` and `.agents/skills` roots;
3. the bound agent’s personal `workspace/agent/<id>/.skills/`;
4. user-home `~/.<client>/skills` and `~/.agents/skills`;
5. global Magenta `skills/`.

When the effective workspace is the bound agent workspace, scan that physical `.skills/` root once. Same-name collisions use closest-scope-wins and retain provenance in diagnostics.

After discovery, enable/disable overlays resolve independently from most specific to least specific:

```text
chat/session → task/workflow → job → project → agent default
```

The most specific explicit decision wins; otherwise the candidate inherits the lower-level decision or its source default. This keeps filesystem presence as availability while preserving auditable DB-backed assignment and temporary overrides. `AgentSkillAssignmentService` should therefore gain target types and generic resolution methods, not be discarded.

**Trust**

Trust should be content-versioned, provenance-aware, and split between instructions and executable resources:

- global skills: operator-managed, still subject to agent assignment;
- agent-personal skills: inherited automatically only when created or approved through the Skills control plane; out-of-band content changes invalidate the approved digest and return to pending review;
- project, user-home, and effective-workspace skills: discovered but gated until the workspace/root and current content digest are trusted;
- session/task overrides cannot elevate an untrusted candidate;
- scripts require separate explicit authorization per package version/hash, path confinement, execution limits, and audit events.

This is safer than unconditional auto-trust for any file an agent can write into its own workspace and avoids a durable prompt-poisoning channel. Generic explorer and shell/file tools should not mutate `.skills/`; management belongs to the Skills service and UI.

A5 package ingestion should stage content outside active roots, validate paths and metadata, record source/version/hash/signature status, then require approval before atomic installation. Registry provenance is evidence, not automatic trust.

**Scope boundaries**

- No `.skills/` discovery in chat workspaces.
- No task-run `.skills/` in this effort; task-level assignment references cataloged skills instead. Per-run bundled skills remain a horizon idea.
- Do not let skill activation implicitly grant shell/script authority.
- Do not create parallel prompt-loading behavior for flat Markdown skills.

### Cluster B — Observability and quality

**B1: artifact indexing and preview**

Build on Cluster C’s stable run hierarchy. Keep `RunOutputArtifact` as the metadata index and add bounded text extraction for allowlisted text formats, size limits, MIME-aware preview, and SQLite FTS5 search. Search results should carry workspace, task, run, artifact type, creation time, and download information. Binary artifacts remain metadata/download-only. Prefer the existing Markdown renderer for safe previews.

Start lineage at the run level, which orchestration already understands. Add artifact parent edges only when producers can explicitly declare consumed artifact IDs; do not add an unused JSON placeholder. Retention should operate on completed run directories and artifact records as one transaction-like policy, with defaults at workspace level and optional task override. It must protect active, resumed, or referenced runs.

**B2: runtime observability**

First compute success rate, duration, failure counts, and health history from existing structured statuses and timestamps. Persist only genuinely missing facts such as exit code, model, and token use in typed run-summary fields or a small `run_metrics` record. Defer a generic `run_events(payload_json)` stream until a concrete intra-run debugging question—such as tool-call latency or step timelines—cannot be answered otherwise.

Expose truthful “unavailable” states rather than synthesizing metrics that were never recorded. Skill activation provenance and trust decisions should be auditable per run, but skill-to-output attribution should only be recorded when the producer can identify it reliably.

**B3: schedules and reactions**

Use SimplyPages and HTMX for schedule/reaction lists, inline editors, enable/disable actions, next-fire previews, simulations, and audit fragments. Required backend concepts are typed schedule/reaction DTOs, timezone-aware cron validation, side-effect-free simulation, and an append-only firing audit that links schedule/reaction → assignment/run → outputs. Avoid JavaScript transport; use narrowly scoped JavaScript only if a visualization or editor interaction is demonstrably simpler.

**B4/B5**

Validation is continuous: path/confinement unit tests, deterministic orchestration fixtures, repository migration tests, service integration tests, and focused Playwright checks for Files, Skills, outputs, metrics, schedules, and reactions. The workspace/service/UX review should verify projects, jobs, tasks/workflows, assignments, effective workspace selection, resume/replay, artifacts, and mobile/first-viewport behavior together after each relevant slice and once at completion.

### Ordering and cross-cutting risks

Suggested sequencing is:

1. structural policy and task-run identity (C);
2. task-run Files UX;
3. scoped skills and trust (A);
4. artifact search/preview (B1);
5. runtime summaries and health UX (B2);
6. schedule/reaction management (B3, which may overlap after C stabilizes);
7. retention and explicit lineage;
8. integrated review, with B4 validation throughout.

Primary risks are path-key instability, undiscovered output callers, generic file tools bypassing reserved policies, skill-cache staleness after file changes, trust elevation through assignments, FTS synchronization/backfill failures, unbounded run growth, and resume/replay choosing the current task name instead of the run’s captured path key.

## Options

### 1. Recommended: one normalized skills pipeline and run-first delivery

Accept flat workspace `.md` authoring through an adapter into the existing catalog/activation pipeline; retain package-format global skills; separate discovery, assignment, and trust; establish task-run directories before indexing. This satisfies the new directive without duplicating runtime behavior.

### 2. Spec-format workspace skills only

Require `.skills/<slug>/SKILL.md`. This maximizes ecosystem compatibility and resource support but conflicts with the stated flat-file authoring direction and creates more user ceremony.

### 3. Directly append all workspace Markdown to prompts

This is simplest initially but should be rejected: it bypasses activation, increases token use, weakens provenance and trust controls, and creates behavior inconsistent with global skills.

### 4. Agent-only task nesting

This exactly mirrors the example path but is coherent only if task execution is prohibited from `USER` workspaces. If user workspace execution remains supported, agent-only nesting breaks the invariant that every task run has an isolated root.

### 5. Add generic telemetry and artifact lineage immediately

A `run_events` store and artifact DAG are feasible, but premature without concrete consumers and complete producer instrumentation. Computed summaries and run-level lineage offer better initial value with less schema and integration risk.

## Trade-offs

- **Human-readable task paths vs stability:** use a captured immutable task path key plus mutable UI label. This preserves the required readable hierarchy without moving history on rename.
- **Agent-personal convenience vs poisoning resistance:** automatic inheritance is retained, but only approved content digests are trusted; generic file mutation cannot silently create durable instructions.
- **Filesystem-first discovery vs operational control:** files determine available candidates, while DB-backed overlays determine enablement and record trust/audit decisions.
- **User-workspace nesting vs narrower scope:** supporting `USER` adds tests and factories but preserves consistent execution semantics. The alternative requires explicitly banning user-workspace task execution.
- **Hidden `.skills/` vs discoverability:** hide it in Files by default and expose scope/status through the dedicated Skills surface and an agent summary link.
- **Raw explorer vs richer task UX:** retain ordinary tree browsing, then compose separate HTMX run/artifact fragments so filesystem and persistence concerns remain separated.
- **FTS5 value vs maintenance:** SQLite FTS5 fits local scale, but extraction must be bounded and index synchronization/backfill must be tested as first-class behavior.
- **Observability depth vs speculative infrastructure:** typed summaries answer common operational questions; event-level telemetry waits for a demonstrated need.

## Open Questions

1. Is flat `.skills/*.md` authoring a firm product contract? If so, should optional YAML frontmatter be allowed for descriptions and activation hints, with filename/body fallbacks?
2. May generic agent shell/file tools write `.skills/`, or is the Skills service the exclusive mutation path? The recommended trust model assumes control-plane protection.
3. Is `USER` definitively a task-capable execution workspace? If not, execution selection should reject it for tasks rather than fall back to flat outputs.
4. Is the current generic `runId` guaranteed to equal `taskRunId`? If yes, avoid a redundant artifact column; if not, persist both with explicit semantics.
5. Can task names be made immutable path keys, or must a separate stable `taskPathKey` be introduced while retaining `<taskName>` as the user-facing notation?
6. Which project context provides roots for `.<client>/skills` and `.agents/skills` now that generic project roots were removed from the workspace model?
7. What are the initial trust UX and ownership rules for shared user/project roots, including who may approve a changed digest?
8. What retention default is acceptable, and which references must pin a run against deletion?

## Recommended Next Step

Produce a short, non-binding decision brief for product/architecture review that resolves the first seven questions above and illustrates three end-to-end examples:

1. an agent task using its personal skills and publishing nested outputs;
2. a task executing in a user workspace with a newly discovered, initially untrusted skill;
3. resume and replay after a task display-name change.

The brief should define the normalized skill model, source and assignment precedence, trust-state transitions, task path-key semantics, and AGENT/USER/CHAT output matrix. Once those contracts are agreed, a separate implementation plan can map the phases and validations; this synthesis itself should remain brainstorming, not authorization to implement.
