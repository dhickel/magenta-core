# Handle durable questions with ask_user

This tutorial builds a complete question flow: the model requests input through
the framework-internal `ask_user` tool, the loop persists the exact ordered
questions, your application collects answers, and the continuation resumes the
same turn — across process restarts.

## Prerequisites

- Complete [Build and run your first turn loop](getting-started.md).
- A model that performs tool calls (Ollama `llama3.2` with `TOOL_CALLING`
  works; OpenAI-compatible and DeepSeek models also do).

## The durable interaction model

When the model calls `ask_user`, the engine:

1. normalizes the questions (nonblank, in order; a blank/empty set becomes the
   single question `"The assistant has a question for you."`);
2. creates a **dedicated interaction UUID** that is distinct from the execution
   turn ID;
3. persists the full `Questions` snapshot (execution facts included) to the
   interaction store **before** publishing it;
4. emits `TurnResponse.Questions` as the terminal outcome and moves the session
   gate to `AWAITING_ANSWER`.

From then on, the interaction is the source of truth. `answer` performs a
compare-and-set: it wins only if the exact session/interaction pair is still
`PENDING`. Stale clients — a restarted UI, a duplicate submit, a wrong session —
cannot mutate a newer interaction.

## The program

The program has two modes so you can see rehydration with your own eyes:

- `java DeployWizard ask "Production"` — creates a session and prompts the model
  for two deployment questions, then answers them.
- `java DeployWizard resume "Production"` — reopens the same database and answers
  a pending interaction created by a previous (possibly crashed) process.

<!-- tutorial-compile: DeployWizard.java -->
```java
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class DeployWizard {

    public static void main(String[] args) {
        boolean resume = args.length > 0 && args[0].equals("resume");
        String sessionId = args.length > 1 ? args[1] : "deploy-session";

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

            if (resume) {
                resumePending(loop, sessionId);
            } else {
                startAndAnswer(loop, sessionId);
            }
        }
    }

    /** Fresh flow: ask the model, collect answers, submit them. */
    private static void startAndAnswer(TurnLoop loop, String sessionId) {
        TurnResponse.Outcome outcome = loop.send(TurnRequest.of(sessionId,
            "You are deploying a service. Ask the user exactly two questions "
                + "using the ask_user tool: (1) which region to deploy to, "
                + "(2) how many instances to run."));

        if (!(outcome instanceof TurnResponse.Questions questions)) {
            System.out.println("No questions this turn: " + describe(outcome));
            return;
        }

        System.out.println("Interaction " + questions.interactionId()
            + " is pending with " + questions.questions().size() + " questions:");
        List<String> ordered = questions.questions();
        ordered.forEach(q -> System.out.println("  - " + q));

        // Exactly one nonblank answer per question, in the original order.
        List<String> answers = List.of("us-east-1", "3");

        TurnResponse.Outcome continuation = loop.answer(
            sessionId, questions.interactionId(), answers);
        System.out.println("Continuation: " + describe(continuation));
    }

    /** Restart flow: rehydrate the durable interaction and finish it. */
    private static void resumePending(TurnLoop loop, String sessionId) {
        Optional<TurnResponse.Questions> pending = loop.pendingQuestions(sessionId);
        if (pending.isEmpty()) {
            System.out.println("No pending interaction for session " + sessionId);
            return;
        }
        TurnResponse.Questions questions = pending.get();
        System.out.println("Rehydrated interaction " + questions.interactionId());
        System.out.println("Still awaiting: " + questions.questions());

        TurnResponse.Outcome continuation = loop.answer(
            sessionId, questions.interactionId(), List.of("eu-central-1", "5"));
        System.out.println("Continuation: " + describe(continuation));
    }

    private static String describe(TurnResponse.Outcome outcome) {
        return switch (outcome) {
            case TurnResponse.Message message -> "message: " + message.text();
            case TurnResponse.Questions questions ->
                "questions: " + questions.questions();
            case TurnResponse.Queued queued -> "queued: " + queued.reason();
            case TurnResponse.Interrupted interrupted -> "interrupted: " + interrupted.reason();
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
}
```

## Run the fresh flow

```bash
# compile as shown in the first tutorial, then:
java ... DeployWizard ask "Production"
```

### Expected behavior

```text
Interaction 6f3a2c1e-... is pending with 2 questions:
  - Which region should the service be deployed to?
  - How many instances should run?
Continuation: message: Deployment confirmed: us-east-1 with 3 instances.
```

The continuation message is a **new turn** — same session, same model, with the
questions and answers injected into context. Note the guarantees:

- The answer list must match the question count exactly and contain no blank
  entries; otherwise `answer` throws `IllegalArgumentException` **before** any
  model call.
- The continuation's `ExecutionInfo.origin` is
  `ExecutionOrigin.Answer(interactionId)`, so you can correlate it with the
  question turn.
- After a successful `Message` continuation, the interaction is deleted; a
  second `answer` with the same ID returns `QuestionsMissing`.

## Run the restart flow

Kill the program between the `Questions` outcome and the `answer` call (or
simply run `resume` against a session that was left pending by an earlier run):

```bash
java ... DeployWizard resume "Production"
```

### Expected behavior

```text
Rehydrated interaction 6f3a2c1e-... is pending
Still awaiting: [Which region ..., How many instances ...]
Continuation: message: Deployment confirmed: eu-central-1 with 5 instances.
```

`pendingQuestions` replayed the exact persisted snapshot — the same interaction
ID, the same ordered questions. Answering it resumed the turn as if the process
had never stopped. If a process died *after* the answers were durably recorded
but before the continuation completed, the next `drain` (or a new submission)
resumes the answered interaction before processing queued work
(see [Queue and drain work](../how-to/queue-and-drain.md)).

## Cancellation and the rejection outcomes

A caller can withdraw a pending interaction instead of answering it:

```java
TurnResponse.Outcome cancelled = loop.cancelQuestions(
    sessionId, questions.interactionId(), "User closed the prompt");
```

Because `cancelQuestions` is a CAS on the exact session/interaction pair, every
concurrent or stale submission loses cleanly and is reported through one of the
typed outcomes:

| Your submission | Underlying state | Returned outcome |
| --- | --- | --- |
| `answer` / `cancelQuestions` | interaction still `PENDING`, session idle | `Message`/`Questions` continuation, or `QuestionsCancelled` |
| `answer` / `cancelQuestions` | no such session/interaction pair | `QuestionsMissing` |
| `answer` | interaction already `ANSWERED` | `QuestionsAlreadyAnswered` |
| `cancelQuestions` | interaction already `ANSWERED` | `QuestionsAlreadyAnswered` |
| `answer` / `cancelQuestions` | interaction already `CANCELLED` | `QuestionsAlreadyCancelled` |
| `answer` / `cancelQuestions` | session is executing or owned by a drain | `TurnLoopBusyException` (thrown, not an outcome) |

Two practical consequences:

- **Stale IDs cannot cancel a newer interaction.** After the first interaction
  is answered, a late `cancelQuestions` with the old ID returns
  `QuestionsMissing` (the consumed interaction was deleted) — it never touches
  a subsequent prompt.
- **Duplicates are idempotent rejections.** The second `cancelQuestions` on the
  same interaction returns `QuestionsAlreadyCancelled` with the same
  interaction ID. The second `answer` returns `QuestionsAlreadyAnswered`.

While a session awaits answers, ordinary `send`/`stream` submissions are durably
queued with reason `AWAITING_QUESTIONS`; answer or cancel the pending
interaction before draining later work.

## What you learned

- `Questions` is a durable, ordered interaction — not a transient event.
- `answer`/`streamAnswer`/`cancelQuestions` all require the exact session and
  interaction ID; they are compare-and-set operations with typed rejection
  outcomes.
- `pendingQuestions` is read-only rehydration; it does not acknowledge delivery.
- The interaction ID is distinct from the execution turn ID; `interrupt` with a
  question turn's ID cancels the interaction (see the
  [lifecycle how-to](../how-to/lifecycle-and-cancellation.md)).

## Next steps

- [Tool use](tool-use.md) — `ask_user` is one tool among many; build your own.
- How-to: [Handle durable questions](../how-to/handle-questions.md) — exact
  procedures and edge cases.
- Explanation: [Turn lifecycle and durable state](../explanation/turn-lifecycle.md)
  — why the interaction survives restarts and how CAS races are resolved.
