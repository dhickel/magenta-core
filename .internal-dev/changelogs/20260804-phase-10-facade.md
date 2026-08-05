# Changelog — Phase 10: TurnLoop Facade

## Date
2026-08-04

## Git Commit

7fd6e0670cb64914737d9eabaebd4a847787bba7

## Change Summary
Created the framework's public facade in `mindspice-core` (three subphases):
- **10-A**: `TurnLoop` interface extending `AutoCloseable` — 10 facade methods (`send`, `stream`, `answer`, `enqueue`, `claim`, `ack`, `release`, `clearSession`, `interrupt`, `close`) + 3 sub-interfaces (`ToolSubinterface`, `MemorySubinterface`, `SessionSubinterface`) with exact handoff Appendix A signatures, framework `@NonNull`/`@Nullable` annotations, and thread-safety/nullability Javadoc.
- **10-B**: `DefaultTurnLoop` (package-private final, 807 lines) — orchestrates `DefaultTurnEngine`, `TurnGate`, `MessageQueue`, `InteractionStore`, `SessionStore`, `ConversationMemory`, `ActiveTurnRegistry`, `TurnExecutor`; exception wrapping at every public boundary; idempotent `close()` with the 7-step ordered drain; `Tools`/`Memory`/`Sessions` inner views. `DefaultTurnEngine` made public (Builder-only construction) and persists messages on `ask_user` suspension so `answer()` can resume.
- **10-C**: `TurnLoopBuilder` (public final, `create(ApplicationContext)` sole factory) — 24 setters (promptContributor cumulative), ApplicationContext auto-discovery with explicit-set tracking, 6-step `build()` validation, zero-external-config working instance. Created 8 concrete defaults: `DefaultTurnExecutor` (VIRTUAL/FIXED), `ConcurrentActiveTurnRegistry`, `DefaultMessageQueue`, `DefaultTokenEstimator` (JTokkit), `DefaultCompactionStrategy`, `DefaultCompletionDetector`, `DefaultRepairStrategy`, `DefaultModelCapabilityProvider`; `MessageQueueStore` SPI gained `releaseClaim(String)`; `DefaultModelRouter.fromContext()` factory; validator added `ModelRouter.closeChatModels()` + wiring into `close()` step 7.

## Files
`TurnLoop.java`, `DefaultTurnLoop.java`, `TurnLoopBuilder.java`, 8 new concrete classes, 3 SPI/internal interface updates, `engine/` visibility updates.

## Behavioral Impact
Embedding applications can now construct a complete, working `TurnLoop` via `TurnLoopBuilder.create(context).model(...).build()` with zero external configuration (in-memory SQLite). The facade provides chat send/stream, question-user answer flow, message queueing (enqueue/claim/ack/release), session/memory/tool sub-interfaces, interrupt, and graceful close. All internal and Spring AI exceptions are translated to the public `TurnLoopException` hierarchy at the facade boundary.

## Specification Impact
None. Framework contract per plan (concepts.md / handoff Appendix A). Accepted deviations: `DefaultTurnEngine` public (Builder guards construction; facade remains package-private); `MessageQueueStore.releaseClaim` SPI addition (needed by queue claim lifecycle).

## Risks
- Model construction is reflection-based (fragile against Spring AI API changes; embedding apps can supply their own `ModelRouter`).
- `TurnLoopMetrics` is auto-discovered only (no programmatic setter — handoff doesn't require one).
- No facade tests yet (Phase 12).
- `DefaultTurnExecutor`/`ConcurrentActiveTurnRegistry` created in this phase were previously flagged missing; now wired via builder.

## Follow-up Items
- Phase 11: `mindspice-core-autoconfigure` module (TurnLoopAutoConfiguration, TurnLoopProperties, DataSource provisioning, StreamLifecycle SSE adapter, TurnLoopHealthIndicator, TurnLoopMetrics Micrometer default).
