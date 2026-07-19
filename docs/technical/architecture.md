# Architecture

Magenta is a Spring Boot application with SQLite persistence, Spring AI backed chat/model routing, filesystem-backed workspaces, and SimplyPages/HTMX web surfaces. The current code is organized around thin HTTP controllers, use-case services, repository-owned persistence details, and record-based domain payloads.

The application baseline is Spring Boot 4 with Spring AI 2. Model clients are constructed explicitly by `ChatModelRouter`: Ollama and OpenAI-compatible endpoints use their corresponding providers, while `DEEPSEEK` uses Spring AI's dedicated DeepSeek provider so typed reasoning content survives explicit tool-call turns. Magenta does not depend on locally patched Spring AI artifacts.

Source anchors:

- Web/API entry points: [`api/web`](../../src/main/java/io/mindspice/magenta2/api/web)
- Chat core: [`ai/chat`](../../src/main/java/io/mindspice/magenta2/ai/chat)
- Runtime orchestration: [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime)
- Workflow engine: [`ai/orchestration/workflow`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow)
- Workspaces and outputs: [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces) — `WorkspaceDirectoryService` constructs trusted `WorkspaceDescriptor` values; `WorkspacePathLayout` owns structural paths; explorer, metadata, action logging, execution, tools, and output services consume descriptors.
- AI configuration: [`ai/config/user`](../../src/main/java/io/mindspice/magenta2/ai/config/user)
- Schema: [`schema.sql`](../../src/main/resources/schema.sql)

## Request Flow

Browser and API callers enter through `io.mindspice.magenta2.api.web`.

1. Public shell pages and HTMX fragments are rendered by `FrontendController`, `FrontendFragmentController`, and `OrchestrationController`.
2. JSON and SSE API calls are handled by focused REST controllers such as `ChatController`, `PlanController`, `TaskController`, `WorkflowController`, `JobController`, `AgentProfileController`, `AgentOrchestrationController`, `ProjectController`, `WorkspaceController`, `OutputController`, and settings/model/dashboard controllers.
3. Controllers normalize path/query/body input and translate known domain exceptions into HTTP status codes. They should not own chat, persistence, workflow, or runtime business rules.
4. Services execute use cases: chat turns, plan/task definition lifecycle, assignment queue operations, job submission, workflow validation/execution, typed workspace resolution, output publication, runtime settings, and agent profile management.
5. Repositories localize SQLite table knowledge and compatibility bootstrapping. `schema.sql` is the canonical startup schema, but several repositories also add compatibility columns for warm data roots.

Typical flows:

- Chat turn: `/api/chat/stream` -> `ChatController` -> `RequestResolver`/`ChatService` -> `ChatModelRouter`, chat memory repositories, audit repository, tool registry, and plan/task services as needed.
- Saved plan submit: `/api/plans/{planId}/submit` -> `PlanController` -> `PlanService.getTask` -> `AssignmentService.create` with `AssignmentType.TASK_RUN`.
- Workflow submit: `/api/workflows/{workflowId}/runs` -> `WorkflowController` -> `WorkflowService.validateGraph` -> `AssignmentService.create` with `AssignmentType.WORKFLOW_RUN`.
- Assignment execution: `AssignmentService` holds queue state, then `OrchestrationRunnerService` executes task/workflow/job boundaries through `TaskService`, `WorkflowRunner`, `JobService`, workspace services, and output artifact services.
- Output read: `/api/outputs` or `/api/outputs/{artifactId}/content` -> `OutputController` -> `OutputArtifactService` -> `run_output_artifacts` plus confined filesystem paths under the data root.

## Package Boundaries

`api.web` owns transport shape: routes, status mapping, SSE emission, HTMX fragments, and browser shell compatibility. It delegates use-case logic into services.

`ai.chat` owns conversational behavior: request resolution, model selection, streaming turns, memory/audit persistence, context compaction, tool calls, plan mode state, and task execution through chat.

`ai.chat.plan` owns saved plan definitions, plan lifecycle, plan runs, input/output field definitions, completion/validation helpers, and plan-to-task template behavior.

`ai.chat.task` owns reusable task definitions, draft task creation from chat, task runs, task field metadata, and task execution records.

`ai.chat.tool` owns approved chat tools and their service boundaries: file, shell, web, plan-save, task, question, and transcript helpers. Tool allowlists are validated through `ChatToolRegistry`.

`ai.config.user` owns file-backed model endpoint configuration. Runtime settings and durable agent profiles live in orchestration packages; file config remains the source for model endpoint definitions and legacy seeding.

`ai.orchestration.agents` owns durable agent profiles and initial seeding from legacy file config.

`ai.orchestration.runtime` owns durable assignments, jobs, schedules, event reactions, direct-line agent inbox messages, orchestration events, projects, and runtime execution coordination.

`ai.orchestration.workflow` owns workflow definitions, v2 routed node graphs, validation, workflow run state, node runs, workflow-owned inbox approvals, and workflow execution.

`ai.orchestration.workspaces` owns filesystem-first workspace identity (three closed kinds: `AGENT`, `CHAT`, `USER`), trusted descriptor resolution through `WorkspaceDirectoryService`, structural paths through `WorkspacePathLayout`, confined file explorer behavior, workspace-scoped `AGENTS.md` resolution, output artifact persistence through `OutputArtifactRepository`, and output publication.

`ai.orchestration.settings` owns persisted runtime defaults such as default agent/model, chat models, tool policy, context limits, and assignment history purge settings.

## Persistence Shape

SQLite is initialized from `schema.sql` through Spring SQL init. Domain repositories create tables defensively and add compatibility columns where older local databases may predate current schema fields.

The schema groups into these domains:

- Chat and audit: `ai_chat_memory`, `ai_chat_session_metadata`, `audit_event`, `agent_jobs`.
- Plan/task and runs: `plan_definitions`, `plan_runs`.
- Workflows: `workflow_definitions`, `workflow_runs`, `workflow_node_runs`, workflow-owned `inbox_messages`.
- Runtime agents and assignments: `agent_profiles`, `orchestration_jobs`, `orchestration_job_items`, `work_assignments`, `assignment_conversation_links`, `agent_inbox_messages`, schedules, reactions, and events.
- Jobs/projects/workspaces/outputs/settings: `job_definitions`, `job_runs`, `job_recurrences`, `projects`, `project_agent_memberships`, `project_events`, `workspace_file_labels`, `workspace_file_label_assignments`, `workspace_file_actions`, `run_output_artifacts`, and `runtime_settings`.

## Streaming Model

Magenta uses Spring `SseEmitter` for browser-visible streaming. Chat streams emit token/tool/context/terminal events from `ChatStreamEvent`. Saved plan/task/workflow public stream endpoints currently submit durable assignments and emit `submitted` or `failed`, rather than executing the model inline. Agent side-panel chat streams agent-scoped chat events from the same chat service path.

See [API Reference](api-reference.md) for route-level SSE details.

## Frontend Shape

The frontend is server-rendered. SimplyPages renders the main shell and operational pages; HTMX drives standard CRUD, tab, panel, list, and fragment refresh interactions. JavaScript is limited to behavior where persistent browser state or SSE interaction is simpler than HTMX alone: chat streaming, shared HTMX/error shell helpers, orchestration dashboard polling/fragment helpers, entity side-panel interactions, and agent chat islands.

See [Frontend HTMX](frontend-htmx.md) for file-level guidance.
