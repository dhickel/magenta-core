
## Prompt

Explore a coherent project-console information architecture and reusable interaction system across Queue, Chat, Tasks, Files, Events, Jobs, and Schedules. Consider shared agent/project fragments, independent HTMX hosts, stale-response isolation, semantic controls, transcript/detail flows, responsive list-detail layouts, and where existing SimplyPages patterns or narrowly justified SSE/JavaScript islands fit.

## Source

Inspected the authoritative specification store (`.internal-dev/specifications/architecture.md`, `web.md`, `simplypages.md`, `services.md`, `api.md`, `service-graph.md`), the monolithic `OrchestrationController` (~8,700 lines) containing both agent and project console rendering, the `WorkAssignment` record and `AssignmentType` enum, `JobDefinition`/`JobService`, `ScheduleService` and `EventReactionService`, CSS patterns in `orchestration.css`, SimplyPages documentation and component catalog at `/home/dhickel/Code/Java/SimplyPages/docs/`, and the knowledge store (`agent-lifecycle-htmx-targets.md`, `operational-ui-contract-phase-01.md`, `schedule-reaction-idempotency.md`, `run-lifecycle-idempotency-and-fencing.md`, `filesystem-first-workspaces.md`, `operational-ui-htmx-parity-patterns-pass-02.md`).

## Findings

### 1. Architecture foundation is strong but surface composition is monolithic

The backend has clean, hardened contracts: closed three-kind workspace identity (AGENT/CHAT/USER), canonical task-run identity matrix, revision-qualified schedule/reaction definitions, durable assignment lifecycle with lease fencing, operation-key idempotency, immutable context bindings, and generation-safe materializations. Project association is correctly metadata-only — projects have no filesystem root authority. The specification layer is explicit and well-maintained.

However, the surface composition lives almost entirely in `OrchestrationController.java` (~8,700 lines), which renders both the agent console and the project console inline. The agent console already has a well-defined independent-host architecture (`#agent-context-panel`, `#agent-queue-panel`, `#agent-skills-panel`, `#system-chat-panel`, `#agent-run-groups`) but the project console has not yet adopted this pattern beyond the six existing stub tabs. The project Queue, Chat, Tasks, Events, and Schedules tabs are entirely absent.

### 2. The unified assignment contract already supports cross-cutting views

`WorkAssignment` already carries the fields needed for dual query surfaces:
- `agentId` — for agent-scoped queue queries
- `projectId` — for project-scoped queue queries
- `assignmentType` — TASK_RUN, WORKFLOW_RUN, JOB_RUN, AGENT_MESSAGE, WAIT_FOR_MESSAGE, REPORT
- `status`, `priority`, `workspaceType`, `workspaceKey`, `workspaceIdentity`

The overlap guarantee (assignment WHERE agent_id=X AND project_id=Y) is already a query projection, not a separate store. `AssignmentService.activeSummariesForProject(projectId)` already exists and is consumed by the project Dashboard tab but only as summary counters — the full queue view is not rendered.

**Gap:** There is no existing `projectQueueTab()` rendering method. The agent queue tab (`agentQueueTab`) renders full assignment lists through `renderAssignmentList`, but this helper is agent-scoped. A project-scoped version needs to reuse the same row-rendering logic while respecting the project's independent-host boundary.

### 3. The project dashboard is feature-aware but surface-thin

The current project Dashboard tab displays member cards, assignment summaries, recent jobs, and materialization counts — proving the services are wired. But clicking any dashboard counter navigates to the corresponding tab but does not carry the rich interactive behavior present in the agent console:
- No cancellation/resume/pause controls on queue items
- No transcript/diagnostics expansion
- No job create/edit lifecycle
- No schedule or event CRUD
- No chat surface at all

### 4. Existing UI contracts are ready for reuse

The specification store defines:
- **Host isolation pattern:** Each operational host is independently loaded through HTMX. A response replaces only its own host. `#project-tab-panel` is the existing target for all project tab swaps, which is correct but insufficient — sub-host replacement needs to be granular for list-detail flows.
- **Semantic chips:** `orch-status-chip` with values like `active`, `failed`, `completed`, `paused`, `queued`, `tracked` are already styled. New project-level surfaces should reuse these.
- **Compact table/row patterns:** `project-operational-list`, `orch-row`, `project-job-row` are styled. A new unified queue/task/job row format can extend these.
- **Status badge pattern:** Normalized state → consistent chip rendering.
- **Fragment route contract:** `GET /projects/_detail/{projectId}/{tab}` targets `#project-tab-panel`. This needs to extend to sub-fragments for detail expansion (similar to `GET /agents/_detail/{agentId}/runs/{taskRunId}` → `#agent-run-detail`).

### 5. Jobs system has definitions and runs but limited surface integration

`JobDefinition` carries `ownerAgentId`, `projectId`, `workspaceType`, `workspaceKey`, `workspaceIdentity`, `status`, `title`, `summary`, and `items`. `JobService` supports CRUD and listing with optional agent/project filters. The current project Jobs tab shows job definitions with status badges but has no run drill-down, no transcript, and no lifecycle controls. "Bounded project run facts are unavailable from the current job query contract" is displayed instead of actual run history — indicating the query contract needs to be extended.

### 6. Schedule and reaction infrastructure exists but lacks project-scoped surfaces

`ScheduleService` and `EventReactionService` are agent-scoped services (`/api/agents/{agentId}/schedules` and `/api/agents/{agentId}/event-reactions`). Definitions carry `agent_id` and are revision-qualified. There is no project-level schedule or reaction binding. Two approaches exist:
- **Approach A (extend existing):** Add `project_id` to `schedule_definitions` and `reaction_definitions`, making schedules/reactions project-scoped but still owned by agents.
- **Approach B (binding layer):** Keep schedules/reactions agent-scoped and add a lightweight project-schedule/reaction binding table that links a project to agent-scoped definitions without duplicating them.

### 7. Task definitions live in plan/chat packages but not yet as project-assignable entities

Tasks are currently `PlanDefinition`/`TaskDefinition` records produced by chat plan flows. There is no CRUD for user-created tasks, no project pinning, and no explicit task-assignment lifecycle separate from a chat-generated plan. The directive calls for a project Tasks tab where tasks can be assigned, pinned, prioritized, and tracked — this requires a new task-entity contract that bridges the gap between ad-hoc chat plans and durable project-tracked work.

### 8. No project chat exists, but the API contract surface is planned

The directive says "plan for the API contract now, implement later." The architecture supports chat scoped to conversations with context bindings. A project chat would need:
- A project-bound conversation with immutable project context binding
- The same SSE/streaming pattern as other chats
- A new `ChatSessionSurface.PROJECT` or equivalent classification
- A `ConversationTurnCoordinator` gate for the project conversation

## Options

### Information Architecture: Hub-and-Spoke with Sub-Hosts

Each project tab becomes an independent HTMX host with its own stable DOM ID. Within each tab, list-detail flows use sub-host targets to avoid replacing the full tab panel.

**Host map for the project console:**
```
#project-tab-panel          (existing — tab content container)
  #project-queue-host       (NEW — Queue tab)
  #project-chat-host        (NEW — Chat tab, future)
  #project-tasks-host       (NEW — Tasks tab)
  #project-files-host       (existing, needs sub-host refinement)
  #project-events-host      (NEW — Events tab)
  #project-jobs-host        (existing, needs sub-host refinement)
  #project-schedules-host   (NEW — Schedules tab)
  #project-settings-host    (existing)
```

Each sub-host is independently loaded via `hx-get` and `hx-trigger="load"` with `hx-target` being the sub-host itself. Detail expansions load sibling detail panels (e.g., `#project-queue-detail` beside `#project-queue-list`).

### Unified Contract: AssignmentRow with Polymorphic Detail

**Recommendation:** Define a render-time contract `OperationalItem` that normalizes assignments, tasks, and jobs into a single sortable/filterable row shape:

```java
record OperationalItem(
    String id,              // canonical identity (assignment id, task id, job id)
    String kind,            // "assignment", "task", "job"
    String agentId,         // bound agent (null for unassigned tasks)
    String projectId,       // bound project
    String displayName,     // task title, job title, plan name
    String status,          // QUEUED, RUNNING, COMPLETED, etc.
    String priority,        // numeric or label
    Instant updatedAt,
    String conversationId,  // for transcript linking
    String taskRunId        // canonical task-run identity
) {}
```

This lets the Queue tab render assignments AND pinned tasks AND job runs in a single unified table, sorted and filtered by kind/status/priority. The agent queue reuses the same renderer with `agentId=X` filter; the project queue with `projectId=Y` filter.

### Queue Tab: List + Inspector + Transcript

**Pattern:** Master list (`#project-queue-list`) with an inline inspector/transcript panel (`#project-queue-detail`).

```
Desktop: [list 60%] [detail 40%]
Mobile:  [list 100%] then [detail 100%] (stacked)
```

Row clicks load detail into the sibling panel via `hx-get` + `hx-target="#project-queue-detail"`. Detail shows:
- Diagnostics (status, lease state, attempt count, elapsed)
- Transcript (read-only, static for terminal assignments)
- Controls (cancel, pause, resume — only when user-authorized)

**Safety:** The `#project-queue-detail` host must be cleared when a row is deselected or when a mutation changes the assignment. Stale responses targeting the old detail host must not persist after a list refresh.

### Chat Tab: Skeleton + Future API Contract

**Now:** Render a chat host shell that shows "Project chat is not yet available" with the planned API shape to be implemented later.

**Future API contract:**
```
POST /api/projects/{projectId}/chat/stream?message=...
GET  /api/projects/{projectId}/chat/history?conversationId=...
GET  /api/projects/{projectId}/chat/conversations
POST /api/projects/{projectId}/chat/conversations  (create project conversation)
```

The SSE stream follows the same `start` → `message` → `done`/`error` event pattern as existing chat streams. The project chat JavaScript surface would be a narrow island that only manages the SSE EventSource and DOM append, mirroring `system-chat.js`.

### Tasks Tab: Project-Pinned Task CRUD + Assignment Lifecycle

**New entity model:**
- `ProjectTask` — durable task definitions pinned to a project (title, description, priority, status)
- Can be assigned to an agent, creating an assignment
- Can be scheduled, shared with the schedule infrastructure
- Shows both in project Tasks tab and agent Queue when assigned

**Tab layout:** List of project tasks with inline create/edit forms. Assignment to an agent creates both the task-assignment link and the durable assignment record. The same row renderer from the Queue tab can display the task in agent and project queues.

### Files Tab: Refinement of Existing Pattern

The existing project Files tab is already materialization-aware and read-only — this is architecturally correct. Improvement areas:
- Sub-host refinement so individual materialization loads don't replace the entire `#project-tab-panel`
- Stable list (`#project-files-list`) and inspector (`#project-files-inspector`) sub-targets
- Materialization selector should OOB-refresh only the files sub-hosts, not the full tab

### Events Tab: Project-Scoped Reaction Configuration

**Option B (recommended — binding layer):** Keep `schedule_definitions` and `reaction_definitions` agent-scoped. Add a `project_event_bindings` table:

```sql
CREATE TABLE project_event_bindings (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    definition_id TEXT NOT NULL,    -- schedule or reaction id
    definition_kind TEXT NOT NULL,  -- "schedule" or "reaction"
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);
```

This avoids duplicating schedule/reaction logic while allowing project-scoped views. The Events tab shows:
- Agent-owned schedules and reactions relevant to this project
- Enable/disable per project (project-scoped suppression without touching the agent definition)
- Simulation previews (reusing existing `ScheduleService`/`EventReactionService` simulation with project-scope filter)

### Schedules Tab: Visual Cron Editor + Project Binding

Same binding-layer approach as Events. The Schedules tab renders:
- Agent-owned schedules that are bound to this project
- A visual cron editor (6-field: second, minute, hour, day-of-month, month, day-of-week + IANA timezone)
- Firing history with attempt audit
- Non-executing simulation preview button
- Enable/disable toggle per project

**Visual cron editor:** A SimplyPages fragment with six dropdowns/inputs, timezone selector, and a human-readable schedule preview that updates inline via HTMX. The existing `ScheduleService` cron validation and simulation evaluator provide the backend contract.

### Jobs Tab: Full Lifecycle with Run Drill-Down

Extend the existing project Jobs tab from definition-only to full lifecycle:
- Create/edit job definitions (existing partial coverage)
- Assign job to agent (creates assignment)
- Run history with status, elapsed, attempt counts
- Transcript access via conversation link
- Reassign capability (change agent binding)
- Cancel/complete lifecycle controls

The existing `JobService.listDefinitions(null, projectId, null)` and `AssignmentService` provide the backend contracts. The gap is in rendering the enriched job rows with run detail expansion and lifecycle controls.

## Trade-offs

### T1: Extend OrchestrationController vs. Extract ProjectConsoleController

**Extend:** Keeps all orchestration fragments in one place, avoids wiring churn, and preserves the current pattern. However, the file is already 8,700 lines and grows linearly with each tab.

**Extract:** A dedicated `ProjectConsoleController` or `ProjectConsoleFragments` class would match the specification's guidance on controller separation. However, the current `OrchestrationController` holds substantial shared rendering helpers (`renderAssignmentList`, `projectCounterCard`, `entitySelector`, `statusBadgeHtml`, etc.) that would need to be refactored into a shared fragment utility.

**Recommendation:** Extract shared rendering into a `ConsoleFragmentRenderer` or similar stateless utility, then create `ProjectConsoleFragments` for project-scoped tab rendering. Keep `OrchestrationController` focused on `/agents` and the project chooser/page shell. This is a moderate refactor but prevents the controller from becoming unmaintainable.

### T2: Single Unified Queue vs. Domain-Separated Tabs

**Unified:** Queue tab shows assignments, tasks, and jobs in one sortable table. Simpler mental model, less tab switching. Risk: overloaded table with mixed semantics.

**Separated:** Queue tab = running assignments only. Tasks tab = pinned task definitions. Jobs tab = job definitions and runs. Matches the directive's explicit tab list but splits related data across surfaces.

**Recommendation:** Unified Queue tab with a kind filter column. The Queue shows everything that runs (assignments, task runs, job runs). Tasks focuses on definition management (create, edit, assign, pin, prioritize). Jobs focuses on definition lifecycle and reassignment. This preserves the directive's tab structure while keeping the Queue as the operational "what's happening" surface.

### T3: Schedule/Reaction Project-Scoping via Binding vs. Schema Extension

**Binding layer (option B):** Clean separation — schedules/reactions remain agent-scoped. Projects bind to them. No schema changes to the proven schedule/reaction tables. Risk: binding table adds a join for all project queries.

**Schema extension (option A):** Simpler queries — `WHERE project_id = ?` directly on the schedule/reaction tables. Risk: migration on hardened tables, and the semantic question of "who owns the definition" becomes muddy (agent or project?).

**Recommendation:** Binding layer. The separation of ownership is cleaner architecturally. Agent-owned schedules/reactions are the canonical source. Projects observe and optionally suppress them. This also naturally supports the directive's overlap requirement (same schedule shows in agent and project views).

### T4: Task Entity — Extend PlanDefinition vs. New ProjectTask

**Extend PlanDefinition:** Reuses the existing plan/task definition model. But `PlanDefinition` is tightly coupled to chat plan flows and carries plan-specific fields (steps, fields, kind, etc.) that don't apply to project-pinned tasks.

**New ProjectTask:** Clean new entity with exactly the fields needed (title, description, priority, status, projectId, assignedAgentId, conversationId). Requires new table, repository, and service. But avoids coupling the chat plan model to project task management.

**Recommendation:** New `ProjectTask` entity. The chat plan model is purpose-built for AI-generated executable plans. Project tasks are user-managed work items. Conflating them would create awkward optional fields and validation rules.

### T5: SSE Island for Project Chat vs. Pure HTMX Polling

**SSE:** Matches the existing chat pattern (`system-chat.js`). Narrow JavaScript island only for EventSource + DOM append. Provides real-time streaming for chat messages.

**Polling:** Keeps the HTMX-first policy. But server-sent chat messages don't fit the polling model well — polling would introduce latency and unnecessary requests.

**Recommendation:** SSE island for project chat, following the exact `system-chat.js` pattern. The directive explicitly calls this out: "No new JavaScript transport unless SSE streaming is needed for chat." This is the one place where JavaScript is justified.

## Open Questions

1. **Should the project Queue tab default to showing ALL project-scoped assignments, or only active (non-terminal) ones?** The agent queue shows all assignments. For project surfaces with potentially many historical runs, pagination and a default filter to active/running might be more useful.

2. **Should task priority be a simple integer (1-n) or a named tier (LOW, MEDIUM, HIGH, CRITICAL)?** The current `WorkAssignment.priority` is an integer. Named tiers would be more user-friendly but require mapping logic.

3. **Should the project chat create a dedicated conversation type, or reuse the existing conversation model with a project scope field?** The architecture has `ChatSessionSurface` enum (currently includes MAIN, PLAN_SAVED, etc.). Adding `PROJECT` as a surface type is cleaner than adding an optional projectId field to generic conversations.

4. **Should project schedules/reactions be editable from the project console, or read-only mirrors of agent-console managed definitions?** If editing from the project console is allowed, the binding layer needs operation-key idempotency to prevent simultaneous edits from the agent console and project console.

5. **How should job reassignment work when a job is already running?** Cancelling the current assignment and creating a new one for the target agent seems safest, but this loses partial progress. Pause → reassign → resume could preserve work-item progress.

6. **Should the Files tab show a unified view across all members' materializations, or force single-member selection?** The current implementation forces single-member selection. A "side-by-side diff" or "merge view" across members would be powerful but complex. The specification says "read-only current-member materialization reads" — single-member is correct for now.

7. **Should the Events tab also show event history (fired events, not just reaction config)?** The `OrchestrationEventService` and `ProjectEvent` model exist. Including a read-only event history panel alongside reaction configuration would make the tab more useful.

8. **How should the unified Queue table handle the fact that different item kinds have different detail views (assignment diagnostics vs. task definition vs. job run)?** A polymorphic detail renderer that inspects the `kind` field and renders the appropriate detail fragment is needed. This is the same pattern used by `RunOperationsFragments` for different source-kind run details.

## Recommended Next Step

1. **Extract `ConsoleFragmentRenderer`** (Phase 0): Pull shared rendering helpers (status badges, operational rows, entity selectors, counter cards, loading placeholders, error handling) from `OrchestrationController` into a stateless renderer utility. This unblocks all subsequent tab work without duplicating rendering logic.

2. **Define the unified operational-item contract** (Phase 0): Create `OperationalItem` record and the `OperationalItemService` that queries assignments, tasks, and jobs into a unified sortable/filterable projection. Apply this first to the existing agent Queue tab to validate the contract before adding project surfaces.

3. **Implement Queue tab** (Phase 1): First cross-cutting surface. Validates the unified contract, host isolation, list-detail pattern, and transcript access. Both agent-scoped and project-scoped queries use the same render paths.

4. **Design ProjectTask entity and Tasks tab** (Phase 2): New entity + repository + service. Tasks tab with CRUD, assignment, and pinning. Tasks that are assigned appear in both project and agent queues via the unified contract.

5. **Jobs system enhancement** (Phase 3): Run drill-down, lifecycle controls, reassignment. Extends existing jobs tab. Ensures job assignments appear in unified queue.

6. **Schedule/Reaction binding layer + Schedules and Events tabs** (Phase 4): Project binding table, project-scoped views of agent-owned schedules and reactions. Visual cron editor. Reuses existing `ScheduleService` and `EventReactionService` simulation.

7. **Project Chat skeleton + API contract** (Phase 5): Chat host shell with planned API shape documented. No backend implementation yet, but the frontend host is in place so the tab is not empty.

8. **Files tab refinement** (Phase 6): Sub-host refinement for independent materialization loads. Inspector and list sub-targets. No architectural changes needed.

Each phase should be independently validated with Playwright screenshots and functional checks, following the established validation contract. The unified contract (Phase 0) must be confirmed correct before Phases 1-6 proceed, as all downstream tabs depend on it.
