# End-User Documentation

These guides explain how to operate Magenta through the current alpha browser UI.

Magenta has two main browser surfaces:

- `/chat` for conversations and anonymous ad hoc planning.
- `/dashboard` and the operational pages for agents, plans, workflows, jobs, projects, workspaces, outputs, inboxes, settings, and skills.

## Start Here

- New user setup: [Quickstart](quickstart.md)
- First workspace-backed run: [Getting Started with Workspaces, Tasks, and Outputs](getting-started-workspaces-tasks-outputs.md)
- Runtime history, outputs, and lineage: [Task Run Monitoring](task-run-monitoring.md)
- Timed and event-driven automation: [Schedules and Reactions](schedules-and-reactions.md)
- Conversational work and planning: [Chat](chat.md)
- Manage the application workspace tree: [Projects and Workspaces](projects-and-workspaces.md)
- Operator overview: [Dashboard](dashboard.md)
- Build executable work: [Plans and Tasks](plans-and-tasks.md)
- Chain work together: [Workflows](workflows.md)
- Coordinate repeated or ordered work: [Jobs](jobs.md)
- Manage execution capacity: [Agents](agents.md)
- Manage reusable agent capabilities: [Agent Skills](agent-skills.md)
- Organize workspace-backed work: [Projects and Workspaces](projects-and-workspaces.md)
- Handle messages, artifacts, and defaults: [Inbox, Outputs, and Settings](inbox-outputs-settings.md)
- View, assign, approve, revoke, and activate reusable capabilities: [Agent Skills](agent-skills.md)

## Common Workflows

Create and run a plan:

1. Open `/plans`.
2. Select **New Plan** or **New Plan Chat**.
3. Fill title, goal, deliverables, structured inputs and outputs, steps, validation criteria, and assumptions.
4. Save and finalize the plan.
5. Use **Submit to Agent**, select an agent and optional workspace, then submit.
6. Track the assignment from the selected agent's queue or history.

Create a workflow:

1. Open `/workflows`.
2. Create a draft workflow.
3. Add nodes, routes, and conditions.
4. Validate the graph.
5. Submit the saved workflow to an agent.
6. Resume approval or waiting runs from the workflow run table or inbox.

Operate a running agent:

1. Open `/agents`.
2. Select an agent.
3. Use the dashboard, queue, inbox, Files plus Task Runs, outputs/artifacts, and history tabs.
4. Pause, resume, cancel, force-interrupt, or inspect diagnostics from the queue and history tabs.

## Selector Behavior

Many entity fields now use searchable selectors. Type part of the name, ID, status, or detail, choose a match, and watch for the selected or not-found validation message. This applies to current selector-backed fields such as project, workspace, plan, workflow, model, target, job, and default agent fields.

Some fields intentionally remain manual or plain dropdowns in the alpha UI. Manual fields include JSON bindings, input JSON, filter JSON, cron expressions, run IDs in output filters, free-form prompts, shell command fields, and some agent dropdown filters. Only enter an opaque ID when the visible field still asks for one or when filtering by an exact run ID.

## Alpha Limits

Magenta is still an alpha operator tool. Expect rough edges around validation messages, stale browser state after concurrent edits, incomplete selector coverage, and infrastructure-dependent execution. If the UI and docs disagree, treat the UI response and controller-backed validation as the source of truth and report the mismatch.
