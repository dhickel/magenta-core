## Orchestration Package

This package owns durable agent, assignment, job, project, schedule, event, and execution state.

### Responsibilities
- Persist runtime settings, agent profiles, projects/memberships/events, jobs/items/runs, work assignments, inbox messages, schedules, reactions, and orchestration events.
- Keep assignment queue leasing, cancellation, interruption, retained history, and conversation links observable and durable.
- Execute task, workflow, and job assignments through their model-backed services.
- Preserve workflow `WAITING` state and resume at workflow/task boundaries.
- Persist execution selection as `workspaceType`, `workspaceKey`, and internally derived `workspaceIdentity`.
- Treat project ids as membership and attribution metadata only.
- Resolve one `WorkspaceDescriptor` at each execution-attempt boundary and carry that selection through tools, prompts, child work, and output publication.
- Treat jobs as task-like executable work that owns no filesystem directory.

### Change guidance
- Controllers remain thin; services own validation and use cases; repositories own schema compatibility.
- Type and key are supplied together or omitted together. Omission selects the bound agent; chat is not executable.
- Do not add generic workspace ids, Work Area routing, project-root selection, run staging, filesystem leases, or host paths to active execution records.
- Existing ambiguous legacy rows remain historical and non-executable; deterministic compatibility may derive only from authoritative agent attribution.
- Keep model keys validated through configured models and tool allowlists through `ChatToolRegistry`.
- Do not add orchestration machinery without a concrete user-facing workflow.

### Validation
- Add focused repository/service tests for typed persistence, existing-schema compatibility, queue transitions, waiting/resume, cancellation, and child-run attribution.
- Inspect fresh roots to ensure execution creates only the selected descriptor root and output publication creates `outputs/` lazily.
