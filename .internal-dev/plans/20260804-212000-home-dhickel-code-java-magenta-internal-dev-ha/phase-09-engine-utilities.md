# Phase 09 — Engine, Utilities, and Code Migration

## Context

The framework now has all its types, SPIs, internal interfaces, and persistence (Phases 06–08). This phase builds the `DefaultTurnEngine` — the 6-state turn-loop state machine extracted intact from `ChatService.toolChat()` — plus the engine companions `DefaultPromptAssembler` and `ContextManager`, concrete SPI implementations not created in Phase 08 (`DefaultToolRegistry`, `SerialTurnGate`, `CompositeThinkingExtractor`, `InMemoryInteractionStore`), utility classes (`MarkdownRenderer`, `TurnEventRenderer`), and example tools. Surviving Magenta code is moved into framework packages and renamed to framework conventions. The `turnloop-core` POM is updated with optional dependencies.

This phase is split into four sequential subphases (A–D). Each lettered subphase maps to one sequential DeepSeek implementation agent session. All subphases complete in order before the phase-level validation and exit criteria apply. No intermediate independent validation gates exist between subphases.

## Goal

Create DefaultTurnEngine with 6-state cycle (PREPARE to INVOKE_MODEL to EVALUATE to EXECUTE_TOOLS to REPAIR to FINALIZE to DONE) extracted intact from ChatService.toolChat(); create DefaultPromptAssembler with 3-layer priority structure; create ContextManager for windowed queries and token-budget management; implement DefaultToolRegistry with two-stage registration and name-collision detection; implement SerialTurnGate with ReentrantLock state machine (IDLE to EXECUTING to AWAITING_ANSWER to EXECUTING to IDLE) timeout and drain; implement CompositeThinkingExtractor (Ollama metadata to DeepSeek reasoningContent to think tag fallback); implement InMemoryInteractionStore; implement MarkdownRenderer with optional dependencies and graceful degradation; implement TurnEventRenderer.json() and TurnEventRenderer.sse(); create example tools (EchoTool DateTimeTool NOT auto-registered); add optional dependencies to turnloop-core POM; move surviving Magenta code into framework packages; delete MagentaWorkExecutor; delete hard-coded ToolTranscriptService switch; rename surviving classes to framework names where applicable; verify turnloop-core compiles independently

## In Scope

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop/engine/DefaultTurnEngine.java, turnloop-core/src/main/java/io/mindspice/turnloop/engine/DefaultPromptAssembler.java, turnloop-core/src/main/java/io/mindspice/turnloop/engine/ContextManager.java, turnloop-core/src/main/java/io/mindspice/turnloop/DefaultToolRegistry.java, turnloop-core/src/main/java/io/mindspice/turnloop/SerialTurnGate.java, turnloop-core/src/main/java/io/mindspice/turnloop/CompositeThinkingExtractor.java, turnloop-core/src/main/java/io/mindspice/turnloop/persistence/InMemoryInteractionStore.java, turnloop-core/src/main/java/io/mindspice/turnloop/MarkdownRenderer.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnEventRenderer.java, turnloop-core/src/main/java/io/mindspice/turnloop/example/EchoTool.java, turnloop-core/src/main/java/io/mindspice/turnloop/example/DateTimeTool.java, turnloop-core/pom.xml, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/repository

- `DefaultTurnEngine` in `io.mindspice.turnloop.engine` (package-private class): 6-state cycle (PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) extracted intact from `ChatService.toolChat()`; `Flux<TurnEvent> stream(TurnContext)` for SSE streaming; `TurnResult execute(TurnContext)` for synchronous calls; interrupt polling via `ActiveTurnRegistry`; `ask_user` suspension/resume via `InteractionHandler` + `InteractionStore`; tool execution via `ToolRegistry.resolveForTurn()`; `ToolLoopGuard` checking before and after each round; `CompletionDetector.isComplete()` after assistant response without tool calls; empty/incomplete responses routed through `RepairStrategy.repair()`; `ThinkingExtractor.extract()` applied to every assistant message; `CompactionStrategy.compact()` triggered asynchronously after turn completion via `CompletableFuture.runAsync()`; accepts all dependencies via a package-private builder grouping them by concern (toolExecution, contextAssembly, responseRepair)
- `DefaultPromptAssembler` in `io.mindspice.turnloop.engine` (package-private): 3-layer priority structure: (1) system prompt from config, (2) registered `PromptContributor` implementations sorted by `@Order`/`Ordered`, (3) turn instructions; called per-turn with `TurnContext`
- `ContextManager` in `io.mindspice.turnloop.engine` (package-private): windowed message queries using `ConversationMemory.getMessages()` + `TokenEstimator`; token-budget management with configurable `bufferPercent`; returns `List<TurnMessage>` plus `ContextUsage` snapshot; handles trim vs. compact decision; integrates with `CompactionStrategy` for summary retrieval
- `MarkdownRenderer`: public utility class with `commonmark` and `owasp-html-sanitizer` as optional Maven dependencies; `render(String markdown)` → `String`; graceful degradation when deps absent: log WARN once at class-load time, return input unmodified
- `TurnEventRenderer`: public utility with `json(ObjectMapper)` returning `Function<TurnEvent, String>` (JSON Lines format) and `sse()` returning `Function<TurnEvent, String>` (SSE `data: {json}\n\n` format)
- `DefaultToolRegistry`: implements `ToolRegistry` with two-stage registration (public map + framework-internal map); `registerFrameworkTool(ToolCallback)` for built-in tools hidden from `definitions()`; `resolveForTurn(TurnContext)` merging public + framework tools after `ToolAccessPolicy` filtering; name-collision detection: registering a public tool whose name matches a framework-internal tool throws `ToolException` with message `"Tool name '" + name + "' conflicts with a framework-internal tool"`; `registerToolBean(Object)` scans `@Tool` annotations via reflection
- `SerialTurnGate`: implements `TurnGate` with `ReentrantLock`; state machine IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE; `acquire(sessionId, mode)` returns `AcquireResult.Acquired`, `Rejected`, or `Queued`; configurable turn timeout (default 10 min) — expired EXECUTING turns force-released, emitting `TurnEvent.Failed` before release; `drain(long timeoutMs)` cancels AWAITING_ANSWER turns with `TurnEvent.Interrupt("shutdown")`, waits for EXECUTING turns, then force-releases; `state(sessionId)` returns current `GateState`
- `CompositeThinkingExtractor`: implements `ThinkingExtractor`; extracts thinking in priority order: (1) Ollama `thinking` metadata field, (2) DeepSeek `reasoningContent`, (3) `<think>...</think>` XML tag fallback via regex; returns `ThinkingResult` with separated `visibleText` and `thinkingText`
- `InMemoryInteractionStore`: implements `InteractionStore` with `ConcurrentHashMap<String, QuestionPendingContext>`; `save`, `load`, `delete` operations
- Example tools in `io.mindspice.turnloop.example`: `EchoTool` (`@Tool(name = "echo", description = "Echoes the input message back to the caller")`, single `String message` parameter); `DateTimeTool` (`@Tool(name = "get_current_time", description = "Returns the current date and time")`, optional `String timezone` parameter defaulting to UTC); neither auto-registered; both use Spring AI `@Tool` annotation
- POM updates: add `commonmark` and `owasp-html-sanitizer` as `<optional>true</optional>` dependencies to `turnloop-core/pom.xml`
- **Magenta code migration**: move/merge surviving Magenta classes into framework packages; delete `MagentaWorkExecutor`; delete the hard-coded `ToolTranscriptService` switch; simplify `ChatService.java`; delete or adapt `ai/chat/repository/` classes; rename surviving classes to framework conventions; verify `mvn compile -pl turnloop-core` passes independently; verify Magenta `mvn compile` passes
- Virtual-thread safety audit of target Spring AI version's HTTP client; document the finding; the engine commits with `ExecutorStrategy` enum (`VIRTUAL`, `FIXED`) controlled by configuration key `turnloop.executor.strategy`, defaulting to `VIRTUAL`; the audit finding may change the default or add documentation but doesn't block compilation

## Out of Scope

- `TurnLoop` facade (Phase 10)
- Auto-configuration (Phase 11)
- Tests (Phase 12)
- Documentation (Phase 13)
- Creating the `TurnLoopBuilder` (Phase 10)
- Implementing `TurnLoopMetrics` Micrometer default or `TurnLoopHealthIndicator` (Phase 11)
- `StreamLifecycle` SSE adapter (Phase 11)
- `SpringAiConversationMemoryAdapter` (Phase 08 — already created)
- `ToolArgumentCoercionModule` (Phase 08 — already created)
- `ToolLoopGuard` (Phase 08 — already created; consumed by engine in this phase)
- `MigrationRunner` / `Migration` interface / `schema.sql` (Phase 08 — already created)
- `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter` (Phase 08 — already created; modified by code migration in this phase)

## Dependencies

phase-08-internal-persistence.md

## Constraints

- `DefaultTurnEngine` must faithfully replicate the 6-state cycle from `ChatService.toolChat()` — every state transition, error-handling branch, and concurrency contract must be preserved
- Engine must be package-private — not visible to external consumers (only the `TurnLoop` facade exposes turn-execution methods in Phase 10)
- `MarkdownRenderer` must gracefully degrade without optional dependencies: log WARN once at class-load time if `commonmark` is absent, return input unmodified
- `TurnEventRenderer.json()` accepts an `ObjectMapper` parameter and returns a `Function<TurnEvent, String>`
- Example tools must NOT be auto-registered — embedding apps opt in explicitly
- Surviving Magenta code must compile after migration; both `turnloop-core` and Magenta `mvn compile` must pass
- No `ThreadLocal` in any framework source
- `TurnEvent.Chunk.thinking` boolean flag set by `ThinkingExtractor` via the engine
- `answer()` restores from `InteractionStore`, resumes turn via `TurnGate.transitionToAwaitingAnswer()` back to execution

## Implementation Steps

### Subphase 9-A: Engine Package (one sequential agent session)

Create the `engine/` package in `turnloop-core` with three tightly coupled classes. The agent must read `ChatService.toolChat()` fully (lines ~1572–2114 in the current Magenta source) plus the `ToolChatState` inner class (~lines 2861–2940) and all handler methods to understand every transition path before writing.

1. **Read prerequisite sources**: Read `ChatService.java` fully — focus on `toolChat()` dispatcher, `handleInvokeModel`, `handleEvaluate`, `handleExecuteTools`, `handleRepair`, `handleFinalize`, and the `ToolChatState` inner class. Read all SPI interfaces from Phase 07 (`TurnGate`, `ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, `InteractionHandler`, `InteractionStore`, `CompletionDetector`, `RepairStrategy`, `ThinkingExtractor`, `CompactionStrategy`, `ConversationMemory`, `TokenEstimator`, `PromptContributor`). Read all internal interfaces from Phase 08 (`ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`). Read `ToolLoopGuard` (concrete final class from Phase 08). Read `TurnContext`, `TurnRequest`, `TurnResult`, `TurnEvent` sealed hierarchy, `TurnPhase` enum, `ContextUsage`, `TurnDiagnostic`, `TurnMessage`, `ThinkingResult`, `AnswerResult` from Phases 06–07.

2. **Create `DefaultTurnEngine`**: Package-private final class in `io.mindspice.turnloop.engine`. Package-private builder accepting all dependencies grouped by concern:
   - `toolExecution`: `ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, `ToolLoopGuard`
   - `contextAssembly`: `ConversationMemory`, `TokenEstimator`, `CompactionStrategy`, `ContextManager`, `DefaultPromptAssembler`
   - `responseRepair`: `CompletionDetector`, `RepairStrategy`
   - `interaction`: `InteractionHandler`, `InteractionStore`
   - `infrastructure`: `ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`, `ThinkingExtractor`
   - `config`: `int maxToolRounds`, `int maxEmptyResponseRetries`, `Duration turnTimeout`

   Implement two public methods:
   - `Flux<TurnEvent> stream(TurnContext ctx)` — streaming execution
   - `TurnResult execute(TurnContext ctx)` — synchronous execution (collects Flux, blocks)

   Internal 6-state cycle (faithful to `ChatService.toolChat()`):
   ```
   PREPARE → (build prompt, resolve tools, register active turn, validate capabilities)
   INVOKE_MODEL → (call ModelRouter, stream/flux chunks through ThinkingExtractor, detect tool calls)
   EVALUATE → (check CompletionDetector → FINALIZE; check hasToolCalls → EXECUTE_TOOLS; check empty/error → REPAIR)
   EXECUTE_TOOLS → (resolve tools, check ToolLoopGuard, execute each tool via ToolRegistry callback, summarize via ToolResultSummarizer, emit ToolActivity events, check interrupt, check ask_user trigger → suspend, loop back to EVALUATE)
   REPAIR → (call RepairStrategy.repair(), inject control messages, loop back to INVOKE_MODEL or EVALUATE; enforce maxEmptyResponseRetries)
   FINALIZE → (emit Completed/Failed, trigger async CompactionStrategy, persist messages, cleanup registry)
   DONE → (return TurnResult)
   ```

   Key behaviors:
   - **Interrupt polling**: After each phase check `activeTurnRegistry.get(turnId).pollInterrupt()`; if present, emit `TurnEvent.Interrupt`, set phase to FINALIZE, skip remaining work
   - **ask_user suspension**: When tool name equals `"ask_user"`, call `interactionHandler.onQuestionsAsked()`, save context to `interactionStore.save()`, call `turnGate.transitionToAwaitingAnswer(sessionId)`, emit `TurnEvent.QuestionPending`, suspend the Flux. Resume when `TurnLoop.answer()` restores context and re-enters the engine (this path is wired in Phase 10; the engine emits the suspend event and stores state)
   - **ToolLoopGuard**: Check `toolLoopGuard.recordToolCalls()` before tool execution; `toolLoopGuard.recordToolResponses()` after. On `LoopExceeded`: catch, route to REPAIR with `RepairReason.TOOL_LOOP_EXCEEDED`, emit `SystemNotice(TOOL_LOOP_WARNING)`
   - **Thinking extraction**: After every assistant message: `thinkingExtractor.extract()` → set `TurnEvent.Chunk.thinking = true` for thinking text chunks
   - **Context monitoring**: After model response, update `ContextUsage` via `contextManager.computeUsage()`, emit on each event's `contextUsage` field
   - **Async compaction**: After FINALIZE, `CompletableFuture.runAsync(() -> compactionStrategy.compact(compactionContext))`; store result as system message with `metadata.type = "compaction_summary"` via `conversationMemory.addMessages()`
   - **Max tool rounds**: Increment counter in EXECUTE_TOOLS; if exceeded, route to FINALIZE with diagnostic
   - **Model capability check**: In PREPARE, if `ctx.request().stream()` and model lacks `STREAMING`, throw `TurnLoopException` before invoking model

3. **Create `DefaultPromptAssembler`**: Package-private class in `engine/`. Accepts `String defaultSystemPrompt` and `List<PromptContributor>` (sorted by `@Order`/`Ordered`). Single method `String assemble(TurnContext ctx)` returning the assembled prompt. 3-layer structure: (1) system prompt from config, (2) each contributor's `contribute(ctx)` appended with newline separator, (3) turn instructions derived from context metadata.

4. **Create `ContextManager`**: Package-private class in `engine/`. Accepts `ConversationMemory`, `TokenEstimator`, `CompactionStrategy`, `int bufferPercent`. Key methods:
   - `WindowedMessages window(TurnContext ctx)` — fetches messages from `ConversationMemory.getMessages()`, walks oldest-to-newest accumulating `TokenEstimator.estimateTokens()`, applies `bufferPercent` to model's context window, returns messages within budget plus a `ContextUsage` snapshot
   - `ContextUsage computeUsage(String sessionId, String modelAlias)` — computes current token usage for a session/model pair
   - `boolean needsCompaction(ContextUsage usage, int contextWindow)` — returns true when usage exceeds threshold

### Subphase 9-B: Concrete SPI Implementations (one sequential agent session)

Create four independent concrete SPI implementations. Each depends only on SPI interfaces from Phase 07.

1. **Read prerequisite sources**: Read `ToolRegistry`, `TurnGate`, `ThinkingExtractor`, `InteractionStore` SPI interfaces from Phase 07. Read any Magenta counterparts that provide implementation logic: `ChatToolRegistry.java` (for two-stage map pattern), `InteractionQuestionTools.java` (for `ask_user` framework tool).

2. **Create `DefaultToolRegistry`**: Package `io.mindspice.turnloop`. Implements `ToolRegistry`. Two `ConcurrentHashMap` fields: `Map<String, ToolCallback> publicTools`, `Map<String, ToolCallback> frameworkTools`. Auto-registers `ask_user` as framework-internal tool in constructor. `register(ToolCallback)` checks frameworkTools map for name collision → throws `ToolException` with message `"Tool name '" + name + "' conflicts with a framework-internal tool"`. `registerFrameworkTool(ToolCallback)` inserts into frameworkTools. `definitions()` returns definitions from publicTools only. `resolveForTurn(TurnContext)` merges publicTools + frameworkTools into one list, passes through `ToolAccessPolicy.filterTools()` (policy injected into registry or passed at resolve time — follow the handoff: `ToolAccessPolicy` is a standalone SPI, engine calls it separately; `resolveForTurn` returns the merged list, engine applies policy). `registerToolBean(Object)` scans for `@Tool` annotated methods via reflection, wraps each as `ToolCallback`. `unregister(String)` removes from publicTools (framework tools cannot be unregistered).

3. **Create `SerialTurnGate`**: Package `io.mindspice.turnloop`. Implements `TurnGate`. Internal state per session tracked via `ConcurrentHashMap<String, GateState>`. `ReentrantLock` per session for atomic state transitions. Constructor accepts `Duration turnTimeout`. `acquire(sessionId, mode)`: if IDLE → transition to EXECUTING, return `Acquired` with lock token; if EXECUTING and mode is ANSWER → allow if AWAITING_ANSWER state; otherwise return `Rejected`. `transitionToAwaitingAnswer(sessionId)`: EXECUTING → AWAITING_ANSWER. `release(sessionId)`: any state → IDLE. `drain(long timeoutMs)`: iterate all non-IDLE sessions; for AWAITING_ANSWER, cancel with Interrupt and release immediately; for EXECUTING, wait up to timeoutMs then force-release. Turn timeout: use `ScheduledExecutorService` (single-thread, daemon) to monitor EXECUTING sessions; on expiry, emit Failed event via registered callback then force-release. Thread-safe: all state transitions inside `lock.lock()` / `lock.unlock()`.

4. **Create `CompositeThinkingExtractor`**: Package `io.mindspice.turnloop`. Implements `ThinkingExtractor`. Constructor can accept custom extractors; default has three strategies in priority order: (1) check `metadata.get("thinking")` for Ollama format, extract thinking text; (2) check `reasoningContent` field for DeepSeek; (3) regex `<think>(.*?)</think>` with `Pattern.DOTALL`. If any strategy finds thinking content: `visibleText` strips the thinking portion, `thinkingText` contains extracted content. If no strategy matches: `visibleText` = raw text, `thinkingText` = empty string. Compiled `Pattern` should be a static final field.

5. **Create `InMemoryInteractionStore`**: Package `io.mindspice.turnloop.persistence`. Implements `InteractionStore`. `ConcurrentHashMap<String, QuestionPendingContext>` backing store. `save` puts, `load` gets (returns null if absent), `delete` removes. Thread-safe by virtue of `ConcurrentHashMap`.

### Subphase 9-C: Utilities, Examples, POM (one sequential agent session)

Create two utility classes, two example tools, and update the POM.

1. **Read prerequisite sources**: Read `TurnEvent` sealed hierarchy (Phase 07) to understand all 8 subtypes and their fields. Read `ChatMarkdownRenderer.java` from Magenta for existing rendering logic.

2. **Create `MarkdownRenderer`**: Package `io.mindspice.turnloop`. Public final class. Constructor performs classpath detection:
   ```java
   private final boolean commonmarkAvailable;
   public MarkdownRenderer() {
       boolean available = false;
       try {
           Class.forName("org.commonmark.parser.Parser");
           available = true;
       } catch (ClassNotFoundException e) {
           logger.warn("commonmark not on classpath; MarkdownRenderer.render() will return input unmodified");
       }
       this.commonmarkAvailable = available;
   }
   ```
   `render(String markdown)`: if `!commonmarkAvailable`, return input unmodified. Otherwise parse with commonmark, render to HTML, sanitize with owasp-html-sanitizer (if available, otherwise skip sanitize), return rendered HTML.

3. **Create `TurnEventRenderer`**: Package `io.mindspice.turnloop`. Public final class with static methods:
   - `json(ObjectMapper mapper)` → `Function<TurnEvent, String>`: serializes each event to JSON Lines (`mapper.writeValueAsString(event) + "\n"`). Handles all 8 TurnEvent subtypes via Jackson polymorphism (ensure TurnEvent has `@JsonTypeInfo` and `@JsonSubTypes` on the sealed interface — add those annotations to `TurnEvent.java` in Phase 07 if not present).
   - `sse()` → `Function<TurnEvent, String>`: formats as SSE `"data: " + json + "\n\n"`. Uses a shared internal `ObjectMapper` instance (or accepts one via overload).

4. **Create example tools**: `EchoTool` and `DateTimeTool` in `io.mindspice.turnloop.example`. Both use Spring AI `@Tool` annotation. `EchoTool`: single method `@Tool(name = "echo", description = "Echoes the input message back to the caller") String echo(String message)`. `DateTimeTool`: single method `@Tool(name = "get_current_time", description = "Returns the current date and time") String getCurrentTime(@ToolParam("timezone") String timezone)` with timezone defaulting to "UTC" when null. NO auto-registration — no `@Component`, no `@Bean`, no static initializer registering them. They are plain classes that embedding apps instantiate and register explicitly.

5. **Update `turnloop-core/pom.xml`**: Add optional dependencies:
   ```xml
   <dependency>
       <groupId>org.commonmark</groupId>
       <artifactId>commonmark</artifactId>
       <version>0.21.0</version>
       <optional>true</optional>
   </dependency>
   <dependency>
       <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
       <artifactId>owasp-java-html-sanitizer</artifactId>
       <version>20240325.1</version>
       <optional>true</optional>
   </dependency>
   ```
   Do NOT add spring-boot-starter-web or any servlet API dependency. Do NOT add spring-ai dependencies (they should already be present from Phase 06 POM setup). Verify `mvn compile -pl turnloop-core` passes.

### Subphase 9-D: Magenta Code Migration (one sequential agent session)

Move/merge surviving Magenta code into framework packages. This subphase reads Magenta sources, adapts them into framework targets (some created in Phase 08, some in prior subphases of this phase), simplifies `ChatService.java`, and deletes dead code. Order matters: read all sources first, then execute merges, then clean up Magenta, then verify compilation.

**Step 1: Read all surviving Magenta sources to understand existing logic.**

Read every file in the migration list below before modifying any of them. This prevents merge conflicts where the same Magenta class supplies logic to multiple framework targets.

**Step 2: Execute per-class merges into framework targets.**

Each merge follows the pattern: extract the implementation logic from the Magenta class, adapt imports to framework types, insert into the framework target, then delete or simplify the Magenta source.

| Magenta Source | Framework Target | Merge Direction |
|---|---|---|
| `service/ChatModelRouter.java` | `internal/ModelRouter` implementation (Phase 08 interface) | Create `internal/DefaultModelRouter.java` in `turnloop-core/src/main/java/io/mindspice/turnloop/internal/` implementing the `ModelRouter` interface. Extract model-resolution logic, `ChatModel` construction, `ToolCallingChatOptions` creation, and streaming-capability detection from `ChatModelRouter`. Map Magenta `ModelConfig` → framework `ModelConfig`. The `DefaultModelRouter` is the sole place where `org.springframework.ai` types appear — it's internal, not public API. |
| `service/ContextManagementAdvisor.java` + `service/ContextUsageTracker.java` | `engine/ContextManager.java` (created in Subphase 9-A) | Extract windowed-message logic from `ContextManagementAdvisor` and token-counting/tracking logic from `ContextUsageTracker`. Merge into `ContextManager` methods `window()` and `computeUsage()`. Delete both Magenta sources after extraction. |
| `rendering/ChatMarkdownRenderer.java` | `MarkdownRenderer.java` (created in Subphase 9-C) | Extract commonmark parsing, HTML rendering, and sanitization logic from `ChatMarkdownRenderer`. Adapt to optional-dependency graceful degradation pattern from Subphase 9-C. Delete `ChatMarkdownRenderer.java` after extraction. |
| `tool/ChatToolRegistry.java` | `DefaultToolRegistry.java` (created in Subphase 9-B) | Extract two-stage registration pattern (`publicTools` + `frameworkTools` maps), `@Tool` scanning via reflection, and tool-name comparison logic. Merge into `DefaultToolRegistry`. Delete `ChatToolRegistry.java` after extraction. |
| `tool/ToolTranscriptService.java` | `ToolResultSummarizer` default implementation (SPI from Phase 07) | Create or update the default `ToolResultSummarizer` in `turnloop-core` (package `io.mindspice.turnloop`). Extract the summarization logic from `ToolTranscriptService` — the switch-based tool-type routing becomes a generic fallback: `"Returned N characters"`. Preserve truncation at `maxResultChars`. Delete `ToolTranscriptService.java` after extraction. Delete the hard-coded switch statement entirely; replace with configurable max-char truncation. |
| `repository/ChatMemoryRepository.java` | `SqliteConversationMemory.java` (Phase 08) | Extract SQLite query patterns, insertion-order retrieval, and session-scoped message storage. Merge into `SqliteConversationMemory` — enhance its existing CRUD with any missing query patterns (e.g., filtered retrieval, metadata column handling). Delete `ChatMemoryRepository.java` after extraction. |
| `repository/ChatSessionMetadataRepository.java` | `SqliteSessionStore.java` (Phase 08) | Extract session metadata CRUD, favorite/archive toggles, and `SessionFilter` query construction. Merge into `SqliteSessionStore`. Delete `ChatSessionMetadataRepository.java` after extraction. |
| `repository/AuditRepository.java` + `service/AuditService.java` | `SqliteAuditWriter.java` (Phase 08) | Extract audit-log insert patterns (fire-and-forget, must-not-throw). Merge into `SqliteAuditWriter` — ensure all five `record*` methods write to `turnloop_audit_log` table with correct `event_type` and JSON `payload_json`. Delete `AuditRepository.java` and `AuditService.java` after extraction. |
| `repository/ChatPendingMessageRepository.java` + `service/ChatPendingMessageService.java` | `SqliteMessageQueueStore.java` (Phase 08) | Extract claim/ack/release SQL patterns, stale-claim recovery query, and session-scoped deletion. Merge into `SqliteMessageQueueStore` — ensure `claimAtomic` uses the existing Magenta pattern (UPDATE with claim_token WHERE claim_token IS NULL). Delete `ChatPendingMessageRepository.java` and `ChatPendingMessageService.java` after extraction. |
| `service/ToolLoopGuard.java` | Already extracted as framework type in Phase 08. | Delete `service/ToolLoopGuard.java` from Magenta. The framework `ToolLoopGuard` in `io.mindspice.turnloop` replaces it. Verify no Magenta references remain. |
| `tool/ToolArgumentCoercionModule.java` | Already extracted as framework type in Phase 08 with `META-INF/services` registration. | Delete `tool/ToolArgumentCoercionModule.java` from Magenta. |
| `service/turn/` surviving types | Most already adapted to SPIs in Phase 03–04. | Delete Magenta copies of `PromptContextAssembler`, `TerminalTurnRepair`, `ToolAccessPolicy`, `TurnAuditWriter`, `TurnContext`, `TurnDiagnostic`, `TurnPhase` — these are superseded by framework SPIs and types from Phases 06–07. |
| `repository/RepositoryBackedChatMemory.java` | `SpringAiConversationMemoryAdapter` (Phase 08) | Extract Spring AI `ChatMemory` bridging logic. If `SpringAiConversationMemoryAdapter` already covers it, delete `RepositoryBackedChatMemory.java`. If there's missing adaptation logic, merge it. |

**Step 3: Delete dead Magenta code.**

- Delete `MagentaWorkExecutor` (any path — search for it and delete).
- Delete the hard-coded `ToolTranscriptService` switch (the entire class, already handled in merge above).
- Delete `service/ToolUseAbort.java` from Magenta (not needed after engine extraction; internal exceptions converted to `TurnLoopException` hierarchy at facade boundary in Phase 10).
- Delete `model/ChatStreamEvent.java` (already renamed to `TurnEvent` in Phase 02).

**Step 4: Simplify `ChatService.java`.**

After all merges, `ChatService.java` should be reduced. All turn-loop logic lives in `DefaultTurnEngine`. The surviving `ChatService` becomes either:
- A thin adapter that delegates to `DefaultTurnEngine` (if Magenta needs it before Phase 10 facade replaces it), OR
- Deleted entirely if no Magenta code references it after migration.

Remove all imports for deleted packages. Remove the `toolChat()` method and its handler methods. Remove the `ToolChatState` inner class. Remove all Magenta-specific collaborator fields that were merged into framework classes. If `ChatService` still has remaining methods (non-turn-loop chat operations), keep those and adapt them. If it has none, delete `ChatService.java` entirely.

**Step 5: Clean up `ai/chat/repository/`.**

After all repository logic merges into framework persistence classes, delete all repository files from `ai/chat/repository/` that have been fully merged. If any repository file still has unmerged responsibility, keep it but strip merged methods. The directory should be empty or contain only any remaining Magenta-specific persistence that wasn't merged.

**Step 6: Verify compilation.**

Run `mvn compile -pl turnloop-core`. Fix all compilation errors. The framework must compile independently with zero dependencies on Magenta source.

Run `mvn compile` from root. Magenta must compile with its remaining source depending on `turnloop-core`. Fix import errors, missing-type errors, and reference errors. If a Magenta reference cannot be resolved because the type was deleted without a framework replacement, escalate — do not silently comment out code.

After both compilation commands pass, run `mvn compile -pl turnloop-core` one final time to confirm no regressions.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md` — Appendix A (type definitions, SPI signatures, TurnLoop facade, ToolLoopGuard API, Migration system, ThinkingExtractor, ToolRegistry), Appendix B (DDL for reference), Appendix C (Phase 4 gate inventory)
- Read `ChatService.java` fully — the `toolChat()` dispatcher, all handler methods, and the `ToolChatState` inner class — before writing `DefaultTurnEngine`
- Read all SPI interfaces from Phase 07 and all internal interfaces from Phase 08 before writing engine and concrete implementations
- `AGENTS.md` (project root) — SimplyPages policy does not apply to this phase (no UI work)

## Technical Guidance

- **Engine builder pattern**: `DefaultTurnEngine` constructor is private. A package-private static inner `Builder` class accumulates dependencies grouped by concern. `Builder.build()` validates required dependencies (throws `ConfigurationException` if `ModelRouter`, `ToolRegistry`, or `ConversationMemory` are null) and returns the engine instance.
- **Flux creation**: `Flux.create(sink -> { ... })` for streaming. The sink emits `TurnEvent` subtypes. On interrupt: `sink.next(new TurnEvent.Interrupt(...))` then `sink.complete()`. On error: `sink.error(new TurnFailedException(...))`. The sink must be completed from FINALIZE handler.
- **`ask_user` detection**: After tool execution, if any tool name equals `"ask_user"` (case-sensitive), extract the question text from the tool arguments, call `interactionHandler.onQuestionsAsked(context)`, save question context to `interactionStore.save()`, call `turnGate.transitionToAwaitingAnswer()`, emit `QuestionPending` event, and complete the sink. The engine does NOT block waiting for an answer — the resume path is wired in Phase 10 via `TurnLoop.answer()` which re-enters the engine with the user's answer appended to conversation.
- **`CompactionStrategy` async trigger**: `CompletableFuture.runAsync(() -> { CompactionResult result = compactionStrategy.compact(ctx); conversationMemory.addMessages(sessionId, List.of(TurnMessage.system(result.summary()).withMetadata("type", "compaction_summary"))); })`. Use the `TurnExecutor` for this, not raw `CompletableFuture` — ensures controlled thread usage.
- **`@Tool` annotation scanning**: `DefaultToolRegistry.registerToolBean(Object)` uses `Method[] methods = bean.getClass().getMethods()` and checks `method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)`. Each annotated method becomes a `ToolCallback` with `ToolDefinition` extracted from annotation attributes. This is a Spring AI dependency at the internal implementation level, not the public API.
- **`SerialTurnGate` timeout**: Use a single `ScheduledExecutorService` (daemon thread) that periodically (every 30s) scans EXECUTING sessions. For each expired session: call a registered `Consumer<String>` callback (set by the engine) that emits the Failed event, then release the gate. The callback is set during gate construction or via a setter.
- **`TurnEventRenderer.sse()` internal ObjectMapper**: The `sse()` method creates or accepts an ObjectMapper configured with Jackson polymorphism for the sealed `TurnEvent` hierarchy. If `TurnEvent` doesn't have `@JsonTypeInfo` / `@JsonSubTypes`, add them now (modify `TurnEvent.java` from Phase 07).
- **Migration merge safety**: When merging Magenta repository logic into Phase 08 framework persistence classes, preserve the Phase 08 DDL and schema assumptions. Do not reintroduce Magenta-specific columns or migration patterns. The framework owns `schema.sql`; the merge only improves query implementations.
- **`ChatService` simplification**: After all merges, check if `ChatService` is referenced by any remaining Magenta code. If not referenced, delete it. If referenced only for non-turn-loop operations (e.g., session listing, title updates), keep those methods but delete `toolChat()` and all handler methods. If Magenta's surviving code includes `ChatService.chat()` / `ChatService.stream()` callers, replace those call sites with direct `DefaultTurnEngine` usage (or leave for Phase 10 to wire via `TurnLoop` facade).
- **ExecutorStrategy enum**: Create `io.mindspice.turnloop.ExecutorStrategy` with `VIRTUAL`, `FIXED`. The engine builder accepts this enum. `VIRTUAL` uses `Executors.newVirtualThreadPerTaskExecutor()`. `FIXED` uses `Executors.newFixedThreadPool(config.turnThreads())`. Default to `VIRTUAL`.

## Validation

All validation applies after all four subphases (A–D) complete. No intermediate independent validation gates.

- `DefaultTurnEngine.java` exists in `engine/` package with complete 6-state cycle implementation
- `DefaultPromptAssembler.java` exists in `engine/` package with 3-layer priority structure
- `ContextManager.java` exists in `engine/` package with windowed query and token-budget methods
- `DefaultToolRegistry.java` exists with two-stage registration, `registerFrameworkTool()`, `resolveForTurn()`, and name-collision detection
- `SerialTurnGate.java` exists with `ReentrantLock` state machine, `acquire()`/`release()`/`transitionToAwaitingAnswer()`/`drain()`, and turn timeout via `ScheduledExecutorService`
- `CompositeThinkingExtractor.java` exists with three strategies: Ollama metadata, DeepSeek reasoningContent, `<think>` tag fallback
- `InMemoryInteractionStore.java` exists with `ConcurrentHashMap`-backed `save`/`load`/`delete`
- `MarkdownRenderer.java` exists with optional dependency graceful degradation (WARN log once, return unmodified)
- `TurnEventRenderer.java` exists with `json(ObjectMapper)` and `sse()` static methods
- `EchoTool.java` and `DateTimeTool.java` exist in `example/` package; neither has auto-registration
- `turnloop-core/pom.xml` contains `commonmark` and `owasp-html-sanitizer` as `<optional>true</optional>` dependencies
- All surviving Magenta sources merged into framework targets (verify: the Magenta classes listed in the merge table exist only as framework equivalents or are deleted)
- `MagentaWorkExecutor` does not exist in Magenta source tree
- `ToolTranscriptService.java` does not exist in Magenta source tree; the hard-coded switch is eliminated
- `ChatService.java` no longer contains `toolChat()` method or `ToolChatState` inner class; remaining code compiles
- `ai/chat/repository/` directory is empty or contains only files with unmerged Magenta-specific responsibility
- `mvn compile -pl turnloop-core` passes with zero errors
- Magenta `mvn compile` (from root) passes with zero errors
- No `ThreadLocal` usage in any new framework source
- `@Tool` annotation import (Spring AI) exists only in `DefaultToolRegistry` and example tools, not in public API types

## Exit Criteria

- `DefaultTurnEngine` with full 6-state cycle created in `engine/` package
- `DefaultPromptAssembler` with 3-layer structure and `ContextManager` with token-budget management created in `engine/` package
- All four concrete SPI implementations created: `DefaultToolRegistry`, `SerialTurnGate`, `CompositeThinkingExtractor`, `InMemoryInteractionStore`
- `MarkdownRenderer` and `TurnEventRenderer` utilities created
- Example tools created in `example/` package without auto-registration
- POM updated with optional Markdown dependencies
- All surviving Magenta code merged into framework targets; dead code deleted
- `ChatService.java` simplified (no `toolChat()`, no `ToolChatState`)
- `mvn compile -pl turnloop-core` passes independently
- Magenta `mvn compile` passes
