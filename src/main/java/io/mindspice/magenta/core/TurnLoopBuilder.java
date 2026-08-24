package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.engine.ContextManager;
import io.mindspice.magenta.core.engine.DefaultPromptAssembler;
import io.mindspice.magenta.core.engine.DefaultTurnEngine;
import io.mindspice.magenta.core.internal.ActiveTurnRegistry;
import io.mindspice.magenta.core.internal.ConcurrentActiveTurnRegistry;
import io.mindspice.magenta.core.internal.DefaultModelRouter;
import io.mindspice.magenta.core.internal.DefaultTurnExecutor;
import io.mindspice.magenta.core.internal.ModelRouter;
import io.mindspice.magenta.core.internal.TurnExecutor;
import io.mindspice.magenta.core.persistence.SqliteAuditWriter;
import io.mindspice.magenta.core.persistence.SqliteConversationMemory;
import io.mindspice.magenta.core.persistence.SqliteInteractionStore;
import io.mindspice.magenta.core.persistence.SqliteMessageQueueStore;
import io.mindspice.magenta.core.persistence.SqliteSessionStore;
import io.mindspice.magenta.core.spi.AuditWriter;
import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.CompletionDetector;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.InteractionHandler;
import io.mindspice.magenta.core.spi.InteractionStore;
import io.mindspice.magenta.core.spi.MessageQueue;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import io.mindspice.magenta.core.spi.ModelCapabilityProvider;
import io.mindspice.magenta.core.spi.PromptContributor;
import io.mindspice.magenta.core.spi.RepairStrategy;
import io.mindspice.magenta.core.spi.SessionStore;
import io.mindspice.magenta.core.spi.ThinkingExtractor;
import io.mindspice.magenta.core.spi.TokenEstimator;
import io.mindspice.magenta.core.spi.ToolAccessPolicy;
import io.mindspice.magenta.core.spi.ToolRegistry;
import io.mindspice.magenta.core.spi.ToolResultSummarizer;
import io.mindspice.magenta.core.spi.TurnGate;
import io.mindspice.magenta.core.spi.TurnLifecycleListener;
import io.mindspice.magenta.core.spi.TurnLoopMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Programmatic composition root for the {@link TurnLoop} facade.
 *
 * <p>The builder deliberately has no Spring application-context dependency.
 * Callers select exactly one storage source, register models in order, and
 * provide optional Magenta-owned collaborators before calling {@link #build()}.
 * The resulting model registry is an immutable snapshot of the registrations
 * made at build time.</p>
 *
 * <p>Build-time model aliases are safe, non-blank identifiers. The alias used
 * to register a model must equal the alias carried by its {@link ModelConfig};
 * this prevents the persisted/session alias from silently selecting a
 * different endpoint.</p>
 */
public final class TurnLoopBuilder {

    private static final Logger log = LoggerFactory.getLogger(TurnLoopBuilder.class);
    private static final Pattern MODEL_ALIAS =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Duration DEFAULT_MODEL_TIMEOUT = Duration.ofSeconds(360);
    private static final String DATABASE_FILE_NAME = "magenta-core.db";

    private final Map<String, ModelConfig> modelConfigs = new LinkedHashMap<>();
    private final List<PromptContributor> promptContributors = new ArrayList<>();

    private Path dataRoot;
    private DataSource dataSource;
    private String defaultSystemPrompt = "You are a helpful AI assistant.";
    private ToolRegistry toolRegistry;
    private ToolAccessPolicy toolAccessPolicy = ToolAccessPolicy.allowAll();
    private ToolResultSummarizer toolResultSummarizer;
    private InteractionHandler interactionHandler;
    private InteractionStore interactionStore;
    private ConversationMemory conversationMemory;
    private SessionStore sessionStore;
    private TokenEstimator tokenEstimator;
    private CompactionStrategy compactionStrategy;
    private CompletionDetector completionDetector;
    private RepairStrategy repairStrategy;
    private TurnGate turnGate;
    private MessageQueue messageQueue;
    private MessageQueueStore messageQueueStore;
    private AuditWriter auditWriter;
    private ModelCapabilityProvider modelCapabilityProvider;
    private ThinkingExtractor thinkingExtractor;
    private TurnLifecycleListener turnLifecycleListener;
    private TurnLoopMetrics turnLoopMetrics;
    private ObjectMapper objectMapper;
    private Duration modelTimeout = DEFAULT_MODEL_TIMEOUT;
    private long drainTimeoutSeconds = 30;

    /** Creates an empty builder with no implicit storage source. */
    public TurnLoopBuilder() {
    }

    /** Creates an empty builder with no implicit storage source. */
    public static TurnLoopBuilder create() {
        return new TurnLoopBuilder();
    }

    /**
     * Registers a model under an immutable build-time alias.
     * Registration order determines the fallback model.
     */
    public TurnLoopBuilder model(@NonNull String alias, @NonNull ModelConfig config) {
        validateAlias(alias, "alias");
        if (config == null) {
            throw configuration(
                "Model configuration for alias '" + alias + "' must not be null",
                Map.of("error", "model_config_missing", "alias", alias));
        }
        if (modelConfigs.containsKey(alias)) {
            throw configuration(
                "Model alias '" + alias + "' is already registered",
                Map.of("error", "duplicate_model_alias", "alias", alias));
        }
        modelConfigs.put(alias, config);
        return this;
    }

    /** Sets the root directory for the durable, file-backed SQLite database. */
    public TurnLoopBuilder dataRoot(@NonNull Path dataRoot) {
        if (dataRoot == null) {
            throw configuration(
                "dataRoot must not be null",
                Map.of("error", "data_root_missing"));
        }
        this.dataRoot = dataRoot;
        return this;
    }

    /** Sets the caller-owned JDBC storage source. */
    public TurnLoopBuilder dataSource(@NonNull DataSource dataSource) {
        if (dataSource == null) {
            throw configuration(
                "dataSource must not be null",
                Map.of("error", "data_source_missing"));
        }
        this.dataSource = dataSource;
        return this;
    }

    /** Sets the default system prompt used when the caller does not supply one. */
    public TurnLoopBuilder defaultSystemPrompt(@NonNull String prompt) {
        this.defaultSystemPrompt = Objects.requireNonNull(prompt, "prompt");
        return this;
    }

    public TurnLoopBuilder toolRegistry(@NonNull ToolRegistry registry) {
        this.toolRegistry = Objects.requireNonNull(registry, "registry");
        return this;
    }

    public TurnLoopBuilder toolAccessPolicy(@NonNull ToolAccessPolicy policy) {
        this.toolAccessPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    public TurnLoopBuilder toolResultSummarizer(@NonNull ToolResultSummarizer summarizer) {
        this.toolResultSummarizer = Objects.requireNonNull(summarizer, "summarizer");
        return this;
    }

    public TurnLoopBuilder interactionHandler(@NonNull InteractionHandler handler) {
        this.interactionHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    public TurnLoopBuilder interactionStore(@NonNull InteractionStore store) {
        this.interactionStore = Objects.requireNonNull(store, "store");
        return this;
    }

    public TurnLoopBuilder conversationMemory(@NonNull ConversationMemory memory) {
        this.conversationMemory = Objects.requireNonNull(memory, "memory");
        return this;
    }

    public TurnLoopBuilder sessionStore(@NonNull SessionStore store) {
        this.sessionStore = Objects.requireNonNull(store, "store");
        return this;
    }

    public TurnLoopBuilder tokenEstimator(@NonNull TokenEstimator estimator) {
        this.tokenEstimator = Objects.requireNonNull(estimator, "estimator");
        return this;
    }

    public TurnLoopBuilder compactionStrategy(@NonNull CompactionStrategy strategy) {
        this.compactionStrategy = Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    public TurnLoopBuilder completionDetector(@NonNull CompletionDetector detector) {
        this.completionDetector = Objects.requireNonNull(detector, "detector");
        return this;
    }

    public TurnLoopBuilder repairStrategy(@NonNull RepairStrategy strategy) {
        this.repairStrategy = Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    public TurnLoopBuilder turnGate(@NonNull TurnGate gate) {
        this.turnGate = Objects.requireNonNull(gate, "gate");
        return this;
    }

    public TurnLoopBuilder messageQueue(@NonNull MessageQueue queue) {
        this.messageQueue = Objects.requireNonNull(queue, "queue");
        return this;
    }

    public TurnLoopBuilder messageQueueStore(@NonNull MessageQueueStore store) {
        this.messageQueueStore = Objects.requireNonNull(store, "store");
        return this;
    }

    public TurnLoopBuilder auditWriter(@NonNull AuditWriter writer) {
        this.auditWriter = Objects.requireNonNull(writer, "writer");
        return this;
    }

    /** Adds a prompt contributor in registration order. */
    public TurnLoopBuilder promptContributor(@NonNull PromptContributor contributor) {
        promptContributors.add(Objects.requireNonNull(contributor, "contributor"));
        return this;
    }

    public TurnLoopBuilder modelCapabilityProvider(@NonNull ModelCapabilityProvider provider) {
        this.modelCapabilityProvider = Objects.requireNonNull(provider, "provider");
        return this;
    }

    public TurnLoopBuilder thinkingExtractor(@NonNull ThinkingExtractor extractor) {
        this.thinkingExtractor = Objects.requireNonNull(extractor, "extractor");
        return this;
    }

    public TurnLoopBuilder turnLifecycleListener(@NonNull TurnLifecycleListener listener) {
        this.turnLifecycleListener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Sets the explicit metrics sink; no Spring bean discovery is performed. */
    public TurnLoopBuilder turnLoopMetrics(@NonNull TurnLoopMetrics metrics) {
        this.turnLoopMetrics = Objects.requireNonNull(metrics, "metrics");
        return this;
    }

    public TurnLoopBuilder objectMapper(@NonNull ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        return this;
    }

    /** Sets the provider request timeout used by internally-owned model clients. */
    public TurnLoopBuilder modelTimeout(@NonNull Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw configuration(
                "model timeout must be greater than zero",
                Map.of("error", "invalid_model_timeout"));
        }
        this.modelTimeout = timeout;
        return this;
    }

    /** Sets the graceful shutdown drain timeout in seconds. */
    public TurnLoopBuilder drainTimeoutSeconds(long seconds) {
        if (seconds < 0) {
            throw configuration(
                "drainTimeoutSeconds must not be negative",
                Map.of("error", "invalid_drain_timeout"));
        }
        this.drainTimeoutSeconds = seconds;
        return this;
    }

    /**
     * Validates the builder and constructs a fully programmatic turn loop.
     *
     * @throws ConfigurationException if storage, model, provider, or schema
     *                                configuration is invalid
     */
    public TurnLoop build() {
        Map<String, ModelConfig> registeredModels = validateAndSnapshotModels();
        DataSource resolvedDataSource = resolveStorage();
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();

        initializeSchema(resolvedDataSource);

        ToolRegistry registry = resolveDefault(
            toolRegistry, "toolRegistry", () -> new DefaultToolRegistry(mapper));
        ToolAccessPolicy accessPolicy = toolAccessPolicy;
        ToolResultSummarizer summarizer = resolveDefault(
            toolResultSummarizer, "toolResultSummarizer", DefaultToolResultSummarizer::new);
        InteractionStore iStore = resolveDefault(
            interactionStore, "interactionStore", () -> new SqliteInteractionStore(resolvedDataSource, mapper));

        ConversationMemory cMemory;
        SessionStore sStore;
        MessageQueueStore mqStore;
        try {
            cMemory = resolveDefault(
                conversationMemory, "conversationMemory", () -> new SqliteConversationMemory(resolvedDataSource, mapper));
            sStore = resolveDefault(
                sessionStore, "sessionStore", () -> new SqliteSessionStore(resolvedDataSource, mapper));
            mqStore = resolveDefault(
                messageQueueStore, "messageQueueStore", () -> new SqliteMessageQueueStore(resolvedDataSource, mapper));
        } catch (RuntimeException e) {
            throw configuration(
                "Framework schema initialization failed: " + messageOf(e),
                Map.of("error", "schema_initialization_failed"), e);
        }

        TokenEstimator tEstimator = resolveDefault(tokenEstimator, "tokenEstimator", DefaultTokenEstimator::new);
        CompactionStrategy cStrategy = resolveDefault(
            compactionStrategy, "compactionStrategy", DefaultCompactionStrategy::new);
        CompletionDetector cDetector = resolveDefault(
            completionDetector, "completionDetector", DefaultCompletionDetector::instance);
        RepairStrategy rStrategy = resolveDefault(
            repairStrategy, "repairStrategy", DefaultRepairStrategy::instance);
        boolean ownsTurnGate = turnGate == null;
        TurnGate gate = resolveDefault(
            turnGate, "turnGate", () -> new SerialTurnGate(Duration.ofMinutes(10)));
        MessageQueue mQueue = resolveDefault(messageQueue, "messageQueue", () -> new DefaultMessageQueue(mqStore));
        AuditWriter resolvedAuditWriter = resolveDefault(
            auditWriter, "auditWriter", () -> new SqliteAuditWriter(resolvedDataSource, mapper));
        ThinkingExtractor tExtractor = resolveDefault(
            thinkingExtractor, "thinkingExtractor", CompositeThinkingExtractor::new);
        TurnLifecycleListener lifecycleListener = resolveDefault(
            turnLifecycleListener, "turnLifecycleListener", () -> result -> { });

        TurnExecutor executor = new DefaultTurnExecutor();
        ActiveTurnRegistry activeTurnRegistry = new ConcurrentActiveTurnRegistry();
        ModelRouter router = new DefaultModelRouter(registeredModels, modelTimeout);

        ContextManager ctxManager = new ContextManager(cMemory, tEstimator, cStrategy, 10);
        DefaultPromptAssembler assembler = new DefaultPromptAssembler(
            defaultSystemPrompt, List.copyOf(promptContributors));

        DefaultTurnEngine engine = new DefaultTurnEngine.Builder()
            .toolExecution(registry, accessPolicy, summarizer, new ToolLoopGuard())
            .contextAssembly(cMemory, tEstimator, cStrategy)
            .contextManager(ctxManager)
            .promptAssembler(assembler)
            .responseRepair(cDetector, rStrategy)
            .interaction(interactionHandler, iStore)
            .infrastructure(router, executor, activeTurnRegistry, tExtractor)
            .build();

        String defaultModel = registeredModels.keySet().iterator().next();
        return new DefaultTurnLoop(
            engine,
            gate,
            mQueue,
            iStore,
            sStore,
            cMemory,
            activeTurnRegistry,
            executor,
            registry,
            resolveModelCapabilityProvider(),
            lifecycleListener,
            turnLoopMetrics,
            mapper,
            defaultSystemPrompt,
            defaultModel,
            drainTimeoutSeconds,
            resolvedAuditWriter,
            ownsTurnGate
        );
    }

    private Map<String, ModelConfig> validateAndSnapshotModels() {
        if (modelConfigs.isEmpty()) {
            throw configuration(
                "At least one model must be configured",
                Map.of("error", "no_models"));
        }

        LinkedHashMap<String, ModelConfig> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ModelConfig> entry : modelConfigs.entrySet()) {
            String alias = entry.getKey();
            ModelConfig config = entry.getValue();
            validateAlias(alias, "alias");
            validateModelConfig(alias, config);
            if (!alias.equals(config.alias())) {
                throw configuration(
                    "ModelConfig alias '" + config.alias() + "' does not match registration alias '" + alias + "'",
                    Map.of("error", "model_alias_mismatch", "alias", alias,
                        "configAlias", String.valueOf(config.alias())));
            }
            snapshot.put(alias, config);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private void validateModelConfig(String alias, ModelConfig config) {
        if (config == null) {
            throw configuration(
                "Model configuration for alias '" + alias + "' must not be null",
                Map.of("error", "model_config_missing", "alias", alias));
        }
        if (config.alias() == null || config.alias().isBlank()) {
            throw configuration(
                "ModelConfig alias must not be blank",
                Map.of("error", "invalid_model_alias", "alias", alias));
        }
        validateAlias(config.alias(), "modelConfig.alias");
        if (config.remoteModelName() == null || config.remoteModelName().isBlank()
            || containsControlCharacter(config.remoteModelName())) {
            throw configuration(
                "Remote model name for alias '" + alias + "' must be a non-blank name",
                Map.of("error", "invalid_remote_model", "alias", alias));
        }
        if (config.remoteEndpoint() == null || config.remoteEndpoint().isBlank()) {
            throw configuration(
                "Remote endpoint for alias '" + alias + "' must not be blank",
                Map.of("error", "invalid_remote_endpoint", "alias", alias));
        }
        validateEndpoint(alias, config.remoteEndpoint());
        if (config.endpointType() == null) {
            throw configuration(
                "Endpoint type for alias '" + alias + "' must not be null",
                Map.of("error", "invalid_endpoint_type", "alias", alias));
        }
        if (config.apiKey() != null && config.apiKey().isBlank()) {
            throw configuration(
                "API key for alias '" + alias + "' must be null or non-blank",
                Map.of("error", "invalid_api_key", "alias", alias));
        }
        if (config.endpointType() == EndpointType.DEEPSEEK
            && (config.apiKey() == null || config.apiKey().isBlank())) {
            throw configuration(
                "DeepSeek requires a non-blank API key for alias '" + alias + "'",
                Map.of("error", "deepseek_api_key_missing", "alias", alias));
        }
        if (config.contextLength() != null && config.contextLength() <= 0) {
            throw configuration(
                "Context length for alias '" + alias + "' must be greater than zero",
                Map.of("error", "invalid_context_length", "alias", alias));
        }
        if (config.thinkLevel() != null && (config.thinkLevel() < 0 || config.thinkLevel() > 4)) {
            throw configuration(
                "Think level for alias '" + alias + "' must be between 0 and 4",
                Map.of("error", "invalid_think_level", "alias", alias));
        }
        if (config.endpointType() == EndpointType.DEEPSEEK
            && config.effectiveThinkLevel() > 0) {
            throw configuration(
                "DeepSeek thinking/reasoning levels are not exposed by the pinned Spring AI 2.0.0 API",
                Map.of("error", "unsupported_provider_option", "provider", "DEEPSEEK",
                    "option", "thinkLevel", "alias", alias));
        }
    }

    private void validateEndpoint(String alias, String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
                throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException e) {
            throw configuration(
                "Remote endpoint for alias '" + alias + "' is invalid: " + messageOf(e),
                Map.of("error", "invalid_remote_endpoint", "alias", alias));
        }
    }

    private DataSource resolveStorage() {
        boolean rootConfigured = dataRoot != null;
        boolean dataSourceConfigured = dataSource != null;
        if (rootConfigured == dataSourceConfigured) {
            if (rootConfigured) {
                throw configuration(
                    "Configure exactly one storage source: dataRoot or dataSource, not both",
                    Map.of("error", "storage_conflict"));
            }
            throw configuration(
                "Configure exactly one storage source: dataRoot or dataSource",
                Map.of("error", "storage_missing"));
        }

        if (dataSourceConfigured) {
            return dataSource;
        }

        Path root = dataRoot;
        if (root.toString().isBlank()) {
            throw configuration(
                "dataRoot must not be blank",
                Map.of("error", "invalid_data_root", "path", root.toString()));
        }
        Path absoluteRoot = root.toAbsolutePath().normalize();
        try {
            if (Files.exists(absoluteRoot) && !Files.isDirectory(absoluteRoot)) {
                throw configuration(
                    "dataRoot is not a directory: " + absoluteRoot,
                    Map.of("error", "invalid_data_root", "path", absoluteRoot.toString()));
            }
            Files.createDirectories(absoluteRoot);
            if (!Files.isDirectory(absoluteRoot) || !Files.isReadable(absoluteRoot)
                || !Files.isWritable(absoluteRoot)) {
                throw configuration(
                    "dataRoot must be a readable and writable directory: " + absoluteRoot,
                    Map.of("error", "invalid_data_root", "path", absoluteRoot.toString()));
            }
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw configuration(
                "Cannot prepare dataRoot '" + absoluteRoot + "': " + messageOf(e),
                Map.of("error", "invalid_data_root", "path", absoluteRoot.toString()), e);
        }

        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + absoluteRoot.resolve(DATABASE_FILE_NAME));
        return sqlite;
    }

    private void initializeSchema(DataSource dataSource) {
        try {
            new MigrationRunner(dataSource).apply(List.of());
        } catch (Exception e) {
            throw configuration(
                "Framework schema initialization failed: " + messageOf(e),
                Map.of("error", "schema_initialization_failed"), e);
        }
    }

    private ModelCapabilityProvider resolveModelCapabilityProvider() {
        return modelCapabilityProvider != null
            ? modelCapabilityProvider
            : DefaultModelCapabilityProvider.instance();
    }

    private static void validateAlias(String alias, String field) {
        if (alias == null || alias.isBlank() || !MODEL_ALIAS.matcher(alias).matches()) {
            throw configuration(
                "Invalid " + field + ": aliases must start with a letter or digit and contain only letters, digits, '.', '_' or '-'",
                Map.of("error", "invalid_model_alias", "field", field,
                    "alias", String.valueOf(alias)));
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static <T> T resolveDefault(T value, String name, Supplier<T> defaultSupplier) {
        if (value != null) {
            return value;
        }
        T resolved = defaultSupplier.get();
        log.debug("Using default for '{}': {}", name, resolved.getClass().getSimpleName());
        return resolved;
    }

    private static ConfigurationException configuration(String message, Map<String, String> details) {
        return new ConfigurationException(message, details);
    }

    private static ConfigurationException configuration(
        String message, Map<String, String> details, Throwable cause) {
        return new ConfigurationException(message, details, cause);
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
