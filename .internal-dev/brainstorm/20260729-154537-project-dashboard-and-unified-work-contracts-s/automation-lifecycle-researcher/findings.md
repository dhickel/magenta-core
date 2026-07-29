## Prompt

Explore how reassignment, queue priority, cancellation, pinning, recurring and one-time schedules, event reactions, and project visibility compose with the existing durable runtime. Generate lifecycle and operational approaches informed by leases, fencing, revision-qualified identities, idempotency, causation, suppression, run lineage, and cross-surface observability without creating parallel execution models.

## Source

- `/home/dhickel/Code/Java/Magenta/.internal-dev/specifications/architecture.md` — Canonical task-run identity matrix, ARCH-20260720-01 (task-run storage), ARCH-20260720-03 (task-run lifecycle and independent fences), ARCH-20260727-01 (agent workspace areas), ARCH-20260727-02 (agent materialization/immutable bindings), ARCH-20260727-05 (agent console composition), cross-domain operational read model (RunSummaryService)
- `/home/dhickel/Code/Java/Magenta/.internal-dev/specifications/services.md` — SVC-20260720-02 (run lifecycle, attempts, fencing), ScheduleService (versioned six-field cron, revision-qualified firing identity, operation-key idempotency, persisted owner/fence claims), EventReactionService (revision-qualified dispatch identity, causation/deduplication, loop/depth/rate suppression), JobService, AssignmentService, TaskRunLifecycleService, RunSummaryService
- `/home/dhickel/Code/Java/Magenta/.internal-dev/specifications/api.md` — Schedule/reaction REST routes (`/api/agents/{agentId}/schedules`, `/event-reactions`), operational fragment routes, project-scoped tab fragments, execution workspace selection rules, run lifecycle operations
- `/home/dhickel/Code/Java/Magenta/.internal-dev/specifications/web.md` — Agent console composition (six independent HTMX hosts), project operational console (six tabs via `#project-tab-panel`), schedule/reaction fragment contracts, agent queue panel, system chat proposals
- `/home/dhickel/Code/Java/Magenta/.internal-dev/specifications/service-graph.md` — Identity flow, service dependency edges, active consumer inventory, project Files web adapter contract
- `/home/dhickel/Code/Java/Magenta/.internal-dev/specifications/decisions.md` — DECISION-20260720-06 (schedule/reaction revision-qualified operation identity, append-only audit), DECISION-20260720-01 (task runs as confined child resources), DECISION-20260719-02 (filesystem-first workspace identity, project association is metadata-only)
- `/home/dhickel/Code/Java/Magenta/.internal-dev/knowledge/schedule-reaction-idempotency.md` — Operation key + digest boundary, fencing for stale scheduler mutation, append-only attempts/recovery, reaction causation dedupe/loop/depth/rate suppression, pure simulation
- `/home/dhickel/Code/Java/Magenta/.internal-dev/knowledge/run-lifecycle-idempotency-and-fencing.md` — Operation keys scoped by type, canonical request digests, writer generation monotonicity, one-current-writer enforcement, SQLITE_BUSY serialization, immutable context bindings through durable owner links
- `/home/dhickel/Code/Java/Magenta/.internal-dev/knowledge/agent-queue-transcript-cancel-lifecycle.md` — Cancel lifecycle: CANCEL_REQUESTED for running leased work, direct terminal CANCELLED for non-running, lease owner preservation
- `/home/dhickel/Code/Java/Magenta/.internal-dev/knowledge/agent-scoped-assignment-lifecycle.md` — Route-agent ownership checks, ownership mismatch treated as not-found
- `/home/dhickel/Code/Java/Magenta/.internal-dev/knowledge/orchestration-runtime-phase-02.md` — Schedules using Spring CronExpression with timezone-aware next-run, event reactions matching top-level payload key/value filters, ENQUEUE_ASSIGNMENT as v1 action
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/WorkAssignment.java` — Assignment record shape: agentId, projectId, workspaceType, workspaceKey, workspaceIdentity, assignmentType, priority, status, leaseOwner, leaseFence, checkpoint, input, output, evidence, errorText, jobId, jobItemId, timestamps
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java` — Submission serialization mutex, agent-scoped lifecycle controls, project-scoped assignment queries, priority-based ordering
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java` — Job definitions with embedded plan/workflow work items, recurrence via JobRecurrence, project binding, agent ownership
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java` — Fenced scheduler with revision-qualified definitions, AgentSchedule record shape (id, agentId, jobId, assignmentTemplate, cronExpression, timezone, enabled, nextRunAt), ScheduleDefinition with six-field cron, IANA timezone, claim/fence pattern
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventReactionService.java` — Fenced event-reaction service, ReactionDefinition with event type, filters, depth/rate limits, ENQUEUE_ASSIGNMENT action, causation chain tracking
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java` — Project lifecycle, membership, project-scoped active-assignment/job-run counting for deletion guards
- `/home/dhickel/Code/Java/Magenta/src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java` — Assignment persistence, project-scoped queries, job/run queries, checkpoint/lease management

## Findings

### 1. The Existing Durable Runtime Provides Most of the Needed Foundation

The orchestration runtime already contains nearly all the primitives required for the sprint, but they are largely agent-scoped today and need projection expansion rather than new models:

**Assignment Record Shape (WorkAssignment):** Already carries `agentId`, `projectId`, `workspaceType`, `workspaceKey`, `workspaceIdentity`, `assignmentType`, `priority`, `status`, `leaseOwner`, `leaseFence`, `jobId`, `jobItemId`, checkpoint/input/output/evidence maps, and full timestamps. This is the unified container — the sprint's "unified contract" already has its data carrier. The `projectId` field exists and is persisted; the gap is in query paths and fragment rendering, not in the persistence model.

**Leases and Fencing:** The `leaseOwner`/`leaseFence`/`leaseExpiresAt` triplet on assignments, the independent writer generation on task runs (`writer_claim_generations`), and the schedule owner/fence claims all follow the same CAS pattern. Any new lifecycle operation (reassign, cancel, pause, resume) inherits this fencing: compare expected owner/fence before mutation, CAS the new value, and fail closed on mismatch.

**Revision-Qualified Identity:** Schedules and reactions already version definitions with immutable revision-qualified snapshots. Editing creates a new revision; historical firings/dispatches remain bound to the original revision. This pattern is directly applicable to task definitions (today overwrite-in-place with run snapshots — a known open question in `task-workflow-schema-and-run-snapshots.md`). Pinned tasks should adopt the same revision-qualified identity: an edit to a pinned task creates a new revision, but the pin binding captures the specific revision at pin time.

**Idempotency:** Operation keys (`operationKey` + canonical digest) are used throughout schedule firings, reaction dispatches, and lifecycle submissions. Same key + same digest converges to the recorded result; same key + different digest is a conflict. Every new mutation path (reassign, pin, cancel from project surface) should use this pattern, not a separate mechanism.

**Causation and Suppression:** The EventReactionService already tracks root event, parent event/dispatch, depth, dedupe key, and correlation before enqueuing. Loop detection (reaction-id cycles), depth caps (8), and rate suppression (10 dispatches per reaction per 60-second window) are active. Assignments enqueued via reactions inherit this causation chain; operations triggered from the project surface (manual queue operations, pin actions, cancellations) should record their own causation facts and be suppressible through the same evaluation path.

**Run Lineage:** TaskRunLifecycleService records RETRY_OF/REPLAY_OF lineage on sibling runs, and `task_run_links` provides typed parent/child relations for workflow children and job work items. Reassignment and cancellation operations can use this same lineage mechanism: a reassigned assignment creates a NEW assignment with REASSIGNED_FROM lineage to the old one; the old one moves to REASSIGNED terminal status.

**Cross-Surface Observability:** RunSummaryService and RunMeasurementRepository already provide rebuildable, non-authoritative read models over native task/workflow/job status. The agent console renders `#agent-run-groups` through `RunOperationsFragments`. The project console can consume the same services for project-scoped run views — no new persistence or query infrastructure is needed, only a filtered projection.

### 2. The Agent/Project Duality is Already Wired but Underexploited

The `WorkAssignment` record already carries both `agentId` and `projectId`. The `OrchestrationRuntimeRepository` and `AssignmentService` already have project-scoped query methods (`countActiveAssignmentsForProject`, `countActiveAssignmentsForProjectAndAgent`). The `ProjectService.requireProjectDeletionAllowed()` already queries assignment and job run counts by project.

The critical gap is not in data modeling — it's in three areas:
1. **Query methods for listing/discovering assignments by project** (the repository has counting but not listing-by-project)
2. **Fragment rendering for project-scoped queue views** (the agent queue panel renders agent-scoped; project needs its own HTMX host with the same row shape)
3. **Project-scoped lifecycle controls** (cancel, pause, resume from the project surface must validate project membership AND agent ownership)

The existing `agentId` + `projectId` dual-key pattern means a single assignment SHOWS IN BOTH the agent queue and the project queue when both are non-null. The filter is: `WHERE agent_id = X` (agent queue), `WHERE project_id = Y` (project queue), and the overlap `WHERE agent_id = X AND project_id = Y` appears in both naturally.

### 3. Priority Ordering Has an Existing but Underdeveloped Foundation

`WorkAssignment.priority` is an int field. `AssignmentService` already has priority-based ordering in `findDueAssignments` (`ORDER BY priority DESC, created_at ASC`). The queue panel displays priority as a column.

What's missing for the sprint:
- **Priority mutation API:** No endpoint to change an assignment's priority. This is a simple CAS update on the priority column.
- **Priority as a user-facing control:** The priority column renders but has no inline edit or modal control.
- **Priority as a queue-ordering claim:** The existing order-by is correct but relies on the `priority` column being treated as an ordered claim. Priority changes during queue-waiting should be safe (the assignment isn't leased yet, so no fence to check).
- **Priority inheritance:** Jobs (which spawn assignments) could inherit a default priority from project settings; schedules could carry an assignment template with priority; event reactions could carry an assignment template with priority. The `AssignmentTemplateParser` can be extended with an optional priority field.

### 4. Cancellation Lifecycle is Mature and Projectable

The existing cancel lifecycle is well-defined: `CANCEL_REQUESTED` for running leased work (preserves lease so the runner can write terminal `CANCELLED`), direct `CANCELLED` for non-running queued work. `AssignmentService` has `cancelAssignment(agentId, assignmentId)` with agent-scoped ownership checks.

For project-scoped cancellation:
- The project surface must validate membership AND agent ownership before cancelling (same pattern as agent-scoped: load assignment, verify `projectId` matches the route project, verify the caller is authorized).
- The same `cancelAssignment` internal path works regardless of which surface triggers it — no new service method needed, only a new controller route.
- System chat proposals for cancellation are already agent-scoped; a project chat (future) would need project-scoped proposal templates. This is a future concern.

### 5. Reassignment Has No Existing Mechanism and Needs the Most Design

No code exists for moving an assignment from one agent to another. This is a new capability with significant implications:

**Design Options for Reassignment:**

Option A: **Revoke-and-Clone** — Terminate the old assignment (CANCELLED/REASSIGNED) and create a new assignment for the new agent with REASSIGNED_FROM lineage. Pros: clean separation, respects existing lifecycle, lineage is traceable. Cons: new assignment id, new lease, new run id (or resume of existing run). The task-run identity question is critical — does reassignment of a running task create a new run, or resume the existing one?

Option B: **In-Place Transfer** — CAS the `agentId` on the assignment, transfer the lease to the new agent. Pros: same assignment id and run, minimal churn. Cons: violates the immutable agent binding pattern used in context capture; the old agent's immutable context binding becomes invalid; the run's provenance becomes ambiguous.

Option C: **Dual-Agent Co-Ownership** — Add `assigned_agent_id` as a mutable ownership column while preserving `original_agent_id` as provenance. The assignment runs under `assigned_agent_id` but credits `original_agent_id`. Cons: adds complexity to every agent-scoped query and context resolution.

**Recommendation:** Option A (Revoke-and-Clone) with explicit lineage. The old assignment moves to `REASSIGNED` (a new terminal status). The new assignment carries `REASSIGNED_FROM` lineage linking to the old assignment's run. The task-run identity question depends on the state of the old assignment:
- If the old assignment was QUEUED (never started): the new assignment starts fresh with a new run. The old run is terminal-cancelled.
- If the old assignment was RUNNING: the runner must be interrupted (force-interrupt), the old run terminal-cancelled, and the new assignment starts with a new run (replay-like semantics) with REASSIGNED_FROM lineage.
- If the old assignment was COMPLETED or FAILED: reassignment is semantically a replay. Create a new assignment with REPLAY_OF the old run + REASSIGNED_FROM lineage, new agent.

This approach reuses existing lifecycle primitives (CANCEL_REQUESTED, force-interrupt, REPLAY_OF lineage) rather than inventing new ones.

**Workspace Considerations:** The new agent will run under its own workspace (not the old agent's). If the task has produced artifacts under the old agent's workspace, they remain there. The new run starts fresh in the new agent's workspace. Project-scoped artifact queries (which look at all member agent workspaces) can surface both the old run's artifacts and the new run's.

**Context Binding:** The new agent's immutable context binding must be captured at reassignment time (same as any new assignment). The old agent's binding remains as historical evidence. No binding retargeting.

### 6. Pinning Composes Naturally with Existing Contracts

"Pinning" a task to a project means: this task definition is project-scoped and should be visible/executable within the project console. This is distinct from assignment — a pinned task is a DEFINITION, not an execution.

**How Pinning Maps to Existing Models:**

- A task/plan definition (`plan_definitions`) or job definition (`job_definitions`) gets a `project_id` column (nullable — null means agent-scoped, non-null means pinned).
- Alternative: A separate `task_pins` table with `(project_id, definition_kind, definition_id, pinned_at, pinned_by_agent_id, revision)` — this preserves the existing task definition model without adding columns and supports revision-qualified pinning.
- The separate-table approach follows the same pattern as agent materializations: a pin is a durable fact linking a project to a definition revision, not a column mutation on the definition.

**Pinning + Reassignment Interaction:** A pinned task assigned to agent A can be reassigned to agent B while staying pinned to the same project. The pin is on the definition, not the assignment. The assignment's `projectId` reflects the pin source but is independent.

**Pinning + Schedules Interaction:** A schedule definition is already agent-scoped (`schedule_definitions.agent_id`). For project-scoped schedules, two paths exist:
1. Add `project_id` to schedule definitions — a schedule fires assignments with that project binding.
2. Keep schedules agent-scoped but allow the assignment template within a schedule to specify a `projectId`. This is simpler and avoids changing the schedule model.

Option 2 is preferred: the assignment template already supports arbitrary fields; adding `projectId` to the template is a parser-only change.

### 7. Schedules Already Support the Sprint Requirements (With Minor Gaps)

The existing `ScheduleService` provides:
- Agent-scoped schedule definitions with six-field cron and IANA timezone
- Per-schedule enable/disable
- Revision-qualified firing identity with operation-key idempotency
- Owner/fence claims for single-process scheduler topology
- Append-only firing attempts and retry audit
- Misfire recovery and coalescing
- Pure simulation evaluator (no side effects)
- REST API routes under `/api/agents/{agentId}/schedules`
- HTMX fragment rendering via `ScheduleReactionFragments`
- Feature-gated behind `magenta.features.schedules-enabled` flag (defaults to `false`)

For the sprint, the gaps are primarily UI and project-scoping:
- **UI: Visual schedule editor** — Today schedules are edited via JSON/field forms. A visual cron builder (dropdowns for minute/hour/day/month/day-of-week) plus timezone selector (from `ZoneId.getAvailableZoneIds()`) would improve usability dramatically. This is a purely fragment-level change.
- **UI: Schedule list on project tab** — A schedules tab showing project-scoped schedules. Query: schedules whose assignment template contains a `projectId` matching the route project. This is a projection filter, not a new persistence model.
- **One-time schedules:** The six-field cron supports one-shot semantics by computing the single next firing, firing once, then auto-disabling. The existing `ScheduleDefinition` has `enabled` and `next_run_at`; a one-time schedule is simply a schedule whose cron expression computes exactly one future firing, and after that firing, the next computation returns null and the schedule auto-disables. No schema change needed.
- **Recurring schedules:** Fully supported by the existing cron-based next-run computation.
- **Project-scoped schedule queries:** The current query `where agent_id = ? and deleted = 0` needs a companion `where assignment_template like '%projectId%' and deleted = 0` or (better) a dedicated `project_id` column on `schedule_definitions`. Adding the column with a migration is straightforward and makes project-scoped schedule listing efficient.

### 8. Event Reactions Already Support the Sprint Requirements (With Minor Gaps)

The existing `EventReactionService` provides:
- Agent-scoped reaction definitions with key/value event filters
- Revision-qualified dispatch identity with operation-key idempotency
- Causation chain tracking (root event, parent, depth, deduplication)
- Loop detection (reaction-id cycles), depth cap (8), rate suppression (10/60s window)
- `ENQUEUE_ASSIGNMENT` as the v1 action (creates assignments through AssignmentService)
- Pure simulation evaluator
- REST API routes under `/api/agents/{agentId}/event-reactions`
- HTMX fragment rendering via `ScheduleReactionFragments`
- Feature-gated behind `magenta.features.reactions-enabled` flag (defaults to `false`)

For the sprint:
- **UI: Event reaction configuration** — Today reactions are configured via JSON payloads. The sprint needs a form-based configuration UI for defining event type, filter key/value pairs, and assignment template. This is a fragment rendering change.
- **UI: Project Events tab** — A tab in the project console for viewing and configuring project-scoped event reactions. Same projection pattern as schedules: a `project_id` column on `reaction_definitions` simplifies queries, or filter by assignment template content.
- **Project-scoped events:** The events themselves (`OrchestrationEventService` + `project_events` table) exist for project audit. Reactions listening for project events (e.g., "new member added", "materialization changed") could trigger assignments bound to that project. The event type catalog (`EventType`) needs project-scoped event types added.
- **Causation/inheritance through project scope:** When a project-scoped event triggers a reaction that creates an assignment, the causation chain records the event and reaction as the root/parent. Future query: "show all assignments triggered by events in project X" — achievable through the existing dispatch audit linking to assignments.

### 9. Job Enhancement Composes with the Existing Runtime

The existing `JobService` manages job definitions (revisioned, with embedded plan/workflow work items), job runs, and recurrence. Jobs already support:
- Project binding via `project_id` on `job_definitions`
- Agent ownership via `owner_agent_id` on `orchestration_jobs`
- Work items with individual plan/workflow definitions
- Recurrence scheduling via `job_definitions.recurrence_cron` and `next_run_at`
- Assignment creation through `AssignmentService`
- Empty-job detection (completes as no-op run)

For the sprint:
- **Reassignable jobs:** A job is bound to an agent at definition time (via `owner_agent_id` or assignment template). Reassignment of a job means: change the agent who executes the job's next fire. For running jobs, the assignment reassignment pattern (revoke-and-clone) applies to the active job run. For future fires, updating the job definition's agent binding changes the target. This maps cleanly to the existing model.
- **Jobs in both dashboards:** A job with `project_id` non-null spawns assignments with `projectId` populated. Those assignments appear in both the agent queue AND the project queue through the dual-key filter pattern. No new persistence — the assignment `projectId` field already bridges the surfaces.
- **Job lifecycle:** create → assign to agent → fire (spawns assignment) → complete/cancel/reassign. The existing `JobRunStatus` tracks per-run state; the `OrchestrationJob` tracks definition state. Lifecycle operations (pause job, resume job, cancel pending fires) are state transitions on the job definition, not on individual assignments.

### 10. Project Visibility as a Filtered Projection, Not a Parallel Model

The most important cross-cutting finding: every operational concern (queue, runs, schedules, reactions, transcripts, artifacts) already has a canonical source of truth. Project visibility is universally a FILTERED PROJECTION of that same truth:

| Domain | Canonical Source | Agent-Query Dimension | Project-Query Dimension |
|--------|-----------------|----------------------|------------------------|
| Queue/Assignments | `work_assignments` | `WHERE agent_id = X` | `WHERE project_id = Y` |
| Runs | `plan_runs`, `workflow_runs`, `job_runs` | Via assignment `agent_id` | Via assignment `project_id` |
| Schedules | `schedule_definitions` | `WHERE agent_id = X` | `WHERE project_id = Y` (add column) |
| Reactions | `reaction_definitions` | `WHERE agent_id = X` | `WHERE project_id = Y` (add column) |
| Transcripts | Conversations linked to assignments | Via `assignment.agent_id` | Via `assignment.project_id` |
| Artifacts | `run_output_artifacts` (workspace-scoped) | `WHERE workspace_type='agent' AND workspace_key = X` | Via project members' agent workspaces |
| Files | Materialization-based read-only browser | N/A (agent-scoped) | Via `MaterializationLocation` for project members |
| Job Definitions | `job_definitions`, `orchestration_jobs` | `WHERE owner_agent_id = X` | `WHERE project_id = Y` |

No domain needs a parallel store. The same repository methods serve both surfaces with different WHERE clauses. The same fragment renderers (assignment rows, status chips, run groups, transcript viewers) render in both console contexts.

This means the unified contract is not a new abstraction layer — it is the EXISTING `WorkAssignment` record and the existing repository/service methods, with:
1. New controller endpoints for project-scoped queries
2. New fragment hosts in the project console
3. Shared fragment renderer components (or cloned with different OOB targets)
4. Minor schema additions (nullable `project_id` on schedule/reaction/reaction tables)

## Options

### Option 1: Minimal Column Expansion + Shared Renderers

Add `project_id` to `schedule_definitions`, `reaction_definitions`, and `task_pins` table. Project-scoped queries use these columns directly. Fragment renderers are shared between agent and project consoles through parameterized HTMX endpoints. Reassignment uses the revoke-and-clone pattern with new `REASSIGNED` status.

**Pros:** Smallest schema change; fully aligned with existing patterns; reuses all existing lifecycle/fencing/idempotency infrastructure; every query path is a simple WHERE clause variation.
**Cons:** `project_id` columns on schedule/reaction tables are nullable and coexist with agent-scoped paths; the `REASSIGNED` terminal status is a new enum value requiring migration; shared fragment renderers need careful OOB target isolation.

### Option 2: Separate Mapping Tables

Instead of adding `project_id` columns, add separate mapping tables: `schedule_project_bindings`, `reaction_project_bindings`, `task_project_pins`. Agent-scoped rows have no mapping row; project-scoped rows have one.

**Pros:** Does not pollute the schedule/reaction tables with a nullable project dimension; pins are naturally multi-project (a task could be pinned to multiple projects); clean separation of concerns.
**Cons:** More tables, more joins, more migration; querying "all schedules for project X" requires a join; the simple WHERE-clause projection pattern becomes a two-table query; more infrastructure for the same outcome.

### Option 3: Assignment Template Content-Based Filtering

Use the existing `assignment_template` JSON column on schedules and reactions to carry `projectId`. Project-scoped queries filter by JSON content (SQLite's `json_extract`). No schema changes for schedules/reactions.

**Pros:** Zero schema migration for schedules/reactions; the assignment template already serializes arbitrary fields; no new columns or tables.
**Cons:** JSON-path queries are less efficient and less type-safe; indices on `json_extract` results are not as clean as indexed columns; the pattern is harder to understand and maintain; any renaming or restructuring of the template breaks queries.

### Option 4: Project-Scoped Variants as Separate Definition Rows

Create project-scoped schedule/reaction definitions as new rows with a different `agent_id` (the project's owner agent) and explicit `project_id`. No new columns — use the existing `agent_id` as the scoping key and add `project_id` inline.

**Pros:** Minimal schema change (single column); the agent who owns the definition is explicit; the same CRUD endpoints work with an optional `projectId` filter parameter.
**Cons:** Two rows for the "same" schedule if it's both agent-scoped and project-scoped; more confusing model.

### Recommendation: Option 1

Option 1 (add nullable `project_id` columns + shared renderers) is the most direct, most aligned with existing patterns, and least disruptive. The `project_id` column is already on `work_assignments`, `orchestration_jobs`, and `job_definitions` — extending it to schedules and reactions is consistent. Shared fragment renderers already exist (the agent queue panel HTML is rendered by `OrchestrationController`; the same row template can be parameterized by context). The `REASSIGNED` status is a new terminal state that follows the existing `OrchestrationStatus` enum pattern. The `task_pins` table is a new small mapping table (schema: `id`, `project_id`, `definition_kind`, `definition_id`, `revision`, `pinned_at`, `pinned_by_agent_id`).

## Trade-offs

### Shared Renderers vs. Duplicated Renderers
**Trade-off:** Shared fragment renderers (same Java method, parameterized by context) reduce code duplication but risk OOB target collision. Duplicated renderers (separate render paths for agent vs. project console) are safer for independent HTMX hosts but duplicate markup.

**Analysis:** The agent console already has six independent HTMX hosts that never replace each other. The project console will have its own hosts. Shared renderers that produce HTML targeting `#agent-queue-panel` when called from the agent controller and `#project-queue-panel` when called from the project controller are safe — the OOB target is a parameter, not a constant. The SimplyPages component/module pattern is ideal here: a `QueueRowComponent` that accepts `hostId` as a parameter and renders the same row shape with the correct target.

**Decision direction:** Shared Java renderers with parameterized HTMX targets. SimplyPages `SlotKey` instances for static shared data (column headers, empty states) and per-request dynamic row rendering.

### One-Time Schedule: Cron Expression vs. Explicit One-Shot Flag
**Trade-off:** Using a cron expression for one-time schedules reuses the existing computation but requires the user to construct a cron that fires exactly once. Adding an explicit `fire_at` timestamp column with a `one_shot` boolean flag is simpler for users but splits the scheduling model.

**Analysis:** For a visual schedule editor, the distinction is presentation-only: the editor presents "One time" and "Recurring" as radio options. For one-time, the UI generates a cron expression that fires once at the specified datetime. The backend doesn't need to know — it processes crons uniformly. This avoids schema changes and keeps the ScheduleService clean.

**Decision direction:** One-time schedules are just schedules whose cron expression evaluates to exactly one future firing. The UI handles the translation. No backend change needed.

### Reassignment as New Assignment vs. In-Place Mutation
**Trade-off:** Creating a new assignment with lineage preserves immutability of the original assignment but creates more rows and a new run. Mutating the existing assignment's `agentId` is simpler but breaks context bindings and creates ambiguity.

**Analysis:** The existing architecture prioritizes immutable facts and explicit lineage (especially for context bindings, which are insert-only immutable snapshots). Reassignment as a new assignment with REASSIGNED_FROM lineage aligns with this philosophy. The cost is more rows and a new run UUID — these are cheap in SQLite and already the pattern for retry/replay.

**Decision direction:** Revoke-and-clone. The old assignment moves to `REASSIGNED` terminal status. The new assignment carries `REASSIGNED_FROM` lineage. The task-run identity follows the existing retry/replay model: new assignment, new run, lineage link to old run.

### Priority as a First-Class Dimension vs. Simple Column
**Trade-off:** Priority is currently an int column with DESC ordering. Making it a full dimension (priority classes with names, inheritance rules, escalation policies) would support richer queue behavior but adds complexity that may never be needed.

**Analysis:** The sprint directive says "queue ordering, priorities" — this suggests the priority column plus the ability to change priority, not a full priority class system. The existing int column with ascending values (higher = more urgent) and the `ORDER BY priority DESC, created_at ASC` ordering is sufficient. Adding a priority mutation endpoint and inline priority editing in the queue panel satisfies the requirement without over-engineering.

**Decision direction:** Keep priority as a simple int. Add PUT endpoint for priority mutation. Render as editable chip in queue rows.

### Project Visibility as Filter vs. Separate Store
**Trade-off:** Filtering a canonical source of truth is simple but means project-scoped queries may scan rows outside the project. A separate project-scoped assignment table would be faster but duplicates truth.

**Analysis:** The existing architecture explicitly says "cross-cutting views are filtered projections, not separate stores" and "the canonical source of truth for assignments, runs, and status must remain singular." SQLite with indexed `project_id` columns handles filtered queries efficiently. The duplication risk (stale project-scoped copies, reconciliation failures, split-brain) far outweighs any performance concern.

**Decision direction:** No separate stores. All project views are filtered projections of the canonical tables. Index `project_id` on `work_assignments`, `schedule_definitions`, `reaction_definitions`, and `task_pins`.

## Open Questions

1. **Should reassignment trigger a context re-capture for the new agent?** When assignment A (agent X, project P, context binding B_X) is reassigned to agent Y, should the new assignment automatically capture Y's current project context binding for P? Yes, following the existing assignment-binding operation pattern: the reassign submission is a durable operation that links a new context capture (agent Y, project P, materialization at current generation) with the new assignment insertion. This aligns with `DECISION-20260727-01` immutable binding semantics.

2. **Can a user-scoped task be pinned to a project?** User workspaces have no project association. Pinning a task means the task definition becomes project-visible and executable by project member agents. User-scoped tasks would need an agent to execute — the pin would include a target agent selection. Or pinning is agent-only: tasks defined in agent workspaces can be pinned to projects; user tasks stay personal. Recommend: pinning is for agent-owned definitions only (consistent with the closed workspace model).

3. **How should the project queue handle assignments from schedules vs. manual vs. reactions?** The `AssignmentType` enum (`TASK`, `WORKFLOW`, `JOB`, etc.) plus the `jobId`/`jobItemId` fields already distinguish assignment provenance. The queue row can render a source chip ("Schedule", "Reaction", "Manual", "Job") derived from these fields. No new column needed.

4. **Should project-scoped schedules inherit the project's timezone?** The schedule definition already has a `timezone` field. The project could have a `default_timezone` column, and the schedule editor UI pre-selects it. The backend doesn't infer timezone — it always uses the schedule's explicit timezone. This is a UI-only concern.

5. **What happens to project-scoped schedules/reactions when the project is deleted?** Following the existing `ProjectService.requireProjectDeletionAllowed()` pattern: project deletion is blocked while active assignments exist. The sprint should extend this guard to include pending schedule firings and active reaction subscriptions. Schedules with no pending firings can be soft-deleted along with the project (set `deleted = 1`). Reactions are soft-deleted similarly. This preserves historical audit.

6. **Can a project member agent view assignments from other project members in the project queue?** The current open-alpha posture has no authentication/authorization. The sprint directive says "Multi-user authentication/authorization is out-of-scope." Given this, project queue visibility shows all project assignments. If the agent console shows only that agent's assignments (existing behavior), and the project console shows all project assignments (new behavior), there's no conflict — they're different surfaces with different scoping. Future authorization would add member-level filtering.

7. **Should reassignment be allowed while an assignment is QUEUED but not yet RUNNING?** Yes, this is the safest case. No runner has acquired the lease. The assignment can be terminal-cancelled and a new one created without force-interrupt or lease expiry concerns. The same operation-key idempotency pattern applies: submit a reassignment operation, get back the new assignment id (or the existing one if retried).

8. **How does pinning interact with task definition revisions?** If a task is pinned at revision 3, and the task is edited to revision 4, the pin should remain at revision 3 until explicitly updated. This matches the snapshot-based run model: task runs snapshot the definition at execution time. Pins are similar — they snapshot the definition at pin time. The pin can be "refreshed" to the latest revision explicitly (with a new pin row). This follows the revision-qualified identity pattern from schedules/reactions.

9. **Should cancellation from the project surface require agent confirmation?** The existing system chat proposal path (propose → confirm) could be reused. A project-scoped cancel button creates a system-chat proposal for the owning agent. Or, simpler: direct cancellation from the project surface bypasses proposals since the project surface is an operator console (like the agent console). Recommend: direct cancellation (and all lifecycle controls) from both agent and project surfaces — consistent with the agent console's existing pattern. Proposals remain a system-chat feature for conversational interaction, not a gate on direct operational controls.

10. **Do project-scoped schedules fire if the owning agent is disabled?** The ScheduleService's due-selection query already filters by `enabled = 1` on the schedule. It does not check agent status. For project-scoped schedules, the question is: should the schedule be tied to a specific agent's liveness, or to the project? Recommend: project-scoped schedules are project-owned, not agent-owned. They fire as long as the schedule is enabled, regardless of any specific agent's enablement. The assignment template specifies the target agent (or the scheduler selects an available member agent — but this is complex and probably out of scope). The simpler path: the assignment template has an explicit `agentId`; if that agent is disabled, the firing attempt fails with an audit record (agent unavailable). This matches the existing per-agent schedule behavior.

## Recommended Next Step

1. **Begin with the unified contract data model pass:** Define the exact schema changes (add `project_id` to `schedule_definitions`, `reaction_definitions`; add `task_pins` table; add `REASSIGNED` to `OrchestrationStatus`; add `REASSIGNED_FROM` to `RunLineageMode`; index `project_id` on all relevant tables). This is a single small migration (014) that unblocks all subsequent phases.

2. **Prototype the dual-key query pattern:** Implement one project-scoped query method (`findAssignmentsByProject`) in `OrchestrationRuntimeRepository` and a minimal project queue fragment endpoint. Prove that the same `WorkAssignment` record, same repository, same fragment renderer serves both agent and project surfaces with different WHERE clauses. This validates the core architectural claim before building seven tabs.

3. **Design the reassignment operation as a lifecycle extension:** Model the revoke-and-clone flow as a new `TaskRunLifecycleService` operation type (`REASSIGN`), reusing existing operation-key idempotency, lineage recording, and context capture infrastructure. Write the service-level test before any UI work. Reassignment touches the most invariants (leases, bindings, lineage, workspaces) and should be proven at the service layer first.

4. **Defer the project chat tab:** The directive explicitly says "plan for the API contract now, implement later." Capture the contract in `deferred-features.md` or `horizon-ideas.md` with a clear API shape, but do not build it in this sprint.

5. **Build tabs in dependency order:** After the unified contract and reassignment are proven, build tabs in this order: (a) Project Files (leverages existing materialization infrastructure), (b) Project Queue (dual-key projection on proven query paths), (c) Project Tasks/Pinning (new `task_pins` table but simple CRUD), (d) Project Jobs (extends existing job model), (e) Project Events (reaction configuration UI over existing engine), (f) Project Schedules (schedule editor UI over existing engine), (g) Project Dashboard (aggregate view over all other tabs' data).
