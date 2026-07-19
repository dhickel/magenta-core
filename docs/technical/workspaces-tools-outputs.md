# Workspaces, Tools, and Outputs

Workspace and output behavior is owned by [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces). Tool behavior is owned by [`ai/chat/tool`](../../src/main/java/io/mindspice/magenta2/ai/chat/tool).

## Filesystem-first identity

`WorkspaceKind` is closed to `agent`, `chat`, and `user`. `WorkspaceDirectoryService` is the only component that creates trusted `WorkspaceDescriptor` values. Canonical identities are derived internally as `<type>/<key>`.

The active layout beneath the configured data root is:

- `workspace/agent/<agentId>/`
- `workspace/chat/<conversationId>/`
- `workspace/user/<name>/`
- `<selected-root>/outputs/` — reserved and created lazily by publication

Agent and chat roots may be absent on reads. A successful write or execution attempt may create the selected agent/chat root without starter files or eager children. User workspaces must be explicitly created before selection. Creating or enabling an agent profile does not allocate a filesystem root.

Projects remain membership and attribution metadata. They do not select, create, link, or lease execution roots.

## Execution selection

Executable submissions carry `workspaceType` and `workspaceKey` together. Omitting both selects the bound agent. Exactly one value is invalid. Explicit agent selection must match the bound agent, an existing user workspace may override it, and chat workspaces cannot execute work.

The runner resolves one descriptor at the execution-attempt boundary. Jobs and workflows persist `workspace_type`, `workspace_key`, and `workspace_identity`; no host path, generic workspace id, Work Area selector, run root, or output route is stored. A waiting workflow resumes from its persisted typed selection.

The operational submission UI intentionally shows no temporary raw workspace selector. Bound-agent submissions omit both values until the reusable selector is introduced on its dedicated surface.

## Runtime guidance and tools

Runtime `AGENTS.md` files are user-authored only. Resolution is confined from the selected descriptor root to the active relative path. Ancestor guidance remains active and the closest file wins only on conflict.

File and shell tools are confined to the selected descriptor root. They reject traversal, symlink escape, and mutation of `outputs/` or descendants. Tools update the active runtime path after confined operations so subsequent prompt context follows the actual target.

## Output publication

`OutputArtifactRepository` independently owns `run_output_artifacts` schema compatibility, writes, reads, updates, deletion, mapping, and attribution. `OutputArtifactService` has no `WorkspaceRepository` dependency.

Declared outputs publish directly beneath the selected descriptor's lazy `outputs/` subtree. Publication:

- normalizes non-empty relative output names;
- rejects absolute, dot-only, traversing, escaping, or symlinked destinations;
- never overwrites collisions;
- accepts file sources only when their real regular-file path remains beneath the same descriptor root;
- stores portable data-root-relative artifact paths;
- retains run, task/plan, workflow, job, assignment, project, agent, workspace type/key/identity, and run-type attribution.

No run, job, task, project, Work Area, scratch, staging, or discovery directory hierarchy is synthesized for active execution. Reads and failed validation do not create `outputs/`.

`GET /api/outputs` supports attribution filters including `agentId`, `jobId`, `jobAssignmentId`, `jobRunId`, `projectId`, `workspaceType`, `workspaceKey`, `workspaceIdentity`, `runId`, `planId`, `runType`, `type`, and `limit`. Filters query metadata and never authorize or select a filesystem root.

## Legacy isolation

Obsolete generic workspace, link, lease, Work Area, project-root, and output-directory definitions may remain until their deletion phase. Existing database columns, rows, and filesystem trees remain untouched. Active execution and artifact paths do not read, write, recreate, alias, migrate, or fall back through them.
