## Workspace Package

This package owns filesystem-first workspace identity, descriptor resolution, confined file behavior, and output artifact publication.

### Active layout
- Agent: `workspace/agent/<agentId>/`
- Chat: `workspace/chat/<conversationId>/`
- User: `workspace/user/<name>/`
- Reserved publication child: `outputs/`

### Responsibilities
- `WorkspaceDirectoryService` is the sole constructor and resolver of trusted `WorkspaceDescriptor` values.
- `WorkspacePathLayout` is the structural path authority.
- Agent/chat reads tolerate absent physical roots. Successful writes/execution may create only the selected root. User roots require explicit creation.
- General file/explorer/shell mutation rejects `outputs/` and descendants.
- `OutputArtifactRepository` independently owns `run_output_artifacts` schema compatibility, persistence, queries, deletion, mapping, and attribution.
- Output publication writes declared values or same-workspace real files directly beneath lazy `outputs/` with normalized, collision-safe names.
- Stored artifact paths are portable data-root-relative values and are never root authorization inputs.

### Change guidance
- Pass `WorkspaceDescriptor` across filesystem boundaries; do not infer identity from `Path` or reconstruct roots downstream.
- Validate traversal, symlink components, real-path confinement, regular-file sources, destination parents, and collisions before publication.
- Reads and failed validation must create nothing. Publication is the only writer beneath `outputs/`.
- Do not synthesize run, job, task, project, Work Area, scratch, staging, or discovery directory layers.
- Obsolete generic workspace, link, lease, Work Area, and output-directory definitions are removed. Do not recreate or replace them.
- Do not migrate, alias, rewrite, or delete legacy filesystem trees during normal startup.

### Validation
- Cover descriptor resolution for all kinds, absent agent/chat roots, explicit user lifecycle, invalid keys, traversal, symlink escape, and reserved-output mutations.
- Cover direct publication, lazy outputs, nested names, source confinement, destination symlinks, collision safety, attribution, persistence failure compensation, and repository independence.
