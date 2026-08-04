# Phase 10 — TurnLoop Facade

## Context

With the engine, SPIs, and persistence complete (phases 06–09), the public `TurnLoop` facade that embedding applications consume can now be built. This phase creates the `TurnLoop` interface, `DefaultTurnLoop` implementation, and `TurnLoopBuilder`. The facade wraps the engine with gate serialization, message queueing, session management, exception translation, and graceful shutdown. All internal exceptions are caught at the facade boundary and wrapped in the public exception hierarchy. No raw `RuntimeException` or Spring AI exception leaks through any `TurnLoop` method.

## Goal

Create TurnLoop interface with full method signatures per handoff Appendix A including send stream answer enqueue claim ack release clearSession interrupt close and sub-interfaces (ToolSubinterface MemorySubinterface SessionSubinterface); implement DefaultTurnLoop orchestrating DefaultTurnEngine TurnGate InteractionStore and all SPI wiring; implement TurnLoopBuilder with all SPI setter methods and ApplicationContext-accepting create() factory; wire close() lifecycle draining gate cancelling pending questions releasing resources and closing models

## In Scope

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoop.java, turnloop-core/src/main/java/io/mindspice/turnloop/DefaultTurnLoop.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoopBuilder.java

- Create `TurnLoop` interface extending `AutoCloseable` with full method signatures from handoff Appendix A: `send(TurnRequest): TurnResult`, `stream(TurnRequest): Flux<TurnEvent>`, `answer(String, String): TurnResult`, `enqueue(TurnRequest): PendingMessage`, `claim(String): ClaimedMessage`, `ack(String): void`, `release(String): void`, `clearSession(String): void`, `interrupt(String, String): boolean`, `close(): void`, plus accessor sub-interfaces `tools(): ToolSubinterface`, `memory(): MemorySubinterface`, `sessions(): SessionSubinterface`
- Create inner interfaces with exact Appendix A signatures: `ToolSubinterface` (register, registerToolBean, unregister, definitions), `MemorySubinterface` (history, clear, contextUsage), `SessionSubinterface` (list, get, updateTitle, setFavorite, setArchived, delete)
- Create `DefaultTurnLoop` (package-private final) implementing `TurnLoop`: delegates to `DefaultTurnEngine`, `TurnGate`, `MessageQueue`, `InteractionStore`, `SessionStore`, `ConversationMemory`, `ActiveTurnRegistry`, `TurnExecutor`; wraps all internal exceptions in public hierarchy at every public method boundary; manages `close()` lifecycle with ordered 7-step drain; implements sub-interfaces as package-private inner classes backed by the enclosing instance
- Create `TurnLoopBuilder` (public final): `static TurnLoopBuilder create(ApplicationContext)`, builder methods for every SPI from handoff Appendix A and configuration (model, dataRoot, defaultSystemPrompt, objectMapper, drainTimeout), `build()` validates configuration and constructs `DefaultTurnLoop` with all wired dependencies, returns `TurnLoop`
- Verify `mvn compile -pl turnloop-core` passes with zero `org.springframework.ai` imports in `io.mindspice.turnloop` facade package

## Out of Scope

- Auto-configuration (Phase 11)
- Tests (Phase 12)
- Documentation (Phase 13)

## Dependencies

phase-09-engine-utilities.md

## Constraints

- `TurnLoop` extends `AutoCloseable`; `close()` is idempotent and must never throw
- `close()` drains `TurnGate` (all states: cancels AWAITING_ANSWER turns with `TurnEvent.Interrupt`, waits for EXECUTING turns up to drain timeout, forces release), releases stream resources, releases pending claims, closes `AutoCloseable` `ChatModel` instances, shuts down `TurnExecutor`
- `TurnLoopBuilder` requires `ApplicationContext` parameter; `static create(ApplicationContext)` is the sole factory entry point
- `TurnLoopBuilder.build()` must produce a working instance with zero external configuration when no SPI overrides are set
- No raw `RuntimeException` or Spring AI exception leaks through any `TurnLoop` method
- All internal exceptions (`ToolUseAbort`, `NonTransientAiException`, Spring AI exceptions from ModelRouter) caught and wrapped in public `TurnLoopException` subtypes at the facade boundary
- `DefaultTurnLoop` is package-private — the only public construction path is `TurnLoopBuilder.build()`
- Sub-interfaces (`tools()`, `memory()`, `sessions()`) return lightweight views backed by the enclosing `DefaultTurnLoop` instance; same instance returned on repeated calls
- `ack()` and `release()` on stale claims log a warning and return normally; only storage failures throw
- `promptContributor()` on TurnLoopBuilder is cumulative (adds to list); all other setters replace

## Implementation Steps

This phase is split into three sequential lettered subphases. Each subphase maps to exactly one sequential agent session. Subphases must complete in order A → B → C. Phase-level validation and exit criteria apply after Subphase C completes; no intermediate independent validation gates exist at subphase boundaries.

### Subphase A — TurnLoop Interface

**Agent session 1 of 3.** Create `TurnLoop.java`.

1. Create `TurnLoop` interface in `io.mindspice.turnloop` extending `AutoCloseable`.
2. Declare every method with exact signatures from handoff Appendix A. Use `@NonNull` from Spring's null-safety annotations on every parameter and return type where the handoff specifies it.

   ```java
   TurnResult send(@NonNull TurnRequest request);
   Flux<TurnEvent> stream(@NonNull TurnRequest request);
   TurnResult answer(@NonNull String sessionId, @NonNull String answer);
   PendingMessage enqueue(@NonNull TurnRequest request);
   @Nullable ClaimedMessage claim(@NonNull String sessionId);
   void ack(@NonNull String claimToken);
   void release(@NonNull String claimToken);
   void clearSession(@NonNull String sessionId);
   boolean interrupt(@NonNull String turnId, @NonNull String reason);
   @Override void close();
   ToolSubinterface tools();
   MemorySubinterface memory();
   SessionSubinterface sessions();
   ```

3. Create inner interface `ToolSubinterface`:
   ```java
   interface ToolSubinterface {
       void register(@NonNull ToolCallback tool);
       void registerToolBean(@NonNull Object bean);
       void unregister(@NonNull String toolName);
       List<ToolDefinition> definitions();
   }
   ```

4. Create inner interface `MemorySubinterface`:
   ```java
   interface MemorySubinterface {
       List<TurnMessage> history(@NonNull String sessionId);
       void clear(@NonNull String sessionId);
       ContextUsage contextUsage(@NonNull String sessionId);
   }
   ```

5. Create inner interface `SessionSubinterface`:
   ```java
   interface SessionSubinterface {
       List<ConversationSession> list(@NonNull SessionFilter filter);
       @Nullable ConversationSession get(@NonNull String sessionId);
       void updateTitle(@NonNull String sessionId, @NonNull String title);
       void setFavorite(@NonNull String sessionId, boolean favorite);
       void setArchived(@NonNull String sessionId, boolean archived);
       void delete(@NonNull String sessionId);
   }
   ```

6. Add Javadoc on the class and every method documenting thread-safety (callers must serialize per-session via `send`/`stream`/`answer`; `tools()`/`memory()`/`sessions()` are safe for concurrent read), nullability contracts, and which methods throw `TurnLoopException` or its subtypes.

7. Compile: `mvn compile -pl turnloop-core` must pass (interface-only compiles against existing types from phases 06–09).

### Subphase B — DefaultTurnLoop Implementation

**Agent session 2 of 3.** Create `DefaultTurnLoop.java`. This is the largest single implementation unit in the plan.

#### B.1 Constructor and fields

Declare `DefaultTurnLoop` as `final class DefaultTurnLoop implements TurnLoop` (package-private, in `io.mindspice.turnloop`). Constructor accepts all required dependencies as a flat parameter list (the builder wires them). Required fields:

| Field | Type | Source |
|---|---|---|
| `engine` | `DefaultTurnEngine` | Constructed by builder |
| `gate` | `TurnGate` | From builder (default: `SerialTurnGate`) |
| `messageQueue` | `MessageQueue` | From builder |
| `interactionStore` | `InteractionStore` | From builder |
| `sessionStore` | `SessionStore` | From builder |
| `conversationMemory` | `ConversationMemory` | From builder |
| `activeTurnRegistry` | `ActiveTurnRegistry` | From builder |
| `executor` | `TurnExecutor` | From builder |
| `lifecycleListener` | `TurnLifecycleListener` | From builder (default: no-op) |
| `metrics` | `TurnLoopMetrics` | From builder (default: no-op) |
| `objectMapper` | `ObjectMapper` | From builder |
| `defaultSystemPrompt` | `String` | From builder |
| `drainTimeoutSeconds` | `long` | From builder (default: 30) |
| `closed` | `AtomicBoolean` | For idempotent close() |

Store the `closed` flag. The sub-interfaces (`tools()`, `memory()`, `sessions()`) are backed by package-private inner classes that hold a reference to the enclosing `DefaultTurnLoop` instance and delegate to its fields.

#### B.2 send() implementation

```
1. Validate request (sessionId non-blank, message non-blank). If invalid, throw TurnLoopException.
2. Acquire gate for sessionId with SEND mode.
   - If gate rejects (wrong state), throw TurnLoopException("Turn already in progress for session " + sessionId).
3. Resolve model from request.model() or default model alias. If model not found, throw ConfigurationException.
4. Construct TurnContext from TurnRequest:
   - Set sessionId, model alias, stream=false
   - Copy toolCallbacks from request
   - Set system prompt override if request provides one
   - Set attributes from request.attributes() into TurnContext typed store
   - Bind interruptToken from ActiveTurnRegistry.register()
5. Register active turn in ActiveTurnRegistry.
6. Call engine.execute(context) → TurnResult.
7. Release gate (in finally block).
8. Unregister active turn from ActiveTurnRegistry.
9. Fire lifecycleListener.onTurnCompleted(result).
10. Record metrics (duration, outcome).
11. Return result.

Exception wrapping:
- ToolUseAbort → caught, wrapped in TurnFailedException with ABORTED outcome
- NonTransientAiException → caught, wrapped in TurnFailedException with FAILED outcome
- Any RuntimeException from engine → caught, wrapped in TurnFailedException with FAILED outcome
- Gate rejection → TurnLoopException with descriptive message (not TurnFailedException since no turn started)
```

#### B.3 stream() implementation

```
1. Validate request.
2. Resolve model. Check ModelCapabilityProvider for STREAMING capability. If absent, throw TurnLoopException fast.
3. Acquire gate with STREAM mode.
4. Construct TurnContext (stream=true).
5. Register active turn.
6. Return engine.stream(context)
   .doOnSubscribe(s -> metrics.recordStreamStart(sessionId))
   .doFinally(signal -> {
       gate.release();
       activeTurnRegistry.unregister(turnId);
       if (signal == SignalType.ON_COMPLETE) {
           lifecycleListener.onTurnCompleted(turnDiagnosticFromLastEvent);
       }
       metrics.recordTurnEnd(sessionId, outcome);
   })
   .onErrorMap(e -> wrapInternalException(e));

Exception wrapping:
- onErrorMap catches all Throwable, maps internal types to public hierarchy
- Gate rejection thrown before Flux returned
```

#### B.4 answer() implementation

```
1. Validate sessionId non-blank, answer non-blank.
2. Check gate is in AWAITING_ANSWER state for sessionId. If not, throw TurnLoopException("No question pending for session " + sessionId).
3. Load QuestionPendingContext from interactionStore. If null (stale/expired), throw TurnLoopException("No pending question context for session " + sessionId).
4. Construct TurnContext continuation:
   - sessionId from stored context
   - injected user answer as single-turn message
   - preserve original model alias and tool callbacks from stored context
5. Call engine.resume(context) → TurnResult.
6. On success, delete interactionStore entry.
7. Release gate.
8. Return result.

Exception wrapping:
- Stored context mismatch → TurnLoopException
- Engine resume failure → TurnFailedException
```

#### B.5 enqueue() / claim() / ack() / release()

Each delegates to `MessageQueue`:

- `enqueue(TurnRequest)`: wraps request text and metadata into `PendingMessage` record, delegates to `messageQueue.enqueue()`. Wrap queue exceptions in `TurnLoopException`.
- `claim(String sessionId)`: delegates to `messageQueue.claim()`. Returns null if queue empty. Wrap in `TurnLoopException` on storage failure.
- `ack(String claimToken)`: delegates to `messageQueue.ack()`. If claimToken is already stale/acked, log warning and return (do not throw). Wrap storage failures in `TurnLoopException`.
- `release(String claimToken)`: delegates to `messageQueue.release()`. If stale, log warning and return (do not throw). Wrap storage failures in `TurnLoopException`.

#### B.6 clearSession() implementation

```
1. Validate sessionId.
2. Call conversationMemory.clear(sessionId).
3. Call interactionStore.delete(sessionId).
4. SessionStore itself is NOT deleted (session metadata persists).
5. Wrap any persistence exception in TurnLoopException.
```

#### B.7 interrupt() implementation

```
1. Validate turnId non-blank.
2. Look up active turn in ActiveTurnRegistry.
3. If not found, return false (turn already completed or never existed).
4. Call activeTurn.interrupt(reason).
5. Gate transitions through interrupt — the engine's polling loop picks up the interrupt flag.
6. Return true.
```

#### B.8 close() lifecycle — exact 7-step ordered sequence

```
1. CAS closed flag false→true. If already true, return immediately (idempotent).

2. CANCEL PENDING QUESTIONS:
   - Iterate all AWAITING_ANSWER entries in gate.
   - For each, load QuestionPendingContext from interactionStore.
   - Emit TurnEvent.Interrupt(turnId, "shutdown") if a stream is active for that turn.
   - Delete interactionStore entry.
   - Gate.forceRelease(sessionId).

3. WAIT FOR EXECUTING TURNS:
   - Poll gate for EXECUTING turns.
   - Wait up to drainTimeoutSeconds (default 30) for turns to complete naturally.
   - Track remaining with a CountDownLatch or polling loop.

4. FORCE-RELEASE REMAINING:
   - After timeout, iterate remaining EXECUTING turns.
   - Call gate.forceRelease() for each.
   - Emit TurnEvent.Interrupt(turnId, "shutdown-force") for any active stream.
   - Unregister from ActiveTurnRegistry.

5. RELEASE STREAM RESOURCES:
   - Cancel any active Flux subscriptions tracked by the facade.
   - Ensure no SseEmitter or subscriber leak (the StreamLifecycle in autoconfigure owns emitter lifecycle; facade cancels the source Flux).

6. RELEASE PENDING CLAIMS:
   - Call messageQueue.releaseAll() or iterate pending claims and release each.
   - Log count of released claims at INFO.

7. CLOSE MODELS AND SHUTDOWN EXECUTOR:
   - Close all AutoCloseable ChatModel instances held by ModelRouter.
   - Shut down TurnExecutor (blocks until running tasks complete, with bounded timeout).
   - Log "TurnLoop closed" at INFO.

All steps catch exceptions individually and log at ERROR without aborting the shutdown sequence.
No step may throw out of close().
```

#### B.9 Sub-interface inner classes

Create three package-private inner classes in `DefaultTurnLoop.java`:

- `Tools` implements `ToolSubinterface`: delegates `register`/`registerToolBean`/`unregister`/`definitions` to the `DefaultToolRegistry` held by the enclosing instance. Each method wraps exceptions in `TurnLoopException`.
- `Memory` implements `MemorySubinterface`: delegates `history` → `conversationMemory.getMessages()`, `clear` → `conversationMemory.clear()`, `contextUsage` → fresh calculation from conversationMemory. Wrap persistence exceptions.
- `Sessions` implements `SessionSubinterface`: delegates all methods to `sessionStore`. `list()` applies `SessionFilter`. `get()` returns null if not found. `delete()` also clears conversation memory for that session. Wrap exceptions.

Each inner class constructor takes the enclosing `DefaultTurnLoop` instance and accesses its fields directly.

#### B.10 Exception wrapping summary

| Internal exception | Public wrapper | Notes |
|---|---|---|
| `ToolUseAbort` | `TurnFailedException(TurnOutcome.ABORTED, diagnostic)` | Map abort reason to diagnostic |
| `NonTransientAiException` | `TurnFailedException(TurnOutcome.FAILED, diagnostic)` | Include original message |
| Spring AI `AiClientException` (from ModelRouter) | `TurnFailedException(TurnOutcome.FAILED)` | Caught in send/stream, never escapes facade |
| Gate rejection (wrong state) | `TurnLoopException("Turn already in progress for session X")` | Not TurnFailedException — no turn started |
| `ConfigurationException` | re-thrown as-is (already public) | |
| `ConversationNotFoundException` | re-thrown as-is (already public) | |
| `ToolException` | re-thrown as-is (already public) | |
| Any unexpected `RuntimeException` | `TurnLoopException` wrapping original | Catch-all at every public method |

#### B.11 Verify compilation

Run `mvn compile -pl turnloop-core`. Fix all compilation errors. DefaultTurnLoop must compile against all types from phases 06–09.

### Subphase C — TurnLoopBuilder

**Agent session 3 of 3.** Create `TurnLoopBuilder.java`.

#### C.1 Class declaration and fields

`public final class TurnLoopBuilder` in `io.mindspice.turnloop`. Private constructor taking `ApplicationContext`. Store context reference. Declare a mutable field for each configurable item with sensible defaults:

| Field | Type | Default |
|---|---|---|
| `context` | `ApplicationContext` | from `create()` |
| `modelConfigs` | `Map<String, ModelConfig>` | empty HashMap |
| `dataRoot` | `Path` | `Path.of(System.getProperty("java.io.tmpdir"), "turnloop")` |
| `defaultSystemPrompt` | `String` | `"You are a helpful AI assistant."` |
| `toolRegistry` | `ToolRegistry` | `new DefaultToolRegistry()` |
| `toolAccessPolicy` | `ToolAccessPolicy` | `ToolAccessPolicy.allowAll()` |
| `toolResultSummarizer` | `ToolResultSummarizer` | default (truncate at 4000 chars) |
| `interactionHandler` | `InteractionHandler` | null (required — build() throws if null when tools include ask_user) |
| `interactionStore` | `InteractionStore` | `new InMemoryInteractionStore()` |
| `conversationMemory` | `ConversationMemory` | `new SqliteConversationMemory(dataSource)` |
| `sessionStore` | `SessionStore` | `new SqliteSessionStore(dataSource)` |
| `tokenEstimator` | `TokenEstimator` | JTokkit-based default |
| `compactionStrategy` | `CompactionStrategy` | summary-based default |
| `completionDetector` | `CompletionDetector` | tool-call-absence default |
| `repairStrategy` | `RepairStrategy` | empty-response-only default |
| `turnGate` | `TurnGate` | `new SerialTurnGate()` |
| `messageQueue` | `MessageQueue` | `new DefaultMessageQueue(messageQueueStore)` |
| `messageQueueStore` | `MessageQueueStore` | `new SqliteMessageQueueStore(dataSource)` |
| `auditWriter` | `AuditWriter` | `AuditWriter.noOp()` |
| `promptContributors` | `List<PromptContributor>` | empty ArrayList |
| `modelCapabilityProvider` | `ModelCapabilityProvider` | null (build() validates if models use streaming) |
| `thinkingExtractor` | `ThinkingExtractor` | `new CompositeThinkingExtractor()` |
| `turnLifecycleListener` | `TurnLifecycleListener` | no-op |
| `objectMapper` | `ObjectMapper` | `new ObjectMapper()` |
| `drainTimeoutSeconds` | `long` | 30 |
| `dataSource` | `DataSource` | auto-created SQLite DataSource (in-memory) |

#### C.2 Factory method

```java
public static TurnLoopBuilder create(@NonNull ApplicationContext context) {
    Objects.requireNonNull(context, "ApplicationContext must not be null");
    return new TurnLoopBuilder(context);
}
```

#### C.3 Builder setter methods

One setter per configurable item. Every setter returns `this` for chaining. Every parameter is `@NonNull`. Names match the handoff Appendix A TurnLoopBuilder signature exactly:

```java
public TurnLoopBuilder model(@NonNull String alias, @NonNull ModelConfig config)
public TurnLoopBuilder dataRoot(@NonNull Path dataRoot)
public TurnLoopBuilder defaultSystemPrompt(@NonNull String prompt)
public TurnLoopBuilder toolRegistry(@NonNull ToolRegistry registry)
public TurnLoopBuilder toolAccessPolicy(@NonNull ToolAccessPolicy policy)
public TurnLoopBuilder toolResultSummarizer(@NonNull ToolResultSummarizer summarizer)
public TurnLoopBuilder interactionHandler(@NonNull InteractionHandler handler)
public TurnLoopBuilder interactionStore(@NonNull InteractionStore store)
public TurnLoopBuilder conversationMemory(@NonNull ConversationMemory memory)
public TurnLoopBuilder sessionStore(@NonNull SessionStore store)
public TurnLoopBuilder tokenEstimator(@NonNull TokenEstimator estimator)
public TurnLoopBuilder compactionStrategy(@NonNull CompactionStrategy strategy)
public TurnLoopBuilder completionDetector(@NonNull CompletionDetector detector)
public TurnLoopBuilder repairStrategy(@NonNull RepairStrategy strategy)
public TurnLoopBuilder turnGate(@NonNull TurnGate gate)
public TurnLoopBuilder messageQueue(@NonNull MessageQueue queue)
public TurnLoopBuilder auditWriter(@NonNull AuditWriter writer)
public TurnLoopBuilder promptContributor(@NonNull PromptContributor contributor)
public TurnLoopBuilder modelCapabilityProvider(@NonNull ModelCapabilityProvider provider)
public TurnLoopBuilder thinkingExtractor(@NonNull ThinkingExtractor extractor)
public TurnLoopBuilder turnLifecycleListener(@NonNull TurnLifecycleListener listener)
public TurnLoopBuilder objectMapper(@NonNull ObjectMapper objectMapper)
```

Note: `promptContributor()` adds to the list (cumulative), doesn't replace. All other setters replace.

#### C.4 Auto-discovery from ApplicationContext

Before `build()`, scan the `ApplicationContext` for beans implementing framework SPIs. Use `context.getBeanProvider()` with ordered stream to auto-populate:

- `PromptContributor` beans: auto-discover all and add to list (sorted by `@Order` / `Ordered`)
- `TurnLifecycleListener` (if not explicitly set): use highest-priority bean
- `TurnLoopMetrics` (if not explicitly set): use highest-priority bean

Auto-discovery runs only if the corresponding setter was never explicitly called. Track which setters were called with a `Set<String> explicitlySet` field.

#### C.5 build() validation and construction

`build()` performs these validations in order before constructing `DefaultTurnLoop`:

1. **At least one model configured**: `modelConfigs` non-empty. If empty, throw `ConfigurationException("At least one model must be configured")`.
2. **Default model references existing provider**: Determine default model alias (first registered, or the one with key `"default"`). It must exist in `modelConfigs`. If not, throw `ConfigurationException("Default model '" + alias + "' not found in providers")`.
3. **ModelCapabilityProvider exists if streaming requested**: If any model config has streaming enabled but no `modelCapabilityProvider` set, log a warning and default to an optimistic provider (assume all models support STREAMING and TOOL_CALLING).
4. **InteractionHandler exists if ToolRegistry contains ask_user**: If any tool is named `"ask_user"` (framework-internal), `interactionHandler` must be non-null. If null, throw `ConfigurationException("interactionHandler is required when ask_user tool is registered")`.
5. **dataRoot exists or can be created**: Call `Files.createDirectories(dataRoot)`. If fails, throw `ConfigurationException("Cannot create data root: " + dataRoot, e)`.
6. **Run MigrationRunner**: Apply schema migrations against the DataSource.

Then construct `DefaultTurnEngine` via its package-private builder:

```java
DefaultTurnEngine engine = DefaultTurnEngine.builder()
    .modelRouter(buildModelRouter())
    .toolRegistry(toolRegistry)
    .toolAccessPolicy(toolAccessPolicy)
    .toolResultSummarizer(toolResultSummarizer)
    .interactionHandler(interactionHandler)
    .conversationMemory(conversationMemory)
    .tokenEstimator(tokenEstimator)
    .compactionStrategy(compactionStrategy)
    .completionDetector(completionDetector)
    .repairStrategy(repairStrategy)
    .thinkingExtractor(thinkingExtractor)
    .promptContributors(promptContributors)
    .defaultSystemPrompt(defaultSystemPrompt)
    .objectMapper(objectMapper)
    .build();
```

Construct `DefaultTurnLoop`:

```java
return new DefaultTurnLoop(
    engine, turnGate, messageQueue, interactionStore, sessionStore,
    conversationMemory, activeTurnRegistry, executor,
    lifecycleListener, metrics, objectMapper,
    defaultSystemPrompt, drainTimeoutSeconds
);
```

`build()` always returns a new `TurnLoop` instance. Calling `build()` multiple times creates independent instances sharing the same DataSource.

#### C.6 Verify compilation

Run `mvn compile -pl turnloop-core`. Fix all compilation errors. Grep for `org.springframework.ai` in `turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoop.java`, `DefaultTurnLoop.java`, and `TurnLoopBuilder.java` — must return zero results.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/specifications/api.md`
- Handoff Appendix A (`.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`) for exact `TurnLoop` and `TurnLoopBuilder` signatures, exception hierarchy, and close() lifecycle specification
- Phase 09 output for `DefaultTurnEngine` constructor/builder shape, `SerialTurnGate` API, `ActiveTurnRegistry` API, `DefaultToolRegistry` API

## Technical Guidance

- **close() must be idempotent.** Use `AtomicBoolean.compareAndSet(false, true)`. All steps must be individually try-caught; no step may prevent subsequent steps from executing.
- **Exception wrapping at every public method boundary.** No internal exception type (including Spring AI exceptions from the ModelRouter mapping layer) may propagate through a `TurnLoop` method. The exception wrapping table in B.10 is the canonical mapping.
- **Sub-interfaces are lightweight views.** `tools()`, `memory()`, `sessions()` return the same instance each call (not new objects). They are not thread-safe for mutation but are safe for concurrent read.
- **TurnLoopBuilder auto-discovery respects explicit settings.** If `toolRegistry(toolRegistry)` was called, do NOT auto-discover a `ToolRegistry` bean from ApplicationContext. Track via `explicitlySet` set.
- **ApplicationContext parameter is required.** `TurnLoopBuilder.create(null)` throws `NullPointerException` via `Objects.requireNonNull`.
- **Gate state checks.** `send()` and `stream()` attempt gate acquisition first — gate rejection is a fast-fail before any turn resources are allocated. `answer()` checks gate is AWAITING_ANSWER before loading from InteractionStore.
- **answer() and stale contexts.** If InteractionStore.load() returns null (session cleared, expired, or never suspended), throw `TurnLoopException` — do not silently start a new turn.
- **ack/release on stale claims.** If the claim token is already acked/released/stale, log a warning at DEBUG or WARN level and return normally. These are idempotent-tolerant operations. Only storage failures throw.
- **DefaultTurnLoop is package-private.** The only public construction path is `TurnLoopBuilder.build()`. The builder and the interface are the public API surface.

## Validation

- `TurnLoop.java` exists with all 14 facade methods and 3 sub-interfaces matching Appendix A signatures exactly
- `DefaultTurnLoop.java` exists in `io.mindspice.turnloop` (package-private final), implements `TurnLoop`
- `TurnLoopBuilder.java` exists in `io.mindspice.turnloop` (public final), has `create(ApplicationContext)` factory and builder methods for every SPI listed in Appendix A
- `mvn compile -pl turnloop-core` passes
- `grep -r 'org.springframework.ai' turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoop.java turnloop-core/src/main/java/io/mindspice/turnloop/DefaultTurnLoop.java turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoopBuilder.java` returns zero results

## Exit Criteria

- `TurnLoop` interface complete with all facade methods and sub-interfaces per handoff Appendix A
- `DefaultTurnLoop` compiles and delegates to engine, gate, queue, session store, conversation memory
- `TurnLoopBuilder` wires all SPIs with production-quality defaults; `build()` validates and constructs a complete instance
- `turnloop-core` compiles with zero Spring AI imports in the facade package
- Close() lifecycle implements the full 7-step ordered drain sequence
