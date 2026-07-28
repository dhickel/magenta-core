# Topic

Filesystem-first workspace identity and the three-kind closed workspace model replacing Avatar, DB-backed Work Areas, and generic workspace persistence.

# Source References

- `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/phase-01-lock-superseding-contracts.md`
- `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/concepts.md`
- `.internal-dev/specifications/architecture.md` (ARCH-20260719-01)
- `.internal-dev/specifications/services.md` (SVC-20260719-01 through SVC-20260719-06)
- `.internal-dev/specifications/decisions.md` (DECISION-20260719-02)
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDescriptor.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceKind.java`

# Key Takeaways

- Magenta uses a closed three-kind workspace model: `AGENT`, `CHAT`, and `USER`, serialized as `agent`, `chat`, and `user`. Canonical identities are `agent/<id>`, `chat/<id>`, and `user/<name>`.
- `WorkspaceDirectoryService` is the sole constructor of trusted `WorkspaceDescriptor` values. It validates keys with `PlainPathSegmentValidator`, checks agent/chat existence through low-level repositories without service cycles, and enforces explicit user-workspace declaration.
- `WorkspacePathLayout` is the sole structural path authority. In addition to the three workspace roots, it owns reserved `tasks/`, workspace-local `.skills/`, canonical task-run, run-local output, and publication-staging grammar introduced by the superseding task-run handoff.
- Agent and chat roots are lazy: the entity must exist, but a missing physical directory reads as empty. Successful file-producing or execution operations may create the root lazily. User roots require explicit creation; missing user roots return `404`.
- Execution carries `workspaceType` and `workspaceKey` together. Omission defaults to the bound agent. Chat selection is rejected. `projectId` is metadata only.
- Existing direct workspace-root `outputs/` publication is legacy/non-task behavior during phased cutover. New task artifacts use `tasks/<taskPathKey>/<taskRunId>/outputs/` only after lifecycle fencing, reserved-tree confinement, and publication ownership activate it. Phase 01 provisions the run directory but keeps output/staging children lazy.
- `OutputArtifactRepository` independently owns `run_output_artifacts` schema, queries, writes, deletion, and attribution.
- Explorer operations accept a `WorkspaceDescriptor` and normalized file-relative path. Explorer mutations reject `outputs/` and descendants. The agent Files tab is the mounted workspace browser surface. The project console may present a read-only member materialization browser only after membership and exact source/generation validation; it passes a trusted `MaterializationLocation` to read-only explorer overloads so the materialization, not the enclosing agent root, is the confinement root.
- Avatar, `WorkArea`/`WorkAreaRepository`/`WorkAreaService`, `WorkspaceService`/`WorkspaceRepository`, `WorkspaceLeaseService`, project roots, filesystem leases, run staging, aliases, and starter `AGENTS.md` generation are removed.
- Existing databases may retain obsolete tables/columns/rows, but active repositories neither read nor write them. Legacy user files remain untouched and inactive.
- Desktop and mobile browser validation should cover the agent Files tab using the documented Playwright MCP workflow.

# Engine Relevance

This is the definitive workspace model for all future Magenta work. When implementing features that touch filesystem identity, always:
1. Use `WorkspaceDirectoryService` to resolve descriptors from type/key.
2. Never reconstruct identity from `Path` or hand-concatenate root segments.
3. Pass the resolved `WorkspaceDescriptor` through all downstream boundaries.
4. Keep canonical identity and normalized file-relative path in separate fields.
5. Treat project association as metadata only.
6. Never introduce generic workspace, Work Area, project-root, or ad hoc staging concepts. The only staging grammar is the reserved run-local publication child owned by `WorkspacePathLayout` and later publication services.

# Open Questions

- Browser-visible project file browsing is implemented as a read-only, current-member, generation-bound materialization view; writable/shared project storage remains out of scope.
- Should workspace sharing or permissions be added for multi-user deployments?
- Should a future migration CLI help operators move legacy workspace files into the new layout?
