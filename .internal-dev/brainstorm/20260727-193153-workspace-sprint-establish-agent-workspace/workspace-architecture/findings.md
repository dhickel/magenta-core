# Workspace Architecture Strategy — Agent, Project, and Git Workspaces Brainstorm

## Prompt

Establish agent workspace structure (skills, scripts, apps, home, repos, current-workspace mount), project workspaces (skills, scripts, apps, workspace-name), Git repository workspaces (clone, branch, PR, merge, sync), work queue display, system chat agent, context-driven system prompts, and skills explorer — all within the existing closed `AGENT`/`CHAT`/`USER` workspace model, `WorkspacePathLayout` authority, `WorkspaceDirectoryService` descriptor ownership, and project-as-metadata baseline.

## Source

Inspected authoritative sources:
- `.internal-dev/specifications/architecture.md` — Closed `WorkspaceKind` (AGENT/CHAT/USER), `WorkspacePathLayout` sole structural authority, `WorkspaceDirectoryService` sole descriptor constructor, project-as-metadata baseline, task-run child locations, reserved `.skills/` and `tasks/` grammar. `ARCH-20260719-01` active.
- `.internal-dev/specifications/services.md` — `WorkspaceDirectoryService` owns descriptor resolution and task-run child provision. `WorkspaceExplorerService` owns confined filesystem operations from a resolved descriptor. `SkillScopeDiscoveryService` discovers `.skills/*.md` from AGENT/USER workspace roots. Root mutation prohibited.
- `.internal-dev/specifications/service-graph.md` — Identity flow: `(type, key) → WorkspaceDirectoryService → WorkspaceDescriptor → downstream services`. Controllers never supply roots. Project edges are metadata-only.
- `.internal-dev/specifications/decisions.md` — `DECISION-20260719-02`: "WorkspaceKind is closed to AGENT, CHAT, and USER." Projects are metadata/membership context without filesystem allocation. `DECISION-20260720-01`: Task runs are confined children of AGENT/USER only. `DECISION-20260720-03`: Skills require immutable snapshot approval.
- `.internal-dev/specifications/api.md` — Typed workspace routes at `/api/workspace/{type}/{id}/files` (type = agent|chat|user). Execution selection carries `workspaceType`/`workspaceKey` pair. Root mutation and `outputs/` mutation rejected.
- `.internal-dev/specifications/web.md` — Agent Files tab mounted at `/agents/{agentId}` bound to `workspace/agent/<agentId>`. Dense operational console visual language. HTMX-first fragments.
- `.internal-dev/knowledge/root-relative-workspace-storage.md` — Data root contains `workspace/agent/`, `workspace/chat/`, `workspace/user/`. Persisted path columns are data-root-relative. `RootRelativePathService` is artifact-relative only, never identity.
- Source code: `WorkspacePathLayout.java` — Reserved names: `workspace`, `outputs`, `tasks`, `.skills`, `staging`. Three root factories: `agentRoot`, `chatRoot`, `userRoot`. Task-run child path grammar. Component validation and reserved-name collision rejection.
- Source code: `WorkspaceDirectoryService.java` — Descriptor resolution for read/write. Agent/chat lazy creation. User explicit creation. Task-run location resolution and provision. Confinement through `ConfinedPathMutationService`. Symlinks rejected. Root mutation rejected.
- Source code: `SkillScopeDiscoveryService.java` — Discovers from effective AGENT/USER `.skills/`, bound-agent workspace `.skills/`, project authority `.skills/` (only when supplied), and global `skills/`. Project roots never guessed.
- Source code: `AgentFileToolService.java` — Confined file operations within the resolved descriptor root. Reserved-tree enforcement via `ReservedWorkspacePathPolicy`.

## Findings

### Current Architecture Ground Truth

1. **`WorkspaceKind` is explicitly closed.** The contract (`ARCH-20260719-01`, `DECISION-20260719-02`) declares the closed set as `AGENT`, `CHAT`, `USER`. Adding a fourth kind is a material architectural decision that would require revising the decision and updating `WorkspaceKind`, `WorkspacePathLayout`, `WorkspaceDirectoryService`, API routes, execution selection rules, and all downstream services.

2. **Project is deliberately metadata-only.** `DECISION-20260719-02` explicitly states "project association is metadata and membership context without filesystem allocation." This was a deliberate removal of project roots, materialized links, and filesystem leases. Reintroducing project filesystem roots would reverse this decision and needs strong justification.

3. **Agent workspace roots are thin.** Current agent workspace structure is deliberately flat — `workspace/agent/<id>/` with no internal structure scaffolding. The reserved names are `tasks/`, `.skills/`, `outputs/`, and `staging/`. Adding mandatory subdirectories requires changes to `WorkspacePathLayout`, `WorkspaceDirectoryService`, and tool confinement policies.

4. **Skills discovery already supports workspace-local skills.** `SkillScopeDiscoveryService` discovers `.skills/*.md` from any AGENT/USER workspace root plus a distinct bound-agent root. The user's `/skills` in agent workspace maps directly to this existing `.skills/` pattern. Project skills discovery exists in code but requires a proven project-root authority.

5. **Symlinks are rejected throughout the architecture.** `WorkspaceDirectoryService`, `WorkspaceExplorerService`, and `SkillScopeDiscoveryService` all explicitly reject symlinks. A symlink-based "mount" is incompatible with the current confinement model.

6. **No sub-workspace or mount concept exists.** There is no existing mechanism for one workspace to reference another or for an agent to have an "active" workspace pointer. The execution model carries `workspaceType`/`workspaceKey` at submission time but there is no persistent "current workspace" state on the agent.

7. **Root mutation is already prohibited.** `WorkspaceExplorerService` rejects workspace-root mutation. File tools operate within the confined root but there is no explicit root-level write prevention — `file_write` with an empty or "." path is currently rejected as invalid relative path, not as a root-mutation attempt.

8. **Git operations are tool-based.** The existing `shell_exec` tool could execute git commands but there is no git-specific service, no repo state tracking, and no PR workflow.

9. **System prompts are assembled from `AGENTS.md` layering.** `PromptContextAssembler` consumes `AGENTS.md` layers from descriptor root to active runtime path. Adding context-driven prompt injection (git vs standalone, project type) would extend this assembler.

10. **The agent dashboard Files tab is a confined filesystem browser.** It is not a general workspace management surface. Work queues, git controls, and system chat would be new independent panels.

### Tensions to Resolve

| Tension | Constraint A | User Need |
|---------|-------------|-----------|
| Project storage | Projects have no filesystem roots | Project workspaces need skills, scripts, apps, and content directories |
| Kind closure | `WorkspaceKind` is closed (AGENT/CHAT/USER) | "Project workspace" is a distinct concept needing identity and mounting |
| Symlink prohibition | Architecture rejects symlinks everywhere | "Mount current workspace" needs a pointer mechanism |
| Agent root simplicity | Agent roots are flat; only reserved subdirs are `tasks/`, `.skills/`, `outputs/`, `staging/` | Agent workspace needs `skills/`, `scripts/`, `apps/`, `home/`, `repos/`, and mount point |
| Execution selection | `workspaceType`/`workspaceKey` resolved at submission time only | Agent needs persistent "current workspace" context across interactions |
| Git abstraction | No git service; shell_exec is raw | Need PR workflow, auto-merge agent, branch tracking, sync |
| Tool confinement | File tools confined to resolved descriptor root | Agent must operate across agent workspace AND mounted project workspace |
| Root edit prohibition | Explorer rejects root mutation but file tools don't explicitly check | Agent "cannot edit root directory" needs explicit enforcement |

## Options

### A. Reserved Subdirectory Model (Minimal Change)

**Description:** Extend `WorkspacePathLayout` with reserved agent-workspace subdirectory names (`skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/`, `current`). These are *convention* directories — they exist when the agent creates them, not scaffolded by the system. `WorkspaceDirectoryService` adds factory methods for resolving these reserved children. Root mutation prohibition is extended to file tools. No new `WorkspaceKind`.

**Contract changes:**
- `WorkspacePathLayout`: Add reserved child constants and factory methods
- `WorkspaceDirectoryService`: Add `resolveAgentSkillDir`, `resolveAgentReposDir`, etc.
- `AgentFileToolService`: Add root-path write rejection
- `WorkspaceExplorerService`: Already rejects root mutation
- API: No new routes needed (existing typed workspace routes cover subdirectory operations)
- Prompt: Add reserved-directory guidance to `AGENTS.md` assembly

**For project workspaces:** Projects remain DB records. When assigned, a project creates a directory under `workspace/agent/<agentId>/projects/<projectName>/`. The `current` file or directory is a metadata pointer (a plain file containing the active project name).

**For git repos:** Agent clones into `workspace/agent/<agentId>/repos/<repoName>/`. A `WORKSPACE.md` at `projects/<projectName>/WORKSPACE.md` points to the git URL. Git operations use `shell_exec` or a new `GitToolService`.

**Pros:**
- Zero change to `WorkspaceKind`, descriptor model, or execution selection
- Fully compatible with existing confinement and symlink policies
- Agent isolation is natural (each agent has its own copy)
- Simple to implement: mostly layout constants + prompt guidance

**Cons:**
- No shared project state across agents — each agent has independent project directories
- Project workspaces are not first-class filesystem citizens; they are convention directories within agent workspaces
- "Mount" semantics are purely prompt-context (the `current` pointer is guidance, not enforced)
- Git repo cloning per-agent means N copies of a repo for N agents — no shared working tree
- No way to have a project workspace independent of any agent (no `workspace/project/<id>/`)

### B. Project as a Fourth WorkspaceKind (Structural Change)

**Description:** Add `PROJECT` to `WorkspaceKind`. Project roots live at `workspace/project/<projectId>/` with reserved subdirectories `skills/`, `scripts/`, `apps/`, `<projectName>/`. `WorkspaceDirectoryService` gains project descriptor resolution. Agent workspaces get a `mounts/` directory containing *reference files* (not symlinks) like `mounts/<projectId>.ref` whose content is the canonical identity `project/<projectId>`. Project discovery validates against the existing `ProjectService` DB records.

**Contract changes:**
- `WorkspaceKind`: Add `PROJECT("project")`
- `WorkspacePathLayout`: Add `projectRoot(String projectId)`, reserved child factories
- `WorkspaceDirectoryService`: Add project `resolveForRead`/`resolveForWrite`, entity validation through `ProjectRepository`
- API: Add `/api/workspace/project/{id}/files` routes (reuse typed route infrastructure)
- Execution: Allow `workspaceType=project` in execution selection (or keep project as a mount context only)
- Decision: Revise `DECISION-20260719-02` to allow the new kind, or record a new decision
- Skill discovery: `SkillScopeDiscoveryService` already has project discovery gated behind a project-root authority; this would supply that authority

**For mounting:** Agent workspace has `mounts/<projectId>/` directory. The mount is a *bind concept*: tools, prompts, and explorers resolve both the agent root and the mounted project root. A `MountResolutionService` translates between agent-relative mount paths and project roots. The mount is *not* a symlink — it's a service-layer resolution that happens at the tool/prompt boundary.

**For git repos:** Git-backed projects have the git URL stored as project metadata. Agent clones into `repos/<projectName>/` within its agent workspace. The mount points to `project/<projectId>`, but tools see the agent's cloned repo. WORKSPACE.md lives at the project root.

**Pros:**
- Projects have independent filesystem identity — shared across agents when needed
- Mount model is explicit and enforceable (tools resolve through the mount)
- Skill discovery for projects gets a proper authority
- API symmetry: `/api/workspace/project/{id}/files` follows existing pattern
- Project skills/scripts/apps live once at the project root, not N times

**Cons:**
- Breaks the closed `WorkspaceKind` contract — requires explicit architectural decision revision
- Adds significant complexity: mount resolution, project descriptor lifecycle, project CRUD API changes
- Git repos still need per-agent clones for branch isolation — project root is workspace definition, not the working tree
- Execution selection gets a third valid target — more rules, more validation
- Project creation/deletion needs filesystem lifecycle (create project root, delete on project deletion)
- Higher risk of architectural drift from the deliberately minimal three-kind model

### C. Workspace-as-Metadata with Structured Agent Roots (Intermediate Change)

**Description:** Keep `WorkspaceKind` closed. Add structured reserved directories to agent workspaces (as in Option A). Introduce a new concept: `WorkspaceContext` — a metadata record that pairs an agent with a "workspace definition." A workspace definition is either a project DB record (standalone) or a git URL + branch config. The `WorkspaceContext` is persisted in the database and is the source of truth for what an agent is "currently working on." The filesystem follows the metadata: agent workspace directories are *derived* from the context, not authoritative.

**Contract changes:**
- New DB table: `workspace_contexts` with agent FK, context type (PROJECT/GIT), target reference, active flag, mounted_at timestamp
- New services: `WorkspaceContextService` (owns context lifecycle), `WorkspaceContextResolver` (resolves context for tools/prompts)
- `WorkspacePathLayout`: Add reserved agent-workspace child constants
- `PromptContextAssembler`: Accept workspace context and inject context-specific system prompts
- `AgentFileToolService`: Extend confinement to include context-resolved paths
- API: New routes for workspace context management (`/api/agents/{agentId}/workspace-context`)
- Dashboard: New "Workspace" panel showing current context, work queue

**For mounting:** No filesystem mount artifact. The `WorkspaceContextResolver` is consulted at every tool invocation, prompt assembly, and explorer request. It tells tools "the agent's working directory is `<agent-root>/projects/<projectName>/`" or "the agent has a git repo checked out at `<agent-root>/repos/<repoName>/`." The `/current-workspace` the user sees is a prompt-generated concept, not a real directory.

**For git repos:** `WorkspaceContext` of type GIT carries the clone URL. `GitWorkspaceService` handles clone, branch, commit, push, PR creation. Repo state (current branch, remote, last sync) is tracked in the context record. The PR auto-approval agent is triggered by a new event when a PR is created.

**Pros:**
- No change to `WorkspaceKind` — preserves the closed contract
- Clear separation: workspace identity is filesystem (AGENT/CHAT/USER), workspace *context* is metadata
- Mount semantics are explicit and queryable (DB record), not guessable from filesystem
- Git state is observable (DB-tracked), not hidden in a git working tree
- System prompts can switch based on context type declaratively
- Agent "current workspace" is first-class data, not a convention file

**Cons:**
- Introduces a new domain concept (`WorkspaceContext`) that must be threaded through services
- Tools need to consult the context resolver, adding a dependency
- Project workspaces still don't have independent filesystem identity — project skills would need to live somewhere (shared git repo? global skills repo?)
- Work queue integration with existing `AssignmentService` and `TaskRunLifecycleService` needs careful mapping
- Git service is new surface area with its own error modes, state management, and concurrency concerns
- Context switching (agent changes active workspace) needs lifecycle management

### D. Git-Centric Model with Lightweight Project Files (Minimal + Git First)

**Description:** Treat git as the primary project workspace mechanism. Projects that are git repos follow the git workflow (clone → branch → work → PR → merge). Projects that are *not* git repos use a simple shared root under `workspace/project/<id>/` that is resolved *not* as a `WorkspaceKind` but as a direct filesystem path managed by a `ProjectWorkspaceService` (similar to how `outputs/` is a direct path managed by `WorkspaceDirectoryService.outputs()` rather than a new kind). The distinction: project filesystem roots exist but are *not* workspace descriptors — they are a parallel concept. Agent workspaces get structured directories. Mount is a `WorkspaceContext` DB record.

**Contract changes:**
- `WorkspacePathLayout`: Add `projectContentRoot(String projectId)` producing `workspace/project/<projectId>/` — but this is *not* a workspace root, it's a content root (similar to how `outputs/` is a reserved child, not a workspace)
- `ProjectWorkspaceService`: Owns project content root lifecycle (create, delete, resolve). Does *not* produce `WorkspaceDescriptor`. Validates against `ProjectService` DB records.
- `WorkspaceContextService` and `WorkspaceContextResolver`: As in Option C
- `GitWorkspaceService`: Owns clone, branch, PR workflow for git-backed projects
- API: `/api/projects/{projectId}/files` for project content browsing (separate from workspace routes)

**For mounting:** `WorkspaceContextResolver` returns the effective working path. For git projects, that's `<agent-root>/repos/<repoName>/`. For standalone projects with a shared content root, the agent is directed to `<agent-root>/projects/<projectName>/` (their own copy) with guidance to sync from the shared root. For shared project content, a `project_sync` tool copies from the project content root to the agent workspace.

**Pros:**
- No `WorkspaceKind` change
- Git is the primary collaboration model — well-understood, tool-rich, merge-safe
- Project content roots exist for non-git projects but don't bloat the descriptor model
- Clear separation: workspace kinds remain AGENT/CHAT/USER; projects are a content management concern
- Git state is transparent and inspectable through standard git tooling

**Cons:**
- Two parallel filesystem concepts (workspace roots + project content roots) with different resolution paths
- Non-git project content sharing is awkward (copy/sync model instead of shared workspace)
- Project content roots lack the confinement guarantees of workspace descriptors
- More services to maintain (`ProjectWorkspaceService`, `GitWorkspaceService`, `WorkspaceContextService`)
- API surface grows with project-specific routes separate from workspace routes
- System prompt assembly must handle three context types: agent workspace (always), project context (when set), git context (when applicable)

### E. Hybrid: Structured Agent Workspaces + Context Pointer + Git Service (Recommended Starting Point)

**Description:** This is a synthesis that picks the smallest-viable pieces from each option. It keeps `WorkspaceKind` closed, adds structured agent workspace directories (reserved names in `WorkspacePathLayout`), introduces a lightweight `WorkspaceContext` metadata pointer (DB-backed, single row per agent), and builds git integration as a dedicated tool service. Projects remain metadata records. The "project workspace" concept is realized through the `WorkspaceContext` — when an agent's context points to a project, their prompt and tools are scoped accordingly. Project content for non-git projects lives in the agent's own workspace (under `/projects/`). For git projects, the agent clones under `/repos/`.

**Key design decisions:**
1. **Reserved agent workspace directories** are convention, not enforcement. `WorkspacePathLayout` adds constants for `skills/` (mapped to `.skills/` for skill discovery compatibility), `scripts/`, `apps/`, `home/`, `repos/`, and `projects/`. The root mutation prohibition in `WorkspaceExplorerService` is extended to file tools with an explicit root-path check.

2. **`WorkspaceContext`** is a single active record per agent: `(agentId, contextType, targetRef, gitUrl, mountedAt)`. `contextType` is `NONE`, `PROJECT`, or `GIT`. This is resolved by `WorkspaceContextResolver` and injected into prompts and tool scoping.

3. **Git integration** is a `GitWorkspaceService` that wraps `shell_exec` with structured operations: `clone(agentId, gitUrl)`, `createBranch(agentId, branchName)`, `commitAndPush(agentId, message)`, `createPR(agentId, title, body)`. Git state is tracked in a `git_repo_states` table. The PR auto-approval agent is a dedicated agent profile that subscribes to PR events.

4. **Mount semantics** are context-driven: when a `WorkspaceContext` is active, the effective working directory for tools is resolved by the context resolver. For PROJECT context, it's `<agent-root>/projects/<projectName>/`. For GIT context, it's `<agent-root>/repos/<repoName>/`. The `/current-workspace` concept is a prompt artifact, not a filesystem directory.

5. **System prompts** are assembled by extending `PromptContextAssembler` to accept a `WorkspaceContext` and inject context-specific guidance (git workflow for GIT context, project structure for PROJECT context, general agent workspace for NONE).

6. **Work queue** is an HTMX panel on the agent dashboard that queries `AssignmentService` for pending/running assignments scoped to the agent and renders them using the operational console visual language.

7. **System chat** is a dedicated agent profile (`system-agent`) with limited tools (read-only file access, work queue management, workspace status). It cannot write files or execute tasks. It's accessible from the agent dashboard as a chat panel.

8. **Skills explorer** reuses the existing `/skills` operational surface with additional filtering to show skills from the agent's `.skills/` directory.

**Contract changes (smallest viable set):**

| Layer | Change | Magnitude |
|-------|--------|-----------|
| `WorkspacePathLayout` | Add reserved child constants (`SKILLS_DIR`, `SCRIPTS`, `APPS`, `HOME`, `REPOS`, `PROJECTS`) and factory methods | Small — additive, no breaking changes |
| `WorkspaceDirectoryService` | Add convenience methods for resolving reserved children from a descriptor. Explicit root-write prohibition. | Small — additive |
| `AgentFileToolService` | Add root-path write rejection ("." or empty path → rejected). | Tiny |
| New: `WorkspaceContext` | DB table + JPA record + repository | Small — new but isolated |
| New: `WorkspaceContextService` | CRUD for workspace context records | Small |
| New: `WorkspaceContextResolver` | Resolves effective context for tools/prompts | Small |
| New: `GitWorkspaceService` | Clone, branch, commit, push, PR operations wrapping shell_exec | Medium — new domain |
| New: `git_repo_states` | DB table tracking clone state per agent per repo | Small |
| `PromptContextAssembler` | Accept `WorkspaceContext` and inject context-specific system prompt segments | Small — additive |
| `SkillScopeDiscoveryService` | Already supports workspace `.skills/`; map agent `skills/` → `.skills/` or adjust discovery path | Small — path alignment |
| Dashboard fragments | New HTMX panels: Work Queue, Workspace Context selector, Git controls, System Chat launcher | Medium — new UI surface |
| API routes | New: `/api/agents/{agentId}/workspace-context`, `/api/agents/{agentId}/git/**` | Small — additive |
| `DECISION-20260719-02` | No revision needed — `WorkspaceKind` remains closed | None |

**What this does NOT change:**
- `WorkspaceKind` enum — stays AGENT/CHAT/USER
- `WorkspaceDescriptor` — no new fields or constructors
- Execution selection — `workspaceType`/`workspaceKey` still agent or user
- Project-as-metadata baseline — projects have no filesystem roots
- Symlink policy — no symlinks introduced
- Task-run child locations — remain under AGENT/USER only
- Existing API routes — no breaking changes

## Trade-offs

### Agent Workspace Subdirectories: Convention vs Enforcement

**Convention approach (recommended):** Reserved names are documented and used by prompts/tools but directories are created lazily by the agent or tools. No scaffolding on agent creation. The system does not require these directories to exist.

**Enforcement approach:** `WorkspaceDirectoryService` scaffolds all reserved directories on agent workspace creation. Tools reject operations outside reserved directories.

*Trade-off:* Convention is simpler and doesn't bloat every agent root with empty directories, but it means prompts are the primary enforcement mechanism. Enforcement is robust but adds startup cost and rigidity. For the first iteration, convention with root-level write prohibition is the right balance — the agent is guided to work inside children, and root mutation is technically prevented.

### WorkspaceContext: DB vs Filesystem

**DB-backed (recommended):** `WorkspaceContext` is a database row. It's atomically updatable, queryable, and can carry rich metadata (type, target, git URL, timestamps, active flag).

**Filesystem-backed:** A `.current-context` file in the agent workspace root containing JSON or plain text.

*Trade-off:* DB-backed is consistent with how other Magenta state is managed (assignments, task runs, skills, artifacts). It enables transactions, concurrency control, and history. Filesystem-backed is simpler but fragile (file corruption, concurrent writes, no queryability). DB-backed wins for Observability and consistency.

### Git: Dedicated Service vs Shell Tool

**Dedicated service (recommended):** `GitWorkspaceService` wraps `shell_exec` but provides structured operations: typed return values, error classification, state tracking, and integration with the PR workflow.

**Shell tool only:** Agents use raw `git clone`, `git branch`, etc. through `shell_exec`. No state tracking.

*Trade-off:* A dedicated service adds code but provides observability (repo state in DB), error handling (clone failures → structured messages), and workflow integration (PR creation triggers events). Raw shell_exec is simpler but leaves git state invisible to the system and makes PR auto-approval impossible without parsing git output. A dedicated service is the right call given the PR auto-approval requirement.

### Project Content: Agent-Local vs Shared Root

**Agent-local (recommended):** Each agent works in their own workspace. For non-git projects, they have `<agent-root>/projects/<projectName>/`. For git projects, they clone into `<agent-root>/repos/<repoName>/`.

**Shared root:** A central `workspace/project/<projectId>/` that multiple agents access.

*Trade-off:* Agent-local is simpler and safer — no concurrency issues, natural isolation, aligns with the "agent owns their workspace" model. Shared root requires lease/lock management (the exact machinery `DECISION-20260719-02` removed). Agent-local is the recommendation because git (the primary collaboration model) handles sharing through PRs, and non-git projects can be managed through the existing assignment/output pipeline.

### Mount Semantics: Resolver vs Filesystem Artifact

**Resolver-based (recommended):** `WorkspaceContextResolver` is called at the tool/prompt/explorer boundary. The effective working directory is computed from context, not read from a filesystem pointer.

**Filesystem artifact:** A `.current-workspace` file or `current/` directory containing a reference.

*Trade-off:* Resolver-based is the only option compatible with the existing architecture (no symlinks, no reference-following). It's also more robust — the DB is the source of truth, not a file that could be corrupted by an agent. Filesystem artifacts are simpler conceptually but create ambiguity (what if the file disagrees with the DB? what if it's deleted?).

### Work Queue: Integration Depth

**Read-only panel (recommended for initial):** The work queue on the agent dashboard queries `AssignmentService` for the agent's assignments and renders them with status chips. The system chat agent can list/describe but cannot modify.

**Full management:** The work queue panel allows reassignment, cancellation, priority changes, and creation.

*Trade-off:* Read-only is the smallest complete thing. Full management requires extending the assignment API with mutation operations that aren't currently needed. Start with read-only and add management as concrete workflows demand it.

### System Chat Agent: Profile vs Service

**Dedicated agent profile (recommended):** A pre-configured agent profile (`system-agent`) with a restricted tool set (file read, workspace status, assignment query, but no file write, no task execution). It appears as a chat panel on the dashboard.

**Service:** A non-agent service that handles workspace management through a chat-like interface.

*Trade-off:* A dedicated agent profile reuses the existing chat infrastructure (ChatService, ChatController, tool registration). It's naturally conversational and can be extended with new tools. A service approach would require building a chat-like interface from scratch or repurposing the chat system in an awkward way. Agent profile is the simpler, more consistent choice.

### PR Auto-Approval Agent: Event-Driven vs Polling

**Event-driven (recommended):** When `GitWorkspaceService.createPR()` succeeds, it publishes a `PullRequestCreated` event. An `EventReactionService` reaction dispatches the PR to the auto-approval agent.

**Polling:** The auto-approval agent periodically checks for open PRs.

*Trade-off:* Event-driven integrates with the existing `EventReactionService` infrastructure. It's immediate and efficient. Polling is simpler but introduces latency, wasted cycles, and scheduling complexity. Event-driven is the better architecture given the existing reaction infrastructure.

## Open Questions

1. **Should agent workspace directories be scaffolded on agent creation or remain lazy?** Current architecture prefers lazy creation (agent roots, task-run directories are created on first write). Scaffolding on creation would create ~6 empty directories per agent. Recommendation: lazy, with prompt guidance to create them as needed.

2. **How does the `skills/` directory in agent workspaces relate to the existing `.skills/` convention?** `SkillScopeDiscoveryService` looks for `.skills/*.md` at the workspace root. The user wants `/skills` (no dot prefix). Options: (a) rename the reserved name to `.skills/` internally but display as "Skills" in the UI, (b) update `SkillScopeDiscoveryService` to also scan `skills/`, or (c) use a prompt convention that treats them as the same. Recommendation: (c) for now — `skills/` is the user-facing name, and the existing `.skills/` discovery path remains the system mechanism. The agent is instructed (via AGENTS.md) to place skill files in `skills/`, and the system discovers from `.skills/`. If the agent creates `skills/`, a future iteration can align discovery.

3. **Can an agent have multiple active workspace contexts, or is it strictly one at a time?** The user's vision implies a single "current workspace" mount. Multiple concurrent contexts would require different tool scoping per conversation or per task. Recommendation: single active context per agent, with context stored per-chat-session for conversations that need different scoping.

4. **Where does WORKSPACE.md for git repos live?** For git-backed projects, should WORKSPACE.md live in the git repo itself (so it's versioned) or in the agent's `projects/` directory (as a local pointer)? Recommendation: in the git repo root. The clone operation brings WORKSPACE.md with it. If the repo doesn't have one, the `GitWorkspaceService` can generate a default based on project metadata.

5. **How does the PR auto-approval agent handle merge conflicts?** The user wants the PR agent to "merge and fix conflicts." Conflict resolution by an AI agent is high-risk — it could produce broken merges. Recommendation: start with conflict detection + notification. The auto-approval agent attempts merge; if conflicts exist, it reports them to the system chat and the PR author rather than attempting automatic resolution. Conflict resolution becomes a deferred feature.

6. **Should the system chat agent share conversations with other users/viewers or be agent-private?** The agent operational console currently has no multi-user concept. Recommendation: the system chat is bound to the agent profile and visible to anyone with dashboard access (current open-alpha posture).

7. **How does the work queue relate to the existing assignment lifecycle?** The assignment system (`AssignmentService`, `TaskRunLifecycleService`) manages task execution. The work queue panel should display assignments in a human-readable format. Question: should it also show unassigned tasks waiting for this agent, or only assigned/running ones? Recommendation: show assigned, running, and completable tasks. Unassigned task discovery is a separate feature (task pool browsing).

8. **Does git repo state need to survive application restarts?** If an agent has cloned a repo and the application restarts, should `GitWorkspaceService` be aware of the existing clone? Recommendation: yes. The `git_repo_states` table tracks clones. On startup, `GitWorkspaceService` probes the filesystem to reconcile DB state with actual repos.

9. **Should non-git projects support any sharing mechanism between agents?** If Agent A and Agent B both work on Project X (non-git), they each have independent `projects/project-x/` directories. Is there a need to share content? Recommendation: not in the initial iteration. Non-git projects are single-agent by default. Multi-agent non-git collaboration is a deferred feature. Git is the sharing mechanism.

10. **How are system prompts discovered and assembled for different context types?** Current prompt assembly uses `AgentsMdResolver` to layer `AGENTS.md` files from the descriptor root. Context-specific prompts (git workflow, project structure) could be: (a) additional `AGENTS.md` files in the resolved directories, (b) injected segments from the `PromptContextAssembler`, or (c) skill files activated for the conversation. Recommendation: (b) — `PromptContextAssembler` injects context-specific segments from a configurable source (template files or DB). This keeps the assembly logic centralized and doesn't require filesystem artifacts for every context type.

## Recommended Next Step

**Proceed with Option E (Hybrid: Structured Agent Workspaces + Context Pointer + Git Service)** as a phased plan:

**Phase 1 — Foundation:** Add reserved agent workspace subdirectory constants to `WorkspacePathLayout`. Add root-write prohibition to `AgentFileToolService`. Extend `WorkspaceDirectoryService` with convenience child resolution methods. Update agent `AGENTS.md` prompt assembly to guide agents to use the structured directories.

**Phase 2 — Workspace Context:** Introduce `WorkspaceContext` DB entity, repository, service, and resolver. Add `POST /api/agents/{agentId}/workspace-context` for context management. Extend `PromptContextAssembler` to inject context-specific guidance. Add a simple context selector to the agent dashboard.

**Phase 3 — Git Integration:** Build `GitWorkspaceService` with clone, branch, commit, push, PR operations. Add `git_repo_states` table. Create git control HTMX fragments for the agent dashboard. Implement PR creation events for auto-approval triggering.

**Phase 4 — Dashboard Panels:** Build Work Queue panel (read-only assignment list). Build System Chat panel (dedicated `system-agent` profile with restricted tools). Build Workspace Context panel (current context display, context switching). Build Skills explorer panel (filtered view of agent's `.skills/`).

**Phase 5 — PR Auto-Approval:** Configure the auto-approval agent profile. Wire `PullRequestCreated` events to `EventReactionService`. Implement merge attempt + conflict detection + notification (defer automatic conflict resolution).

**Before any implementation begins:**
- Socialize Option E with the user for feedback on the trade-offs
- Confirm that keeping `WorkspaceKind` closed is the right architectural constraint
- Get explicit approval on: agent-local project content (no shared project roots), resolver-based mount semantics, convention-based subdirectory structure
- Write the plan as a formal `.internal-dev/plans/` artifact
- Update `DECISION-20260719-02` if any aspect requires revision, or record a new decision documenting the workspace context model as an extension that preserves the closed kind contract
- Create a new git branch for the workspace sprint work
