# Phase 11 — Auto-Configuration Module

## Context

With the `TurnLoop` facade complete, Spring Boot auto-configuration must be built so that embedding applications can add `turnloop-autoconfigure` as a dependency and get a fully wired `TurnLoop` bean with zero additional configuration. This phase creates `TurnLoopAutoConfiguration`, `TurnLoopProperties`, `DataSource` auto-provisioning, `StreamLifecycle` SSE adapter, `TurnLoopHealthIndicator`, and `TurnLoopMetrics` Micrometer default.

## Goal

Create TurnLoopAutoConfiguration with @AutoConfiguration and @EnableConfigurationProperties; create TurnLoopProperties with @ConfigurationProperties(prefix = turnloop) and full property binding per handoff configuration surface including validation rules (model-to-provider reference URL validity range clamping); implement DataSource auto-provisioning conditional on missing bean; implement StreamLifecycle SSE adapter wrapping Flux of TurnEvent and SseEmitter with backpressure (buffer with overflow drop plus SystemNotice) and disconnect handling (cancel flux on emitter completion); implement TurnLoopHealthIndicator pinging default model; implement TurnLoopMetrics Micrometer default with duration histogram tool-call counter active-turns gauge and queue-depth gauge

## Dependencies

phase-10-facade.md

## In Scope

**Write Targets**: turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure/TurnLoopAutoConfiguration.java, turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure/TurnLoopProperties.java, turnloop-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports, turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure/StreamLifecycle.java, turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure/TurnLoopHealthIndicator.java, turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure/TurnLoopMetrics.java, turnloop-autoconfigure/pom.xml

- Create `TurnLoopProperties` (`@ConfigurationProperties(prefix = "turnloop")`) with nested configuration records matching the handoff §12 YAML schema: `Models`, `Context`, `Turn`, `Queue`, `Stream`, `Executor`, `ModelTimeout`, `Prompt`, `ResultSummarizer`, plus nested `ProviderConfig` and `ToolGuardConfig`. Every nested type is a Java record with the exact property names and types from the handoff YAML. Validation in `@PostConstruct`: model-to-provider reference check (throws `ConfigurationException` on missing default or compaction model), endpoint URL validity (throws `ConfigurationException` on invalid URL), range clamping with WARN log for `bufferPercent` (0–50), `maxToolRounds` (≥1), `timeout` (≥1s), `bufferCapacity` (≥1), `maxResultChars` (≥100). `drainTimeout` accepts 0 (no wait, force-release) without clamping.
- Create `TurnLoopAutoConfiguration` (`@AutoConfiguration`, `@EnableConfigurationProperties(TurnLoopProperties.class)`). Define beans:
  - `DataSource` (`@ConditionalOnMissingBean(DataSource.class)`): in-memory SQLite via `org.sqlite.SQLiteDataSource` with `url = "jdbc:sqlite::memory:"`
  - `TurnLoopBuilder` bean: `TurnLoopBuilder.create(applicationContext)` with all SPI beans injected via `ObjectProvider<T>` for singular SPIs (`ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, `InteractionHandler`, `InteractionStore`, `ConversationMemory`, `SessionStore`, `TokenEstimator`, `CompactionStrategy`, `CompletionDetector`, `RepairStrategy`, `TurnGate`, `MessageQueue`, `MessageQueueStore`, `AuditWriter`, `ModelCapabilityProvider`, `ThinkingExtractor`, `TurnLoopMetrics`, `TurnLifecycleListener`) and `List<T>` injection sorted by `@Order` for `PromptContributor`. Wire `TurnLoopProperties` into relevant builder methods (model configs, data root, default system prompt, compaction model alias).
  - `TurnLoop` bean (`@Bean(destroyMethod = "close")`, `@Primary`): `builder.build()`
  - `TurnLoopMetrics` Micrometer impl (`@ConditionalOnClass(MeterRegistry.class)`): registers timer, counter, and gauges per the handoff minimum metrics
  - `TurnLoopHealthIndicator` bean: pings default model via `ChatModel.call()`
  - `StreamLifecycle` bean: public class with SSE adapter method
- Create `StreamLifecycle` public class with `SseEmitter toSseEmitter(Flux<TurnEvent> events, long bufferCapacity)`. Maps each `TurnEvent` through `TurnEventRenderer.sse()`, wrapped in `onBackpressureBuffer(bufferCapacity, BufferOverflowStrategy.DROP_OLDEST, discarded -> /* emit SystemNotice(BUFFER_OVERFLOW) into a side channel or log */)`. Client disconnect: `emitter.onCompletion()` cancels the Flux subscription; `emitter.onError(Throwable)` cancels the Flux. Error recovery: `onErrorResume(throwable -> Flux.just(TurnEvent.Failed(...)))` then `emitter.complete()`. `SseEmitter` timeout set to `Long.MAX_VALUE` (SSE connections are long-lived).
- Create `TurnLoopHealthIndicator` implementing `HealthIndicator`. Obtains the default model alias from `TurnLoopProperties.models().defaultModel()`. Calls `ModelRouter.springAiChatModel(defaultAlias).call(new Prompt("health check"))` with a timeout derived from `TurnLoopProperties.modelTimeout().openaiCompatibleReadSeconds()` (fallback 30s). Returns `Health.up()` on non-null, non-empty response within timeout. Returns `Health.down()` on timeout, connection refused, empty response, or any exception. Detail map includes model alias and error message when unhealthy.
- Create `TurnLoopMetrics` Micrometer implementation of the `TurnLoopMetrics` SPI interface. Behind `@ConditionalOnClass(MeterRegistry.class)`. Registers:
  - `turnloop.turn.duration` — `Timer` (histogram), tags: `model`, `outcome` (COMPLETED/INTERRUPTED/FAILED/TIMED_OUT/ABORTED)
  - `turnloop.tool.calls` — `Counter`, tags: `tool_name`, `status` (COMPLETED/FAILED)
  - `turnloop.turns.active` — `AtomicLong` exposed as `Gauge`
  - `turnloop.queue.depth` — `Gauge` per `session_id` tag, implemented via a `ConcurrentHashMap<String, AtomicLong>` registered through `MeterRegistry.registerGauge` or `MultiGauge`
- Create `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` containing the single FQN `io.mindspice.turnloop.autoconfigure.TurnLoopAutoConfiguration`.
- Update `turnloop-autoconfigure/pom.xml`:
  - Depend on `turnloop-core` (compile scope — transitive for meta-starter)
  - Depend on `spring-boot-autoconfigure` (compile)
  - Depend on `org.xerial:sqlite-jdbc` (compile — required for DataSource auto-provisioning)
  - Optional dependency on `spring-boot-starter-web` (for `SseEmitter` in `StreamLifecycle`)
  - Optional dependency on `micrometer-core` (for `TurnLoopMetrics`)
  - Add `Automatic-Module-Name: io.mindspice.turnloop.autoconfigure` in MANIFEST
- Verify `mvn compile -pl turnloop-autoconfigure` passes.

## Out of Scope

- Integration tests (Phase 12)
- Documentation (Phase 13)
- Magenta re-integration (Phase 14)
- The `README.md` SSE controller example (Phase 13)
- Smoke test verifying `TurnLoop` bean in Spring context (Phase 12)
- `TurnLoopProperties` validation test proving `ConfigurationException` on bad config (Phase 12)

## Constraints

- `TurnLoopProperties` validation must distinguish clamp-vs-throw: range violations clamp with WARN log; missing model references and invalid URLs throw `ConfigurationException` at startup
- `DataSource` auto-provisioning must use `@ConditionalOnMissingBean(DataSource.class)`
- `StreamLifecycle` must handle client disconnect (cancel flux), backpressure (DROP_OLDEST + SystemNotice), and error recovery (onErrorResume → Failed)
- `TurnLoopHealthIndicator` must be healthy when default model responds within timeout; unhealthy on timeout, connection refused, empty response, or exception
- Auto-configuration must produce a working `TurnLoop` bean with zero external configuration files (in-memory SQLite defaults for all SPIs)
- `TurnLoopBuilder` requires Spring `ApplicationContext`; pass it via `TurnLoopAutoConfiguration`'s constructor-injected `ApplicationContext`
- `turnloop-autoconfigure` serves as `spring-boot-starter-turnloop` meta-starter: adding it as a dependency pulls in `turnloop-core` transitively
- `TurnLoop` bean must use `@Bean(destroyMethod = "close")` so graceful shutdown drains gate, cancels pending questions, and releases resources

## Implementation Steps

### 1. Update POM

Edit `turnloop-autoconfigure/pom.xml`:
- Add dependency on `turnloop-core` (compile scope, group `io.mindspice`, version from parent)
- Add dependency on `spring-boot-autoconfigure` (compile)
- Add dependency on `org.xerial:sqlite-jdbc` (compile)
- Add optional dependency on `spring-boot-starter-web` (for `SseEmitter`)
- Add optional dependency on `micrometer-core`
- Set `Automatic-Module-Name` in MANIFEST

### 2. Create TurnLoopProperties

Create `@ConfigurationProperties(prefix = "turnloop")` class with nested Java records for every subsection of the handoff §12 YAML schema. All nested types are records with `@NonNull` annotations matching their YAML defaults.

Nested records and their key properties:

```java
@ConfigurationProperties(prefix = "turnloop")
public class TurnLoopProperties {
    private String dataRoot = "${user.home}/.turnloop";
    private Models models = new Models();
    private Context context = new Context();
    private Turn turn = new Turn();
    private Queue queue = new Queue();
    private Stream stream = new Stream();
    private Executor executor = new Executor();
    private ModelTimeout modelTimeout = new ModelTimeout();
    private Prompt prompt = new Prompt();
    private ResultSummarizer resultSummarizer = new ResultSummarizer();

    public static record Models(String defaultModel, String compactionModel,
                                Map<String, ProviderConfig> providers) {
        public Models() { this("ollama-local", "ollama-local", Map.of()); }
    }
    public static record ProviderConfig(String type, String endpoint, String model,
                                         int contextLength, int thinkLevel, String apiKey) {
        public ProviderConfig() { this("ollama", "http://localhost:11434", "qwen3.6:35b", 128000, 0, ""); }
    }
    public static record Context(int bufferPercent, Compaction compaction, Trimming trimming) {
        public Context() { this(10, new Compaction(), new Trimming()); }
    }
    public static record Compaction(int minTailMessages) { public Compaction() { this(6); } }
    public static record Trimming(boolean enabled) { public Trimming() { this(true); } }
    public static record Turn(int maxToolRounds, int maxEmptyResponseRetries, Duration timeout,
                               Duration drainTimeout, ToolGuard toolGuard) {
        public Turn() { this(50, 2, Duration.ofMinutes(10), Duration.ofSeconds(30), new ToolGuard()); }
    }
    public static record ToolGuard(int identicalCallLimit, int errorWindowSize, int errorWindowLimit) {
        public ToolGuard() { this(5, 8, 5); }
    }
    public static record Queue(Duration staleClaimAge, int maxQueueSizePerSession) {
        public Queue() { this(Duration.ofMinutes(10), 100); }
    }
    public static record Stream(int bufferCapacity) { public Stream() { this(256); } }
    public static record Executor(String strategy, int turnThreads, int turnQueueSize) {
        public Executor() { this("virtual", 8, 200); }
    }
    public static record ModelTimeout(int openaiCompatibleReadSeconds) {
        public ModelTimeout() { this(360); }
    }
    public static record Prompt(String defaultSystemPrompt) {
        public Prompt() { this("You are a helpful assistant."); }
    }
    public static record ResultSummarizer(int maxResultChars) {
        public ResultSummarizer() { this(2000); }
    }

    @PostConstruct
    void validate() { /* validation logic — see Technical Guidance */ }
}
```

Validation logic in `@PostConstruct`:
- If `models.defaultModel` is not a key in `models.providers` → `throw new ConfigurationException("Default model '" + name + "' not found in providers")`
- If `models.compactionModel` is not a key in `models.providers` → `throw new ConfigurationException("Compaction model '" + name + "' not found in providers")`
- For each provider entry: if endpoint is present and not a valid URL → `throw new ConfigurationException("Invalid endpoint URL for model '" + name + "': " + url)`
- `context.bufferPercent` < 0 or > 50 → clamp to [0,50] + WARN log
- `turn.maxToolRounds` < 1 → clamp to 1 + WARN log
- `turn.timeout` < 1s → clamp to 1s + WARN log
- `turn.drainTimeout` accepts any ≥ 0 value (no clamp; 0 = no wait)
- `stream.bufferCapacity` < 1 → clamp to 1 + WARN log
- `resultSummarizer.maxResultChars` < 100 → clamp to 100 + WARN log

### 3. Create TurnLoopAutoConfiguration

Create `@AutoConfiguration` class with `@EnableConfigurationProperties(TurnLoopProperties.class)`. Constructor-inject `ApplicationContext`, `TurnLoopProperties`, and all SPI beans via `ObjectProvider<T>` for singular SPIs and `List<T>` for ordered `PromptContributor`.

Bean definitions:

```java
@AutoConfiguration
@EnableConfigurationProperties(TurnLoopProperties.class)
public class TurnLoopAutoConfiguration {

    private final ApplicationContext applicationContext;
    private final TurnLoopProperties properties;

    public TurnLoopAutoConfiguration(ApplicationContext ctx, TurnLoopProperties props) {
        this.applicationContext = ctx;
        this.properties = props;
    }

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource turnloopDataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        return ds;
    }

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
            ObjectProvider<TurnLoopMetrics> turnLoopMetrics,
            ObjectProvider<TurnLifecycleListener> turnLifecycleListener,
            List<PromptContributor> promptContributors) {

        var builder = TurnLoopBuilder.create(applicationContext);

        // Wire properties into builder: model configs, data root, system prompt
        builder.dataRoot(Path.of(properties.getDataRoot()));
        builder.defaultSystemPrompt(properties.getPrompt().defaultSystemPrompt());
        for (var entry : properties.getModels().providers().entrySet()) {
            builder.model(entry.getKey(), toModelConfig(entry.getValue()));
        }

        // Wire SPIs: ifAvailable → builder setter; absent → framework default (builder handles)
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
        turnLoopMetrics.ifAvailable(builder::turnLoopMetrics);
        turnLifecycleListener.ifAvailable(builder::turnLifecycleListener);

        // Ordered prompt contributors
        for (var contributor : promptContributors) {
            builder.promptContributor(contributor);
        }

        return builder;
    }

    @Bean(destroyMethod = "close")
    @Primary
    TurnLoop turnLoop(TurnLoopBuilder builder) {
        return builder.build();
    }

    @Bean
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    TurnLoopMetrics turnLoopMetrics(MeterRegistry meterRegistry) {
        return new MicrometerTurnLoopMetrics(meterRegistry);
    }

    @Bean
    TurnLoopHealthIndicator turnLoopHealthIndicator(TurnLoop turnLoop, TurnLoopProperties properties) {
        return new TurnLoopHealthIndicator(turnLoop, properties);
    }

    @Bean
    StreamLifecycle streamLifecycle() {
        return new StreamLifecycle();
    }
}
```

### 4. Create StreamLifecycle

Create public class with `SseEmitter toSseEmitter(Flux<TurnEvent> events, long bufferCapacity)`:

```java
public class StreamLifecycle {
    private static final Duration SSE_TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);
    private static final Logger log = LoggerFactory.getLogger(StreamLifecycle.class);

    public SseEmitter toSseEmitter(Flux<TurnEvent> events, long bufferCapacity) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());

        events
            .onBackpressureBuffer(bufferCapacity, BufferOverflowStrategy.DROP_OLDEST,
                discarded -> log.warn("Stream buffer overflow for turn {}", discarded.turnId()))
            .map(event -> SseEmitter.event()
                .name(event.getClass().getSimpleName())
                .data(TurnEventRenderer.sse().apply(event)))
            .doOnComplete(emitter::complete)
            .doOnError(emitter::completeWithError)
            .onErrorResume(throwable -> {
                var failed = new TurnEvent.Failed(/* turnId, timestamp, diagnostic, contextUsage */);
                return Flux.just(SseEmitter.event()
                    .name("Failed")
                    .data(TurnEventRenderer.sse().apply(failed)));
            })
            .subscribe(
                sseEvent -> { try { emitter.send(sseEvent); } catch (IOException e) { /* client disconnect */ } },
                error -> emitter.completeWithError(error),
                emitter::complete
            );

        emitter.onCompletion(() -> { /* Flux subscription already completes */ });
        emitter.onError(throwable -> { /* Flux subscription already errors */ });
        emitter.onTimeout(emitter::complete);

        return emitter;
    }
}
```

Key invariants: (a) `emitter.onCompletion()` and `emitter.onError()` are registration-only — the Flux terminates naturally when the SSE emitter completes; (b) `onBackpressureBuffer` uses DROP_OLDEST so the latest events survive when consumer is slow; (c) `onErrorResume` catches upstream errors and emits a terminal `Failed` event before completing; (d) `SseEmitter` timeout set to `Long.MAX_VALUE` for long-lived SSE connections.

### 5. Create TurnLoopHealthIndicator

Implement `HealthIndicator`. Resolve the default model alias from `TurnLoopProperties.models().defaultModel()`. Use `TurnLoop`'s internal `ModelRouter` (or a `ChatModel` reference obtained through the `TurnLoop` facade) to call the model with a minimal prompt. Wrap in a `CompletableFuture` with timeout from `TurnLoopProperties.modelTimeout().openaiCompatibleReadSeconds()` (fallback 30s).

```java
public class TurnLoopHealthIndicator implements HealthIndicator {
    // Obtains ChatModel from TurnLoop internal wiring or injected ModelRouter
    @Override
    public Health health() {
        try {
            var future = CompletableFuture.supplyAsync(() -> {
                var response = chatModel.call(new Prompt(new UserMessage("health check")));
                return response.getResult().getOutput().getText();
            });
            String text = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (text != null && !text.isBlank()) {
                return Health.up().withDetail("model", defaultModelAlias).build();
            }
            return Health.down().withDetail("model", defaultModelAlias)
                .withDetail("error", "empty response").build();
        } catch (TimeoutException e) {
            return Health.down().withDetail("model", defaultModelAlias)
                .withDetail("error", "timeout after " + timeoutSeconds + "s").build();
        } catch (Exception e) {
            return Health.down().withDetail("model", defaultModelAlias)
                .withDetail("error", e.getMessage()).build();
        }
    }
}
```

Note: The exact internal access pattern for obtaining a `ChatModel` from `TurnLoop` depends on whether `TurnLoop` exposes model access internally. If not, inject `ModelRouter` directly as a package-private bean or use the `TurnLoopBuilder`'s model registry. Resolve this during implementation based on the Phase 10 facade API surface.

### 6. Create TurnLoopMetrics Micrometer default

Create `MicrometerTurnLoopMetrics` implementing `TurnLoopMetrics` SPI behind `@ConditionalOnClass(MeterRegistry.class)`. Register all metrics eagerly in the constructor:

```java
public class MicrometerTurnLoopMetrics implements TurnLoopMetrics {
    private final MeterRegistry registry;
    private final AtomicLong activeTurns = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> queueDepths = new ConcurrentHashMap<>();

    public MicrometerTurnLoopMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("turnloop.turns.active", activeTurns, AtomicLong::get)
            .description("Currently active turns")
            .register(registry);
    }

    @Override
    public void recordTurnDuration(String model, String outcome, long durationMs) {
        Timer.builder("turnloop.turn.duration")
            .description("Turn execution duration")
            .tags("model", model, "outcome", outcome)
            .publishPercentileHistogram()
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordToolCall(String toolName, String status) {
        Counter.builder("turnloop.tool.calls")
            .description("Tool call count")
            .tags("tool_name", toolName, "status", status)
            .register(registry)
            .increment();
    }

    @Override
    public void setActiveTurns(long count) {
        activeTurns.set(count);
    }

    @Override
    public void setQueueDepth(String sessionId, long depth) {
        queueDepths.computeIfAbsent(sessionId, k -> {
            var g = new AtomicLong();
            Gauge.builder("turnloop.queue.depth", g, AtomicLong::get)
                .description("Queue depth per session")
                .tags("session_id", sessionId)
                .register(registry);
            return g;
        }).set(depth);
    }
}
```

### 7. Create AutoConfiguration imports file

Create `turnloop-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with exactly:

```
io.mindspice.turnloop.autoconfigure.TurnLoopAutoConfiguration
```

### 8. Compile verification

Run `mvn compile -pl turnloop-autoconfigure`. Fix any compilation errors from missing imports or API mismatches with the `turnloop-core` types built in phases 06–10.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- Handoff Appendix A for SPI interfaces referenced in AutoConfiguration wiring
- Handoff §12 for complete configuration surface (YAML schema and validation rules)
- Concepts.md Features §12 (configuration validation rules), Constraints §4 (zero-external-config build), Assumptions §4 (TurnLoopBuilder requires ApplicationContext), Assumptions §11 (meta-starter)

## Technical Guidance

- In-memory SQLite `DataSource`: `org.sqlite.SQLiteDataSource` → `setUrl("jdbc:sqlite::memory:")`. Use `@ConditionalOnMissingBean(DataSource.class)` so embedding apps can provide their own.
- `TurnLoopBuilder.create(ApplicationContext)` is the sole entry point. The autoconfigure passes the injected `ApplicationContext`.
- `TurnLoop` bean must use `destroyMethod = "close"` on `@Bean` so Spring calls `TurnLoop.close()` on context shutdown — this drains the gate, cancels pending questions, releases resources, and closes models.
- `@Primary` on `TurnLoop` bean ensures it is the default when multiple `TurnLoop` implementations exist.
- `@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")` on the metrics bean uses the string form to avoid compile-time dependency on Micrometer when it is optional.
- `StreamLifecycle` uses `SseEmitter` from `spring-boot-starter-web` (optional dependency). If `SseEmitter` is not on the classpath at runtime, `StreamLifecycle` is still a bean but the `toSseEmitter` method will throw `NoClassDefFoundError` — acceptable since embedding apps that don't use SSE won't call it.
- `AutoConfiguration.imports` file format: one fully qualified class name per line. Spring Boot 3.x uses this file (not `spring.factories`).
- `TurnLoopProperties` validation: `@PostConstruct` runs after property binding completes. Clamp with `Math.max(floor, Math.min(ceil, value))` and log at WARN with the property name, original value, and clamped value.
- `ConfigurationException` is from `io.mindspice.turnloop.ConfigurationException` (defined in Phase 07).
- The `TurnLoopHealthIndicator` needs access to the default model's `ChatModel`. If `TurnLoop` facade does not expose model access, inject `ModelRouter` as a bean from autoconfigure. The implementation detail must be resolved against the Phase 10 facade API.

## Validation

- `TurnLoopProperties.java` exists with `@ConfigurationProperties(prefix = "turnloop")`, all nested records from handoff §12, and `@PostConstruct` validation with clamp-vs-throw logic
- `TurnLoopAutoConfiguration.java` exists with `@AutoConfiguration`, `@EnableConfigurationProperties`, `DataSource` conditional bean, `TurnLoopBuilder` bean wiring all 20 SPIs, `TurnLoop` bean with `destroyMethod = "close"` and `@Primary`, `TurnLoopMetrics` conditional bean, `TurnLoopHealthIndicator` bean, `StreamLifecycle` bean
- `StreamLifecycle.java` exists with `toSseEmitter(Flux<TurnEvent>, long)` method containing `onBackpressureBuffer(DROP_OLDEST)`, client disconnect handling, and `onErrorResume`
- `TurnLoopHealthIndicator.java` exists implementing `HealthIndicator` with model ping, timeout handling, and UP/DOWN logic
- `TurnLoopMetrics` Micrometer impl exists implementing `TurnLoopMetrics` SPI with timer, counter, active-turns gauge, and queue-depth gauge
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` exists with correct FQN
- `turnloop-autoconfigure/pom.xml` depends on `turnloop-core`, `spring-boot-autoconfigure`, `sqlite-jdbc`, optional `spring-boot-starter-web`, optional `micrometer-core`
- `mvn compile -pl turnloop-autoconfigure` passes

## Exit Criteria

- Every write target file exists with the contents specified in Implementation Steps
- `mvn compile -pl turnloop-autoconfigure` passes
- `TurnLoopAutoConfiguration` wires all 20 SPI beans from context or falls back to framework defaults (via `ObjectProvider.ifAvailable`)
- `TurnLoopProperties` validates model-to-provider references and endpoint URLs (throws `ConfigurationException`), clamps out-of-range values (warns)
- `StreamLifecycle` handles backpressure, disconnect, and error recovery per the handoff contract
- `TurnLoopHealthIndicator` pings default model and returns UP/DOWN with detail map
- `TurnLoopMetrics` Micrometer implementation registers all four required metrics (`turnloop.turn.duration` histogram, `turnloop.tool.calls` counter, `turnloop.turns.active` gauge, `turnloop.queue.depth` gauge)
- `DataSource` auto-provisioning creates in-memory SQLite when no user `DataSource` bean exists
- `TurnLoop` bean is `@Primary` and `destroyMethod = "close"` is declared
- Conceptually matches handoff Phase 5 gate inventory (auto-configuration portion): `TurnLoopAutoConfiguration`, `TurnLoopProperties`, `DataSource` auto-provisioning, `StreamLifecycle`, `TurnLoopHealthIndicator`, `TurnLoopMetrics` exist and compile
- Phase 12 owns the integration-level verification: smoke test with `@SpringBootTest`, health indicator tests, SSE integration test, properties validation tests — these are not required in this phase
