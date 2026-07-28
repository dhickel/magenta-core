package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;

import org.springframework.util.StringUtils;

/**
 * One applicable AGENTS.md layer for a resolved bound root and active path.
 *
 * <p>Layers are ordered broadest-to-closest in {@link AgentsMdResolution#layers()}.
 */
public record AgentsMdLayer(
    Path sourcePath,
    String relativeDirectory,
    String content,
    int precedenceRank,
    boolean truncated
) {
    public AgentsMdLayer {
        if (sourcePath == null) {
            throw new IllegalArgumentException("sourcePath is required");
        }
        if (relativeDirectory == null) {
            throw new IllegalArgumentException("relativeDirectory is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        if (precedenceRank < 0) {
            throw new IllegalArgumentException("precedenceRank must be >= 0");
        }
        relativeDirectory = normalize(relativeDirectory);
    }

    /** Backward-compatible constructor without truncation flag. */
    public AgentsMdLayer(Path sourcePath, String relativeDirectory,
                          String content, int precedenceRank) {
        this(sourcePath, relativeDirectory, content, precedenceRank, false);
    }

    public boolean isRootLayer() {
        return !StringUtils.hasText(relativeDirectory);
    }

    private static String normalize(String value) {
        String normalized = value.trim().replace('\\', '/');
        return ".".equals(normalized) ? "" : normalized;
    }
}
