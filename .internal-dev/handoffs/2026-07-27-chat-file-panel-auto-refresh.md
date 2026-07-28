# Chat File Panel Auto-Refresh During Active Stream

## Context
Currently, the chat "Outputs" side panel only refreshes after the full SSE stream completes. When the AI agent writes files via file_write, file_append, or file_replace during a streaming turn, the panel stays stale until the turn finishes.

## Objective
Make the Outputs panel auto-refresh when file-producing tools run mid-stream, so users see new files immediately.

## Settled Decisions
- Add a new `outputs_changed` SSE event type emitted after file-producing tool calls complete
- Client-side handler calls `loadActiveFiles()` (already race-safe via `fileLoadConversationId`)

## Constraints
- Must not introduce flicker with rapid file writes (acceptable: simple refresh per event)
- Must be conversation-scoped (stale events from previous conversation must not overwrite current panel)
- Backend: ChatService.java emits the event
- Frontend: chat-client.js handles it

## Scope
- Emit `outputs_changed` SSE event in ChatService after any file_write, file_append, file_replace, or file_delete tool call
- Handle the event in chat-client.js SSE reader to call `loadActiveFiles()`
- Validate with existing tests

## Recommended Direction
See above. Minimal change across two files.

## Open Questions
- Should we debounce rapid file writes? Default: no, keep it simple.
