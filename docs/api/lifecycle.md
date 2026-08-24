# Lifecycle, interruption, and observability

Interrupt an active turn with the `turnId` from `TurnResponse.Started`:

```java
loop.interrupt(turnId, "Operator stopped the request");
```

Register Magenta-owned lifecycle and metrics SPIs directly on the builder:

```java
import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnFailure;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.spi.TurnLifecycleListener;
import io.mindspice.magenta.core.spi.TurnLoopMetrics;

import java.nio.file.Path;
import java.util.function.Consumer;

Consumer<TurnResponse.Outcome> outcomeSink = System.out::println;
Consumer<TurnFailure> failureSink = System.out::println;

TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("data"))
    .model("assistant", new ModelConfig(
        "assistant", "llama3.2", "http://localhost:11434", null,
        EndpointType.OLLAMA, 32_000, 0))
    .turnLifecycleListener(new TurnLifecycleListener() {
        @Override
        public void onOutcome(TurnResponse.Outcome outcome) {
            outcomeSink.accept(outcome);
        }

        @Override
        public void onFailure(TurnFailure failure) {
            failureSink.accept(failure);
        }
    })
    .turnLoopMetrics(new TurnLoopMetrics() {
        public void recordTurnDuration(String model, String outcome, long durationMs) { }
        public void recordToolCall(String toolName, String status) { }
        public void setActiveTurns(long count) { }
        public void setQueueDepth(String sessionId, long depth) { }
    })
    .build();
```

The listener receives exactly one `onOutcome(TurnResponse.Outcome)` call for each expected terminal path and exactly one `onFailure(TurnFailure)` call for each unexpected execution failure. Listener exceptions are isolated from the turn result. `TurnFailure` contains safe session, turn, origin, phase, category, timestamp, and message facts; provider exceptions do not cross the public boundary.

`close()` is idempotent. It fences active work, releases framework-owned resources, closes owned provider clients, and shuts down executors. Audit writes use `magenta_core_audit_log` by default or a caller-supplied `AuditWriter`.
