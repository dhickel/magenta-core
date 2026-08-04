# Phase 07 — Events, Phases, Exceptions, and SPI Interfaces

## Context

With core data-carrier types from phase 06 in place, this phase creates the `TurnEvent` sealed hierarchy, `TurnPhase` enum, public exception hierarchy, all 20 SPI interfaces, and supporting types (`ToolCallback`, `ToolDefinition`, `AnswerResult`, `QuestionPendingContext`, `ModelCapability`). All signatures must match the handoff Appendix A exactly. These types form the public API surface that external consumers and the engine will use. No implementation logic belongs here — only interface contracts, records, enums, sealed types, and static factory methods where the handoff prescribes them.

## Goal

Create TurnEvent sealed hierarchy with all 8 subtypes and full field specifications; create TurnPhase enum; create public exception hierarchy (TurnLoopException, TurnFailedException, ConversationNotFoundException, ToolException sealed, ConfigurationException); create all 20 SPI interfaces per handoff Appendix A covering tool framework, interaction, memory, session, token estimation, compaction, completion detection, repair, gate, queue, audit, prompt, model capability, metrics, lifecycle, and thinking extraction

## In Scope

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop/TurnEvent.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnPhase.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoopException.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnFailedException.java, turnloop-core/src/main/java/io/mindspice/turnloop/ConversationNotFoundException.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolException.java, turnloop-core/src/main/java/io/mindspice/turnloop/ConfigurationException.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/ToolRegistry.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/ToolAccessPolicy.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/ToolResultSummarizer.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/InteractionHandler.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/InteractionStore.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/ConversationMemory.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/SessionStore.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/TokenEstimator.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/CompactionStrategy.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/CompletionDetector.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/RepairStrategy.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/TurnGate.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/MessageQueue.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/MessageQueueStore.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/AuditWriter.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/PromptContributor.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/ModelCapabilityProvider.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/TurnLoopMetrics.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/TurnLifecycleListener.java, turnloop-core/src/main/java/io/mindspice/turnloop/spi/ThinkingExtractor.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolCallback.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolDefinition.java, turnloop-core/src/main/java/io/mindspice/turnloop/AnswerResult.java, turnloop-core/src/main/java/io/mindspice/turnloop/QuestionPendingContext.java, turnloop-core/src/main/java/io/mindspice/turnloop/ModelCapability.java

- Create `TurnEvent.java` — sealed interface with 8 inner record subtypes, each carrying an `Instant timestamp`, plus common accessor methods on the sealed interface (`turnId()`, `timestamp()`, `contextUsage()`)
- Create `TurnPhase.java` — enum with 7 constants: `PREPARE`, `INVOKE_MODEL`, `EVALUATE`, `EXECUTE_TOOLS`, `REPAIR`, `FINALIZE`, `DONE`
- Create exception hierarchy in `io.mindspice.turnloop`: `TurnLoopException` (abstract, extends `RuntimeException`), `TurnFailedException` (sealed, permits `ContextExhaustedException`), `ConversationNotFoundException`, `ToolException` (sealed, permits `ArgumentCoercionFailed`, `ExecutionFailed`, `LoopExceeded`, `AccessDenied`), `ConfigurationException`
- Create framework-owned `ToolCallback` interface and `ToolDefinition` record in `io.mindspice.turnloop`
- Create `AnswerResult` sealed interface (permitting `Continue`, `Abort`, `SingleMessage`, `MultiMessage`) and `QuestionPendingContext` record
- Create `ModelCapability` enum (`STREAMING`, `TOOL_CALLING`, `VISION`)
- Create all 20 SPI interfaces in `io.mindspice.turnloop.spi` with exact method signatures, nested types, and static factories matching handoff Appendix A
- Verify `mvn compile -pl turnloop-core` passes with zero Spring AI imports in all created files

**Coordination note — AnswerResult and QuestionPendingContext**: Phase 06's plan also lists these types. Before creating them, check whether they already exist on disk. If they do, verify their signatures match the handoff Appendix A and skip recreation. If they don't exist, create them per this phase.

**Coordination note — ThinkingResult and AssistantMessageContent**: The handoff defines these as nested records inside `ThinkingExtractor`. Do not create standalone files for them; they live inside `ThinkingExtractor.java`. If phase 06 created standalone files, delete them and nest the types inside `ThinkingExtractor`.

## Out of Scope

- Framework-internal interfaces (`ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`) — Phase 08
- Persistence implementations — Phase 08
- Engine implementation — Phase 09
- Utility classes (`MarkdownRenderer`, `TurnEventRenderer`, `DefaultToolRegistry`, `SerialTurnGate`, `CompositeThinkingExtractor`) — Phase 09
- Facade (`TurnLoop`, `DefaultTurnLoop`, `TurnLoopBuilder`) — Phase 10

## Dependencies

phase-06-core-types.md

## Constraints

- All types must be in `io.mindspice.turnloop` or `io.mindspice.turnloop.spi` packages
- No `org.springframework.ai` import in any public API type
- All method signatures must match handoff Appendix A exactly — use the handoff as the authoritative source, not memory
- Every `ToolCallback` and `ToolDefinition` uses framework-native types only
- `TurnEvent` subtypes must be transport-agnostic (no HTML, no `renderedHtml`, no rendered markdown)
- `turnloop-core` must compile independently (`mvn compile -pl turnloop-core`)
- `@FunctionalInterface` on `ToolAccessPolicy`, `CompletionDetector`, `PromptContributor`, `TurnLifecycleListener`
- Sealed interfaces must declare all permits in the same file

## Implementation Steps

### 1. Create TurnEvent sealed hierarchy

Create `turnloop-core/src/main/java/io/mindspice/turnloop/TurnEvent.java` as a sealed interface with 8 inner record subtypes. The sealed interface declares three common accessor methods that every subtype implements:

```java
public sealed interface TurnEvent
    permits TurnEvent.Started, TurnEvent.Chunk, TurnEvent.ToolActivity,
            TurnEvent.SystemNotice, TurnEvent.QuestionPending,
            TurnEvent.Interrupt, TurnEvent.Completed, TurnEvent.Failed {

    @NonNull String turnId();
    @NonNull Instant timestamp();
    @Nullable ContextUsage contextUsage();
```

Each inner record has these fields exact from handoff:
- `Started`: `turnId`, `timestamp`, `interruptToken`, `contextUsage`
- `Chunk`: `turnId`, `timestamp`, `text`, `thinking` (boolean), `contextUsage`
- `ToolActivity`: `turnId`, `timestamp`, `toolName`, `toolCallId`, `status` (ToolActivityStatus), `summary`, `contextUsage`
- `SystemNotice`: `turnId`, `timestamp`, `kind` (NoticeKind), `message`, `contextUsage`
- `QuestionPending`: `turnId`, `timestamp`, `questionText`, `contextUsage`
- `Interrupt`: `turnId`, `timestamp`, `reason`, `contextUsage`
- `Completed`: `turnId`, `timestamp`, `diagnostic` (TurnDiagnostic), `contextUsage`
- `Failed`: `turnId`, `timestamp`, `diagnostic` (TurnDiagnostic), `contextUsage`

Every parameter marked `@NonNull` in the handoff, `@Nullable` only on `contextUsage`. The `timestamp` field is populated via `Instant.now()` at construction time by the engine; the record canonical constructor is sufficient.

### 2. Create TurnPhase enum

Create `turnloop-core/src/main/java/io/mindspice/turnloop/TurnPhase.java` as a plain enum with 7 constants: `PREPARE`, `INVOKE_MODEL`, `EVALUATE`, `EXECUTE_TOOLS`, `REPAIR`, `FINALIZE`, `DONE`. No additional fields or methods.

### 3. Create exception hierarchy

Create 6 files under `turnloop-core/src/main/java/io/mindspice/turnloop/`:

**TurnLoopException.java** — `public abstract class TurnLoopException extends RuntimeException`. Constructor takes `String message` and optional `Throwable cause`.

**TurnFailedException.java** — `public sealed class TurnFailedException extends TurnLoopException permits TurnFailedException, ContextExhaustedException`. Carries `TurnDiagnostic diagnostic` field. The `ContextExhaustedException` inner class (or same-file subclass) carries a `ContextUsage contextUsage` field. Both constructors pass message to super.

**ConversationNotFoundException.java** — `public final class ConversationNotFoundException extends TurnLoopException`. Carries `String sessionId` field. Single constructor `ConversationNotFoundException(String sessionId)`.

**ToolException.java** — `public sealed class ToolException extends TurnLoopException permits ToolException.ArgumentCoercionFailed, ToolException.ExecutionFailed, ToolException.LoopExceeded, ToolException.AccessDenied`. Carries `String toolName` field. Four inner permitted subtypes, each with their own fields:
- `ArgumentCoercionFailed`: `String toolName`, `String callId`, `String message`
- `ExecutionFailed`: `String toolName`, `String callId`, `String message`, `Throwable cause`
- `LoopExceeded`: `String toolName`, `String message`, `List<String> recentErrors`
- `AccessDenied`: `String toolName`, `String message`

**ConfigurationException.java** — `public final class ConfigurationException extends TurnLoopException`. Carries `String message` and `Map<String, String> details`. Constructor `ConfigurationException(String message, Map<String, String> details)`.

### 4. Create ToolCallback and ToolDefinition

Create two files in `io.mindspice.turnloop` (NOT `spi`):

**ToolCallback.java** — `public interface ToolCallback` with two methods:
```java
@NonNull ToolDefinition getToolDefinition();
@NonNull String call(@NonNull String toolInput);  // JSON string → JSON string
```

**ToolDefinition.java** — `public record ToolDefinition(@NonNull String name, @NonNull String description, @NonNull String inputSchema) {}`. `inputSchema` is a JSON Schema string.

### 5. Create AnswerResult and QuestionPendingContext

If not already created by phase 06:

**AnswerResult.java** — `public sealed interface AnswerResult permits AnswerResult.Continue, AnswerResult.Abort, AnswerResult.SingleMessage, AnswerResult.MultiMessage`. Four inner records:
- `record Continue() implements AnswerResult {}`
- `record Abort(@NonNull String reason) implements AnswerResult {}`
- `record SingleMessage(@NonNull String message) implements AnswerResult {}`
- `record MultiMessage(@NonNull List<String> messages) implements AnswerResult {}`

**QuestionPendingContext.java** — `public record QuestionPendingContext(@NonNull String sessionId, @NonNull String turnId, @NonNull List<String> questions) {}`.

### 6. Create ModelCapability enum

**ModelCapability.java** — `public enum ModelCapability { STREAMING, TOOL_CALLING, VISION }`. No additional fields.

### 7. Create tool-framework SPI interfaces

Create under `io.mindspice.turnloop.spi`:

**ToolRegistry.java** — 6 methods: `register(ToolCallback)`, `registerFrameworkTool(ToolCallback)`, `registerToolBean(Object)`, `unregister(String)`, `definitions()` (returns `List<ToolDefinition>`, public-only), `resolveForTurn(TurnContext)` (returns `List<ToolCallback>`, merges public + framework).

**ToolAccessPolicy.java** — `@FunctionalInterface` with method `List<ToolCallback> filterTools(List<ToolCallback> availableTools, TurnContext context)`. Three static factories: `allowAll()` (identity), `allowList(Set<String> toolNames)` (inclusive filter by tool name), `blockList(Set<String> toolNames)` (exclusive filter by tool name).

**ToolResultSummarizer.java** — Method `SummarizeResult summarize(ToolExecution execution)`. Two nested records:
- `record ToolExecution(@NonNull String toolName, @NonNull String toolCallId, @NonNull String arguments, @NonNull String rawResult) {}`
- `record SummarizeResult(@NonNull String summary, boolean truncated) {}`

### 8. Create interaction SPI interfaces

**InteractionHandler.java** — Method `AnswerResult onQuestionsAsked(QuestionPendingContext context)`. One static method: `static InteractionHandler async()` — throws `UnsupportedOperationException` with message referencing notification transport requirement. (Full async implementation is an embedding-app concern.)

**InteractionStore.java** — 3 methods: `void save(String sessionId, QuestionPendingContext context)`, `@Nullable QuestionPendingContext load(String sessionId)`, `void delete(String sessionId)`.

### 9. Create memory, session, and token SPI interfaces

**ConversationMemory.java** — 3 methods: `List<TurnMessage> getMessages(String sessionId)` (insertion order, oldest first), `void addMessages(String sessionId, List<TurnMessage> messages)`, `void clear(String sessionId)`.

**SessionStore.java** — 8 methods: `save(ConversationSession)`, `@Nullable findById(String sessionId)`, `List<ConversationSession> list(SessionFilter filter)`, `delete(String sessionId)`, `updateTitle(String sessionId, String title)`, `setFavorite(String sessionId, boolean favorite)`, `setArchived(String sessionId, boolean archived)`, `updateModel(String sessionId, String model)`.

**TokenEstimator.java** — 2 methods: `int estimateTokens(String text)`, `int estimateTokens(TurnMessage message)`.

**CompactionStrategy.java** — 1 method: `CompactionResult compact(CompactionContext context)`.

### 10. Create completion, repair, and gate SPI interfaces

**CompletionDetector.java** — `@FunctionalInterface` with method `boolean isComplete(TurnContext context)`.

**RepairStrategy.java** — Method `List<TurnMessage> repair(TurnContext context, RepairReason reason)`. Nested `enum RepairReason { EMPTY_RESPONSE, TOOL_LOOP_EXCEEDED, COMPLETION_NOT_DETECTED, CUSTOM }`.

**TurnGate.java** — The most complex SPI. 5 methods: `AcquireResult acquire(String sessionId, TurnGateMode mode)`, `void release(String sessionId)`, `void transitionToAwaitingAnswer(String sessionId)`, `void drain(long timeoutMs)`, `GateState state(String sessionId)`. Three nested types:
- `sealed interface AcquireResult permits AcquireResult.Acquired, AcquireResult.Rejected, AcquireResult.Queued` with inner records:
  - `record Acquired(@NonNull String lockToken) implements AcquireResult {}`
  - `record Rejected(@NonNull String reason) implements AcquireResult {}`
  - `record Queued(@NonNull String claimToken) implements AcquireResult {}`
- `enum TurnGateMode { SEND, STREAM, ANSWER }`
- `enum GateState { IDLE, EXECUTING, AWAITING_ANSWER }`

### 11. Create queue and audit SPI interfaces

**MessageQueue.java** — 7 methods: `PendingMessage enqueue(String sessionId, String messageText, Map<String,String> metadata)`, `List<PendingMessage> list(String sessionId)`, `@Nullable ClaimedMessage claim(String sessionId)`, `void ack(String claimToken)`, `void release(String claimToken)`, `List<ClaimedMessage> recoverStaleClaims(Duration staleAge)`, `void deleteBySessionId(String sessionId)`.

**MessageQueueStore.java** — 6 methods: `void insert(PendingMessage message)`, `List<PendingMessage> findBySessionId(String sessionId)`, `@Nullable PendingMessage findById(String messageId)`, `boolean claimAtomic(String messageId, String claimToken, Instant claimedAt)`, `void deleteByMessageId(String messageId)`, `void deleteBySessionId(String sessionId)`, `List<PendingMessage> findStaleClaims(Instant staleThreshold)`.

**AuditWriter.java** — 5 fire-and-forget methods (all return `void`): `recordUserMessage(String sessionId, TurnMessage message)`, `recordAssistantMessage(String sessionId, TurnMessage message)`, `recordToolExecution(String sessionId, ToolExecutionRecord record)`, `recordContextUsage(String sessionId, ContextUsage usage, String model)`, `recordError(String sessionId, TurnError error)`. Two nested records:
- `record ToolExecutionRecord(@NonNull String toolName, @NonNull String toolCallId, @NonNull String arguments, @NonNull String resultSummary, boolean success, long durationMs) {}`
- `record TurnError(@NonNull String summary, @Nullable String stackTrace, @Nullable String model) {}`

### 12. Create prompt, model-capability, metrics, lifecycle, and thinking SPI interfaces

**PromptContributor.java** — `@FunctionalInterface` with method `String contribute(TurnContext context)`.

**ModelCapabilityProvider.java** — 1 method: `Set<ModelCapability> capabilities(String modelAlias)`.

**TurnLoopMetrics.java** — 4 methods: `void recordTurnDuration(String model, String outcome, long durationMs)`, `void recordToolCall(String toolName, String status)`, `void setActiveTurns(long count)`, `void setQueueDepth(String sessionId, long depth)`.

**TurnLifecycleListener.java** — `@FunctionalInterface` with method `void onTurnCompleted(TurnResult result)`.

**ThinkingExtractor.java** — Method `ThinkingResult extract(AssistantMessageContent content)`. Two nested records:
- `record AssistantMessageContent(@NonNull String rawText, @Nullable String thinkingMetadata, @Nullable String reasoningContent, @NonNull Map<String, Object> metadata) {}`
- `record ThinkingResult(@NonNull String visibleText, @NonNull String thinkingText) {}`

### 13. Verify compilation

Run `mvn compile -pl turnloop-core`. Fix any missing imports or signature mismatches. Then grep to confirm zero Spring AI imports:

```bash
grep -r "org.springframework.ai" turnloop-core/src/main/java/io/mindspice/turnloop/ --include="*.java"
```

Must return zero results.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- Handoff Appendix A (`.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`) — authoritative source for every method signature, field, and nested type

## Technical Guidance

- **TurnEvent sealed interface**: All 8 permits must be declared in `TurnEvent.java`. Use inner records, not separate files. The `timestamp` field in each record enables transport-layer ordering; the engine sets it via `Instant.now()` at emission time.
- **Sealed permits in single file**: `TurnEvent`, `TurnFailedException`, `ToolException`, `AnswerResult`, `TurnGate.AcquireResult` all use sealed types whose permits are inner classes in the same file. Do not create separate files for inner permitted subtypes.
- **ToolAccessPolicy static factories**: `allowList` and `blockList` compare against `tool.getToolDefinition().name()`. The lambdas must compile with `@FunctionalInterface`.
- **InteractionHandler.async()**: Throws `UnsupportedOperationException` for now — the real async implementation is an embedding-app concern wired in autoconfigure.
- **TurnGate.acquire() mode parameter**: `SEND` and `STREAM` require `IDLE` state; `ANSWER` requires `AWAITING_ANSWER` state. This is enforced by the implementation in Phase 09; the interface just declares the signature.
- **AuditWriter methods are fire-and-forget**: All return `void`. Implementations must catch and suppress their own exceptions — callers do not handle audit failures.
- **@NonNull on all parameters**: Every public method parameter is `@NonNull` unless the handoff marks it `@Nullable`.
- **@Nullable on return types**: Mark `@Nullable` only where the handoff explicitly documents nullable returns (e.g., `InteractionStore.load()`, `SessionStore.findById()`, `MessageQueueStore.findById()`, `MessageQueue.claim()`).
- **Imports needed**: Types created in phase 06 (`TurnContext`, `TurnDiagnostic`, `TurnMessage`, `ContextUsage`, `ToolActivityStatus`, `NoticeKind`, `ToolActivityData`, `TurnRequest`, `TurnResult`, `CompactionContext`, `CompactionResult`, `SessionFilter`, `ConversationSession`, `PendingMessage`, `ClaimedMessage`, `AttributeKey`) are referenced by these SPIs. Use their fully-qualified names if unsure about import paths (`io.mindspice.turnloop.*`).

## Validation

1. `TurnEvent.java` exists with sealed interface, 8 inner record subtypes, each carrying `Instant timestamp` and matching handoff fields exactly
2. `TurnPhase.java` exists with 7 enum constants
3. All 6 exception files exist with correct sealed permits and fields
4. `ToolCallback.java` and `ToolDefinition.java` exist in `io.mindspice.turnloop` (not `spi`)
5. `AnswerResult.java` and `QuestionPendingContext.java` exist (created by this phase or verified from phase 06)
6. `ModelCapability.java` exists with 3 enum constants
7. All 20 SPI interface files exist in `io.mindspice.turnloop.spi` with complete method signatures and all nested types
8. Every `@FunctionalInterface` annotation is present where specified
9. `mvn compile -pl turnloop-core` passes
10. `grep -r "org.springframework.ai" turnloop-core/src/main/java/io/mindspice/turnloop/ --include="*.java"` returns zero results
11. 32 specific `.java` files from the Write Targets list exist on disk

## Exit Criteria

- TurnEvent sealed hierarchy with all 8 subtypes and full field specifications created
- TurnPhase enum with 7 constants created
- Public exception hierarchy created (TurnLoopException abstract, TurnFailedException sealed with ContextExhaustedException, ConversationNotFoundException, ToolException sealed with 4 subtypes, ConfigurationException)
- All 20 SPI interfaces created with exact handoff signatures, nested types, and static factories
- Supporting types (ToolCallback, ToolDefinition, AnswerResult, QuestionPendingContext, ModelCapability) created
- `turnloop-core` compiles independently
- Zero Spring AI imports in any file under `io/mindspice/turnloop/` or `io/mindspice/turnloop/spi/`
