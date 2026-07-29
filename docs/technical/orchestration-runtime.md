# Orchestration Runtime

The orchestration runtime is the durable operating layer for agents, assignments, jobs, inboxes, schedules, reactions, projects, runtime settings, workspace ownership, and retained execution history. Source anchors are [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime), [`ai/orchestration/agents`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/agents), and [`AgentOrchestrationController`](../../src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java).

## Agents

Agent profiles are durable records in `agent_profiles`. `AgentProfileService` owns CRUD and validation. A profile has:

- Name and status.
- Default model.
- System prompt text.
- Approved tool names.
- Allowed shell commands.
- Direct-line enabled flag.

`AgentProfileSeeder` imports a legacy file-configured default agent only when the durable profile store needs a seed. File config remains the model endpoint source, not the durable agent state source.

## Assignments

Assignments are stored in `work_assignments` and represented by `WorkAssignment`.

Core fields:

- `agent_id`, optional `job_id` and `job_item_id`.
- `assignment_type`: task, workflow, job, or other runtime work type.
- `priority`, `status`, `model_override`, first-class `project_id`, `workspace_type`, `workspace_key`, and internally derived `workspace_identity`.
- `current_item_index`, `checkpoint_json`, input/output/evidence JSON.
- `error_text`, lease owner/expires, progress/heartbeat timestamps.
- Created, updated, started, completed timestamps.

`AssignmentService` owns creation, queue reads, retained history reads, lifecycle transitions, deletion, purge, diagnostics, transcript lookup, and assignment-to-conversation links.

`projectId` is membership and attribution metadata only. Execution selection uses the nullable-together `workspaceType`/`workspaceKey` pair; omission selects the bound agent. Canonical identity is derived internally.

`AssignmentService.AssignmentSummary` is the UI-facing read model for queue/history/project context. It exposes assignment id, agent/job ids, status, `projectId`, typed workspace selection and identity, linked run ids, and lifecycle timestamps.

## Queue Lifecycle

Assignments enter the queue through API or runtime triggers:

- Plan/task/workflow/job submit routes create assignments.
- Schedules create assignments when due.
- Event reactions can create assignments when matching events are handled.
- Agent direct submission fragments create assignments from operator UI.

Status values are represented by `OrchestrationStatus`. Lifecycle controls include cancel, pause, resume, guarded delete, retained history purge, diagnostics, transcript fragments, and force-interrupt from the operational UI.

The runtime stores retained terminal history separately from active queue reads. History can be purged by age through assignment history APIs and settings.

Assignment queue leases coordinate runner ownership. Filesystem workspace lease blocking and workspace-blocked requeue behavior are not part of active execution.

## Leases and Progress

Assignment queue leasing is DB-backed and independent from filesystem workspace leases. Assignment lease fields (`lease_owner`, `lease_expires_at`, progress and heartbeat timestamps) allow runner ownership and stale-run diagnostics.

Obsolete filesystem lease tables may remain in existing databases but are inert for active execution.

## Execution Boundaries

`OrchestrationRunnerService` executes assignment work at durable boundaries:

- Task assignments execute through task/chat execution services.
- Workflow assignments execute through `WorkflowRunner`, preserving `WAITING` assignment state for resumable approval/wait runs.
- Job assignments execute ordered job items and can continue/fail based on item policy.

The runtime does not attempt token-level or partial model response resume. Durable recovery is at task, workflow, and job item boundaries using assignment checkpoints and run records.

Every execution attempt resolves one descriptor from its persisted `workspaceType`/`workspaceKey` selection. Task, workflow, and job submission reserves the immutable native run id as `taskRunId` before queue dispatch. The runner then loads that reservation, provisions `tasks/<taskPathKey>/<taskRunId>/` through `WorkspaceDirectoryService`, acquires a monotonic writer claim independent from the assignment lease, and carries the descriptor, trusted locations, attempt, and opaque capability through execution. Lifecycle setup fails closed.

Operation keys are scoped by operation type. A supplied key retries one submission; omitted keys are generated uniquely so two intentional submissions with identical definitions and inputs remain separate. Resume reuses the run/location and opens a new attempt. Operator retry/replay create new lineage-linked sibling runs. Workflow task children and job work items also receive typed links and sibling lifecycle roots. File, output, checkpoint, terminal, force-interrupt, and stale-lease paths verify current fences; responses never expose paths, owners, fence generations, or tokens. Native run tables remain status truth. Run-local artifact publication is active with reservation, staging, digest/size verification, create-only promotion, reconciliation, and retention safety. Host shell/process execution remains fail-closed because process confinement and process-tree revocation are not proven.

## Jobs

There are two job-related schemas:

- User-facing `job_definitions`, `job_runs`, and `job_recurrences`, owned by `JobService`/`JobRepository`.
- Runtime legacy/internal `orchestration_jobs` and `orchestration_job_items`, owned by `OrchestrationRuntimeRepository`.

Current public job APIs use `JobDefinition` and `JobWorkItem`. Public job execution submits `AssignmentType.JOB_RUN`. Job items can reference tasks or workflows, include model override and priority, and persist retry count plus continue-on-failure policy. Empty job submissions are valid no-op executions: the assignment-owned `job_runs` row moves to `COMPLETED` with an empty item-run list.

User-facing jobs are task-like executable work bound to an agent and optional project metadata. Definitions and runs persist workspace type, key, and canonical identity. Jobs own no directory and create no eager output tree.

`JobExecutionSummary` bridges the definition, assignment, agent/project attribution, typed workspace selection, job run status, child run ids, output count, and lifecycle timestamps. It exposes no compatibility workspace id or host path.

Job recurrence/start behavior is assignment-routed. Recurrence firing enqueues `JOB_RUN` assignments and advances recurrence timestamps; direct job run allocation is reserved for assignment-owned runner execution. `owner_agent_id` is the sole persisted preferred executor and applies only to later submissions; absent preference requires an explicit executor rather than an arbitrary fallback. Definition updates use `definition_version` CAS where an operator is updating a current view, and runners never write definition lifecycle status.

`JobReassignmentService` atomically revokes and replaces only a queued, unleased, nonterminal source assignment with no `JobRun`. It binds idempotency to the server actor, project scope, source, destination, and expected assignment version; source revocation, a destination-agent context capture, fresh lifecycle reservation, successor, and durable operation record commit together. The source row is never retargeted, and its workspace, checkpoint, lease, outputs, artifacts, results, and cancellation state are not copied. A failed successor creation rolls back the revoke. Running, leased, terminal, native-run, archived, out-of-scope, and ineligible-member cases fail closed.

The alpha mutation policy is conservative. Project deletion and membership removal are blocked while active work references the project or member agent. Job deletion and execution-affecting edits, including item changes, project/default agent, recurrence, and model changes, are blocked while non-terminal job assignments or runs exist. Label-only edits remain allowed where services permit them.

## Inboxes

There are two inbox models:

- Runtime direct-line agent inbox: `agent_inbox_messages`, owned by `ai.orchestration.runtime.InboxService`. Used by `/api/agents/{agentId}/inbox` and operational agent inbox surfaces.
- Workflow approval inbox: `inbox_messages`, owned by `ai.orchestration.workflow.InboxService`. Used by workflow user/agent approval nodes and `/api/users/inbox`.

Keep these separate. They have different recipients, lifecycle flags, and use cases.

## Schedules

`ScheduleService` owns revisioned `schedule_definitions`, `scheduler_claims`, `schedule_firings`, and `schedule_firing_attempts`. A firing identity includes schedule id, revision, slot instant, and timezone. Operation-key digest idempotency and claim fences prevent duplicate enqueue for the same fact; retry/recovery attempts remain append-only audit. Timezone, DST gap/overlap, and misfire semantics are explicit. Simulation is pure and creates no firing, assignment, tool call, artifact, or external mutation.

API routes under `/api/agents/{agentId}/schedules` are gated by `magenta.features.schedules-enabled`; disabled routes return `404`.

## Event Reactions

`EventReactionService` owns revisioned `reaction_definitions`, `reaction_dispatches`, and `reaction_dispatch_attempts`; `OrchestrationEventService` owns source events. Dispatch identity uses persisted event/reaction/revision/causation facts with operation-key digest idempotency. Root-event, parent, depth, rate, and dedupe checks suppress loops and storms before external work is enqueued. Simulation is non-executing and does not create dispatch audit rows.

API routes under `/api/agents/{agentId}/event-reactions` are gated by `magenta.features.reactions-enabled`; disabled routes return `404`.

## Projects

`ProjectService` owns projects, memberships, events, and project-network gating.

Projects are durable membership, tracking, and visibility abstractions. They are not executable work units and do not select or allocate execution filesystems. `ownerAgentId` remains nullable legacy project metadata.

## Runtime Settings

`RuntimeSettingsService` owns persisted runtime defaults:

- Default agent/model.
- Planning, summary, and compaction model choices.
- Context buffer percent.
- System-chat enabled state and model. Legacy stored prompt, approved-tool, and context-limit values do not affect the fixed server-owned system-chat profile.
- Assignment history auto-purge days.

The settings service falls back to legacy file config where persisted values are unset.

## Operator Visibility

Operational surfaces expose:

- Files-adjacent bounded run groups and on-demand detail through `RunOperationsFragments`; `OrchestrationController` scopes agent/type/key before querying `RunSummaryService`, while `WorkspaceExplorerService` remains filesystem-only.
- Rebuildable summaries, normalized health, per-invocation model/tool measurements, attempts, exact skill/artifact/causation audit, and explicit partial/unavailable states.
- Queue and retained assignment history.
- Assignment diagnostics and audit transcript fragments.
- Inbox message counts and actions.
- Jobs, job runs, outputs, project events, workspace summaries.
- Runtime status and settings.

Controllers should keep those surfaces as transport/read-model adapters and delegate state changes to services.
