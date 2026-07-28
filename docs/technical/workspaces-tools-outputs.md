# Workspaces, Tools, and Outputs

Workspace and output behavior is owned by [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces). Tool behavior is owned by [`ai/chat/tool`](../../src/main/java/io/mindspice/magenta2/ai/chat/tool).

## Filesystem-first identity

`WorkspaceKind` is closed to `agent`, `chat`, and `user`. `WorkspaceDirectoryService` is the only component that creates trusted `WorkspaceDescriptor` values. Canonical identities are derived internally as `<type>/<key>`.

The active layout beneath the configured data root is:

- `workspace/agent/<agentId>/`
- `workspace/chat/<conversationId>/`
- `workspace/user/<name>/`
- `<selected-agent-or-user>/tasks/<taskPathKey>/<taskRunId>/` — canonical task-run child, provisioned only by the task lifecycle
- `<task-run>/outputs/` and `<task-run>/staging/` — reserved lazy children owned by active task-run publication
- `<selected-agent-or-user>/.skills/` — reserved lazy workspace skill root
- `<selected-root>/outputs/` — existing legacy/non-task publication subtree

Agent and chat roots may be absent on reads. A successful write or execution attempt may create the selected agent/chat root without starter files or eager children. User workspaces must be explicitly created before selection. Creating or enabling an agent profile does not allocate a filesystem root.

Projects remain membership and attribution metadata. They do not select, create, link, or lease execution roots.

## Agent Workspace Areas

Each agent workspace has nine conceptual lazy areas recognized in the Files tab:

| Area | Location | Behavior |
| --- | --- | --- |
| `.skills` | `<agent-root>/.skills/` | Owned by the skill control plane; Files tab delegates to the skill-owned view. |
| `scripts` | `<agent-root>/scripts/` | Storage only, not execution authority. Confined reads/writes through the explorer. |
| `apps` | `<agent-root>/apps/` | Storage only, not execution authority. Confined reads/writes through the explorer. |
| `home` | `<agent-root>/home/` | Personal confined storage. Confined reads/writes through the explorer. |
| `repos` | `<agent-root>/repos/` | User-managed storage outside Magenta. Existing descendants are descriptor-confined read-only; Magenta never provisions, mutates, interprets, or assigns application behavior to this area. |
| `projects` | `<agent-root>/projects/<stable-key>/` | Owned by context/materialization services. This is an agent-local context container, not a project workspace root. `READY` means safe local availability, not acquired content. |
| `tasks` | `<agent-root>/tasks/<taskPathKey>/<taskRunId>/` | Owned by the task lifecycle; uses lifecycle/publication read models. |
| `outputs` | `<agent-root>/outputs/` | Legacy/non-task publication; uses publication read models. |
| Unknown children | `<agent-root>/*` | Retain only already-permitted reads; not implicitly migrated, relocated, or writable. |

Conceptual area navigation is non-materializing: selecting an area does not create absent directories. An absent area renders a controlled unprovisioned/empty state.

## Agent Materialization

Project materializations are agent-scoped local context containers. They use stable framed identity-derived keys (`v1-PROJECT-<sha256>` from the project id) and a four-state lifecycle:

- `PROVISIONING`: provisioning in progress; location not yet available.
- `READY`: canonical location is safely available. This is a local availability assertion, not a content-acquisition guarantee. The container may be empty.
- `FAILED`: provisioning failed; `failure_detail` records the cause.
- `REVOKED`: materialization has been revoked; location may no longer be valid.

Each materialization carries a monotonic generation. Trusted ready-location resolution validates an expected generation, while provisioning uses the loaded generation for its state-transition CAS and idempotency identity. Provisioning and reconciliation use verified descriptor-confined primitives; a persisted root-relative assertion verifies a location but never authorizes it. Content, when present, is supplied out-of-band.

## Execution selection

Executable submissions carry `workspaceType` and `workspaceKey` together. Omitting both selects the bound agent. Exactly one value is invalid. Explicit agent selection must match the bound agent, an existing user workspace may override it, and chat workspaces cannot execute work.

The runner resolves one descriptor at the execution-attempt boundary. Jobs and workflows persist `workspace_type`, `workspace_key`, and `workspace_identity`; no host path, generic workspace id, Work Area selector, run root, or output route is stored. A waiting workflow resumes from its persisted typed selection.

The operational submission UI intentionally shows no temporary raw workspace selector. Bound-agent submissions omit both values until the reusable selector is introduced on its dedicated surface.

## Runtime guidance and tools

Runtime `AGENTS.md` files are user-authored only. Resolution is confined from the selected descriptor root to the active relative path. Ancestor guidance remains active and the closest file wins only on conflict.

File tools reject traversal, symlink escape, and mutation of reserved outputs, task structure, staging, and `.skills`. Recursive list/search classifies every discovered entry and prunes hidden `.skills` and staging subtrees. Task-run writes resolve from the exact trusted run location and revalidate a capability carrying the same workspace kind/key, task-path key, run id, attempt, owner, and fence immediately before each mutation.

On the deployed Linux 64-bit default filesystem provider, mutations walk and hold relative directory descriptors with `O_NOFOLLOW`, use `openat`-family primitives, check existing regular-file link count with `fstat`, and use no-replace relative rename. Unsupported providers fail closed with a confinement-unavailable error; there is no ordinary-path fallback. Workspace-bound shell remains disabled because host `ProcessBuilder` execution does not prove filesystem isolation or process-tree revocation. Tools update the active runtime path after confined operations so subsequent prompt context follows the actual target.

## Canonical task-run identity

Saved task, workflow, and job definitions use immutable definition ids to derive `TaskPathKey`. The exact v1 hash payload is byte `0x01`, source-kind byte `T`, `W`, or `J`, a two-byte unsigned big-endian UTF-8 id length, then the exact UTF-8 id bytes. No Unicode normalization or case folding occurs. The serialized key is `v1-<kind>-<64 lowercase SHA-256 hex characters>`.

`plan_runs.id`, `workflow_runs.id`, and `job_runs.id` are canonical task-run ids for their respective families. Assignment, workflow-node, and job-work-item identities remain separate explicit linkage. `WorkspaceDirectoryService` recomputes keys and constructs trusted child values; callers cannot supply roots or reconstruct workspace identity from paths. Persisted locations contain workspace kind/key and a data-root-relative path only.

Lifecycle provisioning creates only the run root. Active publication creates output/staging children lazily and only after validating the persisted trusted location and current writer capability.

## Output publication

`SchemaMigrationRunner` exclusively owns publication DDL. `OutputArtifactRepository` owns publication and legacy artifact persistence operations and contains no local schema bootstrap.

Task-run publication accepts only a trusted `RunOutputLocation` plus a current `TaskRunWriteCapability`. It reserves a data-root-relative final path transactionally, records the staging path before writing, verifies the writer before each task mutation and the guarded `STAGED → PUBLISHED` CAS, and promotes create-only. Chat publication uses the separate typed chat operation beneath `workspace/chat/<id>/outputs/`. Standalone workflow execution without a trusted writer completes without publishing rather than falling back to arbitrary paths.

Names use deterministic collision suffixes and existing filesystem entries are collisions. Byte reads use a confined read and verify the digest and size of the exact bytes returned; mismatch moves the row to `INTEGRITY_FAILED`. Reconciliation never promotes or cleans task-owned staging without the opaque live writer capability. Migration 003 preserves legacy rows without inventing task attribution or copying absolute paths into publication records.

Artifact access adds workspace/task-run scoped metadata, extraction, FTS5 search when the deployed SQLite supports it, safe text/Markdown preview, exact lineage, sessions, retention pins, and soft/hard retention actions. Scope constraints are applied before counts and snippets. Unsupported FTS5 is reported as unavailable; integrity failure suppresses preview/download. Retention cannot remove active attempts, claims, downloads, references, lineage/legal pins, or unresolved reconciliation state.

The agent Files tab composes run metadata beside the explorer through independent web fragments. `WorkspaceExplorerService` remains filesystem-only and receives no artifact, summary, skill, schedule, or reaction repository dependency.

`GET /api/artifacts` and `/api/artifacts/search` require workspace kind/key scope and may be narrowed to an exact task run. Scoped metadata, preview/download, lineage, pins, and retention routes never authorize or select a filesystem root from project metadata.

## Legacy isolation

Obsolete generic workspace, link, lease, database-backed filesystem identity, project-root, and output-directory definitions are removed from active code and fresh schema. Existing database columns, rows, and filesystem trees remain untouched. Active execution and artifact paths do not read, write, recreate, alias, migrate, or fall back through them.
