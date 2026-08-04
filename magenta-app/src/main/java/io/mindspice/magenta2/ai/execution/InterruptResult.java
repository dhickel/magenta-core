package io.mindspice.magenta2.ai.execution;

public record InterruptResult(InterruptStatus status) {
    public static InterruptResult accepted() {
        return new InterruptResult(InterruptStatus.ACCEPTED);
    }

    public static InterruptResult queuedAfterTurn() {
        return new InterruptResult(InterruptStatus.QUEUED_AFTER_TURN);
    }

    public static InterruptResult turnNotActive() {
        return new InterruptResult(InterruptStatus.TURN_NOT_ACTIVE);
    }

    public static InterruptResult invalidToken() {
        return new InterruptResult(InterruptStatus.INVALID_TOKEN);
    }
}
