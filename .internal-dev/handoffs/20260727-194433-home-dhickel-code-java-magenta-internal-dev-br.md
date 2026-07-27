# Agent Workspace Establishment Handoff

## Context

Magenta has a governed filesystem-first workspace model. `WorkspaceKind` is closed to `AGENT`, `CHAT`, and `USER`; `WorkspacePathLayout` owns structural paths; `WorkspaceDirectoryService` alone constructs trusted descriptors; projects are metadata rather than filesystem identities; task runs and outputs have reserved lifecycle-owned trees; skill disclosure requires digest-bound approval and immutable snapshots; and process execution remains unavailable without proven confinement.

This direction adds a durable personal filesystem for each agent, metadata-backed project and repository context, managed Git workflows, context-aware prompts, queue visibility, skills composition, and restricted system chat. It extends the existing model without restoring project roots, generic workspaces, links, aliases, filesystem leases, bind mounts, or virtual workspace aliases.

Agent-local materialization, versioned metadata-backed context, service-enforced mutation policy, managed Git, and incremental composition belong in `/agents/{agentId}`. An allowlisted layout and prompt guidance are insufficient: every writer must be brokered, repository-control metadata must be protected, work bindings must be immutable snapshots, and stale or concurrent operations must fail closed.

## Objective

Establish each `AGENT` workspace as a durable, structured, safely managed personal filesystem while preserving workspace, task-run, artifact, skill-trust, and confinement contracts.

The system must:

- Provide predictable personal storage, repository, project-materialization, and skill areas.
- Prevent arbitrary workspace-root mutation.
- Capture project and repository context on conversations and executable work without a `PROJECT` workspace kind.
- Provide structured Git and provider operations without model-generated shell commands.
- Present files, context, Git status, queue state, runs, and skills coherently in the agent console.
- Keep system chat file-read-only and deny implicit shell, Git, skill-trust, execution, or merge authority.
- Remain observable, cancellable, restart-safe, and explicit when a capability is unavailable.

## Targets

- **Workspace structure and confinement:** `WorkspacePathLayout`, `WorkspaceDirectoryService`, `ReservedWorkspacePathPolicy`, `ConfinedPathMutationService`, `WorkspaceExplorerService`, and `AgentFileToolService`.
- **Context and lifecycle:** persisted materialization and workspace-context records; immutable context-binding snapshots for conversations, assignments, and runs; `AssignmentService`, `TaskRunLifecycleService`, execution submission, and conversation binding.
- **Schema:** append-only `SchemaMigrationRunner` resources following the existing immutable contiguous migration sequence.
- **Prompt and instruction assembly:** `PromptContextAssembler`, `AgentsMdResolver`, and trusted generated context policy.
- **Skills:** existing discovery, trust, snapshot, activation, controller, and fragment surfaces.
- **Git and provider integration:** bounded managed-Git service, privileged repository-control storage service, configured GitHub/GitHub Enterprise HTTPS provider client, repository state, operation audit, and reconciliation persistence.
- **API and web:** thin agent-scoped context and Git adapters, `WorkspaceExplorerFragments`, `OrchestrationController`, `/agents/{agentId}` fragments, and independently scoped queue, run, and skill hosts.
- **Documentation and contracts:** architecture, service graph, services, API, web, SimplyPages, decisions, deferred-feature, technical, API, and end-user documentation.

## Features

### Structured agent workspace

The canonical agent-root structure is:

- `.skills/` — existing canonical workspace skill source, labelled **Skills** in the UI.
- `scripts/` — agent-authored script source and data; storage grants no execution authority.
- `apps/` — agent-authored application source and data; storage grants no process or deployment authority.
- `home/` — general personal files.
- `repos/` — application-managed Git materializations with agent-editable worktree content.
- `projects/` — agent-local non-Git project materializations.
- `tasks/` and `outputs/` — existing application-owned reserved trees with unchanged semantics.

The structural navigator may show these conceptual areas without creating them. Reads never create directories. Authorized writes lazily create only the required allowlisted chain. Existing unrecognized root entries remain visible only where existing read behavior permits and remain read-only; no migration, relocation, aliasing, or deletion is inferred.

### Root and reserved-tree enforcement

- Root mutation policy applies only to `AGENT`; `CHAT` and `USER` semantics remain unchanged.
- Agents cannot create, rename, move, copy, or delete arbitrary root entries.
- Root-child creation is mediated by its owning application service and limited to canonical children.
- `.skills/`, `tasks/`, `outputs/`, repository control data, and application metadata are writable only through their owning services.
- File tools may edit ordinary content inside `home/`, `scripts/`, `apps/`, `projects/`, and an authorized repository worktree. A task or conversation receives only its captured context scope; it does not gain broad access merely because the agent owns the workspace. Task-run writer fences remain required where already applicable.
- Hard enforcement lives in path, mutation, repository-control, Git, skill, and lifecycle services. Prompt text explains policy but grants nothing.
- Ordinary content mutations use the existing confined mutation boundary. Repository-control mutation uses the dedicated privileged repository-control storage boundary; managed Git is its sole caller. No generic explorer, file tool, lifecycle service, third-party library, or provider client may write `.git` or equivalent control data directly.
- The repository-control storage boundary must provide descriptor-scoped, no-follow, race-resistant operations equivalent to the supported confined mutation contract, reject links and control-path rebinding, and protect control metadata from ordinary worktree operations. If the selected in-process Git library cannot operate within that boundary, affected Git capability remains unavailable.
- Native process execution, archive extraction, package execution, arbitrary library writes, and shell Git remain unavailable unless separately proven safe.

### Work-scoped context binding

- Current workspace context is a versioned database-backed selection, not a symlink, mount, alias, or editable pointer file.
- A dashboard default is only a selection input for a newly created conversation or work item. Updating it requires the expected default version and never retargets an existing binding.
- Every conversation, assignment, run, retry, replay, and resume persists an immutable context-binding snapshot. The snapshot identifies the owning agent, optional project metadata identity, context type, materialization identity and generation, repository identity, captured branch/work-item facts, and source context revision.
- Materialization identity and generation are authoritative. The canonical agent-relative display path is derived at use time by `WorkspacePathLayout` and `WorkspaceDirectoryService`; it is neither a context identity nor an independently authoritative persisted path.
- Context-changing actions carry an expected version token. Stale tabs, stale fragments, and conflicting switches return a controlled `409` without changing a newer binding.
- Execution continues to select an `AGENT` or `USER` workspace using `workspaceType` and `workspaceKey`. Project context is supplemental metadata within the selected agent workspace and never filesystem identity. `CHAT` selection remains rejected.
- No physical or virtual `/current-workspace/` path exists. Prompts and UI disclose the derived canonical child path.

### Agent-local project materialization

- Projects remain metadata and membership context.
- Non-Git project content resides agent-locally at `projects/<stable-materialization-key>/`; Git content resides agent-locally at `repos/<stable-repository-key>/`.
- Stable keys derive from authoritative identities, never display names, repository basenames, timestamps, or user-controlled titles. Display-name and project-name changes never rename materialization paths.
- A materialization record binds its owning agent, kind, authoritative source identity, stable key, generation, lifecycle state, and data-root-relative location. It is resolved only through trusted layout and directory authorities.
- Non-Git materializations have no cross-agent synchronization or shared source-of-truth implication. Cross-agent workspace or repository browsing is not added.
- Failed, deleted, revoked, generation-mismatched, dirty, corrupt, or partially provisioned materializations are explicit non-ready states and cannot be silently reused.

### Managed Git and provider operations

- Local Git exposes only explicitly supported structured operations through `ManagedGitService`: provision/clone, fetch, status, branch creation and selection, bounded diff inspection, add/commit, push, and reconciliation. The baseline uses an in-process library; Git CLI and `shell_exec` fallback are disabled.
- Managed Git alone calls the privileged repository-control storage boundary. It may mutate worktree bytes only through the same confined ordinary-content operations used by file services or an equivalently verified repository worktree primitive. It cannot accept caller-supplied control paths.
- Provider operations use a configured GitHub or GitHub Enterprise HTTPS API boundary. Other providers and protocols return unavailable.
- Credentials are server-side secret references. They never enter prompts, workspace files, remote URLs, logs, tool responses, browser fragments, audits, or exceptions. Missing, expired, or revoked credentials make protected operations unavailable.
- Remote selection is limited to configured HTTPS hosts and repository/organization allowlists. Reject credential-bearing URLs, `file:` URLs, SSH/scp forms, unapproved redirects, submodules, LFS, external or process filters, alternates, local transports, nested control paths, and symlink checkout.
- The Git and provider HTTP boundaries validate the initial endpoint and every redirect against configured scheme, host, repository, and organization policy; reject local/private/disallowed resolved endpoints and endpoint rebinding; and redact all transport failures. Provider status never substitutes for fresh authorization.
- Clone and fetch enforce cancellation plus configured network, object, disk, expansion, diff, and response bounds.
- `.git` and equivalent control data are application-owned and unavailable through explorer and file-tool operations. Worktree content remains editable only inside the captured authorized repository context.
- Repository mutations serialize per agent materialization with a durable ownership claim and operation identity. A competing mutating conversation, assignment, or run receives busy/conflict and is not silently queued against a mutable checkout.
- Each executable work item receives a persisted server-generated collision-resistant branch identity before branch creation. User task titles are not authoritative branch names. Branch creation, selection, and head facts are audited and bound into the work snapshot.
- Direct push, force update, deletion, and tag-based release operations against protected branches are forbidden. Push and PR operations revalidate remote state, authorization, branch policy, and captured repository generation at their provider boundary.
- Crashed, cancelled, dirty, corrupt, lock-residue, or partial materializations enter explicit reconciliation or blocked state. Reconciliation is an authenticated structured operation with audit; it does not discard or overwrite worktree bytes implicitly.
- Repository worktree/index facts come from the repository; remote refs, checks, reviews, and branch protection come from the provider; the database stores identity, lifecycle, claims, audit, and rebuildable status without competing Git truth.

### Pull requests and merge authority

- Agents may create PRs through the provider boundary when repository policy and credentials permit.
- Review agents may inspect bounded diffs and produce recommendations.
- Merge authority is deterministic and server-side. It revalidates checks, required reviews, branch protection, head freshness, repository policy, and actor authorization at the merge boundary.
- Merge requires an authenticated, server-verifiable human principal and explicit confirmation. In deployments without that identity boundary, merge is unavailable.
- Automatic conflict rewriting, unconditional auto-approval, autonomous merge-to-base, post-conflict self-approval, direct protected-branch mutation, and tag release are excluded.
- Conflicts create an observable remediation assignment or notification and never silently rewrite or merge code.

### Context-aware prompts and project guidance

- Prompt assembly injects a non-editable trusted context segment with derived canonical allowed paths, binding identity/version, materialization generation, project and repository identity, branch/work-item facts, Git workflow, unavailable capabilities, and tool limits.
- Trusted generated policy outranks workspace-authored instructions. Explicit user/task instructions may override `AGENTS.md` only where they do not conflict with enforced security or lifecycle policy.
- `AGENTS.md` resolution remains descriptor-confined and ancestor-inclusive, with closest-file precedence only for conflicts.
- Repository content, issue text, diffs, logs, `AGENTS.md`, `WORKSPACE.md`, and skills are provenance-labelled untrusted inputs. Prompt ordering is not a security boundary.
- `WORKSPACE.md` is optional human-readable guidance, never authoritative configuration. Pre-provision context and Git settings come from persisted context/materialization records. Repository-provided `WORKSPACE.md` cannot grant capabilities.

### Skills integration

- `.skills/` remains the single workspace-local skill path; the UI may label it **Skills**. No parallel `skills/` tree or precedence rule exists.
- Skills are shown through existing skill-domain services and fragments rather than parsed or trusted by the filesystem explorer.
- Agent-local and materialization-local candidates require an explicit trusted source authority that resolves the materialization by owning agent, stable identity, generation, and confined location. Project membership, repository association, assignment, or an unverified path does not provide that authority.
- Every candidate still requires exact digest approval, immutable snapshot creation, and explicit activation. Context association never implies skill trust.
- Executable skill resources and package ingestion remain unavailable while process confinement is unproven.

### Agent console and queue

- `/agents/{agentId}` remains the operational console and Files remains bound to one agent descriptor.
- Add a compact structural navigator in Files while retaining the file list, inspector, and independent run groups.
- Context, Git status, queue facts, runs, and skills use independently scoped stable HTMX hosts. A response updates only its owning host and rechecks agent/context scope at render time.
- Existing queue and lifecycle services remain authoritative. The UI enriches assignments with captured project, repository, branch, and context facts; it does not create another queue.
- Queue and repository state distinguish unavailable, provisioning, ready, busy, dirty, blocked, stale, failed, and reconciled. Missing facts never render as success.
- Broad tab consolidation, cross-agent administration, and a monolithic JavaScript dashboard are excluded.

### Restricted system chat

- System chat reuses normal chat infrastructure with a dedicated deny-by-default agent profile and server-side tool authorization.
- It may read authorized workspace status, bounded non-secret files, assignments, and lifecycle state, and propose existing queue operations.
- Pause, resume, retry, replay, cancellation, and other destructive queue actions require the same target scoping, idempotency, confirmation, authorization, and audit as the ordinary control plane.
- It cannot write files, mutate repository state, invoke shell/process execution, approve skills, activate executable resources, merge PRs, reveal credentials, or broaden its own tools.
- Read access excludes repository-control metadata, secrets, credentials, and other agents’ workspaces.

## Settled Decisions

1. `WorkspaceKind` remains exactly `AGENT`, `CHAT`, and `USER`.
2. Projects remain metadata; no project root, descriptor, route family, lease, or shared filesystem identity returns.
3. Agent workspaces use lazy canonical child areas `.skills`, `scripts`, `apps`, `home`, `repos`, and `projects`, alongside existing reserved trees.
4. `.skills/` is the only workspace skill path and is displayed as **Skills**.
5. Agent-root mutation uses an allowlist enforced at every mutation boundary; unknown root content is read-only.
6. Context defaults are versioned; conversation and work bindings are immutable snapshots. Canonical paths are derived, not persisted as independent identity.
7. Project and repository materializations are agent-local. Non-Git content is neither shared nor synchronized.
8. Repository-control data is application-owned behind one privileged repository-control storage boundary; file tools edit worktree content only.
9. Local Git uses structured in-process operations only; native Git CLI remains disabled under the process-confinement gate.
10. Provider support is restricted to configured GitHub/GitHub Enterprise HTTPS endpoints, allowlisted repositories, and server-held secret references.
11. One mutable work item may own an agent repository materialization at once; competing requests receive busy/conflict.
12. Review intelligence is advisory; deterministic policy plus server-verifiable human authorization owns merge permission.
13. Prompt guidance supplements but never replaces service-side enforcement.
14. Existing skill trust, snapshot, revocation, and activation rules remain unchanged.
15. System chat is restricted, not a privileged filesystem or Git agent.
16. Existing task-run, output-publication, artifact, `USER`, and `CHAT` behavior remains unchanged.
17. New authoritative schema state is introduced only by append-only immutable migrations following the existing migration sequence.

## Constraints

- Preserve `WorkspacePathLayout` as sole structural path authority and `WorkspaceDirectoryService` as sole descriptor authority.
- Never hand-concatenate structural roots, reconstruct identity from `Path`, or persist absolute Magenta-owned paths.
- Persist materialization locations only as data-root-relative values bound to authoritative identity and generation. Resolve them through the root-relative path authority; do not use stored strings as raw paths.
- Do not follow symlinks or accept hard-link, rename-race, traversal, encoded-separator, or path-rebinding escapes.
- Retain task-run writer and assignment-lease checks; repository serialization is separate and does not substitute for them.
- Schema migrations remain contiguous, append-only, immutable, checksum-protected, transactionally applied, and solely owned by `SchemaMigrationRunner`.
- Controllers remain thin; services own use cases; repositories own persistence; renderers consume authorized secret-free values.
- Standard UI interactions use SimplyPages and HTMX. JavaScript needs a documented narrow path-of-least-resistance justification.
- UI fragments enforce scope independently; hidden controls grant no authority.
- Never expose secrets, absolute paths, claim owners, fence values, tokens, raw provider credentials, internal resolved addresses, or unbounded failure output.
- Unsupported Git, provider, repository, provider-authentication, or merge capabilities fail closed with truthful unavailable state.
- Existing files and obsolete database structures are neither migrated nor deleted implicitly.

## Scope

### In scope

- Structured `AGENT` child policy and conceptual navigation.
- Enforcement of agent-root and application-owned metadata policy across explorer, file, skill, Git, and lifecycle paths.
- Versioned context defaults and immutable materialization/context snapshots for conversations, assignments, runs, retries, replays, and resumes.
- Agent-local materialization identity, generation, lifecycle, repository claim, audit, and reconciliation state.
- Bounded in-process Git provision/clone, fetch, status, branch, commit, push, and provider-backed PR creation/status through the privileged repository-control storage boundary.
- Provider transport enforcement, repository serialization, cancellation, reconciliation, and truthful failure states.
- Context-aware prompt policy and continued `AGENTS.md` layering.
- Existing skills UI composition and materialization-authorized discovery without trust bypass.
- Agent-console composition for structure, context, Git status, queue facts, runs, and skills.
- Restricted system chat with confirmed queue operations.
- Agent-scoped API and fragment contracts: versioned context default/materialization actions, captured-context submission fields, bounded Git/PR operations, explicit unavailable/busy/conflict responses, and secret-free status payloads.
- Required specifications, decision record, documentation, tests, startup checks, and browser evidence.

### Out of scope

- A `PROJECT` workspace kind or project filesystem roots.
- Shared non-Git project storage, synchronization, or multi-agent writable roots.
- Cross-agent file/repository browsing or administration.
- Symlink, bind-mount, overlay, or virtual `/current-workspace/` semantics.
- Native Git CLI, `shell_exec` Git, arbitrary providers/protocols, submodules, LFS, external filters, alternates, or nested repository automation.
- Automatic conflict resolution, autonomous approval, unattended merge, or merge without server-verifiable human authorization.
- Script/app execution, deployment, package ingestion, or executable skill authority.
- Automatic migration or relocation of existing root files.
- Broad agent-console consolidation or replacement of queue, run, skill, and explorer domain services.

## Assumptions

- GitHub or GitHub Enterprise is configured; unsupported provider requests return unavailable.
- Deployments supply Git credentials through external server-side secret references. Public read operations may work without credentials; protected operations remain unavailable without valid credentials.
- Deployments that expose merge provide an authenticated human identity the server can verify; otherwise merge remains unavailable.
- `scripts/` and `apps/` are storage namespaces only.
- `projects/` contains agent-local non-Git materializations with no synchronization guarantee.
- Unrecognized agent-root entries may be read where existing policy permits but cannot be mutated through this capability.
- Repositories using unsupported features or violating checkout/confinement policy are rejected rather than partially materialized.
- Browser requests carry context/default version tokens and receive `409` on stale state.
- The in-process Git library can be made to satisfy the privileged repository-control boundary; if verification disproves this, Git mutation remains unavailable rather than falling back to direct path or shell operations.
- Existing local/open-alpha access posture does not remove agent, resource, context, or provider scope checks.

## Recommended Direction

Implement focused domain boundaries rather than a generalized workspace framework:

- Extend existing layout and mutation authorities with a closed agent-child policy.
- Introduce small materialization and context aggregates. Materialization identity/generation is authoritative; immutable binding snapshots carry context facts; `WorkspaceDescriptor` remains unchanged.
- Bind context atomically at conversation and execution submission. The binding snapshot, not a dashboard default or mutable context record, flows through prompts, queue displays, runtime state, retry, replay, and resume.
- Keep Git behind a dedicated managed local-operation service and provider API boundary. Introduce the repository-control storage boundary as its only repository-control writer. Do not wrap shell execution.
- Make repository lifecycle explicit: provisioning, ready, busy, dirty, blocked, failed, and reconciled.
- Append new migrations after the existing sequence and update the migration contract, fresh-schema equivalence tests, and upgrade tests without modifying applied resources.
- Reuse assignment, task-run, skill, and Files-tab composition instead of duplicating those domains.
- Use separate HTMX hosts for explorer, Git metadata, queue state, runs, and skills, each with independent scope validation.
- Treat generated prompt context and `WORKSPACE.md` as explanatory input; enforce authority in services, repository-control storage, lifecycle, provider policy, and authentication boundaries.
- Preserve capability gates. Missing secret, absent verified human identity, unsupported repository feature, stale context, busy materialization, failed integrity check, provider endpoint violation, or unavailable process confinement produces an explicit non-success state.

## Validation

Completion requires all applicable checks below to pass.

### Workspace and confinement

- Test exact canonical-child and reserved-name rules for agent roots.
- Keep existing `CHAT`, `USER`, task-run, output, and skill contracts green.
- Prove conceptual-child reads create no filesystem entries and authorized writes create only required canonical chains.
- Reject root, unknown root child, `.skills`, `tasks`, `outputs`, repository-control, and application-metadata mutation through every exposed route and tool path.
- Cover absolute paths, traversal, encoded separators, symlink components, hard links, rename races, deleted/recreated paths, stale descriptors, and path rebinding.
- Inventory all filesystem writers; prove ordinary writers use confined mutation services and repository-control writes use only the verified managed-Git storage boundary.

### Context, materialization, and lifecycle

- Prove default changes affect only new bindings.
- Prove conversations, assignments, runs, retries, replays, and resumes retain correct immutable context snapshot semantics.
- Prove stale context/default tokens and multi-tab actions return controlled conflict without retargeting work.
- Prove missing, revoked, deleted, failed, dirty, blocked, or generation-mismatched materializations fail closed.
- Prove canonical paths are derived from authoritative materialization identity and cannot be substituted by persisted or caller-supplied paths.
- Keep assignment leases and task-run writer fences independent and enforced.
- Verify fresh and upgraded schemas for migration append order, checksum behavior, rollback, transaction behavior, and schema equivalence.

### Git and provider security

- Reject unsupported schemes, hosts, organizations, repositories, credential-bearing URLs, redirects, local/private/rebound endpoints, submodules, filters, LFS, alternates, nested control paths, and symlink checkout.
- Exercise clone/fetch cancellation and configured resource bounds.
- Prove explorer and file tools cannot mutate `.git` or equivalent metadata; prove Git cannot use an unbrokered control-data write path.
- Prove one repository owner obtains mutation access and competitors receive busy/conflict.
- Exercise crash, cancellation, dirty worktree, lock residue, corrupt repository, partial clone, expired credential, provider outage, and reconciliation paths with explicit recoverable or blocked state.
- Cover protected branches, force update, tags, branch collisions, stale base, changed head, provider races, and repeated operation keys.
- Prove logs, payloads, fragments, audits, and exceptions omit credentials, internal endpoints, and sensitive transport values.
- Revalidate provider facts at push, PR, and merge authorization boundaries.
- Prove merge rejects missing authenticated human identity, missing confirmation, stale head, insufficient review/check state, and unauthorized actor.

### Prompts, skills, and system chat

- Verify deterministic prompt ordering, provenance labels, bounded size, context snapshot version, derived canonical path disclosure, and generated-policy precedence.
- Verify `AGENTS.md` remains confined and ancestor-inclusive; repository/project guidance cannot grant tools or bypass policy.
- Verify skill discovery, digest approval, snapshots, revocation, and activation retain behavior for agent and materialization-authorized sources; project association alone must not authorize discovery.
- Prove system-chat tool registration is deny-by-default and rejects file mutation, Git, shell, merge, and skill-trust operations.
- Prove system-chat queue mutations require exact target scope, idempotency, confirmation, authorization, and audit.

### API, UI, and application validation

- Cover controller/API scope, pairing, validation, expected versions, conflicts, unavailable capabilities, busy states, operation idempotency, and secret-free responses.
- Run focused Playwright validation against changed `/agents/{agentId}` surfaces while the application is live.
- Capture desktop and mobile screenshots for structural navigation, absent children, permitted and rejected file operations, context switching, stale actions, repository status, busy/blocked states, queue composition, skills composition, and system-chat denials.
- Perform a visual critique of first-viewport density, spacing, alignment, wrapping, overflow, affordance, mobile stacking, and consistency with the operational console.
- Verify HTMX owns standard CRUD, filtering, refresh, selection, and fragment replacement; document any narrowly justified JavaScript.
- Run focused tests, the broader Maven suite, relevant static checks, and bounded Spring Boot startup.
- Review the final diff for new workspace kinds, project roots, generic workspace identity, raw-shell Git, direct repository-control writes, credential leakage, placeholder behavior, migration rewriting, and unrelated API growth.

## Open Questions

No blocking questions remain. The following are deliberately deferred and must not be inferred into scope:

- Whether a proven collaboration workflow justifies shared non-Git project storage.
- Whether process confinement supports a bounded native Git fallback.
- Whether additional Git providers or repository features should be supported.
- Whether a virtual current-workspace path is needed after canonical-path UX is validated.
- Whether deterministic policy can permit unattended merge for explicitly approved low-risk repositories.
- Whether cross-agent administrative repository visibility is needed once authentication and authorization exist.

Each item requires a separate contract and security decision based on demonstrated product need.

## Sign-off

This corrected handoff is ready for implementation planning only when treated as the governing scope and security contract, not as permission to restore removed workspace abstractions.

Sign-off confirms that:

- The closed three-kind workspace model and agent-local materialization are non-negotiable.
- Root and repository-control immutability are service-enforced, not prompt-enforced.
- Context is versioned, paths are derived, and each unit of work captures an immutable binding snapshot.
- Git is structured, provider-restricted, secret-safe, and process-free, with repository-control writes limited to the verified privileged boundary.
- Skills retain explicit digest-bound trust and materialization-backed discovery never makes project association authoritative.
- Merge remains deterministic, freshly provider-validated, and server-verifiable-human-authorized.
- UI composition reuses the agent console, queue, run, skill, and explorer domains.
- All listed security, lifecycle, migration, startup, API, and browser validation criteria are completion gates.

**Explicit sign-off: the corrected handoff above is approved as the complete implementation-planning contract.**
