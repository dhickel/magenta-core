# Changelog — Phase 07: Events, Phases, Exceptions, and SPI Interfaces

## Date
2026-08-04

## Git Commit

0a1c1b902f8f0466e35516f95672bd12000e792f

## Change Summary
Created the framework's public API surface in `mindspice-core` (package `io.mindspice.core` / `io.mindspice.core.spi`, user-approved naming override):
- `TurnEvent` sealed interface with 8 inner records (Started, Chunk, ToolActivity, SystemNotice, QuestionPending, Interrupt, Completed, Failed), each carrying `Instant timestamp`, with common accessors `turnId()`/`timestamp()`/`contextUsage()` on the sealed interface; transport-agnostic (no HTML/rendered fields).
- Exception hierarchy: `TurnLoopException` (abstract, extends RuntimeException), `TurnFailedException` (sealed, inner `ContextExhaustedException` with TurnDiagnostic + ContextUsage), `ConversationNotFoundException` (final, sessionId), `ToolException` (sealed, 4 inner subtypes: ArgumentCoercionFailed, ExecutionFailed, LoopExceeded, AccessDenied), `ConfigurationException` (final, message + details map).
- `AnswerResult` sealed (Continue/Abort/SingleMessage/MultiMessage), `QuestionPendingContext(sessionId, turnId, questions)`, `ModelCapability` enum (STREAMING/TOOL_CALLING/VISION).
- All 20 SPI interfaces in `io.mindspice.core.spi`: ToolRegistry, ToolAccessPolicy (@FunctionalInterface + allowAll/allowList/blockList), ToolResultSummarizer (nested ToolExecution/SummarizeResult), InteractionHandler (+ static async() throwing UnsupportedOperationException), InteractionStore, ConversationMemory, SessionStore, TokenEstimator, CompactionStrategy, CompletionDetector (@FunctionalInterface), RepairStrategy (+ RepairReason enum), TurnGate (5 methods + sealed AcquireResult + TurnGateMode + GateState), MessageQueue, MessageQueueStore, AuditWriter (5 fire-and-forget void methods + ToolExecutionRecord/TurnError), PromptContributor (@FunctionalInterface), ModelCapabilityProvider, TurnLoopMetrics, TurnLifecycleListener (@FunctionalInterface), ThinkingExtractor (nested AssistantMessageContent/ThinkingResult).
- Verified Phase 06 `TurnPhase`, `ToolCallback`, `ToolDefinition` match handoff; no duplicates created.

## Files
29 new files under `mindspice-core/src/main/java/io/mindspice/core/` and `.../core/spi/`.

## Behavioral Impact
None at runtime — pure contracts (interfaces, records, enums, sealed types). This is the framework's public API surface that the engine (Phase 09), facade (Phase 10), and embedding applications consume. Zero Spring AI imports in all public API types.

## Specification Impact
None. The framework contract is specified in the plan (concepts.md / handoff Appendix A) and realized here per that specification. One handoff editorial note: `TurnFailedException` permits clause cannot list itself (Java rejects self-reference in sealed permits); implementation is the correct Java form.

## Risks
- `InteractionHandler.async()` throws `UnsupportedOperationException` by design (real async transport is an embedding-app concern, wired in autoconfigure Phase 11).
- Signatures are now the frozen public contract for Phases 08–10; any drift there is a defect.

## Follow-up Items
- Phase 08: framework-internal interfaces (ModelRouter, TurnExecutor, ActiveTurnRegistry), SQLite-backed SPI defaults, MigrationRunner, schema.sql.
