# Magenta Core

Magenta Core is a transport-neutral Java 25 library for building persistent Spring AI turn loops without Spring Boot application wiring.

## Dependency

```xml
<dependency>
  <groupId>io.mindspice</groupId>
  <artifactId>magenta-core</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

The artifact bundles Ollama, OpenAI-compatible, and DeepSeek Spring AI providers transitively. It does not provide HTTP, servlet, web, Actuator, or Spring Boot auto-configuration support.

## Minimal construction

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", new ModelConfig(
        "local", "llama3.2", "http://localhost:11434", null,
        EndpointType.OLLAMA, 32_000, 0))
    .build();

TurnResult result = loop.send(TurnRequest.of("session-1", "Hello"));
System.out.println(result.message());
loop.close();
```

Use `dataSource(DataSource)` instead of `dataRoot(Path)` when the caller owns JDBC storage. Exactly one storage source is required. Sessions are created lazily from caller-supplied IDs, and the model registry is immutable after `build()`.

See [Getting started](docs/end-user/getting-started.md), [turn-loop API](docs/api/turn-loop.md), and [technical architecture](docs/technical/architecture.md).
