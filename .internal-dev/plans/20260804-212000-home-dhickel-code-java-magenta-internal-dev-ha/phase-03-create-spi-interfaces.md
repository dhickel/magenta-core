# Phase 03 — Create SPI Interfaces

## Context

`ChatService` depends on `PromptContextAssembler`, `ToolAccessPolicy`, and `TerminalTurnRepair` as direct concrete-class references. These three components, in turn, depend on ~13 app-shell collaborators (`PlanService`, `TaskService`, `AgentJobService`, etc.) that must be extracted behind typed SPI interfaces before `ChatService` can be decoupled. This phase creates four temporary SPI interfaces in `ai/chat/service/turn/` and adapts the three existing components to implement them. The SPIs use Appendix A signatures to minimize Phase 07 rework. No `ChatService` callers are modified in this phase.

## Goal

Create temporary SPI interfaces matching handoff Appendix A signatures; adapt PromptContextAssembler, ToolAccessPolicy, and TerminalTurnRepair to implement SPIs

## Dependencies

phase-02-delete-web-ui.md

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/service/turn/InteractionHandler.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/PromptContributor.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/CompletionDetector.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/RepairStrategy.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/PromptContextAssembler.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/ToolAccessPolicy.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TerminalTurnRepair.java

- Create `InteractionHandler` SPI with `AnswerResult onQuestionsAsked(QuestionPendingContext context)`, define `QuestionPendingContext` record, and define sealed `AnswerResult` with four permitted subtypes (`Continue`, `Abort`, `SingleMessage`, `MultiMessage`) matching Appendix A
- Create `PromptContributor` SPI as `@FunctionalInterface` with `String contribute(TurnContext context)`
- Create `CompletionDetector` SPI as `@FunctionalInterface` with `boolean isComplete(TurnContext context)`
- Create `RepairStrategy` SPI with `List<Message> repair(TurnContext context, RepairReason reason)` and inner `RepairReason` enum (`EMPTY_RESPONSE`, `TOOL_LOOP_EXCEEDED`, `COMPLETION_NOT_DETECTED`, `CUSTOM`)
- Adapt `PromptContextAssembler` to implement `PromptContributor` via a new `contribute(TurnContext)` method that delegates to `mergeModePrompt()`; preserve existing `assemble()` methods unchanged as bridge entry points
- Add `allowAll()`, `allowList(Set<String>)`, `blockList(Set<String>)` static factories to `ToolAccessPolicy`; preserve all existing constants, constructors, and method signatures
- Adapt `TerminalTurnRepair` to implement both `CompletionDetector` (delegating `isComplete()` to `hasNoContentOrToolCalls()` and `needsExecutionCompletionRepair()` logic) and `RepairStrategy` (switching `repair()` on `RepairReason` to existing control-message methods)
- Verify `mvn compile` passes after all changes

## Out of Scope

- Modifying `ChatService` constructor, field declarations, or turn-loop logic (Phase 04)
- Removing `@Autowired(required = false)` annotations (Phase 04)
- Deleting app-shell packages or types (Phase 05)
- Moving types to framework packages (Phase 06+)
- Changing `PromptContextAssembler.assemble()` callers in `ChatService` at lines 2118 and 2437
- Converting `ToolAccessPolicy` to a `@FunctionalInterface` (Phase 07)

## Constraints

- All four new SPI files must compile without introducing new dependencies beyond the existing `ai/chat/service/turn/` package imports
- `PromptContextAssembler.assemble()` methods must continue to compile and behave identically — this phase adds `implements PromptContributor` without removing any existing public API
- `ToolAccessPolicy` static factories must not change the behavior of existing constructors or `filterToolsByMode()`
- `TerminalTurnRepair` repair logic must not change — only the interface delegation is added
- SPI method signatures match handoff Appendix A in parameter types, return types, and sealed hierarchy shape
- No Spring AI types (`org.springframework.ai.*`) in the four new SPI files (the three adapted files may retain existing Spring AI imports since they are already Magenta-scoped and will be deleted/replaced by Phase 07)

## Implementation Steps

### 1. Create InteractionHandler SPI

Create `InteractionHandler.java`:

```java
package io.mindspice.magenta2.ai.chat.service.turn;

import java.util.List;

public interface InteractionHandler {
    AnswerResult onQuestionsAsked(QuestionPendingContext context);

    record QuestionPendingContext(String sessionId, String turnId, List<String> questions) {}

    sealed interface AnswerResult
        permits AnswerResult.Continue, AnswerResult.Abort,
                AnswerResult.SingleMessage, AnswerResult.MultiMessage {
        record Continue() implements AnswerResult {}
        record Abort(String reason) implements AnswerResult {}
        record SingleMessage(String message) implements AnswerResult {}
        record MultiMessage(List<String> messages) implements AnswerResult {}
    }
}
```

All four `AnswerResult` subtypes and `QuestionPendingContext` are defined in the same file. No default implementation — callers that hit the `ask_user` path without a configured handler will throw `UnsupportedOperationException` (wired in Phase 04).

### 2. Create PromptContributor SPI

Create `PromptContributor.java`:

```java
package io.mindspice.magenta2.ai.chat.service.turn;

@FunctionalInterface
public interface PromptContributor {
    String contribute(TurnContext context);
}
```

The `TurnContext` parameter is the existing Magenta `TurnContext` (mutable carrier in `ai/chat/service/turn/`), not the framework-native `TurnContext` introduced in Phase 06. The method returns the assembled prompt string.

### 3. Create CompletionDetector SPI

Create `CompletionDetector.java`:

```java
package io.mindspice.magenta2.ai.chat.service.turn;

@FunctionalInterface
public interface CompletionDetector {
    boolean isComplete(TurnContext context);
}
```

### 4. Create RepairStrategy SPI

Create `RepairStrategy.java`:

```java
package io.mindspice.magenta2.ai.chat.service.turn;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

public interface RepairStrategy {
    List<Message> repair(TurnContext context, RepairReason reason);

    enum RepairReason {
        EMPTY_RESPONSE,
        TOOL_LOOP_EXCEEDED,
        COMPLETION_NOT_DETECTED,
        CUSTOM
    }
}
```

The return type is `org.springframework.ai.chat.messages.Message` (Spring AI) because this is a temporary Magenta-scoped bridge. Phase 07 replaces it with `TurnMessage`. `RepairReason.COMPLETION_NOT_DETECTED` covers the case where a plan/task execution turn finishes without calling the required completion tool.

### 5. Adapt PromptContextAssembler to implement PromptContributor

Add `implements PromptContributor` to the class declaration. Add the implementation method:

```java
@Override
public String contribute(TurnContext context) {
    PlanMode mode = context.interactionMode();
    String conversationId = context.conversationId();
    return mergeModePrompt(mode, conversationId);
}
```

- The `contribute()` method calls `mergeModePrompt(PlanMode, String)` (the existing no-trusted-policy overload). This preserves the full prompt composition order (mode/text, worktype, AGENTS.md, WORKSPACE.md, skill catalog).
- Existing `assemble(TurnContext ctx, TrustedPolicyResult trustedPolicy)` and `assemble(TurnContext ctx)` methods are **not renamed or removed**. They remain as bridge entry points for `ChatService` callers at lines 2118 and 2437.
- Do **not** modify `ChatService.java` in this step.
- Invariant: the prompt text returned by `contribute()` and the prompt text set on `ctx` by `assemble(TurnContext)` must be identical for a given context. Add a guard: if `ctx.interactionMode()` is null, fall back to `PlanMode.NORMAL`.

### 6. Add static factories to ToolAccessPolicy

Add three static factory methods to the existing `ToolAccessPolicy` class:

```java
public static ToolAccessPolicy allowAll() {
    return new ToolAccessPolicy(null, null, null, null);
}

public static ToolAccessPolicy allowList(Set<String> toolNames) {
    ToolAccessPolicy policy = new ToolAccessPolicy(null, null, null, null);
    // Stored for later use — the allowList semantics will be wired in Phase 04
    return policy;
}

public static ToolAccessPolicy blockList(Set<String> toolNames) {
    ToolAccessPolicy policy = new ToolAccessPolicy(null, null, null, null);
    // Stored for later use — the blockList semantics will be wired in Phase 04
    return policy;
}
```

- The factories exist to provide the SPI surface — their full `filterTools` behavior is wired in Phase 04 when `ToolAccessPolicy` is converted to a functional interface. For now, the factories return instances that preserve backward compatibility with existing `filterToolsByMode()` callers.
- Preserve all existing constants: `PLAN_MODE_TOOLS`, `TASK_MODE_TOOLS`, `NORMAL_BLOCKED_TOOLS`, `EXECUTION_BLOCKED_TOOLS`.
- Preserve all existing constructors, `interactionMode()`, `filterToolsByMode()`, and private helpers.
- Add a no-arg package-private constructor for the factory methods (or use the existing 4-arg constructor with null arguments).

**Edge case**: `allowAll()` with a null `ChatToolRegistry` must not throw — `filterToolsByMode()` already handles null `chatToolRegistry` by returning `List.of()`.

### 7. Adapt TerminalTurnRepair to implement RepairStrategy and CompletionDetector

Add `implements RepairStrategy, CompletionDetector` to the class declaration. Add two implementation methods:

```java
@Override
public boolean isComplete(TurnContext context) {
    // A turn is incomplete if the last response had no content and no tool calls,
    // OR if an execution-mode turn hasn't called its completion tool.
    PlanMode mode = context.interactionMode();
    String conversationId = context.conversationId();
    boolean hasEmptyResponse = /* inspect context for last response emptiness */ true;
    // Bridge: use context fields set by ChatService during turn execution.
    // If context carries a flag for empty last response, use it.
    // Otherwise, for now, delegate to the existing method if we can extract the ChatResponse.
    // For the temporary bridge, this method returns true only when the context
    // indicates completion (non-empty assistant text, no tool calls needed).
    // The precise logic is:
    //   - If plan/task execution is active and completion tool not called → false
    //   - If last assistant message had text and no tool calls → true
    //   - Otherwise → false
    return !needsExecutionCompletionRepair(conversationId, mode);
}

@Override
public List<Message> repair(TurnContext context, RepairReason reason) {
    PlanMode mode = context.interactionMode();
    String conversationId = context.conversationId();
    return switch (reason) {
        case EMPTY_RESPONSE -> List.of((Message) emptyFinalResponseControlMessage(mode));
        case TOOL_LOOP_EXCEEDED -> List.of(); // wired in Phase 04 with ToolUseAbort context
        case COMPLETION_NOT_DETECTED -> List.of(
            (Message) invalidExecutionCompletionControlMessage(mode)
        );
        case CUSTOM -> List.of();
    };
}
```

- All existing public methods (`hasNoContentOrToolCalls`, `emptyFinalResponseControlMessage`, `needsPlanTurnRepair`, `invalidPlanTurnControlMessage`, `needsExecutionCompletionRepair`, `invalidExecutionCompletionControlMessage`, `toolUseAbortControlMessage`) are **preserved unchanged**.
- `isComplete()` delegates to `needsExecutionCompletionRepair()` logic. For the temporary bridge, if no execution-mode state is active, completion is assumed true (the framework-native `DefaultCompletionDetector` in Phase 07 will add the empty-response check).
- `repair()` with `TOOL_LOOP_EXCEEDED` returns an empty list for now — the full `ToolUseAbort` wiring happens in Phase 04.
- `repair()` with `EMPTY_RESPONSE` delegates to `emptyFinalResponseControlMessage(mode)`.
- `repair()` with `COMPLETION_NOT_DETECTED` delegates to `invalidExecutionCompletionControlMessage(mode)`, which produces plan or task completion repair text based on mode.

**Invariant**: The control messages produced by `repair()` must be byte-for-byte identical to the existing methods' output. No repair text changes.

### 8. Verify compilation

Run `mvn compile -pl .` from the project root. Fix any compilation errors:
- Ensure all imports in new files resolve
- Ensure interface method signatures match implementing class method signatures
- Ensure no accidental removal of existing constructors or public methods

Common failure points: `TurnContext` methods called from the new `contribute()`/`isComplete()`/`repair()` methods must exist. `PlanMode` must be importable. Spring AI `Message` and `SystemMessage` imports must resolve in `RepairStrategy.java`.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/AGENTS.md`
- Handoff Appendix A at `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md` lines 720–790 (AnswerResult, QuestionPendingContext, InteractionHandler signatures) and lines 790–830 (PromptContributor, CompletionDetector, RepairStrategy signatures)

## Technical Guidance

- These are temporary Magenta-scoped SPIs in `ai/chat/service/turn/`. They will be deleted and replaced by framework-native SPIs under `io.mindspice.turnloop.spi` in Phase 07. Keep implementation minimal — the interfaces are the deliverable.
- `PromptContextAssembler.contribute()` extracts mode and conversationId from the Magenta `TurnContext` and delegates to the existing `mergeModePrompt(PlanMode, String)` overload. Do not duplicate prompt composition logic.
- `TerminalTurnRepair.isComplete()` is a coarse bridge method. The precise logic will be refined in Phase 04 when `ChatService` wires the SPI. The current `hasNoContentOrToolCalls(ChatResponse)` method cannot be called from `isComplete(TurnContext)` because the Magenta `TurnContext` does not carry the raw `ChatResponse`. The bridge returns a conservative result based on execution-mode state.
- The `ToolAccessPolicy` static factories create instances with null dependencies. This is safe because `filterToolsByMode()` already null-guards `chatToolRegistry` and returns `List.of()` when null. The factories are scaffolding for Phase 04.

## Validation

- `InteractionHandler.java` exists with `QuestionPendingContext` record and sealed `AnswerResult` interface permitting `Continue`, `Abort`, `SingleMessage`, `MultiMessage`
- `PromptContributor.java` exists as `@FunctionalInterface` with `String contribute(TurnContext)`
- `CompletionDetector.java` exists as `@FunctionalInterface` with `boolean isComplete(TurnContext)`
- `RepairStrategy.java` exists with `List<Message> repair(TurnContext, RepairReason)` and inner `RepairReason` enum with all four values
- `PromptContextAssembler` class declaration includes `implements PromptContributor` and has a compiling `contribute(TurnContext)` method; existing `assemble()` methods compile unchanged
- `ToolAccessPolicy` has `allowAll()`, `allowList(Set<String>)`, `blockList(Set<String>)` static factory methods that compile; all existing constants and methods preserved
- `TerminalTurnRepair` class declaration includes `implements RepairStrategy, CompletionDetector` and has compiling `repair(TurnContext, RepairReason)` and `isComplete(TurnContext)` methods; all existing public methods preserved
- `mvn compile` passes from project root
- No changes to `ChatService.java`

## Exit Criteria

- Four SPI interfaces created in `ai/chat/service/turn/` with Appendix-A-aligned signatures
- `PromptContextAssembler` implements `PromptContributor`
- `ToolAccessPolicy` has `allowAll()`, `allowList()`, `blockList()` static factories
- `TerminalTurnRepair` implements `RepairStrategy` and `CompletionDetector`
- `mvn compile` passes
