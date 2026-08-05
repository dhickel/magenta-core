package io.mindspice.core.engine;

import io.mindspice.core.*;
import io.mindspice.core.internal.ActiveTurnRegistry;
import io.mindspice.core.internal.ModelRouter;
import io.mindspice.core.internal.TurnExecutor;
import io.mindspice.core.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Package-private 6-state turn-loop engine extracted intact from Magenta's
 * {@code ChatService.toolChat()}. This is the framework's core execution unit —
 * external consumers use the {@code TurnLoop} facade exclusively.
 *
 * <h3>State cycle</h3>
 * <pre>
 * PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE
 *               ↑                ↑            ↑             │
 *               └────────────────┴────────────┴─────────────┘
 * </pre>
 *
 * <p>The engine is created via its package-private {@link Builder}.</p>
 */
final class DefaultTurnEngine {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTurnEngine.class);

    // ── toolExecution ──
    private final ToolRegistry toolRegistry;
    private final ToolAccessPolicy toolAccessPolicy;
    private final ToolResultSummarizer toolResultSummarizer;
    private final ToolLoopGuard toolLoopGuard;

    // ── contextAssembly ──
    private final ConversationMemory conversationMemory;
    private final TokenEstimator tokenEstimator;
    private final CompactionStrategy compactionStrategy;
    private final ContextManager contextManager;
    private final DefaultPromptAssembler promptAssembler;

    // ── responseRepair ──
    private final CompletionDetector completionDetector;
    private final RepairStrategy repairStrategy;

    // ── interaction ──
    private final InteractionHandler interactionHandler;
    private final InteractionStore interactionStore;

    // ── infrastructure ──
    private final ModelRouter modelRouter;
    private final TurnExecutor turnExecutor;
    private final ActiveTurnRegistry activeTurnRegistry;
    private final ThinkingExtractor thinkingExtractor;

    // ── config ──
    private final int maxToolRounds;
    private final int maxEmptyResponseRetries;
    private final Duration turnTimeout;

    private DefaultTurnEngine(Builder builder) {
        this.toolRegistry = builder.toolRegistry;
        this.toolAccessPolicy = builder.toolAccessPolicy;
        this.toolResultSummarizer = builder.toolResultSummarizer;
        this.toolLoopGuard = builder.toolLoopGuard;
        this.conversationMemory = builder.conversationMemory;
        this.tokenEstimator = builder.tokenEstimator;
        this.compactionStrategy = builder.compactionStrategy;
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
        this.maxToolRounds = builder.maxToolRounds;
        this.maxEmptyResponseRetries = builder.maxEmptyResponseRetries;
        this.turnTimeout = builder.turnTimeout;
    }

    // ── Public API ──

    /**
     * Executes the full 6-state turn cycle, emitting lifecycle events
     * as a {@link Flux} suitable for SSE streaming.
     *
     * @param ctx mutable turn-scoped context
     * @return a cold Flux of turn lifecycle events
     */
    Flux<TurnEvent> stream(TurnContext ctx) {
        return Flux.create(sink -> {
            ActiveTurnRegistry.ActiveTurn activeTurn = null;
            try {
                activeTurn = activeTurnRegistry.register(ctx.turnId());
                runTurnLoop(ctx, sink, activeTurn);
            } catch (TurnFailedException tfe) {
                sink.error(tfe);
            } catch (Exception e) {
                TurnDiagnostic diag = buildDiagnostic(ctx, TurnPhase.FINALIZE,
                    TurnDiagnostic.OutcomeKind.FAILED,
                    Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage()));
                sink.error(new TurnFailedException(
                    e.getMessage() == null ? "Turn failed" : e.getMessage(), diag));
            } finally {
                if (activeTurn != null) {
                    activeTurnRegistry.remove(ctx.turnId());
                }
            }
        });
    }

    /**
     * Synchronous turn execution. Delegates to {@link #stream(TurnContext)}
     * and collects the final {@link TurnResult}.
     *
     * @param ctx mutable turn-scoped context
     * @return the completed turn result
     */
    TurnResult execute(TurnContext ctx) {
        List<TurnEvent> events = new ArrayList<>();
        TurnResult[] resultHolder = new TurnResult[1];
        Exception[] errorHolder = new Exception[1];

        stream(ctx)
            .doOnNext(events::add)
            .doOnComplete(() -> resultHolder[0] = reduceToResult(ctx, events))
            .doOnError(e -> errorHolder[0] = e instanceof Exception ex ? ex : new RuntimeException(e))
            .blockLast();

        if (errorHolder[0] != null) {
            if (errorHolder[0] instanceof TurnFailedException tfe) {
                throw tfe;
            }
            throw new TurnFailedException("Turn execution failed",
                buildDiagnostic(ctx, TurnPhase.FINALIZE, TurnDiagnostic.OutcomeKind.FAILED,
                    Map.of("error", errorHolder[0].getMessage() == null ? "unknown" : errorHolder[0].getMessage())));
        }
        if (resultHolder[0] == null) {
            throw new TurnFailedException("Turn execution produced no result",
                buildDiagnostic(ctx, TurnPhase.FINALIZE, TurnDiagnostic.OutcomeKind.FAILED, Map.of()));
        }
        return resultHolder[0];
    }

    // ── Core turn loop ──

    private void runTurnLoop(TurnContext ctx, FluxSink<TurnEvent> sink,
                             ActiveTurnRegistry.ActiveTurn activeTurn) {
        Instant startedAt = Instant.now();

        // ── PREPARE ──
        activeTurn.phase(TurnPhase.PREPARE);

        // Validate model capabilities
        if (ctx.streaming() && !modelRouter.supportsStreaming(ctx.model())) {
            throw new TurnFailedException(
                "Model '" + ctx.model() + "' does not support streaming",
                buildDiagnostic(ctx, TurnPhase.PREPARE, TurnDiagnostic.OutcomeKind.FAILED,
                    Map.of("reason", "streaming_unsupported", "model", ctx.model())));
        }

        // Resolve tools
        List<ToolCallback> resolvedTools = toolAccessPolicy.filterTools(
            toolRegistry.resolveForTurn(ctx), ctx);
        ctx.activeTools(resolvedTools);

        // Assemble prompt and windowed messages
        String systemText = promptAssembler.assemble(ctx);
        List<TurnMessage> windowedMessages = contextManager.window(ctx);
        List<Message> conversationMessages = turnMessagesToSpring(windowedMessages);

        // Emit started
        ContextUsage startUsage = computeUsage(ctx);
        sink.next(new TurnEvent.Started(ctx.turnId(), Instant.now(),
            ctx.turnId(), startUsage));

        // ── State initialization ──
        TurnLoopState state = new TurnLoopState(ctx, resolvedTools);
        state.systemPromptText = systemText;
        state.conversationMessages = new ArrayList<>(conversationMessages);
        state.currentPhase = TurnPhase.INVOKE_MODEL;
        activeTurn.phase(TurnPhase.INVOKE_MODEL);

        ToolCallingChatOptions toolOptions = modelRouter.toolCallingOptions(
            ctx.model(), resolvedTools);

        // Build initial prompt: system message + history (from conversationMemory)
        List<Message> initialMessages = buildPromptMessages(systemText, conversationMessages);
        state.prompt = new Prompt(initialMessages, toolOptions);

        try {
            while (state.currentPhase != TurnPhase.DONE) {
                // Interrupt poll
                activeTurn.pollInterrupt().ifPresent(reason -> {
                    logger.info("Turn interrupted conv={} reason={}", ctx.sessionId(), reason);
                    sink.next(new TurnEvent.Interrupt(ctx.turnId(), Instant.now(),
                        reason, computeUsage(ctx)));
                    state.currentPhase = TurnPhase.FINALIZE;
                    state.wasInterrupted = true;
                });
                if (state.currentPhase == TurnPhase.DONE || state.currentPhase == TurnPhase.FINALIZE) {
                    if (state.currentPhase == TurnPhase.FINALIZE) {
                        handleFinalize(ctx, state, sink, startedAt);
                    }
                    break;
                }

                activeTurn.phase(state.currentPhase);

                switch (state.currentPhase) {
                    case INVOKE_MODEL  -> handleInvokeModel(ctx, state, sink);
                    case EVALUATE      -> handleEvaluate(ctx, state);
                    case EXECUTE_TOOLS -> handleExecuteTools(ctx, state, sink);
                    case REPAIR        -> handleRepair(ctx, state);
                    default -> state.currentPhase = TurnPhase.FINALIZE;
                }
            }
        } catch (ToolException.LoopExceeded le) {
            logger.warn("Tool loop guard tripped conv={}: {}", ctx.sessionId(), le.getMessage());
            sink.next(new TurnEvent.SystemNotice(ctx.turnId(), Instant.now(),
                NoticeKind.TOOL_LOOP_WARNING, le.getMessage(), computeUsage(ctx)));
            state.currentPhase = TurnPhase.FINALIZE;
            handleFinalize(ctx, state, sink, startedAt);
        }
    }

    // ── Phase handlers ──

    private void handleInvokeModel(TurnContext ctx, TurnLoopState state,
                                   FluxSink<TurnEvent> sink) {
        ChatModel chatModel = modelRouter.springAiChatModel(ctx.model());

        if (ctx.streaming()) {
            // Streaming path
            var streamRef = new Object() {
                ChatResponse finalResponse;
                final List<TurnEvent.Chunk> emittedChunks = new ArrayList<>();
            };

            try {
                chatModel.stream(state.prompt)
                    .doOnNext(chunk -> {
                        streamRef.finalResponse = chunk;
                        if (chunk != null && chunk.getResult() != null) {
                            AssistantMessage output = chunk.getResult().getOutput();
                            if (output != null) {
                                String rawText = output.getText();
                                if (rawText != null && !rawText.isEmpty()) {
                                    ThinkingExtractor.AssistantMessageContent content =
                                        buildContentFromGeneration(chunk.getResult());
                                    ThinkingExtractor.ThinkingResult thinkingResult =
                                        thinkingExtractor.extract(content);

                                    // Emit visible text chunks
                                    if (!thinkingResult.visibleText().isEmpty()) {
                                        TurnEvent.Chunk chunkEvent = new TurnEvent.Chunk(
                                            ctx.turnId(), Instant.now(),
                                            thinkingResult.visibleText(), false,
                                            computeUsage(ctx));
                                        sink.next(chunkEvent);
                                        streamRef.emittedChunks.add(chunkEvent);
                                    }
                                    // Emit thinking chunks (flagged)
                                    if (!thinkingResult.thinkingText().isEmpty()) {
                                        sink.next(new TurnEvent.Chunk(
                                            ctx.turnId(), Instant.now(),
                                            thinkingResult.thinkingText(), true,
                                            computeUsage(ctx)));
                                    }
                                }
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        state.response = streamRef.finalResponse;
                        state.allVisibleText = streamRef.emittedChunks.stream()
                            .filter(c -> !c.thinking())
                            .map(TurnEvent.Chunk::text)
                            .collect(Collectors.joining());
                        state.currentPhase = TurnPhase.EVALUATE;
                    })
                    .doOnError(e -> {
                        logger.error("Model stream error conv={}: {}", ctx.sessionId(), e.getMessage());
                        sink.error(new TurnFailedException("Model stream failed: " + e.getMessage(),
                            buildDiagnostic(ctx, TurnPhase.INVOKE_MODEL,
                                TurnDiagnostic.OutcomeKind.FAILED,
                                Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage()))));
                    })
                    .blockLast();
            } catch (Exception e) {
                throw new TurnFailedException("Model invocation failed: " + e.getMessage(),
                    buildDiagnostic(ctx, TurnPhase.INVOKE_MODEL, TurnDiagnostic.OutcomeKind.FAILED,
                        Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage())));
            }
        } else {
            // Synchronous path
            try {
                state.response = chatModel.call(state.prompt);
            } catch (Exception e) {
                throw new TurnFailedException("Model invocation failed: " + e.getMessage(),
                    buildDiagnostic(ctx, TurnPhase.INVOKE_MODEL, TurnDiagnostic.OutcomeKind.FAILED,
                        Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage())));
            }

            // Extract thinking
            if (state.response != null && state.response.getResult() != null) {
                ThinkingExtractor.AssistantMessageContent content =
                    buildContentFromGeneration(state.response.getResult());
                ThinkingExtractor.ThinkingResult thinkingResult =
                    thinkingExtractor.extract(content);

                state.latestThinking = thinkingResult.thinkingText();
                state.latestVisibleText = thinkingResult.visibleText();

                if (!thinkingResult.visibleText().isEmpty()) {
                    sink.next(new TurnEvent.Chunk(ctx.turnId(), Instant.now(),
                        thinkingResult.visibleText(), false, computeUsage(ctx)));
                }
                if (!thinkingResult.thinkingText().isEmpty()) {
                    sink.next(new TurnEvent.Chunk(ctx.turnId(), Instant.now(),
                        thinkingResult.thinkingText(), true, computeUsage(ctx)));
                }
                state.allVisibleText = thinkingResult.visibleText();
            }
            state.currentPhase = TurnPhase.EVALUATE;
        }
    }

    private void handleEvaluate(TurnContext ctx, TurnLoopState state) {
        // 1. Completion detection
        if (completionDetector != null && completionDetector.isComplete(ctx)) {
            state.currentPhase = TurnPhase.FINALIZE;
            return;
        }

        // 2. Tool calls → EXECUTE_TOOLS
        if (state.response != null && hasToolCalls(state.response)) {
            state.currentPhase = TurnPhase.EXECUTE_TOOLS;
            return;
        }

        // 3. Tool loop abort pending → REPAIR
        if (state.toolLoopAbortReason != null) {
            state.currentPhase = TurnPhase.REPAIR;
            return;
        }

        // 4. Empty/incomplete response → REPAIR (if retries remain)
        if (isEmptyResponse(state.response)
            && state.emptyResponseRetries < maxEmptyResponseRetries) {
            state.currentPhase = TurnPhase.REPAIR;
            return;
        }

        // 5. Otherwise → FINALIZE
        state.currentPhase = TurnPhase.FINALIZE;
    }

    private void handleExecuteTools(TurnContext ctx, TurnLoopState state,
                                    FluxSink<TurnEvent> sink) {
        if (state.response == null) {
            state.currentPhase = TurnPhase.FINALIZE;
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(state.response);
        if (toolCalls.isEmpty()) {
            state.currentPhase = TurnPhase.EVALUATE;
            return;
        }

        // ── Check ToolLoopGuard before execution ──
        List<ToolLoopGuard.ToolCallIdentity> callIdentities = toolCalls.stream()
            .map(tc -> new ToolLoopGuard.ToolCallIdentity(tc.name(), tc.arguments()))
            .toList();
        try {
            toolLoopGuard.recordToolCalls(callIdentities);
        } catch (ToolException.LoopExceeded exceeded) {
            logger.warn("Tool loop exceeded conv={}: {}", ctx.sessionId(), exceeded.getMessage());
            state.toolLoopAbortReason = exceeded.getMessage();
            state.currentPhase = TurnPhase.EVALUATE;
            return;
        }

        // Increment round count
        ctx.incrementToolRoundCount();
        if (ctx.toolRoundCount() > maxToolRounds) {
            state.currentPhase = TurnPhase.FINALIZE;
            return;
        }

        // ── Execute each tool ──
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        Map<String, ToolCallback> toolIndex = indexTools(ctx.activeTools());

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            ToolCallback callback = toolIndex.get(toolCall.name());
            ToolActivityStatus status;
            String summary;
            String resultText;

            if (callback == null) {
                status = ToolActivityStatus.FAILED;
                summary = "Tool not found: " + toolCall.name();
                resultText = summary;
            } else {
                // ── ask_user detection ──
                if ("ask_user".equals(toolCall.name())) {
                    handleAskUser(ctx, state, toolCall, sink);
                    return; // suspension — sink completed in handleAskUser
                }

                try {
                    String rawResult = callback.call(toolCall.arguments());
                    // Summarize
                    ToolResultSummarizer.ToolExecution execution =
                        new ToolResultSummarizer.ToolExecution(
                            toolCall.name(), toolCall.id(), toolCall.arguments(), rawResult);
                    ToolResultSummarizer.SummarizeResult summarizeResult =
                        toolResultSummarizer.summarize(execution);
                    resultText = summarizeResult.summary();
                    summary = summarizeResult.summary();
                    status = ToolActivityStatus.COMPLETED;
                } catch (Exception e) {
                    logger.warn("Tool execution failed conv={} tool={}: {}",
                        ctx.sessionId(), toolCall.name(), e.getMessage());
                    resultText = "Tool execution failed: " + e.getMessage();
                    summary = "Failed: " + e.getMessage();
                    status = ToolActivityStatus.FAILED;
                }
            }

            ToolResponseMessage.ToolResponse toolResponse =
                new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), resultText);
            toolResponses.add(toolResponse);

            ToolActivityData activity = new ToolActivityData(
                toolCall.name(), toolCall.id(), status, summary);
            state.toolActivities.add(activity);

            // Emit ToolActivity event
            sink.next(new TurnEvent.ToolActivity(ctx.turnId(), Instant.now(),
                toolCall.name(), toolCall.id(), status, summary, computeUsage(ctx)));

            // ── Check ToolLoopGuard after each tool ──
            try {
                toolLoopGuard.recordToolResponses(new ToolLoopGuard.ToolExecutionResult(
                    status == ToolActivityStatus.COMPLETED,
                    toolCall.name(),
                    status == ToolActivityStatus.FAILED ? summary : null));
            } catch (ToolException.LoopExceeded exceeded) {
                logger.warn("Tool loop (error rate) exceeded conv={}: {}", ctx.sessionId(), exceeded.getMessage());
                state.toolLoopAbortReason = exceeded.getMessage();
                // Mark for repair; still process remaining tools
            }
        }

        // ── Append tool responses to conversation ──
        AssistantMessage assistantOutput = state.response.getResult().getOutput();
        state.conversationMessages.add(assistantOutput); // assistant message with tool calls
        state.conversationMessages.add(
            ToolResponseMessage.builder().responses(toolResponses).build());

        // Persist messages
        TurnMessage assistantMsg = toTurnMessage(assistantOutput, state.latestThinking);
        state.messagesToPersist.add(assistantMsg);
        List<TurnMessage> toolMsgs = toolResponses.stream()
            .map(tr -> TurnMessage.tool(tr.name(), tr.id(), tr.responseData()))
            .toList();
        state.messagesToPersist.addAll(toolMsgs);

        // ── Build next prompt ──
        List<Message> nextMessages = buildPromptMessages(state.systemPromptText,
            state.conversationMessages);
        ToolCallingChatOptions options = modelRouter.toolCallingOptions(
            ctx.model(), ctx.activeTools());
        state.prompt = new Prompt(nextMessages, options);

        // Update context usage
        ctx.currentContextUsage(computeUsage(ctx));

        // Check if tool loop abort flagged
        if (state.toolLoopAbortReason != null) {
            state.currentPhase = TurnPhase.EVALUATE;
            return;
        }

        state.currentPhase = TurnPhase.INVOKE_MODEL;
    }

    private void handleRepair(TurnContext ctx, TurnLoopState state) {
        if (state.toolLoopAbortReason != null) {
            // Tool loop guard tripped — inject repair messages
            List<TurnMessage> repairMessages = repairStrategy != null
                ? repairStrategy.repair(ctx, RepairStrategy.RepairReason.TOOL_LOOP_EXCEEDED)
                : List.of(TurnMessage.system(
                    "Tool loop guard triggered. Continue without further tool calls."));
            List<Message> springRepairMsgs = repairMessages.stream()
                .map(DefaultTurnEngine::turnMessageToSpring)
                .toList();
            state.conversationMessages.addAll(springRepairMsgs);
            state.messagesToPersist.addAll(repairMessages);

            // Rebuild prompt without tools
            List<Message> nextMessages = buildPromptMessages(state.systemPromptText,
                state.conversationMessages);
            state.prompt = new Prompt(nextMessages,
                modelRouter.basicChatOptions(ctx.model()));
            state.toolLoopAbortReason = null;
            state.currentPhase = TurnPhase.INVOKE_MODEL;
            return;
        }

        if (isEmptyResponse(state.response)
            && state.emptyResponseRetries < maxEmptyResponseRetries) {
            state.emptyResponseRetries++;
            List<TurnMessage> repairMessages = repairStrategy != null
                ? repairStrategy.repair(ctx, RepairStrategy.RepairReason.EMPTY_RESPONSE)
                : List.of();
            if (repairMessages.isEmpty()) {
                // Fallback: inject generic control message
                repairMessages = List.of(TurnMessage.system(
                    "Your previous response had no user-visible message and no tool calls. "
                        + "Continue the turn now with a concise user-visible answer or an appropriate tool call. "
                        + "Do not return an empty assistant message."));
            }
            List<Message> springRepairMsgs = repairMessages.stream()
                .map(DefaultTurnEngine::turnMessageToSpring)
                .toList();
            state.conversationMessages.addAll(springRepairMsgs);

            List<Message> nextMessages = buildPromptMessages(state.systemPromptText,
                state.conversationMessages);
            state.prompt = new Prompt(nextMessages,
                modelRouter.toolCallingOptions(ctx.model(), ctx.activeTools()));
            state.currentPhase = TurnPhase.INVOKE_MODEL;
            return;
        }

        state.currentPhase = TurnPhase.FINALIZE;
    }

    private void handleFinalize(TurnContext ctx, TurnLoopState state,
                                FluxSink<TurnEvent> sink, Instant startedAt) {
        // Determine final assistant message text
        String finalText;
        if (state.allVisibleText != null && !state.allVisibleText.isEmpty()) {
            finalText = state.allVisibleText;
        } else if (state.response != null && state.response.getResult() != null
            && state.response.getResult().getOutput() != null) {
            finalText = state.response.getResult().getOutput().getText();
        } else {
            finalText = "";
        }

        // Persist assistant message
        if (state.response != null && state.response.getResult() != null) {
            TurnMessage assistantMsg = toTurnMessage(state.response.getResult().getOutput(),
                state.latestThinking);
            state.messagesToPersist.add(assistantMsg);
        }

        // Persist all messages to conversation memory
        if (!state.messagesToPersist.isEmpty()) {
            conversationMemory.addMessages(ctx.sessionId(),
                List.copyOf(state.messagesToPersist));
        }

        // Compute final context usage
        ContextUsage finalUsage = computeUsage(ctx);

        // Build diagnostic
        TurnDiagnostic.OutcomeKind outcome = state.wasInterrupted
            ? TurnDiagnostic.OutcomeKind.INTERRUPTED
            : TurnDiagnostic.OutcomeKind.COMPLETED;

        TurnDiagnostic diagnostic = new TurnDiagnostic(
            ctx.turnId(), ctx.sessionId(), startedAt, Instant.now(),
            TurnPhase.FINALIZE, outcome,
            Map.of("model", ctx.model(),
                "toolRounds", ctx.toolRoundCount(),
                "toolActivities", state.toolActivities.size())
        );

        TurnEvent terminalEvent;
        if (outcome == TurnDiagnostic.OutcomeKind.COMPLETED) {
            terminalEvent = new TurnEvent.Completed(ctx.turnId(), Instant.now(),
                diagnostic, finalUsage);
        } else {
            terminalEvent = new TurnEvent.Failed(ctx.turnId(), Instant.now(),
                diagnostic, finalUsage);
        }
        sink.next(terminalEvent);

        // ── Async compaction via TurnExecutor ──
        triggerAsyncCompaction(ctx, finalUsage);

        state.currentPhase = TurnPhase.DONE;
        sink.complete();
    }

    // ── ask_user suspension ──

    private void handleAskUser(TurnContext ctx, TurnLoopState state,
                               AssistantMessage.ToolCall toolCall,
                               FluxSink<TurnEvent> sink) {
        String questionText = extractQuestionText(toolCall);
        QuestionPendingContext pendingCtx = new QuestionPendingContext(
            ctx.sessionId(), ctx.turnId(), List.of(questionText));

        // Notify interaction handler
        if (interactionHandler != null) {
            interactionHandler.onQuestionsAsked(pendingCtx);
        }

        // Save to interaction store for restart resilience
        if (interactionStore != null) {
            interactionStore.save(ctx.sessionId(), pendingCtx);
        }

        // Emit QuestionPending event
        sink.next(new TurnEvent.QuestionPending(ctx.turnId(), Instant.now(),
            questionText, computeUsage(ctx)));

        // Complete the sink — the turn is suspended.
        // Resume path is wired in Phase 10 via TurnLoop.answer().
        state.currentPhase = TurnPhase.DONE;
        sink.complete();
    }

    // ── Async compaction ──

    private void triggerAsyncCompaction(TurnContext ctx, ContextUsage usage) {
        if (compactionStrategy == null || conversationMemory == null || turnExecutor == null) {
            return;
        }
        if (usage == null || !contextManager.needsCompaction(usage, usage.maxTokens())) {
            return;
        }
        try {
            turnExecutor.submit(ctx.sessionId(), () -> {
                try {
                    List<TurnMessage> messages = conversationMemory.getMessages(ctx.sessionId());
                    if (messages.size() < 10) {
                        return null;
                    }
                    int toCompact = Math.min(messages.size(), 50);
                    CompactionContext compCtx = new CompactionContext(
                        ctx.sessionId(),
                        List.copyOf(messages.subList(0, toCompact)),
                        usage);
                    CompactionResult result = compactionStrategy.compact(compCtx);
                    TurnMessage summaryMsg = TurnMessage.system(result.summary());
                    // Attach compaction metadata
                    Map<String, String> meta = new HashMap<>();
                    meta.put("type", "compaction_summary");
                    TurnMessage taggedMsg = new TurnMessage(summaryMsg.role(), summaryMsg.text(),
                        summaryMsg.toolName(), summaryMsg.toolCallId(), meta);
                    conversationMemory.addMessages(ctx.sessionId(), List.of(taggedMsg));
                    logger.info("Compaction complete conv={} messagesCompacted={} summaryTokens={}",
                        ctx.sessionId(), result.messagesCompacted(), result.summaryTokenCount());
                } catch (Exception e) {
                    logger.warn("Async compaction failed conv={}: {}", ctx.sessionId(), e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("Failed to submit compaction task conv={}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    // ── Helpers ──

    private ContextUsage computeUsage(TurnContext ctx) {
        try {
            return contextManager.computeUsage(ctx.sessionId(), ctx.model());
        } catch (Exception e) {
            return new ContextUsage(0, 0, 0);
        }
    }

    private TurnDiagnostic buildDiagnostic(TurnContext ctx, TurnPhase phase,
                                           TurnDiagnostic.OutcomeKind outcome,
                                           Map<String, Object> details) {
        return new TurnDiagnostic(ctx.turnId(), ctx.sessionId(),
            Instant.now(), Instant.now(), phase, outcome, details);
    }

    private List<Message> buildPromptMessages(String systemText,
                                              List<Message> conversationMessages) {
        List<Message> messages = new ArrayList<>();
        if (systemText != null && !systemText.isEmpty()) {
            messages.add(new SystemMessage(systemText));
        }
        messages.addAll(conversationMessages);
        return messages;
    }

    /**
     * Converts framework TurnMessage to Spring AI Message.
     */
    static Message turnMessageToSpring(TurnMessage tm) {
        return switch (tm.role()) {
            case "system" -> new SystemMessage(tm.text());
            case "user" -> new UserMessage(tm.text());
            case "assistant" -> new AssistantMessage(tm.text());
            case "tool" -> ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                    tm.toolCallId() != null ? tm.toolCallId() : "",
                    tm.toolName() != null ? tm.toolName() : "",
                    tm.text())))
                .build();
            default -> new UserMessage(tm.text());
        };
    }

    private List<Message> turnMessagesToSpring(List<TurnMessage> tms) {
        return tms.stream().map(DefaultTurnEngine::turnMessageToSpring).toList();
    }

    private TurnMessage toTurnMessage(AssistantMessage output, String thinking) {
        String text = output != null ? output.getText() : "";
        Map<String, String> metadata = new HashMap<>();
        if (thinking != null && !thinking.isEmpty()) {
            metadata.put("thinking", thinking);
        }
        return new TurnMessage("assistant", text != null ? text : "", null, null, metadata);
    }

    /**
     * Builds an {@link ThinkingExtractor.AssistantMessageContent} from a
     * Spring AI {@link org.springframework.ai.chat.model.Generation}.
     * Uses reflection for DeepSeek detection since it may not be on the classpath.
     */
    private ThinkingExtractor.AssistantMessageContent buildContentFromGeneration(
        org.springframework.ai.chat.model.Generation generation) {
        if (generation == null) {
            return new ThinkingExtractor.AssistantMessageContent("", null, null, Map.of());
        }
        AssistantMessage output = generation.getOutput();
        String rawText = output != null ? output.getText() : "";
        if (rawText == null) rawText = "";

        String thinkingMetadata = null;
        String reasoningContent = null;
        Map<String, Object> metadata = new HashMap<>();

        // Read ChatGenerationMetadata via interface methods
        if (generation.getMetadata() != null) {
            var genMeta = generation.getMetadata();
            for (var entry : genMeta.entrySet()) {
                metadata.put(entry.getKey(), entry.getValue());
            }
            Object thinkObj = genMeta.get("thinking");
            if (thinkObj instanceof String ts && !ts.isEmpty()) {
                thinkingMetadata = ts;
            }
        }

        if (output != null) {
            // DeepSeek reasoning detection via reflection
            reasoningContent = extractReasoningViaReflection(output);

            // Check regular metadata for reasoningContent
            if (output.getMetadata() != null) {
                Object rc = output.getMetadata().get("reasoningContent");
                if (rc instanceof String rcs && !rcs.isEmpty() && reasoningContent == null) {
                    reasoningContent = rcs;
                }
            }
        }

        return new ThinkingExtractor.AssistantMessageContent(
            rawText, thinkingMetadata, reasoningContent, metadata);
    }

    /**
     * Attempts to extract reasoning content from a DeepSeekAssistantMessage
     * via reflection, since the class may not be on the classpath.
     */
    private static String extractReasoningViaReflection(AssistantMessage output) {
        try {
            Class<?> cls = output.getClass();
            if ("org.springframework.ai.deepseek.DeepSeekAssistantMessage".equals(cls.getName())) {
                var method = cls.getMethod("getReasoningContent");
                Object result = method.invoke(output);
                if (result instanceof String s && !s.isEmpty()) {
                    return s;
                }
            }
        } catch (Exception ignored) {
            // DeepSeek not on classpath or reflection failed
        }
        return null;
    }

    private boolean hasToolCalls(ChatResponse response) {
        if (response == null) return false;
        return !extractToolCalls(response).isEmpty();
    }

    private List<AssistantMessage.ToolCall> extractToolCalls(ChatResponse response) {
        if (response == null || response.getResult() == null
            || response.getResult().getOutput() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        return calls != null ? calls : List.of();
    }

    private boolean isEmptyResponse(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return true;
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output == null) {
            return true;
        }
        String text = output.getText();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        return (text == null || text.isBlank()) && !hasToolCalls;
    }

    private Map<String, ToolCallback> indexTools(List<ToolCallback> tools) {
        Map<String, ToolCallback> index = new LinkedHashMap<>();
        for (ToolCallback tc : tools) {
            index.put(tc.getToolDefinition().name(), tc);
        }
        return index;
    }

    private String extractQuestionText(AssistantMessage.ToolCall toolCall) {
        String args = toolCall.arguments();
        if (args == null || args.isBlank()) {
            return "The assistant has a question for you.";
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(args);
            if (node.has("question")) {
                return node.get("question").asText();
            }
            if (node.has("message")) {
                return node.get("message").asText();
            }
        } catch (Exception e) {
            // Fall through
        }
        return args;
    }

    private TurnResult reduceToResult(TurnContext ctx, List<TurnEvent> events) {
        String finalMessage = null;
        ContextUsage finalUsage = null;
        TurnDiagnostic diagnostic = null;
        List<ToolActivityData> toolActivities = new ArrayList<>();

        for (TurnEvent event : events) {
            if (event instanceof TurnEvent.Completed c) {
                diagnostic = c.diagnostic();
            } else if (event instanceof TurnEvent.Failed f) {
                diagnostic = f.diagnostic();
            } else if (event instanceof TurnEvent.ToolActivity ta) {
                toolActivities.add(new ToolActivityData(
                    ta.toolName(), ta.toolCallId(), ta.status(), ta.summary()));
            }
            if (event.contextUsage() != null) {
                finalUsage = event.contextUsage();
            }
        }

        // Collect visible text from chunks
        String visibleText = events.stream()
            .filter(e -> e instanceof TurnEvent.Chunk c && !c.thinking())
            .map(e -> ((TurnEvent.Chunk) e).text())
            .collect(Collectors.joining());
        if (!visibleText.isEmpty()) {
            finalMessage = visibleText;
        }

        if (diagnostic == null) {
            diagnostic = new TurnDiagnostic(ctx.turnId(), ctx.sessionId(),
                Instant.now(), Instant.now(), TurnPhase.FINALIZE,
                TurnDiagnostic.OutcomeKind.COMPLETED, Map.of());
        }
        if (finalUsage == null) {
            finalUsage = computeUsage(ctx);
        }

        return new TurnResult(ctx.sessionId(), ctx.turnId(), finalMessage,
            finalUsage, diagnostic, List.copyOf(toolActivities));
    }

    // ── Turn-loop state ──

    private static final class TurnLoopState {
        final TurnContext ctx;
        final List<ToolCallback> activeTools;
        final List<TurnMessage> messagesToPersist = new ArrayList<>();
        final List<ToolActivityData> toolActivities = new ArrayList<>();

        TurnPhase currentPhase = TurnPhase.PREPARE;

        Prompt prompt;
        ChatResponse response;
        List<Message> conversationMessages = new ArrayList<>();
        String systemPromptText;

        String latestThinking;
        String latestVisibleText;
        String allVisibleText;

        String toolLoopAbortReason;
        boolean wasInterrupted;
        int emptyResponseRetries;

        TurnLoopState(TurnContext ctx, List<ToolCallback> activeTools) {
            this.ctx = ctx;
            this.activeTools = activeTools;
        }
    }

    // ── Builder ──

    static final class Builder {
        // toolExecution
        ToolRegistry toolRegistry;
        ToolAccessPolicy toolAccessPolicy = ToolAccessPolicy.allowAll();
        ToolResultSummarizer toolResultSummarizer;
        ToolLoopGuard toolLoopGuard = new ToolLoopGuard();

        // contextAssembly
        ConversationMemory conversationMemory;
        TokenEstimator tokenEstimator;
        CompactionStrategy compactionStrategy;
        ContextManager contextManager;
        DefaultPromptAssembler promptAssembler;

        // responseRepair
        CompletionDetector completionDetector = ctx -> false;
        RepairStrategy repairStrategy = (ctx, reason) -> List.of();

        // interaction
        InteractionHandler interactionHandler;
        InteractionStore interactionStore;

        // infrastructure
        ModelRouter modelRouter;
        TurnExecutor turnExecutor;
        ActiveTurnRegistry activeTurnRegistry;
        ThinkingExtractor thinkingExtractor;

        // config
        int maxToolRounds = 10;
        int maxEmptyResponseRetries = 2;
        Duration turnTimeout = Duration.ofMinutes(10);
        ExecutorStrategy executorStrategy = ExecutorStrategy.VIRTUAL;
        int turnThreads = 4;

        Builder toolExecution(ToolRegistry toolRegistry, ToolAccessPolicy toolAccessPolicy,
                              ToolResultSummarizer toolResultSummarizer, ToolLoopGuard toolLoopGuard) {
            this.toolRegistry = toolRegistry;
            this.toolAccessPolicy = toolAccessPolicy != null ? toolAccessPolicy : ToolAccessPolicy.allowAll();
            this.toolResultSummarizer = toolResultSummarizer;
            this.toolLoopGuard = toolLoopGuard != null ? toolLoopGuard : new ToolLoopGuard();
            return this;
        }

        Builder contextAssembly(ConversationMemory conversationMemory, TokenEstimator tokenEstimator,
                                CompactionStrategy compactionStrategy) {
            this.conversationMemory = conversationMemory;
            this.tokenEstimator = tokenEstimator;
            this.compactionStrategy = compactionStrategy;
            return this;
        }

        Builder contextManager(ContextManager contextManager) {
            this.contextManager = contextManager;
            return this;
        }

        Builder promptAssembler(DefaultPromptAssembler promptAssembler) {
            this.promptAssembler = promptAssembler;
            return this;
        }

        Builder responseRepair(CompletionDetector completionDetector, RepairStrategy repairStrategy) {
            this.completionDetector = completionDetector != null ? completionDetector : ctx -> false;
            this.repairStrategy = repairStrategy != null ? repairStrategy : (ctx, reason) -> List.of();
            return this;
        }

        Builder interaction(InteractionHandler interactionHandler, InteractionStore interactionStore) {
            this.interactionHandler = interactionHandler;
            this.interactionStore = interactionStore;
            return this;
        }

        Builder infrastructure(ModelRouter modelRouter, TurnExecutor turnExecutor,
                               ActiveTurnRegistry activeTurnRegistry,
                               ThinkingExtractor thinkingExtractor) {
            this.modelRouter = modelRouter;
            this.turnExecutor = turnExecutor;
            this.activeTurnRegistry = activeTurnRegistry;
            this.thinkingExtractor = thinkingExtractor;
            return this;
        }

        Builder config(int maxToolRounds, int maxEmptyResponseRetries, Duration turnTimeout) {
            this.maxToolRounds = maxToolRounds;
            this.maxEmptyResponseRetries = maxEmptyResponseRetries;
            this.turnTimeout = turnTimeout;
            return this;
        }

        Builder executor(ExecutorStrategy strategy, int threads) {
            this.executorStrategy = strategy != null ? strategy : ExecutorStrategy.VIRTUAL;
            this.turnThreads = Math.max(threads, 1);
            return this;
        }

        DefaultTurnEngine build() {
            if (modelRouter == null) {
                throw new ConfigurationException("ModelRouter is required",
                    Map.of("missing", "modelRouter"));
            }
            if (toolRegistry == null) {
                throw new ConfigurationException("ToolRegistry is required",
                    Map.of("missing", "toolRegistry"));
            }
            if (conversationMemory == null) {
                throw new ConfigurationException("ConversationMemory is required",
                    Map.of("missing", "conversationMemory"));
            }
            if (tokenEstimator == null) {
                throw new ConfigurationException("TokenEstimator is required",
                    Map.of("missing", "tokenEstimator"));
            }
            if (compactionStrategy == null) {
                throw new ConfigurationException("CompactionStrategy is required",
                    Map.of("missing", "compactionStrategy"));
            }
            if (activeTurnRegistry == null) {
                throw new ConfigurationException("ActiveTurnRegistry is required",
                    Map.of("missing", "activeTurnRegistry"));
            }
            if (thinkingExtractor == null) {
                throw new ConfigurationException("ThinkingExtractor is required",
                    Map.of("missing", "thinkingExtractor"));
            }
            if (promptAssembler == null) {
                throw new ConfigurationException("DefaultPromptAssembler is required",
                    Map.of("missing", "promptAssembler"));
            }
            if (contextManager == null) {
                throw new ConfigurationException("ContextManager is required",
                    Map.of("missing", "contextManager"));
            }
            if (turnExecutor == null) {
                throw new ConfigurationException("TurnExecutor is required",
                    Map.of("missing", "turnExecutor"));
            }
            return new DefaultTurnEngine(this);
        }
    }
}
