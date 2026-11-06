# Extension SPI reference

SPIs live in `io.mindspice.magenta.core.spi` and are installed explicitly
through `TurnLoopBuilder`. They are Java extension points, not Spring beans.
Replacing a default collaborator transfers the stated correctness obligations
to the caller; the framework still requires one storage source and initializes
the framework schema even when every store is replaced.

## Thread safety and error contract (applies to all SPIs)

- Public methods may be called concurrently for different sessions; store and
  coordination implementations must be safe for that use.
- SPI methods run on the execution path. Unless noted, a throwing SPI
  implementation causes the turn to fail with a categorized
  `TurnFailedException` (`TOOL`, `CONTEXT`, `PERSISTENCE`, ...) rather than
  leaking the raw exception. The exceptions are the observability seams
  (`TurnLifecycleListener`, `TurnLoopMetrics`, `AuditWriter`), which are
  isolated by the framework.
- Keep provider SDK and transport types out of SPI implementations; the public
  contracts use Magenta-owned types only.

## Persistence SPIs

### ConversationMemory

Stores sealed `TurnMessage` values in oldest-first insertion order.

- `getMessages(sessionId)` — full history; internal pending-interaction marker
  messages are stored here too (the facade filters them from `memory().history()`).
- `addMessages(sessionId, messages)` — append.
- `clear(sessionId)` — remove all messages.
- `replaceMessages(sessionId, messages)` — **defaults to clear then add and is
  not intrinsically atomic**; override it when atomic replacement matters
  (the facade uses it for interaction markers and compaction).

### SessionStore

Owns `ConversationSession` create/read/list/delete and title, favorite,
archive, and selected-model updates.

- `findById` is nullable; `list(SessionFilter)` applies the filter semantics
  (favorite/archived/origin/search/limit/offset).
- `save` upserts; `updateModel` is used when an explicit request selection
  changes the session's stored alias.

### InteractionStore

Owns durable question identity and state.

- `Interaction` is sealed as `Pending`, `Answered`, or `Cancelled`; all
  variants share `InteractionInfo`, including the exact emitted `Questions`
  snapshot. `Answered` requires exactly one nonblank answer per question;
  `Cancelled` requires a nonblank reason and a `cancelledAt` not before
  `createdAt`; `interactionId` must be a UUID distinct from the execution turn
  ID.
- `answer` and `cancel` must **atomically qualify both session and interaction
  ID** and return the sealed `MutationResult`: `Applied` (with the resulting
  terminal snapshot), `Missing`, `AlreadyAnswered`, or `AlreadyCancelled`.
  A missing, stale, duplicate, or cancelled target must never mutate a
  different interaction.
- `pending` and `find` must not mutate delivery state or session state.
- `delete` removes only the exact interaction; `deleteBySessionId` is session
  cleanup.
- `save` inserts a new interaction without replacing a different interaction.

### MessageQueueStore

Owns queued intent, oldest-first claims, bounded leases, acknowledgement,
release, and expired-claim recovery.

- `QueuedRequest` preserves nullable model/tool selection (null means normal
  resolution) and immutable metadata, with the enqueue reason and timestamp.
- `claimOldest(sessionId, claimedAt, lease)` claims the oldest unclaimed
  request and returns a `Claim` (request + internal `claimToken` +
  `claimedAt` + `leaseExpiresAt`); the clock overload exists for deterministic
  tests and custom stores.
- `ack(claimToken)` and `release(claimToken)` must affect **only the exact
  active claim** and report whether the token was still active.
- `recoverExpiredClaims(now)` returns claims whose lease expired at the
  supplied instant.
- Custom implementations must support at-least-once recovery semantics: a
  request is retried until its execution is acknowledged.

### AuditWriter

Receives user/assistant messages, tool execution records (including **raw
arguments** and duration), context usage, and turn errors. It is
fire-and-forget: implementations **must** catch and suppress their own
failures so audit problems do not alter caller-visible execution. The framework
also isolates its own call sites. The default is `SqliteAuditWriter`, with a
no-op fallback when it cannot be constructed.

## Tool and prompt SPIs

### ToolRegistry

Two-stage registration: public tools visible to callers and framework-internal
tools hidden from `definitions()`.

- `register` / `unregister` manage public tools; `registerFrameworkTool`
  installs hidden tools; `registerToolBean` discovers `@Tool` methods with
  `@ToolParam` argument coercion.
- `resolveForTurn(context)` returns the merged public + framework callbacks,
  or exactly the requested names when `request.toolNames()` is non-null; an
  unregistered requested name throws `ToolException.AccessDenied`.
- A replacement must retain the internal `ask_user` behavior (the engine
  intercepts the name before invoking the callback) if durable questions are
  required.

### ToolAccessPolicy

Filters resolved callbacks per turn. `@FunctionalInterface` over
`filterTools(availableTools, context)`. Static policies: `allowAll()`,
`allowList(Set<String>)`, `blockList(Set<String>)`.

### ToolResultSummarizer

Converts raw tool execution facts (`toolName`, `toolCallId`, `arguments`,
`rawResult`) into a safe `SummarizeResult(summary, truncated)` for model and
stream use. Avoid exposing secrets from arguments or raw output; a throwing
summarizer converts the tool result into a typed failure.

### PromptContributor

`contribute(context)` returns prompt text appended (in builder registration
order) after the default system prompt on every turn.

## Context and response SPIs

| SPI | Responsibility |
| --- | --- |
| `TokenEstimator` | `estimateTokens(text)` / `estimateTokens(message)` for context accounting. |
| `CompactionStrategy` | `compact(CompactionContext)` → `CompactionResult(summary, messagesCompacted, summaryTokenCount)` when the configured trigger (default 75%, set with `compactionTriggerPercent`) is crossed. |
| `CompletionDetector` | `isComplete(context)` — whether the latest response needs no further model/tool loop. |
| `RepairStrategy` | `repair(context, reason)` → control messages for `EMPTY_RESPONSE`, `TOOL_LOOP_EXCEEDED`, `COMPLETION_NOT_DETECTED`, or `CUSTOM`. |
| `ThinkingExtractor` | `extract(AssistantMessageContent)` → `ThinkingResult(visibleText, thinkingText)` splitting provider output. |
| `ModelCapabilityProvider` | `capabilities(alias)` → subset of `STREAMING`, `TOOL_CALLING`, `VISION`. |

## Coordination and observability SPIs

### TurnGate

Implements the per-session state machine
`IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE`.

- `acquire(sessionId, mode)` returns sealed `Acquired(lockToken)` or
  `Rejected(reason)`. `AWAITING_ANSWER` accepts only `mode == ANSWER`;
  `EXECUTING` always rejects.
- `release` resets to `IDLE`; `transitionToAwaitingAnswer` moves an executing
  session to `AWAITING_ANSWER`; `drain(timeoutMs)` waits for executing turns,
  releases awaiting-answer sessions, and fences still-executing ones through
  the owning loop's token-aware handler.
- `state(sessionId)` projects to `IDLE`, `EXECUTING`, or `AWAITING_ANSWER`.
- A custom gate must preserve per-session serialization and safe timeout
  ownership: never release an executing session while its work could still
  persist.

### TurnLifecycleListener

`onOutcome(outcome)` exactly once per expected terminal outcome (including
`Queued` receipts and standalone question rejections); `onFailure(failure)`
exactly once per unexpected failure. Framework code isolates listener
exceptions.

### TurnLoopMetrics

`recordTurnDuration(model, outcome, durationMs)`, `recordToolCall(activity)`,
`setActiveTurns(count)`, `setQueueDepth(sessionId, depth)`. Outcome names are
the protocol's JSON `type` values plus `failed`. Metrics failures are isolated
by the framework.

## Ownership notes

- The framework closes its default `SerialTurnGate`; a caller-supplied gate
  remains caller-owned and must be closed by the caller.
- A caller-supplied `DataSource` remains caller-owned and is never closed.
- The framework closes provider model clients it created; provider types are
  never exposed to SPI implementations.

## Related

- [Public API reference](api.md)
- [Configuration reference](configuration.md) — defaults per collaborator.
- [Observe and audit how-to](../how-to/observe-and-audit.md)
- [Register tools how-to](../how-to/register-tools.md)
