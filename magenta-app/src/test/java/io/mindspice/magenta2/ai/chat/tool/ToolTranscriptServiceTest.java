package io.mindspice.magenta2.ai.chat.tool;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTranscriptServiceTest {
    private final ToolTranscriptService service = new ToolTranscriptService(new ObjectMapper());

    @Test
    void truncatesLargeToolOutputAfterFourUserTurns() {
        SystemMessage toolResult = service.fullResult("call-1", "search_notes", "{\"query\":\"deploy\"}", "x".repeat(4_100));

        List<Message> rewritten = service.truncateExpiredLargeResults(List.of(
            toolResult,
            new UserMessage("one"),
            new UserMessage("two"),
            new UserMessage("three"),
            new UserMessage("four")
        ));

        Message rewrittenToolResult = rewritten.getFirst();
        assertThat(service.isSummaryToolTranscript(rewrittenToolResult)).isTrue();
        assertThat(service.renderForModel(rewrittenToolResult))
            .contains("Raw output: truncated")
            .doesNotContain("x".repeat(100));
    }

    @Test
    void keepsSmallToolOutputFullEvenAfterFourUserTurns() {
        SystemMessage toolResult = service.fullResult("call-1", "read_note", "{\"id\":\"1\"}", "small result");

        List<Message> rewritten = service.truncateExpiredLargeResults(List.of(
            toolResult,
            new UserMessage("one"),
            new UserMessage("two"),
            new UserMessage("three"),
            new UserMessage("four")
        ));

        assertThat(rewritten.getFirst()).isSameAs(toolResult);
        assertThat(service.renderForModel(rewritten.getFirst())).contains("small result");
    }

    @Test
    void rendersHistoryWithoutRawOutput() {
        SystemMessage toolResult = service.fullResult("call-1", "read_note", "{\"id\":\"1\"}", "sensitive raw result");

        assertThat(service.renderForHistory(toolResult))
            .contains("Tool read_note completed")
            .doesNotContain("sensitive raw result");
    }

    @Test
    void hardCapsStoredRawOutput() {
        SystemMessage toolResult = service.fullResult("call-1", "large_tool", "{}", "x".repeat(50_000));

        assertThat(toolResult.getText()).hasSizeLessThan(45_000);
        assertThat(service.renderForModel(toolResult))
            .contains("Raw output: truncated")
            .doesNotContain("x".repeat(1_000));
    }

    @Test
    void createsToolSpecificDisplayActivity() {
        SystemMessage toolResult = service.fullResult(
            "call-1",
            "file_read",
            "{\"path\":\"notes/today.md\",\"startLine\":2}",
            "{\"path\":\"notes/today.md\",\"totalLines\":10,\"startLine\":2,\"endLine\":4,\"nextStartLine\":5,\"lines\":[\"2:abc|hello\"]}"
        );

        var activity = service.activityFor(toolResult);

        assertThat(activity.toolName()).isEqualTo("file_read");
        assertThat(activity.summary()).isEqualTo("Read notes/today.md lines 2-4 of 10 total lines.");
        assertThat(activity.callDetail()).contains("\"path\" : \"notes/today.md\"");
        assertThat(activity.resultDetail()).contains("\"nextStartLine\" : 5");
    }

    @Test
    void displayActivityCapsExpandedArgumentsAndResults() {
        SystemMessage toolResult = service.fullResult(
            "call-1",
            "file_write",
            "{\"path\":\"big.md\",\"content\":\"" + "a".repeat(12_000) + "\"}",
            "{\"path\":\"big.md\",\"bytesWritten\":12000,\"created\":true,\"echo\":\"" + "b".repeat(12_000) + "\"}"
        );

        var activity = service.activityFor(toolResult);

        assertThat(activity.callDetail()).hasSizeLessThanOrEqualTo(10_000);
        assertThat(activity.resultDetail()).hasSizeLessThanOrEqualTo(10_000);
        assertThat(activity.callTruncated()).isTrue();
        assertThat(activity.resultTruncated()).isTrue();
    }

    @Test
    void shellSummariesAreCappedForCollapsedToolCards() {
        SystemMessage toolResult = service.fullResult(
            "call-1",
            "shell_exec",
            "{\"command\":\"printf\",\"args\":[\"" + "a".repeat(300) + "\"]}",
            "{\"commandLine\":\"printf " + "a".repeat(300) + "\",\"workingDirectory\":\"/tmp/" + "b".repeat(200) + "\",\"exitCode\":0,\"stdout\":\"ok\",\"stderr\":\"\",\"timedOut\":false,\"truncated\":false}"
        );

        var activity = service.activityFor(toolResult);

        assertThat(activity.summary()).hasSizeLessThanOrEqualTo(180);
        assertThat(activity.summary()).contains("[truncated]");
    }
}
