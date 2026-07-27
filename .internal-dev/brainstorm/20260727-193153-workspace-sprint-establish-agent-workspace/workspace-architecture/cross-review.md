# Cross-Report Synthesis — Workspace Architecture Strategy Review

## Prompt

Compare and synthesize four independent brainstorming reports produced by the Workspace Architecture Strategist worker: (1) the original strategist findings recommending Option E (Hybrid: Structured Agent Workspaces + Context Pointer + Git Service), (2) the git-safety-reliability analysis, (3) the agent-workflow-integration analysis, and (4) the product-surface-delivery analysis. Identify agreements, conflicts, omissions, feasibility concerns, and produce a unified set of trade-offs and a recommended next step that draws from all four perspectives without merely defending the first answer.

## Source

All four reports were grounded in the same authoritative sources:
- `.internal-dev/specifications/architecture.md` — closed `WorkspaceKind` (AGENT/CHAT/USER), `ARCH-20260719-01`, `ARCH-20260720-01`, `ARCH-20260720-03`, `ARCH-20260526-02`
- `.internal-dev/specifications/services.md` — `WorkspaceDirectoryService`, `WorkspaceExplorerService`, `SkillScopeDiscoveryService`, `TaskRunLifecycleService`
- `.internal-dev/specifications/service-graph.md` — descriptor-only identity flow, project-as-metadata, operational composition rules
- `.internal-dev/specifications/decisions.md` — `DECISION-20260719-02` (closed kinds, no project roots), `DECISION-20260720-01` (canonical task runs), `DECISION-20260720-03` (skills never auto-trusted), `DECISION-20260720-04` (process confinement fail-closed)
- `.internal-dev/specifications/api.md` — typed workspace routes, execution workspace selection, absent route families
- `.internal-dev/specifications/web.md` — agent Files tab two-column layout, operational fragment composition
- `.internal-dev/specifications/simplypages.md` — HTMX-first policy, independent stable hosts, stateless renderers
- `.internal-dev/specifications/deferred-features.md` — `DEFERRED-20260525-11` (dashboard-aware system chat, deferred), `DEFERRED-20260526-05` (project-local skill scope, partially-implemented/unavailable without trusted authority), `DEFERRED-20260525-14` (project git workspace ops, superseded)
- `.internal-dev/knowledge/` — root-relative-workspace-storage, shell-tool-confinement-pattern, file-tool-workspace-scope-pattern, agents-md-specification-reference, scoped-skill-trust-snapshots
- Source code: `WorkspacePathLayout.java`, `WorkspaceKind.java`, `WorkspaceDirectoryService.java`, `WorkspaceDescriptor.java`, `SkillScopeDiscoveryService.java`, `AgentFileToolService.java`, `AgentShellToolService.java`

## Findings

### Strong Consensus Across All Four Reports

Nine architectural positions achieved unanimous or near-unanimous agreement:

**1. Keep `WorkspaceKind` closed (AGENT/CHAT/USER).** No report recommends adding a `PROJECT` kind. The agent-workflow report calls the closed model a "hard architectural contract." The git-safety report notes that adding a kind "would require a formal decision reversal." The product-surface report calls this "Option A — minimum risk, fastest delivery." My original report's Option E also preserves the closed kind. *Verdict: Settled.*

**2. Convention-based structured directories within agent workspaces.** All reports agree on using reserved subdirectory names (`skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/`) within the existing `workspace/agent/<agentId>/` root. The product-surface report provides the most concrete UI proposal: a directory-tree sidebar in the Files tab. The agent-workflow report notes the `/skills` mapping to existing `.skills/` discovery. *Verdict: Settled.*

**3. Metadata-pointer mount semantics (no symlinks, no filesystem artifacts).** All four reports reject symlinks due to the existing confinement model (`WorkspaceExplorerService` explicitly rejects symlink components). All agree on a metadata pointer approach. However, the reports differ on the pointer mechanism:
- My original report: DB-backed `WorkspaceContext` table
- Agent-workflow report: `workspace_mounts` table or metadata-only pointer
- Product-surface report: `.workspace/mount.json` file
- Git-safety report: Defers mount question entirely

*Verdict: Approach agreed, mechanism disputed.* This is the most important unresolved design choice.

**4. Dedicated `GitService` rather than raw `shell_exec`.** The git-safety report is most emphatic: "process confinement is proven unavailable." The agent-workflow report recommends JGit to "avoid shell process execution entirely." The product-surface report concurs on JGit over process-backed git. My original report recommended a `GitWorkspaceService` wrapping `shell_exec` — this is the one area where my original recommendation conflicts with the other three reports, which uniformly favor JGit to avoid the confinement gate entirely. *Verdict: JGit over shell_exec. My original recommendation should yield to the consensus on this point.*

**5. Root-immutability enforcement at the service layer.** All reports agree: `WorkspaceExplorerService` already rejects root mutation. Extend this to file tools with an explicit root-path rejection (path resolving to `""` or `"."`). The git-safety report adds: complement with `AGENTS.md` language. The product-surface report adds: guard create/delete/rename at root, only allowing operations inside named subdirectories. *Verdict: Settled.*

**6. Phased/incremental delivery.** All reports recommend phased implementation. The product-surface report provides the most detailed sprint plan (5 sprints). The git-safety report recommends phases aligned with the existing architecture roadmap. My original report had 5 phases. *Verdict: Settled on approach, specific phasing needs reconciliation.*

**7. Defer the PR auto-approval/merge agent.** All reports agree this is the most complex component and should come after foundational git operations are working. The git-safety report: "Defer the merge agent. The PR/merge agent lifecycle depends on having git operations working first." The agent-workflow report recommends scheduled polling via `ScheduleService` when the time comes. *Verdict: Settled.*

**8. Skills explorer integration through composition, not reimplementation.** The product-surface report explicitly states: "compose existing `SkillFragments` as independent stable hosts rather than reimplementing skill management" — following `DECISION-20260720-02`. My original report and the agent-workflow report agree. *Verdict: Settled.*

**9. System chat agent as a restricted agent profile with limited tools.** The agent-workflow report recommends a "restricted agent profile" approach. The product-surface report agrees: "a profile with file-read tools but no file-write tools." My original report recommended a dedicated `system-agent` profile. *Verdict: Settled.*

### Critical Conflicts Requiring Resolution

**Conflict 1: Mount State Storage — DB Table vs. Filesystem File**

| Aspect | DB Table (Strategist, Agent-Workflow) | `.workspace/mount.json` (Product-Surface) |
|--------|---------------------------------------|-------------------------------------------|
| Atomic updates | Yes — transactional | No — file race conditions |
| Queryability | Yes — SQL joins with assignments | No — filesystem grep only |
| Consistency with existing patterns | Matches `workspace_contexts`, assignments, task runs | Novel — no existing JSON config files in workspace trees |
| Agent visibility | Hidden from agent (DB is opaque) | Agent can read `.workspace/mount.json` — potential for confusion or tampering |
| Simplicity | Requires new repository + migration | No schema change needed |
| Resilience | Survives workspace deletion; DB is source of truth | Co-located with workspace — deleted if workspace is deleted |

*Analysis:* The DB approach is architecturally consistent with how all other Magenta state is managed (assignments, task runs, skill snapshots, artifacts, schedules, reactions). The filesystem approach is appealingly simple but creates a split-brain risk — what happens when the file says one thing and the actual workspace state says another? The DB approach also enables the critical integration point identified by the agent-workflow report: "assignment-triggered mounts" — when an assignment is created with a project workspace reference, the mount record can be created atomically within the assignment transaction. A filesystem file cannot participate in that transaction.

*Recommendation:* DB-backed `workspace_mounts` (or `workspace_contexts`) table. The migration is trivial (one new table). The `.workspace/` directory can still exist for agent-visible metadata (like agent-owned config files), but mount state belongs in the database.

**Conflict 2: Git Implementation — Shell Exec vs. JGit**

My original report recommended a `GitWorkspaceService` wrapping `shell_exec`. The git-safety report's most important finding is that `DECISION-20260720-04` makes `shell_exec` fail-closed in task-run contexts. JGit bypasses this gate entirely by operating in-process as a Java library.

The git-safety report also identifies a risk I missed: **git hooks**. Any `git clone` of an untrusted repository can execute arbitrary code through `.git/hooks/` scripts (post-checkout, pre-commit, post-merge, etc.). This applies even in chat-context shell execution. The safety wrappers proposed (`--config core.hooksPath=/dev/null`, `GIT_CONFIG_NOSYSTEM=1`) mitigate this but only if consistently applied — and they only work with the git CLI, not JGit (which has its own hook model but is disabled by default).

*Recommendation:* JGit for structured operations (clone, branch, status, diff, log, add, commit). The git CLI with safety wrappers for complex operations that JGit handles poorly (merge with conflicts, rebase). The `GitService` interface abstracts the backend; the initial implementation uses JGit exclusively. CLI fallback is a deferred enhancement gated on either process confinement being proven or an explicit architectural exception for git operations.

**Conflict 3: Phasing Order — Git First vs. Dashboard First**

| Source | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 |
|--------|---------|---------|---------|---------|---------|
| Strategist | Reserved dirs + root guard | WorkspaceContext DB | Git Integration | Dashboard Panels | PR Auto-Approval |
| Product-Surface | Directory tree + skills inline | Git tab | Queue enhancements | System chat | Mount + prompt |
| Git-Safety | Structural contract + root guard | Git service spec | Git safety wrappers | (defers mount) | (defers merge agent) |
| Agent-Workflow | (Recommends plan artifact first) | (Defers implementation) | — | — | — |

The product-surface report places Git integration (Sprint 2) *before* workspace mount and prompt integration (Sprint 5). My original report places Git (Phase 3) *after* WorkspaceContext (Phase 2). The git-safety report wants the git service *designed* before implementation but doesn't specify where in the dashboard evolution it lands.

*Analysis:* The product-surface report's ordering is driven by user-facing value — a Git tab with clone/status/branch is more immediately useful than a metadata mount system. My ordering was driven by dependency analysis — mount context must exist before git operations can be scoped correctly. Both perspectives are valid. The resolution depends on whether the initial git operations are mount-aware or simply operate on `repos/` as a flat directory.

*Recommendation:* Git tab can be an independent sprint that treats `repos/` as a flat directory of clones. Mount-awareness is layered on after the mount model is designed. This lets Git and Mount work proceed in parallel rather than sequentially. The product-surface ordering is correct for user-facing delivery: directory tree (Sprint 1) → Git tab (Sprint 2) → Queue (Sprint 3) → System chat (Sprint 4) → Mount + prompts (Sprint 5). The mount model design should happen in parallel with Sprint 1-2, not delay them.

### Important Omissions in My Original Report

The git-safety and agent-workflow reports surfaced several concerns absent from or underweighted in my original analysis:

**1. Git hook risk vector (critical omission).** The git-safety report identifies that `git clone` of an untrusted repository executes `.git/hooks/` scripts. This is not theoretical — it's the default git behavior. Even with JGit (which disables hooks by default), this risk needs explicit acknowledgment in the design. Mitigation: `--config core.hooksPath=/dev/null` + `GIT_CONFIG_NOSYSTEM=1` for CLI operations; JGit's default hook-disabled behavior for in-process operations.

**2. Git credential management (unaddressed).** None of my options address where git credentials live. The git-safety report asks: "Where do git credentials live? In agent config? In a secrets store? In the environment?" The agent-workflow report identifies this as "a security boundary that doesn't exist in the current architecture." This is not just a deployment detail — it's a design decision that affects whether git operations are per-agent (individual SSH keys) or system-wide (a shared token), with different trust implications for each.

**3. Cross-agent repo visibility (underweighted).** The agent-workflow report asks: "The user wants 'each agent's clone under the workspace' to be explorable. Currently, the Files tab shows the agent's own workspace. Showing other agents' clones requires cross-workspace browsing, which the closed descriptor model doesn't support." My original report assumed agent-local clones without addressing the cross-agent visibility requirement.

**4. `.workspace/` metadata directory (absent from my report).** The product-surface report introduces an application-owned `.workspace/` directory for metadata files, filtered from the file list. This is a useful pattern that provides a filesystem home for agent-visible-but-not-agent-editable configuration without polluting the file browser. My original report only considered DB for state; the `.workspace/` concept adds a useful hybrid.

**5. `DEFERRED-20260525-11` (dashboard-aware system chat) relevance.** The agent-workflow report identifies that system chat is already a deferred feature in the project's deferred-features register. This means the user's request aligns with an already-accepted future capability — it's not a new product direction. My original report didn't reference this register entry, which would have strengthened the recommendation.

**6. Concurrent git safety model (absent).** The git-safety report asks: "How many agents will realistically operate on the same repo simultaneously?" The answer determines whether clone-level isolation suffices (one agent at a time per repo) or whether a coordination mechanism (merge queue, rebase-before-push policy) is needed. My original report assumed single-agent-per-repo without addressing concurrency.

**7. `DEFERRED-20260526-05` (project-local skill scope) status.** The agent-workflow report correctly notes that project-local skill discovery is partially implemented but currently unavailable because "Magenta has no project filesystem-root authority." This is directly relevant to the user's request for project workspace skills. My original report mentioned `SkillScopeDiscoveryService`'s project discovery gate but didn't connect it to this specific deferred feature entry.

### Areas Where My Original Analysis Holds Up Better

**1. WorkspaceContext as a first-class concept.** My Option E's `WorkspaceContext` (with type, target, git URL, timestamps) is more structured than the `.workspace/mount.json` file proposed by the product-surface report. The agent-workflow report independently arrived at a similar concept: a `workspace_mounts` table with `(agent_workspace_kind, agent_workspace_key, mounted_workspace_kind, mounted_workspace_key, mount_path)`. The convergence on a typed, DB-backed context record from two independent analyses strengthens the case for this approach.

**2. PromptContextAssembler extension for context-aware prompts.** My report's recommendation to extend `PromptContextAssembler` to accept `WorkspaceContext` and inject context-specific segments is echoed by both the agent-workflow report ("extend the existing layered resolution to include workspace-type-aware segment injection") and the product-surface report ("mount-aware prompt assembly: when a mount is active, the prompt assembler includes the mounted project's AGENTS.md layers"). This three-way convergence validates the approach.

**3. Agent-local project content (no shared project roots).** My trade-off analysis favored agent-local over shared roots. The agent-workflow report independently reaches the same conclusion: "Start with metadata pointer (Option A)." The product-surface report's Option A is the same pattern. The git-safety report doesn't advocate for shared roots. Three-out-of-four agreement on this point.

**4. Phased approach with explicit architectural decision recording.** My report recommended "Before any implementation begins: Socialize Option E with the user... Write the plan as a formal `.internal-dev/plans/` artifact... Update `DECISION-20260719-02` if any aspect requires revision." The agent-workflow report is even stronger: "Do not implement. Do not edit. Produce a signed plan artifact first." This agreement on process discipline is important — the workspace sprint is architecturally significant enough to warrant formal decision documentation before code changes.

### Feasibility Assessment

**High confidence (implementable in current architecture):**
- Reserved subdirectory constants in `WorkspacePathLayout` — purely additive, zero risk
- Root-immutability guard in `AgentFileToolService` — ~20 lines of validation
- Directory-tree sidebar in Files tab — new SimplyPages component, additive
- Skills chip indicators in file list — reuses existing `SkillScopeDiscoveryService`
- `.workspace/` metadata directory (hidden from explorer) — filesystem + filter
- System chat agent profile — new agent profile with restricted tool set

**Medium confidence (implementable with new services, no architectural changes):**
- `WorkspaceContext` DB table and resolver — new migration (010), new service
- `GitService` with JGit — new dependency, new service, credential management design needed
- Mount-aware prompt assembly — extends `PromptContextAssembler`, no architectural change
- Git tab with clone/status/branch — new HTMX fragments, new REST endpoints
- Queue enhancements (workspace context display) — extends existing `OrchestrationController`

**Low confidence (requires architectural decisions or capability gates):**
- Git operations in task-run context — blocked by `DECISION-20260720-04` (process confinement unavailable)
- PR auto-approval/merge agent — requires git operations, event/reaction integration, new privilege model
- Cross-agent workspace browsing — conflicts with closed descriptor model
- Project-local skill discovery in workspace context — gated by trusted project-root authority (`DEFERRED-20260526-05`)

**Blocked (cannot proceed without explicit architectural exception or capability proof):**
- `shell_exec`-based git operations in any execution context — the shell tool returns `confinementUnavailable()` for task runs
- Symlink-based mounts — rejected by `WorkspaceExplorerService` confinement
- Process-backed arbitrary git commands — same gate as shell_exec

## Options

### Unified Option: Converged Hybrid Model

Drawing from all four reports, the converged approach has these characteristics:

**Foundation (from all reports):**
- Closed `WorkspaceKind` preserved (AGENT/CHAT/USER)
- Reserved agent workspace subdirectories: `skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/`
- Root-immutability enforcement at service layer (file tools reject root-targeting paths)
- `.workspace/` metadata directory for application-owned config, filtered from explorer

**Mount Model (synthesized from strategist + agent-workflow):**
- DB-backed `workspace_mounts` table: `(agent_id, mounted_workspace_kind, mounted_workspace_key, mount_path, context_type, git_url, mounted_at)`
- `WorkspaceContextResolver` service consulted by tools, prompts, and explorers
- Mount path is always `current-workspace` within agent root
- Mount-aware path resolution at the `WorkspaceDirectoryService` boundary (not symlinks)
- Reconciliation: `.workspace/mount.json` is a *read-only cache* derived from DB, not the source of truth. The DB is authoritative.

**Git Integration (synthesized from git-safety + product-surface):**
- `GitService` interface with JGit as primary backend
- Operations: clone, fetch, branch, status, diff, log, add, commit, push, PR create
- Safety: JGit hooks disabled by default; CLI fallback (deferred) uses `--config core.hooksPath=/dev/null` + `GIT_CONFIG_NOSYSTEM=1`
- Remote URL validation against configured allowlist
- Credential management: per-agent SSH key path stored in agent profile; `GitService` reads from agent config, never exposed to model
- `git_repo_states` table: `(agent_id, repo_url, clone_path, current_branch, last_fetch, last_push, dirty_flag)`
- Git operations gated to chat context until process confinement is proven; task-run git operations require the gate to be lifted

**System Chat (from agent-workflow + product-surface):**
- Dedicated `system-agent` agent profile
- Tools: `file_read` (workspace-scoped), `list_assignments`, `get_assignment`, `cancel_assignment`, `workspace_status`
- No: `file_write`, `file_append`, `file_replace`, `file_delete`, `shell_exec`, `git_*`
- Chat surface: new tab "System Chat" in agent console, or embedded panel in workspace dashboard
- Maps to deferred feature `DEFERRED-20260525-11` (dashboard-aware system chat)

**PR Auto-Approval (deferred, design only):**
- PR creation by `GitService` publishes `PullRequestCreated` event
- `ScheduleService` polling checks for open PRs (until webhook support)
- Designated `pr-reviewer` agent profile with elevated privileges
- Merge attempt + conflict detection + notification; automatic conflict resolution deferred

**Skills Explorer (from product-surface + agent-workflow):**
- Inline skill-chip indicators in the file list for `.md` files under `skills/`
- Inspector panel shows skill metadata (name, description from frontmatter)
- Trust/activate actions delegate to existing `SkillController` and `SkillFragments` as independent stable hosts
- Link to full `/skills` control plane for advanced management
- Project-local skills: reference `DEFERRED-20260526-05`; requires trusted project-root authority before project `.skills/` can be discovered

### Delivery Phasing (Unified)

| Phase | Name | Contents | Depends On | Confidence |
|-------|------|----------|------------|------------|
| 1 | Foundation | Reserved subdirectory constants, root-immutability guard, `.workspace/` directory, directory-tree sidebar in Files tab, skills chip indicators | None | High |
| 2 | Workspace Context | `workspace_mounts` DB table + migration, `WorkspaceContextService`, `WorkspaceContextResolver`, context-aware prompt assembly, mount selector in dashboard | Phase 1 | Medium |
| 3 | Git Service | `GitService` (JGit), `git_repo_states` table, Git tab in agent console (clone, status, branch), credential management in agent profile | Phase 1 (does not require Phase 2) | Medium |
| 4 | System Chat | `system-agent` profile, System Chat tab, restricted tool authorization | Phase 2 (needs mount context) | High |
| 5 | Queue + Git Integration | Queue enhancements (workspace context display), git operations scoped to current mount, WORKSPACE.md resolution, mount-triggered git clone guidance | Phase 2 + 3 | Medium |
| 6 | PR Automation | `PullRequestCreated` event, `pr-reviewer` agent, merge lifecycle, conflict detection + notification | Phase 3 + 5 | Low |

**Key insight from cross-report analysis:** Phases 1 and 3 can proceed in parallel (directory tree and git tab are independent). Phase 2 can be designed in parallel with Phase 1 implementation. This parallelism was not identified in any single report — it emerges from comparing the dependency graphs.

## Trade-offs

### DB vs. Filesystem for Mount State (Unresolved)

The product-surface report's `.workspace/mount.json` approach has one genuine advantage over the DB approach: zero migration. In a project that has explicitly removed tables (`workspaces`, `workspace_links`, `workspace_leases`), adding a new one requires a 010 migration, migration runner updates, and repository tests. A filesystem file avoids all that.

However, the DB approach's advantages (transactional consistency, queryability, alignment with existing patterns) outweigh the migration cost. The migration is a single `CREATE TABLE` — the simplest possible schema change — and it follows the project's established pattern (ordered `NNN-kebab.sql` resources with SHA-256 checksums under `SchemaMigrationRunner`).

*Recommendation:* DB-backed with a 010 migration. The migration overhead is minimal and the architectural consistency is worth it.

### Git: JGit vs. CLI (Resolved by Consensus)

JGit is the consensus choice from the git-safety, agent-workflow, and product-surface reports. My original report's shell_exec recommendation was made before fully internalizing the implications of `DECISION-20260720-04`. The confinement gate is not a policy preference — it's a hard architectural constraint that returns `confinementUnavailable()` at runtime.

*Caveat:* JGit has known limitations with complex merge conflict scenarios and some git operations (shallow clones, partial clones, `git worktree`). If these become blocking, the CLI fallback path needs to be designed now: (a) CLI only for specific operations, (b) always with `--config core.hooksPath=/dev/null`, (c) always with validated arguments, (d) only in chat context unless the confinement gate is lifted. This should be documented in the `GitService` interface contract.

### Mount Resolution: Virtual Path vs. Metadata-Only (Leaning Toward Virtual Path)

The agent-workflow report is cautious: "Start with metadata pointer. If the lack of a real mount path causes confusion, add virtual-path resolution later." The product-surface report's directory-tree sidebar approach effectively gives a visual mount without filesystem semantics. The agent sees `current-workspace → project:my-project` in the tree but clicks through to the canonical project path.

*Recommendation:* Metadata-only for Phase 1-2. Add virtual-path resolution (`/current-workspace/` → mounted root) in Phase 5 when mount-aware prompt assembly is mature enough to benefit from it. This avoids the cross-cutting change to every filesystem component until the mount model is validated.

### System Chat: Profile vs. Surface (Resolved — Both)

The agent-workflow report notes this could be "a new chat surface type or simply a restricted agent profile." The product-surface report leans toward "a profile" for initial delivery and "a dedicated tab" later.

*Recommendation:* Both. The `system-agent` is a dedicated agent profile (restricted tools, specialized system prompt). It is exposed through two surfaces: (a) a "System Chat" tab in the agent console for workspace-scoped management conversations, (b) the existing chat interface for direct interaction. The profile is the architectural primitive; the surface is a UI composition choice. This aligns with `DEFERRED-20260525-11`.

### PR Auto-Approval: Event vs. Schedule (Schedule for Start)

The agent-workflow report recommends scheduled polling via `ScheduleService`. The git-safety report notes that "event-driven integrates with the existing `EventReactionService` infrastructure." My original report recommended event-driven.

*Recommendation:* Scheduled polling for Phase 6 initial delivery. It's simpler, uses existing `ScheduleService`, and doesn't require webhook infrastructure. Event-driven reactions can replace polling when webhook support matures — the `GitService.createPR()` already returns enough information to construct a reaction trigger. The `ScheduleService` approach also provides a natural polling interval that can be tuned (check every 5 minutes vs. immediate webhook).

## Open Questions

1. **Where do git credentials live?** This is the most important question none of the reports fully answers. Options: per-agent SSH key paths in `AgentProfile`, a system-level token in `AiConfig`, or a dedicated secrets store. The answer determines whether git operations are agent-scoped (individual identities on remotes) or system-scoped (one identity for all agents). Per-agent SSH keys are the most aligned with the "each agent clones and works on their own branch" model but require key generation and management UX.

2. **Does the mount model require `WorkspaceDirectoryService` changes?** If mounts are DB-backed pointers that tools and prompts resolve, does `WorkspaceDirectoryService` need to know about them? Or is mount resolution a separate concern layered on top? Keeping mount resolution outside `WorkspaceDirectoryService` preserves the descriptor contract but means every filesystem component needs mount-awareness. Adding it to `WorkspaceDirectoryService` (as an optional `mountedDescriptor()` on `WorkspaceDescriptor`) simplifies consumers but expands the descriptor contract.

3. **Can `workspace_mounts` re-use the existing `workspaceType`/`workspaceKey` pairing from execution selection?** The execution selection model (paired type/key, bound-agent default, user override, chat rejection) is battle-tested. The mount model could use the same pairing: `(agent_id, mounted_workspace_type, mounted_workspace_key)`. This would reduce conceptual surface area — the mount is just a persistent version of the execution selection pair.

4. **Git repo naming conflicts.** Two agents clone `https://github.com/org/repo.git` — both get `repos/repo/`. If Agent A is working on `feature-x` and Agent B on `feature-y`, they have independent clones at the same path. Is that correct? The clones are on different branches, so spatial isolation is maintained, but the agent workspace `/repos/` listing would show one entry for two agents' work. This is self-consistent — each agent sees only their own workspace — but the cross-agent visibility requirement (agent-workflow report finding) means an admin view would need to disambiguate.

5. **Does JGit support all required operations without a native git installation?** JGit can clone, fetch, branch, commit, push, diff, and log without native git. It struggles with: (a) merge conflict resolution (the `MergeCommand` has known edge cases), (b) rebase (limited support), (c) shallow clones (not supported), (d) `git worktree` (not supported). If any of these become requirements, a native git fallback path must be available.

6. **How does the system chat agent's "cannot edit files" restriction interact with the mount model?** If the agent's mount points to a project workspace, can the system chat agent read files in the mounted workspace? The user says "cannot edit files" — reading should be allowed. But read access to a mounted project workspace is read access to a potentially different workspace root. Does the system chat agent use its own `WorkspaceDescriptor` (agent workspace) or the mounted one? This is a scope question that maps to the existing tool authorization model but needs explicit design.

7. **What happens to existing agent workspaces after the structured directory layout is introduced?** Current agents have flat `workspace/agent/<id>/` directories (possibly with `tasks/`, `outputs/`, `.skills/`). The lazy-creation approach (`WorkspaceStructureService` ensures directories exist on demand) means existing agents get the structure as they use it. No migration needed. But should the Files tab show an empty state for agents with no structured directories yet?

8. **Is project workspace creation a dashboard operation, an API operation, or both?** The agent-workflow report asks: "Who creates project workspaces — users through the dashboard, agents through tools, or administrators through configuration?" The answer affects API surface design and authorization model. Likely answer: dashboard for manual creation, assignment-triggered for automated creation (when an assignment targets a project that has no workspace yet).

9. **The `DEFERRED-20260526-05` gate: can we supply a trusted project-root authority?** Project-local skill discovery is implemented but gated on a trusted project-root authority. If the mount model provides that authority (the mount record is the "trusted authority" that says "this project workspace exists and is valid"), then `DEFERRED-20260526-05` can move from partially-implemented to implemented. This is a significant unlock — the mount model doesn't just enable workspace browsing, it enables project-scoped skills.

10. **How should `DEFERRED-20260525-11` (dashboard-aware system chat) be updated after this sprint?** If the system chat agent is implemented as a restricted agent profile, the deferred feature should be marked as implemented or partially-implemented with a note about scope. Conversely, if workspace mount and project context are deferred past the current sprint, `DEFERRED-20260525-11` should remain deferred but gain a more specific implementation note.

## Recommended Next Step

**Produce a single signed plan artifact (`workspace-sprint-phase-01`) that reconciles all four reports, resolves the identified conflicts, and defines the exact scope of the first implementation phase.**

The plan artifact should:

1. **Record the architectural decisions:**
   - `DECISION-WS-01`: `WorkspaceKind` remains closed (AGENT/CHAT/USER). Project workspaces are modeled as mount contexts referencing existing workspace kinds.
   - `DECISION-WS-02`: Mount state is DB-backed (`workspace_mounts` table) with a `.workspace/mount.json` read-only cache.
   - `DECISION-WS-03`: `GitService` uses JGit as primary backend with CLI fallback gated on process confinement proof.
   - `DECISION-WS-04`: Agent workspace structured directories are lazy-created by `WorkspaceStructureService`, not scaffolded on agent creation.
   - `DECISION-WS-05`: Root-immutability is enforced at the `AgentFileToolService` layer (reject root-targeting paths).
   - `DECISION-WS-06`: System chat agent is a restricted agent profile (`system-agent`) with operational-tool-only authorization.
   - `DECISION-WS-07`: PR auto-approval agent is deferred to a follow-on phase; initial git integration is clone/branch/status/push only.

2. **Define Phase 1 scope as the union of:**
   - Product-Surface Sprint 1: directory-tree sidebar, skills chip indicators, `.workspace/` directory, empty states
   - Git-Safety Phase 1: `WorkspacePathLayout` reserved constants, root-immutability guard, `AGENTS.md` root-fragment injection
   - Strategist Phase 1: Reserved subdirectory constants, root-write prohibition
   - Agent-Workflow: Formal decision recording, no implementation until plan is signed

3. **Defer to Phase 2 (workspace context) and Phase 3 (git service):**
   - `workspace_mounts` table + migration (010)
   - `WorkspaceContextService` / `WorkspaceContextResolver`
   - `GitService` (JGit) + `git_repo_states` table
   - Git tab in agent console
   - Mount-aware prompt assembly

4. **Update specifications:**
   - `architecture.md`: Add structured agent workspace entry, mount model entry, git integration entry
   - `services.md`: Add `WorkspaceStructureService`, `WorkspaceContextService`, `WorkspaceContextResolver`, `GitService` entries
   - `api.md`: Add workspace context routes, git operation routes
   - `web.md`: Update Files tab composition for directory-tree sidebar, skills indicators
   - `simplypages.md`: Add directory-tree component entry
   - `decisions.md`: Add `DECISION-WS-01` through `DECISION-WS-07`
   - `deferred-features.md`: Update `DEFERRED-20260525-11` status, note `DEFERRED-20260526-05` potential unlock

5. **Proceed with Phase 1 implementation only after:**
   - Plan artifact is reviewed and signed
   - Decisions are recorded in `decisions.md`
   - A dedicated git branch is created for the workspace sprint
   - Migration risk is confirmed near-zero (no schema change in Phase 1)

**The most important meta-finding from this cross-report review:** All four analyses independently converged on the same core architectural stance (closed `WorkspaceKind`, convention-based directories, metadata-pointer mounts, dedicated git service, phased delivery). The disagreements are about implementation mechanism (DB vs. file for mount state, JGit vs. shell for git, virtual path vs. metadata-only for mount resolution), not architectural direction. This level of independent convergence on a project with governed architecture (explicit decisions, living specs, capability gates) is a strong signal that the approach is sound.
