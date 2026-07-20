# API Documentation Index

This index owns route, payload, streaming, and integration contracts for Magenta APIs. The detailed contributor reference is [`../technical/api-reference.md`](../technical/api-reference.md).

Security summary: alpha routes are currently open at the application layer. Request safety currently relies on route semantics, controller/service validation, and bounded runtime/tooling safeguards. See [`../technical/security.md`](../technical/security.md).

## API Families

| Family | Purpose | Source |
| --- | --- | --- |
| `/api/chat` | Chat turns, SSE streaming, sessions, history, chat file listing/downloads, commands, interrupts, pending messages, and anonymous conversation plan controls. | [`ChatController`](../../src/main/java/io/mindspice/magenta2/api/web/ChatController.java), [`ChatFileController`](../../src/main/java/io/mindspice/magenta2/api/web/ChatFileController.java) |
| `/api/fragments` | HTMX chat transcript/session/planning fragments. | [`FrontendFragmentController`](../../src/main/java/io/mindspice/magenta2/api/web/FrontendFragmentController.java) |
| `/api/plans` | Saved plan/task-like definitions, saved plan chat, submit-to-agent, and plan run reads. | [`PlanController`](../../src/main/java/io/mindspice/magenta2/api/web/PlanController.java) |
| `/api/tasks` | Task definitions, chat-backed task drafts, draft approval, and task run reads/submission. | [`TaskController`](../../src/main/java/io/mindspice/magenta2/api/web/TaskController.java) |
| `/api/workflows`, `/api/workflow-runs`, `/api/users/inbox` | Workflow definitions, validation, assignment submission, run reads/resume, and workflow approval inbox. | [`WorkflowController`](../../src/main/java/io/mindspice/magenta2/api/web/WorkflowController.java) |
| `/api/jobs`, `/api/job-runs` | Job definitions, ordered job items, assignment submission, runs, cancellation, outputs, events, and recurrence. | [`JobController`](../../src/main/java/io/mindspice/magenta2/api/web/JobController.java) |
| `/api/agents` | Agent profile CRUD. | [`AgentProfileController`](../../src/main/java/io/mindspice/magenta2/api/web/AgentProfileController.java) |
| `/api/agents/{agentId}` | Agent inbox, assignments/history/lifecycle, revisioned schedules, event reactions, and agent side-panel chat SSE. See [Schedule and Reaction API](schedules-reactions.md). | [`AgentOrchestrationController`](../../src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java) |
| `/api/projects` | Projects, memberships, network view, and events. | [`ProjectController`](../../src/main/java/io/mindspice/magenta2/api/web/ProjectController.java) |
| `/api/skills` | Skill repository management, candidates, exact overlays, trust decisions, immutable snapshots, activation, and explicit executable/package unavailable states. See [Skill API](skills.md). | [`SkillController`](../../src/main/java/io/mindspice/magenta2/api/web/SkillController.java) |
| `/api/workspace/{type}/{id}` | Typed workspace file explorer: list, preview, image view, download, text/Markdown create/save, directory create, rename, move, copy, delete preflight/execute, labels, tags, and recent actions for agent, chat, and user workspaces. | [`WorkspaceController`](../../src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java) |
| `/api/workspace/user` | User workspace collection: `GET` lists valid non-symlink user workspace directories; `POST` creates a named user workspace. | [`WorkspaceController`](../../src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java) |
| `/api/artifacts`, `/api/runs/{taskRunId}/retention` | Scoped artifact metadata/search/preview/download/lineage, pins, and guarded artifact/run retention. FTS5 and byte-integrity unavailable states fail closed. See [Artifact API](artifacts.md). | [`OutputController`](../../src/main/java/io/mindspice/magenta2/api/web/OutputController.java) |
| `/agents/_detail/{agentId}/runs` | Server-rendered bounded run groups and scoped run expansion for the Files surface. | [`OrchestrationController`](../../src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java), [`RunOperationsFragments`](../../src/main/java/io/mindspice/magenta2/api/web/RunOperationsFragments.java) |
| `/api/runtime/*`, `/runtime/*` | JSON runtime summary/health/measurement/audit reads and separate HTMX detail reads. See [Runtime Observability API](runtime-observability.md). | [`RuntimeController`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java), [`RuntimeHealthFragments`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeHealthFragments.java) |
| `/skills` | Server-rendered Agent Skills browser/editor with HTMX list, detail, diagnostics, file editing, guided creation, and assignment fragments. | [`SkillFragments`](../../src/main/java/io/mindspice/magenta2/api/web/SkillFragments.java) |
| `/api/dashboard/summary` | Operational dashboard read model. | [`DashboardController`](../../src/main/java/io/mindspice/magenta2/api/web/DashboardController.java) |
| `/api/runtime/status` | Runtime status summary. | [`RuntimeController`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java) |
| `/api/settings/runtime` | Persisted runtime settings read/update. | [`RuntimeSettingsController`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeSettingsController.java) |
| `/api/models` | Configured model summaries. | [`ModelController`](../../src/main/java/io/mindspice/magenta2/api/web/ModelController.java) |
| `/selectors` | Read-only entity selector options/selected/validation fragments used by operational UI. | [`selector`](../../src/main/java/io/mindspice/magenta2/api/web/selector) |

## Workspace Identity Routes

Workspace operations use typed routes under `/api/workspace/{type}/{id}` where `{type}` is `agent`, `chat`, or `user`. User workspace collection uses `GET|POST /api/workspace/user`. The `/api/work-areas` and `/api/workspaces` (generic) route families are absent, not deprecated.

Execution payloads carry `workspaceType` and `workspaceKey` together. Omission defaults to the bound agent. `chat` workspace selection is rejected.

## Streaming Endpoints

| Route | Stream Purpose | Events |
| --- | --- | --- |
| `POST /api/chat/stream` | Live chat turn. | `start`, `chunk`, `tool`, `system`, `interrupt`, `context`, `done`, `error` |
| `POST /api/chat/{conversationId}/plan/execute/stream` | Anonymous session plan execution stream. Accepts optional `clearContext=true`. | Chat/plan execution events from `ChatController` |
| `POST /api/plans/{planId}/runs/stream` | Submit saved plan/task-like definition to an agent assignment. | `submitted`, `failed` |
| `POST /api/tasks/{taskId}/runs/stream` | Submit saved task to an agent assignment. | `submitted`, `failed` |
| `POST /api/workflows/{workflowId}/runs/stream` | Submit saved workflow to an agent assignment. | `submitted`, `failed` |
| `POST /api/agents/{agentId}/chat/stream` | Agent-scoped side-panel chat. | `start`, `done`, `error` |

## Error Conventions

Controllers generally map:

- Invalid request/body/lifecycle input to `400`.
- Missing records to `404`.
- Invalid lifecycle conflicts to `409`.

HTMX failures typically return fragment-friendly HTML for in-panel rendering; JSON/API failures return JSON or Spring error responses depending on the controller path. Operational run/artifact/skill/runtime/automation fragments preserve request scope and replace only their stable host; no fragment response contains absolute paths, claim secrets, lease owners, writer fences, or tokens.
