## AI Package

This package owns Magenta's AI behavior: chat, planning, tasks, tools, orchestration, workflows, agent profiles, workspaces, skills, and configuration.

### Responsibilities
- Keep AI and agent capabilities organized in domain subpackages (chat, orchestration, config, skills).
- Chat subpackage owns conversational behavior, model routing, streaming, memory, context, and tools.
- Orchestration subpackage owns durable agent profiles, assignments, jobs, projects, schedules, reactions, workflow execution, and workspace identity.
- Workspace subpackage owns filesystem-first workspace identity, descriptor resolution, confined file behavior, and output publication.
- Config subpackage owns file-backed model endpoint configuration and legacy seeding.
- Skills subpackage owns root `skills/` discovery, parsing, assignment, activation, and file management.

### Change guidance
- Prefer new domain subpackages for genuinely new AI capabilities rather than expanding existing packages.
- Keep each subpackage's `AGENTS.md` current with its actual responsibilities.
- Do not add AI machinery without a concrete user-facing workflow.
- For workspace work, `WorkspacePathLayout` is the sole structural path authority and `WorkspaceDirectoryService` alone constructs trusted `WorkspaceDescriptor` values.

### Validation
- Run focused tests in affected subpackages.
- For workspace, execution, or output changes, also run bounded startup and integration validation.
