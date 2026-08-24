# Tools and interactions

Use `io.mindspice.magenta.core.ToolCallback` or the Magenta-owned `@Tool` annotation supported by `registerToolBean`. Annotate each method parameter with `@ToolParam(name = "...")` so schema generation and invocation do not depend on consumer compiler `-parameters` metadata. Spring AI callback and annotation types are not required by consumers.

The reserved `ask_user` tool persists one or more ordered questions, emits `QuestionPending`, suspends the session, and survives reconstructing `TurnLoop` from the same database. Resume with one combined answer string:

```java
TurnResult resumed = loop.answer("session-1", "1) Monday; 2) Remote");
loop.cancelQuestion("session-1", "User cancelled");
```

Cancellation clears pending interaction state, releases the gate, preserves unrelated queued work, and records the outcome through the configured hooks.
