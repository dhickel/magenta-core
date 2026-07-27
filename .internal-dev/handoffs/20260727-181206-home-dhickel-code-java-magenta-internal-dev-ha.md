# Chat File Panel Auto-Refresh Handoff

## Context

The browser chat Outputs panel lists regular files for the active chat conversation through `GET /api/chat/{conversationId}/files`. It currently refreshes when a conversation loads and when a stream reaches terminal handling, so files created or changed by tools remain invisible while the model continues working.

The authoritative request proposes an `outputs_changed` SSE event and a client call to the existing `loadActiveFiles()` function. Repository inspection confirms that direction, with two important code-truth refinements:

- `ChatService` produces `ChatMessage` stream items, while `ChatController` owns conversion to named, typed `ChatStreamEvent` SSE events. A robust implementation therefore cannot remain limited to only `ChatService.java` and `chat-client.js` without bypassing the established transport boundary.
- `chat-client.js` has separate event callbacks for normal chat streaming and saved-plan execution streaming. Both paths must understand the new event.

The current file-tool registry includes `file_write`, `file_append`, and `file_replace`. No `file_delete` tool is currently registered.

## Objective

Refresh the active browser chat Outputs panel during an ongoing chat or conversation-scoped saved-plan execution stream whenever a recognized file-mutating tool returns an attributable completed result, while preserving conversation isolation, stream behavior, and the existing terminal refresh as a fallback.

## Targets

Primary production targets:

- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java` — identify completed file-mutating tool results and publish an internal stream side-channel signal.
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEvent.java` — define the typed SSE payload for `outputs_changed`.
- `src/main/java/io/mindspice/magenta2/api/web/ChatController.java` — map the internal signal to the named SSE event and treat it as a side channel rather than assistant output.
- `src/main/resources/static/js/chat-client.js` — handle the event in both normal chat and saved-plan execution stream callbacks.

Focused validation targets:

- `src/test/java/io/mindspice/magenta2/ai/chat/service/ChatServiceTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/ChatControllerTest.java`
- An established JavaScript test location, if the repository has a suitable harness; otherwise focused browser validation is the client-behavior authority.

Contract and documentation targets for implementation closeout:

- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/web.md`
- `docs/api/00-index.md`
- `docs/technical/api-reference.md`
- `docs/technical/chat-planning-tasks.md`
- Relevant `.internal-dev` changelog and knowledge records required by the project workflow.

## Features

- A named SSE event, `outputs_changed`, emitted during a live stream after a recognized file-mutating tool returns an attributable completed result.
- A minimal typed payload containing the authoritative `conversationId`; no filesystem path or file content is exposed.
- Recognition of `file_write`, `file_append`, `file_replace`, and compatibility recognition of `file_delete`.
- One invalidation signal per matching completed tool result, including multiple matching calls in one tool batch.
- Client handling for both:
  - `POST /api/chat/stream`
  - `POST /api/chat/{conversationId}/plan/execute/stream`
- Immediate invocation of the existing `loadActiveFiles()` listing flow when the event belongs to the active conversation.
- Preservation of the existing refresh on stream completion and error recovery.

## Settled Decisions

- Use `outputs_changed` as the exact SSE event name.
- Treat the event as an invalidation notice, not a file-delta payload. The existing chat-file listing endpoint remains authoritative.
- Include `conversationId` in the event payload and require the client to match it against the stream conversation and active conversation before refreshing.
- Emit the internal signal only after `ToolCallingManager` returns a result entry that is attributable to a recognized mutator. A normal operation-level error returned as that result may emit a refresh because the listing might have changed. Malformed calls, unexecuted requests, and execution/transport failures that produce no attributable result do not emit an event.
- Emit one signal for each matching completed result. Do not debounce or batch events in this change.
- Await `loadActiveFiles()` from the SSE event callback. The SSE parser already awaits callbacks, which serializes rapid refreshes and avoids same-conversation responses completing out of order.
- Do not clear the panel or introduce a loading state before each refresh; retain the current content until the new listing succeeds.
- Preserve `fileLoadConversationId` and active-conversation checks as the final defense against a response from an earlier conversation overwriting the current panel.
- Add the internal signal to `ChatController.isStreamSideChannel(...)` and map it before the default context/chunk branch so it is never rendered as assistant content or treated as a plan’s final message.
- Do not add a `file_delete` tool as part of this work. If that tool exists when implementation begins, it participates normally; otherwise its name remains recognized for forward compatibility without expanding tool capability.
- JavaScript is justified here because this is an extension of the existing browser SSE transport, not a CRUD or fragment interaction that should be moved to HTMX.

## Constraints

- Keep the change narrowly focused on browser chat output-list freshness.
- Preserve current SSE event behavior, ordering, terminal completion, interruption, context updates, tool activity rendering, and error handling.
- Preserve the current chat file API, workspace confinement, download behavior, file ordering, and listing limits.
- Do not introduce polling, timers, debouncing, new endpoints, file watchers, push infrastructure, or panel markup/CSS changes.
- Do not include file paths, tool arguments, results, or other filesystem details in `outputs_changed`.
- Do not refresh for read-only tools such as `file_list`, `file_read`, or `file_search`.
- Do not let queued/background streams for another conversation change the active conversation’s panel.
- Do not remove the terminal `loadActiveFiles()` calls; they remain a recovery path if an event is missed or transport ends unexpectedly.
- Follow the typed `ChatStreamEvent` transport model rather than sending an ad hoc map from the controller.
- Preserve unrelated edits and complete the project’s specification, documentation, changelog, validation, and commit workflow during implementation.

## Scope

In scope:

- Mid-stream output invalidation from completed recognized file mutations.
- Typed backend stream representation and controller SSE mapping.
- Active-conversation client filtering and output-list reload.
- Normal chat and conversation-scoped saved-plan execution browser streams.
- Focused regression coverage, application startup validation, and browser validation of the changed interaction.

Out of scope:

- Creating or exposing a file-delete tool.
- Refreshing session-card output-count badges during the stream.
- Incremental insertion or removal of individual output rows.
- Changing what qualifies as a chat file or renaming the Outputs panel.
- Changes to agent Files tabs, task-run artifacts, artifact publication, workspace selection, or agent side-panel presentation.
- Debounce controls, refresh-rate configuration, or generalized filesystem event infrastructure.
- Visual redesign or SimplyPages component changes.

## Assumptions

- Browser chat file tools operate in the current chat’s trusted workspace context, and `ChatFileService` remains the authoritative listing source.
- A returned, attributable result for a recognized mutator means the listing may have changed; re-fetching is preferable to inferring the change from tool arguments or output.
- `loadActiveFiles()` remains available in both stream flows and continues to enforce active-conversation checks.
- The stream callback continues to await asynchronous handlers, allowing refreshes from rapid events to remain serialized.
- The new event is additive and consumers that do not recognize it may safely ignore it.
- No user-visible message, toast, or panel animation is required for the invalidation itself.

## Recommended Direction

Represent output invalidation as a dedicated internal `ChatMessage` side-channel role emitted by `ChatService` after `ToolCallingManager` has returned completed, attributable tool transcript entries. Determine matching tools from the executed result entries rather than from the model’s requested calls so malformed or unexecuted requests do not produce false signals.

Extend `ChatStreamEvent` with a minimal `OutputsChanged(conversationId)` payload. In `ChatController`, map the side-channel role to `event: outputs_changed`, classify it as a side channel, and leave all other stream mappings unchanged.

In each browser stream callback, handle `outputs_changed` before terminal handling. Verify that the payload conversation matches both the stream conversation and `activeConversationId()`, then await `loadActiveFiles()`. Reuse a small local handler if that prevents the two stream paths from drifting, but do not introduce a broader client event framework.

Keep completion-time refreshes intact. This yields responsive mid-stream behavior while retaining a simple, authoritative full-list reload and existing recovery semantics.

## Validation

Automated behavior criteria:

- A returned, attributable `file_write`, `file_append`, or `file_replace` tool result produces an output-change side-channel item after tool execution and before stream completion.
- A batch with multiple recognized mutators produces one item per matching result.
- Read-only and unrelated tools produce no output-change item.
- Malformed calls, unexecuted requests, and failures that produce no attributable result produce no output-change item.
- The controller serializes the side channel as named `outputs_changed` SSE with the correct `conversationId` on both chat stream routes.
- The side channel is not emitted as `chunk`, does not create a tool-activity duplicate, and does not replace a plan execution’s final assistant message.
- If an established JavaScript test harness exists, client source coverage confirms both stream callbacks handle the event and await `loadActiveFiles()` behind conversation checks.
- Existing chat streaming, plan execution, output listing, and frontend-controller tests remain passing.

Runtime and browser criteria:

- The Spring Boot application context starts successfully using the project’s bounded startup check.
- In a controlled live browser stream with a delayed continuation after a file mutation, `outputs_changed` is observed before terminal handling and the created file appears in the Outputs panel before `done`.
- Append and replace operations refresh the listing without duplicate rows or panel clearing.
- Switching to another conversation before an event or listing response arrives never overwrites the newly active panel.
- A queued stream belonging to a non-active conversation does not refresh or alter the active panel.
- Rapid recognized mutations converge on the latest authoritative listing without visible flicker or stale rollback.
- Stream completion and stream-error recovery still refresh the panel correctly.
- Focused Playwright validation runs through the required subagent workflow, captures the changed chat surface, and confirms there is no visual regression even though layout is unchanged.
- API and technical documentation list `outputs_changed` and describe its conversation-scoped invalidation semantics.

## Open Questions

None block implementation. Debouncing remains explicitly deferred unless measured behavior demonstrates a real need. `file_delete` support remains conditional on a separately authorized tool capability.

## Sign-off

This handoff is approved as the implementation contract when all of the following are satisfied:

- Mid-stream refresh works for recognized completed file mutations in both browser chat stream paths.
- The event is typed, conversation-scoped, path-free, and handled as a stream side channel.
- Stale or background conversation activity cannot overwrite the active Outputs panel.
- No delete tool, polling system, debounce layer, endpoint, or UI redesign is added.
- Focused tests, broader affected tests, application startup, and live Playwright validation pass.
- Relevant specifications, API/technical documentation, changelog, and reusable knowledge are updated, and the implementation is committed according to repository policy.

Explicit sign-off: Corrected handoff approved for implementation.
