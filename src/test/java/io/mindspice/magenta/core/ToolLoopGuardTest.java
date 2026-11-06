package io.mindspice.magenta.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolLoopGuardTest {

    private static final ToolResult.Failure DENIED = new ToolResult.Failure(
        "request denied", ToolFailureCategory.ACCESS_DENIED);

    @Test
    void tripsOnExactlyTheConfiguredConsecutiveFailure() {
        ToolLoopGuard guard = new ToolLoopGuard(3);

        observe(guard, "lookup", "{\"id\":1}", DENIED);
        assertDoesNotThrow(() -> observe(guard, "lookup", "{\"id\":1}", DENIED));
        ToolException.LoopExceeded failure = assertThrows(
            ToolException.LoopExceeded.class,
            () -> observe(guard, "lookup", "{\"id\":1}", DENIED));

        assertEquals("lookup", failure.toolName());
        assertFalse(failure.getMessage().contains("request denied"));
        assertFalse(failure.getMessage().contains("id"));
    }

    @Test
    void successResetsTheRepeatedErrorStreakAndSuccessfulRepeatsNeverTrip() {
        ToolLoopGuard guard = new ToolLoopGuard(3);

        observe(guard, "lookup", "{}", DENIED);
        observe(guard, "lookup", "{}", DENIED);
        observe(guard, "lookup", "{}", new ToolResult.Success("ok"));
        observe(guard, "lookup", "{}", DENIED);
        assertDoesNotThrow(() -> observe(guard, "lookup", "{}", DENIED));
        assertThrows(ToolException.LoopExceeded.class,
            () -> observe(guard, "lookup", "{}", DENIED));

        ToolLoopGuard successful = new ToolLoopGuard(1);
        for (int index = 0; index < 20; index++) {
            assertDoesNotThrow(() -> observe(
                successful, "poll", "{}", new ToolResult.Success("ok")));
        }
    }

    @Test
    void differentCanonicalCallsRestartRatherThanConflateFailedRequests() {
        ToolLoopGuard guard = new ToolLoopGuard(3);

        for (int index = 0; index < 10; index++) {
            String arguments = index % 2 == 0 ? "{\"id\":1}" : "{\"id\":2}";
            assertDoesNotThrow(() -> observe(guard, "lookup", arguments, DENIED));
        }

        observe(guard, "lookup", "{\"id\":1}", DENIED);
        observe(guard, "lookup", "{\"id\":1}", DENIED);
        assertThrows(ToolException.LoopExceeded.class,
            () -> observe(guard, "lookup", "{\"id\":1}", DENIED));
    }

    @Test
    void differentFailureIdentitiesRestartTheStreak() {
        ToolLoopGuard guard = new ToolLoopGuard(3);
        ToolResult.Failure invalid = new ToolResult.Failure(
            "bad request", ToolFailureCategory.INVALID_ARGUMENTS);
        ToolResult.Failure otherInvalid = new ToolResult.Failure(
            "missing field", ToolFailureCategory.INVALID_ARGUMENTS);

        observe(guard, "lookup", "{}", DENIED);
        observe(guard, "lookup", "{}", DENIED);
        observe(guard, "lookup", "{}", invalid);
        observe(guard, "lookup", "{}", otherInvalid);
        observe(guard, "lookup", "{}", otherInvalid);
        assertThrows(ToolException.LoopExceeded.class,
            () -> observe(guard, "lookup", "{}", otherInvalid));
    }

    @Test
    void canonicalCallIdentityIsDeterministicAndDisclosureSafe() {
        ToolLoopGuard.ToolCallIdentity first = new ToolLoopGuard.ToolCallIdentity(
            "lookup", "{\"secret\":\"value\",\"count\":1.0}");
        ToolLoopGuard.ToolCallIdentity reordered = new ToolLoopGuard.ToolCallIdentity(
            "lookup", "{ \"count\": 1, \"secret\": \"value\" }");
        ToolLoopGuard.ToolCallIdentity different = new ToolLoopGuard.ToolCallIdentity(
            "lookup", "{\"secret\":\"other\",\"count\":1}");

        assertEquals(first, reordered);
        assertNotEquals(first, different);
        assertEquals(64, first.arguments().length());
        assertFalse(first.arguments().contains("secret"));
        assertFalse(first.arguments().contains("value"));
    }

    @Test
    void independentTurnGuardsDoNotShareHistory() {
        ToolLoopGuard template = new ToolLoopGuard(2);
        ToolLoopGuard first = template.forTurn();
        ToolLoopGuard second = template.forTurn();

        observe(first, "echo", "{}", DENIED);
        assertDoesNotThrow(() -> observe(second, "echo", "{}", DENIED));
        assertEquals(2, second.repeatedErroredToolCallLimit());
    }

    private static void observe(
        ToolLoopGuard guard,
        String toolName,
        String arguments,
        ToolResult result
    ) {
        guard.recordToolExecution(
            new ToolLoopGuard.ToolCallIdentity(toolName, arguments),
            new ToolLoopGuard.ToolExecutionResult(toolName, result));
    }
}
