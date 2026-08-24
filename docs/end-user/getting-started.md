# Getting started

Magenta Core is embedded directly in a Java application. There is no application context, YAML configuration, HTTP endpoint, or server to start.

## Build a loop

Choose exactly one persistent storage source:

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("data/magenta"))
    .model("assistant", new ModelConfig(
        "assistant", "llama3.2", "http://localhost:11434", null,
        EndpointType.OLLAMA, 32_000, 0))
    .build();
```

For caller-owned JDBC storage use `.dataSource(dataSource)` instead. Register additional models with distinct aliases before `build()`; request aliases take precedence over the stored session choice and then the first registered model is used.

## Send and stream

```java
TurnResult result = loop.send(new TurnRequest("session-1", "Summarize this", null, null, false, Map.of()));
loop.stream(new TurnRequest("session-1", "Continue", null, null, true, Map.of()))
    .doOnNext(System.out::println)
    .blockLast();
```

`TurnLoop` owns persistence, per-session serialization, queueing, interruption, question suspension, and graceful shutdown. Transport adapters can map `TurnEvent` to their own protocol.
