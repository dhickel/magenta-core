package io.mindspice.magenta2.ai.execution;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ActiveTurnRegistry {
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final Map<String, String> activePlanExecutionsByConversationId = new ConcurrentHashMap<>();

    public ActiveTurn register(String conversationId) {
        String turnId = UUID.randomUUID().toString();
        ActiveTurn turn = new ActiveTurn(turnId, token(), conversationId);
        activeTurns.put(turnId, turn);
        return turn;
    }

    public ActiveTurn registerPlanExecution(String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String turnId = UUID.randomUUID().toString();
        ActiveTurn turn = new ActiveTurn(turnId, token(), normalizedConversationId);
        String existingTurnId = activePlanExecutionsByConversationId.putIfAbsent(normalizedConversationId, turnId);
        if (existingTurnId != null) {
            throw new PlanExecutionConflictException(normalizedConversationId);
        }
        activeTurns.put(turnId, turn);
        return turn;
    }

    public Optional<ActiveTurn> find(String turnId) {
        return Optional.ofNullable(activeTurns.get(turnId));
    }

    public void complete(String turnId) {
        if (StringUtils.hasText(turnId)) {
            activeTurns.remove(turnId);
            activePlanExecutionsByConversationId.values().remove(turnId);
        }
    }

    public InterruptResult interrupt(String turnId, String conversationId, String token, String message) {
        ActiveTurn turn = activeTurns.get(turnId);
        if (turn == null || !turn.conversationId().equals(conversationId)) {
            return InterruptResult.turnNotActive();
        }
        if (!turn.token().equals(token)) {
            return InterruptResult.invalidToken();
        }
        if (!StringUtils.hasText(message)) {
            return InterruptResult.queuedAfterTurn();
        }
        return turn.interrupt(message);
    }

    private String token() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        return conversationId.trim();
    }

    public static final class PlanExecutionConflictException extends RuntimeException {
        private PlanExecutionConflictException(String conversationId) {
            super("Plan execution already active for conversation: " + conversationId);
        }
    }

    public static final class ActiveTurn {
        private final String turnId;
        private final String token;
        private final String conversationId;
        private final Deque<String> interrupts = new ArrayDeque<>();
        private ActiveTurnPhase phase = ActiveTurnPhase.MODEL_CALL;
        private boolean acceptsInterrupts;

        private ActiveTurn(String turnId, String token, String conversationId) {
            this.turnId = turnId;
            this.token = token;
            this.conversationId = conversationId;
        }

        public String turnId() {
            return turnId;
        }

        public String token() {
            return token;
        }

        public String conversationId() {
            return conversationId;
        }

        public synchronized void phase(ActiveTurnPhase phase) {
            this.phase = phase;
            this.acceptsInterrupts = phase == ActiveTurnPhase.TOOL_CALL
                || phase == ActiveTurnPhase.TOOL_CHECKPOINT
                || phase == ActiveTurnPhase.MODEL_CALL;
        }

        public synchronized InterruptResult interrupt(String message) {
            if (!acceptsInterrupts) {
                return InterruptResult.queuedAfterTurn();
            }
            interrupts.addLast(message.trim());
            return InterruptResult.accepted();
        }

        public synchronized Optional<String> pollInterrupt() {
            return Optional.ofNullable(interrupts.pollFirst());
        }

        public synchronized ActiveTurnPhase phase() {
            return phase;
        }
    }
}
