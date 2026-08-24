package io.mindspice.magenta.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ToolLoopGuardTest {
    @Test
    void independentTurnGuardsDoNotShareHistory() {
        ToolLoopGuard template = new ToolLoopGuard(1, 2, 1);
        ToolLoopGuard first = template.forTurn();
        ToolLoopGuard second = template.forTurn();
        ToolLoopGuard.ToolCallIdentity call = new ToolLoopGuard.ToolCallIdentity("echo", "{}");

        first.recordToolCalls(List.of(call));
        assertDoesNotThrow(() -> second.recordToolCalls(List.of(call)));
    }
}
