package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.spi.TurnGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnResponseProtocolTest {

    private static final Instant TIME = Instant.parse("2026-08-24T13:49:27Z");
    private static final ContextUsage USAGE = new ContextUsage(10, 100, 4096);
    private static final ToolActivityData ACTIVITY = new ToolActivityData(
        "lookup", "call-1", ToolActivityStatus.COMPLETED, "found one result");

    @Test
    void sealedFamiliesEnumerateEveryProgressAndOutcomeSubtype() {
        assertTrue(TurnResponse.class.isSealed());
        assertEquals(Set.of(TurnResponse.Progress.class, TurnResponse.Outcome.class),
            Set.of(TurnResponse.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                TurnResponse.Started.class,
                TurnResponse.ContentChunk.class,
                TurnResponse.ThinkingChunk.class,
                TurnResponse.ToolActivity.class,
                TurnResponse.Notice.class),
            Set.of(TurnResponse.Progress.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                TurnResponse.Message.class,
                TurnResponse.Questions.class,
                TurnResponse.Queued.class,
                TurnResponse.Interrupted.class,
                TurnResponse.TimedOut.class,
                TurnResponse.QuestionsCancelled.class,
                TurnResponse.TurnCancelled.class),
            Set.of(TurnResponse.Outcome.class.getPermittedSubclasses()));
    }

    @Test
    void gateAcquireContractContainsNoQueueOrClaimBranch() {
        assertEquals(Set.of(
                TurnGate.AcquireResult.Acquired.class,
                TurnGate.AcquireResult.Rejected.class),
            Set.of(TurnGate.AcquireResult.class.getPermittedSubclasses()));
        assertTrue(List.of(TurnGate.AcquireResult.class.getDeclaredClasses()).stream()
            .noneMatch(type -> type.getSimpleName().equals("Queued")));
    }

    @Test
    void everySubtypeUsesItsStableSnakeCaseJsonTagAndRoundTrips() throws Exception {
        String interactionId = UUID.randomUUID().toString();
        ExecutionInfo execution = execution(new ExecutionOrigin.Direct());
        List<TurnResponse> responses = List.of(
            new TurnResponse.Started("session", "turn", TIME,
                new ExecutionOrigin.Direct(), "model", USAGE),
            new TurnResponse.ContentChunk("session", "turn", TIME, "content"),
            new TurnResponse.ThinkingChunk("session", "turn", TIME, "thinking"),
            new TurnResponse.ToolActivity("session", "turn", TIME, ACTIVITY),
            new TurnResponse.Notice("session", "turn", TIME, NoticeKind.COMPACTION, "notice"),
            new TurnResponse.Message(execution, "message"),
            new TurnResponse.Questions(execution, interactionId, List.of("question")),
            new TurnResponse.Queued("session", "message-id", TIME, QueueReason.EXPLICIT),
            new TurnResponse.Interrupted(execution, "interrupted"),
            new TurnResponse.TimedOut(execution, "timed out"),
            new TurnResponse.QuestionsCancelled("session", interactionId, TIME, "cancelled"),
            new TurnResponse.TurnCancelled(execution, "cancelled")
        );
        List<String> tags = List.of(
            "started", "content_chunk", "thinking_chunk", "tool_activity", "notice",
            "message", "questions", "queued", "interrupted", "timed_out",
            "questions_cancelled", "turn_cancelled");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        for (int index = 0; index < responses.size(); index++) {
            String json = TurnResponseRenderer.json(mapper).apply(responses.get(index));
            JsonNode node = mapper.readTree(json);
            assertEquals(tags.get(index), node.get("type").asText());
            TurnResponse decoded = mapper.readValue(json, TurnResponse.class);
            assertEquals(responses.get(index).getClass(), decoded.getClass());
        }
    }

    @Test
    void recordsEnforceCorrelationTimestampsAndTerminalInvariants() {
        ExecutionInfo execution = execution(new ExecutionOrigin.Direct());
        assertThrows(IllegalArgumentException.class,
            () -> new TurnResponse.Message(execution, " "));
        assertThrows(IllegalArgumentException.class,
            () -> new TurnResponse.Questions(execution, "interaction", List.of()));
        assertThrows(NullPointerException.class,
            () -> new TurnResponse.Queued("session", "message", TIME, null));
        assertThrows(IllegalArgumentException.class,
            () -> new TurnResponse.QuestionsCancelled("session", "interaction", TIME, " "));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionInfo("session", "turn", new ExecutionOrigin.Direct(),
                TIME.plusSeconds(1), TIME, USAGE, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionOrigin.Answer(" "));
        assertThrows(IllegalArgumentException.class,
            () -> new ExecutionOrigin.Queued(" "));

        List<ToolActivityData> mutable = new java.util.ArrayList<>(List.of(ACTIVITY));
        ExecutionInfo immutable = new ExecutionInfo("session", "turn",
            new ExecutionOrigin.Direct(), TIME, TIME, USAGE, mutable);
        mutable.clear();
        assertEquals(List.of(ACTIVITY), immutable.toolActivities());
        assertThrows(UnsupportedOperationException.class,
            () -> immutable.toolActivities().add(ACTIVITY));
    }

    @Test
    void turnRequestKeepsCallerIntentButHasNoStreamSelector() {
        assertEquals(List.of("sessionId", "message", "model", "toolNames", "metadata"),
            java.util.Arrays.stream(TurnRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertFalse(java.util.Arrays.stream(TurnRequest.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("stream")));
        TurnRequest request = TurnRequest.of("session", "message");
        assertEquals("session", request.sessionId());
        assertEquals("message", request.message());
        assertEquals(Map.of(), request.metadata());
        assertNotNull(request);
    }

    @Test
    void executionOriginsAreClosedAndNeverUseNullableOriginIds() {
        assertTrue(ExecutionOrigin.class.isSealed());
        assertEquals(Set.of(
                ExecutionOrigin.Direct.class,
                ExecutionOrigin.Answer.class,
                ExecutionOrigin.Queued.class),
            Set.of(ExecutionOrigin.class.getPermittedSubclasses()));
        assertInstanceOf(ExecutionOrigin.Direct.class, new ExecutionOrigin.Direct());
        assertEquals("interaction", new ExecutionOrigin.Answer("interaction").interactionId());
        assertEquals("message", new ExecutionOrigin.Queued("message").messageId());
    }

    private static ExecutionInfo execution(ExecutionOrigin origin) {
        return new ExecutionInfo("session", "turn", origin, TIME, TIME, USAGE,
            List.of(ACTIVITY));
    }
}
