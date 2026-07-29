## Prompt

Cross-review all four specialist reports — Delivery/Migration/Risk Strategist, Unified Contract Architect, Project Context Steward, Operational UI Composer, and Automation Lifecycle Researcher — produced for the Project Dashboard and Unified Work Contracts Sprint. Identify areas of convergence, material conflicts, omissions, feasibility concerns, and trade-offs. Preserve the broad delivery, migration, and risk lens without merely defending the Strategist's own report. The output should inform a consolidated phased plan that incorporates the best from each perspective while resolving genuine disagreements.

## Source

All four reports plus the original directive and the full specification store (`.internal-dev/specifications/architecture.md`, `services.md`, `service-graph.md`, `api.md`, `web.md`, `simplypages.md`, `decisions.md`, `schema.md`), the knowledge store (20+ files on orchestration, workspace, schedule/reaction, assignment lifecycle, agent console patterns), and the live source code (`OrchestrationController.java` ~8,700 lines, `AssignmentService.java`, `ProjectService.java`, `JobService.java`, `WorkAssignment.java`, `ScheduleService.java`, `EventReactionService.java`, `WorkspaceExplorerFragments.java`).

## Findings

### 1. Convergence: Five Reports, One Foundation

All five reports independently converge on the same core architectural truth: **the unified contract already exists in latent form**. The `WorkAssignment` record carries `agentId` and `projectId`. The cross-cutting view is `WHERE agent_id = X` vs. `WHERE project_id = Y` against the same canonical table. No second store, shadow table, or derived projection is needed. This is the single most important finding — it means the sprint is about _surfacing_ existing data through new query paths and fragment hosts, not about _creating_ a new data layer.

All reports further converge on:
- **Phase 1 = Unified Contract + Project Queue** as the undisputed first step (zero schema changes, maximum value, proves the dual-key pattern)
- **Project Files tab** already has correct architecture (read-only materialization browser) and needs enhancement, not redesign
- **Project Chat** should be API-contract-planned now, implemented later (per the directive)
- **Process confinement remains fail-closed** — shell execution, skill executable authorization, and package ingestion are not enabled by this sprint
- **HTMX-first policy** continues; JavaScript only for SSE streaming in chat
- **SimplyPages reusable components** should be extracted for shared queue/task/job row rendering
- **No separate stores** — every cross-cutting view is a filtered projection of canonical tables

### 2. Material Conflict: Schedule/Reaction Project-Scoping Mechanism

This is the most significant architectural disagreement across the reports.

| Report | Approach | Mechanism |
|---|---|---|
| Unified Contract Architect | Schema extension | Add `project_id` column to `schedule_definitions` and `reaction_definitions` |
| Project Context Steward | Schema extension (Option D1) | Add `project_id` column; "single canonical table; single schedule can be viewed from agent or project surface" |
| Operational UI Composer | **Binding layer (Option B)** | New `project_event_bindings` table; "avoids duplicating schedule/reaction logic while allowing project-scoped views" |
| Automation Lifecycle Researcher | Schema extension (Option 1) | Add `project_id` column; "consistent with how assignments already dual-scope on agent_id + project_id" |
| Delivery/Migration/Risk Strategist | Schema extension | Add `project_id` column |

**Four reports say column. One says binding table.** The UI Composer's binding-layer argument is architecturally cleaner — it preserves agent-ownership purity and avoids "who owns this schedule?" ambiguity. But it introduces a join for every project-scoped query, requires a new table migration, and creates two different query patterns (direct column for assignments, join for schedules/reactions). The column approach is simpler, mirrors the existing `work_assignments` dual-scope pattern exactly, and keeps all cross-cutting queries as single-table WHERE clause variations. The column approach also inherits the existing schedule/reaction revision-qualified identity without needing to track which revision was bound — the `project_id` on the definition row stays with the definition through revisions.

**Recommendation: Column approach (4:1 consensus).** The simplicity and consistency win. But the binding-layer concern about ownership ambiguity is valid and should be addressed in documentation: a schedule with `project_id` non-null is a _project-scoped schedule_ that still has an `agent_id` for execution. The `agent_id` is the executor; the `project_id` is the scope. This is the same dual-key pattern as assignments. Document it clearly in the unified contracts spec.

### 3. Material Conflict: Task Entity Model

The reports diverge significantly on what a "Task" is and how to model it.

| Report | Task Model | Storage |
|---|---|---|
| Unified Contract Architect | New lightweight `task_items` table | `id, project_id, title, description, pinned, priority, status, assigned_agent_id, workspace_type/key/identity, created_at, updated_at` |
| Project Context Steward | Pinned plans; junction table `project_task_pins(projectId, planId, priority)` | Reuses `plan_definitions` with a pin overlay |
| Operational UI Composer | New `ProjectTask` entity | `title, description, priority, status, projectId, assignedAgentId, conversationId` |
| Automation Lifecycle Researcher | Revision-qualified pin table | `task_pins` with `definition_kind, definition_id, revision` — pins any definition kind (plan, workflow, job) |
| Delivery/Migration/Risk Strategist | New `task_items` table | Lightweight; distinct from plans and jobs |

**Three reports say new entity. Two say pin overlay on existing definitions.** This is a fundamental design decision with downstream consequences for the entire sprint.

The "pin overlay" approach (Context Steward, Lifecycle Researcher) has the advantage of not creating a third definition table alongside `plan_definitions` and `job_definitions`. A task is just a plan with a project pin. The pin carries priority, status, assignment. The plan carries the content. This avoids the three-table problem the Architect identified.

The "new entity" approach (Architect, UI Composer, Strategist) treats Tasks as a distinct concept from chat-authored plans. Tasks are user-created ("remind me to review this"), plans are AI-generated ("execute this multi-step workflow"). The shapes are different: tasks have title + description + pin + priority; plans have steps + output declarations + JSON snapshots. Conflating them creates nullable columns and awkward validation.

**The Lifecycle Researcher offers a synthesis**: a `task_pins` table that's revision-qualified and can reference any definition kind. This means a task _definition_ can be a plan, a workflow, or a job — the pin is what makes it project-visible, prioritized, and assignable. This elegantly resolves the "is a task a plan or a new thing?" question: a task is a _pinned definition_ of any kind. The pin table carries the task-specific metadata (project, priority, status, assigned agent). The definition table carries the content.

**Recommendation: Lifecycle Researcher's synthesis — a revision-qualified `task_pins` table.** This gives us:
- Zero new definition tables (reuse `plan_definitions`, `workflow_definitions`, `job_definitions`)
- Tasks can be lightweight (a plan with just a title) or complex (a job with work items)
- Pinning is explicit and revision-qualified (pins snapshot the definition at pin time)
- Unpinning removes the project visibility without deleting the definition
- The pin carries task-specific metadata (priority, status, assignment) separate from definition content
- Multi-project pinning is possible (same definition pinned to multiple projects with different priority/status per project)

The schema:
```sql
CREATE TABLE task_pins (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES projects(id),
    definition_kind TEXT NOT NULL,  -- 'PLAN', 'WORKFLOW', 'JOB'
    definition_id TEXT NOT NULL,
    definition_revision INTEGER NOT NULL,
    title_override TEXT,           -- optional display title different from definition
    pinned_by_agent_id TEXT,
    priority INTEGER DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED
    assigned_agent_id TEXT,
    pinned_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(project_id, definition_kind, definition_id)
);
```

When a pinned task is assigned to an agent for execution, the system creates a `WorkAssignment` (with `assignmentType` derived from `definition_kind`) and `project_id` populated from the pin. This assignment then appears in both the project queue and the agent queue through the dual-key filter — no new mechanism needed.

### 4. Alignment: Project Console Host Architecture

All reports agree the project console needs to evolve toward the agent console's independent-host pattern, but they differ on timing:

| Report | Approach |
|---|---|
| UI Composer | Go directly to independent hosts per tab (like agent console) |
| Context Steward | Evolutionary — start with existing `#project-tab-panel`, add sub-hosts inside tabs, refactor to full independent hosts later |
| Strategist | Same evolutionary approach |
| Architect | Not explicitly addressed |
| Lifecycle Researcher | Not addressed |

**The UI Composer's independent-host vision is the correct end state.** The agent console already proves the pattern: six independently loaded hosts that never replace each other, OOB refreshes scoped to single hosts, stale response isolation. The project console should eventually match.

**But the Context Steward/Strategist evolutionary approach is the correct _path_.** Refactoring the existing six-tab shell to independent hosts before adding new tabs would be a large change with no user-visible benefit. Better to add new tabs with their own sub-hosts inside `#project-tab-panel`, prove the pattern works, then refactor the existing tabs in a later phase. This is consistent with the sprint's phasing guidance.

**Resolution: Adopt the evolutionary path with a forward-looking commitment.** New tabs (Queue, Tasks, Events, Schedules) use sub-host targets within `#project-tab-panel`. The sub-host naming convention follows the agent console pattern: `#project-queue-host`, `#project-tasks-host`, etc. Existing tabs (Dashboard, Members, Files, Jobs, Outputs, Settings) keep their current behavior. A later phase (post-sprint) refactors existing tabs to independent hosts.

The one exception: **the project Queue tab should use the full independent-host pattern from day one**, since it's the first new tab and the pattern is already proven in the agent console. This sets the precedent for subsequent tabs.

### 5. Omission: OrchestrationController Extraction

The **Operational UI Composer** is the only report that explicitly flags the monolithic `OrchestrationController` (~8,700 lines) as a delivery risk. Adding 4+ new tabs to this file would make it unmaintainable. The Composer recommends extracting a `ConsoleFragmentRenderer` utility and creating a `ProjectConsoleFragments` class.

**This is a critical omission from the other reports.** The Architect, Context Steward, and Lifecycle Researcher all describe new controller endpoints without addressing where they live. The Strategist mentions "new `ProjectConsoleController`" in passing but doesn't treat it as a blocking prerequisite.

**Recommendation: Phase 0 must include extraction of shared rendering utilities.** Before any new tab is built, extract:
1. `ConsoleFragmentRenderer` — stateless utility with `statusBadgeHtml()`, `renderAssignmentList()`, `entitySelector()`, `counterCard()`, `loadingPlaceholder()`, `errorFragment()`
2. `ProjectConsoleFragments` — new class for project-scoped tab rendering, delegating to `ConsoleFragmentRenderer`
3. Keep `OrchestrationController` for `/agents` routes and the project chooser/page shell

This extraction is small, mechanical, and unblocks all subsequent work. It should be the first commit after the unified contract data model pass.

### 6. Omission: Schedule/Reaction Feature Flags

Only the **Delivery/Migration/Risk Strategist** notes that `magenta.features.schedules-enabled` and `magenta.features.reactions-enabled` default to `false`. This means:
- The Schedules and Events tabs will show "unavailable" states unless the flags are enabled
- Any development or testing of these tabs requires explicit flag activation
- Production deployments that haven't enabled these flags will see placeholder content

The other reports describe full schedule/reaction UIs as if the flags don't exist. The Context Steward's Phase 4 says "Add the configuration UI for project-scoped event reactions and schedules" without mentioning the feature gate.

**Recommendation: The sprint plan must explicitly address feature flags.** The Schedules and Events tabs should:
1. Check the feature flag at the controller level
2. Return a controlled "unavailable" fragment when disabled (matching the existing pattern in `AgentOrchestrationController`)
3. The fragment should clearly indicate the feature is disabled and how to enable it
4. Playwright validation must include both enabled and disabled states

### 7. Omission: Job Model Consolidation Scope

The **Unified Contract Architect** identifies the two-job-model problem (`job_definitions` + `orchestration_jobs`) and recommends consolidation. The **Lifecycle Researcher** acknowledges both models but doesn't push for consolidation — instead extending both. The other reports don't address it.

This is a genuine scope question. Consolidating two job models into one is:
- **High value**: eliminates confusion, simplifies queries, single code path for job operations
- **High risk**: touches orchestration flows, existing tests, and production job data
- **Not strictly required** for the sprint: the project Jobs tab can work with both models through a unified query

**Recommendation: Defer job model consolidation to a post-sprint phase.** The sprint's project Jobs tab can query both `job_definitions` and `orchestration_jobs` through a unified service method (`JobService.listForProject(projectId)`) that internally unions both queries. The rendered rows are the same shape regardless of which table they come from. Consolidation is a cleanup task, not a sprint deliverable. Document the intent in `deferred-features.md`.

### 8. Omission: Reassignment Mechanics During RUNNING State

The **Automation Lifecycle Researcher** provides the only thorough analysis of reassignment mechanics, including the critical edge case: what happens when a RUNNING assignment is reassigned? Their Option A (revoke-and-clone) with `REASSIGNED` terminal status and `REASSIGNED_FROM` lineage is the most complete design.

The other reports mention reassignment as a feature without addressing this edge case. The Context Steward's Open Question 8 asks "Can a running job be reassigned?" but doesn't propose an answer. The Strategist's Open Question 4 asks the same.

**The Lifecycle Researcher's design should be adopted in full:**
- New `OrchestrationStatus.REASSIGNED` terminal status
- New `RunLineageMode.REASSIGNED_FROM` lineage link
- Old assignment: force-interrupt → `REASSIGNED`
- New assignment: fresh run with `REASSIGNED_FROM` lineage
- New agent's context binding captured at reassignment time (immutable, follows `DECISION-20260727-01`)
- This reuses existing primitives (force-interrupt, lineage, context capture) rather than inventing new ones

### 9. Trade-off: Shared Renderers vs. Duplicated Renderers

The **Operational UI Composer** proposes shared Java renderers with parameterized HTMX targets. A `QueueRowComponent` that accepts `hostId` as a parameter and renders the same row shape with the correct OOB target. This is elegant but introduces a new risk: a bug in the shared renderer affects both agent and project consoles simultaneously.

**The Strategist's approach is more conservative**: separate render paths that happen to produce the same HTML shape. The agent queue continues to use its existing rendering. The project queue gets its own rendering method that produces the same markup but targets `#project-queue-panel` instead of `#agent-queue-panel`. This is slightly more code but zero risk of cross-contamination.

**Recommendation: Start with separate render paths, extract shared components once proven.** The first implementation of the project Queue tab should duplicate the agent queue rendering pattern (not literally copy-paste, but produce the same markup with different targets). Once both surfaces are stable and validated, extract the common parts into a shared SimplyPages component. This follows the project's own guidance: "Prefer reusable components and modules over one-off page-level markup" — but only after the shape is proven correct in two independent contexts.

### 10. Feasibility: The 10+ Tab Visual Layout

No report fully addresses the visual design challenge of fitting 10+ tabs into the project console. The current six tabs use a horizontal tab bar. Adding Queue, Chat, Tasks, Events, and Schedules brings the total to 11 tabs. A horizontal tab bar with 11 items will overflow on most screens.

The **UI Composer** hints at this with the independent-host architecture (each tab is independently loaded, so not all need to be visible at once). But nobody proposes a concrete navigation solution.

**Options not explored by any report:**
- Dropdown overflow for secondary tabs
- Grouped tabs (Operations: Queue/Jobs/Tasks, Automation: Schedules/Events, Context: Files/Chat)
- Vertical sidebar navigation (matches `/agents` page pattern)
- Icon + label compact tabs

**Recommendation: Address this in the Phase 0 design pass before building any tabs.** The simplest solution that matches existing patterns: a horizontal tab bar with the most-used tabs (Dashboard, Queue, Tasks, Files, Chat) and a "More" dropdown containing the rest (Members, Jobs, Outputs, Events, Schedules, Settings). This is a UI-only change to `projectTabNav()`.

### 11. Unresolved: Task Pinning Semantics

Every report identifies task pinning as a requirement. None fully defines what pinning _means_:
- Does it prevent reassignment?
- Does it create a sticky queue position?
- Does it survive project deletion?
- Does it auto-resume on agent restart?
- Does it imply the task's execution context is bound to the project materialization?

The **Lifecycle Researcher** comes closest with: "A pinned task is a DEFINITION, not an execution" and "The pin is on the definition, not the assignment." But this still doesn't answer the behavioral questions.

**Recommendation: Pin is a visibility and priority claim, nothing more (for this sprint).** Pin = "this task appears in the project Tasks tab with elevated priority." It does not prevent reassignment, auto-resume, or change execution semantics. These richer behaviors can be added in later sprints when the use cases are proven. This keeps the implementation simple and avoids over-engineering an unvalidated concept.

### 12. Cross-Cutting: Validation Strategy Gaps

The **Strategist** provides the most detailed validation strategy, with a per-phase matrix covering repository tests, service tests, controller tests, startup, HTMX behavior, and Playwright. The other reports mention validation in passing but don't structure it.

However, all reports miss one critical validation dimension: **cross-surface consistency**. How do we validate that an assignment cancelled from the project queue disappears from the agent queue? That a task pinned to a project appears in both the project Tasks tab and the assigned agent's queue? That a schedule firing produces an assignment visible in both surfaces?

**Recommendation: Add cross-surface integration tests to the validation matrix.** These are Playwright-level tests that:
1. Create a task in the project Tasks tab
2. Verify it appears in the agent Queue tab
3. Cancel it from the project Queue tab
4. Verify it disappears from the agent Queue tab
5. Reassign a job from agent A to agent B
6. Verify the assignment moves in both the project and agent queues

These tests validate the core architectural claim (dual-key filter projections) that the entire sprint depends on.

### 13. Risk: Schedule/Reaction Capability Gap

The **Lifecycle Researcher** correctly notes that schedules/reactions are fully built and tested at the service layer, and the sprint needs "primarily UI and project-scoping." But there's a subtle gap: the schedule/reaction services have no `findByProject()` query methods. Adding them requires:

For schedules: new repository method, new service method, new controller endpoint, new fragment host. This is straightforward but touches four layers.

For reactions: same. But reactions also need a way to scope their event matching to project events. Currently, reactions fire on events from any agent. For project-scoped reactions, they should only fire on events from project member agents (or events explicitly tagged with the project). This requires either:
- Adding `project_id` to the `OrchestrationEvent` model and filtering in the reaction evaluator
- Having the reaction evaluator check the event's source agent against project membership

Neither report addresses this event scoping question.

**Recommendation: Add `project_id` to `orchestration_events` and filter reactions by matching `project_id`.** This is the simplest extension and mirrors the dual-scope pattern. A reaction with `project_id = X` only fires for events with `project_id = X`. Events without `project_id` (agent-scoped events) are invisible to project-scoped reactions. This is clean, consistent, and requires only one column addition.

## Options

### Consolidated Phase Plan (Synthesized from All Reports)

**Phase 0 — Foundation (prerequisite for all other phases):**
- Extract `ConsoleFragmentRenderer` from `OrchestrationController` (UI Composer recommendation)
- Add `AssignmentQueryParams` record and unified `findAssignments()` repository method (Architect recommendation)
- Add `project_id` column to `schedule_definitions`, `reaction_definitions`, and `orchestration_events` via ordered migration 014 (4:1 consensus)
- Add `task_pins` table via ordered migration 015 (Lifecycle Researcher synthesis)
- Add `REASSIGNED` to `OrchestrationStatus` and `REASSIGNED_FROM` to `RunLineageMode` (Lifecycle Researcher design)
- Register `ChatSessionSurface.PROJECT` enum value (Context Steward + Architect agreement)
- Index `project_id` on all relevant tables (Lifecycle Researcher recommendation)
- Formalize unified contract in `.internal-dev/specifications/unified-contracts.md` (Context Steward recommendation)

**Phase 1 — Project Queue Tab (highest value, lowest risk, proves the dual-key pattern):**
- Implement `queueAssignmentsByProject(projectId)` in `AssignmentService`
- Implement `GET /projects/_detail/{projectId}/queue` fragment endpoint in `ProjectConsoleFragments`
- Add project-scoped lifecycle controls (cancel, pause, resume) with membership + ownership verification
- Render queue rows reusing agent queue pattern with project-scoped OOB targets (`#project-queue-host`)
- Transcript access via project-scoped assignment-conversation link query
- **First cross-surface validation:** cancel in project queue → verify agent queue updates

**Phase 2 — Project Files Enhancement:**
- Spec-template materialization provisioning (project carries directory spec; applied per agent at materialization time)
- Sub-host refinement for independent materialization list/inspector loads
- Read-only enforcement hardening (already correct; add explicit permission-denied messaging)

**Phase 3 — Project Tasks Tab (using task_pins synthesis):**
- `TaskService` over `task_pins` + existing `plan_definitions`
- Task CRUD: create (lightweight plan + pin), edit, delete, assign, update priority/status
- Assignment creation: pin → `WorkAssignment` with `project_id` populated
- Cross-surface: pinned tasks appear in both project Tasks tab and agent Queue when assigned

**Phase 4 — Jobs Enhancement:**
- Job reassignment lifecycle (revoke-and-clone with `REASSIGNED` status)
- Run drill-down with status, elapsed, attempt counts, transcript
- Unified `JobService.listForProject(projectId)` querying both `job_definitions` and `orchestration_jobs`
- Cross-surface: job assignments appear in both project Queue and agent Queue

**Phase 5 — Project Schedules & Events Tabs:**
- Feature-gated behind `magenta.features.schedules-enabled` and `magenta.features.reactions-enabled`
- Project-scoped schedule/reaction CRUD using `project_id` column
- Visual cron editor (six dropdowns + timezone selector + human-readable preview)
- Event reaction configuration form (event type, filter key/value, assignment template)
- Simulation previews (reuse existing `ScheduleService`/`EventReactionService` simulation)
- Firing/dispatch audit expansion
- Controlled "unavailable" fragment when feature flags are disabled

**Phase 6 — Project Chat Tab (API contract + placeholder):**
- Chat host shell with "Project chat is coming soon" placeholder
- API contract documented: `POST /api/projects/{projectId}/chat/stream`, `GET /history`, `GET /conversations`
- `ChatSessionSurface.PROJECT` registered in conversation routing and session filter
- Full implementation deferred per directive

**Phase 7 — Polish & Cross-Cutting Validation:**
- Cross-surface Playwright integration tests
- Responsive layout audit (desktop + mobile for all new tabs)
- Visual critique pass (spacing, density, chip consistency, empty states)
- Feature flag toggling validation
- Project tab navigation overflow solution

## Trade-offs

### T1: Column vs. Binding Table for Schedule/Reaction Project-Scoping
**Winner: Column (4:1 consensus).** The column approach is simpler, mirrors `work_assignments` exactly, and keeps all queries as single-table WHERE clauses. The binding table adds a join and creates two query patterns. However, the binding table's ownership-clarity argument is valid — address it in documentation rather than architecture.

### T2: New Task Entity vs. Pin Overlay on Existing Definitions
**Winner: Pin overlay (Lifecycle Researcher synthesis).** A `task_pins` table that references any definition kind avoids the three-definition-table problem while supporting lightweight tasks (thin plans) and complex tasks (jobs with work items). It also enables multi-project pinning and revision-qualified snapshots. The "new `task_items` table" approach (3 reports) is simpler but creates a third definition table with ambiguous relationship to plans and jobs.

### T3: Independent Hosts Now vs. Evolutionary Path
**Winner: Evolutionary with Queue exception.** Start with existing `#project-tab-panel` shell. New tabs use sub-host targets within it. The Queue tab uses full independent-host pattern as precedent. Refactor existing tabs to independent hosts post-sprint. This ships value incrementally without a large refactor that has no user-visible benefit.

### T4: Job Model Consolidation Now vs. Later
**Winner: Later (post-sprint).** Consolidation is high-risk and not strictly required. The sprint's project Jobs tab can query both models through a unified service method. Document consolidation intent in `deferred-features.md`.

### T5: Shared vs. Duplicated Fragment Renderers
**Winner: Duplicate first, extract later.** Start with separate render paths for agent and project consoles. Once both are stable and validated, extract common parts into SimplyPages components. This follows the project's reuse policy without introducing shared-renderer bugs.

### T6: Pin Semantics — Rich vs. Minimal
**Winner: Minimal for this sprint.** Pin = visibility + priority claim. No reassignment prevention, auto-resume, or execution-semantic changes. Richer pin behaviors can be added in later sprints when use cases are proven.

## Open Questions

1. **Project tab navigation overflow:** How do 11 tabs fit in the project console? Horizontal bar with overflow dropdown? Grouped tabs? Vertical sidebar? This needs a design decision in Phase 0 before any tab is built.

2. **Project chat bounded read profile:** What exactly can the project chat profile read? Members? Materialization summaries? Assignment status? Should it follow the system-chat deny-by-default pattern? The API contract phase needs this answer even though implementation is deferred.

3. **Event scoping for project reactions:** Should project-scoped reactions only fire on events from project member agents, or on events explicitly tagged with the project? The recommendation is `project_id` on events + reaction filter, but this is a design decision not yet validated against the existing event model.

4. **Reassignment mid-execution run handling:** The Lifecycle Researcher's revoke-and-clone design is thorough, but should the old run's artifacts be preserved and accessible from the new run? Should partial progress be transferred? This has operational implications for long-running jobs.

5. **Schedule/reaction deletion on project delete:** Should project-scoped schedules/reactions be soft-deleted with the project? The recommendation is yes (following existing soft-delete patterns), but this needs explicit product confirmation and implementation in `ProjectService.requireProjectDeletionAllowed()`.

6. **Priority mutation authorization:** Who can change an assignment's priority? The agent owner? Project members? Both? The current open-alpha posture has no auth, but the API contract should document the intended authorization model.

7. **Multi-project task pinning:** Can the same task definition be pinned to multiple projects with different priority/status per project? The `task_pins` UNIQUE constraint allows this. Is this desired behavior or should it be one-project-per-task?

8. **Cross-surface refresh consistency:** When a mutation occurs on the project surface, how does the agent surface learn about it? The current pattern is OOB refresh of same-domain hosts. Cross-surface refresh (project mutation → agent panel update) requires either polling or a new event/SSE mechanism. This is not addressed by any report.

## Recommended Next Step

1. **Resolve the two material conflicts before Phase 0 implementation begins:**
   - **Schedule/reaction scoping**: Confirm column approach (4:1 consensus) over binding table
   - **Task entity model**: Confirm pin overlay (`task_pins` referencing any definition kind) over new `task_items` table

2. **Execute Phase 0 (Foundation) with the following concrete deliverables:**
   - Extract `ConsoleFragmentRenderer` from `OrchestrationController`
   - Create `ProjectConsoleFragments` skeleton
   - Write migration 014 (add `project_id` to schedules, reactions, events)
   - Write migration 015 (create `task_pins` table)
   - Add `REASSIGNED` status and `REASSIGNED_FROM` lineage
   - Add `AssignmentQueryParams` and unified `findAssignments()` to repository
   - Register `ChatSessionSurface.PROJECT`
   - Create `.internal-dev/specifications/unified-contracts.md`
   - Index `project_id` columns
   - Design project tab navigation overflow solution

3. **Begin Phase 1 (Project Queue Tab) only after Phase 0 is fully validated** — this is the proof point for the entire unified contract architecture and the first cross-surface Playwright test.

4. **Produce the project chat API contract as a standalone specification artifact** during Phase 0 — this satisfies the directive's "plan for the API contract now, implement later" requirement and unblocks frontend placeholder work.

5. **Adopt the Lifecycle Researcher's reassignment design in full** — it's the most complete analysis of the hardest lifecycle operation in the sprint, and it reuses existing primitives rather than inventing new ones.
