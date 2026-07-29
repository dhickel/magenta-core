## Prompt

Cross-review all four required brainstorming reports — Unified Contract Architect, Project Context Steward, Operational UI Composer, and Delivery Risk Strategist — through the Automation and Lifecycle Researcher lens. Compare useful ideas, conflicts, omissions, feasibility, and trade-offs. Do not merely defend the first report; synthesize a coherent cross-lens view that surfaces the strongest shared conclusions, resolves tensions, fills gaps, and sharpens the recommended path forward.

## Source

All four reports as provided, plus the same authoritative specification and codebase sources inspected for the original Automation and Lifecycle Researcher exploration:
- `.internal-dev/specifications/architecture.md`, `services.md`, `api.md`, `web.md`, `service-graph.md`, `decisions.md`
- `.internal-dev/knowledge/schedule-reaction-idempotency.md`, `run-lifecycle-idempotency-and-fencing.md`, `agent-queue-transcript-cancel-lifecycle.md`, `agent-scoped-assignment-lifecycle.md`, `orchestration-runtime-phase-02.md`, `project-workspace-lease-runtime-pattern.md`, `entity-selector-htmx-pattern.md`, `task-workflow-schema-and-run-snapshots.md`
- Source code: `WorkAssignment.java`, `AssignmentService.java`, `JobService.java`, `ScheduleService.java`, `EventReactionService.java`, `ProjectService.java`, `OrchestrationRuntimeRepository.java`, `OrchestrationController.java`, `ProjectController.java`, `AgentOrchestrationController.java`

## Findings

### 1. Strong Consensus: The Canonical Row Already Exists — No New Stores Needed

All four reports independently converge on the same foundational insight: `work_assignments` is already the single source of truth, carrying both `agentId` and `projectId`. Every cross-cutting view is a filtered projection of this table. No parallel stores, shadow tables, or derived projections are needed. This is the single most important architectural conclusion of the entire brainstorming exercise — it means the sprint can deliver substantial new surface area with minimal persistence risk.

**Supporting evidence across reports:**
- Unified Contract Architect: "The canonical row is already singular. No second store, shadow table, or derived projection is needed."
- Project Context Steward: "The dual-index pattern (agent-scoped + project-scoped queries returning the same shape) is the direct solution to the unified contract requirement — it exists and needs extension, not reinvention."
- Operational UI Composer: "`WorkAssignment` already carries the fields needed for dual query surfaces."
- Delivery Risk Strategist: "The Unified Contract Foundation Is Already Latent."
- Automation and Lifecycle Researcher: "Project visibility is universally a FILTERED PROJECTION of that same truth."

**This is a settled conclusion.** No dissenting view emerged. The implementation contract should codify this in a specification document (`unified-contracts.md` as proposed by Unified Contract Architect) with the entity projection matrix that Project Context Steward produced.

### 2. Strong Consensus: Phase 1 = Unified Contract + Project Queue Tab

All four reports recommend starting with the project Queue tab as Phase 1 because:
- All data already exists (the `project_id` column on `work_assignments`)
- It proves the cross-cutting filter pattern with zero schema migration
- It delivers immediate user-visible value
- It establishes the query and rendering patterns that all other tabs reuse

**Phase 1 scope convergence:**
- Unified Contract Architect: Phase 1 is "Project Queue Tab" with unified query builder
- Project Context Steward: Phase 2 is "Project Queue Tab" after backend foundation
- Operational UI Composer: Phase 1 is "Queue tab" validating unified contract
- Delivery Risk Strategist: Phase 1 is "Unified Contract & Project Queue"
- Automation and Lifecycle Researcher: "Build tabs in dependency order... Project Queue (dual-key projection on proven query paths)"

**This is a settled conclusion.** The implementation plan should begin with a single Phase 1 deliverable: project-scoped assignment queries + `#project-queue-panel` HTMX host.

### 3. Strong Consensus: Project Chat Is API-Contract-Now, Implementation-Later

All reports respect the directive's explicit scope boundary for project chat. Everyone agrees on:
- Registering `ChatSessionSurface.PROJECT` now
- Designing the API contract shape (`POST /api/projects/{projectId}/chat/stream`)
- Rendering a placeholder host ("coming soon")
- Deferring full implementation to a later phase

**Nuanced differences:**
- Operational UI Composer advocates a functional skeleton host with the planned API shape displayed — useful for visual validation of the tab layout
- Project Context Steward notes the bounded read profile needs design now even if implementation is deferred: what can project chat see? Members, materialization summaries, assignment status, project events — but no filesystem write, process execution, or skill activation
- Delivery Risk Strategist places chat last (Phase 6) after tasks and automation are built — sensible because chat will want to reference tasks, schedules, and queue status in its context

**Recommendation:** Register `ChatSessionSurface.PROJECT` in Phase 0 (contract definition). Design the bounded read profile and API contract in Phase 0. Mount the placeholder host in Phase 2 (after queue is proven). Full implementation deferred per directive, but the placeholder host ensures the tab exists and the visual layout accounts for it from the start.

### 4. Strong Consensus: Schedules and Reactions Need Project-Scoping

All reports agree the existing agent-scoped schedule and reaction infrastructure needs a project dimension. Disagreement exists on the mechanism.

**Unified Contract Architect** and **Delivery Risk Strategist**: Add nullable `project_id` column directly to `schedule_definitions` and `reaction_definitions`. Simple, backward-compatible (existing rows have NULL), single query path.

**Operational UI Composer**: Add a `project_event_bindings` junction table instead. "Clean separation — schedules/reactions remain agent-scoped. Projects bind to them." Avoids schema changes on hardened tables.

**Automation and Lifecycle Researcher**: Add `project_id` column directly, consistent with how `work_assignments` already dual-scopes.

**Project Context Steward**: Option D1 (add `project_id` column) recommended over D2 (separate tables).

**Resolution:** The column approach (3 reports) has stronger consensus than the binding-layer approach (1 report). The column approach is also simpler to query, index, and reason about. The binding-layer concern about "muddy ownership" is valid but the same concern applies to `work_assignments.project_id` and no confusion has resulted there. **Recommend: add nullable `project_id` column.** If project-ownership semantics later require stronger separation, a migration from column to junction table is always possible. The reverse is harder.

### 5. Strong Consensus: Tasks Need a New Entity — But What Shape?

All four reports agree that lightweight user-created work items ("Tasks") don't exist yet and need a new table. But naming, shape, and relationship to existing plans differ across reports.

| Report | Proposed Name | Key Fields | Relationship to Plans |
|--------|--------------|------------|----------------------|
| Unified Contract Architect | `task_items` | id, project_id, title, description, pinned, priority, status, assigned_agent_id, workspace_type/key/identity | Separate from `plan_definitions`; creates `WorkAssignment` with `assignmentType=TASK` |
| Project Context Steward | `project_task_pins` (junction) + use `plan_definitions` | projectId, planId, priority | Pins existing plans to projects; no new definition table |
| Operational UI Composer | `ProjectTask` | title, description, priority, status, projectId, assignedAgentId, conversationId | New entity distinct from chat plans; "avoids coupling the chat plan model to project task management" |
| Automation and Lifecycle Researcher | `task_pins` (junction for pinning existing plans) + separate `task_items` for lightweight tasks | pin table: definition_kind, definition_id, revision, pinned_at. Task table: title, description, project_id, pinned, priority, assigned_agent_id | Tasks as standalone definitions distinct from plans; pins as revision-qualified bindings |

**Critical distinction missed by most reports:** There are TWO concepts here that the directive conflates:
1. **Task definitions** — lightweight user-created work items (title, description, priority) that can be assigned, pinned, and scheduled. These are not AI-authored plans.
2. **Task pinning** — the act of binding an existing definition (plan, workflow, or job) to a project so it appears in the project's task list.

A single `task_items` table handles concept 1, but concept 2 requires a junction mechanism that works across definition kinds. The `plan_definitions` table already holds AI-authored plans — users may want to pin these to projects without duplicating them into a separate task table.

**Recommendation:** Two new tables:
- `task_items` — for lightweight user-created work items (the new domain concept). Fields: `id, project_id, title, description, priority, status, assigned_agent_id, workspace_type, workspace_key, workspace_identity, created_at, updated_at`. Creates `WorkAssignment` with `assignmentType=TASK` when executed.
- `task_pins` — for binding any definition kind to a project. Fields: `id, project_id, definition_kind (TASK_DEF | PLAN | WORKFLOW | JOB), definition_id, revision, pinned_by_agent_id, pinned_at`. Follows the revision-qualified identity pattern from schedules/reactions — a pin captures a specific definition revision.

The project Tasks tab shows both: directly created `task_items` AND pinned definitions from other domains. Both create assignments that appear in the unified queue.

### 6. Conflict: Job Model Consolidation — Now or Later?

**Unified Contract Architect** advocates consolidating `orchestration_jobs`/`orchestration_job_items` into `job_definitions`/`job_runs` as part of the sprint (Phase 3 of their plan). Notes that having two overlapping job models is "ongoing confusion risk."

**Delivery Risk Strategist** treats Jobs enhancement as a simpler addition ("Reassignable means adding a reassign operation") without consolidation. Places Jobs as Phase 3 but only adds lifecycle operations, not model unification.

**Automation and Lifecycle Researcher** notes that both models produce assignments through the same `AssignmentService` path, so the unified contract covers both without consolidation. Consolidation is desirable but risky and not required for the sprint's deliverables.

**Operational UI Composer** and **Project Context Steward** are silent on this.

**Resolution:** Defer job model consolidation. The sprint's Jobs deliverable is: (a) job reassignment, (b) project-queue visibility for job-spawned assignments, (c) run drill-down in the project Jobs tab. None of these require merging `orchestration_jobs` into `job_definitions`. The unified contract filter pattern works identically regardless of which table produced the assignment. Consolidation is a legitimate code-quality improvement but carries migration risk disproportionate to the sprint's goals. Document the consolidation as a deferred task with a clear rationale.

### 7. Gap: Reassignment Mechanics Are Under-Explored by Most Reports

This is where the Automation and Lifecycle Researcher lens contributes the most unique value. Only the Automation report deeply explores HOW reassignment works with the existing lease, fencing, lineage, and context-binding infrastructure.

**What other reports say about reassignment:**
- Unified Contract Architect: "Job reassignment mechanics... Does reassignment change `job_definitions.assigned_agent_id`... or does it cancel the current assignment and create a new one?" — treated as an open question.
- Project Context Steward: "Can a running job be reassigned to a different agent mid-execution?" — treated as an open question.
- Operational UI Composer: "Cancelling the current assignment and creating a new one for the target agent seems safest, but this loses partial progress." — brief treatment.
- Delivery Risk Strategist: "Reassignable means adding a reassign(jobId, fromAgentId, toAgentId) operation." — described as simple.

**What the Automation report contributes:**
- Revoke-and-clone as the specific mechanism, reusing existing CANCEL_REQUESTED/force-interrupt lifecycle
- REASSIGNED_FROM lineage tracking aligning with existing RETRY_OF/REPLAY_OF pattern
- Context re-capture for the new agent at reassignment time (immutable binding)
- Workspace implications: new agent runs under its own workspace
- State-dependent behavior: QUEUED assignments reassign differently than RUNNING assignments

**Recommendation:** The reassignment design must be settled before the Jobs enhancement phase begins. It cannot be treated as "just add a reassign() method." The Automation report's revoke-and-clone proposal should be the starting point for the implementation contract, with explicit lifecycle definitions for each state the assignment might be in at reassignment time.

### 8. Gap: How Cancellation from the Project Surface Interacts with Existing Lifecycle

The existing cancel lifecycle (`CANCEL_REQUESTED` for running leased work, direct `CANCELLED` for non-running) is agent-scoped. All lifecycle controls verify `assignment.agentId()` matches the route agent. A project-scoped cancel must verify `assignment.projectId()` matches the route project AND the caller is a project member.

**What most reports say:** "Add project-scoped lifecycle authorization" — treated as a straightforward extension.

**What the Automation report contributes:**
- The system chat proposal path (propose → confirm) could gate project-scoped cancellation, OR direct cancellation from the project surface could bypass proposals. Recommendation: direct cancellation from both surfaces, consistent with the agent console pattern.
- The force-interrupt path for running assignments must still respect lease ownership regardless of which surface triggered the cancel.
- No new `OrchestrationStatus` values needed — the existing `CANCELLED` and `CANCEL_REQUESTED` serve both surfaces.

**Recommendation:** Adopt the direct-cancellation approach from the project surface. It is operationally simpler and consistent with the agent console. The implementation requires: (a) a new `cancelProjectAssignment(projectId, assignmentId)` service method that verifies membership AND assignment-project match, (b) a new controller route, (c) reusing the existing cancel lifecycle internally. The system chat proposal path remains available for conversational interaction but is not a gate.

### 9. Gap: Priority Inheritance and Mutation Are Under-Explored

Priority is mentioned across reports but no report fully explores how priority flows through the system.

**What exists:** `WorkAssignment.priority` is an int column. Agent queue orders by `priority DESC, created_at ASC`. No mutation endpoint exists.

**What the Automation report contributes:**
- Priority mutation endpoint (simple CAS on the priority column, safe because assignment isn't leased when queued)
- Priority inheritance: jobs could inherit a default priority from project settings; schedules could carry priority in their assignment template; event reactions similarly
- Priority as a user-facing control: inline edit or modal on queue rows

**What's missing:** The Operational UI Composer mentions priority as a column but doesn't design the editing interaction. The Unified Contract Architect mentions `priority` in their `task_items` table but doesn't address the mutation path.

**Recommendation:** Priority mutation is a small, high-value addition to Phase 1 (unified contract). Add `PATCH /api/assignments/{assignmentId}/priority` (or project-scoped equivalent) that CAS-updates the priority column when the assignment is QUEUED. Add inline priority editing to the queue row renderer. Priority inheritance (project default → schedule template → assignment) can be deferred to the Schedule phase.

### 10. Gap: Event Reaction Causation Chains and Project Scope

The existing `EventReactionService` tracks root event, parent event/dispatch, depth, dedupe key, and correlation. When a project-scoped event triggers a reaction that creates an assignment, the causation chain must carry the project identity so that "show all assignments triggered by events in project X" is queryable.

**What the Automation report contributes:**
- The causation chain already links dispatch → assignment via `ENQUEUE_ASSIGNMENT` action recording
- Project-scoped queries can join through the dispatch audit to find project-triggered assignments
- No new causation mechanism needed — the existing chain carries enough context
- The open question is whether event types need a `project_id` column or whether the event payload carries project identity

**What other reports miss:** The Operational UI Composer asks "Should the Events tab also show event history (fired events, not just reaction config)?" — this is the right question. The automation report's answer: yes, project-scoped event history is a read-only audit view over the existing dispatch/attempt tables with a project filter.

### 11. Convergent Phasing: All Reports Agree on Dependency Order

Despite different phase numbering and naming, all five reports agree on the dependency chain:

```text
Unified Contract Foundation
  → Project Queue Tab (proves the pattern)
    → Files Enhancement + Jobs Reassignment (parallelizable)
      → Tasks Domain (largest new surface)
        → Schedules + Events Tabs (extend existing automation)
          → Project Chat (deferred implementation)
```

**Tensions in phasing:**
- Unified Contract Architect places Tasks (Phase 2) before Jobs Consolidation (Phase 3)
- Delivery Risk Strategist places Jobs Reassignment (Phase 3) before Tasks (Phase 4)
- Automation and Lifecycle Researcher agrees with Delivery Risk Strategist: Jobs reassignment is lower-risk because it only adds operations to existing tables, while Tasks require a new table and new domain logic. Building reassignment first also proves the reassignment pattern that Tasks will need.

**Recommendation:** Jobs enhancement before Tasks domain. Order: Queue → Files → Jobs (reassignment) → Tasks → Schedules/Events → Chat. This puts the lowest-risk new capability (reassignment on existing tables) before the highest-risk new domain (task_items table + full CRUD).

### 12. Operational UI Composer's Most Valuable Contribution: The `OperationalItem` Unified Render Contract

The Operational UI Composer proposes an `OperationalItem` record that normalizes assignments, tasks, and jobs into a single sortable/filterable row shape for rendering. This is a render-time contract, not a persistence contract. It elegantly solves the "different detail views for different kinds" problem with a polymorphic detail renderer.

This concept is compatible with every other report's persistence model — it doesn't touch the canonical tables. It's a view-layer concern that makes the Queue tab (the most cross-cutting surface) clean without forcing persistence unification.

**Recommendation:** Adopt the `OperationalItem` concept. Add it to the unified contract specification. The `OperationalItemService` composes data from `WorkAssignment` (for running/queued assignments), `task_items` (for pinned unassigned tasks), and `job_definitions` (for defined but not-yet-fired jobs) into a single sortable projection. The `QueueRowRenderer` accepts `OperationalItem` and renders the appropriate kind-specific detail expansion.

### 13. Project Context Steward's Most Valuable Contribution: Materialization Spec Template Resolution

The directive says "Directory initialized to spec on project creation" for the project Files tab. But projects have no filesystem root, and materializations are agent-scoped. The Project Context Steward resolves this tension cleanly:

> "directory initialized to spec" means the project carries a declarative directory specification (stored in projects table metadata) that each agent's materialization provisioner applies when creating the materialization container. The materialization is still agent-scoped, but the initial structure follows the project spec.

This preserves the architecture while satisfying the requirement. The Automation and Lifecycle Researcher report missed this resolution.

**Recommendation:** Store the directory spec as a JSON field on the `projects` table (or in `projects.settingsOverrideJson`). When `AgentMaterializationService` provisions a materialization for a project member, it reads the project's directory spec and creates the specified structure. This is a small extension to the existing provisioning flow, not a new filesystem authority for projects.

### 14. Delivery Risk Strategist's Most Valuable Contribution: Explicit Validation Matrix

The phase-by-phase validation matrix (repository tests, service tests, controller tests, startup, HTMX behavior, Playwright) is the most concrete quality plan produced by any report. It transforms "we should test this" into specific test categories per phase.

**Recommendation:** Adopt this matrix as the validation contract for each phase. Add two Automation and Lifecycle Researcher concerns to it:
- **Lease/fence tests** for reassignment and project-scoped cancellation (verify CAS guards work when two surfaces attempt conflicting operations)
- **Lineage integrity tests** for reassignment (verify REASSIGNED_FROM links are correct and chaseable)

### 15. Unified Contract Architect's Most Valuable Contribution: The `contracts.md` Specification

The proposal to create `.internal-dev/specifications/contracts.md` (or `unified-contracts.md`) as a formal specification document is essential. It would record:
- The entity projection matrix (which tables serve which views)
- The dual-scope column pattern (`agent_id` + `project_id`)
- The rule that cross-cutting views are filtered projections, never separate stores
- The `OperationalItem` render contract
- The task/job/assignment distinction
- The project chat API contract (for deferred implementation)
- The schedule/reaction project-scoping contract

**Recommendation:** Create this document in Phase 0 before any implementation begins. All phases reference it. This is the "unified contract" deliverable that the sprint directive calls for.

## Options

### Option 1: Adopt the Converged Five-Report Consensus (Strongly Recommended)

Take the strongest agreed-upon positions from across all reports and produce a single coherent plan:

| Decision | Consensus Source | Specific Shape |
|----------|-----------------|----------------|
| Single source of truth | All five reports | `work_assignments` + filtered projections. Codify in `contracts.md`. |
| Phase 1 = Queue Tab | All five reports | Project-scoped queries + `#project-queue-panel` HTMX host. Zero schema changes. |
| `project_id` on schedules/reactions | 4 of 5 reports | Nullable column, backward-compatible. Not a junction table. |
| New `task_items` table | 4 of 5 reports | Lightweight work items. Distinct from plans. Plus `task_pins` junction for cross-kind pinning (Automation contribution). |
| Reassignment = revoke-and-clone | Automation report (others treat as open question) | REASSIGNED_FROM lineage. Context re-capture. State-dependent behavior. |
| `OperationalItem` render contract | Operational UI Composer | Polymorphic detail renderer. View-layer only. |
| Materialization spec template | Project Context Steward | JSON spec on `projects` table, applied at provisioning time. |
| Project chat deferred, API contract now | All five reports | `ChatSessionSurface.PROJECT` registered. Bounded read profile designed. Placeholder host mounted. |
| Job consolidation deferred | Automation + Delivery Risk Strategist | Document as deferred task. Sprint only adds reassignment and run drill-down. |
| Explicit validation matrix | Delivery Risk Strategist | Per-phase test categories. Add lease/fence and lineage integrity tests. |
| `contracts.md` specification | Unified Contract Architect | Phase 0 deliverable. All phases reference it. |

**Pros:** Maximum consensus, minimum risk. Every decision has at least four reports supporting it. The few areas of genuine disagreement (schedule scoping mechanism, task entity shape, job consolidation timing) are resolved by majority view with explicit rationale.

**Cons:** The reassignment design (revoke-and-clone) is the Automation report's contribution and hasn't been stress-tested by the other lenses. This needs explicit review before implementation. The `task_pins` junction table for cross-kind pinning adds a table not proposed by other reports — it's a defensible extension but needs product confirmation.

### Option 2: Minimalist — Ship Only What Has Unanimous Consensus

Scope the sprint to ONLY what all five reports agree on: unified query contract, project Queue tab, project Files enhancement, and schedule/reaction project-scoping. Defer everything else (Tasks, reassignment, project chat, job consolidation) to a follow-up sprint.

**Pros:** Zero risk of building something controversial. Fastest path to user-visible value. All five reports' concerns satisfied.

**Cons:** The directive explicitly asks for "Project Tasks Tab," "Jobs System Enhancement," "Project Events Tab," and "Schedules Tab." Dropping these would violate the directive. The "unified contract" value proposition is weaker without Tasks and Jobs participating in the unified queue.

**Verdict:** Not viable. The directive is clear about scope. The question is HOW to build these things, not WHETHER.

### Option 3: Maximalist — Build Everything, Including Job Consolidation and Full Chat

Consolidate `orchestration_jobs` into `job_definitions`, implement the full project chat with SSE streaming, and add all tabs in a single large sprint.

**Pros:** Most thorough. No deferred technical debt. Single migration window.

**Cons:** Massive risk. Job consolidation touches existing orchestration flows that are tested and stable. Full chat implementation when the directive says "implement later" violates explicit scope boundary. Single large sprint is harder to validate incrementally.

**Verdict:** Not recommended. The directive's own phasing guidance says "Group work into cohesive phases by domain dependency." This option ignores that guidance.

## Trade-offs

### Schedule/Reaction Scoping: Column vs. Junction Table

**Column approach (4 reports):** Add `project_id` to `schedule_definitions` and `reaction_definitions`. Query: `WHERE project_id = ? OR (project_id IS NULL AND agent_id = ?)`. Simple, indexed, consistent with `work_assignments`.

**Junction table approach (1 report):** `project_event_bindings` with project_id, agent_id, definition_id, definition_kind. Query: JOIN with bindings WHERE project_id = ?. Cleaner separation of ownership.

**Analysis:** The column approach wins for three reasons: (1) it's consistent with the established `work_assignments` dual-scope pattern, (2) it's simpler to query and index, (3) a junction table can always be introduced later by migrating from the column if ownership semantics demand it. The reverse migration (junction → column) is lossy. Start simple; add abstraction only when needed.

**Decision:** Column approach.

### Reassignment: Now vs. Later Design Depth

**Now:** Fully specify the revoke-and-clone mechanism with state-dependent behavior, lineage tracking, and context re-capture before any Jobs or Tasks implementation begins.

**Later:** Add a `reassign()` method to `JobService` as a simple agent-ID swap, and refine the mechanism when Tasks need it.

**Analysis:** The Automation and Lifecycle Researcher report argues that reassignment "touches the most invariants (leases, bindings, lineage, workspaces) and should be proven at the service layer first." The other reports treat reassignment more lightly but don't disagree. Since Jobs enhancement (Phase 3 in the recommended ordering) needs reassignment, and Tasks (Phase 4) also needs it, the mechanism should be designed once, correctly, before either phase.

**Decision:** Design now. The reassignment specification should be part of the `contracts.md` document in Phase 0, with service-level tests written before any UI work.

### Project Chat: Placeholder vs. Skeleton Host

**Placeholder:** A static HTML fragment saying "Project chat is coming soon." No interactive elements. Pure information.

**Skeleton:** A functional host shell with the planned route structure visible but non-functional controls. Includes the planned API shape in documentation-style rendering. This is what Operational UI Composer advocates.

**Analysis:** The skeleton host is slightly more work but has two advantages: (1) it validates the visual layout of the project console with the chat host present, (2) it communicates the planned contract to anyone inspecting the UI. The cost is marginal — it's a single fragment with no backend.

**Decision:** Skeleton host. Render the planned API shape (route, method, payload shape) as documentation within the tab, with a "Coming in a future release" notice.

### Task Entity: Single Table vs. Table + Junction

**Single `task_items` table:** Create tasks directly in the table. Pinning is a boolean flag or project_id column on the task row. Does not support pinning existing plans/workflows/jobs to a project.

**`task_items` + `task_pins` junction:** Create lightweight tasks in `task_items`. Pin existing definitions of any kind via `task_pins`. The project Tasks tab shows both.

**Analysis:** The directive says tasks are "user-defined work items, pinnable, assignable, schedulable." This sounds like the single-table approach. But the directive also implies that existing plans and workflows should be project-visible. Without the `task_pins` junction, pinning a workflow to a project would require duplicating the workflow definition into `task_items` — clearly wrong.

**Decision:** Both tables. `task_items` for user-created lightweight work items. `task_pins` for binding existing definitions (any kind) to projects with revision-qualified identity. The project Tasks tab UNIONs both sources.

## Open Questions

### Questions Resolved by Cross-Report Synthesis

1. **Should the project Queue tab show all assignments or only active ones?** Operational UI Composer raised this. Resolution: show active + recently terminal (last 24 hours), with a "Show all" filter. This mirrors the agent queue's behavior while keeping the project queue manageable.

2. **Should project schedules/reactions be editable from the project console?** Operational UI Composer raised this. Resolution: yes, with the same operation-key idempotency pattern used by the agent console. The `project_id` column makes the definition visible to both surfaces; last-write-wins with revision-qualified identity prevents conflicts.

3. **Should the Files tab show unified across members or single-member?** Operational UI Composer raised this. Resolution: single-member selection (existing behavior). Cross-member diffing is complex and out of scope. The materialization spec template ensures consistent structure across members.

4. **Priority: int vs. named tiers?** Operational UI Composer raised this. Resolution: keep int (existing column). Named tiers can be a UI-only mapping (0=LOW, 1=MEDIUM, 2=HIGH, 3=CRITICAL) without schema change.

### Questions Still Open After Synthesis

1. **Reassignment during RUNNING state: interrupt and restart, or wait for completion?** The Automation report proposes force-interrupt → terminal-cancel old run → new assignment with REASSIGNED_FROM lineage. This loses partial progress. An alternative is PAUSE → reassign → RESUME on new agent, but this requires transferring workspace state. This needs product-level decision.

2. **Task-to-agent cardinality: one-at-a-time or multi-agent?** The Automation report flags this but no report resolves it. Multi-agent assignment creates fan-out complexity (N assignments for one task, completion when all complete? any complete?). Recommendation: one-at-a-time for the sprint. Multi-agent fan-out is a deferred feature.

3. **Project chat model selection: owner agent's model or project-level override?** The `projects.model` field exists. The directive says "uses the main chat agent." Resolution: use the project-level model override if set, otherwise the project owner agent's model configuration. This is consistent with the existing model selection hierarchy.

4. **Schedule feature flag gating for project-scoped schedules.** The existing `magenta.features.schedules-enabled` flag controls all schedule behavior. Should project-scoped schedules have a separate flag? Resolution: no. One flag controls both. Project-scoped schedule UI shows "unavailable" when the flag is disabled, matching the agent-scoped behavior.

5. **Project deletion cascade for schedules, reactions, and task pins.** When a project is deleted, what happens to project-scoped schedules, reactions, and pinned tasks? The existing `ProjectService.requireProjectDeletionAllowed()` blocks deletion when active assignments exist. Resolution: extend the guard to include pending schedule firings (next_run_at is not null) and active reaction subscriptions. Soft-delete the project (set `deleted = 1` on project row, schedules, reactions, and pins) rather than hard-deleting rows. This preserves historical audit.

6. **Event type catalog for project-scoped events.** The existing `EventType` enum and `OrchestrationEvent` model are agent-scoped. Adding project-scoped events (e.g., `PROJECT_MEMBER_ADDED`, `PROJECT_MATERIALIZATION_CHANGED`) requires new event type values and a new event emission path in `ProjectService`. This is new infrastructure beyond just adding a `project_id` column to reactions. Scope: add project event types as part of the Events phase, with events emitted from `ProjectService.addAgent()`, `ProjectService.removeAgent()`, and `AgentMaterializationService` lifecycle transitions.

## Recommended Next Step

### Phase 0: Unified Contract Specification (Pre-Implementation)

Before any code changes, produce `.internal-dev/specifications/unified-contracts.md` containing:
1. The entity projection matrix (from Project Context Steward)
2. The dual-scope column pattern rule (from Unified Contract Architect)
3. The `OperationalItem` render contract (from Operational UI Composer)
4. The reassignment specification: revoke-and-clone mechanism with state-dependent behavior, REASSIGNED_FROM lineage, context re-capture contract (from Automation and Lifecycle Researcher)
5. The task definition and pinning schema (`task_items` + `task_pins` tables, revision-qualified pin identity)
6. The project chat API contract (deferred implementation)
7. The schedule/reaction project-scoping design (nullable `project_id` column)
8. The materialization spec template contract (JSON spec on `projects` table)
9. The validation matrix per phase (from Delivery Risk Strategist, extended with lease/fence and lineage tests)

### Phase 1: Unified Query Contract + Project Queue Tab

- Add `AssignmentQueryParams` record and unified `findAssignments()` to `OrchestrationRuntimeRepository`
- Add `AssignmentType.TASK` enum value
- Add project-scoped lifecycle authorization (`cancelProjectAssignment`, `pauseProjectAssignment`, `resumeProjectAssignment`)
- Add priority mutation endpoint (`PATCH /api/assignments/{id}/priority`)
- Mount `#project-queue-panel` HTMX host with reused row rendering
- Implement `OperationalItemService` composing assignments into unified rows
- Validate: repository query tests, service lifecycle authorization tests, controller fragment route tests, bounded startup, Playwright visual critique

### Phase 2: Project Files Enhancement + Job Reassignment (Parallel)

**Track A (Files):**
- Add directory spec JSON column to `projects` table (migration 014)
- Extend `AgentMaterializationService.provision()` to apply spec template
- Validate: provisioning tests, spec-template tests, Playwright for file tree structure

**Track B (Jobs):**
- Implement revoke-and-clone reassignment in `JobService`
- Add `REASSIGNED` to `OrchestrationStatus` and `REASSIGNED_FROM` to `RunLineageMode` (migration 014)
- Add run drill-down to project Jobs tab
- Validate: reassignment service tests (QUEUED, RUNNING, COMPLETED states), lineage integrity tests, Playwright for reassign flow

### Phase 3: Tasks Domain

- Create `task_items` and `task_pins` tables (migration 015)
- Implement `TaskService` with CRUD, assignment, and pinning
- Mount project Tasks tab with create/edit/assign/pin flows
- Tasks appear in unified queue via `OperationalItemService` composition
- Validate: task CRUD tests, assignment lifecycle tests, pin revision tracking tests, Playwright for task management flow

### Phase 4: Schedules + Events Tabs

- Add `project_id` to `schedule_definitions` and `reaction_definitions` (migration 016)
- Add project-scoped schedule/reaction query methods
- Add project event types to `EventType` and emission in `ProjectService`/`AgentMaterializationService`
- Mount project Schedules tab (visual cron editor, firing audit) and Events tab (reaction configuration, event history)
- Validate: schema migration tests, project-scoped CRUD tests, feature flag gating tests, Playwright for schedule editor and event configuration

### Phase 5: Project Chat (API Contract + Placeholder Host)

- Register `ChatSessionSurface.PROJECT`
- Design and document bounded read profile for project chat
- Document API contract (`POST /api/projects/{projectId}/chat/stream`)
- Mount skeleton host in project console with planned API shape
- No backend implementation (per directive)

### Cross-Phase Commitments

- Each phase includes a `.internal-dev/changelogs/` entry
- `unified-contracts.md` is updated if any contract changes during implementation
- The Delivery Risk Strategist's validation matrix is executed per phase
- Playwright screenshots are captured for all new/changed surfaces
- No separate stores, shadow tables, or parallel execution models are introduced — the filtered-projection rule is the architectural invariant across all phases
