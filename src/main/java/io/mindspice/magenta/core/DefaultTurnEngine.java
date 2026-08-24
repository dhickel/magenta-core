package io.mindspice.magenta.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.CompletionDetector;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.InteractionStore;
import io.mindspice.magenta.core.spi.RepairStrategy;
import io.mindspice.magenta.core.spi.ThinkingExtractor;
import io.mindspice.magenta.core.spi.ToolAccessPolicy;
import io.mindspice.magenta.core.spi.ToolRegistry;
import io.mindspice.magenta.core.spi.ToolResultSummarizer;
import io.mindspice.magenta.core.spi.TokenEstimator;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal transport-neutral turn state machine. Spring AI is confined to this
 * engine and the model-router boundary; callers receive only Magenta response
 * records and exceptions.
 */
final class DefaultTurnEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultTurnEngine.class);
    private static final ObjectMapper CODEC = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private static final String PENDING_TYPE = "turnloop.pending_interaction";
    private static final String CONTINUATION_FLAG = "true";
    private static final String PENDING_INTERACTION_ID = "turnloop.pending_interaction_id";
    private static final String PENDING_ANSWERS = "turnloop.pending_answers";

    private final ToolRegistry toolRegistry;
    private final ToolAccessPolicy toolAccessPolicy;
    private final ToolResultSummarizer toolResultSummarizer;
    private final ToolLoopGuard toolLoopGuard;
    private final ConversationMemory conversationMemory;
    private final ContextManager contextManager;
    private final DefaultPromptAssembler promptAssembler;
    private final CompletionDetector completionDetector;
    private final RepairStrategy repairStrategy;
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
        this.interactionStore = builder.interactionStore;
        this.modelRouter = builder.modelRouter;
        this.turnExecutor = builder.turnExecutor;
        this.activeTurnRegistry = builder.activeTurnRegistry;
        this.thinkingExtractor = builder.thinkingExtractor;
        this.maxToolRounds = Math.max(1, builder.maxToolRounds);
        this.maxEmptyResponseRetries = Math.max(0, builder.maxEmptyResponseRetries);

        this.contextManager.contextLengthProvider(this::configuredContextLength);
    }

    /** Executes one turn as a cold response stream. */
    public Flux<TurnResponse> stream(TurnContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return Flux.defer(() -> createStream(ctx));
    }

    private Flux<TurnResponse> createStream(TurnContext ctx) {
        return Flux.create(sink -> {
            Instant submittedAt = Instant.now();
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
                AtomicReference<CompletableFuture<Void>> taskReference = new AtomicReference<>();

                sink.onCancel(() -> {
                    registered.revoke("subscriber_cancelled");
                    CompletableFuture<Void> task = taskReference.get();
                    if (task != null) {
                        task.cancel(true);
                    }
                });

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
                taskReference.set(task);
                if (sink.isCancelled()) {
                    registered.revoke("subscriber_cancelled");
                    task.cancel(true);
                }

                ActiveTurnRegistry.ActiveTurn taskActive = registered;
                task.whenComplete((ignored, failure) -> {
                    if (failure != null && !sink.isCancelled()) {
                        emitFailure(ctx, sink, taskActive, submittedAt, failure);
                    }
                });
            } catch (Throwable failure) {
                if (active != null && registeredHere) {
                    activeTurnRegistry.remove(ctx.turnId());
                }
                emitFailure(ctx, sink, active, submittedAt, failure);
            }
        });
    }

    /**
     * Executes a turn by collecting the same response stream exposed to
     * callers and reducing the exact emitted values to its one outcome.
     */
    public TurnResponse.Outcome execute(TurnContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        try {
            List<TurnResponse> responses = stream(ctx).collectList().block();
            return reduceToOutcome(ctx, responses == null ? List.of() : responses);
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof TurnFailedException turnFailed) {
                throw turnFailed;
            }
            if (isSpecializedFailure(cause)) {
                throw (RuntimeException) cause;
            }
            throw failureException(ctx, Instant.now(), currentPhase(ctx), cause);
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

    private void runTurnLoop(TurnContext ctx, FluxSink<TurnResponse> sink,
                             ActiveTurnRegistry.ActiveTurn active) {
        Instant startedAt = Instant.now();
        TurnLoopState state = new TurnLoopState(ctx, toolLoopGuard, executionOrigin(ctx));

        try {
            active.phase(TurnPhase.PREPARE);
            if (ctx.streaming() && !modelRouter.supportsStreaming(ctx.model())) {
                throw new ConfigurationException(
                    "Selected model does not support streaming",
                    Map.of("error", "streaming_unsupported"));
            }

            state.activeTools = toolAccessPolicy.filterTools(
                exposeInteractionSchema(toolRegistry.resolveForTurn(ctx)), ctx);
            ctx.activeTools(state.activeTools);

            String systemText = promptAssembler.assemble(ctx);
            state.systemPromptText = systemText;
            state.conversationMessages = new ArrayList<>(
                turnMessagesToSpring(contextManager.window(ctx,
                    configuredContextLength(ctx.model()))));

            InteractionStore.Interaction continuation = continuationContext(ctx);
            if (continuation != null) {
                TurnMessage questionAnswer = questionAnswerMessage(continuation, ctx);
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
            emit(sink, new TurnResponse.Started(
                ctx.sessionId(), ctx.turnId(), Instant.now(), state.origin, ctx.model(), startUsage));

            state.currentPhase = TurnPhase.INVOKE_MODEL;
            while (state.currentPhase != TurnPhase.DONE) {
                Optional<String> revoked = active.revocationReason();
                if (revoked.isPresent() || sink.isCancelled() || Thread.currentThread().isInterrupted()) {
                    state.wasInterrupted = true;
                    state.interruptReason = normalizedReason(
                        revoked.orElse("interrupted"), "interrupted");
                    state.currentPhase = TurnPhase.FINALIZE;
                } else {
                    Optional<String> interrupt = active.pollInterrupt();
                    if (interrupt.isPresent()) {
                        state.wasInterrupted = true;
                        state.interruptReason = normalizedReason(interrupt.get(), "interrupted");
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
        } catch (TurnFailedException failure) {
            persistFailure(ctx, state, active);
            clearPendingAfterFailure(ctx.sessionId(), active);
            throw failure;
        } catch (Throwable failure) {
            Optional<String> controlReason = controlReason(active, failure);
            if (controlReason.isPresent() && !sink.isCancelled()) {
                state.wasInterrupted = true;
                state.interruptReason = normalizedReason(controlReason.get(), "interrupted");
                state.currentPhase = TurnPhase.FINALIZE;
                try {
                    handleFinalize(ctx, state, sink, startedAt, active);
                    return;
                } catch (TurnFailedException terminalFailure) {
                    persistFailure(ctx, state, active);
                    clearPendingAfterFailure(ctx.sessionId(), active);
                    throw terminalFailure;
                } catch (Throwable terminalFailure) {
                    failure = terminalFailure;
                }
            }
            if (isSpecializedFailure(failure)) {
                persistFailure(ctx, state, active);
                clearPendingAfterFailure(ctx.sessionId(), active);
                throw (RuntimeException) failure;
            }
            persistFailure(ctx, state, active);
            clearPendingAfterFailure(ctx.sessionId(), active);
            throw failureException(ctx, startedAt, state.currentPhase, failure);
        }
    }

    private void handleInvokeModel(TurnContext ctx, TurnLoopState state,
                                   FluxSink<TurnResponse> sink) {
        ChatModel model = modelRouter.springAiChatModel(ctx.model());
        state.lastResponsePersisted = false;

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
            state.visibleText.append(streaming.visibleText);
            state.latestThinking = streaming.thinkingText.toString();
        } else {
            state.response = model.call(state.prompt);
            if (state.response != null && state.response.getResult() != null) {
                ThinkingExtractor.ThinkingResult extracted = extractThinking(state.response);
                state.latestThinking = extracted.thinkingText();
                state.allVisibleText = extracted.visibleText();
                state.visibleText.append(extracted.visibleText());
                emitThinkingAndVisible(ctx, extracted, sink);
            } else {
                state.latestThinking = "";
                state.allVisibleText = "";
            }
        }
        ctx.currentContextUsage(computeUsage(ctx));
        state.currentPhase = TurnPhase.EVALUATE;
    }

    private void emitResponseContent(TurnContext ctx, ChatResponse response,
                                     FluxSink<TurnResponse> sink, StreamingResponse target) {
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
                                        FluxSink<TurnResponse> sink) {
        if (!extracted.visibleText().isEmpty() && !sink.isCancelled()) {
            emit(sink, new TurnResponse.ContentChunk(
                ctx.sessionId(), ctx.turnId(), Instant.now(), extracted.visibleText()));
        }
        if (!extracted.thinkingText().isEmpty() && !sink.isCancelled()) {
            emit(sink, new TurnResponse.ThinkingChunk(
                ctx.sessionId(), ctx.turnId(), Instant.now(), extracted.thinkingText()));
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
        if (isEmptyResponse(state.response)) {
            if (state.emptyResponseRetries < maxEmptyResponseRetries) {
                state.currentPhase = TurnPhase.REPAIR;
            } else {
                state.currentPhase = TurnPhase.FINALIZE;
            }
            return;
        }
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
        state.currentPhase = TurnPhase.FINALIZE;
    }

    private void handleExecuteTools(TurnContext ctx, TurnLoopState state,
                                    FluxSink<TurnResponse> sink,
                                    ActiveTurnRegistry.ActiveTurn active) {
        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(state.response);
        if (toolCalls.isEmpty()) {
            state.currentPhase = TurnPhase.EVALUATE;
            return;
        }

        try {
            state.toolLoopGuard.recordToolCalls(toolCalls.stream()
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
                state.interruptReason = normalizedReason(interrupt.get(), "interrupted");
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
                emit(sink, new TurnResponse.ToolActivity(ctx.sessionId(), ctx.turnId(), Instant.now(),
                    new ToolActivityData(call.name(), call.id(), ToolActivityStatus.COMPLETED,
                        "Questions pending")));
                handleAskUser(ctx, state, questions, active);
                return;
            }

            ToolActivityStatus status;
            String summary;
            String resultText;
            long started = System.nanoTime();
            ToolCallback callback = tools.get(call.name());
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
                } catch (Throwable failure) {
                    resultText = "Tool execution failed";
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
            emit(sink, new TurnResponse.ToolActivity(ctx.sessionId(), ctx.turnId(), Instant.now(),
                activity));

            try {
                state.toolLoopGuard.recordToolResponses(new ToolLoopGuard.ToolExecutionResult(
                    status == ToolActivityStatus.COMPLETED,
                    call.name(), status == ToolActivityStatus.FAILED ? summary : null));
            } catch (ToolException.LoopExceeded e) {
                state.toolLoopAbortReason = e.getMessage();
            }

            if (state.toolLoopAbortReason != null) {
                break;
            }
        }

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

    private void handleRepair(TurnContext ctx, TurnLoopState state, FluxSink<TurnResponse> sink) {
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
        if (repairs == null || repairs.isEmpty()) {
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
        emit(sink, new TurnResponse.Notice(ctx.sessionId(), ctx.turnId(), Instant.now(),
            reason == RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED
                ? NoticeKind.TOOL_LOOP_WARNING : NoticeKind.REPAIR_APPLIED,
            reason == RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED
                ? "Tool loop guard triggered" : "Turn repair applied"));
        state.toolLoopAbortReason = null;
        state.prompt = new Prompt(buildPromptMessages(state.systemPromptText,
            state.conversationMessages), modelRouter.toolCallingOptions(ctx.model(), state.activeTools));
        state.currentPhase = TurnPhase.INVOKE_MODEL;
    }

    private void handleFinalize(TurnContext ctx, TurnLoopState state,
                                FluxSink<TurnResponse> sink, Instant startedAt,
                                ActiveTurnRegistry.ActiveTurn active) {
        if (!state.wasInterrupted && !state.suspended && !state.lastResponsePersisted
            && state.response != null && state.response.getResult() != null
            && state.response.getResult().getOutput() != null) {
            String text = state.visibleText.toString();
            if (text.isBlank()) {
                throw failureException(ctx, startedAt, TurnPhase.FINALIZE,
                    TurnFailureCategory.MODEL, "Model returned no visible response");
            }
            state.messagesToPersist.add(assistantMessage(nullToEmpty(state.allVisibleText),
                state.latestThinking));
            state.lastResponsePersisted = true;
        }

        if (state.wasInterrupted) {
            state.messagesToPersist.add(withMetadata(
                TurnMessage.system("Turn interrupted: " + nullToEmpty(state.interruptReason)),
                Map.of("turnloop.persistence_type", "interrupted")));
        }
        if (!persistState(state, ctx.sessionId(), active)) {
            state.wasInterrupted = true;
            state.interruptReason = normalizedReason(
                active.revocationReason().orElse("execution_fenced"), "execution_fenced");
        }

        ContextUsage finalUsage = computeUsage(ctx);
        Instant completedAt = atOrAfter(startedAt);
        ExecutionInfo execution = new ExecutionInfo(
            ctx.sessionId(), ctx.turnId(), state.origin, startedAt, completedAt,
            finalUsage, List.copyOf(state.toolActivities));

        TurnResponse.Outcome outcome;
        if (state.wasInterrupted) {
            outcome = interruptionOutcome(execution, state.interruptReason);
        } else if (state.suspended) {
            String interactionId = state.pendingInteractionId;
            if (interactionId == null || interactionId.isBlank() || state.pendingContext == null) {
                throw failureException(ctx, startedAt, TurnPhase.FINALIZE,
                    TurnFailureCategory.PERSISTENCE, "Pending interaction state is incomplete");
            }
            // The interaction store owns the exact Questions snapshot. Reuse
            // that snapshot so pendingQuestions() and the emitted terminal
            // response cannot diverge in execution metadata.
            outcome = state.pendingContext.questions();
        } else {
            String text = state.visibleText.toString();
            if (text.isBlank()) {
                throw failureException(ctx, startedAt, TurnPhase.FINALIZE,
                    TurnFailureCategory.MODEL, "Model returned no visible response");
            }
            outcome = new TurnResponse.Message(execution, text);
        }

        if (!sink.isCancelled()) {
            emit(sink, outcome);
        }
        if (!state.suspended) {
            triggerAsyncCompaction(ctx, finalUsage, active);
        }
        state.currentPhase = TurnPhase.DONE;
        if (!sink.isCancelled()) {
            sink.complete();
        }
    }

    private TurnResponse.Outcome interruptionOutcome(ExecutionInfo execution, String reason) {
        String normalized = normalizedReason(reason, "interrupted");
        return switch (normalized) {
            case "turn_timeout" -> new TurnResponse.TimedOut(execution, normalized);
            case "subscriber_cancelled", "turn_cancelled", "cancelled" ->
                new TurnResponse.TurnCancelled(execution, normalized);
            default -> new TurnResponse.Interrupted(execution, normalized);
        };
    }

    private void handleAskUser(TurnContext ctx, TurnLoopState state,
                               List<String> questions,
                               ActiveTurnRegistry.ActiveTurn active) {
        List<String> ordered = normalizeQuestions(questions);
        String interactionId = UUID.randomUUID().toString();
        Map<String, String> continuation = new LinkedHashMap<>();
        if (ctx.request().metadata() != null) {
            continuation.putAll(ctx.request().metadata());
        }
        continuation.put(PENDING_INTERACTION_ID, interactionId);
        continuation.put("turnloop.pending_tool_names", writeJson(
            ctx.request().toolNames() == null ? List.of() : ctx.request().toolNames()));
        Instant now = Instant.now();
        ExecutionInfo execution = new ExecutionInfo(
            ctx.sessionId(), ctx.turnId(), state.origin, now, now,
            computeUsage(ctx), List.copyOf(state.toolActivities));
        TurnResponse.Questions questionOutcome = new TurnResponse.Questions(
            execution, interactionId, ordered);
        InteractionStore.Interaction pending = new InteractionStore.Interaction(
            interactionId, ctx.sessionId(), questionOutcome, ctx.model(),
            ctx.request().toolNames(), ctx.request().metadata(), continuation,
            List.of(), InteractionState.PENDING, now, now, null, null);

        boolean saved;
        try {
            saved = active.runIfActive(() -> {
                try {
                    interactionStore.save(pending);
                    state.messagesToPersist.add(pendingMarker(pending));
                    persistState(state, ctx.sessionId(), active);
                } catch (Throwable failure) {
                    try {
                        interactionStore.delete(ctx.sessionId(), interactionId);
                    } catch (Throwable cleanupFailure) {
                        log.warn("Unable to clean up pending interaction for session {}",
                            ctx.sessionId());
                    }
                    try {
                        removePendingMarkers(ctx.sessionId());
                    } catch (Throwable cleanupFailure) {
                        log.warn("Unable to clean up pending markers for session {}",
                            ctx.sessionId());
                    }
                    throw new PersistenceFailure(failure);
                }
            });
        } catch (PersistenceFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new PersistenceFailure(failure);
        }
        if (!saved) {
            markRevoked(state, active);
            state.currentPhase = TurnPhase.FINALIZE;
            return;
        }

        state.pendingContext = pending;
        state.pendingInteractionId = interactionId;
        state.suspended = true;
        state.currentPhase = TurnPhase.FINALIZE;
    }

    private void triggerAsyncCompaction(TurnContext ctx, ContextUsage usage,
                                        ActiveTurnRegistry.ActiveTurn active) {
        if (usage == null || !contextManager.needsCompaction(usage, configuredContextLength(ctx.model()))) {
            return;
        }
        try {
            List<TurnMessage> messages = conversationMemory.getMessages(ctx.sessionId()).stream()
                .filter(message -> !isPendingMarker(message))
                .toList();
            if (messages.size() < 2) return;
            int count = Math.min(messages.size(), 50);
            CompactionResult result = toolCompaction(messages, count, usage, ctx.sessionId());
            if (result == null || result.summary().isBlank()) return;
            TurnMessage summary = withMetadata(
                TurnMessage.system(result.summary()), Map.of("type", "compaction_summary"));
            List<TurnMessage> replacement = new ArrayList<>(messages.size() - count + 1);
            replacement.add(summary);
            replacement.addAll(messages.subList(count, messages.size()));
            active.runIfActive(() -> conversationMemory.replaceMessages(
                ctx.sessionId(), List.copyOf(replacement)));
        } catch (Throwable failure) {
            log.warn("Compaction failed for session {}", ctx.sessionId());
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
        try {
            boolean persisted = active.runIfActive(() ->
                conversationMemory.addMessages(sessionId, snapshot));
            if (persisted) {
                state.messagesToPersist.clear();
                state.statePersisted = true;
            }
            return persisted;
        } catch (Throwable failure) {
            throw new PersistenceFailure(failure);
        }
    }

    private void persistFailure(TurnContext ctx, TurnLoopState state,
                                ActiveTurnRegistry.ActiveTurn active) {
        try {
            if (!state.messagesToPersist.isEmpty()) {
                persistState(state, ctx.sessionId(), active);
            }
        } catch (Throwable failure) {
            log.warn("Unable to persist failed turn for session {}", ctx.sessionId());
        }
    }

    private void clearPendingAfterFailure(String sessionId,
                                          ActiveTurnRegistry.ActiveTurn active) {
        try {
            active.runIfActive(() -> {
                interactionStore.pending(sessionId).ifPresent(interaction -> {
                    removePendingMarkers(sessionId);
                    interactionStore.delete(sessionId, interaction.interactionId());
                });
            });
        } catch (Throwable failure) {
            log.warn("Unable to clear pending interaction for session {}", sessionId);
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

    private TurnMessage pendingMarker(InteractionStore.Interaction pending) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", PENDING_TYPE);
        metadata.put("turnloop.pending_turn_id", pending.questions().execution().turnId());
        metadata.put("turnloop.pending_model", pending.model());
        metadata.put("turnloop.pending_interaction_id", pending.interactionId());
        metadata.put("turnloop.pending_questions", writeJson(pending.questions().questions()));
        metadata.put("turnloop.pending_tool_names", writeJson(
            pending.explicitToolNames() == null ? List.of() : pending.explicitToolNames()));
        metadata.put("turnloop.pending_metadata", writeJson(pending.continuationMetadata()));
        return new TurnMessage("system", "[Magenta interaction pending]", null, null, metadata);
    }

    private boolean isPendingMarker(TurnMessage message) {
        return message != null && message.metadata() != null
            && PENDING_TYPE.equals(message.metadata().get("turnloop.persistence_type"));
    }

    private InteractionStore.Interaction continuationContext(TurnContext ctx) {
        Map<String, String> metadata = ctx.request().metadata();
        if (metadata == null || !CONTINUATION_FLAG.equals(metadata.get("turnloop.continuation"))) {
            return null;
        }
        List<String> questions = normalizeQuestions(
            readJsonList(metadata.get("turnloop.pending_questions")));
        String model = emptyToNull(metadata.get("turnloop.pending_model"));
        if (model == null) model = ctx.model();
        Map<String, String> originalMetadata = new LinkedHashMap<>(
            readJsonMap(metadata.get("turnloop.pending_metadata")));
        String interactionId = firstNonBlank(
            metadata.get(PENDING_INTERACTION_ID), originalMetadata.get(PENDING_INTERACTION_ID));
        if (interactionId == null) interactionId = UUID.randomUUID().toString();
        originalMetadata.put(PENDING_INTERACTION_ID, interactionId);
        String pendingTurnId = emptyToNull(metadata.get("turnloop.pending_turn_id"));
        if (pendingTurnId == null) pendingTurnId = ctx.turnId();
        Instant now = Instant.now();
        ExecutionInfo execution = new ExecutionInfo(
            ctx.sessionId(), pendingTurnId, new ExecutionOrigin.Answer(interactionId),
            now, now, computeUsage(ctx), List.of());
        TurnResponse.Questions outcome = new TurnResponse.Questions(
            execution, interactionId, questions);
        return new InteractionStore.Interaction(
            interactionId, ctx.sessionId(), outcome, model,
            readJsonList(metadata.get("turnloop.pending_tool_names")),
            originalMetadata, originalMetadata, List.of(), InteractionState.PENDING,
            now, now, null, null);
    }

    private TurnMessage questionAnswerMessage(InteractionStore.Interaction pending,
                                               TurnContext ctx) {
        StringBuilder text = new StringBuilder();
        text.append("[Questions and answers]\n");
        List<String> answers = readJsonList(ctx.request().metadata().get(PENDING_ANSWERS));
        List<String> questions = pending.questions().questions();
        if (answers.size() == questions.size()) {
            for (int i = 0; i < questions.size(); i++) {
                text.append("Question ").append(i + 1).append(": ")
                    .append(questions.get(i)).append('\n');
                text.append("Answer ").append(i + 1).append(": ")
                    .append(answers.get(i)).append('\n');
            }
        } else {
            for (int i = 0; i < questions.size(); i++) {
                text.append("Question ").append(i + 1).append(": ")
                    .append(questions.get(i)).append('\n');
            }
            text.append("Answer: ").append(ctx.request().message());
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", "question_answer_context");
        metadata.put("turnloop.pending_turn_id", pending.questions().execution().turnId());
        metadata.put("turnloop.answer_turn_id", ctx.turnId());
        metadata.put(PENDING_INTERACTION_ID, pending.interactionId());
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
            : output.getToolCalls().stream()
                .map(call -> call.name() + ":" + call.arguments()).toList();
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
        } catch (RuntimeException failure) {
            return new ContextUsage(0, 0, configuredContextLength(ctx.model()));
        }
    }

    // ── response reduction and failures ──

    private TurnResponse.Outcome reduceToOutcome(TurnContext ctx,
                                                  List<TurnResponse> responses) {
        TurnResponse.Outcome outcome = null;
        int outcomeIndex = -1;
        for (int index = 0; index < responses.size(); index++) {
            TurnResponse response = responses.get(index);
            if (response instanceof TurnResponse.Outcome terminal) {
                if (outcome != null || outcomeIndex >= 0) {
                    throw failureException(ctx, Instant.now(), currentPhase(ctx),
                        TurnFailureCategory.INTERNAL, "Turn emitted multiple outcomes");
                }
                outcome = terminal;
                outcomeIndex = index;
            }
        }
        if (outcome == null) {
            throw failureException(ctx, Instant.now(), currentPhase(ctx),
                TurnFailureCategory.INTERNAL, "Turn completed without an outcome");
        }
        if (outcomeIndex != responses.size() - 1) {
            throw failureException(ctx, Instant.now(), currentPhase(ctx),
                TurnFailureCategory.INTERNAL, "Turn emitted progress after its outcome");
        }
        return outcome;
    }

    private void emitFailure(TurnContext ctx, FluxSink<TurnResponse> sink,
                             ActiveTurnRegistry.ActiveTurn active,
                             Instant startedAt, Throwable failure) {
        if (sink.isCancelled()) {
            return;
        }
        Throwable cause = unwrap(failure);
        Throwable terminal = cause;
        if (!(cause instanceof TurnFailedException) && !isSpecializedFailure(cause)) {
            TurnPhase phase = active == null ? TurnPhase.PREPARE : active.phase();
            terminal = failureException(ctx, startedAt, phase, cause);
        }
        sink.error(terminal);
    }

    private TurnFailedException failureException(TurnContext ctx, Instant startedAt,
                                                  TurnPhase phase, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof TurnFailedException turnFailed) {
            return turnFailed;
        }
        TurnFailureCategory category = failureCategory(cause, phase);
        return failureException(ctx, startedAt, phase, category, safeFailureMessage(category));
    }

    private TurnFailedException failureException(TurnContext ctx, Instant startedAt,
                                                  TurnPhase phase,
                                                  TurnFailureCategory category,
                                                  String message) {
        Instant failedAt = atOrAfter(startedAt);
        String safeMessage = ResponseValidation.nonBlank(message, "message");
        TurnFailure failure = new TurnFailure(
            ctx.sessionId(), ctx.turnId(), executionOrigin(ctx), phase,
            category, startedAt, failedAt, safeMessage);
        return new TurnFailedException(safeMessage, failure);
    }

    private TurnFailureCategory failureCategory(Throwable failure, TurnPhase phase) {
        if (failure instanceof PersistenceFailure) {
            return TurnFailureCategory.PERSISTENCE;
        }
        if (failure instanceof ToolException) {
            return TurnFailureCategory.TOOL;
        }
        if (failure instanceof TurnFailedException.ContextExhaustedException) {
            return TurnFailureCategory.CONTEXT;
        }
        return switch (phase) {
            case INVOKE_MODEL -> TurnFailureCategory.MODEL;
            case EXECUTE_TOOLS -> TurnFailureCategory.TOOL;
            case FINALIZE -> TurnFailureCategory.PERSISTENCE;
            case PREPARE, EVALUATE, REPAIR, DONE -> TurnFailureCategory.EXECUTION;
        };
    }

    private String safeFailureMessage(TurnFailureCategory category) {
        return switch (category) {
            case MODEL -> "Model execution failed";
            case TOOL -> "Tool execution failed";
            case CONTEXT -> "Context processing failed";
            case PERSISTENCE -> "Turn state persistence failed";
            case EXECUTION -> "Turn execution failed";
            case INTERNAL -> "Internal turn execution failure";
        };
    }

    private boolean isSpecializedFailure(Throwable failure) {
        return failure instanceof ConfigurationException
            || failure instanceof ConversationNotFoundException
            || failure instanceof InteractionStateException
            || failure instanceof TurnLoopBusyException;
    }

    private TurnPhase currentPhase(TurnContext ctx) {
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(ctx.turnId());
        return active == null ? TurnPhase.FINALIZE : active.phase();
    }

    private Optional<String> controlReason(ActiveTurnRegistry.ActiveTurn active,
                                           Throwable failure) {
        if (active != null) {
            Optional<String> revoked = active.revocationReason();
            if (revoked.isPresent()) {
                return revoked;
            }
            Optional<String> queued = active.pollInterrupt();
            if (queued.isPresent()) {
                return queued;
            }
        }
        if (failure instanceof InterruptedException
            || Thread.currentThread().isInterrupted()) {
            return Optional.of("interrupted");
        }
        if (failure instanceof CancellationException) {
            return Optional.of("turn_cancelled");
        }
        return Optional.empty();
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

    private List<String> normalizeQuestions(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of("The assistant has a question for you.");
        }
        List<String> normalized = questions.stream()
            .filter(Objects::nonNull)
            .filter(question -> !question.isBlank())
            .toList();
        return normalized.isEmpty()
            ? List.of("The assistant has a question for you.")
            : List.copyOf(normalized);
    }

    private String formatQuestions(List<String> questions) {
        StringBuilder result = new StringBuilder();
        List<String> normalized = normalizeQuestions(questions);
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) result.append('\n');
            result.append("Question ").append(i + 1).append(": ").append(normalized.get(i));
        }
        return result.toString();
    }

    private String writeJson(Object value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
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

    private void emit(FluxSink<TurnResponse> sink, TurnResponse response) {
        if (!sink.isCancelled()) {
            sink.next(response);
        }
    }

    private ExecutionOrigin executionOrigin(TurnContext ctx) {
        try {
            ExecutionOrigin origin = ctx.executionOrigin();
            return origin == null ? new ExecutionOrigin.Direct() : origin;
        } catch (RuntimeException ignored) {
            return new ExecutionOrigin.Direct();
        }
    }

    private void markRevoked(TurnLoopState state, ActiveTurnRegistry.ActiveTurn active) {
        state.wasInterrupted = true;
        state.interruptReason = normalizedReason(
            active.revocationReason().orElse("execution_fenced"), "execution_fenced");
    }

    private String normalizedReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private Instant atOrAfter(Instant baseline) {
        Instant now = Instant.now();
        return now.isBefore(baseline) ? baseline : now;
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
        while ((result instanceof CompletionException || result instanceof ExecutionException)
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
        private final ExecutionOrigin origin;
        private final List<TurnMessage> messagesToPersist = new ArrayList<>();
        private final List<ToolActivityData> toolActivities = new ArrayList<>();
        private final ToolLoopGuard toolLoopGuard;
        private List<ToolCallback> activeTools = List.of();
        private List<Message> conversationMessages = new ArrayList<>();
        private String systemPromptText;
        private Prompt prompt;
        private ChatResponse response;
        private String allVisibleText = "";
        private final StringBuilder visibleText = new StringBuilder();
        private String latestThinking = "";
        private String toolLoopAbortReason;
        private String interruptReason;
        private TurnPhase currentPhase = TurnPhase.PREPARE;
        private int emptyResponseRetries;
        private boolean wasInterrupted;
        private boolean suspended;
        private boolean lastResponsePersisted;
        private boolean statePersisted;
        private InteractionStore.Interaction pendingContext;
        private String pendingInteractionId;

        private TurnLoopState(TurnContext context, ToolLoopGuard toolLoopGuard,
                              ExecutionOrigin origin) {
            this.context = context;
            this.toolLoopGuard = toolLoopGuard.forTurn();
            this.origin = origin;
        }
    }

    private static final class PersistenceFailure extends RuntimeException {
        private PersistenceFailure(Throwable cause) {
            super(cause);
        }
    }

    static final class Builder {
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

        public Builder interactionStore(InteractionStore store) {
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
            return new DefaultTurnEngine(this);
        }

        private static void require(Object value, String name) {
            if (value == null) {
                throw new ConfigurationException(name + " is required", Map.of("missing", name));
            }
        }
    }
}
