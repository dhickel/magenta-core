# Magenta Product Model Rectification — Planning Handoff

## Context

The Magenta project suffers pervasive terminology inconsistency and duplication: "plan" has four meanings, "task" has three, "job" has three, and parallel discriminators carry inconsistent spellings (`source_kind` vs `assignment_type` vs `native_kind` vs `run_type`). Four definition/run pairs duplicate JSON snapshot shapes. Two independent chat-persistence stacks coexist. Workspace identity triples (`workspace_type`+`workspace_key`+`workspace_identity`) are repeated on eight or more tables. The user formalized a **target product model** on 2026-08-09 (`.internal-dev/specifications/product-model.md`) as the decisive contract to bring terminology, filesystem layout, feature set, UI, and behavior inline.

The working tree adds a critical complicating factor: the repository is mid-refactor on branch **`core`**. The `exec-core-refactor-20260804` sprint (`.internal-dev/sprints/exec-core-refactor-20260804/manifest.md`) extracted a reusable `mindspice-core` turn-loop framework from Magenta, completing phases 1–11 and deleting the entire product shell in phase-05 (`8b6cdcc1`): 414 files, ~73,911 lines removed — all orchestration, skills, plan, task, project, system, job, and execution packages, plus `db/migrations/` (20 files), `SchemaMigrationRunner`, and all `static/js/*`. Phases 12–14 (tests, docs hardening, Magenta reintegration) remain **pending and unrecorded**. `magenta-app` is now a 29-file thin chat-config shell over `mindspice-core` with `sql.init.mode: never` and zero controllers, services, tools, or UI.

The full product survives only on two branches:
- **`master`** (2026-07-29) — the last stable full product
- **`sprint/20260729-163244-project-dashboard`** (2026-08-04, 18 commits ahead of master) — the **newest full app**, with the complete 11-tab project console, schedule/reaction management, system chat, agent operational console with all panels, and an `schema.sql` sha256-identical to the working tree. **All implementation evidence cited in this handoff and in the exploration review (`.internal-dev/reviews/2026-08-09-product-rectification-exploration.md`) comes from this branch.**

Deleted code is recoverable via `git show 8b6cdcc1~1:<path>` and `git show sprint/20260729-163244-project-dashboard:<path>`.

Additionally, the spec corpus (all 12 specification files) describes the master-era product; every anchor class referenced in them is deleted on `core`. No drift record exists for the largest drift event in repository history (the 2026-08-04 extraction). The target model explicitly reverses several settled architectural decisions, and rectification must supersede them explicitly rather than silently overwriting existing contracts.

## Objective

Bring the Magenta product — terminology, concept model, filesystem layout, workspace semantics, scheduling, project model, agent capability model, UI surfaces, and specification/documentation corpus — fully inline with the accepted target product model in `.internal-dev/specifications/product-model.md`. Eliminate terminology duplication, remove defunct features, enforce the new workspace/filesystem contracts (`chats/`, agent `home/`/`scripts/`/`skills/`/`agents.md`, project layered workspaces with orchestrator root), replace cron scheduling with the three-cadence model (future-date/daily/interval), add project run authority and task labels, implement the root-agent/subagent capability tier, and deliver dashboard editing surfaces for `agents.md` and `skills/`.

## Settled Decisions

These are **already settled** by the user in the accepted target product model and are not open for re-litigation during planning:

1. **Non-cron scheduling only**: future-date, daily, and interval (days/hours/minutes/seconds) cadences. No cron expressions in any user-facing surface.
2. **Replaceable project owner defaulting to `magenta`**: the default agent is the default owner; the owner is replaceable at any time.
3. **Subagent tool restriction**: subagents lack tools exposing project and core workspace interaction. Only orchestrating (root) agents hold scheduling and workspace/project-management API controls.
4. **Outputs-only sharing**: agents share files with users only through `outputs/`; users see updates in chat UI.
5. **Orchestrator root above `project/`**: the orchestrating agent's workspace root is one level above the `project/` directory. All other agents' project root is `project/`.
6. **Tasks = schema-in/out + criteria; plans reuse machinery**: the plan-definition family (tables, services, execution) underpins tasks. Tasks add schema-in, schema-out, and criteria constraints that plans do not enforce.
7. **Workflow-as-task with visual tag**: workflows are themselves tasks using the task-run lifecycle, but always displayed as "workflow" with a visual tag.
8. **Task labels**: tasks carry labels denoting state (active, scheduled, complete, etc.).
9. **Per-project work tracking**: all work in a project is tracked with the performing agent.
10. **Dashboard `agents.md` and `skills/` editing**: UI surfaces on the agent and project dashboards using file explorer and markdown editor primitives.

## Target Model Summary

The accepted model (`.internal-dev/specifications/product-model.md`, 2026-08-09) defines:

### Terminology Contract

| Term | Definition | Rules |
|------|-----------|-------|
| **workspace** | Container for chats, agents, projects | Consistently used for these three kinds only |
| **task** | Discrete executable with schema-in, schema-out, criteria | Assignable to agent or project; visual tag shows which |
| **plan** | Planning done inline in chat with an agent | Shares most task machinery except schema constraints |
| **workflow** | Node-based composition of tasks | Is itself a task, always called "workflow" with a visual tag |
| **project** | Contained workspace with assigned agents and tasks | Owns bookkeeping, run, and schedule authority |
| **agent** | Assistant identity with its own home workspace | Can be assigned to projects |
| **job** | (current-code concept) | Must be mapped into the task/workflow family; may survive as a distinct user-facing term or be unified |

UI, API payloads, docs, and code names must agree on these terms.

### Workspace & Filesystem

- **`chats/`**: top-level, one folder per chat; outputs go to `outputs/`; scripts/agent-facing files at chat root. Agents share with users only through `outputs/`; user sees updates in chat UI.
- **Agents**: each has a home workspace: `home/` (personal), `scripts/`, `skills/`, and an `agents.md` file. These layer onto any project workspace the agent works in.
- **Projects**: contained workspaces. Root: `agents.md` (describes project) + `skills/` + `scripts/`. These layer with agent files. `project/` folder holds actual project files — the root for all agents *except* the orchestrating agent. The orchestrating agent's root is one level above `project/`.
- **Outputs**: user-facing outputs live in the effective workspace's `outputs/`; agent→user sharing only through outputs.

### Task Model

- Tasks have preset format: schema in, schema out, criteria. Created in chat, assignable to agent or project (interchangeably, with visual tag).
- Plans are inline chat plans sharing most task machinery but without schema constraints.
- Task labels denote state (e.g., active, scheduled).

### Workflow Model

- Node-based composition of tasks. Is itself a task, always referred to as "workflow" with a visual tag.

### Scheduling Model

- **Not cron-based.** Three cadence types: (1) future date (one-time), (2) repeatable daily time, (3) repeatable interval with days/hours/minutes/seconds selectors.
- Agents schedule either a task or a repeatable prompt.

### Project Model

- Contained workspaces with assigned agents and tasks. Only project-assigned agents and tasks runnable.
- Every project has an **orchestrating agent (owner)**, default **`magenta`** (the default agent), replaceable. Chat-able in the drop-down.
- Orchestrating agent gets extra API controls (scheduling, workspace/project management). It is a **root agent**. Subagents lack tools to interact with the project and core workspace.
- All project work tracked with performing agent.

### UI Requirements

- Edit agent and project `agents.md` from their dashboards.
- View and edit skill directories and files.
- Use existing file explorer primitives and markdown editor.

## Constraints

- Scheduling: future-date, daily, and interval only (no cron).
- Default owner agent: `magenta`, replaceable.
- Subagents: no project/core-workspace tools; orchestrating (root) agents alone hold scheduling and workspace/project-management API controls.
- Sharing: outputs-only; user sees updates in chat UI.
- Orchestrator root: one level above `project/`; all other agents' project root = `project/`.
- Task/plan machinery reuse: plan machinery reused for tasks; schema in/out and criteria are the task differentiator.

## Current State vs. Target — Gap Map

All current-state evidence below is recovered from `sprint/20260729-163244-project-dashboard` unless prefixed "working tree." Evidence paths include table names, column names, and class/method references.

### Terminology / Concept Model

| Target | Current State | Evidence |
|--------|--------------|----------|
| **Unified task concept** (schema-in/out/criteria) with plan, workflow-as-task derivatives | `plan_definitions` (kind: `SESSION_PLAN`\|`TASK_TEMPLATE`) is the sole task-definition carrier. `work_task_definitions` is a second, independent task-definition table with no schema/criteria columns. Three task-creation pathways (PlanService, TaskService drafts, WorkTaskService). "Plan" = 4 meanings; "task" = 3; "job" = 3. | `schema.sql` ~L99-130 (`plan_definitions`), ~L1772-1802 (`work_task_definitions`); `WorkTaskService` ops `CREATE/EDIT/BIND/PROMOTE/SUBMIT/RETRY` |
| **Parallel discriminators aligned to taxonomy** | `work_assignments.assignment_type` (TASK_RUN/WORKFLOW_RUN/JOB_RUN/AGENT_MESSAGE/WAIT_FOR_MESSAGE/REPORT) vs `task_run_locations.source_kind` vs `task_run_native_links.native_kind` vs `workflow_runs.run_type` vs `run_output_artifacts.run_type` vs `skill_exact_assignments.target_type` (AGENT/PROJECT/JOB/TASK/WORKFLOW/CHAT_SESSION) — inconsistent spellings, overlapping domains | `schema.sql` L316-366 (`work_assignments`), L660-704 (`task_run_locations`), L740+ (`task_run_native_links`) |
| **Single definition/run pair** | Four definition/run pairs with parallel JSON snapshot shapes: plan, workflow, job, work_assignment. Two chat persistence stacks (`ai_chat_memory`/`ai_chat_session_metadata` vs `plan_chat_messages`). Two output tables (`run_output_artifacts` inert/legacy vs `output_publications` canonical). | `plan_definitions`+`plan_runs`, `workflow_definitions`+`workflow_runs`, `job_definitions`+`job_runs`, `work_assignments` as run carrier; `schema.sql` dual output tables |
| **Schema constraints on all task families** | Schema constraints (`inputs_json`/`outputs_json`/`validation_criteria_json`) exist only on `plan_definitions` (TASK_TEMPLATE kind). SESSION_PLAN explicitly has none. `work_task_definitions` has no schema/criteria columns. | `schema.sql` L99-130 (`plan_definitions` columns), L1772-1802 (`work_task_definitions` — no inputs/outputs/criteria columns) |
| **Clean "job" mapping** | Job has 3 meanings: `job_definitions` (project work units), `orchestration_jobs`/`orchestration_job_items` (legacy coordination), `agent_jobs` (background chat jobs). `AssignmentType.JOB_RUN` + `job_recurrences` + background `agent_jobs` are three independent systems. | `schema.sql` L60-79 (`agent_jobs`), L285-313 (`orchestration_jobs`); `job_recurrences` L611-624; `work_assignments.assignment_type` |
| **Three overlapping profile concepts** | `prompt_profile` (legacy column on `plan_definitions`) vs `WorkTypeProfileService` ("maps structured work type profile values and legacy prompt profile values") vs `plan_definitions.planning_task` — three different profile mechanisms. | `plan_definitions.prompt_profile`, `plan_definitions.planning_task`, `WorkTypeProfileService` (deleted, recoverable) |
| **`deliverables_json` legacy column** | `plan_definitions.deliverables_json` is a legacy not-null column ignored by repositories — dead schema weight. | `schema.sql` L104 (`deliverables_json text not null`) |

### Filesystem / Workspace Layout

| Target | Current State | Evidence |
|--------|--------------|----------|
| **`chats/` top-level with per-chat `outputs/`** | Current root is `workspace/chat/<id>/`; no `chats/` appears anywhere in code or schema. Historical `data/chats/` was replaced pre-2026-07-19. Chat outputs publish to `workspace/chat/<id>/outputs/`. | `WorkspacePathLayout` factories (deleted, recoverable via `git show 8b6cdcc1~1:`); `OutputArtifactService.publishChat()` → `PublicationOrigin.CHAT` (deleted); `api.md` execution selection rejects chat |
| **Agent `home/`, `scripts/`, `skills/`, `agents.md`** | Agent areas: `home`, `scripts`, `apps`, `repos`, `projects` (materializations), `tasks`, `.skills`, `outputs`. Skills directory is dot-prefixed `.skills` everywhere in spec/schema/code. No per-agent `agents.md` concept exists; AGENTS.md is resolved from descriptor-root-relative files. No agent home layering onto projects. | `ARCH-20260727-01` (nine conceptual areas); `ARCH-20260526-02` (AGENTS.md resolution); `WorkspacePathLayout`; `AgentsMdResolver` (deleted) |
| **Project layered workspace: root `agents.md`+`skills/`+`scripts/`, inner `project/`** | Projects have NO filesystem root authority. Spec: "Project association is metadata/membership only, never selecting or creating a root, link, or filesystem lease" (`ARCH-20260719-01`). Project context is materialized under agent workspaces (`v1-PROJECT-<sha256>` or `materializations/v2/<projectKey>/gen-<N>/`). | `architecture.md` ARCH-20260719-01; `projects` table has no path columns; `git_repo_url` survives; `SkillScopeDiscoveryService` reports project discovery unavailable |
| **`skills/` (not `.skills/`)** | Workspace-local skills stored as `.skills/` (dot-prefixed, hidden). Target says `skills/`. Global skill packages root is `MagentaRootProperties.skillsRoot()` (`path/skills` — unused in working tree). | `ARCH-20260727-01` lists `.skills`; `.skills/` appears in reserved-name rejection; schema check constraints reference `.skills` |
| **Orchestrator root one level above `project/`** | No root-agent tier exists. All agents use the same workspace model. | `AgentProfile` has no root flag; `AgentOperationalTools` identical per profile |

### Scheduling

| Target | Current State | Evidence |
|--------|--------------|----------|
| **Non-cron: future-date, daily, interval (days/hrs/mins/secs)** | `schedule_definitions.mode` = `CRON`\|`ONE_TIME`. `CRON` uses `cron_expression` + IANA `timezone` + `dialect_version` (`spring-6-field-v1`). No daily or interval fields anywhere. `job_recurrences` is a second independent cron model (`cron_expression`+`timezone`+`next_fire_time`). Agent tool `agentScheduleSave` is cron-only (params `cronExpression`, `timezone`). UI: `ScheduleReactionFragments` cron-only, no href references (effectively dead). | `schema.sql` L1345-1372 (`schedule_definitions`), L611-624 (`job_recurrences`); `AgentOperationalTools.agentScheduleSave()` (deleted, recoverable); `ScheduleMode` enum `{CRON, ONE_TIME}` |
| **Schedule tasks OR repeatable prompts** | Schedules enqueue `AssignmentType.JOB_RUN` only via `AssignmentTemplateParser.automationRequest`. `AssignmentType` has AGENT_MESSAGE and REPORT but no PROMPT type. | `AssignmentType` enum; `AssignmentService.create` (deleted, recoverable) |
| **Preserve fencing/operation-key machinery** | This is a **strength**, not a gap. `schedule_firings` carries `operation_key` + `request_digest`, `scheduler_claims` (persisted owner/fence), `schedule_firing_attempts` (1-3 attempts with outcomes ENQUEUED/FAILED/RECOVERED/DUPLICATE), `offset_policy` (GAP_SKIP/OVERLAP_FIRE_ONCE). Must be preserved. | `schema.sql` L1389-1411 (`schedule_firings`), L1414+ (`schedule_firing_attempts`); `ScheduleService` poller (10s fixedDelay) |

### Project / Authority

| Target | Current State | Evidence |
|--------|--------------|----------|
| **Only project-assigned agents/tasks runnable** | `AssignmentService.create` enforces `requireActiveProjectWithMember(projectId, agentId)` only when `projectId` is supplied. Unbound tasks/assignments run for any active agent. | `AssignmentService` (deleted, recoverable); runner membership recheck at lease time only when projectId present |
| **Orchestrating/root agent (default `magenta`, replaceable) with extra controls** | `projects.owner_agent_id` is a nullable "legacy compatibility" field, presentation-only ("this UI does not transfer ownership" per `docs/end-user/projects-and-workspaces.md:73`). Default agent `magenta` is seeded only in `runtime_settings.default_agent_id/default_agent_name`. No root/subagent tiering: `AgentProfile` has no root flag; identical tool sets per profile, filtered only by `approved_tool_names_json`. `AgentToolAuthorizationService.requireProjectAccess` = membership check, `requireJobAccess` = owner or member. | `projects` schema L624-638; `AgentProfile` (deleted, recoverable); `AgentProfileSeeder`; `AgentToolAuthorizationService` (deleted) |
| **Task labels (active, scheduled, ...)** | No label column on any task/definition table. Status enums exist: `OrchestrationStatus` (QUEUED/RUNNING/WAITING/PAUSED/...), `PlanStatus` (DRAFT/READY_FOR_APPROVAL/APPROVED/SAVED_TASK/EXECUTING/...), `JobRunStatus`, `WorkflowRunStatus`. Work tasks have no own status. File labels (`workspace_file_labels`) are unrelated. | `OrchestrationStatus`, `PlanStatus` enums (deleted, recoverable); `work_task_definitions` has no status column |
| **All project work tracked with performing agent** | Partial: `work_assignments.project_id` (immutable attribution) + partial indexes, `project_events` (append-only), `orchestration_events.project_id`, `run_summaries`/`run_measurements`, `audit_event` transcripts. No unified per-project "who did what" ledger; project Dashboard shows counts only. | `project_events` schema L648-656; `run_summaries`; `ProjectDashboardFragments` (deleted) |
| **Owner chat drop-down with control surface** | Project chat: `ProjectChatController /api/projects/{projectId}/chat/members` returns ALL members; `ProjectChatService` fixed read-only bounded chat. Not an owner/orchestrator surface. `SystemChatController` (per-agent) is the operator-control chat. | `ProjectChatService`, `SystemChatService` (deleted, recoverable) |

### UI

| Target | Current State | Evidence |
|--------|--------------|----------|
| **Dashboard `agents.md` editing** | Nothing exists. Zero matches for `agents.md`/`AGENTS.md` in `api/web` or `static/` on the sprint branch (one link in `api/web/CLAUDE.md`). Server-side plumbing existed (`AgentsMdResolver`, `WorkspaceMdResolver`) but was deleted. | `api/web/CLAUDE.md`; `AgentsMdResolver` (deleted) |
| **Dashboard `skills/` viewing/editing** | Agent console has no skills tab (web.md promises `#agent-skills-panel`; code has empty-state placeholder). `SkillFragments.fileViewer` (textarea + PUT text) exists. Project Files tab is read-only (materialization preview). No per-agent/per-project skills view. | `SkillFragments._files/_viewer`; `OrchestrationController` agent tabs |
| **Unified terminology in UI** | Wording clash catalog: home card "Plans & Tasks" vs nav "Plans" vs page "Plan and task template definitions"; submit form label "Plan/Workflow/Job ID" with placeholder "type an existing task, workflow, or job ID"; `/jobs` description "coordinating plans and workflows"; "Artifacts" vs "Outputs" pages; Files nav "Skills, Scripts, Apps, Home, Stored Items, Projects, Tasks, Outputs" where "Stored Items" = legacy `repos`; three schedule editor button verbs. | Home page, `/plans`, `/jobs`, `/projects/{id}` pages (sprint branch); error strings using 4 labels for workspace identity (Type/Key/Kind/Identity) |
| **De-duplicated explorer/editor surfaces** | Duplicated: file explorer 3 families + 1 hand-rolled (`SkillFragments._files`); 3 definition CRUD stacks (plan editor, project work-task editor, UI-dead `TaskController` REST); 4 schedule/reaction editor route families (one dead); run/assignment lists duplicated; 4 chat transports (ChatModule SSE — good reuse; agent-chat.js, system-chat.js, project-chat.js bespoke). | `WorkspaceExplorerFragments` (~1600 lines, raw-HTML-string based, hard-codes `/agents/_detail/{agentId}/files` prefixes — spec claims workspace-neutral); `RunOperationsFragments` vs `renderAssignmentList` vs `ProjectWorkController` queue rows |
| **Reusable primitives available** | `WorkspaceExplorerFragments` text/Markdown edit endpoints (`PUT files/text`, `POST files/markdown`); SimplyPages `Markdown` component; SimplyPages editing system (`Editable`, `EditModalBuilder`, `EditFormBuilder`, `ModuleEditHandler` — NOT used by Magenta). Hand-rolled `orch-tabs`/`orch-status-chip` instead of SimplyPages Tag/Badge (library has no Tab component). No file-tree component in library. `WorkspaceSelectionComponents`, `EntitySelectorComponents` reusable. | `WorkspaceExplorerFragments`; SimplyPages `components/Markdown.java`; `editing/` APIs; demo `EditingDemoController` |
| **HTMX-first, SimplyPages conventions** | Mostly compliant where surfaces exist, but `WorkspaceExplorerFragments` uses raw HTML strings (~1600 lines), and 4 chat transports are hand-rolled JavaScript. | `WorkspaceExplorerFragments`; `agent-chat.js`, `system-chat.js`, `project-chat.js` |

### Spec / Docs / Schema Drift

| Area | Gap | Evidence |
|------|-----|----------|
| **Spec corpus orphaning** | All 12 spec files describe the master-era product; every anchor class is deleted on `core`. Services, API, web, simplypages, architecture, service-graph specs are orphaned. | All `.internal-dev/specifications/*.md` files; `core` working tree has none of their anchors |
| **No extraction drift record** | The 2026-08-04 core extraction (414 files, ~74K lines deleted) recorded "Specification Impact: none" on all 11 changelogs. No drift entry exists. | `.internal-dev/changelogs/20260804-phase-*.md` (all 11: "Specification Impact: none") |
| **Explicit conflicting sentences** | - "Projects still have no filesystem root authority" (`architecture.md` Cross-Domain, `docs/technical/architecture.md:58`) — target model requires root authority<br>- `ARCH-20260719-01`: closed `AGENT`/`CHAT`/`USER` kinds, no PROJECT kind — target model adds project workspaces<br>- `api.md`: "workspaceType=chat \| Rejected \| 400 — chat workspaces are not execution targets"<br>- `services.md`: "ScheduleService owns versioned six-field cron/IANA-timezone definitions"<br>- `deferred-features.md` 2026-07-20 reconciliation: "Project filesystem roots remain intentionally absent, not deferred implementation work" — target model reverses this<br>- `web.md`: "Settings labels the compatibility owner as Owner Agent … does not transfer ownership"<br>- `api.md`: "Submission requires promotion and an eligible project-member agent" | `.internal-dev/specifications/architecture.md`, `services.md`, `api.md`, `web.md`, `deferred-features.md` |
| **Doc duplication** | `architecture.md` vs `docs/technical/architecture.md`; `services.md` vs `docs/technical/services.md`; `api.md` vs `docs/technical/api-reference.md` + `docs/api/*` — same contracts stated twice. Migration inventory duplicated (migrations 001-009 vs 010-020 in different docs). `docs/README.md` says "through 013" while `docs/technical/00-index.md` and `data-model.md` say "020". | `docs/README.md`, `docs/technical/00-index.md`, `docs/technical/data-model.md` |
| **Open/overdue DRIFTs** | `DRIFT-20260720-01` (api.md, blocking), `DRIFT-20260525-01/-04/-05`, `DRIFT-20260727-01`, `DRIFT-20260525-02`, `DRIFT-20260525-06/-07` are all open or watching. Deferred features with passed review dates never re-reviewed (DEFERRED-20260525-01, -09..-21, DEFERRED-20260526-06). | `.internal-dev/specifications/architecture.md` Drift/Gaps table; `services.md` Drift/Gaps; `api.md` Drift/Gaps; `web.md` Drift/Gaps; `deferred-features.md` Review Log |
| **Schema cruft** | Dual `schedule_firings` (legacy L421 block created then dropped at L1338, hardened at L1389); `projects.git_repo_url`; `plan_definitions.deliverables_json` legacy not-null; `skill_legacy_conversion_diagnostics`; materialization-generation `root_kind` LEGACY_V1; `output_publications.staging_path`/`publication_state` RESERVED/STAGED; `writer_claim_generations`/`assignment_fence_generations` (mature, keep); `task_run_idempotent_ops` (mature, keep); `skill_package_operations.staging_path` | `schema.sql` L421, L1338-1411; `plan_definitions` L104; `output_publications` L720+ |

## Scope

### In Scope

- Rectification of terminology, concept model, filesystem layout, scheduling, project model, run authority, task labels, per-project work tracking, root-agent capability tier, and the UI surfaces listed below, per the target model.
- The six recommended workstreams in **Recommended Direction** (WS-0 baseline/branch consolidation and spec supersession; WS-1 terminology and concept-model unification; WS-2 filesystem/workspace redesign; WS-3 scheduling redesign; WS-4 project model, run authority, task labels, work ledger; WS-5 UI — `agents.md`/skills editing, wording sweep, de-duplication; WS-6 docs/specs/schema reconciliation and cruft removal).
- Spec supersession records, drift entry for the 2026-08-04 core extraction, schema cruft removal, and stale-doc cleanup listed in **Spec Supersession & Cruft List**.
- Selective salvage of deleted product code from `sprint/20260729-163244-project-dashboard` onto the chosen base branch.

### Out of Scope

- Solving process confinement (shell execution, executable skill authorization, package ingestion remain fail-closed).
- Building an authN/authZ user-identity layer; root-agent tiering is a domain capability model, not user authentication.
- The `mindspice-core` framework extraction itself (phases 12–14 disposition is a user decision, see Recommended Direction).
- New product features beyond the target model (no speculative additions).

## Recommended Direction

### Branch Strategy — SETTLED (user decision, 2026-08-09)

**Decision: create a new sprint branch from `336eb5f0`** — the tip of `sprint/20260729-163244-project-dashboard` (the pre-extraction full product, and the exact commit `core` was created from). **Branch `core` is left as a standalone branch to be addressed later; the `mindspice-core` framework extraction is out of scope for this sprint.**

Consequences for planning:

- The base is the complete, runnable product: controllers, services, fragments, tools, tests, 20-file `db/migrations/` + `SchemaMigrationRunner`, `static/js/*`, CSS. **No code recovery is needed**; `git show`/`git diff` against `core` are only relevant for cross-checking framework-era changes.
- `schema.sql` and the ordered migration infrastructure are **live on the base**. Schema changes follow the existing `NNN-kebab.sql` ordered-migration pattern; keep `SchemaMigrationRunner`.
- The extraction sprint's pending phases 12–14 (tests, docs hardening, reintegration) and any future framework port are deferred to the later `core` effort; this sprint must not touch `core` or the framework.
- Spec supersession, drift entries, and the gap map from this handoff remain fully applicable: the spec corpus describes this product, and its conflicts with the target model are the sprint's work.

**Confirmation items resolution (previously listed as pending):**
1. **Base branch** — ✅ SETTLED: new branch created from `336eb5f0` (user directive 2026-08-09).
2. **Extraction sprint disposition** — ✅ SETTLED: `core` is standalone and addressed later; its phases 12–14 are out of scope for this sprint.
3. **Schema baseline** — ✅ SETTLED: the full schema and migration infrastructure are already live on the base; evolve via new ordered migrations (no revive, no minimal restart).
4. **Migration infrastructure** — ✅ SETTLED: keep `db/migrations/` + `SchemaMigrationRunner`; add new `NNN-kebab.sql` migrations for rectification changes.

### Recommended Sprint Scope & Phasing

Workstream recommendations in dependency order. The planner may reshape them, but the dependency chain should not be broken. Each workstream is a collection of phases; the planner owns the final phase decomposition.

**WS-0: Baseline & Branch Setup.** Goal: create the sprint branch from `336eb5f0`, record spec supersession entries. Deliverables: sprint branch created from `336eb5f0` (base already complete and runnable — no recovery); drift entry for the 2026-08-04 extraction; supersession entries for `DECISION-20260719-02` closed-kind clause, "projects have no filesystem root authority," and cron scheduling contracts; decision entry `DECISION-20260809-02` recorded. Key evidence: this handoff's Branch Strategy section, `decisions.md`, `architecture.md`. Validation: bounded Spring Boot startup on the branch (baseline green before phase work).

**WS-1: Terminology & Concept-Model Unification.** Goal: unify task/plan/workflow/job into the target taxonomy; consolidate schema; map job concepts. Deliverables: canonical task-definition table (synthesize `plan_definitions` + `work_task_definitions`); schema-in/out/criteria on all task families; discriminators aligned to `TASK`/`WORKFLOW` terminology; `job_definitions` → workflow/task definitions; `orchestration_jobs`/`orchestration_job_items` removed or mapped; `agent_jobs` renamed or removed; `job_recurrences` removed (replaced by WS-3); `deliverables_json` dropped; `prompt_profile`/`planning_task`/`WorkTypeProfileService` consolidated; code naming sweep; API route naming aligned (backward-compatible redirects where needed). Key evidence: `schema.sql` L60-130, L285-366, L611-624, L1772-1841; `PlanService`, `WorkTaskService`, `TaskService` (recoverable). Validation: full Maven suite, bounded startup, schema equivalence.

**WS-2: Filesystem & Workspace Redesign.** Goal: implement the target workspace/filesystem layout. Deliverables: `chats/` top-level root with per-chat `outputs/` (publication to `chats/<id>/outputs/`); agent home workspace `home/`, `scripts/`, `skills/` (not `.skills/`), `agents.md`; agent files layered onto project workspaces; `WorkspacePathLayout` project factory `projects/<key>/` with `agents.md`, `skills/`, `scripts/`, `project/` subdirectory; orchestrator root = `projects/<key>/`, subagent root = `projects/<key>/project/`; `WorkspaceKind.PROJECT` + orchestrator/subagent root resolution; `WorkspaceExplorerService` project writability; `chats/` path authority + `.skills/` → `skills/` updates; materialization infrastructure removed (V1/V2, `AgentMaterializationService`, `AgentContextService` bindings, `materialization_generation`); `USER` workspaces retained under `workspace/user/`; migration of existing chat outputs from `workspace/chat/<id>/outputs/` to `chats/<id>/outputs/`. Key evidence: `ARCH-20260719-01`, `ARCH-20260727-01/02`; `WorkspacePathLayout`, `WorkspaceDirectoryService`, `WorkspaceExplorerService` (recoverable); `schema.sql` L660-704, L720+. Validation: path-layout/descriptor/confinement/migration tests, bounded startup, Playwright for Files tab surfaces.

**WS-3: Scheduling Redesign.** Goal: replace cron with the three-cadence model; add repeatable-prompt scheduling. Deliverables: `schedule_definitions` schema `cadence_type` (ONE_TIME/DAILY/INTERVAL) with `fire_at` (ISO instant), `daily_time` (HH:MM), `interval_days/hours/minutes/seconds`; `ScheduleMode` `CRON` → `DAILY` + `INTERVAL`; poller adapted (daily at configured time; interval after elapsed period); `job_recurrences` dropped; agent tools (`agentScheduleSave`) accept cadence fields; repeatable-prompt scheduling via new `AssignmentType.PROMPT` + `AssignmentTemplateParser` extension; fencing/idempotency preserved exactly (`scheduler_claims`, `schedule_firings` operation-key/digest, `schedule_firing_attempts`, `offset_policy`); UI schedule editors updated for cadence selectors; dead `ScheduleReactionFragments` repurposed or removed; dual `schedule_firings` legacy block removed; API routes updated, cron validation removed. Key evidence: `schema.sql` L1345-1411, L611-624; `ScheduleService`, `ScheduleMode`, `AgentOperationalTools`, `AssignmentType`, `AssignmentTemplateParser` (recoverable). Validation: cadence/idempotency/firing-lifecycle/agent-tool tests, bounded startup, Playwright for schedule editor UI with screenshot critique.

**WS-4: Project Model + Run Authority + Task Labels + Work Ledger.** Goal: implement project run authority, root-agent capability model, task labels, per-project work tracking. Deliverables: `AgentProfile` boolean `is_root` + seeder (`magenta` default seeded as root); `AgentToolAuthorizationService` tool tiering (root: scheduling + workspace/project management; subagent: reduced set); `projects.owner_agent_id` non-nullable and semantically meaningful, default `magenta`; run authority in `AssignmentService.create` (block non-project-member agents when projectId supplied; unbound allowed for personal tasks); owner chat drop-down adapted from `ProjectChatController` with control proposals (like `SystemChatActionProposalService`); task-label column on canonical task-definition table (enum `ACTIVE`/`SCHEDULED`/`COMPLETED`/`CANCELLED`/`ARCHIVED`) rendered as semantic chips; per-project work ledger (view or enriched `project_events`); Dashboard shows recent work with agent attribution; `projects.git_repo_url` dropped. Key evidence: `projects` schema L624-638; `AgentProfile`, `AgentProfileSeeder`, `AssignmentService.create`, `ProjectChatService`, `SystemChatService` (recoverable); `project_events` L648-656. Validation: enforcement/tiering/label/ledger tests, bounded startup, Playwright for project dashboard and chat.

**WS-5: UI — `agents.md`/Skills Editing, Wording Sweep, De-Dup.** Goal: dashboard editing for `agents.md` and `skills/`, unified wording, de-duplicated explorer/editor surfaces. Deliverables: agent dashboard `agents.md` editor (text editor + SimplyPages `Markdown` preview with Rendered/Text toggle); project dashboard `agents.md` editor (same pattern at project root); agent + project skills viewer/editor via `WorkspaceExplorerFragments` primitives (replacing hand-rolled `SkillFragments._files`); wording sweep (home card, submit form "Task/Workflow ID", `/jobs` re-title, "Artifacts" vs "Outputs" merge, Files nav "Stored Items", schedule button verbs, workspace error strings); de-duplication (one explorer family; one definition CRUD stack; kill UI-dead `TaskController` REST; 4 schedule/reaction editors → 2; chat transports unified on `ChatModule` SSE + one bespoke transport if needed; hand-rolled tabs/chips vs SimplyPages equivalents); project Files tab made writable with member authorization. Key evidence: `WorkspaceExplorerFragments`, `SkillFragments`, `OrchestrationController`, `ProjectConsoleFragments`, `AppNavigation.TAB_REGISTRY` (recoverable); SimplyPages checkout `/home/dhickel/Code/Java/SimplyPages` (editing docs + demo `EditingDemoController`). Validation: Playwright for all changed surfaces with screenshots and visual-quality critique per project rules; SimplyPages docs consulted before UI work.

**WS-6: Docs/Specs/Schema Reconciliation & Cruft Removal.** Goal: specs reflect the rectified product; obsolete docs removed; schema cruft cleaned. Deliverables: all 12 spec files updated or archived; `docs/` sweep (stale docs archived or updated; `docs/README.md` migration count corrected; one canonical doc per domain); `AGENTS.md`/`README.md` updated; open DRIFTs closed or re-triaged; deferred features re-reviewed (`DEFERRED-20260525-17` cron UI superseded); knowledge cluster dedup where files contradict or overlap; schema cruft removal (see Cruft List); stale root artifacts removed; `MagentaPromptContributor` reconciled with layered agents.md resolution; `MagentaRootProperties.skillsRoot()` removed if unused. Key evidence: all 12 spec files, `docs/`, knowledge clusters, `schema.sql`, root artifacts. Validation: consistent spec cross-references, no broken doc links, clean schema load, bounded startup.

## Spec Supersession & Cruft List

### Exact Spec Entries to Supersede/Update

| Entry | Current Text | Superseded By |
|-------|-------------|---------------|
| `ARCH-20260719-01` | `WorkspaceKind` closed to `AGENT`, `CHAT`, `USER`; "Project association is metadata/membership only, never selecting or creating a root" | Add `PROJECT` kind; remove "never selecting or creating a root" clause; add project workspace root authority |
| `ARCH-20260719-01` cross-domain sentence | "Projects still have no filesystem root authority" | Remove; projects gain filesystem root authority per target model |
| `ARCH-20260719-01` | `workspace/agent/<id>/`, `workspace/chat/<id>/`, `workspace/user/<name>/` roots | Replace `workspace/chat/<id>/` with `chats/<id>/`; add `projects/<key>/` factory |
| `ARCH-20260727-01` | Nine conceptual areas include `.skills` (dot-prefixed) | Rename `.skills` → `skills/`; add `agents.md` as workspace root file |
| `ARCH-20260727-02` | Materializations, context defaults, immutable bindings | Remove (obsolete under real project roots); retain only if materializations kept |
| `ARCH-20260526-03` | "Project discovery is unavailable unless a trusted project-root authority is supplied … executable resources fail closed" | Project discovery becomes available; real project roots supply trust authority |
| `services.md` SVC-20260719-01 | `WorkspaceDescriptor` from closed `AGENT`, `CHAT`, `USER` | Add `PROJECT` kind; add orchestrator/subagent root resolution |
| `services.md` SVC-20260720-02 | ScheduleService "six-field cron/IANA-timezone definitions" | Replace with three-cadence model (ONE_TIME/DAILY/INTERVAL) |
| `api.md` execution selection | "`workspaceType=chat` \| Rejected \| 400 — chat workspaces are not execution targets" | Retain rejection but update rationale; `chats/` is not a task-execution target |
| `api.md` project tasks | "Submission requires promotion and an eligible project-member agent" | Add root-agent extra controls; subagent submission gated by project membership |
| `web.md` agent Files tab | `workspace/agent/<agentId>` binding; Files tab composition | Update to new agent home layout (`home/`, `scripts/`, `skills/`, `agents.md`) |
| `web.md` project console | "Settings labels the compatibility owner as Owner Agent … does not transfer ownership" | Owner becomes authoritative; replaceable with real permission changes |
| `simplypages.md` neutral explorer | Workspace-neutral IDs/classes/routes; `WorkspaceExplorerFragments` hard-codes `/agents/_detail/{agentId}/files` | Fix hard-coded prefixes; make truly workspace-neutral |
| `deferred-features.md` DEFERRED-20260525-17 | "Add scheduling and event reaction management UI" — marked implemented with cron | Supersede: cron UI implementation is incomplete under target model; mark as superseded by cadence model |
| `deferred-features.md` 2026-07-20 reconciliation | "Project filesystem roots remain intentionally absent, not deferred implementation work" | Remove/replace: project filesystem roots are now required |
| `deferred-features.md` DEFERRED-20260526-05 | Project-skill discovery gated on trusted root authority | Mark as implementable once real project roots exist |
| `DECISION-20260719-02` | Closed three-kind workspace model | Add PROJECT kind; add `chats/` outside workspace |
| `DECISION-20260809-01` | Already accepted | Implementation reference only; no change needed |

### Drift Entry to Record

A new `DRIFT-20260804-01` (or similar) entry must be recorded in `architecture.md` documenting that the 2026-08-04 core extraction sprint deleted the entire product shell (phases 02+05) and that the spec corpus was not updated. Status: open; routing: rectification sprint WS-0.

### Cruft / Removal Candidate List

| Item | Location | Reasoning |
|------|----------|-----------|
| `projects.git_repo_url` | `schema.sql` L624-638 | Survived despite removal decision; git removed from plan |
| `plan_definitions.deliverables_json` | `schema.sql` L104 | Legacy not-null column, ignored by repositories |
| `job_recurrences` table | `schema.sql` L611-624 | Second independent cron model; replaced by WS-3 |
| Dual `schedule_firings` legacy block | `schema.sql` ~L421 | Created then dropped at L1338; migration-008 legacy-compat pattern; dead DDL |
| `ScheduleReactionFragments` (agent-level, cron-only) | Deleted code | No UI href references it; effectively dead even when the full app was running |
| `TaskController` REST | Deleted code | UI-dead REST mirror; no browser consumer references it |
| `skill_legacy_conversion_diagnostics` table | `schema.sql` | Legacy skill conversion artifacts |
| `materialization_generation.root_kind` LEGACY_V1 branch | `schema.sql` | Obsolete if materializations removed |
| `output_publications.staging_path`/`publication_state` RESERVED/STAGED | `schema.sql` L720+ | Run-staging artifacts; evaluate whether still needed after WS-2 |
| `ai_chat_session_metadata` materialization-era columns | `schema.sql` | `materialization_key`, `materialization_generation`, `owner_binding_key`, `member_agent_id`, `project_id` — remove if materializations go |
| Empty `data/` dir | Repository root | Empty directory |
| `artifacts/validation/` stale screenshots | Repository root | Orphaned from prior validation campaigns |
| `identifier.sqlite` (empty) | Repository root | Empty SQLite file, no consumer |
| `db-ui-probe.js` | Repository root | Unknown purpose, no consumer found |
| `simplypages-edit-demo-desktop.png` | Repository root | Stale screenshot |
| `node_modules/`, `package.json`, `package-lock.json` | Repository root | Unknown purpose in Java project (may be from an old frontend experiment) |
| `tests/` (empty directory) | Repository root | Empty |
| `htmx` webjar | Deleted static resources | If unused after UI rebuild, remove the webjar dependency |
| `docs/README.md` migration count "013" | `docs/README.md` | Wrong; should match actual migration count or be removed |
| `docs/technical/` duplication of `.internal-dev/specifications/` | `docs/technical/` | Two copies of same architecture/services/API contracts; retain one |
| `docs/maestro/` | `docs/maestro/` | Explicitly retained historical material — preserve |
| `MagentaRootProperties.skillsRoot()` | Working tree `MagentaRootProperties.java` | `path/skills`, marked unused if WS-2 replaces skill resolution |

## Risks

1. **Branch strategy risk (HIGH)**: RESOLVED 2026-08-09 — base is a new branch from `336eb5f0`; `core` is standalone and out of scope. Residual risk: implementers accidentally touching `core`/framework code or assuming the headless working tree; mitigation: branch creation is WS-0 and all phases operate on the new branch.
2. **Spec corpus describes a deleted system (HIGH)**: All 12 spec files reference anchor classes deleted on `core`. The planner must decide whether specs become "product contract to re-achieve" or are rewritten wholesale; either way, they cannot be used as-is for planning reference without confusion.
3. **Target model reverses settled decisions (MEDIUM)**: The open three-kind workspace model, cron scheduling, plan≡task unification, and project-root absence were all deliberately settled architecture decisions. Rectification must explicitly supersede them with new decision entries, or planners will fight the existing contract.
4. **Concurrency/idempotency machinery preservation (MEDIUM)**: `schedule_firings` operation keys, `assignment_fence_generations`, `writer_claim_generations`, `task_run_idempotent_ops`, `scheduler_claims` are mature and battle-tested. The rectification must extend — not regress — these. Any schema replacement that drops these tables without equivalent replacement is a regression.
5. **No authN/authZ (MEDIUM)**: The system runs as a single-operator alpha. Root-agent vs. subagent capability tiering is a new capability model, not existing wiring. It must be implemented defensively (fail-closed: when in doubt, deny).
6. **Process confinement unproven (LOW for this sprint)**: Shell execution, executable skill authorization, and package ingestion remain fail-closed. The rectification sprint does not need to solve process confinement, but must not accidentally open these paths.
7. **Docs sweep size (LOW)**: 12+ technical docs, 14 end-user docs, API indexes, and knowledge clusters is a large tail. Classic risk of under-scoping the last phase. Mitigated by recording the scope explicitly and treating incomplete docs as blocking for the "completed" verdict.

## Validation

Per project rules (`.internal-dev/AGENTS.md`):

- **Focused automated tests**: Every changed service, controller, tool, and repository must have passing focused tests. The full Maven test suite must pass after each workstream.
- **Bounded Spring Boot startup**: After every phase/workstream that changes Spring wiring or configuration:
  ```
  timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0
  ```
  The application context must start successfully.
- **Playwright validation**: Every UI change must pass Playwright validation with:
  - Screenshots of changed surfaces (desktop and mobile where applicable)
  - Visual-quality critique: alignment, spacing, gates, density, scan hierarchy, control affordances, text wrapping, overflow, first-viewport usefulness, mobile stacking, and whether the page uses available space coherently
  - Functional click-through: CRUD flows, form submissions, HTMX partials, OOB swaps, error states, empty states
- **HTMX-first policy**: Standard CRUD, filtering, row actions, form submissions, and partial refreshes must use HTMX. Any JavaScript must be explicitly justified as the path of least resistance.
- **SimplyPages conventions**: Before any UI work, read relevant SimplyPages docs at `/home/dhickel/Code/Java/SimplyPages/docs/INDEX.md`. Consult editing docs, HTMX patterns docs, and demo `EditingDemoController`. Library checkout is `/home/dhickel/Code/Java/SimplyPages`.
- **Playwright execution**: Run on a subagent (via orchestrator), never inline with implementation. Use model `gpt-5.3-codex` with reasoning effort `medium`.

## Open Questions

These are the genuine open items the planning session must resolve, with recommended defaults. The exploration review identified several items flagged for decision.

| # | Question | Recommended Default | Rationale |
|---|----------|---------------------|-----------|
| 1 | **Base branch** (see Recommended Direction §Branch Strategy) | ✅ SETTLED 2026-08-09: new sprint branch from `336eb5f0`; `core` standalone, addressed later | Full product is live on the base; no recovery; framework out of scope |
| 2 | **Extraction sprint phase-12..14 disposition** | Fold into rectification sprint | Phase-14 expects deleted `AgentsMdResolver`; phases 12-13 (tests/docs hardening) apply to a deleted product — better to fold into rectification and validate the real product |
| 3 | **Schema baseline** | Revive 83-table `schema.sql` wholesale, slim iteratively | Preserves mature fencing/idempotency/lifecycle schema. Starting from scratch risks regressions on `schedule_firings` operation keys, `assignment_fence_generations`, `writer_claim_generations`, `task_run_idempotent_ops` |
| 4 | **Job concept mapping** | Unify into task/workflow family; remove `job` as user-facing term | The three job meanings (`job_definitions`, `orchestration_jobs`, `agent_jobs`) map to tasks, workflows, and background chat constructs respectively. Keeping "job" as a distinct term perpetuates the conflation |
| 5 | **`skills/` vs `.skills/`** | `skills/` (no dot) | Target model says `skills/`. Dot-prefix is an accidental implementation detail that survived the filesystem-first cutover. Agent workspace path and project root must agree |
| 6 | **`chats/` vs `workspace/chat/`** | `chats/` as new root, `workspace/` retained for agent/user only | Target model says `chats/`. `workspace/chat/` can be kept as internal backing while UI and API surface `chats/`; or the filesystem layout can change. Recommend full rename to match the contract |
| 7 | **USER workspace fate** | Retain user workspaces under `workspace/user/`, not under `chats/` | User workspaces are distinct from chat workspaces in the target model. They remain a valid execution target. No reason to remove them |
| 8 | **Materializations fate** | Obsolete under real project roots; remove | Project materializations (`v1-PROJECT-<sha256>`, V2 `materializations/v2/<projectKey>/gen-<N>/`) were the workaround for "projects have no filesystem root." Real project roots make them unnecessary. Remove when real project workspaces land |
| 9 | **Task label model** | Enum stored on the task definition (or assignment), not on the run | Labels are task-level metadata (active, scheduled, complete, etc.). Use a column on whichever table becomes the canonical task definition. Recommend enum: `ACTIVE`, `SCHEDULED`, `COMPLETED`, `CANCELLED`, `ARCHIVED` |
| 10 | **Root-agent capability model** | `AgentProfile` boolean `is_root` + tool tiering in `AgentToolAuthorizationService` | Simplest capability model: root agents get scheduling + workspace/project management tools; subagents get a reduced tool set. `magenta` default is seeded as root |
| 11 | **Repeatable-prompt scheduling shape** | New `AssignmentType.PROMPT` or equivalent | A schedule can enqueue a prompt evaluation (distinct from a task run). Prompts have no definition table — they are inline text sent through a scheduling template |
| 12 | **Task schema-in/out/criteria enforcement** | JSON schema validation at task creation/promotion, not at runtime | Plan definitions already have `inputs_json`/`outputs_json`/`validation_criteria_json` columns with `PlanFieldDefinition` (name/type/required/array/description). Extend this contract to work tasks. Validate at promotion/submission, not at chat-draft time |
| 13 | **Workflow node re-pointing** | Nodes reference task definitions, not plan definitions | Currently `workflow_definitions` may reference `plan_definitions` nodes. With unified task concept, nodes reference the canonical task definition table |
| 14 | **Run-authority enforcement** | Block unbound runs when a project exists; allow unbound for personal agent tasks | When a task is project-scoped, only project members can run it. When no project, any active agent can run. Reject `projectId`-less submissions at the project assignment boundary |
| 15 | **Per-project work ledger** | New `project_work_ledger` table or enriched `project_events` | Current `project_events` is append-only with generic `type`/`payload_json`. A work-ledger view joining `work_assignments` (filtered by `project_id`), `run_summaries`, and `agent_profiles` may be sufficient without a new table |
| 16 | **`schedule_definitions` schema migration** | Replace `mode`/`cron_expression`/`timezone`/`fire_at` with `cadence_type` (ONE_TIME/DAILY/INTERVAL), `fire_at`, `daily_time`, `interval_days/hours/minutes/seconds`. Preserve `operation_key`, `request_digest`, `scheduler_claims`, firing/attempt structure | The fencing/idempotency machinery is mature and must be preserved. Only the cadence fields change |
| 17 | **Project workspace layout details** | Root: `projects/<project-key>/` with `agents.md`, `skills/`, `scripts/`, `project/` subdirectory. Orchestrator root = `projects/<project-key>/`, subagent root = `projects/<project-key>/project/` | This matches the target model: orchestrator one level above `project/`, all other agents root at `project/`. `agents.md` at root describes project; `skills/` and `scripts/` layer with agent files |
| 18 | **`agents.md` layering resolution** | Agent's `agents.md` → project root `agents.md` merged with project taking precedence on conflicts; explicit task instructions override both | Follows existing `ARCH-20260526-02` "closest wins on conflict, ancestor content remains active" pattern, adapted for the new layering source |
| 19 | **`repos/`/`apps/` conceptual areas** | Remove from agent workspace; subsumed by project workspace files | The target model describes project workspaces containing actual files. `repos` (Stored Items) is user-managed read-only storage outside Magenta — a separate concern. `apps` is storage-only, no execution authority. Both can remain as readonly legacies or be removed; recommend removal |
| 20 | **Dual `schedule_firings` block intent** | The legacy block (L421, created then dropped at L1338) is a migration-008 legacy-compat pattern in the "fresh" schema. Remove it | It is dead DDL with no consumer. The hardened block at L1389 is the canonical one |
| 21 | **`git_repo_url`/`deliverables_json` removal** | Remove both | `git_repo_url` survived despite explicit removal decisions; `deliverables_json` is legacy not-null ignored by repositories |

## Appendix A: Key Recovery Commands

```
# Recover a file from before the phase-05 deletion:
git show 8b6cdcc1~1:magenta-app/src/main/java/io/mindspice/magenta2/<path> > <dest>

# Recover a file from the sprint branch:
git show sprint/20260729-163244-project-dashboard:magenta-app/src/main/java/io/mindspice/magenta2/<path> > <dest>

# List deleted files from phase-05:
git diff --name-only 8b6cdcc1~1 8b6cdcc1 --diff-filter=D
```

## Appendix B: SimplyPages Docs Quick Reference

| Doc | Path | Use When |
|-----|------|----------|
| INDEX | `docs/INDEX.md` | Entry point for all SimplyPages docs |
| Editing system | `getting-started/03-editing-system-first-implementation.md` | Building agents.md / skills editors |
| Editing workflows | `patterns/04-editing-workflows-owner-user-approval.md` | Approval flows |
| HTMX patterns | `patterns/03-htmx-endpoint-and-swap-patterns.md` | HTMX endpoint design |
| Slots | `core/03-template-rendercontext-slotkey-reference.md` | SlotKey reuse patterns |
| Editing API | `reference/editing-api-reference.md` | Full editing API surface |
| Demo | `demo/EditingDemoController.java` | In-place decorated editing examples |
