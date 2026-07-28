# Comparative Review — Workspace Sprint Brainstorm Reports

## Prompt

Review all four workspace sprint brainstorm reports. Compare useful ideas, identify conflicts and omissions, assess feasibility against the existing architecture, surface trade-offs the reports disagree on, and synthesize a unified recommended next step. Preserve the broad lens of product surface, architecture safety, Git reliability, and agent workflow integration without merely defending the first report's conclusions.

## Source

Four independently authored brainstorm reports from the workspace sprint:

| Report | Focus Lens | Recommendation |
|---|---|---|
| **Product Surface & Incremental Delivery Planner** | Information architecture, UI staging, SimplyPages components, visual language, sprint scoping | Option A: Convention-based mount. Sprint 1 = structured directories + tree sidebar + skills chips. Git deferred. |
| **Workspace Architecture Strategy** | Service boundaries, descriptor model, WorkspaceKind contract, project identity, git service design, prompt assembly | Option E: Hybrid — WorkspaceContext DB entity + structured agent workspaces + GitWorkspaceService. Five-phase plan. |
| **Git Safety & Reliability** | Confinement gate, shell execution risks, git hook vectors, failure recovery, observability, credential management | Option E: Hybrid with phased confinement escalation. Git safety wrappers first. Defer mount and merge agent. |
| **Agent Workflow Integration** | Ownership boundaries, capability limits, state transitions, assignment/skills/lifecycle alignment, decision compatibility | Do not implement yet. Produce signed plan resolving five architectural decision points first. |

All reports inspected the same authoritative sources: `.internal-dev/specifications/architecture.md`, `services.md`, `api.md`, `web.md`, `simplypages.md`, `decisions.md`, `deferred-features.md`, `service-graph.md`, knowledge files, and source code (`WorkspacePathLayout.java`, `WorkspaceDirectoryService.java`, `WorkspaceKind.java`, `WorkspaceExplorerFragments.java`, `OrchestrationController.java`, `SkillScopeDiscoveryService.java`).

## Findings

### Strong Alignments (All Reports Agree)

| Topic | Consensus |
|---|---|
| **Keep WorkspaceKind closed** | No report recommends adding `PROJECT` to the enum. All agree the `AGENT`/`CHAT`/`USER` closure must be preserved. This is the single strongest architectural constraint. |
| **No filesystem mounts** | All reports reject symlinks, bind mounts, and overlay filesystems. The mount must be a metadata/resolver concept, not a kernel-level filesystem operation. |
| **Structured agent workspace first** | All reports agree that adding reserved subdirectories (`skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/`) beneath the agent workspace root is the foundation sprint. |
| **Root immutability at service layer** | All reports recommend extending `WorkspaceExplorerService` and `AgentFileToolService` to reject writes targeting the workspace root (empty or `"."` paths), supplemented by AGENTS.md prompt guidance. |
| **Phased delivery** | All reports recommend incremental sprints starting with workspace structure, deferring git, PR agent, and system chat to follow-on phases. |
| **System chat as restricted agent profile** | All reports converge on modeling the system chat as a dedicated `AgentProfile` with operational tools only (no file mutation), rather than a new chat surface type. |
| **Skills integration via existing discovery** | All reports note that agent-workspace `.skills/` is already discoverable by `SkillScopeDiscoveryService` and that the UI gap is composition, not discovery. |
| **Project-as-metadata stays** | No report advocates restoring `DECISION-20260719-02`'s removed project filesystem roots. All work within the "project association is metadata only" baseline. |

### Key Conflicts and Tensions

#### Conflict 1: Git Implementation Path

| Report | Position |
|---|---|
| Product Surface | Use JGit library to avoid shell execution entirely. "JGit is the safer starting point." |
| Architecture Strategy | Wrap `shell_exec` with `GitWorkspaceService` for structured operations. "Dedicated service wraps shell_exec but provides structured operations." |
| Git Safety | Concerned about both paths. Shell execution is gated; JGit has its own risks. Recommends defining the service interface first, then deciding. Notes the chat-context vs task-context distinction: shell_exec is currently allowed in chat context. |
| Agent Workflow | Notes that process execution is fail-closed and any git approach must address this. Recommends recording a capability-gate decision. |

**Analysis:** This is the highest-stakes conflict because it determines whether the git phase is feasible at all under current architecture. The key overlooked fact (raised only by Git Safety) is that `shell_exec` is currently **allowed in chat context** — only task-run contexts are gated. This means a `GitWorkspaceService` wrapping `shell_exec` could work today for chat-initiated git operations, but would fail for task-execution-initiated git operations. A JGit approach would work in both contexts since it doesn't cross the process boundary. However, JGit introduces a new library dependency and cannot perfectly replicate all `git` CLI behaviors (especially merge, rebase, and conflict resolution).

**Resolution:** The reports collectively point toward a two-tier approach:
1. **Phase 1:** Define `GitOperationService` interface (no implementation) and add git safety wrappers to any future git execution path
2. **Phase 2a (chat context):** `GitWorkspaceService` wrapping `shell_exec` with argument validation and hook suppression
3. **Phase 2b (task context):** Gated behind process confinement — when `DECISION-20260720-04` is revisited, enable task-context git operations
4. **Alternative path:** If chat-only git is insufficient, evaluate JGit as a library-based alternative that doesn't require the confinement gate to be lifted

#### Conflict 2: Mount Mechanism Detail

| Report | Position |
|---|---|
| Product Surface | Metadata pointer + prompt injection. Mount is a `.workspace/mount.json` file or simple metadata. No virtual path resolution. Agent accesses project through canonical path. |
| Architecture Strategy | `WorkspaceContext` DB entity with `WorkspaceContextResolver` that computes effective working directory. Context-aware prompt assembly injects mount state. |
| Git Safety | Defer the mount question entirely. "The metadata-pointer and descriptor-chain approaches are architecturally compatible but need focused design." |
| Agent Workflow | Option D: `WorkspaceDescriptor` pair with virtual path resolution (`/current-workspace/` resolves against mounted descriptor). Notes this is cross-cutting. |

**Analysis:** This is a spectrum from minimal (Product Surface: just a file + prompt) to maximal (Agent Workflow Option D: full virtual path resolution). The Architecture Strategy's DB-backed `WorkspaceContext` is the middle ground — it's more robust than a filesystem file but doesn't require every filesystem component to gain mount-awareness. All reports agree the mount should not be a symlink or bind mount. The unresolved question is whether `/current-workspace/` is a real path agents can `ls` and `cd` into, or purely a prompt concept.

**Resolution:** Start with Architecture Strategy's `WorkspaceContext` DB record (single active row per agent). The initial mount is prompt-level only — the agent is told "your current project workspace is at `/projects/my-project/`." The virtual `/current-workspace/` path is a deferred enhancement. This gives observability (queryable state), atomic updates, and avoids the cross-cutting virtual-path change.

#### Conflict 3: Sprint 1 Scope

| Report | Position |
|---|---|
| Product Surface | Sprint 1: structured directories + directory-tree sidebar + skills chips + `.workspace/` metadata + empty states + system prompt assembly. Git deferred entirely. |
| Architecture Strategy | Phase 1 (Foundation): reserved subdirectory constants + root-write prohibition + AGENTS.md updates. Phase 2 (WorkspaceContext) is separate. |
| Git Safety | Start with layout constants, root immutability enforcement, git service interface spec (no implementation). |
| Agent Workflow | **Do not implement anything yet.** Produce signed plan resolving five architectural decisions first. |

**Analysis:** The Agent Workflow report's caution is well-founded — the other three reports propose implementation details that depend on unresolved architectural questions. However, the "do nothing until signed plan" position is overly conservative for the workspace structure work, which all reports agree has zero architectural risk (no new `WorkspaceKind`, no schema changes, no API restructuring). The layout constants, root-write prohibition, and AGENTS.md updates are purely additive and safe to implement while the higher-stakes decisions (mount model, git approach) are being resolved.

**Resolution:** Split into two tracks:
- **Track A (implement now):** `WorkspacePathLayout` reserved child constants, root-write prohibition in `AgentFileToolService`, basic AGENTS.md workspace structure guidance. These are safe, additive, and unblocked by any architectural decision.
- **Track B (decide first):** `WorkspaceContext` design, mount semantics, git service approach, PR agent lifecycle, system chat scoping. These require signed decisions before implementation.

#### Conflict 4: PR Auto-Merge Agent Trigger

| Report | Position |
|---|---|
| Product Surface | "PR agent is triggered by a schedule/reaction or explicit workflow." Suggests both event-driven and scheduled are viable. |
| Architecture Strategy | Event-driven: `GitWorkspaceService.createPR()` publishes `PullRequestCreated` event → `EventReactionService` dispatches to PR agent. |
| Git Safety | Defer entirely. "Design the merge agent's API contract but do not implement until git clone/branch/commit/push flows are validated." |
| Agent Workflow | Recommends scheduled polling as pragmatic start, then event-driven when webhook support matures. Notes this is a "new class of system agent." |

**Analysis:** The Architecture Strategy's event-driven model is architecturally cleanest (integrates with existing `EventReactionService`) but assumes git operations are already working — you can't have a `PullRequestCreated` event without `createPR()`. The Agent Workflow report correctly identifies that PR events are external (from GitHub, not Magenta) and thus the trigger mechanism must either poll or receive webhooks. The existing `EventReactionService` works with internal events; external webhook integration is a new surface.

**Resolution:** Defer PR agent design until the git baseline (clone, branch, commit, push) is functional. At that point, the simplest working trigger is `ScheduleService` polling. The event-driven model is the target state.

### Critical Omissions

#### Omission 1: Chat-Context vs Task-Context Shell Execution

Only the Git Safety report observes that `shell_exec` is currently **allowed in chat context** but **disabled in task-run contexts**. This distinction has major implications for git integration:

- If agents do git operations during chat sessions (interactive workspace management), shell_exec works today
- If agents do git operations during task execution (automated PR workflow), shell_exec fails
- A JGit-based service would work in both contexts

None of the other reports discuss this context distinction, which changes the calculus for git implementation feasibility.

#### Omission 2: Git Hook Risk Amplification

The Git Safety report identifies that `git clone` can execute arbitrary hooks from the cloned repository (`.git/hooks/post-checkout`, etc.). Even in chat context, cloning an untrusted repo is a code execution vector. The mitigation (`--config core.hooksPath=/dev/null`, `GIT_CONFIG_NOSYSTEM=1`) is low-effort but not discussed in the other reports. This is a concrete safety requirement that must be in any git implementation spec.

#### Omission 3: What Happens to Existing Tabs?

None of the reports address the fate of the current 13 agent console tabs during the workspace sprint. The "exec" tab (shell command form), "outputs" tab (artifact listing), and "history" tab (assignment history) all occupy tab real estate. Adding new tabs (Git, System Chat, Workspace) without consolidating existing ones will produce a cluttered navigation bar. The Product Surface report notes the Files tab evolution but doesn't propose a tab reorganization.

#### Omission 4: User Workspace as Project Carrier

The Agent Workflow report's Option C (model project workspaces as USER workspaces with naming convention) is a pragmatic path that none of the other reports explore. If `workspace/user/project-my-project/` is a USER workspace by identity but a project workspace by convention, the existing API routes, execution selection, and skills discovery all work unchanged. The "project-ness" is metadata — a DB column on the project record pointing to the USER workspace key. This avoids the PROJECT kind question entirely.

#### Omission 5: Cross-Workspace Browsing

The user wants to see "each agent's clone under the workspace" — implying visibility into other agents' workspaces. The current Files tab resolves exactly one `WorkspaceDescriptor` for the current agent. Cross-agent browsing would require either a new API surface or a dashboard-level composition that queries multiple workspaces. None of the reports fully address this, though the Architecture Strategy's `RunOperationsFragments` composition pattern (stateless renderer over authorized values) could be adapted.

#### Omission 6: The `/home` and `/apps` Void

The user specifies `/home` (agent's personal files) and `/apps` (personal applications) as structured workspace directories alongside `/skills`, `/scripts`, and `/repos`. No report defines what concrete behavior uses these directories. Without a use case, adding them is speculative directory creation. The Product Surface report acknowledges them ("for general files" / "personal applications") but doesn't wire them to any tool or UI behavior. The Architecture Strategy lumps them into the reserved-name list without defining their role.

#### Omission 7: Workspace Initialization Policy

When does the agent workspace get its structured subdirectories? Options: on agent creation, on first access, or never (agent creates them). The current architecture avoids starter-file generation. The Product Surface and Architecture Strategy reports both lean toward lazy creation, but neither specifies the trigger: does the UI create them? Does `WorkspaceDirectoryService.provision()` scaffold them? Does the agent create them through tools?

### Feasibility Assessment Against Architecture

| Feature | Feasibility | Blockers |
|---|---|---|
| Reserved subdirectory constants in `WorkspacePathLayout` | **High** — additive, no breaking changes | None |
| Root-write prohibition in file tools | **High** — extends existing rejection pattern | None |
| Directory-tree sidebar in Files tab | **High** — SimplyPages composition of existing explorer + new nav component | None |
| Skills chip indicators in file list | **High** — extend `WorkspaceExplorerFragments` row rendering | None |
| `WorkspaceContext` DB entity + service | **Medium** — new domain object, isolated and small | Needs decision on mount semantics |
| Workspace-context-aware prompt assembly | **Medium** — extends existing `AgentsMdResolver` | Depends on WorkspaceContext design |
| Git operations via shell_exec (chat context) | **Medium** — technically feasible but needs safety wrappers | Git hook risk, argument validation, credential management |
| Git operations via JGit library | **Medium** — new dependency, limited merge/rebase support | Library evaluation, credential management |
| Git tab in agent console | **Medium** — new SimplyPages fragments, depends on GitService | Depends on git service being functional |
| PR auto-merge agent | **Low** — depends on git operations + event/reaction wiring + merge conflict resolution | Multiple unresolved dependencies |
| System chat agent | **Medium** — mostly configuration (restricted agent profile + tools) | Needs tool authorization scoping for work queue management |
| Virtual `/current-workspace/` path resolution | **Low** — cross-cutting change to every filesystem component | Requires mount model decision + extensive component updates |
| Cross-workspace browsing | **Low** — new API surface, authorization model | No existing pattern for multi-descriptor views |

### Tab Consolidation Opportunity

The current agent console has 13 tabs: Dashboard, Profile, Queue, Inbox, Jobs, Schedules, Reactions, Files, Outputs, Exec, History, Submit. The workspace sprint could consolidate these into fewer, richer tabs:

| Current Tabs | Proposed Consolidation | Rationale |
|---|---|---|
| Files + Outputs + Exec | **Workspace** (three-column: tree nav, file browser, run groups) | Files already has run groups; outputs are just a filtered artifact view; exec is a tool that belongs within the workspace surface |
| Queue + History | **Assignments** (active queue + history with filter toggle) | These are temporal views of the same assignment data |
| Schedules + Reactions | **Automation** (unified schedule/reaction management) | Both are automation primitives; the existing fragments already share patterns |
| Dashboard | **Overview** (health, counters, current assignment, workspace status) | Existing dashboard content |
| Profile + Submit | **Configuration** (profile editing + submission forms) | Both are agent configuration |
| Inbox + Jobs | Keep as-is for now | These have distinct enough semantics to remain separate |
| NONE (new) | **Git** (new tab, deferred) | Net-new surface |
| NONE (new) | **System Chat** (could be an accordion on Overview like existing chat accordion, or a new tab) | Depends on UX decision — chat accordion vs dedicated tab |

This consolidation reduces 13 tabs to ~7-8, leaving room for Git and System Chat without tab bar overflow. The Product Surface report's directory-tree sidebar approach already moves in this direction by making Files the rich workspace surface.

## Options

### Option X: Converged Implementation Path (Synthesized Recommendation)

A unified plan that resolves the conflicts identified above, incorporating the strongest ideas from each report while respecting the caution of the Agent Workflow report.

**Pre-Implementation (Now):**
1. Record the five architectural decision points in a decision proposal (as Agent Workflow recommends)
2. Add `WorkspacePathLayout` reserved child constants and root-write prohibition (safe, additive, unblocked)
3. Update agent `AGENTS.md` guidance to reference the structured workspace directories
4. Define `GitOperationService` interface as a spec-only artifact (as Git Safety recommends)

**Sprint 1 — Workspace Browser (Track A, unblocked):**
1. Structured agent workspace directory constants in `WorkspacePathLayout` (`SKILLS_DIR`, `SCRIPTS`, `APPS`, `HOME`, `REPOS`, `PROJECTS`)
2. Root-write prohibition in `AgentFileToolService` (reject empty or `"."` target paths)
3. Directory-tree sidebar in the Files tab (SimplyPages component, HTMX navigation)
4. Skills chip indicators in file list rows
5. Empty states per directory
6. Consolidate Files + Outputs + Exec into a single "Workspace" tab
7. Playwright validation of the new Files tab layout

**Sprint 2 — Workspace Context (Track B, after decisions):**
1. `WorkspaceContext` DB entity, repository, service, resolver
2. Context-aware prompt assembly (extend `AgentsMdResolver`)
3. Dashboard context indicator (current project/git context display)
4. Simple context switching via dashboard controls

**Sprint 3 — Git Visibility (after git service interface approved):**
1. `GitWorkspaceService` wrapping `shell_exec` (chat context only) with safety wrappers
2. Git tab: clone form, branch list, status display
3. Git operation action logging (`WorkspaceGitActionLogRepository`)
4. WORKSPACE.md resolution for git repos

**Sprint 4 — System Chat & Queue (after system-chat agent profile decision):**
1. System chat agent profile with operational tools only
2. Work queue enhancements (workspace context display, priority ordering)
3. System chat embedded in agent console (accordion or tab)

**Sprint 5 — PR Automation (deferred, after git baseline stable):**
1. PR auto-merge agent profile
2. Scheduled PR detection (polling via `ScheduleService`)
3. Merge attempt + conflict detection + notification
4. Post-merge re-sync workflow

### Option Y: Minimum Viable Surface (Aggressive Trimming)

If the goal is the smallest complete thing, strip the sprint to:

1. `WorkspacePathLayout` reserved directory constants
2. Root-write prohibition
3. Directory-tree sidebar in Files tab
4. AGENTS.md guidance for workspace structure
5. **Nothing else** — no `WorkspaceContext`, no git, no system chat, no PR agent

This delivers the per-agent structured workspace browser in a single sprint. Everything else (mounts, git, context-aware prompts, system chat) is deferred to explicit follow-on sprints approved by the user. This aligns most closely with the Product Surface report's Sprint 1 scope but is even more aggressively trimmed.

### Option Z: Decision-First Staging (Agent Workflow Report's Core Insight)

Produce only decision artifacts in this phase:

1. Decision proposal: workspace mount model
2. Decision proposal: project workspace identity (USER namespace convention vs. future PROJECT kind)
3. Decision proposal: git integration approach (shell_exec chat-only vs. JGit library)
4. Decision proposal: PR auto-merge agent lifecycle
5. Decision proposal: system chat agent model

Then write a phased implementation plan from the approved decisions. Zero implementation until decisions are signed. This is the most architecturally rigorous approach but doesn't deliver any user-visible value in the current sprint.

## Trade-offs

### Fast Delivery vs. Architectural Rigor

| | Converged Path (Option X) | Minimum Viable (Option Y) | Decision-First (Option Z) |
|---|---|---|---|
| User-visible value in sprint 1 | Yes (Track A) | Yes (full Files tab) | No |
| Architectural risk | Medium (Track B depends on decisions) | Low (zero architecture change) | None (only decisions) |
| Decision reversibility | High (additive changes) | High (additive changes) | N/A |
| Git feasibility clarity | Partial (interface spec only) | None (deferred) | Full (decision signed) |
| Sprint count to full vision | 5 sprints | Unbounded (each feature is a separate sprint) | 5 sprints after decisions |
| Recommendation | **Best balance** | Safest, slowest to full vision | Most architecturally sound, slowest to value |

### Git: JGit vs shell_exec vs Deferred

| | JGit | shell_exec (chat-only) | Defer Entirely |
|---|---|---|---|
| Works today | Yes (library dependency) | Yes (chat context, needs safety wrappers) | N/A |
| Task context | Works (no process) | Blocked by confinement gate | N/A |
| Merge/rebase support | Limited | Full (git binary) | N/A |
| Git hook risk | None (library, no hooks) | Present (needs mitigation) | N/A |
| Implementation effort | Medium (new dependency, API surface) | Low (wrap existing tool) | None |
| Recommendation | Safer long-term path | Pragmatic short-term path | Defers value |

The Git Safety report's insight about chat-context availability changes the trade-off: shell_exec wrapping is viable for interactive git operations today. JGit can be evaluated as a task-context alternative when that need arises.

### Mount: Prompt-Only vs Virtual Path

| | Prompt-Only | Virtual Path Resolution |
|---|---|---|
| Agent UX | Agent accesses project through canonical path | Agent sees `/current-workspace/` as a real directory |
| Implementation | Prompt assembly + dashboard display | Every filesystem component needs mount-awareness |
| Risk | Low — no path resolution changes | High — cross-cutting, new failure modes |
| Deferred upgrade path | Virtual path can be added later | N/A (started at high complexity) |
| Recommendation | Start here | Target state, not starting point |

## Open Questions

1. **Tab consolidation scope:** Should the sprint plan include tab consolidation (Files + Outputs + Exec → Workspace tab, Queue + History → Assignments tab), or should tab reorganization be a separate UX cleanup sprint? The workspace sprint already changes the Files tab significantly; consolidating tabs at the same time could either simplify or complicate the change.

2. **`/home` and `/apps` purpose:** What concrete agent behavior uses these directories? If the answer is "aspirational, no immediate use case," they should be omitted from sprint 1 and added when needed. Creating empty directories with no tooling or prompt guidance attached is speculative.

3. **Workspace initialization trigger:** Should the structured directories be visible in the Files tab sidebar even when they don't exist yet on disk (as "create on click" targets), or should they only appear when the agent has created them? The former is a better UX (agent always knows where to go) but the latter is lazier and avoids unnecessary filesystem operations.

4. **Chat-context git operations:** Is it acceptable for git operations to work only in chat context (interactive agent sessions) and not in task-execution context (automated workflows)? If PR auto-merge must happen during task execution, chat-only git is insufficient. But for the initial git visibility sprint (clone, status, branch display), chat context is sufficient.

5. **User workspace as project carrier viability:** Would using `workspace/user/project-<name>/` as the project workspace root (Option C from Agent Workflow report) satisfy the user's project workspace vision without requiring any new `WorkspaceKind`? This is the simplest path but may not match the user's mental model of a distinct "project workspace."

6. **Cross-workspace browsing requirement:** The user says agents should see "each agent's clone under the workspace." Is this a sprint 1 requirement or a deferred feature? If sprint 1, it requires multi-descriptor API design that none of the reports have fully specified.

7. **Git credential storage:** Where do git credentials live — per-agent configuration, a system-level secrets store, environment variables? The current architecture has no secrets management layer. This is a blocking dependency for any git push/PR operation.

8. **Merge agent conflict resolution capability:** The user wants the PR agent to "merge and fix conflicts." Is automatic conflict resolution (AI-driven code editing of merge conflicts) in scope, or should it be detection + notification + manual resolution? The Git Safety report recommends detection-only initially; automatic resolution is a separate capability with its own safety concerns.

## Recommended Next Step

**Adopt a two-track approach that delivers unblocked value immediately while resolving architectural decisions for the higher-risk features.**

### Track A — Implement Now (zero architectural risk)

1. **`WorkspacePathLayout` reserved child constants:** Add `SKILLS_DIR` (`skills`), `SCRIPTS` (`scripts`), `APPS` (`apps`), `HOME` (`home`), `REPOS` (`repos`), `PROJECTS` (`projects`) as recognized agent-workspace children. These are convention constants, not enforcement — they define the names the system recognizes but don't require the directories to exist.

2. **Root-write prohibition:** Extend `AgentFileToolService` and `WorkspaceExplorerService` to reject file-write operations targeting the workspace root (`""` or `"."` paths). Add AGENTS.md guidance directing agents to work within named subdirectories.

3. **Directory-tree sidebar:** Extend `WorkspaceExplorerFragments` with a SimplyPages directory-tree component that shows the recognized workspace structure as navigation targets. Directories that exist on disk show as navigable; directories that don't exist show as "create" targets. This evolves the Files tab from two-column to three-column: tree nav | file list + inspector | run groups.

4. **Skills chip indicators:** In the file list, entries under `skills/` that are `.md` files show a "Skill" chip with name and description from YAML frontmatter (parsed at listing time, not skill-discovered).

5. **AGENTS.md workspace structure guidance:** Inject a system prompt segment describing the agent's workspace structure and directing the agent to use the appropriate subdirectories.

6. **Playwright validation:** Screenshots and visual critique of the new three-column Files tab layout on desktop and mobile, directory-tree navigation, skill chip rendering, root-write rejection, empty states.

**This track delivers Sprint 1 from the Product Surface report with no dependency on unresolved architectural decisions. Migration risk is zero — all changes are additive to existing services and fragments.**

### Track B — Decide First (requires signed decisions before implementation)

1. **Draft a decision proposal covering the five architectural questions** identified by the Agent Workflow report:
   - Mount model: prompt-only vs. virtual path resolution (recommend prompt-only for sprint 2, virtual path deferred)
   - Project workspace identity: USER namespace convention vs. future PROJECT kind (recommend USER namespace + DB metadata)
   - Git approach: shell_exec chat-only with safety wrappers vs. JGit library (recommend shell_exec chat-only for sprint 3, evaluate JGit for task context)
   - PR agent lifecycle: scheduled polling vs. event-driven (recommend scheduled polling for sprint 5)
   - System chat model: restricted agent profile (recommend)

2. **Define `GitOperationService` interface as a spec-only artifact** (as Git Safety recommends). Include operation signatures, result types, safety wrapper requirements, and the chat-context limitation.

3. **Resolve the chat-context vs task-context `shell_exec` distinction** with the user: is git-in-chat-only sufficient for the initial git visibility sprint?

4. **Create a `git-safety-and-reliability.md` knowledge file** capturing the hook-risk vector, URL validation pattern, argument sanitization requirements, and credential management considerations.

5. **Socialize the tab consolidation proposal** — reducing 13 tabs to ~7-8 richer tabs — as a parallel UX decision that affects the sprint's scope.

**After Track B decisions are signed, proceed to the Architecture Strategy report's Phase 2-5 plan (WorkspaceContext → Git Visibility → System Chat → PR Automation).**

### Immediate Artifact

Only one artifact should be created now: this comparative review. The decision proposal (Track B, item 1) is the next artifact to produce, followed by the sprint 1 implementation plan for Track A if the user approves the unblocked work.
