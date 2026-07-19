package io.mindspice.magenta2.ai.orchestration.workspaces;

/**
 * Controlled domain failure for workspace resolution.
 * Transport-neutral reasons for Phase 04 HTTP mapping.
 */
public class WorkspaceResolutionException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        CONFLICT
    }

    private final Reason reason;

    public WorkspaceResolutionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
