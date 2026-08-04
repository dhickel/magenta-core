## Prompt

Synthesize the complete brainstorming effort into a concise best-of-class direction. Select the strongest approach for each facet, reconcile conflicts, and contribute a better idea only where it improves the result. This is still a brainstorming synthesis, not an accepted implementation plan. Do not edit the project.

Prioritize feature completeness and production quality. Cover the full requested user scope. Do not propose or accept mocks, stubs, placeholders, deferred work, partial implementations, bare-minimum shortcuts, or non-production-quality paths as satisfying the request; when sequencing work, make every required behavior feature-complete and production-ready by the end of the plan or handoff.

## Source

- architecture-module-boundaries/findings.md
- internal-abstractions-design/findings.md
- risk-constraint-validation/findings.md
- phased-migration-planning/findings.md
- architecture-module-boundaries/cross-review.md
- internal-abstractions-design/cross-review.md
- risk-constraint-validation/cross-review.md
- phased-migration-planning/cross-review.md

## Findings

### Consensus Foundation

All four primary reports and all four cross-reviews converge on a shared bedrock of 21 decisions. These are treated as settled and are not re-litigated here. In brief: the turn-loop state machine from `ChatService.toolChat()` is the framework's crown jewel and must be extracted intact; `api/web/**` (~47 files), `ai/orchestration/**` (~150 files), `ai/skills/**` (~30 files), `ai/chat/plan/**`, `ai/chat/task/**`, `ai/chat/project/**`, `ai/chat/system/**`, and all app-specific tool sub-packages are deleted; `ChatToolRegistry`, `ToolTranscriptService`, `ToolArgumentCoercionModule`, `ToolLoopGuard`, `ChatMemoryRepository`, `RepositoryBackedChatMemory`, `ChatSessionMetadataRepository` (simplified), `ContextManagementAdvisor`, `ContextUsageTracker`, `ChatModelRouter`, `AiConfig`/`ModelConfig`/`EndpointType`, `ChatPendingMessageService`+`ChatPendingMessageRepository`, `ChatMarkdownRenderer`, and `ConversationTurnCoordinator` are kept and generalized; `InteractionQuestionTools` and `PromptContextAssembler` are rewritten against new SPIs; no Spring Security exists to remove; SQLite remains the shipped default with interface abstractions for pluggability; PlanService and TaskService are deleted entirely.

The synthesis below resolves every conflict surfaced across the eight reports and selects the strongest contribution from each for a unified, production-quality framework design.

### Resolved Conflicts

#### Conflict 1: Artifact Naming

Three proposals: `io.mindspice.turnloop` / `turnloop-core` (descriptive, searchable), `io.mindspice.loom` / `mindspice-loom` (distinctive, brandable), and `io.mindspice.core` / `magenta-core` (generic, app-branded). The cross-reviews correctly reject `io.mindspice.core` as ambiguous. Between `turnloop` and `loom`: `turnloop` is the more descriptive name — it communicates exactly what the framework does without requiring explanation. A framework artifact should be self-documenting. "Turn loop" is the precise term for the chat request → model call → tool-call resolution → model continuation → terminal response cycle that the framework owns.

**Decision: `io.mindspice.turnloop` root package, artifact `turnloop-core`, group `io.mindspice`. Facade class: `TurnLoop` (interface) implemented by `DefaultTurnLoop`.**

#### Conflict 2: Module Structure

Proposals range from one module to four (`core`, `transport-sse`, `autoconfigure`, `example`). The cross-reviews settle on a pragmatic v1 approach: two modules. The `turnloop-core` module contains the framework, SQLite defaults, and SSE transport utilities. The `turnloop-autoconfigure` module contains Spring Boot auto-configuration and `@ConfigurationProperties`. This keeps the build simple (two POMs) while preserving the standard Spring Boot auto-configuration separation. The `turnloop-transport-sse` and `turnloop-example` modules are deferred — separating SSE transport adds value only when a second transport binding (WebSocket, gRPC) justifies the split boundary.

**Decision: Two modules for v1: `turnloop-core` and `turnloop-autoconfigure`. `turnloop-core` has zero dependency on `spring-boot-starter-web`, `SseEmitter`, or any servlet API — this is a correctness guarantee enforced by the POM, not just convention.**

#### Conflict 3: Facade API Shape

Proposals: flat single-interface (~12 methods), hybrid (direct methods for 80% case + `tools()`, `memory()`, `sessions()` sub-interfaces), and concrete `final class`. The hybrid approach wins. Flat is simple but becomes a God-object as the surface grows. Concrete class cannot be mocked in tests without a wrapper. The hybrid mirrors the pattern used by Spring's `RestClient` and `JdbcClient`: common operations are directly callable; advanced configuration flows through typed sub-interfaces.

**Decision: `TurnLoop` interface with direct methods for the primary interaction flow (`send`, `stream`, `answer`, `enqueue`, `claim`, `ack`, `clearSession`, `close`) plus accessor methods returning focused sub-interfaces (`tools()`, `memory()`, `sessions()`) for advanced configuration. `TurnLoopBuilder` returns `TurnLoop` (the interface). Spring auto-configuration creates a `DefaultTurnLoop` bean exposed as `TurnLoop`. Internal services (`TurnEngine`, `ToolRegistry`, `ContextManager`) are interfaces mocked directly in tests; the facade is the composition root, not the unit under test.**

#### Conflict 4: TurnMode / InteractionMode Handling

Proposals range from an `InteractionMode` enum (NORMAL, PLANNING, EXECUTING) to a `TurnMode` enum (NORMAL, PLAN, TASK, EXECUTE_PLAN, EXECUTE_TASK) to complete removal with pluggable hooks. The correct pure-framework answer is removal. The framework must not carry plan/task/execute concepts. The current mode-gating behavior in `ToolAccessPolicy` and `TerminalTurnRepair` is replaced by three SPIs that embedding apps implement:

- **`ToolAccessPolicy`**: filters available tools per turn. The framework ships `ToolAccessPolicy.allowAll()`. Apps that need mode-gated tools provide their own implementation.
- **`CompletionDetector`**: called after each model response; returns true when the turn is complete without further tool calls. The framework ships `DefaultCompletionDetector` (never complete without terminal response). Apps that need plan/task completion detection implement the interface.
- **`RepairStrategy`**: called when a model response is empty or incomplete; produces control messages to guide the model. The framework ships `EmptyResponseRepair` (handles empty assistant responses) and `NoOpRepair`. The plan/task repair logic from `TerminalTurnRepair` stays in Magenta as app-specific implementations.

The `TurnContext` carries an opaque `Map<String, Object> attributes` that SPIs can inspect without the framework understanding the contents.

**Decision: Remove TurnMode/PlanMode/InteractionMode entirely. Replace with three SPIs: `ToolAccessPolicy`, `CompletionDetector`, `RepairStrategy`. Framework ships sensible defaults for all three. Embedding apps implement mode-aware behavior through these SPIs without the framework knowing about plan/task/execute modes.**

#### Conflict 5: Memory SPI Depth

Proposals range from two interfaces (`MemoryRepository` + `SessionMetadataRepository`) to five (`ConversationMemory`, `SessionStore`, `ContextManager`, `TokenEstimator`, `CompactionStrategy`). The richer SPI wins because token estimation varies by model provider (Ollama vs. OpenAI use different tokenizers) and compaction strategy varies by use case (summarization vs. sliding window vs. none). However, `ContextManager` is not a public SPI — it is a framework-internal orchestrator that coordinates the other interfaces. The public SPIs are:

- **`ConversationMemory`**: message storage with windowed queries. Standalone interface (not extending Spring AI's `ChatMemory`). A `SpringAiConversationMemoryAdapter` bridges to Spring AI for backward compatibility.
- **`SessionStore`**: session metadata (model, title, favorite, archived, timestamps). Separate from message storage — different replacement cadence.
- **`TokenEstimator`**: estimates token count for messages. Framework ships JTokkit-based estimator with character-count fallback.
- **`CompactionStrategy`**: summarizes older messages into compact form. Framework ships a summary-based default.

The `TurnLoopBuilder` provides sensible defaults for all four SPIs so simple embedding (`TurnLoop.builder().build()`) works out of the box with SQLite-backed persistence and JTokkit token estimation.

**Decision: Four public SPIs — `ConversationMemory`, `SessionStore`, `TokenEstimator`, `CompactionStrategy`. `ContextManager` is framework-internal. SQLite implementations ship as the default for `ConversationMemory` and `SessionStore`. A `SpringAiConversationMemoryAdapter` bridges to Spring AI's `ChatMemory`.**

#### Conflict 6: Question-User Loop Design

Proposals: synchronous callback, async `CompletableFuture`, and state-machine approach with framework-owned lifecycle. The framework must own the question lifecycle. The turn loop needs to know when a question is pending so it can suspend and emit a `TurnEvent.QuestionPending`. The flow:

1. Model invokes `ask_user` tool (framework-registered `ToolCallback`).
2. Framework calls `InteractionHandler.onQuestionsAsked(sessionId, questions)` — the embedding app persists questions and presents them to the user.
3. Turn loop suspends, emits `TurnEvent.QuestionPending`, and holds the turn context.
4. Embedding app calls `TurnLoop.answer(sessionId, answer)`.
5. Framework calls `InteractionHandler.onAnswerReceived(sessionId, answer)` to format the answer, then resumes the turn loop.

The `InteractionStore` SPI (pluggable, in-memory default) persists pending questions so the framework survives a process restart. A `CompletableFuture`-based convenience wrapper (`InteractionHandler.async()`) is provided for simple single-process use cases.

**Decision: Framework owns suspend/resume lifecycle. `InteractionHandler` is the embedding app's callback. `InteractionStore` SPI persists pending questions (in-memory default). The framework registers `ask_user` as a built-in `ToolCallback` internally, not exposed to embedding apps.**

#### Conflict 7: Event Streaming Contract

Proposals: events carry `renderedHtml` (current behavior) vs. transport-agnostic raw text. Transport-agnostic wins — the framework must not dictate rendering format. `TurnEvent` carries raw text only. The `MarkdownRenderer` is a framework-provided utility in `turnloop-core` (with `commonmark` and `owasp-html-sanitizer` as optional dependencies) that embedding apps invoke if they want HTML. The `TurnMessage` record has `text` (raw) and an optional `renderedText` field. The `TurnLoopBuilder` accepts an optional `Function<String, String> renderer`; if provided, `renderedText` is populated.

**Decision: `TurnEvent` sealed hierarchy is transport-agnostic — raw text only, no HTML. `MarkdownRenderer` ships as an optional utility. Embedding apps render markdown to HTML at their transport boundary. `commonmark` and `owasp-html-sanitizer` become optional Maven dependencies.**

### Selected Designs by Area

#### 1. Tool Framework

Adopt the four-interface approach from the cross-review synthesis:

```java
public interface ToolRegistry {
    ToolRegistration register(ToolCallback callback);
    List<ToolRegistration> register(ToolCallbackProvider provider);
    List<ToolRegistration> registerToolBean(Object toolBean);
    void unregister(String toolName);
    List<ToolDefinition> definitions();
    Optional<ToolDefinition> definition(String toolName);
    List<ToolCallback> resolveForTurn(TurnContext context);
    // Framework-internal: register built-in tools not visible to embedding apps
    ToolRegistration registerFrameworkTool(ToolCallback callback);
}

public interface ToolAccessPolicy {
    List<ToolCallback> filter(List<ToolCallback> registered, TurnContext context);
    static ToolAccessPolicy allowAll() { ... }
    static ToolAccessPolicy allowList(Set<String> toolNames) { ... }
    static ToolAccessPolicy blockList(Set<String> blockedNames) { ... }
}

public interface ToolResultSummarizer {
    String summarize(String toolName, String arguments, String result);
    boolean looksLikeError(String result);
}

public interface InteractionHandler {
    void onQuestionsAsked(String sessionId, List<String> questions);
    String onAnswerReceived(String sessionId, String answer);
}

public interface InteractionStore {
    void saveQuestions(String sessionId, List<String> questions);
    Optional<List<String>> getQuestions(String sessionId);
    void clear(String sessionId);
}
```

`ToolLoopGuard` remains a concrete class (zero app dependencies, no SPI needed). `ToolArgumentCoercionModule` remains a Jackson module registered via service loader. The hard-coded tool-name switch in `ToolTranscriptService.summarizeResult()` is replaced by `ToolResultSummarizer` — the framework ships a generic fallback that returns "Returned N characters" summaries.

The framework auto-registers two framework tools: `ask_user` (question-user loop) and `echo` (example demonstrating registration pattern). Neither is visible to embedding apps through `definitions()`.

Sealed exception hierarchy for tool errors:
```java
public sealed abstract class ToolException extends TurnLoopException {
    public static final class ArgumentCoercionFailed extends ToolException {}
    public static final class ExecutionFailed extends ToolException {}
    public static final class LoopExceeded extends ToolException {}
    public static final class AccessDenied extends ToolException {}
}
```

#### 2. Memory / Session / Persistence

```java
public interface ConversationMemory {
    void append(String sessionId, List<Message> messages);
    List<Message> get(String sessionId);
    WindowedMessages getWindowed(String sessionId, int maxTokens);
    void clear(String sessionId);
    boolean exists(String sessionId);
    List<String> sessionIds();
}

public interface SessionStore {
    void saveModel(String sessionId, String model);
    Optional<String> model(String sessionId);
    void saveTitle(String sessionId, String title);
    Optional<String> title(String sessionId);
    void setFavorite(String sessionId, boolean favorite);
    boolean isFavorite(String sessionId);
    void setArchived(String sessionId, boolean archived);
    boolean isArchived(String sessionId);
    Optional<Instant> updatedAt(String sessionId);
    List<SessionInfo> list(SessionFilter filter);
    void delete(String sessionId);
}

public interface TokenEstimator {
    int estimate(String text);
    int estimate(List<Message> messages);
}

public interface CompactionStrategy {
    CompactionResult compact(List<Message> olderMessages, List<Message> previousSummaries, CompactionContext context);
}

// Framework-internal orchestrator (not public SPI)
// ContextManager coordinates ConversationMemory + TokenEstimator + CompactionStrategy
// Embedding apps configure the strategies, not the orchestration.
```

SQLite implementations (`SqliteConversationMemory`, `SqliteSessionStore`) ship as the v1 default. The framework's `schema.sql` creates four tables idempotently: `turnloop_conversation_memory`, `turnloop_session_metadata`, `turnloop_pending_messages`, `turnloop_audit_log`. The existing 20-migration `SchemaMigrationRunner` is deleted — the framework uses the same self-migrating pattern as the current `ChatMemoryRepository.ensureMetadataColumn()`, where each repository ensures its own table on first access.

`SessionStore` removes all Magenta-specific columns (`origin` as enum, `surface`, `agent_id`, `project_id`, `member_agent_id`, `materialization_key`, `materialization_generation`, `owner_binding_key`, `active_task_run_id`, `planning_model`). A `metadata TEXT` JSON column provides an extension point. The `origin` field becomes a simple `String` with framework constant `TurnLoop.ORIGIN = "turnloop"`.

#### 3. Turn-Loop Mechanics

The 6-state machine (PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) is extracted from `ChatService.toolChat()` into `DefaultTurnEngine`. The structural logic stays identical. All plan/task-specific completion detection, mode-gating, and repair control messages are replaced by the three SPIs (`ToolAccessPolicy`, `CompletionDetector`, `RepairStrategy`).

```java
public interface TurnEngine {
    Flux<TurnEvent> stream(TurnRequest request);
    TurnResult execute(TurnRequest request);
    Flux<TurnEvent> answer(String sessionId, String answer);
    void interrupt(String sessionId, String interruptToken, String reason);
}

public record TurnRequest(
    String sessionId,        // null for new session
    String userMessage,
    String model,            // null for default
    Map<String, Object> attributes  // opaque extension point
) {}

public record TurnResult(
    String sessionId,
    String model,
    String responseText,
    ContextUsage contextUsage,
    List<ToolActivity> toolActivities,
    TurnDiagnostic diagnostic
) {}

public sealed interface TurnEvent {
    record Started(String sessionId, String model, String turnId, String interruptToken, ContextUsage usage) implements TurnEvent {}
    record Chunk(String text, boolean thinking, ContextUsage usage) implements TurnEvent {}
    record ToolActivity(ToolActivityData activity, ContextUsage usage) implements TurnEvent {}
    record SystemNotice(String text, NoticeKind kind, ContextUsage usage) implements TurnEvent {}
    record QuestionPending(List<String> questions, ContextUsage usage) implements TurnEvent {}
    record Interrupt(String text, ContextUsage usage) implements TurnEvent {}
    record Completed(String text, ContextUsage usage, List<ToolActivityData> activities, TurnDiagnostic diagnostic) implements TurnEvent {}
    record Failed(String message, TurnOutcome outcome, ContextUsage usage) implements TurnEvent {}
}
```

Key design notes on `TurnEvent`:
- **No HTML.** Raw text only. Rendering is the embedding app's transport concern.
- **Single `Chunk` event** with optional `thinking` boolean rather than separate `ThinkingChunk` and `ContentChunk` types — matches the current code's metadata-extraction pattern.
- **No `ToolCallStarted`.** The current code doesn't emit tool-call-started events. Keep only `ToolActivity` (completed).
- **No `OutputsChanged`.** Tied to file-mutating tools, which are deleted.
- **Every event carries `ContextUsage`** where applicable, matching current behavior.
- `interruptToken` on `Started` supports the existing `ActiveTurnRegistry` interrupt polling pattern.

`ModelRouter` (generalized `ChatModelRouter`) resolves model aliases to Spring AI `ChatModel` instances. The `RuntimeSettingsService` fallback in `defaultRemoteModelName()` is replaced with a direct `AiConfig.defaultModel` fallback. Model aliases are first-class at the facade level; raw remote-name resolution is internal-only.

`TurnGate` (generalized `ConversationTurnCoordinator`) serializes turns per conversation. The implementation uses `ReentrantLock` instead of `synchronized` to avoid virtual thread pinning on Java 25. The `TurnLoopBuilder` accepts a custom `TurnGate`; the default is `SerialTurnGate`.

`PromptAssembler` (generalized `PromptContextAssembler`) builds prompts from a pluggable pipeline of `PromptContributor` implementations. The framework ships a `DefaultPromptAssembler` that builds a 3-layer prompt: (1) system prompt from config, (2) registered contributors in priority order, (3) turn instructions. Embedding apps implement `PromptContributor` to inject additional layers.

#### 4. Message Queueing

```java
public interface MessageQueue {
    PendingMessage enqueue(String sessionId, String messageText, Map<String, String> metadata);
    List<PendingMessage> list(String sessionId);
    Optional<ClaimedMessage> claim(String sessionId);
    void ack(String sessionId, String messageId, String claimToken);
    void release(String sessionId, String messageId, String claimToken);
    int recoverStaleClaims(String sessionId, Duration staleAge);
    void deleteBySessionId(String sessionId);
}

public interface MessageQueueStore {
    // Persistence SPI — SQLite default ships as SqliteMessageQueueStore
    // Same methods as MessageQueue minus claim-token generation and stale recovery
}
```

The claim/ack/release pattern with stale-claim recovery from `ChatPendingMessageService` is preserved exactly — it is production-proven. The `ChatSessionSurface` and `planningModel` fields are removed from `PendingMessage`. A `Map<String, String> metadata` extension point replaces them.

#### 5. Event Streaming and Transport

`TurnEvent` is the framework's streaming contract — a `Flux<TurnEvent>` is what `TurnEngine.stream()` returns. The framework provides:

- **`StreamLifecycle`**: utility class for binding a `Flux<TurnEvent>` to a transport. Handles `SseEmitter` creation, event dispatch, timeout, error, and disconnect. Lives in `turnloop-core` because it has zero web dependencies — it's a thin adapter the embedding app calls.
- **`TurnEventRenderer`**: converts `TurnEvent` to transport-specific formats. `TurnEventRenderer.json(ObjectMapper)` for REST APIs. `TurnEventRenderer.sse()` for SSE named events. Both are in `turnloop-core` as utilities.

The actual HTTP endpoint (controller, `SseEmitter` wiring) is the embedding app's responsibility. The `README.md` provides a copy-paste SSE controller example.

#### 6. Execution Strategy

`TurnExecutor` replaces `MagentaWorkExecutor`. Default is virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` — appropriate for I/O-bound model calls, simplifies lifecycle (no pool sizing), and is idiomatic for Java 25. A `turnloop.executor.strategy=fixed` property provides a fixed thread pool alternative.

#### 7. Audit

```java
public interface AuditWriter {
    void recordUserMessage(String sessionId, String message, String model);
    void recordAssistantMessage(String sessionId, String text, String metadataJson, String model);
    void recordToolExecution(ToolTranscriptEntry entry, String sessionId, String model);
    void recordContext(String sessionId, ContextUsage usage, int messageCount, String model);
    void recordError(String sessionId, String errorType, String errorMessage, String stackTrace, String model);
}
```

The framework ships `NoOpAuditWriter` as the default. `SqliteAuditWriter` is available as an optional component. The framework fires a `TurnLifecycleListener.onTurnCompleted(TurnResult)` callback after each turn — embedding apps use this for title generation, audit logging, or any post-turn action.

### Configuration Surface

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
      gpt-4o:
        type: openai-compatible
        endpoint: https://api.openai.com/v1
        model: gpt-4o
        context-length: 128000
        think-level: 0
        api-key: ${OPENAI_API_KEY}

  context:
    buffer-percent: 10
    compaction:
      min-tail-messages: 6
    trimming:
      enabled: true

  turn:
    max-tool-rounds: 50
    max-empty-response-retries: 2
    tool-guard:
      identical-call-limit: 5
      error-window-size: 8
      error-window-limit: 5

  queue:
    stale-claim-age: 10m
    max-queue-size-per-session: 100

  stream:
    timeout: 0   # PT0S = no server-side timeout

  executor:
    strategy: virtual    # virtual | fixed
    turn-threads: 8      # only for fixed strategy
    turn-queue-size: 200 # only for fixed strategy

  model-timeout:
    openai-compatible-read-seconds: 360

  prompt:
    default-system-prompt: "You are a helpful assistant."
```

The framework starts with sensible defaults when no `application.yml` is present — Ollama at `localhost:11434` and an in-memory SQLite database. Model configuration is also supported via a `turnloop-models.json` file on the classpath and via programmatic `TurnLoopBuilder.model(alias, config)`.

### Exception Hierarchy

```java
public class TurnLoopException extends RuntimeException {}

public sealed class TurnFailedException extends TurnLoopException {
    TurnOutcome outcome();
    TurnDiagnostic diagnostic();
}

public final class ContextExhaustedException extends TurnFailedException {}
public final class ConversationNotFoundException extends TurnLoopException {}
public sealed class ToolException extends TurnLoopException {
    public static final class ArgumentCoercionFailed extends ToolException {}
    public static final class ExecutionFailed extends ToolException {}
    public static final class LoopExceeded extends ToolException {}
    public static final class AccessDenied extends ToolException {}
}
public final class ConfigurationException extends TurnLoopException {}
```

All internal exceptions (`ToolUseAbort`, `NonTransientAiException`) are caught at the facade boundary and wrapped in the public hierarchy. No raw `RuntimeException` or Spring AI exception leaks through `TurnLoop` methods.

### Production Quality Hardening

These concerns were surfaced by the Risk report and are incorporated into the framework design, not deferred:

1. **Thread safety — `streamLocks` race**: The current `streamLocks.compute()` in `doFinally` has a subtle race between `lock.release()` and `compute()`. The `TurnGate` interface eliminates the dual serialization mechanism (semaphore map + turn coordinator). A single `SerialTurnGate` with `ReentrantLock` (virtual-thread-compatible) replaces both.

2. **Graceful lifecycle**: `TurnLoop` extends `AutoCloseable`. `close()` drains all in-flight turns via `TurnGate`, releases all stream resources, releases all pending message claims, and calls `close()` on any `AutoCloseable` `ChatModel` instances cached by `ModelRouter`. A `@PreDestroy` on the auto-configured bean calls `close()`.

3. **`ThreadLocal` audit**: After orchestration deletion, `OrchestrationTaskContextHolder` is removed. The remaining `ThreadLocal` (`activeTrustedPolicies`) becomes a turn-scoped carrier passed explicitly through `TurnContext`, not a `ThreadLocal`. Zero `ThreadLocal` usage remains in the framework.

4. **Virtual thread compatibility**: `SerialTurnGate` uses `ReentrantLock` instead of `synchronized`. `ModelRouter` uses `ConcurrentHashMap` which is virtual-thread-friendly. No carrier-thread pinning.

5. **Config validation**: The framework starts without any external config file. `TurnLoop.builder().build()` creates a working instance with in-memory SQLite and an Ollama default model at `localhost:11434`. Missing config produces a clear `ConfigurationException` with guidance, not a cryptic `IOException`.

6. **Schema migration**: The 20-migration `SchemaMigrationRunner` is deleted. Each repository uses a self-migrating `CREATE TABLE IF NOT EXISTS` pattern. The framework owns four tables with clean, minimal schemas.

### Phased Implementation Plan

The unified plan adopts the Phased Migration Planning report's deletion-first ordering (proven to be the safest and fastest path), the Architecture Boundaries report's Phase 0 scaffolding step, and the Risk report's validation gates:

**Phase 0: Scaffold and Baseline** (1 day)
- Create `turnloop-core` and `turnloop-autoconfigure` Maven modules in the repository.
- Set up minimal POMs with only framework-necessary dependencies.
- Run full baseline: `mvn clean verify`, context startup smoke test.
- Gate: all existing tests pass before any deletions.

**Phase 1: Delete Web/UI Surface** (1-2 days)
- Delete `api/web/**` (~47 files), `src/main/resources/static/**`, SimplyPages, HTMX, webjar dependencies.
- Delete `tests/` directory, `playwright.config.js`, `package.json`.
- Generalize `ChatStreamEvent` → `TurnEvent` (remove `ChatPlanState` references, keep in `ai/chat/model/` temporarily).
- Move `SseStreamLifecycle` to `ai/chat/service/` temporarily.
- Drop `spring-boot-starter-web`, `spring-boot-starter-webmvc-test`, `simplypages`, `webjars-locator-core`, `htmx.org`.
- Gate: compile, non-web tests pass, context starts without embedded Tomcat.

**Phase 2: Sever ChatService Dependencies** (4-6 days — critical path)
- Use the Risk report's collaborator map as the work plan.
- Apply `@Autowired(required = false)` stepping-stone: make all 13 app-shell collaborators optional, then remove one at a time.
- Delete plan/task/agent/skills/workspace/measurement method calls from `ChatService`.
- Generalize `InteractionQuestionTools` → `InteractionHandler` SPI.
- Rewrite `PromptContextAssembler` → `DefaultPromptAssembler` + `PromptContributor` SPI.
- Rewrite `ToolAccessPolicy` → SPI with `allowAll()` default.
- Rewrite `TerminalTurnRepair` → `RepairStrategy` + `CompletionDetector` SPIs.
- Collapse `ChatService`'s 5-constructor chain to a single 10-14 parameter constructor.
- Adapt `ChatServiceTest` incrementally: delete plan/task test methods, update constructor invocations.
- Gate: compile, ChatService tests pass with mock collaborators, zero `PlanService`/`TaskService` imports in `ChatService`.

**Phase 3: Delete App-Shell Packages** (2-3 days)
- Delete `ai/orchestration/**` (~150 files), `ai/skills/**` (~30 files), `ai/chat/plan/**` (~25 files), `ai/chat/task/**` (9 files), `ai/chat/project/**` (6 files), `ai/chat/system/**` (6 files), `ai/agent/job/**` (7 files), `ai/execution/**` (8 files).
- Delete all app-specific tool packages: `ai/chat/tool/file/`, `ai/chat/tool/shell/`, `ai/chat/tool/web/`, `ai/chat/tool/skills/`, `ai/chat/tool/orchestration/`, `ai/chat/tool/plan/`, `ai/chat/tool/savedplan/`, `ai/chat/tool/task/`.
- Delete `InteractionQuestionTools.java` (replaced by `InteractionHandler` SPI).
- Simplify `AiConfig`: remove `agents`, `defaultAgent`, `planningModel`, `compactionModel`, `unsafeAllowWildcardShellCommands`. Collapse to one canonical constructor.
- Simplify `AiUserConfigConfiguration`: remove agent resolution.
- Delete `ChatSessionOrigin.java`, `ChatSessionSurface.java`, `ChatPlanState.java`, `PlanMode.java`, `ChatFileService.java`, `ChatInvocationProfile.java`.
- Delete 16 dead migration files and `SchemaMigrationRunner`.
- Delete `TrustedContextPolicyAssembler`, `OrchestrationTaskContext`, `OrchestrationTaskContextHolder`.
- Grep for remaining app-shell references; eliminate all.
- Gate: compile, all remaining tests pass, context starts.

**Phase 4: Extract into Framework Packages** (4-5 days)
- Move surviving code into `io.mindspice.turnloop` packages under `turnloop-core`.
- Rename: `ChatService` → `DefaultTurnEngine`, `ChatModelRouter` → `DefaultModelRouter`, `ChatToolRegistry` → `DefaultToolRegistry`, `ChatMemoryRepository` → `SqliteConversationMemory`, `ChatSessionMetadataRepository` → `SqliteSessionStore`, etc.
- Create all public SPIs: `ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, `ConversationMemory`, `SessionStore`, `TokenEstimator`, `CompactionStrategy`, `MessageQueue`, `MessageQueueStore`, `InteractionHandler`, `InteractionStore`, `CompletionDetector`, `RepairStrategy`, `TurnGate`, `AuditWriter`, `PromptContributor`.
- Create `TurnRequest`, `TurnResult`, `TurnEvent` sealed hierarchy, `TurnMessage`, `ConversationSession`, `ContextUsage`, `PendingMessage`, `ClaimedMessage`, `TurnDiagnostic`.
- Replace hard-coded `ToolTranscriptService` switch with `ToolResultSummarizer` SPI.
- Replace `MagentaWorkExecutor` with virtual-thread `TurnExecutor`.
- Create fresh `schema.sql` with four framework tables.
- Gate: compile, unit tests pass, context starts with framework beans only.

**Phase 5: Build Facade** (2-3 days)
- Implement `TurnLoop` interface and `DefaultTurnLoop` backing implementation.
- Implement `TurnLoopBuilder`.
- Wire all internal components behind package-private visibility.
- Implement `close()` lifecycle: drain `TurnGate`, release stream resources, release pending claims, close `AutoCloseable` models.
- Create `turnloop-autoconfigure` with `TurnLoopAutoConfiguration` and `TurnLoopProperties`.
- Gate: facade integration tests pass (`TurnLoop.send()` and `TurnLoop.stream()` end-to-end with real Ollama model), lifecycle tests pass.

**Phase 6: Tests, Documentation, Hardening** (3-4 days)
- Unit tests for every public SPI and every `TurnPhase` state-machine transition.
- Integration tests: full turn loop with Ollama, question-user round-trip, queue drain, concurrent turn serialization, interrupt.
- Auto-configuration smoke test: `ApplicationContextRunner` with framework only.
- Concurrency stress: 10 concurrent turns per session serialize correctly; `close()` releases all resources.
- Dead code scan: PMD or IDE unused-code analysis confirms zero references to deleted packages from retained code.
- Documentation: `README.md` with embedding example, `docs/getting-started.md`, `docs/turn-loop.md`, `docs/tools.md`, `docs/memory.md`, `docs/streaming.md`, `docs/interaction.md`, `docs/queueing.md`, `docs/configuration.md`, `docs/facade-api.md`.
- Gate: `mvn clean verify` passes, >80% line coverage on framework packages, zero dead references.

### Example Tools

The framework ships with three example tools, each demonstrating a different aspect of the tool framework:

1. **`EchoTool`** (`@Tool(name = "echo")`): echoes input back. Demonstrates basic `@Tool` usage, argument coercion, and transcript patterns.
2. **`DateTimeTool`** (`@Tool(name = "get_current_time")`): returns current date/time with configurable timezone. Demonstrates parameterized tools.
3. **`ask_user`**: built-in framework tool (not an example — production question-user loop). Registered via `registerFrameworkTool()`, not visible to embedding apps.

These are documentation-quality examples, not production tools. They live in `turnloop-core` under `io.mindspice.turnloop.example`.

### Dependency Footprint

**`turnloop-core` required dependencies:**
- `spring-boot-starter` (core DI/config, no web, no security)
- `spring-boot-starter-jdbc` (SQLite access)
- `spring-boot-starter-json` (Jackson)
- `spring-ai-starter-model-ollama` (Spring AI Ollama)
- `spring-ai-starter-model-openai` (Spring AI OpenAI-compatible)
- `spring-ai-deepseek` (Spring AI DeepSeek)
- `sqlite-jdbc` (SQLite driver)

**`turnloop-core` optional dependencies:**
- `commonmark` + `commonmark-ext-gfm-tables` (MarkdownRenderer utility)
- `owasp-java-html-sanitizer` (MarkdownRenderer utility)
- `jackson-dataformat-yaml` (YAML config loading)

**`turnloop-autoconfigure` required dependencies:**
- `spring-boot-autoconfigure`
- `spring-boot-configuration-processor`
- `turnloop-core`

**Dependencies explicitly NOT included:**
- `spring-boot-starter-web` (no embedded Tomcat, no MVC)
- `spring-boot-starter-security` (no auth)
- `spring-boot-starter-actuator` (embedding apps add their own)
- `spring-boot-starter-webflux` (not needed — framework doesn't serve HTTP)
- `jsoup` (was for web fetch tool — deleted)
- `jna` (was for filesystem operations — deleted)

## Options

No alternatives are presented. The direction above is the unified best-of-class synthesis. Each facet was selected by comparing all four primary reports, all four cross-reviews, and ground-truth code inspection. Where a cross-review identified a stronger approach than any primary report, it was adopted. Where a conflict remained after cross-review, it is resolved above with explicit justification.

## Trade-offs

### Trade-off 1: Rich SPI Surface vs. Simplicity
The framework defines ~15 SPIs. This is a deliberate choice: a reusable framework's value is its extension points. Every SPI ships with a production-quality default. `TurnLoop.builder().build()` works out of the box with zero configuration. The SPI surface is the escape hatch, not the front door.

### Trade-off 2: SQLite-Only Default vs. In-Memory
Shipping only SQLite as the v1 default means embedding apps need a DataSource. This is acceptable because: (a) SQLite is zero-infrastructure (file-based, no server), (b) the current implementation is battle-tested, and (c) building a second in-memory backend before real demand is speculative scope creep. The `ConversationMemory` and `SessionStore` interfaces are defined — an in-memory implementation is straightforward follow-up work.

### Trade-off 3: Transport-Agnostic Events vs. HTML-in-Events
Removing `renderedHtml` from `TurnEvent` means every embedding app must render markdown. The cost is trivial — one `MarkdownRenderer.render()` call at the transport boundary. The benefit is architectural: `turnloop-core` has zero dependency on HTML rendering libraries, and the event contract is clean for non-HTML consumers (CLI, REST API, gRPC).

### Trade-off 4: TurnMode Removal vs. Built-in Mode Support
Removing `TurnMode` entirely means the framework cannot provide mode-aware tool filtering out of the box. This is the correct trade-off — the framework should not know about plan/task/execute modes. Embedding apps implement mode-aware behavior through `ToolAccessPolicy`, `CompletionDetector`, and `RepairStrategy`. This is more work for apps that need modes, but zero mental overhead for apps that don't.

### Trade-off 5: Two Modules vs. Four
Deferring `turnloop-transport-sse` and `turnloop-example` to a later release keeps the v1 build simple. The single `turnloop-core` module contains everything needed. The auto-configuration module is standard Spring Boot practice and costs almost nothing to separate. Multi-module for transport adds value only when there are multiple transport bindings justifying the boundary.

### Trade-off 6: Interface Facade vs. Concrete Class
An interface facade (`TurnLoop`) with package-private implementation (`DefaultTurnLoop`) is slightly more verbose than a `final class`. The benefit is testability: embedding apps can mock `TurnLoop` in their tests. The framework's own internal services are interfaces mocked directly; the facade is the composition root.

### Trade-off 7: `ConversationMemory` Standalone vs. Extending Spring AI `ChatMemory`
A standalone `ConversationMemory` interface is cleaner, supports windowed queries, and has no Spring AI coupling in its contract. The `SpringAiConversationMemoryAdapter` bridges for apps already using Spring AI's `ChatMemory`. This is the same pattern Spring uses for its own abstractions (e.g., `JdbcClient` alongside `JdbcTemplate`).

## Open Questions

1. **Final artifact name approval**: `io.mindspice.turnloop` / `turnloop-core`. The synthesis above uses this name throughout. If the user prefers `io.mindspice.loom`, the design is structurally identical — the name is a search-and-replace operation on packages and POM coordinates.

2. **Example tools**: The synthesis proposes `EchoTool`, `DateTimeTool`, and the built-in `ask_user`. The user may want different examples. The examples are documentation artifacts, not framework infrastructure — they are easily changed.

3. **JPMS module name**: Should the framework declare `Automatic-Module-Name: io.mindspice.turnloop` in its MANIFEST? The synthesis assumes yes — it's standard practice for libraries consumed by modular applications.

4. **Meta-starter**: Should `turnloop-autoconfigure` also serve as a `spring-boot-starter-turnloop` meta-starter that pulls in `turnloop-core` transitively? The synthesis assumes yes — this is the standard Spring Boot starter pattern and makes embedding a one-dependency operation.

5. **Model configuration file format**: Should the framework support the existing `ExternalAiConfigLoader` JSON format, or only `application.yml` and programmatic configuration? The synthesis supports all three: YAML properties, JSON file, and programmatic builder. The JSON file path is an auto-discovery fallback for users migrating from Magenta.

6. **Should `MarkdownRenderer` stay in `turnloop-core` with optional dependencies or move to its own module?** The synthesis keeps it in `turnloop-core` as a utility with optional dependencies. It is a single class — a separate module is overkill. Embedding apps that don't need HTML rendering simply don't have `commonmark` on their classpath and the utility is never loaded.

## Recommended Next Step

1. **User approves the unified direction** — naming (`io.mindspice.turnloop`), module structure (two modules), SPI surface (~15 interfaces), and phased plan (0-6). This synthesis serves as the design contract for implementation.

2. **Execute Phase 0 immediately** — scaffold the two Maven modules on the `core` branch, run the baseline build, and verify the existing test suite passes before any deletions.

3. **Proceed to Phase 1** — delete web/UI. This is the lowest-risk phase and produces immediate clarity on what remains.

4. **Prepare for Phase 2** — because Phase 2 (ChatService refactoring, 4-6 days) is the critical path and highest-risk phase, the Risk report's collaborator map and the Phased Migration report's method-level deletion plan should be combined into a single detailed work specification before implementation begins. The three SPIs introduced in Phase 2 (`ToolAccessPolicy`, `CompletionDetector`, `RepairStrategy`) should have their Java interfaces written and reviewed before `ChatService` surgery starts.

5. **After Phase 3, the framework shape is visible** — Phases 4-6 can parallelize sub-package work (tool framework extraction is independent of memory abstraction extraction) across subagents. The facade in Phase 5 is a thin delegation layer that can be built quickly once Phase 4 internals are stable.

**Estimated total effort**: 22-30 engineering days. Phase 2 is the critical path at 4-6 days. Phase 4 is second-largest at 4-5 days. All other phases are 1-3 days each.
