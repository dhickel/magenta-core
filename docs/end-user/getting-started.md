# Getting started

Magenta Core is embedded directly in a Java application. There is no application context, YAML configuration, HTTP endpoint, or server to start.

## Build a loop

Choose exactly one persistent storage source:

```java
import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;

import java.nio.file.Path;

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
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;

import java.util.List;

TurnResponse.Outcome outcome = loop.send(
    TurnRequest.of("session-1", "Summarize this"));

List<TurnResponse> stream = loop.stream(
        TurnRequest.of("session-1", "Continue"))
    .collectList()
    .block();
```

The stream is cold and contains progress records followed by one expected outcome. `TurnResponse.Message` contains the user-visible text. `Questions` provides a durable interaction ID and ordered questions; `Queued` is returned when the session is not idle.

## Questions and queued work

Use `pendingQuestions(sessionId)` after reconnect, then call `answer(sessionId, interactionId, answers)` with exactly one nonblank answer per question, or call `cancelQuestions(...)`. Use `enqueue(request)` for an explicit durable receipt and subscribe to `drain(sessionId)` when the application wants the framework to process queued work.

`TurnLoop` owns persistence, per-session serialization, queue leases, interruption, question suspension, and graceful shutdown. Embedding applications choose their own transport and rendering.
