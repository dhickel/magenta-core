# Build and run your first turn loop

This tutorial creates a persistent Magenta Core loop, sends one blocking turn, and
handles every expected outcome with an exhaustive Java 25 pattern switch. You will
end with a runnable program and a SQLite database that survives process restarts.

## Prerequisites

- JDK 25 (`java -version` shows `25` or newer).
- Maven 3.9 or newer.
- A model endpoint. The tutorial assumes Ollama at `http://localhost:11434` with a
  locally available `llama3.2` model. Any [supported provider](../how-to/configure-models.md)
  works; only the `ModelConfig` line changes.
- No Spring Boot, servlet, or web framework is involved anywhere.

## 1. Add the dependency

```xml
<dependency>
  <groupId>io.mindspice</groupId>
  <artifactId>magenta-core</artifactId>
  <version>0.2.0</version>
</dependency>
```

The artifact includes the Ollama, OpenAI-compatible, DeepSeek, and Responses
provider implementations as transitive dependencies. Provider-specific types
never appear in your code.

## 2. Understand what you are building

Magenta Core executes one *turn* per request. A turn is the complete model/tool
loop: prompt assembly, model invocation, optional tool calls, repair, and context
accounting, ending in exactly one `TurnResponse.Outcome`. Outcomes are a sealed
Java hierarchy, so `switch` expressions over them are exhaustive by construction:
when the library adds an outcome variant, your code stops compiling until you
handle it.

Your program will:

1. Build a `TurnLoop` with one model registration and a `dataRoot` for storage.
2. Send a blocking turn with `loop.send(TurnRequest.of(...))`.
3. Switch exhaustively over the returned `TurnResponse.Outcome` and print a
   description of each possible result.

## 3. Write the program

<!-- tutorial-compile: FirstTurn.java -->
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
                TurnRequest.of("tutorial-session", "Reply with one short greeting."));

            System.out.println(describe(outcome));
        }
    }

    /** Exhaustive: every sealed outcome variant is matched; no default branch. */
    private static String describe(TurnResponse.Outcome outcome) {
        return switch (outcome) {
            case TurnResponse.Message message -> "message: " + message.text();
            case TurnResponse.Questions questions ->
                "questions (" + questions.interactionId() + "): " + questions.questions();
            case TurnResponse.Queued queued ->
                "queued (" + queued.reason() + "): " + queued.messageId();
            case TurnResponse.Interrupted interrupted ->
                "interrupted: " + interrupted.reason();
            case TurnResponse.TimedOut timedOut -> "timed out: " + timedOut.reason();
            case TurnResponse.QuestionsCancelled cancelled ->
                "questions cancelled: " + cancelled.reason();
            case TurnResponse.QuestionsMissing missing ->
                "questions missing: " + missing.interactionId();
            case TurnResponse.QuestionsAlreadyAnswered answered ->
                "already answered: " + answered.interactionId();
            case TurnResponse.QuestionsAlreadyCancelled cancelled ->
                "already cancelled: " + cancelled.interactionId();
            case TurnResponse.TurnCancelled cancelled ->
                "turn cancelled: " + cancelled.reason();
        };
    }
}
```

## 4. Build and run

```bash
mvn -q package -DskipTests
mvn -q dependency:build-classpath -Dmdep.outputFile=target/consumer-classpath.txt
rm -rf target/first-turn
javac --release 25 \
  -cp "target/magenta-core-0.2.0.jar:$(cat target/consumer-classpath.txt)" \
  -d target/first-turn docs-tutorials/FirstTurn.java
java -cp "target/first-turn:target/magenta-core-0.2.0.jar:$(cat target/consumer-classpath.txt)" \
  FirstTurn
```

(Adjust the source path to wherever you saved `FirstTurn.java`.)

### Expected result

With a working Ollama server, the program prints a greeting, for example:

```text
message: Hello! How can I help you today?
```

The exact text varies by model. What is guaranteed:

- `describe` never throws `MatchException`: the switch covers all ten outcome
  variants and the compiler verifies it.
- `./data/magenta-core.db` exists after the run. It contains the session, the
  ordered `TurnMessage` history, and audit rows, and it is reused by the next run.

### If something else is printed

The other outcomes are not errors; they are the protocol's way of reporting
expected operational states. Each one appears in the switch above:

| Outcome | Meaning in this program |
| --- | --- |
| `Queued` | The session was not idle (already executing or awaiting answers), so the request was durably queued instead of executed. |
| `Questions` | The model called `ask_user`; the interaction is durable and awaiting an answer. See the [durable questions tutorial](durable-questions.md). |
| `Interrupted` / `TimedOut` / `TurnCancelled` | The turn was stopped by an interrupt request, its time budget, or subscriber/lifecycle cancellation. |
| `Questions*` rejections | An answer/cancel targeted a stale, missing, or already-terminal interaction. |

## 5. Try one variation: non-exhaustive matching fails to compile

Replace `describe` with a switch that omits, say, `TurnResponse.Questions`:

```java
return switch (outcome) {
    case TurnResponse.Message message -> message.text();
    // no other cases
};
```

Because `TurnResponse.Outcome` is sealed, `javac` rejects this with a "not
exhaustive" error. This is the library's contract for release consumers: new
protocol variants are compile-time review points, not silent runtime surprises.

## What you learned

- `TurnLoopBuilder` is the programmatic composition root; it needs at least one
  model and exactly one storage source.
- `loop.send(...)` blocks until one expected outcome is available.
- All expected results — including queueing, questions, interruption, and
  timeout — are `TurnResponse.Outcome` values. Unexpected failures throw
  `TurnFailedException` instead.
- `dataRoot` creates a durable SQLite store; session and message state survive
  process restarts.

## Next steps

- [Stream progress and outcomes](streaming-turns.md) — the same execution as a
  cold `Flux` with content, thinking, and tool activity.
- [Handle durable questions](durable-questions.md) — the full `ask_user` flow.
- [Add tools](tool-use.md) — `ToolCallback` and annotated tool methods.
- [Queue work and persist across restarts](queueing-and-persistence.md).
- Reference: [public API](../reference/api.md), [sealed response protocol](../reference/response-protocol.md).
