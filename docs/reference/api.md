# Public API reference

Package: `io.mindspice.magenta.core`. Construction is programmatic through
`TurnLoopBuilder`; `TurnLoop` is the primary facade. SPI interfaces live in
`io.mindspice.magenta.core.spi` ([Extension SPIs](spis.md)).

Every framework exception is unchecked and derives from `TurnLoopException`.
All public methods may throw it; the tables below list the documented
subtypes. Public methods may be called concurrently for different sessions.

## TurnLoop facade

| Method | Contract |
| --- | --- |
| `send(TurnRequest)` | Blocks for one expected `TurnResponse.Outcome`. A non-idle session returns a durable `Queued` receipt instead of executing. Throws `TurnFailedException` (unexpected failure, carries `TurnFailure`), `ConfigurationException` (invalid model or non-streaming model for a streaming-required path), `ConversationNotFoundException` (when an operation requires an existing session). |
| `stream(TurnRequest)` | Cold `Flux<TurnResponse>`: zero or more `Progress`, exactly one expected `Outcome`, then complete; or only `Queued`. Failures use the error channel. Throws `ConfigurationException` on the error channel when the selected model lacks `STREAMING`. |
| `answer(sessionId, interactionId, answers)` | ID-qualified durable answer CAS followed by a blocking continuation. Requires exactly one nonblank answer per ordered question, else `IllegalArgumentException` **before** provider execution. Rejections are typed outcomes (`QuestionsMissing` / `QuestionsAlreadyAnswered` / `QuestionsAlreadyCancelled`); a busy session throws `TurnLoopBusyException`. |
| `streamAnswer(sessionId, interactionId, answers)` | Cold streaming form of `answer`. Rejections are emitted as one outcome value then completion; busy sessions fail the stream with `TurnLoopBusyException`. |
| `pendingQuestions(sessionId)` | Read-only `Optional<TurnResponse.Questions>` rehydration; never changes interaction or gate state. |
| `cancelQuestions(sessionId, interactionId, reason)` | ID-qualified durable cancellation CAS. Returns `QuestionsCancelled` (won), `QuestionsMissing`, `QuestionsAlreadyAnswered`, or `QuestionsAlreadyCancelled`. Reason must be nonblank. Throws `TurnLoopBusyException` while the session executes or a drain owns it. |
| `enqueue(TurnRequest)` | Persists intent and returns `TurnResponse.Queued` (reason `EXPLICIT`); no claim or acknowledgement token is exposed. |
| `drain(sessionId)` | Cold, framework-managed, oldest-first queue drain. Emits each claimed execution's progress and one outcome with `ExecutionOrigin.Queued(messageId)`; acknowledges `Message`/`Questions` internally; stops after `Questions`; releases claims on interruption, timeout, cancellation, or failure. A competing drain or active execution fails the stream with `TurnLoopBusyException`. |
| `interrupt(turnId, reason)` | Requests cooperative interruption of an active execution; returns whether a turn (or pending-question interaction with that turn ID) accepted it. |
| `clearSession(sessionId)` | Clears memory and interaction state, preserving session metadata, queued requests, and audit rows. Deferred while the session executes (active turn interrupted with `session_cleared`). |
| `close()` | Idempotent, thread-safe shutdown: stops drains, cancels pending interactions (`shutdown`), releases awaiting-answer gates, fences active turns, drains the executor/gate up to `drainTimeoutSeconds`, closes provider clients, closes only a framework-owned gate. |

### Sub-interfaces

`tools()` — stable view for this loop:

| Method | Contract |
| --- | --- |
| `register(ToolCallback)` | Registers a public tool; duplicate public name throws `ToolException`. |
| `registerToolBean(Object)` | Registers all `@Tool`-annotated public methods; collisions (including the reserved `ask_user` name) throw `ToolException.AccessDenied` before any registration. |
| `unregister(String)` | Removes a public tool by name; no-op when absent. |
| `definitions()` | Immutable list of public `ToolDefinition` values; framework-internal tools are hidden. |

`memory()`:

| Method | Contract |
| --- | --- |
| `history(sessionId)` | Full session transcript, insertion order, internal interaction markers filtered out. |
| `clear(sessionId)` | Same as `clearSession`. |
| `contextUsage(sessionId)` | `ContextUsage` for the session's stored model (fallback: first registered alias). |

`sessions()`:

| Method | Contract |
| --- | --- |
| `list(SessionFilter)` | Filtered, paged session list. `SessionFilter.all()` is limit 50, offset 0. |
| `get(sessionId)` | The session, or `null` when absent. |
| `updateTitle(sessionId, title)` / `setFavorite` / `setArchived` | Metadata updates. |
| `delete(sessionId)` | Deletes memory, interactions, queued work (including active claims), and session metadata; cascades to audit rows. Deferred while the session executes (active turn interrupted with `session_deleted`). |

## TurnRequest

`TurnRequest(sessionId, message, model, toolNames, metadata)`:

- `sessionId` and `message` must be nonblank (`IllegalArgumentException`).
- `model`: nullable; `null` means normal resolution (session alias, then first
  registered).
- `toolNames`: nullable; `null` means all public + framework tools; `List.of()`
  means no tools; a non-empty list selects exactly those names (an unknown name
  fails the turn during preparation with a tool-category `TurnFailedException`).
- `metadata`: nullable at construction, stored as an immutable empty map when
  omitted.
- `TurnRequest.of(sessionId, message)` selects normal resolution with empty
  metadata.

## Main data types

| Type | Contract |
| --- | --- |
| `TurnResponse` | Sealed progress/outcome protocol; see [Sealed response protocol](response-protocol.md). |
| `TurnMessage` | Sealed `System`, `User`, `Assistant`, `Tool` history variants. Text may be empty but not null; metadata is immutable (`Map.copyOf`). Only `Tool` carries required nonblank `toolName` and `toolCallId`. |
| `ToolResult` | Sealed `Success(output)` (output may be empty, not null) and `Failure(message, category)` (nonblank message, closed `ToolFailureCategory`). |
| `ToolActivityData` | Sealed `Completed(toolName, toolCallId, summary)` and `Failed(..., category)`; all components nonblank, disclosure-safe. |
| `ModelConfig` | Sealed `Ollama`, `OpenAiCompatible`, `DeepSeek`, `Responses`; variant builders via `ModelConfig.ollama()`, `openAiCompatible()`, `deepSeek()`, `responses()` returning `ModelConfig.Builder`; see [Configuration reference](configuration.md#modelconfig). |
| `ExecutionOrigin` | Sealed `Direct()`, `Answer(interactionId)`, `Queued(messageId)`. |
| `ExecutionInfo` | Immutable execution facts: session/turn IDs, origin, ordered `startedAt`/`completedAt`, final `ContextUsage`, immutable ordered `toolActivities`. |
| `ContextUsage` | `(usedTokens, triggerTokens, maxTokens)`; `utilizationPercent()` is 0 when `maxTokens <= 0`. |
| `CompactionContext` | `(sessionId, messagesToCompact, currentUsage)` passed to `CompactionStrategy.compact`. |
| `CompactionResult` | `(summary, messagesCompacted, summaryTokenCount)` returned by `CompactionStrategy.compact`. |
| `TurnFailure` | Safe unexpected-failure facts: session/turn, origin, `TurnPhase`, `TurnFailureCategory`, timestamps, nonblank message. The message carries the provider or root-cause text when usable after configured-key and capability/approval-secret redaction, control-character stripping, and a 500-character bound; otherwise it falls back to category-generic text. |
| `TurnPhase` | `PREPARE`, `INVOKE_MODEL`, `EVALUATE`, `EXECUTE_TOOLS`, `REPAIR`, `FINALIZE`, `DONE`. |
| `TurnFailureCategory` | `MODEL`, `TOOL`, `CONTEXT`, `PERSISTENCE`, `EXECUTION`, `INTERNAL`. |
| `QueueReason` | `EXPLICIT`, `SESSION_ACTIVE`, `AWAITING_QUESTIONS`. |
| `NoticeKind` | `COMPACTION`, `CONTEXT_EXHAUSTED`, `TOOL_LOOP_WARNING`, `REPAIR_APPLIED`, `BUFFER_OVERFLOW` (each with a `defaultMessage()`). The current engine emits `COMPACTION`, `TOOL_LOOP_WARNING`, and `REPAIR_APPLIED`; consumers must handle all kinds. |
| `ModelCapability` | `STREAMING`, `TOOL_CALLING`, `VISION`. |
| `ConversationSession` | Session metadata record: id, nullable title/model, favorite/archived, origin (framework default `"turnloop"`), timestamps, immutable metadata. |
| `SessionFilter` | `(favorite, archived, origin, searchQuery, limit, offset)`; `all()` = 50/0. |

## Advanced and supporting types

| Type | Contract |
| --- | --- |
| `TurnContext` | Mutable, single-turn carrier passed to prompt, tool-policy, completion, repair, and compaction SPIs. Exposes request/session/turn/model, typed `AttributeKey` values, `messagesToPersist`, `currentContextUsage`, `toolRoundCount`, and `activeTools`. `toolRoundCount` counts model responses containing one or more tool calls. Streaming mode and execution origin are engine-owned (not public request properties). |
| `AttributeKey<T>` | Typed context attribute key `(name, type)`; `AttributeKey.of(name, type)`. |
| `Migration` / `MigrationRunner` | Contiguous transactional schema extensions after framework version 1. `MigrationRunner.apply(List<Migration>)` sorts by version, requires contiguity, and commits migration + bookkeeping together. |
| `SerialTurnGate` | Default per-session coordination: `TurnGate` with a per-turn timeout (default 10 minutes, scanned every 30 s) and token-qualified timeout fencing. `setTimeoutCallback` observes timeouts without authorizing release. Caller-supplied gates are caller-closed. |
| `ToolLoopGuard` | Result-aware consecutive repeated-error guard (default 5). `recordToolExecution(call, result)` trips on exactly the configured Nth failure with the same canonical call and typed error identity. Successes and different calls/errors reset or restart the streak. Call arguments and failure details are held only as SHA-256 identities; `forTurn()` creates an independent per-turn copy. |
| `ToolException` | Sealed: `ArgumentCoercionFailed(toolName, callId, message)`, `ExecutionFailed(toolName, callId, message, cause)`, `LoopExceeded(toolName, message, recentErrors)`, `AccessDenied(toolName, message)`. |
| `DefaultToolRegistry` | Default `ToolRegistry`: public + framework-internal registries, annotated-bean registration with argument coercion, `ask_user` auto-registered internally. |
| `DefaultTokenEstimator`, `DefaultCompactionStrategy`, `DefaultCompletionDetector` (singleton via `instance()`), `DefaultRepairStrategy` (singleton), `DefaultToolResultSummarizer`, `DefaultModelCapabilityProvider`, `CompositeThinkingExtractor` | Public default implementations of the corresponding SPIs. |
| `MarkdownRenderer` | Optional utility: renders Markdown to sanitized HTML when commonmark (and optionally the OWASP sanitizer) are on the classpath; otherwise returns input unmodified (`null` becomes `""`). |
| `TurnResponseRenderer` | `json()` / `json(ObjectMapper)` returns `Function<TurnResponse, String>` producing one JSON document with stable `type` tags; no HTTP/SSE/WebSocket framing. |
| `ToolArgumentCoercionModule` | Marker Jackson module for tool argument coercion; registers no global `Object` deserializer. |

## Errors

| Type | Meaning |
| --- | --- |
| `TurnLoopException` | Base; unchecked. Also thrown for operations on a closed loop. |
| `ConfigurationException` | Invalid storage, model, capability, or schema configuration. `details()` is an immutable map; documented keys: `storage_missing`, `storage_conflict`, `invalid_data_root`, `no_models`, `duplicate_model_alias`, `model_alias_mismatch`, `invalid_model_alias`, `unknown_model`, `streaming_unsupported`, `invalid_model_timeout`, `invalid_repeated_errored_tool_call_limit`, `invalid_max_tool_iterations`, `invalid_queue_claim_lease`, `invalid_drain_timeout`, `invalid_configuration`, `schema_initialization_failed`. |
| `TurnFailedException` | Unexpected execution failure; `failure()` returns safe `TurnFailure`. Sealed with `ContextExhaustedException` (also carries final `ContextUsage`). |
| `TurnLoopBusyException` | A session is owned by another execution or drain; `sessionId()` accessor. |
| `ConversationNotFoundException` | Documented for operations that require an existing session (see `TurnLoop.send`); `sessionId()` accessor. The default stores create sessions on demand, so current framework paths do not raise it — a custom `SessionStore` may. |
| `ToolException` subtypes | Tool registration, coercion, execution, or loop failures; `toolName()` (and `callId()` where applicable). |

Expected interruption, timeout, question-state conflicts, queue acceptance,
and lifecycle cancellation are `TurnResponse.Outcome` values, **not**
exceptions.

## Deliberately not public

Implementation details that stay out of the caller API:

- `DefaultTurnLoop`, `DefaultTurnEngine`, `DefaultTurnExecutor`,
  `DefaultModelRouter`, `ModelRouter`, `TurnExecutor`, `ExecutorStrategy`,
  `ActiveTurnRegistry`, `ContextManager`, `DefaultPromptAssembler`,
  `OwnedChatModel`, `ProviderHttpClientBuilders`,
  `SpringAiToolCallbackAdapter`, `ResponseValidation`,
  `ConcurrentActiveTurnRegistry` — internal wiring.
- Spring AI types: `ChatModel`, messages, options, and provider clients never
  appear in public signatures.
- Queue claim tokens, gate lock tokens, and schema hashes are never exposed.

## Related

- [Sealed response protocol](response-protocol.md)
- [Configuration reference](configuration.md)
- [Extension SPIs](spis.md)
- [Tutorials](../README.md#tutorials)
