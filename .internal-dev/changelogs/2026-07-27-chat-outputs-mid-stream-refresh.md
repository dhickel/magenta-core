# Date

2026-07-27

# Change Summary

Added a mid-stream `outputs_changed` SSE event so the browser chat Outputs panel refreshes while the model continues working after file-mutating tool calls. Previously, files created or changed by `file_write`, `file_append`, or `file_replace` only appeared when the stream completed.

# Files

- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEvent.java` — Added `OutputsChanged(conversationId)` record
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java` — Added `FILE_MUTATING_TOOL_NAMES` constant and `outputs_changed` signal emission after completed tool results in `handleExecuteTools`
- `src/main/java/io/mindspice/magenta2/api/web/ChatController.java` — Mapped `outputs_changed` role to named SSE event and registered it in `isStreamSideChannel`
- `src/main/resources/static/js/chat-client.js` — Added `outputs_changed` handler in both normal chat and plan-execution stream callbacks
- `src/test/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEventSerializationTest.java` — Added serialization test for `OutputsChanged`
- `src/test/java/io/mindspice/magenta2/ai/chat/service/ChatServiceTest.java` — Added `fileMutatingToolNamesContainsExpectedTools` and `fileWriteToolProducesOutputsChangedStreamSignal` tests
- `src/test/java/io/mindspice/magenta2/api/web/ChatControllerTest.java` — Added `outputsChangedSideChannelIsNotPlanExecutionFinalMessage` test
- `.internal-dev/specifications/api.md` — Added API-20260727-01 entry
- `.internal-dev/specifications/web.md` — Added WEB-20260727-01 entry
- `docs/api/00-index.md` — Updated streaming events table
- `docs/technical/api-reference.md` — Documented `outputs_changed` event
- `docs/technical/chat-planning-tasks.md` — Documented mid-stream refresh behavior

# Behavioral Impact

- The Outputs panel in browser chat refreshes mid-stream when a file-mutating tool (`file_write`, `file_append`, `file_replace`, `file_delete`) returns a completed, attributable result.
- Both normal chat streams and conversation-scoped plan execution streams emit the event.
- The event carries only `conversationId`; no file paths or tool details are exposed.
- Read-only tools (`file_list`, `file_read`, `file_search`) do not trigger refreshes.
- Malformed tool calls and execution failures produce no event.
- Client matches `conversationId` against both the stream conversation and active conversation before refreshing, preventing stale panel overwrites.
- Terminal refreshes on `done` and error recovery remain as fallback.

# Risks

- Multiple concurrent file mutations in one tool batch produce multiple serial SSE events and listings, which is intentional; the SSE parser's await-on-callback serializes them.
- No loading state or panel clearing during refresh; current content persists until the new listing renders.

# Follow-up Items

- Consider a `file_delete` tool if the product requires mid-stream delete visibility.
- Debouncing may be useful if measured latency shows meaningful overhead from serial rapid refreshes.
