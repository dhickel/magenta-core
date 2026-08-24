package io.mindspice.magenta.core.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.*;
import io.mindspice.magenta.core.internal.ActiveTurnRegistry;
import io.mindspice.magenta.core.internal.ModelRouter;
import io.mindspice.magenta.core.internal.TurnExecutor;
import io.mindspice.magenta.core.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Internal transport-neutral turn state machine. Spring AI is confined to this
 * engine and the model-router boundary; callers receive only Magenta events,
 * records, and exceptions.
 */
public final class DefaultTurnEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultTurnEngine.class);
    private static final ObjectMapper CODEC = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private static final String PENDING_TYPE = "turnloop.pending_interaction";
    private static final String CONTINUATION_FLAG = "true";

    private final ToolRegistry toolRegistry;
    private final ToolAccessPolicy toolAccessPolicy;
    private final ToolResultSummarizer toolResultSummarizer;
    private final ToolLoopGuard toolLoopGuard;
    private final ConversationMemory conversationMemory;
    private final ContextManager contextManager;
    private final DefaultPromptAssembler promptAssembler;
    private final CompletionDetector completionDetector;
    private final RepairStrategy repairStrategy;
    private final InteractionHandler interactionHandler;
    private final InteractionStore interactionStore;
    private final ModelRouter modelRouter;
    private final TurnExecutor turnExecutor;
    private final ActiveTurnRegistry activeTurnRegistry;
    private final ThinkingExtractor thinkingExtractor;
    private final int maxToolRounds;
    private final int maxEmptyResponseRetries;

    private DefaultTurnEngine(Builder builder) {
        this.toolRegistry = builder.toolRegistry;
        this.toolAccessPolicy = builder.toolAccessPolicy;
        this.toolResultSummarizer = builder.toolResultSummarizer;
        this.toolLoopGuard = builder.toolLoopGuard;
        this.conversationMemory = builder.conversationMemory;
        this.contextManager = builder.contextManager;
        this.promptAssembler = builder.promptAssembler;
        this.completionDetector = builder.completionDetector;
        this.repairStrategy = builder.repairStrategy;
        this.interactionHandler = builder.interactionHandler;
        this.interactionStore = builder.interactionStore;
        this.modelRouter = builder.modelRouter;
        this.turnExecutor = builder.turnExecutor;
        this.activeTurnRegistry = builder.activeTurnRegistry;
        this.thinkingExtractor = builder.thinkingExtractor;
        this.maxToolRounds = Math.max(1, builder.maxToolRounds);
        this.maxEmptyResponseRetries = Math.max(0, builder.maxEmptyResponseRetries);

        this.contextManager.contextLengthProvider(this::configuredContextLength);
    }

    /** Executes one turn as a cold event stream. */
    public Flux<TurnEvent> stream(TurnContext ctx) {
        return Flux.create(sink -> {
            ActiveTurnRegistry.ActiveTurn active = null;
            boolean registeredHere = false;
            try {
                active = activeTurnRegistry.get(ctx.turnId());
                if (active == null) {
                    active = activeTurnRegistry.register(ctx.turnId());
                    registeredHere = true;
                }
                ActiveTurnRegistry.ActiveTurn registered = active;
                boolean engineOwnsRegistration = registeredHere;
                CompletableFuture<Void> task = turnExecutor.submit(ctx.sessionId(), () -> {
                    try {
                        runTurnLoop(ctx, sink, registered);
                    } finally {
                        if (engineOwnsRegistration || registered.revocationReason().isPresent()) {
                            activeTurnRegistry.remove(ctx.turnId());
                        }
                    }
                    return null;
                });

                sink.onCancel(() -> {
                    registered.revoke("subscriber_cancelled");
                    task.cancel(true);
                });

                task.whenComplete((ignored, failure) -> {
                    if (failure == null || sink.isCancelled()) {
                        return;
                    }
                    Throwable cause = unwrap(failure);
                    if (cause instanceof java.util.concurrent.CancellationException) {
                        return;
                    }
                    emitFailure(ctx, sink, cause);
                });
            } catch (Throwable failure) {
                if (active != null && registeredHere) {
                    activeTurnRegistry.remove(ctx.turnId());
                }
                emitFailure(ctx, sink, failure);
            }
        });
    }

    /** Executes a turn and reduces its terminal events to a Magenta result. */
    public TurnResult execute(TurnContext ctx) {
        List<TurnEvent> events = new ArrayList<>();
        try {
            return stream(ctx)
                .doOnNext(events::add)
                .blockLast() == null
                ? reduceToResult(ctx, events)
                : reduceToResult(ctx, events);
        } catch (TurnFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof TurnFailedException tfe) {
                throw tfe;
            }
            throw new TurnFailedException(
                cause.getMessage() == null ? "Turn execution failed" : cause.getMessage(),
                failureDiagnostic(ctx, cause));
        }
    }

    /** Closes provider models owned by the internal router. */
    public void closeChatModels() {
        modelRouter.closeChatModels();
    }

    /** Computes model-specific context usage for facade accessors. */
    public ContextUsage contextUsage(String sessionId, String modelAlias) {
        return contextManager.computeUsage(sessionId, modelAlias);
    }

    /** Resolves request, stored-session, then first-registered model precedence. */
    public String selectModelAlias(String requestedAlias, String sessionAlias) {
        return modelRouter.selectModelAlias(requestedAlias, sessionAlias);
    }

    /** Resolves the first registered alias without exposing router/provider types. */
    public String firstRegisteredModel(String fallback) {
        try {
            return modelRouter.selectModelAlias(null, null);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    // ── state machine ──

    private void runTurnLoop(TurnContext ctx, FluxSink<TurnEvent> sink,
                             ActiveTurnRegistry.ActiveTurn active) {
        Instant startedAt = Instant.now();
        TurnLoopState state = new TurnLoopState(ctx);

        try {
            active.phase(TurnPhase.PREPARE);
            if (ctx.streaming() && !modelRouter.supportsStreaming(ctx.model())) {
                throw new TurnFailedException(
                    "Model '" + ctx.model() + "' does not support streaming",
                    diagnostic(ctx, startedAt, TurnPhase.PREPARE,
                        TurnDiagnostic.OutcomeKind.FAILED,
                        Map.of("reason", "streaming_unsupported", "model", ctx.model())));
            }

            state.activeTools = toolAccessPolicy.filterTools(
                exposeInteractionSchema(toolRegistry.resolveForTurn(ctx)), ctx);
            ctx.activeTools(state.activeTools);

            String systemText = promptAssembler.assemble(ctx);
            state.systemPromptText = systemText;
            state.conversationMessages = new ArrayList<>(
                turnMessagesToSpring(contextManager.window(ctx, configuredContextLength(ctx.model()))));

            QuestionPendingContext continuation = continuationContext(ctx);
            if (continuation != null) {
                TurnMessage questionAnswer = questionAnswerMessage(continuation,
                    ctx.request().message(), ctx.turnId());
                state.messagesToPersist.add(questionAnswer);
                state.conversationMessages.add(turnMessageToSpring(questionAnswer));
            }

            TurnMessage currentUser = currentUserMessage(ctx);
            state.messagesToPersist.add(currentUser);
            state.conversationMessages.add(turnMessageToSpring(currentUser));
            state.prompt = new Prompt(buildPromptMessages(systemText, state.conversationMessages),
                modelRouter.toolCallingOptions(ctx.model(), state.activeTools));

            ContextUsage startUsage = computeUsage(ctx);
            ctx.currentContextUsage(startUsage);
            sink.next(new TurnEvent.Started(ctx.turnId(), Instant.now(), ctx.turnId(), startUsage));

            state.currentPhase = TurnPhase.INVOKE_MODEL;
            while (state.currentPhase != TurnPhase.DONE) {
                Optional<String> revoked = active.revocationReason();
                if (revoked.isPresent() || sink.isCancelled() || Thread.currentThread().isInterrupted()) {
                    state.wasInterrupted = true;
                    state.interruptReason = revoked.orElse("subscriber_cancelled");
                    state.currentPhase = TurnPhase.FINALIZE;
                } else {
                    Optional<String> interrupt = active.pollInterrupt();
                    if (interrupt.isPresent()) {
                        state.wasInterrupted = true;
                        state.interruptReason = interrupt.get();
                        sink.next(new TurnEvent.Interrupt(ctx.turnId(), Instant.now(),
                            state.interruptReason, computeUsage(ctx)));
                        state.currentPhase = TurnPhase.FINALIZE;
                    }
                }

                if (state.currentPhase == TurnPhase.FINALIZE) {
                    handleFinalize(ctx, state, sink, startedAt, active);
                    break;
                }
                if (state.currentPhase == TurnPhase.DONE) {
                    break;
                }

                active.phase(state.currentPhase);
                switch (state.currentPhase) {
                    case INVOKE_MODEL -> handleInvokeModel(ctx, state, sink);
                    case EVALUATE -> handleEvaluate(ctx, state);
                    case EXECUTE_TOOLS -> handleExecuteTools(ctx, state, sink, active);
                    case REPAIR -> handleRepair(ctx, state, sink);
                    default -> state.currentPhase = TurnPhase.FINALIZE;
                }
            }
        } catch (TurnFailedException e) {
            persistFailure(ctx, state, active);
            clearPendingAfterFailure(ctx.sessionId(), active);
            throw e;
        } catch (Throwable e) {
            persistFailure(ctx, state, active);
            clearPendingAfterFailure(ctx.sessionId(), active);
            throw new TurnFailedException(
                e.getMessage() == null ? "Turn execution failed" : e.getMessage(),
                failureDiagnostic(ctx, e));
        }
    }

    private void handleInvokeModel(TurnContext ctx, TurnLoopState state,
                                   FluxSink<TurnEvent> sink) {
        ChatModel model = modelRouter.springAiChatModel(ctx.model());
        state.lastResponsePersisted = false;

        try {
            if (ctx.streaming()) {
                StreamingResponse streaming = new StreamingResponse();
                model.stream(state.prompt)
                    .doOnNext(response -> {
                        if (response == null) {
                            return;
                        }
                        streaming.lastResponse = response;
                        emitResponseContent(ctx, response, sink, streaming);
                    })
                    .blockLast();
                state.response = streaming.lastResponse;
                state.allVisibleText = streaming.visibleText.toString();
                state.latestThinking = streaming.thinkingText.toString();
            } else {
                state.response = model.call(state.prompt);
                if (state.response != null && state.response.getResult() != null) {
                    ThinkingExtractor.ThinkingResult extracted = extractThinking(state.response);
                    state.latestThinking = extracted.thinkingText();
                    state.latestVisibleText = extracted.visibleText();
                    state.allVisibleText = extracted.visibleText();
                    emitThinkingAndVisible(ctx, extracted, sink);
                }
            }
            ctx.currentContextUsage(computeUsage(ctx));
            state.currentPhase = TurnPhase.EVALUATE;
        } catch (Throwable e) {
            throw new TurnFailedException(
                "Model invocation failed: " + (e.getMessage() == null ? "unknown" : e.getMessage()),
                diagnostic(ctx, Instant.now(), TurnPhase.INVOKE_MODEL,
                    TurnDiagnostic.OutcomeKind.FAILED,
                    Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage())));
        }
    }

    private void emitResponseContent(TurnContext ctx, ChatResponse response,
                                     FluxSink<TurnEvent> sink, StreamingResponse target) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        ThinkingExtractor.ThinkingResult extracted = extractThinking(response);
        if (!extracted.visibleText().isEmpty()) {
            target.visibleText.append(extracted.visibleText());
        }
        if (!extracted.thinkingText().isEmpty()) {
            target.thinkingText.append(extracted.thinkingText());
        }
        emitThinkingAndVisible(ctx, extracted, sink);
    }

    private void emitThinkingAndVisible(TurnContext ctx,
                                        ThinkingExtractor.ThinkingResult extracted,
                                        FluxSink<TurnEvent> sink) {
        ContextUsage usage = computeUsage(ctx);
        if (!extracted.visibleText().isEmpty() && !sink.isCancelled()) {
            sink.next(new TurnEvent.Chunk(ctx.turnId(), Instant.now(),
                extracted.visibleText(), false, usage));
        }
        if (!extracted.thinkingText().isEmpty() && !sink.isCancelled()) {
            sink.next(new TurnEvent.Chunk(ctx.turnId(), Instant.now(),
                extracted.thinkingText(), true, usage));
        }
    }

    private ThinkingExtractor.ThinkingResult extractThinking(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new ThinkingExtractor.ThinkingResult("", "");
        }
        return extractThinking(response.getResult());
    }

    private ThinkingExtractor.ThinkingResult extractThinking(
        org.springframework.ai.chat.model.Generation generation) {
        if (generation == null || generation.getOutput() == null) {
            return new ThinkingExtractor.ThinkingResult("", "");
        }
        AssistantMessage output = generation.getOutput();
        String rawText = output.getText() == null ? "" : output.getText();
        String thinkingMetadata = null;
        Map<String, Object> metadata = new HashMap<>();
        if (generation.getMetadata() != null) {
            for (Map.Entry<String, Object> entry : generation.getMetadata().entrySet()) {
                metadata.put(entry.getKey(), entry.getValue());
            }
            Object value = generation.getMetadata().get("thinking");
            if (value != null && !value.toString().isBlank()) {
                thinkingMetadata = value.toString();
            }
        }
        String reasoning = null;
        if (output.getMetadata() != null) {
            Object value = output.getMetadata().get("reasoningContent");
            if (value != null && !value.toString().isBlank()) {
                reasoning = value.toString();
            }
        }
        String reflectedReasoning = extractReasoningViaReflection(output);
        if (reasoning == null) {
            reasoning = reflectedReasoning;
        }
        return thinkingExtractor.extract(new ThinkingExtractor.AssistantMessageContent(
            rawText, thinkingMetadata, reasoning, metadata));
    }

    private void handleEvaluate(TurnContext ctx, TurnLoopState state) {
        if (completionDetector.isComplete(ctx)) {
            state.currentPhase = TurnPhase.FINALIZE;
            return;
        }
        if (hasToolCalls(state.response)) {
            state.currentPhase = TurnPhase.EXECUTE_TOOLS;
            return;
        }
        if (state.toolLoopAbortReason != null) {
            state.currentPhase = TurnPhase.REPAIR;
            return;
        }
        if (isEmptyResponse(state.response)
            && state.emptyResponseRetries < maxEmptyResponseRetries) {
            state.currentPhase = TurnPhase.REPAIR;
            return;
        }
        state.currentPhase = TurnPhase.FINALIZE;
    }

    private void handleExecuteTools(TurnContext ctx, TurnLoopState state,
                                    FluxSink<TurnEvent> sink,
                                    ActiveTurnRegistry.ActiveTurn active) {
        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(state.response);
        if (toolCalls.isEmpty()) {
            state.currentPhase = TurnPhase.EVALUATE;
            return;
        }

        try {
            toolLoopGuard.recordToolCalls(toolCalls.stream()
                .map(call -> new ToolLoopGuard.ToolCallIdentity(call.name(), call.arguments()))
                .toList());
        } catch (ToolException.LoopExceeded e) {
            state.toolLoopAbortReason = e.getMessage();
            state.currentPhase = TurnPhase.REPAIR;
            return;
        }

        ctx.incrementToolRoundCount();
        if (ctx.toolRoundCount() > maxToolRounds) {
            state.toolLoopAbortReason = "Maximum tool rounds exceeded";
            state.currentPhase = TurnPhase.REPAIR;
            return;
        }

        Map<String, ToolCallback> tools = indexTools(state.activeTools);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        List<TurnMessage> durableToolMessages = new ArrayList<>();
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (Thread.currentThread().isInterrupted()) {
                flushToolState(state, responses, durableToolMessages);
                state.wasInterrupted = true;
                state.interruptReason = "interrupted";
                state.currentPhase = TurnPhase.FINALIZE;
                return;
            }
            Optional<String> interrupt = active.pollInterrupt();
            if (interrupt.isPresent()) {
                flushToolState(state, responses, durableToolMessages);
                state.wasInterrupted = true;
                state.interruptReason = interrupt.get();
                state.currentPhase = TurnPhase.FINALIZE;
                return;
            }

            if ("ask_user".equals(call.name())) {
                AssistantMessage output = state.response.getResult().getOutput();
                appendAssistantToolMessage(state, output);
                List<String> questions = extractQuestions(call.arguments());
                String questionSummary = formatQuestions(questions);
                ToolResponseMessage.ToolResponse toolResponse =
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), questionSummary);
                state.conversationMessages.add(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse)).build());
                state.messagesToPersist.add(new TurnMessage(
                    "tool", questionSummary, call.name(), call.id(), Map.of(
                        "tool.arguments", call.arguments(),
                        "tool.success", "true",
                        "tool.duration_ms", "0",
                        "tool.summary", "Questions pending")));
                state.toolActivities.add(new ToolActivityData(
                    call.name(), call.id(), ToolActivityStatus.COMPLETED, "Questions pending"));
                sink.next(new TurnEvent.ToolActivity(ctx.turnId(), Instant.now(),
                    call.name(), call.id(), ToolActivityStatus.COMPLETED,
                    "Questions pending", computeUsage(ctx)));
                handleAskUser(ctx, state, questions, sink, active);
                return;
            }

            ToolCallback callback = tools.get(call.name());
            ToolActivityStatus status;
            String summary;
            String resultText;
            long started = System.nanoTime();
            if (callback == null) {
                status = ToolActivityStatus.FAILED;
                summary = "Tool not found: " + call.name();
                resultText = summary;
            } else {
                try {
                    String raw = callback.call(call.arguments());
                    ToolResultSummarizer.SummarizeResult summarized =
                        toolResultSummarizer.summarize(new ToolResultSummarizer.ToolExecution(
                            call.name(), call.id(), call.arguments(), raw == null ? "" : raw));
                    resultText = summarized.summary();
                    summary = summarized.summary();
                    status = ToolActivityStatus.COMPLETED;
                } catch (Throwable e) {
                    resultText = "Tool execution failed: "
                        + (e.getMessage() == null ? "unknown" : e.getMessage());
                    summary = resultText;
                    status = ToolActivityStatus.FAILED;
                }
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText));
            ToolActivityData activity = new ToolActivityData(call.name(), call.id(), status, summary);
            state.toolActivities.add(activity);
            Map<String, String> toolMetadata = new LinkedHashMap<>();
            toolMetadata.put("tool.arguments", call.arguments());
            toolMetadata.put("tool.success", Boolean.toString(status == ToolActivityStatus.COMPLETED));
            toolMetadata.put("tool.duration_ms", Long.toString(durationMs));
            toolMetadata.put("tool.summary", summary);
            durableToolMessages.add(new TurnMessage("tool", resultText,
                call.name(), call.id(), toolMetadata));
            sink.next(new TurnEvent.ToolActivity(ctx.turnId(), Instant.now(),
                call.name(), call.id(), status, summary, computeUsage(ctx)));

            try {
                toolLoopGuard.recordToolResponses(new ToolLoopGuard.ToolExecutionResult(
                    status == ToolActivityStatus.COMPLETED,
                    call.name(), status == ToolActivityStatus.FAILED ? summary : null));
            } catch (ToolException.LoopExceeded e) {
                state.toolLoopAbortReason = e.getMessage();
            }

            if (state.toolLoopAbortReason != null) {
                break;
            }
        }

        AssistantMessage assistantOutput = state.response.getResult().getOutput();
        flushToolState(state, responses, durableToolMessages);

        state.prompt = new Prompt(buildPromptMessages(state.systemPromptText,
            state.conversationMessages), modelRouter.toolCallingOptions(ctx.model(), state.activeTools));
        ctx.currentContextUsage(computeUsage(ctx));
        state.currentPhase = state.toolLoopAbortReason == null
            ? TurnPhase.INVOKE_MODEL : TurnPhase.REPAIR;
    }

    private void flushToolState(TurnLoopState state,
                                List<ToolResponseMessage.ToolResponse> responses,
                                List<TurnMessage> durableToolMessages) {
        AssistantMessage output = state.response == null || state.response.getResult() == null
            ? null : state.response.getResult().getOutput();
        appendAssistantToolMessage(state, output);
        if (!responses.isEmpty()) {
            state.conversationMessages.add(ToolResponseMessage.builder()
                .responses(List.copyOf(responses)).build());
        }
        if (!durableToolMessages.isEmpty()) {
            state.messagesToPersist.addAll(List.copyOf(durableToolMessages));
        }
    }

    private void handleRepair(TurnContext ctx, TurnLoopState state, FluxSink<TurnEvent> sink) {
        RepairStrategy.RepairReason reason = state.toolLoopAbortReason != null
            ? RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED
            : RepairStrategy.RepairReason.EMPTY_RESPONSE;
        if (reason == RepairStrategy.RepairReason.EMPTY_RESPONSE) {
            state.emptyResponseRetries++;
            if (state.emptyResponseRetries > maxEmptyResponseRetries) {
                state.currentPhase = TurnPhase.FINALIZE;
                return;
            }
        }

        List<TurnMessage> repairs = repairStrategy.repair(ctx, reason);
        if (repairs.isEmpty()) {
            repairs = List.of(TurnMessage.system(
                reason == RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED
                    ? "Tool loop guard triggered. Continue without further tool calls."
                    : "Continue with a concise user-visible answer or an appropriate tool call."));
        }
        for (TurnMessage repair : repairs) {
            TurnMessage durable = withMetadata(repair, Map.of(
                "turnloop.persistence_type", "repair"));
            state.messagesToPersist.add(durable);
            state.conversationMessages.add(turnMessageToSpring(durable));
        }
        sink.next(new TurnEvent.SystemNotice(ctx.turnId(), Instant.now(),
            reason == RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED
                ? NoticeKind.TOOL_LOOP_WARNING : NoticeKind.REPAIR_APPLIED,
            reason == RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED
                ? "Tool loop guard triggered" : "Turn repair applied",
            computeUsage(ctx)));
        state.toolLoopAbortReason = null;
        state.prompt = new Prompt(buildPromptMessages(state.systemPromptText,
            state.conversationMessages), modelRouter.toolCallingOptions(ctx.model(), state.activeTools));
        state.currentPhase = TurnPhase.INVOKE_MODEL;
    }

    private void handleFinalize(TurnContext ctx, TurnLoopState state,
                                FluxSink<TurnEvent> sink, Instant startedAt,
                                ActiveTurnRegistry.ActiveTurn active) {
        if (!state.wasInterrupted && !state.suspended && !state.lastResponsePersisted
            && state.response != null && state.response.getResult() != null
            && state.response.getResult().getOutput() != null) {
            String text = state.allVisibleText == null || state.allVisibleText.isEmpty()
                ? nullToEmpty(state.response.getResult().getOutput().getText())
                : state.allVisibleText;
            state.messagesToPersist.add(assistantMessage(text, state.latestThinking));
            state.lastResponsePersisted = true;
        }

        if (state.wasInterrupted) {
            state.messagesToPersist.add(withMetadata(
                TurnMessage.system("Turn interrupted: " + nullToEmpty(state.interruptReason)),
                Map.of("turnloop.persistence_type", "interrupted")));
        }
        if (!persistState(state, ctx.sessionId(), active)) {
            state.wasInterrupted = true;
            state.interruptReason = active.revocationReason().orElse("execution_fenced");
        }

        ContextUsage finalUsage = computeUsage(ctx);
        TurnDiagnostic.OutcomeKind outcome = interruptedOutcome(state.interruptReason,
            state.wasInterrupted);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("model", ctx.model());
        details.put("toolRounds", ctx.toolRoundCount());
        details.put("toolActivities", state.toolActivities.size());
        if (state.interruptReason != null) {
            details.put("reason", state.interruptReason);
        }
        TurnDiagnostic diagnostic = diagnostic(ctx, startedAt, TurnPhase.FINALIZE, outcome, details);

        if (outcome == TurnDiagnostic.OutcomeKind.COMPLETED) {
            sink.next(new TurnEvent.Completed(ctx.turnId(), Instant.now(), diagnostic, finalUsage));
        } else {
            sink.next(new TurnEvent.Failed(ctx.turnId(), Instant.now(), diagnostic, finalUsage));
        }
        triggerAsyncCompaction(ctx, finalUsage, active);
        state.currentPhase = TurnPhase.DONE;
        if (!sink.isCancelled()) {
            sink.complete();
        }
    }

    private void handleAskUser(TurnContext ctx, TurnLoopState state,
                               List<String> questions, FluxSink<TurnEvent> sink,
                               ActiveTurnRegistry.ActiveTurn active) {
        List<String> ordered = questions == null || questions.isEmpty()
            ? List.of("The assistant has a question for you.")
            : List.copyOf(questions);
        Map<String, String> continuation = new LinkedHashMap<>();
        if (ctx.request().metadata() != null) {
            continuation.putAll(ctx.request().metadata());
        }
        continuation.put("turnloop.pending_tool_names", writeJson(
            ctx.request().toolNames() == null ? List.of() : ctx.request().toolNames()));
        QuestionPendingContext pending = new QuestionPendingContext(
            ctx.sessionId(), ctx.turnId(), ordered, ctx.model(), continuation);

        AnswerResult handlerResult;
        try {
            handlerResult = interactionHandler == null
                ? new AnswerResult.Continue()
                : interactionHandler.onQuestionsAsked(pending);
        } catch (Throwable e) {
            active.runIfActive(() -> interactionStore.delete(ctx.sessionId()));
            throw e;
        }
        if (handlerResult instanceof AnswerResult.Abort abort) {
            state.wasInterrupted = true;
            state.interruptReason = abort.reason();
            state.messagesToPersist.add(withMetadata(
                TurnMessage.system("Question cancelled: " + abort.reason()),
                Map.of("turnloop.persistence_type", "interaction_cancelled")));
            if (!persistState(state, ctx.sessionId(), active)) {
                markRevoked(state, active);
                state.currentPhase = TurnPhase.FINALIZE;
                return;
            }
            sink.next(new TurnEvent.Interrupt(ctx.turnId(), Instant.now(),
                abort.reason(), computeUsage(ctx)));
            state.currentPhase = TurnPhase.DONE;
            if (!sink.isCancelled()) sink.complete();
            return;
        }

        boolean saved = active.runIfActive(() -> {
            try {
                interactionStore.save(ctx.sessionId(), pending);
                state.messagesToPersist.add(pendingMarker(pending));
                persistState(state, ctx.sessionId(), active);
            } catch (Throwable e) {
                interactionStore.delete(ctx.sessionId());
                removePendingMarkers(ctx.sessionId());
                throw e;
            }
        });
        if (!saved) {
            markRevoked(state, active);
            state.currentPhase = TurnPhase.FINALIZE;
            return;
        }

        state.pendingContext = pending;
        state.suspended = true;
        sink.next(new TurnEvent.QuestionPending(ctx.turnId(), Instant.now(),
            ordered, computeUsage(ctx)));
        state.currentPhase = TurnPhase.DONE;
        if (!sink.isCancelled()) sink.complete();
    }

    private void triggerAsyncCompaction(TurnContext ctx, ContextUsage usage,
                                        ActiveTurnRegistry.ActiveTurn active) {
        if (usage == null || !contextManager.needsCompaction(usage, configuredContextLength(ctx.model()))) {
            return;
        }
        try {
            turnExecutor.submit(ctx.sessionId(), () -> {
                try {
                    List<TurnMessage> messages = conversationMemory.getMessages(ctx.sessionId()).stream()
                        .filter(message -> !isPendingMarker(message))
                        .toList();
                    if (messages.size() < 2) {
                        return null;
                    }
                    int count = Math.min(messages.size(), 50);
                    CompactionResult result = toolCompaction(messages, count, usage, ctx.sessionId());
                    if (result != null && !result.summary().isBlank()) {
                        List<TurnMessage> summary = List.of(withMetadata(
                            TurnMessage.system(result.summary()),
                            Map.of("type", "compaction_summary")));
                        active.runIfActive(() ->
                            conversationMemory.addMessages(ctx.sessionId(), summary));
                    }
                } catch (Throwable e) {
                    log.warn("Async compaction failed for session {}: {}",
                        ctx.sessionId(), e.getMessage());
                }
                return null;
            });
        } catch (Throwable e) {
            log.warn("Unable to schedule compaction for session {}: {}",
                ctx.sessionId(), e.getMessage());
        }
    }

    private CompactionResult toolCompaction(List<TurnMessage> messages, int count,
                                            ContextUsage usage, String sessionId) {
        return contextManager.compact(new CompactionContext(
            sessionId, List.copyOf(messages.subList(0, count)), usage));
    }

    // ── persistence and continuation ──

    private boolean persistState(TurnLoopState state, String sessionId,
                                 ActiveTurnRegistry.ActiveTurn active) {
        if (state.messagesToPersist.isEmpty()) {
            return active.revocationReason().isEmpty();
        }
        List<TurnMessage> snapshot = List.copyOf(state.messagesToPersist);
        boolean persisted = active.runIfActive(() ->
            conversationMemory.addMessages(sessionId, snapshot));
        if (persisted) {
            state.messagesToPersist.clear();
            state.statePersisted = true;
        }
        return persisted;
    }

    private void persistFailure(TurnContext ctx, TurnLoopState state,
                                ActiveTurnRegistry.ActiveTurn active) {
        try {
            if (!state.messagesToPersist.isEmpty()) {
                persistState(state, ctx.sessionId(), active);
            }
        } catch (Throwable e) {
            log.warn("Unable to persist failed turn for session {}: {}",
                ctx.sessionId(), e.getMessage());
        }
    }

    private void clearPendingAfterFailure(String sessionId,
                                          ActiveTurnRegistry.ActiveTurn active) {
        try {
            active.runIfActive(() -> {
                if (interactionStore.load(sessionId) != null) {
                    removePendingMarkers(sessionId);
                    interactionStore.delete(sessionId);
                }
            });
        } catch (Throwable e) {
            log.warn("Unable to clear pending interaction for session {}: {}",
                sessionId, e.getMessage());
        }
    }

    private void removePendingMarkers(String sessionId) {
        List<TurnMessage> all = conversationMemory.getMessages(sessionId);
        List<TurnMessage> retained = all.stream()
            .filter(message -> !isPendingMarker(message))
            .toList();
        if (retained.size() == all.size()) {
            return;
        }
        conversationMemory.clear(sessionId);
        if (!retained.isEmpty()) {
            conversationMemory.addMessages(sessionId, retained);
        }
    }

    private TurnMessage pendingMarker(QuestionPendingContext pending) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", PENDING_TYPE);
        metadata.put("turnloop.pending_turn_id", pending.turnId());
        metadata.put("turnloop.pending_model", nullToEmpty(pending.model()));
        metadata.put("turnloop.pending_questions", writeJson(pending.questions()));
        metadata.put("turnloop.pending_tool_names", pending.continuationContext()
            .getOrDefault("turnloop.pending_tool_names", "[]"));
        metadata.put("turnloop.pending_metadata", writeJson(pending.continuationContext()));
        return new TurnMessage("system", "[Magenta interaction pending]", null, null, metadata);
    }

    private boolean isPendingMarker(TurnMessage message) {
        return message != null && message.metadata() != null
            && PENDING_TYPE.equals(message.metadata().get("turnloop.persistence_type"));
    }

    private QuestionPendingContext continuationContext(TurnContext ctx) {
        Map<String, String> metadata = ctx.request().metadata();
        if (metadata == null || !CONTINUATION_FLAG.equals(metadata.get("turnloop.continuation"))) {
            return null;
        }
        List<String> questions = readJsonList(metadata.get("turnloop.pending_questions"));
        String model = emptyToNull(metadata.get("turnloop.pending_model"));
        Map<String, String> originalMetadata = readJsonMap(metadata.get("turnloop.pending_metadata"));
        return new QuestionPendingContext(ctx.sessionId(),
            emptyToNull(metadata.get("turnloop.pending_turn_id")) == null
                ? ctx.turnId() : metadata.get("turnloop.pending_turn_id"),
            questions, model, originalMetadata);
    }

    private TurnMessage questionAnswerMessage(QuestionPendingContext pending,
                                               String answer, String turnId) {
        StringBuilder text = new StringBuilder();
        text.append("[Ordered questions and combined answer]\n");
        for (int i = 0; i < pending.questions().size(); i++) {
            text.append("Question ").append(i + 1).append(": ")
                .append(pending.questions().get(i)).append('\n');
        }
        text.append("Combined answer: ").append(answer);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", "question_answer_context");
        metadata.put("turnloop.pending_turn_id", pending.turnId());
        metadata.put("turnloop.answer_turn_id", turnId);
        return new TurnMessage("system", text.toString(), null, null, metadata);
    }

    private TurnMessage currentUserMessage(TurnContext ctx) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", "user_message");
        metadata.put("turnloop.turn_id", ctx.turnId());
        return new TurnMessage("user", ctx.request().message(), null, null, metadata);
    }

    private TurnMessage assistantMessage(String text, String thinking) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", "assistant_message");
        if (thinking != null && !thinking.isBlank()) {
            metadata.put("thinking", thinking);
        }
        return new TurnMessage("assistant", text, null, null, metadata);
    }

    private void appendAssistantToolMessage(TurnLoopState state, AssistantMessage output) {
        if (state.lastResponsePersisted || output == null) {
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", "assistant_tool_activity");
        List<String> calls = output.getToolCalls() == null ? List.of()
            : output.getToolCalls().stream().map(call -> call.name() + ":" + call.arguments()).toList();
        metadata.put("tool_calls", writeJson(calls));
        if (state.latestThinking != null && !state.latestThinking.isBlank()) {
            metadata.put("thinking", state.latestThinking);
        }
        state.messagesToPersist.add(new TurnMessage(
            "assistant", nullToEmpty(output.getText()), null, null, metadata));
        state.lastResponsePersisted = true;
    }

    private TurnMessage withMetadata(TurnMessage message, Map<String, String> additions) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (message.metadata() != null) metadata.putAll(message.metadata());
        metadata.putAll(additions);
        return new TurnMessage(message.role(), message.text(), message.toolName(),
            message.toolCallId(), metadata);
    }

    private ContextUsage computeUsage(TurnContext ctx) {
        try {
            return contextManager.computeUsage(ctx.sessionId(), ctx.model());
        } catch (RuntimeException e) {
            return new ContextUsage(0, 0, configuredContextLength(ctx.model()));
        }
    }

    // ── result and diagnostics ──

    private void markRevoked(TurnLoopState state, ActiveTurnRegistry.ActiveTurn active) {
        state.wasInterrupted = true;
        state.interruptReason = active.revocationReason().orElse("execution_fenced");
    }

    private TurnDiagnostic.OutcomeKind interruptedOutcome(String reason, boolean interrupted) {
        if (!interrupted) {
            return TurnDiagnostic.OutcomeKind.COMPLETED;
        }
        if ("turn_timeout".equals(reason)) {
            return TurnDiagnostic.OutcomeKind.TIMED_OUT;
        }
        if ("subscriber_cancelled".equals(reason)) {
            return TurnDiagnostic.OutcomeKind.CANCELLED;
        }
        return TurnDiagnostic.OutcomeKind.INTERRUPTED;
    }

    private TurnResult reduceToResult(TurnContext ctx, List<TurnEvent> events) {
        TurnDiagnostic diagnostic = null;
        ContextUsage usage = null;
        StringBuilder visible = new StringBuilder();
        List<ToolActivityData> activities = new ArrayList<>();

        for (TurnEvent event : events) {
            if (event instanceof TurnEvent.Completed completed) {
                diagnostic = completed.diagnostic();
            } else if (event instanceof TurnEvent.Failed failed) {
                diagnostic = failed.diagnostic();
            } else if (event instanceof TurnEvent.Queued queued) {
                diagnostic = queued.diagnostic();
            } else if (event instanceof TurnEvent.QuestionPending pending) {
                diagnostic = diagnostic(ctx, Instant.now(), TurnPhase.FINALIZE,
                    TurnDiagnostic.OutcomeKind.SUSPENDED,
                    Map.of("questions", pending.questions()));
            } else if (event instanceof TurnEvent.Interrupt interrupt) {
                diagnostic = diagnostic(ctx, Instant.now(), TurnPhase.FINALIZE,
                    TurnDiagnostic.OutcomeKind.INTERRUPTED,
                    Map.of("reason", interrupt.reason()));
            } else if (event instanceof TurnEvent.ToolActivity activity) {
                activities.add(new ToolActivityData(activity.toolName(), activity.toolCallId(),
                    activity.status(), activity.summary()));
            } else if (event instanceof TurnEvent.Chunk chunk && !chunk.thinking()) {
                visible.append(chunk.text());
            }
            if (event.contextUsage() != null) usage = event.contextUsage();
        }

        if (diagnostic == null) {
            diagnostic = diagnostic(ctx, Instant.now(), TurnPhase.FINALIZE,
                TurnDiagnostic.OutcomeKind.COMPLETED, Map.of());
        }
        if (usage == null) usage = computeUsage(ctx);
        String message = visible.isEmpty() ? null : visible.toString();
        if (diagnostic.outcomeKind() == TurnDiagnostic.OutcomeKind.SUSPENDED
            && message == null) {
            message = events.stream()
                .filter(TurnEvent.QuestionPending.class::isInstance)
                .map(TurnEvent.QuestionPending.class::cast)
                .findFirst()
                .map(event -> String.join("\n", event.questions()))
                .orElse(null);
        }
        return new TurnResult(ctx.sessionId(), ctx.turnId(), message, usage,
            diagnostic, List.copyOf(activities));
    }

    private void emitFailure(TurnContext ctx, FluxSink<TurnEvent> sink, Throwable failure) {
        if (sink.isCancelled()) {
            return;
        }
        Throwable cause = unwrap(failure);
        TurnFailedException exception = cause instanceof TurnFailedException tfe
            ? tfe
            : new TurnFailedException(
                cause.getMessage() == null ? "Turn execution failed" : cause.getMessage(),
                failureDiagnostic(ctx, cause));
        TurnDiagnostic diagnostic = exception.diagnostic();
        sink.next(new TurnEvent.Failed(ctx.turnId(), Instant.now(), diagnostic,
            computeUsage(ctx)));
        sink.error(exception);
    }

    private TurnDiagnostic failureDiagnostic(TurnContext ctx, Throwable failure) {
        return diagnostic(ctx, Instant.now(), TurnPhase.FINALIZE,
            TurnDiagnostic.OutcomeKind.FAILED,
            Map.of("error", failure.getMessage() == null ? "unknown" : failure.getMessage()));
    }

    private TurnDiagnostic diagnostic(TurnContext ctx, Instant startedAt,
                                     TurnPhase phase, TurnDiagnostic.OutcomeKind outcome,
                                     Map<String, ?> details) {
        Map<String, Object> copied = new LinkedHashMap<>();
        details.forEach(copied::put);
        return new TurnDiagnostic(ctx.turnId(), ctx.sessionId(), startedAt, Instant.now(),
            phase, outcome, copied);
    }

    // ── conversion and parsing ──

    static Message turnMessageToSpring(TurnMessage message) {
        return switch (message.role()) {
            case "system" -> new org.springframework.ai.chat.messages.SystemMessage(message.text());
            case "assistant" -> new AssistantMessage(message.text());
            case "tool" -> ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                    nullToEmpty(message.toolCallId()), nullToEmpty(message.toolName()), message.text())))
                .build();
            default -> new org.springframework.ai.chat.messages.UserMessage(message.text());
        };
    }

    private List<Message> turnMessagesToSpring(List<TurnMessage> messages) {
        return messages.stream().map(DefaultTurnEngine::turnMessageToSpring).toList();
    }

    private List<Message> buildPromptMessages(String systemText, List<Message> conversation) {
        List<Message> messages = new ArrayList<>();
        if (systemText != null && !systemText.isBlank()) {
            messages.add(new org.springframework.ai.chat.messages.SystemMessage(systemText));
        }
        messages.addAll(conversation);
        return messages;
    }

    private List<AssistantMessage.ToolCall> extractToolCalls(ChatResponse response) {
        if (response == null || response.getResult() == null
            || response.getResult().getOutput() == null
            || response.getResult().getOutput().getToolCalls() == null) {
            return List.of();
        }
        return response.getResult().getOutput().getToolCalls();
    }

    private boolean hasToolCalls(ChatResponse response) {
        return !extractToolCalls(response).isEmpty();
    }

    private boolean isEmptyResponse(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return true;
        }
        AssistantMessage output = response.getResult().getOutput();
        return (output.getText() == null || output.getText().isBlank()) && !hasToolCalls(response);
    }

    private List<ToolCallback> exposeInteractionSchema(List<ToolCallback> tools) {
        List<ToolCallback> normalized = new ArrayList<>(tools.size());
        for (ToolCallback tool : tools) {
            if (!"ask_user".equals(tool.getToolDefinition().name())) {
                normalized.add(tool);
                continue;
            }
            ToolDefinition original = tool.getToolDefinition();
            ToolDefinition definition = new ToolDefinition(
                original.name(), original.description(),
                "{\"type\":\"object\",\"properties\":{"
                    + "\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                    + "\"question\":{\"type\":\"string\"}},"
                    + "\"anyOf\":[{\"required\":[\"questions\"]},{\"required\":[\"question\"]}]}" );
            normalized.add(new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return definition; }
                @Override public String call(String input) { return tool.call(input); }
            });
        }
        return List.copyOf(normalized);
    }

    private Map<String, ToolCallback> indexTools(List<ToolCallback> tools) {
        Map<String, ToolCallback> indexed = new LinkedHashMap<>();
        for (ToolCallback tool : tools) {
            indexed.put(tool.getToolDefinition().name(), tool);
        }
        return indexed;
    }

    private List<String> extractQuestions(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of("The assistant has a question for you.");
        }
        try {
            var node = CODEC.readTree(arguments);
            if (node.isArray()) {
                return nodeToQuestions(node);
            }
            if (node.has("questions")) {
                return nodeToQuestions(node.get("questions"));
            }
            for (String key : List.of("question", "message", "text")) {
                if (node.has(key) && !node.get(key).asText().isBlank()) {
                    return List.of(node.get(key).asText());
                }
            }
        } catch (Exception ignored) {
            // The raw tool argument is still useful as a visible question.
        }
        return List.of(arguments);
    }

    private List<String> nodeToQuestions(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> questions = new ArrayList<>();
        if (node == null) return questions;
        if (node.isArray()) {
            for (var item : node) {
                String value = item.isTextual() ? item.asText()
                    : item.has("question") ? item.get("question").asText() : item.asText();
                if (value != null && !value.isBlank()) questions.add(value);
            }
        } else if (!node.asText().isBlank()) {
            questions.add(node.asText());
        }
        return List.copyOf(questions);
    }

    private String formatQuestions(List<String> questions) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) result.append('\n');
            result.append("Question ").append(i + 1).append(": ").append(questions.get(i));
        }
        return result.toString();
    }

    private String writeJson(Object value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(CODEC.readValue(json, STRING_LIST));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, String> readJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return Map.copyOf(CODEC.readValue(json, STRING_MAP));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String extractReasoningViaReflection(AssistantMessage output) {
        try {
            Method method = output.getClass().getMethod("getReasoningContent");
            Object value = method.invoke(output);
            return value instanceof String string && !string.isBlank() ? string : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── router metadata ──

    private int configuredContextLength(String alias) {
        try {
            int configured = modelRouter.contextLength(alias);
            return configured > 0 ? configured : 128_000;
        } catch (RuntimeException ignored) {
            return 128_000;
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable result = error;
        while ((result instanceof CompletionException
                || result instanceof java.util.concurrent.ExecutionException)
            && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    // ── state/builder ──

    private static final class StreamingResponse {
        private ChatResponse lastResponse;
        private final StringBuilder visibleText = new StringBuilder();
        private final StringBuilder thinkingText = new StringBuilder();
    }

    private static final class TurnLoopState {
        private final TurnContext context;
        private final List<TurnMessage> messagesToPersist = new ArrayList<>();
        private final List<ToolActivityData> toolActivities = new ArrayList<>();
        private List<ToolCallback> activeTools = List.of();
        private List<Message> conversationMessages = new ArrayList<>();
        private String systemPromptText;
        private Prompt prompt;
        private ChatResponse response;
        private String allVisibleText = "";
        private String latestVisibleText = "";
        private String latestThinking = "";
        private String toolLoopAbortReason;
        private String interruptReason;
        private TurnPhase currentPhase = TurnPhase.PREPARE;
        private int emptyResponseRetries;
        private boolean wasInterrupted;
        private boolean suspended;
        private boolean lastResponsePersisted;
        private boolean statePersisted;
        private QuestionPendingContext pendingContext;

        private TurnLoopState(TurnContext context) {
            this.context = context;
        }
    }

    public static final class Builder {
        ToolRegistry toolRegistry;
        ToolAccessPolicy toolAccessPolicy = ToolAccessPolicy.allowAll();
        ToolResultSummarizer toolResultSummarizer;
        ToolLoopGuard toolLoopGuard = new ToolLoopGuard();
        ConversationMemory conversationMemory;
        TokenEstimator tokenEstimator;
        CompactionStrategy compactionStrategy;
        ContextManager contextManager;
        DefaultPromptAssembler promptAssembler;
        CompletionDetector completionDetector = ctx -> false;
        RepairStrategy repairStrategy = (ctx, reason) -> List.of();
        InteractionHandler interactionHandler;
        InteractionStore interactionStore;
        ModelRouter modelRouter;
        TurnExecutor turnExecutor;
        ActiveTurnRegistry activeTurnRegistry;
        ThinkingExtractor thinkingExtractor;
        int maxToolRounds = 10;
        int maxEmptyResponseRetries = 2;

        public Builder toolExecution(ToolRegistry registry, ToolAccessPolicy policy,
                                     ToolResultSummarizer summarizer, ToolLoopGuard guard) {
            this.toolRegistry = registry;
            this.toolAccessPolicy = policy == null ? ToolAccessPolicy.allowAll() : policy;
            this.toolResultSummarizer = summarizer;
            this.toolLoopGuard = guard == null ? new ToolLoopGuard() : guard;
            return this;
        }

        public Builder contextAssembly(ConversationMemory memory, TokenEstimator estimator,
                                       CompactionStrategy strategy) {
            this.conversationMemory = memory;
            this.tokenEstimator = estimator;
            this.compactionStrategy = strategy;
            return this;
        }

        public Builder contextManager(ContextManager manager) {
            this.contextManager = manager;
            return this;
        }

        public Builder promptAssembler(DefaultPromptAssembler assembler) {
            this.promptAssembler = assembler;
            return this;
        }

        public Builder responseRepair(CompletionDetector detector, RepairStrategy strategy) {
            this.completionDetector = detector == null ? ctx -> false : detector;
            this.repairStrategy = strategy == null ? (ctx, reason) -> List.of() : strategy;
            return this;
        }

        public Builder interaction(InteractionHandler handler, InteractionStore store) {
            this.interactionHandler = handler;
            this.interactionStore = store;
            return this;
        }

        public Builder infrastructure(ModelRouter router, TurnExecutor executor,
                                       ActiveTurnRegistry registry, ThinkingExtractor extractor) {
            this.modelRouter = router;
            this.turnExecutor = executor;
            this.activeTurnRegistry = registry;
            this.thinkingExtractor = extractor;
            return this;
        }

        public Builder config(int maxToolRounds, int maxEmptyResponseRetries,
                             java.time.Duration ignoredTimeout) {
            this.maxToolRounds = maxToolRounds;
            this.maxEmptyResponseRetries = maxEmptyResponseRetries;
            return this;
        }

        public DefaultTurnEngine build() {
            require(toolRegistry, "toolRegistry");
            require(toolResultSummarizer, "toolResultSummarizer");
            require(conversationMemory, "conversationMemory");
            require(tokenEstimator, "tokenEstimator");
            require(compactionStrategy, "compactionStrategy");
            require(contextManager, "contextManager");
            require(promptAssembler, "promptAssembler");
            require(interactionStore, "interactionStore");
            require(modelRouter, "modelRouter");
            require(turnExecutor, "turnExecutor");
            require(activeTurnRegistry, "activeTurnRegistry");
            require(thinkingExtractor, "thinkingExtractor");
            if (interactionHandler == null) {
                interactionHandler = context -> new AnswerResult.Continue();
            }
            return new DefaultTurnEngine(this);
        }

        private static void require(Object value, String name) {
            if (value == null) {
                throw new ConfigurationException(name + " is required", Map.of("missing", name));
            }
        }
    }
}
