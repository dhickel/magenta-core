# Configuration and providers

All configuration is Java code. `TurnLoopBuilder` requires exactly one of `dataRoot(Path)` or `dataSource(DataSource)`, then accepts immutable model registrations through `model(alias, ModelConfig)`. The builder has no Spring application-context dependency.

`EndpointType.OLLAMA`, `OPENAI_COMPATIBLE`, and `DEEPSEEK` are supported. `ModelConfig` carries the Magenta-owned alias, remote model name, HTTP endpoint, optional API key, endpoint type, optional context length, and optional thinking level. Provider-specific request options are applied inside the provider adapters.

The selected facade method determines blocking versus streaming behavior, and `TurnRequest` carries caller intent only. `modelTimeout(Duration)`, `queueClaimLease(Duration)`, and `drainTimeoutSeconds(long)` configure framework-owned execution, queue lease, and shutdown bounds. Queue lease values are positive and bounded; queue processing remains at-least-once across crash windows.

Consumers use aliases, `TurnRequest`, `TurnResponse`, and other Magenta-owned records. Spring AI provider classes and application configuration types are not part of the public contract.
