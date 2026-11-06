# Manage sessions and conversation memory

Sessions are created on demand by the first turn or enqueue, and their metadata
lives in the session store. This page covers listing, updating, deleting, and
inspecting conversation state.

## List and inspect sessions

```java
import io.mindspice.magenta.core.SessionFilter;

// All sessions, first 50 (the default filter):
List<ConversationSession> sessions = loop.sessions().list(SessionFilter.all());

// Filtered and paged:
List<ConversationSession> favorites = loop.sessions().list(
    new SessionFilter(true, null, null, "deploy", 20, 0));
```

`SessionFilter(favorite, archived, origin, searchQuery, limit, offset)`:

- `favorite` / `archived` — `Boolean` filter, `null` means no filter.
- `origin` — origin string filter (sessions created by the framework carry
  origin `"turnloop"`).
- `searchQuery` — matched against the session title.
- `limit` / `offset` — pagination; `SessionFilter.all()` is limit 50, offset 0.

```java
ConversationSession session = loop.sessions().get("session-1");  // null when absent
```

A `ConversationSession` carries `sessionId`, nullable `title` and `model`,
`favorite`/`archived` flags, `origin`, timestamps, and immutable metadata.

## Update and delete

```java
loop.sessions().updateTitle("session-1", "Deployment planning");
loop.sessions().setFavorite("session-1", true);
loop.sessions().setArchived("session-1", false);
loop.sessions().delete("session-1");
```

`delete` clears memory and interactions, deletes queued work (including active
claims), and removes session metadata; child rows cascade. When the session is
mid-turn, deletion is **deferred** until the turn finishes (the active turn is
interrupted first, with reserved reason `session_deleted`).

## Inspect conversation memory

```java
List<TurnMessage> history = loop.memory().history("session-1");   // oldest first
ContextUsage usage = loop.memory().contextUsage("session-1");
```

- `history` returns the ordered sealed `TurnMessage` values — `System`,
  `User`, `Assistant`, `Tool`. Internal interaction bookkeeping messages are
  filtered out, so the list is exactly the visible transcript.
- `contextUsage` returns `ContextUsage(usedTokens, triggerTokens, maxTokens)`
  computed for the session's model (the stored alias, or the first registered
  model). `utilizationPercent()` gives the ratio.
- History reads are safe concurrently; mutations (below) should be serialized
  with respect to execution by the caller.

## Clear memory

```java
loop.memory().clear("session-1");
```

Equivalent to `clearSession`: removes conversation messages and pending
interaction state, but **preserves** session metadata, queued requests, and
audit rows. If the session is executing, the clear is applied after the turn
finishes (the active turn is interrupted first with `session_cleared`).

## Context maintenance behavior

The engine maintains the conversation window automatically:

- Messages are assembled into the model window using the model's
  `contextLength` (or a fallback) with a headroom buffer.
- When `usedTokens` reaches the configured compaction trigger (default 75% of
  the window, set with `compactionTriggerPercent`), the next completed message
  turn triggers **asynchronous compaction**: the oldest messages are replaced
  by a summary message produced by the `CompactionStrategy` (default: a bounded
  summary with a marker prefix).
- If context remains exhausted after compaction, the turn fails with
  `TurnFailedException.ContextExhaustedException`, which carries the final
  `ContextUsage`.
- You can replace `TokenEstimator`, `CompactionStrategy`, and
  `CompletionDetector` through the builder — see
  [Extension SPIs](../reference/spis.md).

## Concurrency guidance

- Public methods may be called concurrently for different sessions.
- For one session, execution-affecting operations are serialized by the
  framework; reads (history, context usage, session get) are safe at any time.
- Avoid calling `clear`/`delete` concurrently with submissions to the same
  session; when that happens, the mutation is deferred and the active turn is
  interrupted, so the transcript is consistent at the next idle point.

## Related

- [Interrupt, cancel, and close safely](lifecycle-and-cancellation.md) —
  clear/delete deferral details.
- [Turn lifecycle explanation](../explanation/turn-lifecycle.md) — context
  window and compaction flow.
- [Public API reference](../reference/api.md) — exact sub-interface contracts.
