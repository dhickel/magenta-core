# Changelog — Phase 11: Auto-Configuration Module

## Date
2026-08-04

## Git Commit

5ecf8abf157733a01232db4c3872cd80174ef288

## Change Summary
Created the `mindspice-core-autoconfigure` module (package `io.mindspice.core.autoconfigure`, user-approved naming override) so embedding applications get a fully wired `TurnLoop` bean with zero additional configuration:
- `TurnLoopProperties`: `@ConfigurationProperties(prefix = "turnloop")` with 11 nested records matching handoff §12 (Models, ProviderConfig, Context, Compaction, Trimming, Turn, ToolGuard, Queue, Stream, Executor, ModelTimeout, Prompt, ResultSummarizer); `@PostConstruct` validation: model-to-provider reference and endpoint-URL violations throw `ConfigurationException`; out-of-range values clamp with WARN (bufferPercent 0–50, maxToolRounds ≥1, timeout ≥1s, bufferCapacity ≥1, maxResultChars ≥100; drainTimeout ≥0 with negative→0 defensive clamp).
- `TurnLoopAutoConfiguration`: `@AutoConfiguration` + `@EnableConfigurationProperties`; `DataSource` (`@ConditionalOnMissingBean`, in-memory SQLite); `TurnLoopBuilder` wiring 18 `ObjectProvider` SPIs + ordered `List<PromptContributor>` + properties (model configs, dataRoot, system prompt, dataSource, drainTimeout); `TurnLoop` (`@Bean(destroyMethod = "close")`, `@Primary`); separate lightweight `ModelRouter` for health checks; `TurnLoopMetrics` (`@ConditionalOnClass` Micrometer); `TurnLoopHealthIndicator`; `StreamLifecycle`.
- `StreamLifecycle`: `toSseEmitter(Flux<TurnEvent>, bufferCapacity)` with DROP_OLDEST backpressure + WARN log, `TurnEventRenderer.sse()` mapping, disconnect handling (IOException catch + completion registration), `onErrorResume` → terminal `TurnEvent.Failed`, `Long.MAX_VALUE` emitter timeout.
- `TurnLoopHealthIndicator`: Boot 4.x `org.springframework.boot.health.contributor.HealthIndicator`; pings default model via `CompletableFuture` with timeout from `modelTimeout.openaiCompatibleReadSeconds()` (fallback 30s); UP/DOWN with detail map.
- `MicrometerTurnLoopMetrics`: implements the `TurnLoopMetrics` SPI — `turnloop.turn.duration` Timer (percentile histogram, model/outcome tags), `turnloop.tool.calls` Counter, `turnloop.turns.active` Gauge, `turnloop.queue.depth` per-session Gauges.
- `AutoConfiguration.imports` with the FQN; POM: optional spring-boot-starter-web + micrometer-core, actuator compile-scope (HealthIndicator API), `Automatic-Module-Name: io.mindspice.core.autoconfigure`.

## Files
7 files under `mindspice-core-autoconfigure/` (pom.xml + 5 Java + AutoConfiguration.imports). Zero `mindspice-core` changes.

## Behavioral Impact
Adding `mindspice-core-autoconfigure` as a dependency (meta-starter, pulls `mindspice-core` transitively) yields a working `TurnLoop` bean with in-memory SQLite defaults, property-driven model configuration under `turnloop.*`, actuator health, Micrometer metrics, and an SSE adapter — no user configuration files required. `destroyMethod = "close"` ensures graceful shutdown drains the gate, cancels pending questions, releases resources, and closes models.

## Specification Impact
None. Framework contract per plan (concepts.md Features §12/§19, handoff §12 configuration surface). Accepted deviations: actuator at compile scope (health API needs compile-time import; excludable by embedding apps); separate health `ModelRouter` (facade intentionally hides Spring AI types; lazy ChatModel creation); drainTimeout negative→0 defensive clamp (strict superset of the ≥0 contract).

## Risks
- `StreamLifecycle` doesn't dispose the Flux subscription on client disconnect (events keep processing but are discarded — matches plan validation scenario 20; future hardening opportunity).
- Timer tag cardinality grows with (model × outcome) combinations — fine for typical 1–5 model deployments.
- Two `DefaultModelRouter` instances (turn path + health path) mean duplicate ChatModel caches per alias — harmless for in-memory models, noted for resource-constrained deployments.

## Follow-up Items
- Phase 12: full test suite (core types, SPI defaults, ToolLoopGuard, SerialTurnGate, DefaultToolRegistry, DefaultTurnEngine state machine, facade, integration with real Ollama, concurrency stress, autoconfigure smoke test, health indicator, SSE integration, properties validation).
- Phase 13: docs + hardening.
