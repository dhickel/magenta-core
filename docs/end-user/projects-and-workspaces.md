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

Each workspace can contain a reserved legacy/non-task `outputs/` directory. New task, workflow, and job runs are canonical children at `tasks/<taskPathKey>/<taskRunId>/`; their `outputs/` and `staging/` children are lazy and publication-owned. Explorer mutations cannot modify reserved output, task, staging, or skill structures.

## Agent Workspace Areas

Each agent workspace recognizes nine conceptual areas in the Files tab:

| Area | Purpose | Mutability |
| --- | --- | --- |
| `.skills` | Agent skill files | Delegates to skill-owned view |
| `scripts` | Script storage | Confined read/write (storage only, no execution) |
| `apps` | Application storage | Confined read/write (storage only, no execution) |
| `home` | Personal files | Confined read/write |
| `repos` | User-managed external storage | Read-only from Magenta; no Magenta provisioning or mutation |
| `projects` | Materialized project context | Delegates to context/materialization panel |
| `tasks` | Task run child directories | Read-only lifecycle/model |
| `outputs` | Legacy publication | Read-only publication model |
| Unknown children | Existing content not in recognized areas | Read-only; no implicit migration or write grant |

Conceptual area navigation is non-materializing: selecting an area does not create absent directories. An absent or empty area renders a controlled empty state.

## Agent Materialization (Project Context)

Project materializations are agent-local context containers. A materialization may be provisioned for a project, producing a `READY` local container beneath the agent workspace. `READY` means only that the canonical location is safely available, not that the project's files have been acquired. An empty `READY` container is normal and is truthfully distinguished from an unavailable or failed materialization.

Materialization state is visible in the agent's **Context** panel at `/agents/{agentId}`. The context default can select a materialization (project context) or a personal area (`HOME`, `SCRIPTS`, or `APPS`). Changing the default affects only future work; existing captures retain their original selection.

## Project Console

Use `/projects` to organize work around a project record while retaining the project chooser sidebar. A selected project opens 11 focused tabs across 5 groups: **Overview** (Dashboard), **Work** (Queue, Tasks, Jobs, Outputs), **Context** (Files, Chat), **Automation** (Schedules, Events), and **Administration** (Members, Settings). Projects remain metadata and membership context; they do not create workspace roots or acquire filesystem leases.

The Dashboard shows the neutral **Tracked** project-record state, authoritative counts, member agent/status cards, active assignments, materialization health, and recent project-attributed jobs. Unsupported or unavailable facts are labelled unavailable rather than shown as zero.

**Queue** shows active project-scoped assignments with exact assignment identity, canonical status, and inline transcript/diagnostic links that use persisted assignment links. **Tasks** supports work-task definition CRUD, promote-to-plan, submit-for-execution, retry, and attempt-history browsing. **Jobs** displays project-attributed job definitions with run-history expansion and preferred-executor update. **Outputs** resolves published project outputs through durable assignment-to-run-to-publication links.

**Files** is a read-only view of current members' exact V2 project materialization generations. **Chat** provides project-scoped SSE streaming chat restricted to read-only context, bound to one member agent per conversation. **Schedules** and **Events** display automation facts; when gated unavailable they render controlled redacted disabled states.

**Members** lists assigned members and roles with HTMX add/remove controls. **Settings** contains name, description, Owner Agent, manager type, default model, materialization template revisions, and inline deletion confirmation. Unavailable, gate-disabled, or archived tabs render redacted disabled states with clear labelling.

### Create A Project

1. Open `/projects`.
2. Select **New Project**.
3. Fill **Name** and optionally a description.
4. Optionally choose the **Owner Agent** used by the existing compatibility owner field.
5. Create the project.

Validation stays inside the creation form and preserves submitted values. The browser UI does not display or submit the compatibility Git repository URL field.

### Members And Materialized Files

The Members tab lists assigned members and roles. Use HTMX controls to add a member, choose a role, or remove a member. Membership removal is guarded while the agent has active assignments.

The Files tab is read-only. It selects one current member's exact V2 project materialization generation and revalidates membership, source project, lifecycle state, and generation for every read. It cannot expose the rest of an agent workspace or create, edit, move, tag, download, or delete materialization content. Project Settings owns directory-only materialization template revisions; each save creates a new revision for future generations. Provisioning and lifecycle actions remain on the owning agent's Context tab. Empty `READY`, missing, provisioning, failed, revoked, stale-generation, and unavailable states are shown distinctly.

### Settings And Deletion

Settings contains name, description, **Owner Agent**, manager type, default model, and compact metadata. The owner value is presentation-only for existing records; this UI does not transfer ownership. Save errors remain inline in the Settings tab. Deletion uses an inline confirmation and remains blocked while active assignments or job runs reference the project.

## Agent Files Tab

Each agent's operational console at `/agents/{agentId}` has a **Files** tab. It combines a neutral explorer bound only to `workspace/agent/<agentId>/` with an independently loaded Task Runs panel. Persistence-backed run metadata is never queried by the filesystem explorer service.

The explorer supports:

- Folder navigation with toolbar and breadcrumb controls.
- Creating folders, `.txt` files, and `.md` files.
- Markdown viewing with Rendered and Text tabs.
- Plain text viewing and editing.
- Image viewing and download links.
- Custom tags and note labels for files and directories.
- Rename, delete confirmation, copy, and move operations.

All operations stay confined to the agent's workspace root. The explorer rejects traversal, absolute paths, symlink path components, workspace-root mutation, and modification of reserved `tasks/`, `.skills/`, run-local `outputs/`/`staging/`, or legacy workspace-root `outputs/` structures.

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

For an end-to-end walkthrough, see [Getting Started with Workspaces, Tasks, and Outputs](getting-started-workspaces-tasks-outputs.md).

## Outputs

New task-run publication reserves collision-safe names beneath `tasks/<taskPathKey>/<taskRunId>/outputs/`, stages bytes under the same trusted run, verifies writer capability, and promotes create-only. Chat publication remains separately confined to chat output locations. Existing workspace-root `outputs/` is legacy/non-task data and is not moved. Durable task, job, run, project, agent, and canonical workspace attribution remains queryable; project ids never select a filesystem root.

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
