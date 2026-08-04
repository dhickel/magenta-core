# Changelog — Phase 03: Create SPI Interfaces

## Date
2026-08-04

## Git Commit

a19e6c868af8cb613fdceeeeb031526367a3f903

## Change Summary
Created four temporary SPI interfaces in `magenta-app/src/main/java/io/mindspice/magenta2/ai/chat/service/turn/` and adapted three existing turn-loop components to implement them, decoupling `ChatService`'s collaborators behind typed extension points ahead of the framework extraction:
- `InteractionHandler`: `AnswerResult onQuestionsAsked(QuestionPendingContext)` with nested `QuestionPendingContext(sessionId, turnId, questions)` record and sealed `AnswerResult` permitting `Continue`/`Abort`/`SingleMessage`/`MultiMessage`.
- `PromptContributor`: `@FunctionalInterface` `String contribute(TurnContext)`.
- `CompletionDetector`: `@FunctionalInterface` `boolean isComplete(TurnContext)`.
- `RepairStrategy`: `List<Message> repair(TurnContext, RepairReason)` with inner `RepairReason` enum (`EMPTY_RESPONSE`, `TOOL_LOOP_EXCEEDED`, `COMPLETION_NOT_DETECTED`, `CUSTOM`).
- `PromptContextAssembler` now implements `PromptContributor`; `contribute()` delegates to `mergeModePrompt(PlanMode, String)` with `PlanMode.NORMAL` null-guard; all `assemble()` methods preserved unchanged.
- `ToolAccessPolicy` gained static factories `allowAll()`, `allowList(Set<String>)`, `blockList(Set<String>)` (with `allowedTools`/`blockedTools` fields for Phase 04 wiring); all constants, constructors, and methods preserved.
- `TerminalTurnRepair` now implements `RepairStrategy` and `CompletionDetector`; `isComplete()` is a conservative bridge delegating to `needsExecutionCompletionRepair`; `repair()` dispatches on `RepairReason` to the existing control-message methods. All existing public methods preserved byte-for-byte.

## Files
- New: `InteractionHandler.java`, `PromptContributor.java`, `CompletionDetector.java`, `RepairStrategy.java`
- Modified: `PromptContextAssembler.java`, `ToolAccessPolicy.java`, `TerminalTurnRepair.java`
(all under `magenta-app/src/main/java/io/mindspice/magenta2/ai/chat/service/turn/`)

## Behavioral Impact
None at runtime — the interfaces are additive; `ChatService` still calls the existing methods. Signatures are Appendix-A-aligned so Phase 07's framework-native SPIs (under `io.mindspice.core.spi`) replace them with minimal rework. `RepairStrategy` is the only new SPI using a Spring AI type (`org.springframework.ai.chat.messages.Message`), intentionally, as a Magenta-scoped bridge.

## Specification Impact
None. Temporary bridge types; the framework contract is specified in the plan (`concepts.md` / handoff Appendix A) and realized in Phases 06–07.

## Risks
- `TerminalTurnRepair.isComplete()` is coarse (execution-mode state only); Phase 04 refines the wiring.
- `ToolAccessPolicy` factory instances have null dependencies; `filterToolsByMode()` null-guards the registry (returns `List.of()`), so no NPE risk.
- The SPIs are temporary and will be deleted/replaced in Phase 07 — no API-stability commitment.

## Follow-up Items
- Phase 04: remove the 13 app-shell collaborator references from `ChatService`; eliminate `@Autowired(required = false)` and ThreadLocal; collapse to single constructor.
