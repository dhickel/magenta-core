## Prompt

> Expand the Magenta project dashboard from its current six-tab console (Dashboard, Members, Files, Jobs, Outputs, Settings) into a comprehensive operational surface that mirrors and extends the agent dashboard capabilities, with robust unified contracts across tasks, jobs, queues, schedules, events, and projects.

The directive calls for seven new or enhanced surfaces (Project Queue, Chat, Tasks, Files enhancement, Events, Jobs enhancement, Schedules) plus cross-cutting unified contract design. It demands dependency-driven phasing, migration/compatibility hazard analysis, fail-closed execution constraints, scope-control decisions, rollout seams, and independently provable validation strategies.

## Source

Inspected (read-only) across 14+ specification, knowledge, and source files:

| Source | Purpose |
|---|---|
| `.internal-dev/specifications/architecture.md` | Current architecture contract, workspace identity, task-run identity matrix, operational read model, artifact/skill/runtime/automation architecture, drift/gaps |
| `.internal-dev/specifications/service-graph.md` | Dependency direction, identity flow, active consumer inventory, operational composition edges |
| `.internal-dev/specifications/services.md` | Service behavior contracts for workspace directory, explorer, metadata, output publication, execution selection, lifecycle/fencing, task-run child resolution, skills, system chat, context/binding, schedules, reactions |
| `.internal-dev/specifications/api.md` | Route/payload contracts for typed workspace routes, execution workspace selection, run lifecycle, project fragment routes, schedule/reaction REST APIs, operational fragment routes |
| `.internal-dev/specifications/web.md` | Page/fragment UX: agent Files tab, console composition, conceptual area navigation, run operations, project operational console, focused operational surfaces |
| `.internal-dev/specifications/simplypages.md` | SimplyPages composition policy, reusable fragment patterns, project console fragment composition, cross-domain fragment patterns |
| `.internal-dev/specifications/schema.md` | Specification schemas and ID prefixes |
| `.internal-dev/specifications/workflow.md` | Specification workflow gates, closeout requirements |
| `.internal-dev/specifications/decisions.md` | Active decisions: filesystem-first workspace identity (`DECISION-20260719-02`), task-run child resources (`DECISION-20260720-01`), operational metadata composition (`DECISION-20260720-02`), skills trust (`DECISION-20260720-03`), schedule/reaction idempotency (`DECISION-20260720-06`), system chat (`DECISION-20260727-04`), context/materialization (`DECISION-20260727-01`) |
| `.internal-dev/knowledge/dashboard-api-contract.md` | Dashboard HTMX partial endpoints, 30s refresh pattern, JS boundaries |
| `.internal-dev/knowledge/agent-scoped-assignment-lifecycle.md` | Route-agent ownership checks for lifecycle controls |
| `.internal-dev/knowledge/schedule-reaction-idempotency.md` | Revision-qualified operation identity, fencing, append-only attempts, simulation purity |
| `.internal-dev/knowledge/project-workspace-lease-runtime-pattern.md` | Project workspace lease lifecycle (superseded by descriptor model but provides context on project execution patterns) |
| `.internal-dev/knowledge/entity-selector-htmx-pattern.md` | Reusable HTMX entity selectors |
| `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java` | Project console fragment routes, agent queue rendering, schedule/reaction fragment routes, project tabs implementation |
| `src/main/java/io/mindspice/magenta2/api/web/ProjectController.java` | Project REST API: CRUD, membership, events |
| `src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java` | Agent-scoped inbox, assignments, schedules, reactions, chat |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java` | Project CRUD, membership, network gating, events, deletion guards |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/WorkAssignment.java` | Assignment record with projectId, agentId, workspace selection, status, lifecycle fields |
| `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java` | Assignment creation with project binding, lifecycle operations, project-scoped queries (`activeSummariesForProject`, `hasActiveAssignmentsForProject`) |

## Findings

### 1. The Unified Contract Foundation Is Already Latent

The `WorkAssignment` record already carries every field needed for cross-cutting views: `agentId`, `projectId`, `workspaceType`, `workspaceKey`, `workspaceIdentity`, `assignmentType`, `status`, `priority`, `jobId`, `conversationId` (via checkpoint). The repository already has `findActiveAssignmentsForProject()` and `countActiveAssignmentsForProject()`. The agent queue renderer (`renderAssignmentList`) is already a reusable helper that accepts a filtered list of `WorkAssignment` records.

**What exists:**
- `WorkAssignment.projectId` — nullable, set at submission from `AssignmentRequest.projectId` or `input.projectId`
- `AssignmentService.queueAssignments(agentId)` — agent-scoped queue query
- `AssignmentService.activeSummariesForProject(projectId)` — project-scoped active summary
- `AssignmentService.hasActiveAssignmentsForProject(projectId)` — gate check
- `renderAssignmentList(agentId, title, assignments)` — reusable HTMX fragment renderer
- Agent-scoped lifecycle: `cancel(agentId, assignmentId)`, `pause`, `resume`, `delete`, `forceInterrupt` — all verify `assignment.agentId()` matches route agent

**What's missing for unified contract:**
- No `queueAssignmentsByProject(projectId)` query (active + queued assignments filtered by project)
- No `historyAssignmentsByProject(projectId)` query (terminal assignments filtered by project)
- No project-scoped lifecycle authorization path (current lifecycle requires agentId match; project-scoped lifecycle requires project-membership verification + assignment.projectId match)
- No project-scoped transcript/diagnostics access pattern
- The `renderAssignmentList` helper always uses agent-scoped route paths (`/agents/_detail/{agentId}/...`); a project-scoped renderer needs new route paths

### 2. Project Console Architecture Is Well-Established

The existing six-tab project console at `/projects/_detail/{projectId}/{tab}` already demonstrates the exact composition pattern needed for new tabs:
- Stable `#project-tab-panel` target, independently HTMX-loaded per tab
- `projectTabNav()` builds tab buttons with active-state CSS
- Each tab is a separate `@GetMapping` returning an HTML fragment
- The agent console at `/agents/{agentId}` provides the template: independent hosts (`#agent-context-panel`, `#agent-queue-panel`, `#agent-skills-panel`, `#system-chat-panel`, `#agent-run-groups`)

### 3. Schedule and Reaction Systems Are Agent-Scoped Only

`ScheduleService` and `EventReactionService` operate under `/api/agents/{agentId}/schedules` and `/api/agents/{agentId}/event-reactions`. Schedule definitions and reaction definitions are keyed to agents. There is no project-scoped schedule or reaction persistence. To add a Project Events tab and Project Schedules tab, we must either:
- Extend the existing schedule/reaction tables with an optional `project_id` column and add project-scoped query paths
- Create parallel project-scoped schedule/reaction tables
- Keep schedules/reactions bound to a specific agent but allow project-level views that aggregate across all member agents

### 4. The "Tasks" Concept Does Not Exist Yet

The directive describes "Tasks" as user-defined work items — pinnable, assignable, schedulable, distinct from plans/workflows/jobs. No `TaskDefinition` table, service, or API exists. This is a new domain with substantial scope:
- Task CRUD with project binding
- Task-to-agent assignment (creates a `WorkAssignment` with a new `AssignmentType`)
- Pin semantics (priority/binding flag)
- Status tracking independent of run lifecycle
- Overlap with agent queue (task assignment creates a queue entry visible in both project and agent views)

The architecture already defines the pattern: `TaskSourceKind` enum has `TASK`, `WORKFLOW`, `JOB`. A new `AssignmentType.TASK_ASSIGNMENT` (distinct from `TASK_RUN`) would distinguish pinned/assigned tasks from task executions.

### 5. Project Files Tab Enhancement Has Architecture Constraints

The existing project Files tab is a read-only materialization browser. The directive asks for "Directory initialized to spec on project creation" and "permissions locked (read-only for members, based on materialization)."

**Architecture constraint:** Projects have NO filesystem root authority (`DECISION-20260719-02`). Project materializations are agent-scoped, not project-scoped. A "directory initialized to spec on project creation" must therefore mean: when a project is created, trigger materialization provisioning for each member agent with a spec-defined directory structure within each agent's materialization. This is consistent with the existing `AgentMaterializationService` lifecycle (`PROVISIONING` → `READY`).

Read-only enforcement already exists via `WorkspaceExplorerService` read-only overloads that accept a `MaterializationLocation`.

### 6. Project Chat Requires New Surface and API Contract

The directive says: "Uses the main chat agent with access to project context/space. Future API for project chat (we will add this later — plan for the API contract now, implement later)."

This implies a new conversation surface: `ChatSessionSurface.PROJECT`. The agent would receive prompt augmentation from the project materialization binding (already supported via `AgentContextService` immutable bindings). The API contract would mirror the existing `/api/chat/stream` pattern but scoped to project context.

### 7. Jobs Enhancement Scope Is Primarily Lifecycle Operations

Jobs already bind to projects (`JobService`, `JobRepository.countActiveRunsByProject()`). "Reassignable" means adding a `reassign(jobId, fromAgentId, toAgentId)` operation. The cross-cutting view (show in both project and agent dashboards) is handled by the unified contract filter pattern.

### 8. Risk: Schedule/Reaction Agent-Scoping Is Deep

The schedule/reaction persistence, service, REST API, fragment, and migration layers are all agent-scoped. Making them project-scoped is a cross-cutting change touching:
- Migration (new `project_id` column or new tables)
- `ScheduleDefinition` and `ReactionDefinition` records
- `ScheduleService` and `EventReactionService` query methods
- `AgentOrchestrationController` route additions
- `ScheduleReactionFragments` rendering
- `OrchestrationController` project tab hosting

The feature flags (`magenta.features.schedules-enabled`, `magenta.features.reactions-enabled`) provide a built-in rollout seam.

### 9. Risk: Project Chat Authorization Gap

The current open-alpha HTTP posture has no multi-user authentication. A project chat surface must at minimum verify project membership before allowing conversation access. This is consistent with existing patterns (`ProjectService.isMember()`), but the chat controller would need a new authorization check path.

### 10. Risk: Cross-Cutting Stale Response Pollution

The agent console specification (`ARCH-20260727-05`) already mandates: "Each host is independently loaded through HTMX and a response replaces only its own host." The project console must follow the same rule. New tabs must never replace `#project-tab-panel` as a broad stale-response target for lifecycle operations — a cancel on the Project Queue tab should OOB-refresh `#project-queue-panel` (new host) not replace the entire tab panel.

## Options

### Option A: Minimal Incremental — Extend Existing Patterns Without New Domain Concepts

**Phasing:**
1. **Unified Contract Layer** — Add `queueAssignmentsByProject()`, `historyAssignmentsByProject()`, project-scoped lifecycle authorization path to `AssignmentService`. Create reusable `renderAssignmentListForProject()` that uses project-scoped route paths.
2. **Project Queue Tab** — Mount `#project-queue-panel` in the project console using the unified contract queries. Reuse agent queue rendering pattern with project-scoped targets.
3. **Project Files Enhancement** — Extend materialization provisioning to accept a spec template. Trigger on project creation.
4. **Jobs Enhancement** — Add `reassign()` to `JobService`. Cross-cutting view handled by unified contract filter.
5. **Project Chat Tab** — Add `ChatSessionSurface.PROJECT`, project-contextual prompt assembly, membership-gated access. API contract documented but deferred.
6. **Project Schedules Tab** — Add optional `project_id` to schedule/reaction tables. Add project-scoped query paths. Mount schedule/reaction fragment hosts in project console.
7. **Project Tasks Tab** — New `TaskDefinition` domain, task CRUD, pin semantics, `AssignmentType.TASK_ASSIGNMENT`.

**Strengths:** Each phase builds directly on existing infrastructure. Minimal new domain concepts. Low risk of breaking existing agent console.

**Weaknesses:** Schedule/reaction project-scoping requires schema migration with backward-compatibility risk. Tasks are a net-new domain requiring substantial service/repository/API work.

### Option B: Full Mirror — Project Console Gets Every Agent Console Feature

Same as Option A but also brings Skills panel, System Chat (project-scoped), Run Groups, and Context panel into the project console.

**Strengths:** Complete operational parity.

**Weaknesses:** Massive scope. System Chat is explicitly agent-scoped by design (`DECISION-20260727-04`). Skills trust is per-agent. Run groups are already partially available via unified contract. Unclear product value for full mirroring vs. targeted additions.

### Option C: Tasks-First — Build the New Task Domain Before Queue/Schedule Work

Invert phasing: build the Task domain (definition, assignment, pinning, scheduling) first, then use it as the driver for unified contract testing, and only then extend schedules/reactions.

**Strengths:** Tasks are the only truly new domain concept; building them first proves the unified contract without modifying existing schedule/reaction infrastructure.

**Weaknesses:** Delays user-visible progress on queue/chat/files tabs. Schedule/reaction project-scoping is independent of tasks.

### Option D (Recommended Hybrid): Contract-First, Queue-First, Then Tasks, Then Automation

1. **Phase 1: Unified Contract & Project Queue** — Add project-scoped queries to `AssignmentService`/`OrchestrationRuntimeRepository`. Add project-scoped lifecycle authorization. Mount `#project-queue-panel` in project console reusing `renderAssignmentList` pattern. This delivers immediate value (Queue tab) while establishing the contract foundation for everything else.
2. **Phase 2: Project Files Enhancement** — Spec-template materialization provisioning on project create. Read-only enforcement hardening.  
3. **Phase 3: Jobs Reassignment** — `reassign()` operation, cross-cutting view validation.
4. **Phase 4: Project Tasks Domain** — `TaskDefinition`, task CRUD, pin/assign lifecycle, `AssignmentType.TASK_ASSIGNMENT`, task status tracking.
5. **Phase 5: Project Schedules & Events** — Extend schedule/reaction schema with `project_id`. Add project-scoped routes and fragment hosts.
6. **Phase 6: Project Chat** — API contract documented. `ChatSessionSurface.PROJECT` with project-context prompt assembly. Deferred implementation per directive.

## Trade-offs

| Decision Axis | Option A (Minimal) | Option D (Recommended) | Rationale |
|---|---|---|---|
| **Schedule/Reaction project-scoping depth** | Add `project_id` column to existing tables | Same | Backward-compatible: existing agent-scoped rows have NULL project_id. Project-scoped queries add WHERE project_id = ?. No existing data migration needed. |
| **Project-scoped lifecycle authorization** | Verify project membership + assignment.projectId match | Same | Mirrors agent-scoped pattern: `assignment.agentId()` → `assignment.projectId()` + `projectService.isMember()`. Fail-closed: non-members see 404. |
| **Task vs. Plan distinction** | Tasks are user-defined work items; Plans are AI-generated execution plans | Same | The architecture already distinguishes `TaskSourceKind.TASK` from plan/workflow/job. Tasks use `AssignmentType.TASK_ASSIGNMENT`; their execution would produce `TASK_RUN` assignments. This avoids conflating pinned task tracking with AI-generated plan execution. |
| **Project chat surface** | `ChatSessionSurface.PROJECT` with membership gate | Same | Consistent with existing surface taxonomy. No new transport. SSE streaming reuses existing infrastructure. |
| **Files "spec template"** | Template applied to each member agent's materialization on project create | Same | Only viable path given no project filesystem root. Each agent gets a spec-defined directory structure within their materialization. |
| **New fragment hosts vs. tab-panel replacement** | Each new tab gets its own stable host ID within `#project-tab-panel` | Same | Prevents stale response pollution. A queue cancellation OOB-refreshes `#project-queue-panel`, not the full tab panel. |
| **JavaScript transport** | None for new tabs; SSE only for chat | Same | HTMX-first policy. Project chat SSE reuses existing `chat-client.js` patterns. |
| **Schedule feature flag reuse** | New tabs gated by existing `magenta.features.schedules-enabled` / `reactions-enabled` | Same | Built-in rollout seam. Disabled state returns controlled "unavailable" fragment. |

## Open Questions

1. **Task-pinning semantics**: What does "pin" mean beyond priority elevation? Does pinning prevent reassignment, auto-resume on agent restart, or create a sticky queue position? The directive says "assign to agent, set priority, pin" — pin needs a concrete behavioral definition before implementation.

2. **Schedule/reaction project ownership model**: Should a project-scoped schedule be owned by the project (any member agent can edit) or by a specific agent (only that agent can manage it, but the firing is visible project-wide)? The current agent-scoped model suggests the latter, but the directive's "Set event triggers and responses per project" suggests project ownership. This has authorization implications.

3. **Project chat model selection**: Which model serves project chat? The directive says "Uses the main chat agent" — does this mean the project owner agent's model configuration, or a project-level model override (the `project.model` field already exists)?

4. **Job reassignment and running state**: Can a RUNNING job be reassigned, or only QUEUED/WAITING jobs? Reassigning a running job would require interrupting the current agent and restarting on the new agent. This needs an explicit lifecycle policy.

5. **Project queue priority ordering**: When viewing the project queue, how are assignments from different agents ordered? Global priority? Per-agent priority with interleaving? Chronological? The agent queue already has priority-based ordering; the project queue needs its own ordering rule.

6. **Materialization "spec template" format**: What defines the directory structure template — a JSON/YAML manifest, a SimplyPages component, or a hardcoded config per project? This affects the materialization provisioning contract.

7. **Task-to-agent assignment cardinality**: Can one task be assigned to multiple agents simultaneously, or is it one-at-a-time with reassignment? Multi-agent assignment creates fan-out complexity for status tracking and completion semantics.

8. **Event reaction scope boundaries**: If a reaction is project-scoped, can it trigger assignments on any member agent, or only the agent that owns the reaction definition? The current `ENQUEUE_ASSIGNMENT` action targets a specific agent.

## Recommended Next Step

**Execute Phase 1 (Unified Contract & Project Queue) as the foundation sprint.** This is the lowest-risk, highest-validity starting point because:

1. **All data already exists** — `WorkAssignment.projectId`, `AssignmentService.activeSummariesForProject()`, the reusable `renderAssignmentList()` helper, and the project console tab infrastructure are all in place.
2. **It proves the cross-cutting contract** — Once project-scoped queue queries work with the same rendering as agent queue, every other cross-cutting feature (tasks, jobs, schedules) follows the same filter pattern.
3. **No schema migration required** — The `project_id` column already exists in `work_assignments`. Only repository query methods and controller routes need to be added.
4. **Immediate user-visible value** — The Project Queue tab is the most impactful new surface, providing operational visibility into what's running under a project.
5. **Independent validation is straightforward** — Repository query tests, service lifecycle authorization tests, controller fragment route tests, bounded startup, and focused Playwright validation for the new tab.

**Phase 1 concrete scope:**
- `OrchestrationRuntimeRepository`: Add `findAssignmentsByProject(projectId)` (active + queued) and `findHistoryAssignmentsByProject(projectId)` (terminal)
- `AssignmentService`: Add `queueAssignmentsByProject(projectId)`, `historyAssignmentsByProject(projectId)`, and `cancelProjectAssignment(projectId, assignmentId)` (with membership verification)
- `OrchestrationController`: Add `GET /projects/_detail/{projectId}/queue` rendering to `#project-tab-panel`; add `POST /projects/_detail/{projectId}/queue/{assignmentId}/cancel` with membership gate
- Reuse `renderAssignmentList()` helper signature; parameterize route paths for agent vs. project context
- No new JavaScript, no schema changes, no new domain concepts

### Phase Dependency Graph

```text
Phase 1: Unified Contract & Project Queue
  ├── No dependencies (all data and patterns exist)
  └── Proves cross-cutting filter pattern used by all later phases

Phase 2: Project Files Enhancement
  ├── Depends on: Phase 1 (project-scoped service patterns established)
  └── Risks: materialization spec-template format decision needed

Phase 3: Jobs Reassignment
  ├── Depends on: Phase 1 (unified contract for cross-cutting views)
  └── Risks: reassignment-during-run policy decision needed

Phase 4: Project Tasks Domain
  ├── Depends on: Phase 1 (unified contract), Phase 3 (reassignment pattern)
  └── Risks: largest new domain surface; pin semantics need definition

Phase 5: Project Schedules & Events
  ├── Depends on: Phase 1 (project-scoped query pattern)
  ├── Risks: schema migration for schedule/reaction tables
  └── Seam: feature flags provide rollout control

Phase 6: Project Chat
  ├── Depends on: Phase 1 (project context binding), Phase 4 (task awareness)
  ├── API contract designed now; implementation deferred per directive
  └── Risks: membership authorization for chat access
```

### Validation Strategy Per Phase

| Phase | Repository Tests | Service Tests | Controller Tests | Startup | HTMX Behavior | Playwright |
|---|---|---|---|---|---|---|
| 1. Queue | Project-filtered queries, null-project handling, pagination | Lifecycle with membership gate, cross-cutting overlap (agent+project), authorization denial | Fragment routes return correct HTML, error status for non-members, OOB refresh isolation | Context loads with new query methods | Tab loading, cancel button OOB-refresh targets correct host, stale response isolation | Visual critique: queue table density, mobile stacking, chip consistency, empty state, cancel flow |
| 2. Files | Materialization template provisioning, generation conflicts | Spec-template application, read-only enforcement | Fragment routes for templated directories, preview/download with template structure | Template config wired | Directory expansion, preview modal, inline errors | Visual: file tree with spec structure, read-only indicator, template conflict states |
| 3. Jobs | Reassignment CAS, active-run blocking | Reassign lifecycle (QUEUED→new agent, RUNNING→interrupt+reassign), project membership for both agents | Reassign button fragment, error states, cross-dashboard refresh | No schema changes | Reassign flow, OOB refresh of both agent and project queue hosts | Visual: reassign control affordance, confirmation modal, post-reassign queue state |
| 4. Tasks | TaskDefinition CRUD, pin flag, project binding | Task assignment lifecycle (create task, assign to agent, pin, track status), overlap queries | Task list/edit forms, assignment selector, pin toggle, status chips | New table migration (migration 014+) | Task creation form, inline edit, assign selector, pin toggle, status refresh | Visual: task rows, pin indicator, assignment selector dropdown, status chip states |
| 5. Schedules/Events | project_id column migration, backward-compat NULL handling | Project-scoped schedule CRUD, firing queries, reaction dispatch with project scope | Schedule editor fragment, simulation preview, firing audit, feature-flag gating | Migration upgrade + fresh, feature flag toggles | Schedule form validation, cron editor, enable/disable toggle, simulation preview | Visual: schedule list density, form layout, simulation result table, feature-flag unavailable state |
| 6. Chat | N/A (deferred implementation) | Project context prompt assembly contract | API route contract (documented, not implemented) | N/A (deferred) | N/A (deferred) | N/A (deferred) |
