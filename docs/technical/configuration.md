# Configuration and providers

All configuration is Java code. The builder requires exactly one of `dataRoot(Path)` or `dataSource(DataSource)`, then accepts immutable model registrations through `model(alias, ModelConfig)`.

`EndpointType.OLLAMA`, `OPENAI_COMPATIBLE`, and `DEEPSEEK` are supported. `remoteEndpoint`, `apiKey`, `remoteModelName`, context length, reasoning/thinking settings, timeout, streaming, and tool options are applied by internal provider adapters. Consumers use aliases and Magenta-owned model records; Spring AI provider classes are not part of the public contract.
