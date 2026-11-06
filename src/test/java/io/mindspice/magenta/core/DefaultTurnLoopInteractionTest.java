package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.AuditWriter;
import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.InteractionStore;
import io.mindspice.magenta.core.persistence.InMemoryInteractionStore;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import io.mindspice.magenta.core.spi.SessionStore;
import io.mindspice.magenta.core.spi.TokenEstimator;
import io.mindspice.magenta.core.spi.ToolAccessPolicy;
import io.mindspice.magenta.core.spi.TurnLifecycleListener;
import io.mindspice.magenta.core.spi.TurnLoopMetrics;
import io.mindspice.magenta.core.NoticeKind;
import io.mindspice.magenta.core.spi.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnLoopInteractionTest {

    private final List<DefaultTurnLoop> loops = new ArrayList<>();

    @AfterEach
    void closeLoops() {
        loops.forEach(DefaultTurnLoop::close);
    }

    @Test
    void directAndStreamingExecutionsHaveOneTypedTerminalOutcome() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("<think>consider the request</think>answer"),
            response("<think>consider the request</think>answer")));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle);

        TurnResponse.Message direct = assertInstanceOf(TurnResponse.Message.class,
            loop.send(TurnRequest.of("direct-session", "hello")));
        List<TurnResponse> streamed = loop.stream(
            TurnRequest.of("stream-session", "hello")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(streamed);
        assertEquals(1, streamed.stream().filter(TurnResponse.Outcome.class::isInstance).count());
        TurnResponse.Message streaming = assertInstanceOf(TurnResponse.Message.class,
            streamed.getLast());
        assertEquals("answer", direct.text());
        assertEquals(direct.text(), streaming.text());
        assertEquals(new ExecutionOrigin.Direct(), direct.execution().origin());
        assertEquals(new ExecutionOrigin.Direct(), streaming.execution().origin());
        assertEquals(direct.execution().finalContextUsage(),
            streaming.execution().finalContextUsage());
        assertTrue(streamed.getFirst() instanceof TurnResponse.Started);
        assertEquals(streaming, streamed.getLast());
        assertEquals(2, lifecycle.outcomes.size());
    }

    @Test
    void successfulCompactionEmitsOneNoticeWithCompactedCount() {
        Memory memory = new Memory();
        memory.addMessages("compaction-session", List.of(
            new TurnMessage.User("a".repeat(7_000), Map.of()),
            new TurnMessage.Assistant("b".repeat(7_000), Map.of())));
        DefaultTurnLoop loop = createLoop(
            new ScriptedChatModel(List.of(response("done"))), memory,
            new InMemoryInteractionStore(), new RecordingQueueStore(),
            new RecordingLifecycle());

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("compaction-session", "compact now"))
            .collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        List<TurnResponse.Notice> notices = responses.stream()
            .filter(TurnResponse.Notice.class::isInstance)
            .map(TurnResponse.Notice.class::cast)
            .filter(notice -> notice.kind() == NoticeKind.COMPACTION)
            .toList();
        assertEquals(1, notices.size());
        assertEquals("Context was compacted to fit the model window (compacted 4 messages)",
            notices.getFirst().message());
        assertInstanceOf(TurnResponse.Message.class, responses.getLast());
        assertEquals("compaction_summary", memory.getMessages("compaction-session")
            .getFirst().metadata().get("type"));
    }

    @Test
    void belowTriggerDoesNotEmitCompactionNotice() {
        DefaultTurnLoop loop = createLoop(
            new ScriptedChatModel(List.of(response("done"))), new Memory(),
            new InMemoryInteractionStore(), new RecordingQueueStore(),
            new RecordingLifecycle());

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("below-trigger-session", "small"))
            .collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        assertTrue(responses.stream().noneMatch(response ->
            response instanceof TurnResponse.Notice notice
                && notice.kind() == NoticeKind.COMPACTION));
    }

    @Test
    void streamingTrailingEmptyFinishChunkDoesNotRepairOrDuplicateText() {
        ChatResponse content = response("streamed answer");
        ChatResponse finish = new ChatResponse(
            List.of(new Generation(new AssistantMessage(""))));
        ScriptedChatModel model = new ScriptedChatModel(List.of(content)) {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                calls.incrementAndGet();
                return Flux.just(content, finish);
            }
        };
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle());

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("empty-finish-session", "hello")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        assertEquals(1, responses.stream().filter(TurnResponse.Outcome.class::isInstance).count());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class, responses.getLast());
        assertEquals("streamed answer", message.text());
        assertTrue(responses.stream().noneMatch(notice ->
                notice instanceof TurnResponse.Notice n
                    && n.kind() == NoticeKind.REPAIR_APPLIED),
            "trailing empty finish chunk must not trigger a repair");
        assertEquals(1, model.calls.get(), "the model must not be re-invoked by a repair");
    }

    @Test
    void streamingToolCallSurvivesTrailingEmptyFinishChunk() {
        ToolCallback denied = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("denied", "Denied tool", "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                return new ToolResult.Failure("access was denied",
                    ToolFailureCategory.ACCESS_DENIED);
            }
        };
        ChatResponse finish = new ChatResponse(
            List.of(new Generation(new AssistantMessage(""))));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            toolCallResponse("denied", "{}"), response("recovered"))) {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                int call = calls.incrementAndGet();
                ChatResponse first = nextResponse();
                return call == 1 ? Flux.just(first, finish) : Flux.just(first);
            }
        };
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)), new EmptyToolRegistry(List.of(denied)));

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("tool-finish-session", "use the tool")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        ToolActivityData.Failed activity = assertInstanceOf(ToolActivityData.Failed.class,
            responses.stream().filter(TurnResponse.ToolActivity.class::isInstance)
                .map(TurnResponse.ToolActivity.class::cast)
                .findFirst().orElseThrow().activity());
        assertEquals(ToolFailureCategory.ACCESS_DENIED, activity.category());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class, responses.getLast());
        assertEquals("recovered", message.text());
        assertEquals(2, model.calls.get(), "tool round-trip requires exactly two model invocations");
    }

    @Test
    void typedToolFailureDrivesModelActivityPersistenceAndRecovery() {
        ToolCallback denied = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("denied", "Denied tool", "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                return new ToolResult.Failure("access was denied",
                    ToolFailureCategory.ACCESS_DENIED);
            }
        };
        Memory memory = new Memory();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            toolCallResponse("denied", "{}"), response("recovered")));
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)), new EmptyToolRegistry(List.of(denied)));

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("tool-session", "use the tool")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        ToolActivityData.Failed activity = assertInstanceOf(ToolActivityData.Failed.class,
            responses.stream().filter(TurnResponse.ToolActivity.class::isInstance)
                .map(TurnResponse.ToolActivity.class::cast)
                .findFirst().orElseThrow().activity());
        assertEquals(ToolFailureCategory.ACCESS_DENIED, activity.category());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            responses.getLast());
        assertEquals("recovered", message.text());
        TurnMessage toolMessage = memory.getMessages("tool-session").stream()
            .filter(TurnMessage.Tool.class::isInstance).findFirst().orElseThrow();
        assertFalse(toolMessage.metadata().containsKey("tool." + "success"));
        assertEquals("ACCESS_DENIED", toolMessage.metadata().get("tool.failure_category"));
    }

    @Test
    void typedToolPipelineCoversCategoriesAndSharedProjections() {
        assertToolScenario("success", new EmptyToolRegistry(List.of(callback(
            "success", new ToolResult.Success("ok")))),
            List.of(toolCallResponse("success", "{}"), response("done")), null);
        assertToolScenario("missing", new EmptyToolRegistry(),
            List.of(toolCallResponse("missing", "{}"), response("done")),
            ToolFailureCategory.NOT_FOUND);
        assertToolScenario("denied", new EmptyToolRegistry(List.of(callback(
            "denied", new ToolResult.Failure("denied", ToolFailureCategory.ACCESS_DENIED)))),
            List.of(toolCallResponse("denied", "{}"), response("done")),
            ToolFailureCategory.ACCESS_DENIED);
        DefaultToolRegistry invalidArguments = new DefaultToolRegistry();
        invalidArguments.registerToolBean(new AnnotatedTool());
        assertToolScenario("invalid", invalidArguments,
            List.of(toolCallResponse("annotated", "{}"), response("done")),
            ToolFailureCategory.INVALID_ARGUMENTS);
        assertToolScenario("thrown", new EmptyToolRegistry(List.of(
            callback("thrown", new IllegalStateException("callback exploded")))),
            List.of(toolCallResponse("thrown", "{}"), response("done")),
            ToolFailureCategory.EXECUTION_FAILED);
        assertToolScenario("annotated", annotatedRegistry(),
            List.of(toolCallResponse("annotated", "{\"value\":\"ok\"}"), response("done")),
            null);

        List<ChatResponse> loopResponses = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            loopResponses.add(toolCallResponse("loop", "{\"n\":" + index + "}",
                "call-" + index));
        }
        loopResponses.add(response("recovered"));
        assertToolScenario("loop", new EmptyToolRegistry(List.of(callback(
            "loop", new ToolResult.Failure("tool failed", ToolFailureCategory.EXECUTION_FAILED)))),
            loopResponses, ToolFailureCategory.EXECUTION_FAILED);

        List<ChatResponse> identicalResponses = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            identicalResponses.add(toolCallResponse("identical", "{}"));
        }
        identicalResponses.add(response("recovered"));
        assertToolScenario("identical", new EmptyToolRegistry(List.of(callback(
            "identical", new ToolResult.Success("ok")))),
            identicalResponses, null);

        List<ChatResponse> repeatedFailureResponses = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            repeatedFailureResponses.add(toolCallResponse("repeated_failure", "{}"));
        }
        repeatedFailureResponses.add(response("recovered"));
        assertToolScenario("repeated-failure", new EmptyToolRegistry(List.of(callback(
            "repeated_failure",
            new ToolResult.Failure("tool failed", ToolFailureCategory.EXECUTION_FAILED)))),
            repeatedFailureResponses, ToolFailureCategory.LOOP_EXCEEDED);
    }

    @Test
    void maxToolIterationBoundaryAllowsNinetyNineAndAbortsBeforeExecutingOneHundred() {
        List<ChatResponse> modelResponses = new ArrayList<>();
        for (int iteration = 1; iteration <= 100; iteration++) {
            modelResponses.add(toolCallResponse(
                "bounded", "{\"iteration\":" + iteration + "}", "call-" + iteration));
        }
        modelResponses.add(response("recovered without more tools"));

        java.util.concurrent.atomic.AtomicInteger executions =
            new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback bounded = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition(
                    "bounded", "Bounded tool", "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                executions.incrementAndGet();
                return new ToolResult.Success("ok");
            }
        };
        ScriptedChatModel model = new ScriptedChatModel(modelResponses);
        DefaultTurnLoop loop = createLoop(
            model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(List.of(bounded)), new RecordingAudit(),
            new RecordingMetrics(), null, new ToolLoopGuard(8), 100);

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("max-iteration-session", "keep using the tool"))
            .collectList().block(Duration.ofSeconds(5));

        assertNotNull(responses);
        assertEquals(99, executions.get(), "iterations 1 through 99 may execute tools");
        assertEquals(101, model.calls.get(),
            "the 100th tool-bearing response aborts and one repair response completes the turn");
        assertEquals(1, responses.stream()
            .filter(TurnResponse.ToolActivity.class::isInstance)
            .map(TurnResponse.ToolActivity.class::cast)
            .map(TurnResponse.ToolActivity::activity)
            .filter(activity -> activity instanceof ToolActivityData.Failed failed
                && failed.category() == ToolFailureCategory.LOOP_EXCEEDED)
            .count());
        assertTrue(responses.stream().anyMatch(response ->
            response instanceof TurnResponse.Notice notice
                && notice.kind() == NoticeKind.TOOL_LOOP_WARNING));
        assertEquals("recovered without more tools",
            assertInstanceOf(TurnResponse.Message.class, responses.getLast()).text());
    }

    @Test
    void maxToolIterationBoundaryPairsEveryBlockedParallelCallWithoutExecutingThem() {
        String secret = "max-boundary-secret-value";
        Map<String, java.util.concurrent.atomic.AtomicInteger> executions =
            new ConcurrentHashMap<>();
        List<ToolCallback> tools = List.of(
            trackedCallback("warmup", new ToolResult.Success("warmup complete"), executions),
            trackedCallback("blocked_one", new ToolResult.Success("must not run"), executions),
            trackedCallback("blocked_two", new ToolResult.Success("must not run"), executions),
            trackedCallback("blocked_three", new ToolResult.Success("must not run"), executions));
        PairingStrictScriptedChatModel model = new PairingStrictScriptedChatModel(List.of(
            toolCallResponse(List.of(toolCall("warmup-call", "warmup", "{}"))),
            toolCallResponse(List.of(
                toolCall("blocked-call-1", "blocked_one",
                    "{\"token\":\"" + secret + "-1\"}"),
                toolCall("blocked-call-2", "blocked_two",
                    "{\"token\":\"" + secret + "-2\"}"),
                toolCall("blocked-call-3", "blocked_three",
                    "{\"token\":\"" + secret + "-3\"}"))),
            response("recovered without tools")));
        Memory memory = new Memory();
        RecordingAudit audit = new RecordingAudit();
        RecordingMetrics metrics = new RecordingMetrics();
        DefaultTurnLoop loop = createLoop(
            model, memory, new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(tools), audit, metrics, null,
            new ToolLoopGuard(8), 2);

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("parallel-max-session", "keep using tools"))
            .collectList().block(Duration.ofSeconds(3));

        assertNotNull(responses);
        assertEquals(1, executionCount(executions, "warmup"));
        assertEquals(0, executionCount(executions, "blocked_one"));
        assertEquals(0, executionCount(executions, "blocked_two"));
        assertEquals(0, executionCount(executions, "blocked_three"));
        assertEquals(3, model.calls.get());

        List<ToolActivityData> activities = toolActivities(responses);
        assertEquals(List.of(
                "warmup-call", "blocked-call-1", "blocked-call-2", "blocked-call-3"),
            activities.stream().map(ToolActivityData::toolCallId).toList());
        assertEquals(List.of("COMPLETED", "LOOP_EXCEEDED", "LOOP_EXCEEDED", "LOOP_EXCEEDED"),
            activities.stream().map(DefaultTurnLoopInteractionTest::activityKind).toList());

        PromptToolBatch boundaryBatch = latestPromptToolBatch(model.prompts.getLast());
        assertEquals(List.of("blocked-call-1", "blocked-call-2", "blocked-call-3"),
            boundaryBatch.calls().stream().map(AssistantMessage.ToolCall::id).toList());
        assertEquals(List.of("blocked-call-1", "blocked-call-2", "blocked-call-3"),
            boundaryBatch.responses().stream()
                .map(ToolResponseMessage.ToolResponse::id).toList());

        List<TurnMessage.Tool> durableTools = durableTools(
            memory, "parallel-max-session");
        assertEquals(List.of(
                "warmup-call", "blocked-call-1", "blocked-call-2", "blocked-call-3"),
            durableTools.stream().map(TurnMessage.Tool::toolCallId).toList());
        assertEquals(List.of(
                "warmup-call", "blocked-call-1", "blocked-call-2", "blocked-call-3"),
            audit.toolExecutions.stream()
                .map(AuditWriter.ToolExecutionRecord::activity)
                .map(ToolActivityData::toolCallId).toList());
        assertTrue(durableTools.subList(1, durableTools.size()).stream().allMatch(message ->
            message.metadata().get("tool.arguments").isEmpty()
                && ToolFailureCategory.LOOP_EXCEEDED.name().equals(
                    message.metadata().get("tool.failure_category"))));
        assertGuardResultProjectionsDoNotContain(
            secret, activities, boundaryBatch.responses(), durableTools, audit.toolExecutions);
        assertEquals("recovered without tools",
            assertInstanceOf(TurnResponse.Message.class, responses.getLast()).text());
    }

    @Test
    void repeatedErrorAbortPairsTrailingParallelCallsWithoutExecutingThem() {
        String secret = "repeated-abort-secret-value";
        Map<String, java.util.concurrent.atomic.AtomicInteger> executions =
            new ConcurrentHashMap<>();
        List<ToolCallback> tools = List.of(
            trackedCallback("before", new ToolResult.Success("before complete"), executions),
            trackedCallback("repeat", new ToolResult.Failure(
                "controlled failure", ToolFailureCategory.EXECUTION_FAILED), executions),
            trackedCallback("after_one", new ToolResult.Success("must not run"), executions),
            trackedCallback("after_two", new ToolResult.Success("must not run"), executions));
        PairingStrictScriptedChatModel model = new PairingStrictScriptedChatModel(List.of(
            toolCallResponse(List.of(
                toolCall("before-call", "before", "{}"),
                toolCall("repeat-call-1", "repeat", "{\"same\":1}"),
                toolCall("repeat-call-2", "repeat", "{\"same\":1}"),
                toolCall("repeat-call-3", "repeat", "{\"same\":1}"),
                toolCall("after-call-1", "after_one",
                    "{\"token\":\"" + secret + "-1\"}"),
                toolCall("after-call-2", "after_two",
                    "{\"token\":\"" + secret + "-2\"}"))),
            response("recovered after repeated error")));
        Memory memory = new Memory();
        RecordingAudit audit = new RecordingAudit();
        RecordingMetrics metrics = new RecordingMetrics();
        DefaultTurnLoop loop = createLoop(
            model, memory, new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(tools), audit, metrics, null,
            new ToolLoopGuard(3), 10);

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("parallel-repeat-session", "use the tools"))
            .collectList().block(Duration.ofSeconds(3));

        assertNotNull(responses);
        assertEquals(1, executionCount(executions, "before"));
        assertEquals(3, executionCount(executions, "repeat"),
            "the matching third failure executes before the guard trips");
        assertEquals(0, executionCount(executions, "after_one"));
        assertEquals(0, executionCount(executions, "after_two"));
        assertEquals(2, model.calls.get());

        List<String> expectedIds = List.of(
            "before-call", "repeat-call-1", "repeat-call-2", "repeat-call-3",
            "after-call-1", "after-call-2");
        List<ToolActivityData> activities = toolActivities(responses);
        assertEquals(expectedIds,
            activities.stream().map(ToolActivityData::toolCallId).toList());
        assertEquals(List.of(
                "COMPLETED", "EXECUTION_FAILED", "EXECUTION_FAILED",
                "LOOP_EXCEEDED", "LOOP_EXCEEDED", "LOOP_EXCEEDED"),
            activities.stream().map(DefaultTurnLoopInteractionTest::activityKind).toList());

        PromptToolBatch batch = latestPromptToolBatch(model.prompts.getLast());
        assertEquals(expectedIds,
            batch.calls().stream().map(AssistantMessage.ToolCall::id).toList());
        assertEquals(expectedIds,
            batch.responses().stream().map(ToolResponseMessage.ToolResponse::id).toList());

        List<TurnMessage.Tool> durableTools = durableTools(
            memory, "parallel-repeat-session");
        assertEquals(expectedIds,
            durableTools.stream().map(TurnMessage.Tool::toolCallId).toList());
        assertEquals(expectedIds, audit.toolExecutions.stream()
            .map(AuditWriter.ToolExecutionRecord::activity)
            .map(ToolActivityData::toolCallId).toList());
        assertEquals(ToolFailureCategory.EXECUTION_FAILED.name(),
            durableTools.get(1).metadata().get("tool.failure_category"));
        assertEquals(ToolFailureCategory.EXECUTION_FAILED.name(),
            durableTools.get(2).metadata().get("tool.failure_category"));
        assertTrue(durableTools.subList(3, durableTools.size()).stream().allMatch(message ->
            ToolFailureCategory.LOOP_EXCEEDED.name().equals(
                message.metadata().get("tool.failure_category"))));
        assertTrue(durableTools.subList(4, durableTools.size()).stream().allMatch(message ->
            message.metadata().get("tool.arguments").isEmpty()));
        assertGuardResultProjectionsDoNotContain(
            secret, activities, batch.responses(), durableTools, audit.toolExecutions);
        assertEquals("recovered after repeated error",
            assertInstanceOf(TurnResponse.Message.class, responses.getLast()).text());
    }

    @Test
    void toolLoopHardBoundFailsIfTheModelIgnoresTheToolFreeRepair() {
        java.util.concurrent.atomic.AtomicInteger executions =
            new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("bounded", "Bounded tool", "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                executions.incrementAndGet();
                return new ToolResult.Success("ok");
            }
        };
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            toolCallResponse("bounded", "{\"n\":1}"),
            toolCallResponse("bounded", "{\"n\":2}"),
            toolCallResponse("bounded", "{\"n\":3}"),
            toolCallResponse("bounded", "{\"n\":4}")));
        DefaultTurnLoop loop = createLoop(
            model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(List.of(tool)), new RecordingAudit(),
            new RecordingMetrics(), null, new ToolLoopGuard(8), 3);

        TurnFailedException failure = assertThrows(TurnFailedException.class,
            () -> loop.send(TurnRequest.of("hard-bound-session", "keep using tools")));

        assertEquals(TurnFailureCategory.TOOL, failure.failure().category());
        assertEquals(2, executions.get());
        assertEquals(4, model.calls.get());
    }

    @Test
    void contextAwareCallbacksReceiveExactRequestMetadataWhileLegacyCallbacksStayUnchanged() {
        Map<String, String> requestMetadata = Map.of(
            "origin", "scheduled", "permission", "read-only", "approvalId", "approval-7");
        TurnRequest request = new TurnRequest(
            "meta-session", "use tools", "explicit-model", null, requestMetadata);

        java.util.concurrent.atomic.AtomicReference<TurnContext> captured =
            new java.util.concurrent.atomic.AtomicReference<>();
        ToolCallback contextAware = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("context_aware", "Context-aware tool",
                    "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                return new ToolResult.Success("legacy-path");
            }

            @Override
            public ToolResult call(String toolInput, TurnContext context) {
                captured.set(context);
                return new ToolResult.Success("context-path:" + context.request().metadata());
            }
        };
        DefaultTurnLoop loop = createLoop(
            new ScriptedChatModel(List.of(
                toolCallResponse("context_aware", "{}"), response("done"))),
            new Memory(), new InMemoryInteractionStore(), new RecordingQueueStore(),
            new RecordingLifecycle(), new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(List.of(contextAware)));

        List<TurnResponse> responses = loop.stream(request)
            .collectList().block(Duration.ofSeconds(3));

        assertNotNull(responses);
        TurnResponse.Started started = assertInstanceOf(TurnResponse.Started.class,
            responses.getFirst());
        TurnResponse.Message outcome = assertInstanceOf(TurnResponse.Message.class,
            responses.getLast());
        assertEquals("done", outcome.text());
        TurnContext seen = Objects.requireNonNull(captured.get(), "context must be captured");
        assertEquals(requestMetadata, seen.request().metadata());
        assertEquals("meta-session", seen.sessionId());
        assertEquals(started.turnId(), seen.turnId());
        assertEquals(started.model(), seen.model());
        assertEquals("explicit-model", seen.model());
        List<String> toolTexts = responses.stream()
            .filter(TurnResponse.ToolActivity.class::isInstance)
            .map(TurnResponse.ToolActivity.class::cast)
            .map(activity -> ((ToolActivityData.Completed) activity.activity()).summary())
            .toList();
        assertTrue(toolTexts.stream().anyMatch(text -> text.contains("context-path:")));

        // A legacy one-argument callback must still be dispatched unchanged.
        ToolCallback legacy = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("legacy_only", "Legacy one-arg tool",
                    "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                return new ToolResult.Success("legacy-dispatched:" + toolInput);
            }
        };
        DefaultTurnLoop legacyLoop = createLoop(
            new ScriptedChatModel(List.of(
                toolCallResponse("legacy_only", "{\"n\":1}"), response("legacy done"))),
            new Memory(), new InMemoryInteractionStore(), new RecordingQueueStore(),
            new RecordingLifecycle(), new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(List.of(legacy)));

        List<TurnResponse> legacyResponses = legacyLoop.stream(
            TurnRequest.of("legacy-session", "use legacy tool"))
            .collectList().block(Duration.ofSeconds(3));
        assertNotNull(legacyResponses);
        assertInstanceOf(TurnResponse.Message.class, legacyResponses.getLast());
        assertTrue(legacyResponses.stream()
            .filter(TurnResponse.ToolActivity.class::isInstance)
            .map(TurnResponse.ToolActivity.class::cast)
            .map(activity -> ((ToolActivityData.Completed) activity.activity()).summary())
            .anyMatch(text -> text.contains("legacy-dispatched:{\"n\":1}")));
    }

    @Test
    void streamingProgressSeparatesVisibleContentAndThinking() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("<think>private reasoning</think>visible answer")));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle());

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("session", "show progress")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        assertEquals(List.of("visible answer"), responses.stream()
            .filter(TurnResponse.ContentChunk.class::isInstance)
            .map(TurnResponse.ContentChunk.class::cast)
            .map(TurnResponse.ContentChunk::text)
            .toList());
        assertEquals(List.of("private reasoning"), responses.stream()
            .filter(TurnResponse.ThinkingChunk.class::isInstance)
            .map(TurnResponse.ThinkingChunk.class::cast)
            .map(TurnResponse.ThinkingChunk::text)
            .toList());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            responses.getLast());
        assertEquals("visible answer", message.text());
        assertTrue(responses.indexOf(responses.stream()
            .filter(TurnResponse.ContentChunk.class::isInstance).findFirst().orElseThrow())
            < responses.indexOf(message));
        assertTrue(responses.stream().noneMatch(response -> response instanceof TurnResponse.ContentChunk chunk
            && chunk.text().contains("private reasoning")));
    }

    @Test
    void questionsAreDurableRehydratableAndAnsweredInOrder() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("first question", "second question"), response("continued")));
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, memory, interactions, queue,
            new RecordingLifecycle());

        TurnResponse.Questions questions = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "start")));
        UUID interactionUuid = UUID.fromString(questions.interactionId());

        assertNotNull(interactionUuid);
        assertNotNull(questions.execution().turnId());
        assertFalse(questions.interactionId().equals(questions.execution().turnId()));
        assertEquals(List.of("first question", "second question"), questions.questions());
        assertTrue(questions.execution().completedAt().compareTo(
            questions.execution().startedAt()) >= 0);
        assertEquals(List.of(new ToolActivityData.Completed(
                "ask_user", "call-1", "Questions pending")),
            questions.execution().toolActivities());
        assertEquals(loop.memory().contextUsage("session"),
            questions.execution().finalContextUsage());
        assertEquals(Optional.of(questions), loop.pendingQuestions("session"));
        assertEquals(questions, interactions.find("session", questions.interactionId())
            .orElseThrow().questions());

        TurnResponse.Queued queued = assertInstanceOf(TurnResponse.Queued.class,
            loop.send(new TurnRequest("session", "while waiting", null, null,
                Map.of("source", "ui"))));
        assertEquals(QueueReason.AWAITING_QUESTIONS, queued.reason());
        assertEquals(1, model.calls.get());
        assertEquals(List.of("while waiting"), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::message).toList());

        TurnResponse.Message continued = assertInstanceOf(TurnResponse.Message.class,
            loop.answer("session", questions.interactionId(),
                List.of("first answer", "second answer")));

        assertEquals("continued", continued.text());
        assertEquals(new ExecutionOrigin.Answer(questions.interactionId()),
            continued.execution().origin());
        assertTrue(loop.pendingQuestions("session").isEmpty());
        assertEquals(2, model.calls.get());
        assertTrue(model.prompts.get(1).getInstructions().stream()
            .map(org.springframework.ai.chat.messages.Message::getText)
            .anyMatch(text -> text.contains("Question 1: first question")
                && text.contains("Answer 1: first answer")
                && text.contains("Question 2: second question")
                && text.contains("Answer 2: second answer")));
        assertTrue(memory.getMessages("session").stream()
            .anyMatch(message -> message.text() != null
                && message.text().contains("Question 1: first question")));
        assertTrue(interactions.find("session", questions.interactionId()).isEmpty());
    }

    @Test
    void streamingAnswerUsesTheSameInteractionCasAndOrigin() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("one", "two"), response("streamed continuation")));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle());
        TurnResponse.Questions questions = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "ask")));

        StreamCapture capture = subscribe(loop.streamAnswer("session", questions.interactionId(),
            List.of("answer one", "answer two")));
        assertDoesNotThrow(capture::await);

        assertNull(capture.error.get());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            capture.values.getLast());
        assertEquals("streamed continuation", message.text());
        assertEquals(new ExecutionOrigin.Answer(questions.interactionId()),
            message.execution().origin());
        assertEquals(1, capture.values.stream()
            .filter(TurnResponse.Outcome.class::isInstance).count());
    }

    @Test
    void exactAnswerCardinalityAndStaleOrConsumedIdsAreRejected() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("first"), response("first complete"),
            askUserResponse("second")));
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), interactions,
            new RecordingQueueStore(), new RecordingLifecycle());

        TurnResponse.Questions first = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "first")));
        assertThrows(IllegalArgumentException.class,
            () -> loop.answer("session", first.interactionId(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> loop.answer("session", first.interactionId(), List.of(" ")));

        assertInstanceOf(TurnResponse.Message.class,
            loop.answer("session", first.interactionId(), List.of("answer")));
        assertInstanceOf(TurnResponse.QuestionsMissing.class,
            loop.answer("session", first.interactionId(), List.of("duplicate")));

        TurnResponse.Questions second = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "second")));
        assertInstanceOf(TurnResponse.QuestionsMissing.class,
            loop.cancelQuestions("session", first.interactionId(), "stale"));
        assertInstanceOf(TurnResponse.QuestionsMissing.class,
            loop.answer("other-session", second.interactionId(), List.of("wrong session")));
        assertEquals(Optional.of(second), loop.pendingQuestions("session"));

        TurnResponse.QuestionsCancelled cancelled = assertInstanceOf(
            TurnResponse.QuestionsCancelled.class, loop.cancelQuestions(
                "session", second.interactionId(), "caller stopped waiting"));
        assertEquals(second.interactionId(), cancelled.interactionId());
        assertEquals("caller stopped waiting", cancelled.reason());
        assertTrue(loop.pendingQuestions("session").isEmpty());
        InteractionStore.Interaction.Cancelled persisted = assertInstanceOf(
            InteractionStore.Interaction.Cancelled.class,
            interactions.find("session", second.interactionId()).orElseThrow());
        assertEquals(persisted.cancelledAt(), cancelled.cancelledAt());
        assertEquals(persisted.reason(), cancelled.reason());
        assertInstanceOf(TurnResponse.QuestionsAlreadyCancelled.class,
            loop.cancelQuestions("session", second.interactionId(), "again"));
    }

    @Test
    void durableRejectionsReturnOnceWithoutProviderExecution() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("question")));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RecordingAudit audit = new RecordingAudit();
        RecordingMetrics metrics = new RecordingMetrics();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, new SerialTurnGate(Duration.ofSeconds(30)),
            new EmptyToolRegistry(), audit, metrics);
        String missingId = UUID.randomUUID().toString();

        TurnResponse.QuestionsMissing missing = assertInstanceOf(
            TurnResponse.QuestionsMissing.class,
            loop.answer("missing-session", missingId, List.of("answer")));
        assertEquals("missing-session", missing.sessionId());
        assertEquals(0, model.calls.get());

        List<TurnResponse> missingStream = loop.streamAnswer(
            "missing-session", missingId, List.of("answer"))
            .collectList().block(Duration.ofSeconds(2));
        assertEquals(List.of(new TurnResponse.QuestionsMissing("missing-session", missingId)),
            missingStream);
        assertEquals(0, model.calls.get());
        assertEquals(2, lifecycle.outcomes.size());

        TurnResponse.Questions questions = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("terminal-session", "ask")));
        assertInstanceOf(TurnResponse.QuestionsCancelled.class,
            loop.cancelQuestions("terminal-session", questions.interactionId(), "stop"));
        TurnResponse.QuestionsAlreadyCancelled duplicate = assertInstanceOf(
            TurnResponse.QuestionsAlreadyCancelled.class,
            loop.cancelQuestions("terminal-session", questions.interactionId(), "again"));
        assertEquals(questions.interactionId(), duplicate.interactionId());
        List<TurnResponse> duplicateStream = loop.streamAnswer(
            "terminal-session", questions.interactionId(), List.of("late"))
            .collectList().block(Duration.ofSeconds(2));
        assertEquals(List.of(duplicate), duplicateStream);
        assertEquals(1, model.calls.get());
        assertEquals(6, lifecycle.outcomes.size());
        assertEquals(5, audit.errors.size());
        assertEquals(2, metrics.outcomes.stream()
            .filter("questions_missing"::equals).count());
        assertEquals(2, metrics.outcomes.stream()
            .filter("questions_already_cancelled"::equals).count());
        assertEquals(1, metrics.outcomes.stream()
            .filter("questions_cancelled"::equals).count());
    }

    @Test
    void interruptingPendingQuestionsAuditsCancellation() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("question")));
        RecordingAudit audit = new RecordingAudit();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle(),
            new SerialTurnGate(Duration.ofSeconds(30)), new EmptyToolRegistry(), audit,
            new RecordingMetrics());

        TurnResponse.Questions questions = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("interrupt-session", "ask")));
        assertTrue(loop.interrupt(questions.execution().turnId(), "operator stopped"));
        assertEquals(1, audit.errors.size());
        assertEquals("Question cancelled: operator stopped", audit.errors.getFirst().summary());
        assertTrue(loop.pendingQuestions("interrupt-session").isEmpty());
    }

    @Test
    void activeSubmissionIsQueuedWithoutASecondProviderCall() throws Exception {
        BlockingChatModel model = new BlockingChatModel(
            List.of(response("first complete"), response("queued complete")), 1);
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());

        CompletableFuture<TurnResponse.Outcome> active = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(model.entered.await(5, TimeUnit.SECONDS),
            "model calls=" + model.calls.get() + ", active done=" + active.isDone());

        TurnResponse.Queued queued = assertInstanceOf(TurnResponse.Queued.class,
            loop.send(TurnRequest.of("session", "second")));
        assertEquals(QueueReason.SESSION_ACTIVE, queued.reason());
        assertEquals(1, model.calls.get());
        assertEquals(queued.messageId(), queue.list("session").getFirst().messageId());

        model.release.countDown();
        assertEquals("first complete",
            assertInstanceOf(TurnResponse.Message.class,
                active.get(2, TimeUnit.SECONDS)).text());
        assertEquals(1, model.calls.get());
    }

    @Test
    void drainIsColdOldestFirstAndUsesQueuedExecutionOrigins() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("first result"), response("second result")));
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());

        TurnResponse.Queued first = loop.enqueue(new TurnRequest(
            "session", "first", null, null, Map.of("order", "1")));
        TurnResponse.Queued second = loop.enqueue(new TurnRequest(
            "session", "second", "model", List.of("tool"), Map.of("order", "2")));
        Flux<TurnResponse> cold = loop.drain("session");
        assertEquals(0, model.calls.get());

        List<TurnResponse> responses = cold.collectList().block(Duration.ofSeconds(5));
        assertNotNull(responses);
        List<TurnResponse.Message> messages = responses.stream()
            .filter(TurnResponse.Message.class::isInstance)
            .map(TurnResponse.Message.class::cast)
            .toList();
        assertEquals(List.of("first result", "second result"),
            messages.stream().map(TurnResponse.Message::text).toList());
        assertEquals(List.of(first.messageId(), second.messageId()),
            messages.stream().map(message -> ((ExecutionOrigin.Queued)
                message.execution().origin()).messageId()).toList());
        assertEquals(List.of(first.messageId(), second.messageId()), queue.acknowledgedMessageIds);
        assertTrue(queue.releasedMessageIds.isEmpty());
        assertTrue(responses.stream().noneMatch(TurnResponse.Queued.class::isInstance));
        assertTrue(loop.drain("session").collectList().block(Duration.ofSeconds(2)).isEmpty());
    }

    @Test
    void drainRecoversAnsweredContinuationBeforeClaimingQueuedWork() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("answered recovery"), response("queued result")));
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingQueueStore queue = new RecordingQueueStore();
        String interactionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TurnResponse.Questions questions = new TurnResponse.Questions(
            new ExecutionInfo("session", UUID.randomUUID().toString(),
                new ExecutionOrigin.Direct(), now, now,
                new ContextUsage(1, 100, 4096), List.of()),
            interactionId, List.of("recover me"));
        InteractionStore.InteractionInfo info = new InteractionStore.InteractionInfo(
            interactionId, "session", questions, "model", null, Map.of(), Map.of(), now, now);
        InteractionStore.Interaction answered = new InteractionStore.Interaction.Answered(
            info, List.of("recovered answer"));
        interactions.save(answered);
        memory.addMessages("session", List.of(new TurnMessage.System(
            "[Magenta interaction pending]", Map.of(
                "turnloop.persistence_type", "turnloop.pending_interaction",
                "turnloop.pending_interaction_id", interactionId,
                "turnloop.pending_answers", "[\"recovered answer\"]"))));
        DefaultTurnLoop loop = createLoop(model, memory, interactions, queue,
            new RecordingLifecycle());
        TurnResponse.Queued queued = loop.enqueue(TurnRequest.of("session", "queued"));

        List<TurnResponse> responses = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));

        assertNotNull(responses);
        List<TurnResponse.Message> messages = responses.stream()
            .filter(TurnResponse.Message.class::isInstance)
            .map(TurnResponse.Message.class::cast).toList();
        assertEquals(List.of("answered recovery", "queued result"),
            messages.stream().map(TurnResponse.Message::text).toList());
        assertEquals(new ExecutionOrigin.Answer(interactionId),
            messages.getFirst().execution().origin());
        assertEquals(new ExecutionOrigin.Queued(queued.messageId()),
            messages.getLast().execution().origin());
        assertEquals(List.of(queued.messageId()), queue.acknowledgedMessageIds);
        assertEquals(2, queue.claimCalls);
        assertTrue(interactions.find("session", interactionId).isEmpty());
    }

    @Test
    void drainRetainsAndReleasesClaimWhenAcknowledgementFails() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("first execution"), response("retried execution")));
        RecordingQueueStore queue = new RecordingQueueStore();
        queue.failedAcknowledgements = 1;
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());
        TurnResponse.Queued receipt = loop.enqueue(TurnRequest.of("session", "retry oldest"));

        StreamCapture firstDrain = subscribe(loop.drain("session"));
        assertDoesNotThrow(firstDrain::await);

        assertTrue(firstDrain.error.get() instanceof TurnLoopException);
        assertEquals(List.of(receipt.messageId()), queue.releasedMessageIds);
        assertEquals(List.of(receipt.messageId()), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::messageId).toList());
        assertTrue(queue.acknowledgedMessageIds.isEmpty());

        List<TurnResponse> retry = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));
        assertEquals("retried execution", assertInstanceOf(TurnResponse.Message.class,
            retry.getLast()).text());
        assertEquals(List.of(receipt.messageId()), queue.acknowledgedMessageIds);
        assertTrue(queue.list("session").isEmpty());
    }

    @Test
    void drainReleasesAClaimWhenQueuedExecutionFails() {
        FailureChatModel model = new FailureChatModel();
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());
        TurnResponse.Queued receipt = loop.enqueue(TurnRequest.of("session", "retry me"));

        StreamCapture capture = subscribe(loop.drain("session"));
        assertDoesNotThrow(capture::await);

        assertTrue(capture.error.get() instanceof TurnFailedException);
        assertTrue(capture.values.stream().noneMatch(TurnResponse.Outcome.class::isInstance));
        assertEquals(List.of(receipt.messageId()), queue.releasedMessageIds);
        assertEquals(List.of(receipt.messageId()), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::messageId).toList());
        assertTrue(queue.acknowledgedMessageIds.isEmpty());
    }

    @Test
    void drainStopsAfterQuestionsAndPendingRehydrationDoesNotClaimMoreWork() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("drain question"), response("after cancellation")));
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());
        TurnResponse.Queued first = loop.enqueue(TurnRequest.of("session", "ask first"));
        TurnResponse.Queued second = loop.enqueue(TurnRequest.of("session", "run second"));

        List<TurnResponse> firstDrain = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));
        TurnResponse.Questions pending = assertInstanceOf(TurnResponse.Questions.class,
            firstDrain.getLast());
        assertEquals(List.of(first.messageId()), queue.acknowledgedMessageIds);
        assertEquals(List.of(second.messageId()), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::messageId).toList());

        List<TurnResponse> blockedDrain = loop.drain("session")
            .collectList().block(Duration.ofSeconds(2));
        assertEquals(List.of(pending), blockedDrain);
        assertEquals(1, queue.claimCalls);
        assertEquals(Optional.of(pending), loop.pendingQuestions("session"));

        loop.cancelQuestions("session", pending.interactionId(), "continue queue");
        List<TurnResponse> finalDrain = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            finalDrain.getLast());
        assertEquals("after cancellation", message.text());
        assertEquals(new ExecutionOrigin.Queued(second.messageId()),
            message.execution().origin());
        assertEquals(List.of(first.messageId(), second.messageId()), queue.acknowledgedMessageIds);
        assertTrue(queue.list("session").isEmpty());
    }

    @Test
    void lifecycleListenerFailuresAreIsolatedAndUnexpectedStreamsErrorWithoutOutcome() {
        RecordingLifecycle successfulLifecycle = new RecordingLifecycle(true);
        DefaultTurnLoop successfulLoop = createLoop(
            new ScriptedChatModel(List.of(response("ok"))), new Memory(),
            new InMemoryInteractionStore(), new RecordingQueueStore(), successfulLifecycle);
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            successfulLoop.send(TurnRequest.of("success", "hello")));
        assertEquals("ok", message.text());
        assertEquals(1, successfulLifecycle.outcomes.size());
        assertTrue(successfulLifecycle.failures.isEmpty());

        RecordingLifecycle failureLifecycle = new RecordingLifecycle(true);
        DefaultTurnLoop failureLoop = createLoop(new FailureChatModel(), new Memory(),
            new InMemoryInteractionStore(), new RecordingQueueStore(), failureLifecycle);
        TurnFailedException sendFailure = assertThrows(TurnFailedException.class,
            () -> failureLoop.send(TurnRequest.of("failure", "hello")));
        assertEquals(TurnFailureCategory.MODEL, sendFailure.failure().category());
        assertEquals(new ExecutionOrigin.Direct(), sendFailure.failure().origin());
        assertTrue(failureLifecycle.outcomes.isEmpty());
        assertEquals(1, failureLifecycle.failures.size());

        StreamCapture streamFailure = subscribe(failureLoop.stream(
            TurnRequest.of("failure-stream", "hello")));
        assertDoesNotThrow(streamFailure::await);
        assertTrue(streamFailure.error.get() instanceof TurnFailedException);
        assertTrue(streamFailure.values.stream().noneMatch(
            TurnResponse.Outcome.class::isInstance));
        assertEquals(2, failureLifecycle.failures.size());
    }

    @Test
    void providerFailureSurfacesRedactedBoundedMessageAndPreservesCause() {
        String apiKey = "provider-secret-key";
        IllegalStateException providerCause = new IllegalStateException(
            "DeepSeek API 400: rejected " + apiKey
                + " capability=approval-capability\u0000 " + "x".repeat(600));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        DefaultTurnLoop loop = createLoop(
            new FailureChatModel(providerCause), new Memory(),
            new InMemoryInteractionStore(), new RecordingQueueStore(), lifecycle,
            new SerialTurnGate(Duration.ofSeconds(30)), new EmptyToolRegistry(),
            new RecordingAudit(), new RecordingMetrics(), apiKey);

        TurnFailedException failure = assertThrows(TurnFailedException.class,
            () -> loop.send(TurnRequest.of("provider-failure", "hello")));

        assertEquals(TurnFailureCategory.MODEL, failure.failure().category());
        assertTrue(failure.failure().message().startsWith("DeepSeek API 400:"));
        assertTrue(failure.failure().message().contains("[REDACTED]"));
        assertFalse(failure.failure().message().contains(apiKey));
        assertFalse(failure.failure().message().contains("approval-capability"));
        assertFalse(failure.failure().message().contains("\u0000"));
        assertTrue(failure.failure().message().length() <= 500);
        assertEquals(providerCause, failure.getCause());
        assertEquals(failure.failure(), lifecycle.failures.getFirst());
    }

    @Test
    void interruptionProducesTypedOutcome() throws Exception {
        BlockingStreamingChatModel model = new BlockingStreamingChatModel(response("late"));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(Duration.ofSeconds(30));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, gate);

        StreamCapture capture = subscribe(loop.stream(TurnRequest.of("session", "interrupt")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));
        TurnResponse.Started started = capture.values.stream()
            .filter(TurnResponse.Started.class::isInstance)
            .map(TurnResponse.Started.class::cast)
            .findFirst().orElseThrow();
        assertTrue(loop.interrupt(started.turnId(), "user interrupted"));
        model.release.countDown();
        capture.await();

        assertNull(capture.error.get());
        TurnResponse.Interrupted interrupted = assertInstanceOf(TurnResponse.Interrupted.class,
            capture.values.getLast());
        assertEquals("user interrupted", interrupted.reason());
        assertEquals(1, capture.values.stream()
            .filter(TurnResponse.Outcome.class::isInstance).count());
    }

    @Test
    void timeoutTerminatesBlockingCallerAndRecordsOneCoherentOutcome() throws Exception {
        BlockingChatModel model = new BlockingChatModel(List.of(response("late")), 1);
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofMillis(250), Duration.ofMillis(5));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, gate);
        CompletableFuture<TurnResponse.Outcome> caller = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("timeout-session", "block")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        TurnResponse.TimedOut timedOut;
        try {
            timedOut = assertInstanceOf(TurnResponse.TimedOut.class,
                caller.get(2, TimeUnit.SECONDS));
        } finally {
            model.release.countDown();
        }

        assertEquals("turn_timeout", timedOut.reason());
        assertEquals(1, lifecycle.outcomes.size());
        assertEquals(timedOut, lifecycle.outcomes.getFirst());
        assertTrue(lifecycle.failures.isEmpty());
        awaitCondition(() -> gate.state("timeout-session")
            == io.mindspice.magenta.core.spi.TurnGate.GateState.IDLE);
    }

    @Test
    void timeoutTerminatesStreamingCallerBeforeProviderUnwinds() throws Exception {
        BlockingStreamingChatModel model = new BlockingStreamingChatModel(response("late"));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofMillis(250), Duration.ofMillis(5));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, gate);
        StreamCapture capture = subscribe(loop.stream(
            TurnRequest.of("timeout-stream", "block")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        try {
            capture.await();
        } finally {
            model.release.countDown();
        }

        assertNull(capture.error.get());
        TurnResponse.TimedOut timedOut = assertInstanceOf(TurnResponse.TimedOut.class,
            capture.values.getLast());
        assertEquals(1, capture.values.stream()
            .filter(TurnResponse.Outcome.class::isInstance).count());
        assertEquals(List.of(timedOut), lifecycle.outcomes);
        assertTrue(lifecycle.failures.isEmpty());
    }

    @Test
    void streamCancellationFencesLateInteractionPersistence() throws Exception {
        CancellationIgnoringStreamingChatModel model = new CancellationIgnoringStreamingChatModel();
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(Duration.ofSeconds(30));
        DefaultTurnLoop loop = createLoop(model, memory, interactions,
            new RecordingQueueStore(), lifecycle, gate);

        List<TurnResponse> firstResponses = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Disposable subscription = loop.stream(TurnRequest.of("session", "cancelled"))
            .subscribe(firstResponses::add, firstError::set);
        assertTrue(model.firstEntered.await(2, TimeUnit.SECONDS));
        subscription.dispose();
        awaitCondition(() -> gate.state("session") == io.mindspice.magenta.core.spi.TurnGate.GateState.IDLE);

        List<TurnResponse> successor = loop.stream(
            TurnRequest.of("session", "successor")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(successor);
        assertInstanceOf(TurnResponse.Message.class, successor.getLast());

        model.releaseFirst.countDown();
        assertTrue(model.firstExited.await(2, TimeUnit.SECONDS));
        awaitCondition(() -> model.calls.get() == 2);
        assertNull(firstError.get());
        assertTrue(firstResponses.stream().noneMatch(TurnResponse.Outcome.class::isInstance));
        assertEquals(List.of("successor", "fresh"), memory.getMessages("session").stream()
            .filter(message -> message instanceof TurnMessage.User
                || message instanceof TurnMessage.Assistant)
            .map(TurnMessage::text).toList());
        assertTrue(interactions.pending("session").isEmpty());
        assertTrue(lifecycle.outcomes.stream().anyMatch(
            TurnResponse.TurnCancelled.class::isInstance));
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle) {
        return createLoop(model, memory, interactions, queue, lifecycle,
            new SerialTurnGate(Duration.ofSeconds(30)));
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle,
                                       SerialTurnGate gate) {
        return createLoop(model, memory, interactions, queue, lifecycle, gate,
            new EmptyToolRegistry());
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle,
                                       SerialTurnGate gate,
                                       ToolRegistry tools) {
        return createLoop(model, memory, interactions, queue, lifecycle, gate, tools,
            new RecordingAudit(), new RecordingMetrics());
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle,
                                       SerialTurnGate gate,
                                       ToolRegistry tools,
                                       RecordingAudit audit,
                                       RecordingMetrics metrics) {
        return createLoop(model, memory, interactions, queue, lifecycle, gate, tools,
            audit, metrics, null);
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle,
                                       SerialTurnGate gate,
                                       ToolRegistry tools,
                                       RecordingAudit audit,
                                       RecordingMetrics metrics,
                                       String apiKey) {
        return createLoop(model, memory, interactions, queue, lifecycle, gate, tools,
            audit, metrics, apiKey, new ToolLoopGuard(), 10);
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle,
                                       SerialTurnGate gate,
                                       ToolRegistry tools,
                                       RecordingAudit audit,
                                       RecordingMetrics metrics,
                                       String apiKey,
                                       ToolLoopGuard toolLoopGuard,
                                       int maxToolIterations) {
        TokenEstimator estimator = new SimpleEstimator();
        CompactionStrategy compaction = context -> new CompactionResult("summary", 0, 0);
        ContextManager manager = new ContextManager(memory, estimator, compaction, 0, 75);
        ActiveTurnRegistry activeRegistry = new ConcurrentActiveTurnRegistry();
        TurnExecutor executor = new DefaultTurnExecutor();
        DefaultTurnEngine engine = new DefaultTurnEngine.Builder()
            .toolExecution(tools, ToolAccessPolicy.allowAll(), new DefaultToolResultSummarizer(),
                toolLoopGuard)
            .contextAssembly(memory, estimator, compaction)
            .contextManager(manager)
            .promptAssembler(new DefaultPromptAssembler("system", List.of()))
            .responseRepair(ctx -> false, (ctx, reason) -> List.of())
            .interactionStore(interactions)
            .infrastructure(new FakeRouter(model, apiKey), executor, activeRegistry,
                new CompositeThinkingExtractor())
            .config(maxToolIterations, 2, Duration.ZERO)
            .build();
        DefaultTurnLoop loop = new DefaultTurnLoop(
            engine, gate, queue, interactions, new Sessions(), memory, activeRegistry,
            executor, tools, alias -> Set.of(ModelCapability.STREAMING,
                ModelCapability.TOOL_CALLING), lifecycle, metrics, null,
            "model", Duration.ofMinutes(5), 5, audit, true);
        loops.add(loop);
        return loop;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse askUserResponse(String... questions) {
        String json = "{\"questions\":[\"" + String.join("\",\"", questions) + "\"]}";
        return toolCallResponse("ask_user", json);
    }

    private static ChatResponse toolCallResponse(String name, String arguments) {
        return toolCallResponse(name, arguments, "call-1");
    }

    private static ChatResponse toolCallResponse(String name, String arguments, String callId) {
        return toolCallResponse(List.of(toolCall(callId, name, arguments)));
    }

    private static ChatResponse toolCallResponse(List<AssistantMessage.ToolCall> calls) {
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.copyOf(calls))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage.ToolCall toolCall(
        String callId, String name, String arguments
    ) {
        return new AssistantMessage.ToolCall(callId, "function", name, arguments);
    }

    private static ToolCallback trackedCallback(
        String name,
        ToolResult result,
        Map<String, java.util.concurrent.atomic.AtomicInteger> executions
    ) {
        java.util.concurrent.atomic.AtomicInteger counter =
            new java.util.concurrent.atomic.AtomicInteger();
        executions.put(name, counter);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition(name, name, "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String input) {
                counter.incrementAndGet();
                return result;
            }
        };
    }

    private static int executionCount(
        Map<String, java.util.concurrent.atomic.AtomicInteger> executions,
        String toolName
    ) {
        return executions.get(toolName).get();
    }

    private static List<ToolActivityData> toolActivities(List<TurnResponse> responses) {
        return responses.stream()
            .filter(TurnResponse.ToolActivity.class::isInstance)
            .map(TurnResponse.ToolActivity.class::cast)
            .map(TurnResponse.ToolActivity::activity)
            .toList();
    }

    private static String activityKind(ToolActivityData activity) {
        return activity instanceof ToolActivityData.Failed failed
            ? failed.category().name() : "COMPLETED";
    }

    private static List<TurnMessage.Tool> durableTools(Memory memory, String sessionId) {
        return memory.getMessages(sessionId).stream()
            .filter(TurnMessage.Tool.class::isInstance)
            .map(TurnMessage.Tool.class::cast)
            .toList();
    }

    private static void assertGuardResultProjectionsDoNotContain(
        String secret,
        List<ToolActivityData> activities,
        List<ToolResponseMessage.ToolResponse> responses,
        List<TurnMessage.Tool> durableTools,
        List<AuditWriter.ToolExecutionRecord> auditRecords
    ) {
        assertFalse(activities.stream().anyMatch(activity ->
            activity.summary().contains(secret)));
        assertFalse(responses.stream().anyMatch(response ->
            response.responseData().contains(secret)));
        assertFalse(durableTools.stream().anyMatch(message ->
            message.text().contains(secret)
                || message.metadata().values().stream().anyMatch(value -> value.contains(secret))));
        assertFalse(auditRecords.stream().anyMatch(record ->
            record.arguments().contains(secret)
                || record.activity().summary().contains(secret)));
    }

    private static PromptToolBatch latestPromptToolBatch(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (!(messages.get(index) instanceof AssistantMessage assistant)
                || assistant.getToolCalls().isEmpty()) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (int responseIndex = index + 1;
                 responseIndex < messages.size()
                     && messages.get(responseIndex) instanceof ToolResponseMessage;
                 responseIndex++) {
                ToolResponseMessage tool =
                    (ToolResponseMessage) messages.get(responseIndex);
                responses.addAll(tool.getResponses());
            }
            return new PromptToolBatch(
                List.copyOf(assistant.getToolCalls()), List.copyOf(responses));
        }
        throw new AssertionError("prompt contains no assistant tool-call batch");
    }

    private static void requireCompleteToolPairing(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message instanceof ToolResponseMessage) {
                throw new IllegalStateException(
                    "provider prompt contains an orphaned tool response");
            }
            if (!(message instanceof AssistantMessage assistant)
                || assistant.getToolCalls().isEmpty()) {
                continue;
            }

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            int responseIndex = index + 1;
            while (responseIndex < messages.size()
                && messages.get(responseIndex) instanceof ToolResponseMessage tool) {
                responses.addAll(tool.getResponses());
                responseIndex++;
            }
            List<String> expectedIds = assistant.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::id).toList();
            List<String> expectedNames = assistant.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name).toList();
            List<String> actualIds = responses.stream()
                .map(ToolResponseMessage.ToolResponse::id).toList();
            List<String> actualNames = responses.stream()
                .map(ToolResponseMessage.ToolResponse::name).toList();
            if (!expectedIds.equals(actualIds) || !expectedNames.equals(actualNames)) {
                throw new IllegalStateException(
                    "provider prompt tool-call/result pairing mismatch: expected "
                        + expectedIds + " but received " + actualIds);
            }
            index = responseIndex - 1;
        }
    }

    private record PromptToolBatch(
        List<AssistantMessage.ToolCall> calls,
        List<ToolResponseMessage.ToolResponse> responses
    ) {
    }

    private void assertToolScenario(String name, ToolRegistry tools,
                                    List<ChatResponse> responses,
                                    ToolFailureCategory expectedCategory) {
        Memory memory = new Memory();
        RecordingAudit audit = new RecordingAudit();
        RecordingMetrics metrics = new RecordingMetrics();
        DefaultTurnLoop loop = createLoop(new ScriptedChatModel(responses), memory,
            new InMemoryInteractionStore(), new RecordingQueueStore(),
            new RecordingLifecycle(), new SerialTurnGate(Duration.ofSeconds(30)), tools,
            audit, metrics);
        List<TurnResponse> stream = loop.stream(
            TurnRequest.of(name + "-session", "use tool")).collectList()
            .block(Duration.ofSeconds(3));
        assertNotNull(stream);
        TurnResponse.Message outcome = assertInstanceOf(TurnResponse.Message.class, stream.getLast());
        assertTrue(outcome.text().equals("done") || outcome.text().equals("recovered"));
        List<ToolActivityData> activities = stream.stream()
            .filter(TurnResponse.ToolActivity.class::isInstance)
            .map(TurnResponse.ToolActivity.class::cast)
            .map(TurnResponse.ToolActivity::activity)
            .toList();
        assertFalse(activities.isEmpty());
        ToolActivityData activity = expectedCategory == null
            ? assertInstanceOf(ToolActivityData.Completed.class, activities.getLast())
            : activities.stream()
                .filter(candidate -> candidate instanceof ToolActivityData.Failed failed
                    && failed.category() == expectedCategory)
                .findFirst().orElseThrow();
        assertTrue(metrics.activities.contains(activity));
        assertTrue(audit.toolExecutions.stream()
            .map(AuditWriter.ToolExecutionRecord::activity)
            .anyMatch(activity::equals));
        List<TurnMessage.Tool> persistedMessages = memory.getMessages(name + "-session").stream()
            .filter(TurnMessage.Tool.class::isInstance)
            .map(TurnMessage.Tool.class::cast)
            .toList();
        assertFalse(persistedMessages.isEmpty());
        if (expectedCategory == null) {
            assertFalse(persistedMessages.getFirst().metadata().containsKey("tool.failure_category"));
        } else {
            assertTrue(persistedMessages.stream().anyMatch(persisted ->
                expectedCategory.name().equals(persisted.metadata().get("tool.failure_category"))));
        }
    }

    private static ToolRegistry annotatedRegistry() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerToolBean(new AnnotatedTool());
        return registry;
    }

    private static ToolCallback callback(String name, Object result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition(name, name, "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String input) {
                if (result instanceof Throwable failure) {
                    throw new RuntimeException(failure);
                }
                return (ToolResult) result;
            }
        };
    }

    private static final class AnnotatedTool {
        @Tool(name = "annotated", description = "Annotated test tool")
        public String execute(@ToolParam(name = "value") String value) {
            return "annotated:" + value;
        }
    }

    private static StreamCapture subscribe(Flux<TurnResponse> stream) {
        StreamCapture capture = new StreamCapture();
        stream.subscribe(capture.values::add, error -> {
            capture.error.set(error);
            capture.done.countDown();
        }, capture.done::countDown);
        return capture;
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "condition was not satisfied before timeout");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        for (;;) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // This fake deliberately models a provider that ignores cancellation.
            }
        }
    }

    private static final class StreamCapture {
        private final List<TurnResponse> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);

        private void await() throws InterruptedException {
            assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not terminate");
        }
    }

    private static final class PairingStrictScriptedChatModel extends ScriptedChatModel {
        private PairingStrictScriptedChatModel(List<ChatResponse> responses) {
            super(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            requireCompleteToolPairing(prompt);
            return super.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            requireCompleteToolPairing(prompt);
            return super.stream(prompt);
        }
    }

    private static class ScriptedChatModel implements ChatModel {
        private final Deque<ChatResponse> responses;
        protected final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        protected final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

        private ScriptedChatModel(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return nextResponse();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return Flux.just(nextResponse());
        }

        protected synchronized ChatResponse nextResponse() {
            return responses.isEmpty() ? response("default") : responses.removeFirst();
        }
    }

    private static final class BlockingChatModel extends ScriptedChatModel {
        private final int blockingCall;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingChatModel(List<ChatResponse> responses, int blockingCall) {
            super(responses);
            this.blockingCall = blockingCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            int call = calls.incrementAndGet();
            if (call == blockingCall) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
            return nextResponse();
        }
    }

    private static final class BlockingStreamingChatModel extends ScriptedChatModel {
        private final ChatResponse response;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingStreamingChatModel(ChatResponse response) {
            super(List.of());
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return Flux.create(sink -> {
                entered.countDown();
                awaitUninterruptibly(release);
                sink.next(response);
                sink.complete();
            });
        }
    }

    private static final class CancellationIgnoringStreamingChatModel extends ScriptedChatModel {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstExited = new CountDownLatch(1);

        private CancellationIgnoringStreamingChatModel() {
            super(List.of());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            int call = calls.incrementAndGet();
            if (call != 1) {
                return Flux.just(response("fresh"));
            }
            return Flux.create(sink -> {
                firstEntered.countDown();
                awaitUninterruptibly(releaseFirst);
                try {
                    sink.next(askUserResponse("late question"));
                    sink.complete();
                } finally {
                    firstExited.countDown();
                }
            });
        }
    }

    private static final class FailureChatModel extends ScriptedChatModel {
        private final RuntimeException failure;

        private FailureChatModel() {
            this(new IllegalStateException("provider failure"));
        }

        private FailureChatModel(RuntimeException failure) {
            super(List.of());
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            throw failure;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return Flux.error(failure);
        }
    }

    private static final class FakeRouter implements ModelRouter {
        private final ChatModel model;
        private final String apiKey;

        private FakeRouter(ChatModel model) {
            this(model, null);
        }

        private FakeRouter(ChatModel model, String apiKey) {
            this.model = model;
            this.apiKey = apiKey;
        }

        @Override public ChatModel springAiChatModel(String alias) { return model; }
        @Override public String remoteModelName(String alias) { return alias; }
        @Override public String apiKey(String alias) { return apiKey; }
        @Override public String selectModelAlias(String requested, String stored) {
            return requested != null && !requested.isBlank()
                ? requested : stored != null && !stored.isBlank() ? stored : "model";
        }
        @Override public int contextLength(String alias) { return 4096; }
        @Override public ToolCallingChatOptions toolCallingOptions(
            String alias, List<ToolCallback> tools) {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public ToolCallingChatOptions basicChatOptions(String alias) {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public boolean supportsStreaming(String alias) { return true; }
        @Override public void closeChatModels() { }
    }

    private static final class Memory implements ConversationMemory {
        private final Map<String, List<TurnMessage>> messages = new ConcurrentHashMap<>();

        @Override
        public List<TurnMessage> getMessages(String sessionId) {
            return List.copyOf(messages.getOrDefault(sessionId, List.of()));
        }

        @Override
        public synchronized void addMessages(String sessionId, List<TurnMessage> additions) {
            List<TurnMessage> existing = new ArrayList<>(messages.getOrDefault(sessionId, List.of()));
            existing.addAll(additions);
            messages.put(sessionId, List.copyOf(existing));
        }

        @Override
        public synchronized void replaceMessages(String sessionId, List<TurnMessage> replacement) {
            messages.put(sessionId, List.copyOf(replacement));
        }

        @Override
        public synchronized void clear(String sessionId) {
            messages.remove(sessionId);
        }
    }

    private static final class Sessions implements SessionStore {
        private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

        @Override public void save(ConversationSession session) { sessions.put(session.sessionId(), session); }
        @Override public ConversationSession findById(String id) { return sessions.get(id); }
        @Override public List<ConversationSession> list(SessionFilter filter) {
            return List.copyOf(sessions.values());
        }
        @Override public void delete(String id) { sessions.remove(id); }
        @Override public void updateTitle(String id, String title) { }
        @Override public void setFavorite(String id, boolean value) { }
        @Override public void setArchived(String id, boolean value) { }
        @Override public void updateModel(String id, String model) {
            ConversationSession current = sessions.get(id);
            if (current != null) {
                sessions.put(id, new ConversationSession(current.sessionId(), current.title(), model,
                    current.favorite(), current.archived(), current.origin(), current.createdAt(),
                    current.updatedAt(), current.metadata()));
            }
        }
    }

    private static final class EmptyToolRegistry implements ToolRegistry {
        private final List<ToolCallback> tools;

        private EmptyToolRegistry() {
            this(List.of());
        }

        private EmptyToolRegistry(List<ToolCallback> tools) {
            this.tools = List.copyOf(tools);
        }

        @Override public void register(ToolCallback tool) { }
        @Override public void registerFrameworkTool(ToolCallback tool) { }
        @Override public void registerToolBean(Object bean) { }
        @Override public void unregister(String name) { }
        @Override public List<ToolDefinition> definitions() {
            return tools.stream().map(ToolCallback::getToolDefinition).toList();
        }
        @Override public List<ToolCallback> resolveForTurn(TurnContext context) { return tools; }
    }

    private static final class RecordingQueueStore implements MessageQueueStore {
        private final List<QueuedRequest> requests = new ArrayList<>();
        private final Map<String, Claim> claimsByToken = new HashMap<>();
        private final Map<String, String> tokenByMessage = new HashMap<>();
        private final List<String> acknowledgedMessageIds = new ArrayList<>();
        private final List<String> releasedMessageIds = new ArrayList<>();
        private int claimCalls;
        private int failedAcknowledgements;

        @Override
        public synchronized void insert(QueuedRequest request) {
            requests.add(request);
        }

        @Override
        public synchronized List<QueuedRequest> list(String sessionId) {
            return requests.stream()
                .filter(request -> request.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(QueuedRequest::enqueuedAt)
                    .thenComparing(QueuedRequest::messageId))
                .toList();
        }

        @Override
        public synchronized Optional<Claim> claimOldest(String sessionId,
                                                         Instant claimedAt,
                                                         Duration lease) {
            claimCalls++;
            QueuedRequest request = list(sessionId).stream()
                .filter(candidate -> !tokenByMessage.containsKey(candidate.messageId()))
                .findFirst().orElse(null);
            if (request == null) return Optional.empty();
            String token = UUID.randomUUID().toString();
            Claim claim = new Claim(request, token, claimedAt, claimedAt.plus(lease));
            claimsByToken.put(token, claim);
            tokenByMessage.put(request.messageId(), token);
            return Optional.of(claim);
        }

        @Override
        public synchronized boolean ack(String claimToken) {
            if (failedAcknowledgements > 0) {
                failedAcknowledgements--;
                return false;
            }
            Claim claim = claimsByToken.remove(claimToken);
            if (claim == null) return false;
            tokenByMessage.remove(claim.request().messageId());
            requests.removeIf(request -> request.messageId().equals(claim.request().messageId()));
            acknowledgedMessageIds.add(claim.request().messageId());
            return true;
        }

        @Override
        public synchronized boolean release(String claimToken) {
            Claim claim = claimsByToken.remove(claimToken);
            if (claim == null) return false;
            tokenByMessage.remove(claim.request().messageId());
            releasedMessageIds.add(claim.request().messageId());
            return true;
        }

        @Override
        public synchronized int recoverExpiredClaims(Instant now) {
            List<String> expired = claimsByToken.values().stream()
                .filter(claim -> !claim.leaseExpiresAt().isAfter(now))
                .map(Claim::claimToken).toList();
            expired.forEach(token -> {
                Claim claim = claimsByToken.remove(token);
                if (claim != null) tokenByMessage.remove(claim.request().messageId());
            });
            return expired.size();
        }

        @Override
        public synchronized void deleteBySessionId(String sessionId) {
            requests.removeIf(request -> request.sessionId().equals(sessionId));
            claimsByToken.values().removeIf(claim -> claim.request().sessionId().equals(sessionId));
            tokenByMessage.entrySet().removeIf(entry -> requests.stream()
                .noneMatch(request -> request.messageId().equals(entry.getKey())));
        }
    }

    private static final class SimpleEstimator implements TokenEstimator {
        @Override public int estimateTokens(String text) {
            return text == null ? 0 : Math.max(1, text.length() / 4);
        }
        @Override public int estimateTokens(TurnMessage message) {
            return estimateTokens(message.text()) + 1;
        }
    }

    private static final class RecordingLifecycle implements TurnLifecycleListener {
        private final List<TurnResponse.Outcome> outcomes = new CopyOnWriteArrayList<>();
        private final List<TurnFailure> failures = new CopyOnWriteArrayList<>();
        private final boolean throwFromCallbacks;

        private RecordingLifecycle() {
            this(false);
        }

        private RecordingLifecycle(boolean throwFromCallbacks) {
            this.throwFromCallbacks = throwFromCallbacks;
        }

        @Override
        public void onOutcome(TurnResponse.Outcome outcome) {
            outcomes.add(outcome);
            if (throwFromCallbacks) throw new IllegalStateException("listener outcome failure");
        }

        @Override
        public void onFailure(TurnFailure failure) {
            failures.add(failure);
            if (throwFromCallbacks) throw new IllegalStateException("listener failure failure");
        }
    }

    private static final class RecordingAudit implements AuditWriter {
        private final List<TurnError> errors = new CopyOnWriteArrayList<>();
        private final List<ToolExecutionRecord> toolExecutions = new CopyOnWriteArrayList<>();

        @Override public void recordUserMessage(String sessionId, TurnMessage message) { }
        @Override public void recordAssistantMessage(String sessionId, TurnMessage message) { }
        @Override public void recordToolExecution(String sessionId, ToolExecutionRecord record) {
            toolExecutions.add(record);
        }
        @Override public void recordContextUsage(String sessionId, ContextUsage usage, String model) { }
        @Override public void recordError(String sessionId, TurnError error) {
            errors.add(error);
        }
    }

    private static final class RecordingMetrics implements TurnLoopMetrics {
        private final List<String> outcomes = new CopyOnWriteArrayList<>();
        private final List<ToolActivityData> activities = new CopyOnWriteArrayList<>();

        @Override public void recordTurnDuration(String model, String outcome, long durationMs) {
            outcomes.add(outcome);
        }
        @Override public void recordToolCall(ToolActivityData activity) {
            activities.add(activity);
        }
        @Override public void setActiveTurns(long count) { }
        @Override public void setQueueDepth(String sessionId, long depth) { }
    }
}
