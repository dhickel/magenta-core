
## Prompt

Expand the Magenta project dashboard from its current six-tab console into a comprehensive operational surface that mirrors and extends the agent dashboard capabilities, with robust unified contracts across tasks, jobs, queues, schedules, events, and projects. The directive targets seven capability areas: Project Queue, Project Chat, Project Tasks, Project Files (enhanced), Project Events, Jobs System Enhancement, and Schedules — all united by a cross-cutting unified contract that avoids duplicating persistence, query paths, or fragment rendering.

## Source

Architecture specifications (`.internal-dev/specifications/architecture.md`, `services.md`, `api.md`, `web.md`, `service-graph.md`, `simplypages.md`, `decisions.md`) plus direct code inspection of `ProjectController.java`, `OrchestrationController.java`, `ProjectService.java`, `AssignmentService.java`, `JobService.java`, `WorkspaceExplorerFragments.java`, and knowledge files `2026-07-19-filesystem-first-workspaces.md`, `agent-lifecycle-htmx-targets.md`, `root-relative-workspace-storage.md`, and `live-chat-mcp-workflow-testing.md`.

## Findings

### 1. Foundation: What Already Aligns

**Closed Workspace Model Is a Hard Boundary.** `WorkspaceKind` is strictly `AGENT`, `CHAT`, `USER`. Projects have zero filesystem root authority — `projectId` is membership and attribution metadata only. Execution carries `workspaceType`/`workspaceKey` together; `projectId` rides along as a context tag that never selects a root. This constraint is settled architecture, not an open question. The sprint must treat it as a design invariant.

**Materializations Are Agent-Scoped, Generation-Safe.** Project materializations live under `<agent-root>/projects/<stable-key>/` with `PROVISIONING`/`READY`/`FAILED`/`REVOKED` lifecycle. Each is an agent's local context container, not a shared project filesystem. The existing Project Files tab already navigates this correctly: it revalidates membership, exact source kind, lifecycle state, generation, and trusted location per read. This is the only path to project-visible files and it must remain the only path.

**Cross-Cutting Assignment Identity Already Exists.** `work_assignments` carries both `agent_id` and `project_id`. The dashboard query `activeSummariesForProject(projectId)` already produces project-scoped assignment views. Assignments also carry `workspace_type`, `workspace_key`, and native `task_run_id`. The dual-index pattern (agent-scoped + project-scoped queries returning the same shape) is the direct solution to the unified contract requirement — it exists and needs extension, not reinvention.

**Orchestration Runtime Maturity.** Canonical task-run identity (`TaskPathKey` + native `taskRunId`), independent assignment leases and writer fences, lineage-linked retry/replay, job work-item runs, and the `RunSummaryService` read model are all live. The Run Operations fragments (`#agent-run-groups`, `#agent-run-detail`) already render bounded, secret-free run history in the agent console. These are directly reusable for project-scoped views.

**Schedules and Reactions Are Revision-Qualified and Agent-Scoped.** Six-field cron definitions, operation-key idempotency, claim/fence pattern, and `ENQUEUE_ASSIGNMENT` action exist under `/api/agents/{agentId}/schedules` and `/event-reactions`. Both support pure simulation without mutation. The bottom-up automation contracts are solid; only the project-scoped UI and project-level scoping do not yet exist.

**Agent Console Composition Pattern Is Proven.** Independent HTMX hosts (`#agent-queue-panel`, `#agent-context-panel`, `#agent-skills-panel`, `#system-chat-panel`, `#agent-run-groups`, Files list/inspector/modal) each load independently. Stale responses replace only their own host. Project console currently uses a simpler single `#project-tab-panel` target. The agent pattern is the template.

**Project Chat Has Conceptual Groundwork.** `DECISION-20260727-04` established system chat as a fixed deny-by-default profile per agent with bounded reads, restricted tools, and user-confirmable proposals. The project chat surface would need a similar bounded profile but scoped to project context. The chat infrastructure (SSE streaming, pending-message queue, tool registry, `ChatService`) exists and is separately tested.

### 2. Gaps and Tensions

**No Project-Scoped Queue View.** `OrchestrationController` queries agent queue panels through `assignmentService.queueAssignments(agentId)`. There is no equivalent `queueAssignmentsForProject(projectId)` fragment. The data exists (assignments have `project_id`) but no HTMX host renders it filtered by project. When the directive says "overlap with agent queues," this means the same assignment row must render in two hosts — trivial if both views filter the same canonical table.

**Project Files Is Already Implemented But Only As Read-Only Browser.** The existing files tab navigates current members' materializations with generation-verified read-only confinement. The sprint directive wants "directory initialized to spec on project creation" and "permissions locked (read-only for members, based on materialization)." The read-only contract is satisfied; directory initialization at project creation is not. But materialization provisioning is agent-scoped — a project cannot create a materialization; an agent member must do so through its `AgentContextService`. This tension between "project creates the directory" and "materialization is agent-scoped" needs explicit resolution.

**Project Chat Needs API Contract Design Before Implementation.** The directive explicitly says "Future API for project chat (we will add this later — plan for the API contract now, implement later)." This means the sprint plans the contract shape (routes, payloads, authorization model, conversation scoping) but defers full implementation. The tension is: project conversations require a surface kind (`PROJECT`), which must be added to `ChatSessionSurface` enum, conversation routing, and the session filter to avoid leaking project conversations into the browser `/chat` surface.

**Task/Job Distinction Is Unclear in Current Naming.** The directive says "Tasks: user-defined work items, pinnable, assignable, schedulable" and "Jobs: reassignable agent tasks, bound to projects, may contain multiple work items." Currently, the codebase uses `plan_definitions` (tasks/plans), `workflow_definitions`, and `job_definitions`. Plans are saved from chat and can be executed directly or embedded in jobs. Jobs wrap plans/workflows and add scheduling, reassignment, and project binding. The distinction exists but the user-facing UI currently treats them as separate silos. The sprint asks for a "Project Tasks Tab" that makes tasks first-class assignable items, overlapping with how agents see them. This means a task (plan) needs project-level pinning and assignment views that today only exist at the agent level.

**No Per-Project Schedule/Event Scope.** Schedules and reactions are currently agent-scoped (`/api/agents/{agentId}/schedules`). The sprint wants them per-project. This means either: (a) schedules acquire an optional `project_id`, allowing agent-scoped schedules to fire project-scoped assignments, or (b) a parallel project-scoped schedule domain mirrors the agent one. Option (a) is simpler and aligns with how assignments already dual-scope on `agent_id` + `project_id`.

**No Project Chat Agent Profile.** System chat uses a fixed per-agent deny-by-default profile. Project chat needs an equivalent: a server-owned profile bound to the project, able to read project context (members, materializations, assignment status) while remaining deny-by-default for filesystem writes, process execution, and skill activation. The profile would use the main chat agent but with project-scoped bounded reads and context injection.

**Process Confinement Remains Unavailable.** Shell execution, skill executable authorization, and package ingestion fail closed. Jobs that require process execution must continue to fail at the runner boundary. Nothing in the sprint changes this, but any job-capability UI must truthfully display unavailability.

### 3. Cross-Cutting Contract Architecture

The unified contract is the structural backbone. Here is how each entity projects across agent and project surfaces:

| Entity | Canonical Store | Agent View Filter | Project View Filter | Overlap |
| --- | --- | --- | --- | --- |
| Assignment | `work_assignments` (single table) | `WHERE agent_id = X` | `WHERE project_id = Y` | `WHERE agent_id=X AND project_id=Y` |
| Assignment Status/Lifecycle | `work_assignments.status` + `assignment_leases` | `assignmentService.queueAssignments(agentId)` | `assignmentService.queueAssignmentsForProject(projectId)` (new) | Same record, same controls |
| Task (Plan) Definition | `plan_definitions` | Exists; query by owner | Needs project-pin table `project_task_pins(projectId, planId, priority)` | Via pin cross-reference |
| Job Definition | `job_definitions` | `findByOwnerAgent(agentId)` | `findByProject(projectId)` (already exists) | `WHERE owner_agent_id=X AND project_id=Y` |
| Job/Task/Workflow Run | `plan_runs`, `workflow_runs`, `job_runs` + `task_run_native_links` | `RunSummaryService` scoped to agent workspace | `RunSummaryService` scoped to project (filter by project-attributed runs) | Same canonical run |
| Transcript | `ai_chat_memory` (conversation-scoped) | Via `conversationId` on assignment | Via same `conversationId` | Same transcript, read-only |
| Schedule | `schedule_definitions` | `WHERE agent_id = X` | `WHERE project_id = Y` (new column) | Enqueue produces project-attributed assignment |
| Event Reaction | `event_reaction_definitions` | `WHERE agent_id = X` | `WHERE project_id = Y` (new column) | Dispatch produces project-attributed assignment |
| Materialization | `agent_materializations` | Per-agent context tab | Per-agent member view in project Files tab | One materialization, viewed from two surfaces |
| Context Binding | `agent_context_bindings` | Per-agent context tab | Not directly visible at project scope (binding is agent-private) | N/A |

**Key principle**: the canonical source of truth for assignments, runs, and status remains singular. Cross-cutting views are filtered projections of the same records, never separate stores. This is already true for assignments (dual `agent_id` + `project_id` columns). It must be extended for schedules and reactions (add `project_id` column) and for task pinning (new junction table).

## Options

### Option A: Full Mirror — Project Console Matches Agent Console Host-by-Host

Replace the single `#project-tab-panel` with independent HTMX hosts (`#project-queue-host`, `#project-chat-host`, `#project-tasks-host`, `#project-events-host`, `#project-schedules-host`, plus existing Dashboard, Members, Files, Jobs, Outputs, Settings hosts). Each host independently loads through HTMX. This mirrors the agent console pattern exactly.

- **Pros**: Proven pattern, stale-response isolation, independent failure, parallel loading, aligns with ARCH-20260727-05 precedent.
- **Cons**: Substantial refactor of existing project tab shell; 10+ independent hosts may overwhelm the visual layout; the project console currently uses a single `#project-tab-panel` target that all mutations target.

### Option B: Hybrid — Keep Tab Shell, Add Independent Hosts Inside Tabs

Preserve the existing `#project-tab-panel` as the tab content target (Dashboard, Members, Settings, Outputs keep current behavior) but add new tabs (Queue, Chat, Tasks, Events, Schedules) as independent hosts. Jobs and Files tabs stay as-is but gain richer content.

- **Pros**: Smaller blast radius; existing tabs don't change; new tabs benefit from independent host pattern.
- **Cons**: Inconsistent host architecture across tabs; harder to maintain long-term; mutation OOB targets become confusing.

### Option C: Evolutionary — Incremental Refactor Toward Agent Console Parity

Phase the work so early phases add new tabs inside the existing shell, and a later phase refactors the project console to the full independent-host pattern once all tabs exist and the visual layout is proven. This is the approach implied by the sprint's phasing guidance ("Group work into cohesive phases by domain dependency").

- **Pros**: Ships value incrementally; validates layout before architectural refactor; reduces risk.
- **Cons**: Requires discipline to not leave the project console forever in hybrid state.

**Recommendation**: Option C is the most practical. Start with the unified contract (backend), then implement new tabs incrementally inside the existing shell, with a forward-looking commitment to refactor to independent hosts in a later phase if complexity warrants it.

### Option D: Project-Scoped Schedules — Add `project_id` Column vs. Mirror Domain

**D1: Add `project_id` to existing `schedule_definitions` and `event_reaction_definitions`.** A schedule/reaction optionally belongs to a project. When it fires, the resulting assignment carries the project binding. Agent-scoped schedules (no `project_id`) continue to work as-is.

- **Pros**: One canonical table; single schedule can be viewed from agent or project surface; no domain duplication; aligns with assignment dual-scope pattern.
- **Cons**: Column proliferation on agent-scoped tables; agent schedules and project schedules share the same UI/data shape; filtering becomes more complex.

**D2: Create separate `project_schedule_definitions`.** A parallel domain with its own tables, services, and fragments.

- **Pros**: Clean separation; no risk of agent/project schedule confusion; independent evolution.
- **Cons**: Duplication of six-field cron validation, claim/fence pattern, firing audit, and simulation — all identical logic.

**Recommendation**: D1 (add `project_id` column). The schedule/reaction infrastructure is mature and well-tested. Adding one nullable column preserves the existing agent-scoped behavior while enabling project-scoped queries. The dual-scope pattern is already proven on `work_assignments`.

### Option E: Project Chat Surface — Conversation Model

**E1: Add `ChatSessionSurface.PROJECT` with `projectId` on conversations.** Project chats are a new surface kind alongside `MAIN`, `PLAN`, `AGENT`, `INTERNAL`. A project-scoped chat creates a conversation with `surface = PROJECT` and `project_id = <projectId>`. The browser chat session filter excludes `PROJECT` conversations. Project chat is accessed through its own route and host.

- **Pros**: Clean separation; conversation history is scoped; no risk of project conversations leaking into browser chat.
- **Cons**: Requires new surface kind and filter logic; project chat tool authorization needs its own profile/policy.

**E2: Use existing `AGENT` surface with project context injection.** Instead of a new surface, use the main chat agent's conversation but inject project context (materializations, members, assignments) into the prompt.

- **Pros**: No new surface kind; reuses existing chat routing.
- **Cons**: Project context injection is fragile; can't easily filter project conversations; risk of leaking project context into non-project conversations.

**Recommendation**: E1. The surface-kind model is already established and adding `PROJECT` is the cleanest path. The project chat profile should be a bounded read-only profile (like system chat but for project context) that has access to project members, materialization summaries, assignment status, and project events — but no filesystem write, process execution, or skill activation authority.

## Trade-offs

1. **Single vs. Dual Source of Truth.** The unified contract is architecturally simple (filter projections over canonical tables) but requires discipline: every new domain entity that can be both agent-scoped and project-scoped must carry both IDs and never create parallel tables. The trade-off is schema complexity (more nullable `project_id` columns) vs. query simplicity (no joins across parallel domains).

2. **Project Chat: Now vs. Later.** The directive explicitly says "plan for the API contract now, implement later." This is a critical phasing decision: the project chat tab can be an unprovisioned placeholder ("Chat for this project is not yet available") while the API contract is designed and the `PROJECT` surface kind is registered. This avoids shipping a half-built chat surface while ensuring the contract is designed before the surrounding tabs assume incompatible shapes.

3. **Tasks vs. Jobs vs. Plans — Naming and User Model.** The codebase uses `plan`, `task`, `workflow`, and `job` interchangeably in different contexts. The sprint directive distinguishes "tasks" (pinnable, assignable, schedulable user work items) from "jobs" (reassignable agent tasks, project-bound, multi-work-item containers). The user-facing UI should adopt this distinction consistently, even if the underlying persistence uses `plan_definitions` for tasks and `job_definitions` for jobs. Introducing a `project_task_pins` table to track tasks assigned to projects is cleaner than overloading the existing job-assignment machinery.

4. **Materialization vs. Project File Initialization.** The directive says "Directory initialized to spec on project creation." But materializations are agent-scoped — a project creates no filesystem directory. The resolution: "directory initialized to spec" means the project carries a declarative directory specification (stored in `projects` table metadata) that each agent's materialization provisioner applies when creating the materialization container. The materialization is still agent-scoped, but the initial structure follows the project spec. This preserves the architecture while satisfying the requirement.

5. **HTMX Host Fragmentation.** Moving from one `#project-tab-panel` to multiple independent hosts increases HTMX target count but eliminates stale-response bugs where one tab's error replaces another tab's content. The agent console already proves this works. The project console's simpler single-target model was appropriate when there were only 6 simple tabs; with 10+ tabs including live SSE chat and real-time queue updates, the independent-host pattern becomes necessary.

6. **Schedule/Event Scope Creep.** Adding `project_id` to schedules and reactions is mechanically simple but raises a design question: should a project schedule fire an assignment that runs on the project's owner agent, a specified agent, or any member agent? The answer should follow the existing assignment model: a schedule firing creates an `AssignmentRequest` with `agentId` (specified in the schedule), optional `projectId`, and `workspaceType`/`workspaceKey`. The agent selection is explicit, not inferred from project membership.

## Open Questions

1. **Project Chat Agent Profile Design.** What bounded reads should a project chat profile have? Minimum: project members, materialization summaries (state only, not content), active assignment status, project events, and job definitions. Should it be able to propose queue operations (like system chat)? Should it be the same fixed deny-by-default profile pattern or a configurable profile?

2. **Task Pinning Lifecycle.** When a task is "pinned" to a project, is it pinned to a specific agent or to the project generically? Can any member agent pick it up? Does unpinning remove the task from the project view or just unlink it? These semantics drive the `project_task_pins` schema shape.

3. **Project Chat Transcript Access.** The directive says "Transcripts linked to assignments via conversationId, accessible from both agent and project surfaces, read-only transcript view." Should the transcript be embedded in the project queue view (inline expansion) or navigated to a separate page? The agent console currently links to conversation history via the assignment's `conversationId`.

4. **Project Events Tab vs. Event Reactions.** The directive names both "Project Events Tab" (for setting triggers and reactions) and mentions the existing event reaction infrastructure. Should the project Events tab be a configuration surface (manage reaction rules), an audit surface (view dispatch history), or both? The agent console provides both configuration and audit in separate fragments.

5. **Visual Schedule Editor Scope.** The directive says "Visual schedule editor" and "Integration with existing schedule infrastructure." Is "visual" a cron expression builder (dropdowns for minute/hour/day/month/day-of-week plus timezone) or a timeline/gantt view? The existing schedule forms are text-field-based with validation.

6. **Project Queue Priority Ordering.** When the project queue shows assignments across multiple agents, what is the sort order? Across-agent priority is not well-defined because each agent has its own queue ordering. Options: group by agent, sort by status, or sort by priority within agent. The dashboard's `activeSummariesForProject` currently returns a flat list.

7. **Materialization Spec on Project Creation.** If a project declares a directory structure spec, where is it stored? In `projects.settingsOverrideJson`? In a new `project_directory_spec` table? When does it apply — at materialization provisioning time (agent action) or at project creation time (noop until first materialization)?

8. **Job Reassignment Contract.** The directive says "Jobs are reassignable tasks that agents perform." What does reassignment mean in terms of the existing run model? Can a running job be reassigned to a different agent mid-execution? Or does reassignment mean the next run of a recurring job uses the new agent? The `job_definitions` table already has `owner_agent_id` — reassignment could update that column for future runs while preserving historical run attribution.

## Recommended Next Step

1. **Formalize the Unified Contract Document.** Create a specification file `.internal-dev/specifications/unified-contracts.md` that codifies the entity projection matrix, the dual-scope column pattern, and the rule that cross-cutting views are filtered projections of canonical tables. This becomes the reference for all phase implementation.

2. **Design the Project Chat API Contract (Implementation-Deferred).** Specify `ChatSessionSurface.PROJECT`, the project chat route shape (`/api/projects/{projectId}/chat/stream`), the bounded read profile, the project context injection contract, and the future authorization model. Write the API doc now; implement the chat host later.

3. **Phase 1: Backend Foundation (Unified Contract + New Columns).**
   - Add `project_id` column to `schedule_definitions` and `event_reaction_definitions` via ordered migration (014).
   - Add `project_task_pins` junction table via ordered migration (015).
   - Extend `RunSummaryService` with project-scoped summary queries.
   - Add `queueAssignmentsForProject(projectId)` to `AssignmentService`.
   - Add `findSchedulesByProject(projectId)` and `findReactionsByProject(projectId)` to schedule/reaction repositories.
   - Register `ChatSessionSurface.PROJECT` and conversation filter logic (no chat endpoint yet).

4. **Phase 2: Project Queue Tab.** Implement `#project-queue-host` as an independent HTMX host reusing `RunOperationsFragments` and the agent queue row rendering pattern. Add cancellation controls via the existing lifecycle control plane (already project-aware through assignment `project_id`). The queue host can live inside the existing `#project-tab-panel` initially.

5. **Phase 3: Project Tasks Tab.** Implement the project tasks view with task pinning, priority, status tracking, and assignment lifecycle. Reuse `PlanController` and `PlanService` submission patterns but add the pinning overlay. Tasks assigned to agents show in both the project tasks view and the agent queue.

6. **Phase 4: Project Events + Schedules Tabs.** Add the configuration UI for project-scoped event reactions and schedules, reusing `ScheduleReactionFragments` and the agent-scoped editing patterns. Add the visual schedule editor as a cron-expression builder.

7. **Phase 5: Jobs Enhancement.** Add job reassignment flows (update `owner_agent_id` on `job_definitions`), richer project-level job view with run status expansion, and the conceptual boundary documentation that distinguishes tasks from jobs.

8. **Phase 6: Project Chat Tab (Deferred Implementation).** Placeholder host ("Project chat is coming soon") with the designed API contract visible in documentation. Full implementation gated on the `PROJECT` surface kind being registered and the bounded read profile being finalized.
