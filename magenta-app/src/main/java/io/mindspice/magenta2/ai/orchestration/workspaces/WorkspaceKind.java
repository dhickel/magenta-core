package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed namespace for filesystem-first workspace identity.
 * Tokens are the canonical type segment used in layout paths and APIs.
 */
public enum WorkspaceKind {
    AGENT("agent"),
    CHAT("chat"),
    USER("user");

    private final String token;

    WorkspaceKind(String token) {
        this.token = token;
    }

    @JsonValue
    public String token() {
        return token;
    }

    /**
     * Parse a case-normalized token. Rejects blank, whitespace-only,
     * and unknown values.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WorkspaceKind fromToken(String token) {
        if (token == null || token.isBlank() || !token.equals(token.trim())) {
            throw new IllegalArgumentException("Workspace kind token is required without surrounding whitespace");
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        for (WorkspaceKind kind : values()) {
            if (kind.token.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown workspace kind: " + token);
    }
}
