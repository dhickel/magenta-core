package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/**
 * Turn-scoped guard for a model that repeats one failing canonical tool call.
 *
 * <p>A streak contains consecutive executions with both the same canonical
 * tool-call identity and the same typed failure identity. The configured Nth
 * failure trips the guard. A success, a different canonical call, or a
 * different failure identity resets or restarts the streak. Successful
 * repeated calls and unrelated failures never trip this guard.</p>
 *
 * <p>Arguments and failure details are canonicalized into SHA-256 identities;
 * the guard neither retains nor reports their raw values. Failure categories
 * remain the primary typed identity, with a normalized message digest used to
 * distinguish failures that share a category.</p>
 */
public final class ToolLoopGuard {

    private static final int DEFAULT_REPEATED_ERROR_LIMIT = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int repeatedErroredToolCallLimit;
    private final Deque<ToolCallIdentity> pendingLegacyCalls = new ArrayDeque<>();

    private ToolCallIdentity streakCall;
    private ErrorIdentity streakError;
    private int streakLength;

    /** Creates a guard that trips on the configured consecutive failure. */
    public ToolLoopGuard(int repeatedErroredToolCallLimit) {
        if (repeatedErroredToolCallLimit < 1) {
            throw new IllegalArgumentException(
                "repeatedErroredToolCallLimit must be >= 1");
        }
        this.repeatedErroredToolCallLimit = repeatedErroredToolCallLimit;
    }

    /**
     * Compatibility constructor for the superseded call/error-window guard.
     *
     * <p>The first argument now configures the repeated errored-call limit.
     * The historical window arguments are validated for source compatibility
     * but no error window or successful-call guard is retained.</p>
     */
    @Deprecated
    public ToolLoopGuard(int identicalCallLimit, int errorWindowSize, int errorWindowLimit) {
        this(validateLegacyLimits(identicalCallLimit, errorWindowSize, errorWindowLimit));
    }

    /** Creates a guard with the framework's conservative default limit of five. */
    public ToolLoopGuard() {
        this(DEFAULT_REPEATED_ERROR_LIMIT);
    }

    /** Creates an independent guard with the same configured limit for one turn. */
    public ToolLoopGuard forTurn() {
        return new ToolLoopGuard(repeatedErroredToolCallLimit);
    }

    /** Returns the consecutive repeated-error threshold. */
    public int repeatedErroredToolCallLimit() {
        return repeatedErroredToolCallLimit;
    }

    /**
     * Records one completed execution and trips on exactly the configured Nth
     * consecutive occurrence of the same canonical call and failure identity.
     */
    public synchronized void recordToolExecution(
        @NonNull ToolCallIdentity call,
        @NonNull ToolExecutionResult result
    ) throws ToolException.LoopExceeded {
        if (!call.toolName().equals(result.toolName())) {
            throw new IllegalArgumentException(
                "Tool call and result names must match");
        }

        if (result.result() instanceof ToolResult.Success) {
            resetStreak();
            return;
        }

        ToolResult.Failure failure = (ToolResult.Failure) result.result();
        ErrorIdentity error = ErrorIdentity.from(failure);
        if (call.equals(streakCall) && error.equals(streakError)) {
            streakLength++;
        } else {
            streakCall = call;
            streakError = error;
            streakLength = 1;
        }

        if (streakLength >= repeatedErroredToolCallLimit) {
            throw new ToolException.LoopExceeded(
                displayToolName(call.toolName()),
                "Repeated errored tool call limit reached on consecutive failure "
                    + streakLength + " of " + repeatedErroredToolCallLimit,
                List.of());
        }
    }

    /**
     * Compatibility half of the former two-step observation API.
     * New code should call {@link #recordToolExecution(ToolCallIdentity,
     * ToolExecutionResult)} after each execution.
     */
    @Deprecated
    public synchronized void recordToolCalls(@NonNull List<ToolCallIdentity> calls) {
        pendingLegacyCalls.addAll(List.copyOf(calls));
    }

    /**
     * Compatibility half of the former two-step observation API.
     * New code should call {@link #recordToolExecution(ToolCallIdentity,
     * ToolExecutionResult)} after each execution.
     */
    @Deprecated
    public synchronized void recordToolResponses(@NonNull ToolExecutionResult result)
            throws ToolException.LoopExceeded {
        ToolCallIdentity call = pendingLegacyCalls.pollFirst();
        if (call == null) {
            throw new IllegalStateException(
                "recordToolCalls must precede recordToolResponses");
        }
        recordToolExecution(call, result);
    }

    private void resetStreak() {
        streakCall = null;
        streakError = null;
        streakLength = 0;
    }

    private static int validateLegacyLimits(
        int identicalCallLimit, int errorWindowSize, int errorWindowLimit
    ) {
        if (identicalCallLimit < 1) {
            throw new IllegalArgumentException("identicalCallLimit must be >= 1");
        }
        if (errorWindowSize < 1) {
            throw new IllegalArgumentException("errorWindowSize must be >= 1");
        }
        if (errorWindowLimit < 0) {
            throw new IllegalArgumentException("errorWindowLimit must be >= 0");
        }
        return identicalCallLimit;
    }

    private static String displayToolName(String toolName) {
        String safe = toolName.replaceAll("[\\p{Cntrl}]", " ").trim();
        return safe.length() <= 128 ? safe : safe.substring(0, 128);
    }

    /**
     * Disclosure-safe canonical identity of a tool name and argument document.
     * The {@code arguments} component contains a SHA-256 digest, never raw
     * argument text. JSON object property order and insignificant numeric form
     * do not affect the digest; array order remains significant.
     */
    public record ToolCallIdentity(@NonNull String toolName, @NonNull String arguments) {
        public ToolCallIdentity {
            toolName = ResponseValidation.nonBlank(toolName, "toolName");
            arguments = canonicalArgumentsDigest(
                ResponseValidation.required(arguments, "arguments"));
        }
    }

    /** Typed observation of one tool execution. */
    public record ToolExecutionResult(
        @NonNull String toolName,
        @NonNull ToolResult result
    ) {
        public ToolExecutionResult {
            toolName = ResponseValidation.nonBlank(toolName, "toolName");
            result = ResponseValidation.required(result, "result");
        }
    }

    private record ErrorIdentity(
        ToolFailureCategory category,
        String detailDigest
    ) {
        private static ErrorIdentity from(ToolResult.Failure failure) {
            String normalized = failure.message()
                .replaceAll("[\\p{Cntrl}]", " ")
                .trim()
                .replaceAll("\\s+", " ");
            return new ErrorIdentity(
                failure.category(), sha256(normalized.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String canonicalArgumentsDigest(String arguments) {
        MessageDigest digest = sha256Digest();
        try {
            JsonNode node = JSON.readTree(arguments);
            update(digest, "json");
            updateNode(digest, node);
        } catch (Exception ignored) {
            update(digest, "raw");
            update(digest, arguments.strip());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateNode(MessageDigest digest, JsonNode node) {
        if (node == null || node.isNull()) {
            update(digest, "null");
        } else if (node.isObject()) {
            update(digest, "object");
            List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            update(digest, Integer.toString(names.size()));
            for (String name : names) {
                update(digest, name);
                updateNode(digest, node.get(name));
            }
        } else if (node.isArray()) {
            update(digest, "array");
            update(digest, Integer.toString(node.size()));
            node.forEach(child -> updateNode(digest, child));
        } else if (node.isNumber()) {
            update(digest, "number");
            update(digest, node.decimalValue().stripTrailingZeros().toPlainString());
        } else if (node.isTextual()) {
            update(digest, "text");
            update(digest, node.textValue());
        } else if (node.isBoolean()) {
            update(digest, node.booleanValue() ? "true" : "false");
        } else if (node.isBinary()) {
            update(digest, "binary");
            try {
                update(digest, node.binaryValue());
            } catch (java.io.IOException failure) {
                update(digest, node.asText());
            }
        } else {
            update(digest, node.getNodeType().name());
            update(digest, node.asText());
        }
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static String sha256(byte[] value) {
        MessageDigest digest = sha256Digest();
        digest.update(value);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
