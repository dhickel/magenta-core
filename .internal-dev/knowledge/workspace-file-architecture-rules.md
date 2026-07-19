# Topic

Filesystem-first workspace and execution rules after the Phase 05 cutover.

# Source References

- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/phase-05-cut-over-execution-and-outputs.md`
- `docs/technical/workspaces-tools-outputs.md`

# Key Takeaways

- Workspace identity is the closed `agent`, `chat`, or `user` kind plus a validated key. Canonical identity is derived internally as `<kind>/<key>`.
- `WorkspaceDirectoryService` alone constructs descriptors; `WorkspacePathLayout` alone owns structural paths.
- Execution accepts a nullable-together `workspaceType`/`workspaceKey` pair. Omission selects the bound agent; explicit agent must match; existing user may override; chat cannot execute.
- Each execution attempt resolves one descriptor. Projects are membership and attribution metadata and never select or allocate a root.
- Jobs and workflows persist type, key, and identity. They persist no host paths, generic workspace ids, Work Area selectors, or output routes.
- `WAITING` workflows resume from their persisted typed selection.
- Agent profile creation is filesystem-lazy. It creates neither a generic UUID workspace nor eager `outputs/`.
- Publication is the only writer beneath a selected root's lazy `outputs/`. General explorer, file, and shell mutation rejects that subtree.
- `OutputArtifactRepository`, not `WorkspaceRepository`, owns all artifact persistence and schema compatibility.
- Active publication creates no run, job, task, project, Work Area, scratch, or staging hierarchy.
- Existing obsolete database structures and filesystem trees remain untouched and inert until their deletion phase.

# Engine Relevance

When changing assignments, jobs, workflows, tools, prompts, or outputs, pass the typed selection and trusted descriptor rather than reconstructing roots from ids or paths. Fresh-root validation should show no workspace tree at startup; execution may create only the selected descriptor root, and successful publication may then add `outputs/`.

# Open Questions

- Final deletion of obsolete generic workspace, Work Area, link, lease, and output-directory definitions remains Phase 06 work.
- The reusable visible execution selector remains Phase 07 work.
