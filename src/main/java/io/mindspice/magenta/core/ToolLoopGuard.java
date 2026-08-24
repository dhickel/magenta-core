package io.mindspice.magenta.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Guards against infinite tool-call loops by tracking two patterns:
 * <ol>
 *   <li><strong>Identical-call detection</strong>: throws
 *       {@link ToolException.LoopExceeded} when the same tool+arguments pair
 *       is called more than {@code identicalCallLimit} consecutive times.</li>
 *   <li><strong>Error-window detection</strong>: throws
 *       {@link ToolException.LoopExceeded} when more than
 *       {@code errorWindowLimit} failures occur within the last
 *       {@code errorWindowSize} tool executions.</li>
 * </ol>
 *
 * <p>All public methods are thread-safe via {@code synchronized} on the
 * internal deques.</p>
 */
public final class ToolLoopGuard {

    private final int identicalCallLimit;
    private final int errorWindowSize;
    private final int errorWindowLimit;

    private final Deque<ToolCallIdentity> callHistory;
    private final Deque<Boolean> errorWindow;

    /**
     * Creates a guard with explicit limits.
     *
     * @param identicalCallLimit max identical consecutive calls before trip
     * @param errorWindowSize    sliding window size for error tracking
     * @param errorWindowLimit   max errors allowed in the window
     */
    public ToolLoopGuard(int identicalCallLimit, int errorWindowSize, int errorWindowLimit) {
        if (identicalCallLimit < 1) {
            throw new IllegalArgumentException("identicalCallLimit must be >= 1");
        }
        if (errorWindowSize < 1) {
            throw new IllegalArgumentException("errorWindowSize must be >= 1");
        }
        if (errorWindowLimit < 0) {
            throw new IllegalArgumentException("errorWindowLimit must be >= 0");
        }
        this.identicalCallLimit = identicalCallLimit;
        this.errorWindowSize = errorWindowSize;
        this.errorWindowLimit = errorWindowLimit;
        this.callHistory = new ArrayDeque<>(identicalCallLimit + 1);
        this.errorWindow = new ArrayDeque<>(errorWindowSize);
    }

    /**
     * Creates a guard with defaults: identical-call limit 5, error window
     * size 8, error window limit 5.
     */
    public ToolLoopGuard() {
        this(5, 8, 5);
    }

    /** Creates an independent guard with the same configured limits for one turn. */
    public ToolLoopGuard forTurn() {
        return new ToolLoopGuard(identicalCallLimit, errorWindowSize, errorWindowLimit);
    }

    // ── Public methods ──

    /**
     * Records a batch of tool calls and throws if the identical-call limit
     * is breached.
     *
     * @param calls the tool calls executed this round
     * @throws ToolException.LoopExceeded if the identical-call limit is breached
     */
    public synchronized void recordToolCalls(@NonNull List<ToolCallIdentity> calls)
            throws ToolException.LoopExceeded {

        for (ToolCallIdentity call : calls) {
            callHistory.addLast(call);
            if (callHistory.size() > identicalCallLimit + 1) {
                callHistory.removeFirst();
            }
        }

        // Scan from the back: count consecutive identical calls
        int consecutive = 1;
        var it = callHistory.descendingIterator();
        if (it.hasNext()) {
            ToolCallIdentity last = it.next();
            while (it.hasNext()) {
                ToolCallIdentity prev = it.next();
                if (last.toolName.equals(prev.toolName) && last.arguments.equals(prev.arguments)) {
                    consecutive++;
                } else {
                    break;
                }
            }

            if (consecutive > identicalCallLimit) {
                throw new ToolException.LoopExceeded(
                    last.toolName,
                    "Identical tool call loop detected: " + last.toolName
                        + " called " + consecutive + " consecutive times",
                    List.of()
                );
            }
        }
    }

    /**
     * Records a tool execution result and throws if the error-window limit
     * is breached.
     *
     * @param result the tool execution result
     * @throws ToolException.LoopExceeded if the error-window limit is breached
     */
    public synchronized void recordToolResponses(@NonNull ToolExecutionResult result)
            throws ToolException.LoopExceeded {

        errorWindow.addLast(result.success);
        if (errorWindow.size() > errorWindowSize) {
            errorWindow.removeFirst();
        }

        int errorCount = 0;
        for (boolean success : errorWindow) {
            if (!success) {
                errorCount++;
            }
        }

        if (errorCount > errorWindowLimit) {
            throw new ToolException.LoopExceeded(
                result.toolName,
                "Error loop detected: " + errorCount + " errors in last "
                    + errorWindow.size() + " tool calls",
                List.of()
            );
        }
    }

    // ── Inner types ──

    /**
     * Identifies a tool call by tool name and serialized arguments.
     */
    public record ToolCallIdentity(@NonNull String toolName, @NonNull String arguments) {}

    /**
     * Result of a single tool execution.
     */
    public record ToolExecutionResult(
        boolean success,
        @NonNull String toolName,
        @Nullable String errorMessage
    ) {}
}
