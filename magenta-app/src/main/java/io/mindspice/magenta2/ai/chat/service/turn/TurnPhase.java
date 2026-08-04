package io.mindspice.magenta2.ai.chat.service.turn;

/**
 * Explicit phase states for the tool-calling turn loop.
 * Replaces the implicit boolean-driven control flow with named phases
 * that make state transitions explicit and traceable.
 *
 * <pre>
 * PREPARE       -> INVOKE_MODEL
 * INVOKE_MODEL  -> EVALUATE
 * EVALUATE      -> EXECUTE_TOOLS | REPAIR | FINALIZE
 * EXECUTE_TOOLS -> INVOKE_MODEL (normal round calls model)
 *                | EVALUATE   (abort or plan/task completion)
 * REPAIR        -> INVOKE_MODEL | FINALIZE | THROW
 * FINALIZE      -> DONE
 * </pre>
 */
public enum TurnPhase {
    /** Assemble prompt, initialize loop state, build first Prompt */
    PREPARE,
    /** Call the model, collect thinking */
    INVOKE_MODEL,
    /** Decide next step based on response: tool calls, empty, completion, or done */
    EVALUATE,
    /** One round: guard, execute tools, checkpoint, detect completion */
    EXECUTE_TOOLS,
    /** Apply retry/repair logic in precedence order */
    REPAIR,
    /** Assemble final message, persist, audit */
    FINALIZE,
    /** Terminal state — turn is complete */
    DONE
}
