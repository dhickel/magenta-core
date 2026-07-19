## Workspace Package

This package owns filesystem workspace management including centralized layout helpers, effective durable workspace resolution, agent/project workspace roots, Work Area directories, run-local output staging, final output promotion destinations, artifact metadata, and workspace leases.

### Responsibilities
- Manage all workspace directories and Work Area metadata confined under the configured `dataRoot`.
- Resolve effective durable workspace centrally: project workspace when `projectId` is present, otherwise executing agent workspace.
- Provide typed directory paths through centralized structural constants/helpers, including data-root children, agent workspace roots, Home, Work Areas, run roots, run-local outputs, final output destinations, chat files, and project roots.
- Enforce exclusive writable leases on project workspaces and reconcile expired leases before they block reacquisition. Job workspace leasing is legacy compatibility only and must not be expanded.
- Materialize explicit run output artifacts into effective workspace output directories and persist metadata.
- Keep loose artifact discovery compatibility-gated and confined under the real data root and expected run output directory.
- Clean up run staging only through retention-aware cleanup; never delete Work Areas, project/agent workspaces, or promoted final outputs.

### Layout (Phase 02 — transitional)

**New three-kind roots (active):**
- Agent workspace: `workspace/agent/<agentId>/`
- Chat workspace: `workspace/chat/<conversationId>/`
- User workspace: `workspace/user/<name>/`
- Reserved child: `outputs/` (publication-only)

**Legacy paths (retained for backward compatibility until Phase 06):**
- Legacy agent workspace: `workspace/<agentWorkspaceId>/`
- Legacy chat files: `chats/<conversationId>/files/` (deprecated)
- Legacy agents root: `agents/<agentId>/`
- Legacy projects: `projects/<projectId>/`
- Legacy runtime: `runtime/task-runs/`, `runtime/workflow-runs/`, `jobs/`
- Legacy Home: `workspace/<agentWorkspaceId>/home/`
- Legacy Work Areas: `workspace/<agentWorkspaceId>/workareas/<workAreaId>/`
- Legacy run staging: `workspace/<agentWorkspaceId>/runs/<runId>/`

### Services
- `WorkspaceDescriptor` — immutable trusted identity value; package-controlled construction only via `WorkspaceDirectoryService`.
- `WorkspaceKind` — closed enum: `AGENT`, `CHAT`, `USER`.
- `WorkspaceDirectoryService` — descriptor resolution (`resolveForRead`/`resolveForWrite`), user workspace lifecycle (`createUserWorkspace`/`listUserWorkspaces`), publication `outputs(descriptor)`, path confinement, and legacy directory management. Depends on `AgentProfileRepository` and `ChatConversationExistenceRepository` with no service cycles.
- `ChatConversationExistenceRepository` — composes low-level chat existence from memory, session metadata, and optional plan_definitions.
- `WorkspaceResolutionException` — domain failure with `NOT_FOUND`/`CONFLICT` reasons.
- `WorkAreaService` — mark/list/unmark confined workspace subdirectories as user-selectable Work Areas.
- `WorkAreaRepository` — durable `work_areas` metadata and active assignment/output target guard checks.
- `WorkAreaExplorerService` — confined Work Area browse, preview, edit, download, create, rename, copy, move, delete, label, recent-action, and nested mark operations.
- `WorkspaceFileMetadataService` and related repositories — reusable file labels, file label assignments, and recent file action records.
- `EffectiveWorkspaceResolver` — effective durable workspace selection and layout path record creation.
- `WorkspaceLeaseService` — exclusive writable lease acquisition, extension, and release.
- `OutputDirectoryService` — typed output directory resolution for task, workflow, and job publications.
- `OutputArtifactService` — explicit output materialization/publication, copied temp publication, compatibility loose discovery, and artifact persistence.

### Change guidance
- Workspace paths constructed by this package must be confined under `dataRoot` with normalized path checks.
- Work Areas are DB-backed metadata with stable id directories. Do not mark the owner workspace root itself as a Work Area.
- The `home/` Work Area is system-owned and cannot be unmarked.
- Unmarking must reject Work Areas referenced by queued/running assignments or active output targets.
- Source paths supplied for persisted output materialization must also resolve through `toRealPath()` and stay under the real `dataRoot` before copy or artifact registration, so symlinks cannot escape the managed data tree.
- Copied temp/run publication must skip symlinks, including project workspace links, and must register copied files under `copied_temp/...` artifact names.
- New descriptor reads never create roots. New agent/chat write resolution creates only the root; user roots require explicit `createUserWorkspace`. Use single-directory creation where collision semantics matter, and never create starter content or eager children.
- Run staging is retained for at least one day and must remain available for active or resumable work.
- Runtime aliases are part of this architecture contract: `workspace/` for the selected Work Area or durable workspace, `root/` for the owner workspace root, `outputs/` for the current run-local output staging directory, and `run/` for current run staging. Legacy aliases such as scratch or job workspace are compatibility only when still accepted.
- Lease extension must verify holder ownership.
- Do not add UI, scheduling, or agent behavior here.

### Validation
- Path confinement tests for all directory methods.
- Work Area persistence, Home creation, duplicate reactivation, active-use guard, and symlink escape tests.
- Lease conflict, extension, and release tests.
- Output materialization tests for each PlanFieldType.
