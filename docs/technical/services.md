# Services

Magenta services own use-case behavior. Controllers adapt HTTP/SSE/HTMX calls into service calls; repositories hide SQLite details and compatibility bootstrapping.

## Chat Services

Sources: [`ai/chat/service`](../../src/main/java/io/mindspice/magenta2/ai/chat/service), [`ai/chat/repository`](../../src/main/java/io/mindspice/magenta2/ai/chat/repository), [`ai/chat/tool`](../../src/main/java/io/mindspice/magenta2/ai/chat/tool).

- `ChatService` owns conversational turns, streaming response assembly, message persistence, plan/task interaction hooks, context usage, active task execution, and model/tool loop handling.
- `RequestResolver` normalizes incoming chat requests, conversation ids, model keys, and planning model choices into `ResolvedChatRequest`.
- `ChatModelRouter` resolves configured model keys from file-backed AI config and builds Spring AI chat clients.
- `ContextManagementAdvisor` and `ContextUsageTracker` keep prompt context within model limits and record context usage.
- `AuditService`, `AuditRepository`, `TurnAuditWriter`, and `ToolTranscriptService` record user/assistant/tool/context events separately from chat memory.
- `ChatToolRegistry` resolves approved tools and is the validation point for runtime tool allowlists.
- File, shell, and web tool services keep tool-specific constraints local: filesystem scope, shell working directory/allowlist handling, web fetch/search bounds, and transcript-safe results.

Tables: `ai_chat_memory`, `ai_chat_session_metadata`, `audit_event`, `agent_jobs`.

Controllers: `ChatController`, `FrontendFragmentController`, agent chat parts of `AgentOrchestrationController`, and chat surfaces in `OrchestrationController`.

## Plan Services

Sources: [`ai/chat/plan`](../../src/main/java/io/mindspice/magenta2/ai/chat/plan).

- `PlanService` owns plan definition CRUD, session plan state, pending questions, approval/continue/cancel lifecycle, task-template saving, plan runs, output materialization, execution evidence, and validation feedback.
- `SavedPlanChatService` owns plan-scoped saved plan chat state, opening question collection, saved-plan model turn handoff, and transcript notices for manual editor saves.
- `SavedPlanPlanningModelClient` runs saved-plan planning model turns with plan-id-scoped saved-plan tools, without using `/api/chat` conversation memory or chat session metadata.
- `PlanCompletionService` inspects plan completeness and validation criteria.
- `WorkTypeProfileService` maps structured work type profile values and legacy prompt profile values.
- `PlanRepository` persists `plan_definitions` and `plan_runs`, and snapshots definitions at run start so later edits do not rewrite run history.

Tables: `plan_definitions`, `plan_runs`, `run_output_artifacts`.

Controllers: `PlanController`, plan endpoints in `ChatController`, and `/plans` fragments in `OrchestrationController`.

## Task Services

Sources: [`ai/chat/task`](../../src/main/java/io/mindspice/magenta2/ai/chat/task).

- `TaskService` owns reusable task definitions, chat-backed task drafts, draft question/answer lifecycle, draft approval, task runs, task execution through chat, and run state.
- Task definitions and fields are separate domain records from plan definitions, but public plan submission currently treats saved plans/task-like definitions as durable assignment input.

Tables: task-like data is persisted through `plan_definitions`/`plan_runs` in the current unified persistence path.

Controllers: `TaskController`, task endpoints in `ChatController`, and task/plan operational fragments in `OrchestrationController`.

## Agent Profile Services

Sources: [`ai/orchestration/agents`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/agents), [`ai/config/user`](../../src/main/java/io/mindspice/magenta2/ai/config/user).

- `AgentProfileService` owns durable agent profile CRUD and validation.
- `AgentProfileRepository` owns `agent_profiles`.
- `AgentProfileSeeder` imports a legacy file-configured default agent profile when the database is empty.
- File-backed `AiConfig` remains the source for model endpoint definitions, summary/planning model defaults, web search config, and legacy agent seed values.

Tables: `agent_profiles`, `runtime_settings`.

Controllers: `AgentProfileController`, agent editor fragments in `OrchestrationController`, and runtime settings/model controllers.

## Assignment and Runtime Services

Sources: [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime).

- `AssignmentService` owns durable assignment creation, queue reads, history reads, lifecycle transitions, history purge, assignment diagnostics, transcript lookup, conversation links, and lease/progress fields.
- Assignment creation persists first-class `projectId` metadata plus `workspaceType`, `workspaceKey`, and internally derived canonical workspace identity; `AssignmentService.AssignmentSummary` exposes that context for UI/API consumers.
- Assignment service helpers own active project/job mutation checks and requeue operations so controllers do not reason over raw queue rows.
- `OrchestrationRunnerService` executes queued assignment boundaries for task, workflow, and job work against one resolved `WorkspaceDescriptor`, preserves waiting workflow assignments for resume, and does not attempt token-level resume; durable state resumes at task/workflow/job item boundaries.
- `OrchestrationRunService` provides run-facing helpers for assignment execution paths.
- `OrchestrationRuntimeRepository` owns runtime queue, schedules, reactions, direct-line inbox, events, legacy orchestration jobs, and compatibility columns.
- `InboxService` in `runtime` owns direct-line agent/operator inbox messages in `agent_inbox_messages`.
- `ScheduleService`, `EventReactionService`, and `OrchestrationEventService` own optional scheduled and event-triggered assignment creation.

Tables: `work_assignments`, `assignment_conversation_links`, `agent_inbox_messages`, `agent_schedules`, `schedule_firings`, `agent_event_reactions`, `orchestration_events`, `orchestration_jobs`, `orchestration_job_items`.

Controllers: `AgentOrchestrationController`, runtime queue fragments in `OrchestrationController`, `RuntimeController`.

## Job Services

Sources: [`JobService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java), [`JobRepository`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobRepository.java).

- `JobService` owns user-facing job definitions, ordered work items, run records, recurrence, cancellation, and output run id lookup.
- Public job run routes submit `JOB_RUN` assignments through `AssignmentService`; job execution is coordinated by `OrchestrationRunnerService`.
- `JobExecutionSummary` is the read model for job run/operator context. It ties job definition, assignment, run, agent/project attribution, selected workspace type/key/identity, child runs, and output counts together.
- Recurrence/start paths enqueue assignments; direct run allocation is guarded for assignment-owned execution.
- Jobs bind to an agent and optional project metadata but do not own workspace directories. Execution uses the assignment's resolved agent or user workspace; publication writes directly beneath that workspace's reserved lazy `outputs/` subtree and artifacts retain assignment/run/project attribution.
- Jobs can be empty `DRAFT` definitions so the UI can save metadata before items are added. If an empty job is submitted, the assignment-owned run completes as a no-op instead of leaving `job_runs` in `RUNNING`.

Tables: `job_definitions`, `job_runs`, `job_recurrences`, `run_output_artifacts`, plus assignment tables when submitted.

Controllers: `JobController` and `/jobs` fragments in `OrchestrationController`.

## Project Services

Sources: [`ProjectService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java), [`ProjectRepository`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectRepository.java).

- `ProjectService` owns durable project CRUD, project membership, project events, and project-network gating.
- Projects are metadata, membership, visibility, and attribution records, not filesystem roots or executable work units. `ownerAgentId` is nullable legacy compatibility metadata.
- Project membership controls are service-guarded. Removing a member or deleting a project can fail while active assignments or jobs still reference that project/member context.
- Project operations never select, allocate, link, lease, or delete a filesystem root.

Tables: `projects`, `project_agent_memberships`, `project_events`.

Controllers: `ProjectController` and `/projects` fragments in `OrchestrationController`.

## Workflow Services

Sources: [`ai/orchestration/workflow`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow).

- `WorkflowService` owns definition CRUD, validation, run records, resume operations, and repository coordination.
- `WorkflowValidator` enforces v2 routed graph rules, required node references, and validation warnings/errors.
- `WorkflowRunner` executes workflow nodes, routes, approval waits, output mappings, durable output materialization, and task node delegation with propagated orchestration context.
- `WorkflowTaskExecutor` bridges workflow task nodes into task execution.
- `InboxService` in `workflow` owns workflow/user approval messages in `inbox_messages`.

Tables: `workflow_definitions`, `workflow_runs`, `workflow_node_runs`, workflow-owned `inbox_messages`.

Controllers: `WorkflowController` and `/workflows` fragments in `OrchestrationController`.

## Workspace and Output Services

Sources: [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces).

- `WorkspaceDirectoryService` is the sole constructor of trusted `WorkspaceDescriptor` values. It resolves agent, chat, and user descriptors from a closed `WorkspaceKind` enum, validates keys with `PlainPathSegmentValidator`, checks agent/chat existence through low-level repositories without service cycles, enforces explicit user-workspace declaration, and resolves roots through `WorkspacePathLayout`.
- `WorkspacePathLayout` is the sole structural path authority. Its retained public names are `WORKSPACE` and `OUTPUTS`; its retained factories produce `workspace/agent/<id>`, `workspace/chat/<id>`, and `workspace/user/<name>` roots.
- `WorkspaceExplorerService` owns confined list, inspect, preview, image view, bounded download, text/Markdown create/save, directory create, rename, move, copy, delete preflight/execute, labels, tags, and recent actions. All operations accept a `WorkspaceDescriptor` and normalized file-relative path. It rejects absolute/traversing paths, symlink escape, symbolic recursive trees, workspace-root mutation, and mutation of `outputs/` or descendants.
- `WorkspaceFileMetadataService` and `WorkspaceFileMetadataRepository` own file labels, file label assignments, and file action records keyed by canonical workspace identity plus normalized file-relative path.
- `WorkspaceFileActionLogRepository` owns file action logging keyed by canonical workspace identity.
- `AgentsMdResolver` resolves layered `AGENTS.md` files from the descriptor root to the active confined tool path during execution. No starter `AGENTS.md` content is generated.
- `OutputArtifactRepository` independently owns `run_output_artifacts` schema compatibility, writes, reads, updates, deletion, mapping, and attribution.
- `OutputArtifactService` publishes declared output values or same-workspace source files directly beneath the effective descriptor's lazy `outputs/` directory with collision-safe normalized names. No run-owned directory hierarchy is created. Durable task, job, run, project, agent, and canonical workspace attribution is queryable.
- `RootRelativePathService` is retained only for portable artifact-relative paths. It does not determine workspace identity or authorize roots.

The `/api/work-areas`, `/api/workspaces` (generic), and `/avatar` route families are absent. Workspace file operations use typed routes under `/api/workspace/{type}/{id}` and user workspace collection uses `GET|POST /api/workspace/user`.

Tables: `workspace_file_labels`, `workspace_file_annotations`, `workspace_file_action_log`, `run_output_artifacts`.

Controllers: `WorkspaceController`, `OutputController`, and workspace explorer fragments in `WorkspaceExplorerFragments`.

## Runtime Settings and Operations

Sources: [`ai/orchestration/settings`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/settings), [`application.yml`](../../src/main/resources/application.yml).

- `RuntimeSettingsService` merges persisted runtime settings with file-configured legacy defaults.
- `RuntimeSettingsRepository` owns compatibility columns for evolving settings.
- `ModelController` reads configured models from file-backed AI config; it does not mutate model definitions.

Tables: `runtime_settings`.

Controllers: `RuntimeSettingsController`, `ModelController`, `RuntimeController`, `DashboardController`, and settings fragments in `OrchestrationController`.
