
## Prompt

Explore the canonical domain, persistence, service, query, and API contracts that can unify tasks, jobs, assignments, queues, runs, transcripts, and status across agent and project projections. Generate options for identity, lifecycle ownership, task/job distinctions, shared record shapes, filtering boundaries, and backward-compatible evolution while preserving one source of truth. The target is the Project Dashboard and Unified Work Contracts Sprint: expanding the project console from 6 to potentially 10+ tabs (adding Queue, Chat, Tasks, Events, Schedules) and ensuring every cross-cutting view reads the same canonical row through a single persistence boundary.

## Source

- `.internal-dev/specifications/architecture.md` — workspace identity (closed AGENT/CHAT/USER), task-run canonical identity matrix, cross-domain operational read model
- `.internal-dev/specifications/service-graph.md` — service edges, active consumer inventory, operational composition edges
- `.internal-dev/specifications/services.md` — service contracts for assignment lifecycle, execution selection, task-run resolution, schedule/reaction services, output artifact persistence
- `.internal-dev/specifications/api.md` — typed workspace routes, execution workspace selection rules, lifecycle operations, operational REST/fragment routes, absent route families
- `.internal-dev/specifications/web.md` — agent console composition (6 independently loaded HTMX hosts), project operational console (6 tabs), fragment host contracts
- `.internal-dev/specifications/decisions.md` — filesystem-first identity, immutable context bindings, snapshot-only skill approval, revision-qualified schedule/reaction identity
- `.internal-dev/knowledge/dashboard-api-contract.md` — dashboard shell composition, HTMX partial pattern, 30s refresh contract
- `.internal-dev/knowledge/agent-scoped-assignment-lifecycle.md` — route-agent ownership checks for assignment mutations
- `.internal-dev/knowledge/task-workflow-schema-and-run-snapshots.md` — schema-owned DDL, snapshot-based run records
- `.internal-dev/knowledge/orchestration-runtime-phase-01.md` and `phase-02.md` — runtime settings, durable assignment lifecycle
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/WorkAssignment.java` — 28-field assignment record with agentId, projectId, workspaceType/key/identity, status, lease, checkpoint, timestamps
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentRequest.java` — submission DTO with agentId, projectId, workspaceType/key, operationKey
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java` — query methods: findAssignmentsForAgent, findQueueAssignmentsForAgent, findActiveAssignmentsForProject, findActiveAssignmentsForProjectAndAgent, findActiveAssignmentsForWorkspaceIdentity
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java` and `ProjectRepository.java` — project CRUD, membership, network gating (no filesystem authority)
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobDefinition.java`, `JobRun.java`, `OrchestrationJob.java` — two job models with workspaceType/key/identity pattern
- `src/main/java/io/mindspice/magenta2/api/web/ProjectController.java` — existing project REST API surface
- `src/main/java/io/mindspice/magenta2/api/web/selector/EntityKind.java` — entity selector kinds: AGENT, PLAN, TASK, WORKFLOW, JOB, PROJECT, MODEL, RUN, TARGET
- `src/main/java/io/mindspice/magenta2/api/web/DashboardController.java` — dashboard summary composing cross-domain facts

## Findings

### 1. The cross-cutting assignment query pattern already partially exists

`WorkAssignment` carries both `agentId` and `projectId`. `OrchestrationRuntimeRepository` already has `findActiveAssignmentsForProject(projectId)` and `findActiveAssignmentsForProjectAndAgent(projectId, agentId)` alongside the agent-scoped query methods. The missing piece is a unified query API boundary — a single service method or query builder that accepts optional `agentId`, `projectId`, `status[]`, `limit`, and `offset` and returns the same `WorkAssignment` shape regardless of which surface (agent queue or project queue) requested it.

**Key finding**: The canonical row is already singular. No second store, shadow table, or derived projection is needed. The work is to surface that row through a unified query contract and render it through reusable fragment components.

### 2. There are two overlapping "job" models that need reconciliation

The codebase currently has:
- **`JobDefinition`** / **`JobRun`** (in `ai.orchestration.runtime`): durable task-like work with `JobWorkItem` children. Has `projectId`, `workspaceType`/`workspaceKey`/`workspaceIdentity`. Produce `WorkAssignment` rows with `AssignmentType.JOB_RUN`.
- **`OrchestrationJob`** / **`OrchestrationJobItem`** (also in `ai.orchestration.runtime`): orchestration-owned work defined by `owner_agent_id`, with ordered `OrchestrationJobItem` children pointing at task/workflow ids. Produce `WorkAssignment` rows with appropriate `AssignmentType`.

Both models carry the `workspaceType`/`workspaceKey`/`workspaceIdentity` triple. Both produce assignments. They have different schemas (`job_definitions` + `job_runs` vs `orchestration_jobs` + `orchestration_job_items`). The directive's vision of "Jobs are reassignable agent tasks, bound to projects" maps more naturally to `OrchestrationJob`, but `JobDefinition` already has `projectId`.

**Key finding**: The directive calls for a "Tasks vs Jobs" distinction where Tasks are lightweight, pinnable, assignable user-defined work items and Jobs are heavier reassignable containers with work items. This maps to: **Tasks ≈ `plan_definitions` with simpler lifecycle** and **Jobs ≈ a unified model replacing both `JobDefinition` and `OrchestrationJob`**. A new `task_definitions` table may be warranted if tasks need pinning, priority assignment, and project binding as first-class facts rather than inferred from plan metadata.

### 3. The assignment status lifecycle is already uniform across all work types

`OrchestrationStatus` defines: `QUEUED → RUNNING → (WAITING → RUNNING)* → COMPLETED | FAILED | CANCELLED | NEEDS_REVIEW`, with `PAUSED`, `INTERRUPTED`, and `CANCEL_REQUESTED` as intermediate states. This lifecycle applies identically to `TASK_RUN`, `WORKFLOW_RUN`, `JOB_RUN`, `AGENT_MESSAGE`, `WAIT_FOR_MESSAGE`, and `REPORT` assignment types. No per-type lifecycle variation exists.

**Key finding**: The status lifecycle contract is already unified. New work types (a lightweight "Task" assignment, a "project chat" assignment) can adopt the same enum without schema change.

### 4. Projects have no filesystem root — this constrains the Files and Chat tabs

`ARCH-20260719-01` and `DECISION-20260719-02` are clear: projects have NO filesystem root. The project Files tab is a read-only materialization browser that revalidates agent membership, context generation, and materialization lifecycle on every read. A project Chat tab similarly cannot create a workspace root for the project; it must select an agent workspace, capture a binding with project materialization context, and chat through that agent.

**Key finding**: The project Files tab and project Chat tab must both consume the existing materialization infrastructure. The Files tab already does this. The Chat tab needs a new `ConversationSurface.PROJECT` or equivalent, plus binding capture that includes the project materialization.

### 5. Schedules and event reactions are exclusively agent-scoped

`AgentSchedule` and `AgentEventReaction` have `agent_id` as their owner. There are no project-scoped schedules or reactions. The directive's vision of a Project Events tab requires either:
- Adding `project_id` to the reaction table and allowing project-scoped reaction CRUD
- Filtering the existing agent-scoped reactions by project membership
- Both: project-level configuration that delegates to member agents

**Key finding**: The cleanest path is `project_id` as an optional column on `agent_event_reactions` (and optionally `agent_schedules`), with project-scoped queries filtering by that column. The same canonical row serves both agent and project views.

### 6. The "Task" concept needs design — it's not currently a first-class domain entity

The directive says: "Tasks: user-defined work items, pinnable, assignable, schedulable." Currently, the closest thing is `plan_definitions` (saved plans), but those are model-authored chat plans, not lightweight user-created work items. There's no table for user-defined tasks with pin, priority, project binding, and assignment lifecycle.

**Key finding**: A new `task_definitions` table is warranted. It should share the canonical identity pattern (`task_run_links`, `TaskPathKey`) so it participates in the unified run model, but its definition shape can be simpler than `plan_definitions` — just title, description, projectId, pinned flag, priority, and assignment template fields.

### 7. Transcript access is agent-scoped but linkable to projects

`assignment_conversation_links` stores `(assignment_id, conversation_id)`. Transcript retrieval currently goes through agent-scoped controllers. For project-scoped transcript access, a query that joins `work_assignments.project_id` → `assignment_conversation_links.conversation_id` would work, subject to membership validation.

**Key finding**: No new persistence is needed. The query path needs project-membership gating added before transcript access.

### 8. The two Job models represent a unification opportunity

The directive says "Jobs must be bound to workspaces/projects" and "If an agent is assigned jobs, they show in both the project dashboard and the agent dashboard." Currently both job models can carry project binding, but they use different tables and different service surfaces. Unifying them into a single `job_definitions` table (or consolidating `orchestration_jobs` into `job_definitions`) would simplify querying, rendering, and lifecycle ownership.

**Key finding**: The most backward-compatible path is to **evolve `job_definitions`** to absorb the orchestration job fields it's missing (`project_id`, work items, status) while keeping `orchestration_jobs` as a legacy compatibility read surface. But a simpler, cleaner path is to **pick one model and cut over** — `job_definitions` is the more complete model (it already has `projectId`, work items, run tracking).

---

## Options

### Option A: Evolve In-Place (Backward-Compatible Extension)

**Identity strategy**: Keep existing tables, add columns where needed, add new query methods. No schema migration rewrites.

| Contract Area | Approach |
|---|---|
| **Assignment Contract** | Add `assignment_kind` enum column (`TASK`, `JOB`, `WORKFLOW`) to `work_assignments` for UI grouping. Add unified query builder to `OrchestrationRuntimeRepository` accepting `(agentId?, projectId?, kind?, status[], limit, offset)`. |
| **Task Definition** | New `task_definitions` table: `id, project_id, title, description, pinned, priority, status, assignment_template_json, created_at, updated_at`. Shares `TaskPathKey` derivation (`v1-T-<sha256(id)>`) for run storage. |
| **Job Unification** | Add `project_id` to `orchestration_jobs`. Deprecate `JobDefinition`/`JobRun` in favor of `OrchestrationJob`/`OrchestrationJobItem` (which already match the directive's "reassignable jobs with work items" model). Add `project_id` to `orchestration_job_items` for project-scoped work-item queries. |
| **Queue Views** | Single `AssignmentQueryService.findAssignments(AssignmentQuery)` returning `List<WorkAssignment>`. `AssignmentQuery` is a record with optional `agentId`, `projectId`, `kind[]`, `status[]`, `limit`, `offset`. Agent queue calls with `agentId=X`; project queue calls with `projectId=Y`. Overlap is `agentId=X AND projectId=Y`. |
| **Transcript Access** | New query: `findConversationIdsForProjectAssignment(projectId, assignmentId)` with membership check. Read-only transcript view reuses existing `AssignmentAuditTranscriptRenderer`. |
| **Project Chat** | New `ConversationSurface.PROJECT` enum value. New `ProjectChatService` that captures an immutable binding with project materialization context, then delegates to the existing `ChatService` with that binding. API contract: `POST /api/projects/{projectId}/chat/stream?message=`. |
| **Project Events Tab** | Add optional `project_id` to `agent_event_reactions`. `GET /api/projects/{projectId}/event-reactions` queries by `project_id`. Same CRUD, same rendering. |
| **Project Schedules Tab** | Add optional `project_id` to `agent_schedules`. `GET /api/projects/{projectId}/schedules` queries by `project_id`. Same CRUD, same rendering. |
| **Project Files Tab** | Already exists as read-only materialization browser. No change needed for the sprint scope beyond possibly adding the project-root `.skills/` browse if project skills are introduced. |
| **Fragment Rendering** | Reuse existing `RunOperationsFragments` for queue rows, status chips, detail expansion. Extract a `QueueRowRenderer` component usable by both `OrchestrationController` (agent) and a new `ProjectConsoleController` (project). |

**Pros**:
- Zero schema migration risk for existing tables (only additive columns)
- All existing tests continue to pass
- Can ship incrementally — each new tab is independently deployable
- Preserves the established contract that projects have no filesystem root

**Cons**:
- Two overlapping job models persist indefinitely — ongoing confusion risk
- `task_definitions` and `plan_definitions` are separate tables for what is conceptually "things that produce runs" — three definition tables total
- Adding columns to existing wide tables (`work_assignments` is already 28 fields) increases maintenance burden

---

### Option B: Contract Unification (New Canonical Tables, Legacy Adapters)

**Identity strategy**: Introduce new canonical tables with clean shapes, provide read adapters over legacy tables, cut over write paths one domain at a time.

| Contract Area | Approach |
|---|---|
| **Unified Work Item** | New `work_items` table: `id, kind (TASK|JOB|WORKFLOW), project_id, owner_agent_id, title, description, status, pinned, priority, assignment_template_json, workspace_type, workspace_key, workspace_identity, created_at, updated_at`. Single table for all assignable work. `task_definitions`, `job_definitions`, and `orchestration_jobs` become legacy read-only sources. |
| **Unified Run Record** | New `work_runs` table absorbing `plan_runs`, `workflow_runs`, `job_runs`: `id, work_item_id, work_item_kind, attempt_number, status, workspace_type, workspace_key, workspace_identity, snapshot_json, created_at, updated_at, started_at, completed_at`. Legacy run tables become read-only. |
| **Assignment stays single source** | `work_assignments` stays the single assignment table. Add `work_item_id` (FK to `work_items`) alongside existing `job_id`, `job_item_id`. The `assignmentType` enum gains `TASK`. |
| **Query Contract** | `WorkItemQueryService` with methods: `findByAgent(agentId)`, `findByProject(projectId)`, `findAssignments(AssignmentQuery)`, `findRuns(RunQuery)`. All return canonical shapes regardless of `work_item_kind`. |
| **Project Chat** | New `project_conversations` table linking `project_id` → `conversation_id` with binding snapshot. `ProjectChatService` captures context, delegates to `ChatService`. |
| **Project Events/Schedules** | Move `agent_event_reactions` → `event_reactions` with nullable `agent_id` and `project_id` (mutually exclusive owner). Same for `agent_schedules` → `schedules`. |
| **Fragment Rendering** | New `WorkItemFragments` and `QueueFragments` as shared rendering components. Agent and project controllers both delegate to these. |

**Pros**:
- Single definition table eliminates the three-table cognitive load
- "Tasks vs Jobs" becomes a `kind` discriminator on one table, not separate tables
- Clean projection for any cross-cutting view
- Run records unified under one schema — simpler observability

**Cons**:
- Massive migration risk — `plan_definitions`, `plan_runs`, `workflow_definitions`, `workflow_runs`, `job_definitions`, `job_runs`, `orchestration_jobs`, `orchestration_job_items` all touched
- All existing tests, services, repositories, tools, and chat flows would need updating
- The canonical task-path key scheme (`T`, `W`, `J` source kinds) would need re-derivation
- High chance of regressions in chat planning, workflow execution, and job orchestration
- Backward-incompatible without a dual-write migration period

---

### Option C: Pragmatic Tiered Unification (Recommended)

**Identity strategy**: Keep existing definition tables for now. Add a lightweight "Task" concept that's clearly distinct from Plans and Jobs. Unify the query boundary without migrating existing rows. Consolidate the two job models into one.

| Contract Area | Approach |
|---|---|
| **Assignment Contract (unchanged shape, unified query)** | `WorkAssignment` already carries all necessary cross-cutting fields (`agentId`, `projectId`, `workspaceType/key/identity`, `assignmentType`). Add `AssignmentQueryParams` record: `agentId?`, `projectId?`, `assignmentTypes[]`, `statuses[]`, `includeTerminal?`, `limit`, `offset`. One method: `OrchestrationRuntimeRepository.findAssignments(AssignmentQueryParams)`. Agent queue and project queue both call this method with different filters. Existing named query methods become convenience delegates. |
| **Task Definition (new, lightweight)** | New `task_items` table: `id TEXT PK, project_id TEXT, title TEXT NOT NULL, description TEXT, pinned INTEGER DEFAULT 0, priority INTEGER DEFAULT 0, status TEXT NOT NULL DEFAULT 'OPEN', assigned_agent_id TEXT, workspace_type TEXT, workspace_key TEXT, workspace_identity TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL`. Lightweight — no snapshot, no model-authored content, no plan steps. A Task is a user-created work item that can be assigned to an agent, pinned to a project, given priority, and scheduled. When executed, it creates a `WorkAssignment` with `assignmentType=TASK` and an optional `plan_id` link if the agent converts it to a plan. Tasks participate in the `task_run_links` family with source kind `K` (for "tasK"). |
| **Job Consolidation** | Deprecate `orchestration_jobs`/`orchestration_job_items` in favor of `job_definitions`/`job_runs`. Add `project_id`, `status`, and `workspace_type/key/identity` to `job_definitions` (they already have `project_id` and `workspace*`). Add `assigned_agent_id` to `job_definitions` for reassignment. `JobService` becomes the single job service — `OrchestrationJobService` delegates to it. Legacy `orchestration_jobs` rows remain readable through a compatibility view; new writes go to `job_definitions`. |
| **Queue Views (unified fragment rendering)** | Extract `QueueRowComponent` and `QueueDetailComponent` as SimplyPages reusable modules. `OrchestrationController` (agent) and new `ProjectConsoleController` (project) both call the same `AssignmentQueryService` and render through the same components. The only difference is the filter (agentId vs projectId) and the OOB target (`#agent-queue-panel` vs `#project-queue-panel`). |
| **Transcript Access** | Add `findConversationIdsByProjectAssignment(projectId, assignmentId)` to `OrchestrationRuntimeRepository`. New `GET /api/projects/{projectId}/assignments/{assignmentId}/transcript` that validates project membership, then delegates to existing transcript retrieval. Read-only, rendered through existing `AssignmentAuditTranscriptRenderer`. |
| **Project Chat (new surface, existing infrastructure)** | New `ConversationSurface.PROJECT` enum value. `POST /api/projects/{projectId}/chat/stream` creates a conversation with `surface=PROJECT`, captures an immutable binding with the project's materialization context on the calling agent's workspace, and streams through the existing `ChatService`. The API contract mirrors `POST /api/chat/stream` but is project-scoped: membership validation at the controller, binding capture at submission, then normal chat flow. SSE events include `taskRunId` and metadata. Transcripts stored in `ai_chat_memory` with `surface=PROJECT` for future project-scoped listing. |
| **Project Events Tab** | Add `project_id TEXT` to `agent_event_reactions`. The `EventReactionService` gains `findByProject(projectId)` and `saveForProject(projectId, reaction)`. The reaction still needs an agent for execution (the agent that enqueues assignments), so `agent_id` remains on the row — `project_id` is a filtering column, not an ownership replacement. Controller: `GET /api/projects/{projectId}/event-reactions` (list), `POST/PUT/DELETE /api/projects/{projectId}/event-reactions/{id}` — all gated by project membership. |
| **Project Schedules Tab** | Add `project_id TEXT` to `agent_schedules`. Same pattern: schedule still needs an agent for execution, `project_id` enables project-scoped filtering. `ScheduleService` gains `findByProject(projectId)`. Controller: `GET /api/projects/{projectId}/schedules`, `POST/PUT/DELETE /api/projects/{projectId}/schedules/{id}` — gated by project membership. |
| **Project Files Tab (enhance)** | Already exists as read-only materialization browser. For the sprint, add `.skills/` browse if project-scoped skill discovery is enabled (read-only, through materialization authority). No new persistence. |
| **Project Tasks Tab** | New `TaskService` over `task_items`. Controller: `GET /api/projects/{projectId}/tasks` (list), `POST` (create), `PUT /{taskId}` (update — pin, priority, assign), `DELETE /{taskId}`. When assigned for execution, `TaskService.submit(taskId)` creates a `WorkAssignment` with `assignmentType=TASK` and `project_id=task.projectId`. The assignment then appears in both the project queue and the assigned agent's queue. |
| **API Contract Shape (unified)** | Every cross-cutting list endpoint returns `List<WorkAssignmentSummary>` (a secret-free projection of `WorkAssignment`): `id, agentId, agentName, projectId, projectName, assignmentType, runDisplayName, priority, status, workspaceType, workspaceKey, createdAt, updatedAt, startedAt, completedAt`. No lease owner, fence, or path exposure. Agent detail and project detail both get this shape from the same query service. |

**Pros**:
- No migration of existing definition/run tables — only additive columns
- Lightweight Task concept fills the gap between "chat-authored plan" and "orchestrated job"
- Job consolidation is incremental: legacy reads preserved, new writes unified
- Query unification without schema rewrite — just a new repository method
- Reusable fragment rendering avoids code duplication
- All cross-cutting views read the same `work_assignments` row
- Can be phased: Queue tab first (zero schema change), then Tasks (new table), then Jobs consolidation, then Chat/Events/Schedules

**Cons**:
- Three definition tables persist (`plan_definitions`, `task_items`, `job_definitions`) with similar but not identical shapes
- `task_items` and `plan_definitions` both produce runs — some shared logic will need extraction
- Job consolidation requires careful `OrchestrationJobService` → `JobService` delegation to avoid breaking existing orchestration flows
- `ConversationSurface.PROJECT` needs careful binding capture to ensure the chat has access to project context but not other agents' workspaces

---

## Trade-offs

### Single canonical table vs. kind-discriminated tables

**For unification**: One `work_items` table with a `kind` column eliminates three separate repositories, three separate query surfaces, and the mental overhead of deciding "is this a task, a plan, or a job?" The canonical run identity matrix already acknowledges that all three families share the same `task_run_links` shape. Collapsing them into one table with a discriminator column is the logical endpoint of that design.

**Against unification**: The three definition types currently have meaningfully different shapes:
- `plan_definitions`: rich model-authored content with steps, output declarations, JSON snapshots
- `job_definitions` / `OrchestrationJob`: ordered work items with retry counts, continue-on-failure flags
- Proposed `task_items`: lightweight with just title, description, pin, priority

A single table would need nullable columns for plan-specific, job-specific, and task-specific fields, creating a wide sparse table. A `snapshot_json` catch-all column would work but loses schema enforcement. Three focused tables with a shared query interface (via `UNION ALL` views or service composition) keeps each table tight while still enabling cross-kind queries.

**Recommendation**: Keep separate tables for now (Option C). Build a `WorkItemSummaryProjection` that can be populated from any of the three tables via service composition. If the three shapes converge over time, revisit unification.

### Project chat: new surface vs. project-bound regular chat

**Option 1 — New `ConversationSurface.PROJECT`**: The chat is a distinct surface type with its own conversation lifecycle, its own binding capture, and project-scoped transcript listing. This is clean but adds a new surface to every chat-related code path.

**Option 2 — Regular chat with project context injected via binding**: The user opens a normal chat, selects an agent, and the system automatically captures a binding that includes the project materialization. The conversation is a regular `CHAT` surface conversation, but with project context in the binding. This reuses existing infrastructure but makes project-scoped transcript listing harder (the `surface` column would say `CHAT`, not `PROJECT`).

**Recommendation**: Option 1 for the sprint — `ConversationSurface.PROJECT`. The surface type is already an established pattern (CHAT, PLAN, AGENT_CHAT, SYSTEM_CHAT). Adding PROJECT is a small enum change that enables project-scoped listing, project-specific prompt behavior, and clear transcript ownership. The API contract for project chat should mirror `POST /api/chat/stream` but under `POST /api/projects/{projectId}/chat/stream`.

### Assignment shape: add kind vs. use existing assignmentType

The `assignmentType` enum already has `TASK_RUN`, `WORKFLOW_RUN`, `JOB_RUN`, `AGENT_MESSAGE`, `WAIT_FOR_MESSAGE`, `REPORT`. A new `TASK` assignment type (for the lightweight task items) could be added. This is cleaner than a separate `assignment_kind` column because `assignmentType` already drives UI grouping and lifecycle behavior.

**Recommendation**: Add `TASK` to `AssignmentType`. No new column needed.

### Project-owned vs. agent-owned with project filter for schedules/reactions

Schedules and reactions currently belong to agents. The sprint directive says "Set event triggers and responses per project" and "Schedule recurring or one-time tasks... Per-project scheduling scope." The question is whether the project **owns** the schedule/reaction or merely **filters** agent-owned ones.

**Option A — Project owns**: `schedule.owner_type = 'PROJECT'`, `schedule.owner_id = projectId`. When the schedule fires, it picks an agent from project members to execute. Requires new execution-agent selection logic.

**Option B — Agent owns, project filters**: `schedule.project_id = projectId` (nullable FK). The schedule still has `agent_id` as its executor. Project-scoped listing filters by `project_id`. No execution-agent selection change.

**Recommendation**: Option B for the sprint. It's additive (new column, new query, new UI) without changing the execution model. Option A can be revisited when project-owned execution-agent selection has a proven design.

### Tasks as new table vs. tasks as a stripped-down plan

A "Task" (user-defined work item, pinnable, assignable, schedulable) could be modeled as a `plan_definition` with a `task_mode` flag, using the existing plan infrastructure. This avoids a new table but couples the lightweight task concept to the heavier plan model (steps, output declarations, JSON snapshots).

**Recommendation**: New `task_items` table. The plan model carries too much weight for a simple work item. Tasks are for "remind me to review this PR" or "check the logs for errors" — not for multi-step model-authored plans. A dedicated table with just the fields needed (title, description, project, agent, pin, priority) keeps the concept clean and the table small.

---

## Open Questions

1. **Task → Plan conversion**: When an agent executes a Task assignment, should the agent's chat surface automatically create a `plan_definition` from the task, or should tasks remain as standalone executions with their own run records? The directive says tasks "may contain multiple work items" — this sounds like a Task might evolve into a Job. How should Task → Job promotion work?

2. **Job reassignment mechanics**: "Jobs are reassignable tasks that agents perform." Does reassignment change `job_definitions.assigned_agent_id` (and thus change which agent picks it up next time), or does it cancel the current assignment and create a new one for a different agent? The answer affects whether `WorkAssignment.agentId` is mutable after creation.

3. **Project chat binding scope**: When a user opens project chat, which agent's workspace is used? The project owner agent? A user-selected agent from project members? A dedicated "project agent"? This affects the `WorkspaceDescriptor` selection and the materialization authority.

4. **Project Files tab write capability**: Currently read-only. Should project members with appropriate roles be able to create/edit files in the project materialization (which is agent-scoped and generation-safe)? If so, this requires a new mutation authorization path that crosses the agent/project boundary.

5. **Cross-project task visibility**: If an agent is a member of Project A and Project B, and a task is pinned to Project A, should it appear in the agent's queue when viewing Project B? Or only when viewing Project A or the agent's own queue? The recommendation is: agent queue shows all, project queue shows only that project's — but this needs explicit product confirmation.

6. **Task pinning semantics**: Does "pinning" a task to a project mean it's only visible in that project's task list, or does it mean the task's execution context is bound to that project's materialization? The answer determines whether `task_items` needs `workspace_type/key` or just `project_id`.

7. **Event reaction project filter vs. project ownership**: If a reaction has `project_id = X`, should it fire for events from any agent in project X, or only for events explicitly tagged with project X? The current event model has `source_type` and `source_id` — adding `project_id` to events could enable project-scoped dispatch.

8. **`orchestration_jobs` vs `job_definitions` consolidation order**: Should `orchestration_jobs` be merged into `job_definitions` before or after the new Task concept is introduced? The recommendation is after — Tasks are the highest-value, lowest-risk new surface. Jobs consolidation is higher risk (breaks existing orchestration flows if done wrong) and can follow.

---

## Recommended Next Step

**Adopt Option C (Pragmatic Tiered Unification)** and produce a phased implementation plan covering:

1. **Phase 0 — Unified Query Contract**: Add `AssignmentQueryParams` and unified `findAssignments()` to `OrchestrationRuntimeRepository`. Add `TASK` to `AssignmentType`. Zero schema changes. This alone enables the Project Queue tab.

2. **Phase 1 — Project Queue Tab**: New `ProjectConsoleController` with `GET /projects/_detail/{projectId}/queue` fragment endpoint. Reuses `RunOperationsFragments` for queue rows. Validates project membership, calls unified query with `projectId` filter.

3. **Phase 2 — Task Items**: New `task_items` table, `TaskService`, `TaskRepository`. Project Tasks Tab with CRUD. Task submission creates `WorkAssignment` with `assignmentType=TASK`, populating both agent and project queues.

4. **Phase 3 — Job Consolidation**: Merge `orchestration_jobs` into `job_definitions`. Add `assigned_agent_id` for reassignment. `JobService` becomes single job authority.

5. **Phase 4 — Project Chat**: `ConversationSurface.PROJECT`, project binding capture, `POST /api/projects/{projectId}/chat/stream`, SSE chat surface in project console.

6. **Phase 5 — Events & Schedules**: Add `project_id` columns, project-scoped CRUD controllers, fragment rendering. Integrate with existing schedule/reaction execution infrastructure.

7. **Phase 6 — Polish & Cross-Cutting Validation**: Ensure every assignment that has a `project_id` appears in both the agent and project queues. Validate transcript access across surfaces. Ensure fragment OOB targets don't cross-contaminate. Playwright validation for all new tabs.

**Contract artifact to produce first**: A `contracts.md` specification in `.internal-dev/specifications/` that records the unified assignment query shape, the task/job distinction, the project chat API contract, the project events/schedules ownership model, and the fragment rendering contract. This becomes the implementation contract that all phases reference.
