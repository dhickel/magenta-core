# Magenta Core

Magenta Core is a transport-neutral Java 25 library for embedding persistent AI turn loops. It is one `io.mindspice:magenta-core` JAR with programmatic construction and no Spring Boot application, HTTP, servlet, Actuator, or UI surface.

## Dependency

```xml
<dependency>
  <groupId>io.mindspice</groupId>
  <artifactId>magenta-core</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Ollama, OpenAI-compatible, and DeepSeek provider implementations are bundled as runtime dependencies. Provider-specific types stay behind the Magenta API.

## Minimal construction

```java
import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;

import java.nio.file.Path;

try (TurnLoop loop = TurnLoopBuilder.create()
        .dataRoot(Path.of("./data"))
        .model("local", new ModelConfig(
            "local", "llama3.2", "http://localhost:11434", null,
            EndpointType.OLLAMA, 32_000, 0))
        .build()) {
    TurnResponse.Outcome outcome = loop.send(TurnRequest.of("session-1", "Hello"));
    String summary = switch (outcome) {
        case TurnResponse.Message message -> message.text();
        case TurnResponse.Questions questions -> "questions: " + questions.interactionId();
        case TurnResponse.Queued queued -> "queued: " + queued.messageId();
        case TurnResponse.Interrupted interrupted -> interrupted.reason();
        case TurnResponse.TimedOut timedOut -> timedOut.reason();
        case TurnResponse.QuestionsCancelled cancelled -> cancelled.reason();
        case TurnResponse.TurnCancelled cancelled -> cancelled.reason();
    };
    System.out.println(summary);
}
```

`send` returns `TurnResponse.Outcome`. `stream`, `streamAnswer`, and `drain` return cold `Flux<TurnResponse>` values containing progress followed by expected outcomes. Use `dataSource(DataSource)` instead of `dataRoot(Path)` when the caller owns JDBC storage; configure exactly one storage source.

See [Getting started](docs/end-user/getting-started.md), the [TurnLoop API](docs/api/turn-loop.md), the [response stream](docs/api/streaming.md), and [technical architecture](docs/technical/architecture.md).
