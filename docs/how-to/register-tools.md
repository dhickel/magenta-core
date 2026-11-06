# Register tool callbacks

Magenta tools use Magenta-owned definitions and `ToolResult`; caller code never
implements Spring AI callbacks. This page is the full registration procedure,
including schema rules, failure mapping, and per-turn selection.

## Register an explicit callback

```java
import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;
import io.mindspice.magenta.core.ToolResult;

ToolCallback lookup = new ToolCallback() {
    @Override
    public ToolDefinition getToolDefinition() {
        return new ToolDefinition(
            "lookup",
            "Looks up an item by identifier",
            "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},"
                + "\"required\":[\"id\"]}");
    }

    @Override
    public ToolResult call(String toolInput) {
        return new ToolResult.Success("item found");
    }
};

loop.tools().register(lookup);
```

`register` rejects duplicate public names with `ToolException`. The
`inputSchema` string must be a valid JSON Schema document; the framework passes
it to the provider verbatim as the tool's parameter schema. `call(String
toolInput)` receives the raw JSON arguments string from the model — parse it
with your own Jackson mapper or a `ToolArgumentCoercionModule`-aware mapper.

## Typed results

```java
return new ToolResult.Success("12 units available");          // completed
return new ToolResult.Failure("Unknown SKU",
    ToolFailureCategory.NOT_FOUND);                            // typed failure
```

| `ToolResult` | Meaning |
| --- | --- |
| `Success(output)` | The callback completed; `output` becomes the tool transcript text (possibly via the summarizer). |
| `Failure(message, category)` | An expected failure; `message` is safe, caller/model-visible text; `category` is one of `NOT_FOUND`, `ACCESS_DENIED`, `INVALID_ARGUMENTS`, `EXECUTION_FAILED`, `LOOP_EXCEEDED`. |

Raw exceptions are converted by the default registry:

| Callback outcome | Result |
| --- | --- |
| Returns `null` | `EXECUTION_FAILED` failure ("Tool execution failed") |
| Throws `ToolException.ArgumentCoercionFailed` | `INVALID_ARGUMENTS` |
| Throws `ToolException.AccessDenied` | `ACCESS_DENIED` |
| Throws `ToolException.LoopExceeded` | `LOOP_EXCEEDED` |
| Throws anything else (unwrapped through `CompletionException`/`ExecutionException`) | `EXECUTION_FAILED` |

Failures do not abort the turn: the failure message is fed back to the model as
the tool result, and the engine decides how to continue. The stream reports the
invocation as `ToolActivityData.Failed` with the same category; `ExecutionInfo`
carries the full ordered activity list on the terminal outcome.

## Register annotated methods

```java
import io.mindspice.magenta.core.Tool;
import io.mindspice.magenta.core.ToolFailureCategory;
import io.mindspice.magenta.core.ToolParam;
import io.mindspice.magenta.core.ToolResult;

final class LookupTools {
    @Tool(name = "lookup", description = "Looks up an item")
    public ToolResult lookup(
            @ToolParam(name = "id", description = "Item identifier") String id) {
        return id.isBlank()
            ? new ToolResult.Failure("id is blank", ToolFailureCategory.INVALID_ARGUMENTS)
            : new ToolResult.Success("item=" + id);
    }
}

loop.tools().registerToolBean(new LookupTools());
```

### Schema generation rules

- Every public method annotated `@Tool` on the bean becomes a tool; the tool
  name defaults to the method name when `@Tool.name()` is empty.
- Declare `@ToolParam(name = ...)` on every parameter unless caller classes are
  compiled with `-parameters`; the annotation is authoritative either way.
- `@ToolParam(required = false)` makes a parameter optional.
- The generated schema is intentionally simple (string/number/boolean
  properties with descriptions). Use an explicit `ToolCallback` when an exact
  JSON Schema is required.
- Return value wrapping: an ordinary return value is wrapped as
  `ToolResult.Success(value.toString())`; `null` becomes an empty `Success`;
  a `ToolResult` return value passes through unchanged; a thrown exception is
  mapped as in the table above.
- Bean registration is all-or-nothing per name: duplicate names, a collision
  with an existing public tool, or a collision with the reserved
  framework-internal name fails with `ToolException.AccessDenied` before any
  tool is registered.

## Inspect and remove

```java
List<ToolDefinition> definitions = loop.tools().definitions(); // public tools only
loop.tools().unregister("lookup");                             // no-op if absent
```

`definitions()` returns immutable definitions for **publicly registered tools
only**; the framework-internal `ask_user` tool is hidden and cannot be replaced
by a public registration (registering it throws).

## Per-turn selection

`TurnRequest(sessionId, message, model, toolNames, metadata)`:

| `toolNames` | Behavior |
| --- | --- |
| `null` | Normal resolution: all public tools plus framework-internal tools. |
| `List.of()` | No tools; the model cannot call any tool this turn. |
| `List.of("lookup", ...)` | Exactly those names, in request order; an unregistered name fails the turn during preparation with a tool-category `TurnFailedException`. |

Per-turn filtering without changing the request is done with a
`ToolAccessPolicy` on the builder:

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .toolAccessPolicy(ToolAccessPolicy.allowList(Set.of("lookup")))
    .build();
```

Provided policies: `allowAll()` (default), `allowList(names)`, `blockList(names)`.
A custom policy implements `filterTools(List<ToolCallback>, TurnContext)`.

## Tool loop protection

Configure both independent limits on the builder:

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .repeatedErroredToolCallLimit(8)
    .maxToolIterations(100)
    .build();
```

The repeated-error guard trips on exactly the configured Nth consecutive
execution with both the same canonical tool call and the same typed failure
identity. A success, a different call, or a different failure identity resets
or restarts the streak; repeated successful polling does not trip it. JSON
argument object order is canonicalized and the guard retains only digests, not
raw arguments or failure text.

One tool iteration is one model response containing one or more tool calls. At
a maximum of N, responses 1 through N-1 may execute tools; response N is
counted but none of its calls execute. Either guard emits a `Notice` with
`NoticeKind.TOOL_LOOP_WARNING` and requests one final response with no tool
definitions. Before that repair request, every call in the retained parallel
batch receives one ordered result: earlier results survive a repeated-error
trip, while its triggering/trailing calls become `LOOP_EXCEEDED`; every call at
the hard boundary becomes `LOOP_EXCEEDED` without execution. A model that still
calls a tool fails the turn with a tool-category `TurnFailedException`. Core
defaults to 5 repeated errors and 10 iterations. Callbacks within one turn are
never invoked concurrently.

## Safe summaries

`ToolResultSummarizer` converts raw tool execution facts into the summary the
model (and the stream) sees:

```java
loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .toolResultSummarizer(execution -> {
        String safe = summarizeSafely(execution.arguments(), execution.rawResult());
        return new SummarizeResult(safe, false);
    })
    .build();
```

Keep arguments and raw output out of summaries when they may contain secrets.
The default summarizer truncates long outputs; the `truncated` flag is part of
the SPI result.

## Related

- [Tool use tutorial](../tutorials/tool-use.md) — runnable example with both
  registration styles.
- [ToolRegistry / ToolAccessPolicy / ToolResultSummarizer SPIs](../reference/spis.md#tool-and-prompt-spis).
- [Sealed response protocol](../reference/response-protocol.md) — `ToolActivity`
  progress values.
- [Observe and audit turns](observe-and-audit.md) — audit records for tool
  execution with raw arguments.
