# Chat, Planning, and Tasks

This page explains the implemented chat/planning/task stack. Source anchors are [`ai/chat`](../../src/main/java/io/mindspice/magenta2/ai/chat), [`ChatController`](../../src/main/java/io/mindspice/magenta2/api/web/ChatController.java), [`PlanController`](../../src/main/java/io/mindspice/magenta2/api/web/PlanController.java), and [`TaskController`](../../src/main/java/io/mindspice/magenta2/api/web/TaskController.java).

## Conversation State

Conversation content is stored in `ai_chat_memory` through `ChatMemoryRepository` and exposed as `ChatHistory`. Session metadata is stored in `ai_chat_session_metadata` through `ChatSessionMetadataRepository` and exposed as `ChatSession`/`ChatSessions`.

Session metadata includes:

- Current model and planning model.
- Title, favorite, and archived state.
- Active task run id.
- Origin and agent id for agent-scoped conversations.
- Updated timestamp.

`AuditService` writes a separate append-only audit trail in `audit_event`, including messages, tool calls, compaction/context snapshots, and errors. This is used for diagnostics and transcript visibility separate from the user-facing chat memory.

## Chat Turn Lifecycle

`ChatController` receives `ChatRequest.MsgRequest` or `ChatRequest.CmdRequest`. `RequestResolver` normalizes conversation/model context into `ResolvedChatRequest`. `ChatService` then:

1. Records user input.
2. Resolves model settings through `ChatModelRouter`.
3. Assembles prompt context and context usage through `ContextManagementAdvisor`.
4. Applies tool access policy and approved tool registry.
5. Streams model output and tool activity.
6. Persists assistant messages, audit events, context usage, and any plan/task state changes.

Streaming events are represented by `ChatStreamEvent` and sent over `/api/chat/stream` and `/api/chat/{conversationId}/plan/execute/stream`. Expected event names are `start`, `chunk`, `tool`, `system`, `interrupt`, `context`, `outputs_changed`, `done`, and `error`.

`outputs_changed` is a side-channel invalidation signal carrying only `conversationId`. It is emitted mid-stream after a recognized file-mutating tool (`file_write`, `file_append`, `file_replace`, or `file_delete`) returns a completed, attributable result. The browser client uses this to refresh the Outputs panel while the model continues working, without waiting for stream completion. This is additive and ignored by consumers that do not recognize it. The existing terminal refresh on `done` and error recovery remains intact as a fallback.

## Anonymous Chat Planning

The `/chat` planning path is anonymous in-chat planning. It is conversation-scoped, uses `PlanMode` and `ChatPlanState` in `ai.chat.model`, and persists a `SESSION_PLAN` keyed by the chat conversation id. It is not a saved `/plans` definition and cannot be saved or submitted as a task template.

`PlanService` owns:

- Session plan creation and retrieval.
- Three backend-seeded opening questions for goal, assumptions/details/constraints/approach, and expected deliverables.
- Pending follow-up planning questions and current question index.
- Plan answer recording.
- Plan approval, continue, cancel, anonymous execution, deletion, and final message state.
- Execution evidence and validation feedback.
- Persistent chat file directory resolution under `data/chats/<conversationId>/files/`.

Anonymous plan prompts do not expose structured inputs or outputs. They focus on goal, assumptions, expected deliverables, ordered steps, and validation. Approval actions are continue planning, approve and execute with conversation context, approve and execute clean, and cancel.

`/api/chat/commands` accepts `/plan` with optional inline text. When inline text is present, `ChatController` starts anonymous plan mode and `ChatService.beginPlan(..., userInstruction)` records that text as the first backend-seeded goal answer before returning the next prompt state. This keeps command handling model-free while supporting the natural `/plan research this topic` chat flow.

When an anonymous plan has a pending prompt question, `/chat` renders the question as a compact prompt card directly above the main chat composer. The main `#chat-input` is the answer field for that state and submits to `/api/chat/{conversationId}/plan/answers`; slash-prefixed text is treated as answer content while the question is active, not as a command.

After the final queued prompt answer is recorded, `ChatService` immediately asks the planning model to continue from the structured seed context. If that continuation fails because the model request fails, a planning tool call cannot complete, or the worker thread is interrupted after draft edits, the answer remains saved and the route returns a controlled assistant message instead of propagating a servlet error. The answered prompt is not re-queued; when the draft would otherwise have no next action, Magenta queues a recovery clarification so the UI remains in a recoverable planning state after the unavailable model, credential, or tool dependency is corrected.

If a duplicate or stale answer arrives after the server has already consumed the active question or advanced to a later question, `ChatService` treats it as a recoverable plan-state refresh while the conversation remains in `PLAN` mode. It returns the current plan state with the active prompt or a recovery clarification rather than surfacing `No active planning question exists for this conversation` or `Stale planning answer` as a terminal browser error.

Anonymous execution installs a chat-scoped file context when there is no assignment context. File tools then resolve to the active chat file directory instead of a broad data-root fallback. Validator-approved final anonymous execution messages are persisted as markdown files in that directory. Clean execution is prompt-scoped: it omits stored chat messages from the model prompt for that run, while preserving the persisted transcript and appending the execution turn afterward.

Anonymous execution completion is gated by `plan_complete`. `PlanCompletionService` records the latest execution report, checks per-criterion evidence coverage, carries forward artifact paths previously recorded through `plan_report`, and asks the configured planning validator model to compare the approved plan, evidence, artifact contents, prior feedback, and proposed final message. Validator input sections are framed as untrusted data and the validator request is isolated from broad chat history. Durable validation feedback records the validator model used, records that model validation was skipped when a fail-closed preflight rejected completion before the model call, or fails closed if no planning validator model can resolve. Completion validation does not fall back to the execution model. On pass, the plan becomes `COMPLETED` and the stored final message is the only trusted user-facing completion. On failure, remediation stays in the execution tool loop; if the model exhausts completion repair without a validator-passed `plan_complete`, `ChatService` marks the plan `NEEDS_REVIEW` and persists a controlled review message instead of ordinary assistant text.

For anonymous chat plans, relative artifact paths supplied to `plan_report` or `plan_complete` are resolved against `data/chats/<conversationId>/files` first, then `dataRoot`. Absolute paths and fallback paths must still stay inside `dataRoot`. This lets validator input include files written by chat-scoped file tools without requiring the model to spell the full `chats/<conversationId>/files/...` path.

Anonymous execution is single-flight per conversation. `ActiveTurnRegistry` rejects a second saved-plan execution request for the same conversation while an execution turn is active, and the `/api/chat/{conversationId}/plan/execute` and `/api/chat/{conversationId}/plan/execute/stream` routes return `409 Conflict` before marking the plan executing.

Plan execution SSE transport errors are tracked separately from domain execution errors. If the browser closes or the SSE response breaks while anonymous execution is still running, `ChatController` records a `plan_stream_disconnect` audit diagnostic and stops sending events to that client without calling `recordExecutionFailure`. Underlying model/tool execution errors still record `plan_stream_error` and can move the plan to `NEEDS_REVIEW`.

History reload and completed execution stream finalization use a non-compacting context usage snapshot. These paths must not call the summary/compaction model or mutate stored transcript state. If stored context is already over the compaction trigger, reload can still report the over-budget usage while preserving the completed plan transcript and artifacts; the next model-backed send remains responsible for prompt-time compaction, trim, or fail-closed handling.

Model/provider response extraction failures are treated as transient when the exception chain contains `ResourceAccessException` or `IOException`, including wrapped `RestClientException` cases from a closed response body. Tool turns use the existing conversation snapshot/restore retry path for those failures.

Tool-call argument JSON is preflight validated before Spring AI tool execution. If any tool call in a model batch has malformed argument JSON, none of the tools in that batch execute. Magenta persists and streams compact synthetic tool diagnostics, records audit detail for the rejected calls, adds a system control message telling the model which call failed parsing, and continues the tool loop so the model can retry. These recovered parser failures do not directly update plan status, execution evidence, or validation feedback.

If Spring AI accepts the raw JSON but fails to convert it into the Java tool parameter types, Magenta treats that as the same class of recoverable tool-call diagnostic. The failed tool call is recorded as an error transcript, the model receives a control message with the conversion failure, and the turn continues so PLAN mode can still end in a queued question or approval state.

`ask_user_questions` accepts both simple string questions and object-shaped entries with fields such as `question`, `text`, `prompt`, or `label`. The server normalizes those entries to plain question text before calling `PlanService` or `TaskService`, because some models emit UI-like question objects with headers and free-response metadata.

`plan_report` and `plan_complete` accept both string lists and object-shaped evidence lists. Objects with `criterion` and `evidence` fields are converted to the per-criterion evidence string format expected by completion preflight. Deviation, unmet-criterion, and artifact lists are also normalized from simple objects, and `artifacts` is accepted as an alias for `artifactPaths`.

`NEEDS_REVIEW` is an execution-review state, not draft planning. `PlanService.mode(...)` resolves it as `NORMAL` while `ChatPlanState.status` remains `NEEDS_REVIEW`, so clients can show evidence and validation feedback without reinstalling PLAN-mode prompts, tools, or planning controls.

## Saved Plan Chat

Saved task planning happens in `/plans` and is separate from `/api/chat`, `ai_chat_memory`, and `ai_chat_session_metadata`. It is plan-scoped under `/api/plans`, stores messages in `plan_chat_messages`, and produces durable `TASK_TEMPLATE` definitions for later submission.

`SavedPlanChatService` owns:

- Creating a `TASK_TEMPLATE` draft directly for new saved plan chats.
- Persisting plan-scoped chat messages.
- Seeding four backend questions in a fixed order: runtime inputs, goal, high-level deliverables, and structured outputs.
- Passing the opening answers to a plan-id-scoped saved-plan model turn as seed context, not final field values.
- Continuing saved-plan chat turns through saved-plan-specific tools that update typed inputs, typed outputs, deliverables, assumptions, steps, and validation details on the saved draft.
- Enforcing saved-plan planning terminal states: either queued follow-up questions or a draft marked ready for approval.

Saved plan model turns do not use `/api/chat`, `ai_chat_memory`, or `ai_chat_session_metadata`. They use `plan_chat_messages`, the current `PlanDefinition`, and saved-plan-specific tool callbacks scoped by plan id.

Saved plan chat uses the same prompt-card visual pattern for pending questions, but keeps its own HTMX `#plan-chat-form` and `#plan-chat-input` submission flow under `/plans/_editor/{planId}/planning-chat/answers`.

`PlanDefinition` is the durable plan contract for saved definitions. It contains title, summary, goal, notes, deliverables, structured inputs/outputs, assumptions, ordered steps, validation criteria, evidence, feedback, planning/execution model choices, settings overrides, pending questions, and conversation linkage.

The `/api/chat/{conversationId}/plan/*` routes operate on anonymous session plans. The `/api/plans` routes operate on saved definitions and plan-scoped saved chats.

## Task Drafts

Task drafts are chat-backed task creation sessions owned by `TaskService`.

Routes:

- `POST /api/tasks/drafts/{conversationId}` starts a draft.
- `GET /api/tasks/drafts/{conversationId}` returns the active draft.
- `POST /api/tasks/drafts/{conversationId}/answers` records an answer.
- `POST /api/tasks/drafts/{conversationId}/approve` promotes the draft into a `TaskDefinition`.

Draft state is represented by `TaskDraft` and `TaskDraftStatus`. Draft answers are captured as structured task definition fields rather than freeform blob-only text.

## Saved Definitions

Saved plans and task-like definitions are durable records used by chat, operational pages, and assignment submission.

- `PlanController` exposes the unified saved plan/task API under `/api/plans`.
- `TaskController` exposes task-specific definition/draft/run routes under `/api/tasks`.
- Public run controls submit saved definitions to an agent assignment. They do not directly run arbitrary model execution from the controller.

Plan/task fields use record types such as `PlanFieldDefinition`, `TaskFieldDefinition`, `PlanStep`, and `TaskStep`. Field value types come from `PlanFieldType` and `TaskValueType`.

## Runs and Evidence

Plan runs and task runs preserve execution history:

- `PlanRun` stores input values, output values, definition snapshot, workspace/output paths, evidence, validation feedback, deliverable evidence, final/error messages, status, and timestamps.
- `TaskRun` stores task-specific run state and is used by task execution paths.

Runs snapshot their definitions so edits after execution do not rewrite historical meaning. During execution, task outputs publish with create-only placement beneath `tasks/<taskPathKey>/<taskRunId>/outputs/`; staging is a lazy sibling owned by publication. No legacy `runs/<runId>` hierarchy is created. Canonical task publication and integrity state live in `output_publications`; `run_output_artifacts` is compatibility metadata for legacy/non-task rows.

## Submit-To-Agent Semantics

Public plan/task run routes now submit durable assignments:

- `POST /api/plans/{planId}/submit`
- `POST /api/plans/{planId}/runs/stream`
- `POST /api/tasks/{taskId}/runs/stream`

The controller resolves an active agent when no `agentId` is supplied, defaults priority to `9`, builds `AssignmentRequest`, and calls `AssignmentService.create` with `AssignmentType.TASK_RUN`. The SSE stream returns `submitted` or `failed` and then completes.

Submission payloads carry `projectId` for attribution metadata and `workspaceType`/`workspaceKey` together for workspace selection. Omission defaults to the bound agent. `projectId` is metadata only and does not select or create a filesystem root. Anonymous plan-chat execution does not expose workspace selection controls.

Actual execution is handled later by orchestration runner services. This keeps HTTP request handling short and makes queued work observable/cancellable.

## Model and Tool Routing

Model choices come from both file-backed AI config and runtime settings:

- File config in `ai.config.user` defines model endpoints and legacy agent defaults.
- `RuntimeSettingsService` provides default model, planning model, summary/compaction model, system chat settings, and tool defaults.
- `ChatModelRouter` resolves concrete model clients for chat turns.

Tools are controlled by `ChatToolRegistry` and per-agent approved tools. Shell execution also consults agent shell command allowlists and the explicit unsafe wildcard override from file config.

## Interruption and Active Turns

`ConversationTurnCoordinator`, `ActiveTurnRegistry`, and related `ai.execution` records track active work and interrupt status. `/api/chat/turns/{turnId}/interrupt` accepts a conversation id, interrupt token, and message to request interruption of an active turn.

The orchestration runtime can also force-interrupt agent queue work through operational routes, but durable assignment state remains owned by `AssignmentService`.
