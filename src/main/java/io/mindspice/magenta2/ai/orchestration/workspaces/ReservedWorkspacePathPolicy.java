package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.Locale;

/**
 * Closed, side-effect-free classification of workspace-relative paths and
 * explicit operation decisions for each mutation authority class.
 *
 * <p>This component derives segment structure from {@link WorkspacePathLayout},
 * performs only lexical classification, and never discovers workspace identity,
 * constructs roots, touches the filesystem, or performs database access.
 *
 * <h3>Classification rules</h3>
 * <ul>
 *   <li>{@code .skills/} and descendants → {@link Classification#SKILLS}</li>
 *   <li>{@code tasks/} exactly → {@link Classification#TASKS_ROOT}</li>
 *   <li>{@code tasks/<key>/<runId>/outputs/} and descendants → {@link Classification#RUN_OUTPUTS}</li>
 *   <li>{@code tasks/<key>/<runId>/staging/} and descendants → {@link Classification#PUBLICATION_STAGING}</li>
 *   <li>{@code tasks/<key>/<runId>/} exactly → {@link Classification#RUN_ROOT}</li>
 *   <li>{@code tasks/<key>/<runId>/<anything else>} → {@link Classification#RUN_WORKING_CONTENT}</li>
 *   <li>{@code tasks/<key>/} exactly → {@link Classification#TASK_KEY_ROOT}</li>
 *   <li>{@code tasks/<key>/<anything>} that does not match deeper rules →
 *       {@link Classification#RUN_WORKING_CONTENT} when the next segment is a run id</li>
 *   <li>{@code outputs/} and descendants → {@link Classification#WORKSPACE_OUTPUTS}</li>
 *   <li>Everything else → {@link Classification#ORDINARY_CONTENT}</li>
 * </ul>
 *
 * <h3>Default posture</h3>
 * Unknown combinations and unclassifiable paths → {@link Decision#DENY}.
 */
public final class ReservedWorkspacePathPolicy {

    private ReservedWorkspacePathPolicy() {
    }

    /** Closed set of reserved-tree classifications. */
    public enum Classification {
        /** Regular workspace content outside any reserved subtree. */
        ORDINARY_CONTENT,
        /** Workspace-root {@code outputs/} and descendants (legacy/non-task publication). */
        WORKSPACE_OUTPUTS,
        /** The {@code tasks/} directory itself. */
        TASKS_ROOT,
        /** {@code tasks/<taskPathKey>/} — a task-definition root. */
        TASK_KEY_ROOT,
        /** {@code tasks/<key>/<runId>/} — a specific run root. */
        RUN_ROOT,
        /** Content under a run root that is not outputs or staging. */
        RUN_WORKING_CONTENT,
        /** {@code tasks/<key>/<runId>/outputs/} and descendants. */
        RUN_OUTPUTS,
        /** {@code tasks/<key>/<runId>/staging/} and descendants. */
        PUBLICATION_STAGING,
        /** {@code .skills/} and descendants. */
        SKILLS
    }

    /** Operation classes that map to distinct authority requirements. */
    public enum Operation {
        /** List, read, preview, download, inspect — no mutation. */
        BROWSE,
        /** Create, save, rename, move, copy, delete in ordinary content. */
        ORDINARY_MUTATION,
        /** Create or delete task-key roots and run roots. */
        STRUCTURAL_MUTATION,
        /** Write to run outputs or staging (publication authority). */
        PUBLICATION_MUTATION,
        /** Write to {@code .skills/}. */
        SKILLS_MUTATION,
        /** Write to run working content (requires current writer capability). */
        RUN_WRITER_MUTATION
    }

    public enum Decision {
        ALLOW,
        DENY
    }

    /**
     * Classify a normalized workspace-relative path. The path must use
     * forward slashes, must not contain {@code ..}, {@code \0}, or
     * absolute-prefix patterns, and must not contain URL-encoded separators.
     *
     * @param relativePath normalized workspace-relative path; {@code "."} or
     *                     empty classifies as {@link Classification#ORDINARY_CONTENT}
     * @return the classification
     * @throws IllegalArgumentException if the path contains traversal,
     *         encoded separators, or structural ambiguity
     */
    public static Classification classify(String relativePath) {
        String path = normalize(relativePath);

        // <.> or empty → ordinary
        if (path.isEmpty() || ".".equals(path)) {
            return Classification.ORDINARY_CONTENT;
        }

        // Reject encoded-separator bypasses before segment analysis
        rejectEncodedSeparators(relativePath);

        String[] segments = path.split("/", -1);

        // --- .skills ---
        if (segments.length >= 1 && WorkspacePathLayout.SKILLS.equals(segments[0])) {
            return Classification.SKILLS;
        }

        // --- workspace-root outputs ---
        if (segments.length >= 1 && WorkspacePathLayout.OUTPUTS.equals(segments[0])) {
            return Classification.WORKSPACE_OUTPUTS;
        }

        // --- tasks ---
        if (segments.length >= 1 && WorkspacePathLayout.TASKS.equals(segments[0])) {
            return classifyTasksPath(segments);
        }

        // Everything else is ordinary
        return Classification.ORDINARY_CONTENT;
    }

    /**
     * Decide whether an operation with a given authority is permitted for a
     * classification. The caller must also provide a valid capability for
     * {@link Operation#RUN_WRITER_MUTATION}; this method only maps authority
     * to the classification, it does not verify capabilities.
     */
    public static Decision decide(Classification classification, Operation operation) {
        return switch (classification) {
            case ORDINARY_CONTENT -> switch (operation) {
                case BROWSE, ORDINARY_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case WORKSPACE_OUTPUTS -> switch (operation) {
                case BROWSE, PUBLICATION_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case TASKS_ROOT -> switch (operation) {
                case BROWSE, STRUCTURAL_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case TASK_KEY_ROOT -> switch (operation) {
                case BROWSE, STRUCTURAL_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case RUN_ROOT -> switch (operation) {
                case BROWSE, STRUCTURAL_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case RUN_WORKING_CONTENT -> switch (operation) {
                case BROWSE, RUN_WRITER_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case RUN_OUTPUTS -> switch (operation) {
                case BROWSE, PUBLICATION_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case PUBLICATION_STAGING -> switch (operation) {
                case PUBLICATION_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
            case SKILLS -> switch (operation) {
                case SKILLS_MUTATION -> Decision.ALLOW;
                default -> Decision.DENY;
            };
        };
    }

    // ── Internal helpers ──

    private static Classification classifyTasksPath(String[] segments) {
        // "tasks" exactly
        if (segments.length == 1) {
            return Classification.TASKS_ROOT;
        }

        // Layout owns both canonical structural component validators.  Refuse
        // to guess that arbitrary second/third segments are task structure.
        WorkspacePathLayout.validateTaskPathKeySegment(segments[1]);

        // "tasks/<key>" exactly
        if (segments.length == 2) {
            return Classification.TASK_KEY_ROOT;
        }

        WorkspacePathLayout.validateRunId(segments[2]);

        // "tasks/<key>/<runId>" exactly
        if (segments.length == 3) {
            return Classification.RUN_ROOT;
        }

        // "tasks/<key>/<runId>/outputs/..."
        if (segments.length >= 4 && WorkspacePathLayout.OUTPUTS.equals(segments[3])) {
            return Classification.RUN_OUTPUTS;
        }

        // "tasks/<key>/<runId>/staging/..."
        if (segments.length >= 4 && WorkspacePathLayout.STAGING.equals(segments[3])) {
            return Classification.PUBLICATION_STAGING;
        }

        // "tasks/<key>/<runId>/<anything else>" → run working content
        if (segments.length >= 4) {
            return Classification.RUN_WORKING_CONTENT;
        }

        // Should not be reachable given length checks, but belt-and-suspenders
        return Classification.TASKS_ROOT;
    }

    private static String normalize(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("relativePath is required");
        }
        String trimmed = relativePath.trim();
        if (trimmed.isEmpty()) {
            return ".";
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("path contains null character");
        }
        if (trimmed.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path must use forward slashes");
        }
        if (trimmed.startsWith("/") || trimmed.matches("^[A-Za-z]:[/\\\\].*")) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        // Reject traversal
        for (String segment : trimmed.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment) && !trimmed.equals(".")) {
                // "." alone is allowed; "." as a segment component is not
                if (".".equals(segment)) {
                    throw new IllegalArgumentException("path contains ambiguous '.' segment");
                }
                throw new IllegalArgumentException("path escapes workspace");
            }
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("path contains empty segment");
            }
        }
        return trimmed;
    }

    private static void rejectEncodedSeparators(String path) {
        if (path == null) return;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
            throw new IllegalArgumentException("path contains encoded separators");
        }
    }
}
