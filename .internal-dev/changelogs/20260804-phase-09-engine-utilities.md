# Changelog — Phase 09: Engine, Utilities, and Code Migration

## Date
2026-08-04

## Git Commit

277a29bc20dd6bf5be0b3f841c21191c79e5a867

## Change Summary
Built the framework's execution core in `mindspice-core` (four subphases) and migrated all surviving Magenta chat machinery into it:
- **9-A Engine**: `engine/DefaultTurnEngine` — package-private 6-state turn-loop state machine (PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) extracted from Magenta's ChatService, with `Flux<TurnEvent> stream()` / `TurnResult execute()`, interrupt polling via `ActiveTurnRegistry`, `ask_user` suspension via `InteractionHandler`/`InteractionStore`, `ToolLoopGuard` integration, `ThinkingExtractor` on chunks, async `CompactionStrategy` via `TurnExecutor`, model-capability checks, package-private Builder; `engine/DefaultPromptAssembler` (3-layer: system prompt → ordered `PromptContributor`s → turn instructions); `engine/ContextManager` (windowed queries, token budgets, compaction gating); `ExecutorStrategy` enum (VIRTUAL/FIXED).
- **9-B Concrete SPIs**: `DefaultToolRegistry` (two-stage public/framework maps, `ask_user` framework-internal tool, name-collision `ToolException`, `@Tool` reflection scanning), `SerialTurnGate` (per-session ReentrantLock state machine IDLE→EXECUTING→AWAITING_ANSWER→EXECUTING→IDLE, daemon 30s timeout scanner with Failed-event callback, drain semantics), `CompositeThinkingExtractor` (Ollama metadata → DeepSeek reasoningContent → `<think>` regex), `InMemoryInteractionStore`.
- **9-C Utilities**: `MarkdownRenderer` (optional commonmark/OWASP deps with lazy class-load degradation, GFM tables), `TurnEventRenderer` (JSON Lines + SSE formatters), `EchoTool`/`DateTimeTool` examples (no auto-registration), `TurnEvent` gained `@JsonTypeInfo`/`@JsonSubTypes` for all 8 subtypes.
- **9-D Migration**: created `ModelConfig`, `EndpointType`, `internal/DefaultModelRouter` (reflective provider detection, `SpringAiToolCallbackAdapter`), `DefaultToolResultSummarizer` (generic truncation, no hard-coded switch); deleted 32 Magenta main files including `ChatService.java` entirely (zero remaining references), `RequestResolver`, `ResolvedChatRequest`, `StoredContextUsage`, all of `service/turn/`, `tool/`, `rendering/`, `repository/`; deleted 13 test files and 1 META-INF services file; `ChatBeanConfig` simplified to `TokenCountEstimator`.

## Files
~30 new framework files + ~46 deleted Magenta files + `ChatBeanConfig.java` rewrite (see commit for full list).

## Behavioral Impact
The framework now has a complete, compiling execution core: engine, prompt assembly, context management, tool registry, turn gate, thinking extraction, interaction store, renderers, and model routing. Magenta is reduced to its remaining non-chat shell (config, application class, health) — the chat turn loop now lives only in `mindspice-core`. Both modules compile independently; 20 Magenta tests pass; the app context still starts (~1.6s).

## Specification Impact
None. Framework contract per plan (concepts.md / handoff Appendices A+C). Two accepted deviations: `internal/` interfaces (ModelRouter/TurnExecutor/ActiveTurnRegistry) are public (package-naming convention signals internal; handoff package-private intent documented in Javadoc); `TurnLoopException` is concrete (directly instantiated by `TurnEventRenderer`; sealed subclasses intact).

## Risks
- `TurnExecutor` has no concrete implementation yet — `DefaultTurnEngine.triggerAsyncCompaction()` NPEs at runtime until Phase 10 wires it (interface contract correct).
- `ContextManager.computeUsage()` hard-codes 128k max tokens; model-specific window integration deferred to later phases.
- `DefaultModelRouter` is reflection-based for optional providers; embedding apps can supply their own `ModelRouter` implementation.
- Dead code left for Phase 14: Magenta `model/TurnEvent.java` (superseded) and `AgentChatPromptService` (unreferenced).
- `commonmark 0.24.0` used (matches reactor dependency management) instead of handoff's 0.21.0 — deliberate.

## Follow-up Items
- Phase 10: `TurnLoop` facade + `TurnLoopBuilder` (wires TurnExecutor concretely, `answer()` resume path, close() lifecycle).
- Phase 11: autoconfigure module (properties, DataSource provisioning, StreamLifecycle, health, metrics).
