# Getting Started With Workspaces, Tasks, And Outputs

This guide follows one piece of executable work from workspace selection through its published outputs.

## 1. Choose The Workspace

Magenta has three workspace kinds:

- **Agent**: the default execution workspace for work submitted to an agent.
- **User**: an explicitly created workspace that can be selected as an execution override.
- **Chat**: a conversation workspace. It is not selectable for task, workflow, or job execution.

Projects add membership and attribution only. Selecting a project never selects or creates a filesystem root.

To use a user workspace, create it first through the workspace selector or `POST /api/workspace/user`. If no override is selected, Magenta uses the bound agent workspace. Agent roots are created lazily; user roots must already exist.

## 2. Create And Submit Work

1. Open `/plans` and create a saved task definition.
2. Add its goal, deliverables, inputs, outputs, steps, validation criteria, and assumptions.
3. Finalize the definition.
4. Select **Submit to Agent**.
5. Choose an agent, optional project metadata, model and priority.
6. Keep the agent workspace default or choose an existing user workspace.
7. Submit and open the selected agent.

Workflows and jobs use the same workspace rule. A submission reserves a distinct task-run identity before execution. Resume adds an attempt to the same run; operator retry or replay creates a new, lineage-linked run.

## 3. Understand The On-Disk Layout

Task-capable agent and user workspaces use:

```text
workspace/<agent-or-user>/<key>/
  tasks/<taskPathKey>/<taskRunId>/
    outputs/       # lazy, publication-owned
    staging/       # lazy, publication-owned
  .skills/         # lazy, hidden control-plane root
  outputs/         # legacy/non-task outputs only
```

The readable task title is not used as filesystem identity. Magenta derives `taskPathKey` from the immutable definition identity. Do not rename or reconstruct task-run locations from display names.

The Files explorer can show task trees, but reserved task, output, staging, and skill structures are read-only to ordinary explorer mutations.

## 4. Monitor The Run

On `/agents/{agentId}`, use:

- **Queue** for active assignment controls and diagnostics.
- **Files** for workspace files plus independently loaded Task Runs.
- **History** for terminal assignment history and transcripts.
- **Outputs** or `/artifacts` for published artifact operations.

`Unavailable` means Magenta did not record enough source facts. It does not mean zero, success, or healthy. See [Task Run Monitoring](task-run-monitoring.md) for status, attempts, output integrity, and lineage.

## 5. Open Outputs Safely

New task outputs publish below the run-local `outputs/` directory using create-only final placement. Artifact metadata records digest, size, publication state, and producer linkage.

Use `/artifacts` for scoped search, detail, preview, download, lineage, and retention. Preview/download are suppressed if bytes are missing, out of scope, unreadable, or fail digest/size verification. Search is unavailable when the deployed SQLite lacks FTS5; that state does not mean no artifacts exist.

The older `/outputs` page remains a compatibility and debugging view for legacy and attributed output rows.

## Common Problems

- **User workspace not found**: create it before submission.
- **Chat workspace rejected**: chat is not a task execution target.
- **FTS5 search unavailable**: use scoped metadata/detail reads.
- **Integrity verification failed**: metadata remains visible, but bytes are blocked.
- **Run metadata unavailable**: Files remains usable; runtime summaries are an independent service.
