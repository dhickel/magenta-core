
## Prompt

Cross-review all four prior reports — Operational Surface and SimplyPages Composer, Unified Contract Architect, Project Context Steward, Automation Lifecycle Researcher, and Delivery Risk Strategist — against each other and against the sprint directive. Identify convergences, divergences, blind spots, feasibility gaps, and unresolved design tensions. Synthesize a coherent, actionable recommended path.

## Source

All four submitted reports plus the authoritative specification store, knowledge files, and source code inspected during the exploration phase (`.internal-dev/specifications/architecture.md`, `services.md`, `api.md`, `web.md`, `simplypages.md`, `service-graph.md`, `decisions.md`; `OrchestrationController.java`, `ProjectController.java`, `WorkAssignment.java`, `AssignmentService.java`, `JobService.java`, `ScheduleService.java`, `EventReactionService.java`; knowledge files across `.internal-dev/knowledge/`).

## Findings

### 1. Strongest Convergences (Five-Independent-Report Consensus)

**Queue tab must be Phase 1.** All five reports independently conclude that the Project Queue tab is the correct starting point. The reasons converge: (a) `WorkAssignment` already carries `projectId` — zero schema change needed, (b) `AssignmentService.activeSummariesForProject()` already exists, (c) it proves the unified-contract filter pattern that all subsequent tabs depend on, (d) it delivers immediate operational value by answering "what's running under this project?" The Delivery Risk Strategist calls this "the lowest-risk, highest-validity starting point."

**Project chat is API-contract-now, implementation-later.** Every report aligns with the directive's explicit deferral. The consensus API shape: `ConversationSurface.PROJECT` enum value, `POST /api/projects/{projectId}/chat/stream` mirroring existing SSE streaming patterns, project-context prompt augmentation via immutable context bindings, and membership-gated access. No report proposes implementing the full chat backend in this sprint.

**Cross-cutting views are filtered projections, not separate stores.** All reports cite the architecture's settled rule: assignments, runs, schedules, reactions, and transcripts each have a single canonical table. Agent queue = `WHERE agent_id = X`. Project queue = `WHERE project_id = Y`. Overlap = `WHERE agent_id = X AND project_id = Y`. No report proposes a parallel store.

**HTMX-first, SSE only for chat.** All reports affirm the policy: new tabs use HTMX fragments and OOB swaps. The only justified JavaScript is for project chat SSE streaming (matching the existing `system-chat.js` pattern). Schedule/reaction simulation, queue controls, task editing, and file browsing are all HTMX.

**Tasks need a new domain concept.** All reports agree "task" as described in the directive (user-defined, pinnable, assignable, schedulable, distinct from AI-generated plans) does not exist in the codebase. `plan_definitions` serves AI-generated plans; `job_definitions` serves orchestrated multi-work-item containers. A new entity is needed.

**Projects have no filesystem root — settled invariant.** All reports treat this as an unchangeable architectural constraint. The Project Files tab is exclusively a read-only materialization browser. Project Chat must bind through an agent's workspace.

### 2. Key Divergences That Need Resolution

#### Divergence A: Schedule/Reaction Project-Scoping Mechanism

The reports split on how to make schedules and reactions project-scoped:

| Report | Recommendation |
|---|---|
| Operational Surface Composer | Binding layer — new `project_event_bindings` table, no schema changes to hardened schedule/reaction tables |
| Unified Contract Architect | Add `project_id` column to `schedule_definitions` and `reaction_definitions` (Option C) |
| Project Context Steward | Add `project_id` column (D1), explicitly rejecting a parallel domain |
| Automation Lifecycle Researcher | Add `project_id` column (Option 1), calls it "most direct, most aligned" |
| Delivery Risk Strategist | Add `project_id` column, notes this is a "cross-cutting change touching: migration, records, services, routes, fragments" |

**Analysis:** Four reports favor adding `project_id` columns. The binding-layer approach (separate table) from the Operational Surface Composer was based on a concern about "hardened" tables — but the schedule/reaction tables have already been migrated multiple times (migrations 008, 009, 010) and the architecture explicitly supports additive column migrations. The `project_id` column approach is simpler, more consistent with how `work_assignments` already carries both `agentId` and `projectId`, and enables the same "filtered projection" pattern the sprint requires.

**Resolution:** Add `project_id` columns. This is the majority recommendation and aligns with the `work_assignments` precedent. The binding layer adds unnecessary indirection.

#### Divergence B: Project Console Host Architecture

| Report | Recommendation |
|---|---|
| Operational Surface Composer | Independent sub-hosts per tab: `#project-queue-host`, `#project-chat-host`, etc., each with sibling list-detail panels |
| Project Context Steward | Option C (evolutionary) — start inside existing `#project-tab-panel`, commit to refactor later |
| Delivery Risk Strategist | "Each new tab gets its own stable host ID within `#project-tab-panel`" — hybrid, sub-hosts inside the tab panel |
| Unified Contract Architect | Less prescriptive on host architecture |
| Automation Lifecycle Researcher | Less prescriptive on host architecture |

**Analysis:** The Operational Surface Composer recommends the most aggressive architecture — full independent hosts mirroring the agent console. The Project Context Steward and Delivery Risk Strategist recommend starting simpler (sub-targets within the existing panel) and evolving. The key insight from the Delivery Risk Strategist: "A queue cancellation OOB-refreshes `#project-queue-panel`, not the full tab panel." This implies sub-hosts exist but are scoped within tabs.

**Resolution:** Hybrid approach. New tabs (Queue, Chat, Tasks, Events, Schedules) use stable sub-host IDs within `#project-tab-panel` (e.g., `#project-queue-list`, `#project-queue-detail`). Existing tabs (Dashboard, Members, Files, Jobs, Outputs, Settings) keep their current placement. This avoids refactoring the existing six tabs while giving new tabs the stale-response isolation they need. The list-detail sub-panel pattern (sibling list and inspector) should be used for Queue, Tasks, and Jobs where row-click expansion is the primary interaction. The Operational Surface Composer's full-independent-host vision should be documented as the target architecture for a later console-wide refactor, not attempted in this sprint.

#### Divergence C: Task Pinning Mechanism

| Report | Recommendation |
|---|---|
| Operational Surface Composer | New `ProjectTask` entity with `projectId` and `pinned` boolean on the task itself |
| Unified Contract Architect | New `task_items` table with `project_id`, `pinned` flag, `priority` |
| Automation Lifecycle Researcher | Separate `task_pins` table: `(project_id, definition_kind, definition_id, revision, pinned_at, pinned_by)` — revision-qualified pinning |
| Project Context Steward | Junction table `project_task_pins(projectId, planId, priority)` |

**Analysis:** The Automation Lifecycle Researcher makes the strongest case for a separate `task_pins` table with revision-qualified identity: "An edit to a pinned task creates a new revision, but the pin binding captures the specific revision at pin time." This mirrors how schedule/reaction definitions snapshot at execution time and how materializations are generation-safe. However, tasks in the sprint directive are lightweight user work items (title, description, priority, pinned, assignable) — they are NOT the same as AI-generated `plan_definitions` with multi-revision snapshots. If tasks are simple enough that edits are in-place overwrites (no revision history needed), a `pinned` boolean column on a new `task_items` table is sufficient.

**Resolution:** For the initial sprint, a `pinned` boolean on `task_items` is adequate. Tasks are lightweight. If future requirements demand revision tracking, the migration path to a `task_pins` table is additive and backward-compatible. Document this evolution path but do not build the revision-qualified pinning in this sprint.

#### Divergence D: Phase Order After Queue Tab

| Report | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|
| Operational Surface Composer | Tasks | Jobs Enhancement | Schedules/Events |
| Unified Contract Architect | Tasks | Job Consolidation | Chat |
| Project Context Steward | Backend Foundation (columns) | Queue | Tasks |
| Automation Lifecycle Researcher | Files Enhancement | Tasks | Jobs |
| Delivery Risk Strategist | Files Enhancement | Jobs Reassignment | Tasks |

**Analysis:** The divergence stems from different prioritization philosophies:
- **Tasks-first** (Operational Surface Composer, Unified Contract Architect): Tasks are the only net-new domain; building them next validates the unified contract for a new entity type and delivers the biggest missing feature.
- **Files-first** (Automation Lifecycle Researcher, Delivery Risk Strategist): Files enhancement is lower risk because materialization infrastructure already exists; it builds on Phase 1 patterns without new domain concepts.
- **Backend-first** (Project Context Steward): Add all `project_id` columns and migrations before building any UI tabs.
- **Jobs before Tasks** (Delivery Risk Strategist): Job reassignment is less new-domain work than tasks, and its reassignment pattern informs task assignment later.

**Resolution:** Tasks before Jobs, with Files in parallel. Rationale:
1. **Tasks are the highest-value new surface.** Users gain the ability to create, pin, assign, and track work items. This is the directive's most transformative feature.
2. **Tasks validate the unified contract for a new entity type.** If the unified contract works for tasks (new table, new service, new assignment type), it's proven for jobs and schedules.
3. **Jobs enhancement is primarily lifecycle operations** (reassignment) on existing infrastructure. It depends on the unified contract being proven, which Phase 1 (Queue) establishes.
4. **Files enhancement can run in parallel** with Tasks since it uses different services with no shared persistence.
5. **Schedules/Events come last** because they require the largest cross-cutting schema change. Doing them after Tasks and Jobs proves the project-scoped query pattern on lower-risk surfaces first.

### 3. Critical Omissions Across All Reports

**No report fully addresses the `orchestration_jobs` vs `job_definitions` duality.** The Unified Contract Architect flags it as needing reconciliation but defers it. The Automation Lifecycle Researcher notes their existence but doesn't recommend a path. The Delivery Risk Strategist doesn't mention it. The operational consequence: the "Jobs enhancement" phase must either pick one model to enhance or reconcile them. If left unresolved, any project jobs tab must query two different tables through two different services, doubling the fragment-rendering complexity.

**No report addresses task-to-plan promotion.** When a pinned task is assigned to an agent and the agent's chat generates a plan from it, how does the task-tracker know the plan was created? Is there a `task_id` on `plan_definitions`? Or does the task remain "OPEN" until the agent manually marks it complete? This is the bridge between the user-facing task system and the agent-facing execution system — without it, tasks become dead-end tracking records.

**No report specifies the visual schedule editor design in detail.** All mention it, but none specify whether it's a cron expression builder (six dropdowns + timezone), a calendar picker for one-time schedules, or a timeline view. The Delivery Risk Strategist is closest with "cron editor" but doesn't design the interaction.

**No report addresses project-scoped queue sort order.** When the project queue displays assignments from multiple agents, what is the default sort? Per-agent priority? Global priority interleaved? Chronological across agents? The agent queue sorts by `priority DESC, created_at ASC` — this breaks when multiple agents' assignments are in one list because priority is per-agent, not globally meaningful.

**No report addresses the "directory initialized to spec on project creation" requirement in detail.** The Project Context Steward mentions it as a tension: "materialization is agent-scoped — a project cannot create a materialization." The resolution is conceptually sound but no report specifies the schema format, how it's applied at materialization provisioning time, or how it handles conflicts when an agent already has content in their materialization.

### 4. Feasibility Assessment

**High confidence (immediately implementable):**
- Project Queue tab: zero schema migration, existing queries, proven rendering pattern
- Project queue lifecycle (cancel/pause/resume): existing `AssignmentService` methods gated by membership check
- Read-only transcript/diagnostics in project context: existing queries with project-membership validation
- `ConversationSurface.PROJECT` enum registration: additive enum change, no backend chat implementation
- Project Files enhancement (spec template): existing materialization infrastructure, template is new metadata

**Medium confidence (design choices needed before implementation):**
- Task domain: entity shape settled, but task-to-plan promotion bridge isn't designed
- Job reassignment: revoke-and-clone approach is sound, but "can a RUNNING job be reassigned?" is unresolved
- Schedule/reaction `project_id` column: mechanically straightforward (additive migration), but authorization model (who can edit a project-scoped schedule?) is unclear
- Shared fragment rendering: parameterized targets work, but OOB target isolation must be carefully reviewed per tab

**High risk (needs explicit design decision before coding):**
- `orchestration_jobs` vs `job_definitions` consolidation: which model wins? Both have active consumers. Consolidation touches the orchestration runner, which is the most critical runtime path.
- Project Chat surface authorization: the open-alpha posture has no authentication. Membership-gating for chat needs `ProjectService.isMember()` but the current chat controllers don't have project-awareness.
- Schedule/reaction execution-agent model: if a project-scoped schedule fires, which agent executes it? The template's explicit `agentId`? Arbitrary project member? The project owner agent?

## Options

### Option A: Conservative Queue-Only First (Lowest Risk)

Deliver only the Project Queue tab as the entire sprint scope, stacking the remaining six capabilities as deferred phases. This delivers one fully validated surface with the unified contract proven, but postpones all new domain work.

**Pros:** Fastest time-to-value. Proves the architecture. Minimal risk. Can be demoed immediately.
**Cons:** Doesn't deliver the sprint's broader vision. Leaves users asking "where are tasks, schedules, chat?"

### Option B: Full-Breadth Minimum Depth (Broadest Surface)

Deliver every tab as a minimal viable shell: Queue shows assignments (complete), Files shows spec-initialized directories, Tasks has CRUD but no plan-promotion bridge, Jobs has reassignment but no running-job policy, Schedules/Events show agent-scoped items filtered by project, Chat is a documented API contract placeholder. Every tab exists but some are shallow.

**Pros:** Users see the full console vision. Each tab can be deepened independently later. Project console matches user expectations.
**Cons:** Some tabs are "hollow" — users click Chat and see a placeholder. Schedule/Events show agent-scoped data confusingly labeled as "project."

### Option C: Depth-First Priority Order (Recommended)

Deliver Queue, Tasks, and Files as Phase A (full depth). Then Jobs, Schedules, and Events as Phase B (full depth). Chat placeholder throughout. Each phase delivers complete, validated surfaces.

**Pros:** Every delivered tab is fully functional. No hollow surfaces. Natural dependency order (Tasks inform Jobs; Queue informs all). Each phase is independently demoable.
**Cons:** Schedules/Events are phase B — users wait longer for automation features. Larger Phase A scope means longer before any visible progress.

### Option D: Automation-Forward (Schedules/Events Early)

Deliver Schedules and Events right after Queue, treating them as the highest-value automation surface. Tasks and Jobs follow.

**Pros:** Automation is a differentiator. Schedules/Events infrastructure is already mature — UI is the main gap.
**Cons:** Schedule/reaction `project_id` migration is the highest-risk schema change. Doing it before the unified contract is proven on lower-risk surfaces (tasks) is backwards.

## Trade-offs

### T1: `project_id` Column vs. Binding Table for Schedule/Reaction Scoping

**Columns on existing tables:** Simpler. Consistent with `work_assignments` pattern. Requires one additive migration. Enables the same `WHERE project_id = ?` filter used everywhere else. Risk: the schedule/reaction tables are deeply agent-scoped in service code; adding a nullable column requires touching every query method to ensure the column is included in reads/writes.

**Separate binding table:** No schema changes to hardened tables. Clean separation of ownership (agent still owns the definition; project just observes it). Supports multi-project binding (one schedule visible in multiple projects). Risk: adds a join to all project-scoped queries. More infrastructure for the same outcome.

**Decision:** Columns on existing tables. Four of five reports agree. The schedule/reaction tables survived migrations 008-010 without issue. The binding table pattern would be justified only if multi-project binding were a product requirement, which the sprint directive does not specify.

### T2: Tasks as New Table vs. Tasks as Plan Subtype

**New table:** Clean separation — lightweight user work items don't carry plan baggage (steps, field definitions, JSON snapshots, output declarations). Simpler CRUD. Simpler query surface. Risk: eventual convergence with plans may create awkward "is this a task or a plan?" moments in the UI. Requires a bridge column (`plan_id` on `task_items`) for task-to-plan tracking.

**Plan subtype:** Reuses existing plan infrastructure — one less table, one less service, one less set of tests. Plans can already be saved and assigned. Risk: `plan_definitions` is a wide table designed for AI-generated content. A simple "Check logs" task would carry nulls for most plan-specific columns. Confusing for users who see "Plan type: TASK" in a plan editor that's designed for multi-step AI plans.

**Decision:** New table. All five reports agree, and the directive's task concept is explicitly distinct from plans. The bridge column (`plan_id`) is a small cost for clean separation.

### T3: Shared Renderers vs. Duplicated Fragment Methods

**Shared renderers:** One `QueueRowRenderer` used by both agent and project controllers. Parameterized by context (AGENT/PROJECT) to emit correct `hx-target` values. Reduces code duplication. Risk: OOB target collision if two surfaces are open simultaneously and a mutation fires a global OOB refresh. Mitigated by scoping OOB targets to the active surface's host IDs.

**Duplicated methods:** Separate render paths for agent vs. project console. Safer for independent HTMX hosts — no shared state or target ambiguity. Risk: identical markup maintained in two places, drift over time, harder to apply visual changes consistently.

**Decision:** Shared renderers with parameterized targets. The Automation Lifecycle Researcher's analysis is correct: "Shared Java renderers with parameterized HTMX targets. SimplyPages `SlotKey` instances for static shared data and per-request dynamic row rendering." The Delivery Risk Strategist's validation approach (verifying OOB refresh targets the correct host) catches the main risk.

### T4: Full Independent Hosts vs. Sub-Targets Within Tabs

**Full independent hosts:** Each tab is an independent HTMX host (`#project-queue-host`, `#project-tasks-host`, etc.) like the agent console. Maximum stale-response isolation. No cross-tab contamination. Risk: requires refactoring all six existing project tabs which currently use `#project-tab-panel`. Large blast radius. Risk of breaking existing Dashboard, Members, Settings, etc.

**Sub-targets within existing shell:** New tabs add stable sub-host IDs inside `#project-tab-panel`. Existing tabs unchanged. Phased migration path to full independent hosts. Risk: inconsistent architecture across tabs within the same console. More discipline needed to maintain isolation.

**Decision:** Sub-targets within existing shell for this sprint. Document full-host migration as `horizon-ideas.md`. The Project Context Steward's Option C guidance applies: "Phase the work so early phases add new tabs inside the existing shell, and a later phase refactors the project console to the full independent-host pattern once all tabs exist and the visual layout is proven."

### T5: Phase A Scope — Queue+Taska+Files vs. Queue+Files+Taska

**Queue+Taska+Files (recommended):** Delivers the three highest-value, lowest-risk tabs in one phase. Tasks validates the unified contract for a new entity type. Files is low-risk enhancement of existing infrastructure. Three fully functional tabs at Phase A completion.

**Queue+Files+Taska:** Files is even lower risk than Tasks. Delivers visible progress faster. Risk: Tasks depends on the unified contract being proven, which Queue alone proves — Files doesn't add contract validation. Tasks-first gives more architectural confidence for Phase B.

**Decision:** Both orders work. Preference for Tasks before Files within Phase A because Tasks proves the contract for a net-new entity, which Jobs and Schedules/Events also need. Files can overlap because it's in a separate service domain.

### T6: Job Reassignment — Revoke-and-Clone vs. In-Place Agent Mutation

**Revoke-and-clone:** Old assignment → `REASSIGNED` terminal status. New assignment → `REASSIGNED_FROM` lineage. New agent, new run, new workspace. Preserves immutable context bindings. Consistent with retry/replay pattern. Risk: more rows, more runs. Workspace artifacts from old agent remain in old agent's workspace.

**In-place mutation:** CAS the `agentId` on the assignment. Same run, same workspace? But workspace is bound to agent — changing agent without changing workspace would break descriptor-based confinement. Risk: violates immutable binding semantics (`DECISION-20260727-01`). Context binding becomes ambiguous.

**Decision:** Revoke-and-clone. The Automation Lifecycle Researcher's analysis is definitive: in-place mutation violates immutable context bindings. The existing retry/replay infrastructure (lineage links, fresh runs, new context capture) is directly reusable.

### T7: Schedule Execution Agent — Template `agentId` vs. Member Pool

**Template `agentId`:** Schedule fires → `ENQUEUE_ASSIGNMENT` using the assignment template's explicit `agentId` field. Same as agent-scoped schedules today. Simple, predictable, no new selection logic. Risk: if the specified agent is disabled, the firing fails.

**Member pool:** Schedule fires → scheduler picks an available member agent from the project. More robust (no single point of failure). Risk: new agent-selection logic needed. Which agent to pick? Round-robin? Least-busy? How to track "this firing went to agent X"?

**Decision:** Template `agentId`. The existing schedule infrastructure already supports this. Member-pool selection is a significant new feature (agent availability tracking, selection policy, audit) that's out of scope for this sprint.

## Open Questions

1. **Which job model survives consolidation?** `job_definitions` has `projectId`, `workspaceType/key`, work items, and run tracking. `orchestration_jobs` has `owner_agent_id` and ordered work items with per-item plan/workflow IDs. The directive says "Jobs are reassignable tasks that agents perform" — `orchestration_jobs` fits better (agent-owned, contains work items). But `job_definitions` has the richer schema. The sprint needs an explicit decision: evolve `job_definitions` to add agent ownership and work-item execution state, or consolidate `orchestration_jobs` into `job_definitions`.

2. **Should task-to-plan promotion be automatic or manual?** When an agent executes a task assignment and creates a plan, should the task status auto-update to "IN_PROGRESS" with a link to the plan? Or should the agent explicitly mark the task as in-progress? The former requires a `task_id` → `plan_id` / `assignment_id` bridge. The latter is simpler but creates manual tracking burden.

3. **What is the project queue default sort order when multiple agents' assignments are displayed?** Sort by `priority DESC, created_at ASC` within each agent group, or globally? Per-agent grouping is more readable but means a low-priority item from agent A might appear above a high-priority item from agent B. The sprint needs a product decision.

4. **Can a RUNNING job be reassigned, or only QUEUED/WAITING jobs?** The Automation Lifecycle Researcher's revoke-and-clone approach works for both, but interrupting a running job has semantic consequences (lost partial progress, new run, new workspace). The sprint directive doesn't specify.

5. **Who owns a project-scoped schedule?** The project owner agent? Any member agent? The agent specified in the assignment template? The execution model is: schedule fires → `ENQUEUE_ASSIGNMENT` with template → assignment gets created for `template.agentId`. If nobody "owns" the schedule for editing purposes, who can modify or disable it? The agent console's pattern is "the agent who owns the schedule can edit it." Project-scoped schedules need an explicit ownership rule.

6. **What is the spec-template format for project directory initialization?** JSON manifest? Hardcoded `projects.specJson` column? SimplyPages component path? The materialization provisioner needs to consume this format. The sprint directive doesn't specify.

7. **Should the project queue show only non-terminal assignments by default?** The agent queue shows all. For project surfaces with potentially hundreds of historical assignments across multiple agents, default-filtering to active/running reduces noise. But the agent console precedent is "show all." Needs explicit product decision.

8. **Does the project console need its own version of the "Run Groups" panel?** The agent console has `#agent-run-groups` showing task/workflow/job run history. Should the project console show a cross-agent run history filtered by project? This would reuse `RunSummaryService` with a project-scoped filter — straightforward backend-wise but a significant UI surface.

## Recommended Next Step

**Adopt Option C (Depth-First Priority Order) with the clarified design decisions from the convergence analysis:**

### Phase A: Queue + Tasks + Files (Foundation Sprint)

**Pre-phase (zero-risk, zero-schema):**
- Register `ConversationSurface.PROJECT` enum value (additive, no behavior change)
- Add `AssignmentType.TASK` to the enum (additive, no behavior change)
- Document project chat API contract in `deferred-features.md` (no code)

**A1 — Project Queue Tab (zero schema migration):**
- Add `findAssignmentsByProject(projectId)` and `findHistoryAssignmentsByProject(projectId)` to `OrchestrationRuntimeRepository`
- Add `queueAssignmentsByProject()` and `cancelProjectAssignment()` to `AssignmentService` (with membership verification)
- Mount `#project-queue-list` and `#project-queue-detail` sub-hosts in project console
- Shared `QueueRowRenderer` with `AGENT`/`PROJECT` context parameter
- Cancel/pause/resume/diagnostics/transcript all reuse existing patterns with project-membership gate
- Default sort: per-agent groups with priority ordering within each group

**A2 — Project Tasks Tab (new table, new domain):**
- Migration 014: `task_items` table (`id, project_id, title, description, pinned, priority, status, assigned_agent_id, plan_id, created_at, updated_at`)
- `TaskService` CRUD with project membership gate
- Task assignment creates `WorkAssignment` with `assignmentType=TASK` and `projectId`
- `pinned` boolean column (revision-qualified pinning deferred)
- Task-to-plan promotion bridge: `task_items.plan_id` nullable FK to `plan_definitions` (set when agent creates a plan from a task assignment)
- Task status lifecycle: `OPEN → ASSIGNED → IN_PROGRESS → COMPLETED | CANCELLED`
- Project Tasks tab: task list + inline create/edit form + assignment selector

**A3 — Project Files Enhancement (no new schema, spec-template metadata):**
- Add `directory_spec_json` column to `projects` table (nullable TEXT, stores JSON array of `{ "path": "relative/path", "type": "file" | "directory", "content": "optional template content" }`)
- `AgentMaterializationService.provision()` accepts optional spec template and applies it when creating the materialization directory structure
- No behavior change for existing materializations; template only applies at initial provisioning
- Read-only enforcement already exists — no change needed

**Phase A validation:** Repository tests for project-filtered queries, service tests for task lifecycle and membership gates, controller tests for fragment routes, bounded startup, Playwright functional and visual critique for Queue, Tasks, and Files tabs.

### Phase B: Jobs + Schedules + Events (Automation Sprint)

**B1 — Job Model Resolution:** Decide `orchestration_jobs` vs `job_definitions` consolidation path before coding. The Automation Lifecycle Researcher's revoke-and-clone reassignment approach applies regardless of which model survives.

**B2 — Jobs Enhancement:**
- Reassignment via revoke-and-clone: old assignment → `REASSIGNED`, new assignment → `REASSIGNED_FROM` lineage
- Add `REASSIGNED` to `OrchestrationStatus` and `REASSIGNED_FROM` to `RunLineageMode`
- Cross-cutting view: job assignments with `projectId` appear in both agent and project queues (already works through A1's unified query)
- Running-job reassignment policy: block reassignment while `RUNNING`; require explicit cancel-before-reassign. Document the policy.

**B3 — Schedules & Events (schema migration):**
- Migration 015: add `project_id TEXT` to `schedule_definitions` and `reaction_definitions` (nullable, indexed)
- Add `findByProject()` to `ScheduleService` and `EventReactionService`
- Project-scoped schedule/reaction CRUD controllers under `/api/projects/{projectId}/schedules` and `/event-reactions`
- Ownership rule: only the schedule/reaction's `agent_id` can edit; project-scoped views are read-only mirrors for other project members (consistent with agent-scoped ownership model)
- Visual schedule editor: six dropdowns (minute, hour, day-of-month, month, day-of-week) + IANA timezone selector + human-readable preview + "One time" / "Recurring" radio with calendar picker for one-time
- Events tab: reaction configuration form (event type selector, filter key/value pairs, assignment template) + dispatch audit
- Mount schedule/reaction fragment hosts in project console
- Feature-gate behind existing `magenta.features.schedules-enabled` and `magenta.features.reactions-enabled` flags

**Phase B validation:** Migration upgrade + fresh tests, repository tests for project-scoped queries, service tests for schedule/reaction project scope, controller tests for fragment routes with feature-flag gating, Playwright for schedule editor interaction and reaction configuration flow.

### Architecture Decisions to Record

1. Update `decisions.md` with: task entity design, job consolidation path, schedule/reaction project-scoping via `project_id` column, project console host architecture (hybrid sub-hosts), and reassignment lifecycle (revoke-and-clone).
2. Update `architecture.md` with: project-scoped operational surfaces entry, task-run identity matrix extension for task source kind, and project chat deferred API contract.
3. Update `web.md` with: project console sub-host contracts for Queue, Tasks, Schedules, and Events tabs.
4. Update `api.md` with: project-scoped assignment query routes, task CRUD routes, project-scoped schedule/reaction routes, and project chat API contract (deferred).

### What NOT to Build in This Sprint

- **Not:** Full independent-host refactor of existing six tabs. Document as `horizon-ideas.md`.
- **Not:** Project chat backend. Document API contract in `deferred-features.md`. Placeholder host only.
- **Not:** Revision-qualified task pinning. Simple boolean pin. Document evolution path.
- **Not:** Project-scoped Run Groups panel. Deferred. Queue tab provides sufficient operational visibility.
- **Not:** Multi-agent task assignment. One task → one agent at a time. Document multi-agent as deferred.
- **Not:** Job consolidation (resolving the two models). Deferred with explicit decision in `decisions.md`. Both models work independently for now.
- **Not:** Process execution for jobs. Fails closed per `ARCH-20260720-03`.
