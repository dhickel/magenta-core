# Architecture

Magenta is a Spring Boot application with SQLite persistence, Spring AI backed chat/model routing, filesystem-backed workspaces, and SimplyPages/HTMX web surfaces. The current code is organized around thin HTTP controllers, use-case services, repository-owned persistence details, and record-based domain payloads.

The application baseline is Spring Boot 4 with Spring AI 2. Model clients are constructed explicitly by `ChatModelRouter`: Ollama and OpenAI-compatible endpoints use their corresponding providers, while `DEEPSEEK` uses Spring AI's dedicated DeepSeek provider so typed reasoning content survives explicit tool-call turns. Magenta does not depend on locally patched Spring AI artifacts.

Source anchors:

- Web/API entry points: [`api/web`](../../src/main/java/io/mindspice/magenta2/api/web)
- Chat core: [`ai/chat`](../../src/main/java/io/mindspice/magenta2/ai/chat)
- Runtime orchestration: [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime)
- Workflow engine: [`ai/orchestration/workflow`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow)
- Workspaces and outputs: [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces) — `WorkspaceDirectoryService` constructs trusted `WorkspaceDescriptor` values; `WorkspacePathLayout` owns structural paths; explorer, metadata, action logging, execution, tools, and output services consume descriptors.
- Agent context and materialization: [`ai/orchestration/context`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/context) — `AgentContextService` owns versioned defaults, insert-only immutable bindings, and context capture; `AgentMaterializationService` owns generation-safe project materialization lifecycle.
- System chat: [`ai/chat/system`](../../src/main/java/io/mindspice/magenta2/ai/chat/system) — `SystemChatService` provides a fixed deny-by-default profile with bounded reads, restricted tools, and user-confirmable queue-operation proposals. Runtime settings currently supply enabled state and model; the prompt, callback allowlist, and context bound are server-owned constants.
- AI configuration: [`ai/config/user`](../../src/main/java/io/mindspice/magenta2/ai/config/user)
- Schema: [`schema.sql`](../../src/main/resources/schema.sql) and ordered migrations 001-020 in [`db/migrations`](../../src/main/resources/db/migrations)

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
- Assignment execution: `AssignmentService` holds queue state, then `OrchestrationRunnerService` executes task/workflow/job boundaries through `TaskService`, `WorkflowRunner`, `JobService`, lifecycle/fencing, workspace, skill snapshot, and artifact publication services.
- Artifact read: `/api/artifacts*` -> `OutputController` -> scoped search/preview/retention services -> `output_publications` plus digest-verified confined bytes. Unsupported FTS5 and integrity failures remain explicit unavailable states.
- Files/run UI: `/agents/_detail/{agentId}/files` renders `WorkspaceExplorerFragments`; its independent run host calls the thin `OrchestrationController` adapter -> `RunSummaryService` -> `RunOperationsFragments`. No persistence edge enters `WorkspaceExplorerService`.

## Package Boundaries

`api.web` owns transport shape: routes, status mapping, SSE emission, HTMX fragments, and browser shell compatibility. It delegates use-case logic into services.

`ai.chat` owns conversational behavior: request resolution, model selection, streaming turns, memory/audit persistence, context compaction, tool calls, plan mode state, and task execution through chat.

`ai.chat.plan` owns saved plan definitions, plan lifecycle, plan runs, input/output field definitions, completion/validation helpers, and plan-to-task template behavior.

`ai.chat.task` owns reusable task definitions, draft task creation from chat, task runs, task field metadata, and task execution records.

`ai.chat.tool` owns approved chat tools and their service boundaries: file, shell, web, plan-save, task, question, and transcript helpers. Tool allowlists are validated through `ChatToolRegistry`.

`ai.config.user` owns file-backed model endpoint configuration. Runtime settings and durable agent profiles live in orchestration packages; file config remains the source for model endpoint definitions and legacy seeding.

`ai.orchestration.agents` owns durable agent profiles and initial seeding from legacy file config.

`ai.orchestration.runtime` owns durable assignments, jobs, revisioned/fenced schedules and event reactions, direct-line agent inbox messages, orchestration events, projects, runtime execution coordination, rebuildable run summaries, and per-invocation measurements. Native family records remain status authority.

`ai.orchestration.workflow` owns workflow definitions, v2 routed node graphs, validation, workflow run state, node runs, workflow-owned inbox approvals, and workflow execution.

`ai.orchestration.workspaces` owns filesystem-first workspace identity (three closed kinds: `AGENT`, `CHAT`, `USER`), trusted descriptor/run-location resolution, confined file behavior, `AGENTS.md`, create-only output publication, reconciliation, scope-constrained extraction/search/preview/lineage, and retention. Projects have no root authority.

`ai.orchestration.settings` owns persisted runtime defaults such as default agent/model, chat models, tool policy, context limits, and assignment history purge settings.

## Operational Task-Run, Skill, And Observability Layers

The execution path is split into explicit layers rather than one generic workspace/runtime model:

- **Context and binding layer** owns agent-scoped versioned context defaults (`MATERIALIZATION` or `PERSONAL` area selection), generation-safe project materializations (`PROVISIONING`/`READY`/`FAILED`/`REVOKED`), insert-only immutable bindings, and durable assignment-binding operations linking capture, assignment, and lifecycle reservation. Materialization `READY` means safe local availability, not acquired content.
- **Task-run lifecycle** reserves canonical native run identity, attempts, retry/replay lineage, assignment fences, writer claims, and trusted workspace-relative run locations. Native task/workflow/job rows remain status truth.
- **Artifact publication/access** owns idempotent reservation, run-local staging, create-only promotion, digest/size integrity, reconciliation, scoped search/preview/download/lineage, and retention blockers.
- **Skill control plane** resolves digest-qualified candidates and exact overlays, then separates trust decisions, immutable snapshots, activation, revocation, executable authorization, and package/resource audit. Model-visible instructions come only from approved snapshots. Prompt integration uses fixed provenance order with labelled untrusted guidance.
- **Observability** rebuilds `run_summaries` from native facts and records one `run_measurements` row per model/tool invocation. Missing facts stay unavailable.
- **Automation** stores revisioned schedules and reactions with fenced claims, immutable firing/dispatch snapshots, append-only attempts, operation-key digest idempotency, causation, dedupe, and suppression.
- **System chat** provides a fixed deny-by-default profile per agent with bounded agent-status, workspace-metadata, and assignment-lifecycle reads, a restricted tool registry, and persisted user-confirmable queue-operation proposals confirmed through the ordinary lifecycle control plane.

The web layer composes these through independent HTMX hosts. It does not inject persistence-backed concerns into `WorkspaceExplorerService`.

## Persistence Shape

SQLite is initialized by `SchemaMigrationRunner` from `schema.sql` and immutable ordered migration resources. Some older, unrelated repositories still retain compatibility columns for warm databases; migration-owned structures must not regain repository-local DDL.

The schema groups into these domains:

- Chat and audit: `ai_chat_memory`, `ai_chat_session_metadata`, `audit_event`, `agent_jobs`.
- Plan/task and runs: `plan_definitions`, `plan_runs`.
- Workflows: `workflow_definitions`, `workflow_runs`, `workflow_node_runs`, workflow-owned `inbox_messages`.
- Runtime agents and assignments: `agent_profiles`, `orchestration_jobs`, `orchestration_job_items`, `work_assignments`, `assignment_conversation_links`, `agent_inbox_messages`, and `orchestration_events`.
- Task-run lifecycle: `task_run_locations`, `task_run_attempts`, scoped idempotent operations, native/child links, assignment fence generations, and writer-claim generations.
- Artifact system: `output_publications`, extraction/FTS capability structures, lineage edges, retention policies/pins, and active artifact sessions.
- Skill control plane: candidate versions/observations, exact overlays, trust decisions, immutable snapshots/resources, activations, executable authorizations, package operations, and resource execution audit.
- Observability and automation: `run_summaries`, `run_measurements`, revisioned schedule definitions/claims/firings/attempts, and reaction definitions/dispatches/attempts.
- Jobs/projects/workspace metadata/settings: `job_definitions`, `job_runs`, `job_recurrences`, `projects`, memberships/events, file labels/actions, compatibility `run_output_artifacts`, and `runtime_settings`.

See [Data Model](data-model.md) and the historical [Schema Migrations 001–009](migrations-001-009.md) guide. Ordered resources are contiguous through `020-project-chat.sql`: versions 010-012 add materialization/context/binding persistence, version 013 adds system-chat proposals, version 014 adds project assignment authority, versions 015-016 add lightweight work tasks and their corrective integrity repair, version 017 adds job reassignment operations, version 018 adds immutable project template revisions plus V2 materialization generations, version 019 adds project automation/event attribution, and version 020 adds immutable project-chat metadata columns and indexes. Schedule/reaction hardening remains immutable version 008, while version 009 records the runner-owned warm-bookkeeping compatibility release without changing schema end state.

## Streaming Model

Magenta uses Spring `SseEmitter` for browser-visible streaming. Chat streams emit token/tool/context/terminal events from `ChatStreamEvent`. Saved plan/task/workflow public stream endpoints currently submit durable assignments and emit `submitted` or `failed`, rather than executing the model inline. Agent side-panel chat streams agent-scoped chat events from the same chat service path.

See [API Reference](api-reference.md) for route-level SSE details.

## Frontend Shape

The frontend is server-rendered. SimplyPages renders the main shell and operational pages; HTMX drives standard CRUD, tab, panel, list, run expansion, and fragment refresh interactions. Files and persistence-backed run metadata are independently composed at stable hosts. JavaScript is limited to behavior where persistent browser state or SSE interaction is simpler than HTMX alone: chat streaming, shared HTMX/error shell helpers, orchestration dashboard polling/fragment helpers, entity side-panel interactions, and agent chat islands.

See [Frontend HTMX](frontend-htmx.md) for file-level guidance.
