# Magenta Core

Magenta Core is a transport-neutral Java 25 library for embedding persistent AI
turn loops in applications. It is published as the
`io.mindspice:magenta-core` Maven library artifact; normal runtime dependencies
are resolved by Maven. Construction is programmatic, and it is not a web
framework or an application server.

## Why Magenta Core

Building an agentic pipeline takes more than sending a prompt to a model. An
application needs to assemble context, run tools, persist state, handle questions
and interruptions, and coordinate work arriving for the same session. Magenta
Core brings those pieces together so applications can add agentic workflows
without assembling and maintaining each part of the turn lifecycle themselves.

## What it provides

- A programmatically constructed `TurnLoop` with persistent conversation and
  session state, model routing, context management, and compaction.
- Tool callbacks and annotated tools, tool access policies, loop guards, and
  response repair.
- Durable questions and answers, queued requests and drain, and per-session
  serialization across execution and pending interactions.
- Blocking and streaming execution, interruption and shutdown, plus lifecycle,
  audit, and metrics hooks.
- Ollama, OpenAI-compatible, DeepSeek, and Responses model configuration
  variants. Provider implementations are runtime dependencies behind the API.

## How a turn flows

A caller builds a loop, registers models, and submits a request for a session.
The loop selects a model, coordinates access to that session, assembles the
persisted context and prompt, then invokes the provider. It executes permitted
tools as needed, persists messages and interactions, and reports progress and an
outcome. Context compaction can run after a completed message turn.

If the session is already executing or awaiting answers, a new submission is
persisted as queued work. Questions are persisted before they are returned; the
caller can retrieve, answer, or cancel them later. A subscribed drain processes
queued requests in order and stops for pending questions. Queue delivery is
at-least-once.

## API style

`TurnLoopBuilder` is the composition root; `TurnLoop` is the main facade. The
caller-facing `io.mindspice.magenta.core` API uses Magenta-owned records,
sealed algebraic data types, callbacks, exceptions, and SPIs. Spring AI models,
messages, options, and provider clients remain internal. Expected outcomes such
as a message, questions, queue receipt, interruption, or timeout are typed
values; unexpected execution failures use exceptions.

`send` blocks for a `TurnResponse.Outcome`. `stream`, `streamAnswer`, and
`drain` return cold Reactor `Flux<TurnResponse>` values; execution starts on
subscription. The response protocol carries progress and outcomes, while
failures use the stream's error channel.

## What it improves over raw Spring AI

Spring AI supplies model and provider primitives. An application assembling
those primitives still has to coordinate prompt and context persistence, tool
execution, retries and repair, a streaming response protocol, durable
interactions, queueing, session serialization, shutdown, and transport mapping.
Magenta Core provides a cohesive lifecycle for those concerns behind a narrower,
transport-neutral API. It uses Spring AI internally rather than eliminating it;
the embedding application still owns its transport and application policy.

## Quick start

Use JDK 25 and add the dependency to your Maven project:

```xml
<dependency>
  <groupId>io.mindspice</groupId>
  <artifactId>magenta-core</artifactId>
  <version>0.2.0</version>
</dependency>
```

With Ollama running at `http://localhost:11434` and `llama3.2` available, save
this as `FirstTurn.java`:

```java
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import java.nio.file.Path;

public final class FirstTurn {
    public static void main(String[] args) {
        try (TurnLoop loop = TurnLoopBuilder.create()
                .dataRoot(Path.of("./data"))
                .model("local", ModelConfig.ollama()
                    .alias("local")
                    .remoteModelName("llama3.2")
                    .remoteEndpoint("http://localhost:11434")
                    .contextLength(32_000)
                    .thinkLevel(0)
                    .build())
                .build()) {
            TurnResponse.Outcome outcome = loop.send(
                TurnRequest.of("session-1", "Hello"));
            String summary = switch (outcome) {
                case TurnResponse.Message message -> message.text();
                case TurnResponse.Questions questions -> "questions: " + questions.interactionId();
                case TurnResponse.Queued queued -> "queued: " + queued.messageId();
                case TurnResponse.Interrupted interrupted -> interrupted.reason();
                case TurnResponse.TimedOut timedOut -> timedOut.reason();
                case TurnResponse.QuestionsCancelled cancelled -> cancelled.reason();
                case TurnResponse.QuestionsMissing missing -> "questions missing";
                case TurnResponse.QuestionsAlreadyAnswered answered -> "already answered";
                case TurnResponse.QuestionsAlreadyCancelled cancelled -> "already cancelled";
                case TurnResponse.TurnCancelled cancelled -> cancelled.reason();
            };
            System.out.println(summary);
        }
    }
}
```

The exhaustive switch makes every expected outcome explicit. With a working
model endpoint, a normal response prints the model's message. Closing the loop
shuts down its owned resources. See the [first-turn tutorial](docs/tutorials/getting-started.md)
for build and run instructions.

## Configuration and persistence

Register at least one model and configure exactly one storage source. `dataRoot`
creates a SQLite database with default stores; alternatively, provide a
caller-owned `DataSource`, which the loop does not close. The builder initializes
the framework schema in either case. Model selection uses the request alias,
then the stored session alias, then the first registration.

Callers can replace supported stores and collaborators through Magenta-owned
SPIs, including conversation memory, interaction and queue stores, compaction,
and audit. A storage source and framework schema initialization are still
required when replacing stores. Model configuration JSON includes API keys for
round trips; protect serialized configuration even though diagnostics redact
keys. See the [configuration reference](docs/reference/configuration.md) and
[SPI reference](docs/reference/spis.md) for contracts and ownership.

## Transport and scope

`TurnResponseRenderer` renders a response as one JSON document. The embedding
application owns HTTP, SSE, WebSocket, or other transport framing. Magenta Core
has no Spring Boot application, application-context discovery,
auto-configuration, HTTP routes, servlet layer, Actuator endpoints, or UI.

## Documentation

- [Documentation index](docs/README.md)
- Tutorials: [first turn](docs/tutorials/getting-started.md), [streaming](docs/tutorials/streaming-turns.md), [durable questions](docs/tutorials/durable-questions.md), [tools](docs/tutorials/tool-use.md), [queueing and persistence](docs/tutorials/queueing-and-persistence.md)
- Reference: [public API](docs/reference/api.md), [configuration](docs/reference/configuration.md), [response protocol](docs/reference/response-protocol.md), [extension SPIs](docs/reference/spis.md)
- Explanation: [architecture](docs/explanation/architecture.md), [turn lifecycle](docs/explanation/turn-lifecycle.md), [compatibility](docs/explanation/compatibility.md)
- [Standalone consumer fixture](consumer-fixture/README.md)
