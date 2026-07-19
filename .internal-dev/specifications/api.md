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
| API-20260526-01 | Task/workflow/job submission and run reads | active | Non-job task/workflow submissions require a user-visible `runDisplayName`; project association is metadata-only. Run reads expose execution outputs. Selection uses `workspaceType`/`workspaceKey` pair (see execution selection above). | Additive `runDisplayName` request/record field for non-job work; selection fields replace `selectedWorkAreaId`/`outputWorkAreaId`/`workspaceId`/`outputRouteType`. | Legacy compatibility fields are removed, not retained. | Controller/API tests for workspace selection rules, required names, and docs updates. | `DECISION-20260719-02` | `workspace-api-list-and-agent-tab-operational-pattern.md` |
| API-20260526-02 | Agent Skills management and assignment APIs | active | Expose APIs for skill metadata list/read/refresh/create, diagnostics, root-confined file tree/view/save/create, and agent-skill assignment add/remove/list. MVP assignment scope is agent profiles only. | Implemented `/api/skills` routes. | Project-local `.agents/skills/`, user-home scopes, and project/job/task/workflow/chat/session layered assignment are deferred and must not appear as active API behavior. | Controller/API tests for success and negative paths, plus docs updates. | `DECISION-20260526-03`, `DECISION-20260526-06` | `agent-skills-specification-reference.md` |

## Ownership Boundary

This file owns route and payload contracts. Service behavior belongs in `services.md`; page/fragment behavior belongs in `web.md`.

## Drift/Gaps

| id | status | observed_drift | routing | review_after |
| --- | --- | --- | --- | --- |
| DRIFT-20260525-05 | open | Workspace file explorer status mapping is partly message based. | Deferred typed domain errors before expanding compatibility claims. | 2026-06-23 |

## Validation Expectations

API changes require focused controller/API tests, docs updates for route or payload changes, and bounded startup when Spring wiring changes.

## Related Decisions

See `DECISION-20260719-02`, `DECISION-20260523-05`.

## Related Knowledge

Search knowledge filenames for `api`, `workspace`, `enum`, `empty-job`, `sse`, and `workflow`.
