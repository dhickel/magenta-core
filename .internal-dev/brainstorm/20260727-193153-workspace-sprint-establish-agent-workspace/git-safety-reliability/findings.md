
## Prompt

Explore robust end-to-end Git operating models for per-agent clones, branch and PR lifecycles, merge-agent responsibilities, conflict handling, resynchronization, failure recovery, and observability. Examine where policy prompts are sufficient versus where application, tool, filesystem, or provider-level enforcement is needed, including root immutability and confinement risks. Sources and constraints: the project's closed three-kind workspace architecture (`WorkspaceKind` = AGENT, CHAT, USER), the sole-path-authority `WorkspacePathLayout`, sole-descriptor-constructor `WorkspaceDirectoryService`, process-confinement-unavailable gating (`DECISION-20260720-04`), existing `shell_exec`, file tools, AGENTS.md layered runtime instruction injection, scoped Agent Skills with immutable snapshot/trust gating, SimplyPages HTMX-first UI, and the operational console at `/agents/{agentId}`.

## Source

The source consists of the full directive included in this sprint prompt — a detailed product vision for per-agent workspace structure, project workspaces, git repository workspaces, workspace features, git integration requirements, system prompts, and a skills explorer — plus the project's living specification and knowledge store.

Inspected sources:
- `.internal-dev/specifications/architecture.md` (ARCH-20260719-01, ARCH-20260720-01, ARCH-20260720-03, ARCH-20260526-02, ARCH-20260526-03)
- `.internal-dev/specifications/service-graph.md` (all active service edges, composite operational composition)
- `.internal-dev/specifications/services.md` (SVC-20260719-01 through SVC-20260719-06, SVC-20260720-01 through SVC-20260720-02, skill/artifact/runtime/automation services)
- `.internal-dev/specifications/decisions.md` (DECISION-20260719-02, DECISION-20260720-01 through 006, DECISION-20260526-02)
- `.internal-dev/specifications/api.md` (workspace identity routes, execution workspace selection, absent routes)
- `.internal-dev/specifications/web.md` (agent Files tab, operational surfaces, composition rules)
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- `.internal-dev/knowledge/shell-tool-confinement-pattern.md`
- `.internal-dev/knowledge/file-tool-workspace-scope-pattern.md`
- `.internal-dev/knowledge/agents-md-specification-reference.md`
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java`

## Findings

### 1. The Confinement Trap

The most consequential architectural fact for git integration is that **process confinement is proven unavailable** (`DECISION-20260720-04`). The shell tool explicitly returns `confinementUnavailable()` for any task-run context. This means that from the tool perspective, `git clone`, `git push`, `git fetch`, `git merge`, `git rebase`, and any other `git` subcommand executed via `ProcessBuilder` cannot be authorized in workspace-bound execution today.

This is not a policy gap — it is a **capability gate** that falls closed. The decision states: "workspace-bound shell behavior, skill executable authorization/execution, and package ingestion remain disabled" with a review trigger for "a tested confinement capability or explicitly approved bounded search fallback."

### 2. The Current Tool Surface

The agent tool surface for filesystem operations is:
- `file_read`, `file_write`, `file_append`, `file_replace` — confined to the `WorkspaceDescriptor` root via `AgentFileToolService`
- `shell_exec` — confined to the data root, further scoped to active workspace, but **disabled in task-run contexts**
- No dedicated `github`, `git`, `pr`, or `clone` tools exist.

File tools enforce path confinement (no traversal, no symlink escape, root-relative normalization) at the service layer, not through OS boundaries. Shell tool does the same but currently gates on task context. The key insight: **application-layer path validation is the existing pattern**, not OS-level sandboxing.

### 3. Workspace Model Constraints

The workspace model is closed to three kinds: `AGENT`, `CHAT`, `USER`. There is no `PROJECT` kind. Project association is metadata only, with no filesystem root authority. The directive proposes project workspaces with their own directory structure — this would require either a new `WorkspaceKind.PROJECT` or living under `USER`/`AGENT` spaces with a different structural convention.

`WorkspacePathLayout` is the sole path authority. Adding `/repos`, `/home`, `/apps`, `/scripts`, `/skills` as first-class children inside agent workspaces means `WorkspacePathLayout` must own those reserved names, and `WorkspaceDirectoryService` must construct descriptors that respect them.

### 4. AGENTS.md Layering is Already the Right Pattern

The project already implements layered runtime `AGENTS.md` resolution: all AGENTS.md files from the descriptor root to the active confined tool path remain active, with closest-file precedence for conflicts. This is exactly the mechanism needed for "system prompts that direct agents based on git-repo vs. regular workspace context." The dynamic re-resolution on tool-path changes means an agent moving into `/repos/some-project` would automatically pick up any `AGENTS.md` or `WORKSPACE.md` in that subtree.

### 5. The Skills System Already Solves the Trust Problem for Skills

The scoped Agent Skills system (immutable digest-bound snapshots, explicit approval, revocation) already handles trust lifecycle for executable and instructional content. The directive's `.skills/*.md` files in agent/project workspaces would map naturally onto `SkillScopeDiscoveryService` (which already reads from AGENT/USER workspace `.skills/` directories and could expand to project-scoped discovery when a project root authority exists).

### 6. Workspace Mounting Has No Existing Primitive

The directive proposes `<agent-workspace>/<current-workspace>` as a "mount" of a project workspace. No filesystem mount — symlink, bind mount, overlay, or metadata pointer — exists in the current architecture. The existing model is resolver-only: one `WorkspaceDescriptor` is constructed at a time, and file/shell tools operate within that single root. A "mount" implies either kernel-level filesystem operations (bind mounts, which require root or user-namespace capabilities and still face the confinement gate) or a virtual path resolver that stitches multiple roots together — neither of which exists.

### 7. Git Repository Concurrency is Untamed Territory

Multiple agents operating on separate clones of the same repository, each on their own branch, each making PRs, with a merge-agent handling conflicts — this is a distributed concurrency problem. The project has no existing queue, lock, or coordination primitive for git operations. The assignment queue (with its CAS lease pattern) is the closest analogue but is designed for task execution, not repository mutation coordination. Each agent's clone is spatially isolated (different directories), but the shared remote (GitHub/GitLab/etc.) is the concurrency choke point.

### 8. Root Immutability Enforcement Options Are Limited

The directive states "The agent cannot edit the root directory itself; they can only operate within the children folders. This needs to be enforced." Enforcement options, in descending order of strength:

| Layer | Mechanism | Feasibility | Notes |
|-------|-----------|-------------|-------|
| OS filesystem | Unix permissions (agent user lacks write on root dir) | **Unavailable** — Magenta runs as a single OS user; no per-agent UID separation | Would require Magenta to create OS users per agent, which is far outside current scope |
| OS filesystem | chroot / container per agent | **Unavailable** — process confinement is unproven, and container orchestration is deferred (see Phase 07 shell gate) | The very reason `shell_exec` fails closed in task contexts |
| Application filesystem | Service-layer mutation rejection | **Available** — `WorkspaceExplorerService` already rejects root mutation and `outputs/` mutation | Extend reserved-name checking to reject any write targeting the workspace root itself |
| Application policy | AGENTS.md / system prompt text | **Available but advisory** — the model can read this and try to comply, but has no hard enforcement | An agent determined to write to root could do so via `shell_exec` (outside task context) or `file_write` if no service-layer check exists |
| Application policy | Tool argument validation | **Available** — reject `path=""` or `path="."` that resolves to the workspace root in file_write/file_append calls | Most reliable: combine service-layer rejection with prompt text |

The strongest approach that is available today without process confinement: **service-layer rejection of root-relative writes targeting "" or "."** plus **AGENTS.md language directing agents to the child directories**. A dedicated tool argument validator that rejects any file path resolving to the workspace root would be the minimum hard enforcement.

### 9. The PR/Merge Agent Model Must Navigate the Confinement Gate

The directive describes an "auto-approval/merge agent" that "when any agent makes a PR, it triggers and passes the PR number to a designated PR agent who merges and fixes conflicts." This merge agent must:
- Clone or pull the repository (requires `git fetch`, which needs `shell_exec`)
- Run `git merge` or use GitHub API (same constraint)
- Resolve conflicts (requires file tools for conflict markers, then `git add`/`git commit` via shell)
- Push results (requires `git push` via shell)

If the merge agent runs inside Magenta's execution framework, it faces the same confinement gate. If it runs outside — as a GitHub Action, a webhook-driven service, or a separate daemon — then it escapes the gate but introduces a separate operational surface that Magenta must configure, monitor, and recover.

## Options

### Option A: Pure Prompt-Enforced Model (Lowest Implementation)

Agents receive AGENTS.md / system prompt text instructing them:
- Clone repos into `/repos/<name>` using `shell_exec git clone ...`
- Create branches, work, commit, push using `shell_exec git ...`
- File PRs using `shell_exec gh pr create ...` or web tool
- Pull from master before starting work
- Resync after merge

**Enforcement**: Only advisory. Application-layer checks on root immutability via service-layer guards. No process confinement — tools must be whitelisted, and git is inherently network-touching.

**Confinement risk**: A `git clone` needs network access and writes to the specified directory. The existing shell tool limits working directories to the data root and rejects shell-control syntax, but does not prevent `git clone https://evil-server/repo.git` from pulling arbitrary content. Git's `.git/config` can contain arbitrary command hooks (`post-checkout`, `pre-commit`, etc.) that run during git operations — this is an unaddressed risk vector.

**Failure recovery**: None automated. If a clone fails, an agent must retry. If a push fails (non-fast-forward), the agent must rebase/merge. If a PR has conflicts, the merge agent must resolve them manually.

### Option B: Service-Layer Git Operations with Shell Exec (Medium)

Add dedicated git service classes that:
- Accept `WorkspaceDescriptor` and a `/repos`-relative path
- Validate confinement (repository path is within the workspace under `/repos`)
- Construct safe `git` commands with validated arguments
- Execute through the existing `AgentShellToolService` (extending whitelist to include `git`, `gh`)
- Return structured results (status, branch, diff stats, PR URL)

Add `WorkspacePathLayout` reserved names: `REPOS` (`repos`), `HOME` (`home`), `SCRIPTS` (`scripts`), `APPS` (`apps`), `SKILLS_DIR` (`skills`), `CURRENT_WORKSPACE` (`<current-workspace>`). Update `WorkspaceExplorerService` to reject root mutation (already done) and add explicit root-relative path rejection for writes targeting `""` or `"."`.

**Enforcement**: Service-layer path validation prevents writes outside `/repos` children and the workspace root itself. Process confinement gate still applies to task-run contexts, but chat-context shell execution (which is currently allowed) could run git commands.

**Confinement gap**: Chat-context execution is currently allowed for `shell_exec`. A chat agent could `git clone` a malicious repo; git hooks in `.git/hooks/` would be in the workspace directory under the data root. This is not a git-specific problem — it's the same class as any `shell_exec` in chat context — but git repos amplify it because they arrive with pre-configured hook scripts.

### Option C: GitHub/GitLab API-First with Web Tools (Medium-High)

Replace most shell-exec git operations with HTTP API calls:
- Clone → GitHub API download archive + extract (or use a service-side JGit library for initial clone)
- Branch, commit, push → GitHub API (create ref, create blob, create tree, create commit, update ref)
- PR → GitHub API
- Merge → GitHub API
- Conflict detection → API query for mergeability
- Fetch/pull → API for ref comparison, then JGit or API-based tree updates

**Benefits**: Bypasses shell execution for most operations, avoids git hook risks, provides structured API responses, enables better observability.

**Drawbacks**: JGit or GitHub API do not perfectly replicate `git` CLI behavior (especially for merge conflict resolution). Some git operations (complex merges, rebase, interactive operations) are hard to replicate through APIs. JGit has its own bug surface and API compatibility constraints.

**Observability**: API responses are structured and can be logged, stored in `WorkspaceFileActionLogRepository` entries, and displayed in the operational console.

### Option D: Dedicated Git Daemon with API Gateway (Highest Safety, Highest Implementation)

A separate process or container that:
- Owns the git data directory (clone storage)
- Exposes a local API (HTTP or Unix socket) for clone, branch, commit, push, PR, merge operations
- Runs with its own OS-level confinement (separate user, limited network, no access to Magenta data root)
- Accepts only structured, validated operation requests
- Reports results through a shared database or response payloads
- The merge agent lives inside this daemon, triggered by Magenta sending a PR merge request

**Benefits**: True process isolation for the highest-risk operations. Can restrict git daemon's network to only GitHub/GitLab API endpoints. No git hook risk to Magenta's data. Failures are independently observable.

**Drawbacks**: Significant deployment complexity. Requires the daemon to be started, configured, monitored, and recovered. Adds a new operational surface. Crosses the current architecture's boundary (currently a single-process Spring Boot app with SQLite).

### Option E: Hybrid — Service-Layer Git with Phased Confinement Escalation (Recommended)

Phase 1: Service-layer git operations with shell exec (Option B), restricted to chat context only until process confinement is proven. Add explicit `git` arguments validation (reject `--exec-path`, `--git-dir` that escapes workspace, etc.). Add AGENTS.md injection for git workflow instructions.

Phase 2: When process confinement is proven (Phase 07 of the existing architecture roadmap), enable git operations in task-run contexts through the existing `requireCurrentWriterForMutation()` gate.

Phase 3: Add GitHub/GitLab API integration for PR, merge, and status operations. Keep shell-exec git for clone, commit, and push where API replacements are incomplete.

Phase 4: Evaluate dedicated git daemon if concurrent multi-agent git operations reveal concurrency problems that service-level coordination cannot resolve.

## Trade-offs

### Prompt-Only vs. Service-Layer Git Enforcement

| Aspect | Prompt-Only (Option A) | Service-Layer (Option B) | API-First (Option C) | Daemon (Option D) |
|--------|------------------------|--------------------------|----------------------|-------------------|
| Git hook risk | High — model executes arbitrary repos | High — same shell execution path | Medium — JGit bypasses hooks but has own risks | Low — isolated process |
| Network surface | Unrestricted git remotes | Unrestricted git remotes (unless URL validated) | API-only, URL validated | API-only, network-restricted |
| Root immutability | Prompt text only | Service-layer rejection | Service-layer rejection | OS-level enforcement possible |
| Confinement satisfaction | No | No (chat context only) | Partial (API calls, not git binary) | Yes (separate process) |
| Implementation effort | Hours | Days | Weeks | Weeks+ |
| Operational complexity | None added | Minimal (new service + config) | Moderate (JGit, API tokens) | High (daemon lifecycle) |
| Failure observability | Model-dependent | Structured results, action log | Structured API responses | Full audit trail |
| Concurrent agent safety | None — each agent has own clone | Clone-level isolation, remote shared | Clone-level isolation, remote shared | Centralized coordination |

### Workspace Mount Modeling

**Symlink approach**: `ln -s /data/root/workspace/project/<id> /data/root/workspace/agent/<agentId>/<current-workspace>`. Simple to implement, transparent to file tools. But: symlinks are explicitly rejected by `WorkspaceExplorerService` confinement (no-follow component opens, `LinkOption.NOFOLLOW_LINKS`). Changing this would be a confinement regression.

**Bind mount approach**: Requires `mount --bind`, which typically needs root or `CAP_SYS_ADMIN`. Not available under the confinement gate. Kernel-level overlay mounts (`OverlayFS`) are even more involved.

**Metadata pointer approach**: A database record or a pointer file (e.g., `.current-workspace` containing `project:my-project`). The file/shell tool resolution layer reads this pointer and remaps requests. This is the most architecturally compatible approach — it keeps `WorkspaceDirectoryService` as the sole resolver but adds a "virtual child" that resolves to a different physical root. The challenge is making this transparent to file tools, which currently bind to a single `WorkspaceDescriptor.root`.

**Descriptor-chain approach**: When an agent has a "mounted" project workspace, file tools accept a compound descriptor that knows about both the agent root and the mounted root. Paths under `<current-workspace>/` resolve against the mounted root; all other paths resolve against the agent root. The key insight: this is not a symlink — it is a routing rule in the path resolution layer.

### Git Repo vs. Regular Workspace Discovery

**WORKSPACE.md file**: The directive suggests placing a WORKSPACE.md file in place of the `<workspace-name>` directory for git repos. This works well with the existing AGENTS.md resolution pattern — AGENTS.md files from the workspace root to the active path are already injected into the prompt. A WORKSPACE.md can be treated similarly: when the agent navigates to a directory containing WORKSPACE.md, inject its instructions (which direct the agent to clone the git URL before working).

**Alternative — `.workspace` config file**: A structured config file (TOML/JSON/YAML) that declares the workspace type and git URL. Machine-parseable for tool-level enforcement, but diverges from the AGENTS.md / WORKSPACE.md Markdown convention.

### Merge Agent Design

**Dedicated agent profile**: A persistent agent profile configured with specific tools (git merge, PR review, conflict resolution) and a system prompt specialized for merge duties. Can be triggered by a webhook or polling.

**System service**: A background service (Spring `@Scheduled` or event-driven) that polls for open PRs or listens for webhook events. When a PR is detected from a known agent, it queues a merge review task for the merge agent.

**GitHub Actions**: Offload merge approval to GitHub Actions. When an agent pushes a branch and opens a PR, a workflow runs tests/linting and auto-approves if checks pass. The merge agent's role becomes monitoring and intervening only when automation cannot resolve conflicts.

**Trade-off**: Dedicated agent profile is the easiest to implement and most consistent with Magenta's agent-centric architecture. GitHub Actions offloads merge responsibility but adds external dependency. System service is the most reliable but adds a new service surface.

### Observability and Failure Recovery

**Git operation observability**: Each git operation should produce a structured event (loggable, queryable, displayable). The existing `WorkspaceFileActionLogRepository` pattern (keyed by canonical workspace identity plus file-relative path) can be extended to `WorkspaceGitActionLogRepository` with columns: agent id, repository URL, branch, operation (clone/fetch/commit/push/pr/merge), status, output, timestamps, PR URL. This gives the operational console a "Git Operations" panel in the agent dashboard.

**Clone failure**: If a clone fails (network, auth, disk space), the agent's workspace should show the `/repos/<name>` directory as "unavailable" or "error" state. The agent should receive the error in their tool response and be able to retry. The dashboard should surface git operation status (similar to the existing run-status pattern).

**Stale clone**: If an agent works on a stale clone (master advanced), the pre-push check (git fetch + diff) should warn them. This can be a pre-commit hook injected by the service layer or a check in the `git push` wrapper. If a push fails due to non-fast-forward, the agent must rebase/merge, which may produce conflicts. The system should not auto-resolve these.

**PR conflict**: When a merge agent detects conflicts, it should:
1. Log the conflict with affected files
2. Notify the originating agent (via a chat message or a task queue item)
3. Optionally attempt auto-resolution if conflict markers are simple
4. Escalate to a human operator if auto-resolution fails

**Merge failure**: If the merge agent's merge fails (e.g., protected branch rules, CI failure), the agent should retry with updated master. The failure should be visible in the dashboard.

**Agent gone/terminated**: If an agent is disabled or deleted while it has an open PR, the merge agent should detect this and close the PR or reassign it.

## Open Questions

1. **Process confinement path**: What is the concrete plan for Phase 07 process confinement? Without this, all git operations are gated to chat context only. Would a `chroot` or user-namespace approach be acceptable, or does it require full container isolation? The answer determines whether Option B or Option D/E is the realistic path.

2. **Project workspace kind**: Should project workspaces be a new `WorkspaceKind.PROJECT` or managed under `USER` spaces? A new kind would follow the closed-model pattern but requires a fourth value in the enum and new resolution paths. Managed under USER avoids this but confuses ownership semantics. The decision affects the entire workspace identity model.

3. **Multi-agent concurrent git safety**: How many agents will realistically operate on the same repo simultaneously? If the answer is "one at a time per repo," clone-level isolation suffices. If multiple agents push to the same remote concurrently, a coordination mechanism (merge queue, rebase-before-push policy) is needed.

4. **Git hosting assumption**: Is the remote always GitHub, or can it be GitLab, Bitbucket, Gitea, or a self-hosted Git server? GitHub API integration is well-supported; other providers vary. If git is always accessed via HTTPS with tokens, credential management becomes a concern.

5. **Auth token management**: Where do git credentials live? In agent config? In a secrets store? In the environment? The existing `AiConfig` / `RuntimeSettingsService` pattern could hold tokens, but git tokens are high-value secrets that should not travel through model context.

6. **Mount semantics for nested repos**: If agent A clones repo R into `/repos/R` and repo R itself contains a WORKSPACE.md that points to another repo, does the agent recursively clone? The directive says WORKSPACE.md "directs agents to clone from the git URL and work within it" — implied one level deep, but edge cases matter.

7. **The `/home` and `/apps` directories**: What concrete agent behaviors use these? If they are aspirational, they should be deferred features. If they have immediate use cases, the path layout and tool scoping must account for them.

8. **The system chat agent**: The directive mentions a "system chat with a default agent who cannot edit files but can interact with the work queue and workspace management." This is a new tool access policy (read-only file access, queue management, no file writes). The existing `ToolAccessPolicy` already supports mode-specific tool gates — this would be a new mode.

9. **WORKSPACE.md vs AGENTS.md**: When both exist, which takes precedence for git workflow instructions? Should WORKSPACE.md be treated as a special AGENTS.md variant (injected into prompt) or as a machine-parseable config file?

10. **Git hook risk vector**: Even in chat context, `git clone` of an untrusted repo can execute hooks. Should Magenta inject `--config core.hooksPath=/dev/null` or `GIT_CONFIG_NOSYSTEM=1` into every git command it constructs? This is a low-effort, high-impact safety measure.

## Recommended Next Step

**Adopt a phased approach aligned with the existing architecture roadmap, starting with Option E (Hybrid) Phase 1:**

1. **Define the structural contract first.** Extend `WorkspacePathLayout` with reserved agent-workspace children: `REPOS`, `HOME`, `SCRIPTS`, `APPS`, `SKILLS_DIR`. Keep `CURRENT_WORKSPACE` as a deferred descriptor-chain feature pending the mount-model decision. Update `WorkspaceDirectoryService` and `WorkspaceExplorerService` to recognize and constrain these paths. This is pure layout/validation work with no new execution surface.

2. **Add root-immutability enforcement at the service layer.** Reject any `file_write`/`file_append`/`file_replace` with a target path resolving to the workspace root (empty or `"."`). Add a corresponding AGENTS.md fragment injected at the descriptor root level. This is a few dozen lines of validation with immediate safety benefit.

3. **Design the Git service interface as a read-only spec first.** Define `GitOperationService` method signatures (clone, fetch, branch, commit, push, prStatus, prCreate, mergeCheck) that accept a `WorkspaceDescriptor`, validate the `/repos` confinement, and return structured `GitOperationResult` records. Write the spec, not the implementation, to get the contract right before touching any git binary.

4. **Implement git safety wrappers.** For any git execution (whether via shell or future daemon), inject `--config core.hooksPath=/dev/null` and `GIT_CONFIG_NOSYSTEM=1`. Validate remote URLs against a configured allowlist. This is low effort and mitigates the worst git hook risks before any actual git execution ships.

5. **Defer the mount question.** The `<current-workspace>` mount model should be a separate investigation. The metadata-pointer and descriptor-chain approaches are architecturally compatible with the existing `WorkspaceDirectoryService` pattern, but the semantics need a focused design session.

6. **Defer the merge agent.** The PR/merge agent lifecycle (trigger, review, conflict, merge) depends on having git operations working first. Design the merge agent's API contract (event subscription, operation request/response schema) but do not implement until git clone/branch/commit/push flows are validated.

7. **Route the git daemon question to the Phase 07 process-confinement investigation.** The decision between shell-exec git and a separate daemon depends entirely on whether Phase 07 produces a viable process confinement mechanism. Do not commit to one path before that investigation concludes.

8. **Create a `git-safety-and-reliability.md` knowledge file** capturing the hook-risk vector, URL validation pattern, concurrency model, and failure-recovery checklist from this analysis so future implementers have the context.
