# Chat

The `/chat` page is the main conversation surface. Use it for normal assistant conversations and anonymous, ad hoc plan drafting.

## Layout

The chat page has:

- A session sidebar with bulk actions for delete, archive, and favorite.
- An **Agent Model** dropdown for normal responses.
- A **Planning Model** dropdown for planning mode.
- A session indicator showing the active conversation.
- A plan status area with **View plan** details when planning is active.
- An **Outputs** panel for files created in the selected chat.
- A context usage bar.
- A message composer.

## Start Or Continue A Chat

Open `/chat` for a new or existing chat session. Select a prior session from the sidebar to continue it. The session list supports favorites, archives, deletion, and bulk actions.

The sidebar only shows browser-surface `/chat` conversations. Agent chats, planning-only turns, and other internal assistant use do not appear there.

Use **Send** to submit a message. Enter sends the message; Shift+Enter inserts a newline.

The model dropdowns show configured alias names, such as `deepseek-v4-pro-high`, `deepseek-v4-pro-max`, `deepseek-v4-flash-high`, and `deepseek-v4-flash-max`, rather than provider-specific model names. Magenta resolves the alias and its thinking level to the configured provider model when it sends the request.

If you send a normal message while the assistant is still responding, Magenta queues it above the composer and sends it automatically after the active response finishes. Queued cards show their order and survive a page reload or session switch; after a reload, Magenta keeps retrying until the active response is no longer blocking the conversation. Slash commands are not queued mid-turn; wait for the active response to finish before using commands.

Sessions with chat-scoped files show a green `<n> Outputs` badge in the session card. Selecting that session shows the files in the right-side **Outputs** panel with file type, name, relative path when useful, size, modified time, and a **Download** button. The panel lists ordinary chat files created under the chat's persistent file directory; it does not preview or edit file contents.

For installs moved to the root-owned layout, existing chat outputs appear after the operator copies old `chats/` into `<magenta.root.path>/root/chats/`. Magenta does not auto-copy chat files from an old data root.

## Planning Mode

Planning mode creates an anonymous in-chat session plan inside the current chat conversation. These plans are `SESSION_PLAN` records keyed by the chat conversation id. They are not saved to `/plans`, do not define structured runtime inputs or outputs, and cannot be submitted to agents as saved task definitions.

To start planning:

1. Open `/chat`.
2. Use the plan entry point.
3. Answer the three opening questions for goal, assumptions/details/constraints/approach, and expected deliverables.
4. Answer any follow-up clarifying questions in the planning panel.
5. Review **View plan** as the draft fills in.
6. Continue planning, approve and execute, approve and execute clean, or cancel when ready.

The assistant should collect enough goal, deliverable, assumption, step, and validation detail before the plan is approved.

## Answer Questions

When Magenta asks a planning question, answer it in the visible planning prompt. Include direct decisions and constraints. If the question is not relevant, say so explicitly so the plan can record the assumption or continue without it.

## Approve, Continue, Cancel, Execute

- **Continue Planning** keeps planning open when the draft is incomplete.
- **Approve And Exec** executes the approved anonymous plan with the current conversation context.
- **Approve And Exec Clean** executes the approved anonymous plan with only the approved plan instructions and the chat file directory context. It does not delete the conversation transcript; it only omits prior chat messages from that execution prompt.
- **Cancel** exits planning mode for that conversation.

Anonymous chat execution writes chat-scoped files under the persistent chat file directory. If the final response is itself a deliverable, Magenta persists it as `final-message.md` or a collision-safe variant in that directory.

## Saved Plan Chat

Saved plan authoring happens in `/plans`. **New Plan Chat** creates a saved draft there and starts a plan-scoped chat that collects opening answers, gives them to the planning model as seed context, and then continues with model-led follow-up questions until the saved plan is ready to review. It does not use the `/chat` session list or `/api/chat` session architecture.

## Session Management

Use the sidebar to:

- Rename sessions when the title is not useful.
- Mark important sessions as favorites.
- Archive old sessions without deleting them.
- Delete sessions that no longer matter.
- Bulk-select sessions for repeated actions.

Deleting or clearing a session removes the visible conversation history and any pending queued messages for that conversation. It does not automatically delete saved plans, tasks, workflow definitions, jobs, agent history, output artifacts, or persistent chat files created from the conversation.

## Common Errors

- **Unknown command**: only supported chat commands are available. Use visible UI buttons for planning and saved work.
- **Conversation not found**: the session was deleted or the URL points at an invalid conversation.
- **Anonymous chat plans cannot be saved**: create saved plans from `/plans`.
- **Another stream is active**: direct overlapping stream calls are still rejected, but normal browser messages submitted mid-turn are queued and sent after the active response finishes.
- **Model unavailable**: choose a model from the dropdown or update model settings.

## System Chat vs Normal Chat

Magenta has three distinct chat surfaces:

| Surface | Location | Purpose | Authority |
| --- | --- | --- | --- |
| **Normal chat** | `/chat` | Conversational assistant and anonymous plan drafting | Configured chat tools, subject to their individual capability gates |
| **Project chat** | `/projects/{projectId}` Chat tab | Project-scoped chat restricted to read-only context | Server-created per project/agent pair; SSE streaming with safe `textContent` rendering; no file write, process execution, skill activation, or tool authority |
| **System chat** | `/agents/{agentId}` System Chat panel | Operational monitoring and queue-operation proposals | Fixed deny-by-default profile; bounded non-secret reads; restricted tools; non-executing proposals only |

### Project Chat

The project **Chat** tab provides project-scoped streaming chat restricted to read-only context. It is bound to one member agent per conversation and does not create, edit, or delete workspace content. Select a member agent from the tab's selector to scope the conversation. Project chat conversations are server-created per project/agent pair and do not appear in the `/chat` session list.

Project chat SSE transport uses only safe `textContent` DOM rendering with strict event-type validation and explicit payload size limits. It carries no `innerHTML`, tool selection, or HTMX error-policy in its JavaScript module. The stream validates `projectId`, `memberAgentId`, `conversationId`, and `operationId` on every event.

System chat is exclusively agent-scoped and server-created. It uses a server-owned fixed prompt and tool set; runtime settings currently supply its enabled state and model. It can read bounded agent-status, workspace-metadata, and assignment-lifecycle facts, and can propose queue operations (pause, resume, retry, replay, cancel) on assignments. Proposals require explicit user confirmation through the ordinary lifecycle control plane before execution.

System chat cannot:
- Write files or invoke processes
- Approve or activate skills
- Access other agents
- Execute its own proposals
- Be selected or created through the `/chat` surface

System chat SSE streaming uses safe `textContent`-only rendering in `system-chat.js`. It is the only new JavaScript transport added for operational surfaces; all other agent console interactions use HTMX.

## Alpha Limits

Chat and operational pages are intentionally separate. Use `/chat` for conversation and anonymous execution. Use `/plans`, `/workflows`, `/jobs`, and `/agents` for saved definitions and agent submission. System chat provides operational monitoring and proposal creation through the agent console. Plan status and session lists can become stale after concurrent browser tabs edit the same conversation, so refresh before making destructive changes.
