# Handoff: Extract Magenta Chat Turn-Loop into Reusable Framework `turnloop-core`

## Context

Magenta is a Spring Boot and Spring AI operational assistant. Its `ChatService.toolChat()` contains a production-hardened turn-loop state machine — the 6-state cycle (PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) that orchestrates tool-calling chat with model interaction, tool execution, response repair, context management, and streaming event emission. This turn loop is entangled with ~47 web/UI files, ~150 orchestration files, ~30 skill files, and dozens of app-specific tool, plan, task, project, and system packages. The current design forces every consumer to pull in the full Magenta application.

A coordinated, multi-agent brainstorming effort across four domains (architecture boundaries, internal abstractions, risk/constraint validation, and phased migration planning) plus cross-reviews and a red-team audit produced a unified direction. The consensus: extract the turn loop into a standalone, production-quality, embeddable framework under `io.mindspice.turnloop` with artifact `turnloop-core` and companion `turnloop-autoconfigure`. Twenty-one foundation decisions are settled. Twenty-six red-team findings must be addressed in the design before implementation begins.

**This corrected handoff includes complete type definitions, SPI signatures, database DDL, the TurnEvent hierarchy, and all previously-unspecified contracts as appendices.** The handoff is now implementation-ready: an implementer can build every phase from the specifications herein without inventing contracts.

## Objective

Produce a reusable, embeddable, production-quality Java framework — `turnloop-core` / `turnloop-autoconfigure` under `io.mindspice.turnloop` — that owns the AI chat turn-loop lifecycle. The framework ships with sensible defaults (in-memory SQLite, Ollama at localhost, JTokkit token estimation, summary-based compaction), exposes ~15 pluggable SPIs, has zero dependency on servlet APIs or Spring MVC, and works out of the box with `TurnLoop.builder().build()`. Embedding apps (including a re-integrated Magenta) implement app-specific behavior through the SPI surface without the framework knowing about plan/task/execute modes, orchestration, skills, or agent identity.

## Targets

### Production Artifacts
- **`turnloop-core`** (group `io.mindspice`, artifact `turnloop-core`): the framework JAR. Contains the turn engine, all SPIs, SQLite defaults, SSE transport utilities, `MarkdownRenderer` utility, example tools, and internal wiring. Zero dependency on `spring-boot-starter-web`, `SseEmitter`, or any servlet API — enforced by the POM with Maven Enforcer plugin.
- **`turnloop-autoconfigure`** (group `io.mindspice`, artifact `turnloop-autoconfigure`): Spring Boot auto-configuration. Contains `TurnLoopAutoConfiguration`, `TurnLoopProperties`, `@ConfigurationProperties` binding, `DataSource` auto-provisioning for in-memory SQLite, `TurnLoopHealthIndicator`, `TurnLoopMetrics` (Micrometer), and the `StreamLifecycle` SSE adapter. Also serves as the `spring-boot-starter-turnloop` meta-starter that pulls in `turnloop-core` transitively.
- **Magenta re-integration**: Magenta becomes an embedding app depending on `turnloop-core`, implementing app-specific SPIs (mode-gated `ToolAccessPolicy`, plan-detecting `CompletionDetector`, plan/task `RepairStrategy`, agent-scoped `SessionStore`, etc.) and removing all extracted code in favor of the framework dependency.

### Deleted from Magenta (during extraction)
- `api/web/**` (~47 files), `src/main/resources/static/**`, SimplyPages, HTMX, webjar dependencies
- `ai/orchestration/**` (~150 files), `ai/skills/**` (~30 files), `ai/chat/plan/**` (~25 files), `ai/chat/task/**` (9 files), `ai/chat/project/**` (6 files), `ai/chat/system/**` (6 files), `ai/agent/job/**` (7 files), `ai/execution/**` (8 files)
- All app-specific tool sub-packages: `ai/chat/tool/file/`, `shell/`, `web/`, `skills/`, `orchestration/`, `plan/`, `savedplan/`, `task/`
- `InteractionQuestionTools.java`, `ChatSessionOrigin.java`, `ChatSessionSurface.java`, `ChatPlanState.java`, `PlanMode.java`, `ChatFileService.java`, `ChatInvocationProfile.java`
- `TrustedContextPolicyAssembler`, `OrchestrationTaskContext`, `OrchestrationTaskContextHolder`
- `SchemaMigrationRunner` and 20 migration files (replaced by framework-owned self-migrating `schema.sql`)
- `MagentaWorkExecutor` (replaced by virtual-thread `TurnExecutor`)
- `spring-boot-starter-web`, `spring-boot-starter-webmvc-test`, `simplypages`, `webjars-locator-core`, `htmx.org` Maven dependencies
- Web-related test packages and resources only — non-web tests for surviving code (`ChatService`, `ChatMemoryRepository`, `ChatToolRegistry`, `ChatModelRouter`, `ConversationTurnCoordinator`) are preserved and adapted incrementally

## Features

### 1. Turn-Loop Engine (`DefaultTurnEngine`)
- 6-state machine extracted intact from `ChatService.toolChat()`: PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE
- `Flux<TurnEvent> stream(TurnRequest)` for SSE streaming, `TurnResult execute(TurnRequest)` for synchronous calls
- Interrupt support via `ActiveTurnRegistry` polling pattern with `interruptToken` on `TurnEvent.Started`
- Single `SerialTurnGate` using `ReentrantLock` (virtual-thread-compatible) replaces the current dual serialization (semaphore map + turn coordinator), eliminating the `streamLocks` race
- Zero `ThreadLocal` usage — `activeTrustedPolicies` becomes a turn-scoped carrier in `TurnContext`
- No `TurnMode`, `PlanMode`, or `InteractionMode` in the framework
- `ThinkingExtractor` SPI extracts thinking/reasoning content from model responses in a provider-agnostic way. Default implementation handles Ollama `thinking` metadata, DeepSeek `reasoningContent`, and `<think>` tag fallback. The `TurnEvent.Chunk` carries a `thinking` boolean flag set by the extractor
- `ToolLoopGuard` is a concrete final class (see Appendix A for API). Limits: max 5 identical consecutive tool calls, max 5 errors in a window of 8 tool calls. When tripped, throws `ToolException.LoopExceeded` which is caught by the engine and routed to REPAIR phase

### 2. Public API Surface (`TurnLoop` Facade)
- Interface `TurnLoop` with full method signatures (see Appendix A)
- Sub-interfaces via accessor methods: `tools()`, `memory()`, `sessions()`
- `TurnLoopBuilder` returns `TurnLoop` (the interface). Spring auto-configuration exposes `DefaultTurnLoop` bean as `TurnLoop`
- `TurnLoop` extends `AutoCloseable`; `close()` drains `TurnGate` (all states: cancels AWAITING_ANSWER turns with `TurnEvent.Interrupt`, waits for EXECUTING turns up to a configurable drain timeout, then forces release), releases stream resources, releases pending claims, closes `AutoCloseable` `ChatModel` instances
- All internal exceptions (`ToolUseAbort`, `NonTransientAiException`) caught at facade boundary, wrapped in public exception hierarchy
- No raw `RuntimeException` or Spring AI exception leaks through `TurnLoop` methods

### 3. Public SPIs (Pluggable Extension Points)
Every SPI ships with a production-quality default. `TurnLoop.builder().build()` works with zero configuration. Full interface signatures are in Appendix A.

| SPI | Responsibility | Framework Default |
| --- | --- | --- |
| `ToolRegistry` | Register, unregister, resolve tools per turn. Framework-internal tools hidden from `definitions()`. Two-stage registration: public + framework-internal with name-collision detection | `DefaultToolRegistry` |
| `ToolAccessPolicy` | Filter tools available per turn. Static factories: `allowAll()`, `allowList()`, `blockList()` | `ToolAccessPolicy.allowAll()` |
| `ToolResultSummarizer` | Summarize tool results for transcript. Replaces hard-coded `ToolTranscriptService` switch. Enforces configurable `maxResultChars` before truncation with a `"[truncated: N chars]"` suffix | Generic fallback: `"Returned N characters"` |
| `InteractionHandler` | Callback for question-user loop: `onQuestionsAsked(QuestionPendingContext)` returns `AnswerResult` (sealed: `AnswerResult.Continue`, `AnswerResult.Abort`, `AnswerResult.SingleMessage`, `AnswerResult.MultiMessage`). `InteractionHandler.async()` convenience wrapper for async notification without blocking the turn | Pluggable; default throws if `ask_user` is called without handler configured |
| `InteractionStore` | Persist pending questions across restarts. `save(sessionId, QuestionPendingContext)`, `load(sessionId)`, `delete(sessionId)` | `InMemoryInteractionStore` |
| `ConversationMemory` | Message storage. `getMessages(sessionId)` returns `List<TurnMessage>` in insertion order. `addMessages(sessionId, List<TurnMessage>)`. `clear(sessionId)`. Does NOT extend Spring AI `ChatMemory` | `SqliteConversationMemory` |
| `SessionStore` | Session metadata. `save(SessionMeta)`, `findById(sessionId)`, `list(ConversationFilter)`, `delete(sessionId)`. `metadata TEXT` JSON extension column | `SqliteSessionStore` |
| `TokenEstimator` | `estimateTokens(String text)` → `int`. `estimateTokens(TurnMessage)` → `int` | JTokkit-based with character-count/4 fallback |
| `CompactionStrategy` | `CompactionResult compact(CompactionContext)` — summarizes older messages. Called asynchronously after turn completion. Result stored via `ConversationMemory.addMessages()` as a system message with `metadata.type = "compaction_summary"` | Summary-based default using the compaction model |
| `CompletionDetector` | `boolean isComplete(TurnContext)` — called after model response; returns true when turn complete without further tools | `DefaultCompletionDetector` (never complete without terminal response — checks for non-null assistant text and zero tool calls) |
| `RepairStrategy` | `List<TurnMessage> repair(TurnContext, RepairReason)` — produces control messages when model response is empty/incomplete. `RepairReason` enum: `EMPTY_RESPONSE`, `TOOL_LOOP_EXCEEDED`, `CUSTOM` | `EmptyResponseRepair` + `NoOpRepair` |
| `TurnGate` | Serialize turns per session. Stateful: IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE. `TurnGate.AcquireResult` sealed: `Acquired`, `Rejected(String reason)`, `Queued(String claimToken)`. Configurable turn timeout (default 10 min) — expired turns are force-released | `SerialTurnGate` with `ReentrantLock` |
| `MessageQueue` / `MessageQueueStore` | Claim/ack/release pattern with stale-claim recovery for mid-turn queued messages. `MessageQueue` interface: `enqueue`, `list`, `claim`, `ack`, `release`, `recoverStaleClaims`. `MessageQueueStore` is the persistence SPI | `SqliteMessageQueueStore` |
| `AuditWriter` | `recordUserMessage(sessionId, TurnMessage)`, `recordAssistantMessage(sessionId, TurnMessage)`, `recordToolExecution(sessionId, ToolExecution)`, `recordContextUsage(sessionId, ContextUsage)`, `recordError(sessionId, TurnError)`. All methods are fire-and-forget (return void, must not throw) | `NoOpAuditWriter` (default), `SqliteAuditWriter` (optional) |
| `PromptContributor` | `String contribute(TurnContext)` — inject prompt layers. `@Order` annotation or `Ordered` interface controls priority. Receives `TurnContext`, returns prompt text | None by default; framework ships `DefaultPromptAssembler` with 3-layer structure |
| `ModelCapabilityProvider` | `Set<ModelCapability> capabilities(String modelAlias)` — declare model capabilities. `ModelCapability` enum: `STREAMING`, `TOOL_CALLING`, `VISION` | Pluggable; embedding apps populate |
| `TurnLoopMetrics` | Micrometer metrics: turn duration histogram, tool-call counter, active-turns gauge, queue-depth gauge | Default Micrometer implementation in autoconfigure |
| `TurnLifecycleListener` | `void onTurnCompleted(TurnResult)` — post-turn callback for title generation, audit, custom actions | No-op default |
| `ThinkingExtractor` | `ThinkingResult extract(AssistantMessage)` — extracts thinking/reasoning from model response. `ThinkingResult` record: `String visibleText, String thinkingText`. Provider-agnostic | `CompositeThinkingExtractor` (Ollama metadata → DeepSeek reasoningContent → `<think>` tag fallback) |

### 4. Event Streaming Contract
- `TurnEvent` sealed hierarchy (full definitions in Appendix A). Transport-agnostic: raw text only, no HTML, no `renderedHtml`
- Events:
  - `Started(String turnId, String interruptToken, ContextUsage contextUsage)`
  - `Chunk(String turnId, String text, boolean thinking, ContextUsage contextUsage)`
  - `ToolActivity(String turnId, String toolName, String toolCallId, ToolActivityStatus status, String summary, ContextUsage contextUsage)` where `ToolActivityStatus` enum: `COMPLETED`, `FAILED`
  - `SystemNotice(String turnId, NoticeKind kind, String message, ContextUsage contextUsage)` where `NoticeKind` enum: `COMPACTION`, `CONTEXT_EXHAUSTED`, `TOOL_LOOP_WARNING`, `REPAIR_APPLIED`
  - `QuestionPending(String turnId, String questionText, ContextUsage contextUsage)`
  - `Interrupt(String turnId, String reason, ContextUsage contextUsage)`
  - `Completed(String turnId, TurnDiagnostic diagnostic, ContextUsage contextUsage)`
  - `Failed(String turnId, TurnDiagnostic diagnostic, ContextUsage contextUsage)`
- No `OutputsChanged` (tied to deleted file-mutating tools)
- `MarkdownRenderer` is an optional utility in `turnloop-core` with `commonmark` and `owasp-html-sanitizer` as optional Maven dependencies. When dependencies are absent, `render()` returns the input text unmodified (graceful degradation). Embedding apps render at their transport boundary
- `TurnEventRenderer.json(ObjectMapper)` returns a `Function<TurnEvent, String>` that serializes events to JSON Lines
- `TurnEventRenderer.sse()` returns a `Function<TurnEvent, String>` that formats events as SSE (`data: {json}\n\n`)
- `StreamLifecycle` SSE adapter lives in `turnloop-autoconfigure` (not core). Wraps a `Flux<TurnEvent>` and an `SseEmitter`, handling client disconnect (cancels flux), backpressure (buffers up to configurable capacity, drops oldest on overflow with `SystemNotice`), and error recovery (emits `Failed` event before completing emitter)

### 5. Tool Framework
- `ToolRegistry` with `register(ToolCallback)`, `registerToolBean(Object)`, `unregister(String toolName)`, `definitions()` → `List<ToolDefinition>`, `resolveForTurn(TurnContext)` → `List<ToolCallback>`, and `registerFrameworkTool(ToolCallback)` for built-in tools
- Framework auto-registers two framework tools: `ask_user` (question-user loop) and `echo` (demonstration). Neither visible through `definitions()`. Framework tools stored in a separate internal map; `resolveForTurn` merges public + framework tools. Name collision with app-registered tools throws `ToolException` with message: `"Tool name '" + name + "' conflicts with a framework-internal tool"`
- `ToolAccessPolicy` with static factory methods: `allowAll()`, `allowList(Set<String>)`, `blockList(Set<String>)`
- `ToolResultSummarizer` replaces hard-coded `ToolTranscriptService` switch. `SummarizeResult summarize(ToolExecution execution)` where `ToolExecution` carries tool name, arguments, and raw result. `SummarizeResult` record: `String summary, boolean truncated`. Configurable `maxResultChars` (default 2000)
- `ToolArgumentCoercionModule` (Jackson module, service-loader registered via `META-INF/services/com.fasterxml.jackson.databind.Module`)
- `ToolLoopGuard` (concrete final class, see Appendix A)
- Sealed exception hierarchy: `ToolException.ArgumentCoercionFailed`, `ExecutionFailed`, `LoopExceeded`, `AccessDenied`
- Example tools (`EchoTool`, `DateTimeTool`) ship in `turnloop-core` under `io.mindspice.turnloop.example` but are NOT auto-registered — embedding apps opt in by explicitly registering them

### 6. Memory, Session, and Persistence
- Four framework-owned tables with complete DDL (see Appendix B)
- Self-migrating `CREATE TABLE IF NOT EXISTS` pattern per repository
- Minimal version-tracked migration runner: `turnloop_schema_version` table with `VERSION INTEGER NOT NULL`, `Migration` interface with `int version()` and `void migrate(DataSource)`, `MigrationRunner` that applies pending migrations in version order within a single transaction per migration. See Appendix A for interface definitions
- `SessionStore` removes all Magenta-specific columns. `SessionMeta` record: `String sessionId, String title, String model, boolean favorite, boolean archived, String origin, Instant createdAt, Instant updatedAt, Map<String,String> metadata`. `origin` defaults to `"turnloop"`
- `ConversationMemory` defines `getMessages(String sessionId)` returning `List<TurnMessage>` in insertion order (oldest first). Windowed queries handled by framework-internal `ContextManager`, not the SPI
- `TokenEstimator` is a standalone SPI injected into `ContextManager`, not `ConversationMemory`
- `CompactionStrategy` is called asynchronously after turn completion, not synchronously during the turn. Result stored as a `TurnMessage` with `role = "system"` and `metadata.type = "compaction_summary"` via `ConversationMemory.addMessages()`
- `SpringAiConversationMemoryAdapter` bridges to Spring AI's `ChatMemory` for backward compatibility — wraps a `ConversationMemory`, adapts `TurnMessage` ↔ Spring AI `Message`

### 7. Message Queueing
- `MessageQueue` interface (see Appendix A for full signatures)
- `PendingMessage` record: `String messageId, String sessionId, String messageText, Map<String, String> metadata, Instant enqueuedAt`. Reserved metadata keys: `"turnloop.origin"`, `"turnloop.priority"`
- `ClaimedMessage` record: `PendingMessage message, String claimToken, Instant claimedAt`
- Claim/ack/release with stale-claim recovery preserved exactly from `ChatPendingMessageService`. Stale claim threshold configurable via `turnloop.queue.stale-claim-age`
- Queue-to-turn lifecycle: claiming a message does NOT automatically start a turn. The embedding app calls `TurnLoop.send(sessionId, claimedMessage.message().messageText())` after claiming. If a turn is already executing for that session, `send()` delegates to the `TurnGate` which rejects or queues

### 8. Prompt Assembly
- `DefaultPromptAssembler` builds prompt from a prioritized pipeline of `PromptContributor` implementations
- 3-layer default: (1) system prompt from config, (2) registered contributors sorted by `@Order` / `Ordered`, (3) turn instructions
- Contributors registered via `TurnLoopBuilder.promptContributor(PromptContributor)` or auto-discovered as Spring beans
- `PromptContributor.contribute(TurnContext)` called per-turn; return value appended to system prompt

### 9. Model Routing and Capability Detection
- `ModelRouter` is a framework-internal interface (see Appendix A). Maps model aliases to `ChatModel` instances internally; public methods return framework-native types only
- `ModelCapabilityProvider` SPI: `Set<ModelCapability> capabilities(String modelAlias)`
- `TurnEngine` queries required capabilities from the request context: if `TurnRequest.stream()` requested, requires `STREAMING`; if `TurnRequest.toolCallbacks()` non-empty, requires `TOOL_CALLING`. Fails fast with `TurnLoopException` before invoking the model
- Virtual-thread safety audited for target Spring AI version and its HTTP client before Phase 4 commit

### 10. Observability
- `TurnLoopMetrics` SPI with default Micrometer implementation in `turnloop-autoconfigure`
- Minimum metrics: `turnloop.turn.duration` (histogram, tags: model, outcome), `turnloop.tool.calls` (counter, tags: tool_name, status), `turnloop.turns.active` (gauge), `turnloop.queue.depth` (gauge, tag: session_id)
- `TurnLoopHealthIndicator` (Spring Boot `HealthIndicator`): pings the default model's endpoint via `ChatModel.call()` with a minimal prompt. Healthy if response is non-null and non-empty within configurable timeout (default 30s). Unhealthy on timeout, connection refused, or empty response
- `TurnLifecycleListener.onTurnCompleted(TurnResult)` for custom post-turn actions

### 11. Lifecycle and Graceful Shutdown
- `TurnLoop` extends `AutoCloseable`
- `close()`: (1) Cancels all AWAITING_ANSWER turns with `TurnEvent.Interrupt`, (2) Waits for EXECUTING turns up to configurable drain timeout (default 30s), (3) Force-releases any remaining EXECUTING turns after timeout, (4) Releases all stream resources, (5) Releases all pending message claims, (6) Closes `AutoCloseable` `ChatModel` instances, (7) Shuts down `TurnExecutor`
- `@PreDestroy` on auto-configured bean calls `close()`
- No `ThreadLocal` usage remains in the framework

### 12. Configuration Surface
```yaml
turnloop:
  data-root: ${user.home}/.turnloop
  models:
    default: ollama-local
    compaction: ollama-local
    providers:
      ollama-local:
        type: ollama
        endpoint: http://localhost:11434
        model: qwen3.6:35b
        context-length: 128000
        think-level: 0
        api-key: ""
  context:
    buffer-percent: 10
    compaction:
      min-tail-messages: 6
    trimming:
      enabled: true
  turn:
    max-tool-rounds: 50
    max-empty-response-retries: 2
    timeout: 10m
    drain-timeout: 30s
    tool-guard:
      identical-call-limit: 5
      error-window-size: 8
      error-window-limit: 5
  queue:
    stale-claim-age: 10m
    max-queue-size-per-session: 100
  stream:
    buffer-capacity: 256
  executor:
    strategy: virtual
    turn-threads: 8
    turn-queue-size: 200
  model-timeout:
    openai-compatible-read-seconds: 360
  prompt:
    default-system-prompt: "You are a helpful assistant."
  result-summarizer:
    max-result-chars: 2000
```

**Configuration validation rules:**
- `models.default` must reference a key in `models.providers` — throws `ConfigurationException("Default model '" + name + "' not found in providers")`
- `models.providers.<name>.endpoint` must be a valid URL — throws `ConfigurationException("Invalid endpoint URL for model '" + name + "': " + url)`
- `context.buffer-percent` must be 0–50 — clamps with warning log
- `turn.max-tool-rounds` must be >= 1 — clamps to 1 with warning log
- `turn.timeout` must be >= 1s — clamps to 1s with warning log
- `turn.drain-timeout` must be >= 0 (0 = no wait, force-release) — no clamp, accepts 0
- `stream.buffer-capacity` must be >= 1 — clamps to 1
- `result-summarizer.max-result-chars` must be >= 100 — clamps to 100

### 13. Exception Hierarchy
```
TurnLoopException (RuntimeException, unchecked)
├── TurnFailedException (sealed)
│   ├── ContextExhaustedException(contextUsage)
│   └── TurnFailedException with TurnOutcome + TurnDiagnostic
├── ConversationNotFoundException(sessionId)
├── ToolException (sealed)
│   ├── ArgumentCoercionFailed(toolName, callId, message)
│   ├── ExecutionFailed(toolName, callId, message, cause)
│   ├── LoopExceeded(toolName, message, recentErrors)
│   └── AccessDenied(toolName, message)
└── ConfigurationException(message, details)
```
All internal exceptions wrapped at facade boundary. No Spring AI types leak.

### 14. Framework-Native Types (No Spring AI Leakage)
- All public API types defined in `io.mindspice.turnloop` (see Appendix A for complete definitions)
- `ToolCallback` is a framework-owned interface in `io.mindspice.turnloop` — the `ModelRouter` maps internally to Spring AI's `ToolCallback`, so the public API never exposes Spring AI types
- Mapping layer between framework types and Spring AI types lives at the `ModelRouter` boundary

### 15. Documentation
- Javadoc on every public SPI method: thread-safety guarantees, nullability contracts (all public method parameters are `@NonNull` unless documented otherwise; return values are `@Nullable` only when explicitly documented), expected exception behavior, lifecycle invariants
- Markdown docs: `README.md` with embedding example (including a production-quality SSE controller example that handles client disconnect with `emitter.complete()`, backpressure with `onBackpressureBuffer`, and error recovery with `onErrorResume`), `docs/getting-started.md`, `docs/turn-loop.md`, `docs/tools.md`, `docs/memory.md`, `docs/streaming.md`, `docs/interaction.md`, `docs/queueing.md`, `docs/configuration.md`, `docs/facade-api.md`

### 16. Example Tools
- `EchoTool` (`@Tool(name = "echo", description = "Echoes the input message back to the caller")`): single `String message` parameter. Demonstrates basic `@Tool` usage
- `DateTimeTool` (`@Tool(name = "get_current_time", description = "Returns the current date and time")`): optional `String timezone` parameter (default UTC). Demonstrates parameterized tools
- `ask_user`: built-in framework tool (not an example — production question-user loop)
- Example tools are NOT auto-registered

## Settled Decisions

The following decisions resolve all conflicts from the brainstorming and incorporate every red-team finding as design constraints.

### Naming and Structure
1. **Artifact naming**: `io.mindspice.turnloop` root package, artifact `turnloop-core`, group `io.mindspice`. Facade: `TurnLoop` (interface) implemented by `DefaultTurnLoop`.
2. **Module structure**: Two modules for v1: `turnloop-core` and `turnloop-autoconfigure`. `turnloop-transport-sse` and `turnloop-example` deferred.
3. **`turnloop-core` has zero web dependency**: No `spring-boot-starter-web`, `SseEmitter`, or servlet API. Enforced by POM with Maven Enforcer plugin `bannedDependencies` rule. `StreamLifecycle` lives in `turnloop-autoconfigure`. `TurnEventRenderer.json()` stays in core (depends only on Jackson).

### SPI and API Design
4. **Hybrid facade**: `TurnLoop` interface with direct methods plus accessor sub-interfaces. Builder returns the interface. Internal services are interfaces mocked directly in tests.
5. **No TurnMode/PlanMode**: Removed entirely. Replaced by three SPIs: `ToolAccessPolicy`, `CompletionDetector`, `RepairStrategy`.
6. **Four memory SPIs**: `ConversationMemory`, `SessionStore`, `TokenEstimator`, `CompactionStrategy`. `ContextManager` is framework-internal.
7. **Question-user loop**: Framework owns suspend/resume lifecycle. `InteractionHandler` callback + `InteractionStore` SPI. `ask_user` auto-registered as framework-internal tool.
8. **Transport-agnostic events**: `TurnEvent` carries raw text only. `MarkdownRenderer` is an optional utility. Rendering is the embedding app's transport concern.
9. **Framework-native types**: All public types defined in `io.mindspice.turnloop`. Mapping layer at `ModelRouter` boundary.
10. **`TurnGate` as state machine**: IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE. Built on `ReentrantLock` internally. `answer()` requires `AWAITING_ANSWER`; `send()`/`stream()` requires `IDLE`.
11. **`ConversationMemory` windowing on `ContextManager`**: `ConversationMemory` keeps only `getMessages(String sessionId)`. Framework-internal `ContextManager` uses `ConversationMemory.getMessages()` + `TokenEstimator` for windowed queries.
12. **`CompactionStrategy` called asynchronously**: After turn completion, not synchronously during the turn.
13. **`DefaultTurnEngine` uses builder**: Dependencies grouped by concern (toolExecution, contextAssembly, responseRepair) via a package-private builder.
14. **`InteractionHandler.onAnswerReceived` returns `AnswerResult`**: Sealed type supporting continue/abort and multiple messages.
15. **`ToolResultSummarizer` enforces max result size**: Configurable `maxResultChars` with truncation marker.
16. **`ToolRegistry` name-collision detection**: Registering a tool with the same name as a framework-internal tool throws `ToolException` with a clear message.
17. **`TurnContext` uses typed attribute accessor**: `AttributeKey<T>` typed key system (see Appendix A).
18. **Example tools not auto-registered**: Opt-in only.

### Schema and Persistence
19. **Schema migration**: Minimal version-tracked migration runner. Complete DDL in Appendix B.
20. **DataSource auto-provisioning**: `turnloop-autoconfigure` provides a `DataSource` bean for `jdbc:sqlite::memory:` when no `DataSource` exists and `sqlite-jdbc` is on the classpath. `sqlite-jdbc` is a required dependency of autoconfigure, not core.

### Lifecycle and Hardening
21. **Question-pending close behavior**: `close()` cancels all pending questions with `TurnEvent.Interrupt` before draining the `TurnGate`.
22. **Virtual-thread safety**: `TurnGate` uses `ReentrantLock`. `ModelRouter` uses `ConcurrentHashMap`. Target Spring AI version audited for HTTP client virtual-thread pinning before committing to virtual-thread-by-default.
23. **Observability from v1**: `TurnLoopMetrics` SPI + default Micrometer implementation + `TurnLoopHealthIndicator`.
24. **Model capability detection**: `ModelCapability` enum + `ModelCapabilityProvider` SPI. `TurnEngine` fails fast before model invocation.
25. **Java 25 target**: Virtual threads by default. `Executors.newVirtualThreadPerTaskExecutor()` for `TurnExecutor`. Fixed thread pool alternative via `turnloop.executor.strategy=fixed`.

### Test Strategy During Migration
26. **Phase 1 deletes only web-related tests**: Non-web tests for surviving code preserved and adapted incrementally.
27. **`@Autowired(required = false)` stepping-stone cleanup**: Phase 2 includes automated verification (grep) confirming zero `required = false` annotations remain.

### Magenta Re-integration
28. **Phase 7 exists**: Magenta becomes an embedding app consuming `turnloop-core`, implementing app-specific SPIs.

### Turn Event Design
29. **TurnEvent sealed hierarchy with complete field specifications**: Defined in Appendix A.

### Additional SPI Specifications
30. **ThinkingExtractor**: Extracts thinking/reasoning content provider-agnostically. Default `CompositeThinkingExtractor` handles Ollama, DeepSeek, and `<think>` tag fallback.
31. **TurnGate turn timeout**: Configurable timeout (default 10 min). Expired EXECUTING turns are force-released; the turn's `Flux<TurnEvent>` emits `TurnEvent.Failed` with diagnostic before the gate releases.

## Constraints

1. **`turnloop-core` POM must not depend on `spring-boot-starter-web`, `SseEmitter`, or any servlet API.** Enforced by Maven Enforcer plugin `bannedDependencies` rule.
2. **No Spring AI type may appear in any public API signature.** All mapping happens at the `ModelRouter` boundary. Verification: automated check in Phase 6 that greps all public types in `io.mindspice.turnloop` for `org.springframework.ai` imports.
3. **No `ThreadLocal` usage remains in the framework.** Verified by grep in Phase 6.
4. **`TurnLoop.builder().build()` must produce a working instance with zero external configuration.**
5. **The framework must survive process restart for question-pending turns.** `InteractionStore` persists pending questions.
6. **Schema must be evolvable.** `MigrationRunner` exists before v1.0.0 ships.
7. **Every SPI must ship with a production-quality default.** `TurnLoop.builder().build()` works without any SPI wiring.
8. **Java 25 is the target runtime.** Virtual threads are default.
9. **Existing Magenta non-web tests must survive the extraction.**
10. **Zero `@Autowired(required = false)` annotations remain after Phase 2.**
11. **`ToolRegistry` name collisions with framework-internal tools fail with a clear exception.**
12. **`TurnContext` uses typed attribute access.**
13. **`InteractionHandler.onAnswerReceived` returns `AnswerResult`, not raw `String`.**
14. **`ToolResultSummarizer` enforces configurable max result size.**
15. **Example tools are NOT auto-registered.**
16. **`CompactionStrategy` is called asynchronously** after turn completion.
17. **`TurnGate` is a state machine**, not a bare lock.
18. **`MarkdownRenderer` has optional Maven dependencies** with graceful degradation when absent.

## Scope

### In Scope
- Extract `ChatService.toolChat()` turn-loop state machine into `DefaultTurnEngine`
- Define all ~17 SPIs with framework-native types and production-quality defaults (see Appendix A for complete interfaces)
- Build `TurnLoop` facade and `TurnLoopBuilder`
- Create `turnloop-core` and `turnloop-autoconfigure` Maven modules
- Implement SQLite-backed defaults for `ConversationMemory`, `SessionStore`, `MessageQueueStore`
- Implement `SqliteAuditWriter` as optional component with audit table (see Appendix B)
- Implement minimal version-tracked `MigrationRunner`
- Implement `TurnLoopMetrics` Micrometer default and `TurnLoopHealthIndicator`
- Implement `MarkdownRenderer` utility with optional dependencies and graceful degradation
- Implement typed `TurnContext` attribute accessor with `AttributeKey<T>`
- Implement stateful `TurnGate` with timeout and drain semantics
- Implement `StreamLifecycle` SSE adapter in autoconfigure with backpressure and disconnect handling
- Implement `ThinkingExtractor` SPI with composite default
- Implement `ToolLoopGuard` with identical-call and error-window tracking
- Delete all web, orchestration, skill, plan, task, project, system, and app-specific tool packages from Magenta
- Simplify `AiConfig` and `AiUserConfigConfiguration`
- Delete Magenta-specific types listed in Targets
- Delete 20 migration files and `SchemaMigrationRunner` from Magenta
- Rename surviving Magenta classes to framework names
- Create framework-owned `schema.sql` with five tables (four data + version)
- Phase 7: Re-integrate Magenta as embedding app
- Javadoc on every public SPI method
- Markdown documentation
- Full unit test suite for every SPI and state-machine transition
- Integration tests as specified in Validation
- Auto-configuration smoke test
- Concurrency stress test
- Dead code scan

### Out of Scope
- `turnloop-transport-sse` module (deferred)
- `turnloop-example` module (deferred; example tools in core are sufficient)
- WebSocket or gRPC transport bindings
- In-memory (non-SQLite) `ConversationMemory` implementation
- Multi-module build beyond two modules
- Any web UI, SimplyPages, HTMX, or browser surface in the framework
- Authentication, authorization, or multi-tenancy
- Process execution, shell tools, file tools, or workspace filesystem operations
- Task, plan, workflow, job, orchestration, or agent lifecycle
- Skill discovery, trust, snapshots, or activation
- Project materialization or context binding
- System chat or bounded-read profiles
- Artifact search, preview, retention, or publication
- Schedule or reaction automation
- Agent console or operational UI
- Playwright or browser-based validation
- CLI or non-Spring embedding support
- Migrating existing Magenta databases to the framework schema

## Assumptions

1. **Spring AI's HTTP client is virtual-thread-safe for the target version.** Audit required before Phase 4.
2. **All five framework tables can be reduced from Magenta's 20-migration schema.** The DDL in Appendix B captures equivalent state.
3. **Phase 1 test deletion scoped to web only.** Exact file list to be produced before Phase 1 begins.
4. **`TurnLoopBuilder` requires Spring `ApplicationContext`.** Standalone usage out of scope for v1.
5. **Embedding apps provide their own HTTP endpoints.** Framework provides `StreamLifecycle` and `TurnEventRenderer` utilities; `README.md` includes a production-quality SSE controller example.
6. **Question-user loop is single-question-per-turn.** Multi-question dialogs within one turn are out of scope.
7. **SQLite is the only v1 default persistence.**
8. **Token estimation via JTokkit is approximate.** `TokenEstimator` SPI exists for provider-specific estimation.
9. **Magenta re-integration (Phase 7) is required for framework validation.**
10. **JPMS `Automatic-Module-Name: io.mindspice.turnloop`** in framework MANIFEST.
11. **`turnloop-autoconfigure` serves as `spring-boot-starter-turnloop` meta-starter.**

## Recommended Direction

### Pre-work (design finalization, before Phase 0)
- Review and approve Appendix A (type definitions and SPI signatures) and Appendix B (database DDL)
- Audit target Spring AI version and its HTTP client for virtual-thread pinning; document finding
- Inspect `src/test/` directory and produce exact file list for Phase 1 preservation vs. deletion
- Write and review the `README.md` SSE controller example

### Phase 0: Scaffold and Baseline
**Artifacts created:**
- `turnloop-core/pom.xml` with Maven Enforcer `bannedDependencies` rule (no `spring-boot-starter-web`, `spring-web`, `spring-webmvc`, `jakarta.servlet`, `SseEmitter`)
- `turnloop-autoconfigure/pom.xml` depending on `turnloop-core` + `spring-boot-autoconfigure` + `sqlite-jdbc`
- Root `pom.xml` with `<modules>` for both

**Gate:** `mvn clean verify` passes on unchanged codebase; context startup smoke test passes

### Phase 1: Delete Web/UI Surface
**Artifacts deleted:** `api/web/**`, `src/main/resources/static/**`, SimplyPages, HTMX, webjar dependencies, web-related test packages, Playwright config
**Artifacts preserved:** Non-web tests for `ChatService`, `ChatMemoryRepository`, `ChatToolRegistry`, `ChatModelRouter`, `ConversationTurnCoordinator` (exact list TBD in pre-work)
**Artifacts modified:** `ChatStreamEvent` → `TurnEvent` (remove `ChatPlanState` references, keep in `ai/chat/model/` temporarily)
**POM changes:** Drop `spring-boot-starter-web`, `spring-boot-starter-webmvc-test`, `simplypages`, `webjars-locator-core`, `htmx.org`

**Gate:** compile, non-web tests pass, context starts without embedded Tomcat (verify via `--server.port=0` and check no `TomcatWebServer` in logs)

### Phase 2: Sever ChatService Dependencies
**Artifacts created:**
- `InteractionHandler` SPI interface in `ai/chat/service/turn/`
- `PromptContributor` SPI interface in `ai/chat/service/turn/`
- `CompletionDetector` SPI interface in `ai/chat/service/turn/`
- `RepairStrategy` SPI interface in `ai/chat/service/turn/`
- `DefaultTurnEngine` builder/package-private factory
**Artifacts modified:**
- `ChatService`: apply `@Autowired(required = false)` to all 13 app-shell collaborators, remove one at a time, delete plan/task/agent/skills/workspace/measurement method calls
- `PromptContextAssembler` → adapt to `PromptContributor` SPI
- `ToolAccessPolicy` → generalize to SPI with `allowAll()` default
- `TerminalTurnRepair` → adapt to `RepairStrategy` + `CompletionDetector`
- `ChatServiceTest`: delete plan/task test methods, update constructor invocations
**Verification step:** grep for `@Autowired(required = false)` — must return zero results

**Gate:** compile, `ChatServiceTest` passes with mock collaborators, zero `PlanService`/`TaskService` imports in `ChatService`, zero `@Autowired(required = false)`

### Phase 3: Delete App-Shell Packages
**Artifacts deleted:** All packages listed in Targets (orchestration, skills, plan, task, project, system, job, execution, app-specific tools, `InteractionQuestionTools.java`); `ChatSessionOrigin.java`, `ChatSessionSurface.java`, `ChatPlanState.java`, `PlanMode.java`, `ChatFileService.java`, `ChatInvocationProfile.java`; 16 dead migration files, `SchemaMigrationRunner`; `TrustedContextPolicyAssembler`, `OrchestrationTaskContext`, `OrchestrationTaskContextHolder`
**Artifacts modified:** `AiConfig` (remove agents, defaultAgent, planningModel, compactionModel, unsafeAllowWildcardShellCommands; collapse to one canonical constructor); `AiUserConfigConfiguration` (remove agent resolution)
**Verification step:** grep for references to deleted packages from retained code — must return zero results

**Gate:** compile, all remaining tests pass, context starts

### Phase 4: Extract into Framework Packages
**Artifacts created:**
- All types from Appendix A defined in `io.mindspice.turnloop` packages under `turnloop-core/src/main/java/`
- All SPI interfaces from Appendix A
- `TurnRequest`, `TurnResult` records
- `TurnEvent` sealed hierarchy with all fields
- `TurnMessage`, `ConversationSession`, `ContextUsage`, `PendingMessage`, `ClaimedMessage`, `TurnDiagnostic`, typed `TurnContext` with `AttributeKey<T>`
- `TurnExecutor` (virtual-thread executor wrapping `Executors.newVirtualThreadPerTaskExecutor()`)
- `ActiveTurnRegistry` (ConcurrentHashMap-backed, `register(String turnId)` → `ActiveTurn`, `get(String turnId)`, `remove(String turnId)`)
- `ToolLoopGuard` concrete final class
- `ToolArgumentCoercionModule` with `META-INF/services` registration
- `ToolResultSummarizer` SPI with default generic implementation
- `ThinkingExtractor` SPI with `CompositeThinkingExtractor` default
- `SerialTurnGate` with timeout and drain semantics
- `DefaultToolRegistry` with two-stage registration
- `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`
- `MigrationRunner` with `Migration` interface
- `schema.sql` with five tables (Appendix B DDL)
- `MarkdownRenderer` with optional dependency graceful degradation
- `TurnEventRenderer.json()` and `TurnEventRenderer.sse()`
**Artifacts moved:** Surviving Magenta code relocated into `io.mindspice.turnloop` packages, renamed to framework names
**Artifacts deleted:** `MagentaWorkExecutor`, hard-coded `ToolTranscriptService` switch

**Gate:** compile, unit tests pass, context starts with framework beans only

### Phase 5: Build Facade
**Artifacts created:**
- `TurnLoop` interface with all facade methods (see Appendix A)
- `DefaultTurnLoop` implementation
- `TurnLoopBuilder` with methods: `model(String alias, ModelConfig)`, `dataRoot(Path)`, `defaultSystemPrompt(String)`, `toolRegistry(ToolRegistry)`, `toolAccessPolicy(ToolAccessPolicy)`, `toolResultSummarizer(ToolResultSummarizer)`, `interactionHandler(InteractionHandler)`, `interactionStore(InteractionStore)`, `conversationMemory(ConversationMemory)`, `sessionStore(SessionStore)`, `tokenEstimator(TokenEstimator)`, `compactionStrategy(CompactionStrategy)`, `completionDetector(CompletionDetector)`, `repairStrategy(RepairStrategy)`, `turnGate(TurnGate)`, `messageQueue(MessageQueue)`, `auditWriter(AuditWriter)`, `promptContributor(PromptContributor)`, `modelCapabilityProvider(ModelCapabilityProvider)`, `thinkingExtractor(ThinkingExtractor)`, `turnLifecycleListener(TurnLifecycleListener)`, `objectMapper(ObjectMapper)`, `build()`
- `turnloop-autoconfigure`: `TurnLoopAutoConfiguration`, `TurnLoopProperties`, `DataSource` auto-provisioning, `StreamLifecycle` SSE adapter, `TurnLoopHealthIndicator`, `TurnLoopMetrics` Micrometer default
- `close()` lifecycle implementation

**Gate:** facade integration tests pass end-to-end with real Ollama model, lifecycle tests pass

### Phase 6: Tests, Documentation, Hardening
**Artifacts created:**
- Unit tests for every public SPI method and every `TurnPhase` state-machine transition
- Integration tests as listed in Validation
- Auto-configuration smoke test (`@SpringBootTest` with `TurnLoop` bean)
- Concurrency stress test
- Javadoc on every public SPI method
- Markdown documentation (all files listed in Features §15)
- `README.md` with production-quality SSE controller example
**Verification steps:**
- `mvn clean verify` passes
- JaCoCo >80% line coverage on `io.mindspice.turnloop` packages
- PMD or IDE unused-code scan confirms zero references to deleted Magenta packages
- grep for `org.springframework.ai` in public API types — must return zero results
- grep for `ThreadLocal` in framework source — must return zero results
- Javadoc `mvn javadoc:jar` succeeds with zero warnings

**Gate:** all above pass

### Phase 7: Magenta Re-integration
**Artifacts created:**
- Magenta depends on `turnloop-core`
- App-specific SPI implementations: mode-gated `ToolAccessPolicy`, plan-detect `CompletionDetector`, plan/task `RepairStrategy`, agent-scoped `SessionStore`, app-specific `PromptContributor`, etc.
**Artifacts deleted:** All extracted code now provided by framework dependency

**Gate:** Magenta context starts, existing non-web tests pass consuming framework

## Validation

### Per-Phase Gates
- Phase 0: `mvn clean verify` passes on unchanged codebase
- Phase 1: compile, non-web tests pass, context starts without embedded Tomcat (no `TomcatWebServer` in log)
- Phase 2: compile, `ChatServiceTest` passes, zero `PlanService`/`TaskService` imports in `ChatService`, zero `@Autowired(required = false)`
- Phase 3: compile, all remaining tests pass, context starts, zero references from retained code to deleted packages
- Phase 4: compile, unit tests pass, context starts with framework beans only
- Phase 5: facade integration tests pass (real Ollama), lifecycle tests pass
- Phase 6: `mvn clean verify` passes, >80% line coverage, zero dead references, Javadoc complete, zero `org.springframework.ai` in public API, zero `ThreadLocal` in framework
- Phase 7: Magenta context starts, existing tests pass consuming framework

### Key Validation Scenarios
1. `TurnLoop.builder().build()` produces a working instance with in-memory SQLite and default Ollama config — no external files needed
2. Full turn loop: message sent via `TurnLoop.send()`, model responses streamed via `Flux<TurnEvent>`, tool calls executed, terminal `TurnEvent.Completed` received with non-null `TurnDiagnostic`
3. Question-user loop: turn suspends on `ask_user`, `TurnEvent.QuestionPending` emitted with question text, `TurnLoop.answer(sessionId, "user answer")` called, turn resumes, terminal response received
4. `close()` during question-pending turn: `TurnEvent.Interrupt` emitted with reason `"shutdown"`, gate drained, resources released
5. Concurrent turns on same session: `SerialTurnGate` enforces IDLE → EXECUTING → AWAITING_ANSWER state transitions; second `send()` receives `TurnGate.Rejected`
6. Interrupt mid-turn: `TurnLoop.interrupt(turnId, "test interrupt")` via `interruptToken`, turn terminates with `TurnEvent.Interrupt`
7. Model capability mismatch: `stream()` called on model without STREAMING capability → fast-fail `TurnLoopException` with message `"Model '" + alias + "' does not support STREAMING"`
8. Queue claim/ack/release: claim message → receive unique `ClaimedMessage` with `claimToken` → `ack(claimToken)` removes it → stale claim recovered by `recoverStaleClaims()` (claim older than `stale-claim-age`)
9. Graceful shutdown: `@PreDestroy` calls `close()`, all resources released, no leaked threads
10. Configuration validation: missing `models.default` produces `ConfigurationException("Default model 'missing' not found in providers")`
11. Tool name collision: registering `"ask_user"` via `ToolRegistry.register()` throws `ToolException` with message `"Tool name 'ask_user' conflicts with a framework-internal tool"`
12. Turn timeout: EXECUTING turn exceeds `turn.timeout` → `TurnEvent.Failed` emitted with diagnostic `"Turn timed out after 10m"` → gate released
13. Tool loop guard trip: 6th identical tool call → `ToolException.LoopExceeded` → REPAIR phase → `TurnEvent.SystemNotice(TOOL_LOOP_WARNING)` → turn continues with control message
14. `ToolResultSummarizer` truncation: tool returns 5000 chars, `maxResultChars=500` → summary is first 500 chars + `" [truncated: 5000 chars]"`
15. `MarkdownRenderer` without optional dependencies: `render("**bold**")` returns `"**bold**"` (unmodified), logs WARN once at startup
16. `ConversationMemory.getMessages()` returns messages in insertion order (oldest first)
17. `CompactionStrategy.compact()` result stored as system message with `metadata.type = "compaction_summary"`, retrieved by next turn's `ContextManager`
18. `InteractionStore` survives restart: save question context, restart JVM, load returns same `QuestionPendingContext`
19. `TurnLoop.stream()` backpressure: consumer slow → buffer fills → oldest `TurnEvent` dropped → `TurnEvent.SystemNotice(BUFFER_OVERFLOW)` emitted
20. Client disconnect during stream: `SseEmitter` completes, `Flux<TurnEvent>` cancelled, turn continues (model keeps running but events discarded — no crash)

## Open Questions

1. **Final artifact name approval**: `io.mindspice.turnloop` / `turnloop-core` is used throughout. Alternative `io.mindspice.loom` / `mindspice-loom` is a search-and-replace. Await user preference.

2. **Example tool selection**: `EchoTool` and `DateTimeTool` proposed. Easily changed.

3. **JPMS module name**: Should the framework declare `Automatic-Module-Name: io.mindspice.turnloop`? This handoff assumes yes.

4. **Model configuration file format**: Should the framework support existing `ExternalAiConfigLoader` JSON format? This handoff supports `application.yml`, programmatic, and classpath `turnloop-models.json` auto-discovery.

5. **Phase 1 test preservation scope**: Exact file list must be scoped before Phase 1 begins.

6. **Target Spring AI version**: Which version(s) does the framework target? Virtual-thread safety audit depends on this.

7. **`MarkdownRenderer` module placement**: Kept in `turnloop-core` with optional dependencies. Revisit if dependency conflicts arise.

8. **Audit table retention**: The `turnloop_audit_log` table (Appendix B) can grow unboundedly. Should the framework include a configurable retention cleaner, or is this the embedding app's responsibility?

## Appendix A: Type Definitions and SPI Signatures

### Core Records and Types

```java
// ── TurnRequest ──
public record TurnRequest(
    @NonNull String sessionId,
    @NonNull String message,
    @Nullable String model,           // null = default model
    @Nullable List<String> toolNames, // null = resolve via ToolAccessPolicy
    boolean stream,                    // true = caller expects Flux<TurnEvent>
    @Nullable Map<String, String> metadata
) {
    public static TurnRequest of(String sessionId, String message) {
        return new TurnRequest(sessionId, message, null, null, false, Map.of());
    }
}

// ── TurnResult ──
public record TurnResult(
    @NonNull String sessionId,
    @NonNull String turnId,
    @Nullable String message,        // final assistant message text
    @NonNull ContextUsage contextUsage,
    @NonNull TurnDiagnostic diagnostic,
    @NonNull List<ToolActivityData> toolActivities
) {}

// ── TurnContext (mutable turn-scoped carrier) ──
public final class TurnContext {
    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private final List<TurnMessage> messagesToPersist = new ArrayList<>();

    // Immutable fields set at turn start
    public TurnRequest request();
    public String sessionId();
    public String turnId();
    public String model();
    public boolean streaming();

    // Typed attribute access
    public <T> void setAttribute(AttributeKey<T> key, T value);
    public <T> T getAttribute(AttributeKey<T> key);              // returns null if absent
    public <T> T requireAttribute(AttributeKey<T> key);          // throws IllegalStateException if absent
    public boolean hasAttribute(AttributeKey<T> key);

    // Mutable turn state
    public List<TurnMessage> messagesToPersist();
    public ContextUsage currentContextUsage();
    public void currentContextUsage(ContextUsage v);
    public int toolRoundCount();
    public void incrementToolRoundCount();

    // Tool-related (set by engine during execution)
    public List<ToolCallback> activeTools();
    public void activeTools(List<ToolCallback> v);
}

// ── AttributeKey<T> ──
public record AttributeKey<T>(@NonNull String name, @NonNull Class<T> type) {
    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type);
    }
}

// ── TurnMessage (framework-native, no Spring AI dependency) ──
public record TurnMessage(
    @NonNull String role,       // "system", "user", "assistant", "tool"
    @NonNull String text,
    @Nullable String toolName,
    @Nullable String toolCallId,
    @Nullable Map<String, String> metadata
) {
    public static TurnMessage user(String text) {
        return new TurnMessage("user", text, null, null, Map.of());
    }
    public static TurnMessage assistant(String text) {
        return new TurnMessage("assistant", text, null, null, Map.of());
    }
    public static TurnMessage system(String text) {
        return new TurnMessage("system", text, null, null, Map.of());
    }
    public static TurnMessage tool(String toolName, String toolCallId, String resultText) {
        return new TurnMessage("tool", resultText, toolName, toolCallId, Map.of());
    }
}

// ── ContextUsage ──
public record ContextUsage(
    int usedTokens,
    int triggerTokens,   // budget threshold that triggers compaction
    int maxTokens        // model context window size
) {
    public double utilizationPercent() { return maxTokens > 0 ? (100.0 * usedTokens / maxTokens) : 0; }
}

// ── TurnDiagnostic ──
public record TurnDiagnostic(
    @NonNull String turnId,
    @NonNull String sessionId,
    @NonNull Instant startedAt,
    @NonNull Instant completedAt,
    @NonNull TurnPhase finalPhase,
    @NonNull OutcomeKind outcomeKind,
    @NonNull Map<String, Object> details
) {
    public enum OutcomeKind { COMPLETED, INTERRUPTED, FAILED, TIMED_OUT, ABORTED }
}

// ── ToolActivityData ──
public record ToolActivityData(
    @NonNull String toolName,
    @NonNull String toolCallId,
    @NonNull ToolActivityStatus status,   // COMPLETED, FAILED
    @NonNull String summary               // human-readable result summary
) {}

public enum ToolActivityStatus { COMPLETED, FAILED }

// ── NoticeKind ──
public enum NoticeKind {
    COMPACTION("Context was compacted to fit the model window"),
    CONTEXT_EXHAUSTED("Context window is nearly full"),
    TOOL_LOOP_WARNING("Tool call loop guard triggered"),
    REPAIR_APPLIED("Turn repair applied"),
    BUFFER_OVERFLOW("Stream buffer overflow, oldest events dropped");

    private final String defaultMessage;
    NoticeKind(String defaultMessage) { this.defaultMessage = defaultMessage; }
    public String defaultMessage() { return defaultMessage; }
}

// ── CompactionContext / CompactionResult ──
public record CompactionContext(
    @NonNull String sessionId,
    @NonNull List<TurnMessage> messagesToCompact,  // oldest messages to summarize
    @NonNull ContextUsage currentUsage
) {}

public record CompactionResult(
    @NonNull String summary,       // the compacted summary text
    int messagesCompacted,          // number of original messages replaced
    int summaryTokenCount           // estimated tokens in summary
) {}

// ── ConversationFilter ──
public record SessionFilter(
    @Nullable Boolean favorite,
    @Nullable Boolean archived,
    @Nullable String origin,
    @Nullable String searchQuery,    // matches title
    int limit,                       // max results
    int offset
) {
    public static SessionFilter all() { return new SessionFilter(null, null, null, null, 50, 0); }
}

// ── ConversationSession ──
public record ConversationSession(
    @NonNull String sessionId,
    @Nullable String title,
    @Nullable String model,
    boolean favorite,
    boolean archived,
    @NonNull String origin,
    @Nullable Instant createdAt,
    @Nullable Instant updatedAt,
    @NonNull Map<String, String> metadata
) {}

// ── PendingMessage / ClaimedMessage ──
public record PendingMessage(
    @NonNull String messageId,
    @NonNull String sessionId,
    @NonNull String messageText,
    @NonNull Map<String, String> metadata,
    @NonNull Instant enqueuedAt
) {}

public record ClaimedMessage(
    @NonNull PendingMessage message,
    @NonNull String claimToken,
    @NonNull Instant claimedAt
) {}
```

### TurnEvent Sealed Hierarchy

```java
public sealed interface TurnEvent
    permits TurnEvent.Started, TurnEvent.Chunk, TurnEvent.ToolActivity,
            TurnEvent.SystemNotice, TurnEvent.QuestionPending,
            TurnEvent.Interrupt, TurnEvent.Completed, TurnEvent.Failed {

    @NonNull String turnId();
    @NonNull Instant timestamp();
    @Nullable ContextUsage contextUsage();

    record Started(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String interruptToken,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Chunk(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String text,
        boolean thinking,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record ToolActivity(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull ToolActivityStatus status,
        @NonNull String summary,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record SystemNotice(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull NoticeKind kind,
        @NonNull String message,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record QuestionPending(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String questionText,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Interrupt(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String reason,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Completed(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull TurnDiagnostic diagnostic,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Failed(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull TurnDiagnostic diagnostic,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}
}
```

### TurnLoop Facade Interface

```java
public interface TurnLoop extends AutoCloseable {
    // ── Turn execution ──
    TurnResult send(@NonNull TurnRequest request);
    Flux<TurnEvent> stream(@NonNull TurnRequest request);

    // ── Question-user loop ──
    TurnResult answer(@NonNull String sessionId, @NonNull String answer);

    // ── Queue ──
    PendingMessage enqueue(@NonNull TurnRequest request);
    @Nullable ClaimedMessage claim(@NonNull String sessionId);
    void ack(@NonNull String claimToken);
    void release(@NonNull String claimToken);

    // ── Session management ──
    void clearSession(@NonNull String sessionId);

    // ── Interrupt ──
    boolean interrupt(@NonNull String turnId, @NonNull String reason);

    // ── Accessors ──
    ToolSubinterface tools();
    MemorySubinterface memory();
    SessionSubinterface sessions();

    // ── Lifecycle ──
    @Override void close();

    // ── Sub-interfaces ──
    interface ToolSubinterface {
        void register(@NonNull ToolCallback tool);
        void registerToolBean(@NonNull Object bean);
        void unregister(@NonNull String toolName);
        List<ToolDefinition> definitions();
    }

    interface MemorySubinterface {
        List<TurnMessage> history(@NonNull String sessionId);
        void clear(@NonNull String sessionId);
        ContextUsage contextUsage(@NonNull String sessionId);
    }

    interface SessionSubinterface {
        List<ConversationSession> list(@NonNull SessionFilter filter);
        @Nullable ConversationSession get(@NonNull String sessionId);
        void updateTitle(@NonNull String sessionId, @NonNull String title);
        void setFavorite(@NonNull String sessionId, boolean favorite);
        void setArchived(@NonNull String sessionId, boolean archived);
        void delete(@NonNull String sessionId);
    }
}
```

### TurnLoopBuilder

```java
public final class TurnLoopBuilder {
    public static TurnLoopBuilder create(@NonNull ApplicationContext context);

    public TurnLoopBuilder model(@NonNull String alias, @NonNull ModelConfig config);
    public TurnLoopBuilder dataRoot(@NonNull Path dataRoot);
    public TurnLoopBuilder defaultSystemPrompt(@NonNull String prompt);
    public TurnLoopBuilder toolRegistry(@NonNull ToolRegistry registry);
    public TurnLoopBuilder toolAccessPolicy(@NonNull ToolAccessPolicy policy);
    public TurnLoopBuilder toolResultSummarizer(@NonNull ToolResultSummarizer summarizer);
    public TurnLoopBuilder interactionHandler(@NonNull InteractionHandler handler);
    public TurnLoopBuilder interactionStore(@NonNull InteractionStore store);
    public TurnLoopBuilder conversationMemory(@NonNull ConversationMemory memory);
    public TurnLoopBuilder sessionStore(@NonNull SessionStore store);
    public TurnLoopBuilder tokenEstimator(@NonNull TokenEstimator estimator);
    public TurnLoopBuilder compactionStrategy(@NonNull CompactionStrategy strategy);
    public TurnLoopBuilder completionDetector(@NonNull CompletionDetector detector);
    public TurnLoopBuilder repairStrategy(@NonNull RepairStrategy strategy);
    public TurnLoopBuilder turnGate(@NonNull TurnGate gate);
    public TurnLoopBuilder messageQueue(@NonNull MessageQueue queue);
    public TurnLoopBuilder auditWriter(@NonNull AuditWriter writer);
    public TurnLoopBuilder promptContributor(@NonNull PromptContributor contributor);
    public TurnLoopBuilder modelCapabilityProvider(@NonNull ModelCapabilityProvider provider);
    public TurnLoopBuilder thinkingExtractor(@NonNull ThinkingExtractor extractor);
    public TurnLoopBuilder turnLifecycleListener(@NonNull TurnLifecycleListener listener);
    public TurnLoopBuilder objectMapper(@NonNull ObjectMapper objectMapper);

    public TurnLoop build();
}
```

### SPI Interface Signatures

```java
// ── ToolCallback (framework-owned, not Spring AI) ──
public interface ToolCallback {
    @NonNull ToolDefinition getToolDefinition();
    @NonNull String call(@NonNull String toolInput);  // JSON string → JSON string
}

public record ToolDefinition(@NonNull String name, @NonNull String description, @NonNull String inputSchema) {}

// ── ToolRegistry ──
public interface ToolRegistry {
    void register(@NonNull ToolCallback tool);
    void registerFrameworkTool(@NonNull ToolCallback tool);  // hidden from definitions()
    void registerToolBean(@NonNull Object bean);             // scans @Tool annotations
    void unregister(@NonNull String toolName);
    @NonNull List<ToolDefinition> definitions();             // public tools only
    @NonNull List<ToolCallback> resolveForTurn(@NonNull TurnContext context);
}

// ── ToolAccessPolicy ──
@FunctionalInterface
public interface ToolAccessPolicy {
    @NonNull List<ToolCallback> filterTools(
        @NonNull List<ToolCallback> availableTools,
        @NonNull TurnContext context
    );

    static ToolAccessPolicy allowAll() { return (tools, ctx) -> tools; }
    static ToolAccessPolicy allowList(@NonNull Set<String> toolNames) {
        return (tools, ctx) -> tools.stream()
            .filter(t -> toolNames.contains(t.getToolDefinition().name()))
            .toList();
    }
    static ToolAccessPolicy blockList(@NonNull Set<String> toolNames) {
        return (tools, ctx) -> tools.stream()
            .filter(t -> !toolNames.contains(t.getToolDefinition().name()))
            .toList();
    }
}

// ── ToolResultSummarizer ──
public interface ToolResultSummarizer {
    @NonNull SummarizeResult summarize(@NonNull ToolExecution execution);

    record ToolExecution(
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String arguments,
        @NonNull String rawResult
    ) {}

    record SummarizeResult(@NonNull String summary, boolean truncated) {}
}

// ── InteractionHandler ──
public interface InteractionHandler {
    @NonNull AnswerResult onQuestionsAsked(@NonNull QuestionPendingContext context);

    static InteractionHandler async() {
        // Returns a handler that notifies asynchronously without blocking the turn.
        // The turn is suspended; the embedding app must call TurnLoop.answer() to resume.
        throw new UnsupportedOperationException("async() requires a notification transport");
    }
}

public record QuestionPendingContext(
    @NonNull String sessionId,
    @NonNull String turnId,
    @NonNull List<String> questions
) {}

public sealed interface AnswerResult
    permits AnswerResult.Continue, AnswerResult.Abort,
            AnswerResult.SingleMessage, AnswerResult.MultiMessage {

    record Continue() implements AnswerResult {}
    record Abort(@NonNull String reason) implements AnswerResult {}
    record SingleMessage(@NonNull String message) implements AnswerResult {}
    record MultiMessage(@NonNull List<String> messages) implements AnswerResult {}
}

// ── InteractionStore ──
public interface InteractionStore {
    void save(@NonNull String sessionId, @NonNull QuestionPendingContext context);
    @Nullable QuestionPendingContext load(@NonNull String sessionId);
    void delete(@NonNull String sessionId);
}

// ── ConversationMemory ──
public interface ConversationMemory {
    @NonNull List<TurnMessage> getMessages(@NonNull String sessionId);  // insertion order, oldest first
    void addMessages(@NonNull String sessionId, @NonNull List<TurnMessage> messages);
    void clear(@NonNull String sessionId);
}

// ── SessionStore ──
public interface SessionStore {
    void save(@NonNull ConversationSession session);
    @Nullable ConversationSession findById(@NonNull String sessionId);
    @NonNull List<ConversationSession> list(@NonNull SessionFilter filter);
    void delete(@NonNull String sessionId);
    void updateTitle(@NonNull String sessionId, @NonNull String title);
    void setFavorite(@NonNull String sessionId, boolean favorite);
    void setArchived(@NonNull String sessionId, boolean archived);
    void updateModel(@NonNull String sessionId, @NonNull String model);
}

// ── TokenEstimator ──
public interface TokenEstimator {
    int estimateTokens(@NonNull String text);
    int estimateTokens(@NonNull TurnMessage message);
}

// ── CompactionStrategy ──
public interface CompactionStrategy {
    @NonNull CompactionResult compact(@NonNull CompactionContext context);
}

// ── CompletionDetector ──
@FunctionalInterface
public interface CompletionDetector {
    boolean isComplete(@NonNull TurnContext context);
    // context provides: last AssistantMessage text, tool calls, toolRoundCount, etc.
}

// ── RepairStrategy ──
public interface RepairStrategy {
    @NonNull List<TurnMessage> repair(@NonNull TurnContext context, @NonNull RepairReason reason);

    enum RepairReason {
        EMPTY_RESPONSE,
        TOOL_LOOP_EXCEEDED,
        COMPLETION_NOT_DETECTED,
        CUSTOM
    }
}

// ── TurnGate ──
public interface TurnGate {
    @NonNull AcquireResult acquire(@NonNull String sessionId, @NonNull TurnGateMode mode);
    void release(@NonNull String sessionId);
    void transitionToAwaitingAnswer(@NonNull String sessionId);
    void drain(long timeoutMs);
    @NonNull GateState state(@NonNull String sessionId);

    sealed interface AcquireResult
        permits AcquireResult.Acquired, AcquireResult.Rejected, AcquireResult.Queued {

        record Acquired(@NonNull String lockToken) implements AcquireResult {}
        record Rejected(@NonNull String reason) implements AcquireResult {}
        record Queued(@NonNull String claimToken) implements AcquireResult {}
    }

    enum TurnGateMode { SEND, STREAM, ANSWER }
    enum GateState { IDLE, EXECUTING, AWAITING_ANSWER }
}

// ── MessageQueue ──
public interface MessageQueue {
    @NonNull PendingMessage enqueue(@NonNull String sessionId, @NonNull String messageText,
                                     @NonNull Map<String, String> metadata);
    @NonNull List<PendingMessage> list(@NonNull String sessionId);
    @Nullable ClaimedMessage claim(@NonNull String sessionId);
    void ack(@NonNull String claimToken);
    void release(@NonNull String claimToken);
    @NonNull List<ClaimedMessage> recoverStaleClaims(@NonNull Duration staleAge);
    void deleteBySessionId(@NonNull String sessionId);
}

// ── MessageQueueStore (persistence SPI) ──
public interface MessageQueueStore {
    void insert(@NonNull PendingMessage message);
    @NonNull List<PendingMessage> findBySessionId(@NonNull String sessionId);
    @Nullable PendingMessage findById(@NonNull String messageId);
    boolean claimAtomic(@NonNull String messageId, @NonNull String claimToken, @NonNull Instant claimedAt);
    void deleteByMessageId(@NonNull String messageId);
    void deleteBySessionId(@NonNull String sessionId);
    @NonNull List<PendingMessage> findStaleClaims(@NonNull Instant staleThreshold);
}

// ── AuditWriter ──
public interface AuditWriter {
    void recordUserMessage(@NonNull String sessionId, @NonNull TurnMessage message);
    void recordAssistantMessage(@NonNull String sessionId, @NonNull TurnMessage message);
    void recordToolExecution(@NonNull String sessionId, @NonNull ToolExecutionRecord record);
    void recordContextUsage(@NonNull String sessionId, @NonNull ContextUsage usage, @NonNull String model);
    void recordError(@NonNull String sessionId, @NonNull TurnError error);

    record ToolExecutionRecord(
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String arguments,
        @NonNull String resultSummary,
        boolean success,
        long durationMs
    ) {}

    record TurnError(
        @NonNull String summary,
        @Nullable String stackTrace,
        @Nullable String model
    ) {}
}

// ── PromptContributor ──
@FunctionalInterface
public interface PromptContributor {
    @NonNull String contribute(@NonNull TurnContext context);
}

// ── ModelCapability / ModelCapabilityProvider ──
public enum ModelCapability { STREAMING, TOOL_CALLING, VISION }

public interface ModelCapabilityProvider {
    @NonNull Set<ModelCapability> capabilities(@NonNull String modelAlias);
}

// ── TurnLoopMetrics ──
public interface TurnLoopMetrics {
    void recordTurnDuration(@NonNull String model, @NonNull String outcome, long durationMs);
    void recordToolCall(@NonNull String toolName, @NonNull String status);
    void setActiveTurns(long count);
    void setQueueDepth(@NonNull String sessionId, long depth);
}

// ── TurnLifecycleListener ──
@FunctionalInterface
public interface TurnLifecycleListener {
    void onTurnCompleted(@NonNull TurnResult result);
}

// ── ThinkingExtractor ──
public interface ThinkingExtractor {
    @NonNull ThinkingResult extract(@NonNull AssistantMessageContent content);

    record AssistantMessageContent(
        @NonNull String rawText,
        @Nullable String thinkingMetadata,      // Ollama "thinking" metadata
        @Nullable String reasoningContent,       // DeepSeek reasoningContent
        @NonNull Map<String, Object> metadata    // full metadata map
    ) {}

    record ThinkingResult(
        @NonNull String visibleText,    // text without thinking
        @NonNull String thinkingText    // extracted thinking, or empty string
    ) {}
}
```

### Framework-Internal Interfaces

```java
// ── ModelRouter (framework-internal; maps Spring AI ChatModel) ──
interface ModelRouter {
    @NonNull ChatModel springAiChatModel(@NonNull String modelAlias);
    @NonNull String remoteModelName(@NonNull String modelAlias);
    @NonNull ToolCallingChatOptions toolCallingOptions(@NonNull String modelAlias,
                                                        @NonNull List<ToolCallback> tools);
    @NonNull ToolCallingChatOptions basicChatOptions(@NonNull String modelAlias);
    boolean supportsStreaming(@NonNull String modelAlias);
}

// ── TurnExecutor ──
interface TurnExecutor extends AutoCloseable {
    <T> CompletableFuture<T> submit(@NonNull String sessionId, @NonNull Supplier<T> task);
    @Override void close();
}

// ── ActiveTurnRegistry ──
interface ActiveTurnRegistry {
    @NonNull ActiveTurn register(@NonNull String turnId);
    @Nullable ActiveTurn get(@NonNull String turnId);
    void remove(@NonNull String turnId);
    @NonNull List<ActiveTurn> activeTurns();

    interface ActiveTurn {
        @NonNull String turnId();
        void phase(@NonNull TurnPhase phase);
        @NonNull TurnPhase phase();
        @NonNull Optional<String> pollInterrupt();
        void queueInterrupt(@NonNull String reason);
    }
}
```

### ToolLoopGuard

```java
public final class ToolLoopGuard {
    public ToolLoopGuard(int identicalCallLimit, int errorWindowSize, int errorWindowLimit);
    public ToolLoopGuard();  // defaults: 5, 8, 5

    public void recordToolCalls(@NonNull List<ToolCallIdentity> calls) throws ToolException.LoopExceeded;
    public void recordToolResponses(@NonNull ToolExecutionResult result) throws ToolException.LoopExceeded;

    public record ToolCallIdentity(@NonNull String toolName, @NonNull String arguments) {}
}
```

### Migration System

```java
public interface Migration {
    int version();                          // e.g., 1, 2, 3
    void migrate(@NonNull DataSource dataSource) throws Exception;
}

public final class MigrationRunner {
    public MigrationRunner(@NonNull DataSource dataSource);
    public void apply(@NonNull List<Migration> migrations);
    public int currentVersion();
}
```

## Appendix B: Database Schema (DDL)

```sql
-- Framework version tracking
CREATE TABLE IF NOT EXISTS turnloop_schema_version (
    version INTEGER NOT NULL,
    applied_at TEXT NOT NULL DEFAULT (datetime('now')),
    description TEXT
);

-- Conversation memory: stores all chat messages
CREATE TABLE IF NOT EXISTS turnloop_conversation_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,                -- 'system', 'user', 'assistant', 'tool'
    content TEXT NOT NULL,
    tool_name TEXT,
    tool_call_id TEXT,
    metadata_json TEXT,                -- JSON object for extension data
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_memory_session ON turnloop_conversation_memory(session_id, id);
CREATE INDEX IF NOT EXISTS idx_memory_session_role ON turnloop_conversation_memory(session_id, role);

-- Session metadata
CREATE TABLE IF NOT EXISTS turnloop_session_metadata (
    session_id TEXT PRIMARY KEY,
    title TEXT,
    model TEXT,
    favorite INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,
    origin TEXT NOT NULL DEFAULT 'turnloop',
    metadata_json TEXT,                -- JSON object for extension data
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_session_origin ON turnloop_session_metadata(origin);
CREATE INDEX IF NOT EXISTS idx_session_favorite ON turnloop_session_metadata(favorite);
CREATE INDEX IF NOT EXISTS idx_session_archived ON turnloop_session_metadata(archived);

-- Pending message queue
CREATE TABLE IF NOT EXISTS turnloop_pending_messages (
    message_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    message_text TEXT NOT NULL,
    metadata_json TEXT,
    claim_token TEXT,
    claimed_at TEXT,
    enqueued_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_pending_session ON turnloop_pending_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_pending_claim ON turnloop_pending_messages(claim_token);
CREATE INDEX IF NOT EXISTS idx_pending_stale ON turnloop_pending_messages(claimed_at);

-- Audit log
CREATE TABLE IF NOT EXISTS turnloop_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    event_type TEXT NOT NULL,          -- 'USER_MESSAGE', 'ASSISTANT_MESSAGE', 'TOOL_EXECUTION', 'CONTEXT_USAGE', 'ERROR'
    payload_json TEXT NOT NULL,        -- JSON object with event-specific fields
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_audit_session ON turnloop_audit_log(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_type ON turnloop_audit_log(event_type, created_at);
```

## Appendix C: Phase Gate Artifact Inventories

### Phase 0 Gate Inventory
- `turnloop-core/pom.xml` exists with correct GAV, dependencies, and Maven Enforcer `bannedDependencies` rule
- `turnloop-autoconfigure/pom.xml` exists depending on `turnloop-core`, `spring-boot-autoconfigure`, `sqlite-jdbc`
- Root `pom.xml` includes both modules
- `mvn clean verify` passes on unchanged Magenta codebase

### Phase 1 Gate Inventory
- `api/web/` directory does not exist
- `src/main/resources/static/` directory does not exist
- POM has no `spring-boot-starter-web`, `spring-boot-starter-webmvc-test`, `simplypages`, `webjars-locator-core`, `htmx.org`
- `ChatStreamEvent` renamed to `TurnEvent` (temporary location: `ai/chat/model/`)
- All preserved non-web tests pass (exact list TBD)
- `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0` starts without `TomcatWebServer` in logs

### Phase 2 Gate Inventory
- `InteractionHandler` interface exists in `ai/chat/service/turn/`
- `PromptContributor` interface exists in `ai/chat/service/turn/`
- `CompletionDetector` interface exists in `ai/chat/service/turn/`
- `RepairStrategy` interface exists in `ai/chat/service/turn/`
- `DefaultTurnEngine` has builder or package-private factory (not 10+ parameter constructor)
- `ChatServiceTest` passes
- grep for `@Autowired(required = false)` returns zero results
- grep for `import.*PlanService` in `ChatService.java` returns zero results
- grep for `import.*TaskService` in `ChatService.java` returns zero results

### Phase 3 Gate Inventory
- None of the deleted packages exist on disk
- `AiConfig` has single canonical constructor (no `agents`, `defaultAgent`, `planningModel`, `compactionModel`, `unsafeAllowWildcardShellCommands`)
- `AiUserConfigConfiguration` has no agent resolution logic
- grep from retained `src/main/java/` for deleted package references returns zero results
- All remaining tests pass
- Context starts

### Phase 4 Gate Inventory
- All types from Appendix A exist as `.java` files in `turnloop-core/src/main/java/io/mindspice/turnloop/`
- All SPI interfaces from Appendix A exist with complete method signatures
- `TurnEvent` sealed hierarchy with all 8 subtypes exists
- `TurnRequest`, `TurnResult` records exist
- `TurnLoopBuilder` class exists with all builder methods
- `SerialTurnGate` exists with timeout and drain semantics
- `DefaultToolRegistry` exists with two-stage registration and collision detection
- `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore` exist
- `SqliteAuditWriter` exists
- `Migration` interface, `MigrationRunner` class, and `schema.sql` exist
- `MarkdownRenderer` exists with optional dependency graceful degradation
- `TurnEventRenderer` exists with `json()` and `sse()` methods
- `ThinkingExtractor` interface and `CompositeThinkingExtractor` default exist
- `ToolLoopGuard` class exists
- `ToolResultSummarizer` interface and default generic implementation exist
- `TurnExecutor` interface and virtual-thread implementation exist
- `ActiveTurnRegistry` exists
- Context starts with framework beans only
- Unit tests pass

### Phase 5 Gate Inventory
- `TurnLoop` interface exists with all facade methods
- `DefaultTurnLoop` exists implementing `TurnLoop`
- `TurnLoopBuilder.build()` returns a working `TurnLoop`
- `TurnLoopAutoConfiguration` exists
- `TurnLoopProperties` exists with `@ConfigurationProperties(prefix = "turnloop")`
- `DataSource` auto-provisioning bean exists (conditional on missing `DataSource`)
- `StreamLifecycle` exists in autoconfigure
- `TurnLoopHealthIndicator` exists
- `TurnLoopMetrics` Micrometer default exists
- `close()` lifecycle implementation exists with drain + cancel + force-release
- Integration tests pass with real Ollama
- Lifecycle tests pass (close drains, close cancels questions, close releases resources)

### Phase 6 Gate Inventory
- `mvn clean verify` passes (all tests, including autoconfigure tests)
- JaCoCo report shows >80% line coverage on `io.mindspice.turnloop` packages
- `mvn javadoc:jar` succeeds with zero warnings
- grep for `org.springframework.ai` in `turnloop-core/src/main/java/io/mindspice/turnloop/` returns zero results
- grep for `ThreadLocal` in `turnloop-core/src/main/java/` returns zero results
- PMD dead-code scan confirms zero references from retained code to deleted Magenta packages
- All markdown docs exist (README.md + 9 docs files)
- README.md SSE controller example compiles and handles disconnect, backpressure, error recovery

### Phase 7 Gate Inventory
- Magenta `pom.xml` depends on `turnloop-core` (test scope or compile scope TBD)
- Magenta has app-specific SPI implementations in its own packages
- No extracted code remains in Magenta (verified by class absence, not import absence)
- Magenta context starts
- Magenta's existing non-web tests pass

## Sign-off

This corrected handoff represents the unified, red-team-hardened, implementation-ready design for extracting the Magenta chat turn-loop into the `turnloop-core` reusable framework. It includes complete type definitions, all SPI interface signatures, database DDL, the full `TurnEvent` sealed hierarchy, the `TurnLoop` facade API, the `TurnLoopBuilder` API, the gate state machine with timeout and drain semantics, phase gate artifact inventories, and 20 concrete validation scenarios.

All 26 red-team findings are resolved as design constraints. All 11 constraints have verifiable acceptance criteria. All 8 open questions are explicit and none block implementation. The handoff is ready for user review and acceptance as the design contract for implementation.
