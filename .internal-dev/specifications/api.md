---
schema_version: 1
document_type: api-specification
status: active
owner: api
created: 2026-05-25
---

# API Specification

## Intended Contract

API routes expose stable request/response shapes, controlled status codes, and compatibility behavior. Controllers stay thin and parse type/key path segments; they never accept roots, canonical identities, or arbitrary paths. `/api/work-areas/*`, `/api/workspaces/*`, and `/avatar` are absent, not deprecated.

## Workspace Identity Routes

### User Workspace Collection

| route | method | status | contract | payload | status codes | validation |
| --- | --- | --- | --- | --- | --- | --- |
| `/api/workspace/user` | `GET` | active | List valid, confined, direct non-symlink user workspace directories under `workspace/user/`. | JSON array of user workspace names. | `200` with array (may be empty). | Listing excludes nested entries and symlinks; physically present but invalid-name directories are excluded. |
| `/api/workspace/user` | `POST` | active | Create a named user workspace as a direct directory beneath `workspace/user/`. Name validated with `PlainPathSegmentValidator`. | `{"name": "string"}` | `201` created; `400` invalid name; `409` collision (directory already exists). | User workspace must be created explicitly; no implicit creation during selection. Root rename and destructive deletion are unavailable. |

### Typed Workspace Item Operations

Routes rooted at `/api/workspace/{type}/{id}` where `{type}` is `agent`, `chat`, or `user` and `{id}` is a single validated decoded path segment. Encoded separators and traversal syntax are rejected at the web boundary. Decoded keys receive `PlainPathSegmentValidator` enforcement: nonblank, trimmed, non-dot-only, non-absolute, without slash, backslash, drive syntax, or encoded path syntax. Unknown workspace types, malformed or unpaired selection fields, invalid keys, encoded separators/traversal, and invalid file-relative paths map to `400`; missing agent/chat entities, missing user declarations, and missing files map to `404`; create/rename/move/copy collisions map to `409`.

All explorer mutation operations reject the workspace root and `outputs/` or any descendant. File-producing mutations may lazily create a valid agent/chat physical root after descriptor resolution; they never implicitly create a user root. Read operations against a valid agent/chat descriptor with no physical root return the operation's empty/not-found result without creating the root.

| route | method | status | contract | payload | status codes | notes |
| --- | --- | --- | --- | --- | --- | --- |
| `/api/workspace/{type}/{id}/files` | `GET` | active | List files and directories at the given workspace-relative path. Accepts `?path=` query parameter. | `WorkspaceDescriptor` resolved by `WorkspaceDirectoryService`. | `200` with file listing; `404` missing entity, user declaration, or requested child path. | Agent/chat roots with missing physical directory return an empty listing. Missing user workspace is `404`. |
| `/api/workspace/{type}/{id}/files/preview` | `GET` | active | Return a bounded text/Markdown preview of a file. | `?path=relative/path` | `200` with preview content; `400` invalid path; `404` missing; `413` exceeds size limit. | UTF-8 validation; Markdown rendering safety. |
| `/api/workspace/{type}/{id}/files/view` | `GET` | active | Return an image file for inline viewing. | `?path=relative/path` | `200` with image bytes; `400` invalid path/type; `404` missing. | Bounded image types and sizes. |
| `/api/workspace/{type}/{id}/files/download` | `GET` | active | Download a file as attachment. | `?path=relative/path` | `200` with file bytes; `400` invalid; `404` missing. | Download size limits. |
| `/api/workspace/{type}/{id}/files/text` | `PUT` | active | Save an existing text file. | `?path=relative/path` plus `{"content": "text"}` | `200`; `400` invalid path/UTF-8; `404` missing; `413` exceeds edit limit. | Hard edit limit (25MB); create remains the `POST` contract. |
| `/api/workspace/{type}/{id}/files/text` | `POST` | active | Create a new text file (rejects overwrite). | `{"parentPath": "relative/directory", "fileName": "name.txt"}` | `201`; `400` invalid; `409` already exists. | Preserves the existing create payload semantics. |
| `/api/workspace/{type}/{id}/files/markdown` | `POST` | active | Create a new Markdown file. | `{"parentPath": "relative/directory", "fileName": "name.md"}` | `201`; `400` invalid; `409` already exists. | Markdown extension and rendering safety remain enforced. |
| `/api/workspace/{type}/{id}/directories` | `POST` | active | Create a directory. | `{"path": "relative/path"}` | `201`; `400` invalid; `409` already exists. | Rejects creation beneath `outputs/`. |
| `/api/workspace/{type}/{id}/files/rename` | `POST` | active | Rename a file or directory. | `{"path": "...", "newName": "..."}` | `200`; `400` invalid; `404` missing source; `409` target exists. | Rejects root rename. |
| `/api/workspace/{type}/{id}/files/move` | `POST` | active | Move a file or directory. | `{"path": "...", "destinationDirectoryPath": "...", "newName": "optional"}` | `200`; `400` invalid/traversal; `404` missing; `409` target exists. | Rejects root move. |
| `/api/workspace/{type}/{id}/files/copy` | `POST` | active | Copy a file or directory. | `{"path": "...", "destinationDirectoryPath": "...", "newName": "optional"}` | `200`; `400` invalid; `404` missing; `409` target exists. | Rejects root copy. |
| `/api/workspace/{type}/{id}/files/delete/preflight` | `GET` | active | Preview what would be deleted. | `?path=relative/path&step=INTENT|FILE_CONFIRM|DIRECTORY_RECURSIVE_CONFIRM` | `200` with deletion preview; `400` invalid; `404` missing. | Preserves staged recursive-delete confirmation and directory counts. |
| `/api/workspace/{type}/{id}/files/delete` | `POST` | active | Delete a file or directory after the required confirmation stage. | `{"path": "relative/path", "step": "FILE_CONFIRM|DIRECTORY_RECURSIVE_CONFIRM"}` | `200`; `400` invalid; `404` missing; `409` incomplete confirmation or reserved path. | Rejects root deletion and `outputs/` mutation. |
| `/api/workspace/{type}/{id}/files/labels` | `GET` | active | List labels for a file. | `?path=relative/path` | `200` with label list; `404` missing. | |
| `/api/workspace/{type}/{id}/files/labels` | `POST` | active | Add an existing label/tag to a file. | `{"path": "...", "label": "..."}` | `200`; `400` invalid; `404` missing. | |
| `/api/workspace/{type}/{id}/files/labels` | `DELETE` | active | Remove a label/tag from a file. | `?path=...&label=...` | `200`; `400` invalid; `404` missing. | |
| `/api/workspace/{type}/{id}/tags` | `POST` | active | Create or return a reusable tag label. | `{"label": "...", "displayName": "..."}` | `200`; `400` invalid; `409` incompatible collision. | Assignment to files remains the labels endpoint. |
| `/api/workspace/{type}/{id}/files/actions/recent` | `GET` | active | List recent actions for a workspace. | `?limit=N` | `200` with action list. | Actions keyed by canonical workspace identity. |

### Execution Workspace Selection

Execution payloads (`AssignmentRequest` and equivalent submission DTOs) carry `workspaceType` and `workspaceKey` together:

| field | requirement | behavior |
| --- | --- | --- |
| `workspaceType` | string, paired with `workspaceKey` | One of `agent` or `user`. `chat` is rejected. |
| `workspaceKey` | string, paired with `workspaceType` | Validated with `PlainPathSegmentValidator`. |
| Both omitted | Valid | Defaults to the bound agent's workspace. |
| Only one present | Invalid | `400` — both or neither. |
| `workspaceType=agent`, `workspaceKey=X` | X must match bound agent | `400` if mismatch; `404` if agent entity missing. |
| `workspaceType=user`, `workspaceKey=X` | User workspace must exist | `404` if not declared/created. Overrides agent default. |
| `workspaceType=chat` | Rejected | `400` — chat workspaces are not execution targets. |

Canonical identity (`agent/<id>` or `user/<name>`) is derived internally and persisted alongside the pair. Clients never submit canonical identity.

### Run Lifecycle Operations

Task, workflow, job, and agent-assignment submission payloads accept an optional `operationKey`. If omitted, the server generates and persists a unique key for that submission. Repeating the same operation type/key with the same canonical request returns the recorded assignment/run identity; a different digest is a conflict. Assignment responses expose lifecycle facts in the secret-free checkpoint (`taskRunId`, `nativeRunId`, `lifecycleOperationKey`, `attemptNumber`, and applicable lineage), while task/workflow submission SSE events include `taskRunId` and `operationKey`. No response exposes paths, lease owners, fence values, or writer tokens.

Agent assignments support `POST /api/agents/{agentId}/assignments/{assignmentId}/retry` and `/replay` with optional `{ "operationKey": "..." }`. Resume continues to use the existing `/resume` endpoint: it reuses the native/task-run id and opens a new attempt. Retry/replay create a new assignment and native/task-run id with `RETRY_OF` or `REPLAY_OF` lineage.

### Absent Routes

The following route families are absent from the active API, not deprecated:

| route family | status |
| --- | --- |
| `/api/work-areas/*` (all ~20 routes) | absent |
| `/api/workspaces/*` (generic workspace list/get/links/leases) | absent |
| `/avatar` (all Avatar dashboard, shell, widget, and chat routes) | absent |

### Retained API Entries

| id | route_or_surface | status | intended_contract | payload_or_status | compatibility_rule | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| API-20260525-02 | Chat surface values | active | Known chat surface enum values accept case-insensitive names and reject blank/unknown values. | JSON boundary normalization. | Keep normalization narrow at the JSON boundary. | Controller/API tests. | `DECISION-20260523-05` | `api-boundary-enums-and-empty-job-run-completion.md` |
| API-20260525-03 | Empty submitted jobs | active | Empty submitted jobs complete as no-op job runs unless product policy later changes to reject them at submission. | Terminal job run instead of `RUNNING`. | Do not change without explicit product decision. | Assignment/job tests. | `DECISION-20260523-05` | `api-boundary-enums-and-empty-job-run-completion.md` |
| API-20260525-04 | Direct task run SSE | deferred | Consider native reactive SSE or explicit executor-backed execution if concurrency requirements grow. | SSE stream behavior. | Current blocking bridge is acceptable for current scope. | SSE tests and startup. | none | `plan-execution-stream-finalization.md` |
| API-20260525-06 | Browser chat pending messages | active | Normal `/chat` messages submitted during an active stream are stored through pending-message routes and later drained through the normal stream route. | `GET/POST /api/chat/{conversationId}/pending-messages`, `POST /claim`, `POST /{messageId}/ack`, `POST /{messageId}/release`; Java record DTOs. | Keep `/api/chat/turns/{turnId}/interrupt` intact for explicit interrupt semantics, but ordinary browser mid-turn messages must not use it by default. | Repository/service/controller tests, browser queue validation. | none | `chat-planning-composer-architecture.md`, `event-delegation-sse-dom-replacement.md` |
| API-20260727-01 | Chat stream `outputs_changed` SSE event | active | The `outputs_changed` named SSE event carries only `conversationId`. It is emitted mid-stream after a recognized file-mutating tool (`file_write`, `file_append`, `file_replace`, or `file_delete`) returns a completed, attributable result. The event is a side-channel invalidation notice; it does not carry file paths, tool arguments, or result content. | `ChatStreamEvent.OutputsChanged(conversationId)` mapped by `ChatController` as a named SSE side channel. Emitted on both `POST /api/chat/stream` and `POST /api/chat/{conversationId}/plan/execute/stream`. | Additive event ignored by consumers that do not recognize it. Existing `done`/error terminal refresh remains as fallback. | Controller/API tests, serialization tests, service tests, browser validation. | none | `chat-file-panel-auto-refresh.md` |
| API-20260727-02 | Agent context REST | active | Agent-scoped context and materialization APIs beneath `/api/agents/{agentId}/context`. | `GET /context` returns default availability/default record and a materialization count; `GET /defaults`, `/materializations`, and `/bindings` provide the corresponding JSON read models. `PUT /defaults` accepts expected-version and selection form parameters. `POST /materializations` accepts `projectKey` and an optional operation key. Panel/editor variants are HTMX fragments. Responses contain redacted context facts only; never roots, raw paths, secret values, or untrusted candidate bytes. | `400` malformed facts; `404` missing agent; `409` default/materialization conflicts; `503` temporarily unavailable fragment operations. `READY` means an empty-or-existing local container, not acquired content. | Controller tests covering malformed ids, wrong agent-resource pairing, default conflicts, unavailable states, and empty-READY truthfulness. | `DECISION-20260727-01`, `DECISION-20260727-03` |
| API-20260727-03 | System chat REST | active | Agent-scoped system-chat APIs beneath `/api/agents/{agentId}/system-chat`. | `GET /panel` and `/panel/history` return HTMX hosts; `POST /stream?message=` emits `start`, `message`, `proposals`, then `done` SSE events (or `error`); `GET /proposals` negotiates an HTML fragment or JSON list; `POST /proposals/{proposalId}/confirm?digest=` confirms an exact proposal. System chat is server-created and agent-scoped; callers cannot select `INTERNAL` or submit a system conversation id. | `404` missing agent; `409` confirmation conflict/unavailable target; `503` unavailable panel/proposals operations. Creation and confirmation use persisted proposal identity/digest; the server derives confirmation operation identity and rechecks agent, target state, and action before lifecycle dispatch. | Controller tests covering fixed-profile routing, proposal target/state confirmation, file/shell/skill/cross-agent denial, stream behavior, and secret-free output. | `DECISION-20260727-04` |
| API-20260526-01 | Task/workflow/job submission and run reads | active | Non-job task/workflow submissions require a user-visible `runDisplayName`; project association is metadata-only. Run reads expose execution outputs. Selection uses `workspaceType`/`workspaceKey` pair (see execution selection above). | Additive `runDisplayName` request/record field for non-job work; selection fields replace `selectedWorkAreaId`/`outputWorkAreaId`/`workspaceId`/`outputRouteType`. | Legacy compatibility fields are removed, not retained. | Controller/API tests for workspace selection rules, required names, and docs updates. | `DECISION-20260719-02` | `workspace-api-list-and-agent-tab-operational-pattern.md` |
| API-20260526-02 | Agent Skills management, exact assignment, trust, snapshot, and activation APIs | active | Preserve root-repository metadata/file/agent-assignment routes and expose candidate collections/history, exact overlay writes/listing, explicit approve/reject/revoke, decision history, immutable snapshot collections/resources, activation history, approved catalog, executable-capability status, and fail-closed package status. | `/api/skills/**`; see operational API table. | Project roots are never guessed. No source/scope/assignment auto-trusts a skill. Collection routes query persisted Phase 06/07 control-plane state. Package ingest returns `503 UNAVAILABLE` and accepts no package while confinement is unproven. | Controller/API tests for collection reads, scope, operation-key conflict, digest mismatch, snapshot activation, revocation, and fail-closed capability paths. | `DECISION-20260720-03`, `DECISION-20260720-04` | `scoped-skill-trust-snapshots.md` |

## Operational REST APIs

Every artifact and runtime workspace request requires an explicit `agent` or `user` scope; `chat` is not task-selectable. Scope is checked before aggregate queries, and byte access is checked again when opened.

### Artifacts and Retention

| route family | methods | contract |
| --- | --- | --- |
| `/api/artifacts`, `/api/artifacts/{artifactId}` | `GET` | Bounded workspace/task-run listing and scoped metadata. Out-of-scope artifacts are indistinguishable from missing artifacts. |
| `/api/artifacts/search` | `GET` | Scope-constrained FTS5 search with bounded page inputs. Returns `503` when runtime FTS5 probing fails; there is no fallback scan. |
| `/api/artifacts/{artifactId}/preview|download|lineage` | `GET` | Integrity-checked bounded preview/download and explicit producer lineage. Integrity failure suppresses bytes. |
| `/api/artifacts/{artifactId}/retention`, `/pins` | `GET` | Return lifecycle state, blockers, and retention pins inside the authorized scope. |
| `/api/artifacts/{artifactId}/retention/soft-archive|hard-expire` | `POST` | Explicit artifact lifecycle actions; blocked actions return conflict without deleting protected state. |
| `/api/runs/{taskRunId}/retention/soft-archive|hard-expire` | `POST` | Scoped batch lifecycle actions for one canonical run. |
| `/api/runs/{taskRunId}/retention/policy` | `GET`, `POST` | Read/set workspace/task policy. Absence means `PRESERVE`/keep indefinitely. |

### Skills

| route family | methods | contract |
| --- | --- | --- |
| `/api/skills`, `/api/skills/{skillName}`, `/refresh`, `/{skillName}/diagnostics`, `/{skillName}/files/**` | `GET`, `POST`, `PUT` | Root-repository management plus confined metadata/file operations. |
| `/api/skills/{skillName}/assignments/**`, `/api/skills/overlays` | `GET`, `POST`, `DELETE` | Legacy agent assignment and exact candidate overlay controls. Overlay writes bind candidate version, target type/key, enabled state, actor, and operation key; they do not imply trust. |
| `/api/skills/candidates`, `/api/skills/candidates/{logicalKey}`, `/trust/decisions/{logicalKey}`, `/snapshots`, `/snapshots/{logicalKey}`, `/snapshots/{snapshotId}/resources`, `/activations/{taskRunId}`, `/catalog/approved` | `GET` | Persisted control-plane candidate history, trust decisions, immutable snapshots/resources, activation audit, and snapshot-only approved catalog. Untrusted content is not a model catalog. |
| `/api/skills/trust/approve|reject|revoke` | `POST` | Operation-keyed explicit trust decisions. Approval requires the exact observed candidate digest and creates immutable snapshot state. |
| `/api/skills/activate` | `POST` | Activates only an exact approved snapshot and records the activation context; revocation and resume semantics apply. |
| `/api/skills/exec-auth/{logicalKey}` | `GET` | Reports executable authorization unavailable while confinement is not proven. |
| `/api/skills/packages/ingest` | `POST` | Fails closed with `503 UNAVAILABLE`; it does not accept/install a package while process confinement is unproven. |

### Runtime Observability

| route family | methods | contract |
| --- | --- | --- |
| `/api/runtime/status` | `GET` | Runtime workspace-service availability. |
| `/api/runtime/runs/{taskRunId}/summary|rebuild|normalized-status|audit` | `GET` | Rebuildable summary, normalized status, and bounded exact-link audit. Native run rows remain authoritative. |
| `/api/runtime/runs/{taskRunId}/measurements`, `/attempts/{attemptNumber}/measurements` | `GET` | Repeated typed invocation facts, optionally filtered by kind; unavailable values remain null. |
| `/api/runtime/workspaces/{workspaceType}/{workspaceKey}/summaries|health|measurements` | `GET` | Bounded agent/user history, explicit health denominator, and workspace-scoped measurements. |

### Schedules and Reactions

All routes below are under `/api/agents/{agentId}` and return `404` when their capability flag is disabled.

| route family | methods | contract |
| --- | --- | --- |
| `/schedules`, `/schedules/{scheduleId}`, `/{scheduleId}/enable` | `GET`, `POST`, `PUT`, `DELETE` | Revisioned schedule CRUD, soft deletion, and enable/disable with six-field cron and IANA timezone validation. |
| `/schedules/{scheduleId}/firings`, `/firings/{firingId}/attempts` | `GET` | Bounded revision-qualified firing and append-only attempt audit. `ENQUEUED` is not completion. |
| `/schedules/simulate` | `POST` | Pure preview with no firing, claim, assignment, run, tool, artifact, event, or external mutation. |
| `/schedules/{scheduleId}/cancel-linked` | `POST` | Requests lifecycle-owned cancellation without rewriting firing audit. |
| `/event-reactions`, `/event-reactions/{reactionId}`, `/{reactionId}/enable` | `GET`, `POST`, `PUT`, `DELETE` | Revisioned reaction CRUD, soft deletion, and enable/disable. |
| `/event-reactions/{reactionId}/dispatches`, `/event-reactions/dispatches/by-root-event/{rootEventId}` | `GET` | Bounded dispatch, causation, suppression, and linked-work audit. |
| `/event-reactions/simulate` | `POST` | Pure match/dedupe/loop/depth/rate evaluation with no persistent or external side effect. |

## Operational Fragment Routes

These server-rendered routes are scoped browser adapters, not trusted filesystem APIs:

| route | contract |
| --- | --- |
| `GET /agents/_detail/{agentId}/runs?limit=&oob=` | Requires an existing agent, clamps `limit` to 1-100, queries only `workspace_type=agent` and the path agent key, and returns `#agent-run-groups`. `oob=true` marks refresh responses for `outerHTML` OOB replacement. Empty and service-unavailable states are explicit. |
| `GET /agents/_detail/{agentId}/runs/{taskRunId}` | Validates run-id shape, requires an existing agent, and rechecks the persisted summary workspace type/key before composing audit detail. Returns `#agent-run-detail`; out-of-scope and missing rows disclose no run facts. |
| `GET /runtime/workspaces/{workspaceType}/{workspaceKey}/health|history` | Agent/user runtime health and bounded history fragments. Missing elapsed or samples remain unavailable rather than zero. |
| `GET /runtime/runs/{taskRunId}/measurements-fragment|audit-fragment` | On-demand measurement/audit fragments over persisted read models; no lease owner, claim secret, fence, token, or absolute path is returned. |
| `/fragments/agents/{agentId}/schedules/*`, `/fragments/agents/{agentId}/reactions/*` | HTMX list, non-executing simulation preview, and bounded firing/attempt or dispatch audit fragments. |
| `/artifacts`, `/artifacts/_search`, `/_detail/{artifactId}`, `/_preview/{artifactId}`, `/_lineage/{artifactId}`, `/_retention/{artifactId}` | Scoped artifact page and stable search/detail subfragments. FTS5-unavailable and integrity-failed states suppress unsupported search or byte access. |
| `/skills/_trust/{logicalKey}`, `/skills/_trust/approve|reject|revoke`, `/skills/_snapshots/{logicalKey}` | Skill trust decision and immutable snapshot fragments; mutations refresh only the trust host. |
| `GET /projects/_detail/{projectId}/{dashboard|queue|tasks|jobs|outputs|files|chat|schedules|events|members|settings}` | Project-scoped tab fragments targeting only `#project-tab-panel`; missing scope is `404` and unavailable service facts use controlled marked fragments. Gate-disabled/archived tabs render redacted disabled states. |
| `GET /projects/_detail/{projectId}/files/list|preview` | Read-only current-member materialization reads requiring exact agent/project source, current V2 generation, `READY`, and a trusted confined materialization location. Stale/lifecycle conflicts are `409`; missing project/member/materialization/path is `404`. |
| `POST /projects/_detail/{projectId}/templates` | Trusted-deployment HTMX mutation accepting one relative directory per line. It creates an append-only template revision; malformed, duplicate, content/executable/overwrite, traversal, or prefix-conflicting paths return `400`. |
| `GET /projects/_detail/{projectId}/outputs/{artifactId}/preview|download` | Project-scoped byte adapters. Each repeats trusted-deployment/active-project checks and durable assignment-to-run-to-publication authorization before opening verified canonical publication bytes; generic artifact URLs are not emitted by the project console. |
| `POST|DELETE /projects/_detail/{projectId}/members/**`, `PUT /projects/_editor/{projectId}`, `DELETE /projects/{projectId}` | Local HTMX mutation fragments. Validation is `400`, scoped absence `404`, guarded conflicts `409`, and infrastructure failure `503`. Failed mutations do not OOB-refresh unrelated regions. |
| `GET|POST|PUT /projects/_detail/{projectId}/tasks/**` | Work-task definition CRUD, promote-to-plan, submit-for-execution, retry, and attempt-history detail. All mutations require `operationKey`; version-mismatch conflicts are `409`. Submission requires promotion and an eligible project-member agent. |
| `GET /projects/_detail/{projectId}/chat`, `POST /api/projects/{projectId}/chat/stream` | Project-scoped SSE chat restricted to read-only context; bound to one member agent and its exact READY materialization generation. The server derives a conversation from that immutable tuple. SSE stream uses safe `textContent` rendering and strict event validation; no `innerHTML` or raw transport. |

The artifact REST family under `/api/artifacts` owns scoped metadata, preview/download, search/lineage, and retention operations. Request scope is workspace kind/key with optional exact task run; authorization is applied before counts or rows are calculated.

## Ownership Boundary

This file owns route and payload contracts. Service behavior belongs in `services.md`; page/fragment behavior belongs in `web.md`.

## Drift/Gaps

| id | status | observed_drift | routing | review_after |
| --- | --- | --- | --- | --- |
| DRIFT-20260525-05 | open | Workspace file explorer status mapping is partly message based. | Deferred typed domain errors before expanding compatibility claims. | 2026-06-23 |
| DRIFT-20260720-01 | blocking | Task-run `/api/runtime/runs/{taskRunId}/*` reads lack workspace scope; schedule firing-attempt JSON exposes claim owner/fence and does not bind the firing to the path schedule/agent; root-event reaction dispatch lookup lacks path-agent filtering. | Add authorization-first scope resolution and secret-free response DTOs before Phase 10 API/security PASS. | 2026-07-20 |

## Validation Expectations

API changes require focused controller/API tests, docs updates for route or payload changes, and bounded startup when Spring wiring changes.

## Related Decisions

See `DECISION-20260719-02`, `DECISION-20260523-05`.

## Related Knowledge

Search knowledge filenames for `api`, `workspace`, `enum`, `empty-job`, `sse`, and `workflow`.
