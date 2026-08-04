# Internal Domain Abstraction Designer — Brainstorm

## Prompt

Refactor Magenta into a reusable core LLM turn-loop framework. Design the generalized public API surface for: (1) tool framework, (2) memory/session abstraction, (3) turn-loop mechanics, (4) question-user loop and queueing, (5) event streaming. Propose the new artifact naming, facade API, module layout, configuration surface, and a phased plan outline. Stay exploratory.

## Source

- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java` (3009 lines) — the monolithic chat turn orchestrator
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/PromptContextAssembler.java` — system prompt composition in fixed provenance order
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/ToolAccessPolicy.java` — mode-gated tool filtering
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TerminalTurnRepair.java` — empty-response, plan, execution repair control messages
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TurnContext.java` — mutable turn carrier
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TurnPhase.java` — explicit state machine phases
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ContextManagementAdvisor.java` — Spring AI `CallAdvisor`/`StreamAdvisor`; compaction, trimming, token estimation
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ToolLoopGuard.java` — runaway tool detection (identical calls, error rate)
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatModelRouter.java` — multi-provider ChatModel cache (Ollama, OpenAI-compatible, DeepSeek)
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatPendingMessageService.java` — enqueue/claim/ack/release queue
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ResolvedChatRequest.java` — immutable resolved turn input
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/ChatToolRegistry.java` — ToolCallback aggregation from Spring beans + ToolCallbackProviders
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/ToolArgumentCoercionModule.java` — Jackson module that coerces scalars into single-element collections for tool arguments
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/ToolTranscriptService.java` — tool result → SystemMessage transcript with structured entries, truncation, rendering
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/InteractionQuestionTools.java` — `ask_user_questions` Spring AI `@Tool`
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/RepositoryBackedChatMemory.java` — Spring AI `ChatMemory` backed by `ChatMemoryRepository`
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatMemoryRepository.java` — SQLite message persistence (implements Spring AI `ChatMemoryRepository`)
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatSessionMetadataRepository.java` — SQLite session metadata (model, title, origin, surface, project)
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatPendingMessageRepository.java` — SQLite pending message queue with claim/ack/release
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEvent.java` — sealed hierarchy of SSE-typed events (Start, Chunk, Tool, SystemNotice, Interrupt, Context, Done, Error, OutputsChanged)
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatMessage.java` — rendered chat message record
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatResponse.java` — sealed response hierarchy (MsgResponse, CmdResponse)
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatToolActivity.java` — tool execution activity record
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ContextUsage.java` — token usage record
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatRequest.java` — sealed request hierarchy
- `src/main/java/io/mindspice/magenta2/ai/chat/model/PendingChatMessage.java` / `ClaimedPendingChatMessage.java` — queue records
- `src/main/java/io/mindspice/magenta2/api/web/SseStreamLifecycle.java` — SSE transport lifecycle (SseEmitter creation, SubscriptionGuard, callbacks)
- `pom.xml` — Spring Boot 4.0.7, Spring AI 2.0.0, Java 25, sqlite-jdbc, simplypages, Ollama/OpenAI/DeepSeek starters

## Findings

### 1. Current code structure reveals clear domain boundaries

The existing code already separates concerns into distinct packages (`tool/`, `repository/`, `model/`, `service/turn/`). The key finding is that `ChatService` is a massive 3009-line monolith that directly depends on ~30 collaborators and embeds the tool-loop state machine (`ToolLoopState`), repair logic, measurement instrumentation, and all five turn-phase handler methods. The extracted turn components (`PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`, `TurnAuditWriter`) are already clean abstractions — but `ChatService` still contains the turn orchestration state machine directly.

### 2. The tool framework uses Spring AI's native ToolCallback/ToolCallbackProvider

`ChatToolRegistry` is a thin wrapper that aggregates `ToolCallback` beans and `ToolCallbackProvider` beans into a name-indexed map. `ToolArgumentCoercionModule` is a Jackson `SimpleModule` registered via service loader that coerces scalar values into single-element collections. `ToolTranscriptService` produces structured `SystemMessage` entries with JSON payloads that carry tool results, summaries, and display details. This is a solid pattern but currently only works through auto-discovery (Spring bean scanning + Jackson service loader).

### 3. Memory is tightly coupled to SQLite via Spring AI's ChatMemory/ChatMemoryRepository

`RepositoryBackedChatMemory` implements Spring AI's `ChatMemory` and delegates to `ChatMemoryRepository`, which implements Spring AI's `ChatMemoryRepository` with hard SQLite SQL. `ChatSessionMetadataRepository` does session-level metadata (model, title, favorite, archived, origin, surface) with SQLite-specific schema management. `ContextManagementAdvisor` implements both Spring AI `CallAdvisor` and `StreamAdvisor` and owns compaction (summary-based), trimming, token estimation, and the tool-loop prompt builder.

### 4. The turn loop is a state machine in `ChatService`

The core loop has explicit `TurnPhase` states (PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS/REPAIR/FINALIZE → DONE). Each phase has a handler method on `ChatService` operating on a mutable `ToolLoopState` inner class. The loop handles: model invocation, tool-call evaluation, malformed argument repair, tool execution via `ToolCallingManager`, `ToolLoopGuard` checks, `ContextManagementAdvisor` checkpoints, plan/task completion detection, tool-use abort recovery, and finalization. The streaming path (`stream()`) wraps the tool loop in a `Flux.create` sink with per-conversation semaphore locking.

### 5. Question-user loop is plan/task-mode specific

`InteractionQuestionTools` is a `@Component` that registers `ask_user_questions` as a Spring AI `@Tool`. It saves questions to `PlanService`/`TaskService` structured state. The pending-message queue (`ChatPendingMessageService` + `ChatPendingMessageRepository`) is a separate mechanism for queueing user messages when the model is busy — it uses claim/ack/release with stale-claim recovery. These are two separate question mechanisms: one for structured plan questions, one for raw message queueing.

### 6. Event streaming is SSE-transport specific

`ChatStreamEvent` is a sealed hierarchy of 9 event types that carry rendered HTML and `ChatPlanState` — both UI concerns. `SseStreamLifecycle` is a Spring MVC `SseEmitter` utility. The `ChatController` wires the SSE emitter to the `ChatService.stream()` Flux. Events are constructed inline in `ChatService` (no emitter abstraction exists; the streaming path builds `ChatMessage` objects that the controller's `doOnNext` translates to SSE events).

### 7. App-specific coupling is deep

`ChatService` has direct references to: `PlanService`, `TaskService`, `AgentJobService`, `ConversationTurnCoordinator`, `OrchestrationTaskContext`, `WorkspaceDirectoryService`, `AgentContextService`, `AgentSkillActivationService`, `RunMeasurementRepository`, `AuditService`, `RuntimeSettingsService`, `AgentsMdResolver`, `WorkTypeProfileService`, `AgentSkillPromptCatalogAssembler`. The turn-loop models (`ChatStreamEvent`, `ChatResponse`, `TurnContext`) carry `ChatPlanState`, `ChatSessionSurface`, `ChatSessionOrigin`. These must be stripped or generalized.

### 8. Configuration is scattered

Model configuration lives in `AiConfig` (YAML-backed user config). Runtime settings in `RuntimeSettingsService`. The framework needs a single configuration surface: `CoreConfig` with properties-driven defaults plus a programmatic builder for code-first embedding.

---

## Options

### New artifact naming and package structure

**Option A: `io.mindspice.core` (minimal rename)**
- Artifact: `mindspice-core`
- Packages: `io.mindspice.core.turn.*`, `io.mindspice.core.tool.*`, `io.mindspice.core.memory.*`, `io.mindspice.core.queue.*`, `io.mindspice.core.event.*`, `io.mindspice.core.model.*`, `io.mindspice.core.facade.*`
- Pro: Minimal friction, close to current naming
- Con: "core" is generic; doesn't communicate LLM-framework identity

**Option B: `io.mindspice.loom` (domain-communicative name)**
- Artifact: `mindspice-loom`
- Packages: `io.mindspice.loom.turn.*`, `io.mindspice.loom.tool.*`, `io.mindspice.loom.memory.*`, `io.mindspice.loom.queue.*`, `io.mindspice.loom.event.*`, `io.mindspice.loom.model.*`, `io.mindspice.loom.facade.*`
- Pro: Distinctive, communicates "weaving turns," memorable
- Con: New name with no existing brand recognition

**Recommendation: Option B (`loom`).** A standalone framework deserves a name that won't collide with Java standard library (`java.lang` subpackages, Spring `core`). The name "Loom" communicates turn-weaving and is short/searchable.

### Facade API shape

**Option A: Single `LoomCore` facade bean with all methods**
```java
public interface LoomCore {
    // Send a message, get streaming events
    Flux<TurnEvent> stream(TurnRequest request);
    // Send a message, block for result
    TurnResponse send(TurnRequest request);
    // Answer a pending question
    void answer(String sessionId, String answer);
    // Queue a message
    PendingMessage enqueue(String sessionId, String message);
    // Claim oldest pending
    Optional<ClaimedMessage> claim(String sessionId);
    // Acknowledge
    void ack(String sessionId, String messageId, String claimToken);
    // Session management
    void clearSession(String sessionId);
    List<SessionInfo> listSessions();
    ContextUsage usage(String sessionId);
    // Lifecycle
    void close();
}
```
- Pro: Simple, single entry point, discoverable
- Con: Becomes a God-object if the surface grows

**Option B: Segmented facade with sub-interfaces**
```java
public interface LoomCore {
    TurnRunner turns();
    MessageQueue queue();
    SessionManager sessions();
    ToolRegistry tools();
    void close();
}
```
- Pro: Domain-grouped, extensible without God-object growth
- Con: Two-level navigation; more types to learn

**Recommendation: Hybrid.** `LoomCore` is the top-level facade with direct methods for the 80% case (`stream`, `send`, `answer`, `enqueue`, `claim`, `ack`) plus accessor methods returning focused sub-interfaces (`tools()`, `memory()`, `sessions()`) for advanced configuration. The sub-interfaces surface builder/configuration methods, not runtime operations.

---

## Detailed API Designs

### Area 1: Tool Framework

**Current state:** `ChatToolRegistry` aggregates Spring beans. `ToolArgumentCoercionModule` is a Jackson module. `ToolTranscriptService` wraps results. `ToolAccessPolicy` does mode-gating.

**Generalized contract:**

```java
// ── Registration: tool callbacks are the unit of registration ──
public interface ToolRegistry {
    // Register a single tool callback (the canonical form)
    ToolRegistration register(ToolCallback callback);

    // Register a provider that can supply multiple callbacks
    List<ToolRegistration> register(ToolCallbackProvider provider);

    // Register a plain Java object with @Tool-annotated methods
    List<ToolRegistration> registerToolBean(Object toolBean);

    // Unregister
    void unregister(String toolName);

    // Query
    List<ToolDefinition> definitions();
    Optional<ToolDefinition> definition(String toolName);

    // Resolve for a turn (applies mode/access policy)
    List<ToolCallback> resolveForTurn(TurnContext context);
}

public record ToolDefinition(
    String name,
    String description,
    String inputSchema,    // JSON Schema string
    boolean isFramework    // true for built-in tools like ask_user
) {}

public record ToolRegistration(
    String name,
    ToolCallback callback,
    Instant registeredAt
) {}

// ── Argument coercion: pluggable strategy ──
public interface ToolArgumentCoercer {
    // Coerce raw argument JSON before deserialization
    String coerce(String toolName, String rawArgumentsJson);

    // Factory for the Jackson module approach
    static ToolArgumentCoercer jacksonModule(com.fasterxml.jackson.databind.Module module) { ... }
    // Identity (no coercion)
    static ToolArgumentCoercer identity() { ... }
}

// ── Tool activity: transport-agnostic event ──
public record ToolActivity(
    String id,
    String toolCallId,
    String toolName,
    String status,          // "completed" | "error"
    Instant createdAt,
    String summary,         // one-line summary for history
    String detail,          // full result for inspection
    boolean truncated       // true if result exceeded storage limit
) {}

// ── Tool transcript: how results enter the model context ──
public interface ToolTranscriptStrategy {
    // Turn a tool execution result into messages for the model
    List<Message> transcriptMessages(
        String toolCallId,
        String toolName,
        String argumentsJson,
        String resultText
    );

    // Compact large results after N user turns
    List<Message> compactExpired(List<Message> messages);

    // Render for display/history
    String renderForDisplay(Message transcriptMessage);
}

// ── Tool access policy: which tools are available for a turn ──
public interface ToolAccessPolicy {
    // Given registered tools and a turn context, return allowed tools
    List<ToolCallback> filter(List<ToolCallback> registered, TurnContext context);

    // Built-in policies
    static ToolAccessPolicy allowAll() { ... }
    static ToolAccessPolicy allowList(Set<String> toolNames) { ... }
    static ToolAccessPolicy modeBased(
        Map<String, Set<String>> modeToTools,  // e.g. "plan" -> {file_read, ask_user, ...}
        Set<String> blockedInNormal            // tools blocked in normal mode
    ) { ... }
}

// ── Framework-owned tools: question-user loop ──
// The framework registers this internally; embedding apps can override behavior
public interface UserQuestionTool {
    // Called when the model invokes ask_user_questions
    QuestionResult askQuestions(String sessionId, List<String> questions);

    record QuestionResult(List<String> acceptedQuestions) {}
}

// ── Exception types ──
public class ToolNotFoundException extends LoomException { ... }
public class ToolExecutionAbortedException extends LoomException {
    List<String> recentErrors();  // for diagnostics
}
public class ToolArgumentException extends LoomException {
    String toolName();
    String rawArguments();
    String parserMessage();
}
```

**Key design decisions:**
1. Tools are always `ToolCallback` — the Spring AI contract is the native type. No wrapping, no custom annotation framework.
2. Argument coercion is a pluggable `ToolArgumentCoercer` registered on the `ToolRegistry`. The Jackson module approach ships as a default.
3. `ToolAccessPolicy` is a first-class interface so embedding apps can define their own mode-gating without forking the framework.
4. The `ToolTranscriptStrategy` is pluggable: SQLite-backed apps may want different truncation than in-memory apps.
5. `UserQuestionTool` abstracts the question-user loop's framework tool into a replaceable component.

### Area 2: Memory / Session Abstraction

**Current state:** `RepositoryBackedChatMemory` delegates to `ChatMemoryRepository` (SQLite-specific). `ChatSessionMetadataRepository` does schema management and session metadata CRUD. `ContextManagementAdvisor` owns compaction and token estimation.

**Generalized contract:**

```java
// ── Core memory interface (replaces Spring AI ChatMemory for the framework) ──
public interface ConversationMemory {
    // Append messages to a conversation
    void append(String sessionId, List<Message> messages);

    // Get all messages for a conversation (for context assembly)
    List<Message> get(String sessionId);

    // Get messages within a token budget (for prompt assembly)
    WindowedMessages getWindowed(String sessionId, int maxTokens);

    // Clear a conversation
    void clear(String sessionId);

    // Check existence
    boolean exists(String sessionId);

    // List known session IDs
    List<String> sessionIds();
}

public record WindowedMessages(
    List<Message> messages,
    int estimatedTokens,
    boolean compacted      // true if compaction was applied
) {}

// ── Session metadata (separate from message storage) ──
public interface SessionStore {
    // Persist model selection for a session
    void saveModel(String sessionId, String model);

    // Retrieve model
    Optional<String> model(String sessionId);

    // Persist title
    void saveTitle(String sessionId, String title);
    Optional<String> title(String sessionId);

    // Favorites, archives, etc.
    void setFavorite(String sessionId, boolean favorite);
    boolean isFavorite(String sessionId);
    void setArchived(String sessionId, boolean archived);
    boolean isArchived(String sessionId);

    // Timestamps
    Optional<Instant> updatedAt(String sessionId);

    // List sessions (with optional filtering)
    List<SessionInfo> list(SessionFilter filter);

    // Delete
    void delete(String sessionId);
}

public record SessionInfo(
    String sessionId,
    String title,
    String model,
    boolean favorite,
    boolean archived,
    Instant updatedAt,
    Map<String, String> attributes   // extension point
) {}

public record SessionFilter(
    boolean includeArchived,
    boolean sortByFavoritesFirst,
    int maxResults
) {
    public static SessionFilter defaults() { ... }
}

// ── Context management: compaction and token budgeting ──
public interface ContextManager {
    // Prepare a prompt from current instructions + stored messages
    PreparedPrompt prepare(
        String sessionId,
        List<Message> currentInstructions,
        String model,
        PrepareOptions options
    );

    // Maintain stored context (compaction, trimming)
    ContextMaintenance maintain(String sessionId, String model);

    // Estimate token usage
    ContextUsage estimate(String sessionId, String model);

    // Token estimation (used internally, exposed for custom strategies)
    int estimateTokens(List<Message> messages);

    record PreparedPrompt(
        List<Message> messages,
        ContextUsage usage,
        boolean compacted
    ) {}

    record ContextMaintenance(
        ContextUsage usage,
        boolean compacted,
        boolean degraded,
        String degradationReason
    ) {}

    record PrepareOptions(
        boolean omitStoredMessages,
        boolean allowCompaction,
        int minTailMessages
    ) {
        public static PrepareOptions defaults() { ... }
    }
}

// ── Token counting strategy ──
public interface TokenEstimator {
    int estimate(String text);
    int estimate(List<Message> messages);   // default: render + count

    // Built-in: use Spring AI's TokenCountEstimator
    static TokenEstimator springAi(TokenCountEstimator estimator) { ... }
}

// ── Compaction strategy ──
public interface CompactionStrategy {
    // Summarize older messages into a compact form
    CompactionResult compact(
        List<Message> olderMessages,       // messages to summarize
        List<Message> previousSummaries,    // prior compaction summaries
        CompactionContext context
    );

    record CompactionResult(
        List<Message> compactedMessages,    // summary + tail
        String summaryText,
        boolean success
    ) {}

    record CompactionContext(
        String sessionId,
        String model,
        int maxTokens,
        ChatModel summaryModel             // a model used only for summarization
    ) {}
}

// ── SQLite default implementation ──
// The framework ships with:
//   SqliteConversationMemory implements ConversationMemory
//   SqliteSessionStore implements SessionStore
//   DefaultContextManager implements ContextManager
//     (uses TokenEstimator + CompactionStrategy + ConversationMemory + SessionStore)

// ── Pluggable store pattern ──
// Embedding apps wire their own:
public class LoomCoreBuilder {
    public LoomCoreBuilder conversationMemory(ConversationMemory memory) { ... }
    public LoomCoreBuilder sessionStore(SessionStore store) { ... }
    public LoomCoreBuilder tokenEstimator(TokenEstimator estimator) { ... }
    public LoomCoreBuilder compactionStrategy(CompactionStrategy strategy) { ... }
    public LoomCore build() { ... }
}
```

**Key design decisions:**
1. Split `ConversationMemory` (message storage) from `SessionStore` (metadata). Current code has `ChatMemoryRepository` and `ChatSessionMetadataRepository` as separate repos — the abstraction formalizes this.
2. `ContextManager` is a framework-internal orchestrator that uses `ConversationMemory` + `TokenEstimator` + `CompactionStrategy`. Embedding apps configure the strategy, not the orchestration.
3. `TokenEstimator` and `CompactionStrategy` are the true extension points. Apps that want Redis-backed memory replace `ConversationMemory` and `SessionStore`. Apps that want a different compaction algorithm replace `CompactionStrategy`.
4. The `SessionFilter` record replaces the ad-hoc SQL filtering currently in `ChatSessionMetadataRepository`.
5. `SessionInfo.attributes()` provides an extension point so embedding apps can stash custom metadata without subclassing.

### Area 3: Turn-Loop Mechanics

**Current state:** The turn loop is a `while`-`switch` state machine inside `ChatService.toolChat()` with `ToolLoopState` inner class. The streaming path wraps it in `Flux.create`. Phases are `INVOKE_MODEL`, `EVALUATE`, `EXECUTE_TOOLS`, `REPAIR`, `FINALIZE`.

**Generalized contract:**

```java
// ── Turn orchestrator: the framework's core engine ──
public interface TurnOrchestrator {
    // Execute a turn, streaming events
    Flux<TurnEvent> stream(TurnRequest request);

    // Execute a turn, blocking for result
    TurnResult execute(TurnRequest request);

    // Answer a pending user question (resumes a suspended turn)
    Flux<TurnEvent> answer(String sessionId, String answer);

    // Interrupt an active turn
    void interrupt(String sessionId, String interruptToken, String message);
}

// ── Turn request (immutable input) ──
public record TurnRequest(
    String sessionId,
    String userMessage,
    String model,             // optional; uses default if null
    TurnMode mode,            // NORMAL, PLAN, TASK, EXECUTE_PLAN, EXECUTE_TASK
    Map<String, Object> attributes   // extension: plan state, task run, etc.
) {
    public static TurnRequest of(String sessionId, String message) { ... }
}

public enum TurnMode {
    NORMAL,
    PLAN,           // model asks clarification questions
    TASK,           // model designs a task
    EXECUTE_PLAN,   // model executes an approved plan
    EXECUTE_TASK    // model executes a task
}

// ── Turn result (blocking path) ──
public record TurnResult(
    String sessionId,
    String model,
    String responseText,
    String renderedHtml,        // optional; null if no renderer configured
    ContextUsage contextUsage,
    List<ToolActivity> toolActivities,
    TurnDiagnostic diagnostic
) {}

// ── Turn diagnostic (observability) ──
public record TurnDiagnostic(
    String turnId,
    Instant startedAt,
    Instant completedAt,
    int modelCalls,
    int toolRounds,
    int repairRetries,
    boolean compacted,
    TurnOutcome outcome,
    String outcomeDetail
) {}

public enum TurnOutcome {
    COMPLETED,
    USER_QUESTION_PENDING,   // turn suspended waiting for answer
    TOOL_ABORTED,
    CONTEXT_EXHAUSTED,
    MODEL_FAILURE,
    TERMINAL_REPAIR_FAILED
}

// ── Turn events (replaces ChatStreamEvent) ──
// Sealed hierarchy, transport-agnostic
public sealed interface TurnEvent {
    record Started(
        String sessionId,
        String model,
        String turnId,
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record ThinkingChunk(      // model thinking/reasoning
        String text
    ) implements TurnEvent {}

    record ContentChunk(       // visible model output
        String text
    ) implements TurnEvent {}

    record ToolCallStarted(
        String toolName,
        String toolCallId
    ) implements TurnEvent {}

    record ToolCallCompleted(
        ToolActivity activity
    ) implements TurnEvent {}

    record SystemNotice(
        String text,
        NoticeKind kind        // COMPACTION, TOOL_ABORT, REPAIR, etc.
    ) implements TurnEvent {}

    record UserQuestionPending(
        List<String> questions,
        int questionIndex      // which question is current (0-based)
    ) implements TurnEvent {}

    record ContextUsageUpdate(
        ContextUsage usage
    ) implements TurnEvent {}

    record Completed(
        String text,
        ContextUsage usage,
        List<ToolActivity> toolActivities,
        TurnDiagnostic diagnostic
    ) implements TurnEvent {}

    record Failed(
        String message,
        TurnOutcome outcome,
        ContextUsage usage
    ) implements TurnEvent {}
}

public enum NoticeKind {
    COMPACTION,
    TOOL_ABORT,
    EMPTY_RESPONSE_REPAIR,
    PLAN_TURN_REPAIR,
    EXECUTION_REPAIR,
    INTERRUPT
}

// ── Model router (replaces ChatModelRouter) ──
public interface ModelProvider {
    // Resolve a ChatModel by logical name
    ChatModel resolve(String modelName);

    // Resolve ChatOptions (temperature, thinking level, etc.)
    ChatOptions options(String modelName);

    // List available model names
    List<String> availableModels();

    // Default model
    String defaultModel();
}

// ── System prompt assembler ──
public interface SystemPromptAssembler {
    // Build the system prompt for a turn
    String assemble(TurnRequest request, TurnContext context);

    // Built-in: simple text
    static SystemPromptAssembler fixed(String prompt) { ... }
    // Built-in: composite from ordered segments
    static SystemPromptAssembler ordered(List<PromptSegment> segments) { ... }
}

public interface PromptSegment {
    String assemble(TurnRequest request, TurnContext context);
    int order();               // lower = earlier in prompt
    String provenance();       // label for debugging
}

// ── Turn context (mutable carrier, framework-internal) ──
// Not part of the public API; framework uses it internally.
// Embedding apps interact via TurnRequest + TurnEvent stream.

// ── Turn repair strategy ──
public interface TurnRepairStrategy {
    // Check if repair is needed
    boolean needsRepair(TurnState state);

    // Produce repair message(s)
    List<Message> repairMessages(TurnState state);

    // How many retries before giving up
    int maxRetries();
}

// ── Tool loop guard ──
public interface ToolLoopGuard {
    // Called before tool calls
    void beforeToolCalls(List<ToolCall> calls);

    // Called after tool responses
    void afterToolResponses(List<ToolResult> results);

    // Check if guard is tripped
    Optional<ToolUseAbort> checkAbort();

    record ToolCall(String id, String name, String arguments) {}
    record ToolResult(String id, boolean error, String errorDetail) {}

    // Built-in: identical-call + error-rate guard
    static ToolLoopGuard standard(int identicalCallLimit, int errorWindowSize, int errorLimit) { ... }
}

// ── Exception types ──
public class TurnFailedException extends LoomException {
    TurnOutcome outcome();
    TurnDiagnostic diagnostic();
}
public class ContextExhaustedException extends TurnFailedException { ... }
```

**Key design decisions:**
1. The turn loop itself is NOT a public interface — it's framework-internal. The public API is `TurnRequest` → `Flux<TurnEvent>`. Embedding apps never see the state machine.
2. `TurnEvent` is transport-agnostic: no HTML, no SSE. Rendering (markdown → HTML) is a separate concern that an embedding app can add via a `TurnEventRenderer`.
3. The framework provides default strategies (`TurnRepairStrategy`, `ToolLoopGuard`, `SystemPromptAssembler`) but every strategy is replaceable.
4. `TurnMode` replaces `PlanMode` as the generalized concept. Embedding apps that don't need plan/task modes just use `NORMAL`.
5. The `TurnRequest.attributes` map is the extension point for mode-specific data (plan state, task run, tool approvals) — no framework-level plan/task dependencies.
6. The `ModelProvider` wraps Spring AI's `ChatModel` resolution with a clean interface. Embedding apps can provide custom routing.

### Area 4: Question-User Loop and Queueing

**Current state:** Two separate mechanisms: (1) `InteractionQuestionTools` for plan/task structured questions, (2) `ChatPendingMessageService` for queueing raw user messages with claim/ack/release.

**Generalized contract:**

```java
// ── Question-user mechanism (framework-internal tool) ──
// The framework registers "ask_user" as a ToolCallback.
// When the model calls it, the turn suspends and emits UserQuestionPending events.

public interface UserQuestionHandler {
    // Called when the model asks questions
    void onQuestionsAsked(String sessionId, List<String> questions);

    // The embedding app provides the answer
    CompletableFuture<String> awaitAnswer(String sessionId, Duration timeout);
}

// ── Message queue (separate from question-user loop) ──
public interface MessageQueue {
    // Enqueue a message for a busy session
    PendingMessage enqueue(
        String sessionId,
        String messageText,
        Map<String, String> metadata   // model preference, etc.
    );

    // List visible pending messages for a session
    List<PendingMessage> list(String sessionId);

    // Claim the oldest pending message (atomic)
    Optional<ClaimedMessage> claim(String sessionId);

    // Acknowledge (remove) after successful processing
    void ack(String sessionId, String messageId, String claimToken);

    // Release back to pending if processing failed
    void release(String sessionId, String messageId, String claimToken);

    // Clean up
    void deleteBySessionId(String sessionId);
}

public record PendingMessage(
    String id,
    String sessionId,
    String messageText,
    String status,            // PENDING | CLAIMED
    int position,
    int total,
    Instant createdAt,
    Instant updatedAt,
    Map<String, String> metadata
) {}

public record ClaimedMessage(
    PendingMessage message,
    String claimToken
) {}

// ── Queue persistence ──
public interface MessageQueueStore {
    PendingMessage enqueue(PendingMessage message);
    List<PendingMessage> findVisibleBySessionId(String sessionId);
    Optional<ClaimedMessage> claimOldest(String sessionId);
    boolean ack(String sessionId, String messageId, String claimToken);
    boolean release(String sessionId, String messageId, String claimToken);
    int recoverStaleClaims(String sessionId, Duration staleAge);
    void deleteBySessionId(String sessionId);

    // Factory for SQLite default
    static MessageQueueStore sqlite(DataSource dataSource) { ... }
}

// ── Default implementation ──
public class DefaultMessageQueue implements MessageQueue {
    // Delegates to MessageQueueStore
    // Handles claim token generation, stale claim recovery
}
```

**Key design decisions:**
1. `UserQuestionHandler` is the interface between the framework and the embedding app for structured questions. The framework tool (`ask_user`) calls `onQuestionsAsked()`, which suspends the turn and emits events. The embedding app calls `answer()` on the facade when the user responds.
2. `MessageQueue` is a clean CRUD interface for raw message queueing. The claim/ack/release flow is exactly the current pattern generalized.
3. `MessageQueueStore` is the persistence SPI. SQLite ships as default; embedding apps can provide Redis/Postgres implementations.
4. The queueing flow (`enqueue` → `claim` → `ack`/`release` with stale recovery) is production-proven; the only change is making it pluggable.

### Area 5: Event Streaming

**Current state:** `ChatStreamEvent` sealed hierarchy with 9 SSE-specific event types. `SseStreamLifecycle` handles `SseEmitter` creation/callback/disposal. `ChatController` wires the SSE transport to `ChatService.stream()`.

**Generalized contract:**

```java
// ── Turn event stream (transport-agnostic) ──
// Defined above as TurnEvent sealed hierarchy.
// The framework produces Flux<TurnEvent>.
// Embedding apps bind it to their transport.

// ── Transport binding: SSE ──
public interface TurnEventTransport {
    // Bind a Flux<TurnEvent> to a transport-specific output
    <T> T bind(Flux<TurnEvent> events, TransportContext context);

    // Built-in: SSE via Spring MVC
    static TurnEventTransport sse() { ... }

    // Built-in: WebSocket
    static TurnEventTransport webSocket(WebSocketSession session) { ... }

    // Built-in: Reactive Streams pass-through
    static TurnEventTransport passthrough() { ... }
}

public interface TransportContext {
    // Transport-specific carrier (e.g., SseEmitter, WebSocketSession)
    <T> T nativeHandle();
}

// ── Event renderer (optional; for HTML-rendering transports) ──
public interface TurnEventRenderer {
    // Render a TurnEvent to a transport-ready format
    String render(TurnEvent event);

    // Built-in: JSON (for API transports)
    static TurnEventRenderer json(ObjectMapper mapper) { ... }

    // Built-in: SSE named events
    static TurnEventRenderer sseNamedEvents() { ... }
}

// ── Stream lifecycle ──
public interface StreamLifecycle {
    // Create a new stream context
    StreamHandle start(Flux<TurnEvent> events, Consumer<StreamOutcome> onComplete);

    // Handle contains controls
    interface StreamHandle {
        void cancel();
        boolean isActive();
        StreamState state();
    }

    enum StreamState { ACTIVE, COMPLETING, COMPLETED, CANCELLED, FAILED }

    enum StreamOutcome {
        COMPLETED,
        CLIENT_DISCONNECTED,
        TIMEOUT,
        CANCELLED,
        MODEL_FAILURE,
        INTERNAL_ERROR
    }
}

// ── SSE-specific utilities (shipped but not framework-core) ──
// These live in loom-transport-sse module, not the core framework
public final class SseTransport {
    public static SseEmitter createEmitter(long timeoutMillis) { ... }
    public static void sendEvent(SseEmitter emitter, String name, Object data) { ... }
    public static boolean trySendEvent(SseEmitter emitter, String name, Object data) { ... }
    public static void completeQuietly(SseEmitter emitter) { ... }
    public static SubscriptionGuard guardSubscription() { ... }
}
```

**Key design decisions:**
1. `TurnEvent` is the canonical type. No HTML, no SSE names. Pure data.
2. `TurnEventTransport` is the bridge from framework events to transport. The framework ships `sse()` and `passthrough()` transports. WebSocket can be a separate module.
3. `TurnEventRenderer` is optional: JSON for API apps, SSE-named-events for web apps. The renderer is applied by the transport binding, not by the framework core.
4. `StreamLifecycle` replaces the inline emitter management in `ChatController`. It's a framework-provided utility that embedding apps use when wiring `Flux<TurnEvent>` to their transport.
5. The existing `SseStreamLifecycle` becomes `SseTransport` in a `loom-transport-sse` module, with zero dependency on SimplyPages, controllers, or Magenta concepts.

---

## Package and Module Layout (proposed)

```
mindspice-loom/
├── pom.xml                          (parent POM, dependency management)
├── loom-core/                        (the framework)
│   └── src/main/java/io/mindspice/loom/
│       ├── LoomCore.java             (facade interface)
│       ├── LoomCoreBuilder.java      (builder with defaults)
│       ├── LoomProperties.java       (@ConfigurationProperties)
│       ├── turn/
│       │   ├── TurnOrchestrator.java (engine interface)
│       │   ├── TurnRequest.java
│       │   ├── TurnResult.java
│       │   ├── TurnEvent.java        (sealed hierarchy)
│       │   ├── TurnMode.java
│       │   ├── TurnDiagnostic.java
│       │   ├── TurnOutcome.java
│       │   ├── TurnRepairStrategy.java
│       │   ├── DefaultTurnOrchestrator.java  (the state machine)
│       │   └── TurnContext.java      (internal mutable carrier)
│       ├── tool/
│       │   ├── ToolRegistry.java
│       │   ├── ToolDefinition.java
│       │   ├── ToolRegistration.java
│       │   ├── ToolActivity.java
│       │   ├── ToolAccessPolicy.java
│       │   ├── ToolArgumentCoercer.java
│       │   ├── ToolTranscriptStrategy.java
│       │   ├── ToolLoopGuard.java
│       │   ├── UserQuestionTool.java
│       │   ├── DefaultToolRegistry.java
│       │   ├── DefaultToolTranscript.java
│       │   └── coercion/
│       │       └── JacksonCollectionCoercer.java
│       ├── memory/
│       │   ├── ConversationMemory.java
│       │   ├── SessionStore.java
│       │   ├── ContextManager.java
│       │   ├── ContextUsage.java
│       │   ├── SessionInfo.java
│       │   ├── SessionFilter.java
│       │   ├── TokenEstimator.java
│       │   ├── CompactionStrategy.java
│       │   ├── DefaultContextManager.java
│       │   ├── DefaultCompactionStrategy.java
│       │   └── sqlite/
│       │       ├── SqliteConversationMemory.java
│       │       ├── SqliteSessionStore.java
│       │       └── SqliteSchemaManager.java
│       ├── queue/
│       │   ├── MessageQueue.java
│       │   ├── MessageQueueStore.java
│       │   ├── PendingMessage.java
│       │   ├── ClaimedMessage.java
│       │   ├── DefaultMessageQueue.java
│       │   └── sqlite/
│       │       └── SqliteMessageQueueStore.java
│       ├── event/
│       │   ├── TurnEventTransport.java
│       │   ├── TurnEventRenderer.java
│       │   ├── StreamLifecycle.java
│       │   └── TransportContext.java
│       ├── model/
│       │   ├── ModelProvider.java
│       │   ├── DefaultModelProvider.java
│       │   └── ModelConfig.java
│       ├── prompt/
│       │   ├── SystemPromptAssembler.java
│       │   └── PromptSegment.java
│       └── exception/
│           ├── LoomException.java
│           ├── TurnFailedException.java
│           ├── ContextExhaustedException.java
│           └── ToolNotFoundException.java
├── loom-transport-sse/               (optional SSE binding)
│   └── src/main/java/io/mindspice/loom/transport/sse/
│       ├── SseTransport.java
│       ├── SseTurnEventRenderer.java
│       └── SseTransportContext.java
├── loom-autoconfigure/               (Spring Boot auto-config)
│   └── src/main/java/io/mindspice/loom/autoconfigure/
│       ├── LoomAutoConfiguration.java
│       └── LoomProperties.java
└── loom-example/                     (minimal demo app)
    └── src/main/java/io/mindspice/loom/example/
        └── ExampleApplication.java
```

### Configuration surface

```yaml
# application.yml (loom.* namespace)
loom:
  # Model configuration
  models:
    default: ollama-local
    compaction: ollama-local
    providers:
      ollama-local:
        type: ollama
        endpoint: http://localhost:11434
        model: llama3.2
        context-length: 128000
        think-level: 2
      openai-4o:
        type: openai-compatible
        endpoint: https://api.openai.com/v1
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o
        context-length: 128000
        think-level: 0

  # Memory
  memory:
    type: sqlite                    # sqlite | in-memory | custom
    datasource: primary             # Spring DataSource bean name (for sqlite)

  # Context management
  context:
    buffer-percent: 15              # % of context window reserved as trigger buffer
    compaction:
      enabled: true
      min-tail-messages: 6
      trigger-tokens: 8000          # compact when this many tokens over trigger
    trimming:
      enabled: true

  # Tools
  tools:
    auto-register-spring-beans: true  # scan for @Tool beans
    argument-coercion: jackson        # jackson | none

  # Turn loop
  turn:
    max-tool-rounds: 50
    max-empty-response-retries: 2
    max-plan-turn-repair-retries: 2
    max-execution-repair-retries: 2
    tool-loop-guard:
      identical-call-limit: 5
      error-window-size: 8
      error-window-limit: 5

  # Queue
  queue:
    stale-claim-age: PT10M
    recovery-enabled: true

  # Streaming
  stream:
    timeout: PT0S                   # PT0S = no server-side timeout
    per-session-lock: true
```

### LoomCoreBuilder programmatic API (for code-first embedding)

```java
LoomCore loom = LoomCore.builder()
    .modelProvider(modelProvider)
    .conversationMemory(new InMemoryConversationMemory())
    .sessionStore(new InMemorySessionStore())
    .toolRegistry(registry -> registry
        .registerToolBean(new FileTools())
        .registerToolBean(new WebTools())
        .accessPolicy(ToolAccessPolicy.allowAll()))
    .systemPrompt("You are a helpful assistant.")
    .contextManager(ctx -> ctx
        .bufferPercent(15)
        .compactionEnabled(false))    // in-memory apps may skip compaction
    .turnRepair(TurnRepairStrategy.simple())
    .build();

Flux<TurnEvent> events = loom.stream(
    TurnRequest.of("session-1", "Hello, world!"));

// Bind to SSE
SseEmitter emitter = SseTransport.createEmitter(0);
SseTransport.bind(events, emitter);
```

---

## Trade-offs

### Trade-off 1: Keep Spring AI ChatMemory vs. Own Memory Interface
- **Keep Spring AI:** Less code to maintain; apps already know it. **Risk:** Spring AI's `ChatMemory` is not feature-rich (no windowed queries, no compaction hooks). The framework would still need `ContextManager` on top.
- **Own interface:** Cleaner abstraction, richer semantics (windowed gets, session metadata split). **Risk:** Another abstraction layer; apps that already use Spring AI `ChatMemory` need adapters.
- **Recommendation:** Own interface with a Spring AI adapter. The framework's `ConversationMemory` is richer; we ship `SpringAiConversationMemoryAdapter` for apps that want to keep Spring AI's `ChatMemory`.

### Trade-off 2: TurnMode vs. App-Specific Behavior
- **Keep `TurnMode` as a nullable hint:** The framework orchestrates identically for all modes; mode only gates tools and repair logic. Apps can ignore it. **Risk:** The `TurnMode` enum is still visible; apps may feel pressured to use it.
- **Remove mode entirely:** Make tool access and repair entirely pluggable via `ToolAccessPolicy` and `TurnRepairStrategy`. No enum. **Risk:** The default implementations become less discoverable; every app must configure.
- **Recommendation:** Keep `TurnMode` as an optional field on `TurnRequest` with sensible defaults. `NORMAL` mode means no mode-specific gating. Apps that don't need plan/execute modes never touch it.

### Trade-off 3: Facade Monolith vs. Segmented Facade
- **Monolith:** Single `LoomCore` with ~15 methods. Simple, discoverable. **Risk:** grows unbounded; breaks interface segregation.
- **Segmented:** `LoomCore.turns()`, `LoomCore.queue()`, etc. Clean separation. **Risk:** two-level navigation; more types.
- **Recommendation:** Hybrid. `LoomCore` has direct methods for the primary interaction flow (`stream`, `send`, `answer`, `enqueue`, `claim`, `ack`, `clearSession`, `close`). Advanced configuration is accessed through sub-interfaces (`tools()`, `memory()`, `sessions()`). This keeps the 80% path flat while providing expansion room.

### Trade-off 4: Transport Module vs. All-in-One
- **All-in-one:** SSE transport classes live in the core module. **Risk:** Pulls in Spring Web MVC dependency.
- **Separate module:** `loom-transport-sse` depends on `loom-core`. Core has zero web dependencies. **Risk:** More modules to manage.
- **Recommendation:** Separate module. The core must have zero dependence on Spring Web MVC, SimplyPages, or any web technology. `loom-transport-sse` is the reference implementation of a transport binding.

### Trade-off 5: SQLite as Default vs. In-Memory as Default
- **SQLite:** Production-ready, persistent, proven. **Risk:** Requires DataSource; heavier for simple embedding.
- **In-memory:** Zero dependencies; perfect for testing and simple apps. **Risk:** Data loss on restart.
- **Recommendation:** In-memory by default for the `LoomCore.builder()` code path. SQLite shipped as a first-class default module (`loom-memory-sqlite`). The auto-configuration wires SQLite when a DataSource is present, in-memory otherwise. This matches Spring Boot's philosophy.

---

## Open Questions

1. **What is the target artifact name?** "Loom" is proposed but needs user approval. Alternatives: `mindspice-core`, `mindspice-turn`, `mindspice-agent-core`.

2. **Should the framework own `ToolCallingManager` or delegate to Spring AI?** Currently the turn loop uses Spring AI's `ToolCallingManager.executeToolCalls()`. This is a reasonable abstraction. The framework should keep it but allow apps to provide their own `ToolExecutionStrategy`.

3. **Does the framework need a `ChatClient` wrapper?** Currently `ChatService` uses `ChatModelRouter` and `ChatClient`. The `ModelProvider` abstraction wraps `ChatModel` resolution. The question is whether `DefaultTurnOrchestrator` should use `ChatClient` + advisors (the Spring AI way) or call `ChatModel.call()` directly (more control).

4. **What happens to `ConversationTurnCoordinator`?** Currently it's a turn queue for agent orchestration. In the core framework, do we need per-session turn serialization? The `streamLocks` semaphore already handles this. The coordinator can stay in the app layer.

5. **Configuration format: YAML only or programmatic builder?** Both. The auto-config module reads `loom.*` properties. The `LoomCoreBuilder` is for code-first embedding. Both produce the same internal configuration.

6. **Should the framework expose a synchronous `execute()` or only `stream()`?** Both. `stream()` returns `Flux<TurnEvent>` for reactive/SSE apps. `execute()` blocks and returns `TurnResult` for CLI/batch apps. The implementation of `execute()` just collects from `stream()`.

7. **Password/API-key safety:** No framework logging of API keys. The `ModelConfig` should mark `apiKey` as sensitive. Already addressed in current code; carry forward.

---

## Recommended Next Step

**Phase 0: Settle naming and module boundaries.** Confirm `io.mindspice.loom` naming, the 4-module split (core, transport-sse, autoconfigure, example), and the façade interface shape with the user. This prevents churn during extraction.

**Phase 1: Extract the model and event types.** Create `loom-core` module with `TurnEvent`, `TurnRequest`, `TurnResult`, `TurnMode`, `ContextUsage`, `ToolActivity`, `PendingMessage`, `ClaimedMessage`, and all exception types. These are pure data with no dependencies — safe first step.

**Phase 2: Extract the tool framework.** Move `ToolRegistry`, `ToolAccessPolicy`, `ToolArgumentCoercer`, `ToolTranscriptStrategy`, `ToolLoopGuard`, and `UserQuestionTool` to their interfaces. Create `DefaultToolRegistry` from `ChatToolRegistry`. Ship the Jackson coercion module. Unit test with mock `ToolCallback` instances.

**Phase 3: Extract the memory/session abstraction.** Create `ConversationMemory`, `SessionStore`, `ContextManager`, `TokenEstimator`, `CompactionStrategy`. Port `RepositoryBackedChatMemory` to `SqliteConversationMemory`, `ChatSessionMetadataRepository` to `SqliteSessionStore`. Extract `ContextManagementAdvisor` logic into `DefaultContextManager`.

**Phase 4: Extract the turn orchestrator.** Move the `while`-`switch` state machine from `ChatService` into `DefaultTurnOrchestrator`. Wire `ModelProvider`, `ToolRegistry`, `ContextManager`, `ToolLoopGuard`, `TurnRepairStrategy`, `SystemPromptAssembler` as constructor dependencies. The state machine logic stays the same; only dependencies change.

**Phase 5: Extract the queue.** Port `ChatPendingMessageService` to `DefaultMessageQueue`, `ChatPendingMessageRepository` to `SqliteMessageQueueStore`.

**Phase 6: Create the façade.** Build `LoomCore` interface and `DefaultLoomCore` implementation that wires all components. Build `LoomCoreBuilder`.

**Phase 7: Auto-configuration and properties.** Create `loom-autoconfigure` with `LoomAutoConfiguration` and `LoomProperties`. Create `loom-transport-sse` with `SseTransport`.

**Phase 8: Delete application code.** Strip `api/web/`, `ai/orchestration/`, `ai/chat/plan/`, `ai/chat/task/`, `ai/chat/project/`, `ai/skills/`, `ai/execution/`, `ai/agent/`, SimplyPages UI, Spring Security, auth code. Keep only what the framework needs.

**Phase 9: Validation.** Unit tests for every public interface. Integration tests for the turn loop with mock model responses. Context startup smoke test. Build verification. The framework must compile, test, and start as a Spring Boot application with zero application-specific code.

**Phase 10: Example app.** Create `loom-example` — a minimal Spring Boot app that embeds Loom with in-memory storage, a single echo tool, and a REST endpoint that streams SSE. Serves as integration test and documentation.
