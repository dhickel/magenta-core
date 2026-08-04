# Phase 06 — Core Types and Records

## Context

With Magenta stripped to its chat core, framework-native types must be created in `turnloop-core`. This phase creates all data-carrier records and classes defined in the handoff Appendix A "Core Records and Types" section, plus three trivial dependency types (TurnPhase, ToolCallback, ToolDefinition) needed for compilation since TurnDiagnostic and TurnContext reference them. These types have zero dependencies on Spring, Spring AI, or any framework internals. This phase also adds the minimal framework dependencies to the `turnloop-core` POM.

## Goal

Create all core data-carrier records and enums per handoff Appendix A including TurnContext with typed AttributeKey, CompactionContext/Result, and session/queue records; add framework dependencies to turnloop-core POM

## In Scope

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop/TurnRequest.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnResult.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnMessage.java, turnloop-core/src/main/java/io/mindspice/turnloop/ContextUsage.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnDiagnostic.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolActivityData.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolActivityStatus.java, turnloop-core/src/main/java/io/mindspice/turnloop/NoticeKind.java, turnloop-core/src/main/java/io/mindspice/turnloop/TurnContext.java, turnloop-core/src/main/java/io/mindspice/turnloop/AttributeKey.java, turnloop-core/src/main/java/io/mindspice/turnloop/CompactionContext.java, turnloop-core/src/main/java/io/mindspice/turnloop/CompactionResult.java, turnloop-core/src/main/java/io/mindspice/turnloop/SessionFilter.java, turnloop-core/src/main/java/io/mindspice/turnloop/ConversationSession.java, turnloop-core/src/main/java/io/mindspice/turnloop/PendingMessage.java, turnloop-core/src/main/java/io/mindspice/turnloop/ClaimedMessage.java, turnloop-core/pom.xml

This phase also creates three dependency types required for compilation of the above, though not listed in the ledger targets:

- `turnloop-core/src/main/java/io/mindspice/turnloop/TurnPhase.java` — 7-value enum needed by TurnDiagnostic
- `turnloop-core/src/main/java/io/mindspice/turnloop/ToolCallback.java` — two-method interface needed by TurnContext
- `turnloop-core/src/main/java/io/mindspice/turnloop/ToolDefinition.java` — one-field record needed by ToolCallback

**Deliberately excluded** (belong to Phase 07): QuestionPendingContext, AnswerResult, ThinkingResult, AssistantMessageContent — these are tied to their owning SPI interfaces (InteractionHandler, ThinkingExtractor) and are created alongside them.

## Out of Scope

- Events, the TurnEvent sealed hierarchy, and TurnPhase enum usage beyond the minimal enum definition (Phase 07)
- Full SPI interfaces — only the minimal ToolCallback interface skeleton (Phase 07 creates the full SPI set)
- Framework-internal interfaces: ModelRouter, TurnExecutor, ActiveTurnRegistry (Phase 08)
- Persistence layer and SQLite-backed defaults (Phase 08)
- Engine, DefaultTurnEngine, DefaultPromptAssembler, ContextManager (Phase 09)
- Facade, TurnLoopBuilder, TurnLoop interface (Phase 10)

## Dependencies

phase-05-delete-app-shell.md

## Constraints

- All types must be in `io.mindspice.turnloop` package
- Zero dependencies on Spring, Spring AI, or servlet APIs in these types
- All types must match the handoff Appendix A signatures exactly
- `turnloop-core` must compile independently (`mvn compile -pl turnloop-core`)
- TurnPhase, ToolCallback, and ToolDefinition are created here as dependency types only; Phase 07 may verify and extend their signatures if the handoff requires it

## Implementation Steps

### Step 1 — Add POM dependencies

Open `turnloop-core/pom.xml` (created by Phase 01). Add these dependencies:

**Required (compile scope):**
- `com.fasterxml.jackson.core:jackson-databind` — needed for JSON serialization of records and metadata maps
- `io.projectreactor:reactor-core` — needed for `Flux<TurnEvent>` in the TurnLoop facade (Phase 10) but added now to avoid repeated POM edits
- `org.springframework.boot:spring-boot-starter-jdbc` — needed for JdbcTemplate in Phase 08 SQLite-backed defaults; no servlet/Spring MVC dependency
- `org.xerial:sqlite-jdbc` — SQLite JDBC driver for Phase 08; verify latest compatible version at implementation time (handoff suggests 3.50.3.0)
- `com.knuddels:jtokkit` — token estimation library for TokenEstimator SPI (Phase 07); verify latest compatible version at implementation time (handoff suggests 1.1.0)

**Optional (provided scope or optional=true):**
- `org.springframework.ai:spring-ai-starter-model-ollama` — for Ollama model support in Phase 08 ModelRouter
- `org.commonmark:commonmark` — for MarkdownRenderer in Phase 09; verify latest compatible version at implementation time (handoff suggests 0.24.0)
- `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` — for MarkdownRenderer HTML sanitization in Phase 09; verify latest compatible version at implementation time (handoff suggests 20240325.1)

**Note**: The Phase 01 scaffold POM may already include some of these. Check existing POM before adding duplicates. The implementer should verify the latest compatible versions at implementation time rather than relying on the versions noted above, which reflect the point-in-time handoff.

### Step 2 — Create TurnPhase enum (dependency type)

File: `turnloop-core/src/main/java/io/mindspice/turnloop/TurnPhase.java`

7-value enum matching the handoff's 6-state cycle plus DONE terminal state:

```java
package io.mindspice.turnloop;

public enum TurnPhase {
    PREPARE,
    INVOKE_MODEL,
    EVALUATE,
    EXECUTE_TOOLS,
    REPAIR,
    FINALIZE,
    DONE
}
```

Minimal — no methods, no annotations. Created here because TurnDiagnostic references it. The Phase 07 TurnEvent hierarchy also references it; the Phase 07 implementer must verify this enum matches their needs (it will) and not create a duplicate.

### Step 3 — Create ToolCallback interface and ToolDefinition record (dependency types)

File: `turnloop-core/src/main/java/io/mindspice/turnloop/ToolCallback.java`

```java
package io.mindspice.turnloop;

import io.mindspice.turnloop.NonNull;

public interface ToolCallback {
    @NonNull ToolDefinition getToolDefinition();
    @NonNull String call(@NonNull String toolInput);
}
```

File: `turnloop-core/src/main/java/io/mindspice/turnloop/ToolDefinition.java`

```java
package io.mindspice.turnloop;

import io.mindspice.turnloop.NonNull;

public record ToolDefinition(
    @NonNull String name,
    @NonNull String description,
    @NonNull String inputSchema
) {}
```

Minimal — two files. Created here because TurnContext references `ToolCallback`. Phase 07 creates the full `ToolRegistry` SPI and related types; the Phase 07 implementer must verify these signatures match the handoff (they will) and not create duplicates.

### Step 4 — Create the 16 ledger-target core type files

Create each file in `turnloop-core/src/main/java/io/mindspice/turnloop/` with exact signatures from the handoff Appendix A. Work through them in dependency order (types referenced by later types first).

**4a. AttributeKey.java**

```java
public record AttributeKey<T>(@NonNull String name, @NonNull Class<T> type) {
    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type);
    }
}
```

**4b. ToolActivityStatus.java**

```java
public enum ToolActivityStatus { COMPLETED, FAILED }
```

**4c. NoticeKind.java**

```java
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
```

**4d. ContextUsage.java**

```java
public record ContextUsage(int usedTokens, int triggerTokens, int maxTokens) {
    public double utilizationPercent() { return maxTokens > 0 ? (100.0 * usedTokens / maxTokens) : 0; }
}
```

**4e. TurnMessage.java**

```java
public record TurnMessage(
    @NonNull String role,
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
```

Uses `@NonNull` / `@Nullable` from `io.mindspice.turnloop.NonNull` (create as a package-level annotation if not already provided by a framework dependency). No Spring AI `Message` dependency.

**4f. TurnRequest.java**

```java
public record TurnRequest(
    @NonNull String sessionId,
    @NonNull String message,
    @Nullable String model,
    @Nullable List<String> toolNames,
    boolean stream,
    @Nullable Map<String, String> metadata
) {
    public static TurnRequest of(String sessionId, String message) {
        return new TurnRequest(sessionId, message, null, null, false, Map.of());
    }
}
```

**4g. ToolActivityData.java**

```java
public record ToolActivityData(
    @NonNull String toolName,
    @NonNull String toolCallId,
    @NonNull ToolActivityStatus status,
    @NonNull String summary
) {}
```

**4h. TurnDiagnostic.java**

```java
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
```

**4i. TurnResult.java**

```java
public record TurnResult(
    @NonNull String sessionId,
    @NonNull String turnId,
    @Nullable String message,
    @NonNull ContextUsage contextUsage,
    @NonNull TurnDiagnostic diagnostic,
    @NonNull List<ToolActivityData> toolActivities
) {}
```

**4j. CompactionContext.java**

```java
public record CompactionContext(
    @NonNull String sessionId,
    @NonNull List<TurnMessage> messagesToCompact,
    @NonNull ContextUsage currentUsage
) {}
```

**4k. CompactionResult.java**

```java
public record CompactionResult(
    @NonNull String summary,
    int messagesCompacted,
    int summaryTokenCount
) {}
```

**4l. SessionFilter.java** — note: the handoff uses `ConversationFilter` in prose but `SessionFilter` in the code block. Use `SessionFilter` to match the code block and the ledger target:

```java
public record SessionFilter(
    @Nullable Boolean favorite,
    @Nullable Boolean archived,
    @Nullable String origin,
    @Nullable String searchQuery,
    int limit,
    int offset
) {
    public static SessionFilter all() { return new SessionFilter(null, null, null, null, 50, 0); }
}
```

**4m. ConversationSession.java**

```java
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
```

**4n. PendingMessage.java**

```java
public record PendingMessage(
    @NonNull String messageId,
    @NonNull String sessionId,
    @NonNull String messageText,
    @NonNull Map<String, String> metadata,
    @NonNull Instant enqueuedAt
) {}
```

**4o. ClaimedMessage.java**

```java
public record ClaimedMessage(
    @NonNull PendingMessage message,
    @NonNull String claimToken,
    @NonNull Instant claimedAt
) {}
```

**4p. TurnContext.java** — mutable turn-scoped carrier, most complex file in this phase. Follow the handoff exactly:

```java
public final class TurnContext {
    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private final List<TurnMessage> messagesToPersist = new ArrayList<>();

    // Immutable fields set at turn start — store as private final fields, expose via accessors
    public TurnRequest request();
    public String sessionId();
    public String turnId();
    public String model();
    public boolean streaming();

    // Typed attribute access
    public <T> void setAttribute(AttributeKey<T> key, T value);
    public <T> T getAttribute(AttributeKey<T> key);
    public <T> T requireAttribute(AttributeKey<T> key);  // throws IllegalStateException if absent
    public boolean hasAttribute(AttributeKey<T> key);

    // Mutable turn state
    public List<TurnMessage> messagesToPersist();
    public ContextUsage currentContextUsage();
    public void currentContextUsage(ContextUsage v);
    public int toolRoundCount();
    public void incrementToolRoundCount();

    // Tool-related
    public List<ToolCallback> activeTools();
    public void activeTools(List<ToolCallback> v);
}
```

Thread-safety note: TurnContext is turn-scoped (one instance per turn, writes from the owning turn thread). The ConcurrentHashMap on attributes provides safe reads from asynchronous compaction or lifecycle-listener callbacks. The `messagesToPersist` list and mutable counters (`toolRoundCount`, `currentContextUsage`) are written only by the turn thread.

### Step 5 — Verify compilation

```bash
mvn compile -pl turnloop-core
```

Fix any compilation errors. The most likely errors are missing `@NonNull`/`@Nullable` annotations — if no dependency provides them (e.g., `org.eclipse.jdt.annotation` or `jakarta.annotation`), create a simple package-level annotation:

```java
package io.mindspice.turnloop;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
public @interface NonNull {}
```

(And an equivalent `@Nullable`.) This avoids pulling in a full annotations library for what are documentation/IDE markers.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- Handoff Appendix A for exact type definitions (`.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`)

## Technical Guidance

- Every record and class must match the handoff Appendix A field names, types, and method signatures exactly. Do not add convenience methods, extra constructors, or additional fields.
- `TurnMessage` is the framework-native message type — no Spring AI `Message` import.
- `TurnContext.requireAttribute()` throws `IllegalStateException` (unchecked), not a checked exception.
- Use `java.time.Instant` for all timestamp fields.
- `Map.of()` for empty immutable maps; `new ConcurrentHashMap<>()` for mutable maps.
- All public method parameters are `@NonNull` unless the handoff explicitly marks them `@Nullable`.

## Validation

- All 16 ledger-target Java files exist in `turnloop-core/src/main/java/io/mindspice/turnloop/`
- TurnPhase.java, ToolCallback.java, and ToolDefinition.java also exist (dependency types)
- All types match handoff Appendix A signatures exactly — verify field names, method names, parameter types, and return types against the appendix
- `turnloop-core/pom.xml` contains the required and optional dependencies
- `mvn compile -pl turnloop-core` passes with zero errors
- grep for `org.springframework.ai` in the created source files returns zero results
- grep for `jakarta.servlet` in the created source files returns zero results

## Exit Criteria

- All 19 Java files (16 ledger + 3 dependency) created and compile
- Framework POM dependencies added
- `turnloop-core` compiles independently from the root project
- Zero Spring AI or servlet imports in any created type
