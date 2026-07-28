## Prompt

Synthesize the four independent brainstorming reports generated during this workspace sprint into a unified analysis. Compare useful ideas, conflicts, omissions, feasibility, and trade-offs. Do not merely defend the first answer — evaluate where reports converge, where they disagree, what each missed, and what the combined evidence recommends for the safest, most deliverable path within the existing architectural constraints. Keep the broad control-plane and workflow-design lens. Do not edit the project.

## Source

Four independent brainstorming reports, each produced from the same sprint directive:

1. **Agent Workflow and Control-Plane Designer** — broad architectural compatibility, ownership boundaries, capability limits, state transitions, alignment with `TaskRunLifecycleService`, `AssignmentService`, and skill trust/activation contracts.

2. **Workspace Architecture Strategy** — detailed workspace architecture design, workspace-structure conventions, mount models, phased implementation plan with five concrete phases.

3. **Git Safety and Reliability** — end-to-end Git operating model, per-agent clone safety, branch/PR lifecycle, merge-agent responsibilities, conflict handling, failure recovery, process-confinement gating, and hard-vs-soft enforcement options.

4. **Product Surface and Incremental Delivery** — user-facing information architecture, SimplyPages HTMX-first composition, visual language continuity, staged UI sprints, validation strategy, and smallest viable scope.

All reports drew from the same authoritative sources: architecture specification (`ARCH-20260719-01`, `ARCH-20260720-01`, `ARCH-20260720-03`, `ARCH-20260526-03`), service graph, services specification, decisions register (`DECISION-20260719-02`, `DECISION-20260720-01` through `-06`), API/web/simplypages specifications, knowledge files, and live source code (`WorkspaceKind`, `WorkspaceDescriptor`, `WorkspacePathLayout`, `WorkspaceDirectoryService`, `SkillScopeDiscoveryService`, `WorkspaceExplorerFragments`, `OrchestrationController`, `AgentOperationalToolService`).

## Findings

### Convergence Points (All Four Reports Agree)

**1. `WorkspaceKind` must remain closed at `AGENT`, `CHAT`, `USER`.**

Every report independently concluded that adding a `PROJECT` kind would violate `DECISION-20260719-02` and create a cascade of architectural changes (migration 010, new descriptor resolution, API route families, execution selection rules, path layout factories). Report 1 quantified this as "directly conflicts with a governed architectural decision that was validated through phases 02-08." Report 2 called it "a material architectural decision that would require revising the decision." Report 3 noted it would "require a new migration, new path layout factories, new descriptor resolution paths." Report 4 recommended deferring it to "horizon/deferred until proven need."

**Recommendation strength:** Unanimous. No dissent. This is a hard constraint for any implementation plan.

**2. Project workspaces must be realized as a convention or metadata layer over existing workspace kinds, not as a new filesystem root class.**

All reports converged on treating "project workspace" as a naming convention, a subdirectory structure within an agent or user workspace, or a metadata pointer. Report 1 proposed metadata-only project context. Report 2 proposed structured agent workspace subdirectories with a `WorkspaceContext` DB record. Report 3 deferred the mount question but agreed it should follow the closed model. Report 4 proposed a `.workspace/mount.json` file inside the agent workspace.

**Recommendation strength:** Unanimous.

**3. The "mount" must be a metadata/resolver concept, not a filesystem link.**

All reports explicitly rejected symlinks as incompatible with the existing confinement model (`WorkspaceExplorerService` uses `LinkOption.NOFOLLOW_LINKS`; `WorkspaceDirectoryService` rejects symlink components). Report 1 identified three mount options and selected metadata pointer. Report 2 proposed `WorkspaceContextResolver` as a service-layer resolution mechanism. Report 3 called symlinks "a confinement regression" and instead proposed descriptor-chain or metadata-pointer approaches. Report 4 used `.workspace/mount.json`. All reject kernel-level mounts (bind mounts, OverlayFS) as requiring root or `CAP_SYS_ADMIN` — unavailable under process confinement gating.

**Recommendation strength:** Unanimous.

**4. Git integration must be a dedicated service, not raw `shell_exec` tool calls by the agent.**

All reports concluded that agents should not be expected to manage git workflows through ad-hoc shell commands. Report 1 proposed a `GitService` using JGit. Report 2 proposed `GitWorkspaceService` wrapping structured git operations. Report 3 proposed a dedicated git service layer with command validation. Report 4 proposed `GitService` wrapping JGit. The rationale spans safety (shell injection, git hook risk), observability (structured results vs. output parsing), and workflow integration (PR events, merge automation).

**Recommendation strength:** Unanimous on dedicated service. Divergence on implementation approach (see conflicts below).

**5. System prompts should leverage existing `AGENTS.md` layered resolution.**

All reports identified `DECISION-20260526-02` (runtime `AGENTS.md` layering from descriptor root to active runtime path) as the correct mechanism for injecting context-aware instructions. Report 1 proposed extending `PromptContextAssembler` with workspace-type-aware segments. Report 2 proposed context-specific injected segments from a configurable source. Report 3 described AGENTS.md layering plus WORKSPACE.md as a variant. Report 4 proposed extending `AgentsMdResolver` to inject workspace structure context.

**Recommendation strength:** Unanimous.

**6. The agent operational console should evolve incrementally from the existing Files tab.**

All reports agreed that the existing two-column Files + Run Groups layout is the right foundation. Report 4 provided the most detailed UI evolution plan (five sprints). Reports 1-2 described dashboard panels at a higher level. All agreed on: directory tree sidebar, skills chip indicators, root-level mutation guards, and Git controls in a dedicated tab.

**Recommendation strength:** Unanimous.

**7. System chat agent should be a restricted agent profile, not a new service.**

All reports identified the existing tool authorization system (`AgentToolAuthorizationService`, `ToolAccessPolicy`) as sufficient for creating a workspace-management agent with read-only file access, work queue management, and no file mutation tools. Report 1 called this "a restricted agent profile with operational tools only." Report 2 called it "a dedicated agent profile with a restricted tool set." Report 4 noted the existing chat accordion as a starting surface.

**Recommendation strength:** Unanimous.

**8. Skills explorer integration is low-risk and already partially supported.**

All reports recognized that `SkillScopeDiscoveryService` already discovers `.skills/*.md` from the effective AGENT/USER workspace, making the agent's `/skills` (or `.skills/`) directory a natural skill source. Report 1 noted that mapping the user-facing `skills/` to the system's `.skills/` convention is a minor path alignment. Report 2 noted that project-local skill discovery already exists in code but requires a trusted project-root authority. Report 4 proposed inline skill-chip indicators in the file list.

**Recommendation strength:** Unanimous.

### Points of Conflict (Where Reports Diverge)

**Conflict 1: Git service implementation — JGit library vs. wrapping `shell_exec`**

This is the most significant disagreement across reports.

- **Reports 1 and 4** recommend **JGit** (pure Java Git library): "A dedicated GitService using JGit would avoid shell process execution entirely, provide controlled operations, and integrate with workspace confinement naturally." Report 4 states "JGit is the safer starting point for structured Git operations."

- **Report 2** recommends **wrapping `shell_exec`**: "GitWorkspaceService wraps shell_exec but provides structured operations: typed return values, error classification, state tracking." It acknowledges the confinement gate but notes "chat-context shell execution (which is currently allowed) could run git commands."

- **Report 3** takes a nuanced middle position: JGit for structured operations, CLI for complex operations (rebase, merge), with strict argument validation. It also raises the critical git hook risk vector that neither Report 1 nor Report 2 discusses: "A `git clone` of an untrusted repo can execute hooks. Should Magenta inject `--config core.hooksPath=/dev/null`?"

**Analysis:** The disagreement hinges on what `DECISION-20260720-04` actually gates. Report 2 interprets it narrowly (shell execution is disabled only in *task-run contexts*, chat context is available). Reports 1, 3, and 4 interpret the spirit of the decision more broadly (process confinement is unproven; workspace-bound shell behavior is inherently risky regardless of context). Report 3's git hook analysis strengthens the broader interpretation — a `git clone` in chat context that pulls hooks into the workspace creates a persistent risk vector that survives the initial shell execution.

**Resolution recommendation:** Follow Report 3's hybrid approach: JGit for structured operations (clone, status, branch, diff) where the API provides safety; CLI for complex operations (rebase, merge) with mandatory safety wrappers (`--config core.hooksPath=/dev/null`, `GIT_CONFIG_NOSYSTEM=1`, URL validation). Gate all git execution behind the same capability check — do not assume chat context is safe for git operations just because `shell_exec` is currently allowed there.

**Conflict 2: Mount state storage — DB vs. filesystem**

- **Reports 1, 2, and 3** prefer **database-backed** mount state: "`WorkspaceContext` is a database row. It's atomically updatable, queryable, and can carry rich metadata." Report 2 specifically: "DB-backed is consistent with how other Magenta state is managed (assignments, task runs, skills, artifacts)."

- **Report 4** prefers **filesystem-backed** mount state: `.workspace/mount.json` file inside the agent workspace root.

**Analysis:** The database approach (Reports 1-3) wins on consistency grounds. Every other piece of Magenta state (assignments, task runs, skills, artifacts, run summaries, schedules, reactions) lives in SQLite. A filesystem JSON file would be an anomaly — fragile to concurrent access, hard to query, and would require separate migration and backup considerations. Report 4's filesystem preference appears to prioritize simplicity ("a metadata file") over architectural consistency.

**Resolution recommendation:** Database-backed `WorkspaceContext` (as described in Reports 1-2). The `.workspace/` directory could still exist for application-owned runtime state, but mount state authoritative source is the database.

**Conflict 3: Phase/sprint ordering**

- **Report 2** orders phases as: Foundation (structured directories) → WorkspaceContext → Git Integration → Dashboard Panels → PR Auto-Approval.

- **Report 4** orders sprints as: Directory Pane + Skills Inline → Git Tab → Queue Enhancements → System Chat → Mount + Prompt.

- **Report 1** recommends: structured subdirectories + metadata mount pointer + skills explorer first; defer git and system chat.

- **Report 3** recommends: define structural contract → add root immutability enforcement → design git service interface as spec → implement git safety wrappers → defer mount and merge agent.

**Analysis:** All reports agree on the first deliverable: structured agent workspace directories + root-level immutability enforcement. They diverge on what follows. Report 2 prioritizes the context/metadata infrastructure. Report 4 prioritizes the user-visible UI. Report 3 prioritizes safety wrappers before any git execution. Report 1 prioritizes the simplest complete feature.

**Resolution recommendation:** Synthesize as: **Phase 1 — Foundation** (structured directories, root immutability guard, directory tree sidebar, skills chip indicators) — this is the unanimous first step. **Phase 2 — Mount Model** (WorkspaceContext DB entity + resolver + prompt assembly extension) — the infrastructure needed before project workspaces mean anything. **Phase 3 — Git Safety** (GitService interface spec, safety wrappers, JGit-backed clone/status/branch) — no git execution until safety wrappers are in place. **Phase 4 — UI Depth** (Git tab, Queue enhancements, System Chat tab). **Phase 5 — Automation** (PR auto-approval agent, merge workflows).

**Conflict 4: Root-immutability enforcement — prompt vs. service layer**

- **Report 2** treats prompt-level (AGENTS.md text) as the primary mechanism, with service-layer rejection as "additive."
- **Report 3** explicitly ranks the layers: "The strongest approach that is available today without process confinement: service-layer rejection of root-relative writes targeting '' or '.' plus AGENTS.md language." It calls prompt-only the weakest approach.
- **Report 1** proposes service-layer root-write rejection in file tools combined with root-mutation rejection in `WorkspaceExplorerService`.
- **Report 4** treats it as a guard in `WorkspaceStructureService`.

**Analysis:** Report 3 provides the most rigorous analysis of enforcement tiers (OS filesystem → Application filesystem → Application policy → Tool argument validation). All reports agree service-layer rejection is necessary. The disagreement is whether prompt text alone (Report 2's framing) is sufficient — Reports 1 and 3 explicitly say it is not: "An agent determined to write to root could do so via shell_exec or file_write if no service-layer check exists."

**Resolution recommendation:** Service-layer rejection as the hard enforcement (reject any file_write/file_append/file_replace/file_delete targeting the workspace root path or `.`). AGENTS.md text as guidance. This follows Report 3's tiered approach with Report 1's specific tool guards.

### Omissions (What Each Report Missed That Another Caught)

| Topic | Caught By | Missed By | Importance |
|---|---|---|---|
| Git hook risk vector (`core.hooksPath`) | Report 3 | Reports 1, 2, 4 | **High** — a `git clone` silently executes hooks; this is a security vulnerability that must be mitigated before any git execution |
| Canonical task-run identity and lifecycle fence integration | Report 1 | Reports 2, 3, 4 | **Medium** — task execution and workspace context must not conflict on workspace selection and writer capability checks |
| Skill trust/activation contract alignment with workspace skills | Report 1 | Reports 2, 3, 4 | **Medium** — the existing immutable snapshot model means skills discovered in agent workspaces still require explicit trust before activation |
| SimplyPages component reuse patterns (directory tree, skill chips) | Report 4 | Reports 1, 2, 3 | **Medium** — the UI implementation strategy depends on existing component patterns that Reports 1-3 don't examine |
| Playwright validation strategy and visual critique requirements | Report 4 | Reports 1, 2, 3 | **Medium** — the project requires Playwright screenshots and visual quality critique for UI changes; this gates the UI delivery |
| Failure recovery scenarios (stale clone, PR conflict, agent termination) | Report 3 | Reports 1, 2, 4 | **Medium** — operational robustness requires explicit failure modes; Reports 1, 2, and 4 describe happy paths |
| `DECISION-20260720-04` confinement gate implications for git | Report 3 | Reports 1, 2, 4 | **High** — Report 1 mentions it briefly; Report 2 assumes chat context is available; only Report 3 analyzes that chat-context git execution still creates persistent hook risk |
| Execution workspace selection rules (`workspaceType`/`workspaceKey`) | Report 1 | Reports 2, 3, 4 | **Low** — the mount model doesn't change execution selection rules |
| Ordered migration ownership (resources 001-009) | Report 1 | Reports 2, 3, 4 | **Low** — no new migration needed for convention-based approach |
| Concrete API route design for workspace context management | Report 2 | Reports 1, 3, 4 | **Low** — `POST /api/agents/{agentId}/workspace-context` is a minor route addition |

### Feasibility Assessment

**High feasibility (low risk, compatible with current architecture):**

1. **Structured agent workspace directories** — Adding reserved child constants to `WorkspacePathLayout` and convenience methods to `WorkspaceDirectoryService` is additive. No schema change, no `WorkspaceKind` change, no API route restructuring. Reports 1-4 all agree this is the right starting point.

2. **Root-level immutability guard** — Extending `WorkspaceExplorerService`'s existing root-mutation rejection to also reject `file_write` targeting root paths is a small validation addition. Reports 1, 3, and 4 agree on service-layer enforcement.

3. **Directory tree sidebar in Files tab** — A new SimplyPages component in `WorkspaceExplorerFragments` that renders the structured root entries. Follows existing HTMX-first fragment composition. Report 4 details the UI approach.

4. **Skills chip indicators in file list** — Adding a "Skill" chip to `.md` files under the skills directory is a rendering change in existing fragments. Compatible with existing `SkillScopeDiscoveryService`.

5. **System prompt context injection** — Extending `PromptContextAssembler` or `AgentsMdResolver` to inject workspace structure context is additive to existing prompt assembly. Uses the same mechanism as existing `AGENTS.md` layering.

**Medium feasibility (new service surface, requires design approval):**

6. **`WorkspaceContext` DB entity** — New table, repository, service, and resolver. Reports 1-2 agree on the design. Requires a new migration if a table is added, or can live as a simple key-value row in an existing configuration store. Medium risk because it introduces a new concept that tools, prompts, and explorers must consume.

7. **`GitService` with JGit** — New dependency (JGit), new service, new error modes, new state tracking. Report 3 provides the most thorough risk analysis. Medium feasibility because JGit is mature but adds compile-time and runtime surface area. Credential management is an unresolved sub-problem.

8. **Git tab in agent console** — New HTMX surface, new REST endpoints. Follows existing tab pattern. Medium feasibility because it depends on `GitService` being operational.

**Low feasibility (high risk, requires architectural decision or capability that does not exist):**

9. **PR auto-approval/merge agent** — Depends on Git operations being available, PR creation events being publishable, and the merge agent having a defined lifecycle. Reports 1-4 all defer this to later phases. Report 3 notes the confinement gate applies to merge operations too.

10. **Cross-agent repo sharing** — Requires either a shared clone directory (concurrency problems) or a bare-repo + worktree pattern (complexity). All reports recommend per-agent clones for the initial implementation.

11. **Executable skill authorization for project skills** — Currently fail-closed (`DECISION-20260720-04`). Reports 1 and 2 note this is gated on process confinement being proven.

### Trade-off Synthesis

**The fundamental trade-off across all four reports is: local simplicity vs. shared identity.**

If every project workspace is a subdirectory inside an agent workspace (Reports 1, 2, 4), the model is simple: one root, one descriptor, one set of confinement rules. But project workspaces have no independent identity — they cannot be addressed outside their owning agent, they cannot be shared across agents, and they cannot have their own API routes.

If project workspaces are a new `WorkspaceKind` (explored but not recommended by any report), they gain independent identity at the cost of architectural upheaval.

The synthesis recommends accepting the local-simplicity trade-off for the initial delivery and revisiting only if concrete use cases for shared project identity emerge. This aligns with the project's "smallest complete thing" policy.

**The secondary trade-off: JGit safety vs. git CLI completeness.**

JGit provides safety (no shell, no process, no hook execution) but is a substantial dependency and doesn't cover every git operation. The git CLI covers everything but requires process execution and hook-risk mitigation.

Report 3's hybrid approach resolves this: JGit for the structured, high-frequency operations (clone, status, branch, diff, log, add, commit); CLI behind safety wrappers for complex operations (rebase, merge, push with force options). The safety wrappers (`core.hooksPath=/dev/null`, `GIT_CONFIG_NOSYSTEM=1`) are mandatory before any CLI execution — they are low effort and high impact.

## Options

### Option 1: Unified Minimum Viable Phase (Reports 1 + 4 Consensus)

Implement the intersection of what all reports agree on:

1. Structured agent workspace directories (reserved names in `WorkspacePathLayout`)
2. Root-level immutability guard (service-layer rejection in file tools)
3. Directory tree sidebar in Files tab (SimplyPages component)
4. Skills chip indicators in file list
5. Workspace structure injection into system prompts

**Scope:** Tight. Delivers a visible workspace browser experience without any git, mount, or project infrastructure.

**Value:** Agents immediately see their structured workspace; the Files tab gains navigation; skills are visible inline. The foundation is laid for mount and git in subsequent phases.

**Risk:** Near zero. No schema change, no new `WorkspaceKind`, no process execution, no API restructuring.

### Option 2: Full Context Infrastructure First (Reports 2 + 3 Consensus)

Build the `WorkspaceContext` infrastructure before any UI changes:

1. `WorkspaceContext` DB entity + repository + service + resolver
2. `WorkspaceContext` management API (`POST /api/agents/{agentId}/workspace-context`)
3. `PromptContextAssembler` extension for context-specific injection
4. Structured agent workspace directories
5. Root-level immutability guard

**Scope:** Medium. Adds a new domain concept and persistence but defers all git and UI work.

**Value:** The mount/context model is operational; agents can have an active project context; prompts adapt to context type. But there's no visible UI yet.

**Risk:** Medium. New DB entity, new service dependency for tools/prompts. Without git integration, PROJECT and GIT context types are stubs.

### Option 3: Git First (Report 3 Emphasis)

Prioritize Git safety infrastructure before workspace browser or context:

1. `GitService` interface design + spec
2. JGit-backed structured operations (clone, status, branch)
3. Git safety wrappers (`core.hooksPath=/dev/null`, URL validation)
4. `git_repo_states` table for clone tracking
5. Git tab in agent console with clone, status, branch controls

**Scope:** Medium-large. New library dependency, new service, new UI tab.

**Value:** Agents can clone and manage repos. The highest-risk area gets safety mitigations first.

**Risk:** Medium-high. JGit dependency, credential management unresolved, process confinement question unresolved for CLI operations. Without the workspace context model, git repos exist in isolation.

### Option 4: Unified Phased Delivery (Synthesis Recommendation)

Combine the consensus into a phased plan ordered by architectural dependency:

**Phase 1 — Workspace Foundation (Week 1-2)**
Exactly Option 1 above: structured directories, root guard, directory tree sidebar, skills chips, prompt injection. This establishes the workspace structure that everything else builds on.

**Phase 2 — Context Model (Week 3-4)**
`WorkspaceContext` DB entity and service. Mount metadata. Context-aware prompt assembly. Context selector in dashboard. Project context type (NONE/PROJECT/GIT). This makes the "mount" concept real at the service layer.

**Phase 3 — Git Safety and Basics (Week 5-7)**
`GitService` interface + JGit-backed operations. Safety wrappers. `git_repo_states` table. Git tab with clone, status, branch. No PR/merge yet. This adds git capability behind safety barriers.

**Phase 4 — Dashboard Depth (Week 8-10)**
Queue enhancements (workspace context display, priority). System Chat tab (restricted agent profile). Full skills explorer panel. Work queue management from system chat. This makes the dashboard a complete operational surface.

**Phase 5 — Automation (Week 11+)**
PR auto-approval agent. Merge workflows. Conflict detection + notification. This adds autonomous git workflow management.

Each phase is independently deliverable and validated. Phases 2-5 each require Phase 1 but are otherwise loosely coupled.

## Trade-offs

### Phase ordering: Foundation-first vs. Context-first vs. Git-first

| Criterion | Foundation-First (Option 1) | Context-First (Option 2) | Git-First (Option 3) | Unified Phased (Option 4) |
|---|---|---|---|---|
| Immediate visible value | High — workspace browser | Low — invisible infrastructure | Medium — git tab | High in Phase 1, then cumulative |
| Architectural risk | Near zero | Medium — new domain concept | Medium-high — unproven git surface | Phased risk; Phase 1 is zero-risk |
| Unblocks subsequent work | Everything | Mount-based features | PR/merge automation | Yes — ordered by dependency |
| Validation surface | Focused UI tests | DB + service + UI | Library + service + UI + safety | Incremental at each phase |
| Reversibility | Fully reversible | Reversible with DB migration | JGit dependency is sticky | Phases 1-2 fully reversible; Phase 3 dependency is sticky |
| Recommendation | **Necessary but insufficient alone** | **Valuable but invisible alone** | **Premature without foundation** | **Yes — the right path** |

### JGit vs. git CLI: the definitive trade-off

| Factor | JGit | git CLI | Hybrid (Report 3) |
|---|---|---|---|
| Process execution | None | Required | Required for complex ops only |
| Hook risk | None | Present (`core.hooksPath` must be set) | Present for CLI ops; mitigated by safety wrappers |
| Feature coverage | Clone, status, diff, log, add, commit, branch, tag, push (limited), fetch | Complete | JGit for structured ops; CLI for rebase, merge, complex push |
| Credential handling | Built-in (SSH, HTTP) | Relies on git credential helpers | Both paths need credential management |
| Error handling | Java exceptions, structured | Exit codes + output parsing | Structured for JGit ops; parsed for CLI ops |
| Dependency weight | ~3MB JAR + dependencies | Requires `git` on host PATH | Both required |
| Recommendation | **Start here** | **Defer for complex ops** | **Yes — matches Report 3** |

### Mount state: DB vs. filesystem

| Factor | DB (Reports 1, 2, 3) | Filesystem (Report 4) |
|---|---|---|
| Consistency with Magenta state | High — all state is in SQLite | Low — anomaly |
| Atomic updates | Yes (transactions) | No (file writes are not atomic) |
| Queryability | High (SQL) | None (file parsing required) |
| Visibility to agent | Hidden (tool-accessible only) | Visible in `.workspace/` |
| Backup/migration | Covered by existing SQLite backup | Separate file backup |
| Recommendation | **Yes** | Only as a cache/reflection of DB state |

## Open Questions

1. **Process confinement roadmap:** When does Phase 07 deliver process confinement? The answer determines whether git CLI operations can move from chat-context-only to full task-run support. Until then, git operations should be treated as capability-gated with the same fail-closed posture as `shell_exec` in task contexts.

2. **Credential management for git:** Where do git credentials live? The existing `AiConfig` / `RuntimeSettingsService` pattern could hold tokens, but git tokens are high-value secrets. Should they be agent-config-level, project-level, or system-level? Should they be stored in the database, environment variables, or a dedicated secrets file? None of the reports answer this concretely.

3. **`skills/` vs. `.skills/` path convention:** Report 2 identifies this as an alignment question. The user wants `/skills` (without dot prefix). `SkillScopeDiscoveryService` looks for `.skills/` (with dot prefix). Should the service be updated to scan both, should the directory be created as `.skills/` with `skills/` as a display alias, or should the AGENTS.md prompt map between them?

4. **Project workspace lifecycle for non-git projects:** If a project is not a git repo, how does its content get into the agent's workspace? Report 2 proposes agent-local copies with a sync tool. Report 4 proposes assignment-triggered mount creation. Should project content be pushed to the agent workspace on assignment, pulled by the agent on mount, or shared via a different mechanism?

5. **System chat agent's exact tool surface:** Reports agree on "read-only file access + work queue management + no file mutation." But what about: `shell_exec`? Git operations? Skill activation? Schedule management? The exact tool authorization boundary needs specification before implementation.

6. **Cross-agent visibility:** The user mentions "task queues showing tasks/agents" — implying visibility into what other agents are working on. The current assignment system is agent-scoped (`DECISION-20260526-02` agent-scoped lifecycle). Should the work queue panel show only the logged-in agent's work, or cross-agent work? The current open-alpha posture has no authentication, so "other agents" is a future concern.

7. **WORKSPACE.md precedence and scope:** When both AGENTS.md and WORKSPACE.md exist, which takes precedence for conflicting instructions? Report 3 suggests treating WORKSPACE.md as a specialized AGENTS.md variant. Report 2 suggests WORKSPACE.md lives in the project workspace directory and is injected when that project is mounted.

8. **`/home` and `/apps` directories:** Are these aspirational or immediately needed? Reports 2 and 4 include them in the structured layout but no report defines concrete behaviors for them. If they are aspirational, they should be deferred to `horizon-ideas.md` rather than implemented as empty directories.

9. **Existing Exec and Outputs tabs:** Report 4 asks whether these should be preserved, merged, or replaced. The Exec tab (shell) is particularly relevant — if git operations use the same shell execution path, the Exec tab and Git tab should be visually and functionally related.

10. **Validation strategy for multi-phase delivery:** Report 4 specifies Playwright screenshots and visual critique. Reports 1-3 specify service tests and bounded startup. A unified validation strategy across all phases should be documented: what gets Playwright-validated at each phase, what gets service-tested, and what integration tests bridge the phases.

## Recommended Next Step

**Adopt the Unified Phased Delivery plan (Option 4) with the following immediate actions:**

### This Sprint: Record Decisions and Create the Plan Artifact

1. **Create `.internal-dev/plans/workspace-sprint/`** with this synthesis as the concept document and a phase-01 implementation plan.

2. **Record these design decisions in `decisions.md`:**
   - `WorkspaceKind` remains closed at `AGENT`, `CHAT`, `USER` (affirms `DECISION-20260719-02`)
   - Project workspaces are realized as structured agent-workspace subdirectories with metadata context
   - Mount semantics are resolver-based (database `WorkspaceContext`), not filesystem links
   - Git operations use JGit for structured operations + CLI with mandatory safety wrappers for complex operations
   - Root-level immutability is enforced at the service layer, not via prompt text alone
   - System chat is a restricted agent profile with workspace-management tool authorization

3. **Resolve the `skills/` vs. `.skills/` path question** with a concrete decision: either align on `.skills/` internally (with UI display as "Skills") or update `SkillScopeDiscoveryService` to scan both paths.

4. **Defer `/home` and `/apps` to `horizon-ideas.md`** with a note that they are aspirational directory slots requiring concrete use cases before implementation.

### Phase 1 Implementation: Workspace Foundation

5. **Extend `WorkspacePathLayout`** with reserved agent-workspace child constants (`SKILLS_DIR`, `SCRIPTS`, `APPS`, `HOME`, `REPOS`, `PROJECTS`) and factory methods. Keep `skills/` as the user-facing name, map to `.skills/` internally for `SkillScopeDiscoveryService` compatibility.

6. **Add root-level immutability guard** in file tools and `WorkspaceExplorerService`: reject any `file_write`/`file_append`/`file_replace`/`file_delete` targeting the workspace root path (empty string, `"."`, or the exact root).

7. **Build directory-tree sidebar** as a SimplyPages component in `WorkspaceExplorerFragments`, rendering the structured root entries as a navigation pane. Use HTMX fragment swaps for navigation.

8. **Add skills chip indicators** in the file list for `.md` files under the skills directory. Compose existing skill metadata from `SkillScopeDiscoveryService` for the chip content.

9. **Extend system prompt assembly** to inject workspace structure context (list of available directories, their purposes) into the runtime prompt.

10. **Validate** with focused Playwright screenshots (directory navigation, skill chip rendering, root-mutation rejection), service tests (lazy directory creation, root guard), and bounded Spring startup. Follow the project's validation policy: use model `gpt-5.3-codex` with reasoning effort `medium` for Playwright, execute on a subagent.

### Before Any Git Execution Ships

11. **Create `.internal-dev/knowledge/git-safety-and-reliability.md`** capturing the hook-risk vector (`core.hooksPath=/dev/null`, `GIT_CONFIG_NOSYSTEM=1`), URL validation pattern, concurrency model, and failure-recovery checklist from Report 3.

12. **Design the `GitService` interface as a spec first** — method signatures, return types, error classification, state tracking — before writing any implementation. Get the contract right before touching JGit or git CLI.

### Deferred to Horizon/Deferred

- PR auto-approval/merge agent (Phase 5)
- `/home` and `/apps` directory behaviors (aspirational)
- `PROJECT` as a fourth `WorkspaceKind` (revisit only if convention-based model proves insufficient)
- Executable skill authorization for project skills (gated on process confinement)
- Cross-agent repo sharing (gated on multi-agent concurrency needs)
