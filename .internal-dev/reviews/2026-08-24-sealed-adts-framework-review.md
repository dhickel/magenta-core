# Sealed ADTs Framework Review

## Scope

Reviewed the `eb9f92f` through `a580780` sealed `TurnResponse` change against its predecessor (`TurnResult`, `TurnEvent`, `TurnDiagnostic`, callback-driven interactions, and caller-managed queue claims), then surveyed the current public API, tool pipeline, engine state, model configuration, lifecycle gate, persistence SPIs, tests, and branch-specific specifications for comparable simplification opportunities.

This was a read-only assessment. No production code or tests were changed or executed.

## Findings

### What made the `TurnResponse` refactor successful

The old design represented the same terminal fact in several forms: `TurnResult`, terminal `TurnEvent` variants, `TurnDiagnostic.OutcomeKind`, separate blocking/stream reducers, question callback state, and synthetic queued results. The refactor succeeded because it:

- made one finite caller protocol exhaustive;
- separated materially different payloads into variants rather than nullable fields or booleans;
- made blocking and streaming paths reduce the same response stream;
- moved unexpected failures to one explicit error channel;
- distinguished execution, interaction, message, and claim identities;
- hid processing mechanics, especially queue claims, behind the owning SPI;
- persisted the exact typed state required for restart-safe rehydration.

The best additional targets are places with these same properties, not every enum or boolean.

### 1. Recommended: package-private tool invocation outcome ADT

**Evidence:** `DefaultTurnEngine.handleExecuteTools()` currently derives and keeps parallel values for `ToolActivityStatus`, result text, summary, duration, string metadata (`tool.success`), audit facts, and `ToolLoopGuard.ToolExecutionResult(boolean success, nullable errorMessage)`. Missing tools and callback exceptions are normalized through repeated branching.

**Proposed shape:** one internal sealed result family, for example:

- `Succeeded(resultText, summary, duration, truncated)`
- `Failed(modelVisibleText, summary, duration, failureKind)`

The exact variants should be limited to distinctions consumed by engine behavior. It should become the authoritative input for `ToolActivityData`, durable `TurnMessage`, model tool responses, audit/metrics, and loop-guard observation.

**Benefit:** high. It removes boolean/null/status duplication in the tool pipeline and gives one conversion point much like `TurnResponse` did for terminal turn meaning.

**Boundary:** keep `ToolCallback`, `ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, and `RepairStrategy` open. They are caller extension SPIs and must not be sealed. Keep the callback's JSON-in/string-out contract unless a separate public API break is explicitly approved.

### 2. Recommended for a deliberate persistence-SPI refactor: interaction lifecycle ADT

**Evidence:** `InteractionStore.Interaction` combines `InteractionState` with answers, nullable cancellation timestamp, and nullable cancellation reason. Its compact constructor contains a large state-dependent validation switch. `MISSING` is carried in the same enum despite being an operation result rather than persisted state. `MutationResult(boolean applied, state)` permits a result shape that requires additional invariant checks.

**Proposed shape:** preserve common immutable interaction facts and replace state-dependent fields with a sealed lifecycle value:

- `Pending`
- `Answered(orderedAnswers, answeredAt)`
- `Cancelled(cancelledAt, reason)`

Separately model mutation outcomes as `Applied(updatedState)` and `Rejected(currentOrMissingState)` (or equally explicit names). Do not make `Missing` a persisted interaction variant.

**Benefit:** very high semantic clarity and fewer constructible illegal states. This is the closest match to the `TurnResponse` rationale.

**Cost/risk:** very high. `InteractionStore` is an advanced public SPI and the change touches in-memory/SQLite stores, schema mapping, answer/cancel CAS, crash recovery, fixtures, and restart/concurrency tests. The database can retain columns and checks as its storage representation while Java exposes the ADT.

### 3. Recommended only as a clean-break public API change: sealed `TurnMessage`

**Evidence:** `TurnMessage` represents four closed roles with a raw string plus nullable `toolName` and `toolCallId`. It permits invalid combinations, and provider conversion contains role-string switches with fallback behavior. Persistence and audit paths repeatedly inspect role and tool fields.

**Proposed shape:** sealed message variants for `System`, `User`, `Assistant`, and `Tool`, with tool identity required only by `Tool`. Keep metadata as common immutable extension data.

**Benefit:** high. It removes invalid role/tool combinations, gives exhaustive provider/persistence conversion, and eliminates magic role strings.

**Cost/risk:** high. `TurnMessage` is public through memory and compaction SPIs and is stored in SQLite. This should be planned as a source-breaking message-model migration, not slipped into the internal tool refactor. Constructor validation is a lower-cost interim improvement, but does not provide exhaustive handling.

### 4. Worthwhile internal cleanup: sealed gate session state

**Evidence:** `SerialTurnGate.SessionState` combines `GateState` with nullable lock/start/timeout tokens and an `awaitingAnswer` boolean that duplicates `GateState.AWAITING_ANSWER`.

**Proposed shape:** internal states such as `Idle`, `Executing(token, startedAt, timeoutFence)`, and `AwaitingAnswer`. Keep the public `GateState` enum as a projection and retain the already-sealed `AcquireResult`.

**Benefit:** medium. It can make timeout fencing and reset transitions safer, but is internal cleanup rather than embedding-API simplification.

**First step:** verify and remove the apparently redundant `awaitingAnswer` field before undertaking the larger state rewrite.

### 5. Conditional future candidate: provider-specific model configuration

**Evidence:** `ModelConfig` combines `EndpointType` with provider-dependent nullable `apiKey`, `contextLength`, and `thinkLevel`; `TurnLoopBuilder` enforces cross-field rules such as DeepSeek requiring a key and rejecting nonzero thinking level.

A sealed endpoint configuration could make provider-specific options structural, but the current provider set is small and centralized. The migration would affect the builder, adapters, examples, docs, and consumer source. Defer until provider-specific options materially diverge or invalid combinations become a recurring defect.

### Targets that should not be converted

- **Do not seal caller SPIs/callbacks:** `ToolCallback`, `ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, `RepairStrategy`, model/prompt/compaction SPIs, and persistence SPIs require caller implementations.
- **Keep simple enums:** `ToolActivityStatus`, `QueueReason`, `NoticeKind`, `TurnPhase`, `ModelCapability`, and similar payload-free closed sets are already appropriate ADTs.
- **Keep queue claim carriers as-is:** `QueuedRequest`, `Claim`, and `Optional<Claim>` correctly separate durable intent, lease ownership, and absence. A broader public queue-state ADT would leak framework policy that the response refactor intentionally hid.
- **Do not merge expected outcomes and failures:** the `TurnResponse` versus `TurnFailure`/error-channel split is intentional.
- **Do not collapse interrupted, timed-out, and cancelled outcomes merely because their record fields match:** they have distinct caller and JSON protocol semantics.
- **Do not replace `SummarizeResult(summary, truncated)` solely to remove a boolean:** summary and truncation are orthogonal facts, not mutually exclusive states.
- **Avoid a wholesale sealed `TurnLoopState` rewrite initially:** the mutable engine state is complex and side-effectful. A phase ADT would create substantial churn without an equivalent public simplification.

## Risk Assessment

| Candidate | Benefit | Cost | Main risk |
| --- | --- | --- | --- |
| Internal tool invocation outcome | High | Medium | Over-designing failure variants not consumed by behavior |
| Interaction lifecycle/mutation ADTs | Very high | Very high | Persistence SPI and recovery compatibility |
| Sealed `TurnMessage` roles | High | High | Public source break and storage/provider conversion |
| Internal gate state | Medium | Medium | Timeout/fencing concurrency regression |
| Provider configuration ADT | Conditional | High | Public API growth without enough real variation |

The primary architectural risk is applying sealing to extension points. Sealed hierarchies are valuable for framework-owned finite protocols and internal state, but harmful where embedding applications are expected to supply implementations.

## Recommendations

1. Start with the package-private tool invocation result. It has a favorable benefit/cost ratio and directly addresses the user's tool-handling example without constraining callers.
2. Plan interaction lifecycle and mutation outcomes as the next clean-break persistence-SPI refactor if additional API breakage is acceptable. Treat it as a full restart/concurrency/schema-mapping change.
3. Consider sealed `TurnMessage` roles in a separate public message-model plan. Do not bundle it with tools or interactions.
4. Perform the small gate-state audit (`awaitingAnswer`, token/timestamp invariants) before deciding whether a sealed internal state rewrite is warranted.
5. Defer endpoint-specific model configuration and broader engine phase ADTs until concrete variation or defects justify them.

For every proposed ADT, require the same tests used by the successful response refactor: exhaustive variant handling, constructor invariants, serialization/storage mapping, one authoritative reduction/conversion path, restart behavior where durable, and explicit proof that open caller extension points remain open.

## Follow-ups

- If implementation is authorized, write a focused plan for the internal tool invocation outcome first, including ordinary success, missing callback, callback exception, summary truncation, durable message metadata, audit/metrics, and loop-guard tests.
- Before an interaction ADT plan, inventory third-party `InteractionStore` implementations and decide source-compatibility posture.
- Before a `TurnMessage` plan, define stable persisted role tags and whether unknown historical roles fail fast or are rejected during loading.
- Verify whether `SerialTurnGate.SessionState.awaitingAnswer` and `DefaultTurnEngine.TurnLoopState.statePersisted` are dead state before larger lifecycle changes.
