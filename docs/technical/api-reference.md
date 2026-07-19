# API Reference

This page maps implemented API families to their controllers, domain services, payload records, security expectations, and stream behavior. The route index in [`../api/00-index.md`](../api/00-index.md) is the shorter integration entry point.

In current alpha posture, routes are open at the application layer. Use controller/service validation and route semantics as the contract for expected failures. See [`security.md`](security.md) for the current safety posture.

## Chat: `/api/chat`

Source: [`ChatController`](../../src/main/java/io/mindspice/magenta2/api/web/ChatController.java), [`ChatFileController`](../../src/main/java/io/mindspice/magenta2/api/web/ChatFileController.java), [`ChatRequest`](../../src/main/java/io/mindspice/magenta2/ai/chat/model/ChatRequest.java), [`ChatResponse`](../../src/main/java/io/mindspice/magenta2/ai/chat/model/ChatResponse.java), [`ChatStreamEvent`](../../src/main/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEvent.java), [`ChatService`](../../src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java), [`ChatFileService`](../../src/main/java/io/mindspice/magenta2/ai/chat/service/ChatFileService.java).

- `POST /api/chat`: non-streaming chat turn from `ChatRequest.MsgRequest`.
- `POST /api/chat/stream`: SSE chat turn. `ChatRequest.MsgRequest` accepts an optional `surface` field so the browser chat page can tag sessions separately from internal assistant use. Known surface values (`BROWSER`, `INTERNAL`) are accepted case-insensitively; blank or unknown values are rejected.
- `GET /api/chat/{conversationId}/pending-messages`: list visible browser pending-message rows ordered FIFO.
- `POST /api/chat/{conversationId}/pending-messages`: enqueue a normal browser message for later delivery. Payload is `PendingMessageRequest(message, model, planningModel, surface)` and returns the refreshed queue list.
- `POST /api/chat/{conversationId}/pending-messages/claim`: atomically claim the oldest pending row and return `ClaimedPendingChatMessage(message, claimToken)`, or `null` when empty.
- `POST /api/chat/{conversationId}/pending-messages/{messageId}/ack`: delete a claimed row only when the supplied `claimToken` matches.
- `POST /api/chat/{conversationId}/pending-messages/{messageId}/release`: return a matching claimed row to pending status after a failed queued send.
- `POST /api/chat/{conversationId}/plan/execute`: execute an approved anonymous session plan. Payload may set `clearContext=true` for clean execution.
- `POST /api/chat/{conversationId}/plan/execute/stream`: SSE execution of the current anonymous session plan path. Payload may set `clearContext=true` for clean execution.
- `POST /api/chat/turns/{turnId}/interrupt`: interrupt an active turn. The browser `/chat` composer does not use this route for ordinary mid-turn messages; it uses the pending-message queue instead.
- `GET /api/chat/sessions`, `GET /api/chat/{conversationId}/history`: session/history reads.
- `GET /api/chat/{conversationId}/files`: list ordinary chat files from `chats/<conversationId>/files/` as `ChatFileListing`.
- `GET /api/chat/{conversationId}/files/download?path=<relativePath>`: download one ordinary chat file as an attachment.
- `PATCH /api/chat/{conversationId}/title`, `/favorite`, `/archive`: session metadata updates.
- `POST /api/chat/commands`: command request using `ChatRequest.CmdRequest`.
- `DELETE /api/chat/{conversationId}`: delete a conversation.
- Plan controls under `/api/chat/{conversationId}/plan/*`: answer, approve, continue, cancel, execute, delete. Anonymous chat plans cannot be saved as task templates.

SSE events are JSON events. Chat emits `start`, `chunk`, `tool`, `system`, `interrupt`, `context`, `done`, and `error` from `ChatStreamEvent`; the controller also emits plan-execution updates around anonymous plan execution. Anonymous plan execution reports `planState.status=COMPLETED` only after validator-gated `plan_complete` succeeds. If automatic completion repair is exhausted, `done.planState.status` is `NEEDS_REVIEW` and the response text is a controlled review notice, not the model's unvalidated completion text.

`GET /api/chat/sessions` includes `outputCount` on each session for regular files under that conversation's persistent chat file directory and only returns browser-surface chats in normal mode. Chat file listing and download routes require a UUID-shaped existing conversation id, expose only relative paths, reject traversal, and enforce the same 10 MB controller download limit used by output artifacts.

Common errors: validation failures return `400`; missing sessions/plans generally return `404`; active-turn conflicts or invalid lifecycle operations return controller-specific conflict/error payloads.

## Fragments: `/api/fragments`

Source: [`FrontendFragmentController`](../../src/main/java/io/mindspice/magenta2/api/web/FrontendFragmentController.java).

Fragment routes return `text/html` for HTMX refreshes:

- `GET /api/fragments/chat/transcript`
- `GET /api/fragments/chat/sessions`
- `GET /api/fragments/chat/planning`

They read chat state and render server HTML. They are public `GET` routes.

## Plans: `/api/plans`

Source: [`PlanController`](../../src/main/java/io/mindspice/magenta2/api/web/PlanController.java), [`PlanDefinition`](../../src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanDefinition.java), [`PlanRun`](../../src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanRun.java), [`PlanService`](../../src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanService.java).

- `GET /api/plans`, `GET /api/plans/{planId}`: list/read saved definitions.
- `POST /api/plans`, `PUT /api/plans/{planId}`, `DELETE /api/plans/{planId}`: definition CRUD.
- `POST /api/plans/{planId}/finalize-task`: resave/finalize a plan as a task-like definition.
- `POST /api/plans/{planId}/submit`: create a `TASK_RUN` assignment for an agent.
- `GET /api/plans/{planId}/chat-prompt`: produce a prompt for continuing work in chat.
- `GET /api/plans/{planId}/runs`, `GET /api/plans/runs/{runId}`: run reads.
- `POST /api/plans/{planId}/runs/stream`: SSE submit-to-agent path.
- `POST /api/plans/planning-chats`: create a saved draft and start a saved plan chat.
- `POST /api/plans/{planId}/planning-chat/start`: seed or restart the saved plan chat for an existing plan, or pass a supplied instruction into the saved-plan model turn.
- `POST /api/plans/{planId}/planning-chat/answers`: answer saved plan chat opening or follow-up questions. After opening questions complete, answers are passed to the saved-plan model as seed context instead of copied directly into fields.
- `POST /api/plans/{planId}/planning-chat/messages`: append a saved plan chat message and run the saved-plan model turn.
- `GET /api/plans/{planId}/planning-chat`: read saved plan chat state and messages.

Create/update payloads include title, summary, goal, notes, deliverables, inputs, outputs, assumptions, steps, validation criteria, work type/prompt profile, planning model, and execution model. `PlanRunRequest` accepts input values, conversation id, agent id, job id, `projectId`, `workspaceType`/`workspaceKey` pair, `runDisplayName` for non-job runs, model override, and priority. `SubmitRequest` accepts agent id, model override, priority, `projectId`, `workspaceType`/`workspaceKey` pair, and `runDisplayName` for non-job submissions. Saved plan chat input/output questions collect initial user context; the saved-plan model synthesizes typed field definitions with name, type, required flag, array flag, description, examples, and optional schema through plan-scoped tools.

`runs/stream` emits `submitted` with assignment metadata or `failed`. It does not stream inline model output. Assignment responses include first-class project metadata plus workspace type, key, and internally derived canonical identity where the route returns `WorkAssignment`; `projectId` never selects a filesystem root.

## Tasks: `/api/tasks`

Source: [`TaskController`](../../src/main/java/io/mindspice/magenta2/api/web/TaskController.java), [`TaskDefinition`](../../src/main/java/io/mindspice/magenta2/ai/chat/task/TaskDefinition.java), [`TaskDraft`](../../src/main/java/io/mindspice/magenta2/ai/chat/task/TaskDraft.java), [`TaskRun`](../../src/main/java/io/mindspice/magenta2/ai/chat/task/TaskRun.java), [`TaskService`](../../src/main/java/io/mindspice/magenta2/ai/chat/task/TaskService.java).

- `GET /api/tasks`, `GET /api/tasks/{taskId}`: list/read task definitions.
- `POST /api/tasks`, `PUT /api/tasks/{taskId}`, `DELETE /api/tasks/{taskId}`: task CRUD.
- `POST /api/tasks/drafts/{conversationId}`, `GET /api/tasks/drafts/{conversationId}`: begin/read a chat-backed task draft.
- `POST /api/tasks/drafts/{conversationId}/answers`, `/approve`: answer draft questions and promote a draft.
- `GET /api/tasks/{taskId}/runs`, `GET /api/tasks/runs/{runId}`: run reads.
- `POST /api/tasks/{taskId}/runs/stream`: SSE submit-to-agent path.

Task create/update payloads carry title, summary, goal, notes, input/output descriptions, structured fields, assumptions, steps, and validation criteria. `TaskRunRequest` accepts input values, conversation id, agent id, job id, explicit `projectId`, paired `workspaceType`/`workspaceKey` selection, `runDisplayName` for non-job runs, model override, and priority. Omitting both workspace fields defaults to the bound agent; partial pairs and chat selection are rejected. `runs/stream` emits `submitted` or `failed` with assignment metadata when accepted.

## Workflows: `/api/workflows`, `/api/workflow-runs`, `/api/users/inbox`

Source: [`WorkflowController`](../../src/main/java/io/mindspice/magenta2/api/web/WorkflowController.java), [`WorkflowDefinition`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowDefinition.java), [`WorkflowService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowService.java), [`WorkflowValidator`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowValidator.java).

- `GET /api/workflows`, `GET /api/workflows/{workflowId}`: list/read definitions.
- `POST /api/workflows`, `PUT /api/workflows/{workflowId}`, `DELETE /api/workflows/{workflowId}`: definition CRUD.
- `POST /api/workflows/validate`, `POST /api/workflows/{workflowId}/validate`: structured validation errors and warnings.
- `POST /api/workflows/{workflowId}/runs`: submit a `WORKFLOW_RUN` assignment.
- `POST /api/workflows/{workflowId}/runs/stream`: SSE submit path, emitting `submitted` or `failed`.
- `GET /api/workflows/{workflowId}/runs`, `GET /api/workflow-runs/{runId}`: run reads.
- `POST /api/workflow-runs/{runId}/resume`: resume paused approval/waiting runs through `WorkflowService`.
- `GET /api/users/inbox`, `POST /api/users/inbox/{messageId}/respond`: workflow-owned user approval inbox.

Workflow definitions include schema version, title, summary, max concurrency, nodes, routes, and UI layout. `WorkflowRunRequest` accepts agent id, job id, `projectId`, `workspaceType`/`workspaceKey` pair, `runDisplayName` for non-job runs, model override, and priority. Waiting workflow assignments remain `WAITING` and resumable instead of being treated as failed only because the workflow run is nonterminal. Workflow run records carry nullable agent/job/job-assignment/job-run/project/workspace/run-type attribution for output and context views.

## Jobs: `/api/jobs`, `/api/job-runs`

Source: [`JobController`](../../src/main/java/io/mindspice/magenta2/api/web/JobController.java), [`JobDefinition`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobDefinition.java), [`JobService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java).

- `GET /api/jobs?agentId=&projectId=&status=`: list definitions.
- `POST /api/jobs`, `GET /api/jobs/{jobId}`, `PUT /api/jobs/{jobId}`, `DELETE /api/jobs/{jobId}`: job definition CRUD.
- `GET/POST /api/jobs/{jobId}/items`, `PUT/DELETE /api/jobs/{jobId}/items/{itemId}`: ordered job item management.
- `POST /api/jobs/{jobId}/runs`: submit a `JOB_RUN` assignment.
- `GET /api/jobs/{jobId}/runs`, `GET /api/job-runs/{runId}`, `POST /api/job-runs/{runId}/cancel`: run reads and cancellation.
- `GET /api/jobs/{jobId}/outputs`, `GET /api/jobs/{jobId}/events`: derived output/event views.
- `POST /api/jobs/{jobId}/recurrence`, `GET /api/jobs/{jobId}/recurrence`: recurrence configuration.

Job definitions include owner agent, optional project, status, title, summary, items, prompt profile, model, and settings override JSON. `JobRunRequest` accepts agent id, model override, `projectId`, `workspaceType`/`workspaceKey` pair, and priority. Job item payloads reference task or workflow ids, model override, priority, retry count, continue-on-failure, and config JSON.

Job run reads are summary-backed where operator context is needed. The summary includes job definition id/title/status, assignment id/status/type/priority, agent and project labels, workspace type/key/identity, job run id/status, child run ids, output count, and lifecycle timestamps. Cancellation keeps assignment-owned job run lifecycle state synchronized.

## Agents: `/api/agents` and `/api/agents/{agentId}`

Source: [`AgentProfileController`](../../src/main/java/io/mindspice/magenta2/api/web/AgentProfileController.java), [`AgentOrchestrationController`](../../src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java), [`AgentProfileService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/agents/AgentProfileService.java), [`AssignmentService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java).

Profile routes:

- `GET /api/agents`, `POST /api/agents`, `GET/PUT/DELETE /api/agents/{agentId}`.
- `GET /api/agents/{agentId}/workspace`: agent workspace status.

Agent runtime routes:

- Inbox: `GET/POST /api/agents/{agentId}/inbox`, `POST /inbox/{messageId}/read`, `POST /inbox/{messageId}/handled`.
- Assignments: `GET /assignments`, `GET/DELETE /assignment-history`, `POST /assignments`, `POST /assignments/{assignmentId}/cancel|pause|resume`, `DELETE /assignments/{assignmentId}`.
- Schedules: `GET/POST /schedules`, `PUT/DELETE /schedules/{scheduleId}`. These return `404` when `magenta.features.schedules-enabled=false`.
- Event reactions: `GET/POST /event-reactions`, `PUT/DELETE /event-reactions/{reactionId}`. These return `404` when `magenta.features.reactions-enabled=false`.
- `POST /chat/stream`: agent-scoped SSE chat.

Agent chat emits `start`, `done`, and `error` events as assembled by `AgentOrchestrationController` from `ChatService` responses.

Assignment create/read payloads expose `projectId`, `workspaceType`, `workspaceKey`, and internally derived `workspaceIdentity` on `WorkAssignment`. `projectId` is metadata and membership context only; it does not select or create a filesystem root. Operational assignment summary fragments also expose linked run ids for diagnostics.

## Projects: `/api/projects`

Source: [`ProjectController`](../../src/main/java/io/mindspice/magenta2/api/web/ProjectController.java), [`ProjectService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java).

- `GET/POST /api/projects`, `GET/PUT/DELETE /api/projects/{projectId}`.
- `POST /api/projects/{projectId}/agents`, `GET /api/projects/{projectId}/agents`, `DELETE /api/projects/{projectId}/agents/{agentId}`.
- `GET /api/projects/{projectId}/network`, `GET /api/projects/{projectId}/events`.
- `GET /api/projects/{projectId}/workspace`, `POST /api/projects/{projectId}/workspace/release`.

Create accepts `name`/`description` or legacy-compatible `title`/`summary`, plus nullable legacy `ownerAgentId` and git repo URL. Projects are shared workspace and visibility records, not executable work units. Membership add/remove controls are available through project routes. Deletion and membership removal can return `409` when active assignments, leases, jobs, or runs still reference the project/member. Workspace release can return `409` when a lease cannot be released immediately.

## Workspaces: `/api/workspace/{type}/{id}` and `/api/workspace/user`

Source: [`WorkspaceController`](../../src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java), [`WorkspaceDirectoryService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java), [`WorkspaceExplorerService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java).

The workspace API uses typed routes under `/api/workspace/{type}/{id}` where `{type}` is `agent`, `chat`, or `user` and `{id}` is a single validated decoded path segment. Encoded separators and traversal syntax are rejected at the web boundary.

**User workspace collection:**

- `GET /api/workspace/user`: list valid, confined, direct non-symlink user workspace directories under `workspace/user/`. Returns `200` with array (may be empty).
- `POST /api/workspace/user`: create a named user workspace. Payload: `{"name": "string"}`. Returns `201` created, `400` invalid name, `409` collision.

**Typed workspace file operations:**

All item operations are rooted at `/api/workspace/{type}/{id}`. Unknown workspace types, invalid keys, missing entities, missing user declarations, and invalid file-relative paths map to `400` or `404` as appropriate. Create/rename/move/copy collisions map to `409`. All explorer mutation operations reject the workspace root and `outputs/` or descendants.

- `GET /api/workspace/{type}/{id}/files?path=`: list a confined workspace directory.
- `GET /api/workspace/{type}/{id}/files/preview?path=`: bounded text/Markdown preview.
- `GET /api/workspace/{type}/{id}/files/view?path=`: inline image view (png/jpg/jpeg/gif/webp).
- `GET /api/workspace/{type}/{id}/files/download?path=`: download a confined file up to 10 MB.
- `PUT /api/workspace/{type}/{id}/files/text?path=`: save an existing text file (hard edit limit 25 MB).
- `POST /api/workspace/{type}/{id}/files/text`: create a new `.txt` file (rejects overwrite).
- `POST /api/workspace/{type}/{id}/files/markdown`: create a new `.md` file (rejects overwrite).
- `POST /api/workspace/{type}/{id}/directories`: create a directory.
- `POST /api/workspace/{type}/{id}/files/rename`: rename a file or directory.
- `POST /api/workspace/{type}/{id}/files/move`: move with collision checks.
- `POST /api/workspace/{type}/{id}/files/copy`: copy with collision checks.
- `GET /api/workspace/{type}/{id}/files/delete/preflight?path=&step=`: deletion preflight (`INTENT`, `FILE_CONFIRM`, `DIRECTORY_RECURSIVE_CONFIRM`).
- `POST /api/workspace/{type}/{id}/files/delete`: execute deletion after required confirmation stage.
- `GET /api/workspace/{type}/{id}/files/labels?path=`, `POST`, `DELETE`: list/add/remove file labels.
- `POST /api/workspace/{type}/{id}/tags`: create or return a reusable tag label.
- `GET /api/workspace/{type}/{id}/files/actions/recent?limit=`: recent action log rows keyed by canonical workspace identity.

Explorer routes reject traversal, absolute paths, symlink path components, symbolic recursive trees, workspace-root mutation, and mutation of `outputs/` or descendants. Agent/chat roots with missing physical directories return empty listings; missing user workspaces return `404`. File-producing mutations may lazily create agent/chat roots after descriptor resolution.

The `/api/work-areas/*` and `/api/workspaces/*` (generic) route families are absent, not deprecated.

## Agent Skills: `/api/skills` and `/skills`

Source: [`SkillController`](../../src/main/java/io/mindspice/magenta2/api/web/SkillController.java), [`SkillFragments`](../../src/main/java/io/mindspice/magenta2/api/web/SkillFragments.java), [`AgentSkillManagementService`](../../src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillManagementService.java).

- `GET /api/skills`: list discovered skill metadata plus valid/warning/invalid counts.
- `POST /api/skills/refresh`: rescan root `skills/` repository and refresh metadata/diagnostics.
- `POST /api/skills`: create a new skill directory and starter `SKILL.md`.
- `GET /api/skills/{skillName}`: skill detail by name or directory slug.
- `GET /api/skills/{skillName}/diagnostics`: diagnostics for malformed/warning skills.
- `GET /api/skills/{skillName}/files?path=`: list a confined skill subdirectory.
- `GET /api/skills/{skillName}/files/view?path=`: read bounded UTF-8 file view metadata/content.
- `PUT /api/skills/{skillName}/files/text?path=`: save UTF-8 text to an existing confined file.
- `POST /api/skills/{skillName}/files`: create a confined text file.
- `GET /api/skills/{skillName}/assignments`: list assignment rows for that skill.
- `POST /api/skills/{skillName}/assignments/agents/{agentId}`: assign (upsert/idempotent) to an agent.
- `DELETE /api/skills/{skillName}/assignments/agents/{agentId}`: unassign from an agent.

HTMX shell/fragments are available at `/skills`, `/skills/_list`, `/skills/_refresh`, `/skills/_create`, `/skills/_detail/{skillName}`, `/skills/_detail/{skillName}/refresh`, `/skills/_files/{skillName}`, `/skills/_viewer/{skillName}`, `/skills/_directories/{skillName}`, and `/skills/_assignments/{skillName}`. These routes render the browser catalog, diagnostics, `SKILL.md` editor, confined file table/editor, guided creation form, and assignment panel.

Skill file routes are confined to `<magenta-root>/skills/<skill>/...` and reject traversal, absolute paths, and symlink escapes. Where distinguishable, invalid requests map to `400`, missing skill/path/agent to `404`, collisions to `409`, and unsupported text/binary operations to `415`.

## Outputs: `/api/outputs`

Source: [`OutputController`](../../src/main/java/io/mindspice/magenta2/api/web/OutputController.java), [`OutputArtifactService`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java).

- `GET /api/outputs?agentId=&jobId=&jobAssignmentId=&jobRunId=&projectId=&workspaceType=&workspaceKey=&workspaceIdentity=&runId=&planId=&runType=&type=&limit=`: query output artifacts.
- `GET /api/outputs/{artifactId}/content`: return metadata plus text/json/user-message content when safe.
- `GET /api/outputs/{artifactId}/download`: download a confined file under the data root.

Output artifacts carry agent, job, job assignment, job run, project, workspace type/key/identity, run type, and work-unit attribution. Content/download enforce a 10 MB limit in the controller. Download resolves real paths and rejects paths outside the data root.

Outputs publish directly beneath the effective workspace's `outputs/` directory. No `runs/<runId>` hierarchy is created. Artifact records remain queryable independently of generic workspace records.

## Dashboard, Runtime, Settings, Models

Sources: [`DashboardController`](../../src/main/java/io/mindspice/magenta2/api/web/DashboardController.java), [`RuntimeController`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java), [`RuntimeSettingsController`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeSettingsController.java), [`ModelController`](../../src/main/java/io/mindspice/magenta2/api/web/ModelController.java).

- `GET /api/dashboard/summary`: project/work/job/assignment/agent/inbox/output/system summary. `activeWork` includes non-terminal job definitions plus active assignment rows when assignment services are available; `pendingAssignments` reflects the active assignment count in that summary.
- `GET /api/runtime/status`: app status, timestamp, default agent, and model count.
- `GET /api/settings/runtime`, `PUT /api/settings/runtime`: persisted runtime defaults.
- `GET /api/models`: configured model summaries from file-backed AI config.

## Entity Selectors: `/selectors`

Source: [`selector`](../../src/main/java/io/mindspice/magenta2/api/web/selector).

Selector endpoints are read-only HTMX fragment surface for reusable operational entity selection:

- `GET /selectors/{kind}/options`
- `GET /selectors/{kind}/selected`
- `GET /selectors/{kind}/validate`

They are read-only `GET` routes. They should be kept in sync with selector-supported entity kinds.
