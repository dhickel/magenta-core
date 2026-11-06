# Configure and route models

Magenta Core bundles four provider families: Ollama, OpenAI-compatible,
DeepSeek, and Responses. Configuration is programmatic, sealed, and validated
at construction.

## Register models

Register each model under the same alias carried by its `ModelConfig`.
Registration order defines the fallback model (first registered wins).

```java
TurnLoopBuilder builder = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", ModelConfig.ollama()
        .alias("local")
        .remoteModelName("llama3.2")
        .remoteEndpoint("http://localhost:11434")
        .contextLength(32_000)
        .thinkLevel(0)
        .build())
    .model("hosted", ModelConfig.openAiCompatible()
        .alias("hosted")
        .remoteModelName("example-model")
        .remoteEndpoint("https://models.example/v1")
        .apiKey(System.getenv("MODEL_API_KEY"))
        .contextLength(128_000)
        .thinkLevel(2)
        .build());
```

### Variant builder

Each sealed variant has a factory entry point — `ModelConfig.ollama()`,
`ModelConfig.openAiCompatible()`, `ModelConfig.deepSeek()`, and
`ModelConfig.responses()` — returning the shared, fluent `ModelConfig.Builder`.
`build()` returns the typed variant and applies exactly the validation listed
under [variant constraints](#variant-constraints), so an invalid combination
fails with the same `IllegalArgumentException` as the direct record constructor.
Unset optional components stay `null`, which keeps `effectiveThinkLevel()` at
the historical zero default. `reasoningEffort` is rejected immediately by the
`Ollama` and `DeepSeek` builders because those variants carry no such
component. The direct record constructors remain public and equivalent; pick
the clearer form at the call site.

### Alias rules

- Alias pattern: `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` (start with a letter or
  digit; letters, digits, `.`, `_`, `-`; at most 128 characters).
- The registration alias must equal `config.alias()` exactly, or `build()`
  throws `ConfigurationException` with detail key `model_alias_mismatch`.
- Duplicate registration aliases fail with `duplicate_model_alias`; a builder
  with no models fails with `no_models`.

## Resolution precedence

For every turn, the model alias is resolved in this order:

1. `TurnRequest.model()` when supplied (non-null);
2. the model alias stored for the session (updated whenever an explicit
   request selection resolves to a different alias);
3. the first registered alias.

An unknown alias — from the request or the stored session — produces
`ConfigurationException` with `details().get("error")` equal to
`unknown_model`. This is thrown synchronously by `send`/`answer` and on the
error channel by `stream`/`streamAnswer`/`drain` executions.

## Variant constraints

Every `ModelConfig` variant has the common components
`(alias, remoteModelName, remoteEndpoint, apiKey, contextLength, thinkLevel)`;
`OpenAiCompatible` and `Responses` add a `reasoningEffort` component.

| Variant | API key | Thinking level (`thinkLevel`) | Protocol |
| --- | --- | --- | --- |
| `Ollama` | must be `null` | `null` or 0–4 | Ollama chat |
| `OpenAiCompatible` | `null` or nonblank | `null` or 0–4 | OpenAI chat completions |
| `DeepSeek` | required and nonblank | `null` or 0 only | DeepSeek chat completions |
| `Responses` | required and nonblank | `null` or 0–4 | OpenAI Responses protocol |

The `Responses` variant targets any OpenAI Responses-protocol endpoint —
OpenAI's Responses API, DeepSeek V4 (`https://api.deepseek.com`), and the
OpenAI Codex endpoints. Spring AI 2.0 exposes no Responses-API chat model, so
Magenta implements the Spring AI `ChatModel` contract directly over the
official OpenAI Java SDK's `responses()` service; streaming, tool calls,
reasoning, and usage map to and from Responses items, and thinking surfaces
through the same `reasoningContent` metadata channel the chat-completions
adapters use.

Common validation (constructor-thrown `IllegalArgumentException`):

- `alias`, `remoteModelName`, `remoteEndpoint` must be nonblank and free of
  ISO control characters.
- `remoteEndpoint` must be an absolute HTTP(S) URL with a host
  (`URI`-validated). Ollama endpoints commonly end in the base URL
  (`http://localhost:11434`); OpenAI-compatible, DeepSeek, and Responses
  endpoints carry the `/v1` (or provider) path.
- `contextLength` must be positive when present; it overrides the provider
  default context window.
- `thinkLevel` outside 0–4 is rejected; `effectiveThinkLevel()` clamps a null
  value to the historical zero default.
- `reasoningEffort` is optional and normalized to lowercase; valid values are
  `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`. It overrides the
  `thinkLevel` mapping when set — use `max` for DeepSeek V4's highest
  reasoning tier.

## Credential handling

- `ModelConfig` JSON **includes `apiKey`** so configurations can round-trip.
  Treat serialized configuration as secret-bearing.
- `toString()`, framework diagnostics, and validation errors redact the key
  (`apiKey=<redacted>`).
- `Ollama` rejects any key; `DeepSeek` requires one; `OpenAiCompatible`
  permits none.

## Provider request timeout

`modelTimeout(Duration)` sets the request timeout used by internally owned
provider clients (default 360 seconds; must be positive, detail key
`invalid_model_timeout`).

## Capabilities and streaming

The default capability provider optimistically reports `STREAMING` and
`TOOL_CALLING` for every alias and does not report `VISION`. Supply a
`ModelCapabilityProvider` when the real model set differs:

```java
Map<String, Set<ModelCapability>> capabilities = Map.of(
    "local", Set.of(ModelCapability.TOOL_CALLING),
    "hosted", Set.of(ModelCapability.STREAMING, ModelCapability.TOOL_CALLING));
builder.modelCapabilityProvider(alias -> capabilities.getOrDefault(alias, Set.of()));
```

Consequences:

- If the selected alias lacks `STREAMING`, `stream` and `streamAnswer` fail
  with `ConfigurationException` (`streaming_unsupported`) **before**
  execution.
- If the alias lacks `TOOL_CALLING`, tool-enabled turns may fail or produce
  degraded results depending on the provider; the capability is advisory
  metadata used for routing decisions.

## What is deliberately not exposed

- Provider-specific Spring AI clients and options remain internal. There is no
  public way to pass arbitrary provider options (temperature defaults, top-p,
  and so on are managed inside the provider adapters).
- No runtime model registration: the model registry is an immutable snapshot at
  `build()` time.
- No auto-discovery: models are registered only through the builder, and
  capabilities only through `ModelCapabilityProvider`.

## Related

- [Configuration reference](../reference/configuration.md) — every builder
  option and default.
- [Model routing explanation](../explanation/turn-lifecycle.md#model-resolution)
  — why precedence is request → session → first registration.
- [ModelConfig protocol reference](../reference/response-protocol.md#related-sealed-adts)
  — JSON tags.
