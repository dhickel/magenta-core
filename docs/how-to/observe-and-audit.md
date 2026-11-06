# Observe and audit turns

Magenta Core exposes four public observability seams: the lifecycle listener,
metrics sink, audit writer, and the builder-replaceable strategy SPIs. All of
them are installed explicitly — there is no bean discovery.

## Turn lifecycle listener

`TurnLifecycleListener` receives **exactly one callback per expected outcome**
and one per unexpected failure:

```java
import io.mindspice.magenta.core.spi.TurnLifecycleListener;

loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .turnLifecycleListener(new TurnLifecycleListener() {
        @Override
        public void onOutcome(TurnResponse.Outcome outcome) {
            notifyUi(outcome);   // Message, Questions, Queued, rejections, ...
        }

        @Override
        public void onFailure(TurnFailure failure) {
            alert(failure.category());   // MODEL, TOOL, CONTEXT, PERSISTENCE, ...
        }
    })
    .build();
```

Contract:

- `onOutcome` fires for every expected terminal value: executed outcomes,
  `Queued` receipts (immediate), and standalone question rejections
  (`QuestionsMissing` and friends) — each exactly once.
- `onFailure` fires once per unexpected execution failure with safe
  `TurnFailure` facts (session, turn, origin, `TurnPhase`, category,
  timestamps, message).
- **Listener exceptions are isolated**: a throwing listener is logged and
  never alters the caller-visible result.
- The listener is called synchronously from the execution path; keep handlers
  cheap, or hand off to your own executor.

## Metrics

```java
import io.mindspice.magenta.core.spi.TurnLoopMetrics;

loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .turnLoopMetrics(new TurnLoopMetrics() {
        @Override
        public void recordTurnDuration(String model, String outcome, long durationMs) {
            // outcome is one of: message, questions, queued, interrupted,
            // timed_out, questions_cancelled, questions_missing,
            // questions_already_answered, questions_already_cancelled,
            // turn_cancelled, failed
        }

        @Override
        public void recordToolCall(ToolActivityData activity) {
            // sealed Completed/Failed summary
        }

        @Override
        public void setActiveTurns(long count) {
        }

        @Override
        public void setQueueDepth(String sessionId, long depth) {
        }
    })
    .build();
```

Metric failures are isolated exactly like listener failures. The default is an
internal no-op sink.

## Audit writer

The default `SqliteAuditWriter` persists session-scoped JSON audit events
(user/assistant messages, tool executions with **raw arguments**, context
usage, and errors) into `magenta_core_audit_log`. Replace it through the
builder:

```java
loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .auditWriter(new AuditWriter() {
        @Override
        public void recordUserMessage(String sessionId, TurnMessage message) { }

        @Override
        public void recordAssistantMessage(String sessionId, TurnMessage message) { }

        @Override
        public void recordToolExecution(String sessionId, ToolExecutionRecord record) {
            // record.arguments() is the raw tool arguments string
        }

        @Override
        public void recordContextUsage(String sessionId, ContextUsage usage, String model) { }

        @Override
        public void recordError(String sessionId, TurnError error) { }
    })
    .build();
```

Contract:

- Fire-and-forget: implementations **must** catch and suppress their own
  failures so audit problems do not alter caller-visible execution. The
  framework also isolates its own call sites.
- Tool execution records intentionally include raw arguments and duration —
  the audit seam is where full fidelity belongs, unlike the disclosure-safe
  stream summaries.
- If the default SQLite audit writer cannot be constructed, the loop falls
  back to a no-op writer.

## Strategy seams

These builder replacements change execution behavior; see
[Extension SPIs](../reference/spis.md) for ownership and error contracts:

| Builder method | Default | When it runs |
| --- | --- | --- |
| `promptContributor(...)` | none (system prompt only) | Every turn, in registration order, appended to the default system prompt. |
| `toolResultSummarizer(...)` | `DefaultToolResultSummarizer` | After each successful tool call, before the model transcript is built. |
| `tokenEstimator(...)` | `DefaultTokenEstimator` (jtokkit-based) | Context accounting for every turn. |
| `compactionStrategy(...)` | `DefaultCompactionStrategy` | When the context trigger (default 75% of the window, set with `compactionTriggerPercent`) is crossed after a message turn. |
| `completionDetector(...)` | `DefaultCompletionDetector` | After each model response, decides whether another model/tool round is needed. |
| `repairStrategy(...)` | `DefaultRepairStrategy` | For empty, incomplete, or loop-exceeded responses. |
| `thinkingExtractor(...)` | `CompositeThinkingExtractor` | Splits provider output into visible content and thinking. |
| `modelCapabilityProvider(...)` | optimistic `STREAMING` + `TOOL_CALLING` | Model routing and streaming eligibility. |

A minimal custom prompt contributor:

```java
loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .promptContributor(context ->
        "Today is " + LocalDate.now() + ". Keep answers current.")
    .build();
```

## What is deliberately not exposed

- No provider-native events or raw transport streams: the sealed
  `TurnResponse` family is the complete caller-visible projection.
- No queue claim or lease telemetry beyond `setQueueDepth`: claim tokens never
  cross the facade.
- No per-chunk observability hooks: progress values are the hook.
- No automatic exporters: Magenta Core is transport-neutral and does not ship
  Micrometer, Prometheus, or logging adapters — the SPI is the integration
  point.

## Related

- [Extension SPIs](../reference/spis.md) — full contracts for every SPI.
- [Lifecycle and cancellation](lifecycle-and-cancellation.md) — which outcomes
  reach observers.
- [Configure persistence](configure-persistence.md) — the audit table.
