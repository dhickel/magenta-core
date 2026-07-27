## Prompt

Synthesis review of three brainstorm reports across the Skills System + Observability &amp; Quality + Task Output Structure sprint scope. Compare `workspace-skill-architecture/findings.md` (architecture-and-security-lens on Clusters A/A∩C), `runtime-output-observability/findings.md` (operational-lifecycle lens on B/C), and the original `Cross-Cluster Product Integration and Delivery` report. Identify useful ideas, conflicts, omissions, feasibility assessments, and trade-offs; preserve a broad integrative lens without merely defending the first report.

## Source

- **Report 1:** `Cross-Cluster Product Integration and Delivery` (original synthesis, authored in this session)
- **Report 2:** `workspace-skill-architecture/findings.md` — deep architecture + security lens on Cluster A and its intersections with C
- **Report 3:** `runtime-output-observability/findings.md` — operational lifecycle lens on Clusters B and C as one continuous pipeline

All three reports draw from the same authoritative specifications, knowledge files, and source-code anchors documented in each. No new project files were inspected for this synthesis.

## Findings

### Consensus Across All Three Reports

**Phase ordering.** All three independently arrive at **C → A → B** as the phase sequence that minimizes rework. The reasoning converges: task-run directory structure (C) is the foundational filesystem change; skills (A) can then place `.skills/` directories in the correct locations; observability (B) indexes and enriches the final output shape. No report argues for a different order.

**WorkspacePathLayout authority.** All three agree that new structural constants (`TASKS`, task-run factories) must live in `WorkspacePathLayout` — the "sole structural path authority" contract from `DECISION-20260719-02` is non-negotiable. Hand-concatenation of path segments outside this class is uniformly rejected.

**OutputArtifactService caller-side change.** All three confirm that `OutputArtifactService.materialize()` already accepts a generic `Path outputDir`, so the Cluster C change is purely in the callers (`WorkflowRunner`, `OrchestrationRunnerService`, `PlanService`) — no service signature change required. Report 3 adds the useful suggestion of a convenience overload `materializeToTaskRun()` that takes descriptor + taskName + taskRunId internally.

**Closed WorkspaceKind.** All three confirm that `WorkspaceKind` stays closed to `AGENT`, `CHAT`, `USER`. Task runs are children of workspaces, not peers. Report 3 explicitly calls out `TASK_RUN` as a rejected extension.

**Legacy flat outputs remain inert.** No migration of existing `workspace/agent/<id>/outputs/` files. The split between flat-legacy and nested-new is acknowledged as a temporary inconsistency that doesn't justify migration risk.

**Files tab reserved-directory semantics.** All three agree that `tasks/`, `.skills/`, and `outputs/` need reserved-directory policy — `WorkspaceExplorerService` should reject mutations on these subtrees. Reports 1 and 3 both suggest a centralized reserved-directory registry.

**.skills/ should NOT appear in chat workspaces.** Chat workspace `.skills/` is redundant with the existing `activate_skill` tool and creates confusing scope overlap. Reports 2 explicitly recommends against it.

### Divergences and Tensions

#### Divergence 1: .skills/ flat .md vs spec-format directories

Report 1 was ambiguous about whether `.skills/` contains flat `.md` files or spec-format subdirectories. Report 2 explicitly frames this as the **critical architectural fork**: flat `.md` files bypass the entire progressive-disclosure lifecycle (catalog → activate → resources), meaning workspace-local skills would always consume context tokens without activation gating. The spec-format approach reuses the existing parser, catalog, activation, and deduplication infrastructure.

**Assessment:** Report 2's framing is correct and more architecturally rigorous. Flat `.md` files would create a parallel skill-loading path that doesn't benefit from the investment in progressive disclosure. The directive says "Skills are `.md` files within these directories" — this needs product clarification. If flat `.md` is required, the minimum viable approach is to up-convert at scan time: treat each `.md` file as a degenerate skill directory where the filename is the skill name and the file body is the full instruction. This preserves the existing activation pipeline while supporting the flat format. If spec-format is acceptable, the existing `AgentSkillParser` and `AgentSkillCatalogService` work unchanged with additional scan roots.

#### Divergence 2: User workspace task-run nesting

Report 1 said task-run nesting is "agent-only." Report 3 explicitly questions this and recommends user workspaces also get `tasks/<taskName>/<taskRunId>/outputs/` because user workspaces can be selected as execution targets via the `workspaceType`/`workspaceKey` override.

**Assessment:** Report 3 is correct. The execution workspace selection contract (`SVC-20260719-05`) allows user workspace override. If an agent executes a task run with `workspaceType=user, workspaceKey=alice`, the outputs should nest under `workspace/user/alice/tasks/<taskName>/<taskRunId>/outputs/`. Denying task-run nesting for user workspaces would force flat output publication when executing against a user workspace — an unnecessary inconsistency. The constraint should be: task-run nesting applies to **any workspace that hosts task execution**, not just agent workspaces. Chat workspaces are the only exception because they don't host task runs (chats use conversation-scoped output, not task-run-scoped).

#### Divergence 3: .skills/ Files tab visibility

Report 1 suggested `.skills/` be visible but read-only in the Files tab. Report 2 recommends **hidden by default** with a "show hidden files" toggle, citing dotfile conventions.

**Assessment:** Report 2's hidden-by-default approach is better. `.skills/` is a dot-prefixed configuration directory, not user-managed content. Making it visible adds clutter to the Files tab. The dedicated Skills management surface (`/skills`) is the correct place for skill browsing and editing. A "show hidden" toggle in the Files tab is a nice-to-have deferred enhancement — the explorer already has filtering capability.

#### Divergence 4: Runtime observability mechanism

Report 1 didn't deeply explore B2 mechanics. Report 3 provides a detailed analysis with two options: (a) an append-only `run_events` table with JSON payloads, and (b) computing metrics from existing timestamps. Report 3 recommends starting with computed metrics from existing `started_at`/`completed_at` columns — latency percentiles, success/failure rates, and exit codes are computable without a new table.

**Assessment:** Report 3's pragmatism is well-founded. The existing `work_assignments` and run tables already carry timestamps and status transitions. Latency = `completed_at − started_at`. Success rate = `count(status=COMPLETED) / count(status in {COMPLETED, FAILED, ERROR})`. Adding a `run_events` table should be gated on a concrete need for intra-run granularity (tool-call latencies, step-by-step timelines) that the status transitions don't capture.

#### Divergence 5: Artifact lineage schema

Report 1 mentioned lineage briefly. Report 3 proposes a concrete approach: `parent_artifact_ids text` (JSON array of IDs) on `RunOutputArtifact`, plus a `GET /api/outputs/{id}/lineage` endpoint for DAG traversal.

**Assessment:** Report 3's approach is concrete and implementable. However, lineage at the artifact level (rather than the run level) may be over-engineered for a first pass. Runs already carry parentage through workflow DAG edges and job dependency chains. Artifact-level lineage is valuable for multi-output workflows but comes with a significant integration burden: every output producer must declare its input artifact IDs. **Recommendation:** start with run-level lineage (already present in orchestration tables) and defer artifact-level lineage to a follow-up sprint.

#### Divergence 6: Phase granularity

Report 1 proposed three coarse phases (C → A → B). Report 3 proposes six finer-grained phases (C → Files tab → B1 indexing → B2 observability → B3 scheduling → B1 extension lineage/retention). Report 2 focuses deeply on A.

**Assessment:** Report 3's finer granularity is more actionable. The six phases create a natural delivery cadence where each phase delivers user-visible value. The Files tab enhancement (Phase 2 in Report 3's scheme) is particularly smart — it gives immediate user-facing payoff from the mechanical Cluster C change before moving on to observability work.

### Omissions and Complementary Insights

#### Report 2 adds what Report 1 omitted:

1. **Skill discovery pipeline changes in detail.** Report 2 maps out exactly how `AgentSkillRuntimeCatalogService.catalogForConversation()` would merge from multiple roots, how `AgentSkillAgentContextResolver` would need to resolve workspace descriptor + task run ID + trust posture, and how `PromptContextAssembler.mergeModePrompt()` already has a layered append pattern that naturally accommodates workspace-skill injection.

2. **Task-run `.skills/` as an A∩C intersection.** Report 2 raises the possibility of `<task-run>/.skills/` directories for per-run skill injection. Report 1 mentioned this only as an open question. Report 2 gives it more architectural weight by connecting it to the trust model (scoped to run duration).

3. **Script trust (A4) and package provenance (A5) touchpoints.** Report 2 identifies concrete service boundaries: `SkillScriptExecutionPolicy` for gating shell_exec within skill script directories, `SkillPackageImportService` for registry ingestion with provenance tracking. These are deferred but the architectural hooks are worth documenting now.

4. **`SkillResolutionContext` record proposal.** Report 2 suggests a richer resolution result than just `Optional<String> agentId` — a record carrying workspace descriptor, task run ID, and ordered discovery roots. This is a clean API evolution from the current `AgentSkillAgentContextResolver`.

#### Report 3 adds what Report 1 omitted:

1. **User workspace task-run nesting.** This is a correction to Report 1's agent-only constraint.

2. **Task-run ID as directory name.** Report 3 explicitly recommends using the same `taskRunId` (UUID from `TaskRun.id`) as the directory name for 1:1 traceability. Report 1 left this as an open question.

3. **Files tab run-aware grouping.** Report 3 gives a concrete UX vision: when browsing `tasks/`, the Files tab should show grouped entries with run status badges, artifact counts, and timestamps — not just a raw directory tree. This is a higher-value endpoint than Report 1's simpler navigation approach.

4. **Run_events table pragmatism.** Report 3 correctly identifies that a new telemetry table may be premature and that computed metrics from existing timestamps cover the most valuable observability cases.

5. **Artifact content_text extraction strategy.** Report 3 specifies file-type-specific extraction rules (strip Markdown, concatenate JSON keys, etc.) rather than a generic "add full-text search" — necessary implementation detail.

6. **Convenience overload suggestion.** `materializeToTaskRun(descriptor, taskName, taskRunId, ...)` to reduce caller boilerplate — a small API improvement that Report 1 didn't consider.

### Cross-Report Validations

**All three reports independently validate these architectural invariants:**
- `WorkspaceDescriptor` is not extended or subclassed for task runs
- `WorkspaceKind` remains closed
- `WorkspacePathLayout` is the single structural authority
- `OutputArtifactRepository` independently owns artifact persistence
- HTMX-first UI policy applies to all new surfaces

**Risk: specification-drift on user workspace task-run nesting.** Report 3's correction that user workspaces should get task-run nesting needs to be reflected in the Cluster C specification. If the spec is written as "agent-only," the implementation will create an inconsistency when user workspace execution is selected. This should be caught in specification review before implementation begins.

## Options

### Option A: Resolve the flat .md vs spec-format question before any Cluster A work

Both Report 1 and Report 2 identify this as the critical fork. The options:

- **Flat .md with up-conversion:** Accept flat `.md` files as the storage format but up-convert each file to an internal `AgentSkillParseResult` at scan time (filename → skill name, file body → body, no frontmatter). This preserves the activation pipeline (catalog → activate → content) without requiring spec-format directories. Trade-off: no `scripts/`, `references/`, or `assets/` support for workspace-local skills.

- **Spec-format only:** Require `.skills/` directories to follow the Agent Skills spec (`skill-slug/SKILL.md` + optional resources). Full reuse of existing parser/catalog. Trade-off: more complex directory structure for users to create manually.

- **Hybrid:** Accept both. Scan for `skill-slug/SKILL.md` subdirectories first (spec-format), then for bare `*.md` files (flat). Up-convert flat files. Catalog marks provenance format.

**Recommendation:** Flat `.md` with up-conversion. It satisfies the directive's "Skills are `.md` files" requirement while preserving the progressive-disclosure architecture investment. Script/reference/resource support for workspace-local skills can be deferred.

### Option B: Adopt Report 3's six-phase plan with one adjustment

Report 3's phases: C → Files Tab → B1 indexing → B2 observability → B3 scheduling → Lineage/Retention.

**Adjustment:** Insert Cluster A (agent-personal `.skills/`) between Files Tab and B1 indexing. This gives:

1. **C: Task-run directory identity** — `WorkspacePathLayout` extensions, caller updates, `RunOutputArtifact` column additions
2. **C UX: Files tab task-run navigation** — run-aware grouping, status badges, artifact counts
3. **A: Agent-personal `.skills/`** — `WorkspacePathLayout.SKILLS_DIR`, multi-root catalog merge, trust model, Files tab hidden-by-default policy
4. **B1: Artifact indexing and preview** — full-text extraction, search API, preview rendering
5. **B2: Runtime observability** — computed metrics from timestamps, agent history tab replacement
6. **B3: Scheduling/reaction UI** — SimplyPages management surfaces
7. **B1 ext: Lineage and retention** — deferred until volume warrants it

This places Cluster A after the task-run UX work delivers immediate value but before observability work that benefits from skill-usage metrics. The skills system is self-contained enough to land independently.

### Option C: Unified reserved-directory registry as a prerequisite

Before any cluster touches the Files tab, extract the existing ad-hoc `outputs/` rejection in `WorkspaceExplorerService` into a formal `ReservedDirectoryPolicy`:

```java
public enum ReservedDirectory {
    OUTPUTS("outputs", MutationPolicy.REJECTED, Visibility.VISIBLE),
    DOT_SKILLS(".skills", MutationPolicy.REJECTED, Visibility.HIDDEN_DEFAULT),
    TASKS("tasks", MutationPolicy.REJECT_CHILDREN, Visibility.VISIBLE);
}
```

This prevents scattered conditionals and gives each cluster a clear policy hook. Report 1 proposed this; Reports 2 and 3 both reference the pattern without naming it explicitly. Making it a Phase 0 prerequisite reduces merge conflicts between the clusters.

## Trade-offs

### Trade-off: User workspace task-run nesting adds implementation surface vs. fixing an inconsistency

**Pro nesting:** If user workspaces can host execution, they should also host task-run directories. The inconsistency of flat outputs in one case and nested in another is confusing for agents and breaks the "outputs are scoped to their run" invariant.

**Con nesting:** More `WorkspacePathLayout` factories to add, more test cases, more `WorkspaceDirectoryService` validation paths. User workspaces are rarely used as execution targets today; this might be solving for a future use case.

**Resolution:** Add the nesting. The additional factories are mechanical (two more `taskRunRoot()` overloads accepting `WorkspaceKind.USER`). The invariant "every task run has a directory" is simpler than "task runs have directories only in agent workspaces."

### Trade-off: Hidden vs visible .skills/ in Files tab

**Hidden (Report 2 recommendation):** Clean interface, follows dotfile conventions, no accidental edits. Skills management stays in the Skills browser. Risk: users don't discover the feature.

**Visible read-only (Report 1 suggestion):** Discoverable, users can see what skills their agent has from the Files tab. Risk: clutter, confusion between Files tab and Skills browser.

**Resolution:** Hidden by default, with a "show hidden" toggle deferred as a horizon enhancement. The Skills browser is the canonical skills surface. The Files tab is for workspace content, not agent configuration. If discovery is a concern, the agent dashboard can show a "Skills loaded from workspace: 3" summary card that links to the Skills browser.

### Trade-off: Artifact-level lineage (Report 3) vs run-level lineage (Report 1)

**Artifact-level:** Enables queries like "what inputs produced this specific output file?" — powerful for debugging multi-step workflows where runs produce many artifacts. Requires every output producer to declare `parentArtifactIds`.

**Run-level:** Already present in orchestration tables (workflow DAG edges, job dependency chains). Covers the common case of "run B consumed run A's outputs" without per-artifact granularity.

**Resolution:** Start with run-level lineage (already implemented). Add artifact-level lineage columns (`parent_artifact_ids`) as a schema placeholder in Phase 1 but defer population to the B1 extension phase. This avoids blocking on the integration work while leaving the schema ready.

### Trade-off: Run events table now vs later

**Now (Report 3, Option 2):** Schema is ready, execution paths emit events from day one, historical data accumulates. But adds a table that may not be needed if computed metrics from timestamps suffice.

**Later (Report 3 recommendation):** Proves the need with computed metrics first. When a concrete question can't be answered from timestamps (e.g., "what was the p50 tool-call latency across all runs last week?"), then add `run_events`.

**Resolution:** Later. The existing timestamps already capture the high-value observability signals: run duration, success/failure, agent identity, workspace identity. Compute metrics from these first. Add `run_events` only when intra-run granularity is needed.

## Open Questions

1. **Flat `.md` vs spec-format `.skills/`** remains the single highest-impact unresolved question. It determines whether Cluster A extends the existing parser pipeline or creates a parallel path. Product decision needed before Cluster A specification begins.

2. **Task name validation rules.** Report 2 raises: should task names in `tasks/<taskName>/` have the same `PlainPathSegmentValidator` constraints as agent IDs, or more permissive rules? Recommendation: same strict constraints for consistency. Task names that don't pass validation get a sanitized slug for the directory name, with the display name stored in a `task.json` metadata file.

3. **Task-run directory retention.** Report 3 asks about cleanup. If every run creates a directory, agent workspaces grow unboundedly. Recommendation: add a `retention_policy` field to task definitions with a workspace-level default. A `@Scheduled` method prunes task-run directories older than the TTL. Default: 90 days, configurable per task. This is a B1 extension feature — not blocking for C.

4. **`SkillResolutionContext` adoption timing.** Report 2 proposes a richer resolution record. Should this land in the Cluster A phase or be deferred? Recommendation: land in Cluster A. The `AgentSkillAgentContextResolver` is already the resolution boundary; evolving its return type from `Optional<String>` to a context record is a contained change that improves the entire downstream pipeline.

5. **Should the Files tab explorer gain artifact-awareness or stay filesystem-only?** Report 3 envisions artifact metadata cards alongside file listings when browsing inside task-run `outputs/`. This means the explorer would need an `OutputArtifactRepository` dependency to enrich directory listings. Trade-off: tighter coupling vs richer UX. Recommendation: keep the explorer filesystem-only; add a separate `OutputBrowserFragments` HTMX surface that renders artifact metadata. The Files tab can embed artifact views via HTMX includes — no coupling in the explorer service layer.

6. **Does `.skills/` need to be mutable through the Files tab?** Report 2 says no (Skills browser only). If the directive intends users to manage skills through the Files tab as normal files, this conflicts with the reserved-directory policy. Recommendation: `.skills/` is read-only in the Files tab and hidden by default. Skill creation/editing goes through the Skills browser. If flat `.md` is chosen, the Skills browser can provide a simple text-editor surface for workspace-local `.md` skill files.

## Recommended Next Step

1. **Product decision on flat `.md` vs spec-format** — escalate to user for clarification before any Cluster A specification work begins. Frame the trade-off: flat `.md` is simpler for users but bypasses progressive disclosure; spec-format reuses existing infrastructure but requires directory-per-skill structure.

2. **Adopt the unified phase plan** with the adjustment to insert Cluster A after Files Tab UX:

   ```
   Phase 0: ReservedDirectoryPolicy extraction (refactor, no feature)
   Phase 1: Task-run directory identity (C)
   Phase 2: Files tab task-run navigation (C UX)
   Phase 3: Agent-personal .skills/ (A)
   Phase 4: Artifact indexing + preview (B1)
   Phase 5: Runtime observability (B2)
   Phase 6: Scheduling/reaction UI (B3)
   Phase 7: Lineage + retention (B1 extension)
   Phase 8: Validation coverage + UX review (B4 + B5)
   ```

3. **Correct the user workspace task-run nesting assumption** in the Cluster C specification. Task-run directories apply to any workspace kind that hosts execution (AGENT + USER), not agent-only.

4. **Draft a `WorkspacePathLayout` extension proposal** that includes all structural constants needed by all three clusters: `TASKS`, `SKILLS_DIR`, task-run factories for both agent and user workspaces, and `.skills/` factory. This prevents each cluster from independently extending the layout class and creating merge conflicts.

5. **Create a specification artifact** for the `ReservedDirectoryPolicy` as a Phase 0 prerequisite — this is the one change that all three clusters depend on and benefits from being done first with zero user-visible impact.
