package io.mindspice.magenta2.ai.orchestration.workspaces;

/**
 * Controlled domain failure for workspace file operations.
 * Reasons map directly to HTTP status codes at the controller boundary.
 */
public class WorkspaceFileException extends RuntimeException {

    public enum Reason {
        INVALID,
        NOT_FOUND,
        CONFLICT
    }

    private final Reason reason;

    public WorkspaceFileException(Reason reason, String clientMessage) {
        super(clientMessage);
        this.reason = reason;
    }

    public WorkspaceFileException(Reason reason, String clientMessage, Throwable cause) {
        super(clientMessage, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
