# Tools and questions

Use `io.mindspice.magenta.core.ToolCallback` or the Magenta-owned `@Tool` annotation supported by `registerToolBean`. Annotate method parameters with `@ToolParam(name = "...")` so schema generation and invocation do not depend on consumer compiler parameter metadata. Provider callback and annotation types are not required by consumers.

The reserved model-facing `ask_user` tool is internal. When it is invoked, Magenta durably stores and emits an `Outcome.Questions` snapshot with a dedicated interaction UUID, ordered nonblank question strings, asking-turn `ExecutionInfo`, origin, context usage, timestamps, model, tool activities, and continuation metadata. The model-facing tool is not part of the public tool definitions.

## Answer or cancel an interaction

```java
import io.mindspice.magenta.core.TurnResponse;
import reactor.core.publisher.Flux;

import java.util.List;

TurnResponse.Questions pending = loop.pendingQuestions("session-1").orElseThrow();

TurnResponse.Outcome resumed = loop.answer(
    "session-1", pending.interactionId(), List.of("Monday", "Remote"));

// For streaming delivery, use this instead of the blocking call above:
// Flux<TurnResponse> streamed = loop.streamAnswer(
//     "session-1", pending.interactionId(), List.of("Monday", "Remote"));

TurnResponse.QuestionsCancelled cancelled = loop.cancelQuestions(
    "session-2", "interaction-id", "User stopped waiting");
```

Answers are an ordered list with exactly one nonblank value for every ordered question. Magenta persists the ordered values and injects explicit question/answer pairs into the continuation context. `pendingQuestions` is read-only and does not mark delivery. No delivery acknowledgement is required. A stale, duplicate, already answered, or already cancelled interaction ID raises `InteractionStateException`; Magenta does not replay a cached continuation outcome.
