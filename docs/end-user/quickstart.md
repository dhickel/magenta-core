# Quickstart

Use this guide to start Magenta, find the main UI pages, and complete the first useful workflow.

## Start Magenta

For local alpha use, start the Spring Boot app from the repository root with the configured local profile and model providers for your environment. A typical development startup is:

```bash
mvn spring-boot:run
```

If the app is already running on a remote host, open the host URL in your browser. In local development, the default Spring Boot URL is usually `http://localhost:8080`.

By default, Magenta stores runtime state under `${user.home}/.magenta`: SQLite at `<magenta.root.path>/magenta.sqlite` and the data root at `<magenta.root.path>/root` when AI config omits `dataRoot`.

When carrying an older local install forward to this layout, stop Magenta, back up the old database and data root, copy old `./chat-memory.db` or the configured database to `<magenta.root.path>/magenta.sqlite`, and copy old `chats/` to `<magenta.root.path>/root/chats/`. Do not copy workspace, output, or runtime directories unless you are archiving them outside Magenta; Magenta does not auto-copy or delete those directories.

## Open The UI

Start at `/`.

The home page links to:

- `/chat`: conversation and anonymous ad hoc planning surface.
- `/dashboard`: operational overview for jobs, agents, inbox messages, outputs, and recent events.
- `/plans`: saved plan and task definitions.
- `/workflows`: graph-based workflow definitions.
- `/jobs`: ordered plan/workflow job definitions and run history.
- `/inbox`: user and agent inbox messages.

Operational pages also expose `/agents`, `/projects`, `/outputs`, and `/settings`.

## Open Alpha Access

Magenta currently has no built-in login gate in this application layer. The alpha UI is intended for local or otherwise trusted environments.

If your deployment adds credentials, they come from an external host/proxy layer. Sign in there before opening Magenta. Browser forms use server-rendered HTMX requests; prefer the UI controls over manual request replay unless you are testing API behavior directly.

## Choose Models

The chat toolbar shows **Agent Model** and **Planning Model** dropdowns populated from configured models. Operational forms use similar model dropdowns or searchable model selectors where selector work has landed.

If no models appear, check `/settings` for runtime defaults and confirm the underlying model provider is configured. Model selection only controls routing; it does not create or download models.

## First Useful Run

1. Open `/agents` and create an agent if none exist.
2. Open `/plans`.
3. Select **New Plan** and fill in the plan title, goal, deliverables, steps, validation criteria, and any structured inputs or outputs.
4. Save, then finalize when the plan is ready.
5. Select **Submit to Agent**.
6. Choose an agent. If a searchable selector is visible, type part of the agent, workspace, or model name and choose a match instead of copying IDs.
7. Submit the assignment.
8. Open the agent detail page and use **Queue**, **History**, **Files**, and **Outputs** to track progress.
9. Follow [Getting Started with Workspaces, Tasks, and Outputs](getting-started-workspaces-tasks-outputs.md) for workspace selection and [Task Run Monitoring](task-run-monitoring.md) for attempts, lineage, and integrity-aware artifacts.

## Common First-Run Errors

- **No active agents available**: create or enable an agent under `/agents`.
- **Title is required** or **Name is required**: fill the required form field before saving.
- **Not found** in a selector: the selected entity was deleted or is not available. Search again and choose a valid option.
- **Anonymous chat plans cannot be saved**: create saved plans from `/plans`; use `/chat` planning only for ad hoc execution.
- **Shell execution service is unavailable**: agent workspace shell execution is not enabled in this runtime.

## Alpha Limits

The UI is optimized for current alpha operation, not hardening. Some forms refresh panels in place; if a panel looks stale after concurrent edits, use the page's reload button or refresh the browser. Some fields remain manual because they represent user-authored content rather than entity selection, especially JSON bindings, cron expressions, shell commands, and exact run IDs.
