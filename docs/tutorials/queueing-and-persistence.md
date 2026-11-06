# Queue work, drain it, and persist across restarts

This tutorial builds the durable queue and persistence story: explicit enqueue,
oldest-first drain with typed origins, restart recovery, and both storage modes
(`dataRoot` versus caller-owned `DataSource`).

## Prerequisites

- Complete [Build and run your first turn loop](getting-started.md).
- The same model endpoint as before. Draining executes real turns.

## Why a queue

A session allows at most one authorized execution at a time. Everything else —
submissions made while a turn is running or while questions are pending — is
**durably queued**, not rejected. Queued intent survives process crashes and is
processed oldest-first by a drain. The queue is also the recovery mechanism for
interrupted answer continuations.

## The program

Run it in three modes against the same database:

- `java QueueDemo queue` — enqueue three requests and print the durable receipts.
- `java QueueDemo drain` — drain the queue and print each execution's origin.
- `java QueueDemo datasource` — same work through a caller-owned `DataSource`.

<!-- tutorial-compile: QueueDemo.java -->
```java
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class QueueDemo {

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "queue";
        switch (mode) {
            case "queue" -> {
                try (TurnLoop loop = dataRootLoop()) {
                    enqueueThree(loop);
                }
            }
            case "drain" -> {
                try (TurnLoop loop = dataRootLoop()) {
                    drainAll(loop, "job-session");
                }
            }
            case "datasource" -> {
                SQLiteDataSource dataSource = new SQLiteDataSource();
                dataSource.setUrl("jdbc:sqlite:" + Path.of("./data/caller-owned.db"));
                try (TurnLoop loop = TurnLoopBuilder.create()
                        .dataSource(dataSource)
                        .model("local", ollama())
                        .build()) {
                    enqueueThree(loop);
                    drainAll(loop, "job-session");
                }
            }
            default -> System.out.println("unknown mode: " + mode);
        }
    }

    private static TurnLoop dataRootLoop() {
        return TurnLoopBuilder.create()
            .dataRoot(Path.of("./data"))
            .model("local", ollama())
            .build();
    }

    private static ModelConfig ollama() {
        return ModelConfig.ollama()
            .alias("local")
            .remoteModelName("llama3.2")
            .remoteEndpoint("http://localhost:11434")
            .contextLength(32_000)
            .thinkLevel(0)
            .build();
    }

    private static void enqueueThree(TurnLoop loop) {
        for (int i = 1; i <= 3; i++) {
            TurnResponse.Queued receipt = loop.enqueue(
                TurnRequest.of("job-session", "Report job " + i + " status."));
            System.out.println("enqueued " + receipt.messageId()
                + " reason=" + receipt.reason());
        }
        System.out.println("queue mode done; restart the program with 'drain'");
    }

    private static void drainAll(TurnLoop loop, String sessionId) throws InterruptedException {
        List<TurnResponse> responses = loop.drain(sessionId)
            .collectList()
            .block(Duration.ofSeconds(120));
        if (responses == null) {
            System.out.println("drain produced no responses (empty queue?)");
            return;
        }
        for (TurnResponse response : responses) {
            if (response instanceof TurnResponse.Outcome outcome) {
                System.out.println("== " + describeOutcome(outcome));
            }
        }
        System.out.println("drain complete");
    }

    private static String describeOutcome(TurnResponse.Outcome outcome) {
        return switch (outcome) {
            case TurnResponse.Message message -> describeOrigin(message.execution().origin())
                + " -> " + message.text();
            case TurnResponse.Questions questions -> describeOrigin(questions.execution().origin())
                + " -> questions: " + questions.questions();
            case TurnResponse.Queued queued -> "queued: " + queued.reason();
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
            case TurnResponse.TurnCancelled cancelled -> "turn cancelled: " + cancelled.reason();
        };
    }

    private static String describeOrigin(ExecutionOrigin origin) {
        return switch (origin) {
            case ExecutionOrigin.Direct ignored -> "direct";
            case ExecutionOrigin.Answer answer -> "answer(" + answer.interactionId() + ")";
            case ExecutionOrigin.Queued queued -> "queued(" + queued.messageId() + ")";
        };
    }
}
```

## Expected behavior

```bash
java ... QueueDemo queue
# enqueued 8d3a...-1 reason=EXPLICIT
# enqueued 8d3a...-2 reason=EXPLICIT
# enqueued 8d3a...-3 reason=EXPLICIT
# queue mode done; restart the program with 'drain'

java ... QueueDemo drain
# == queued(8d3a...-1) -> Report job 1 status: all systems nominal.
# == queued(8d3a...-2) -> Report job 2 status: ...
# == queued(8d3a...-3) -> Report job 3 status: ...
# drain complete
```

Key facts demonstrated:

- The receipts printed by `queue` are durable acceptance, not completion. A
  brand-new process drains them because the queue lives in SQLite.
- Each drained execution emits its normal progress sequence and one outcome
  whose `ExecutionOrigin.Queued(messageId)` matches the receipt.
- `drain` is cold: nothing runs until you subscribe. It is also exclusive:
  a second concurrent drain of the same session fails with
  `TurnLoopBusyException`.
- Once drained, the queue is empty; a later `drain` completes immediately with
  no values.

## Implicit queueing

You usually do not call `enqueue` at all. `send` and `stream` queue
automatically when the session is not idle, and the receipt tells you why:

| `QueueReason` | When |
| --- | --- |
| `EXPLICIT` | The caller called `enqueue`. |
| `SESSION_ACTIVE` | The session is executing another turn (or owned by a drain). |
| `AWAITING_QUESTIONS` | The session is waiting for answers to a pending interaction. |

```java
// While session-1 is mid-turn elsewhere:
TurnResponse.Outcome outcome = loop.send(TurnRequest.of("session-1", "later work"));
if (outcome instanceof TurnResponse.Queued queued) {
    System.out.println(queued.reason()); // SESSION_ACTIVE
}
```

Model selection, tool selection, and request metadata are retained with the
queued request exactly as submitted (a `null` model stays "normal resolution").

## Drain order and stopping rules

A drain processes one session, oldest-first, and:

1. recovers expired claims from crashed drains;
2. resumes a durably answered interaction (crash recovery) before queued work;
3. claims the oldest request, executes it, and acknowledges it after a
   `Message` **or** `Questions` outcome;
4. continues after `Message`; **stops after `Questions`** (the session must be
   answered first) and emits the pending `Questions` outcome if one already
   exists without claiming queue work;
5. releases (rather than acknowledges) on interruption, timeout, subscriber
   cancellation, unexpected failure, or acknowledgement failure — the request
   is retried by a later drain.

The guarantee is **at-least-once**: a provider call can complete before the
durable acknowledgement. If duplicate execution would be harmful, your tool
callbacks and side effects need their own idempotency keys. Claim tokens are
internal; the facade deliberately exposes no claim or acknowledgement API.

## Persistence: two storage modes, one contract

```java
// Mode A: Magenta owns the SQLite file.
.dataRoot(Path.of("./data"))                     // -> ./data/magenta-core.db

// Mode B: the caller owns the JDBC source (never closed by the loop).
SQLiteDataSource dataSource = new SQLiteDataSource();
dataSource.setUrl("jdbc:sqlite:/srv/app/magenta.db");
.dataSource(dataSource)
```

Exactly one of the two must be configured: `build()` throws
`ConfigurationException` with detail key `storage_missing` or `storage_conflict`
otherwise. In both modes the builder validates the schema identity and installs
the framework tables (`magenta_core_*`) if needed; mode B additionally never
closes your `DataSource`.

### What survives a restart

| State | Survives | Notes |
| --- | --- | --- |
| Sessions (metadata, model selection) | yes | Recreated on demand if absent. |
| Conversation history (sealed `TurnMessage` rows) | yes | Reused to assemble context. |
| Queued requests and leases | yes | Drained after restart; expired leases recovered. |
| Pending/answered/cancelled interactions | yes | Rehydrated with `pendingQuestions`, resumed by drain. |
| Audit events | yes | Session-scoped JSON rows. |
| In-memory gate state, active turns | no | Restart means nothing is "active"; gate state starts `IDLE`. |

Two process generations may use the same database sequentially. The schema
identity check (exact ID `io.mindspice:magenta-core:sealed-adts-v1`, version 1,
exact SHA-256 of the packaged `schema.sql`) guarantees that a mismatched
library version fails fast at `build()` instead of corrupting or reusing an
incompatible database. Historical `turnloop_*` tables from earlier pre-release
builds may coexist and are never read or modified.

## What you learned

- The queue is durable caller intent; receipts prove acceptance, not completion.
- `drain` is the only queue consumer: it owns claims, leases, acknowledgement,
  and recovery, and emits typed origins per execution.
- The delivery guarantee is at-least-once; design side effects to be idempotent.
- `dataRoot` and `dataSource` are interchangeable storage modes with identical
  schema validation and restart semantics.

## Next steps

- How-to: [Queue and drain work](../how-to/queue-and-drain.md) — lease tuning
  and failure procedures.
- How-to: [Configure persistence](../how-to/configure-persistence.md) — schema
  checks and migrations.
- Tutorial: [Handle durable questions](durable-questions.md) — how interactions
  and the queue cooperate around `AWAITING_QUESTIONS`.
