# Agents

Use `/agents` to manage execution agents, inspect queues and histories, operate workspaces, and submit saved work.

## Page Layout

The agents page has:

- A sidebar with **Create Agent**, **Reload**, filter, and agent cards.
- Agent detail tabs: dashboard, profile, queue, inbox, jobs, schedules, reactions, workspace (Files), outputs, exec, history, and submit.
- A collapsible **Chat with Agent** panel.

## Create And Select An Agent

Select **Create Agent** to create an active agent profile with a generated name. Select an agent card to open its detail panel. Agent cards show status, workspace health, queue count, inbox count, and default model.

Use **Reload** or refresh the page if another operator creates or deletes agents while you are viewing the list.

## Dashboard Tab

The dashboard tab summarizes:

- Name, status, model, ID, direct-line state, and creation time.
- Queue, inbox, and job counts.
- Current assignment if one is running.
- Workspace health.
- Lifecycle controls.

Use **Disable Agent** to stop new work from targeting an agent. Use **Enable Agent** to make it available again.

## Profile Tab

The profile tab lets you edit:

- Name.
- Status.
- Default model.
- Direct-line enabled state.
- System prompt.
- Approved tools.
- Shell allowlist.

Approved tools and shell commands are comma-separated. Avoid broad wildcard access for public-alpha agents unless the deployment intentionally allows it.

## Queue Tab

The queue tab lists active assignments with type, status, priority, project metadata, typed workspace selection, progress, assignment-queue lease state, job, created time, and actions.

Available actions depend on assignment status:

- **Pause** queued or running work.
- **Resume** waiting or paused work.
- **Cancel** non-terminal work.
- **Diagnostics** to inspect runtime details.
- **Transcript** from diagnostics/history views.
- **Delete** assignments that the runtime marks deletable.
- **Force Interrupt** for running assignments after inspecting diagnostics.

If an assignment is marked suspected stuck, inspect diagnostics before forcing interruption.

## Inbox Tab

The agent inbox tab shows messages sent to the selected agent. Use `/inbox` for read and handled actions. The agent tab is useful for inspection in the context of one agent.

## Jobs, Schedules, And Reactions

The jobs tab lists jobs owned by the agent.

Schedules can enqueue assignments on a cron expression when schedules are enabled. Schedule forms include searchable selectors for job, model override, and workspace where available, plus manual fields for cron, timezone, priority, assignment type, enabled state, and input JSON.

Event reactions can enqueue assignments when matching runtime events occur, when reactions are enabled. Reaction forms use selectors for model override and workspace where available. Filter JSON and input JSON remain manual because they are user-authored JSON objects.

If schedules or reactions are disabled, the tab shows the required feature flag.

## Workspace (Files Tab)

The **Files** tab shows the agent's workspace file explorer, bound to `workspace/agent/<agentId>/`. This is a neutral file browser providing:

- Confined folder navigation.
- File and directory create, rename, move, copy, and delete.
- Text and Markdown viewing and editing.
- Image preview and download.
- Labels, tags, and recent action history.

All operations stay confined to the agent's workspace root. The reserved `outputs/` subtree is read-only through the explorer (output publication is the only writer). The explorer uses HTMX for standard CRUD operations with OOB list, inspector, and modal refreshes.

## Runtime AGENTS.md Guidance

Magenta resolves layered `AGENTS.md` files from the effective workspace descriptor root down to the active confined tool path during execution. Files that exist beneath the descriptor root are ordered root-to-leaf. Closest guidance wins only on conflicts; non-conflicting ancestor guidance remains active. No starter `AGENTS.md` content is generated. Explicit user prompts and assignment instructions override all `AGENTS.md` guidance.

## Outputs Tab

The outputs tab lists recent artifacts associated with the agent. For filtering, downloading, and reading text or JSON outputs inline, use `/outputs`.

## Exec Tab

The exec tab runs a bounded shell command in the agent workspace when the shell execution service is available.

Fields:

- **Command**: executable and arguments.
- **Working Directory**: defaults to the agent workspace root.

Use exec for small diagnostics. If the service is unavailable or the command is outside the allowlist, the UI returns an error.

## History Tab

The history tab shows terminal assignments, diagnostics, transcripts, completion context, and saved agent chats. Use **Purge Terminal History** to delete terminal assignment history older than the selected number of days.

Purging history does not remove saved plan definitions, job definitions, project records, or output artifact files unless the backend explicitly does so elsewhere.

## Submit Tab

Use submit to send saved work directly to this agent:

1. Choose assignment type: task run, workflow run, or job run.
2. Use the searchable **Plan/Workflow/Job ID** selector to choose the target.
3. Set priority.
4. Choose optional model override.
5. Choose optional project context. Project association is metadata and membership context only.
6. Keep the bound agent workspace default or choose an existing declared user workspace.
7. Submit.

The submit result shows assignment ID, project metadata, and the effective workspace type, key, and canonical identity.

The target selector searches across tasks, workflows, and jobs. Choose a result instead of copying an opaque ID when the selector is visible.

## Lifecycle Actions

Agent lifecycle controls include:

- Enable.
- Disable.
- Archive and disable.
- Hard delete after typing the exact confirmation text shown by the UI.

Prefer disabling before destructive deletion. Hard delete is intentionally guarded because it can remove runtime state tied to the agent.

## Common Errors

- **Agent not found**: the agent was deleted or the page is stale.
- **No assignments**: the agent has no active queue items.
- **Shell execution service is unavailable**: exec is not enabled in this runtime.
- **Error creating agent**: check runtime logs and agent profile persistence.
- **Invalid JSON for inputJson/filterJson**: schedule or reaction JSON fields must be objects.
- **Priority must be a number**: enter a numeric priority in the allowed range.

## Alpha Limits

Agent pages expose powerful controls. Diagnostics, force interrupt, shell exec, history purge, archive, and hard delete should be treated as operator actions. Some summary rows still show raw IDs, while target, job, model, workspace, and default-agent fields increasingly use searchable selectors.
