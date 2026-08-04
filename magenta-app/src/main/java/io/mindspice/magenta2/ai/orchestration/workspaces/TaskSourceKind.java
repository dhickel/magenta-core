package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.Locale;

/** Closed execution-definition families that can own task-run paths. */
public enum TaskSourceKind {
    TASK('T'),
    WORKFLOW('W'),
    JOB('J');

    private final byte canonicalByte;

    TaskSourceKind(char canonicalByte) {
        this.canonicalByte = (byte) canonicalByte;
    }

    byte canonicalByte() {
        return canonicalByte;
    }

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskSourceKind fromToken(String token) {
        if (token == null || token.isBlank() || !token.equals(token.trim())) {
            throw new IllegalArgumentException("Task source kind token is required without surrounding whitespace");
        }
        try {
            return valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported task source kind: " + token, exception);
        }
    }
}
