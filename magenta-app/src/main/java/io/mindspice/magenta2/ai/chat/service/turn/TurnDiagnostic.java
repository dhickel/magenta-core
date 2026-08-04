package io.mindspice.magenta2.ai.chat.service.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Diagnostic marker emitted at DEBUG level for each phase transition in the tool loop.
 * Provides branch-level observability for production troubleshooting.
 */
public record TurnDiagnostic(
    String conversationId,
    TurnPhase phase,
    Instant startTime,
    Duration duration,
    OutcomeKind outcomeKind,
    Map<String, Object> details
) {
    public enum OutcomeKind {
        PROGRESS, RETRY, REPAIR, COMPLETED, ABORTED, FAILED
    }

    public static TurnDiagnostic start(String conversationId, TurnPhase phase, Map<String, Object> details) {
        return new TurnDiagnostic(conversationId, phase, Instant.now(), null, OutcomeKind.PROGRESS, details);
    }

    public TurnDiagnostic end(OutcomeKind outcome, Map<String, Object> additionalDetails) {
        var merged = new java.util.LinkedHashMap<>(details);
        merged.putAll(additionalDetails);
        return new TurnDiagnostic(conversationId, phase, startTime,
            Duration.between(startTime, Instant.now()), outcome, Map.copyOf(merged));
    }
}
