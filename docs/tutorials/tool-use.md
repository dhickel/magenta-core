# Add tools: callbacks, annotated methods, and typed results

This tutorial builds a tool-enabled turn loop: one explicit `ToolCallback` with
an exact JSON Schema, one bean of annotated `@Tool` methods, typed
`ToolResult.Success`/`Failure` results, and disclosure-safe tool activity on the
stream.

## Prerequisites

- Complete [Build and run your first turn loop](getting-started.md).
- A model that performs tool calls. The model must advertise or optimistically
  support `TOOL_CALLING` (the default capability provider reports it for every
  alias; see [Configure and route models](../how-to/configure-models.md)).

## The two registration styles

| Style | API | Best for |
| --- | --- | --- |
| Explicit callback | `loop.tools().register(new ToolCallback() {...})` | Exact JSON Schema control; dynamic results. |
| Annotated bean | `loop.tools().registerToolBean(new MyTools())` | Plain Java methods; schema is generated. |

Both produce the same runtime type: a `ToolCallback` with a `ToolDefinition`
(name, description, inputSchema) and a `call(String toolInput)` method that
returns `ToolResult`. Magenta-owned types only — you never implement a Spring AI
callback.

## The program

<!-- tutorial-compile: Toolbox.java -->
```java
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.Tool;
import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;
import io.mindspice.magenta.core.ToolFailureCategory;
import io.mindspice.magenta.core.ToolParam;
import io.mindspice.magenta.core.ToolResult;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.ToolActivityData;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class Toolbox {

    /** Annotated tools: the registry generates the JSON Schema. */
    public static final class InventoryTools {

        @Tool(name = "inventory_lookup", description = "Looks up an item's stock")
        public ToolResult lookup(
                @ToolParam(name = "sku", description = "Item identifier") String sku) {
            return switch (sku) {
                case "A-100" -> new ToolResult.Success("12 units available");
                case "A-200" -> new ToolResult.Success("0 units available (backorder)");
                default -> new ToolResult.Failure(
                    "Unknown SKU: " + sku, ToolFailureCategory.NOT_FOUND);
            };
        }

        @Tool(name = "apply_discount", description = "Applies a percentage discount")
        public String discount(
                @ToolParam(name = "percent", description = "Discount percent") int percent) {
            if (percent < 0 || percent > 90) {
                throw new IllegalArgumentException("discount must be 0-90");
            }
            // Ordinary return values are wrapped as ToolResult.Success.
            return "discount of " + percent + "% applied";
        }
    }

    public static void main(String[] args) throws InterruptedException {
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

            // Style 1: explicit callback with an exact JSON Schema.
            loop.tools().register(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return new ToolDefinition(
                        "shipping_cost",
                        "Returns the shipping cost for an item",
                        "{\"type\":\"object\","
                            + "\"properties\":{\"sku\":{\"type\":\"string\"}},"
                            + "\"required\":[\"sku\"]}");
                }

                @Override
                public ToolResult call(String toolInput) {
                    if (toolInput == null || !toolInput.contains("A-100")) {
                        return new ToolResult.Failure(
                            "Shipping quote unavailable",
                            ToolFailureCategory.EXECUTION_FAILED);
                    }
                    return new ToolResult.Success("$4.99");
                }
            });

            // Style 2: annotated bean. @ToolParam names make the schema stable
            // even when classes are compiled without the -parameters flag.
            loop.tools().registerToolBean(new InventoryTools());

            System.out.println("Registered tools: "
                + loop.tools().definitions().stream()
                    .map(ToolDefinition::name)
                    .sorted()
                    .toList());

            CountDownLatch done = new CountDownLatch(1);
            loop.stream(TurnRequest.of(
                    "tool-session",
                    "Check the inventory for SKU A-100, then compute its shipping cost."))
                .subscribe(
                    response -> {
                        if (response instanceof TurnResponse.Progress progress) {
                            renderProgress(progress);
                        } else if (response instanceof TurnResponse.Outcome outcome) {
                            System.out.println();
                            System.out.println("== " + describe(outcome));
                        }
                    },
                    error -> {
                        System.out.println("stream failed: " + error.getMessage());
                        done.countDown();
                    },
                    done::countDown);

            done.await(120, TimeUnit.SECONDS);
        }
    }

    private static void renderProgress(TurnResponse.Progress progress) {
        switch (progress) {
            case TurnResponse.Started started -> { /* correlation only */ }
            case TurnResponse.ContentChunk chunk -> System.out.print(chunk.text());
            case TurnResponse.ThinkingChunk chunk -> { /* keep separate */ }
            case TurnResponse.ToolActivity activity -> {
                var data = activity.activity();
                String line = switch (data) {
                    case ToolActivityData.Completed completed ->
                        completed.toolName() + ": " + completed.summary();
                    case ToolActivityData.Failed failed ->
                        failed.toolName() + " failed: " + failed.summary()
                            + " (" + failed.category() + ")";
                };
                System.out.println("[tool] " + line);
            }
            case TurnResponse.Notice notice ->
                System.out.println("[notice] " + notice.kind());
        }
    }

    private static String describe(TurnResponse.Outcome outcome) {
        return switch (outcome) {
            case TurnResponse.Message message -> "message: " + message.text();
            case TurnResponse.Questions questions -> "questions: " + questions.questions();
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

## Expected behavior

```text
Registered tools: [apply_discount, inventory_lookup, shipping_cost]
[tool] inventory_lookup: 12 units available
[tool] shipping_cost: $4.99
== message: SKU A-100 has 12 units in stock and ships for $4.99.
```

Note that `ask_user` is **not** listed: it is a framework-internal tool, hidden
from `definitions()` and reserved — a public registration with that name fails.
The tool lines appear in execution order, and the summaries are exactly what the
model sees: raw arguments and exception traces never reach the transcript.

## Typed results and the failure taxonomy

Return `ToolResult.Failure(message, category)` for **expected** callback
failures. The category is `ToolFailureCategory`:

| Category | Typical use |
| --- | --- |
| `NOT_FOUND` | The requested entity does not exist. |
| `ACCESS_DENIED` | The caller may not perform this action. |
| `INVALID_ARGUMENTS` | Arguments fail domain validation. |
| `EXECUTION_FAILED` | The operation itself failed. |
| `LOOP_EXCEEDED` | Reserved for the framework's tool-loop guard. |

What the framework does with each outcome:

- `Success` output (or a summarizer's version of it) becomes the `ToolMessage`
  transcript text; `Failure` message becomes the transcript text with the
  category recorded.
- Both are projected to the stream as sealed `ToolActivityData.Completed` /
  `ToolActivityData.Failed` — disclosure-safe summaries.
- Raw callback exceptions are converted by the default registry: a thrown
  `IllegalArgumentException` from `apply_discount` becomes an
  `INVALID_ARGUMENTS` failure, and anything else becomes `EXECUTION_FAILED`.
  The turn continues; the failure is fed back to the model.
- A `null` return from an annotated method becomes an empty `Success`.
- An unregistered tool name requested via `TurnRequest.toolNames` fails the
  turn during preparation with a tool-category `TurnFailedException`.

## Tool selection and access policy

`TurnRequest(sessionId, message, model, toolNames, metadata)` controls selection:

- `toolNames == null` — normal resolution: all public plus framework tools.
- `toolNames == List.of()` — no tools at all (useful for pure chat).
- `toolNames == List.of("inventory_lookup", ...)` — exactly those tools;
  unknown names fail the turn.

For per-turn filtering, install a `ToolAccessPolicy` on the builder:

```java
import io.mindspice.magenta.core.spi.ToolAccessPolicy;

loop = TurnLoopBuilder.create()
        .dataRoot(Path.of("./data"))
        .model("local", modelConfig)
        .toolAccessPolicy(ToolAccessPolicy.allowList(Set.of("inventory_lookup")))
        .build();
```

`allowAll`, `allowList`, and `blockList` are provided; a custom policy filters
the resolved `List<ToolCallback>` by inspecting `getToolDefinition().name()`.

## The tool-loop guard

The engine protects against infinite tool loops with two independent builder
settings:

```java
loop = TurnLoopBuilder.create()
        .dataRoot(Path.of("./data"))
        .model("local", modelConfig)
        .repeatedErroredToolCallLimit(8)
        .maxToolIterations(100)
        .build();
```

- **Repeated-error detection** — exactly the configured Nth consecutive
  execution with the same canonical call and typed failure identity trips the
  guard. Successful repetition is allowed; a success or different call/error
  identity resets or restarts the streak.
- **Iteration maximum** — one iteration is one model response containing one or
  more tool calls. At a maximum of N, responses 1 through N-1 may execute;
  response N is counted but none of its calls execute.

When either guard trips, the engine emits a `Notice` with
`NoticeKind.TOOL_LOOP_WARNING` and requests one response with no tool
definitions. Every call in the retained parallel batch first receives one
ordered result. A repeated-error trip preserves earlier results, marks the
executed triggering call and unexecuted trailing calls `LOOP_EXCEEDED`, and
runs no trailing callbacks. The hard boundary executes none of that response's
calls and marks all of them `LOOP_EXCEEDED`. If the model still requests a
tool, the turn fails with a tool-category `TurnFailedException`. Canonical
identities retain only digests, synthetic blocked results omit original
arguments from result/audit metadata, and callbacks are never called
concurrently within one turn.

## What you learned

- Tools are Magenta-owned: `ToolCallback` + `ToolDefinition` + `ToolResult`,
  never Spring AI callbacks.
- `ToolResult` is sealed — `Success` or typed `Failure` — and failures are safe,
  caller/model-visible text plus a category.
- Annotated beans generate simple schemas; use an explicit callback when you
  need exact JSON Schema control.
- Tool activity on the stream and in `ExecutionInfo` is summarized and
  disclosure-safe.
- `ask_user` is reserved framework tooling; durable questions are a built-in
  capability layered on the same tool machinery.

## Next steps

- How-to: [Register tool callbacks](../how-to/register-tools.md) — procedure,
  schema generation rules, and failure mapping in detail.
- Tutorial: [Handle durable questions](durable-questions.md) — the built-in
  `ask_user` interaction.
- Explanation: [Turn lifecycle and durable state](../explanation/turn-lifecycle.md)
  — where tools fit in the seven-phase turn state machine.
