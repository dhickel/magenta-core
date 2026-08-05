package io.mindspice.core.autoconfigure;

import io.mindspice.core.ConfigurationException;
import io.mindspice.core.EndpointType;
import io.mindspice.core.ModelConfig;
import io.mindspice.core.TurnLoop;
import io.mindspice.core.TurnLoopBuilder;
import io.mindspice.core.internal.DefaultModelRouter;
import io.mindspice.core.internal.ModelRouter;
import io.mindspice.core.spi.AuditWriter;
import io.mindspice.core.spi.CompactionStrategy;
import io.mindspice.core.spi.CompletionDetector;
import io.mindspice.core.spi.ConversationMemory;
import io.mindspice.core.spi.InteractionHandler;
import io.mindspice.core.spi.InteractionStore;
import io.mindspice.core.spi.MessageQueue;
import io.mindspice.core.spi.MessageQueueStore;
import io.mindspice.core.spi.ModelCapabilityProvider;
import io.mindspice.core.spi.PromptContributor;
import io.mindspice.core.spi.RepairStrategy;
import io.mindspice.core.spi.SessionStore;
import io.mindspice.core.spi.ThinkingExtractor;
import io.mindspice.core.spi.TokenEstimator;
import io.mindspice.core.spi.ToolAccessPolicy;
import io.mindspice.core.spi.ToolRegistry;
import io.mindspice.core.spi.ToolResultSummarizer;
import io.mindspice.core.spi.TurnGate;
import io.mindspice.core.spi.TurnLifecycleListener;
import io.mindspice.core.spi.TurnLoopMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot auto-configuration for the {@code mindspice-core} turn-loop
 * framework. Produces a fully wired {@link TurnLoop} bean backed by in-memory
 * SQLite with zero external configuration required.
 *
 * <p>Embedding applications enable this module by adding
 * {@code mindspice-core-autoconfigure} as a dependency.
 * Override any SPI by declaring a bean of the corresponding type;
 * each slot uses {@code @ConditionalOnMissingBean} or
 * {@code ObjectProvider.ifAvailable()} semantics.</p>
 *
 * <h3>Bean inventory</h3>
 * <ul>
 *   <li>{@link DataSource} — in-memory SQLite when no user {@code DataSource}
 *       exists</li>
 *   <li>{@link TurnLoopBuilder} — wires properties + all 19 SPI
 *       {@code ObjectProvider}/{@code List} parameters</li>
 *   <li>{@link TurnLoop} — {@code @Primary}, {@code destroyMethod = "close"}</li>
 *   <li>{@link ModelRouter} — separate lightweight router for health checks</li>
 *   <li>{@link TurnLoopMetrics} — Micrometer implementation when
 *       {@code MeterRegistry} is on the classpath</li>
 *   <li>{@link TurnLoopHealthIndicator} — actuator health indicator</li>
 *   <li>{@link StreamLifecycle} — SSE adapter utility</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(TurnLoopProperties.class)
public class TurnLoopAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TurnLoopAutoConfiguration.class);

    private final ApplicationContext applicationContext;
    private final TurnLoopProperties properties;

    public TurnLoopAutoConfiguration(ApplicationContext ctx, TurnLoopProperties props) {
        this.applicationContext = ctx;
        this.properties = props;
    }

    // ── DataSource ──

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource turnloopDataSource() {
        log.info("No DataSource bean found — auto-provisioning in-memory SQLite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        return ds;
    }

    // ── TurnLoopBuilder ──

    @Bean
    TurnLoopBuilder turnLoopBuilder(
            ObjectProvider<ToolRegistry> toolRegistry,
            ObjectProvider<ToolAccessPolicy> toolAccessPolicy,
            ObjectProvider<ToolResultSummarizer> toolResultSummarizer,
            ObjectProvider<InteractionHandler> interactionHandler,
            ObjectProvider<InteractionStore> interactionStore,
            ObjectProvider<ConversationMemory> conversationMemory,
            ObjectProvider<SessionStore> sessionStore,
            ObjectProvider<TokenEstimator> tokenEstimator,
            ObjectProvider<CompactionStrategy> compactionStrategy,
            ObjectProvider<CompletionDetector> completionDetector,
            ObjectProvider<RepairStrategy> repairStrategy,
            ObjectProvider<TurnGate> turnGate,
            ObjectProvider<MessageQueue> messageQueue,
            ObjectProvider<MessageQueueStore> messageQueueStore,
            ObjectProvider<AuditWriter> auditWriter,
            ObjectProvider<ModelCapabilityProvider> modelCapabilityProvider,
            ObjectProvider<ThinkingExtractor> thinkingExtractor,
            ObjectProvider<TurnLifecycleListener> turnLifecycleListener,
            List<PromptContributor> promptContributors,
            DataSource dataSource) {

        TurnLoopBuilder builder = TurnLoopBuilder.create(applicationContext);

        // ── Properties → builder ──

        builder.dataRoot(Path.of(properties.getDataRoot()));
        builder.defaultSystemPrompt(properties.getPrompt().defaultSystemPrompt());
        builder.dataSource(dataSource);
        builder.drainTimeoutSeconds(properties.getTurn().drainTimeout().getSeconds());

        // Wire model configs from properties
        Map<String, TurnLoopProperties.ProviderConfig> providers =
            properties.getModels().providers();
        if (providers.isEmpty()) {
            throw new ConfigurationException(
                "At least one model provider must be configured",
                Map.of("error", "no_providers"));
        }
        for (var entry : providers.entrySet()) {
            builder.model(entry.getKey(), toModelConfig(entry.getKey(), entry.getValue()));
        }

        // ── SPI wiring (ifAvailable → builder; absent → framework default) ──

        toolRegistry.ifAvailable(builder::toolRegistry);
        toolAccessPolicy.ifAvailable(builder::toolAccessPolicy);
        toolResultSummarizer.ifAvailable(builder::toolResultSummarizer);
        interactionHandler.ifAvailable(builder::interactionHandler);
        interactionStore.ifAvailable(builder::interactionStore);
        conversationMemory.ifAvailable(builder::conversationMemory);
        sessionStore.ifAvailable(builder::sessionStore);
        tokenEstimator.ifAvailable(builder::tokenEstimator);
        compactionStrategy.ifAvailable(builder::compactionStrategy);
        completionDetector.ifAvailable(builder::completionDetector);
        repairStrategy.ifAvailable(builder::repairStrategy);
        turnGate.ifAvailable(builder::turnGate);
        messageQueue.ifAvailable(builder::messageQueue);
        messageQueueStore.ifAvailable(builder::messageQueueStore);
        auditWriter.ifAvailable(builder::auditWriter);
        modelCapabilityProvider.ifAvailable(builder::modelCapabilityProvider);
        thinkingExtractor.ifAvailable(builder::thinkingExtractor);
        turnLifecycleListener.ifAvailable(builder::turnLifecycleListener);
        // TurnLoopMetrics: no setter on builder; auto-discovered from context

        // Ordered PromptContributor list
        for (var contributor : promptContributors) {
            builder.promptContributor(contributor);
        }

        return builder;
    }

    // ── TurnLoop (primary facade bean) ──

    @Bean(destroyMethod = "close")
    @Primary
    TurnLoop turnLoop(TurnLoopBuilder builder) {
        return builder.build();
    }

    // ── ModelRouter (lightweight, for health checks) ──
    //
    // Resolution: the TurnLoop facade does not expose ChatModel access
    // (by design — Spring AI types are internal). The health indicator
    // needs a way to ping the default model. We construct a separate
    // lightweight ModelRouter from the same configuration properties.
    // This creates a second, independent ChatModel cache per model alias,
    // which is acceptable for the infrequent health-check use case.
    // The TurnLoopBuilder's internal ModelRouter is unaware of this one.

    @Bean
    ModelRouter modelRouter() {
        TurnLoopProperties.Models models = properties.getModels();
        Map<String, ModelConfig> configs = new HashMap<>();
        for (var entry : models.providers().entrySet()) {
            configs.put(entry.getKey(), toModelConfig(entry.getKey(), entry.getValue()));
        }
        if (configs.isEmpty()) {
            throw new ConfigurationException(
                "At least one model provider must be configured",
                Map.of("error", "no_providers"));
        }
        return DefaultModelRouter.fromContext(configs, applicationContext);
    }

    // ── Metrics (Micrometer) ──

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    TurnLoopMetrics turnLoopMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        log.info("Micrometer MeterRegistry detected — registering TurnLoopMetrics");
        return new MicrometerTurnLoopMetrics(meterRegistry);
    }

    // ── Health indicator ──

    @Bean
    TurnLoopHealthIndicator turnLoopHealthIndicator(ModelRouter modelRouter) {
        return new TurnLoopHealthIndicator(
            modelRouter,
            properties.getModels().defaultModel(),
            properties.getModelTimeout().openaiCompatibleReadSeconds());
    }

    // ── StreamLifecycle ──

    @Bean
    StreamLifecycle streamLifecycle() {
        return new StreamLifecycle();
    }

    // ── Helpers ──

    static ModelConfig toModelConfig(String alias, TurnLoopProperties.ProviderConfig pc) {
        return new ModelConfig(
            alias,
            pc.model(),
            pc.endpoint(),
            pc.apiKey() != null && !pc.apiKey().isBlank() ? pc.apiKey() : null,
            EndpointType.valueOf(pc.type().toUpperCase()),
            pc.contextLength(),
            pc.thinkLevel()
        );
    }
}
