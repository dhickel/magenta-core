package io.mindspice.magenta2.ai.chat.service;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolLoopGuardTest {

    @Test
    void identicalToolCallLimitIsEnforced() {
        ToolLoopGuard guard = new ToolLoopGuard();
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function", "my_tool", "{}");

        for (int i = 0; i < 4; i++) {
            guard.recordToolCalls(List.of(call));
        }

        assertThatThrownBy(() -> guard.recordToolCalls(List.of(call)))
            .isInstanceOf(ToolUseAbort.class)
            .hasMessageContaining("5 identical calls")
            .hasMessageContaining("my_tool");
    }

    @Test
    void differentToolCallsDoNotTriggerAbort() {
        ToolLoopGuard guard = new ToolLoopGuard();

        for (int i = 0; i < 10; i++) {
            guard.recordToolCalls(List.of(
                new AssistantMessage.ToolCall("call-" + i, "function", "tool_" + i, "{}")
            ));
        }
    }

    @Test
    void whitespaceIsNormalizedBeforeCounting() {
        ToolLoopGuard guard = new ToolLoopGuard();
        AssistantMessage.ToolCall call1 = new AssistantMessage.ToolCall("c1", "function", "my_tool", "{\"key\":  \"val\"}");
        AssistantMessage.ToolCall call2 = new AssistantMessage.ToolCall("c2", "function", "my_tool", "{\"key\": \"val\"}");

        for (int i = 0; i < 4; i++) {
            guard.recordToolCalls(List.of(call1));
        }

        assertThatThrownBy(() -> guard.recordToolCalls(List.of(call2)))
            .isInstanceOf(ToolUseAbort.class)
            .hasMessageContaining("5 identical calls");
    }

    @Test
    void errorWindowLimitIsEnforcedWhenWindowFillsWithFiveErrors() {
        ToolLoopGuard guard = new ToolLoopGuard();

        // 4 errors + 3 successes = 7 items, window not full, 4 errors
        for (String response : List.of(
            "{\"timedOut\":true}",
            "{\"ok\":true}",
            "{\"timedOut\":true}",
            "{\"ok\":true}",
            "{\"timedOut\":true}",
            "{\"timedOut\":true}",
            "{\"ok\":true}"
        )) {
            guard.recordToolResponses(toolResult(response));
        }

        // 8th item fills window with 5 errors -> triggers
        assertThatThrownBy(() -> guard.recordToolResponses(toolResult("{\"timedOut\":true}")))
            .isInstanceOf(ToolUseAbort.class)
            .hasMessageContaining("5 errors in the last 8 tool responses");
    }

    @Test
    void errorsSlideOutOfWindowAfterSufficientSuccesses() {
        ToolLoopGuard guard = new ToolLoopGuard();

        // Fill window with all successes
        for (int i = 0; i < 8; i++) {
            guard.recordToolResponses(toolResult("{\"ok\":true}"));
        }

        // Now add errors one at a time with interleaved successes so that
        // by the time we accumulate 5 errors in the window, at least 3
        // of the original successes have fallen out
        guard.recordToolResponses(toolResult("{\"timedOut\":true}")); //  9: 1 error in window
        guard.recordToolResponses(toolResult("{\"ok\":true}"));       // 10: 1 error
        guard.recordToolResponses(toolResult("{\"timedOut\":true}")); // 11: 2 errors
        guard.recordToolResponses(toolResult("{\"ok\":true}"));       // 12: 2 errors
        guard.recordToolResponses(toolResult("{\"timedOut\":true}")); // 13: 3 errors
        guard.recordToolResponses(toolResult("{\"ok\":true}"));       // 14: 3 errors
        guard.recordToolResponses(toolResult("{\"timedOut\":true}")); // 15: 4 errors
        guard.recordToolResponses(toolResult("{\"ok\":true}"));       // 16: 4 errors
        // Window now has 8 items: 4 errors + 4 successes. Not triggered.

        // Two more errors should trigger: 4->5->6 errors in window, but
        // at least 4 of the original 8 successes have fallen out
        guard.recordToolResponses(toolResult("{\"timedOut\":true}"));

        assertThatThrownBy(() -> guard.recordToolResponses(toolResult("{\"timedOut\":true}")))
            .isInstanceOf(ToolUseAbort.class)
            .hasMessageContaining("5 errors in the last 8 tool responses");
    }

    @Test
    void mixedErrorsAndSuccessesBelowLimit() {
        ToolLoopGuard guard = new ToolLoopGuard();

        for (int i = 0; i < 30; i++) {
            guard.recordToolResponses(toolResult("{\"ok\":true}"));
            guard.recordToolResponses(toolResult("{\"timedOut\":true}"));
            guard.recordToolResponses(toolResult("{\"ok\":true}"));
        }
    }

    @Test
    void nullToolCallsDoesNotThrow() {
        ToolLoopGuard guard = new ToolLoopGuard();
        guard.recordToolCalls(null);
    }

    @Test
    void emptyToolCallsDoesNotThrow() {
        ToolLoopGuard guard = new ToolLoopGuard();
        guard.recordToolCalls(List.of());
    }

    @Test
    void nullToolExecutionResultDoesNotThrow() {
        ToolLoopGuard guard = new ToolLoopGuard();
        guard.recordToolResponses(null);
    }

    @Test
    void toolUseAbortCarriesRecentErrors() {
        ToolLoopGuard guard = new ToolLoopGuard();

        // 4 errors + 3 successes = 7 items
        for (String response : List.of(
            "{\"timedOut\":true}",
            "{\"ok\":true}",
            "{\"timedOut\":true}",
            "{\"ok\":true}",
            "{\"timedOut\":true}",
            "{\"timedOut\":true}",
            "{\"ok\":true}"
        )) {
            guard.recordToolResponses(toolResult(response));
        }
        // 8th error fills window and triggers
        assertThatThrownBy(() -> guard.recordToolResponses(toolResult("{\"timedOut\":true}")))
            .isInstanceOfSatisfying(ToolUseAbort.class, abort -> {
                assertThat(abort.recentErrors()).hasSize(5);
                assertThat(abort.recentErrors()).allMatch(e -> e.contains("timedOut"));
            });
    }

    private ToolExecutionResult toolResult(String responseData) {
        return ToolExecutionResult.builder()
            .conversationHistory(List.of(
                ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("call-id", "my_tool", responseData)))
                    .build()
            ))
            .build();
    }
}
