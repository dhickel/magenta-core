
## Prompt

Cross-report comparison and synthesis: review the Git Safety & Reliability findings against the Workspace Architecture Strategy, Agent Workflow Integration, and Product Surface & Incremental Delivery reports. Identify useful ideas, conflicts, omissions, feasibility concerns, cross-cutting trade-offs, updated open questions, and a synthesized recommended next step.

## Source

Four prior brainstorming reports and the project's living specifications:

1. **Git Safety & Reliability** (this worker's first output) — confinement gate, git hook risks, root immutability enforcement, merge-agent failure recovery, observability
2. **Workspace Architecture Strategy** — WorkspaceKind closedness, project-as-metadata, symlink prohibition, structured agent roots, WorkspaceContext DB entity, Option E (Hybrid) recommendation
3. **Agent Workflow Integration** — assignment lifecycle, skill trust/activation, system chat agent model, PR merge agent lifecycle, five architectural decisions needing formal treatment
4. **Product Surface & Incremental Delivery** — Files tab UI evolution, SimplyPages composition, HTMX patterns, sprint scoping, Option A (Convention-Based Mount) recommendation

Plus: `architecture.md`, `services.md`, `service-graph.md`, `decisions.md`, `api.md`, `web.md`, `simplypages.md`, `root-relative-workspace-storage.md`, `agent-skills-ui-htmx-pattern.md`, `shell-tool-confinement-pattern.md`, and source code for `WorkspacePathLayout.java`, `WorkspaceDirectoryService.java`, `AgentShellToolService.java`, `SkillScopeDiscoveryService.java`, and `OrchestrationController.java`.

## Findings

### 1. Consensus Zone: What All Four Reports Agree On

**Keep `WorkspaceKind` closed.** No report recommends adding a `PROJECT` workspace kind in the initial sprint. All four converge on either convention-based subdirectories under the existing AGENT root, or a metadata pointer (WorkspaceContext) that preserves the closed three-kind model. This is a strong signal — the architectural decision recorded in `DECISION-20260719-02` should remain undisturbed for this sprint.

**Metadata-based mounting, not filesystem.** All four reports reject symlinks as incompatible with the existing confinement model. The mount mechanism must be a metadata pointer (DB row, JSON file, or in-memory resolution) that prompts and tools consult — not a filesystem artifact that the explorer would need to defend against.

**Incremental delivery over big-bang.** Workspace Architecture proposes phases 1-5; Agent Workflow demands formal decision proposals before implementation; Product Surface maps sprints 1-5 on the Files tab; Git Safety proposes phases 0-4 with safety wrappers first. All agree: structured agent workspace subdirectories come first, git integration later, merge agent last.

**Root immutability needs service-layer enforcement.** All reports agree that prompt guidance alone is insufficient for "agent cannot edit root directory." Workspace Architecture proposes extending the existing `WorkspaceExplorerService` root-mutation rejection. Git Safety maps the enforcement spectrum and recommends service-layer rejection of `""` and `"."` paths. Product Surface includes root-level mutation guards in sprint 1.

**Skills explorer reuses existing infrastructure.** All reports recognize that `SkillScopeDiscoveryService` already discovers from AGENT/USER `.skills/` directories and that the `/skills` control plane already has HTMX fragments. The work is integration, not invention.

### 2. Conflict Zone: Where Reports Disagree

#### Conflict 1: Git Execution Mechanism — Shell vs. JGit

This is the deepest and most consequential disagreement across the four reports.

| Report | Position | Rationale |
|--------|----------|-----------|
| **Git Safety** | Shell with safety wrappers first, JGit API later (Phase 3) | Shell is the existing tool surface; add `--config core.hooksPath=/dev/null` wrappers; JGit adds a new dependency and API surface |
| **Workspace Architecture** | Shell via `GitWorkspaceService` wrapping `shell_exec` | GitWorkspaceService provides structured operations over shell; manageable risk with validated args |
| **Agent Workflow** | **JGit** — avoid shell execution entirely | "The safer path. It aligns with the architecture's capability-gated approach and avoids the shell confinement problem entirely." Process execution is fail-closed by `DECISION-20260720-04`. |
| **Product Surface** | JGit for structured operations, CLI for complex | "Given DECISION-20260720-04 (process execution fails closed until confinement is proven), JGit is the safer starting point." |

**Analysis:** The Git Safety report's position is the weakest here. It acknowledges the confinement gate (`DECISION-20260720-04`) but then recommends shell execution with safety wrappers as Phase 1, which directly contradicts the gate. Agent Workflow and Product Surface correctly identify that crossing the process boundary for git operations is architecturally incompatible with the current fail-closed posture. JGit avoids the process boundary entirely — it operates within the JVM, uses the same filesystem confinement rules already enforced by `WorkspaceExplorerService`, and doesn't need `shell_exec` at all.

**Resolution:** JGit is the correct starting point. The Git Safety report's phased approach should be revised: JGit for clone, branch, status, commit, push in Phase 1; CLI for complex operations (interactive rebase, complex merge) only after Phase 07 confinement is proven. The safety wrappers (`core.hooksPath=/dev/null`, URL allowlist) still apply to JGit's transport layer.

#### Conflict 2: WorkspaceContext Storage — DB vs. Filesystem

| Report | Position |
|--------|----------|
| **Git Safety** | DB-backed (favors observability and queryability) |
| **Workspace Architecture** | DB-backed (`workspace_contexts` table with agent FK, contextType, targetRef, gitUrl) |
| **Agent Workflow** | DB or metadata pointer — agnostic |
| **Product Surface** | `.workspace/mount.json` file in the agent workspace root |

**Analysis:** This is a genuine conflict but a low-stakes one. DB-backed is superior for: atomic updates, concurrency control, history, and cross-service queryability. A JSON file is simpler to debug, doesn't need a migration, and follows the filesystem-first philosophy. The file approach has a subtle failure mode: an agent with `file_write` access to its workspace could corrupt or delete the mount file.

**Resolution:** DB-backed. Not because JSON files are inadequate, but because the workspace context is operational state that other services (prompt assembler, assignment service, git service) need to read consistently. A DB row with a `unique(agent_id, active)` constraint prevents multi-mount ambiguity. The migration is trivial (one new table). The risk of agent filesystem corruption of mount state is eliminated. However, the `.workspace/` directory should still exist as an application-owned metadata container for other uses (lock files, cache manifests).

#### Conflict 3: Git Service Implementation Depth — Full Service vs. Thin Wrapper

| Report | Position |
|--------|----------|
| **Git Safety** | Start with contract design (spec-first), then wrap shell |
| **Workspace Architecture** | Full `GitWorkspaceService` with structured operations, state tracking, event publishing |
| **Agent Workflow** | Dedicated `GitService` with JGit; full PR workflow integration |
| **Product Surface** | `GitService` with clone, branch, status, pull, push, diff; PR agent deferred |

**Analysis:** This is more of a scoping disagreement. Git Safety wants to design the contract first and implement later. Workspace Architecture wants a full service. Product Surface wants a minimal service with PR agent deferred.

**Resolution:** Product Surface's scoping is the most pragmatic. Phase 1 Git service: clone, branch, status, commit, push. All state tracked in `git_repo_states` table. Phase 2: PR creation, merge check. Phase 3: PR auto-approval agent. This incremental approach validates the Git service design before tackling the harder PR/merge automation.

#### Conflict 4: System Chat Agent — When to Build

| Report | Position |
|--------|----------|
| **Git Safety** | Not specifically addressed; root immutability and confinement are the focus |
| **Workspace Architecture** | Phase 4 of their plan (after foundation, context, git) |
| **Agent Workflow** | "Deferred capability" — maps to `DEFERRED-20260525-11` |
| **Product Surface** | Out of scope for initial sprint; deferred |

**Analysis:** This is actually consensus. System chat is recognized as valuable but not blocking the initial sprint. The mechanism is clear (restricted agent profile with operational tools only) and can be implemented as a standalone increment.

### 3. Useful Ideas From Each Report

#### From Git Safety (this worker's first report)

- **Git hook risk vector analysis**: The observation that `git clone` can execute hooks from `.git/hooks/` (post-checkout, pre-commit, etc.) and that this is a persistent risk even after the clone completes is not surfaced in any other report. The `--config core.hooksPath=/dev/null` and `GIT_CONFIG_NOSYSTEM=1` wrappers are concrete, low-effort safety measures that should be adopted regardless of whether the implementation uses JGit or CLI.
- **Git URL allowlist**: Validating remote URLs against a configured allowlist prevents agents from cloning arbitrary repositories. Other reports assume git URLs come from WORKSPACE.md or project metadata and don't address the case where an agent is instructed to clone a malicious URL.
- **`WorkspaceGitActionLogRepository`**: Extending the existing `WorkspaceFileActionLogRepository` pattern to git operations provides structured observability. No other report proposes an observability mechanism for git operations.
- **Stale clone detection**: The pre-push fetch+diff check to warn agents when master has advanced. This is a reliability concern not covered by other reports.
- **Failure recovery taxonomy**: Clone failure, push failure, PR conflict, merge failure, agent terminated — each with a concrete handling strategy. Other reports mention failure in passing but don't systematize it.

#### From Workspace Architecture

- **Contract change table with effort magnitudes**: The "What this does NOT change" section and the detailed contract change table with Small/Medium/Tiny effort estimates is the best scoping artifact across all four reports. It would make an excellent `.internal-dev/plans/` phase document.
- **`WorkspaceContext` DB schema**: The single-row-per-agent model (`contextType: NONE|PROJECT|GIT`, `targetRef`, `gitUrl`, `mountedAt`) is clean and minimal. This design should be adopted.
- **Reserved subdirectory names**: The explicit list (`SKILLS_DIR`, `SCRIPTS`, `APPS`, `HOME`, `REPOS`, `PROJECTS`) with `WorkspacePathLayout` constants and `WorkspaceDirectoryService` convenience methods is the right structural approach. It's additive and non-breaking.
- **Git repo state table**: Tracking clone state (`git_repo_states` with agent FK, repo name, clone URL, current branch, last sync, dirty flag) makes git operations observable and restart-safe. The Product Surface report mentions this in passing but Workspace Architecture defines it.
- **"Convention vs. Enforcement" framing**: The explicit trade-off between lazy directory creation (convention) and scaffolded directories (enforcement) is a design decision that needs to be made explicitly. Workspace Architecture recommends convention with root-level write prohibition.

#### From Agent Workflow Integration

- **Five architectural decision points requiring formal treatment**: This is the most important process insight. Before any implementation, decisions must be recorded on: workspace mount model, project workspace identity, git integration approach, system chat agent model, and PR auto-merge agent lifecycle. Other reports jump to recommendations without this process step.
- **`SkillScopeDiscoveryService` path alignment**: The observation that the user wants `/skills` (no dot prefix) but `SkillScopeDiscoveryService` discovers from `.skills/` is a concrete path-alignment detail missed by other reports. The recommendation to use prompt convention for now and align discovery later is pragmatic.
- **`DEFERRED-20260525-11` mapping**: Identifying that the system chat agent maps to an already-deferred capability gives it architectural standing — it's not a new idea, it's an activation of a deferred feature.
- **Cross-workspace skills scope expansion**: The note that "cross-workspace skills the agent can use anywhere" would require a scope expansion beyond what `SkillScopeDiscoveryService` currently supports is a non-obvious constraint.
- **Multi-agent coordination visibility**: The observation that "task queues showing tasks/agents" implies cross-agent visibility that currently doesn't exist is important for scoping the queue dashboard.

#### From Product Surface & Incremental Delivery

- **Concrete Files tab evolution plan**: The three-column layout (directory tree | file list + inspector | run groups), skills chip indicators, and per-directory empty states are the most concrete UI specifications across all reports. This can go directly into a SimplyPages component design.
- **Sprint scoping table**: The clear in-scope/out-of-scope list with validation requirements and migration risk assessment is directly usable as a plan phase.
- **Independent stable host pattern**: The reminder that operational panels (runs, skills, artifacts, git) should be independent stable hosts that never OOB-replace unrelated panels follows `DECISION-20260720-02` and is the correct SimplyPages composition pattern.
- **`.workspace/` metadata directory**: The concept of an application-owned hidden directory for mount state, lock files, and metadata that is filtered from the file list is a clean mechanism that other reports didn't specify.
- **Tab consolidation strategy**: The suggestion to merge the Exec tab under a "Tools" tab alongside Git, and potentially fold Outputs into the Files tab inspector, addresses the existing tab proliferation problem that other reports ignore.

### 4. Omissions: What Each Report Missed

#### Git Safety omissions

- **Skills discovery path alignment**: Didn't address the `.skills/` vs `/skills` naming tension. Agent Workflow caught this.
- **Concrete UI delivery plan**: Focused on backend safety and did not specify the Files tab UI evolution. Product Surface fills this gap completely.
- **WorkspaceContext design**: Mentioned DB-backed context but didn't design the schema or resolver. Workspace Architecture provides the detailed design.
- **Assignment lifecycle integration**: Didn't map how workspace context interacts with `TaskRunLifecycleService` and `AssignmentService`. Agent Workflow covers this.
- **SimplyPages composition patterns**: No mention of stable hosts, OOB swap targets, or the directory-tree sidebar. Product Surface covers the UI architecture.

#### Workspace Architecture omissions

- **Git hook risk**: No mention of git hook execution risk or safety wrappers. Git Safety covers this.
- **Shell confinement gate**: Acknowledges process execution but doesn't deeply engage with the `DECISION-20260720-04` fail-closed posture. Git Safety and Agent Workflow both flag this.
- **Git operation observability**: Mentions state tracking but doesn't propose action logging or dashboard surfacing. Git Safety provides the `WorkspaceGitActionLogRepository` pattern.
- **Failure recovery**: Describes the happy path for clone/branch/PR but doesn't address stale clones, push failures, merge conflicts, or agent termination. Git Safety covers these systematically.
- **Playwright validation strategy**: Doesn't specify how to validate the new UI surfaces. Product Surface includes validation checkpoints.

#### Agent Workflow omissions

- **UI delivery plan**: Focused on backend ownership boundaries with no UI specification. Product Surface fills this gap.
- **Git safety mechanisms**: No mention of hook risk, URL validation, or git operation confinement. Git Safety covers these.
- **Root immutability enforcement**: Mentions it as a requirement but doesn't specify the enforcement mechanism (service-layer rejection of root paths). Git Safety maps the enforcement spectrum.
- **Agent workspace subdirectory structure**: References it but doesn't specify the concrete directory names or `WorkspacePathLayout` changes. Workspace Architecture provides this.
- **Git observability**: No discussion of how git operations are logged, monitored, or surfaced. Git Safety proposes the action log pattern.

#### Product Surface omissions

- **Process confinement implications**: Doesn't address how `DECISION-20260720-04` gates the Git tab implementation. Git Safety and Agent Workflow both flag this as the primary constraint.
- **Git hook risk**: No mention of security implications of the Git tab. Git Safety covers this thoroughly.
- **Merge agent design**: Defers the PR agent entirely without specifying the event model, trust boundaries, or failure recovery. Git Safety and Agent Workflow both provide analysis.
- **WorkspaceContext design**: Uses `.workspace/mount.json` without comparing to DB-backed alternatives. Workspace Architecture provides the detailed DB schema.
- **Multi-agent coordination**: Doesn't address what happens when two agents work on the same Git repo. Git Safety raises concurrent git safety as an open question.

### 5. Cross-Cutting Trade-offs Spanning Multiple Reports

#### Trade-off 1: JGit Library vs. Process-Backed Git CLI

This spans all four reports and is the highest-impact unresolved decision.

| Dimension | JGit | CLI via shell_exec |
|-----------|------|---------------------|
| Confinement gate (`DECISION-20260720-04`) | **Passes** — no process boundary crossed | **Fails** — requires process execution in task-run context |
| Git hook risk | **Low** — JGit doesn't execute hooks by default | **High** — even with `core.hooksPath=/dev/null`, edge cases exist |
| Feature coverage | Limited — no interactive rebase, some merge strategies unavailable | Full — native git behavior |
| Dependency weight | ~3MB JGit + SLF4J bridge | None (git binary must be on host) |
| Structured output | Native — Java objects | Requires CLI output parsing |
| Credential management | SSH/HTTPS via JGit transport | Inherits host git config |
| Error handling | Java exceptions with typed causes | Exit codes + stderr parsing |
| Recommended for Phase 1 | **Yes** — clone, branch, status, commit, push, diff | **No** — blocked by confinement gate |

**Synthesis recommendation:** JGit for Phase 1 structured git operations. Add CLI-backed operations (interactive rebase, complex merge) only after Phase 07 confinement is proven. This is a reversal of the Git Safety report's original recommendation and an adoption of Agent Workflow's analysis.

#### Trade-off 2: DB vs. Filesystem for WorkspaceContext

This spans Workspace Architecture (DB) and Product Surface (JSON file).

| Dimension | DB (`workspace_contexts` table) | Filesystem (`.workspace/mount.json`) |
|-----------|--------------------------------|--------------------------------------|
| Atomic updates | Yes — transactional | No — file corruption possible |
| Cross-service reads | Clean — repository injection | Requires filesystem access |
| Agent corruption risk | **None** — agent tools don't touch DB | **Present** — agent could overwrite the file |
| Debugging | Requires SQL query | Direct file read |
| Migration | Schema migration needed | No migration |
| Alignment with architecture | Consistent with all other operational state | Consistent with filesystem-first philosophy |
| Recommended for Phase 1 | **Yes** | Use `.workspace/` for non-critical metadata only |

**Synthesis recommendation:** DB-backed for authoritative state (active context), with `.workspace/` as an application-owned directory for non-authoritative metadata (cache manifests, lock files). This follows the existing pattern where task-run lifecycle, assignment state, skill trust, and artifact metadata are all DB-backed.

#### Trade-off 3: Agent-Local vs. Shared Project Content

This spans Workspace Architecture and Agent Workflow.

| Dimension | Agent-local (each agent has own copy) | Shared root (single project directory) |
|-----------|--------------------------------------|---------------------------------------|
| Concurrency safety | Natural isolation | Requires lease/lock management |
| Architecture compatibility | Full — existing descriptor model | **Conflicts** with `DECISION-20260719-02` (removed project roots and leases) |
| Storage efficiency | N copies for N agents | 1 copy |
| Git integration | Each agent clones independently | Bare repo + worktrees or shared clone |
| Non-git project sharing | Requires explicit sync mechanism | Natural — single source of truth |
| Recommended | **Yes** for Phase 1 | Deferred — re-evaluate if multi-agent non-git collaboration becomes a concrete need |

**Synthesis recommendation:** Agent-local for all project content in Phase 1. This aligns with the architecture's removal of project roots and avoids reintroducing the lease/lock machinery. Git projects handle sharing naturally through PRs. Non-git project sharing between agents is a deferred feature.

#### Trade-off 4: Prompt-Only vs. Service-Layer vs. OS-Level Enforcement

This spans Git Safety and Workspace Architecture.

| Enforcement Layer | Root Immutability | Git Workflow Rules | Mount Path Scoping |
|-------------------|-------------------|--------------------|--------------------|
| Prompt/AGENTS.md | Advisory — agent may ignore | Advisory — agent may skip PR flow | Advisory — agent may access outside mount |
| Service-layer path validation | Strong — tool rejects root paths | Partial — can validate git directory scope | Strong — resolver remaps mount paths |
| OS-level (permissions, chroot) | **Unavailable** — process confinement unproven | **Unavailable** | **Unavailable** |

**Synthesis recommendation:** Service-layer for root immutability and mount scoping (hard enforcement in tool services). Prompt-level for git workflow rules (the agent needs flexibility; AGENTS.md guidance is appropriate). OS-level deferred until Phase 07.

### 6. Updated Open Questions

1. **JGit dependency acceptance**: Does adding JGit (~3MB, Apache 2.0 license) as a dependency require product owner approval? It's a new class of dependency (Java implementation of a system tool) not currently in the project.

2. **Git credential storage**: Even with JGit, pushing to remotes requires credentials. Where do they live? Options: (a) agent config (`AgentConfig.allowedGitRemotes` with tokens), (b) `~/.gitconfig` or `~/.ssh` on the host (but JGit may not read these), (c) a new `git_credentials` table (dangerous — tokens in DB). This is a security design question that no report fully addresses.

3. **`.skills/` vs `skills/` path alignment**: Should `WorkspacePathLayout` use `.skills/` (matching `SkillScopeDiscoveryService`) or `skills/` (matching the user's prompt)? If `.skills/`, the UI must display it as "Skills" but the filesystem path is `.skills/`. If `skills/`, `SkillScopeDiscoveryService` must be extended to scan both paths. Agent Workflow recommends prompt convention (agent uses `skills/`, system discovers `.skills/`). Is this acceptable or confusing?

4. **Agent workspace initialization on existing agents**: When the structured subdirectory model is deployed, existing agents have flat `workspace/agent/<id>/` roots with task-run children, outputs, and potentially arbitrary user files. How does the system handle this? Options: (a) lazy — directories appear as the agent creates them, (b) migration — create all reserved directories for existing agents, (c) hybrid — create `.workspace/` application directory on next agent access, others lazy. The current architecture avoids starter-file generation. This needs an explicit decision.

5. **Cross-agent git repo visibility**: The user wants "each agent's clone under the workspace" to be explorable. Does this mean Agent A can browse Agent B's `/repos`? The current Files tab is bound to a single `WorkspaceDescriptor` for the owning agent. Cross-agent browsing would break the descriptor model. Is this a hard requirement or a nice-to-have?

6. **WORKSPACE.md resolution precedence**: When both an `AGENTS.md` and a `WORKSPACE.md` exist in the same directory, which takes precedence? Should `WORKSPACE.md` be treated as an `AGENTS.md` variant (injected into prompt context) or as a machine-parseable config file consumed by `GitWorkspaceService`? The existing `AgentsMdResolver` only resolves files named `AGENTS.md`.

7. **Git operation timeout and cancellation**: Git clone of a large repository can take minutes. The existing shell tool has a 30-second max timeout. JGit operations don't have built-in timeouts. How are long-running git operations managed? Is there a cancellation path if the agent context is interrupted?

8. **PR auto-approval trust model scope**: If the merge agent has "elevated privileges" (write to main), how is this distinguished from normal agent capabilities in the existing `ToolAccessPolicy`? Should there be a new tool tier (READ, WRITE, GIT_WRITE, MERGE) or a separate agent authorization class?

9. **SimplyPages directory-tree component**: A collapsible directory-tree sidebar is a new SimplyPages component. Does it already exist in the SimplyPages library, or does it need to be built? If built, should it be contributed upstream to SimplyPages or remain Magenta-local? The existing `WorkspaceExplorerFragments` uses a flat file list, not a tree.

10. **Minimum viable Git tab**: What is the absolute minimum git functionality to be useful? Product Surface proposes clone, branch switcher, pull. But if the agent can already `shell_exec git clone` (in chat context, not task-run context), is a GUI Git tab adding value beyond what the model can do? The answer depends on whether the git workflow is primarily model-driven (agent clones/branches/PRs via tools) or human-driven (user manages repos through dashboard).

11. **Git action logging retention**: If `WorkspaceGitActionLogRepository` logs every git operation, what's the retention policy? The existing `WorkspaceFileActionLogRepository` appears to keep recent actions with no explicit retention. Git action logs could grow quickly with fetch/pull operations.

12. **JGit transport security**: Does JGit's HTTPS transport validate certificates by default? Does it support SSH with known_hosts validation? The git hook risk is eliminated by using JGit, but transport security (man-in-the-middle, malicious remotes) still needs consideration.

### 7. Synthesis: Unified Problem Map

Across the four reports and the directive, the workspace sprint decomposes into these layers:

| Layer | What | Owner Report | Status |
|-------|------|-------------|--------|
| **Filesystem structure** | Reserved agent workspace subdirectories (`skills/`, `scripts/`, `apps/`, `home/`, `repos/`, `projects/`), `.workspace/` metadata directory | Workspace Architecture + Product Surface | Design clear, needs `WorkspacePathLayout` constants |
| **Root immutability** | Service-layer rejection of root-targeting writes | Git Safety + Workspace Architecture | Design clear, additive to existing guards |
| **Mount mechanism** | WorkspaceContext DB entity + resolver | Workspace Architecture | Design clear, needs schema migration |
| **Context-aware prompts** | Extend `AgentsMdResolver` + `PromptContextAssembler` | Workspace Architecture + Agent Workflow | Design clear, extends existing resolution |
| **Skills explorer** | Inline skill chips in Files tab + reuse `/skills` fragments | Agent Workflow + Product Surface | Integration, not invention |
| **Git operations** | JGit-backed `GitService` with structured ops + action log | Git Safety (observability) + Agent Workflow (JGit choice) | **Design conflict resolved: JGit** |
| **Git safety** | Hook suppression, URL allowlist, stale clone detection | Git Safety | Adopted by all other reports |
| **Git dashboard** | Git tab in agent console (clone list, branch, status) | Product Surface | Deferred to post-foundation sprint |
| **System chat** | Restricted agent profile with operational tools | Agent Workflow | Deferred capability (`DEFERRED-20260525-11`) |
| **PR automation** | Event-driven merge agent with conflict detection | Git Safety + Agent Workflow | Deferred to post-git sprint |
| **Work queue** | Enhanced Queue tab with workspace context display | Agent Workflow + Product Surface | Incremental on existing Queue tab |
| **Failure recovery** | Stale clone detection, push failure, agent termination | Git Safety | Needs integration with `GitService` design |

## Options

### Option 1: Full Sprint — All Layers at Once (Not Recommended)

Implement filesystem structure, WorkspaceContext, GitService, Git tab, system chat, PR automation, and queue enhancements in one large sprint.

**Why not:** Violates the "smallest complete thing" policy. Crosses the confinement gate without resolution. Requires five architectural decisions to be made simultaneously. Would create a large, hard-to-validate changeset.

### Option 2: Sequenced Sprints — Foundation First (Recommended)

Three sequenced sprints, each independently shippable:

**Sprint A: Workspace Structure & Browser**
- `WorkspacePathLayout` reserved subdirectory constants
- Root-write prohibition in `AgentFileToolService` and `WorkspaceExplorerService`
- `WorkspaceStructureService` for lazy directory provisioning
- Files tab: directory-tree sidebar + skills chips + empty states
- `.workspace/` metadata directory (hidden from file list)
- Root-level `AGENTS.md` prompt injection for workspace structure
- **Does NOT include:** WorkspaceContext, git operations, system chat, PR agent
- **Gate:** No architectural decisions needed — all additive to existing contracts

**Sprint B: Workspace Context & Git Foundation**
- `WorkspaceContext` DB entity, repository, service, resolver
- Workspace context API (`/api/agents/{agentId}/workspace-context`)
- `PromptContextAssembler` context-aware segment injection
- `GitService` (JGit): clone, branch, status, commit, push
- `git_repo_states` table
- `WorkspaceGitActionLogRepository`
- Git safety: URL allowlist, hook suppression, credential config
- Git tab in agent console: clone list, branch switcher, status
- **Does NOT include:** PR operations, merge agent, system chat
- **Gate:** Resolve JGit dependency acceptance and credential storage design

**Sprint C: PR Automation & System Chat**
- PR creation via JGit or GitHub API
- PR merge check
- System chat agent profile (restricted tools)
- PR auto-approval agent (event-driven via `EventReactionService`)
- Conflict detection + notification (defer auto-resolution)
- Queue enhancements (workspace context display)
- **Gate:** Resolve merge agent trust model

### Option 3: Parallel Tracks (Higher Velocity, Higher Risk)

Split Sprint A into two parallel tracks:
- Track 1: Backend structure (PathLayout, StructureService, root guards, prompt assembly)
- Track 2: Frontend (Files tab directory tree, skills chips, empty states)

Then merge before Sprint B.

**Why not:** The frontend depends on the backend API for directory listing and path scoping. Parallel tracks would need a contract-first approach with mock endpoints. The increment is small enough that sequential is lower risk for minimal schedule cost.

## Trade-offs

### Sequencing: Git Before Mount vs. Mount Before Git

The Git Safety report originally placed git integration concerns as the primary gating factor. After cross-report review, the mount/context mechanism is actually a lighter-touch, lower-risk foundation:

- **Mount before Git**: Sprint A (workspace structure + browser) + Sprint B starts with WorkspaceContext. This gives agents a "current workspace" concept before Git operations exist. Git operations then have a natural scoping mechanism (clone into `/repos` under the agent workspace pointed to by the context).
- **Git before Mount**: Jumping to GitService without a context mechanism means agents have no structured way to know which project they're working on. The system prompt would need ad-hoc git workflow injection without context awareness.

**Recommendation:** Mount before Git. Sprint A establishes the workspace structure; Sprint B's first half establishes WorkspaceContext; Sprint B's second half adds GitService scoped by that context.

### Sprint A Scope: Minimal vs. Enriched Files Tab

| Dimension | Minimal (directory tree + empty states) | Enriched (+ skills chips, + root guard, + `.workspace/`) |
|-----------|----------------------------------------|----------------------------------------------------------|
| Effort | Low | Medium |
| User-visible value | Moderate — navigation improvement | High — skills integration, immutability enforcement |
| Risk | Very low | Low — all additive, no schema change |
| Recommendation | **Enriched** — the extra items are small and deliver high value |

### Git Tab: New Tab vs. Integrated into Files Tab vs. Under Tools Tab

| Approach | User Experience | Implementation | Recommendation |
|----------|----------------|----------------|----------------|
| New "Git" tab | Clear separation | New tab route, new fragment set, 14th tab in console | Best for dedicated git workflow surface |
| Integrated into Files tab | Single workspace surface | Git status rows embedded in file list under `/repos` | Insufficient for branch/PR operations |
| Under "Tools" tab alongside Exec | Consolidated tooling | Relocate Exec tab content, add Git sub-panel | Clean consolidation, reduces tab count |

**Recommendation:** "Tools" tab that consolidates Exec (shell) and Git. The Exec tab currently has limited functionality (shell is fail-closed in task contexts) so consolidating it into a broader Tools tab reduces clutter. If Git functionality grows beyond 3-4 operations, promote to its own tab.

## Open Questions

See Section 6 for the full list. The most blocking questions for Sprint A are:

- **Question 4** (existing agent workspace initialization)
- **Question 3** (`.skills/` vs `skills/` path alignment)
- **Question 9** (SimplyPages directory-tree component availability)

For Sprint B:

- **Question 1** (JGit dependency acceptance)
- **Question 2** (git credential storage)
- **Question 7** (git operation timeout and cancellation)

For Sprint C:

- **Question 8** (PR auto-approval trust model)
- **Question 5** (cross-agent git repo visibility)

## Recommended Next Step

**Execute Sprint A ("Workspace Structure & Browser") as the first implementation increment**, following this sequence:

### Before Any Code

1. **Record a decision** in `decisions.md` documenting the convention-based mount model (preserving `WorkspaceKind` closure) and the JGit-first git integration approach (preserving process-confinement gating). This addresses the five architectural decision points flagged by Agent Workflow.

2. **Resolve Question 3** (`.skills/` vs `skills/`): either rename the user-visible directory to `.skills/` with UI display name "Skills", or extend `SkillScopeDiscoveryService` to scan both paths. Present the trade-off to the user.

3. **Resolve Question 4** (existing agent initialization): choose lazy creation (consistent with current architecture).

4. **Check SimplyPages** for an existing directory-tree component. If absent, scope its creation as part of Sprint A.

### Sprint A Implementation

1. `WorkspacePathLayout`: Add reserved child constants (`SKILLS_DIR`, `SCRIPTS`, `APPS`, `HOME`, `REPOS`, `PROJECTS`, `WORKSPACE_META`).
2. `WorkspaceDirectoryService`: Add convenience resolution methods for reserved children from a descriptor.
3. `WorkspaceExplorerService`: Extend root-mutation rejection to explicitly reject writes targeting `""` or `"."`.
4. `AgentFileToolService`: Add root-path write rejection at the tool boundary.
5. `WorkspaceStructureService`: Lazy directory provisioning. Creates reserved directories on first access. Does not scaffold on agent creation.
6. `WorkspaceExplorerFragments`: Add directory-tree sidebar component. Add skills chip indicators. Add per-directory empty states. Ensure `.workspace/` is filtered from the file list.
7. `AgentsMdResolver` / `PromptContextAssembler`: Inject workspace structure context (available directories, current context state) into the agent system prompt.
8. **Validation**: Playwright screenshots of directory tree navigation, file list filtering, skill chip rendering, root-level mutation rejection, empty states, mobile stacking. Service unit tests for lazy creation, root guard, and structure service. Bounded Spring startup with existing agents.

### Sprint A Deliverables

- Agent workspace has structured directories visible in the Files tab
- Root directory is immutable through all tool paths
- `.skills/*.md` files show skill chips in the file browser
- Agent system prompt includes workspace structure guidance
- No database migrations (Sprint A is filesystem and UI only)
- All existing tests pass; new focused tests cover the additive behavior

### Post-Sprint A Artifacts

- Update `web.md` (Files tab composition with directory tree)
- Update `services.md` (new `WorkspaceStructureService`)
- Update `architecture.md` (convention-based structured workspace)
- Update `simplypages.md` (directory-tree component if built)
- Update `decisions.md` (convention-based mount model decision)
- Create changelog entry
- Update `.internal-dev/knowledge/` with workspace-structure pattern
- Create Sprint B plan artifact (WorkspaceContext + GitService)
- **Do not commit to Sprint B scope until Sprint A is validated and user feedback is received**
