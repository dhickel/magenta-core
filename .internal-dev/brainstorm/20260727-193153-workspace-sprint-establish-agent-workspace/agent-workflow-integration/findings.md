## Prompt

Brainstorm how project context, task queues, assignments, execution lifecycle, scoped skills, dynamic system prompts, and a restricted system-chat agent can compose into understandable user and agent workflows. Focus on ownership boundaries, capability limits, state transitions, and alignment with existing `AssignmentService`, `TaskRunLifecycleService`, and skill trust/activation contracts.

The user's vision is a workspace sprint establishing:

1. **Per-Agent Workspace Structure** — a personal "system" root with `/skills`, `/scripts`, `/apps`, `/home`, `/repos`, and `/&lt;current-workspace&gt;` mount point. Agent cannot edit root; only children.

2. **Project Workspaces** — project-scoped mirrors with `/skills`, `/scripts`, `/apps`, and the named workspace content directory. Mounted into agent workspace at `/&lt;current-workspace&gt;`.

3. **Git Repository Workspaces** — git repos cloned under `/repos` per agent, agents branch-and-PR workflow, WORKSPACE.md directs agents, auto-approval merge agent, after-merge re-sync. Strong enforcement via AGENTS.md/system prompts.

4. **Workspace Features** — work queue, task/workflow assignment, file explorer, git controls, and a "system chat" with a restricted agent who cannot edit files but can manage work queues and workspace state.

5. **Git Integration** — per-agent clones under `/repos`, branch-per-agent, PR-to-main, auto-approval agent triggers on PR, designated PR agent merges/resolves conflicts, post-merge re-sync.

6. **Dynamic System Prompts** — context-aware prompts loaded based on workspace type (git repo vs. regular), directing agent behavior for the current mount.

7. **Skills Explorer** — agent dashboard skills explorer integrating with existing scoped agent skills system.

## Source

The working project is Magenta at `/home/dhickel/Code/Java/Magenta`. The analysis draws from:

- **Architecture specification** (`ARCH-20260719-01`, `ARCH-20260720-01`, `ARCH-20260720-03`, `ARCH-20260526-03`): Three-kind closed workspace model (`AGENT`, `CHAT`, `USER`), canonical task-run identity with `TaskRunLifecycleService`, scoped agent skills with immutable snapshot trust.
- **Service graph** (`SVC-20260719-01` through `SVC-20260720-02`): Descriptor-only identity flow, execution workspace selection with type/key pairs, run lifecycle with independent assignment/writer fences.
- **Decisions** (`DECISION-20260719-02`, `DECISION-20260720-03`, `DECISION-20260720-04`): Filesystem-first workspace identity, no auto-trust skills, capability-gated process execution and FTS5.
- **Deferred features** (`DEFERRED-20260525-11`, `DEFERRED-20260526-05`, `DEFERRED-20260525-18`): Dashboard-aware system chat (deferred), project-local skill scope (partially-implemented, currently unavailable without trusted project-root authority), role-aware project collaboration (deferred).
- **Key knowledge files**: `scoped-skill-trust-snapshots.md`, `run-lifecycle-idempotency-and-fencing.md`, `task-run-artifact-lifecycle.md`, `agent-skills-ui-htmx-pattern.md`, `root-relative-workspace-storage.md`.
- **Live implementation**: `WorkspaceKind` enum (closed to `AGENT`, `CHAT`, `USER`), `WorkspaceDescriptor` (package-constructed identity with kind/key/root), `TaskRunLifecycleService` (idempotent reservations, attempts, writer fences), `AssignmentService` (type/key workspace selection), `SkillScopeDiscoveryService` (precedence-ordered workspace/bound-agent/project/global discovery), `AgentSkillActivationService` (snapshot-only, never reads live files).

## Findings

### 1. The closed WorkspaceKind model is a hard architectural contract

`WorkspaceKind` is explicitly closed to `AGENT`, `CHAT`, and `USER`. Architecture entry `ARCH-20260719-01` states: "`WorkspaceKind` is closed to `AGENT`, `CHAT`, and `USER`." Decision `DECISION-20260719-02` explicitly removed project roots and materialized links: "project association is metadata and membership context without filesystem allocation." Adding a `PROJECT` kind or project filesystem roots would directly conflict with a governed architectural decision that was validated through phases 02-08.

The architecture also explicitly states: "Project discovery is unavailable unless a trusted project-root authority is supplied; chat, task-run, process-home, and guessed roots are never scanned" (`ARCH-20260526-03`). This means project-scoped skill discovery is capability-gated and currently disabled.

### 2. The per-agent structured workspace maps cleanly to existing AGENT workspace root

The existing `workspace/agent/&lt;agentId&gt;/` root is already the Files tab mount point on the agent operational console. Adding structured subdirectories (`/skills`, `/scripts`, `/apps`, `/home`, `/repos`) beneath it is architecturally compatible — these are just children of the existing descriptor root. Workspace explorer operations already confine to the descriptor root, so the "agent cannot edit root" constraint is naturally satisfied by the existing root-mutation rejection in `WorkspaceExplorerService`.

The `/skills` subdirectory under an agent workspace maps directly to the `SkillScope.WORKSPACE` discovery path that `SkillScopeDiscoveryService` already scans. The `/&lt;current-workspace&gt;` mount is the novel piece.

### 3. The "mount" concept has no existing filesystem or metadata anchor

There is currently no concept of "mounting" one workspace into another. Options for implementing a mount:

- **Symlinks**: Simple but architecturally dangerous. Symlink traversal is explicitly rejected by `WorkspaceExplorerService` confinement. Introducing symlinks into the workspace tree would create a persistent escape path that the explorer must then defend against.
- **Virtual path resolution (application-layer remapping)**: The agent's File Tool and explorer could resolve paths under `/&lt;current-workspace&gt;/` by consulting a metadata table that maps the agent to a mounted project workspace descriptor. This is cleaner but creates a new resolution layer that must be consistently applied across every filesystem interaction.
- **Metadata pointer only (no filesystem mount)**: The "mount" could be purely a dashboard/UI concept where the agent dashboard displays the project workspace as a linked context, and the agent's system prompt tells it where the project files actually live. The agent accesses the project workspace through its own canonical path rather than a virtual mount.

### 4. Git integration introduces process-execution dependency that is currently fail-closed

Decision `DECISION-20260720-04` states: "Host process confinement is not proven, so workspace-bound shell behavior, skill executable authorization/execution, and package ingestion remain disabled." Git operations (clone, branch, commit, push, PR) require shell execution. The existing `shell_exec` tool is workspace-confined (`SVC-20260525-07`) but its parser only handles whitespace, quotes, and backslash escapes — shell operators are not yet hardened (`DEFERRED-20260525-10`).

Running `git clone` through `shell_exec` would be:
- The first production use of shell execution for a core workflow
- Requiring the tool to operate outside workspace confinement (cloning from remote URLs)
- Introducing credential management concerns

A dedicated `GitService` with controlled operations could be a safer alternative, but it still requires process execution or a JGit embedded approach.

### 5. System chat agent maps to deferred capability, not current architecture

`DEFERRED-20260525-11` captures "dashboard-aware system chat" as an accepted deferred capability: "Assistant can reason over dashboard summary state and propose confirmed actions." The user's vision extends this to a workspace management agent that can interact with work queues but not edit files.

The existing tool authorization system (`AgentToolAuthorizationService`, `ToolAccessPolicy`) already supports per-agent tool restriction. A "system chat agent" could be modeled as:
- A dedicated agent profile with a restricted tool set (work queue read/write, assignment management, workspace status — but no file_write, file_append, file_replace, file_delete, shell_exec)
- A persistent chat session bound to the workspace rather than a user conversation
- Or a new chat surface type that is workspace-scoped rather than conversation-scoped

The operational tool surface already exposes `list_assignments`, `list_work_queue`, and related tools through `AgentOperationalToolService`. A system chat agent would simply be an agent profile with only these operational tools authorized.

### 6. Dynamic system prompts: AGENTS.md resolution already supports hierarchy

The existing `AGENTS.md` resolution (`SVC-20260526-02`, `DECISION-20260526-02`) walks from the descriptor root downward to the active confined tool path, with closest-wins conflict precedence. This can be extended to support context-aware prompt loading:

- A root `AGENTS.md` in the agent workspace could contain baseline instructions
- A `WORKSPACE.md` in the mounted project workspace (or repo WORKSPACE.md) could layer project-specific instructions
- The task-run prompt assembler could inject workspace-type-specific guardrails (e.g., git workflow rules for repo workspaces, mount-path instructions for project workspaces)

The prompt assembly would not need a new mechanism — it would extend the existing layered resolution to include workspace-type-aware segment injection from a service that inspects the current mount context.

### 7. Work queue integration with existing assignment system

The assignment queue already exists (`AssignmentService`, `OrchestrationRuntimeRepository`). The agent operational console at `/agents/{agentId}` shows the queue. The "work queue" in the user's vision is essentially:

- A filterable/actionable view of pending assignments for the agent
- Assignment creation from the workspace dashboard (currently done through UI forms and API)
- System chat agent being able to create/inspect/cancel assignments

The existing `AgentOrchestrationController` and `OrchestrationController` already expose lifecycle operations (cancel, pause, resume, retry, replay, force interrupt) with agent-scoped authorization.

### 8. The PR auto-approval/merge agent is a new class of system agent

No existing concept maps to an automated agent that triggers on external events (PR creation) and performs privileged operations (merge, conflict resolution). This requires:

- A webhook or polling mechanism to detect PRs
- An agent profile authorized to perform git operations and merge
- A lifecycle distinct from task-based execution — this is more like a scheduler/reaction trigger
- Trust boundaries: this agent has elevated privileges (merge to main) that normal agents lack

The existing `ScheduleService` and `EventReactionService` (`ARCH-20260720-02`) provide revisioned scheduling and event reactions. A PR merge agent could be modeled as an event reaction: "on PR opened against main, dispatch merge agent." But the reaction system currently dispatches to the assignment pipeline, and the merge agent would need tooling that doesn't exist yet.

### 9. Ownership boundaries and capability limits

| Concept | Closest existing owner | Gap |
|---|---|---|
| Per-agent structured workspace | `WorkspaceDirectoryService`, `WorkspaceExplorerService` | Subdirectory initialization/layout policy; currently no service creates agent workspace structure |
| Project workspace | No owner (intentionally removed) | Architecture explicitly removed project roots; would need a `DECISION` reversal or a non-`PROJECT` approach |
| Workspace mount | No owner | New concept requiring either symlinks (rejected by confinement), virtual path resolution (new service), or metadata-only pointer (new descriptor relationship) |
| Git integration | `shell_exec` tool (shell execution is fail-closed) | Process execution capability gate must be lifted or a dedicated non-shell git service built |
| PR auto-merge agent | `EventReactionService`, `ScheduleService` | New agent class that's event-triggered, not task-queued; new privilege model |
| System chat agent | `AgentOperationalToolService`, `ChatService` | New chat surface type (workspace-scoped), restricted tool profile |
| Dynamic system prompts | `AgentsMdResolver`, `PromptContextAssembler` | Extension to inject workspace-type-aware segments |
| Skills explorer | `SkillFragments`, `SkillScopeDiscoveryService` | Agent-workspace skills already discoverable; UI composition is the gap |
| Work queue dashboard | `OrchestrationController`, `RunOperationsFragments` | Mostly exists; system chat agent interaction is the gap |

## Options

### Option A: Extend the closed model with metadata-only "project workspaces"

Keep `WorkspaceKind` closed at `AGENT`, `CHAT`, `USER`. Create project workspaces as structured directories under a user or agent workspace, referenced by metadata. The "mount" is a simple metadata pointer (a `current_workspace` column on the agent or a workspace-link table) that the dashboard and system prompt consume. No new workspace kind, no filesystem link/symlink.

- Agent workspace root: `workspace/agent/&lt;id&gt;/` (existing, add subdirectory layout)
- Project workspace: lives under a designated user's `workspace/user/&lt;name&gt;/projects/&lt;project&gt;/` or as a standalone `workspace/user/&lt;project-name&gt;/` directory
- Mount: a simple DB row `(agent_id, mounted_workspace_kind, mounted_workspace_key)` that the prompt assembler and dashboard read
- Skills discovery: project-local skills under the mounted workspace's `.skills/` or `/skills` are discovered via existing `SkillScopeDiscoveryService` when a trusted project-root authority is configured

**Pros**:
- No architecture spec changes needed (no new `WorkspaceKind`, no filesystem links)
- Compatible with existing descriptor resolution
- Mount is a pure application-layer concept

**Cons**:
- "Mount" is not a real filesystem mount — agents see the project workspace through its canonical path, not through `/&lt;current-workspace&gt;/` in their own tree
- `SkillScopeDiscoveryService` project discovery still needs a trusted project-root authority

### Option B: Add `PROJECT` as a fourth WorkspaceKind

Reverse `DECISION-20260719-02`'s project-root removal and add `PROJECT` to the closed enum. Project workspaces get their own `workspace/project/&lt;id&gt;/` roots with the structured subdirectory layout. Mount is either symlink (risky) or a virtual-path resolution service.

**Pros**:
- Gives project workspaces equal footing with agent/chat/user
- Clean identity model: `project/&lt;id&gt;` is canonical
- `SkillScopeDiscoveryService` can discover project skills directly without a "trusted authority" gate

**Cons**:
- Directly conflicts with `DECISION-20260719-02` — would need a formal decision reversal with justification
- Adds complexity to workspace selection (execution would need to select project workspace)
- The architecture removed project roots for good reasons (identity drift, mixed-purpose persistence, link/lease machinery)

### Option C: Model project workspaces as USER workspaces with naming convention

Use existing `USER` workspace kind with a naming convention: `workspace/user/project-&lt;name&gt;/`. The mount metadata pointer references the user workspace. This requires no architecture change except the subdirectory layout policy.

**Pros**:
- Zero architecture friction
- Existing API and services work unchanged
- User workspaces already support explicit creation

**Cons**:
- Semantic blurring: a "project workspace" is just a user workspace by convention
- No type-level distinction for prompt assembly or skills discovery
- Doesn't match the user's mental model cleanly

### Option D: Hybrid — metadata mount with WorkspaceDescriptor pair

Introduce a `workspace_mounts` table: `(agent_workspace_kind, agent_workspace_key, mounted_workspace_kind, mounted_workspace_key, mount_path)` where `mount_path` is always `current-workspace`. The agent descriptor is enhanced with an optional `mountedDescriptor()`. The prompt assembler, file tool, and explorer all gain a mount-resolution layer: when a path starts with `current-workspace/`, resolve it against the mounted descriptor root. This is application-layer virtual mounting.

**Pros**:
- Provides real `/current-workspace/` path semantics for agents
- Works within closed `WorkspaceKind`
- Clean separation: mount is metadata, not filesystem
- Prompt assembler can inject mount-context instructions

**Cons**:
- Every filesystem-interacting component needs mount-awareness (file tool, shell tool, explorer, prompt context)
- New failure modes: stale mounts, missing mounted roots, circular mount detection
- Skills discovery needs to know about mounts

## Trade-offs

### Mount mechanism: application-layer virtual path vs. metadata pointer only

**Virtual path** (Option D) gives agents the intuitive `/current-workspace/` path they expect, but requires every filesystem component to gain mount-awareness. This is a cross-cutting change touching file tools, shell tools, explorer, prompt assembly, AGENTS.md resolution, and output publication. Each component must have a consistent mount resolution strategy, or agents will see different views of "the same" path.

**Metadata pointer** (Option A) is simpler — the dashboard and prompt tell the agent "your project workspace is at `/workspace/user/my-project/`" and the agent operates there directly. But the agent's mental model diverges from the dashboard's "mounted workspace" metaphor.

**Recommendation**: Start with metadata pointer (Option A). If the lack of a real mount path causes confusion, add virtual-path resolution later (Option D) as a layer over Option A. The metadata pointer is the minimal viable approach.

### Project workspace identity: USER namespace vs. new PROJECT kind

Using `USER` workspaces for projects (Option C) avoids architecture conflict but creates an identity problem: is `workspace/user/alice/` Alice's personal workspace or a project workspace? The prompt assembler and dashboard can't distinguish them by type.

Adding `PROJECT` kind (Option B) is architecturally clean but requires a formal decision reversal for `DECISION-20260719-02`, which was signed after phases 02-08 of validation. The reversal must address why the earlier rationale (project roots enabling identity drift and link/lease machinery) no longer applies.

**Recommendation**: Defer the `PROJECT` kind decision. Start with a `workspace_mounts` metadata approach (Option D lite) where mounted workspaces retain their actual kind (AGENT or USER) and the mount is purely a relationship. If project-specific behavior becomes compelling, revisit with a decision proposal.

### Git integration: shell_exec vs. dedicated GitService

Using the existing `shell_exec` tool for git operations is the path of least resistance but also the path of least safety. Shell execution is fail-closed by architectural decision. A dedicated `GitService` using JGit (embedded Java git library) would:

- Avoid shell process execution entirely
- Provide controlled operations (clone, branch, commit, push, PR create)
- Integrate with workspace confinement naturally (clone target is under `/repos` in the agent workspace)
- Require credential management (SSH keys or tokens for push/PR)

The trade-off is implementation effort: JGit is a substantial dependency but provides safe, controllable git operations without shell escape risk.

**Recommendation**: A dedicated `GitService` with JGit is the safer path. It aligns with the architecture's capability-gated approach and avoids the shell confinement problem entirely.

### System chat agent: new chat surface vs. restricted agent profile

A "system chat" is conceptually a chat with an agent that has limited tools. This could be:
- A new `ChatSessionSurface` type (like `WORKSPACE_MANAGEMENT`) that the UI renders differently
- Simply a restricted agent profile that users chat with through the normal chat interface

The second approach requires zero new architecture — just an agent profile with operational tools only (no file mutation tools). The first approach would give the workspace dashboard its own embedded chat panel, similar to how the Avatar dashboard had a dedicated chat rail (removed in Phase 03).

**Recommendation**: Start with a restricted agent profile. Add a dedicated workspace chat panel later if the dashboard UX demands it. This aligns with the "smallest complete thing" policy.

### PR auto-merge agent: event reaction vs. scheduled polling vs. dedicated service

| Approach | Mechanism | Trust model | Effort |
|---|---|---|---|
| Event reaction | `EventReactionService` triggers on webhook | Reaction definition with merge agent as target | Medium (reaction system exists, needs webhook) |
| Scheduled polling | `ScheduleService` polls git remote periodically | Schedule definition with merge agent | Low (schedule system exists, polling logic needed) |
| Dedicated service | Standalone `PrMergeService` with its own lifecycle | Service owns merge logic directly | High (new service class) |

**Recommendation**: Scheduled polling through `ScheduleService` is the pragmatic start. It's the simplest integration with existing infrastructure and can be replaced with event-driven reactions when webhook support matures.

## Open Questions

1. **Mount semantics**: Is the `/current-workspace/` mount a filesystem reality or a metadata fiction? If metadata, does the agent see the mounted workspace through its canonical path or through a virtual path? The answer determines whether every filesystem component needs mount-awareness.

2. **Project workspace creation lifecycle**: Who creates project workspaces — users through the dashboard, agents through tools, or administrators through configuration? What's the creation API surface?

3. **Git credential management**: How are git credentials stored and scoped? Per-agent SSH keys? A system-level GitHub token? This is a security boundary that doesn't exist in the current architecture.

4. **PR agent privilege model**: The merge agent has elevated privilege (write to main). How is this distinguished from normal agent capabilities? Is it a tool authorization, a separate agent profile, or a new authorization layer?

5. **Workspace initialization**: When an agent workspace is first accessed, should the structured subdirectories (`/skills`, `/scripts`, `/apps`, `/home`, `/repos`) be auto-created? The current architecture explicitly avoids starter-file generation and lazy-creates agent roots only on file-producing operations. A structured layout is a new policy decision.

6. **Git workspace vs. regular workspace**: The user wants WORKSPACE.md for git repos that directs agents to clone, branch, work, PR, and sync. Should this be an `AGENTS.md` variant in the workspace root, or a separate file with different resolution rules? The existing AGENTS.md resolver already supports layered resolution from descriptor root — a WORKSPACE.md could simply be another resolved layer.

7. **System chat agent's work queue operations**: What exactly can the system chat agent do? Create assignments? Cancel them? Re-prioritize? All of these map to existing `AssignmentService` operations but need tool exposure and authorization scoping.

8. **Cross-workspace skills**: The user wants `/skills` in the agent workspace to be "cross-workspace skills the agent can use anywhere." Currently, skill scope discovery includes the effective workspace and bound agent workspace. "Anywhere" — including across different mounted project workspaces — would require a scope expansion.

9. **Multi-agent coordination**: The user mentions "task queues showing tasks/agents" — implying visibility into what other agents are working on. The current assignment queue is agent-scoped. Cross-agent visibility is a new authorization domain.

10. **Git repo workspace exploration**: The user wants "each agent's clone under the workspace" to be explorable. Currently, the Files tab shows the agent's own workspace. Showing other agents' clones requires cross-workspace browsing, which the closed descriptor model doesn't support.

## Recommended Next Step

**Do not implement. Do not edit. Produce a signed plan artifact first.**

The brainstorming reveals that the user's vision touches five architectural decision points that require formal treatment before any implementation:

1. **Workspace mount model** (Option A vs. D): Needs a design decision recorded in `decisions.md`.
2. **Project workspace identity** (USER namespace vs. new PROJECT kind): Needs either a formal `DECISION` reversal proposal or explicit acceptance of USER-namespace convention.
3. **Git integration approach** (shell_exec vs. dedicated GitService): Needs a capability-gate decision — does git integration justify lifting or working around shell execution confinement?
4. **System chat agent model** (restricted profile vs. new chat surface): Needs product scoping.
5. **PR auto-merge agent lifecycle** (scheduled vs. event-driven): Needs a design decision.

**Recommended sequence:**

1. **Record this brainstorm** as a `.internal-dev/plans/workspace-sprint-brainstorm/` artifact with this analysis as the concept document.
2. **Draft a decision proposal** for the five architectural questions above, each with options, trade-offs, and a concrete recommendation. The proposal must address `DECISION-20260719-02` compatibility explicitly.
3. **Resolve the capability gate** for process execution: can git operations proceed under a dedicated `GitService` (JGit) without lifting shell execution confinement? Or does the architecture need a confinement exception for git?
4. **Define the minimal viable slice**: What is the smallest complete feature that delivers value? Likely: per-agent structured workspace subdirectories + metadata mount pointer + skills explorer enhancement. Defer git integration and system chat to follow-on phases.
5. **Create a phased implementation plan** with the resolved decisions, aligned with the existing `WorkspacePathLayout`, `WorkspaceDirectoryService`, `WorkspaceExplorerService`, `SkillScopeDiscoveryService`, and `TaskRunLifecycleService` contracts.

**Key constraint**: Any change that adds a `PROJECT` workspace kind or project filesystem roots must go through a formal decision reversal of `DECISION-20260719-02`, with the same level of validation (architecture spec update, service graph update, bounded startup, and browser validation).
