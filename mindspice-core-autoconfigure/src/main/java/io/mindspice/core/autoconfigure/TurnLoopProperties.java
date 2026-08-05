package io.mindspice.core.autoconfigure;

import io.mindspice.core.ConfigurationException;
import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;

/**
 * Type-safe configuration binding for the {@code turnloop} prefix.
 *
 * <p>Every subsection of the handoff YAML schema is represented as a nested
 * {@code record} with sensible defaults. Validation in {@link #validate()}
 * distinguishes clamp-with-warn (range violations) from throw-on-startup
 * (missing model references, invalid endpoint URLs).</p>
 */
@ConfigurationProperties(prefix = "turnloop")
public class TurnLoopProperties {

    private static final Logger log = LoggerFactory.getLogger(TurnLoopProperties.class);

    // ── Top-level properties ──

    private String dataRoot = System.getProperty("user.home") + "/.turnloop";
    private Models models = new Models();
    private Context context = new Context();
    private Turn turn = new Turn();
    private Queue queue = new Queue();
    private Stream stream = new Stream();
    private Executor executor = new Executor();
    private ModelTimeout modelTimeout = new ModelTimeout();
    private Prompt prompt = new Prompt();
    private ResultSummarizer resultSummarizer = new ResultSummarizer();

    // ── Getters / Setters for top-level properties ──

    public String getDataRoot() { return dataRoot; }
    public void setDataRoot(String dataRoot) { this.dataRoot = dataRoot; }

    public Models getModels() { return models; }
    public void setModels(Models models) { this.models = models; }

    public Context getContext() { return context; }
    public void setContext(Context context) { this.context = context; }

    public Turn getTurn() { return turn; }
    public void setTurn(Turn turn) { this.turn = turn; }

    public Queue getQueue() { return queue; }
    public void setQueue(Queue queue) { this.queue = queue; }

    public Stream getStream() { return stream; }
    public void setStream(Stream stream) { this.stream = stream; }

    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }

    public ModelTimeout getModelTimeout() { return modelTimeout; }
    public void setModelTimeout(ModelTimeout modelTimeout) { this.modelTimeout = modelTimeout; }

    public Prompt getPrompt() { return prompt; }
    public void setPrompt(Prompt prompt) { this.prompt = prompt; }

    public ResultSummarizer getResultSummarizer() { return resultSummarizer; }
    public void setResultSummarizer(ResultSummarizer resultSummarizer) { this.resultSummarizer = resultSummarizer; }

    // ── Nested configuration records ──

    public record Models(
        @NonNull String defaultModel,
        @NonNull String compactionModel,
        @NonNull Map<String, ProviderConfig> providers
    ) {
        public Models() {
            this("ollama-local", "ollama-local", Map.of());
        }
    }

    public record ProviderConfig(
        @NonNull String type,
        @NonNull String endpoint,
        @NonNull String model,
        @Nullable Integer contextLength,
        @Nullable Integer thinkLevel,
        @NonNull String apiKey
    ) {
        public ProviderConfig() {
            this("ollama", "http://localhost:11434", "qwen3.6:35b", 128000, 0, "");
        }
    }

    public record Context(
        int bufferPercent,
        @NonNull Compaction compaction,
        @NonNull Trimming trimming
    ) {
        public Context() {
            this(10, new Compaction(), new Trimming());
        }
    }

    public record Compaction(int minTailMessages) {
        public Compaction() { this(6); }
    }

    public record Trimming(boolean enabled) {
        public Trimming() { this(true); }
    }

    public record Turn(
        int maxToolRounds,
        int maxEmptyResponseRetries,
        @NonNull Duration timeout,
        @NonNull Duration drainTimeout,
        @NonNull ToolGuard toolGuard
    ) {
        public Turn() {
            this(50, 2, Duration.ofMinutes(10), Duration.ofSeconds(30), new ToolGuard());
        }
    }

    public record ToolGuard(
        int identicalCallLimit,
        int errorWindowSize,
        int errorWindowLimit
    ) {
        public ToolGuard() { this(5, 8, 5); }
    }

    public record Queue(
        @NonNull Duration staleClaimAge,
        int maxQueueSizePerSession
    ) {
        public Queue() { this(Duration.ofMinutes(10), 100); }
    }

    public record Stream(int bufferCapacity) {
        public Stream() { this(256); }
    }

    public record Executor(
        @NonNull String strategy,
        int turnThreads,
        int turnQueueSize
    ) {
        public Executor() { this("virtual", 8, 200); }
    }

    public record ModelTimeout(int openaiCompatibleReadSeconds) {
        public ModelTimeout() { this(360); }
    }

    public record Prompt(@NonNull String defaultSystemPrompt) {
        public Prompt() { this("You are a helpful assistant."); }
    }

    public record ResultSummarizer(int maxResultChars) {
        public ResultSummarizer() { this(2000); }
    }

    // ── Validation ──

    @PostConstruct
    void validate() {
        // 1. Model-to-provider reference checks
        if (!models.providers.containsKey(models.defaultModel)) {
            throw new ConfigurationException(
                "Default model '" + models.defaultModel + "' not found in providers",
                Map.of("error", "default_model_missing", "alias", models.defaultModel));
        }
        if (!models.providers.containsKey(models.compactionModel)) {
            throw new ConfigurationException(
                "Compaction model '" + models.compactionModel + "' not found in providers",
                Map.of("error", "compaction_model_missing", "alias", models.compactionModel));
        }

        // 2. Endpoint URL validity
        for (var entry : models.providers.entrySet()) {
            String name = entry.getKey();
            String endpoint = entry.getValue().endpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                try {
                    new URL(endpoint);
                } catch (MalformedURLException e) {
                    throw new ConfigurationException(
                        "Invalid endpoint URL for model '" + name + "': " + endpoint,
                        Map.of("error", "invalid_endpoint", "model", name, "endpoint", endpoint));
                }
            }
        }

        // 3. Range clamping with WARN
        clampWithWarn("context.buffer-percent", context.bufferPercent, 0, 50,
            v -> context = new Context(v, context.compaction, context.trimming));

        clampWithWarn("turn.max-tool-rounds", turn.maxToolRounds, 1, Integer.MAX_VALUE,
            v -> turn = new Turn(v, turn.maxEmptyResponseRetries, turn.timeout, turn.drainTimeout, turn.toolGuard));

        if (turn.timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            log.warn("turnloop.turn.timeout={} is below minimum 1s — clamping to 1s", turn.timeout);
            turn = new Turn(turn.maxToolRounds, turn.maxEmptyResponseRetries,
                Duration.ofSeconds(1), turn.drainTimeout, turn.toolGuard);
        }

        // drainTimeout: accepts >= 0 (no clamp, no warn)
        if (turn.drainTimeout.isNegative()) {
            log.warn("turnloop.turn.drain-timeout={} is negative — clamping to 0", turn.drainTimeout);
            turn = new Turn(turn.maxToolRounds, turn.maxEmptyResponseRetries,
                turn.timeout, Duration.ZERO, turn.toolGuard);
        }

        clampWithWarn("stream.buffer-capacity", stream.bufferCapacity, 1, Integer.MAX_VALUE,
            v -> stream = new Stream(v));

        clampWithWarn("result-summarizer.max-result-chars", resultSummarizer.maxResultChars, 100, Integer.MAX_VALUE,
            v -> resultSummarizer = new ResultSummarizer(v));
    }

    @FunctionalInterface
    private interface IntSetter {
        void set(int value);
    }

    private static void clampWithWarn(String propertyName, int value, int min, int max, IntSetter setter) {
        if (value < min) {
            log.warn("turnloop.{}={} is below minimum {} — clamping to {}", propertyName, value, min, min);
            setter.set(min);
        } else if (value > max) {
            log.warn("turnloop.{}={} is above maximum {} — clamping to {}", propertyName, value, max, max);
            setter.set(max);
        }
    }
}
