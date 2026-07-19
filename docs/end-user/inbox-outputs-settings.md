# Inbox, Outputs, And Settings

Use these pages for approvals and messages, materialized artifacts, and runtime defaults.

## Inbox

Open `/inbox` to view user and agent inboxes.

### User Inbox

The user inbox shows:

- Message type.
- Sender.
- Body.
- State.
- Actions.

Pending approval messages expose **Approve** and **Reject**. Responded messages show no further action.

Workflow approval nodes and other user-directed messages can appear here. After approving or rejecting, return to the workflow run, agent queue, or dashboard to confirm the runtime resumed or recorded the response.

### Agent Inbox

The agent inbox section has an agent selector and message table.

Actions:

- **Read** marks the message read.
- **Handled** marks the message handled.

The agent selector is currently a plain dropdown in `/inbox`. Agent detail pages also show an inbox tab for inspection in the context of one agent.

## Outputs

Open `/outputs` to browse output artifacts.

Filters:

- Agent.
- Job.
- Project.
- Workspace ID.
- Plan/workflow ID.
- Job assignment ID.
- Job run ID.
- Run ID.
- Run type.
- Type.

Agent, job, and project filters are currently plain dropdowns. Exact ID filters remain manual because they are intended for operator debugging. Type options include `file_path`, `user_message`, `json`, and `text`.

Output rows show output name, artifact type, run, provenance context, creation time, and an action:

- **View** for text, JSON, and user-message artifacts.
- **Download** for binary or file-path artifacts.

The inline content pane loads up to the configured content limit and also offers download.

Output filters are discovery and debugging controls in the current alpha posture. They do not enforce project or agent permissions by themselves. During execution, publication writes directly beneath the effective workspace's reserved `outputs/` subtree; this page shows those output artifacts and their attribution metadata. Chat conversation files remain separate from orchestration output artifacts and are listed from the chat page instead.

## Settings

Open `/settings` to edit runtime defaults.

### Model Routing

Settings include:

- Default agent.
- Default agent name.
- Default model.
- Planning model.
- Summary model.
- Compaction model.
- Context buffer percent.

The default agent field uses a searchable selector where available. Model fields use configured model dropdowns.

### System Chat

System chat settings include:

- Enabled state.
- Model.
- Context limit percent.
- Approved tools.
- System prompt.

The regular `/chat` page remains the canonical conversation surface. System chat settings control bounded dashboard/system-chat behavior.

### Available Models

The available models panel lists models detected from configuration. If a model is missing, update the model provider configuration rather than typing arbitrary model names into unrelated fields.

### Assignment History

**Auto Purge Days** controls automatic purging of terminal assignment rows:

- `-1` disables automatic purge.
- Positive values purge terminal assignment rows older than that many days.

This setting targets assignment history rows, not necessarily output files or saved definitions.

## Common Errors

- **No user messages**: no current user-directed inbox items.
- **Select an agent**: choose an agent before loading agent inbox messages.
- **No outputs found**: adjust filters or confirm the run produced artifacts.
- **Failed to read artifact**: the artifact metadata exists but content could not be loaded from storage.
- **Not found** in settings selector: the saved default agent no longer exists; choose a valid agent or clear the field.
- **Context percent rejected**: keep buffer and context values inside the visible min/max range.

## Alpha Limits

Inbox approval responses are basic approve/reject controls. Outputs are artifact browsers, not a full file manager. Settings are runtime defaults for the current deployment and should be changed carefully; bad model or tool settings can affect chat, planning, summaries, compaction, and agent work.
