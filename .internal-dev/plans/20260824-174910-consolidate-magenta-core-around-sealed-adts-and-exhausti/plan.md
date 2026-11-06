# Consolidate Magenta Core around sealed ADTs and exhaustive pattern matching

## Feature

Perform a coordinated clean-break refactor of Magenta Core's tool, message, durable-interaction, provider-configuration, and turn-gate models. Follow the recently completed TurnResponse approach: framework-owned finite protocols become sealed hierarchies with nested records, illegal state combinations become unconstructable, consumers use exhaustive pattern matching, one typed value drives all downstream projections, and caller extension SPIs remain open. Expand TurnResponse with typed question-rejection outcomes and introduce a new incompatible Magenta Core schema that rejects prior framework schemas without mutating them.

## Required Behavior

- Replace ToolCallback's String result with a public sealed ToolResult root using nested Success and Failure records. Success carries the callback output; Failure carries a nonblank safe message and a closed ToolFailureCategory.
- Define ToolFailureCategory around framework stages: NOT_FOUND, ACCESS_DENIED, INVALID_ARGUMENTS, EXECUTION_FAILED, and LOOP_EXCEEDED. Normalize callback-thrown exceptions into safe typed failures and preserve existing recoverable tool-loop behavior rather than terminating the turn.
- Allow annotated @Tool methods to keep returning ordinary domain values, automatically wrap those values as ToolResult.Success, pass returned ToolResult values through unchanged, and normalize thrown exceptions as typed failures.
- Replace ToolActivityData plus ToolActivityStatus with a sealed ToolActivityData hierarchy containing Completed and Failed variants. Both expose only current caller-visible facts: tool name, tool call ID, and summary; Failed additionally exposes ToolFailureCategory. Do not expose raw arguments, raw results, exceptions, or timing through TurnResponse.
- Use stage-specific ToolResult and ToolActivityData roots because callback output and caller-visible activity have different disclosure requirements, while sharing the same failure taxonomy and conversion rules.
- Make the typed tool invocation outcome the sole source for model tool responses, activity progress, durable tool-message metadata, audit, metrics, summarization, and loop-guard observation. Remove boolean success, nullable error-message, and duplicate status side channels from public/internal tool result carriers where the ADT supplies the same fact.
- Keep ToolCallback implementable despite changing its return type, and keep ToolRegistry, ToolAccessPolicy, ToolResultSummarizer, RepairStrategy, model/prompt/compaction SPIs, and persistence SPIs open for embedding applications.
- Replace TurnMessage's raw role string and nullable tool identity with a public sealed TurnMessage root and nested System, User, Assistant, and Tool records. Require tool name and call ID only for Tool; make metadata immutable and non-null for every variant; preserve empty-text cases that are valid for provider/tool-call transcripts.
- Give TurnMessage stable Jackson type tags `system`, `user`, `assistant`, and `tool`, and convert provider adapters, memory, compaction, audit, marker handling, and persistence through exhaustive pattern matching with no unknown-role fallback to user messages.
- Replace InteractionStore.Interaction's enum/null/empty-list state encoding with a sealed Interaction hierarchy containing Pending, Answered, and Cancelled snapshots. Each variant contains a shared validated InteractionInfo value plus only its legal payload.
- InteractionInfo must retain the durable common facts required by the current recovery contract: dedicated interaction ID, session correlation, exact Questions snapshot, model, explicit tool selection semantics, request and continuation metadata, and created/updated timestamps. Answered carries exactly one nonblank ordered answer per question; Cancelled carries cancellation time and nonblank reason.
- Remove InteractionState as the persisted Java-state model. Replace MutationResult(boolean applied, state) with sealed explicit variants: Applied(resulting snapshot), Missing, AlreadyAnswered, and AlreadyCancelled. Store implementations must return the exact race-winning terminal result after CAS contention.
- Expand TurnResponse.Outcome with separate QuestionsMissing, QuestionsAlreadyAnswered, and QuestionsAlreadyCancelled variants, each with stable snake_case JSON tags and validated session/interaction correlation facts. Do not use a secondary rejection enum.
- Make blocking answer/cancel return these expected rejection outcomes without provider execution when the target is absent or terminal. Make streamAnswer emit exactly one rejection outcome and complete without progress in the same cases. Wrong or stale IDs must never expose or mutate a newer pending interaction.
- Change cancelQuestions' signature to the broader TurnResponse.Outcome needed for successful QuestionsCancelled and rejection outcomes. Preserve typed validation/configuration/busy exceptions: invalid answer cardinality or blank values remain validation errors, and transient execution ownership remains TurnLoopBusyException.
- Send every new expected interaction outcome through normal standalone lifecycle, audit, and metrics observation exactly once. Preserve the rule that unexpected execution failures use TurnFailure and the error channel, not TurnResponse variants.
- Replace ModelConfig plus EndpointType with a sealed public ModelConfig hierarchy using nested Ollama, OpenAiCompatible, and DeepSeek records and exhaustive provider routing.
- Retain shared optional apiKey, contextLength, and thinkLevel components on all provider variants as directed, but validate each variant's supported combinations at construction: Ollama rejects API keys, OpenAI-compatible permits an optional key, DeepSeek requires a key and rejects nonzero thinking level, context length must be positive when present, and thinking level must remain in the supported range.
- Give every public ADT introduced or changed by this job stable Jackson `type` discriminators, including ToolResult, ToolActivityData, TurnMessage, Interaction snapshots, interaction mutation outcomes, and ModelConfig. ModelConfig JSON must serialize API keys normally for full round trips, per the accepted decision; diagnostics and logging must nevertheless avoid incidental secret disclosure.
- Refactor SerialTurnGate's internal SessionState into a sealed immutable state family that structurally represents Idle, Executing, TimingOut, and AwaitingAnswer. Remove the redundant awaitingAnswer boolean and nullable token/timestamp combinations. Keep public TurnGate.GateState as the existing small projection and keep AcquireResult sealed.
- Introduce a clean-break framework schema identity. Construct only the new message and interaction representations on a fresh Magenta Core schema; detect and reject the immediately prior or any incompatible magenta_core_* schema before initialization can reuse its tables; never drop, rewrite, or migrate it automatically; leave unrelated historical turnloop_* tables untouched.
- Store TurnMessage variants in one tagged message table with database checks requiring tool identity exactly for tool rows. Store interactions in one discriminated common table with state-dependent constraints plus ordered answer rows, and map rows strictly into sealed Java variants.
- Delete superseded public classes/enums/constructors and compatibility helpers rather than shipping deprecated bridges. Update all examples, consumer fixtures, documentation, specifications, API/JAR scans, and tests to expose only the sealed contracts.
- Preserve existing provider support, queue/drain behavior, question durability and crash recovery, context management, compaction, audit, metrics, interruption fencing, caller-subscribed draining, and one-outcome stream guarantees except for the explicitly accepted typed interaction-rejection behavior.

## Targets

- `src/main/java/io/mindspice/magenta/core/ToolCallback.java, ToolActivityData.java, ToolActivityStatus.java, ToolException.java, ToolLoopGuard.java, and new ToolResult/ToolFailureCategory support`: Publish the sealed callback-result and activity hierarchies, align tool failure categories, remove the status enum and boolean/null result carriers, and validate all variant invariants.
- `src/main/java/io/mindspice/magenta/core/DefaultTurnEngine.java and DefaultToolRegistry.java`: Create one authoritative typed tool-invocation conversion path; adapt direct and annotated callbacks; exhaustively map success/failure into model responses, summaries, activity progress, durable messages, repair/loop behavior, and safe normalized failures.
- `src/main/java/io/mindspice/magenta/core/spi/ToolResultSummarizer.java, AuditWriter.java, TurnLoopMetrics.java and implementations`: Consume typed tool values instead of duplicate success booleans/status/null error combinations while preserving current disclosure and operational behavior.
- `src/main/java/io/mindspice/magenta/core/TurnMessage.java`: Replace the role-string record with nested sealed System/User/Assistant/Tool records, common accessors, immutable metadata, stable JSON tags, and constructor invariants.
- `src/main/java/io/mindspice/magenta/core/persistence/SqliteConversationMemory.java, SpringAiConversationMemoryAdapter.java, ContextManager.java, DefaultCompactionStrategy.java, DefaultTurnLoop.java, and message consumers`: Use exhaustive message pattern matching for storage, provider conversion, compaction, interaction markers, audit, and history processing; remove string-role switches and unknown-role fallbacks.
- `src/main/java/io/mindspice/magenta/core/spi/InteractionStore.java and InteractionState.java`: Publish sealed Pending/Answered/Cancelled snapshots, shared InteractionInfo, and Applied/Missing/AlreadyAnswered/AlreadyCancelled mutation outcomes; remove enum/null state encoding.
- `src/main/java/io/mindspice/magenta/core/persistence/InMemoryInteractionStore.java and SqliteInteractionStore.java`: Implement strict variant persistence and ID-qualified answer/cancel CAS, return the exact explicit mutation outcome under races, preserve exact Questions rehydration and answered crash recovery, and prevent stale IDs from affecting newer interactions.
- `src/main/java/io/mindspice/magenta/core/TurnResponse.java, TurnLoop.java, DefaultTurnLoop.java, renderer and lifecycle integration`: Add separate question rejection Outcome variants and JSON tags; return or emit them for missing/answered/cancelled submissions; broaden cancelQuestions appropriately; preserve busy/validation exceptions and exactly-once outcome observation.
- `src/main/java/io/mindspice/magenta/core/ModelConfig.java, EndpointType.java, TurnLoopBuilder.java, DefaultModelRouter.java, and provider adapters`: Replace enum-discriminated configuration with sealed Ollama/OpenAiCompatible/DeepSeek records, variant-local validation, exhaustive routing, stable polymorphic JSON, normal API-key JSON serialization, and redacted diagnostics/logging; remove EndpointType.
- `src/main/java/io/mindspice/magenta/core/SerialTurnGate.java and spi/TurnGate.java`: Represent internal idle/executing/timing-out/awaiting-answer state as sealed immutable values under the existing lock/fencing protocol, remove redundant fields, and keep the public state projection stable.
- `src/main/resources/schema.sql, MigrationRunner.java, and persistence initialization`: Publish the new incompatible schema identity, strengthen tagged message and discriminated interaction constraints, reject prior magenta_core_* schemas without mutation, and preserve unrelated turnloop_* tables.
- `src/test/java/io/mindspice/magenta/core/** and persistence/provider tests`: Replace old API assertions and add exhaustive ADT, JSON, callback, interaction race/restart, message storage, provider validation/routing, gate concurrency/timeout, lifecycle, audit, metrics, and schema safety coverage.
- `consumer-fixture/**, README.md, docs/api/**, docs/technical/**, docs/end-user/**`: Demonstrate exhaustive pattern matching for responses, tools, messages, interactions, and model configuration; document source/schema incompatibility, API-key JSON behavior, and retained open SPI boundaries.
- `.internal-dev/specifications/api.md, architecture.md, decisions.md, schema.md; knowledge and changelogs`: Record the clean-break ADT contract, interaction rejection semantics, schema policy, extension-boundary rationale, validation evidence, reusable lessons, and final Git provenance.

## Constraints

- Planning only in this workflow; do not implement until the published plan is explicitly invoked.
- Remain one Java 25 transport-neutral `io.mindspice:magenta-core` JAR with programmatic TurnLoopBuilder composition and no Spring Boot application, auto-configuration, servlet, HTTP route, UI, or configuration-file loading.
- Public API remains under `io.mindspice.magenta.core`; Spring AI/provider implementation types remain internal.
- Use sealed hierarchies only for framework-owned finite protocols and internal states. Do not seal caller implementation SPIs or add speculative interfaces, factories, policies, providers, or extension hooks.
- This is a deliberate source- and schema-incompatible clean break. Do not add deprecated aliases, compatibility constructors, adapters for the old public types, or automatic data migration/reset.
- Never delete or rewrite an incompatible existing database. Historical `turnloop_*` tables remain unread and untouched.
- Maintain distinct execution turn IDs, interaction IDs, queued message IDs, and internal claim/fence tokens.
- Keep unexpected failures on exception/error channels. Only the accepted durable interaction-state rejections join TurnResponse.Outcome.
- ModelConfig API keys are intentionally present in serialized JSON per user direction, but must not leak through framework logs, exception details, metrics, or incidental diagnostic rendering.
- Preserve unrelated edits and existing behavior outside the declared ADT conversion targets.

## Assumptions

- Magenta Core remains pre-release, so all direct ToolCallback implementers, custom persistence implementations, and consumers can update in lockstep.
- A fresh Magenta Core database is acceptable for this branch; operators with a prior magenta_core_* schema will receive a clear incompatibility error and must explicitly choose a new data root/database.
- The existing three bundled provider families are the complete closed ModelConfig hierarchy for this job; adding arbitrary provider kinds is out of scope.
- Null model/tool selections in TurnRequest and QueuedRequest remain intentional deferred-resolution semantics and are not converted into additional selection ADTs.
- ConversationSession flags, SessionFilter tri-state fields, ContextUsage, CompactionResult, the overall mutable engine phase machine, and queue claim carriers remain in their current conceptual forms unless a direct compile-time adaptation is required.
- The database may use discriminators and constrained nullable columns as a storage representation even though the Java API exposes sealed variants.
- ToolResult.Success may carry an empty but non-null output because annotated/provider tool calls can validly produce no textual content; summaries and failure messages remain nonblank where caller-visible.
- Question rejection outcomes contain only the attempted session/interaction correlation and observation facts needed by clients; they do not disclose stored ordered answers or a newer pending interaction.
- Stable JSON discriminator property remains `type` with snake_case values, matching TurnResponse conventions.

## Settled Decisions

- Implement all five recommended areas: tool outcomes, interaction lifecycle/mutations, TurnMessage roles, internal gate state, and provider-specific ModelConfig.
- Use a clean-break public API with no one-release adapters.
- Use a clean-break schema and the same reject-without-mutation safety posture as the TurnResponse refactor.
- Change ToolCallback to return a public ToolResult ADT; normalize thrown callback exceptions to typed failures and continue existing tool-loop behavior.
- Use separate ToolResult and ToolActivityData sealed roots with shared framework-stage failure categories; expose current activity facts only.
- Use nested variant naming under sealed roots, following TurnResponse style.
- Represent durable interactions as sealed snapshots containing shared InteractionInfo, not as one record with a nested state value.
- Use explicit mutation variants Applied, Missing, AlreadyAnswered, and AlreadyCancelled.
- Give every public ADT stable Jackson polymorphic tags.
- Serialize ModelConfig API keys normally in JSON, while redacting framework logs and diagnostics.
- Convert durable interaction rejections into separate TurnResponse outcomes; retain busy and validation exceptions.
- Automatically wrap ordinary annotated tool returns, pass through ToolResult values, and normalize annotated-tool exceptions.
- Use sealed provider-specific ModelConfig records but retain shared optional provider option fields with variant validation.
- Use a single tagged message table and a single discriminated interaction table with constraints rather than per-variant tables.

## Implementation Approach

1. Establish the clean-break contract in focused compile-time/protocol tests: enumerate every planned sealed root and permitted subtype, exact nested names, common accessors, validation rules, Jackson `type` tags, and removed symbols before rewiring runtime behavior.
2. Introduce ToolFailureCategory, sealed ToolResult, and sealed ToolActivityData. Change ToolCallback.call, update examples/test doubles, and delete ToolActivityStatus once all consumers compile against variants.
3. Refactor DefaultToolRegistry and DefaultTurnEngine around one internal typed invocation normalization function. Cover direct callbacks, ordinary annotated returns, annotated ToolResult pass-through, missing tools, coercion/access failures, thrown exceptions, summarization, and ask_user's framework-owned activity.
4. Rewire tool-loop guard, audit writer, metrics, durable metadata, response progress, interaction snapshot serialization, and summarization to pattern-match typed tool values. Remove redundant boolean/status/error-message carriers and verify safe model-visible failure text.
5. Replace TurnMessage with nested sealed role records and update every construction/conversion site. Use exhaustive switches in Spring AI adapters, compaction, audit, interaction markers, history handling, and persistence; remove role-string defaults.
6. Update the fresh message schema and SQLite mapping to stable role tags with checks for tool-only identity fields. Add round-trip and malformed-row tests for every variant.
7. Replace InteractionState/Interaction record/MutationResult with InteractionInfo, sealed snapshots, and explicit mutation outcomes. Update in-memory and SQLite stores together so each answer/cancel CAS rereads and returns the exact winning state.
8. Add QuestionsMissing, QuestionsAlreadyAnswered, and QuestionsAlreadyCancelled to TurnResponse, renderer metadata, lifecycle naming/timestamps, and facade methods. Rework blocking/streaming answer and cancellation preparation so durable rejection returns an outcome without model execution, while busy and invalid input continue to throw.
9. Reprove interaction durability end to end: pending snapshot rehydration, exact answer cardinality, cancellation, stale/mismatched IDs, concurrent answer/cancel, answered crash recovery, marker cleanup, lifecycle/audit/metric notification, and queue ordering around questions.
10. Replace EndpointType/flat ModelConfig with nested sealed provider records. Move provider-option validation into variant constructors, update builder alias/endpoint validation, route providers through exhaustive pattern matching, and ensure all three variants serialize/deserialize with API keys included while logs/errors remain redacted.
11. Refactor SerialTurnGate.SessionState into immutable sealed Idle/Executing/TimingOut/AwaitingAnswer states. Preserve lock ownership and token-aware fencing, project internal timing-out state to public EXECUTING, and remove redundant nullable/boolean fields.
12. Publish the new schema identity and coordinated fresh schema for sealed messages and interactions. Add explicit detection for the immediately prior and malformed Magenta Core schemas before CREATE statements, with no destructive path; verify unrelated turnloop_* tables remain untouched.
13. Update lifecycle listeners, failure mapping, public API scans, forbidden-symbol scans, package checks, and JAR contents so no superseded constructors, enums, endpoint discriminators, role strings, or interaction-state classes remain exposed.
14. Rewrite the consumer fixture and all API/configuration/persistence/tool/interaction documentation around exhaustive pattern matching. Clearly document open versus sealed boundaries, expected rejection outcomes, source/schema incompatibility, and API-key JSON disclosure.
15. Update branch-specific architecture/API/schema/decision specifications, reusable knowledge, and changelog provenance. Review the final diff for accidental API growth, nullable-union regressions, unhandled variants, compatibility shims, placeholders, and unrelated changes.
16. Run focused suites after each domain, then complete the full validation matrix: `mvn clean verify`, deterministic provider tests, standalone consumer fixture, dependency/package scans, JAR/public API inspection, forbidden-old-symbol scans, schema coexistence/incompatibility tests, and `git diff --check`. Repair every in-scope failure before closeout.

## Validation Criteria

- Java compilation and reflection tests prove ToolResult, ToolActivityData, TurnMessage, InteractionStore.Interaction, InteractionStore.MutationResult, ModelConfig, TurnResponse, TurnGate.AcquireResult, and internal gate state have exactly the intended permitted variants.
- Exhaustive pattern-matching tests handle every subtype without role/status/state string fallbacks. Source and built-JAR scans find no ToolActivityStatus, InteractionState, EndpointType, raw TurnMessage constructor, old Interaction record constructor, boolean tool success carrier, or deprecated compatibility bridge.
- Jackson tests round-trip every new public variant with exact `type` tags, including all ModelConfig variants and API keys. Separate tests prove configuration errors, logs, audit payloads, and metrics do not incidentally disclose API keys.
- Direct ToolCallback tests prove Success and Failure behavior; thrown callbacks normalize to EXECUTION_FAILED without terminating otherwise recoverable turns; annotated ordinary values auto-wrap; annotated ToolResult values pass through; missing, denied, invalid-argument, execution, and loop-exceeded paths use the accepted categories.
- Tool integration tests prove one typed outcome consistently determines model response text, caller-visible activity, durable tool message, audit, metrics, and loop-guard observation, with no contradictory status/boolean/null combination.
- TurnMessage tests prove legal role-specific construction, immutable metadata, required tool identity, exhaustive Spring AI conversion, persistence round trips, compaction/audit behavior, stable JSON tags, and database rejection of invalid role/tool combinations.
- Interaction constructor tests prove Pending cannot contain terminal payloads, Answered requires exact ordered nonblank answers, Cancelled requires time/reason and no answers, IDs correlate with the exact Questions snapshot, and all collections are immutable.
- In-memory and SQLite contract tests prove Applied/Missing/AlreadyAnswered/AlreadyCancelled results under normal, duplicate, stale, mismatched, and concurrent answer/cancel operations; the exact race winner is reported and no newer pending interaction is exposed or mutated.
- Facade tests prove answer, streamAnswer, and cancelQuestions return or emit the correct QuestionsMissing/QuestionsAlreadyAnswered/QuestionsAlreadyCancelled outcomes without provider execution; stream rejection emits exactly one outcome and completes; successful continuation semantics remain unchanged.
- Busy ownership still raises TurnLoopBusyException, malformed answers still fail validation, unexpected execution failures still use TurnFailedException/onError, and every expected rejection outcome reaches lifecycle/audit/metrics exactly once.
- Restart tests prove exact pending Questions rehydration, answered continuation recovery, cancellation persistence, ordered answers, tool activities, execution origin, timestamps, model, and continuation metadata under the sealed interaction representation.
- Provider configuration tests prove each variant's accepted and rejected option combinations, URI/alias/model validation, exhaustive routing to the correct deterministic adapter, capability behavior, API-key requirements, and unsupported DeepSeek thinking rejection.
- Gate tests prove idle/executing/timing-out/awaiting-answer transitions, answer reacquisition, timeout fencing success/failure, release, drain, close, concurrent acquisition, and absence of stale token/state leaks.
- Fresh-schema tests pass for both dataRoot and caller DataSource construction. Databases with the immediately prior or malformed magenta_core_* schema fail clearly before mutation; table/data snapshots prove no deletion or rewrite; unrelated turnloop_* tables remain unchanged.
- The complete provider, queue/drain, context, compaction, audit, metrics, session, interruption, timeout, and graceful-close regression suites remain green.
- The standalone consumer fixture compiles and runs against only the built magenta-core JAR and demonstrates exhaustive matching for TurnResponse, ToolResult/ToolActivityData, TurnMessage, interactions/mutations, and ModelConfig without Spring Boot or an ApplicationContext.
- `mvn clean verify`, deterministic provider transport checks, dependency bans, package/public API scans, JAR inspection, forbidden-symbol scans, consumer smoke, schema safety checks, and `git diff --check` all pass.
- README, API/end-user/technical docs, specifications, knowledge, and changelog describe only the new ADT contracts and accurately state compatibility, JSON, persistence, error-channel, and extension-boundary behavior.

## Out of Scope

- Backward-compatible constructors, adapters, deprecated aliases, old response/state/status/endpoint types, or automatic migration of prior Magenta Core data.
- Sealing ToolCallback implementations or any caller-provided registry, policy, summarizer, repair, prompt, model-routing, compaction, lifecycle, audit, metrics, or persistence SPI.
- Adding providers, dynamically extensible provider kinds, provider plugins, live credential requirements, or configuration-file/YAML/JSON loading through TurnLoopBuilder.
- Changing queue receipt/claim/drain semantics, exposing claim tokens, background auto-draining, distributed coordination, or exactly-once provider/tool execution guarantees.
- Converting TurnRequest model/tool null-selection semantics, SessionFilter tri-state values, ConversationSession flags, ContextUsage, CompactionResult, or all simple enums into sealed hierarchies.
- A wholesale rewrite of DefaultTurnEngine's mutable phase machine beyond adaptations required to consume the new tool/message/interaction contracts.
- Adding interaction command queues, a QuestionsBusy outcome, partial answers, per-question IDs, cached duplicate-answer replay, or disclosure of stored answers/newer pending interactions in rejection outcomes.
- Merging expected TurnResponse outcomes with unexpected failure types or adding a general Failed response variant.
- Exposing raw tool arguments/results, exception causes, timing, or secrets in caller-visible activity outcomes.
- Spring Boot, HTTP/SSE/WebSocket framing, UI assets, authentication/authorization, Micrometer adapters, auto-configuration, or unrelated product-domain features.

## Planning Record

- Interactive rounds completed: 8
- User questions answered: 24
- Remaining consequential open questions: none
