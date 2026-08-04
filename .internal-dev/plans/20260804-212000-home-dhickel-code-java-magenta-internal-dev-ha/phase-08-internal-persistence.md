# Phase 08 — Internal Interfaces and Persistence Layer

## Context

With the public API surface complete (Phase 07 delivered all 20 SPI interfaces, the TurnEvent sealed hierarchy, the exception hierarchy, and framework-native carrier types), the framework-internal interfaces, concrete utilities (`ToolLoopGuard`, `ToolArgumentCoercionModule`), and SQLite-backed persistence defaults must be created. This phase builds the persistence layer, migration system, and schema that the Phase 09 engine will use. The framework-internal interfaces (`ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`) define the boundary between the framework and Spring AI — `ModelRouter` is the sole mapping point where Spring AI types appear, and it is package-private so embedding apps never see them.

The persistence implementations use in-memory SQLite accessed via `JdbcTemplate` (spring-jdbc is a required dependency of `turnloop-core`). Every implementation self-migrates with `CREATE TABLE IF NOT EXISTS`. The `SpringAiConversationMemoryAdapter` bridges the framework's `ConversationMemory` SPI to Spring AI's `ChatMemory` interface for backward compatibility during Magenta re-integration.

## Goal

Create framework-internal interfaces (ModelRouter, TurnExecutor, ActiveTurnRegistry); implement ToolLoopGuard concrete final class with identical-call and error-window tracking; implement ToolArgumentCoercionModule with service-loader registration; implement all SQLite-backed SPI defaults with full CRUD and concurrent-access safety; implement SpringAiConversationMemoryAdapter bridging to Spring AI ChatMemory; implement MigrationRunner with Migration interface; create schema.sql with five tables per handoff Appendix B DDL

## In Scope

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop/internal/ModelRouter.java, turnloop-core/src/main/java/io/mindspice/turnloop/internal/TurnExecutor.java, turnloop-core/src/main/java/io/mindspice/turnloop/internal/ActiveTurnRegistry.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolLoopGuard.java, turnloop-core/src/main/java/io/mindspice/turnloop/ToolArgumentCoercionModule.java, turnloop-core/src/main/resources/META-INF/services/com.fasterxml.jackson.databind.Module, turnloop-core/src/main/java/io/mindspice/turnloop/persistence/SqliteConversationMemory.java, turnloop-core/src/main/java/io/mindspice/turnloop/persistence/SqliteSessionStore.java, turnloop-core/src/main/java/io/mindspice/turnloop/persistence/SqliteMessageQueueStore.java, turnloop-core/src/main/java/io/mindspice/turnloop/persistence/SqliteAuditWriter.java, turnloop-core/src/main/java/io/mindspice/turnloop/persistence/SpringAiConversationMemoryAdapter.java, turnloop-core/src/main/java/io/mindspice/turnloop/Migration.java, turnloop-core/src/main/java/io/mindspice/turnloop/MigrationRunner.java, turnloop-core/src/main/resources/schema.sql, turnloop-core/pom.xml

- Create three package-private framework-internal interfaces in `io.mindspice.turnloop.internal`: `ModelRouter` (sole mapping point to Spring AI `ChatModel` — returns `ChatModel`, `ToolCallingChatOptions` internally, never exposes Spring AI types through any public method; package-private interface, not visible to embedding apps), `TurnExecutor` (extends `AutoCloseable`, `submit(String sessionId, Supplier<T>)` returns `CompletableFuture<T>`), `ActiveTurnRegistry` (`ConcurrentHashMap`-backed, `register(String turnId) → ActiveTurn`, where `ActiveTurn` is an inner interface with `turnId()`, `phase(TurnPhase)`, `phase() → TurnPhase`, `pollInterrupt() → Optional<String>`, `queueInterrupt(String reason)`)
- Create `ToolLoopGuard` public final class in `io.mindspice.turnloop` with constructors `(int identicalCallLimit, int errorWindowSize, int errorWindowLimit)` and no-arg (defaults 5, 8, 5); inner record `ToolCallIdentity(String toolName, String arguments)`; method `recordToolCalls(List<ToolCallIdentity>)` throws `ToolException.LoopExceeded` when identical-call limit breached; method `recordToolResponses(ToolExecutionResult)` throws `ToolException.LoopExceeded` when error-window limit breached; tracking uses sliding windows in instance state
- Create `ToolArgumentCoercionModule` extending Jackson `SimpleModule` in `io.mindspice.turnloop` with service-loader registration file at `META-INF/services/com.fasterxml.jackson.databind.Module` containing the fully qualified class name
- Create SQLite persistence implementations in `io.mindspice.turnloop.persistence`: `SqliteConversationMemory` (implements `ConversationMemory`, uses `JdbcTemplate`, `addMessages()` batch insert via `batchUpdate()`, `getMessages()` returns insertion order via `ORDER BY id ASC`), `SqliteSessionStore` (implements `SessionStore`, full CRUD with `metadata_json` as JSON string column), `SqliteMessageQueueStore` (implements `MessageQueueStore`, `claimAtomic()` uses `UPDATE ... WHERE claim_token IS NULL` for atomicity, stale recovery via `findStaleClaims(Instant staleThreshold)`), `SqliteAuditWriter` (implements `AuditWriter`, all methods fire-and-forget void, store `payload_json` per event type), `NoOpAuditWriter` (implements `AuditWriter`, all methods no-op, the production default), `SpringAiConversationMemoryAdapter` (bridges `ConversationMemory` to Spring AI `ChatMemory`, adapts `TurnMessage` ↔ Spring AI `Message`)
- Create `Migration` interface (`int version()`, `void migrate(DataSource)`) and `MigrationRunner` final class (constructor takes `DataSource`, `apply(List<Migration>)` runs pending migrations in version order within single transactions, `currentVersion()` reads from `turnloop_schema_version` table)
- Create `schema.sql` with five tables per handoff Appendix B DDL: `turnloop_schema_version`, `turnloop_conversation_memory` (with indexes on `session_id, id` and `session_id, role`), `turnloop_session_metadata` (with indexes on `origin`, `favorite`, `archived`), `turnloop_pending_messages` (with indexes on `session_id`, `claim_token`, `claimed_at`), `turnloop_audit_log` (with indexes on `session_id, created_at` and `event_type, created_at`)
- Add `spring-jdbc` and `sqlite-jdbc` dependencies to `turnloop-core/pom.xml`
- Verify `mvn compile -pl turnloop-core` passes

## Out of Scope

- Engine implementation (`DefaultTurnEngine`, `DefaultPromptAssembler`, `ContextManager`) — Phase 09
- `SerialTurnGate`, `DefaultToolRegistry`, `CompositeThinkingExtractor`, `InMemoryInteractionStore` — Phase 09
- `MarkdownRenderer`, `TurnEventRenderer`, example tools — Phase 09
- `TurnLoop` facade, `DefaultTurnLoop`, `TurnLoopBuilder` — Phase 10
- Auto-configuration, `TurnLoopProperties`, `StreamLifecycle`, health, metrics — Phase 11
- Tests — Phase 12
- `spring-boot-starter-web`, `SseEmitter`, servlet API (permanently out of scope for `turnloop-core`)

## Dependencies

phase-07-events-spis.md

## Constraints

- `ModelRouter` is the sole mapping point between framework types and Spring AI types; it must be package-private (not visible to embedding app consumers)
- No Spring AI type may appear in any public (non-internal) signature
- All persistence implementations must use `CREATE TABLE IF NOT EXISTS` in their constructor or init — self-migrating tables
- `SqliteConversationMemory.addMessages()` must use `JdbcTemplate.batchUpdate()` for batch insert performance
- `SqliteMessageQueueStore.claimAtomic()` must use `UPDATE turnloop_pending_messages SET claim_token = ?, claimed_at = ? WHERE message_id = ? AND claim_token IS NULL` for atomic claim
- `MigrationRunner.apply()` must run each migration in a single transaction (commit after each migration, rollback on failure)
- All `AuditWriter` methods are fire-and-forget (void return, must not throw — catch and log internally)
- `SpringAiConversationMemoryAdapter` wraps a `ConversationMemory` instance and adapts `TurnMessage` ↔ Spring AI `Message`. Spring AI `Message` imports are permitted inside this adapter because it lives in the persistence package as an explicit bridge, but the adapter must be package-private
- `turnloop-core` must compile independently (`mvn compile -pl turnloop-core`)
- `schema.sql` DDL must match handoff Appendix B exactly — table names, column names, column types, index names, index columns

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/specifications/schema.md`
- Handoff Appendix A (internal interfaces section) and Appendix B (complete DDL)

## Technical Guidance

- `ModelRouter` is package-private — not visible to external consumers. Spring AI types (`ChatModel`, `ToolCallingChatOptions`) are permitted in its signature because this is the sole mapping boundary. The `ToolCallback` parameter is the framework-native type from Phase 07.
- `SpringAiConversationMemoryAdapter` wraps `ConversationMemory` and adapts `TurnMessage` ↔ Spring AI `Message`. It is package-private and lives in `persistence/`, not `internal/`.
- `SqliteConversationMemory.addMessages()` should use `JdbcTemplate.batchUpdate()` for batch inserts — do not insert messages one at a time.
- `MigrationRunner` is minimal: no `ScriptUtils` dependency. The `schema.sql` is applied as an initial migration (version 1) or can be run independently. Migrations execute in version order within single transactions.
- `SqliteAuditWriter` is optional — `NoOpAuditWriter` is the production default. Both must exist.
- `ToolLoopGuard` must be thread-safe. Use `synchronized` blocks on the internal deques or `ConcurrentLinkedDeque`s with atomic counters.
- `ActiveTurnRegistry` and `TurnExecutor` are interface-only in this phase — their concrete implementations live in Phase 09.
- `sqlite-jdbc` is a compile-scope dependency in `turnloop-core` because the persistence implementations import it directly.

## Implementation Steps

### Subphase A — Framework Internals, Utilities, Migration, and Schema

*One sequential DeepSeek implementation agent. After subphase A completes, subphase B begins with a fresh sequential agent of the same model.*

#### A1. Read prerequisite artifacts
Read the SPI interfaces created in Phase 07 that persistence implementations will implement: `ConversationMemory`, `SessionStore`, `MessageQueueStore`, `AuditWriter` — note their full method signatures, return types, and parameter contracts. Read the handoff Appendix A for internal interface signatures, `ToolLoopGuard` API, `Migration`/`MigrationRunner` signatures, and Appendix B for the complete DDL.

#### A2. Create framework-internal interfaces
Create three package-private interfaces in `io.mindspice.turnloop.internal`:

**`ModelRouter.java`**:
```java
interface ModelRouter {
    ChatModel springAiChatModel(String modelAlias);
    String remoteModelName(String modelAlias);
    ToolCallingChatOptions toolCallingOptions(String modelAlias, List<ToolCallback> tools);
    ToolCallingChatOptions basicChatOptions(String modelAlias);
    boolean supportsStreaming(String modelAlias);
}
```
Spring AI types (`ChatModel`, `ToolCallingChatOptions`) are permitted here — this is the sole mapping boundary. `ToolCallback` in the parameter list is the framework-native `io.mindspice.turnloop.ToolCallback`.

**`TurnExecutor.java`**:
```java
interface TurnExecutor extends AutoCloseable {
    <T> CompletableFuture<T> submit(String sessionId, Supplier<T> task);
    @Override void close();
}
```
The concrete virtual-thread implementation is created in Phase 09. This phase creates only the interface.

**`ActiveTurnRegistry.java`**:
```java
interface ActiveTurnRegistry {
    ActiveTurn register(String turnId);
    ActiveTurn get(String turnId);
    void remove(String turnId);
    List<ActiveTurn> activeTurns();

    interface ActiveTurn {
        String turnId();
        void phase(TurnPhase phase);
        TurnPhase phase();
        Optional<String> pollInterrupt();
        void queueInterrupt(String reason);
    }
}
```
Do not implement the concrete class — Phase 09 creates the `ConcurrentHashMap`-backed implementation. `TurnPhase` is the enum from Phase 07 at `io.mindspice.turnloop.TurnPhase`. Verify it exists before writing this interface.

#### A3. Create ToolLoopGuard
Create `ToolLoopGuard.java` as a public final class in `io.mindspice.turnloop`:

- Two constructors: `ToolLoopGuard(int identicalCallLimit, int errorWindowSize, int errorWindowLimit)` and no-arg `ToolLoopGuard()` defaulting to (5, 8, 5)
- Inner record `ToolCallIdentity(String toolName, String arguments)` — identity defined by the pair, string comparison on arguments
- `recordToolCalls(List<ToolCallIdentity> calls)`: append each call to an internal deque (max size = identicalCallLimit + 1). After append, scan from the back: count consecutive entries with the same toolName+arguments pair. If count > identicalCallLimit, throw `ToolException.LoopExceeded` with message `"Identical tool call loop detected: " + toolName + " called " + count + " consecutive times"`
- `recordToolResponses(ToolExecutionResult result)`: push success/failure into an internal `ArrayDeque<Boolean>` capped at `errorWindowSize`. After each push, if the count of `false` values in the window exceeds `errorWindowLimit`, throw `ToolException.LoopExceeded` with message `"Error loop detected: " + errorCount + " errors in last " + windowSize + " tool calls"`. Define inner record `ToolExecutionResult(boolean success, String toolName, String errorMessage)` if no suitable type from Phase 06.
- Both methods must be thread-safe: use `synchronized` on internal state or `ConcurrentLinkedDeque` with atomic counting.

#### A4. Create ToolArgumentCoercionModule
Create `ToolArgumentCoercionModule.java` as a public class in `io.mindspice.turnloop` extending `com.fasterxml.jackson.databind.module.SimpleModule`. Register a custom deserializer that attempts to coerce JSON string arguments into tool method parameter types. Log a warning and pass through when coercion is not possible. Create service-loader registration file at `turnloop-core/src/main/resources/META-INF/services/com.fasterxml.jackson.databind.Module` containing a single line: `io.mindspice.turnloop.ToolArgumentCoercionModule`.

#### A5. Create Migration interface and MigrationRunner
Create `Migration.java` as a public interface:
```java
public interface Migration {
    int version();
    void migrate(DataSource dataSource) throws Exception;
}
```

Create `MigrationRunner.java` as a public final class:
- Constructor takes `DataSource`
- `apply(List<Migration> migrations)`: sort by `version()` ascending. Query `SELECT COALESCE(MAX(version), 0) FROM turnloop_schema_version`. For each migration with `version > currentVersion`: begin transaction, call `migration.migrate(dataSource)`, insert version row, commit. On exception: rollback, wrap in unchecked exception, stop.
- `currentVersion()`: returns `SELECT COALESCE(MAX(version), 0) FROM turnloop_schema_version`
- Use `DataSource.getConnection()` directly — no Spring transaction management. Clean up connections in finally blocks.

#### A6. Create schema.sql
Create `turnloop-core/src/main/resources/schema.sql` by transcribing the handoff Appendix B DDL exactly. Every table uses `CREATE TABLE IF NOT EXISTS`. Every index uses `CREATE INDEX IF NOT EXISTS`. Five tables with all indexes — match Appendix B character-for-character.

#### A7. Update turnloop-core/pom.xml
Add required dependencies: `org.springframework:spring-jdbc` (compile), `org.xerial:sqlite-jdbc` (compile), `com.fasterxml.jackson.core:jackson-databind` (compile if not already present). Verify `org.springframework.ai:spring-ai-core` is present. Verify the Maven Enforcer `bannedDependencies` rule still prohibits web/servlet dependencies.

#### A8. Compile check
Run `mvn compile -pl turnloop-core`. On success, subphase A is complete; proceed to subphase B.

---

### Subphase B — SQLite Persistence Implementations

*One sequential DeepSeek implementation agent. Runs after subphase A completes successfully. All subphase A artifacts are available at compile time.*

#### B1. Read prerequisite artifacts
Re-read the SPI interfaces from Phase 07: `ConversationMemory`, `SessionStore`, `MessageQueueStore`, `AuditWriter`. Re-read handoff Appendix B DDL. Read `TurnMessage`, `ConversationSession`, `PendingMessage`, `ClaimedMessage`, `SessionFilter` type definitions from Phase 06.

#### B2. Create SqliteConversationMemory
`SqliteConversationMemory.java` in `io.mindspice.turnloop.persistence` implementing `ConversationMemory`:

- Constructor takes `DataSource`. Self-migrate with `CREATE TABLE IF NOT EXISTS turnloop_conversation_memory (...)` matching Appendix B.
- `getMessages(String sessionId)`: `SELECT ... WHERE session_id = ? ORDER BY id ASC`. Map rows to `TurnMessage`. Parse `metadata_json` via Jackson; null → `Map.of()`.
- `addMessages(String sessionId, List<TurnMessage> messages)`: `JdbcTemplate.batchUpdate()` with `INSERT INTO turnloop_conversation_memory (session_id, role, content, tool_name, tool_call_id, metadata_json) VALUES (?, ?, ?, ?, ?, ?)`. Serialize metadata map to JSON.
- `clear(String sessionId)`: `DELETE FROM turnloop_conversation_memory WHERE session_id = ?`.
- Handle `DataAccessException` by logging and re-throwing as unchecked.

#### B3. Create SqliteSessionStore
`SqliteSessionStore.java` in `io.mindspice.turnloop.persistence` implementing `SessionStore`:

- Constructor takes `DataSource`. Self-migrate with `CREATE TABLE IF NOT EXISTS turnloop_session_metadata (...)`.
- `save(ConversationSession)`: `INSERT OR REPLACE INTO turnloop_session_metadata (...) VALUES (...)`.
- `findById(String)`: `SELECT ... WHERE session_id = ?`. Return null if no row.
- `list(SessionFilter)`: dynamic query with `WHERE` clauses. `favorite`/`archived` as INTEGER 0/1. `searchQuery` → `title LIKE '%' || ? || '%'`. `LIMIT ? OFFSET ?`.
- `delete`, `updateTitle`, `setFavorite`, `setArchived`, `updateModel`: straightforward UPDATE/DELETE statements with `updated_at = datetime('now')`.

#### B4. Create SqliteMessageQueueStore
`SqliteMessageQueueStore.java` in `io.mindspice.turnloop.persistence` implementing `MessageQueueStore`:

- Constructor takes `DataSource`. Self-migrate.
- `insert(PendingMessage)`: `INSERT INTO turnloop_pending_messages (...)`.
- `findBySessionId`: `SELECT ... WHERE session_id = ? AND claim_token IS NULL ORDER BY enqueued_at ASC`.
- `findById`: `SELECT ... WHERE message_id = ?`. Return null if absent.
- `claimAtomic`: `UPDATE turnloop_pending_messages SET claim_token = ?, claimed_at = ? WHERE message_id = ? AND claim_token IS NULL`. Return `true` if rows updated == 1.
- `deleteByMessageId`, `deleteBySessionId`: straightforward DELETEs.
- `findStaleClaims`: `SELECT ... WHERE claim_token IS NOT NULL AND claimed_at < ?`.

#### B5. Create SqliteAuditWriter and NoOpAuditWriter
`NoOpAuditWriter.java` in `io.mindspice.turnloop.persistence` implementing `AuditWriter`: all methods no-op. Public final with static factory `NoOpAuditWriter instance()`.

`SqliteAuditWriter.java` in `io.mindspice.turnloop.persistence` implementing `AuditWriter`:

- Constructor takes `DataSource`. Self-migrate.
- Each method: serialize payload to JSON, `INSERT INTO turnloop_audit_log (session_id, event_type, payload_json) VALUES (?, ?, ?)`.
- All methods catch and log internally — must not throw. Use `org.slf4j.Logger`.

#### B6. Create SpringAiConversationMemoryAdapter
`SpringAiConversationMemoryAdapter.java` in `io.mindspice.turnloop.persistence` implementing `org.springframework.ai.chat.memory.ChatMemory`:

- Wraps `ConversationMemory` (constructor injection).
- `get(String conversationId, int lastN)`: get messages, take last N, map `TurnMessage` → Spring AI `Message` (`UserMessage`, `AssistantMessage`, `SystemMessage`, `ToolResponseMessage`).
- `add(String, List<Message>)`: reverse-map Spring AI messages to `TurnMessage` list, delegate to `conversationMemory.addMessages()`.
- `clear(String)`: delegate to `conversationMemory.clear()`.
- Package-private.

#### B7. Compile check
Run `mvn compile -pl turnloop-core`. On success, all subphase B artifacts exist and `turnloop-core` compiles independently.

---

## Validation

- All 3 internal interfaces exist in `internal/` with correct package-private visibility and signatures matching handoff Appendix A
- `ModelRouter` imports `org.springframework.ai.chat.model.ChatModel` and `ToolCallingChatOptions` — verify these are the only Spring AI imports in `internal/`
- `ToolLoopGuard` exists as public final class with both constructors, `ToolCallIdentity` inner record, `recordToolCalls()`, and `recordToolResponses()` methods
- `ToolArgumentCoercionModule` exists extending `SimpleModule` with service-loader registration file
- `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`, `NoOpAuditWriter`, and `SpringAiConversationMemoryAdapter` all exist in `persistence/`
- `Migration` interface and `MigrationRunner` class exist with correct method signatures
- `schema.sql` exists with exactly 5 `CREATE TABLE IF NOT EXISTS` statements and all indexes matching Appendix B
- `turnloop-core/pom.xml` includes `spring-jdbc`, `sqlite-jdbc`, `jackson-databind`, and `spring-ai-core` dependencies
- `mvn compile -pl turnloop-core` passes with zero errors

## Exit Criteria

- Three framework-internal interfaces created (`ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`)
- `ToolLoopGuard` created with identical-call and error-window tracking
- `ToolArgumentCoercionModule` created with service-loader registration
- All SQLite persistence implementations created with full CRUD and self-migration (`SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`, `NoOpAuditWriter`)
- `SpringAiConversationMemoryAdapter` created bridging `ConversationMemory` to Spring AI `ChatMemory`
- `Migration` interface and `MigrationRunner` created with version-ordered transactional application
- `schema.sql` exists with all five tables per handoff Appendix B DDL
- `turnloop-core` compiles independently (`mvn compile -pl turnloop-core`)
