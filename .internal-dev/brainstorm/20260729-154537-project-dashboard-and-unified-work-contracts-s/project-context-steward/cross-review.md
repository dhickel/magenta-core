
## Prompt

Cross-review four independent brainstorming reports — Project Context and Workspace Steward (architecture/scope), Unified Contract Architect (persistence/domain), Operational UI Composer (UI/fragments), and Automation Lifecycle Researcher (runtime/lifecycle) plus Delivery Risk Strategist (phasing/risk) — all addressing the same Project Dashboard and Unified Work Contracts Sprint. Identify agreements, conflicts, omissions, and the strongest synthesized path forward across identity, persistence, rendering, lifecycle, and phasing.

## Source

The four submitted reports plus the authoritative specification and knowledge store previously inspected: `.internal-dev/specifications/{architecture,services,api,web,simplypages,service-graph,decisions,deferred-features}.md`, `.internal-dev/knowledge/{2026-07-19-filesystem-first-workspaces,filesystem-first-workspaces,agent-lifecycle-htmx-targets,schedule-reaction-idempotency,run-lifecycle-idempotency-and-fencing,agent-queue-transcript-cancel-lifecycle,agent-scoped-assignment-lifecycle,dashboard-api-contract,operational-ui-contract-phase-01}.md`, and source files `OrchestrationController.java`, `ProjectController.java`, `ProjectService.java`, `AssignmentService.java`, `JobService.java`, `WorkAssignment.java`.

## Findings

### 1. Deep Agreement Zones (All Reports Converge)

**The unified contract is filtered projections over canonical tables — never separate stores.** Every report identifies the same core pattern: `work_assignments` already carries `agent_id` and `project_id`. Agent queue = `WHERE agent_id = X`. Project queue = `WHERE project_id = Y`. Overlap = `WHERE agent_id = X AND project_id = Y`. The same `WorkAssignment` record, same repository, same renderer. No report even entertains a parallel store.

**Projects have zero filesystem root authority — this is settled architecture, not an open question.** All reports treat `DECISION-20260719-02` (three-kind closed workspace model) as an invariant. The Project Files tab must consume agent-scoped materializations through `MaterializationLocation`. The Project Chat tab must capture an agent's context binding, not create a project workspace.

**Phase 1 should be the Project Queue tab.** Four of five reports explicitly recommend this as the starting point. It requires zero schema changes (the `project_id` column already exists on `work_assignments`), proves the unified contract end-to-end, and delivers immediate user-visible value. The Delivery Risk Strategist provides the most detailed rationale: "All data already exists... No schema migration required... Immediate user-visible value."

**Reassignment should use a revoke-and-clone pattern, not in-place mutation.** Automation Lifecycle Researcher provides the definitive analysis: creating a new assignment with `REASSIGNED_FROM` lineage preserves immutable context bindings and aligns with existing retry/replay patterns. My report and Unified Contract Architect concur. No report argues for in-place `agentId` mutation.

**Project chat needs `ConversationSurface.PROJECT` as a new surface kind.** All reports that address chat converge on this. The existing surface taxonomy (CHAT, PLAN, AGENT_CHAT, SYSTEM_CHAT) is already an established pattern. Adding PROJECT is a small enum change that enables project-scoped conversation listing, project-specific prompt behavior, and clear transcript ownership.

**`project_id` should be added to `schedule_definitions` and `event_reaction_definitions` as a nullable column.** Three reports favor this (mine, Unified Contract Architect, Automation Lifecycle Researcher). Two reports (Operational UI Composer, indirectly Delivery Risk Strategist) consider a binding-layer alternative. The column approach is simpler, aligns with how `project_id` already exists on `work_assignments`, and preserves the existing schedule/reaction execution model (the `agent_id` on the row still designates the executing agent).

**Task items warrant a new table, not extension of `plan_definitions`.** Three reports argue this explicitly (mine, Unified Contract Architect, Operational UI Composer). The plan model carries AI-generated plan steps, output declarations, and JSON snapshots that don't apply to lightweight user-created work items. A `task_items` table with just title, description, project binding, agent assignment, pin flag, and priority is cleaner and avoids coupling.

**Reusable SimplyPages fragment renderers with parameterized HTMX targets.** All reports that address UI (mine, Operational UI Composer, partially Delivery Risk Strategist) agree that shared Java rendering methods with parameterized OOB targets (not duplicated render paths) prevent code duplication while maintaining host-isolation safety.

### 2. Significant Conflicts

#### Conflict A: Schedule/Reaction Project-Scoping — Column vs. Binding Layer

- **Column approach** (Steward, Contract Architect, Lifecycle Researcher): Add nullable `project_id` to `schedule_definitions` and `reaction_definitions`. `WHERE project_id = ?` directly. Same row serves both agent and project views.
- **Binding layer approach** (UI Composer): Keep schedules/reactions agent-scoped. Add a `project_event_bindings` table that links projects to agent-owned definitions. This avoids "polluting" the schedule/reaction tables.

**Resolution: Column approach.** The binding layer adds an extra table and join for every project-scoped query without meaningful architectural benefit. The `project_id` column is already the established pattern on `work_assignments`, `job_definitions`, and `orchestration_jobs`. Extending it to schedules and reactions is consistent. The binding layer's stated advantage (clean separation of ownership) is undermined by the fact that schedules and reactions already carry both ownership (`agent_id`) and context (`assignment_template`) — adding `project_id` is a contextual filter, not an ownership change. The executing agent still comes from the `agent_id` column or the assignment template.

#### Conflict B: Job Model Consolidation Timing

- **Consolidate now** (Contract Architect, Option C): Merge `orchestration_jobs` into `job_definitions` in Phase 3.
- **Consolidate later** (Steward): Defer job consolidation. "Tasks are the highest-value, lowest-risk new surface. Jobs consolidation is higher risk."
- **Not addressed** (UI Composer, Lifecycle Researcher, Risk Strategist): Job consolidation is outside their scope.

**Resolution: Defer job consolidation to a follow-on sprint.** The directive's Jobs Enhancement section asks for "reassignable" jobs and cross-dashboard visibility — both achievable without merging the two job models. `JobService` already supports project-scoped queries and runs through `AssignmentService` — those cross-cutting paths work regardless of whether the definition came from `job_definitions` or `orchestration_jobs`. Consolidation is valuable (eliminates confusion) but is a schema migration on tables that drive orchestration execution. The risk of regressing running workflows and chat-triggered job runs is too high to bundle into an already large sprint. Add a deferred scope entry for a "Job Model Unification" follow-up.

#### Conflict C: Task Participation in `task_run_links` Family

- **Participate with source kind `K`** (Contract Architect): `task_items` derive a `TaskPathKey` with source kind byte `K` (for "tasK") so tasks produce canonical runs stored under `tasks/<key>/<runId>/`.
- **Participate with source kind `T`** (Steward): Mentioned `T` for Task in the canonical matrix expansion, but didn't fully commit.
- **Not addressed** (UI Composer, Lifecycle Researcher): Scoping outside their concern.

**Resolution: Source kind `K` with distinct path key.** The Contract Architect's `K` choice is deliberate: `T` is already the source kind for `TASK` in `TaskSourceKind` (representing task *runs* from plan definitions), and reusing `T` for task *items* would collide. Using `K` ("tasK" item) distinguishes the definition source from the execution family. The `TaskPathKey` hash payload becomes: version byte `0x01`, source-kind byte `K`, two-byte length, then the `task_items.id` UTF-8 bytes. This follows the exact same derivation as plans (`T`), workflows (`W`), and jobs (`J`).

#### Conflict D: `OrchestrationController` Extraction Strategy

- **Extract now** (UI Composer): Pull `ConsoleFragmentRenderer` and `ProjectConsoleFragments` out of the 8,700-line monolith before building new tabs.
- **Extract incrementally** (Steward): Don't refactor before building; extract as-needed when duplication forces it.
- **Not addressed** (other reports): Controller architecture is outside their scope.

**Resolution: Extract shared renderers, keep controller structure for now.** The UI Composer's concern about the 8,700-line file becoming unmaintainable is valid. But a full extraction before Phase 1 adds schedule risk (wiring churn, regression surface) without delivering user-visible value. The pragmatic middle: extract stateless `ConsoleFragmentRenderer` helper class with shared methods (status badges, row templates, counter cards, loading placeholders) as part of Phase 1. New project-scoped fragment endpoints can live inside `OrchestrationController` initially (matching how agent-scoped endpoints live there now). If the file grows past ~10,000 lines after Phases 1-3, extract `ProjectConsoleFragments` as a follow-on. This balances maintainability and schedule risk.

### 3. Omissions and Weaknesses

#### In My Report (Project Context and Workspace Steward)

- **No specific repository query signatures.** I identified the need for project-scoped queries but didn't provide method names or parameter shapes.
- **No task run identity detail for the new Task entity.** I mentioned tasks participating in the task-run identity matrix but didn't specify the source kind byte or the `TaskPathKey` hash payload.
- **Under-explored the two-job-model tension.** I noted it but didn't provide the detailed comparison the Contract Architect did.
- **No feature flag consideration.** The existing `magenta.features.schedules-enabled` and `reactions-enabled` flags are a built-in rollout seam I didn't mention.
- **Weak on validation strategy.** I provided phasing but not per-phase test coverage expectations.

#### In Unified Contract Architect (Persistence)

- **Under-specified the materialization interaction.** The report focuses on data models but doesn't address how project chat or project files consume materialization locations.
- **No SimplyPages component reuse strategy.** The rendering contract is purely about record shapes, not about fragment composition or HTMX host isolation.
- **Optimistic about job consolidation risk.** Merging `orchestration_jobs` into `job_definitions` touches the execution paths that drive all running orchestration. The risk assessment is thinner than warranted.
- **Missing the project chat authorization question.** The `ConversationSurface.PROJECT` proposal is clear but doesn't address membership gating for chat access.

#### In Operational UI Composer (Rendering)

- **No schema or migration analysis.** The report focuses on fragment rendering and host architecture but doesn't address what backend changes enable the fragments.
- **Binding layer for schedules/reactions adds unnecessary complexity.** The `project_event_bindings` table is a solution in search of a problem — a `project_id` column is mechanically simpler and architecturally consistent.
- **Over-commits to `ConsoleFragmentRenderer` extraction before Phase 1.** This refactoring is valuable but creates schedule risk as a prerequisite.
- **Weak on reassignment UI flow.** The report covers queue/task/job rendering but doesn't detail the reassignment control flow (confirmation modal, agent selector, post-reassign OOB refresh).

#### In Automation Lifecycle Researcher (Runtime)

- **No task pinning mechanics.** The report covers reassignment, cancellation, and scheduling deeply but doesn't address what "pinning" means for task lifecycle.
- **Under-specified the task submission path.** How does a pinned task become a running assignment? The report references `AssignmentType.TASK` but doesn't trace the full submission flow.
- **No project Files tab analysis.** The Files tab is outside the lifecycle scope but interacts with materialization lifecycle — and the report doesn't address this boundary.
- **Mentions `task_pins` table but doesn't define its schema.** The concept is raised but not specified.

#### In Delivery Risk Strategist (Phasing)

- **Phase ordering has a tension.** Phase 4 (Tasks) depends on Phase 3 (Jobs Reassignment) for "reassignment pattern" but Tasks is a net-new domain whose reassignment behavior doesn't depend on job reassignment mechanics. These are independent — Tasks could come before or after Jobs.
- **Underestimates schedule/reaction migration risk.** The report says "Add optional `project_id` column with backward-compatible NULL handling" but doesn't address the fact that `schedule_definitions` and `reaction_definitions` are consumed by the running scheduler and reaction engine — a migration that adds a column mid-flight could interact with in-progress firing/dispatch cycles.
- **Missing the `OrchestrationController` extraction risk.** Adding 3+ new tabs to an 8,700-line file without any extraction strategy creates codebase health risk that the report doesn't acknowledge.

### 4. Self-Corrections from My Report

After reviewing the other four reports, I revise or strengthen several positions:

**a) Task source kind should be `K`, not `T`.** The Contract Architect correctly identifies that `T` is already the source kind byte for task *runs* from plan definitions. A new lightweight task item needs a distinct byte to avoid `TaskPathKey` hash collisions. Using `K` (for "tasK" item) is correct.

**b) Project queue should include task items, not just assignments.** My report framed the Queue tab as assignment-only. The UI Composer and Contract Architect correctly argue for a unified `OperationalItem` projection that includes assignments, tasks, and jobs in a single sortable table. This matches the directive's "queue ordering, priorities" language and makes the Queue tab the definitive project health surface.

**c) Feature flags are a critical rollout seam I omitted.** The existing `magenta.features.schedules-enabled` and `magenta.features.reactions-enabled` flags (both defaulting to `false`) let the Schedules and Events tabs be built and merged without exposing half-finished surfaces. The Delivery Risk Strategist correctly identifies this.

**d) The `OrchestrationController` size problem needs acknowledgment, even if full extraction is premature.** At 8,700 lines with 3+ new tabs coming, extracting shared renderer helpers is a pragmatic Phase 0 activity, not an optional cleanup. The UI Composer is right to flag this even though full controller extraction can wait.

**e) Project chat API contract should be explicit in a specification file, not just summarized.** My report mentions designing the contract but doesn't say where. The Contract Architect and Delivery Risk Strategist both imply this should go into `.internal-dev/specifications/`. A new `project-chat-api.md` specification is the right home.

### 5. Best Ideas from Each Report

| Report | Best Unique Contribution |
|---|---|
| **Steward** | Materialization-as-agent-scoped invariant; "directory initialized to spec" means project carries declarative spec applied at provisioning |
| **Contract Architect** | `OperationalItem` record as unified render-time projection; `K` source kind for task items; `AssignmentQueryParams` as unified query builder |
| **UI Composer** | `ConsoleFragmentRenderer` extraction; independent sub-host OOB targets; visual cron editor UX pattern; polymorphic detail renderer by item `kind` |
| **Lifecycle Researcher** | Revoke-and-clone reassignment with `REASSIGNED_FROM` lineage; `REASSIGNED` terminal status; operation-key idempotency on reassignment; reassignment-state matrix (QUEUED→fresh, RUNNING→force-interrupt+replay, TERMINAL→replay) |
| **Risk Strategist** | Feature flags as rollout seams; Phase 1 zero-schema-change proof; validation strategy matrix per phase; `OrchestrationController` growth risk |

## Options

### Option S: Synthesized Path (Recommended)

Take the strongest position from each report and resolve the four conflicts above with explicit rationale.

**Phase 0 — Foundation (contract artifact only, no code):**
- Write `specifications/unified-contracts.md` codifying the entity projection matrix, `OperationalItem` record shape, `AssignmentQueryParams`, dual-scope query contract, and task/job/assignment distinction
- Write `specifications/project-chat-api.md` with `ConversationSurface.PROJECT`, route shapes, context binding contract, membership authorization model
- Decide schema changes: `project_id` on `schedule_definitions` and `reaction_definitions` (column approach); `task_items` table with `K` source kind; `REASSIGNED` terminal status; index `project_id` on all relevant tables

**Phase 1 — Project Queue Tab (zero schema changes):**
- Add `findAssignmentsByProject()`, `findHistoryAssignmentsByProject()` to `OrchestrationRuntimeRepository`
- Add `queueAssignmentsByProject()`, `historyAssignmentsByProject()`, `cancelProjectAssignment()` to `AssignmentService` with project-membership gating
- Extract `ConsoleFragmentRenderer` stateless helper (status badges, operational rows, counter cards)
- Add `GET /projects/_detail/{projectId}/queue` and lifecycle endpoints to `OrchestrationController`
- Reuse `renderAssignmentList` pattern with project-scoped route paths; shared renderer with parameterized OOB targets
- Validate: repository/service/controller tests + bounded startup + Playwright visual critique

**Phase 2 — Project Tasks Domain (new `task_items` table, migration 014):**
- `task_items` table: `id, project_id, title, description, pinned, priority, status, assigned_agent_id, created_at, updated_at`
- `TaskService` with CRUD, pin/unpin, assign/reassign lifecycle
- Task submission creates `WorkAssignment` with `assignmentType=TASK_ASSIGNMENT` (new enum value)
- Tasks appear in project queue (via unified query) and agent queue (via `assigned_agent_id`)
- `TaskPathKey` derivation with source kind byte `K`
- Project Tasks tab with inline CRUD, pin toggle, agent selector, status tracking
- Validate: migration integrity, service/repository tests, controller tests, bounded startup, Playwright

**Phase 3 — Jobs Enhancement:**
- `reassign()` on `JobService` using revoke-and-clone pattern with `REASSIGNED_FROM` lineage
- Project-scoped job run history query
- Lifecycle controls (cancel, pause, resume for job-spawned assignments) from project surface
- Enriched project Jobs tab with run drill-down, transcript access, reassign button
- Validate: lifecycle tests (QUEUED, RUNNING, TERMINAL reassignment states), cross-dashboard visibility, Playwright

**Phase 4 — Project Schedules & Events (migration 015):**
- Add `project_id TEXT` to `schedule_definitions` and `reaction_definitions`
- `ScheduleService.findByProject()` and `EventReactionService.findByProject()`
- Visual cron editor (SimplyPages fragment with six dropdowns + timezone selector)
- Event reaction configuration form
- Project Schedules tab and Project Events tab with project-scoped listing, enable/disable, simulation preview, firing/dispatch audit
- Feature flag gating: `magenta.features.schedules-enabled` and `reactions-enabled`
- Validate: migration upgrade + fresh, feature flag toggles, service tests, controller fragment tests, bounded startup, Playwright including flag-disabled unavailable state

**Phase 5 — Project Files Enhancement:**
- Project directory spec template (JSON) stored on `projects` table
- Applied per-agent at materialization provisioning time via `AgentMaterializationService`
- Read-only enforcement hardening on project file browser
- Validate: template application, generation-conflict behavior, read-only rejection of writes, Playwright visual with spec structure

**Phase 6 — Project Chat (API contract documented; implementation deferred):**
- Register `ConversationSurface.PROJECT` enum value
- Document `POST /api/projects/{projectId}/chat/stream` contract in specifications
- Membership-gated access design
- Project context prompt assembly design (binding capture with materialization context)
- Placeholder chat host in project console: "Project chat is coming soon"
- No backend chat implementation in this sprint per directive

### Option A: Contract-First (all specs before any code)

Write every specification, design every API, and resolve every open question before implementing anything.

**Pros:** Zero rework risk; all decisions have explicit rationale.
**Cons:** Delays user-visible progress; specifications without implementation feedback drift.

### Option B: Queue + Tasks First (skip Files, Jobs, Automation in initial sprint)

Build only Phase 0 + Phase 1 + Phase 2 in this sprint. Defer Phases 3-6.

**Pros:** Smallest scope; highest-value surfaces first.
**Cons:** The job/schedule/event infrastructure is the backbone of project automation and leaving it for later means the project console is mostly read-only for longer.

## Trade-offs

1. **Column vs. Binding Layer for Schedules/Reactions.** The binding layer is conceptually cleaner (agent owns, project observes) but adds a join table and more infrastructure for the same query outcome. The column approach (`project_id` nullable on existing tables) is simpler and already the established pattern on `work_assignments`. **Verdict: Column approach.**

2. **Job Model Consolidation Now vs. Later.** Consolidating reduces long-term confusion but risks breaking running orchestration during an already large sprint. **Verdict: Defer.** The sprint delivers reassignment and cross-dashboard visibility through the existing dual-model architecture. Consolidation becomes a follow-on sprint with its own risk budget.

3. **`OrchestrationController` Extraction Now vs. Incrementally.** Full extraction before Phase 1 adds schedule risk. No extraction at all makes an 8,700-line file become a 12,000+ line file. **Verdict: Extract shared renderers only in Phase 0.** Stateless `ConsoleFragmentRenderer` with no wiring dependencies. Defer controller extraction.

4. **Task Source Kind `K` vs. `T`.** `T` collides with the existing plan-task source kind used for `plan_definitions`-derived runs. `K` is unused and unambiguous. **Verdict: `K`.**

5. **Phase Ordering: Tasks Before or After Jobs Enhancement.** The Contract Architect says Jobs before Tasks. The Risk Strategist says Tasks before Jobs. Neither dependency is real — Tasks don't depend on job reassignment mechanics and job reassignment doesn't depend on the Task domain. **Verdict: Tasks before Jobs.** Tasks are a net-new feature surface with higher user impact. Jobs enhancement is an incremental improvement to an existing surface. Build the new thing first, enhance the existing thing second.

## Open Questions

1. **Should `task_items` participate in `task_run_native_links` from the start, or only when a task is executed?** The canonical task-run identity matrix already covers plans, workflows, and jobs. Adding tasks means adding a fourth row to the matrix. The `task_run_native_links` row should be created at task-execution time (when the assignment transitions to RUNNING), not at task-definition time. This matches how plan runs work (definition exists first, run row created at execution).

2. **Can one task be assigned to multiple agents simultaneously?** This has significant implications for `task_items.assigned_agent_id` — if multi-agent, it becomes a mapping table. The simpler model is one-agent-at-a-time with reassignment. Multi-agent assignment can be a deferred feature.

3. **Should project schedules fire if the executing agent is disabled?** The Automation Lifecycle Researcher raises this and recommends the firing attempt fails with an audit record. This is consistent with how agent-scoped schedules work (they don't check agent liveness) — the assignment creation would fail, generating an audit record.

4. **What happens to project-scoped schedules when the project is deleted?** Following the existing `ProjectService.requireProjectDeletionAllowed()` pattern: project deletion is blocked while pending schedule firings exist. Enabled schedules with no pending firings are soft-deleted alongside the project. This preserves audit history.

5. **Does the materialization spec template apply retroactively to existing agent materializations?** No — materializations are generation-safe. A spec template change triggers a new materialization generation for agents that reprovision. Existing materializations at older generations remain at their current structure. This matches the immutable binding pattern.

## Recommended Next Step

1. **Write the unified contract specification** (`.internal-dev/specifications/unified-contracts.md`) as the single implementation contract that all phases reference. This is the deliverable that resolves the four conflicts identified above and codifies the synthesized path. It should contain:
   - The entity projection matrix (canonical table → agent query → project query → overlap condition) for assignments, tasks, schedules, reactions, runs, transcripts, and artifacts
   - The `OperationalItem` record shape for unified rendering
   - The `AssignmentQueryParams` query builder contract
   - The `task_items` schema with `K` source kind derivation
   - The `REASSIGNED` terminal status and `REASSIGNED_FROM` lineage contract
   - The project-scoped lifecycle authorization pattern (membership + `assignment.projectId` match)
   - The schedule/reaction `project_id` column contract

2. **Execute Phase 1 (Project Queue Tab) as the proof-of-concept.** Zero schema changes, immediate user value, validates the unified contract with real rendered fragments. This is the Risk Strategist's strongest recommendation and every report supports it.

3. **Register the architectural decisions** in `.internal-dev/specifications/decisions.md`: `DECISION-202608XX-01` (unified contract as filtered projections), `DECISION-202608XX-02` (task items with source kind `K`), `DECISION-202608XX-03` (revoke-and-clone reassignment), `DECISION-202608XX-04` (project chat surface deferred to follow-on).
