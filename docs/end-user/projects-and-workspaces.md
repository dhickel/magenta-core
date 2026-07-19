# Projects And Workspaces

Magenta uses a filesystem-first workspace model. Workspaces are owned by agents, chats, or users and live directly under the application data root. Projects provide shared membership and attribution context but do not own filesystem roots.

## Workspace Model

Three closed workspace kinds exist:

| Kind | Identity | Location | Behavior |
| --- | --- | --- | --- |
| **Agent** | `agent/<agentId>` | `workspace/agent/<agentId>/` | Created lazily when the agent exists. Reads empty until files are written. |
| **Chat** | `chat/<conversationId>` | `workspace/chat/<conversationId>/` | Created lazily when the conversation exists. Reads empty until files are written. |
| **User** | `user/<name>` | `workspace/user/<name>/` | Must be explicitly created before use. Missing roots return 404. |

Agent and chat workspaces do not require prior explicit creation. If the entity exists, successful file-producing operations create the physical root when needed. User workspaces are the exception: they must be created through the API or UI before any file or execution operation targets them.

Each workspace includes a reserved `outputs/` directory. Output publication writes there directly; explorer mutations (create, save, rename, move, copy, delete) cannot modify `outputs/` or its descendants.

## Project Membership

Use `/projects` to organize work around a project record, manage agent memberships, and inspect project events. Projects are metadata and membership context. They do not create workspace directories, acquire filesystem leases, or own materialized links.

### Create A Project

1. Open `/projects`.
2. Select **New Project**.
3. Fill **Name**.
4. Optionally fill description and Git repo URL.
5. Choose manager type and default model if needed.
6. Save.

### Edit A Project

Select a project from the sidebar. You can update name, description, Git repo URL, manager type, and default model. Deleting a project is destructive and is blocked while active assignments, jobs, or runs still reference it.

### Project Members

The agents section lists assigned members and roles. Use HTMX controls to add a member, choose a role, or remove a member. Membership removal is guarded while the agent has active assignments.

## Agent Files Tab

Each agent's operational console at `/agents/{agentId}` has a **Files** tab. This tab is a neutral file explorer bound only to `workspace/agent/<agentId>/`.

The explorer supports:

- Folder navigation with toolbar and breadcrumb controls.
- Creating folders, `.txt` files, and `.md` files.
- Markdown viewing with Rendered and Text tabs.
- Plain text viewing and editing.
- Image viewing and download links.
- Custom tags and note labels for files and directories.
- Rename, delete confirmation, copy, and move operations.

All operations stay confined to the agent's workspace root. The explorer rejects traversal, absolute paths, symlink path components, workspace-root mutation, and modification of the reserved `outputs/` subtree.

The Files tab uses HTMX for CRUD operations with out-of-band list, inspector, and modal refreshes. JavaScript is limited to narrow interactions where it is the simpler path.

## User Workspaces

User workspaces are explicitly created directories under `workspace/user/`. Names must be valid plain path segments (nonblank, no slashes, no drive syntax, no encoded path syntax). Existing user workspaces appear as valid execution workspace overrides when submitted work offers workspace choice.

User workspace management operations:
- **List**: `GET /api/workspace/user` returns all valid, confined, non-symlink user workspace directories.
- **Create**: `POST /api/workspace/user` with `{"name": "string"}`. Returns `201` on success, `400` on invalid name, `409` on collision.

Root rename and destructive root deletion are unavailable for user workspaces.

## Execution Workspace Selection

When submitting tasks, workflows, or jobs, execution payloads carry `workspaceType` and `workspaceKey` together:

- Both omitted: defaults to the bound agent's workspace.
- Only one present: invalid (`400`).
- `workspaceType=agent`, `workspaceKey=X`: X must match the bound agent.
- `workspaceType=user`, `workspaceKey=X`: user workspace must exist; overrides agent default.
- `workspaceType=chat`: rejected.

The UI workspace selection control presents the bound-agent default and only declared existing user-workspace overrides. Chat is never selectable.

## Outputs

Output publication writes directly beneath the effective workspace's `outputs/` directory using collision-safe normalized names. No `runs/<runId>` hierarchy is created. Durable task, job, run, project, agent, and canonical workspace attribution remains queryable through `/api/outputs`.

## What Changed

The Avatar dashboard, DB-backed Work Areas, generic filesystem workspace records, project roots, filesystem leases, run staging, aliases, and starter files are removed. Legacy user files and obsolete database structures remain untouched on disk but are inactive. This is a non-migration cutover: current code does not open, migrate, alias, or delete legacy trees.

## Common Errors

- **Name is required**: every project needs a name.
- **No agents exist**: create an agent before creating a project.
- **Project not found**: the project was deleted or the page is stale.
- **User workspace not found**: create the user workspace first.
- **Invalid workspace name**: names must be plain path segments without slashes, dots-only, or encoded syntax.

## Alpha Limits

Project CRUD and membership editing are available in the Projects UI. The agent Files tab is the primary workspace browser surface. Some project summary panels may still display raw member, run, or job IDs for traceability. Searchable selectors are used where available.
