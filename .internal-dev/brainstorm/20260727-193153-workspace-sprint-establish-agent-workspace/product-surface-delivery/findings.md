# Product Surface and Incremental Delivery Planner — Workspace Sprint

## Prompt

Explore user-facing information architecture and staged implementation approaches for the `/agents/{agentId}` operational console: full workspace and skills exploration, clone visibility, Git controls, queue views, and workspace management. Keep ideas aligned with SimplyPages reusable components, HTMX-first fragments, existing APIs and visual language, validation needs, migration risk, and the smallest complete sprint scope.

## Source

- `.internal-dev/specifications/architecture.md` — closed three-kind workspace model (`AGENT`, `CHAT`, `USER`), `WorkspacePathLayout` as sole path authority, `WorkspaceDirectoryService` as sole descriptor constructor, canonical task-run identity matrix, scoped agent skills, run-lifecycle fences, ordered migration ownership
- `.internal-dev/specifications/services.md` — `WorkspaceDirectoryService`, `WorkspaceExplorerService`, `TaskRunLifecycleService`, `SkillScopeDiscoveryService`, `SkillResolutionService`, `SkillTrustService`, `AgentSkillActivationService`, `OutputArtifactService`, `RunSummaryService` contracts
- `.internal-dev/specifications/api.md` — typed workspace API at `/api/workspace/{type}/{id}/files`, execution workspace selection rules (`workspaceType`/`workspaceKey` pair), run lifecycle operations, skills management APIs, absent legacy route families
- `.internal-dev/specifications/web.md` — agent Files tab contract (two-column: filesystem browser + run groups), operational fragment patterns, HTMX-first control plane, visual language (dense panels, thin blue-gray borders, small radii, semantic chips)
- `.internal-dev/specifications/simplypages.md` — HTMX default policy, reusable module candidates, independent stable host pattern, stateless renderers over authorized values, truthful state components
- `.internal-dev/specifications/decisions.md` — `DECISION-20260719-02` (filesystem-first workspace identity replacing Avatar/Work Areas), `DECISION-20260720-01` (canonical task-run paths), `DECISION-20260720-02` (operational metadata composed beside Files, not inside explorer services), `DECISION-20260720-03` (skills never auto-trusted)
- `.internal-dev/knowledge/root-relative-workspace-storage.md` — data-root-relative path storage, `WorkspacePathLayout` grammar
- `.internal-dev/knowledge/agent-skills-specification-reference.md` — progressive disclosure lifecycle, agent scopes, trust snapshots
- `.internal-dev/knowledge/agent-skills-ui-htmx-pattern.md` — master/detail surface, OOB list refresh, HTMX-first CRUD
- Source code: `OrchestrationController.java` (agent detail layout 5400-5600, agent dashboard tab 5438-5560, agent files tab 6389-6480, agent exec/history/submit tabs 6854-7054, agent run fragments 6318-6380), `WorkspaceExplorerFragments.java` (agentShell 726-836, two-column files+run layout), `WorkspacePathLayout.java` (reserved names, path factories), `WorkspaceDirectoryService.java` (descriptor resolution, confinement), `WorkspaceKind.java` (closed enum — AGENT/CHAT/USER), `RunOperationsFragments.java` (reusable HTMX run group/detail renderer), `FrontendController.java` (SimplyPages shells, rows, cards)

## Findings

### Current Agent Console Architecture

The agent operational console at `/agents/{agentId}` is a tabbed HTMX surface with 13 tabs: Dashboard, Profile, Queue, Inbox, Jobs, Schedules, Reactions, **Files**, Outputs, Exec, History, Submit. Each tab loads via `hx-get` into `#agent-tab-panel`. The Files tab uses `WorkspaceExplorerFragments.agentShell` which renders a two-column layout: a file browser panel (list + inspector + modal) on the left and `#agent-run-groups` (task run operations) on the right. The run panel loads independently via `hx-trigger="load"` from `/agents/_detail/{agentId}/runs?limit=25`.

### What Exists, What's Missing

| User Requirement | Current State | Gap |
|---|---|---|
| Per-agent workspace with structured directories | Raw `workspace/agent/<id>/` root — no enforced structure | Add: `/skills`, `/scripts`, `/apps`, `/home`, `/repos`, `/<current-workspace>` directories with enforcement |
| Agent cannot edit root; only children | `WorkspaceExplorerService` rejects root mutations and `outputs/`; no per-child enforcement | Extend: reject root-level create/delete, expose only named subdirectories |
| File/skills explorer in Files tab | Works: basic file list + inspector + run groups | Expand: directory-tree sidebar, skills entries inline, structured empty states |
| Project workspaces | No `PROJECT` workspace kind; projects are metadata-only (`DECISION-20260719-02`) | Must decide: new kind or AGENT subdirectory convention |
| Workspace "mounting" of project workspaces | No concept of mounts | Metadata pointer + system-prompt injection simplest; symlinks dangerous |
| Git integration (clone, branch, PR, merge) | No Git services; only `shell_exec` tool | New: `GitService` (JGit or process-backed), Git tab in console, PR agent profile |
| Work queue views | Queue tab exists (list of `WorkAssignment` rows with lifecycle controls) | Enhance: status chips, priority ordering, workspace-context display, bulk actions |
| "System chat" agent | Chat accordion exists on detail page; agent chat JS module present | Formalize: system-chat agent profile with limited tools, workspace-scoped chat |
| Skills explorer integration | `/skills` is a separate control-plane page; skills scoped to agent workspace via `.skills/` | Unify: embedded skills browser in agent console Files tab or a dedicated Skills tab |
| Dynamic system prompts | `AGENTS.md` layered resolution exists (`DECISION-20260526-02`) | Extend: context-aware prompt templates that inject workspace structure, Git rules, mount state |
| Git clone visibility | No concept | Files tab could show `/repos` directory; Git tab could list clones and their branches |

### Three-Kind Model Constraint

`WorkspaceKind` is explicitly closed to `AGENT`, `CHAT`, `USER` per `DECISION-20260719-02` and the architecture specification. Adding a `PROJECT` kind would require:
- A new enum value and all the descriptor resolution, path layout, API routing, execution selection, and migration work that entails
- Updating the drift history and decisions
- The user's prompt models project workspaces as mirrors of agent workspaces but project-scoped — this could be achieved as an `AGENT` subdirectory convention or a new kind

The subdirectory-convention approach (project workspace content lives inside the agent workspace under a named mount) is significantly lower risk and aligns with the filesystem-first philosophy. It would be a lightweight metadata layer over the existing tree rather than a new kind.

## Options

### Option A: Convention-Based Mount (Minimum Risk, Fastest Delivery)

**Core idea:** Project workspaces are the agent's own workspace with structured subdirectories. A "project mount" is a metadata pointer that tells the agent which subdirectory is the active project context. System prompts inject the mount path.

**Agent workspace structure:**
```
workspace/agent/<agentId>/
  .workspace/          — application-owned metadata (mount config, lock files)
  skills/              — cross-project skills (discovered by existing SkillScopeDiscoveryService)
  scripts/             — personal scripts
  apps/                — personal applications
  home/                — general files
  repos/               — git clones
  projects/            — project workspace directories (one subdirectory per project)
  tasks/               — canonical task runs (existing, reserved)
  outputs/             — legacy output artifacts (existing, reserved)
```

**Project workspace (inside projects/<name>/):**
```
projects/<projectName>/
  skills/              — project-specific .skills/ (discovered as AGENT .skills)
  scripts/             — project scripts
  apps/                — project applications
  workspace/           — the actual content
  WORKSPACE.md         — (for git repos) clone instructions
```

**Pros:**
- Zero architectural change to `WorkspaceKind`, `WorkspacePathLayout`, or `WorkspaceDirectoryService`
- Existing `WorkspaceExplorerService` confinement already protects the root; adding a root-level create-guard is additive
- Existing `.skills/` discovery already scans the effective AGENT workspace root recursively
- Existing `AGENTS.md` resolution already layers ancestor files from root → target
- Mount is a simple metadata row or `.workspace/mount.json` file
- No API route restructuring; Files tab is already bound to `workspace/agent/<id>`
- System prompt injection already works with `AGENTS.md` layered resolution
- Immediate delivery of the workspace browser experience

**Cons:**
- Project workspaces are not independently addressable via the typed workspace API without additional routing
- No per-project filesystem identity for the API; projects are always accessed through the agent context
- Git repos under `/repos` are agent-owned; no project-level clone sharing across agents (unless symlinks, which are rejected)
- The "mount" is conceptual; not a true filesystem mount

### Option B: New `PROJECT` WorkspaceKind (Full Architecture Extension)

**Core idea:** Add `PROJECT` to the `WorkspaceKind` enum with its own root at `workspace/project/<id>/`, full descriptor resolution, API routes, execution selection, and migration.

**Pros:**
- First-class project workspace identity
- Independently addressable via `/api/workspace/project/{id}/files`
- Clean separation of agent-owned vs project-owned files
- Execution can target a project workspace directly

**Cons:**
- Violates `WorkspaceKind` closed contract — requires explicit decision override
- Requires a new migration (010), new path layout factories, new descriptor resolution paths, new API routes
- Execution selection rules expand (project workspace selection)
- The user's prompt clearly says project workspace is mounted into agent workspace — so the indirection still exists
- Significantly more implementation surface and testing
- Risk of scope creep given the project already has a strong closed-kind architecture

### Option C: Hybrid — Project as AGENT Subdirectory + Typed Shortcut Routes

**Core idea:** Keep `Option A`'s filesystem structure but add lightweight API shortcuts for project-specific operations (e.g., `/api/workspace/agent/{agentId}/projects/{projectName}/files`) without adding a new `WorkspaceKind`. The shortcut resolves to the `projects/{projectName}/` subdirectory of the agent workspace.

**Pros:**
- No architectural change to `WorkspaceKind`
- Convenient project-scoped URLs for the UI
- Clear routing without polluting the closed kind model

**Cons:**
- URL structure implies a resource hierarchy that isn't a true workspace
- Could be confusing whether projects are first-class or just directories
- Additional routing complexity

### Git Integration Sprint Scoping

**Phase 1 — Visibility:** Show the `/repos` directory in the Files tab with a Git status column (branch name, dirty state). This is purely filesystem reading — no Git tooling. Already achievable with the existing explorer.

**Phase 2 — Controls:** A new "Git" tab in the agent console with:
- Clone: POST form that accepts a URL, clones to `repos/<repoName>/`
- Branch: list branches, create new branch, switch branch
- Status: staged/unstaged changes, remote ahead/behind
- Pull/Push: bounded operations with confirmation
- All rendered HTMX-first, backed by a new `GitService` wrapping JGit or a process-backed Git adapter

**Phase 3 — PR Automation:** 
- An agent profile designated as `PR_REVIEWER` 
- On PR creation by any agent, a schedule/reaction or explicit workflow triggers the PR agent
- PR agent clones/checks out repo, reviews diff, approves/requests changes
- For auto-merge: PR agent resolves conflicts and merges
- This is a task/workflow execution, not a standalone service

### Workspace Browser UI: Incremental Evolution of the Files Tab

The current Files tab has a two-column layout: file list on the left, run operations on the right. The evolution:

**Sprint 1 — Directory Pane + Skills Inline:**
- Add a narrow directory-tree sidebar that shows the structured root entries: `skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/`
- Clicking a root directory navigates the file list
- The file list highlights `.skills/*.md` entries with a skill-chip indicator
- Empty state per directory ("No skills installed. Add `.md` files to `skills/` to create agent skills.")
- Root-level mutation guard (no create/delete/rename at root)
- Keep the right-side run operations panel

**Sprint 2 — Git Tab:**
- New tab "Git" replaces the current "Exec" tab (or sits beside it)
- Lists clones under `repos/` with status: branch, ahead/behind, dirty
- Clone form, branch switcher, pull button
- Operations post to new REST endpoints, HTMX refreshes

**Sprint 3 — Queue Enhancements:**
- The existing Queue tab gets workspace-context display (which project/workspace the assignment targets)
- Status chip refinements, priority ordering controls
- Bulk cancel/pause operations

**Sprint 4 — System Chat Formalization:**
- The existing agent chat accordion becomes a first-class "System Chat" tab
- System chat agent: a profile with file-read tools but no file-write tools
- Can interact with queue, inspect workspace, trigger Git operations
- Chat context includes workspace structure and current mount state

**Sprint 5 — Mount and Prompt Integration:**
- `.workspace/mount.json` metadata file
- Context-aware `AGENTS.md` fragments: root-level workspace policy, Git workflow rules, project-specific instructions
- Dynamic system prompt assembly from mount state

### Skills Explorer Integration

The existing Skills system already discovers `.skills/*.md` from the effective AGENT/USER workspace. The agent Files tab can:

1. **Inline:** Show `.md` files in the `skills/` directory with a skill-chip indicator column
2. **Trust/activate from Files:** Right-click or inspector actions to trust/activate a skill discovered in the workspace
3. **Skills status panel:** Show approved/activated skills for this agent in a sidebar panel beside the file list
4. **Link to /skills:** A "Manage Skills" button that opens the full `/skills` control plane in a new context

Because skills have their own dedicated controller (`SkillController`), trust/approval/activation endpoints, and fragment renderer (`SkillFragments`), the Files tab should compose these as independent stable hosts rather than reimplementing skill management. This follows `DECISION-20260720-02` — operational metadata is composed beside Files, not inside explorer services.

### System Prompt Strategy

The user wants dynamic system prompts based on context (git repo workspace vs regular workspace). The existing `AGENTS.md` layered resolution (`DECISION-20260526-02`) is the right vehicle:

1. **Root `AGENTS.md`** (`workspace/agent/<id>/AGENTS.md`): general agent workspace policy
2. **Context-specific `AGENTS.md`** (`repos/<name>/AGENTS.md`): Git workflow rules (clone, branch, PR, sync)
3. **Project-specific `AGENTS.md`** (`projects/<name>/AGENTS.md`): project workspace rules
4. **Mount-aware prompt assembly:** when a mount is active, the prompt assembler includes the mounted project's `AGENTS.md` layers

The existing `AgentsMdResolver` already traverses from descriptor root to the active runtime target path. The mount-aware extension would add the mount target's `AGENTS.md` hierarchy when a mount is active.

## Trade-offs

| Trade-off | Option A (Convention) | Option B (New Kind) |
|---|---|---|
| Architecture risk | None — closed model preserved | High — new enum value, migration, API expansion |
| Delivery speed | Fast — incremental UI + metadata | Slow — architectural change gates everything |
| API surface | No new routes needed | New `/api/workspace/project/...` family |
| Migration | No schema change | New migration resource (010) |
| Future-proofing | Adequate — project identity via metadata | Strong — first-class project identity |
| User need match | High — everything modeled as agent-owned files | Medium — indirection between project identity and agent mount |
| Testing surface | Focused — UI + prompt assembly | Broad — descriptor, API, execution, migration |
| Recommendation | **Yes — start here** | Defer to horizon/deferred until proven need |

### Git: Process-backed vs JGit Library

| Factor | JGit (Java library) | Process-backed (git CLI) |
|---|---|---|
| Deployment | Requires JGit dependency | Requires `git` on host |
| Confinement | Pure Java, easier to sandbox | Must be confined via `shell_exec` (fail-closed per `DECISION-20260720-04`) |
| Feature surface | Full Git API | Relies on CLI output parsing |
| Risk | Library CVEs, classpath | Shell injection, process escape |
| Recommendation | JGit for structured operations | CLI for complex operations (rebase, merge) with strict argument validation |

Given `DECISION-20260720-04` (process execution fails closed until confinement is proven), JGit is the safer starting point for structured Git operations. A dedicated `GitService` wrapping JGit can provide clone, branch, status, pull, push, and diff operations without crossing the process boundary.

## Open Questions

1. **Project workspace identity:** Should a project workspace have its own `PROJECT` kind eventually, or is the convention-based approach sufficient indefinitely? The user's prompt describes project workspaces as distinct from agent workspaces, but the current architecture deliberately removed project roots. Revisit only after the convention approach is proven.

2. **Cross-agent repo sharing:** If two agents work on the same Git project, should they share a clone or each have their own? The user says "each agent clones the repo." This implies agent-local clones under `repos/`. If sharing is needed later, a shared bare-repo + per-agent worktree pattern is possible but adds complexity.

3. **PR agent identity:** Is the PR auto-approval/merge agent a dedicated `AgentProfile` with restricted tools, or a system service with no model involvement? A dedicated profile gives flexibility (the PR agent can intelligently review diffs) but adds trust concerns. A service is deterministic but inflexible. The user's prompt implies "a designated PR agent who merges and fixes conflicts" — this leans toward a dedicated agent profile.

4. **Workspace root enforcement:** The user says agents "cannot edit the root directory itself; they can only operate within the children folders." This is filesystem-policy enforcement. `WorkspaceExplorerService` already rejects root mutations and `outputs/` mutations. Extending this to reject create/delete/rename at root (only allowing operations inside named subdirectories) is additive. For prompt-level enforcement (the model itself), `AGENTS.md` instructions + tool confinement should be sufficient if the tool service rejects root-targeting paths.

5. **WORKSPACE.md placement:** For git repos, where does the WORKSPACE.md live? Options: (a) at `repos/<name>/WORKSPACE.md` directing agents to clone from a URL, (b) in the project workspace at `projects/<name>/WORKSPACE.md` referencing the repo. Option (b) makes more sense — the project workspace holds the WORKSPACE.md, and when the agent mounts the project, the WORKSPACE.md tells them to clone if they don't already have the repo under `repos/`.

6. **Mount lifecycle:** When an agent is assigned work in a project, who creates the mount? The assignment payload could include a `projectWorkspace` field that triggers mount creation. Or a REST endpoint `/api/agents/{agentId}/mounts` for explicit mount management. The assignment-based approach integrates with the existing `AssignmentRequest` → `TaskRunLifecycleService` flow.

7. **System chat context:** Should the system chat agent have visibility into the full workspace or just the management surface? The user says "cannot edit files but can interact with the work queue and workspace management." This implies read-only file access + write access to queue/assignments — a tool-scoping question for the agent profile.

8. **Root directory creation:** Who creates the structured directories (skills/, scripts/, apps/, home/, repos/, projects/) in the agent workspace? Options: (a) on agent creation, (b) lazily on first access, (c) migration for existing agents. The current architecture avoids starter-file generation (`ARCH-20260719-01`). Lazy creation on first write preserves this philosophy. A `WorkspaceStructureService` could ensure directories exist on demand.

9. **File tab vs. dedicated tabs:** Should workspace browsing, skills, Git, and queue be separate tabs or composited into one rich workspace surface? The current two-column Files+Run layout works well. Adding a directory tree sidebar, skills chip indicators, and Git status in-place keeps the surface coherent without multiplying tabs. Separate tabs for Git and Skills make sense when the interaction depth warrants it.

10. **Existing `exec` and `outputs` tabs:** The current agent console has Exec (shell) and Outputs (artifact listing) tabs. Should these be preserved, merged, or replaced? The Exec tab could be relocated under a "Tools" tab alongside Git. The Outputs tab could remain or be folded into the Files tab's inspector.

## Recommended Next Step

**Start with Option A (Convention-Based Mount) in a single focused sprint:**

### Sprint Scope: "Agent Workspace Structure and Browser"

**In scope:**
1. **Structured directory enforcement** — `WorkspaceStructureService` that lazily ensures `skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/` exist under the agent workspace root; root-level mutation guard in `WorkspaceExplorerService`
2. **Directory-tree sidebar** — New SimplyPages component in `WorkspaceExplorerFragments` that renders the structured root entries as a narrow navigation pane on the left of the Files tab (adds to the existing two-column layout → three-column: tree | file list+inspector | run groups)
3. **Skills chip indicators** — In the file list, rows under `skills/` that are `.md` files show a "Skill" chip; inspector shows skill metadata (name, description from frontmatter)
4. **`.workspace/` metadata directory** — Application-owned, hidden from the file list (filtered), holds `mount.json` for active project mount state
5. **Empty states** — Per-directory empty messages ("No skills installed. Add `.md` files...", "No git repositories cloned.")
6. **System prompt assembly** — Extend `AgentsMdResolver` to inject workspace structure context (list of available directories, current mount state) into the runtime prompt

**Out of scope (deferred to subsequent sprints):**
- Git tab and Git operations (deferred, needs GitService design)
- Full skills trust/activation from Files tab (compose existing SkillFragments, but trust actions stay in `/skills`)
- Project workspace creation and mount management UI
- System chat formalization (chat accordion exists, not formalized)
- Queue enhancements
- PR agent and merge automation

**Validation:**
- Focused Playwright screenshots: directory tree navigation, file list filtering by directory, skill chip rendering, root-level mutation rejection, mobile stacking
- Workspace structure service unit tests (lazy creation, root guard)
- Prompt assembly tests (structure injection, mount-aware layers)
- Bounded Spring startup

**Migration risk:** Near zero — no schema change, no new `WorkspaceKind`, no API route restructuring. All changes are additive to the existing Files tab and services.

**Artifacts:** Update `web.md` (Files tab composition), `services.md` (new `WorkspaceStructureService`), `architecture.md` (convention-based project model), `simplypages.md` (directory-tree component). Changlog entry documenting the Files tab evolution. Knowledge update for the workspace structure pattern.

**Subsequent sprint candidates (ordered by value/risk):**
1. Git tab + `GitService` (JGit-backed, clone/status/branch/pull)
2. Project mount lifecycle (`.workspace/mount.json` management, assignment-triggered mounts)
3. Queue enhancements (workspace context display, priority ordering, bulk actions)
4. System chat agent profile and dedicated tab
5. PR automation agent (workflow-triggered review/merge)
