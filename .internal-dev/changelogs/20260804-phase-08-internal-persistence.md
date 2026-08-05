# Changelog — Phase 08: Internal Interfaces and Persistence Layer

## Date
2026-08-04

## Git Commit

c65602c5ab0dd311525ddfd21c597c2aea584c86

## Change Summary
Created the framework-internal interfaces, utilities, migration system, schema, and SQLite-backed persistence defaults in `mindspice-core` (package `io.mindspice.core`, user-approved naming override):
- **Subphase A**: package-private `internal/ModelRouter` (sole Spring AI mapping boundary: `ChatModel`/`ToolCallingChatOptions`, 5 methods), `internal/TurnExecutor` (AutoCloseable, `submit` → CompletableFuture), `internal/ActiveTurnRegistry` (with inner `ActiveTurn` phase/interrupt API); public final `ToolLoopGuard` (identical-call + error-window sliding-window detection, exact exception messages, synchronized); `ToolArgumentCoercionModule` (extends Jackson `SimpleModule`, contextual coercion deserializer, service-loader registration); `Migration` interface + `MigrationRunner` (version-ordered, per-migration transactions with commit/rollback, `currentVersion`); `schema.sql` matching handoff Appendix B character-for-character (5 tables + all indexes); POM: `spring-ai-model` compile, `sqlite-jdbc` compile.
- **Subphase B**: `SqliteConversationMemory` (batchUpdate inserts, insertion-order reads, Jackson metadata round-trip), `SqliteSessionStore` (INSERT OR REPLACE, dynamic SessionFilter queries), `SqliteMessageQueueStore` (atomic `UPDATE ... WHERE claim_token IS NULL` claims, stale-claim recovery), `SqliteAuditWriter` (fire-and-forget JSON payload inserts) + `NoOpAuditWriter` (singleton default), `SpringAiConversationMemoryAdapter` (package-private bridge to Spring AI `ChatMemory`).

## Files
16 new/modified files under `mindspice-core/` (internal/, persistence/, root package, resources).

## Behavioral Impact
The framework now has its persistence and migration layer: self-migrating SQLite tables for conversation memory, session metadata, pending message queue, and audit log; a version-tracked migration runner; tool-loop guard; Jackson argument-coercion module (service-loader auto-registered). `ModelRouter` is the sole Spring AI mapping boundary — embedding apps never see Spring AI types through the public API.

## Specification Impact
None. Framework contract per plan (concepts.md / handoff Appendices A+B). One deviation documented: Spring AI 2.0.0 `ChatMemory.get(String)` has no `lastN` parameter; the adapter returns all messages (windowing left to callers), verified against the actual interface via javap.

## Risks
- `ToolArgumentCoercionModule` registers on `Object.class` — behavior with complex Jackson configs validated in Phase 12 tests.
- Spring AI 2.0.0 module layout (`spring-ai-model` provides ChatModel/ToolCallingChatOptions) may shift in future versions.
- `MigrationRunner` uses plain JDBC (no Spring transaction propagation) — intentional per spec.
- `SqliteAuditWriter` grows the audit table unboundedly (embedding-app retention responsibility, per plan open question).

## Follow-up Items
- Phase 09: engine (DefaultTurnEngine 6-state machine), DefaultPromptAssembler, ContextManager, DefaultToolRegistry, SerialTurnGate, CompositeThinkingExtractor, InMemoryInteractionStore, MarkdownRenderer, TurnEventRenderer, example tools; extract ChatService.toolChat() into the engine.
