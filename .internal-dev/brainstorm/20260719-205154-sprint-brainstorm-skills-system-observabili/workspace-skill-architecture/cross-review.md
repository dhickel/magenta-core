
# Comparative Review: Skills, Observability, and Task-Run Architecture Reports

## Prompt

Cross-report comparative analysis of three sprint-brainstorm artifacts produced by independent workers exploring overlapping clusters: **(A)** Agent Skills System with `.skills/` workspace directories, **(B)** Observability & Quality, and **(C)** Task Run Output Structure. Each report was asked to explore from a different lens — broad architecture/security (Report 1, "Workspace, Skills, and Trust Architecture"), operational lifecycle (Report 2, "runtime-output-observability"), and product-integration delivery (Report 3, "product-integration-delivery"). This review compares useful ideas, identifies conflicts, flags omissions, evaluates feasibility, and synthesizes trade-offs without defending any single report's conclusions.

## Source

Three reports compared:

| Label | Lens | Primary Clusters | Distinctive Contribution |
|-------|------|-----------------|--------------------------|
| **R1** (Workspace, Skills, and Trust Architecture) | Broad architecture + security; A∩C intersection | A, C | Three concrete options for `.skills/` integration with existing parser/catalog pipeline; class-level method signatures; trust boundary propagation into prompts/tools/chat |
| **R2** (runtime-output-observability) | Operational lifecycle; B+C as one continuum | B, C | Concrete SQL schemas and API routes; artifact lineage, retention, and full-text indexing; `run_events` telemetry table; schedule/reaction UI design |
| **R3** (product-integration-delivery) | Product delivery feasibility; all three clusters | A, B, C | Reserved-directory registry enum; scope precedence chain; `.skills/` structural path options (A1a vs A1b); task-name stability analysis |

All three reports consulted the same authoritative specification files, knowledge documents, and source code anchors. Differences arise from lens and depth of exploration within each cluster, not from conflicting source material.

## Findings

### Agreements (All Three Reports Concur)

| Finding | R1 | R2 | R3 | Notes |
|---------|:--:|:--:|:--:|-------|
| Phase ordering: C → A → B | ✓ | ✓ | ✓ | Unanimous. C establishes the filesystem foundation; A builds on it; B polishes. |
| `WorkspacePathLayout` must own new structural constants | ✓ | ✓ | ✓ | All propose adding `TASKS` and factory methods. Hand-concatenation forbidden. |
| `OutputArtifactService.materialize()` needs no signature change | ✓ | ✓ | ✓ | Caller-side change only — pass nested path instead of flat `outputs/`. |
| Legacy flat `outputs/` left inert, not migrated | ✓ | ✓ | ✓ | Old files remain; new runs use nested structure. No `RootRelativePathService` breakage. |
| Chat workspaces: no task-run nesting | ✓ | ✓ | ✓ | Chat outputs stay flat at `workspace/chat/<id>/outputs/`. |
| `.skills/` should be in the reserved-directory registry | ✓ | — | ✓ | R2 doesn't address `.skills/` Files-tab policy directly but its reserved-directory analysis covers `outputs/` and `tasks/` — same pattern. |
| Files tab needs task-run-aware grouping view | ✓ | ✓ | ✓ | Raw directory tree insufficient; status badges, artifact counts, timestamps needed. |
| Closed `WorkspaceKind` (AGENT, CHAT, USER) is not threatened | ✓ | ✓ | ✓ | No new workspace kinds. `tasks/` and `.skills/` are workspace children, not peers. |
| `WorkspaceDirectoryService` gains `taskRunOutputs()` convenience method | ✓ | ✓ | ✓ | All agree on the method; R2 provides most concrete signature detail. |
| Agent-personal `.skills/` auto-discovered (no assignment rows needed) | ✓ | — | ✓ | R2 doesn't address skills assignment but its execution-context analysis is compatible. |
| Trust gating for non-agent `.skills/` scopes | ✓ | — | ✓ | R1 details trust model; R3 adds audit-log nuance; R2 doesn't address skills trust. |

### Conflicts (Divergent Recommendations)

#### Conflict 1: User Workspace Task-Run Nesting

- **R1 (and R3):** Task-run nesting is agent-only. User workspaces do NOT get `tasks/` directories. `WorkspacePathLayout.taskRunRoot()` should validate `descriptor.kind() == AGENT`.
- **R2:** "User workspaces should get the same task-run structure as agent workspaces since they host the same task runs. Add `workspace/user/<name>/tasks/<taskName>/<taskRunId>/outputs/`."

**Analysis:** This is the most consequential disagreement. R2's argument is logical — if execution can target a user workspace (which the current model allows via `workspaceType=user`), then task runs executing there need output directories. R1/R3's counter-argument is that user workspaces are explicitly created, declarative spaces without task execution concepts. The execution model says user workspaces *override* agent workspaces for file operations, but task runs are always agent-owned. The resolution depends on whether `OrchestrationRunnerService` passes the user workspace descriptor to task execution or always uses the bound agent descriptor for task-run identity.

**Resolution needed:** Audit `OrchestrationRunnerService` and `WorkflowRunner` to determine if task runs ever actually execute against a non-agent descriptor. If they do, R2 is correct and user workspaces need `tasks/`. If they always use the agent descriptor regardless of the selected workspace, R1/R3 are correct and task-run nesting is agent-only.

#### Conflict 2: `.skills/` Format — Flat `.md` vs Spec-Format Directories

- **R1:** Identifies this as the critical open question. The directive says "Skills are `.md` files within these directories" — suggesting flat files. But flat files bypass progressive disclosure (catalog→activate→resources), are token-expensive (always loaded), and are incompatible with the agentskills.io ecosystem (scripts/, references/, assets/). Three options offered.
- **R3:** Assumes spec-format (`skill-slug/SKILL.md` with subdirectories) without discussing the flat `.md` alternative. References "SKILL.md" and "skill directories" throughout.
- **R2:** Does not address skills format — focused on B+C clusters.

**Analysis:** This is the single most impactful architectural decision for Cluster A. If flat `.md` is chosen, the entire existing `AgentSkillParser`, `AgentSkillCatalogService`, and `AgentSkillActivationService` pipeline is bypassed for workspace-local skills — a parallel lightweight path is needed. If spec-format is chosen, the existing pipeline extends naturally to multiple discovery roots. R1's Option 3 (hybrid spec-format with progressive trust) aligns with R3's assumed approach.

**Resolution needed:** Product decision on whether `.skills/` uses flat `.md` files or the agentskills.io spec format. This decision gates the entire Cluster A implementation approach.

#### Conflict 3: Agent-Personal `.skills/` Auto-Trust vs Explicit Gating

- **R1:** Auto-trusted — "agent owns its workspace, so skills the agent places there are implicitly trusted by that agent."
- **R3:** Start with auto-trust but add audit logging. "If poisoning becomes a concern, add gating later as a non-breaking policy tightening."
- **R2:** Does not address.

**Analysis:** This is a minor conflict — both R1 and R3 favor auto-trust for the initial implementation. R3 adds the audit-log refinement. The disagreement is only about whether to mention the future gating option upfront.

**Resolution:** R3's audit-log position is the stronger recommendation — auto-trust with observability. Both reports should converge here.

#### Conflict 4: `.skills/` in `WorkspacePathLayout` — Structural Constant vs Domain Resolver

- **R1:** More cautious — "Could also be a workspace-relative convention, not a structural path" but acknowledges the `OUTPUTS` precedent.
- **R3:** Definitive — Option A1a wins; add `SKILLS_DIR = ".skills"` to `WorkspacePathLayout` as a structural constant alongside `WORKSPACE` and `OUTPUTS`.
- **R2:** Does not address.

**Analysis:** R3's position is stronger. The `OUTPUTS` constant sets the precedent: reserved workspace-child directories are structural names owned by `WorkspacePathLayout`. `.skills/` has the same characteristics — it's a reserved directory beneath the workspace root with special access policy. R1's caution is understandable (keeping the layout class sparse) but the "sole structural path authority" contract matters more than line count.

**Resolution:** R3's A1a recommendation should stand. Add `SKILLS_DIR` to `WorkspacePathLayout`.

### Omissions by Report

#### R1 Omissions (Addressed by R2 or R3)

| Omission | Addressed By | Detail |
|----------|-------------|--------|
| Artifact lineage (`parent_artifact_ids`) | R2 Option 5 | R1 doesn't discuss how outputs relate to each other across runs |
| Retention policy design | R2 Option 5 | R1 mentions cleanup only as an open question; R2 proposes concrete columns and cleanup mechanics |
| Schedule/reaction UI (B3) | R2 Option 4 | R1 lists B3 as "summary" only; R2 provides fragment names, routes, cron preview design |
| `run_events` telemetry table | R2 Option 2 | R1 doesn't propose concrete observability schema; R2 has a full SQL DDL and service design |
| Reserved-directory registry enum | R3 Cross-Cutting X4 | R2 touches on it; R3 proposes a concrete `ReservedDirectory` enum with `AccessPolicy` and `Visibility` |
| Task name stability and rename handling | R3 Open Question 1 | R1 mentions task name as Open Question 10 but doesn't explore the rename problem deeply |
| `WorkspaceDescriptor` scoping to task runs | R3 Open Question 2 | R1 doesn't discuss whether descriptor should carry run sub-context |

#### R2 Omissions (Addressed by R1 or R3)

| Omission | Addressed By | Detail |
|----------|-------------|--------|
| `.skills/` integration with existing skills parser/catalog | R1 Options 1-3 | R2 doesn't address how `.skills/` connects to `AgentSkillParser`, `AgentSkillCatalogService`, or the activation lifecycle |
| `ToolAccessPolicy` interactions with workspace-local skills | R1 A-Option 3 | R2 doesn't discuss how `activate_skill` tool visibility changes with multi-scope catalogs |
| `AgentSkillAgentContextResolver` needing workspace-descriptor awareness | R1 Cross-Cutting X3 | R2 doesn't address how skill resolution changes when execution context carries workspace info |
| `PromptContextAssembler` layered append for multi-scope skills | R1 A-Option 3 | R2 doesn't discuss how the system prompt changes with multi-scope skill catalogs |
| Files tab `.skills/` visibility and access policy | R1 Cross-Cutting X4, R3 A5 | R2 discusses reserved directories but only for `outputs/` and `tasks/` |
| Scope precedence chain (6 levels) | R3 A2 | R2 doesn't address skill scope ordering |

#### R3 Omissions (Addressed by R1 or R2)

| Omission | Addressed By | Detail |
|----------|-------------|--------|
| `AgentSkillParser` reuse analysis (flat .md vs spec-format) | R1 Options 1-3 | R3 assumes spec-format without discussing the flat .md alternative from the directive |
| `OutputArtifactService` caller audit (grep targets) | R2 Option 1 | R3 mentions callers conceptually; R2 names specific classes to change |
| Full-text search implementation details (FTS5) | R2 Option 3 | R3 mentions indexing but R2 has concrete SQLite FTS5 approach |
| `RunOutputArtifact` column additions (`taskName`, `taskRunId`) | R2 Option 1 | R3 references the need; R2 specifies the columns and their role in queries |
| `run_events` table schema | R2 Option 2 | R3 doesn't propose concrete observability persistence |

### Feasibility Assessment

| Proposal | Feasibility | Risk | Notes |
|----------|------------|------|-------|
| `WorkspacePathLayout` task-run factories (all agree) | **High** | Low | Three new factories, one constant. Follows existing `agentRoot()` pattern exactly. |
| Caller-side `outputDir` change (all agree) | **High** | Low | ~3-5 call sites. `OutputArtifactService` unchanged. |
| Spec-format `.skills/` in agent workspace (R1 Opt 3, R3) | **Medium** | Medium | Extends existing parser/catalog. New `AgentSkillScopeDiscoveryService`, multi-root `AgentSkillCatalogService`. ~4 new/modified classes. |
| Flat `.md` `.skills/` (R1 Opt 2) | **Medium** | Medium-Low | Simpler code but bypasses activation lifecycle. Token-cost concern: all workspace skills loaded every turn. |
| `run_events` telemetry table (R2) | **Medium** | Low | Additive schema, no existing table changes. Append-only write path is cheap. Query performance depends on JSON extraction. |
| Artifact full-text search (R2) | **Medium** | Medium | FTS5 virtual table needs sync maintenance. Content extraction from arbitrary files is best-effort. |
| Schedule/reaction UI (R2) | **Medium** | Medium | New controller surface, ~6 fragments. Cron preview is non-trivial server-side. |
| Artifact lineage (R2) | **Low-Medium** | High | Requires every consumer to declare inputs. Integration scope is large — spans all execution paths. |
| Reserved-directory registry enum (R3) | **High** | Low | Centralizes scattered conditionals. Three entries initially (`OUTPUTS`, `DOT_SKILLS`, `TASKS`). |
| `.skills/` in `WorkspacePathLayout` (R3 A1a) | **High** | Low | One constant, one factory method. Follows `OUTPUTS` precedent. |

## Options

### Synthesis Option 1: Converged Phased Plan (Recommended)

Adopt the unanimous C → A → B ordering with resolved conflicts:

**Phase 1 — Task-Run Structure (C):**
- Add `TASKS` to `WorkspacePathLayout` + `taskRunRoot()` / `taskRunOutputs()` factories
- Add `taskRunOutputs()` to `WorkspaceDirectoryService`
- Resolve Conflict 1 (user workspace nesting) by auditing execution paths before implementing
- Update all output-path callers per R2's caller audit
- Add `taskName`/`taskRunId` columns to `RunOutputArtifact` (R2)
- Register `tasks/` in reserved-directory registry (R3; read-only children)
- Leave flat outputs inert (all agree)

**Phase 2 — Skills System (A):**
- Resolve Conflict 2 (flat .md vs spec-format) with product decision first
- Add `SKILLS_DIR` to `WorkspacePathLayout` (R3 A1a recommendation)
- Implement `AgentSkillScopeDiscoveryService` with `GlobalSkillScopeResolver` + `AgentPersonalSkillScopeResolver` (R1, R3)
- Extend `AgentSkillCatalogService` for multi-root scanning with provenance (R1 Option 3)
- Extend `AgentSkillRuntimeCatalogService` for merged catalogs (R1, R3)
- Update `AgentSkillAgentContextResolver` for workspace-descriptor awareness (R1 X3)
- Update `PromptContextAssembler` for multi-scope catalog append (R1 A-Option 3)
- Register `.skills/` in reserved-directory registry (R3; read-only)
- Add trust audit logging (R3 refinement)
- Defer layered assignment (A3), script trust (A4), and package ingestion (A5) per existing deferred-feature entries

**Phase 3 — Observability (B):**
- Sub-phase 3a: Output indexing (B1) — full-text search per R2 Option 3; preview rendering; retention policy per R2 Option 5
- Sub-phase 3b: Runtime observability (B2) — computed metrics from existing timestamps first; `run_events` table per R2 Option 2 when intra-run granularity needed
- Sub-phase 3c: Schedule/reaction UI (B3) — per R2 Option 4; can overlap with 3a/3b
- Sub-phase 3d: Validation coverage (B4) — Playwright fixtures covering Phases 1-3
- Sub-phase 3e: UX review (B5) — cross-cutting audit artifact

### Synthesis Option 2: Skills-First Ordering

If the product decision on `.skills/` format and trust model is already firm, swap A and C:

**Rationale:** Skills system is the more complex feature with more design unknowns. Landing it first means the task-run structure (C) can accommodate whatever `.skills/` layout decisions are made (e.g., if per-task-run `.skills/` is desired later, the layout is already designed). Risk: C is the foundation for output indexing (B1); delaying C delays B1's richer task-scoped search.

**Not recommended** unless the product decision on `.skills/` is urgent and C can follow within 1-2 weeks.

### Synthesis Option 3: Parallel A+C With Shared Path Authority Contract

Define `WorkspacePathLayout` additions for both `.skills/` and `tasks/` in a single contract-first phase, then implement A and C in parallel:

**Rationale:** No actual dependency between A and C — they touch different directory trees under the agent root. The shared dependency is `WorkspacePathLayout` and `WorkspaceDirectoryService`, which can be extended for both in a single PR. This minimizes branch divergence.

**Risk:** Parallel implementation means two features landing simultaneously, doubling the integration-test surface. But if both are small, surgical changes (as all reports suggest), this is manageable.

**Recommended only if** team capacity allows parallel work and integration testing.

## Trade-offs

### Trade-off 1: Converged Plan vs Independent Cluster Delivery

**Converged (Synthesis Option 1):** Clear dependency chain, minimal rework, each phase builds on stable foundation. Slower time-to-value for skills and observability.

**Independent delivery:** Faster time-to-value for whichever cluster ships first. Risk of rework if Cluster A lands before C and needs path layout adjustments when C lands later.

**Assessment:** Converged wins. The structural path changes in C are small enough that they shouldn't gate A for long. The rework risk of independent delivery outweighs the minor schedule gain.

### Trade-off 2: Spec-Format `.skills/` vs Flat `.md` (Conflict 2)

This is the critical pre-decision before any Cluster A work begins.

| Dimension | Spec-Format | Flat .md |
|-----------|------------|----------|
| Reuses existing `AgentSkillParser`/`AgentSkillCatalogService` | ✓ Full reuse | ✗ New parallel parser needed |
| Progressive disclosure (catalog→activate→resources) | ✓ Full lifecycle | ✗ Always-loaded, token-expensive |
| Compatible with agentskills.io ecosystem | ✓ Standard format | ✗ Incompatible |
| Supports `scripts/`, `references/`, `assets/` | ✓ Built-in | ✗ No resource directories |
| Simplicity | Moderate — directory-per-skill | High — one file per skill |
| User experience (adding a skill) | Create directory + SKILL.md | Create single .md file |
| Migration path to global `skills/` | Direct — same format | Requires reformatting |

**Recommendation:** Spec-format. The existing investment in the Agent Skills spec pipeline (parser, catalog, activation, metadata persistence, assignment, UI browser) is substantial. Throwing it away for workspace-local skills creates two parallel systems with different capabilities, different UIs, and different lifecycle behaviors. If the directive's "Skills are `.md` files" is a firm requirement, treat `.md` files as a simplified authoring format that gets up-converted to spec-format at scan time (a `.md` file in `.skills/` is treated as the body of a `SKILL.md` with filename-as-name and no frontmatter).

### Trade-off 3: `run_events` Table Now vs Later

R2 proposes a `run_events` table for structured telemetry. R1 and R3 don't address this level of detail.

**Now:** Rich observability from day one. Append-only table is cheap. Schema is additive — no migration risk.
**Later:** Start with computed metrics from existing timestamps (R2's own recommendation). Add `run_events` when concrete debugging needs justify it.

**Recommendation:** Later. Computed metrics from `work_assignments.started_at`/`completed_at` cover latency and success-rate use cases. The `run_events` table is well-designed but should be justified by a concrete observability gap, not pre-built.

### Trade-off 4: Reserved-Directory Registry Centralization

R3 proposes a `ReservedDirectory` enum. All reports touch on the concept. The trade-off is:

**Centralized enum:** Single source of truth. Easy to audit. Any new reserved directory requires an enum entry — forces deliberate consideration.
**Scattered conditionals (current):** `if (path.startsWith("outputs/"))` scattered across `WorkspaceExplorerService`. Hard to audit, easy to miss when adding new reserved dirs.

**Recommendation:** Centralized (R3's proposal). The current scattered approach already has `outputs/` checks in multiple places. Adding `.skills/` and `tasks/` without centralization would create a maintenance hazard.

## Open Questions

1. **Critical — `.skills/` format decision (from R1, not resolved by any report):** Does the product directive's "Skills are `.md` files" mean literal flat files, or is it a colloquial description of the agentskills.io spec format (`skill-slug/SKILL.md`)? This gates the entire Cluster A approach. None of the three reports can resolve this — it requires product input.

2. **Critical — User workspace task nesting (Conflict 1):** Do task runs ever execute against a non-agent `WorkspaceDescriptor`? Audit `OrchestrationRunnerService` and `WorkflowRunner` to determine. If yes, user workspaces need `tasks/` (R2's position). If no, task-run nesting is agent-only (R1/R3's position).

3. **Task name vs task ID as path segment (from R2, R3):** Task definitions can be renamed. Using `taskName` as the path segment means renames orphan directories. Using `taskId` means opaque directory names. R2 and R3 both raise this. R3 suggests `taskId` + a `task.json` metadata file. R2 doesn't take a position. Resolution needed before `WorkspacePathLayout.taskRunRoot()` is finalized.

4. **`.skills/` in chat workspaces (from R1, R3):** R1 and R3 both recommend no. R2 doesn't address. Is there any use case for conversation-scoped skills loaded from `workspace/chat/<id>/.skills/`? If not, `WorkspacePathLayout` should only provide `agentSkillsRoot()`, not a generic `skillsRoot(descriptor)`.

5. **Reserved directory collision (from R3):** If an agent already has a directory named `tasks` or `.skills` before these features land, what happens? R3 suggests "treat pre-existing directories as inert until the owning feature initializes them." Is this acceptable, or should the system detect and warn?

6. **Skill provenance in `RunOutputArtifact` (from R1):** Should artifact rows track which skill generated them? R2 doesn't address this. R1 raises it as an open question. Adding `skillName` to artifact records enables "what did skill X produce?" queries but adds schema.

7. **`WorkspaceDescriptor` sub-context (from R3):** Should `WorkspaceDescriptor` be extended to carry optional `taskName`/`taskRunId`? R3 recommends no — keep descriptor as identity-only. R1 and R2 don't directly address. This affects how callers pass context through the execution chain.

8. **B4 (validation) scope and timing (from R2):** R2 recommends Playwright fixtures covering Phases 1-3. Should B4 be a separate phase or continuous throughout? Recommendation: continuous — validate each phase before starting the next.

## Recommended Next Step

1. **Resolve the two critical open questions with product input:**
   - `.skills/` format: flat `.md` or spec-format (skill-slug/SKILL.md)?
   - User workspace task nesting: yes or no?

   These two decisions unlock the detailed design for both Cluster A and Cluster C.

2. **Create a unified `WorkspacePathLayout` extension proposal** that covers all structural names needed across all three clusters:
   - `TASKS` + `taskRunRoot()` / `taskRunOutputs()` (from all reports)
   - `SKILLS_DIR` + `agentSkillsRoot()` (from R3, assuming spec-format)
   - Reserved-directory registry (from R3, enriched with R2's access policies)
   
   This single contract artifact prevents divergent implementation.

3. **Audit execution paths for user-workspace task runs** (Conflict 1 resolution):
   - Grep for `workspaceType` usage in `OrchestrationRunnerService`, `WorkflowRunner`, `PlanService`
   - Determine if non-agent descriptors ever flow into task-run execution
   - Document the answer as a decision in `.internal-dev/specifications/decisions.md`

4. **Proceed with Phase 1 (Task-Run Structure)** using the converged plan (Synthesis Option 1). This phase is the most mechanical and least controversial — all three reports agree on the approach. Starting here defers the `.skills/` format decision slightly while making concrete progress.

5. **Defer Cluster B** until C's task-run structure stabilizes and A's discovery model is decided. B1 (output indexing) in particular benefits from the task-run hierarchy being in place before search is built.
