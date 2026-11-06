# Configuration reference

Exact builder settings, model configuration rules, defaults, and validation
error keys. All validation happens at `build()` time unless noted; failures are
`ConfigurationException` with the `details().get("error")` key shown.

## Required builder settings

| Method | Constraint | Error keys |
| --- | --- | --- |
| `model(alias, config)` | At least one model; aliases unique; alias must equal `config.alias()`; alias matches `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. Registration order defines the fallback (first registered). | `no_models`, `duplicate_model_alias`, `model_alias_mismatch`, `invalid_model_alias`, `model_config_missing` |
| `dataRoot(Path)` / `dataSource(DataSource)` | Exactly one storage source. `dataRoot` creates `magenta-core.db`; a `DataSource` remains caller-owned (never closed). | `storage_missing`, `storage_conflict`, `invalid_data_root`, `schema_initialization_failed` |

`build()` also runs framework schema initialization and installs defaults for
every optional collaborator, then returns an immutable model snapshot.

## ModelConfig

Every variant has the components
`(alias, remoteModelName, remoteEndpoint, apiKey, contextLength, thinkLevel)`;
`OpenAiCompatible` and `Responses` add a `reasoningEffort` component. All
validation happens in the constructor (`IllegalArgumentException`).

| Variant | API key | Thinking | Protocol |
| --- | --- | --- | --- |
| `Ollama` | must be `null` | `null` or 0–4 | Ollama chat |
| `OpenAiCompatible` | `null` or nonblank | `null` or 0–4 | OpenAI chat completions |
| `DeepSeek` | required and nonblank | `null` or 0 only | DeepSeek chat completions |
| `Responses` | required and nonblank | `null` or 0–4 | OpenAI Responses protocol (OpenAI, DeepSeek V4, Codex endpoints) |

Each variant is assembled with its factory entry point —
`ModelConfig.ollama()`, `ModelConfig.openAiCompatible()`,
`ModelConfig.deepSeek()`, or `ModelConfig.responses()` — which returns the
shared `ModelConfig.Builder`. The builder exposes fluent `alias`,
`remoteModelName`, `remoteEndpoint`, `apiKey`, `contextLength`, and `thinkLevel`
setters plus `reasoningEffort` on the variants that carry it; `build()` returns
the typed variant and applies the constructor validation below. Unset optional
components stay `null`, and calling `reasoningEffort` on an `Ollama` or
`DeepSeek` builder throws `IllegalArgumentException` immediately because those
variants have no such component. Direct record construction remains public and
equivalent.

Common rules:

- `alias`, `remoteModelName`, `remoteEndpoint`: nonblank, no ISO control
  characters.
- `remoteEndpoint`: absolute HTTP(S) URL with a host (`URI`-validated).
- `contextLength`: positive when present; overrides provider default context
  window.
- `thinkLevel`: 0–4 when present; `effectiveThinkLevel()` maps `null` to 0 and
  clamps.
- `reasoningEffort`: optional, normalized to lowercase, one of `none`,
  `minimal`, `low`, `medium`, `high`, `xhigh`, `max`. When set it overrides the
  `thinkLevel` mapping (the Responses adapter emits it as `reasoning.effort`,
  the chat-completions adapter as `reasoning_effort`). The `max` tier is how
  providers such as DeepSeek V4 request their highest reasoning effort.
- `apiKey` is included in `ModelConfig` JSON (round-trip); `toString()` and
  framework diagnostics redact it (`<redacted>`).

## Selection precedence

1. `TurnRequest.model()` when non-null;
2. the model alias stored for the session (updated on explicit selection);
3. the first registered alias.

Unknown aliases fail with `ConfigurationException`, key `unknown_model`.

## Optional builder settings

| Method | Default | Contract / error key |
| --- | --- | --- |
| `defaultSystemPrompt(String)` | `You are a helpful AI assistant.` | Base system text; prompt contributors append in registration order. |
| `modelTimeout(Duration)` | 360 seconds | Provider request timeout for internally owned clients; must be positive (`invalid_model_timeout`). |
| `compactionTriggerPercent(int)` | 75 | Post-turn compaction threshold as a percentage of the model context window; values clamp to 1–99. |
| `repeatedErroredToolCallLimit(int)` | 5 | Positive consecutive same-call/same-error threshold; the Nth matching failure executes and trips (`invalid_repeated_errored_tool_call_limit`). Success and a different canonical call or failure identity reset/restart the streak. Earlier parallel results remain intact; trailing calls are blocked and paired with typed loop results. |
| `maxToolIterations(int)` | 10 | Positive maximum tool-bearing model responses per turn (`invalid_max_tool_iterations`). Responses 1 through N-1 may execute; reaching N blocks every call in that response, pairs each with a typed loop result, and starts one tool-free repair. |
| `queueClaimLease(Duration)` | 5 minutes | Queue claim lease; positive and at most one hour (`invalid_queue_claim_lease`). |
| `drainTimeoutSeconds(long)` | 30 | Graceful shutdown wait; nonnegative (`invalid_drain_timeout`); 0 skips the wait. |
| `objectMapper(ObjectMapper)` | `new ObjectMapper().findAndRegisterModules()` | Shared by default stores, tool coercion, and rendering internals. |
| `promptContributor(PromptContributor)` | none | Adds a contributor in registration order. |
| `toolAccessPolicy(ToolAccessPolicy)` | `allowAll()` | Per-turn tool filtering. |
| `turnLifecycleListener(TurnLifecycleListener)` | no-op | Exactly-once outcome/failure observation; exceptions isolated. |
| `turnLoopMetrics(TurnLoopMetrics)` | internal no-op sink | Duration/tool/active/queue-depth metrics; exceptions isolated. |
| `modelCapabilityProvider(ModelCapabilityProvider)` | optimistic `STREAMING` + `TOOL_CALLING`, no `VISION` | Drives streaming eligibility and routing. |

## Replaceable collaborators

| Method | Default implementation | See |
| --- | --- | --- |
| `toolRegistry(...)` | `DefaultToolRegistry` | [ToolRegistry SPI](spis.md#toolregistry) |
| `toolResultSummarizer(...)` | `DefaultToolResultSummarizer` | [ToolResultSummarizer](spis.md#toolresultsummarizer) |
| `interactionStore(...)` | `SqliteInteractionStore` | [InteractionStore](spis.md#interactionstore) |
| `conversationMemory(...)` | `SqliteConversationMemory` | [ConversationMemory](spis.md#conversationmemory) |
| `sessionStore(...)` | `SqliteSessionStore` | [SessionStore](spis.md#sessionstore) |
| `tokenEstimator(...)` | `DefaultTokenEstimator` | [TokenEstimator](spis.md#context-and-response-spis) |
| `compactionStrategy(...)` | `DefaultCompactionStrategy` | [CompactionStrategy](spis.md#context-and-response-spis) |
| `completionDetector(...)` | `DefaultCompletionDetector` | [CompletionDetector](spis.md#context-and-response-spis) |
| `repairStrategy(...)` | `DefaultRepairStrategy` | [RepairStrategy](spis.md#context-and-response-spis) |
| `turnGate(...)` | `SerialTurnGate` (10-minute turn timeout) | [TurnGate](spis.md#turngate) |
| `messageQueueStore(...)` | `SqliteMessageQueueStore` | [MessageQueueStore](spis.md#messagequeuestore) |
| `auditWriter(...)` | `SqliteAuditWriter` (no-op fallback) | [AuditWriter](spis.md#auditwriter) |
| `thinkingExtractor(...)` | `CompositeThinkingExtractor` | [ThinkingExtractor](spis.md#context-and-response-spis) |

Replacing a collaborator transfers its stated correctness obligations to the
caller — read the [SPI reference](spis.md) before doing so. The framework still
requires one `dataRoot`/`dataSource` and initializes the framework schema even
when every store is replaced.

## Engine constants (not builder-configurable)

| Constant | Value | Effect |
| --- | --- | --- |
| Max empty-response retries | 2 | Repair attempts for blank model responses before finalization. |
| Fallback context window | 128,000 tokens | When no context length is configured. |
| Turn gate scan interval | 30 seconds | `SerialTurnGate` timeout scanner. |

## Related

- [Public API reference](api.md)
- [Configure and route models how-to](../how-to/configure-models.md)
- [Configure persistence how-to](../how-to/configure-persistence.md)
- [Extension SPIs](spis.md)
