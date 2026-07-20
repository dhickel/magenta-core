# Reserved-Tree Mutation Route Matrix (Phase 03)

## Topic

Reserved-tree mutation route inventory, enforcement points, and provider limits.

## Source References

- `ReservedWorkspacePathPolicy`
- `ConfinedPathMutationService`
- `WorkspaceExplorerService`
- `AgentFileToolService`
- `AgentShellToolService`
- `WorkspaceController`
- `ChatFileService`
- `OutputArtifactService`
- `AgentSkillManagementService`
- Phase 03 contract and validation review

## Key Takeaways

The earlier “19 routes / no unguarded bypass” claim was incorrect. Controller aliases are not separate mutation boundaries, and two listed services do not have the authority previously attributed to them.

| owner / route | operations | classification / authority | actual enforcement | status |
| --- | --- | --- | --- | --- |
| `WorkspaceController` | REST and HTMX create, save, rename, move, copy, delete | ordinary mutation | Thin delegation to `WorkspaceExplorerService`; no weaker controller file-I/O path | enforced |
| `WorkspaceDirectoryService` | canonical workspace/task-run provisioning and canonical agent-root deletion | structural authority | Layout-derived data-root-relative paths use the same held-handle mkdir/delete boundary; configuration data-root bootstrap is outside workspace content | enforced |
| `WorkspaceExplorerService` | create text/Markdown, save, mkdir, rename/move, file/tree copy, file/tree delete | source and destination independently require `ORDINARY_MUTATION` | `ReservedWorkspacePathPolicy` plus Linux `openat`/`mkdirat`/`renameat2`/`unlinkat` held-directory-handle primitives in `ConfinedPathMutationService`; existing regular files require `fstat.st_nlink == 1` | enforced on supported provider; typed fail-closed elsewhere |
| `AgentFileToolService` | write, append, anchored replace | task runs require `RUN_WRITER_MUTATION`; other active workspaces require `ORDINARY_MUTATION` | Exact trusted `TaskRunLocation` scope, location-bound current capability immediately before each primitive, and the same confined mutation service | enforced |
| `AgentFileToolService` | recursive list/search | `BROWSE` per discovered entry | `walkFileTree` classifies every entry and prunes `.skills` and publication staging subtrees | enforced |
| `AgentShellToolService` | shell process launch | executable confinement | All active task/workspace contexts return explicit `confinementUnavailable`; no host process starts | disabled truthfully |
| `ChatFileService` | count/list/download | read-only chat workspace | No mutation or upload route exists. Chat roots are non-task-selectable and this service has no write primitive. | not a mutation route |
| `OutputArtifactService` | legacy and task output publication | publication authority | Current Phase 02 writer checks are location-bound. Publication still uses its existing publication-specific path checks and ordinary JDK writes; complete run-local publication confinement belongs to Phase 04. It is **not** represented as Phase 03 ordinary-route enforcement. | explicit remaining Phase 04 gap |
| `AgentSkillManagementService` | root skill repository edits | root skill repository authority | Manages application `<dataRoot>/skills`, not workspace-local `.skills`; it is not a workspace reserved-tree mutation route. Workspace `.skills` has no active mutation owner in Phase 03. | removed from this matrix |
| repository upload/import/archive scan | none found | n/a | No `MultipartFile`, upload, archive, or generic import mutation route exists in production code. | absent |

### Provider contract

The deployed Linux 64-bit default file provider is supported through libc relative-handle primitives. JDK `SecureDirectoryStream` alone is insufficient because it cannot create directories or expose link count for the opened file. The native boundary opens every directory component with `O_NOFOLLOW`, holds source/destination parents through the primitive, checks existing regular files with `fstat`, and uses no-replace rename semantics. Non-Linux, non-64-bit, non-default, or non-POSIX providers receive `ConfinementUnavailableException`; no path-based fallback is labeled confined.

## Engine Relevance

- Keep route inventories about actual filesystem boundaries, not controller aliases or future owners.
- Capability currency and location identity are separate checks; both are required at each task-run mutation.
- A lexical/realpath check is not a mutation primitive. Use a held relative handle or fail closed.
- Multi-link regular files are denied before overwrite, append, move, copy-read, or deletion.

## Open Questions

- Phase 04 must move publication writes to the confined primitive and dedicated publication authority before claiming task-output race resistance.
- Workspace-local `.skills` remains intentionally without an active mutation owner until its control-plane phase.
- The native Linux ABI support list must be extended deliberately and tested before another provider can be enabled.
