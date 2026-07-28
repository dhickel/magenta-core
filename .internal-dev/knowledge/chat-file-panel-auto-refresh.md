# Chat File Panel Auto-Refresh

## Pattern

The browser chat Outputs panel uses an `outputs_changed` SSE side-channel to refresh mid-stream when file-mutating tools complete, rather than only refreshing at stream termination.

## Key Design Points

- **Side-channel role:** `ChatMessage` with role `"outputs_changed"` emitted by `ChatService.handleExecuteTools` after `ToolCallingManager` returns completed transcript entries.
- **Tool set:** `FILE_MUTATING_TOOL_NAMES = Set.of("file_write", "file_append", "file_replace", "file_delete")` — matched against executed result entries, not requested calls.
- **Event payload:** `ChatStreamEvent.OutputsChanged(conversationId)` — path-free, no tool arguments or results.
- **Controller mapping:** Added before default `chunk` branch in `ChatController.streamResolved`; registered in `isStreamSideChannel` so it doesn't count as plan execution final message.
- **Client filtering:** Both stream callbacks check `data.conversationId === activeConversationId()` before calling `loadActiveFiles()`.
- **No locking/state:** `loadActiveFiles()` uses internal `fileLoadConversationId` guard; the SSE parser already awaits callbacks, serializing rapid refreshes.
- **Terminal fallback:** Existing `done` and error-recovery `loadActiveFiles()` calls remain intact.

## Gotchas

- The `toolChat` non-streaming path passes `null` consumer, so `outputs_changed` is only emitted on the streaming path. This is by design — there is no browser panel to refresh during non-streaming calls.
- The `shouldUpdateConversationUi()` guard in the normal chat callback already handles queued-claim scenarios where the stream conversation differs from the active panel.
- Malformed tool calls (`handleMalformedToolCallArguments`) and execution failures (`handleToolExecutionFailure`) do not emit `outputs_changed` because they produce no attributable completed result.

## Related

- Handoff: `.internal-dev/handoffs/20260727-181206-home-dhickel-code-java-magenta-internal-dev-ha.md`
- Changelog: `.internal-dev/changelogs/2026-07-27-chat-outputs-mid-stream-refresh.md`
- Spec entries: `API-20260727-01`, `WEB-20260727-01`
