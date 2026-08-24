# Replace turn results and events with a sealed TurnResponse protocol

## Feature

Perform a clean-break refactor of Magenta Core’s caller-visible turn response system. Replace the split TurnResult, TurnEvent, diagnostic-enum, callback-answer, and low-level queue-facade model with one sealed TurnResponse hierarchy used by synchronous execution, streaming, questions, queue receipts, cancellation, interruption, timeout, and framework-managed queue draining. Keep the model-facing ask_user tool internal while making user interaction and queued-work handling typed, durable, restart-safe, and straightforward for embedding applications.

## Required Behavior

- Create a sealed `TurnResponse` root with nested sealed `Progress` and `Outcome` families.
- Define progress variants `Started`, `ContentChunk`, `ThinkingChunk`, `ToolActivity`, and `Notice`. All progress records must carry stable session/turn correlation and timestamps; content and thinking must be separate variants rather than a boolean mode.
- Define outcome variants `Message`, `Questions`, `Queued`, `Interrupted`, `TimedOut`, `QuestionsCancelled`, and `TurnCancelled`. `Message` must contain nonblank user-visible assistant text.
- Introduce a shared typed `ExecutionInfo` for execution-related outcomes containing session ID, turn ID, typed origin, start/completion timestamps, final context usage, and accumulated tool activities.
- Introduce a sealed `ExecutionOrigin` with `Direct`, `Answer(interactionId)`, and `Queued(messageId)` variants. Avoid nullable origin IDs.
- Make `send(TurnRequest)` return `TurnResponse.Outcome` and `stream(TurnRequest)` return a cold `Flux<TurnResponse>`. A direct stream must emit zero or more progress records followed by exactly one expected outcome and then complete.
- Unexpected execution failures must throw or terminate the Flux with an error and must not also emit a failure outcome. Add a typed `TurnFailure` payload with safe session/turn/origin facts, phase, category, timestamps, and message for `TurnFailedException` and lifecycle observation. Keep validation/configuration/not-found exceptions specialized.
- Remove the redundant `stream` flag from `TurnRequest`; the selected facade method determines blocking versus streaming behavior.
- Replace the current interaction callback protocol with first-class question outcomes. Remove `InteractionHandler` and `AnswerResult`; the internal `ask_user` tool must durably persist and emit `Outcome.Questions` directly.
- Generate a dedicated durable interaction UUID distinct from turn IDs. `Questions` must expose this interaction ID and an ordered nonempty question list.
- Provide blocking `answer(sessionId, interactionId, List<String>)` and streaming `streamAnswer(sessionId, interactionId, List<String>)`. Require exactly one nonblank answer per ordered question, persist the ordered answers, and inject explicit question/answer pairs into continuation context.
- Provide `cancelQuestions(sessionId, interactionId, reason)` returning `Outcome.QuestionsCancelled`. Use the interaction ID in answer and cancellation CAS operations so stale UI submissions cannot affect a newer interaction.
- Provide a read-only `pendingQuestions(sessionId)` query returning `Optional<Outcome.Questions>` so clients can rehydrate the exact pending interaction after reconnect or process reconstruction without sending another message.
- Do not require a separate question-delivery acknowledgement. Durable PENDING persistence is the delivery checkpoint; answering or cancellation consumes the interaction. Duplicate or already-consumed answer submissions must raise a specific interaction-state exception and must not replay a cached outcome.
- Persist enough question execution data to reconstruct the same `Questions` response, including interaction ID, asking-turn execution info, ordered questions, origin, context usage, tool activities, timestamps, model, and continuation metadata. Preserve ANSWERED crash recovery using the exact stored ordered answers.
- Make `enqueue(TurnRequest)` return `Outcome.Queued`. Automatic queueing from `send`/`stream` must use the same outcome shape.
- `Queued` must be a durable receipt, not a processing claim. It must expose session ID, stable message ID, enqueue timestamp, and typed `QueueReason` (`EXPLICIT`, `SESSION_ACTIVE`, or `AWAITING_QUESTIONS`). Receiving it requires no client acknowledgement, and claim tokens must never appear in `TurnResponse`.
- Queue every `send` or `stream` submission made while that session is non-idle, whether another turn is executing or questions are pending, rather than rejecting active-session submissions.
- Persist queued caller intent: message, explicitly supplied model, explicitly supplied tool names, and metadata. Null model/tool selection must remain unresolved until execution under normal turn rules.
- Add cold `drain(sessionId): Flux<TurnResponse>`. On subscription, the framework—not the embedding app—must perform oldest-first claim, execution, acknowledgement/release, and repeated processing until the queue is empty or processing becomes blocked.
- If `drain` starts while questions are pending, emit the durable `Questions` outcome and complete without claiming a queue item. If another execution or drain owns the session, terminate with a specific busy exception rather than waiting or completing silently.
- A drain stream repeats the normal progress-then-one-outcome sequence for each claimed queued request and correlates every execution through `ExecutionOrigin.Queued(messageId)`. It emits no synthetic execution turn for the queue receipt itself.
- Internally acknowledge queued requests that finish as `Message` or `Questions`; stop after `Questions`. Internally release and stop on `Interrupted`, `TimedOut`, `TurnCancelled`, or unexpected failure so the item remains retryable. Queue acknowledgement is required internally for durable consumption but is not a caller-facing response acknowledgement.
- Cancelling a drain subscription must revoke/fence the current execution, suppress late framework writes, release the current claim, and leave later queued requests untouched.
- Retain restart-safe queue claims as an internal/store-SPI concern. Recover only expired claims when draining, use a bounded claim lease, and document the queue as at-least-once across process-crash windows rather than claiming exactly-once provider execution.
- Remove public `TurnLoop.claim`, `ack`, and `release`. Remove the existing high-level custom `MessageQueue` injection. Retain/refactor `MessageQueueStore` as an advanced SPI with SPI-scoped queued-request/claim carriers so framework code exclusively owns processing policy.
- Retain and refactor `InteractionStore` as an advanced SPI with interaction IDs, exact response rehydration, ordered-answer CAS, cancellation CAS, and restart recovery requirements.
- Refactor lifecycle observation to notify exactly once through `onOutcome(TurnResponse.Outcome)` for expected terminal paths and `onFailure(TurnFailure)` for unexpected failures. Listener failures must remain isolated from the turn result.
- Replace `TurnEventRenderer` with a JSON-only `TurnResponseRenderer`. Use stable Jackson discriminator property `type` with snake_case values: `started`, `content_chunk`, `thinking_chunk`, `tool_activity`, `notice`, `message`, `questions`, `queued`, `interrupted`, `timed_out`, `questions_cancelled`, and `turn_cancelled`. Do not retain an SSE framing helper.
- Delete the superseded `TurnResult`, `TurnEvent`, `TurnDiagnostic`, `AnswerResult`, old response reducers, compatibility constructors/accessors, synthetic queued text results, and obsolete outcome enum branches. Do not ship deprecated bridges.
- Use one authoritative internal stream-to-outcome reduction path so blocking calls, streaming calls, lifecycle observers, audit, and metrics cannot derive divergent terminal meaning.
- Change the pre-release `magenta_core_*` schema as a clean break. Existing databases with the superseded Magenta Core schema must fail fast with an explicit incompatible-schema error; initialization must never drop or rewrite them automatically. Historical unrelated `turnloop_*` tables remain untouched.
- Update the consumer fixture, public API scans, tests, README, API/technical/end-user documentation, branch-specific architecture/decision specifications, and closeout records to describe only the new sealed protocol.

## Targets

- `src/main/java/io/mindspice/magenta/core/TurnResponse.java and new response support records/enums`: Add the sealed Progress/Outcome protocol, ExecutionInfo, ExecutionOrigin, QueueReason, cancellation outcomes, validation invariants, and Jackson subtype contract.
- `src/main/java/io/mindspice/magenta/core/TurnLoop.java and TurnRequest.java`: Replace facade signatures with Outcome/Flux<TurnResponse>, add answer/streamAnswer/pendingQuestions/cancelQuestions/enqueue/drain, remove stream flag and low-level claim/ack/release methods, and rewrite threading/error Javadocs.
- `src/main/java/io/mindspice/magenta/core/DefaultTurnLoop.java and DefaultTurnEngine.java`: Unify sync/stream reduction, emit the new response variants, add typed origins, enforce one expected outcome per execution, queue all non-idle submissions, implement framework-managed drain sequencing, and remove diagnostic/synthetic-result branches.
- `src/main/java/io/mindspice/magenta/core/TurnResult.java, TurnEvent.java, TurnDiagnostic.java, AnswerResult.java, TurnEventRenderer.java, ClaimedMessage.java, and obsolete queue/interaction carriers`: Delete or replace the superseded public response and facade-level claim types with the new protocol and SPI-scoped records; retain no compatibility bridge.
- `src/main/java/io/mindspice/magenta/core/spi/InteractionStore.java and interaction persistence implementations`: Refactor to dedicated interaction IDs, exact Questions snapshots, ordered answer lists, ID-qualified CAS, cancellation, pending lookup, and answered crash recovery; remove InteractionHandler.
- `src/main/java/io/mindspice/magenta/core/spi/MessageQueueStore.java, DefaultMessageQueue.java, queue records, and SQLite/in-memory queue stores`: Move claim mechanics behind the store SPI, persist full queued request intent and QueueReason, implement internal claim/ack/release/stale-lease behavior, and support ordered drain processing.
- `src/main/java/io/mindspice/magenta/core/TurnLoopBuilder.java`: Remove interaction-handler and high-level message-queue injection, wire refactored stores/listeners, validate claim-lease configuration, and preserve programmatic no-context construction.
- `src/main/java/io/mindspice/magenta/core/TurnFailedException.java, TurnFailure.java, lifecycle SPI, metrics/audit integration, and TurnResponseRenderer.java`: Replace diagnostic-bearing failures and TurnResult callbacks with typed failure/outcome observation and tested JSON rendering.
- `src/main/resources/schema.sql and schema identity/initialization code`: Install the clean-break interaction and queued-request schema needed for IDs, exact response snapshots, ordered answers, request intent, queue reasons, and claims; fail safely on the superseded schema without deleting data.
- `src/test/java/io/mindspice/magenta/core/** and persistence/provider tests`: Rewrite old result/event assertions and add exhaustive protocol, stream, interaction, queue/drain, concurrency, restart, failure, lifecycle, serialization, and schema-reset safety coverage.
- `consumer-fixture/**`: Compile and run consumer examples that exhaustively pattern-match outcomes, consume progress, answer questions, enqueue/drain queued work, and use both data-root and DataSource construction without Spring Boot.
- `README.md, docs/api/**, docs/end-user/**, docs/technical/**`: Replace old TurnResult/TurnEvent/combined-answer/manual-claim examples with the sealed protocol, ordered-answer APIs, durable pending query, queue receipt, and observable drain workflow.
- `.internal-dev/specifications/architecture.md, .internal-dev/specifications/decisions.md, prior plan/archive records, knowledge, and changelogs`: Record the sealed response protocol as branch-specific intended truth, supersede prior response/queue clauses, document at-least-once drain tradeoffs and clean-break schema behavior, archive finalized plan records as required, and capture final Git provenance.

## Constraints

- Do not implement during planning; implementation begins later on a new dedicated branch created from the current clean HEAD.
- Remain one Java 25 `io.mindspice:magenta-core` JAR with programmatic construction and no Spring Boot application context, auto-configuration, servlet/server, Actuator, or product UI surface.
- Keep Spring AI and provider-specific types package-private/internal; no public response, exception, SPI, or facade signature may expose them.
- Preserve existing provider support, tool execution, compaction, audit, metrics, interruption fencing, context management, and storage-selection behavior except where the response refactor explicitly changes their public contract.
- Never auto-delete an incompatible existing database. Old `magenta_core_*` schema identity must fail clearly; unrelated historical `turnloop_*` tables must remain unread and untouched.
- Questions and queued receipts are durable states, not delivery acknowledgements. Interaction IDs, message IDs, execution turn IDs, and internal claim tokens have distinct meanings and must not be conflated.
- Queue draining is caller-started and observable through a cold Flux; there is no hidden fire-and-forget/background auto-drain.
- Queue execution is at-least-once across unrecoverable crash windows. Do not claim exactly-once model/provider effects.
- A direct execution stream has exactly one expected Outcome; a drain stream has exactly one expected Outcome per claimed execution. Unexpected failures use `onError`/exceptions and lifecycle failure notification, never a second terminal response.
- No old package/Maven compatibility wrappers, deprecated response overloads, response adapters, or cached duplicate-answer replay.
- Follow repository closeout rules: update specifications/docs/knowledge/changelog, archive completed plan artifacts, run complete validation, review the final diff, and commit each completed implementation phase on the dedicated branch.

## Assumptions

- The library remains pre-release, so a source- and schema-incompatible clean break is acceptable.
- Single-process coordination remains the supported runtime topology; durable SQLite CAS and claim leases provide restart safety but are not a distributed turn-gate guarantee.
- The internal `ask_user` model tool remains reserved and hidden from public tool definitions; only its caller-facing representation changes.
- `Questions` stores plain ordered question strings, not per-question IDs. The interaction ID identifies the whole ordered interaction.
- Pending-question rehydration returns the originally persisted Questions outcome without marking it delivered or mutating state.
- Blocking answer and streaming answer share the same CAS and continuation implementation; only response delivery differs.
- A queued request yielding Questions is considered consumed and is acknowledged before draining stops because its durable continuation has moved to the interaction store.
- Interrupted, timed-out, cancelled, or failed queued executions remain retryable and therefore release their claims; later drain invocation is an explicit retry decision.
- The JSON renderer produces response JSON only. HTTP/SSE/WebSocket framing belongs to embedding applications.
- Deterministic fake models and mock provider transports are sufficient for the default validation suite; live external-provider validation remains optional and separately configured.

## Settled Decisions

- Use one sealed TurnResponse protocol for both sync and stream surfaces.
- Nest sealed Progress and Outcome families so blocking methods can return the narrower Outcome type.
- Use progress variants Started, ContentChunk, ThinkingChunk, ToolActivity, and Notice.
- Use outcome variants Message, Questions, Queued, Interrupted, TimedOut, QuestionsCancelled, and TurnCancelled; unexpected failures are not response variants.
- Use a shared ExecutionInfo and a sealed Direct/Answer/Queued ExecutionOrigin.
- Require nonblank Message text.
- Perform a clean-break deletion of TurnResult, TurnEvent, TurnDiagnostic, AnswerResult, and response compatibility helpers.
- Use dedicated interaction IDs and ID-qualified answer/cancel operations.
- Accept an exact ordered list of nonblank answers, one per question.
- Remove InteractionHandler and its veto/abort result; embedding applications react to Questions outcomes.
- Require no question-delivery acknowledgement and reject duplicate/consumed answers rather than replaying cached results.
- Expose pendingQuestions(sessionId) for reconnect/restart recovery.
- Use Queued as a durable receipt with stable message ID and typed reason; never expose claim tokens in responses.
- Return Queued from explicit enqueue and from send/stream whenever the session is non-idle.
- Use caller-subscribed drain(sessionId) for framework-managed oldest-first processing; do not background-drain.
- If drain sees pending questions, emit Questions and complete; if another execution/drain is active, fail busy.
- Hide claim/ack/release from the facade while retaining a refactored queue store SPI.
- Internally ack Message and Questions, release retryable stop/failure outcomes, and release on drain-subscription cancellation.
- Retain/refactor InteractionStore as an advanced SPI.
- Provide both blocking answer and streaming streamAnswer APIs.
- Refactor lifecycle observers to typed outcome and failure callbacks.
- Retain JSON rendering only, using `type` and stable snake_case subtype tags; remove built-in SSE framing.
- Remove TurnRequest.stream.
- Persist queued caller intent without eagerly resolving null model/tool selections.
- Fail fast on the superseded Magenta Core schema and never auto-delete it.
- Implement on a new dedicated branch from the current clean HEAD.

## Implementation Approach

1. Create the dedicated implementation branch from the current HEAD. Re-read the accepted job plan and branch-specific specifications, inventory all references to the superseded response types, and establish phase commits.
2. Introduce the new public protocol first: TurnResponse, nested Progress/Outcome variants, ExecutionInfo, ExecutionOrigin, QueueReason, TurnFailure, interaction-state/busy exceptions, and invariants. Add focused compile/serialization tests before rewiring behavior.
3. Refactor TurnRequest and TurnLoop signatures as a clean break. Add send/stream, answer/streamAnswer, pendingQuestions, cancelQuestions, enqueue, and drain contracts; remove the stream flag and facade claim/ack/release APIs.
4. Replace the engine/facade event/result dual reducers with one authoritative internal execution Flux that emits TurnResponse records. Implement progress emission, nonblank Message enforcement, typed interruption/timeout/cancellation outcomes, one-outcome terminal guarantees, sync collection, typed failures, and exactly-once lifecycle observer notification.
5. Remove InteractionHandler/AnswerResult. Generate and persist interaction IDs when ask_user is intercepted; save the exact Questions snapshot before emission; implement pending lookup, exact ordered-answer validation, ID-qualified CAS, streaming/blocking continuation, question cancellation, stale/duplicate rejection, and ANSWERED restart recovery.
6. Refactor the interaction schema/store records to hold execution metadata, questions, ordered answers, continuation/model data, origin, timestamps, context usage, and tool activities. Add in-memory/custom-SPI contract tests and SQLite restart/concurrency tests.
7. Refactor queued request persistence to store full caller intent and QueueReason. Remove high-level queue injection and facade claim methods; retain a store SPI with internal framework processing and bounded stale-claim recovery.
8. Change non-idle send/stream paths to enqueue atomically and return/emit Queued without creating synthetic turn IDs or invoking a provider. Ensure direct enqueue uses the identical receipt shape.
9. Implement cold drain(sessionId): enforce one drain owner, emit pending Questions or fail busy when blocked, claim oldest-first, reconstruct TurnRequest, execute with Queued origin, ack/release according to outcome, repeat until empty, and fence/release correctly on subscriber cancellation, timeout, interruption, failure, or close.
10. Refactor audit, metrics, lifecycle listeners, renderers, exceptions, and builder options around the new protocol. Remove obsolete branches, classes, constructors, aliases, diagnostic maps, and dead API imports; verify no Spring AI types escape.
11. Replace the schema identity with the new pre-release schema and implement explicit incompatible-schema failure without destructive reset. Verify fresh initialization and untouched legacy turnloop tables.
12. Rewrite and expand tests for exhaustive pattern matching, JSON subtype tags, sync/stream parity, exact terminal rules, content/thinking separation, question restart/reload/answer/cancel/race cases, busy queueing, full request snapshots, drain ordering and stop rules, claim release/recovery, lifecycle/failure callbacks, subscription cancellation fencing, provider transport, and schema safety.
13. Update the consumer fixture to compile/run representative pattern matching and data-root/DataSource construction. Rewrite all API/end-user/technical docs and README examples; remove every TurnResult/TurnEvent/manual claim/combined-answer reference.
14. Update branch-specific architecture and decision records, reusable queue/interaction knowledge, prior plan/archive status, and the finalized changelog. Run public API/JAR/dependency/forbidden-symbol scans and review the final diff for old response classes, compatibility shims, unhandled variants, placeholders, and unrelated edits.
15. Run focused suites during each phase and finish with `mvn clean verify`, consumer fixture execution, deterministic provider transport checks, JSON round-trip checks, schema coexistence/incompatibility checks, JAR/module inspection, dependency bans, and `git diff --check`. Commit each completed phase and final closeout records on the dedicated branch.

## Validation Criteria

- Compilation proves `send` and `answer` return `TurnResponse.Outcome`; `stream`, `streamAnswer`, and `drain` return `Flux<TurnResponse>`; no public method returns TurnResult/TurnEvent/TurnDiagnostic/AnswerResult.
- Source and JAR scans find no superseded response classes, old response compatibility accessors/constructors, facade claim/ack/release methods, InteractionHandler, or TurnRequest.stream field.
- Sealed-hierarchy tests exhaustively cover every Progress and Outcome subtype and verify record invariants, especially nonblank Message content and valid interaction/cancellation identifiers.
- Jackson tests round-trip every response subtype using exact stable `type` values and prove no SSE helper remains.
- Direct stream tests prove zero-or-more progress records followed by exactly one expected Outcome and completion; unexpected failures prove onError with no failure outcome and one lifecycle failure callback.
- Blocking and streaming executions produce semantically identical outcomes, execution origins, usage, tool activities, and terminal behavior from one reducer.
- Question tests prove a hidden ask_user call persists and emits ordered Questions with a dedicated interaction ID; pendingQuestions rehydrates the exact response after reconstruction; no delivery ack is required.
- Answer tests prove exact answer cardinality/nonblank validation, blocking and streaming continuation, explicit question/answer pairing, same interaction correlation, repeated question loops with new IDs, and cleanup after success.
- Stale, mismatched, duplicate, concurrent, and already-consumed interaction IDs reject without mutating a newer interaction. ANSWERED crash recovery resumes only the exact stored answer list.
- Cancellation tests prove cancelQuestions returns QuestionsCancelled, frees the session, preserves unrelated queued requests, and rejects stale IDs. Subscription cancellation is observable as TurnCancelled to lifecycle hooks while the cancelled subscriber receives no contradictory terminal record.
- Queue tests prove explicit enqueue and automatic queueing return the same Queued shape, with correct QueueReason and no synthetic turn/provider call. Active and awaiting-question sessions both queue rather than reject.
- Queued persistence tests prove explicit model/tool selections and metadata survive restart while null selections remain deferred until drain execution.
- Drain tests prove oldest-first processing, Queued origins, repeated progress/outcome sequences, internal acknowledgement of Message/Questions, stop-after-Questions, release/stop on interruption/timeout/failure, empty completion, busy failure, pending-Questions emission, and no public claim token leakage.
- Drain cancellation and close tests prove active execution fencing, late-write suppression, current-claim release, later-message preservation, and no gate/registry/drain-owner leaks.
- Stale claim tests prove only expired claims are recovered under the configured lease and document at-least-once crash behavior without claiming exactly-once provider effects.
- Lifecycle tests prove exactly one `onOutcome` per expected terminal execution/control result and exactly one `onFailure` per unexpected failure, with observer exceptions isolated.
- Fresh schema tests pass for dataRoot and DataSource construction. Databases with the superseded magenta_core schema fail clearly without any table deletion, and unrelated turnloop tables remain byte/schema untouched.
- Provider transport, tool execution, compaction, context, audit, metrics, session, interruption, timeout, and graceful-close regression suites remain green under the new response protocol.
- The standalone consumer fixture compiles and runs using only the built magenta-core artifact, pattern-matches the sealed hierarchy, and requires no Spring Boot/application context.
- `mvn clean verify`, deterministic provider transport checks, consumer smoke, dependency bans, public API scan, JAR/module inspection, forbidden-old-symbol scan, and `git diff --check` all pass.
- Documentation and branch-specific specifications describe only the sealed protocol, ordered answers, pending reload, queue receipts, caller-subscribed draining, internal acknowledgement semantics, schema incompatibility behavior, and at-least-once queue guarantee.

## Out of Scope

- Backward-compatible adapters, deprecated overloads, old response aliases, package relocations, or Maven compatibility artifacts.
- Automatic fire-and-forget/background queue draining. Draining begins only when an embedding application subscribes to drain(sessionId).
- Client acknowledgements for receiving Questions or Queued responses.
- Per-question IDs, optional/partial answer lists, free-form combined-answer overloads, or cached replay of completed answer outcomes.
- Exactly-once model/provider/tool execution across crashes or distributed multi-process coordination.
- A Failed TurnResponse variant or emission of both a terminal failure response and stream error.
- Built-in SSE, WebSocket, HTTP controllers, browser UI, Spring Boot integration, or transport-specific framing.
- Migration of the current pre-release magenta_core schema or historical turnloop data. Existing incompatible data is never auto-deleted.
- Automatic processing of queue items after answer/cancel without a drain subscription.
- Provider additions, runtime model-registry mutation, product agents/tasks/jobs/workspaces, authentication, authorization, or unrelated orchestration features.
- Live external-provider validation as a mandatory default-suite requirement.

## Planning Record

- Interactive rounds completed: 15
- User questions answered: 45
- Remaining consequential open questions: none
