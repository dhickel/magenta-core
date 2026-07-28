
# Sprint Brainstorm: Skills System + Observability & Quality + Task Output Structure

## Prompt

Architectural brainstorming across three clusters:
- **Cluster A**: Extend the agent skills system with `.skills/` directories in agent/personal workspaces and workspace-local `.skills/` discovery, with layered assignment and trust boundaries.
- **Cluster B**: Observability & quality — output artifact indexing, runtime observability, scheduling UI, orchestration validation, workspace/UX review.
- **Cluster C**: Nest task run outputs under `workspace/agent/<id>/tasks/<taskName>/<taskRunId>/outputs/` instead of flat `outputs/`.

Focus directive: explore Cluster A and its intersections with Cluster C through the broad architecture and security lens — concrete packages/classes, workspace path authorities, layered discovery/assignment models, trust and script/package provenance boundaries, propagation into prompts/tools/chat/task contexts, and compatibility risks with the closed workspace-kind and descriptor contracts.

## Source

Files consulted for current-state grounding:

| File | Purpose |
|------|---------|
| `.internal-dev/specifications/deferred-features.md` | DEFERRED-20260526-05 through 09 (skills), 15-17+20-21 (observability) |
| `.internal-dev/specifications/architecture.md` | ARCH-20260719-01 (filesystem-first), ARCH-20260526-03 (skills MVP) |
| `.internal-dev/specifications/services.md` | SVC-20260719-01 through 06 (workspace/descriptor/output contracts), SVC-20260526-03 (skills services) |
| `.internal-dev/specifications/service-graph.md` | Identity flow, descriptor propagation, active consumer inventory |
| `.internal-dev/specifications/decisions.md` | DECISION-20260719-02 (three-kind model), DECISION-20260526-03 through 06 (skills MVP) |
| `.internal-dev/specifications/web.md` | Agent Files tab, `/skills` browser/editor composition |
| `.internal-dev/specifications/simplypages.md` | Explorer composition, selector controls |
| `.internal-dev/knowledge/agent-skills-specification-reference.md` | Agent Skills spec research, progressive disclosure, activation patterns |
| `.internal-dev/knowledge/root-relative-workspace-storage.md` | Root-relative path storage, WorkspacePathLayout authority |
| `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md` | Definitive workspace model rules |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java` | Sole structural path authority — only `WORKSPACE` and `OUTPUTS` constants, three root factories |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDescriptor.java` | Package-private constructor, kind+key+identity+root, never reconstructed from Path |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceKind.java` | Closed enum: AGENT, CHAT, USER |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java` | Sole descriptor authority, resolveForRead/Write, outputs(), confinedRelative() |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java` | materialize() writes to generic outputDir; flat collision-safe filenames; no run hierarchy |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillRepositoryService.java` | Root-confined skill directory resolution; `MagentaRootProperties.path()/skills` root |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillCatalogService.java` | Scans/catalogs skills from global root; refresh lifecycle |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentService.java` | Agent-level assignment; `AgentSkillTargetType.AGENT` only |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillActivationService.java` | Dedicated `activate_skill` tool; dedupe per conversation; body-only content |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillRuntimeCatalogService.java` | Filters catalog to agent's assigned+enabled skills |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillPromptCatalogAssembler.java` | Appends `<available_skills>` XML block to system prompt |
| `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillAgentContextResolver.java` | Resolves agentId from OrchestrationTaskContext → session metadata → default agent |
| `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/PromptContextAssembler.java` | Merges mode prompt + AGENTS.md + skill catalog append |
| `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/ToolAccessPolicy.java` | Hides `activate_skill` when no skills available; mode-gated tool filtering |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationTaskContext.java` | Runtime context: agentId, workspaceType, workspaceKey, hostDurableWorkspacePath, activeRuntimePath |

## Findings

### F1: Global Skills Repository Is the Single Source Today

The current MVP (`DECISION-20260526-03`) roots all skill discovery at `MagentaRootProperties.path()/skills`. `AgentSkillRepositoryService` owns root-confined path resolution; `AgentSkillCatalogService` scans subdirectories for `SKILL.md`; `AgentSkillParser` extracts frontmatter+body; `AgentSkillAssignmentService` manages agent-level enablement. There is exactly one discovery root, one assignment scope (agent profile), and one trust domain (operator-controlled filesystem placement).

### F2: WorkspacePathLayout Has Exactly Two Structural Names

`WorkspacePathLayout` exposes only `WORKSPACE` and `OUTPUTS` as public constants. Root factories produce `workspace/agent/<id>`, `workspace/chat/<id>`, `workspace/user/<name>`. There are no `tasks/`, `.skills/`, or other structural path constants. Any new structural path must be added here — hand-concatenation is forbidden by the architecture contract.

### F3: WorkspaceDescriptor Is a Closed Trust Token

`WorkspaceDescriptor` construction is package-private. Only `WorkspaceDirectoryService` creates instances. The descriptor carries kind, validated key, canonical identity (`agent/<id>`), and a normalized absolute root. Downstream services (explorer, tools, outputs, prompt assembly) consume the descriptor without reconstructing identity. This pattern must extend to any skill or task-run feature that touches workspace files.

### F4: OutputArtifactService Accepts a Generic outputDir

`OutputArtifactService.materialize()` and `publishExistingFile()` accept a `Path outputDir` parameter. The caller is responsible for providing the correct directory. Today the caller passes `WorkspaceDirectoryService.outputs(descriptor)`, which returns `<root>/outputs`. There is no task-run nesting. The `outputDir` parameter is structural — `OutputArtifactService` does not own the directory structure, only the write+persist behavior within it.

### F5: Skill Context Resolution Is Agent-Scoped Only

`AgentSkillAgentContextResolver.resolveAgentId()` checks `OrchestrationTaskContext` first, then session metadata, then the default agent. There is no workspace-descriptor awareness, no task-scoped resolution, and no concept of "where are we running" beyond the agent identity. Skill catalog disclosure and activation are conversation-scoped with agent-level filtering only.

### F6: PromptContextAssembler Has a Layered Append Pattern

`mergeModePrompt()` already appends context in order: mode-specific prompt → worktype profile → AGENTS.md layers → skill catalog. Each layer is independently assembled and appended. A `.skills/` directory layer would fit naturally into this existing append pipeline.

### F7: The Files Tab Explorer Has Reserved-Directory Semantics

`WorkspaceExplorerService` already rejects mutation of `outputs/` and descendants. A similar reserved-directory policy could apply to `.skills/` and `tasks/`. Read access is permitted through the explorer; the question is visibility preferences.

### F8: The Agent Skills Spec Defines Directory-Per-Skill, Not Flat .md Files

The Agent Skills specification (agentskills.io) defines a skill as a directory containing `SKILL.md` + optional `scripts/`, `references/`, `assets/`. The new directive's `.md` files in `.skills/` is a simpler flat format. These two formats must either coexist with different parsers, or the flat format must be up-converted to the spec format at scan time.

---

## Options

### Cluster A: Skills System — `.skills/` Directory Discovery

#### A-Option 1: Extend Existing Parser/Catalog to Multiple Roots

Add scan roots to `AgentSkillCatalogService`:
- `MagentaRootProperties.path()/skills` (global, existing)
- `<agent-workspace>/.skills/` — discovered via `WorkspaceDirectoryService`
- `<user-workspace>/.skills/` — for user-scoped skills
- Future: `<project>/.agents/skills/` (deferred)

Each `.skills/` directory contains subdirectories matching the Agent Skills spec format (`skill-slug/SKILL.md`). The existing parser, catalog, and assignment infrastructure works unchanged — only discovery roots multiply.

**Concrete touches:**
- `AgentSkillRepositoryService`: add `resolveWorkspaceSkillDirectory(WorkspaceDescriptor, skillSlug)` and `workspaceSkillsRoot(WorkspaceDescriptor)`
- `AgentSkillCatalogService`: add `scanWorkspaceSkills(WorkspaceDescriptor)` → returns `List<AgentSkill>` with workspace provenance metadata
- `AgentSkill` record: add `Optional<String> workspaceIdentity` field for provenance
- `AgentSkillAssignmentService`: add `TargetType.WORKSPACE` assignments (or keep agent-level only and use workspace as auto-assignment)

**Precedence:** workspace `.skills/` overrides global `skills/` on name collision (closest scope wins). Agent-personal `.skills/` is treated as workspace `.skills/` of the agent's own workspace.

**Trust:** Agent-personal `.skills/` auto-trusted (agent owns its workspace). User-workspace `.skills/` requires explicit trust policy gating. Global `skills/` remains operator-gated via file placement + agent assignment.

#### A-Option 2: Separate Lightweight `.skills/` Pipeline (Flat .md Files)

Treat `.skills/` directories as a distinct, simpler mechanism:
- `.skills/` contains flat `.md` files (not subdirectories)
- File name is the skill name; content is the full instruction body
- No frontmatter parsing, no metadata persistence, no assignment table
- Loaded at prompt assembly time via `WorkspaceDirectoryService` + direct file reads
- No catalog/activation lifecycle — always loaded when present

**Concrete touches:**
- New service: `WorkspaceSkillDiscoveryService` — scans `.skills/` in a descriptor, returns `List<WorkspaceSkill>` (name + markdown content)
- `PromptContextAssembler`: append workspace skills block after global catalog
- `AgentSkillAgentContextResolver`: extend to resolve workspace descriptor from execution context
- No changes to `AgentSkillAssignmentService`, `AgentSkillCatalogService`, or activation flow
- `WorkspacePathLayout`: no new constants needed (`.skills/` is a workspace-relative convention, not a structural path)

**Trust:** Agent-personal `.skills/` auto-loaded. User-workspace `.skills/` loaded only when workspace is explicitly selected and trusted. No task-scoped `.skills/` in this option.

#### A-Option 3: Hybrid — Spec-Format `.skills/` With Progressive Trust

Combine the spec-format directory structure with workspace-local discovery, adding a trust-gating layer:

- `.skills/` directories use the standard `skill-slug/SKILL.md` format
- `AgentSkillCatalogService` gains multi-root scanning with provenance tracking
- New `SkillTrustPolicy` service gates which roots are loaded for which agents
- Agent-personal `.skills/` → implicit trust (agent owns the files)
- User-workspace `.skills/` → trust flag on the user workspace record
- Task-run `.skills/` → scoped trust for the duration of that run only
- Global `skills/` → existing assignment-based gating

**Concrete touches (largest surface):**
- `WorkspacePathLayout`: add `SKILLS_DIR = ".skills"` (not a structural root, but a well-known name constant)
- `WorkspaceDirectoryService`: add `skillsDir(WorkspaceDescriptor)` → confined `<root>/.skills`
- `AgentSkillRepositoryService`: add `workspaceSkillsRoot(WorkspaceDescriptor)`, `resolveWorkspaceSkillMarkdown(WorkspaceDescriptor, skillSlug)`
- `AgentSkillCatalogService`: add `scanRoot(Path root, String provenance)` overload; `refreshCatalog(WorkspaceDescriptor)`
- `AgentSkill` record: add `provenanceType` (GLOBAL, AGENT_WORKSPACE, USER_WORKSPACE, TASK_RUN) and `provenanceIdentity`
- `SkillTrustPolicy`: new service with `isTrusted(WorkspaceDescriptor, String agentId)` — consults agent ownership, user workspace trust flags, task context
- `AgentSkillRuntimeCatalogService`: merge catalogs from global + trusted workspace roots with deterministic precedence
- `AgentSkillPromptCatalogAssembler`: annotate catalog entries with provenance source in prompt
- `AgentSkillActivationService`: resolve skill directory through provenance-aware repository service
- `ToolAccessPolicy`: no changes needed (activate_skill gating remains catalog-driven)

### Cluster A: Skills System — Layered Assignment (A3)

#### A3-Option 1: Target-Type Enum Extension (Incremental)

Extend `AgentSkillTargetType` from `AGENT` to include `PROJECT`, `JOB`, `TASK`, `CHAT_SESSION`. Assignment rows carry target type + target ID. Resolution merges from most specific to least specific:

```
Task-scoped assignments (override)
  → Job-scoped assignments (override)
    → Project-scoped assignments (override)
      → Agent-default assignments (base)
```

`AgentSkillRuntimeCatalogService.catalogForConversation()` resolves the full chain given conversation context that carries task/job/project IDs.

**Concrete touches:**
- `AgentSkillTargetType`: add `PROJECT`, `JOB`, `TASK`, `CHAT_SESSION`
- `AgentSkillAssignmentRepository`: queries already support `(targetType, targetId)` — new types are additive
- `AgentSkillAssignmentService`: add `assignToTask(taskId, skillName, enabled)` etc.
- `AgentSkillRuntimeCatalogService`: resolve chain from OrchestrationTaskContext
- UI: assignment panels on task editor, job editor, project settings

#### A3-Option 2: Implicit Scope-By-Discovery (Filesystem-Driven)

Instead of DB assignment rows for every scope, layer assignment through filesystem presence:
- Agent defaults: DB-assigned from global `skills/`
- Project skills: `.agents/skills/` in the relevant project context (deferred)
- Task skills: `.skills/` directory inside the task run directory (Cluster C intersection)
- Chat/session: explicit `activate_skill` tool call, already implemented

No DB assignment expansion needed. Presence in the scope's filesystem = assignment. This aligns with the filesystem-first philosophy.

**Trust implication:** Filesystem presence IS the assignment. Trust is gated by whether the scope's `.skills/` directory is trusted for the executing agent.

### Cluster A: Skills System — Script Trust (A4) and Package Provenance (A5)

These are the most security-sensitive deferred features. Key architectural touchpoints:

#### A4 — Script Execution Policy

- **Tool boundary:** `AgentShellToolService` already confines execution to the workspace root. Skill scripts would need a sub-confinement: execute only within `<skill-directory>/scripts/` and only when the skill is explicitly activated.
- **Policy service:** New `SkillScriptExecutionPolicy` — checks: is the skill activated in this conversation? Is the skill's provenance trusted? Is script execution enabled for this agent?
- **Prompt gating:** Before `shell_exec` runs a skill-relative script, `ToolAccessPolicy` or a new pre-flight check validates the script path against activated-skill script directories.
- **Audit:** `WorkspaceFileActionLogRepository` entries for script executions tagged with skill provenance.

#### A5 — Package/Registry Ingestion

- **Import service:** `SkillPackageImportService` — downloads/extracts skill packages into a staging area, validates `SKILL.md`, checks cryptographic provenance (if signatures present), prompts for trust confirmation.
- **Provenance record:** `SkillProvenance` record — source URL, registry, version, signature status, import timestamp, importing agent.
- **Storage:** Imported skills land in the global `skills/` repository (operator-managed) or a new `skills/imported/` subdirectory.
- **Update policy:** `SkillPackageImportService.checkForUpdates()` — compares version/signature with imported records.

### Cluster C: Task Run Output Structure

#### C-Option 1: WorkspacePathLayout Extensions (Minimal, Aligned)

Add well-known name constants and factory methods to `WorkspacePathLayout`:

```java
public static final String TASKS = "tasks";
// OUTPUTS already exists

/** workspace/agent/<id>/tasks/ */
public static Path agentTaskRunsRoot(String agentId) { ... }

/** workspace/agent/<id>/tasks/<taskName>/<taskRunId>/ */
public static Path taskRunRoot(String agentId, String taskName, String taskRunId) { ... }

/** workspace/agent/<id>/tasks/<taskName>/<taskRunId>/outputs/ */
public static Path taskRunOutputs(String agentId, String taskName, String taskRunId) { ... }
```

Or descriptor-accepting overloads:
```java
public static Path taskRunRoot(WorkspaceDescriptor descriptor, String taskName, String taskRunId) {
    // validates descriptor.kind() == AGENT, then delegates
}
public static Path taskRunOutputs(WorkspaceDescriptor descriptor, String taskName, String taskRunId) { ... }
```

`WorkspaceDirectoryService` gains:
```java
public Path taskRunOutputs(WorkspaceDescriptor descriptor, String taskName, String taskRunId) {
    validateDescriptor(descriptor);
    // agent only
    Path outputsDir = confinedRelative(descriptor,
        TASKS + "/" + taskName + "/" + taskRunId + "/" + OUTPUTS);
    return ensureDirectory(outputsDir);
}
```

**Key constraint:** Task-run nesting is agent-only. User workspaces and chat workspaces do not get `tasks/` directories. `WorkspacePathLayout` factory methods for task runs must enforce `WorkspaceKind.AGENT`.

#### C-Option 2: Task Runs as a WorkspaceKind Extension (Rejected Pattern)

Add `TASK_RUN` to `WorkspaceKind`. This would violate the closed-kind contract (`DECISION-20260719-02` explicitly closes to AGENT, CHAT, USER) and create a proliferation problem — task runs aren't independent workspace identities; they're children of agent workspaces. **Not recommended.**

#### OutputArtifactService Changes

`materialize()` and `publishExistingFile()` already accept a generic `Path outputDir`. The caller (orchestration runner or task service) becomes responsible for passing `WorkspaceDirectoryService.taskRunOutputs(descriptor, taskName, runId)` instead of `WorkspaceDirectoryService.outputs(descriptor)`. This is a **caller-side change only** — `OutputArtifactService` needs no signature changes.

`RunOutputArtifact` already carries `runId`, `planId`, `taskId`, `jobId`, and canonical workspace identity columns. Adding task-name to the artifact record would improve queryability but isn't strictly required since `runId` already provides lineage.

#### Migration Of Old Flat Outputs

- Existing flat outputs under `workspace/agent/<id>/outputs/` are left inert. No automated migration.
- New runs use the nested `tasks/<name>/<runId>/outputs/` structure.
- The `outputs/` reservation at workspace root remains — it's simply no longer the target for new task-run publications. Chat workspace outputs still use `<chat-root>/outputs/`.
- `OutputArtifactRepository.findArtifactsByRunId()` continues to work regardless of where the file lives, because artifact rows store root-relative `filePath`.

#### Files Tab Navigation

Task-run directories appear as normal subdirectories under `workspace/agent/<id>/tasks/`. The explorer's existing list/inspector handles them. Users can browse `tasks/` → `taskName/` → `runId/` → `outputs/`. No special UI needed beyond the existing tree navigation.

One design decision: should the Files tab show `tasks/` as a top-level entry in the root listing with a special icon or label? Current explorer lists all directories equally. A lightweight enhancement would add a `directoryKind` hint in the listing response for `tasks/`, `.skills/`, and `outputs/`.

---

### Cross-Cutting: Cluster A ∩ Cluster C

#### X1: Task-Run `.skills/` Directories

If a task run directory can contain `.skills/`, this enables task-scoped skill assignment (A3) through pure filesystem presence. The executing agent picks up skills from `<task-run>/.skills/` for the duration of that run.

**Trust model:** Task-run `.skills/` are scoped to the run. If the agent created them during execution, they're implicitly trusted for that run. If they were pre-placed by a user or workflow, trust is gated by whether the parent workspace is trusted for that agent.

**Concrete intersection:**
- `WorkspaceDirectoryService.skillsDir(descriptor)` already handles any `.skills/` under any descriptor root.
- Task-run `.skills/` is simply `confinedRelative(taskRunDescriptor, ".skills")`.
- `AgentSkillRuntimeCatalogService` would check for task-run `.skills/` when an `OrchestrationTaskContext` with a task run ID is active.
- Precedence: task-run `.skills/` > agent-personal `.skills/` > global `skills/`.

#### X2: Skill-Generated Outputs in Task-Run `outputs/`

When an activated skill produces artifacts (generated files, reports, data), they naturally land in the task run's `outputs/` directory since the agent's working directory is the task run root. `OutputArtifactService` already handles this through the existing `materialize()` flow — the caller just passes the task-run outputs path.

**Attribution chain:** `RunOutputArtifact` rows would carry `runId` → `taskName` → `taskRunId` lineage plus the skill name that generated them (if tracked). This enables queries like "show all outputs from skill X across all runs."

#### X3: Agent Context Resolution for Task Scopes

`AgentSkillAgentContextResolver` currently resolves agent identity from `OrchestrationTaskContext`. To support workspace/task-scoped skills, it needs to also resolve:
- The active `WorkspaceDescriptor` (from `workspaceType`/`workspaceKey` in the task context)
- The active task run ID (for task-scoped `.skills/`)
- The trust posture for each discovered skill root

This suggests a richer resolution result than just `Optional<String> agentId` — perhaps a `SkillResolutionContext` record:

```java
public record SkillResolutionContext(
    String agentId,
    WorkspaceDescriptor workspaceDescriptor,  // nullable
    String taskRunId,                          // nullable
    List<SkillDiscoveryRoot> discoveryRoots    // ordered by precedence
) {}
```

#### X4: `.skills/` Visibility in Files Tab

Design choice: should `.skills/` directories be visible in the agent Files tab?

- **Hidden by default (recommended):** `.skills/` is a dot-prefixed convention directory. The explorer already has filtering capability. Like `.git`, it should be hidden from normal browsing to avoid clutter. A "show hidden" toggle could reveal it.
- **Shown with special treatment:** Display `.skills/` entries with a distinct icon and label ("Skills") but otherwise browsable.

The `outputs/` directory is already visible (read) but mutation-rejected. `.skills/` could follow the same pattern or be fully hidden.

#### X5: Compatibility with Closed Workspace-Kind Model

The closed `WorkspaceKind` enum (AGENT, CHAT, USER) is not threatened by either cluster:

- `.skills/` directories are workspace-relative children, not new workspace kinds
- `tasks/` directories are agent-workspace children, not new workspace kinds
- `WorkspacePathLayout` adds well-known child-path constants (`.skills`, `tasks`) alongside existing `OUTPUTS`
- `WorkspaceDirectoryService` adds convenience methods that delegate to `confinedRelative()`
- No new descriptor types, no new identity models

**Risk:** If a developer is tempted to add `WorkspaceKind.TASK_RUN` to model task-run identity, this would violate the closed-kind contract. The architectural guard is that `WorkspaceKind` is explicitly documented as closed and task runs are explicitly children of agent workspaces, not peers.

---

### Cluster B: Observability & Quality (Summary)

This cluster is the least architecturally entangled with A and C, but has touchpoints:

| Feature | Key Concrete Touches | Intersection with A/C |
|---------|---------------------|----------------------|
| B1: Output artifact indexing (25-15) | `OutputArtifactRepository` + full-text index; `OutputController` preview/download routes | Task-run output structure (C) makes indexing more valuable — artifacts are organized by task; lineage queries become natural |
| B2: Runtime observability (25-16) | New `RuntimeMetricsService`; agent detail history tab; container exit-code tracking | Skills activation metrics (which skills were used, success rates) feed into observability |
| B3: Scheduling UI (25-17) | `ScheduleController` fragments; reaction rule editor; `ReactionRuleRepository` | Schedulers may trigger task runs → task-run output structure (C) applies |
| B4: Orchestration validation (25-20) | Test fixtures; Playwright specs; deterministic graph tests | Task-run paths in test fixtures must use `WorkspacePathLayout` factories |
| B5: Workspace/UX review (25-21) | Cross-cutting audit of projects/jobs/tasks/workspaces/outputs/assignment | Natural follow-up after A+C changes land |

B1 (output indexing) is the most valuable early follow-up after C lands because the task-run output structure gives indexing natural hierarchy and lineage.

---

## Trade-offs

### T1: Spec-Format `.skills/` vs Flat `.md` Files

| Dimension | Spec-Format (Option 1/3) | Flat .md (Option 2) |
|-----------|--------------------------|---------------------|
| Compatibility with agentskills.io ecosystem | Full — directories with SKILL.md, scripts/, references/, assets/ | None — incompatible format |
| Parser reuse | Reuses existing `AgentSkillParser`, `AgentSkillCatalogService` | Needs a new simple parser |
| Metadata/assignment | Full metadata persistence, assignment gating, status tracking | No metadata — just raw markdown |
| Activation lifecycle | Progressive disclosure (catalog → activate → resources) | Always-loaded (no activation) |
| Token cost | Low catalog + on-demand activation | High — all flat skills loaded every turn |
| Implementation complexity | Higher — multi-root catalog, provenance tracking | Lower — simple file scan + append |
| Trust gating | Fine-grained (per-skill assignment, per-root trust policy) | Coarse (per-directory trust) |

**Assessment:** Option 3 (hybrid with spec-format `.skills/` and progressive trust) aligns with the existing architecture investment in the Agent Skills spec pipeline while adding workspace-local discovery. Option 2 (flat .md) is simpler but throws away the progressive disclosure lifecycle that protects context windows. The directive says "Skills are `.md` files within these directories" — this suggests Option 2, but the architectural tension with progressive disclosure must be explicitly resolved. If flat `.md` is chosen, the entire activation lifecycle is bypassed for workspace-local skills, and those skills always consume context tokens.

### T2: DB Assignment vs Filesystem-Presence Assignment for Layered Scopes

| Dimension | DB Assignment (A3-Option 1) | Filesystem Presence (A3-Option 2) |
|-----------|------------------------------|-----------------------------------|
| Discovery cost | DB query (fast) | Filesystem scan (slower, cached) |
| Enable/disable control | Per-skill toggle in UI | Add/remove files from directory |
| Audit trail | DB rows with timestamps | Filesystem metadata only |
| Alignment with filesystem-first philosophy | Mixed — DB + filesystem | Pure filesystem |
| Implementation complexity | DB migration, new UI panels | Minimal — path resolution only |

**Assessment:** Filesystem-presence assignment (A3-Option 2) aligns with the filesystem-first workspace model (`DECISION-20260719-02`). However, it loses the enable/disable toggle and audit trail that DB assignment provides. A hybrid is possible: filesystem discovery determines what's available, DB assignment determines what's enabled (already the model for global skills today).

### T3: Task-Run Nesting — `WorkspacePathLayout` vs Caller Responsibility

| Dimension | Layout-Owned Factories | Caller-Constructed Paths |
|-----------|------------------------|--------------------------|
| Consistency | Single authority for path structure | Risk of drift between callers |
| Confinement | Built-in validation | Each caller must validate |
| Testability | Factory method tests | Caller tests must cover path logic |
| Discoverability | IDE-visible constants/factories | Path segments scattered in callers |

**Assessment:** `WorkspacePathLayout`-owned factories are the only correct approach under the architecture contract. Hand-concatenation is explicitly forbidden.

### T4: Phase Ordering — Which Cluster First?

| Order | Rationale |
|-------|-----------|
| **C → A → B** | Task-run output structure (C) is the foundational filesystem change. Skills (A) can then place `.skills/` directories in the correct locations. Observability (B) indexes the final output structure. |
| A → C → B | Skills system lands first, then task runs can carry `.skills/`. But task-run restructuring changes where outputs land, potentially requiring skills work to be revisited. |
| A+C together → B | Highest risk of merge conflict but avoids rework. Skills design depends on task-run directory structure decisions. |

**Recommended:** C first (task-run nesting), then A (skills leverage the new structure), then B (observability indexes the final shape). C is the smallest, most mechanical change with the clearest contract. It establishes the directory layout that A's `.skills/` and task-scoped skills depend on. B is mostly additive and can proceed in parallel with A once C stabilizes.

### T5: `.skills/` Files Tab Visibility

- **Hidden:** Clean interface, no clutter, aligns with dotfile conventions. Risk: users can't see or manage skills through the UI.
- **Visible:** Users can browse skill files, edit them in the built-in editor. Risk: clutter in the file tree, accidental edits break skills.
- **Hidden with toggle:** Best of both, but adds UI complexity.

**Assessment:** Hidden by default with a "show hidden files" toggle is the standard pattern and aligns with the existing explorer design.

---

## Open Questions

1. **Flat `.md` vs spec-format for `.skills/`:** The directive says "Skills are `.md` files within these directories." Does this mean flat files (no subdirectories, no `SKILL.md`, no `scripts/`/`references/`/`assets/`)? If so, how does progressive disclosure work? Are workspace-local skills always loaded (token-expensive) or is there a simplified activation model? Should the flat format be up-converted to spec format internally?

2. **`.skills/` in chat workspaces:** Should `workspace/chat/<id>/.skills/` be supported? Chat workspaces are agent execution surfaces — the executing agent's own `.skills/` would already be loaded from the agent workspace. Chat-specific `.skills/` would be per-conversation skill injection, which overlaps with the existing `activate_skill` tool. Recommend: chat workspaces do NOT get `.skills/` — use tool activation instead.

3. **`.skills/` in user workspaces:** User workspaces can have `.skills/`. When an agent runs in a user workspace context, should it pick up the user's `.skills/`? This connects to the trust model — user-workspace `.skills/` must be explicitly trusted before an agent loads them.

4. **Task-run root cleanup:** When a task run completes (success or failure), should the run directory persist indefinitely or have a retention policy? The `tasks/<name>/<runId>/` structure accumulates runs. Without cleanup, agent workspaces grow unboundedly. Should there be a max-runs-per-task or TTL-based cleanup?

5. **Reserved directory expansion:** `outputs/` is currently the only reserved directory name. Should `.skills/` and `tasks/` also be reserved (explorer mutations rejected)? Recommend: `tasks/` and `.skills/` are reserved like `outputs/` — explorer can read but not mutate or delete.

6. **`WorkspacePathLayout` constant naming:** Should `.skills` be a public constant (`SKILLS_DIR = ".skills"`) or remain a workspace-relative convention? Adding it as a constant signals that it's a first-class structural element, but it's not a root-level directory like `workspace/` or `outputs/`.

7. **Skill provenance in RunOutputArtifact:** Should output artifacts track which skill generated them? Adding a `skillName` column to `run_output_artifacts` enables skill-output lineage queries but adds schema complexity.

8. **Task-run `.skills/` and AGENTS.md interaction:** `AGENTS.md` resolution already walks from descriptor root to active runtime path. If a task-run `.skills/` exists alongside a task-run `AGENTS.md`, what's the precedence between skill instructions and AGENTS.md guidance? The AGENTS.md layered model (`DECISION-20260526-02`) already handles ancestor context with closest-wins conflict resolution. Skill content from `activate_skill` is injected as tool output, not as a prompt layer — so it doesn't directly conflict with AGENTS.md layers.

9. **Global skills migration:** If agent-personal `.skills/` becomes the primary mechanism, does the global `skills/` repository become a fallback or shared library? The directive says "agents inherit skills from their own personal workspace's `.skills/`" — this suggests agent-personal is the primary source, with global as a shared baseline.

10. **Task name validation:** Task names in `tasks/<taskName>/` paths need the same `PlainPathSegmentValidator` treatment as agent IDs and user names. Should task names have the same strict character constraints, or a more permissive policy since they're user-created?

---

## Recommended Next Step

1. **Resolve the flat `.md` vs spec-format question** (Open Question 1) with a product decision. This determines whether Cluster A extends the existing parser/catalog pipeline or introduces a parallel lightweight path. The architectural implications are materially different.

2. **Specification artifact for Cluster C** — author `.internal-dev/specifications/` entries for `WorkspacePathLayout` task-run extensions, `WorkspaceDirectoryService` new methods, and `OutputArtifactService` caller contract changes. This is the most mechanical change with the clearest contract and should be specified first.

3. **Lightweight prototype of `.skills/` directory resolution in `WorkspaceDirectoryService`** — add `skillsDir(WorkspaceDescriptor)` and validate that path confinement, reserved-directory interactions, and Files-tab visibility work as expected. This de-risks the A∩C intersection without committing to the full skills pipeline.

4. **Defer Cluster B** until C lands and A's discovery model stabilizes. B1 (output indexing) is the most valuable B feature and depends on C's task-run structure. B2-B5 can be sequenced independently.
