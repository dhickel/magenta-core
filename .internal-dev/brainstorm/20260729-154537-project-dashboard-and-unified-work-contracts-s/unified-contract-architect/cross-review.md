
## Prompt

Cross-examine every other required report against the Unified Work Contract Architect's findings. Compare useful ideas, surface conflicts, identify omissions, evaluate feasibility and trade-offs, and produce a synthesis that preserves the broad lens without merely defending the first answer. The four reports are: Unified Work Contract Architect (domain/persistence/API contracts), Project Context Steward (information architecture and reusable interaction system), Automation Lifecycle Researcher (reassignment, priority, cancellation, pinning, schedules, reactions, visibility), and Delivery Risk Strategist (migration risk, compatibility, scope control, rollout seams, validation).

## Source

All four submitted reports:

- **Unified Work Contract Architect** — Options A (Evolve In-Place), B (Contract Unification with new canonical tables), C (Pragmatic Tiered Unification — Recommended). Introduced `task_items` table, `AssignmentQueryParams` unified query, two-job-model consolidation, `ConversationSurface.PROJECT`, project_id columns on schedules/reactions.

- **Project Context Steward** — Options A (Full Mirror console), B (Hybrid), C (Evolutionary — Recommended). Options D1/D2 for schedule scoping. Options E1/E2 for project chat. Introduced the entity projection matrix table, `project_task_pins` junction table, materialization spec-template idea, and the tension around "directory initialized to spec on project creation."

- **Automation Lifecycle Researcher** — Most technically deep on reassignment (Option A: Revoke-and-Clone with REASSIGNED status and REASSIGNED_FROM lineage), pinning semantics, schedule/reaction idempotency reuse, priority mutation, and cancellation projection. Introduced the REASSIGNED terminal status, REASSIGNED_FROM lineage mode, and the task_pins revision-qualified pinning model.

- **Delivery Risk Strategist** — Options A (Minimal Incremental), B (Full Mirror), C (Tasks-First), D (Contract-First, Queue-First — Recommended). Introduced the phase dependency graph, per-phase validation strategy table, risk inventory for schedule/reaction agent-scoping depth, feature-flag reuse as rollout seam, and the materialization spec-template format question.

Backing specifications and knowledge files already cited in each report are incorporated by reference.

## Findings

### 1. Strong Consensus on Foundation — The Unified Contract Already Exists as a Latent Pattern

All four reports agree on the core architectural truth: `WorkAssignment` already carries `agentId`, `projectId`, `workspaceType/key/identity`, and `assignmentType`. The dual-key query pattern (`WHERE agent_id = X` / `WHERE project_id = Y`) already has repository methods like `findActiveAssignmentsForProject()`. No second store, shadow table, or derived projection is needed. The work is surfacing this through a unified query contract and rendering it through reusable fragment components.

**Alignment assessment**: This is the single strongest agreement across all reports. The cross-cutting contract is not a new abstraction — it is the existing `WorkAssignment` record with new query methods and controller routes. This finding alone eliminates the largest architectural risk (designing a new persistence model from scratch) and collapses the scope of Phase 0 to query-method additions and fragment parameterization.

### 2. Critical Conflict: Schedule/Reaction Project-Scoping Mechanism

This is the most significant disagreement across the four reports:

| Report | Mechanism | Pros | Cons |
|---|---|---|---|
| **Unified Work Contract Architect** | Add `project_id` column to `schedule_definitions` and `reaction_definitions` (Option C) | Simple WHERE-clause queries; consistent with assignment dual-scope pattern; no join overhead | Column proliferation; nullable column semantic ambiguity (NULL = agent-scoped or unset?) |
| **Project Context Steward** | Add `project_id` column (Option D1) | Same as above; explicitly calls this the "simpler" path | Same concerns |
| **Operational UI Composer** | Binding layer — separate `project_event_bindings` table (Option B) | Clean separation of concerns; agent-owned definitions remain canonical; project can suppress without owning; multi-project binding possible | Extra join on every project query; more tables to migrate; two-table consistency concerns |
| **Automation Lifecycle Researcher** | Add `project_id` column (Option 1) | "Most direct, most aligned with existing patterns, least disruptive" | Same as above |
| **Delivery Risk Strategist** | Extend existing tables with `project_id` column (Option A/D) | Backward-compatible; existing agent-scoped rows have NULL; no existing data migration needed | Notes that schedule/reaction agent-scoping is "deep" — touching 5+ layers |

**Resolution**: Three of four authors favor adding `project_id` columns directly. The UI Composer's binding-layer argument has architectural merit (cleaner separation of concerns) but introduces a join that complicates every project-scoped query. Given that `project_id` already exists as a nullable column on `work_assignments`, `orchestration_jobs`, and `job_definitions`, extending the same pattern to schedules and reactions is the consistent choice. **Recommendation**: Add `project_id` columns. The binding-layer approach can be revisited if multi-project binding or project-scoped suppression without agent awareness becomes a concrete requirement.

**Decision guardrail**: The `project_id` column must be indexed and remain nullable. Agent-scoped queries (`WHERE agent_id = X AND project_id IS NULL`) and project-scoped queries (`WHERE project_id = Y`) are disjoint. A schedule with both `agent_id` and `project_id` non-null is scoped to that project but executed by that agent — this is the expected dual-scope case.

### 3. Critical Conflict: Task Entity Design — New Table vs. Junction Table vs. Plan Extension

| Report | Approach | Concrete Shape |
|---|---|---|
| **Unified Work Contract Architect** | New `task_items` table | `id, project_id, title, description, pinned, priority, status, assigned_agent_id, workspace_type/key/identity, timestamps` — lightweight, standalone entity |
| **Project Context Steward** | New `ProjectTask` entity + `project_task_pins` junction | Two tables: task definition + pin mapping. Task is a first-class entity; pin is a separate durable fact |
| **Automation Lifecycle Researcher** | `task_pins` junction table over existing plan/job definitions | `(project_id, definition_kind, definition_id, revision, pinned_at, pinned_by_agent_id)` — no new task table; reuses plan_definitions and job_definitions |
| **Delivery Risk Strategist** | New `TaskDefinition` domain (Option D) | Task CRUD, pin semantics, `AssignmentType.TASK_ASSIGNMENT` — acknowledges this is "the largest new domain surface" |

**Analysis**: This disagreement stems from different interpretations of what "Tasks" are:
- The **Architect** sees Tasks as lightweight user-created work items distinct from AI-generated plans — hence a new clean table.
- The **Steward** sees Tasks as project-pinnable definitions that need a pin junction for multi-project and revision tracking.
- The **Automation Researcher** sees Tasks primarily through the lens of pinning existing definitions to projects — hence a junction table over plan/job definitions.
- The **Risk Strategist** sees Tasks as a net-new domain requiring substantial service/repository/API work.

**Resolution**: The Automation Researcher's junction-table-only approach is too narrow — it assumes tasks are always backed by a `plan_definition`, which conflates lightweight user work items with heavy AI-generated plans. The Architect's standalone `task_items` table is the right starting point, but the Steward and Automation Researcher are correct that pinning should be a revision-qualified separate fact (not a boolean column), consistent with the revision-qualified identity pattern used by schedules and reactions.

**Recommended synthesis**: A new `task_items` table as the canonical task definition, plus a `task_pins` junction table for the project-pinning relationship with revision qualification.

```sql
-- Canonical task definition
CREATE TABLE task_items (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    project_id TEXT,          -- optional: task created in project context
    assigned_agent_id TEXT,   -- optional: currently assigned agent
    priority INTEGER DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED
    workspace_type TEXT,
    workspace_key TEXT,
    workspace_identity TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Revision-qualified project pin (follows schedule/reaction pattern)
CREATE TABLE task_pins (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    revision TEXT NOT NULL,        -- snapshot of task definition revision at pin time
    pinned_by_agent_id TEXT,
    pinned_at TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (task_id) REFERENCES task_items(id)
);
```

The pin is revision-qualified: editing a task creates a new revision; the pin stays at the pinned revision until explicitly refreshed. This matches the schedule/reaction revision model and the snapshot-based run model.

### 4. Strong Consensus: Project Chat Surface Design

All four reports agree:
- Use `ChatSessionSurface.PROJECT` as a new surface kind
- Defer full implementation per the directive ("plan for the API contract now, implement later")
- The chat should have a bounded read-only profile with project context access
- SSE streaming reuses existing infrastructure
- Membership-gated access (via `ProjectService.isMember()`)
- API contract shape: `POST /api/projects/{projectId}/chat/stream?message=...`

**Refinement from cross-report analysis**: The Automation Lifecycle Researcher raises an important question that the other reports don't fully address: should the project chat profile be able to propose queue operations (like system chat)? The recommendation from the Steward is yes — a project chat should be able to propose PAUSE/RESUME/CANCEL on project-scoped assignments, following the same system-chat proposal pattern. This should be noted in the API contract design but deferred along with the rest of project chat implementation.

### 5. Strong Consensus: Console Architecture Evolution

| Report | Console Architecture Recommendation |
|---|---|
| **Unified Work Contract Architect** | Keep existing tab shell; add independent hosts inside tabs; eventual refactor |
| **Project Context Steward** | Option C — Evolutionary, with sub-host refinement; extract `ConsoleFragmentRenderer` |
| **Operational UI Composer** | Hub-and-spoke with sub-hosts; extract `ProjectConsoleController` and shared `ConsoleFragmentRenderer` |
| **Delivery Risk Strategist** | Same as agent console pattern: independent HTMX hosts |

**Alignment**: All agree that the project console should move toward the agent console's independent-host pattern, but pragmatically: existing tabs keep current `#project-tab-panel` behavior, new tabs get their own stable host IDs, and rendering helpers are extracted from the 8,700-line `OrchestrationController` into a shared `ConsoleFragmentRenderer`.

**The UI Composer's `OperationalItem` contract** is a valuable synthesis that bridges the Architect's `AssignmentQueryParams` and the Steward's entity projection matrix. A unified render-time record:

```java
record OperationalItem(
    String id,
    String kind,        // "assignment", "task", "job"
    String agentId,
    String projectId,
    String displayName,
    String status,
    int priority,
    Instant updatedAt,
    String conversationId,
    String taskRunId
) {}
```

This lets the Queue tab render assignments, pinned tasks, and job runs in a single sortable/filterable table with a `kind` discriminator column. The agent queue and project queue both use the same `OperationalItem` shape with different filters.

### 6. Important Omission: Reassignment Mechanism Design (Resolved by Automation Researcher)

The Architect and Steward reports mention reassignment but do not design it. The Automation Lifecycle Researcher provides the critical design: **Revoke-and-Clone with REASSIGNED terminal status and REASSIGNED_FROM lineage**.

This is the most architecturally significant new lifecycle primitive proposed across all four reports, and it composes correctly with existing infrastructure:
- **Revoke**: Old assignment moves to `REASSIGNED` (new terminal status in `OrchestrationStatus`). If RUNNING, force-interrupt first.
- **Clone**: New assignment created for new agent with `REASSIGNED_FROM` lineage (new value in `RunLineageMode`). New run, new lease, new context binding capture.
- **Lineage**: The new run carries `REASSIGNED_FROM` pointing to the old run, following the existing `RETRY_OF`/`REPLAY_OF` pattern.
- **Context**: The new agent's immutable context binding is captured at reassignment time. The old agent's binding remains as historical evidence.
- **Workspace**: The new agent runs under its own workspace. Old artifacts remain in the old workspace; project-scoped artifact queries surface both.

**Recommendation**: Adopt this design as the authoritative reassignment contract. It reuses existing primitives (CANCEL_REQUESTED, force-interrupt, REPLAY_OF lineage, context capture, operation-key idempotency) without inventing new mechanisms. The Delivery Risk Strategist correctly identifies this as a Phase 3 concern that depends on Phase 1 (unified contract) and should be service-tested before any UI work.

### 7. Important Omission: Materialization Spec-Template (Partially Resolved)

The directive says "Directory initialized to spec on project creation" for the Files tab. The Steward identifies the tension: projects have no filesystem root, materializations are agent-scoped. The Steward proposes: the project carries a declarative directory specification that each agent's materialization provisioner applies.

The Risk Strategist flags this as an open question: "What defines the directory structure template — a JSON/YAML manifest, a SimplyPages component, or a hardcoded config per project?"

**Resolution proposal**: Store the spec as a JSON column on `projects` (e.g., `directory_spec_json`). The spec is a simple array of relative paths:

```json
["docs/", "scripts/", "outputs/", "README.md"]
```

When an agent provisions a materialization for this project, `AgentMaterializationService` reads the project's spec and creates the listed directories/files within the materialization root after provisioning but before marking `READY`. This is:
- Consistent with projects having no filesystem root (materialization is still agent-scoped)
- Consistent with `READY` meaning "canonical location available" (the spec is applied before READY)
- Backward-compatible (NULL spec = no template, existing behavior)
- Simple to implement (directory creation is a confined explorer operation)

### 8. Important Omission: The Two-Job-Model Consolidation

Only the Architect's report identifies the duplication between `JobDefinition`/`JobRun` and `OrchestrationJob`/`OrchestrationJobItem`. The other reports treat "Jobs" as a single unified concept. This is a genuine architectural debt that should be addressed in the sprint, but the Risk Strategist's phasing correctly places Jobs enhancement (Phase 3) before Tasks (Phase 4) and after the unified contract is proven.

**Recommendation**: Consolidate during Phase 3 (Jobs Enhancement). The `OrchestrationJob` model absorbs `JobDefinition` semantics:
- `OrchestrationJob` gains `project_id` (nullable), `workspace_type/key/identity`, `status` (already has these)
- `OrchestrationJobItem` already has `task_id`, `workflow_id`, `model_override`, `priority`, `retry_count`, `continue_on_failure`
- `JobDefinition`/`JobRun` become legacy read-only; `JobService` adapts to `OrchestrationJobService`
- `job_definitions` and `job_runs` tables are tolerated as inert in existing databases; fresh schema omits them

This is a moderate-risk consolidation that should be done early enough that Tasks don't need to integrate with both job models.

### 9. Cross-Report Agreement on Phasing

All reports agree on the dependency-driven phasing order, with minor variations:

| Phase | Architect | Steward | Automation Researcher | Risk Strategist |
|---|---|---|---|---|
| Unified Contract | Phase 0 | Phase 1 (back-end) | Before all tabs | Phase 1 |
| Project Queue | Phase 1 | Phase 2 | After unified contract | Phase 1 |
| Project Files | Existing | Phase 4 refinement | Phase (a) | Phase 2 |
| Jobs Enhancement | Phase 3 | Phase 5 | Phase (d) | Phase 3 |
| Project Tasks | Phase 2 | Phase 3 | Phase (c) | Phase 4 |
| Project Events | Phase 5 | Phase 4 (with Schedules) | Phase (e) | Phase 5 |
| Project Schedules | Phase 5 | Phase 4 (with Events) | Phase (f) | Phase 5 |
| Project Chat | Phase 4 (deferred) | Phase 5 (deferred) | Deferred | Phase 6 (deferred) |

**Synthesis**: The Risk Strategist's phasing (Contract-First, Queue-First) has the strongest validation justification — Phase 1 proves the cross-cutting pattern without any schema changes. But the Architect's Phase 2 (Task Items) and the Automation Researcher's recommendation to design reassignment early are correct that tasks and reassignment are the riskiest new domain concepts and should be prototyped at the service layer before building UI. The Files enhancement is lower-risk and could run in parallel with Queue work.

**Recommended phasing (synthesized)**:
1. **Phase 0 — Unified Contract + Shared Renderers**: `AssignmentQueryParams`, `OperationalItem`, `ConsoleFragmentRenderer` extraction. One `findAssignments(AssignmentQueryParams)` method. Zero schema changes.
2. **Phase 1 — Project Queue Tab**: Mount `#project-queue-panel`, prove dual-key filter pattern with Playwright validation.
3. **Phase 2 — Project Files Enhancement**: Materialization spec-template on project creation. Sub-host refinement.
4. **Phase 3 — Jobs Consolidation + Reassignment**: Merge `JobDefinition` into `OrchestrationJob`. Add `REASSIGNED` status, `REASSIGNED_FROM` lineage, `reassign()` operation. Service-test reassignment before UI.
5. **Phase 4 — Project Tasks**: New `task_items` + `task_pins` tables. Task CRUD, assignment, pinning. Tasks appear in unified queue via `OperationalItem`.
6. **Phase 5 — Project Schedules + Events**: Add `project_id` to schedule/reaction tables (migration 014). Project-scoped CRUD. Visual cron editor. Reuse existing simulation.
7. **Phase 6 — Project Chat (API Contract + Skeleton)**: `ChatSessionSurface.PROJECT`, API contract doc, placeholder host. Deferred implementation.

### 10. Risk Inventory: What Each Report Identifies That Others Miss

| Risk | Identified By | Mitigation |
|---|---|---|
| Schedule/reaction agent-scoping is "deep" — touches 5+ layers | Risk Strategist | Feature flags as rollout seam; add project_id column with NULL default; backward-compatible |
| Project chat authorization gap in open-alpha posture | Risk Strategist | Membership check at controller; consistent with existing `ProjectService.isMember()` |
| Cross-cutting stale response pollution | Risk Strategist, UI Composer | Independent HTMX hosts; OOB refresh targets specific hosts, never full tab panel |
| Task entity scope — largest new domain surface | Risk Strategist | Phase 4 placement after contract proven; pin semantics defined before implementation |
| Two-job-model confusion | Architect | Consolidate in Phase 3; legacy tables tolerated as inert |
| Materialization spec-template format undefined | Risk Strategist, Steward | JSON array on projects table; simple relative paths; applied at provisioning time |
| Job reassignment during RUNNING state | Automation Researcher | Revoke-and-clone with force-interrupt; explicit policy decision needed |
| Multi-agent task assignment cardinality | Architect | Open question — one-at-a-time with reassignment until product decision |
| Reassignment + context rebinding | Automation Researcher | Immutable context capture at reassignment time; old binding preserved as history |

## Options

Rather than restating each report's options, this section evaluates the cross-cutting architectural choices that span multiple reports and require a unified decision.

### Option S1: Column Extension for Project Scoping (Consensus)

Add nullable `project_id` columns to `schedule_definitions`, `reaction_definitions`, and index them. Three reports favor this; the UI Composer's binding-layer alternative has merit but adds complexity without a concrete multi-project requirement. Go with columns.

### Option S2: New Task Domain with Revision-Qualified Pins (Synthesis)

New `task_items` table (Architect) + `task_pins` junction table with revision qualification (Automation Researcher + Steward). This combines the best of both: a clean lightweight task definition that doesn't conflate with plans, plus revision-qualified pinning that follows the established schedule/reaction snapshot pattern.

### Option S3: Revoke-and-Clone Reassignment with Lineage (Automation Researcher Design)

Adopt the Automation Researcher's reassignment design as the authoritative contract. It composes correctly with existing lifecycle primitives and has the most thorough analysis across all four reports.

### Option S4: ConsoleFragmentRenderer Extraction (UI Composer + Steward + Risk Strategist)

Three reports identify the 8,700-line `OrchestrationController` as a growing maintenance risk. Extract shared rendering into a stateless `ConsoleFragmentRenderer` utility. Create `ProjectConsoleFragments` for project-scoped rendering. The `OperationalItem` record (UI Composer) serves as the unified render-time contract.

### Option S5: Evolutionary Console Architecture (Consensus)

Keep existing tabs on `#project-tab-panel`. New tabs get independent stable host IDs. This is the pragmatic middle path between the Steward's Full Mirror (too much refactor) and doing nothing (stale-response risk). The UI Composer's hub-and-spoke host map is the target architecture.

### Option S6: Materialization Spec-Template as JSON on Projects (New Synthesis)

Store an optional `directory_spec_json` column on `projects`. Applied by `AgentMaterializationService` at provisioning time. Simple array of relative paths. This resolves the tension identified by the Steward between "directory initialized to spec on project creation" and "projects have no filesystem root."

## Trade-offs

### T1: Column Extension vs. Binding Table for Project Scoping

**Column extension (add project_id)**: Simpler queries, consistent with existing assignment pattern, no join overhead. But: nullable columns proliferate; semantic ambiguity between "agent-scoped" (NULL) and "project-scoped" (non-NULL); every schedule/reaction table needs the column.

**Binding table**: Cleaner separation, supports multi-project binding, project can suppress without agent awareness. But: extra join on every query, more migration surface, two-table consistency concerns, more complex CRUD.

**Synthesis**: Column extension wins on simplicity and consistency. The `project_id` column is already the established pattern on `work_assignments`, `orchestration_jobs`, and `job_definitions`. Adding it to schedules and reactions is the consistent choice. The binding table can be added later if multi-project binding becomes a concrete requirement — it's additive, not replacement.

### T2: Lightweight Task Entity vs. Plan Extension

**New task_items table**: Clean domain boundary, no plan-specific fields leaked into tasks, independent evolution. But: new table, new repository, new service. Three definition tables (plans, tasks, jobs) to maintain.

**Extend plan_definitions**: Less new code, reuses existing CRUD. But: plan_definitions carries AI-generated plan structure (steps, fields, output declarations) that's irrelevant to user-created tasks. Validation rules conflict.

**Junction table only**: Minimal new persistence. But: forces every task to be backed by a plan/job definition even for simple work items.

**Synthesis**: New `task_items` table. The semantic gap between "AI-generated multi-step plan" and "user-created work item" is too wide to bridge with optional fields. The additional table is justified. Consolidated alongside the job model consolidation in Phase 3 to reduce total table count.

### T3: Deferred Project Chat vs. Skeleton Host

**Deferred entirely**: Clean — no code shipped until ready. But: the project console has 10+ tabs and a visibly empty "Chat" tab looks broken.

**Skeleton host with API contract**: A placeholder host renders "Project chat is not yet available" with the designed API shape visible in documentation. This reserves the tab slot, communicates the plan, and avoids shipping a broken surface. The API contract is designed now so surrounding tabs don't assume incompatible shapes.

**Synthesis**: Skeleton host with documented API contract. This matches the directive's explicit instruction ("plan for the API contract now, implement later") and the Risk Strategist's validation strategy (Phase 6 requires no automated tests). The skeleton takes minutes to build and prevents the tab layout from needing rebalancing when chat is eventually implemented.

### T4: OrchestrationController Extraction Now vs. Later

**Now (Phase 0)**: Extracting `ConsoleFragmentRenderer` before building any tabs prevents the 8,700-line controller from expanding. The extraction is mechanical — move static rendering methods to a new class.

**Later (after tabs)**: Risk of duplicating rendering logic across `OrchestrationController` and a new `ProjectConsoleController`. Refactoring after tabs are built is harder.

**Synthesis**: Extract in Phase 0. The UI Composer is right that this unblocks all subsequent tab work without duplicating rendering logic. The extraction is low-risk (move methods, update imports) and provides immediate value.

### T5: Single Shared Fragment Renderer vs. Per-Context Renderers

**Shared**: `renderAssignmentRow(item, hostId)` — same Java method, parameterized target. Reduces code duplication. But: risk of OOB target collision if hostId is wrong.

**Per-context**: `renderAgentQueueRow()` and `renderProjectQueueRow()` — separate methods. Safer OOB targets. But: duplicated markup that must be kept in sync.

**Synthesis**: Shared parameterized renderers with strongly-typed host ID constants. The SimplyPages `SlotKey` pattern for static shared data (column headers, empty states) and per-request dynamic row rendering. Host IDs are typed constants (`AgentHosts.QUEUE_PANEL`, `ProjectHosts.QUEUE_PANEL`), not bare strings.

## Open Questions

1. **Should the `task_pins` table track which agent pinned the task, and should pins be visible across project members?** The Automation Researcher recommends `pinned_by_agent_id`. The Steward implies project-level visibility. Recommendation: `pinned_by_agent_id` as audit, project-level visibility for all members.

2. **When a task is pinned to a project and assigned to an agent, does the agent need to be a project member?** The existing `AssignmentRequest.projectId` doesn't enforce membership today (open-alpha posture). For the sprint, enforce project membership on task assignment. The assignment should fail if the agent is not a member.

3. **What is the concrete cron-expression format for "one-time" schedules in the visual editor?** The Automation Researcher notes that one-time schedules are just cron expressions that fire once. The visual editor should present "One time" as a datetime picker that generates the appropriate cron. The backend doesn't distinguish.

4. **How should priority be displayed and edited in the unified queue?** The Risk Strategist suggests an editable chip. The UI Composer suggests priority as a column. Recommendation: priority as an inline-editable chip in queue rows, with a PUT endpoint for priority mutation. Ties broken by `created_at ASC`.

5. **Should a reassigned task keep the same `taskRunId` or get a new one?** The Automation Researcher recommends a new run with REPLAY_OF lineage. This is correct — the old run is terminal-cancelled, the new run starts fresh in the new agent's workspace. The lineage link preserves traceability.

6. **What happens when a project is deleted while it has pinned tasks?** The `ProjectService.requireProjectDeletionAllowed()` pattern should be extended to check for active task pins. Pins on terminal tasks don't block deletion. Active pins (tasks with non-terminal status) should block deletion or be auto-unpinned with audit.

7. **Should the project Schedules tab show schedules from all member agents, or only schedules explicitly scoped to the project?** With the `project_id` column approach, only schedules with `project_id = Y` appear in the project view. Agent-scoped schedules (NULL project_id) do not appear. This is the cleaner semantics — the project tab shows only project-scoped artifacts.

8. **How should the Outputs tab evolve with project-scoped work?** Not addressed in any report. Project-scoped outputs would include artifacts from all member agents' project-attributed runs. The existing `OutputArtifactService.query()` would need a project-scoped filter. This is additive and can be included in Phase 1 (unified contract) alongside the queue work.

9. **Should system chat proposals support project-scoped targets?** The Automation Researcher raises this. System chat is agent-scoped by design (`DECISION-20260727-04`), but its proposals currently target agent-scoped assignments. A project-scoped assignment should be cancellable via system chat on the owning agent, but the proposal should reflect the project context. The existing proposal model already supports this — the target is an assignment ID, and the assignment carries projectId.

10. **Is the `OrchestrationJob` → `JobDefinition` consolidation direction correct, or should it be the reverse?** The Architect suggested `OrchestrationJob` absorbs `JobDefinition`. But `JobDefinition` already has `projectId`, `JobWorkItem` children, and `JobRun` tracking — it's the more complete model. Recommendation: `JobDefinition` absorbs `OrchestrationJob`. `OrchestrationJob`'s `OrchestrationJobItem` maps to `JobWorkItem`. `OrchestrationJobService` delegates to `JobService`. Legacy `orchestration_jobs` rows remain readable.

## Recommended Next Step

1. **Ratify the cross-report synthesis decisions before any implementation begins.** The decision set is:
   - Add `project_id` columns to schedules and reactions (not binding tables)
   - New `task_items` table + `task_pins` junction (not plan extension)
   - Revoke-and-clone reassignment with REASSIGNED status and REASSIGNED_FROM lineage
   - `ConsoleFragmentRenderer` extraction from `OrchestrationController`
   - `OperationalItem` as the unified render-time contract
   - `ChatSessionSurface.PROJECT` with skeleton host and documented API contract
   - Materialization spec-template as JSON array on projects
   - Job consolidation: `JobDefinition` absorbs `OrchestrationJob` (reverse of Architect's initial direction)

2. **Produce a single unified-contracts specification** (`.internal-dev/specifications/unified-contracts.md`) that codifies:
   - The entity projection matrix from the Steward's report
   - The `OperationalItem` shape from the UI Composer's report
   - The reassignment lifecycle from the Automation Researcher's report
   - The schedule/reaction project-scoping model
   - The task entity + pinning model
   - The project chat API contract (deferred implementation)
   - The materialization spec-template format
   - The fragment host map for the project console

3. **Execute Phase 0 (Unified Contract + Shared Renderers)** as the foundation sprint, with Phase 1 (Project Queue) immediately following. These two phases together:
   - Prove the cross-cutting filter pattern (zero schema change)
   - Establish the shared rendering infrastructure
   - Deliver the first user-visible new tab
   - Are independently validatable with the Risk Strategist's per-phase validation strategy

4. **Before Phase 2 (Files Enhancement), resolve the materialization spec-template format** with a brief product decision: JSON array of relative paths vs. richer manifest. The JSON array proposal in this synthesis should be the starting recommendation.

5. **Before Phase 3 (Jobs Consolidation + Reassignment), service-test the reassignment flow** as recommended by the Automation Researcher. The `REASSIGNED` status and `REASSIGNED_FROM` lineage are new lifecycle primitives that must be proven at the service layer before any UI work begins.

6. **Phase 4 (Project Tasks) should be the last major domain work before automation tabs** — its `task_items` and `task_pins` tables are the only new domain entities in the sprint. After Phase 4, Phases 5 (Schedules + Events) and 6 (Chat skeleton) are primarily UI and query-path work over existing infrastructure.
