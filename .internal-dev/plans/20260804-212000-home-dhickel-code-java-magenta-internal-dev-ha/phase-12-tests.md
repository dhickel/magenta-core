# Phase 12 — Unit and Integration Tests

## Context

The framework is functionally complete. This phase adds comprehensive test coverage across five sequential subphases, each executed by a dedicated implementation agent. Unit tests cover every core type, SPI default, utility, engine transition, and facade method. Integration tests exercise the full stack with real Ollama. A concurrency stress test validates gate correctness under load. Auto-configuration tests verify Spring Boot wiring.

## Goal

Full unit test suite for all core types (TurnContext attribute access TurnMessage factory methods ContextUsage utilization) all SPI default implementations (SqliteConversationMemory insertion-order and concurrent access SqliteSessionStore CRUD SqliteMessageQueueStore claim/ack/release/stale-recovery InMemoryInteractionStore save/load/delete) ToolLoopGuard (identical-call limit trip error-window trip) SerialTurnGate state transitions and timeout DefaultToolRegistry two-stage registration and collision detection; unit tests for DefaultTurnEngine state-machine transitions (every TurnPhase path including REPAIR branch tool-loop-guard trip empty-response repair question-user suspend/resume interrupt mid-turn); integration tests with real Ollama model (full turn loop question-user loop concurrent-turn rejection model capability mismatch turn timeout stream backpressure client disconnect resilience); concurrency stress test (parallel turns on distinct sessions concurrent send/receive on same session rejection stale-claim recovery); auto-configuration smoke test (@SpringBootTest verifying TurnLoop bean exists and is functional); TurnLoopHealthIndicator healthy/unhealthy tests; StreamLifecycle SSE integration test; TurnLoopProperties validation tests

## In Scope

**Write Targets**: turnloop-core/src/test, turnloop-autoconfigure/src/test

- **Subphase A** — Unit tests for all core data-carrier records and enums: `TurnContext` typed attribute access (`setAttribute`, `getAttribute`, `requireAttribute` throws, `hasAttribute`), `TurnMessage` static factories (`user`, `assistant`, `system`, `tool`), `TurnRequest.of()` factory, `ContextUsage.utilizationPercent()`, `TurnDiagnostic` construction, `ToolActivityData`, `ToolActivityStatus`, `NoticeKind.defaultMessage()`, `CompactionContext`, `CompactionResult`, `SessionFilter.all()`, `ConversationSession`, `PendingMessage`, `ClaimedMessage`, `AttributeKey<T>` equality, `AnswerResult` sealed hierarchy (Continue, Abort, SingleMessage, MultiMessage), `QuestionPendingContext`, `ToolDefinition`, `ToolCallback` contract, `ModelCapability` enum, `TurnPhase` enum ordering, `RepairReason` enum
- **Subphase A** — Unit tests for the public exception hierarchy: `TurnLoopException`, `TurnFailedException` (sealed subtypes), `ConversationNotFoundException`, `ToolException` (sealed: `ArgumentCoercionFailed`, `ExecutionFailed`, `LoopExceeded`, `AccessDenied`), `ConfigurationException`
- **Subphase B** — Unit tests for persistence SPI defaults: `SqliteConversationMemory` (insert, retrieval order oldest-first via `ORDER BY id ASC`, clear, concurrent access safety), `SqliteSessionStore` (save, findById, list with `SessionFilter` combinations, updateTitle, setFavorite, setArchived, updateModel, delete), `SqliteMessageQueueStore` (insert PendingMessage, findBySessionId, findById, atomic claim via `claimAtomic`, delete by messageId, delete by sessionId, findStaleClaims by threshold), `InMemoryInteractionStore` (save QuestionPendingContext, load returns same context, load returns null for unknown session, delete removes, survives new instance within same JVM), `SqliteAuditWriter` (all five record methods write to `turnloop_audit_log`, fire-and-forget does not throw)
- **Subphase B** — Unit tests for `MigrationRunner`: first-time application of migrations in version order, version tracking via `turnloop_schema_version`, idempotent re-run (migrations not reapplied when version already recorded), `currentVersion()` returns correct value, empty migration list is no-op
- **Subphase C** — Unit tests for non-persistence SPI defaults: `DefaultToolRegistry` (register, registerToolBean scanning `@Tool` annotations, unregister, `definitions()` excludes framework tools, `resolveForTurn` merges public + framework tools, framework-tool name collision throws `ToolException` with expected message), `ToolAccessPolicy` static factories (`allowAll()` returns all, `allowList(names)` filters to allowed, `blockList(names)` excludes blocked), `ToolResultSummarizer` default (truncation at `maxResultChars`, truncated flag true when raw exceeds limit, suffix `" [truncated: N chars]"`, untruncated passthrough)
- **Subphase C** — Unit tests for utilities: `SerialTurnGate` full state machine (IDLE → `acquire(SEND)` → EXECUTING → `release` → IDLE; IDLE → `acquire(SEND)` → `transitionToAwaitingAnswer` → AWAITING_ANSWER → `release` → IDLE; `acquire` rejects with `Rejected` when EXECUTING; `acquire(ANSWER)` returns `Acquired` when AWAITING_ANSWER; timeout force-release emits `Interrupt` event candidate; `drain` cancels AWAITING_ANSWER, waits for EXECUTING up to timeout, force-releases remainder; `state()` returns correct `GateState`)
- **Subphase C** — Unit tests for `CompositeThinkingExtractor` (Ollama `thinking` metadata extraction, DeepSeek `reasoningContent` extraction, `<think>...</think>` tag fallback, empty thinking when none present, priority order: metadata → reasoningContent → tag → empty)
- **Subphase C** — Unit tests for `TurnEventRenderer`: `json()` produces JSON Lines (one JSON object per line), `sse()` produces `data: {json}\n\n` format, all 8 `TurnEvent` subtypes round-trip correctly
- **Subphase C** — Unit tests for `MarkdownRenderer`: renders markdown when `commonmark` on classpath, returns plain text unmodified when optional dependencies absent (graceful degradation), logs WARN once at startup when dependencies absent
- **Subphase C** — Unit tests for `ToolLoopGuard`: identical-call detection trips at `identicalCallLimit` (default 5), error-window tracking trips at `errorWindowLimit` within `errorWindowSize` window (defaults 5 within 8), non-identical calls reset identical-call counter, error window slides correctly, both trips throw `ToolException.LoopExceeded`
- **Subphase D** — Unit tests for `DefaultTurnEngine` state-machine transitions with mocked SPIs (mocked `ModelRouter`, `ToolRegistry`, `CompletionDetector`, `RepairStrategy`, `ThinkingExtractor`, `ConversationMemory`):
  - PREPARE → INVOKE_MODEL: normal message send, `TurnEvent.Started` emitted with valid `interruptToken`, context assembled
  - INVOKE_MODEL → EVALUATE: model returns text without tool calls, `TurnEvent.Chunk` events emitted, `CompletionDetector.isComplete()` consulted
  - INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS: model returns tool calls, `TurnEvent.ToolActivity` events emitted per tool, `ToolRegistry.resolveForTurn()` called, tool results summarized via `ToolResultSummarizer`
  - EXECUTE_TOOLS → EVALUATE: tool results fed back, loop continues, `toolRoundCount` increments
  - EVALUATE → FINALIZE → DONE: `CompletionDetector` returns true, `TurnEvent.Completed` emitted with `TurnDiagnostic`, `CompactionStrategy.compact()` called asynchronously, `TurnLifecycleListener.onTurnCompleted()` called
  - EVALUATE → REPAIR (empty response): model returns null/empty text and no tool calls, `RepairStrategy.repair(EMPTY_RESPONSE)` called, `TurnEvent.SystemNotice(REPAIR_APPLIED)` emitted, retries up to `maxEmptyResponseRetries`, then REPAIR → FINALIZE if exhausted
  - EVALUATE → REPAIR (tool loop guard): `ToolLoopGuard` throws `LoopExceeded`, caught by engine, `RepairStrategy.repair(TOOL_LOOP_EXCEEDED)` called, `TurnEvent.SystemNotice(TOOL_LOOP_WARNING)` emitted
  - Question-user suspend: `ask_user` framework tool called, interaction handler invoked, `TurnEvent.QuestionPending` emitted, turn suspends in AWAITING_ANSWER state; `answer()` resumes, model invoked with answer message, turn completes
  - Interrupt mid-turn: `interruptToken` polled between phases, `TurnEvent.Interrupt` emitted with reason, gate released, `TurnDiagnostic.outcomeKind = INTERRUPTED`
  - Context exhaustion: `ContextManager` detects token budget exceeded, `TurnEvent.SystemNotice(CONTEXT_EXHAUSTED)` emitted, compaction triggered if eligible
  - Model capability mismatch: stream requested on non-STREAMING model fails fast with `TurnLoopException` before model invocation
  - `TurnEvent.Failed` emitted on unrecoverable error, `TurnDiagnostic.outcomeKind = FAILED`
- **Subphase D** — Unit tests for `DefaultPromptAssembler`: 3-layer default (system prompt from config, registered `PromptContributor` implementations sorted by `@Order`, turn instructions), empty contributors produces base prompt only, contributors receive `TurnContext`
- **Subphase D** — Unit tests for `ContextManager`: windowed queries via `ConversationMemory.getMessages()` + `TokenEstimator`, token-budget management respecting `bufferPercent`, compaction triggering when usage exceeds threshold, oldest-first message selection for compaction candidates
- **Subphase D** — Unit tests for `DefaultTurnLoop` facade: `send()` delegates to engine and returns `TurnResult`, `stream()` returns `Flux<TurnEvent>`, `answer()` resumes suspended turn, `enqueue()`/`claim()`/`ack()`/`release()` delegate to `MessageQueue`, `interrupt()` dispatches via `ActiveTurnRegistry`, `close()` drains gate (cancels AWAITING_ANSWER, waits for EXECUTING up to drain timeout, force-releases remainder), releases pending claims, closes models
- **Subphase D** — Unit tests for `TurnLoopBuilder`: all setter methods accept non-null SPI, `build()` returns `TurnLoop` instance, `build()` with minimum config (just `model`) produces working instance, `build()` throws on missing required configuration
- **Subphase D** — Unit tests for `SpringAiConversationMemoryAdapter`: adapts `TurnMessage` to Spring AI `Message` and back, `addMessages()` and `getMessages()` delegate correctly, `clear()` delegates
- **Subphase E** — Integration tests in `turnloop-autoconfigure/src/test/`: `@SpringBootTest` with `TurnLoop` bean wired; full turn loop with real Ollama (`send()` returns `TurnResult` with non-null message); streaming turn loop (`stream()` emits `Started`, `Chunk` events, terminal `Completed`); tool-calling turn (model invokes `EchoTool`, tool result in transcript, `ToolActivity` events); question-user loop suspend/resume (turn suspends on `ask_user`, `QuestionPending` emitted, `answer()` called, turn resumes and completes); `close()` during question-pending turn (`Interrupt` emitted with reason `"shutdown"`, gate released); interrupt via token (`interrupt()` called mid-stream, `Interrupt` event emitted); model capability mismatch (`stream()` on model without STREAMING fails fast); tool name collision (registering `"ask_user"` throws `ToolException`); turn timeout (EXECUTING turn exceeds timeout, `Failed` emitted, gate released); queue claim/ack/release (claim message → `ClaimedMessage` returned → `ack()` removes it); stale claim recovery (`recoverStaleClaims()` returns claims older than threshold)
- **Subphase E** — Auto-configuration smoke test: `@SpringBootTest` verifying `TurnLoop` bean is created and functional, `TurnLoopProperties` bound from `application.yml`, `TurnLoopHealthIndicator` reports UP when model reachable and DOWN when unreachable
- **Subphase E** — `TurnLoopHealthIndicator` unit tests: healthy when `ChatModel.call()` returns non-null non-empty response within timeout, unhealthy on timeout, unhealthy on connection refused, unhealthy on empty response
- **Subphase E** — `StreamLifecycle` SSE integration test: `Flux<TurnEvent>` wrapped with `SseEmitter`, client disconnect completes emitter without crash, backpressure buffer overflow emits `SystemNotice(BUFFER_OVERFLOW)`, error in flux emits `Failed` before emitter completes
- **Subphase E** — `TurnLoopProperties` validation tests: `models.default` missing from providers throws `ConfigurationException`, invalid endpoint URL throws `ConfigurationException`, `buffer-percent` 0–50 clamps with warning, `max-tool-rounds` < 1 clamps to 1, `timeout` < 1s clamps to 1s, `buffer-capacity` < 1 clamps to 1, `max-result-chars` < 100 clamps to 100
- **Subphase E** — Concurrency stress test in `turnloop-core/src/test/`: 10 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`, each executing `send()` on distinct sessions, `CountDownLatch` for synchronization, verify all 10 complete with `Completed` events, verify no deadlocks or `SerialTurnGate` state corruption, verify stale-claim recovery correctness under concurrent claim/release

## Out of Scope

- Javadoc (Phase 13)
- Markdown documentation (Phase 13)
- Hardening verification scans — grep for `org.springframework.ai`, `ThreadLocal`, dead-code (Phase 13)
- JaCoCo coverage enforcement with >80% threshold (Phase 13)
- Playwright or browser-based validation
- Any test requiring Spring MVC, servlet API, or embedded Tomcat
- Tests for Magenta-specific SPI implementations (Phase 14)

## Dependencies

phase-11-autoconfigure.md

## Constraints

- Unit tests must use in-memory SQLite (`jdbc:sqlite::memory:`) with table creation from `schema.sql` per test class, dropped after each test class via `@AfterAll`
- `DefaultTurnEngineTest` must mock `ModelRouter`, `ToolRegistry`, and other SPIs with Mockito — zero real model calls in unit tests
- Integration tests require Ollama running at `localhost:11434`; skip via `@EnabledIf("ollamaAvailable")` with a static method when Ollama is not reachable
- Concurrency stress test must use `Executors.newVirtualThreadPerTaskExecutor()`
- All tests must pass for the phase gate; no partial subphase validation gates — the gate applies only after subphases A through E are all complete
- Test framework: JUnit 5 (Jupiter) + AssertJ assertions + Mockito mocking
- Test classes in `turnloop-core/src/test/` mirror source package structure exactly
- No test may depend on Spring context or `@SpringBootTest` in `turnloop-core`; Spring-dependent tests live in `turnloop-autoconfigure/src/test/` only

## Implementation Steps

### Subphase A — Core Types, Enums, and Exception Hierarchy Unit Tests

**Agent**: One sequential DeepSeek implementation agent.

Create test files in `turnloop-core/src/test/java/io/mindspice/turnloop/`:

1. `TurnContextTest.java` — test `setAttribute`/`getAttribute` round-trip, `requireAttribute` throws `IllegalStateException` when absent, `hasAttribute` true/false, `AttributeKey` type safety (storing String under `AttributeKey<Integer>` retrieves null for `getAttribute(IntegerKey)`), `messagesToPersist` accumulation, `toolRoundCount` increment, `currentContextUsage` get/set, `activeTools` get/set, immutable fields (`sessionId`, `turnId`, `model`, `streaming`) preserved
2. `TurnMessageTest.java` — test all four static factories (`user`, `assistant`, `system`, `tool`), verify `tool` factory sets `toolName` and `toolCallId`, verify metadata map is non-null, verify role string matches factory
3. `TurnRequestTest.java` — test `of()` factory defaults (`stream=false`, `metadata` empty), verify all-args constructor preserves nullable fields, verify `toolNames` nullable
4. `ContextUsageTest.java` — test `utilizationPercent()` edge cases: zero `maxTokens` returns 0%, normal case returns correct percentage, verify `triggerTokens` and `usedTokens` preserved
5. `TurnDiagnosticTest.java` — test construction with all `OutcomeKind` values, `details` map preserved
6. `ToolActivityDataTest.java` — test construction with `COMPLETED` and `FAILED` statuses
7. `SessionFilterTest.java` — test `all()` factory returns default filter (null fields, limit=50, offset=0), test filtered combinations
8. `ConversationSessionTest.java` — test record construction, default `origin`, `metadata` non-null
9. `PendingMessageTest.java` / `ClaimedMessageTest.java` — test record construction, `enqueuedAt`/`claimedAt` preserved
10. `AttributeKeyTest.java` — test equality by name+type, inequality by different name, inequality by different type
11. `AnswerResultTest.java` — test all four sealed subtypes construction
12. `QuestionPendingContextTest.java` — test record construction
13. `ToolDefinitionTest.java` — test record construction, `inputSchema` preserved
14. `CompactionContextTest.java` / `CompactionResultTest.java` — test record construction
15. `NoticeKindTest.java` — test `defaultMessage()` non-null for every enum constant
16. `TurnPhaseTest.java` — test enum ordering (PREPARE first, DONE last)
17. `ModelCapabilityTest.java` — test all three enum constants exist
18. `RepairReasonTest.java` — test all four enum constants exist
19. `TurnLoopExceptionTest.java` — test all exception subtypes with expected messages and causes, verify `ToolException` sealed hierarchy cannot be extended outside permitted subtypes

### Subphase B — Persistence SPI Defaults and MigrationRunner Unit Tests

**Agent**: One sequential DeepSeek implementation agent.

Create test files in `turnloop-core/src/test/java/io/mindspice/turnloop/persistence/`:

1. Create a shared test helper `SqliteTestFixture` that opens `jdbc:sqlite::memory:`, executes `schema.sql` DDL, and provides a `DataSource`. Each test class uses this fixture in `@BeforeEach` and drops tables or closes the connection in `@AfterEach`.
2. `SqliteConversationMemoryTest.java` — test `addMessages` inserts, `getMessages` returns insertion order (oldest first, verified by `ORDER BY id ASC`), `clear` removes all messages for session, messages from distinct sessions are isolated, concurrent `addMessages` from two threads does not corrupt ordering (use `CountDownLatch` + verify all messages retrieved)
3. `SqliteSessionStoreTest.java` — test `save` inserts, `findById` returns saved session, `findById` returns null for unknown, `list` with `SessionFilter` (favorite filter, archived filter, origin filter, searchQuery matching title, limit/offset pagination, combined filters), `updateTitle` modifies title, `setFavorite` toggles boolean, `setArchived` toggles boolean, `updateModel` modifies model, `delete` removes session, verify `updatedAt` changes on mutation
4. `SqliteMessageQueueStoreTest.java` — test `insert` creates PendingMessage, `findBySessionId` returns messages for session in enqueued order, `findById` returns exact message, `claimAtomic` sets claim token and claimedAt atomically (returns true on first claim, false on second claim of same message), `deleteByMessageId` removes message, `deleteBySessionId` removes all messages for session, `findStaleClaims` returns messages with `claimedAt` before threshold
5. `InMemoryInteractionStoreTest.java` — test `save` stores context, `load` returns identical context, `load` returns null for unknown session, `delete` removes, save→delete→load returns null, multiple saves to same session overwrite
6. `SqliteAuditWriterTest.java` — test all five record methods insert rows into `turnloop_audit_log` with correct `event_type` and non-null `payload_json`, verify methods never throw even when DB connection is broken (fire-and-forget)
7. `MigrationRunnerTest.java` — test first-time application runs all migrations in version order, `currentVersion()` returns latest applied version, re-running same migrations is idempotent (no duplicate application), migration that throws rolls back within its transaction, empty migration list leaves version table untouched

### Subphase C — Non-Persistence SPI Defaults, Utilities, and ToolLoopGuard Unit Tests

**Agent**: One sequential DeepSeek implementation agent.

Create test files in `turnloop-core/src/test/java/io/mindspice/turnloop/`:

1. `DefaultToolRegistryTest.java` — test `register` adds tool, `definitions()` returns public tools only (framework tools excluded), `resolveForTurn` returns merged public + framework tools, `unregister` removes, registering same name twice throws, registering name colliding with framework tool throws `ToolException` with message containing `"conflicts with a framework-internal tool"`, `registerToolBean` scans `@Tool`-annotated methods, framework tools stored in separate internal map
2. `ToolAccessPolicyTest.java` — test `allowAll()` returns unmodified list, `allowList(Set.of("a", "b"))` filters to matching names, `blockList(Set.of("x"))` excludes blocked name, empty allow-list returns empty result, empty block-list returns all
3. `ToolResultSummarizerTest.java` — test default implementation: result under `maxResultChars` returned untruncated with `truncated=false`, result over limit truncated with `truncated=true` and suffix `" [truncated: N chars]"`, exact boundary value not truncated, zero-length result handled
4. `SerialTurnGateTest.java` — test IDLE → `acquire(SEND)` → `Acquired` with non-null `lockToken` → `state()` returns EXECUTING → `release()` → IDLE. Test IDLE → `acquire(SEND)` → `transitionToAwaitingAnswer()` → AWAITING_ANSWER → `acquire(ANSWER)` → `Acquired` → `release()` → IDLE. Test `acquire(SEND)` when EXECUTING → `Rejected` with non-null reason. Test `acquire(SEND)` when AWAITING_ANSWER → `Rejected`. Test `acquire(ANSWER)` when IDLE → `Rejected`. Test timeout: `acquire(SEND)`, sleep past timeout duration, verify gate force-releases and next `acquire(SEND)` succeeds. Test `drain(timeout)`: one session in AWAITING_ANSWER cancels immediately, one in EXECUTING waits up to timeout then force-releases. Test `state()` returns correct enum for unknown session (IDLE).
5. `CompositeThinkingExtractorTest.java` — test Ollama metadata extraction (when `thinkingMetadata` is non-null JSON with `"thinking"` field), DeepSeek `reasoningContent` extraction (when `reasoningContent` is non-null), `<think>` tag fallback (text contains `<think>content</think>`), empty thinking when none present, priority: metadata overrides reasoningContent overrides tag, `visibleText` excludes thinking content, `thinkingText` contains extracted thinking
6. `TurnEventRendererTest.java` — test `json()` produces JSON Lines with one JSON object per line for all 8 TurnEvent subtypes, `sse()` produces `data: {json}\n\n` format, verify JSON includes `turnId`, `timestamp`, and subtype-specific fields
7. `MarkdownRendererTest.java` — test render with dependencies present (bold, italic, code blocks, links), test graceful degradation (when dependencies absent, returns input text unmodified), test that WARN log fires once at startup when absent
8. `ToolLoopGuardTest.java` — test identical-call detection: 4 identical calls do not trip, 5th trips (if limit=5), 6th trips. Test error-window tracking: N errors within window size trips at errorWindowLimit. Test non-identical call resets identical-call counter. Test error window slides (error at position 1 drops out when window moves past it). Test default constructor uses limits (5, 8, 5). Verify `ToolException.LoopExceeded` thrown with tool name and message on trip.

### Subphase D — Engine, Facade, Prompt Assembler, and Context Manager Unit Tests

**Agent**: One sequential DeepSeek implementation agent.

Create test files in `turnloop-core/src/test/java/io/mindspice/turnloop/engine/` and `turnloop-core/src/test/java/io/mindspice/turnloop/`:

1. `DefaultTurnEngineTest.java` — the largest single test class. Use `@Mock ModelRouter`, `@Mock ToolRegistry`, `@Mock CompletionDetector`, `@Mock RepairStrategy`, `@Mock ThinkingExtractor`, `@Mock ConversationMemory`, `@Mock InteractionHandler`, `@Mock InteractionStore`, `@Mock CompactionStrategy`, `@Mock TurnLifecycleListener`. Inject mocks via `DefaultTurnEngine` builder or constructor. Test each transition:
   - PREPARE→INVOKE_MODEL: verify `TurnEvent.Started` emitted with valid `interruptToken`, `ContextManager` queried for windowed messages
   - INVOKE_MODEL→EVALUATE (text only): mock `ModelRouter` returns `Flux.just(chatResponseWithText("Hello"))`, verify `TurnEvent.Chunk` events emitted with `thinking=false`, verify `CompletionDetector.isComplete()` called
   - INVOKE_MODEL→EVALUATE→EXECUTE_TOOLS (tool calls): mock model returns response with tool calls, verify `ToolRegistry.resolveForTurn()` called, verify `ToolResultSummarizer.summarize()` called per tool result, verify `TurnEvent.ToolActivity` emitted per tool
   - EXECUTE_TOOLS→EVALUATE loop: verify tool results appended to conversation, `toolRoundCount` increments, loop continues until `CompletionDetector` returns true
   - EVALUATE→FINALIZE→DONE: `CompletionDetector` returns true, verify `TurnEvent.Completed` with `OutcomeKind.COMPLETED`, verify `CompactionStrategy.compact()` invoked asynchronously, verify `TurnLifecycleListener.onTurnCompleted()` invoked
   - EVALUATE→REPAIR (empty response): mock model returns null/empty text and no tool calls, verify `RepairStrategy.repair(EMPTY_RESPONSE)` called, verify `SystemNotice(REPAIR_APPLIED)` emitted, verify retry loop, verify REPAIR→FINALIZE after max retries exhausted
   - EVALUATE→REPAIR (tool loop guard): mock `ToolLoopGuard` to throw `LoopExceeded`, verify caught, verify `RepairStrategy.repair(TOOL_LOOP_EXCEEDED)` called, verify `SystemNotice(TOOL_LOOP_WARNING)` emitted
   - Question-user suspend: mock model calls `ask_user` tool, verify `InteractionHandler.onQuestionsAsked()` called, verify `QuestionPending` event emitted, verify turn suspended; then call `answer()`, verify turn resumes with answer message in conversation
   - Interrupt: register interrupt via `ActiveTurnRegistry.queueInterrupt()`, verify engine polls `pollInterrupt()` between phases, verify `Interrupt` event emitted, verify `OutcomeKind.INTERRUPTED`, verify gate released
   - Context exhaustion: `ContextManager` reports usage exceeding threshold with compaction ineligible, verify `SystemNotice(CONTEXT_EXHAUSTED)` emitted
   - Model capability mismatch: request `stream=true` but `ModelCapabilityProvider` reports no STREAMING, verify `TurnLoopException` thrown before model invocation
   - Unrecoverable error: mock model throws `RuntimeException`, verify caught, wrapped in `TurnFailedException`, `TurnEvent.Failed` emitted with `OutcomeKind.FAILED`
2. `DefaultPromptAssemblerTest.java` — test 3-layer assembly: system prompt from config, ordered contributors sorted by `@Order`, turn instructions. Test empty contributors produces base prompt only. Test each contributor receives non-null `TurnContext`. Test contributor return value appears in assembled prompt.
3. `ContextManagerTest.java` — test `getWindowedMessages()` respects token budget and buffer percent, oldest messages selected for compaction candidates when budget exceeded, `TokenEstimator.estimateTokens()` consulted per message, compaction not triggered when within budget, `bufferPercent` boundary cases (0%, 50%)
4. `DefaultTurnLoopTest.java` — test `send()` constructs `TurnRequest` and delegates to engine, `stream()` returns `Flux<TurnEvent>`, `answer()` resumes suspended turn, `enqueue()`→`claim()`→`ack()` lifecycle, `release()` returns claim without removing message, `interrupt()` dispatches via registry, `clearSession()` delegates to `ConversationMemory.clear()`, `close()` drains gate (order: cancel AWAITING_ANSWER, wait for EXECUTING, force-release remaining), releases claims, closes models. Verify sub-interfaces `tools()`, `memory()`, `sessions()` delegate correctly.
5. `TurnLoopBuilderTest.java` — test `create(context)` returns builder, each setter method accepts SPI, `build()` with only `model()` configured succeeds, `build()` with all SPIs configured succeeds, builder rejects null SPI arguments, builder returns `TurnLoop` interface (not `DefaultTurnLoop`)
6. `SpringAiConversationMemoryAdapterTest.java` — test `addMessages()` converts `TurnMessage` list to Spring AI `Message` list, `getMessages()` converts back, `clear()` delegates, verify role mapping correctness (`"user"` → `MessageType.USER`, `"assistant"` → `MessageType.ASSISTANT`, `"system"` → `MessageType.SYSTEM`, `"tool"` → `MessageType.TOOL`)

### Subphase E — Integration, Auto-Configuration, and Concurrency Tests

**Agent**: One sequential DeepSeek implementation agent.

Create test files in `turnloop-autoconfigure/src/test/java/io/mindspice/turnloop/autoconfigure/` and `turnloop-core/src/test/java/io/mindspice/turnloop/`:

1. Create a shared integration test helper `OllamaAvailability` with static method `ollamaAvailable()` that attempts a TCP connection to `localhost:11434` and returns `true` if reachable within 2 seconds. Use this in all Ollama-dependent tests via `@EnabledIf("io.mindspice.turnloop.autoconfigure.OllamaAvailability#ollamaAvailable")`.
2. Create an auto-configuration test base class with `@SpringBootTest(classes = {TurnLoopAutoConfiguration.class})` and a minimal `@Configuration` inner class providing mock `ChatModel` beans as needed for non-Ollama tests.
3. `TurnLoopIntegrationTest.java` — `@SpringBootTest` with full `turnloop-autoconfigure` on classpath. Test full turn loop: `send(request)` returns `TurnResult` with non-null message, diagnostic with `OutcomeKind.COMPLETED`. Test streaming: `stream(request)` returns `Flux` emitting `Started`, multiple `Chunk`, terminal `Completed`. Test tool calling: register `EchoTool`, send message that triggers tool use, verify `ToolActivity` events, verify tool result appears in final message. Test question-user loop: configure `InteractionHandler` that returns `AnswerResult.Continue()`, send message that triggers `ask_user`, verify `QuestionPending` event, call `answer()`, verify turn resumes and completes. Test `close()` during question-pending: start a turn that suspends on question, call `close()`, verify `Interrupt` emitted with reason `"shutdown"`, gate released. Test interrupt via token: start a streaming turn, capture `interruptToken` from `Started` event, call `interrupt(token, "test")`, verify `Interrupt` event emitted. Test model capability mismatch: configure model without STREAMING, call `stream()`, verify `TurnLoopException` thrown. Test tool name collision: attempt to register tool named `"ask_user"`, verify `ToolException`. Test turn timeout: configure `turnloop.turn.timeout=2s`, start a turn that takes >2s, verify `Failed` with `OutcomeKind.TIMED_OUT`. Test queue claim/ack/release: `enqueue()` a message, `claim()` returns `ClaimedMessage`, `ack()` removes it, verify `claim()` on same session returns null after ack. Test stale claim recovery: `claim()` a message, do not `ack()`, call `recoverStaleClaims(Duration.ofSeconds(0))`, verify message is recoverable.
4. `TurnLoopAutoConfigurationTest.java` — `@SpringBootTest` verifying `TurnLoop` bean is created and not null. Verify `TurnLoopProperties` bound from `application.yml` in test resources. Verify `DataSource` bean created when none exists. Verify `TurnLoopHealthIndicator` bean created.
5. `TurnLoopHealthIndicatorTest.java` — unit test (no Spring context) mocking `ChatModel`: healthy when `call()` returns non-empty, unhealthy when `call()` throws `IOException`, unhealthy when `call()` returns null, unhealthy when `call()` times out. Test timeout handling.
6. `StreamLifecycleTest.java` — integration test: create `SseEmitter`, wrap `Flux<TurnEvent>` with `StreamLifecycle`, verify SSE events received. Test client disconnect: call `emitter.complete()` mid-stream, verify `Flux` cancelled. Test backpressure: slow consumer, verify buffer fills, `SystemNotice(BUFFER_OVERFLOW)` emitted, oldest events dropped. Test error recovery: `Flux` emits error, verify `Failed` event sent to emitter before completion.
7. `TurnLoopPropertiesTest.java` — test all validation rules: missing `models.default` throws `ConfigurationException` with message containing model name, invalid endpoint URL throws `ConfigurationException` with message containing URL, `buffer-percent` 60 clamps to 50 with warning, `buffer-percent` -5 clamps to 0 with warning, `max-tool-rounds` 0 clamps to 1 with warning, `timeout` 500ms clamps to 1s with warning, `buffer-capacity` 0 clamps to 1 with warning, `max-result-chars` 50 clamps to 100 with warning. Test valid configurations produce no exceptions. Test `drain-timeout` accepts 0.
8. `TurnLoopConcurrencyTest.java` — in `turnloop-core/src/test/`: create 10 distinct session IDs, use `CountDownLatch` to start all threads simultaneously, each thread calls `send()` on a `TurnLoop` backed by mocked engine that completes immediately. Verify `CountDownLatch` reaches zero (all complete), verify no exceptions from `SerialTurnGate`, verify all `TurnResult` objects have `OutcomeKind.COMPLETED`. Second test: 2 threads on same session, verify one succeeds and second receives `TurnGate.Rejected`. Third test: concurrent claim/release on `MessageQueueStore`, verify no stale claims incorrectly recovered.

### Run All Tests

After all subphases A–E are complete, run `mvn test -pl turnloop-core` and `mvn test -pl turnloop-autoconfigure`. All tests must pass. If integration tests require Ollama and it is unavailable, the `@EnabledIf` guard must skip them cleanly (zero failures, zero errors).

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- Handoff Appendix A for expected SPI and type behavior contracts
- Handoff Appendix B for database DDL used in persistence test fixtures
- Handoff Appendix C Phase 6 gate inventory for expected test artifacts
- `concepts.md` Final Validation Criteria (items 1–25) for coverage targets

## Technical Guidance

- **SQLite fixture pattern**: Create a `SqliteTestFixture` helper class that opens `jdbc:sqlite::memory:`, reads `schema.sql` from classpath (`turnloop-core/src/main/resources/schema.sql`), executes each `CREATE TABLE IF NOT EXISTS` statement, and returns a configured `DataSource`. Each persistence test class calls this helper in `@BeforeAll` or `@BeforeEach`. Use `try-with-resources` on the `DataSource` connection in `@AfterAll`.
- **In-memory SQLite isolation**: Each test class gets its own `jdbc:sqlite::memory:` connection. No cross-class state leakage.
- **Mocking strategy**: Use `@ExtendWith(MockitoExtension.class)` for unit tests. `DefaultTurnEngineTest` uses `@Mock` for all SPI dependencies and creates the engine via its builder or constructor. Mock `ModelRouter` to return a `ChatModel` that produces a `Flux<ChatResponse>` with configurable text and tool calls.
- **`DefaultTurnEngineTest` patterns**: For each state transition, write a `@Nested` inner class. Use `StepVerifier.create(flux).expectNextMatches(...).verifyComplete()` for streaming tests. Mock `ChatModel.stream()` to return `Flux.just(chatResponse)` for single-response tests, `Flux.just(chunk1, chunk2, chunk3)` for multi-chunk streaming tests.
- **Integration test skip**: All Ollama-dependent tests use `@EnabledIf("io.mindspice.turnloop.autoconfigure.OllamaAvailability#ollamaAvailable")`. The `OllamaAvailability` class lives in `turnloop-autoconfigure/src/test/java/` and attempts `new java.net.Socket("localhost", 11434)` with a 2-second connect timeout.
- **Auto-configuration test classpath**: `turnloop-autoconfigure/src/test/resources/application.yml` provides minimal config (`turnloop.models.default: test-model`, `turnloop.models.providers.test-model.type: ollama`, `turnloop.models.providers.test-model.endpoint: http://localhost:11434`, `turnloop.models.providers.test-model.model: test`). Non-Ollama tests provide a mock `ChatModel` bean via an inner `@TestConfiguration`.
- **Concurrency test**: Use `Executors.newVirtualThreadPerTaskExecutor()` to spawn threads. Use `CountDownLatch(1)` for start signal and `CountDownLatch(threadCount)` for completion signal. Use `AtomicInteger` for success/failure counters. Assert with `assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()`.
- **Test data factories**: Create `TestFixtures` class with static factory methods for common test objects: `TestFixtures.turnRequest(String sessionId)`, `TestFixtures.turnMessage()`, `TestFixtures.conversationSession(String sessionId)`, etc.

## Validation

- `mvn test -pl turnloop-core` passes (all unit tests + concurrency stress test)
- `mvn test -pl turnloop-autoconfigure` passes (all integration + auto-configuration tests; Ollama-dependent tests skip cleanly when unavailable)
- Concurrency stress test passes (no deadlocks, no gate state corruption, `CountDownLatch` reaches zero)
- Manual coverage checklist: every SPI interface method has at least one test method, every `TurnPhase` ordinal has at least one test path, every `TurnEvent` subtype has at least one serialization/emission test, every `ToolException` subtype has at least one thrown-and-caught test

## Exit Criteria

- All unit tests pass for `turnloop-core`
- All unit and integration tests pass for `turnloop-autoconfigure` (with Ollama-dependent tests either passing or cleanly skipped)
- Concurrency stress test completes successfully with zero deadlocks or gate corruption
- Coverage checklist confirms every SPI method and every `TurnPhase` transition is exercised by at least one test
- Zero test failures, zero test errors, zero skipped tests due to misconfiguration (only integration tests skip due to missing Ollama)
