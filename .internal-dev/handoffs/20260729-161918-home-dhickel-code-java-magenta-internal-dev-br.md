# Project Dashboard and Unified Work Contracts — High-Level Handoff

## Context

Magenta’s project console currently provides Dashboard, Members, Files, Jobs, Outputs, and Settings. The requested direction expands it into a comprehensive operational console spanning Queue, Tasks, Jobs, Outputs, Files, Chat, Schedules, and Events while preserving the existing orchestration, workspace, lifecycle, and SimplyPages contracts.

The core architectural opportunity is already present: `WorkAssignment` carries both agent and project attribution. Agent and project views can therefore project the same canonical execution row through different mandatory scope predicates. The design must strengthen that contract rather than introduce project-specific execution stores or a universal work-item schema.

The brainstorming synthesis is a candidate direction, not yet an implementation contract. Its red-team review identified several prerequisites that must be resolved from repository evidence: field-level runtime authority, immutable assignment attribution, task-attempt lineage, temporal membership rules, recoverable reassignment, authorization or deployment gating, stable pagination, and schema/index feasibility.

## Objective

Define and deliver a project operational console whose agent and project surfaces agree on execution identity, status, priority, lineage, transcripts, automation attribution, and outputs.

The resulting system must:

- Preserve one canonical runtime truth for submitted work.
- Keep Tasks, Plans/Workflows, Jobs, and Assignments as distinct concepts.
- Expose project views as bounded projections over canonical records.
- Preserve the closed `AGENT`, `CHAT`, and `USER` workspace model.
- Provide durable, idempotent, fenced lifecycle behavior.
- Follow SimplyPages composition and HTMX-first interaction patterns.
- Implement project chat reusing the existing agent system-chat component pattern.
- Fail closed where authorization, process execution, workspace authority, or lifecycle preconditions are absent.

## Targets

### Contract and persistence targets

- `WorkAssignment` and its repository/service query boundary.
- Assignment-to-definition, assignment-to-run, assignment-to-conversation, lineage, output, and audit links.
- Lightweight Task definitions and their canonical execution link.
- Existing Job definition/run models and the compatibility boundary between them.
- Schedule, reaction, and event definitions that require explicit project attribution.
- Project membership, archive, deletion-guard, and historical-access rules.
- Project materialization template metadata without project filesystem ownership.

### Service and API targets

- One internal assignment query contract with structurally mandatory route scope.
- Agent- and project-scoped assignment summaries sourced from that contract.
- Project-scoped read and mutation routes with explicit resource validation and capability checks.
- Task CRUD, submission, priority, pin, and preferred-agent behavior.
- Job run drill-down, preferred-agent changes, and safe reassignment.
- Project-scoped schedule and reaction CRUD, simulation, and audit access.
- Read-only transcript, Files, and Outputs access through canonical links.
- Project chat reusing the agent system-chat component pattern with SSE streaming.

### UI targets

Use grouped project navigation rather than eleven flat tabs:

- **Overview:** Dashboard
- **Work:** Queue, Tasks, Jobs, Outputs
- **Context:** Files, Chat
- **Automation:** Schedules, Events
- **Administration:** Members, Settings

Retain the selected-tab shell while giving interactive tabs stable, host-scoped list and inspector targets. Queue, Tasks, Jobs, Files, Schedules, and Events must refresh only their own hosts.

## Features

### Project Queue

- Display submitted assignments only; unsubmitted Task, Plan, Workflow, or Job definitions do not belong in the Queue.
- Default to active/nonterminal assignments, with terminal history available through a separate bounded filter.
- Sort active work by explicit state rank, priority descending, creation time ascending, and assignment ID ascending as the unique tie-breaker.
- Use cursor-based pagination with enforced limits; do not expose an all-optional or unbounded search endpoint.
- Show a shared secret-minimized assignment summary on agent and project surfaces.
- Compute available actions server-side from route scope, caller capability, assignment state, and concurrency preconditions.
- Provide project-scoped read-only transcript and diagnostics views after verifying immutable project attribution.
- Allow priority changes only for mutable, nonterminal, unleased work, with idempotency, optimistic concurrency, and audit.
- Reuse the existing cancellation lifecycle: direct `CANCELLED` for eligible non-running work and `CANCEL_REQUESTED` for leased running work. Project controls must not bypass lease or fence rules.

### Tasks

- Introduce a lightweight user-managed Task definition distinct from AI-authored Plans/Workflows and multi-item Jobs.
- A Task has a title, description, one optional project binding, pin state, default priority, optional preferred agent, timestamps, and canonical execution links.
- A project Task belongs to at most one project. Its project binding is immutable once execution history exists; moving work to another project requires a new definition with explicit lineage or provenance.
- Pin means project visibility and prominence only. It does not alter queue position, lease behavior, reassignment, restart behavior, or workspace selection.
- Unassigned Tasks report an `OPEN` definition state. Once submitted, displayed execution state is derived from the designated current canonical assignment rather than copied into an independent task lifecycle column.
- Enforce at most one active assignment per Task transactionally. Retries and reassignments create new attempts linked to the Task; a current-assignment reference selects the attempt used for display.
- Task default priority is copied into a new assignment. Editing the Task default affects subsequent submissions only; changing an active assignment uses the Queue priority operation.
- For a project-bound Task, a preferred agent must be a current member of that project. For an unbound Task, a preferred agent must be an explicitly named agent eligible under the established agent-submission contract. Automatic agent or member-pool selection is out of scope in both cases.
- Task-to-Plan promotion is explicit and linked. It must not silently convert every Task into a Plan.

### Jobs

- Keep Job definitions, work items, runs, and assignments distinct.
- Inventory both existing Job models and establish a field-level authority matrix before adding writes. The matrix must settle authority for definition state, run identity, assignment status, priority, owner/executor, cancellation, retry, outputs, and terminal result.
- Select exactly one authority for new writes. A temporary compatibility adapter may expose legacy reads, but dual writes and an indefinite union abstraction are prohibited.
- Job-model consolidation is not part of this scope.
- Changing a Job definition’s preferred agent affects subsequent runs only and never rewrites historical assignments.
- Reassignment uses revoke-and-clone, never in-place mutation of `WorkAssignment.agentId`.
- Exposed reassignment is limited to queued or otherwise unleased work unless the durable running-work protocol is separately proven. Running reassignment is semantically cancel-and-restart, not migration or resume.
- A replacement receives a new assignment, run, lease, immutable context binding, and workspace selection. Historical artifacts remain attached to the original run.
- Reassignment must be represented as a recoverable, idempotent operation with explicit lineage and no gap that can permanently cancel the old assignment without recording the replacement outcome.

### Files and materialization template

- Projects do not gain a workspace kind, root path, or shared writable directory.
- The Files tab remains a single-member, read-only browser over a current, `READY`, generation-validated agent materialization.
- Project creation stores a declarative materialization template; it does not create a physical project directory.
- The accepted template format is directories only: normalized relative paths beneath the materialization root. Absolute paths, `..`, symlinks, seed-file content, executable content, and overwrite directives are rejected.
- Each member agent applies the validated template inside its own confined materialization before that generation becomes `READY`.
- Template changes apply only through a new materialization generation. They do not mutate historical or currently bound generations.
- Reads must remain protected against traversal, symlink escape, stale generation access, non-ready state, and unauthorized member selection.

### Outputs

- Discover project outputs through durable assignment/run/output metadata, not by scanning or inferring across member filesystems.
- Preserve the effective workspace as the output authority and display project attribution as metadata.
- Project output views are read-only in this scope. Retention and deletion continue to follow the canonical output owner’s contract.

### Schedules and Events

- Extend the existing revision-qualified schedule and reaction definitions with an optional indexed `project_id`; do not create a parallel project automation engine or mapping layer without a confirmed many-to-many requirement.
- `agent_id` remains the explicit executor/owner. `project_id` is immutable attribution and query scope for a definition revision.
- The executor must be a project member when a project-scoped revision is created and when it fires. If membership or eligibility is lost, firing fails audibly and is recorded; no replacement agent is inferred.
- Project reactions match only events carrying trusted, immutable attribution to the same project. Current agent membership must never be used to infer historical event scope.
- Define explicit schedule modes:
  - `CRON` carries a six-field cron expression and IANA timezone.
  - `ONE_TIME` carries one `fireAt` instant, normalized from the user’s selected timezone.
- Do not encode one-time behavior as a cron expression that may repeat.
- Both modes reuse revision identity, owner/fence claims, operation-key idempotency, simulation, and append-only attempt audit.
- Automation guarantees at-least-once attempts with idempotent effects, not impossible exactly-once external side effects.
- Events presents both reaction configuration and bounded dispatch/event audit views.
- Existing schedule/reaction feature flags remain the rollout seam; disabled features render controlled unavailable states with no active controls.

### Project Chat

- Implement a project-scoped expandable dropdown chat interface reusing the existing agent system-chat component pattern (`SystemChatFragments`, `SystemChatService`, `SystemChatController`).
- The chat is keyed by `projectId` and bound to an explicitly selected current project-member agent.
- Persist the selected agent for the conversation. Do not silently switch agents mid-conversation; selecting another agent creates a new conversation.
- Use the selected agent's workspace plus an immutable project materialization binding for context. Do not create a project workspace.
- Define routes under `/api/projects/{projectId}/chat/...` mirroring existing agent system-chat conventions (panel load, history, SSE stream).
- Carry conversation identity, message text, selected agent, and idempotency data in structured request payloads.
- Use a server-owned, deny-by-default profile permitting bounded reads of project metadata, membership, materialization state, assignments, Jobs, Schedules, Reactions, Outputs metadata, and transcripts.
- Deny filesystem writes, process execution, skill activation, package ingestion, and unrestricted tool access.
- Render the chat panel as a collapsible/expandable dropdown host (`#project-chat-panel`) with the same visual and interaction pattern as the agent system-chat panel, including bounded history (`#project-chat-history`), SSE streaming, and user-confirmable queue-operation proposals where applicable.
- SSE streaming uses a narrow JavaScript module (`project-chat.js`) following the same safe `textContent` and bounded event-parsing conventions as the existing `system-chat.js`. No other JavaScript transport is added.

## Settled Decisions

1. `WorkAssignment` is the intended canonical execution, queue, priority, status, lease, and assignment-ownership record, subject to a repository-verified field-authority matrix for existing Job/run consumers.
2. Assignment attribution fields are immutable after submission: agent, project, assignment type, source-definition link, workspace selection, and immutable context binding. Lifecycle state, priority, leases, checkpoints, outputs, evidence, and terminal results are mutable only through their owning fenced services.
3. Project attribution is captured at submission and is never recomputed from current project membership.
4. Agent and project queues are filtered views of the same assignment row. Route scope is mandatory and cannot be omitted or overridden by client filters.
5. Definitions are not queue entries. Tasks, Plans/Workflows, Jobs, and Assignments remain distinct.
6. Lightweight Tasks are first-class definitions. Pin is a project-local visibility/prominence flag, not an execution policy. A preferred agent is valid only when it satisfies the explicit project-member rule for project-bound Tasks or the established agent-submission eligibility rule for unbound Tasks; neither case implies automatic selection.
7. Job-model consolidation is deferred; one verified model must own new writes before Job enhancements proceed.
8. Reassignment is revoke-and-clone with durable operation state and explicit lineage. In-place agent mutation is forbidden.
9. Running reassignment is not exposed as transparent migration. Any supported form is explicit cancel-and-restart after writer fencing is proven.
10. Schedules and reactions use optional `project_id` columns while retaining explicit agent execution ownership.
11. One-time schedules use an explicit mode and fire instant, not synthetic cron.
12. Project events require trusted project attribution; membership inference is insufficient.
13. Projects never become workspace authorities. Materialization templates are declarative metadata applied within agent-owned generations.
14. Files, transcripts, and project output views are read-only. “Project views are read-only” does not apply to explicitly authorized Queue, Task, Job, Schedule, or Reaction controls.
15. Grouped navigation and stable per-surface HTMX sub-hosts are the project-console direction.
16. Project chat is implemented by adapting the existing agent system-chat component pattern to project scope with a selected member agent, deny-by-default profile, and auto-scrolling collapsible dropdown panel.
17. No mutation or sensitive read is exposed merely because resource scope matches. Real caller authorization or an explicit trusted-deployment gate is required; otherwise the capability remains disabled.
18. Project archive preserves historical assignments, transcripts, outputs, and audit. Hard deletion or cascading destruction of operational history is out of scope.

## Constraints

- Preserve the closed workspace-kind model: `AGENT`, `CHAT`, `USER` only.
- Use `WorkspacePathLayout` and `WorkspaceDirectoryService` as the sole trusted workspace construction path; never infer workspace identity from arbitrary `Path` values.
- Preserve immutable context bindings, generation-safe materializations, assignment leases, writer fences, and canonical task-run identity.
- Do not add parallel assignment, schedule, reaction, run, transcript, output, or project-workspace stores.
- Do not expose leases, fences, checkpoints, internal paths, raw evidence, secrets, or unrestricted error content in list payloads.
- Treat titles, descriptions, transcripts, output metadata, and filenames as potentially sensitive even when a DTO is otherwise secret-free.
- All mutation families use operation-key plus canonical-digest idempotency with actor, action, resource, revision, and request semantics bound into the key/digest contract.
- Standard CRUD, filtering, selection, simulation, and lifecycle controls are HTMX-first. JavaScript remains narrowly scoped: only the project-chat SSE stream module (`project-chat.js`) following the existing `system-chat.js` safe-rendering conventions.
- Reuse SimplyPages components, render contexts, SlotKeys, semantic chips, empty states, selectors, and inspector patterns. Do not fall back to raw HTML strings where the library supports the behavior.
- Avoid a broad rewrite of the orchestration controller or existing project shell as a prerequisite. Extract only proven reusable rendering seams needed by these surfaces.
- Preserve existing feature flags and fail-closed process-execution behavior.
- No universal `work_items`/`work_runs` migration, automatic member-pool scheduling, multi-agent Task fan-out, project filesystem writes, or multi-user authorization system is included.

## Scope

### In scope

- Unified, bounded assignment query and summary contracts.
- Read-only Project Queue proof plus mutation controls only after authority, concurrency, and access gates are satisfied.
- Lightweight project Tasks with pin, priority, preferred agent, submission, and execution lineage.
- Project Job visibility, run drill-down, preferred-agent updates, and safe queued/unleased reassignment.
- Project Files refinement and directories-only materialization templates.
- Metadata-backed project Outputs view.
- Project-scoped Schedules and Reactions with explicit executor, simulation, and audit.
- Grouped project-console navigation and stable list/inspector HTMX hosts.
- Project chat with expandable dropdown panel reusing the existing system-chat component pattern.
- Specifications, decisions, API/web documentation, migrations where proven necessary, tests, startup validation, and focused Playwright review when implementation is authorized.

### Out of scope

- A project workspace kind or shared writable project root.
- Project file editing or cross-member merged filesystem views.
- Job-table consolidation or broad migration of existing Job/run models.
- A universal discriminator-based work-item/run schema.
- Transparent migration of running work between agents or transfer of partial workspace state.
- Automatic executor selection from project members.
- Multi-project Task pinning and multi-agent Task execution.
- Exactly-once guarantees for arbitrary external side effects.
- Broad multi-user identity/role/authorization design; sensitive functionality remains gated until such authority exists.
- A console-wide controller or host architecture rewrite unrelated to the delivered surfaces.

## Assumptions

- `work_assignments.project_id` is already persisted for project-attributed work, but indexes and complete query support must be verified.
- Existing assignment cancellation, pause/resume, lease, fence, idempotency, transcript-link, and run-lineage primitives can be reused once their exact service ownership is confirmed.
- Task definitions belong to zero or one project; project-created Tasks use exactly one immutable project binding once execution exists. A project-bound Task’s preferred agent is a current project member; an unbound Task’s preferred agent is an explicitly named agent eligible under the established agent-submission contract.
- Project members may view project-attributed operational history only when the application’s access boundary permits it. Current membership is required for active mutations but does not rewrite historical attribution.
- Removing an agent or archiving a project is blocked while that action would strand active assignments or enabled automation, unless an explicit audited disable/cancel operation resolves those dependencies.
- The application can render a disabled capability when feature flags, authorization, or deployment gates are absent.
- Existing SimplyPages documentation and demos remain the implementation baseline for grouped navigation, list/detail layouts, selectors, modals, OOB swaps, and edit decorators.
- Any schema claim from the brainstorm is provisional until checked against the current repository and both fresh-install and upgrade paths.

## Recommended Direction

1. **Harden the contract before mutations.** Produce a field-level authority and immutability matrix covering assignments, both Job models, native runs, transcripts, outputs, cancellation, retries, and terminal results. Resolve conflicts in favor of one writer per field rather than masking them behind a shared DTO.
2. **Prove singular projection with a bounded read-only Queue.** Establish mandatory agent/project route scopes, stable cursor ordering, project transcript scope checks, shared summaries, and isolated HTMX hosts without claiming mutation safety or migration-free delivery prematurely.
3. **Enable controls only behind explicit gates.** Add priority, cancellation, Task submission, and Job reassignment only when operation idempotency, concurrency preconditions, caller capability, membership policy, and audit are testable. Validate preferred agents as project members for project-bound Tasks and as eligible named agents under the existing submission contract for unbound Tasks; do not infer a fallback executor.
4. **Keep domain definitions focused.** Build Tasks as lightweight definitions, preserve Plans/Workflows and Jobs for their current semantics, and use Assignments as the only execution lifecycle authority.
5. **Extend existing automation rather than duplicate it.** Add project attribution and explicit one-time mode to the existing revisioned schedule/reaction machinery; preserve the named executor and fail audibly when it is ineligible.
6. **Preserve filesystem confinement.** Treat the project materialization template as validated directories-only metadata and publish project Outputs through canonical artifact links.
7. **Compose the UI around operational use.** Use grouped navigation, dense list/inspector layouts, semantic chips, compact controls, isolated hosts, and responsive stacking. Implement project chat as an expandable dropdown panel matching the agent system-chat interaction pattern.
8. **Record durable decisions and drift.** The implementation contract must update the relevant architecture, services, API, web, SimplyPages, decisions, and deferred-feature specifications before code is treated as approved.

## Validation

### Contract and persistence

- Verify the runtime authority matrix against every writer and consumer of assignment, Job, run, transcript, output, cancellation, retry, and status data.
- Prove immutable attribution cannot change after submission.
- Prove the Task active-assignment invariant under concurrent submit, retry, and reassign requests.
- Validate project-bound preferred-agent membership and unbound preferred-agent eligibility, including absent preferences and no-fallback behavior.
- Validate schema migrations for both clean installation and upgrade from supported existing schemas, including indexes, foreign keys, rollback/failure behavior, and feature-disabled startup.
- Confirm project archive/member-removal guards preserve historical records and do not strand active work.

### Query and access safety

- Verify project routes always enforce a non-overridable project predicate and agent routes always enforce a non-overridable agent predicate.
- Test empty filters, hostile filter combinations, cross-project IDs, null project attribution, unauthorized callers, and resource-not-found behavior.
- Test cursor pagination under concurrent status and priority changes with no duplicate or skipped rows attributable to unstable ordering.
- Exercise representative queue volumes and confirm indexed bounded queries.
- Confirm summaries and error payloads exclude internal runtime fields and sensitive content not required by the view.

### Lifecycle and concurrency

- Verify same operation key plus same digest converges, while the same key plus a different digest conflicts.
- Test cancellation and reprioritization against stale versions, active leases, expired leases, and fence mismatches.
- Failure-inject reassignment at request creation, revocation, fence confirmation, context capture, replacement creation, and response replay. Repeated requests must never create multiple replacements.
- Confirm old workers cannot commit after revocation and that unavoidable external side effects are reported rather than represented as rolled back.
- Verify assignment/run/task/job lineage remains chaseable across retry, replay, cancellation, and reassignment.

### Automation

- Validate revision-qualified CRUD, simulation purity, claim/fence conflicts, restart recovery, duplicate firing suppression, timezone normalization, past `fireAt`, cancellation, and terminal audit retention.
- Verify project reactions ignore events without trusted matching project attribution and reject spoofed project identity.
- Verify create-time and fire-time executor membership checks, project archive behavior, and disabled/ineligible executor audit outcomes.
- Test both enabled and feature-disabled UI/API states.

### Files and outputs

- Test invalid template paths, absolute paths, traversal, symlinks, duplicate/conflicting paths, partial provisioning failure, generation activation, and retry recovery.
- Confirm a materialization is not `READY` until the accepted template is applied.
- Verify read-only Files behavior for current/non-current members, stale generations, failed/revoked materializations, traversal, symlink escape, preview, and download.
- Confirm Outputs are discovered from assignment/run metadata and never by broad filesystem inference.

### UI and application

- Validate canonical overlap end to end: create or mutate one assignment and observe the same identity and state through both agent and project projections.
- Test HTMX host isolation, stale/out-of-order responses, duplicate submissions, inspector invalidation, OOB target uniqueness, deep links, browser history, keyboard operation, and accessible labels/focus behavior.
- Run focused Playwright validation on a subagent while the application is live. Capture desktop and mobile screenshots and critique alignment, density, hierarchy, wrapping, overflow, controls, empty/error/disabled states, and first-viewport usefulness.
- Confirm standard interactions use HTMX and that no unjustified JavaScript transport is introduced.
- Run focused tests, broader relevant tests, type/lint/build checks where available, and a bounded Spring Boot application-context startup.

## Open Questions

These are evidence gates, not invitations to guess during implementation:

1. **Runtime authority:** Which existing Job model and service own new writes, and which fields or consumers still treat native Job/run rows rather than `WorkAssignment` as lifecycle authority?
2. **Access boundary:** What existing authentication or trusted-deployment mechanism can gate project transcripts, Files, Outputs, and lifecycle mutations? If none exists, those sensitive capabilities remain disabled.
3. **Schema feasibility:** Do current assignment, transcript-link, event, schedule, reaction, and output schemas contain the required attribution, stable cursor fields, foreign keys, and indexes? Any “no migration” claim depends on this inspection.
4. **Lifecycle removal behavior:** What current repository mechanisms support project archive, member removal, automation disablement, and historical reads without destructive cascades?
5. **Running writer quiescence:** Which existing fence/interrupt mechanism proves a running assignment can no longer commit before a replacement is created? Until proven, reassignment remains limited to unleased work.
6. **Legacy Job compatibility:** Which read adapter can preserve existing Job history without becoming a permanent second authority or forcing an unsafe consolidation?
7. **Unbound Task preferred agent:** Which established agent-submission eligibility rule governs an explicitly named preferred agent for an unbound Task, and where is that rule enforced consistently across API and UI submission paths?

## Sign-off

This handoff is ready for implementation planning only when:

- The field-level authority matrix is verified against current code and schema.
- Assignment immutability, route scope, membership, archive, idempotency, pagination, and preferred-agent eligibility contracts are documented as intended truth.
- A caller authorization or trusted-deployment gate is identified for every sensitive read and mutation.
- The authoritative Job write model and bounded legacy-read strategy are selected.
- Reassignment scope is explicitly limited to the protocol proven by tests.
- Project chat uses the adapted system-chat component pattern with deny-by-default profile and selected member agent.
- The closed workspace model, fail-closed execution posture, directories-only materialization-template rules, and no-fallback preferred-agent rule are accepted.
- Validation criteria above are incorporated into the implementation acceptance contract.

No project files were edited by this handoff.

**Explicit sign-off:** The corrected handoff is signed off for implementation planning only, subject to the listed evidence gates.
