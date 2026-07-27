## Prompt

Sprint brainstorm for three interconnected clusters targeting the Magenta agent platform after the 8-phase filesystem-first workspace refactor. The prompt requests architectural design brainstorming across: **(A)** Agent Skills System expansion with `.skills/` directories in agent personal workspaces and layered skill scoping; **(B)** Observability &amp; Quality features covering output indexing, runtime observability, scheduling UI, validation coverage, and UX review; and **(C)** Task Run Output Structure — nesting task-run outputs under `workspace/agent/<id>/tasks/<taskName>/<taskRunId>/outputs/` instead of the current flat `outputs/` layout. The brief also introduces a new directive for agent-personal `.skills/` directories in workspaces.

## Source

- `.internal-dev/specifications/deferred-features.md` — DEFERRED-20260526-05 through -09 (skills) and DEFERRED-20260525-15, 16, 17, 20, 21 (observability/quality)
- `.internal-dev/specifications/architecture.md` — ARCH-20260719-01 (workspace model), ARCH-20260526-03 (skills MVP boundary)
- `.internal-dev/specifications/services.md` — SVC-20260719-01 through -06 (workspace services), SVC-20260526-03 (skills services)
- `.internal-dev/specifications/service-graph.md` — identity flow contracts
- `.internal-dev/specifications/web.md` — agent Files tab, skills browser contracts
- `.internal-dev/specifications/simplypages.md` — SimplyPages/HTMX conventions
- `.internal-dev/specifications/decisions.md` — DECISION-20260719-02, DECISION-20260526-03 through -06
- `.internal-dev/knowledge/agent-skills-specification-reference.md` — Agent Skills spec reference and MVP boundary
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md` — workspace model takeaways
- `.internal-dev/knowledge/output-artifact-attribution-query-and-backfill-pattern.md` — output patterns
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java` — sole structural path authority (current: only `WORKSPACE`, `OUTPUTS`, three root factories)
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java` — sole descriptor resolver; has `outputs(WorkspaceDescriptor)` creating `<root>/outputs`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java` — materialize accepts `Path outputDir` directly from caller
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDescriptor.java` — package-private constructor, carries kind/key/identity/root
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceKind.java` — closed enum `AGENT`, `CHAT`, `USER`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java` — descriptor-first file ops; mutations reject `outputs/`
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillRepositoryService.java` — root-confined skill file discovery at `MagentaRootProperties.path()/skills`
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentService.java` — agent-profile-only assignment
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillActivationService.java` — catalog lookup and deduplicated activation per conversation
- `src/main/java/io/mindspice/magenta2/core/config/MagentaRootProperties.java` — `skillsRoot()` returns `<magenta-root>/skills`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunner.java` — `outputPathFor(run)` delegates to `directoryService.outputs(descriptor)` (flat)
- Orchestration task run code in `OrchestrationRunnerService`, `TaskService`/`PlanService`

## Findings

### Current Architecture Baseline

**Workspace path layout** (`WorkspacePathLayout`) is the sole structural authority with exactly two public names (`WORKSPACE`, `OUTPUTS`) and three root factories: `agentRoot(id)`, `chatRoot(id)`, `userRoot(name)`. There are no task, run, skills, or other structural constants. `WorkspaceDirectoryService.outputs(descriptor)` resolves `<root>/outputs` as the sole publication destination — there is no deeper nesting.

**Output artifact service** (`OutputArtifactService.materialize()`) accepts `Path outputDir` as a caller-supplied parameter. It does not own output path construction; callers (`WorkflowRunner`, `OrchestrationRunnerService`, `PlanService`) construct the output directory path and pass it in. Currently all callers pass the flat `descriptor.root/outputs/` path.

**Skills system** operates from a single Magenta-owned root repository at `<magenta-root>/skills/`. Discovery is via `AgentSkillRepositoryService` which uses `MagentaRootProperties.skillsRoot()`. Assignment is agent-profile-only through `AgentSkillAssignmentService`. There is no concept of workspace-local or project-local `.skills/` discovery. The runtime catalog (`AgentSkillRuntimeCatalogService`) resolves agent→enabled skill names through catalog lookups only against the root repository.

**Files tab** (`WorkspaceExplorerService` + `WorkspaceExplorerFragments`) browses `WorkspaceDescriptor.root()` with confinement. It lists, previews, edits, and mutates files. It rejects mutation of `outputs/` and descendants. Currently it navigates a flat hierarchy under the agent root with no task-run subdirectories.

**Execution identity** carries `workspaceType`/`workspaceKey` but has no concept of task-run nesting. Run IDs are UUIDs stored in DB rows; the filesystem is not coupled to run identity beyond the flat `outputs/` directory.

### Gaps Between Current State and Target

1. **Cluster C gap**: `WorkspacePathLayout` has no `tasks/` or run-level structural names. `WorkspaceDirectoryService` has no factory methods for task-run paths. `OutputArtifactService` callers construct flat output paths — all would need to construct the deeper `tasks/<taskName>/<runId>/outputs/` path instead. The Files tab has no navigation affordance for task-run subdirectories. `OutputArtifactRepository` rows would need `taskName` and `runId` alignment with the new path structure.

2. **Cluster A gap**: Agent personal `.skills/` doesn't exist as a concept anywhere in `WorkspacePathLayout` or `WorkspaceDirectoryService`. The skills discovery pipeline (`AgentSkillRepositoryService` → `AgentSkillCatalogService` → `AgentSkillRuntimeCatalogService` → `AgentSkillActivationService`) is entirely rooted at the global `skills/` repository. No multi-scope catalog merge, no trust gating, no precedence rules exist. The `AgentSkillAssignmentService` only stores `(skillName, AGENT, agentId, enabled)` — it has no concept of workspace-level or project-level assignment.

3. **Cluster B gap**: `OutputArtifactRepository` supports query by run/plan but has no full-text index or preview rendering pipeline. Runtime observability is minimal — no persisted exit codes, latency histograms, or health timelines. Scheduling UI is a baseline placeholder. No comprehensive UX review artifact exists.

## Options

### Cluster A: Agent Skills System

#### A1: Where `.skills/` lives in the structural path layout

**Option A1a — `WorkspacePathLayout` owns `.skills/` as a structural name alongside `WORKSPACE` and `OUTPUTS`**

Add `public static final String SKILLS_DIR = ".skills"` to `WorkspacePathLayout` with factory methods like `agentSkillsRoot(String agentId)` → `workspace/agent/<id>/.skills`. The `.skills/` directory is a *reserved structural child* of each agent root (and optionally of chat/user roots), parallel to `outputs/`. The Files tab treats it as reserved (readable, not mutable by explorer), similar to `outputs/` policy but read-only instead of write-only. Pros: centralized authority, consistent with the existing reserved-directory pattern. Cons: ties the `.skills/` concept to workspace identity even when skill loading might conceptually be independent of workspace kind.

**Option A1b — Skill-domain services own `.skills/` resolution, not `WorkspacePathLayout`**

Keep `WorkspacePathLayout` focused on workspace identity only. Add a `SkillPathResolver` service that resolves agent-personal `.skills/` by delegating to `WorkspaceDirectoryService.resolveForRead(AGENT, agentId)` and then appending `.skills/` — with `WorkspacePathLayout.confinedRelative()` or equivalent confinement. Pros: avoids polluting `WorkspacePathLayout` with domain-specific names. Cons: splits path authority; multiple services would need to know about `.skills/` location conventions.

**Recommendation: A1a.** The pattern established by `OUTPUTS` as a reserved structural child is clean and testable. `.skills/` is analogous — it's a reserved directory beneath the workspace root with special access policy. Adding it to `WorkspacePathLayout` keeps the single source of truth. The Files tab benefits from a clear reserved-directory registry rather than scattered magic strings.

#### A2: Skill scope loading precedence

With the new directive that each agent workspace has `.skills/`, the precedence chain becomes:

```
1. Task/session-level assignment (Cluster A3, deferred)
2. Agent personal workspace `.skills/`            [NEW]
3. Workspace-local `.skills/` (on the execution workspace)  [NEW]
4. Project-local `.skills/` (A1, deferred)
5. User-home `.skills/` (A2, deferred)
6. Global `skills/` repository (current MVP)
```

Within same-level name collisions, closest scope wins. Agent-personal `.skills/` are auto-discovered (don't require assignment rows) because the agent owns that directory. Workspace-local `.skills/` during execution would be discovered from the effective `WorkspaceDescriptor` of the current run. All scopes are gated by explicit trust policy.

**Recommendation**: For this sprint, implement agent-personal `.skills/` (scope 2) and workspace-local `.skills/` (scope 3) as the immediate new scopes. Keep project-local (A1), user-home (A2), and layered assignment (A3) deferred per existing deferred-feature entries. The agent-personal scope is the lowest-risk expansion: it's auto-discovered, the agent owns it, and the trust model is straightforward (agent always trusts its own `.skills/`).

#### A3: Skill discovery pipeline changes

**Current pipeline**: `AgentSkillRepositoryService.discoverFromRoot()` → `AgentSkillCatalogService` → `AgentSkillRuntimeCatalogService.catalogForConversation(agentId)`.

**Target pipeline**: A new `AgentSkillScopeDiscoveryService` that aggregates from multiple scope resolvers:

- `GlobalSkillScopeResolver` — wraps existing `AgentSkillRepositoryService` (reads `MagentaRootProperties.skillsRoot()`)
- `AgentPersonalSkillScopeResolver` — new, reads `workspace/agent/<id>/.skills/` via `WorkspaceDirectoryService`
- `WorkspaceExecutionSkillScopeResolver` — new, reads `.skills/` from the execution `WorkspaceDescriptor` root

Each resolver returns `List<AgentSkillParseResult>` with provenance metadata (scope kind, trust status). The aggregator merges with deterministic precedence and deduplication by skill name. `AgentSkillCatalogService` stores skills with a new `scope` column. `AgentSkillRuntimeCatalogService` filters the merged catalog by agent assignment *plus* auto-included agent-personal skills.

#### A4: Trust model

- **Agent-personal `.skills/`**: Auto-trusted. The agent owns this directory; skills placed here are explicitly placed by or for that agent.
- **Workspace-execution `.skills/`**: Gated by trust prompt or explicit allowlist. When a task run executes against a workspace that has a `.skills/` directory, those skills are only loaded if the workspace is explicitly trusted or the user has approved.
- **Global `skills/`**: Already trusted (managed by Magenta operator).
- **Project-local and user-home**: Deferred, with trust gating specified in existing deferred features.

#### A5: Files tab visibility of `.skills/`

The Files tab should show `.skills/` as a visible but *read-only* directory entry (similar to how `outputs/` could be visible but mutation-rejected). The `.skills/` directory listing shows skill directories and `SKILL.md` files with a distinct visual treatment (icon, muted styling). Users can browse, preview, and download skill files. They cannot create, edit, rename, move, or delete within `.skills/` through the Files tab — skill management goes through the dedicated Skills browser. A `SKILLS_DIR` entry in the reserved-directory registry controls this.

### Cluster C: Task Run Output Structure

#### C1: Structural path design

Add to `WorkspacePathLayout`:

```java
public static final String TASKS = "tasks";

// workspace/agent/<id>/tasks/<taskName>/<taskRunId>
public static Path taskRunRoot(String agentId, String taskName, String taskRunId) { ... }

// workspace/agent/<id>/tasks/<taskName>/<taskRunId>/outputs
public static Path taskRunOutputs(String agentId, String taskName, String taskRunId) { ... }

// Convenience for descriptor-bound callers
public static Path taskRunRoot(WorkspaceDescriptor descriptor, String taskName, String taskRunId) { ... }
public static Path taskRunOutputs(WorkspaceDescriptor descriptor, String taskName, String taskRunId) { ... }
```

`taskName` and `taskRunId` are validated through `PlainPathSegmentValidator` (same as agentId/chatId/userName). `taskName` is the task definition name, sanitized to a path-safe slug if needed. `taskRunId` is the UUID run identifier.

Agent root under the new layout:

```
workspace/agent/<id>/
├── .skills/               # Cluster A (reserved, read-only via Files tab)
├── <agent working files>  # unstructured agent files
├── outputs/               # legacy flat outputs (inert, read-only)
└── tasks/
    └── <taskName>/
        └── <taskRunId>/
            ├── outputs/   # published artifacts
            └── <working>  # agent scratch space during run
```

#### C2: OutputArtifactService changes

`OutputArtifactService.materialize()` already accepts `Path outputDir` from the caller — this is ideal because it doesn't need to change its API. The change is in the *callers*:

- `WorkflowRunner.outputPathFor(run)` — currently calls `directoryService.outputs(descriptor)`. Change to call `WorkspacePathLayout.taskRunOutputs(descriptor, taskName, runId)` then ensure directories exist.
- `OrchestrationRunnerService` (task execution path) — same change.
- `PlanService.executeTask()` — same change.
- `TaskService` compatibility layer — same change.

The `OutputArtifactService` itself needs one addition: a `publishExistingFile` variant or path resolution that works with the new nested structure. But since it's caller-driven, the core materialization logic is unaffected.

#### C3: WorkspaceDirectoryService additions

Add to `WorkspaceDirectoryService`:

```java
// Publication-only: ensure the task-run output directory exists
public Path taskRunOutputs(WorkspaceDescriptor descriptor, String taskName, String taskRunId) { ... }

// Ensure task run root exists (agent only)
public Path ensureTaskRunRoot(WorkspaceDescriptor descriptor, String taskName, String taskRunId) { ... }
```

These delegate to `WorkspacePathLayout` factories, validate confinement, and create directories as needed (following the lazy-create pattern for agent roots).

#### C4: Files tab navigation into task runs

The Files tab needs to navigate into `tasks/<taskName>/<taskRunId>/` subdirectories. This is a natural extension of the existing list/detail navigation. `WorkspaceExplorerService` already operates on `WorkspaceDescriptor` + `RelativePath` — `tasks/` is just another subdirectory. The Explorer service needs no structural changes for basic navigation.

However, the Explorer should treat `tasks/` children with read-only semantics by default — no rename/move/delete of task run directories through the Files tab. Task run directories are execution artifacts, not user-managed files. This adds `tasks/` to the reserved-children registry alongside `outputs/` and `.skills/`.

#### C5: Legacy flat outputs migration

**Recommendation: Leave inert, do not migrate.** The existing flat `outputs/` directory under agent roots contains historical artifacts referenced by `OutputArtifactRepository` rows. Moving these would break stored `filePath` references (which use `RootRelativePathService`). Keep flat `outputs/` as inert legacy data — readable but not writable. New task runs exclusively use the nested structure. The `OutputArtifactRepository` rows already carry `runId` and `planId`; the stored file path is the artifact reference. Backfill is not required for queryability.

#### C6: User and chat workspaces

- **User workspaces**: Do NOT get task run nesting. User workspaces have no task execution concept; they are declarative spaces. User workspace output publishing would continue to the flat `outputs/` if ever enabled.
- **Chat workspaces**: Continue with flat `outputs/` as specified. Chats don't have task runs.

Task-run nesting is **agent-only**. `WorkspacePathLayout.taskRunRoot()` should validate that the descriptor kind is `AGENT`.

#### C7: Resume/replay of task runs

When resuming a task run, the system needs to locate prior outputs. Options:

- **Store the full output directory path** in the run record (e.g., `OrchestrationRuntimeRepository` run row). This is the simplest approach and avoids rebuilding the path from task name + run ID.
- **Rebuild from task name + run ID** using `WorkspacePathLayout.taskRunOutputs()`. This works but requires the task name to be stable and representable as a path segment.

**Recommendation**: Store both the relative path (from `WorkspacePathLayout`) and the absolute path in the run record. The relative path is portable; the absolute path is fast. When the absolute path doesn't exist (e.g., after data root relocation), rebuild from the relative path.

### Cluster B: Observability &amp; Quality

#### B1: Output artifact indexing and preview

- Add a `full_text_search` column or virtual table to `run_output_artifacts` for FTS5 indexing on SQLite. Index `outputName`, `fileName`, and `contentJson` (for JSON/text artifacts).
- Add a `GET /api/outputs/search?q=...&type=...&agentId=...` endpoint to `OutputController`.
- Render previews inline: Markdown artifacts render through the existing `ChatMarkdownRenderer`; code/text artifacts get syntax-highlighted preview; images get thumbnail preview; binary files get a download-only card.
- Add retention policy: `created_at` + configurable TTL. A scheduled task cleans expired artifacts. Default: 90 days, configurable per agent.
- Lineage: Artifact rows already carry `runId`, `planId`, `jobId`, `agentId`. Add a `parentArtifactId` for derived artifacts. Display as a breadcrumb or tree in the UI.

#### B2: Richer agent runtime observability

- Persist per-run exit codes, duration, token usage, model used, and error messages in a new `run_metrics` table or as columns on existing run tables.
- Add a `GET /api/agents/{agentId}/metrics` endpoint returning latency percentiles (p50/p90/p99), success/failure/error rates, token consumption over time, and recent exit-code distribution.
- UI: Add a "Metrics" tab to the agent operational console. Use HTMX polling for live updates during active runs. Render sparkline-style charts with lightweight SVG or a minimal chart component.
- Health timeline: A chronological feed of run starts, completions, failures, interruptions on the agent detail page.

#### B3: Scheduling and event reaction management UI

- Schedule editor: A SimplyPages-based form for cron expressions, next-run preview, enabled/disabled toggle. Mount on the job/workflow submission surface.
- Reaction rule editor: "When X happens, trigger Y" rules with event type selector, condition builder, and action selector. Store as structured JSON in a `reaction_rules` table.
- Simulation: "Show next 5 runs" for schedules; "Would this rule have fired in the last 24h?" for reactions.
- Audit trail: Log every schedule trigger and reaction firing to an `event_audit` table. Expose as a filterable HTMX table.

#### B4: Broader validation coverage

- UI integration tests: Playwright fixtures for the Files tab, Skills browser, task submission, output browsing, metrics tab.
- Deterministic graph fixtures: Pre-built workflow graphs (linear, fan-out, conditional) with known inputs/outputs for reproducible validation.
- JS module tests: If any custom JS remains (e.g., for streaming, local resizing), add focused unit tests.

#### B5: Workspace &amp; service/UX follow-up review

- Create a review artifact in `.internal-dev/reviews/` auditing projects, jobs, tasks, workflows, workspaces, outputs, and assignment flows together.
- Check for: broken cross-references after the workspace refactor, orphaned UI routes, confusing terminology (plan vs task), inconsistent error handling across controllers, missing mobile layouts.

### Cross-Cutting Concerns

#### X1: `.skills/` inside task run directories?

A task run *could* bundle skills in its own directory (e.g., `tasks/<taskName>/<runId>/.skills/`). This would allow a specific run to carry custom skill instructions. However, this adds complexity: skills are typically stable across runs; per-run skills would need activation logic changes and wouldn't benefit from the catalog pre-loading pattern. **Recommendation: Out of scope for this sprint.** Keep `.skills/` at the agent-root and execution-workspace-root level. Document per-run skills as a horizon idea.

#### X2: Task run directories visible to skill discovery?

When the execution workspace is the agent's own workspace (the default), the workspace `.skills/` and agent-personal `.skills/` are the same directory. When execution uses a different workspace (e.g., a user workspace with a different root), the workspace `.skills/` is distinct. The skill discovery pipeline needs to handle this: always include agent-personal `.skills/`, conditionally include workspace `.skills/` when the execution workspace differs from the agent root. This requires `AgentSkillRuntimeCatalogService` to accept both an agentId and an optional `WorkspaceDescriptor` for the current execution scope.

#### X3: Output indexing depends on Cluster C

Full-text output search (B1) is more valuable when outputs are organized by task/run (Cluster C). The search results can display `taskName` and `runId` as grouping/contextual metadata. If Cluster C doesn't land first, the search results lack this organizational context. **This is a sequencing argument: do C before B1.**

#### X4: Files tab reserved-directory registry

After both Cluster A and Cluster C land, the Files tab needs to handle at least three reserved directories: `outputs/` (existing, mutation-rejected), `.skills/` (new, read-only), and `tasks/` (new, read-only for children). This suggests a centralized `ReservedDirectoryPolicy` enum or registry in `WorkspaceExplorerService` rather than scattered conditionals:

```java
enum ReservedDirectory {
    OUTPUTS("outputs", AccessPolicy.MUTATION_REJECTED, Visibility.VISIBLE),
    DOT_SKILLS(".skills", AccessPolicy.READ_ONLY, Visibility.VISIBLE),
    TASKS("tasks", AccessPolicy.READ_ONLY_CHILDREN, Visibility.VISIBLE);
}
```

## Trade-offs

### Trade-off 1: Cluster C first vs last

**If Cluster C lands first**: Task run output structure is the foundation. Output indexing (B1) builds on it naturally. Skills system (A) doesn't depend on it but benefits from a stable path authority. The Files tab gets a richer navigation tree. Risk: larger blast radius — every output-producing code path changes at once.

**If Cluster A lands first**: Skills system is self-contained and can land without touching output paths. Lower risk. But when Cluster C lands later, skill discovery might need adjustment for task-run directory nuances. Risk: rework in skill scope resolution if task-run `.skills/` becomes a thing later.

**Recommendation: Cluster C first.** The structural path changes are the foundation. Getting them right first means Cluster A can build on a stable, complete `WorkspacePathLayout` that includes all reserved names. The output callers are concentrated (WorkflowRunner, OrchestrationRunnerService, PlanService) — the change is surgical, not sprawling.

### Trade-off 2: Skills in WorkspacePathLayout vs separate resolver

Putting `.skills/` in `WorkspacePathLayout` (Option A1a) keeps authority centralized but makes the layout class grow beyond its current sparse state. A separate resolver (A1b) keeps the layout class minimal but distributes path knowledge.

The current `WorkspacePathLayout` has only 2 constants and 3 factories — it's intentionally minimal. Adding `.skills/` and `tasks/` roughly doubles its surface. However, the alternative (distributed path knowledge) directly violates the "sole structural path authority" contract established in the workspace refactor. The contract is more important than the class's line count.

### Trade-off 3: Auto-trust vs explicit gating for agent-personal `.skills/`

Auto-trusting agent-personal `.skills/` is simpler and matches the mental model (the agent owns its workspace, including skills). But it means any file write to `.skills/` by the agent during a task run immediately becomes trusted skill content for future runs — a potential persistence/poisoning vector if the agent is compromised or makes mistakes.

Explicit gating (even for agent-personal skills) would require the operator to approve each skill before it's active, adding friction. A middle ground: auto-discover and list agent-personal skills but require the operator to enable them individually, similar to how global skills work today with the assignment table (`enabled` flag).

**Recommendation**: Start with auto-trust for agent-personal `.skills/` but add an audit log of which skills were loaded from which scope per run. This gives observability without friction. If poisoning becomes a concern, add gating later as a non-breaking policy tightening.

### Trade-off 4: Legacy flat outputs — migrate vs leave inert

Migrating would mean moving files from `outputs/` to `tasks/<name>/<runId>/outputs/` — complex, risky, and breaks `RootRelativePathService` references in existing `OutputArtifactRepository` rows. Leaving them inert is simpler but creates a split: some outputs in the flat directory, new ones in nested directories.

**Recommendation**: Leave inert. The split is visible in the Files tab but clearly delineated (old = flat, new = nested). The `OutputArtifactRepository` preserves queryability regardless of physical layout. Add a horizon note for a future migration CLI if needed.

## Open Questions

1. **Task name stability**: Task definitions can be renamed. If `taskName` is the path segment, renaming a task orphanes the old run directories from name-based lookup. Should we use task ID (UUID) as the path segment instead of task name? Or store the task-name-to-run mapping in the DB so rename updates the mapping? Trade-off: human-readable names vs stable identifiers. Suggestion: use `taskId` (UUID) as the directory name, display `taskName` as a label metadata file (`task.json` at the run root).

2. **WorkspaceDescriptor for task runs**: Should `WorkspaceDescriptor` be extended to carry optional `taskName`/`taskRunId` sub-context? Or should task-run path resolution remain separate from descriptor identity? The current descriptor is "identity + root" — scoping it to a run would make it "identity + root + run context" which might be too broad. Suggestion: keep descriptor as identity-only; pass `taskName`/`runId` as separate parameters to path factories.

3. **Skill activation at task-run scope**: The deferred feature DEFERRED-20260526-07 mentions layered assignment at task/workflow level. When this lands, how does task-level skill assignment interact with agent-personal `.skills/`? Does a task-run `.skills/` subdirectory override or supplement? Suggestion: defer to the layered-assignment sprint; document the precedence chain in the meantime.

4. **Chat workspace `.skills/`**: Should chat workspaces also have `.skills/` directories? Chats can execute tasks (through the chat→plan→task flow). Suggestion: no — `.skills/` in chat workspaces adds complexity without clear use case. If a chat-sourced task runs against the agent workspace, it picks up agent skills. If it runs against a user workspace, it might pick up workspace skills. Chat workspaces are conversation-scoped; skill loading from chat directories would be transient and confusing.

5. **`WorkspaceDirectoryService.resolve()` and task-run paths**: What happens when `WorkspaceDirectoryService.confinedRelative()` is called with a path inside `tasks/<name>/<runId>/`? This works naturally — the descriptor root is the agent root, and `tasks/` is a child directory. No change needed in the resolution logic. The only special case is `resolveForRead/Write` — these resolve the agent root, not a subdirectory. Task-run paths are always constructed via `WorkspacePathLayout` factories, not descriptor resolution.

6. **Reserved directory collision**: What if an agent manually creates a directory named `tasks` or `.skills` before these features land? The reserved-directory policy would need to handle this gracefully: treat pre-existing directories as inert until the owning feature initializes them. The lazy-create pattern from agent roots applies.

## Recommended Next Step

**Phase ordering that minimizes rework:**

**Phase 1: Cluster C — Task Run Output Structure** (foundation)
- Add `TASKS` constant and `taskRunRoot()`/`taskRunOutputs()` factories to `WorkspacePathLayout`
- Add `taskRunOutputs()` and `ensureTaskRunRoot()` to `WorkspaceDirectoryService`
- Update all output-path callers (`WorkflowRunner`, `OrchestrationRunnerService`, `PlanService`, `TaskService`) to use `WorkspacePathLayout.taskRunOutputs()` instead of `directoryService.outputs()`
- Update `OutputArtifactService` documentation and add task-run-aware path validation
- Update `OutputArtifactRepository` rows to carry `taskName` (optional, backfill-compatible)
- Register `tasks/` in the Files tab reserved-directory registry — read-only children
- Validate: run task execution, verify output files land in nested path, verify Files tab can navigate into task runs, verify legacy flat outputs are still queryable

**Phase 2: Cluster A — Skills System** (builds on stable path layout)
- Add `SKILLS_DIR` constant and `agentSkillsRoot()` factory to `WorkspacePathLayout`
- Add `AgentSkillScopeDiscoveryService` with `GlobalSkillScopeResolver` and `AgentPersonalSkillScopeResolver`
- Extend `AgentSkillCatalogService` to store `scope` provenance
- Extend `AgentSkillRuntimeCatalogService` to merge agent-personal + global catalogs
- Update `AgentSkillActivationService` to accept skills from multiple scopes
- Register `.skills/` in the Files tab reserved-directory registry — read-only
- Register `.skills/` in the Explorer mutation rejection alongside `outputs/`
- Add trust audit logging for scope-loaded skills
- Validate: create skill in agent workspace `.skills/`, verify it appears in catalog, verify it activates, verify Files tab shows it read-only, verify trust audit events

**Phase 3: Cluster B — Observability &amp; Quality** (polish on stable features)
- Sub-phase 3a: Output indexing and preview (B1) — depends on Phase 1 output structure
- Sub-phase 3b: Runtime observability (B2) — independent
- Sub-phase 3c: Scheduling UI (B3) — independent
- Sub-phase 3d: Validation coverage (B4) — spans all phases
- Sub-phase 3e: UX follow-up review (B5) — spans all phases

This ordering ensures: (a) the filesystem layout is finalized before skills discovery depends on it; (b) output indexing has rich task/run context from the nested structure; (c) validation and UX review cover the full feature set without rework from later structural changes.

**Immediate actions for Phase 1:**
1. Decide task-name-vs-task-ID path segment (open question 1)
2. Draft `WorkspacePathLayout` additions and review against existing three-kind factory pattern
3. Audit all callers of `directoryService.outputs()` — grep for `outputs(` and `outputPathFor`
4. Create plan artifact in `.internal-dev/plans/` with detailed task breakdown
