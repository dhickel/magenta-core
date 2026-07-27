## Prompt

Synthesize the workspace sprint brainstorming into one concise architectural and product direction covering agent workspaces, project context, Git, prompts, skills, queues, system chat, and the `/agents/{agentId}` console. Reconcile conflicting recommendations while preserving Magenta’s closed `AGENT`/`CHAT`/`USER` workspace model and current confinement, lifecycle, skill-trust, and SimplyPages contracts. This is a brainstorming synthesis, not an accepted implementation plan.

## Source

- workspace-architecture/findings.md
- git-safety-reliability/findings.md
- agent-workflow-integration/findings.md
- product-surface-delivery/findings.md
- workspace-architecture/cross-review.md
- git-safety-reliability/cross-review.md
- agent-workflow-integration/cross-review.md
- product-surface-delivery/cross-review.md

## Findings

### Converged direction

1. **Preserve the closed workspace identity model.** `WorkspaceKind` should remain `AGENT`, `CHAT`, and `USER`. A project is metadata and assignment context, not a fourth descriptor type or shared filesystem root. Reversing that decision would recreate identity, lifecycle, locking, and API complexity without first proving that agent-local materialization is insufficient.

2. **Make each AGENT workspace the agent’s durable personal filesystem.** Recognized child areas should cover personal skills, scripts, apps, home files, repositories, and project materializations. They should be created lazily, while the browser may always show their conceptual locations. `WorkspacePathLayout` remains the naming authority and `WorkspaceDirectoryService` remains the trusted path authority.

3. **Enforce root immutability as an allowlist, not merely a `"."` check.** The requirement says agents may operate only *within child folders*. Therefore, mutation policy should reject both mutation of the root and creation of arbitrary root-level entries. Every mutating path—file tools, explorer actions, Git operations, and future workspace-management tools—should require an approved first path segment. Application-owned root files or metadata must be immutable to agents. Prompt text is supplementary guidance, never the enforcement boundary.

4. **Model “current workspace” as contextual binding, not a symlink or kernel mount.** Symlinks conflict with existing no-follow confinement, and bind mounts add unavailable host privileges. The authoritative state should be database-backed and resolve to an agent-local materialization such as a regular project area or `repos/<repo>`. Initially, the dashboard can present `current workspace` as a virtual navigation node while tools receive the canonical effective path. A transparent `/current-workspace/` filesystem alias should be deferred unless user testing proves it necessary.

5. **Bind context to work, not only to the agent.** A single mutable “current context per agent” is unsafe when an agent has concurrent chats, assignments, or runs. The stronger model is an immutable workspace binding on each assignment/run or conversation, plus an optional dashboard-selected default for new work. This reuses the existing execution principle that context is captured at submission and prevents one interaction from silently retargeting another.

6. **Keep project content agent-local unless a concrete sharing requirement emerges.** Git projects naturally materialize as one clone per agent. Regular projects can materialize under an agent-owned project area, but the source-of-truth and synchronization semantics for non-Git projects remain unresolved. Calling independent copies a shared “project workspace” would be misleading; this product distinction must be explicit.

7. **Use a dedicated Git capability with split responsibilities.** Local repository operations should be structured service operations, initially using an in-process implementation such as JGit where it is adequate. PR creation, checks, review state, and merge should use the configured hosting provider API rather than pretending they are local Git operations. Native Git CLI fallback should remain capability-gated until process confinement is proven and should never be exposed as arbitrary model-generated shell commands.

8. **Enforce the Git workflow technically.** Prompts should explain the workflow, but services and provider controls should enforce it: validated remote allowlists, server-held credential references, clone targets confined beneath `repos/`, protected base branches, task-derived branch names, no direct push to the base branch, pre-push divergence checks, serialized mutation per clone, and structured audit events. Repository state should be probed from Git; database state should be an observable index/cache, not a competing source of truth.

9. **Treat repository safety more precisely than the reports did.** Remote repositories do not normally deliver executable `.git/hooks` through cloning. The broader risks are local/system Git configuration, template hooks, credential helpers, filters, submodules, LFS/process filters, symlink checkout, and untrusted content. An in-process implementation should disable external hooks/process filters and avoid automatic submodule initialization; any later CLI path should use isolated configuration, disabled hooks, validated arguments, and the same confinement gate.

10. **Separate review intelligence from merge authority.** A PR-review agent may inspect diffs and recommend approval, but a deterministic policy service should decide whether checks, reviews, branch protection, freshness, and authorization permit merge. Initial conflict behavior should be detection, notification, and a bounded remediation assignment—not autonomous conflict rewriting followed by unconditional merge. Provider webhooks or internally generated PR events are the preferred trigger; polling is a fallback, not the target architecture.

11. **Build dynamic prompts from trusted context plus existing instruction layers.** The prompt assembler should inject a non-editable context policy derived from the binding (allowed paths, project identity, Git workflow, branch, and tool limits), then layer applicable `AGENTS.md` and project instructions. Generated safety policy must outrank workspace-authored files. `WORKSPACE.md` should represent application-managed project guidance outside the clone, because a file inside a repository cannot tell an agent how to clone that repository before it exists.

12. **Reuse the existing skill trust pipeline.** Personal skills should remain discoverable through the bound-agent scope and still require digest-bound approval and activation. A trusted context binding can eventually provide the authoritative root needed for project-local skill discovery, but it must not bypass snapshot trust. The `/skills` requested by the product and the existing `.skills/` filesystem contract must be reconciled deliberately; two live directories with unclear precedence would be worse than either convention alone.

13. **Evolve the console through composition, not a new monolithic dashboard.** The Files tab should become a workspace browser with a compact structural navigator, existing file list/inspector, run groups, contextual repository status, and an independently rendered skills panel that reuses existing skill fragments. Queue data should continue to come from `AssignmentService`/`TaskRunLifecycleService`, enriched with project/repository context rather than duplicated in a new queue. Git controls and system chat should be independent HTMX stable hosts shown only when relevant. Broad tab consolidation is a separate UX decision.

14. **Model system chat as a restricted profile.** Reuse normal chat infrastructure with a dedicated profile authorized for workspace status, read-only file inspection, and explicitly scoped queue/workspace-management commands. It should have no file mutation, shell, or implicit Git authority. Any destructive queue action should use the same authorization and confirmation rules as the ordinary control plane.

## Options

### Option A — Foundation-only increment

Establish the structured AGENT workspace policy, hard root-child mutation rules, browser navigation, and personal-skill composition, while deferring project context and Git.

- **Strength:** Lowest risk and immediate visible value.
- **Weakness:** Does not yet deliver a meaningful project mount or repository workflow.
- **Use:** Best first delivery candidate after decisions are accepted, but not the complete direction.

### Option B — Context-bound agent-local workspaces with managed Git

Keep the three workspace kinds; add work-scoped database bindings to agent-local regular-project materializations or per-agent clones; inject trusted prompt context; expose managed Git/provider operations; compose queue, skills, and restricted system chat in the agent console.

- **Strength:** Satisfies the product intent without reversing governed workspace architecture, and gives hard enforcement and observable lifecycle state.
- **Weakness:** Requires decisions on non-Git project provenance, credentials, concurrency, and context granularity.
- **Verdict:** **Strongest overall direction.** It should be explored as staged, independently valuable increments rather than one sprint-sized implementation.

### Option C — First-class shared PROJECT workspace

Add `PROJECT` identity, project roots, descriptor/API support, and virtual or physical mounts.

- **Strength:** Clean independent identity and natural shared non-Git content.
- **Weakness:** Reverses an explicit architecture decision and reintroduces shared-root concurrency, lifecycle, and confinement machinery.
- **Verdict:** Defer unless agent-local materialization demonstrably fails a concrete collaboration workflow.

### Option D — Prompt-led shell workflow

Use `AGENTS.md`, `WORKSPACE.md`, and raw `shell_exec` to direct clone/branch/PR/merge behavior.

- **Strength:** Fastest prototype.
- **Weakness:** Advisory rather than enforced, weakly observable, unsafe under the current process-confinement gate, and unsuitable for automated task runs.
- **Verdict:** Reject as the product architecture; prompts remain explanatory layers only.

## Trade-offs

| Decision | Preferred direction | Cost accepted |
|---|---|---|
| Project identity | Metadata plus agent-local materialization | No independently browsable shared project root |
| Mount semantics | DB binding plus virtual UI node/canonical tool path | No literal filesystem mount initially |
| Context lifetime | Assignment/run/chat binding plus optional default | More records than one mutable agent pointer |
| Root policy | Approved-child allowlist at every mutation boundary | Less freedom to invent root-level layout |
| Directory creation | Lazy physical creation, persistent conceptual navigation | Browser may show not-yet-materialized nodes |
| Git implementation | In-process structured local Git plus provider API | New dependency/adapter and incomplete support for exotic Git operations |
| Git state | Probe repository; persist audit/index data | Reconciliation work instead of trusting a status table |
| Collaboration | Per-agent clones and branch isolation | Storage duplication and need for remote coordination |
| Merge automation | Deterministic policy gate plus advisory review agent | Less “fully autonomous” conflict handling |
| Skills path | One canonical filesystem path with UI-friendly label | Either a compatibility migration or departure from literal `/skills` wording |
| UI | Contextual HTMX modules on the existing console | Less dramatic redesign and no immediate tab consolidation |
| Enforcement | Service/provider controls plus prompt guidance | More explicit policy code than prompt-only behavior |

## Open Questions

1. What is the authoritative source and synchronization model for a **non-Git** project shared by more than one agent?
2. Must the physical directory literally be `skills/`, or may canonical `.skills/` be presented as “Skills”? If migration is required, what is the single precedence rule?
3. Should one agent be allowed multiple concurrent writers to the same clone, or should an agent/repository writer lease serialize work? Would separate per-task clones be acceptable when concurrency is required?
4. Is `current-workspace` required to behave as a real path for all tools, or is a virtual dashboard node plus a bound canonical working directory sufficient?
5. Which Git host or hosts are initially supported, and where are credential references stored? Credentials must never enter prompts, repository files, URLs, or ordinary operational logs.
6. Which remote URL schemes, organizations, and repositories are allowed, and how are redirects, submodules, LFS, filters, and symlink checkouts handled?
7. Is `WORKSPACE.md` primarily human-readable guidance, machine-readable project configuration, or both? What precedence does it have relative to generated policy and `AGENTS.md`?
8. What exact queue mutations may the system-chat profile perform, and which require confirmation or elevated authorization?
9. Does “see each agent’s clone” mean navigating each agent’s own authorized Files tab, or a new cross-agent administrative repository overview? Direct cross-workspace browsing should not be assumed.
10. What merge policy is acceptable: mandatory CI, required human approval, AI recommendation only, or bounded auto-merge for explicitly low-risk repositories?
11. Who may create or switch project contexts—dashboard users, assignment submission, agents through tools, or some combination—and how is authorization scoped?
12. Are `apps/` and `home/` simply general file namespaces, or do they imply executable/application lifecycle capabilities that should remain separately gated?

## Recommended Next Step

Use **Option B** as the candidate direction, but do not treat it as an implementation commitment yet. The next artifact should be a focused decision proposal that resolves the blocking semantics: canonical skills path, work-scoped context identity, non-Git project provenance, literal versus virtual mount behavior, Git provider/credential boundaries, clone concurrency, and merge authority.

After those decisions are reviewed, derive a phased implementation plan. The likely first independently valuable slice is Option A: structured agent workspace navigation, a hard approved-child mutation policy across existing tools, reuse of the current skills surface, and trusted workspace-structure prompt guidance. Context binding should follow before managed Git; PR automation and autonomous conflict remediation should remain last and separately approved.
