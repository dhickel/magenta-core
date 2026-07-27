## Prompt

Synthesis review of three sprint brainstorm reports covering the Agent Skills System (A), Observability & Quality (B), and Task Run Output Structure (C) for the Magenta agent platform. The task is to compare useful ideas, identify conflicts, omissions, feasibility issues, and trade-offs across the reports, preserving the Runtime Outputs & Lifecycle lens without merely defending the first report. Do not edit the project.

The three reports under review:

1. **"Runtime Outputs, Observability, and Lifecycle"** (this worker — Clusters B+C as one lifecycle)
2. **"Workspace & Skill Architecture"** (Cluster A + C intersections, architecture/security lens)
3. **"Product Integration & Delivery"** (all three clusters, product/UX/delivery lens)

## Source

All three reports consulted the same authoritative specifications and source code; no new project files were read for this synthesis. Cross-references verified against:

- `.internal-dev/specifications/architecture.md` — ARCH-20260719-01 (three-kind workspace), ARCH-20260526-03 (skills MVP)
- `.internal-dev/specifications/services.md` — SVC-20260719-01 through 06, SVC-20260526-03
- `.internal-dev/specifications/decisions.md` — DECISION-20260719-02 (closed WorkspaceKind)
- `.internal-dev/knowledge/agent-skills-specification-reference.md` — spec-format requirement (directory per skill, `SKILL.md` with YAML frontmatter, progressive disclosure)
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md` — definitive workspace model
- `WorkspacePathLayout.java` — current: only `WORKSPACE` + `OUTPUTS`, three root factories
- `WorkspaceDirectoryService.java` — sole descriptor constructor, `outputs()`, `confinedRelative()`
- `OutputArtifactService.java` — `materialize(Path outputDir)` caller-supplied directory
- `WorkspaceExplorerService.java` — descriptor-first, mutation-rejects `outputs/`
- `AgentSkillActivationService.java` — dedicated `activate_skill` tool, conversation-scoped dedupe
- `AgentSkillRepositoryService.java` — root-confined at `MagentaRootProperties.path()/skills`

## Findings

### Agreements (All Three Reports Converge)

**F1: Phase ordering is C → A → B.** Task-run output structure is the foundational filesystem change. Skills discovery depends on a stable `WorkspacePathLayout`. Observability indexes the final structure. All three reports independently arrive at this sequence, though with different sub-phase granularity.

**F2: `WorkspacePathLayout` owns all new structural constants.** No hand-concatenation. The sole structural path authority contract from `DECISION-20260719-02` extends to `tasks/`, `.skills/`, and any future reserved names. All three reports agree: add constants (`TASKS`, `SKILLS_DIR`) and factory methods (`taskRunRoot()`, `taskRunOutputs()`, `agentSkillsRoot()`) to `WorkspacePathLayout`.

**F3: Legacy flat outputs remain inert.** Moving historical files from `workspace/agent/<id>/outputs/` to the new nested structure would break `RootRelativePathService` references in existing `OutputArtifactRepository` rows. All three reports independently conclude: leave inert, queryable via DB, no migration.

**F4: Task-run nesting is agent-only.** Chat workspaces keep flat `outputs/`. User workspaces have no task execution concept. `WorkspacePathLayout` task-run factories must validate `WorkspaceKind.AGENT`. Strong agreement.

**F5: `OutputArtifactService.materialize()` needs no signature change.** It already accepts `Path outputDir` from the caller. The change is in the callers (`WorkflowRunner`, `OrchestrationRunnerService`, `PlanService`, `TaskService`), which switch from passing `directoryService.outputs(descriptor)` to passing the nested path. All three reports identify this as a caller-side change only.

**F6: A reserved-directory registry is needed.** With `outputs/`, `.skills/`, and `tasks/` all having special access policies, scattered conditionals become unmaintainable. The Product Integration report proposes a centralized `ReservedDirectoryPolicy` enum. The Skill Architecture report implies it. The Runtime Outputs report mentions "optional special rendering" for reserved directories. This is a clear implementation convergence point.

**F7: Agent-personal `.skills/` auto-trust with audit.** The Skill Architecture report recommends auto-trust (agent owns its workspace). The Product Integration report recommends auto-trust with audit log of which skills were loaded per run. No conflict — the audit log is an enhancement, not a disagreement.

**F8: `.skills/` must be in the reserved-directory registry.** The Files tab shows `.skills/` as read-only (browsable, not mutable by explorer). Skill management goes through the dedicated Skills browser. All reports that address Files-tab behavior converge here.

### Conflicts (Design Decisions That Diverge)

**C1: Task name vs. task ID as the directory segment.**

| Report | Position | Rationale |
|--------|----------|-----------|
| Runtime Outputs (this worker) | `taskName` (validated with `PlainPathSegmentValidator`) | Human-readable, what agents and users see. Name collision resolved with dedup suffix. |
| Skill Architecture | `taskName` (same) | Consistent with the directive's `tasks/<taskName>/<taskRunId>/` notation. |
| Product Integration | `taskId` (UUID) | Task definitions can be renamed; `taskName` as a mutable path segment orphanes old run directories from name-based lookup. Store `taskName` as a `task.json` metadata file at the run root. |

**Resolution required:** This is a genuine design decision with non-trivial consequences. Using `taskName` makes the filesystem human-navigable but breaks if tasks are renamed. Using `taskId` (UUID) is stable but opaque. A compromise: use `taskId` as the primary directory name and create a sibling `taskName → taskId` mapping file or symlink. Alternatively, store the mapping in the DB and treat directory names as immutable keys. **Recommendation: use `taskId` (UUID) for stability.** The Files tab can display the task name as a label via the DB mapping. This is the safer choice and avoids an irreversible coupling between task definition renames and filesystem layout. The Product Integration report's `task.json` pattern is a clean way to surface the name.

**C2: User workspace task-run nesting.**

| Report | Position |
|--------|----------|
| Runtime Outputs (this worker) | User workspaces SHOULD get task-run nesting ("User workspaces are explicit overrides for agent execution. They should get the same task-run structure as agent workspaces since they host the same task runs.") |
| Product Integration | User workspaces do NOT get task-run nesting ("User workspaces have no task execution concept; they are declarative spaces.") |

**Resolution required:** The question hinges on whether user workspaces ever host task runs. Under `DECISION-20260719-02`, execution can select a user workspace via `workspaceType=user, workspaceKey=X`. If a task runs against a user workspace, it needs a place to write outputs. The Product Integration position ("user workspaces don't execute tasks") contradicts the execution selection contract which explicitly allows user workspace overrides. **Recommendation: user workspaces should get task-run nesting when selected as execution targets.** The execution selection contract (`workspaceType=user, workspaceKey=X`) already permits this; the filesystem should support it. The `WorkspacePathLayout` factories for task-run paths should accept any descriptor where `kind() == AGENT || kind() == USER` (not `CHAT`).

**C3: Task-run `.skills/` directories.**

| Report | Position |
|--------|----------|
| Skill Architecture | Explores as a natural A∩C intersection — task-scoped skill assignment through pure filesystem presence. Documents the precedence chain: "task-run `.skills/` > agent-personal `.skills/` > global `skills/`." |
| Product Integration | Explicitly out of scope: "A task run *could* bundle skills in its own directory... this adds complexity: skills are typically stable across runs; per-run skills would need activation logic changes and wouldn't benefit from the catalog pre-loading pattern. Keep `.skills/` at the agent-root and execution-workspace-root level." |

**Resolution required:** This is a scope decision, not a design correctness decision. The Skill Architecture report documents the *mechanism* (it's feasible and fits the filesystem-first model). The Product Integration report argues it's *unnecessary complexity* for this sprint. **Recommendation: follow Product Integration — defer task-run `.skills/`.** Document it as a horizon idea with the precedence chain already specified by Skill Architecture. The mechanism is clear if needed; the use case isn't proven.

**C4: `run_events` table for runtime observability.**

| Report | Position |
|--------|----------|
| Runtime Outputs (this worker) | Proposes optional append-only `run_events` table with JSON payload for intra-run telemetry (step timeline, tool calls, LLM turn latencies). Recommends computed metrics from existing timestamps first; add the table only when needed. |
| Skill Architecture | Does not address runtime observability in detail (focuses on A+C). |
| Product Integration | Proposes a `run_metrics` table with "exit codes, duration, token usage, model used, and error messages as columns." Also proposes per-agent metrics endpoint with p50/p90/p99 latencies and sparkline charts. |

**These aren't conflicting — they're at different granularities.** The Product Integration `run_metrics` table captures per-run summary metrics (exit code, duration, token usage). The Runtime Outputs `run_events` table captures intra-run event-level detail (step timeline, individual tool calls). Both are useful and complementary. **Recommendation: adopt both.** `run_metrics` for dashboard summaries (cheap, indexed). `run_events` for debugging individual runs (append-only, flexible JSON payload). Start with `run_metrics` in Phase 4 (Runtime Observability); defer `run_events` until a concrete debugging need arises.

### Omissions (One Report's Contribution That Others Miss)

**O1: Artifact lineage (Runtime Outputs only).** The concept of `parentArtifactIds` for DAG-based artifact derivation chains is unique to the Runtime Outputs report. Neither other report addresses lineage tracking. This is a genuine observability gap — without lineage, you can't answer "what input artifacts produced this output?" or "which downstream runs consumed this artifact?" **Recommendation: adopt as Phase 6 (after task-run nesting, artifact search, and runtime metrics).** Lineage depends on task-run directories for coherent scope.

**O2: FTS5 full-text search (Runtime Outputs only).** The `content_text` column + SQLite FTS5 virtual table design for artifact content search is unique to Runtime Outputs. Product Integration mentions "Add a `full_text_search` column or virtual table" but doesn't provide the `content_text` extraction strategy (strip Markdown, concatenate JSON keys). Skill Architecture doesn't address search. **Recommendation: adopt the Runtime Outputs `content_text` + FTS5 approach.** It's the most concrete and leverages SQLite's built-in full-text capabilities without external dependencies.

**O3: Progressive disclosure and token economics (Skill Architecture only).** The Skill Architecture report identifies a critical architectural tension: if `.skills/` uses flat `.md` files (as the directive suggests), the entire progressive disclosure lifecycle (catalog → activate → resources) is bypassed, and workspace-local skills always consume context tokens. Product Integration assumes spec-format without flagging this tension. Runtime Outputs doesn't address skills at all. **This is the most important unresolved tension in Cluster A.** The directive says "Skills are `.md` files within these directories" — but the entire existing skills pipeline is built around `SKILL.md` in subdirectories with progressive disclosure. **Recommendation: resolve this product decision before any skills implementation begins.** If flat `.md` is required, a new lightweight pipeline is needed and the token cost must be explicitly accepted. If spec-format, the directive's "`.md` files" phrasing is imprecise and should be interpreted as "skills are stored in `.skills/` directories using the standard Agent Skills spec format."

**O4: Centralized `ReservedDirectoryPolicy` enum (Product Integration only).** The Product Integration report proposes:

```java
enum ReservedDirectory {
    OUTPUTS("outputs", AccessPolicy.MUTATION_REJECTED, Visibility.VISIBLE),
    DOT_SKILLS(".skills", AccessPolicy.READ_ONLY, Visibility.VISIBLE),
    TASKS("tasks", AccessPolicy.READ_ONLY_CHILDREN, Visibility.VISIBLE);
}
```

Neither other report proposes a concrete enum, though both imply the need. This is the right implementation pattern — it prevents scattered string comparisons in `WorkspaceExplorerService` and `WorkspaceExplorerFragments`. **Recommendation: adopt the `ReservedDirectoryPolicy` enum.** It cleanly encodes the access policy (MUTATION_REJECTED, READ_ONLY, READ_ONLY_CHILDREN) and visibility (VISIBLE, HIDDEN, HIDDEN_WITH_TOGGLE) for each reserved directory.

**O5: `SkillResolutionContext` record for agent context resolution (Skill Architecture only).** The Skill Architecture report proposes:

```java
public record SkillResolutionContext(
    String agentId,
    WorkspaceDescriptor workspaceDescriptor,
    String taskRunId,
    List<SkillDiscoveryRoot> discoveryRoots
) {}
```

This enriches the current `AgentSkillAgentContextResolver` (which only resolves `agentId`) with workspace descriptor and task-run awareness. Neither other report addresses this integration point. **Recommendation: adopt.** This is the cleanest way to thread workspace-aware skill discovery through the existing context resolution pipeline without invasive changes to `PromptContextAssembler` or `ChatService`.

**O6: Caller audit list for output-path changes (Product Integration only).** The Product Integration report specifically identifies `WorkflowRunner.outputPathFor(run)`, `OrchestrationRunnerService` (task execution path), `PlanService.executeTask()`, and `TaskService` compatibility layer as the callers that need updating for the new nested output paths. Runtime Outputs identifies the same pattern but doesn't list the specific callers. **Recommendation: use the Product Integration caller list as the implementation checklist.** It's verified and comprehensive.

### Cross-Cutting Integration Points (All Three Clusters)

**X1: Reserved directory registry is the integration backbone.** After all three clusters land, `WorkspaceExplorerService` must handle `outputs/`, `.skills/`, and `tasks/` with distinct access policies. The `ReservedDirectoryPolicy` enum (Product Integration) is the single integration point. Without it, each cluster would add its own conditionals to the explorer, creating merge conflicts and maintenance debt.

**X2: `WorkspacePathLayout` is the structural integration backbone.** Every new path constant and factory method from every cluster must land here. The class will grow from 2 constants + 3 factories to roughly 5-6 constants + 8-9 factories. This is a manageable growth that preserves the single-authority contract.

**X3: `AgentSkillRuntimeCatalogService` is the A∩C integration point.** When task-run nesting (C) lands, the skill discovery pipeline (A) needs to resolve which skills are applicable for a given run. This means `AgentSkillRuntimeCatalogService.catalogForConversation()` must accept a `SkillResolutionContext` that includes the `WorkspaceDescriptor`, not just the `agentId`. The Skill Architecture report's `SkillResolutionContext` record is the right vehicle.

**X4: `OutputArtifactRepository` is the B∩C integration point.** Output indexing (B1) and task-run nesting (C) both touch artifact persistence. The `taskName`/`taskRunId` columns added for C become query dimensions for B1 ("show all outputs from task X"). The `content_text` column added for B1 (full-text search) is populated at materialization time, which happens in the caller that also constructs the nested output path (C). No circular dependency — additive columns.

**X5: Skill-usage metrics are the A∩B integration point missing from all reports.** When skills are activated during task runs, observability (B2) could track: which skills were activated per run, activation success/failure rates, skill-to-output correlations. None of the reports address this. **Recommendation: add `skillName` to `RunOutputArtifact` (optional column) and `skillName` to the proposed `run_metrics` or `run_events` tables.** This closes the A→B gap with minimal schema work.

### Feasibility Assessment

**Highest-confidence changes (ready to implement):**
- `WorkspacePathLayout` additions (TASKS constant, task-run factories) — mechanical, well-understood pattern
- Caller-side output-path changes — surgical, concentrated in 4-5 callers
- `RunOutputArtifact` column additions (taskName, taskRunId) — additive `addColumnIfMissing` pattern proven in prior refactor
- Reserved directory enum — new type with low blast radius

**Medium-confidence (design resolved, implementation non-trivial):**
- Skill scope discovery aggregation — new service with multi-root merging, precedents in existing catalog
- Files tab run-aware grouping — UI work with Playwright validation burden
- FTS5 artifact search — SQLite feature with well-understood integration pattern, but triggers/virtual tables need care
- Schedule/reaction UI — new controller + fragments surface, domain logic exists

**Requires product decision before implementation:**
- Flat `.md` vs. spec-format for `.skills/` directories (Open Question 1 in Skill Architecture)
- Task name vs. task ID as directory segment (Conflict C1 above)
- Whether user workspaces host task runs (Conflict C2 above)

**Deferred or optional:**
- `run_events` intra-run event table — implement only when concrete debugging need arises
- Artifact lineage (`parentArtifactIds`) — Phase 6, depends on Phases 1-3
- Task-run `.skills/` — horizon idea, not this sprint
- Retention policies — Phase 6
- Per-run skill bundling — horizon idea

## Options

### Option 1: Unified Phase Plan (Resolve Conflicts First, Then Implement)

1. **Pre-phase: Resolve the three product decisions** (flat `.md` vs spec-format, taskName vs taskId, user-workspace task nesting). Without these, implementation will rework.
2. **Phase 1 (C): Task-Run Directory Identity.** `WorkspacePathLayout` additions, `WorkspaceDirectoryService` methods, caller-side output-path changes. Uses `taskId` (UUID) as directory name based on Conflict C1 resolution. Supports user workspaces based on Conflict C2 resolution.
3. **Phase 2 (A): Skills `.skills/` Discovery.** Uses spec-format based on O3 resolution. Adds `SKILLS_DIR` to `WorkspacePathLayout`. Implements `AgentSkillScopeDiscoveryService` with `AgentPersonalSkillScopeResolver`. Adds `SkillResolutionContext`. Registers `.skills/` and `tasks/` in `ReservedDirectoryPolicy`.
4. **Phase 3 (B1): Artifact Indexing & Search.** `content_text` column, FTS5 virtual table, search API endpoint, Files tab search integration.
5. **Phase 4 (B2): Runtime Observability.** `run_metrics` table, computed metrics from timestamps, agent metrics dashboard. Defers `run_events` to post-sprint.
6. **Phase 5 (B3): Schedule/Reaction UI.** SimplyPages management surfaces for schedules and reaction rules. Independent of prior phases.
7. **Phase 6 (B1 extension): Lineage & Retention.** `parentArtifactIds`, TTL policies, background cleanup.

**Pros:** Resolves the three conflicts before any code touches files. Prevents rework from wrong directory naming or wrong skills format. Each phase has a clear dependency chain.
**Cons:** Pre-phase product decisions may stall the entire sprint. If the decisions take time, Phase 1 could start with the mechanical `WorkspacePathLayout` additions (which are identical under either taskName/taskId resolution) while the product questions are debated.

### Option 2: Parallel Tracks (A and C Independent, B Follows)

- **Track 1:** Cluster C (task-run nesting) — no dependency on A
- **Track 2:** Cluster A (skills `.skills/` discovery) — can begin once `SKILLS_DIR` constant exists in `WorkspacePathLayout`, which Track 1 adds
- **Track 3:** Cluster B (observability) — begins after Track 1 stabilizes

Since Track 2 depends on `WorkspacePathLayout` additions from Track 1, this isn't fully parallel. However, the skills pipeline changes (scoped discovery, `SkillResolutionContext`, trust audit) can be developed against a test fixture while Track 1 lands.

**Pros:** Some parallelism reduces wall-clock time. Skills work doesn't block on task-run output callers.
**Cons:** Integration risk — skills discovery might need Files-tab changes that aren't ready yet.

### Option 3: Minimum Viable Liftoff (C only, then reassess)

Land only Cluster C (task-run nesting) and the Files tab navigation updates. Ship it. Then reassess whether A or B is most valuable. This is the lowest-risk path and aligns with the architectural principle of building the smallest complete thing.

**Pros:** Lowest risk. Gets the foundational filesystem change in production. Provides real-world usage data for Files tab task-run navigation.
**Cons:** Delays skills and observability value. Skills system in particular has strong user demand.

## Trade-offs

| Trade-off | Options | Recommendation |
|-----------|---------|----------------|
| `taskName` vs `taskId` | Human-readable but mutable vs. stable but opaque | **`taskId` (UUID).** Safer. Add `task.json` with display name. Human readability comes from the Files tab UI, not the path. |
| User workspace task nesting? | Support it (execution contract allows it) vs. Agent-only (user workspaces are declarative) | **Support it.** The execution contract `workspaceType=user, workspaceKey=X` already permits this. Denying it would create a silent failure mode in production. |
| Flat `.md` vs. spec-format `.skills/` | Simple but token-expensive and loses progressive disclosure vs. complex but preserves lifecycle | **Spec-format.** The existing pipeline investment, progressive disclosure lifecycle, and agentskills.io ecosystem compatibility outweigh the simplicity of flat files. If the directive's `.md` phrasing is intentional, treat it as a product override requiring explicit acceptance of the token cost and lifecycle loss. |
| `run_metrics` vs. `run_events` vs. both | Summary metrics now vs. event-level detail now vs. both staged | **`run_metrics` first, `run_events` deferred.** Per-run summary metrics (exit code, duration, tokens) enable the agent history dashboard immediately. Event-level detail is for debugging and can wait. |
| Centralized `ReservedDirectoryPolicy` vs. scattered conditionals | Single enum, testable, growable vs. inline checks | **Centralized enum.** All three clusters add reserved directories. Scattered conditionals are the path to merge conflicts and missed policies. |
| Lineage/retention now vs. later | Adds complexity to Phase 1 vs. misses observability | **Phase 6 (later).** Lineage requires every consumer to declare inputs — significant integration work. Retention needs stable task-run directories first. Both are valuable but not blocking. |
| Task-run `.skills/` now vs. horizon | Filesystem presence as assignment vs. deferred complexity | **Horizon idea.** The mechanism is sound (Skill Architecture report documents it clearly) but the use case isn't proven and the activation pipeline isn't ready for per-run scope. |

## Open Questions

1. **Flat `.md` vs. spec-format for `.skills/`:** The directive says "Skills are `.md` files within these directories." The entire existing skills pipeline (parser, catalog, activation, progressive disclosure) assumes the agentskills.io spec format (directory per skill, `SKILL.md` with YAML frontmatter). If flat `.md` is the product intent, this is a substantially different implementation than any report assumes. **Must be resolved before Cluster A implementation begins.**

2. **Does the product directive intentionally bypass progressive disclosure?** If `.skills/` uses flat `.md` files, they are always-loaded (no catalog → activate lifecycle), which means all workspace-local skills consume context tokens on every turn. This is a product-level trade-off: simplicity vs. token cost. None of the reports address whether this trade-off has been explicitly accepted.

3. **`taskId` vs. `taskName` as directory segment:** Conflict C1 above. Needs product decision. The Files tab can display names regardless of the directory naming convention, so the filesystem choice is about stability, not user experience.

4. **Should the `WorkspacePathLayout` task-run factories accept `WorkspaceKind.USER`?** Conflict C2 above. If yes, user workspaces get `tasks/` directories. If no, user-workspace execution must write outputs to flat `outputs/`, creating an inconsistency.

5. **Where does the `ReservedDirectoryPolicy` enum live?** Product Integration suggests `WorkspaceExplorerService`. It could also live in `WorkspacePathLayout` (alongside the path constants) or as a standalone type. Recommendation: `WorkspacePathLayout` or a sibling `ReservedDirectoryPolicy` class in the same package, since it's structural policy closely tied to path layout.

6. **Should skill-usage metrics (A∩B) be included in the Phase 4 `run_metrics` table?** Adding a `skills_activated` column (JSON array of skill names) to `run_metrics` is cheap and closes the A→B observability gap. This was missed by all three reports.

7. **What is the Files tab behavior for `tasks/<taskId>/` entries where the task has been deleted?** If task definitions can be deleted, orphaned run directories remain. The Files tab would show UUID-named directories with no display name. Recommendation: the task-run listing queries the DB for task names and shows "Deleted task (ID)" for orphans.

8. **Should `workspace/agent/<id>/.skills/` be auto-created like `outputs/`?** The lazy-create pattern for agent roots currently only applies to the root directory itself. `outputs/` is lazy-created by `WorkspaceDirectoryService.outputs()`. Should `.skills/` be lazy-created or must the agent/operator explicitly create it? Recommendation: explicitly created (not lazy). An empty `.skills/` directory implies "this agent has no personal skills" which is semantically different from "skills haven't been loaded yet."

9. **How does the Files tab `tasks/` listing handle very large numbers of runs?** An agent might have hundreds of runs for a single task. The explorer should paginate or group by task name and show the most recent N runs with a "show more" affordance.

10. **Cross-report validation:** The Skill Architecture report and Product Integration report both recommend Phase 1 specification artifacts. Runtime Outputs does not. All three reports should produce or contribute to a single specification update for `architecture.md` and `services.md` before implementation. How should the spec merge be coordinated?

## Recommended Next Step

1. **Resolve the three blocking product decisions** before any implementation begins:
   - Flat `.md` vs. spec-format for `.skills/` (O3)
   - `taskName` vs. `taskId` as directory segment (C1)
   - User workspace task-run nesting (C2)

   These decisions have cascading effects across all three clusters. Present them to the product owner as a single decision document with the trade-offs documented here.

2. **Merge the reports into a single Phase 1 specification update.** All three reports agree on the `WorkspacePathLayout` additions, `WorkspaceDirectoryService` methods, `ReservedDirectoryPolicy` enum, and caller-side output-path changes. Consolidate into `.internal-dev/specifications/architecture.md` (new ARCH entry) and `.internal-dev/specifications/services.md` (new SVC entries) before implementation. This prevents drift between the three perspectives.

3. **Adopt the concrete artifacts from each report:**
   - From Runtime Outputs: `content_text` column design, FTS5 integration pattern, `parentArtifactIds` lineage model, phased `run_metrics`/`run_events` strategy
   - From Skill Architecture: `SkillResolutionContext` record, `AgentSkillScopeDiscoveryService` aggregation pattern, trust model tiers, A∩C precedence chain documentation
   - From Product Integration: `ReservedDirectoryPolicy` enum design, caller audit checklist, `task.json` display-name pattern, Files tab visibility policy

4. **Implement Phase 1 (Cluster C) using the merged specification.** Track 1: add `TASKS` constant, `taskRunRoot()`/`taskRunOutputs()` factories, `ReservedDirectoryPolicy` enum. Track 2: update callers. Track 3: `RunOutputArtifact` columns (`taskName`, `taskRunId`). Track 4: Files tab task-run navigation with reserved-directory treatment.

5. **Prepare Phase 2 (Cluster A) specification during Phase 1 implementation.** The skills system changes depend on the product decisions from step 1. Having the spec ready means Phase 2 can start immediately when Phase 1 lands.

6. **Defer `run_events`, lineage, retention, and task-run `.skills/`** to post-sprint horizon or later phases. Document all four in `.internal-dev/specifications/horizon-ideas.md` with the design rationale from the relevant reports.
