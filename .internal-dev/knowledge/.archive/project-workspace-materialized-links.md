# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

# Project Workspace Materialized Links

## Topic

Materializing leased project workspaces into run-local workspaces for tool access.

## Source References

- `WorkspaceDirectoryService.materializeAssignmentProjectLink`
- `PlanService.startRun`
- `OrchestrationRunnerService.executeWithLease`
- `AgentFileToolService.resolveProjectScope`
- `AgentShellToolService.resolveProjectWorkingDirectory`

## Key Takeaways

- The canonical project workspace remains under the application-owned `projects/` tree below the configured data root.
- The target run staging model uses the current run root under the relevant agent workspace, with output staging at `runs/<runId>/outputs/`.
- Historical implementations used an assignment-local symlink at `runtime/task-runs/<runId>/projects/<projectId>`; treat that as legacy compatibility, not a new-contract path.
- Shell and file tools still expose the friendly alias `projects/<projectId>/...`, but active assignment contexts now verify the materialized link exists and targets the current project workspace before resolving through it.
- Cleanup has two layers: the runner removes project materialization before lease release, and retention-aware run cleanup removes eligible run staging only after the minimum retention period.

## Engine Relevance

This keeps project membership and lease acquisition in the runner while making the acquired workspace visible through the same path that task prompts and tool aliases advertise. New implementation should route physical paths through centralized layout helpers.

## Open Questions

- If Magenta must support host filesystems without symlink support, the materialization strategy needs an explicit alternate design rather than silently falling back to direct canonical project paths.
