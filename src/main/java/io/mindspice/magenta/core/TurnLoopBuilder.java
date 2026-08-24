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
import io.mindspice.magenta.core.persistence.InMemoryInteractionStore;
import io.mindspice.magenta.core.persistence.NoOpAuditWriter;
import io.mindspice.magenta.core.persistence.SqliteConversationMemory;
import io.mindspice.magenta.core.persistence.SqliteMessageQueueStore;
import io.mindspice.magenta.core.persistence.SqliteSessionStore;
import io.mindspice.magenta.core.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Public construction path for the {@link TurnLoop} facade.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * TurnLoop turnLoop = TurnLoopBuilder.create(applicationContext)
 *     .model("gpt-4", new ModelConfig("gpt-4", "gpt-4", "https://api.openai.com", key,
 *         EndpointType.OPENAI_COMPATIBLE, 128000, 0))
 *     .dataRoot(Path.of("/data/turnloop"))
 *     .defaultSystemPrompt("You are a helpful assistant.")
 *     .build();
 * }</pre>
 *
 * <h3>Defaults</h3>
 * Every SPI slot has a production-quality default. Calling {@code build()}
 * with only a model configured produces a fully working instance backed by
 * an in-memory SQLite database.
 *
 * <h3>Auto-discovery</h3>
 * {@link PromptContributor}, {@link TurnLifecycleListener}, and
 * {@link TurnLoopMetrics} beans are auto-discovered from the
 * {@link ApplicationContext} unless explicitly set via their respective
 * setter methods.
 *
 * <h3>Thread safety</h3>
 * The builder is <strong>not</strong> thread-safe. Build on a single
 * thread and call {@link #build()} once (or multiple times to create
 * independent instances sharing the same {@link DataSource}).
 */
public final class TurnLoopBuilder {

    private static final Logger log = LoggerFactory.getLogger(TurnLoopBuilder.class);

    // ── Required ──

    private final ApplicationContext context;

    // ── Configurable fields with defaults ──

    private final Map<String, ModelConfig> modelConfigs = new LinkedHashMap<>();
    private Path dataRoot = Path.of(System.getProperty("java.io.tmpdir"), "turnloop");
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
    private final List<PromptContributor> promptContributors = new ArrayList<>();
    private ModelCapabilityProvider modelCapabilityProvider;
    private ThinkingExtractor thinkingExtractor;
    private TurnLifecycleListener turnLifecycleListener;
    private TurnLoopMetrics turnLoopMetrics;
    private ObjectMapper objectMapper;
    private long drainTimeoutSeconds = 30;
    private DataSource dataSource;

    // ── Auto-discovery tracking ──

    private final Set<String> explicitlySet = new HashSet<>();

    // ── Private constructor ──

    private TurnLoopBuilder(ApplicationContext context) {
        this.context = Objects.requireNonNull(context, "ApplicationContext must not be null");
    }

    // ── Factory ──

    /**
     * Creates a new builder backed by the given Spring {@link ApplicationContext}.
     * The context is used for auto-discovery of SPI beans and for obtaining
     * the {@link ToolCallingManager}.
     *
     * @param context the Spring application context (must not be null)
     * @return a new builder instance
     * @throws NullPointerException if {@code context} is null
     */
    public static TurnLoopBuilder create(@NonNull ApplicationContext context) {
        return new TurnLoopBuilder(context);
    }

    // ── Builder setters ──

    /** Registers a model under the given alias. At least one model is required. */
    public TurnLoopBuilder model(@NonNull String alias, @NonNull ModelConfig config) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(config, "config");
        modelConfigs.put(alias, config);
        return this;
    }

    /** Sets the root directory for SQLite database files. Created if it does not exist. */
    public TurnLoopBuilder dataRoot(@NonNull Path dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        explicitlySet.add("dataRoot");
        return this;
    }

    /** Sets the default system prompt used when the caller does not supply one. */
    public TurnLoopBuilder defaultSystemPrompt(@NonNull String prompt) {
        this.defaultSystemPrompt = Objects.requireNonNull(prompt, "prompt");
        explicitlySet.add("defaultSystemPrompt");
        return this;
    }

    public TurnLoopBuilder toolRegistry(@NonNull ToolRegistry registry) {
        this.toolRegistry = Objects.requireNonNull(registry, "registry");
        explicitlySet.add("toolRegistry");
        return this;
    }

    public TurnLoopBuilder toolAccessPolicy(@NonNull ToolAccessPolicy policy) {
        this.toolAccessPolicy = Objects.requireNonNull(policy, "policy");
        explicitlySet.add("toolAccessPolicy");
        return this;
    }

    public TurnLoopBuilder toolResultSummarizer(@NonNull ToolResultSummarizer summarizer) {
        this.toolResultSummarizer = Objects.requireNonNull(summarizer, "summarizer");
        explicitlySet.add("toolResultSummarizer");
        return this;
    }

    public TurnLoopBuilder interactionHandler(@NonNull InteractionHandler handler) {
        this.interactionHandler = Objects.requireNonNull(handler, "handler");
        explicitlySet.add("interactionHandler");
        return this;
    }

    public TurnLoopBuilder interactionStore(@NonNull InteractionStore store) {
        this.interactionStore = Objects.requireNonNull(store, "store");
        explicitlySet.add("interactionStore");
        return this;
    }

    public TurnLoopBuilder conversationMemory(@NonNull ConversationMemory memory) {
        this.conversationMemory = Objects.requireNonNull(memory, "memory");
        explicitlySet.add("conversationMemory");
        return this;
    }

    public TurnLoopBuilder sessionStore(@NonNull SessionStore store) {
        this.sessionStore = Objects.requireNonNull(store, "store");
        explicitlySet.add("sessionStore");
        return this;
    }

    public TurnLoopBuilder tokenEstimator(@NonNull TokenEstimator estimator) {
        this.tokenEstimator = Objects.requireNonNull(estimator, "estimator");
        explicitlySet.add("tokenEstimator");
        return this;
    }

    public TurnLoopBuilder compactionStrategy(@NonNull CompactionStrategy strategy) {
        this.compactionStrategy = Objects.requireNonNull(strategy, "strategy");
        explicitlySet.add("compactionStrategy");
        return this;
    }

    public TurnLoopBuilder completionDetector(@NonNull CompletionDetector detector) {
        this.completionDetector = Objects.requireNonNull(detector, "detector");
        explicitlySet.add("completionDetector");
        return this;
    }

    public TurnLoopBuilder repairStrategy(@NonNull RepairStrategy strategy) {
        this.repairStrategy = Objects.requireNonNull(strategy, "strategy");
        explicitlySet.add("repairStrategy");
        return this;
    }

    public TurnLoopBuilder turnGate(@NonNull TurnGate gate) {
        this.turnGate = Objects.requireNonNull(gate, "gate");
        explicitlySet.add("turnGate");
        return this;
    }

    public TurnLoopBuilder messageQueue(@NonNull MessageQueue queue) {
        this.messageQueue = Objects.requireNonNull(queue, "queue");
        explicitlySet.add("messageQueue");
        return this;
    }

    public TurnLoopBuilder messageQueueStore(@NonNull MessageQueueStore store) {
        this.messageQueueStore = Objects.requireNonNull(store, "store");
        explicitlySet.add("messageQueueStore");
        return this;
    }

    public TurnLoopBuilder auditWriter(@NonNull AuditWriter writer) {
        this.auditWriter = Objects.requireNonNull(writer, "writer");
        explicitlySet.add("auditWriter");
        return this;
    }

    /**
     * Adds a prompt contributor. This method is cumulative — each call
     * appends to the contributor list. Contributors are invoked in
     * registration order (then sorted by {@code @Order}).
     */
    public TurnLoopBuilder promptContributor(@NonNull PromptContributor contributor) {
        promptContributors.add(Objects.requireNonNull(contributor, "contributor"));
        explicitlySet.add("promptContributor");
        return this;
    }

    public TurnLoopBuilder modelCapabilityProvider(@NonNull ModelCapabilityProvider provider) {
        this.modelCapabilityProvider = Objects.requireNonNull(provider, "provider");
        explicitlySet.add("modelCapabilityProvider");
        return this;
    }

    public TurnLoopBuilder thinkingExtractor(@NonNull ThinkingExtractor extractor) {
        this.thinkingExtractor = Objects.requireNonNull(extractor, "extractor");
        explicitlySet.add("thinkingExtractor");
        return this;
    }

    public TurnLoopBuilder turnLifecycleListener(@NonNull TurnLifecycleListener listener) {
        this.turnLifecycleListener = Objects.requireNonNull(listener, "listener");
        explicitlySet.add("turnLifecycleListener");
        return this;
    }

    public TurnLoopBuilder objectMapper(@NonNull ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        explicitlySet.add("objectMapper");
        return this;
    }

    /** Sets the drain timeout for graceful shutdown in seconds (default 30). */
    public TurnLoopBuilder drainTimeoutSeconds(long seconds) {
        this.drainTimeoutSeconds = Math.max(0, seconds);
        explicitlySet.add("drainTimeoutSeconds");
        return this;
    }

    /** Sets the JDBC {@link DataSource}. Defaults to an in-memory SQLite database. */
    public TurnLoopBuilder dataSource(@NonNull DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        explicitlySet.add("dataSource");
        return this;
    }

    // ── build() ──

    /**
     * Validates configuration and constructs a fully wired {@link TurnLoop}.
     *
     * <h3>Validation checks (in order)</h3>
     * <ol>
     *   <li>At least one model configured</li>
     *   <li>Default model exists in the model map</li>
     *   <li>ModelCapabilityProvider set or optimistic default if any model
     *       has streaming enabled</li>
     *   <li>InteractionHandler required when {@code ask_user} tool is registered</li>
     *   <li>{@code dataRoot} exists or can be created</li>
     *   <li>Schema migrations applied</li>
     * </ol>
     *
     * <p>Calling {@code build()} multiple times creates independent instances
     * sharing the same {@link DataSource}.</p>
     *
     * @return a fully wired {@code TurnLoop} instance
     * @throws ConfigurationException if validation fails
     */
    public TurnLoop build() {
        // ── 1. At least one model ──
        if (modelConfigs.isEmpty()) {
            throw new ConfigurationException("At least one model must be configured",
                Map.of("error", "no_models"));
        }

        // ── 2. Default model ──
        String defaultModel = resolveDefaultModelAlias();
        if (!modelConfigs.containsKey(defaultModel)) {
            throw new ConfigurationException(
                "Default model '" + defaultModel + "' not found in configured models",
                Map.of("error", "default_model_missing", "alias", defaultModel));
        }

        // ── 3. Auto-discover from ApplicationContext ──
        autoDiscoverBeans();

        // ── 4. ModelCapabilityProvider ──
        ModelCapabilityProvider capabilityProvider = resolveModelCapabilityProvider();

        // ── 5. InteractionHandler check ──
        if (interactionHandler == null && hasAskUserTool()) {
            throw new ConfigurationException(
                "interactionHandler is required when the ask_user tool is registered",
                Map.of("error", "missing_interaction_handler"));
        }

        // ── 6. dataRoot ──
        try {
            Files.createDirectories(dataRoot);
        } catch (Exception e) {
            throw new ConfigurationException(
                "Cannot create data root: " + dataRoot, Map.of("error", "data_root", "path", dataRoot.toString()));
        }

        // ── 7. DataSource ──
        DataSource ds = resolveDataSource();

        // ── 8. Run MigrationRunner ──
        runMigrations(ds);

        // ── 9. Resolve all defaults ──
        ToolRegistry registry = resolveDefault(toolRegistry, "toolRegistry",
            DefaultToolRegistry::new);
        ToolAccessPolicy accessPolicy = toolAccessPolicy;
        ToolResultSummarizer summarizer = resolveDefault(toolResultSummarizer, "toolResultSummarizer",
            DefaultToolResultSummarizer::new);
        InteractionStore iStore = resolveDefault(interactionStore, "interactionStore",
            InMemoryInteractionStore::new);
        ConversationMemory cMemory = resolveDefault(conversationMemory, "conversationMemory",
            () -> new SqliteConversationMemory(ds));
        SessionStore sStore = resolveDefault(sessionStore, "sessionStore",
            () -> new SqliteSessionStore(ds));
        TokenEstimator tEstimator = resolveDefault(tokenEstimator, "tokenEstimator",
            DefaultTokenEstimator::new);
        CompactionStrategy cStrategy = resolveDefault(compactionStrategy, "compactionStrategy",
            DefaultCompactionStrategy::new);
        CompletionDetector cDetector = resolveDefault(completionDetector, "completionDetector",
            DefaultCompletionDetector::instance);
        RepairStrategy rStrategy = resolveDefault(repairStrategy, "repairStrategy",
            DefaultRepairStrategy::instance);
        TurnGate gate = resolveDefault(turnGate, "turnGate",
            () -> new SerialTurnGate(Duration.ofMinutes(10)));
        MessageQueueStore mqStore = resolveDefault(messageQueueStore, "messageQueueStore",
            () -> new SqliteMessageQueueStore(ds));
        MessageQueue mQueue = resolveDefault(messageQueue, "messageQueue",
            () -> new DefaultMessageQueue(mqStore));
        AuditWriter aWriter = resolveDefault(auditWriter, "auditWriter",
            NoOpAuditWriter::instance);
        ThinkingExtractor tExtractor = resolveDefault(thinkingExtractor, "thinkingExtractor",
            CompositeThinkingExtractor::new);
        TurnLifecycleListener lifecycleListener = resolveDefault(turnLifecycleListener,
            "turnLifecycleListener", () -> result -> {});
        ObjectMapper mapper = resolveDefault(objectMapper, "objectMapper", ObjectMapper::new);

        // ── 10. Build TurnExecutor ──
        TurnExecutor executor = new DefaultTurnExecutor();

        // ── 11. Build ActiveTurnRegistry ──
        ActiveTurnRegistry activeTurnRegistry = new ConcurrentActiveTurnRegistry();

        // ── 12. Build ModelRouter ──
        ModelRouter router = DefaultModelRouter.fromContext(
            Map.copyOf(modelConfigs), context);

        // ── 13. Build DefaultTurnEngine ──
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

        // ── 14. Build DefaultTurnLoop ──
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
            capabilityProvider,
            lifecycleListener,
            turnLoopMetrics,
            mapper,
            defaultSystemPrompt,
            defaultModel,
            drainTimeoutSeconds
        );
    }

    // ── Internal helpers ──

    private String resolveDefaultModelAlias() {
        // Prefer the alias literally named "default"
        if (modelConfigs.containsKey("default")) {
            return "default";
        }
        // Otherwise use the first registered model
        return modelConfigs.keySet().iterator().next();
    }

    private ModelCapabilityProvider resolveModelCapabilityProvider() {
        if (modelCapabilityProvider != null) {
            return modelCapabilityProvider;
        }
        // Check if any model config requests streaming
        boolean streamingRequested = modelConfigs.values().stream()
            .anyMatch(mc -> mc.endpointType() != EndpointType.OLLAMA);
        if (streamingRequested) {
            log.warn("No ModelCapabilityProvider configured — using optimistic default. "
                + "Streaming capability checks will assume all models support STREAMING and TOOL_CALLING. "
                + "Set a real ModelCapabilityProvider via TurnLoopBuilder.modelCapabilityProvider() "
                + "for accurate capability detection.");
            return DefaultModelCapabilityProvider.instance();
        }
        return DefaultModelCapabilityProvider.instance();
    }

    private boolean hasAskUserTool() {
        // The default ToolRegistry always registers ask_user as a framework-internal
        // tool. Custom registries may or may not include it. We check the resolved
        // tools from a synthetic context — resolveForTurn() includes both public and
        // framework-internal tools.
        ToolRegistry registry = toolRegistry != null ? toolRegistry : new DefaultToolRegistry();
        try {
            return registry.resolveForTurn(
                new TurnContext(TurnRequest.of("_", "_"), "_", "_"))
                .stream()
                .anyMatch(tc -> "ask_user".equals(tc.getToolDefinition().name()));
        } catch (Exception e) {
            // If resolution fails, assume ask_user is present (safe default)
            return true;
        }
    }

    private DataSource resolveDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        log.info("No DataSource configured — using in-memory SQLite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:file:" + dataRoot.resolve("turnloop.db").toAbsolutePath()
            + "?mode=memory&cache=shared");
        return ds;
    }

    private void runMigrations(DataSource ds) {
        try {
            MigrationRunner runner = new MigrationRunner(ds);
            // Individual stores self-migrate in their constructors.
            // The MigrationRunner ensures the turnloop_schema_version table
            // exists; we pass an empty list since no additional migrations
            // are needed for the default stores.
            runner.apply(List.of());
            log.info("Schema migrations applied successfully");
        } catch (Exception e) {
            throw new ConfigurationException(
                "Schema migration failed: " + e.getMessage(),
                Map.of("error", "migration_failed"));
        }
    }

    /**
     * Auto-discovers {@link PromptContributor}, {@link TurnLifecycleListener},
     * and {@link TurnLoopMetrics} beans from the {@link ApplicationContext}.
     * Only runs for slots that were never explicitly set.
     */
    private void autoDiscoverBeans() {
        // PromptContributor beans (cumulative — add to list if no explicit call)
        if (!explicitlySet.contains("promptContributor")) {
            try {
                var providers = context.getBeanProvider(PromptContributor.class);
                providers.orderedStream().forEach(pc -> {
                    log.debug("Auto-discovered PromptContributor: {}", pc.getClass().getName());
                    promptContributors.add(pc);
                });
            } catch (Exception e) {
                log.debug("No PromptContributor beans found in context");
            }
        }

        // TurnLifecycleListener (use highest-priority if not explicitly set)
        if (!explicitlySet.contains("turnLifecycleListener")) {
            try {
                turnLifecycleListener = context.getBeanProvider(TurnLifecycleListener.class)
                    .getIfAvailable();
                if (turnLifecycleListener != null) {
                    log.debug("Auto-discovered TurnLifecycleListener: {}",
                        turnLifecycleListener.getClass().getName());
                }
            } catch (Exception e) {
                log.debug("No TurnLifecycleListener bean found in context");
            }
        }

        // TurnLoopMetrics (use highest-priority if not explicitly set)
        if (!explicitlySet.contains("turnLoopMetrics")) {
            try {
                turnLoopMetrics = context.getBeanProvider(TurnLoopMetrics.class)
                    .getIfAvailable();
                if (turnLoopMetrics != null) {
                    log.debug("Auto-discovered TurnLoopMetrics: {}",
                        turnLoopMetrics.getClass().getName());
                }
            } catch (Exception e) {
                log.debug("No TurnLoopMetrics bean found in context");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolveDefault(T value, String name, java.util.function.Supplier<T> defaultSupplier) {
        if (value != null) {
            return value;
        }
        T def = defaultSupplier.get();
        log.debug("Using default for '{}': {}", name, def.getClass().getSimpleName());
        return def;
    }
}
