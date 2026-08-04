## Prompt

Red-team only the supplied synthesis. Identify overlooked issues, hidden constraints, likely defects, failure modes, invalid assumptions, production-quality gaps, incomplete scope coverage, and any reliance on mocks, stubs, placeholders, deferred work, partial implementations, or shortcuts — without reconstructing or asking for the raw reports.

## Source

Single authoritative synthesis document representing a brainstorm-level unified direction for extracting the Magenta chat turn-loop into a standalone framework (`turnloop-core` / `turnloop-autoconfigure` under `io.mindspice.turnloop`). The synthesis claims full production quality, zero deferred work, and feature-complete coverage.

## Findings

### Critical (would block production readiness or correct-by-construction guarantee)

#### F1. `StreamLifecycle` dependency contradiction

The synthesis claims `turnloop-core` has "zero dependency on `spring-boot-starter-web`, `SseEmitter`, or any servlet API — this is a correctness guarantee enforced by the POM." It then places `StreamLifecycle` in `turnloop-core`, describing it as a utility that "Handles `SseEmitter` creation, event dispatch, timeout, error, and disconnect." `SseEmitter` lives in `org.springframework.web.servlet.mvc.method.annotation`, which is part of `spring-boot-starter-web`. This is a direct, flat contradiction. Either `StreamLifecycle` must move to `turnloop-autoconfigure` (or a dedicated transport module), or the "zero web dependency" guarantee must be walked back. The synthesis cannot have both.

#### F2. No schema evolution strategy

The synthesis deletes the "20-migration `SchemaMigrationRunner`" and replaces it with `CREATE TABLE IF NOT EXISTS` — a creation-only pattern that provides zero migration capability. This means the framework CANNOT evolve its schema across versions. A v1.1 that adds a column, renames a column, or adds an index has no mechanism to apply that change to existing installations without manual SQL intervention or complete data loss. Every database-backed library needs at minimum a version-tracked, idempotent migration runner. `CREATE TABLE IF NOT EXISTS` is suitable only for the very first install. This is a production-quality gap that guarantees breakage on the first schema change.

#### F3. In-memory SQLite default contradicts zero-config startup

The synthesis claims `TurnLoop.builder().build()` works "out of the box with zero configuration" and produces a working instance with "in-memory SQLite." It also states "shipping only SQLite as the v1 default means embedding apps need a DataSource." These are contradictory. Either a `DataSource` bean is auto-provisioned by the framework (requiring `spring-boot-starter-jdbc` and `sqlite-jdbc` on the classpath and a `DataSource` auto-configuration), or the embedding app must provide one. If the framework auto-provisions it, the stated "zero web dependency" rigor is not applied to JDBC — the framework ships with a hard JDBC dependency for its default persistence, meaning every embedder must have `sqlite-jdbc` on their classpath even if they plan to use a different `ConversationMemory` implementation. If the framework does NOT auto-provision a `DataSource`, then `TurnLoop.builder().build()` does NOT work out of the box — it fails with a missing bean.

#### F4. Phase 1 deletes the entire test safety net

The Phase 1 plan says "Delete `tests/` directory." The entire test directory. The gate condition then says "non-web tests pass" — but if the full `tests/` directory is deleted, there are no non-web tests left to run. The framework extraction is a 22-30 day rearchitecture during which the codebase is being aggressively deleted and refactored (Phases 1-5) with zero test coverage until Phase 6. Every phase gate claims "all remaining tests pass" or "unit tests pass," but after Phase 1 there are no tests. Phase 2 claims "ChatService tests pass with mock collaborators" — those tests were deleted in Phase 1. This is either a documentation error (intent was to delete only web-related tests) or a genuine plan defect that leaves 5 phases of surgery with no regression safety net. Either way, the phase plan as written is internally inconsistent on testing.

#### F5. `ask_user` tool-name collision risk

The framework auto-registers `ask_user` as a "framework tool" via `registerFrameworkTool()`, stating it is "not visible to embedding apps through `definitions()`." But the model sees tool names, not Java visibility. If an embedding app registers its own `@Tool(name = "ask_user")` callback, the model receives two tools with the same name — or the framework silently shadows one. The synthesis does not address: does `registerFrameworkTool` prevent an app from registering the same name? Does it silently overwrite? Throw an exception? The `ToolRegistry.register(ToolCallback)` method has no documented behavior for name conflicts with framework-internal tools. This is a latent defect.

#### F6. Open-ended question-pending state during `close()`

`TurnLoop.close()` is specified to "drain all in-flight turns via `TurnGate`" and "release all pending message claims." It does not address turns suspended on a `QuestionPending` event. If a turn is waiting for user input (potentially indefinitely — a user who walked away), `close()` has no documented behavior. Does it time out? Cancel the pending question and emit a `TurnEvent.Failed`? Leave the turn dangling? The `InteractionStore` persists pending questions "so the framework survives a process restart," but `close()` is an intentional shutdown, not a crash. The lifecycle contract for question-pending turns during intentional shutdown is undefined.

### High (significant production gaps that would surface in real usage)

#### F7. No observability surface

The framework defines ~15 SPIs and a state machine but has zero observability hooks: no Micrometer metrics (turn duration, tool-call count, model latency, queue depth, compaction events), no tracing span integration, no health indicator for model connectivity or database reachability. A framework that replaces a production chat service must be monitorable. The synthesis mentions `TurnLifecycleListener.onTurnCompleted(TurnResult)` as the post-turn callback — a single coarse-grained hook is not an observability surface. Production operators need to know: is the Ollama endpoint reachable? Are turns backing up? What is the P95 turn latency? The synthesis is silent on all of this.

#### F8. `ConversationMemory.getWindowed()` has an undeclared `TokenEstimator` dependency

`ConversationMemory` is defined as a standalone SPI. Its `getWindowed(String sessionId, int maxTokens)` method requires knowing how many tokens each message consumes to compute the window. The only way to implement this correctly is with a `TokenEstimator`. But `ConversationMemory` has no `TokenEstimator` in its interface contract. The synthesis states `ContextManager` (framework-internal) coordinates this, but `getWindowed()` is on the public SPI that embedding apps implement. An embedding app providing a custom `ConversationMemory` must independently solve token estimation, without access to the framework's `TokenEstimator`, or the framework must inject it — and the SPI contract doesn't show that injection. This is a design gap: either `getWindowed()` belongs on `ContextManager` (not `ConversationMemory`), or `ConversationMemory` must receive `TokenEstimator` at construction time.

#### F9. Question-user loop and turn serialization interaction unspecified

`TurnGate` serializes turns per session. When a turn suspends on `QuestionPending`, the turn loop "suspends" — what does this mean for the `TurnGate` lock? If the lock is held during the question-pending state, no other turn can start on that session, which is correct. But the synthesis also describes `TurnLoop.answer(sessionId, answer)` which "resumes the turn loop." Does `answer()` acquire the `TurnGate` lock before resuming? If the lock was released during suspend, a concurrent `send()` could start a new turn interleaved with the pending one. If the lock was held, the `TurnGate` must support a "held but dormant" state distinct from "actively executing." The `ReentrantLock`-based `SerialTurnGate` has no concept of a dormant hold — either the lock is held or it isn't. The question-user loop needs a stateful gate that distinguishes "executing a turn" from "waiting for user answer during a turn," and `ReentrantLock` alone cannot model this.

#### F10. No model capability detection or graceful degradation

`ModelRouter` resolves model aliases to Spring AI `ChatModel` instances. Different `ChatModel` implementations have different capabilities: some support streaming, some support tool calling, some support neither. The synthesis doesn't address capability detection. What happens when a model configured for a session doesn't support tool calling but the turn requires tools? What if it doesn't support streaming but `stream()` is called? The current Magenta code likely handles this implicitly (all configured models support both), but a framework consumed by unknown embedding apps must either detect capabilities and fail fast with a clear error, or provide a capability SPI that embedding apps populate.

#### F11. Spring AI types leak through the public API

The framework's `ToolRegistry` works with Spring AI's `ToolCallback` interface. `TurnEvent` carries `ContextUsage` — the synthesis doesn't define whether this is Spring AI's `ContextUsage` or a framework-native record. The `ToolActivity` event carries `ToolActivityData` (undefined), which in the current code carries Spring AI `ToolExecutionResult`. If these types are Spring AI's, the framework's public API is permanently coupled to Spring AI's version cadence. If the framework defines its own equivalents, that mapping layer needs to exist and be specified. The synthesis takes neither position clearly.

#### F12. The 10-14 parameter constructor is an anti-pattern presented as improvement

Phase 2 states: "Collapse `ChatService`'s 5-constructor chain to a single 10-14 parameter constructor." A constructor with 10-14 parameters is a well-known anti-pattern (telescoping constructor, excessive coupling). The synthesis presents this as progress over a 5-constructor chain — it is not. A class with 14 constructor parameters belongs behind a builder or should be decomposed into collaborator groups (e.g., `TurnEngine` with sub-components for tool execution, context assembly, response repair). The synthesis itself defines `TurnLoopBuilder` for the facade and multiple focused SPI interfaces — the same discipline should apply to `DefaultTurnEngine` internally.

#### F13. No defined migration path for Magenta consuming the extracted framework

The entire synthesis is about extracting a framework FROM Magenta. It details what gets deleted, what gets renamed, and what becomes framework SPI. It never describes what Magenta looks like AFTER the extraction. Does Magenta become an embedding app that depends on `turnloop-core`? That would require Magenta to implement all ~15 SPIs with its app-specific logic — a significant engineering effort that isn't in the phase plan. Does the extraction happen on a separate branch, with Magenta continuing independently? The relationship between the extracted framework and the app it was extracted from is undefined. This is not a framework design issue per se, but it is a scope-completeness gap: the synthesis claims to cover "the full requested user scope" but the user's scope presumably includes a working Magenta at the end.

### Medium (design gaps and undefined contracts)

#### F14. Opaque `Map<String, Object> attributes` is type-unsafe by design

`TurnRequest` and `TurnContext` carry `Map<String, Object> attributes` as an extension point. The SPIs (`ToolAccessPolicy`, `CompletionDetector`, `RepairStrategy`) inspect these attributes. With `Map<String, Object>`, every SPI implementation performs unchecked casts from `Object` to expected types. A single attribute-name typo or type mismatch surfaces as a `ClassCastException` deep in the turn loop with no framework-level validation. Production frameworks provide typed accessors (e.g., `TurnContext.getAttribute("key", String.class)`) or a typed key system (e.g., `AttributeKey<String> PLANNING_MODE = AttributeKey.of("planningMode", String.class)`). Raw `Map<String, Object>` is a prototype convenience, not a production API surface.

#### F15. `InteractionHandler.onAnswerReceived` return signature is too narrow

The method returns `String` — "presumably the formatted answer that gets fed back to the model." But the question-user loop may need to inject structured content (e.g., multiple messages, a system message + user answer, or a refusal that aborts the turn). A single `String` return cannot express "the user declined to answer — abort the turn" or "here is a system-level instruction plus the answer." The interface needs at minimum a result type that can signal continue/abort, and ideally support for multiple messages.

#### F16. `ToolResultSummarizer` cannot handle large results safely

`String summarize(String toolName, String arguments, String result)` takes the full tool result as a `String`. Tool results can be arbitrarily large (file contents, web page bodies, database query results). Loading a multi-megabyte result into a `String` and passing it through a summarizer is a memory-pressure and denial-of-service vector. The framework should either enforce a maximum result size before summarization (with truncation), or provide a streaming summarization path. Neither is addressed.

#### F17. At least ten types are referenced but never defined

The synthesis uses the following type names without providing their definition, contract, or even a sketch: `TurnContext`, `TurnOutcome`, `TurnDiagnostic`, `ToolActivityData`, `NoticeKind`, `CompactionContext`, `CompactionResult`, `SessionFilter`, `ConversationSession`, `TurnMessage`. Several of these appear in public SPI signatures or event records. Without definitions, the SPIs cannot be implemented, the event contract cannot be consumed, and the phase gates ("Create all public SPIs... Create... `TurnContext`") are not verifiable.

#### F18. Example tools ship in the production artifact

`EchoTool` and `DateTimeTool` are placed in `turnloop-core` under `io.mindspice.turnloop.example`. This means every embedding app has these tools on its classpath. Unless the framework explicitly does NOT register them by default, they will appear in every `ToolRegistry.definitions()` listing. Example code belongs in test scope, a separate `turnloop-example` module, or documentation snippets — not in the production JAR. If the intent is that they are NOT auto-registered, the synthesis should state this. If they ARE auto-registered (alongside `ask_user`), the framework ships with tools the embedding app may not want, which is a security and UX concern.

#### F19. `PromptAssembler` and `PromptContributor` interfaces are completely unspecified

The synthesis describes `DefaultPromptAssembler` building a "3-layer prompt" from contributors "in priority order." The interfaces for `PromptAssembler` and `PromptContributor` are never defined. What does a `PromptContributor` receive as input? What does it return? How is priority determined — annotation, method parameter, registration order? Is the contributor called per-turn or once? Does it receive `TurnContext`? Without the interface contract, this is a hand-wave, not a design.

#### F20. No Javadoc or API contract requirement

The synthesis lists markdown documentation files (`docs/getting-started.md`, etc.) but never mentions Javadoc. A framework consumed as a library must have comprehensive Javadoc on every public SPI method: thread-safety guarantees, nullability contracts, expected exception behavior, and lifecycle invariants. Markdown docs are supplementary; Javadoc is the primary API contract. Omitting this from the specification means the implementation may ship with undocumented SPIs that embedding apps cannot use correctly without reading framework internals.

### Assumption and Scope Gaps

#### F21. Assumption: Spring AI `ChatModel` implementations are virtual-thread-safe

The synthesis correctly replaces `synchronized` with `ReentrantLock` in the framework's own code for virtual thread compatibility. It does not audit the virtual-thread safety of Spring AI's `ChatModel` implementations (Ollama, OpenAI) which use HTTP clients internally. The JDK's `HttpClient` is virtual-thread-friendly, but OkHttp (commonly used by Spring AI) uses `synchronized` internally on connection pools. If the embedded Spring AI version uses OkHttp, carrier-thread pinning will occur inside the `ChatModel.call()` invocation, which the framework cannot fix. The synthesis should acknowledge this as a dependency-level risk and specify which Spring AI version(s) are validated for virtual-thread safety.

#### F22. Assumption: deleted `tests/` directory contains only web tests

The Phase 1 deletion of `tests/` assumes it contains only web-related tests. In a typical Spring Boot project, `tests/` contains unit tests for services, repositories, utilities, and integration tests — many of which may validate code that survives the extraction (`ChatService`, `ChatMemoryRepository`, `ChatToolRegistry`, `ChatModelRouter`, `ConversationTurnCoordinator`). If these tests are deleted, there is no automated verification that Phase 2-5 refactoring preserves correct behavior. The plan must either explicitly scope the deletion to web-test packages and resources, or acknowledge that the migration proceeds without a test safety net.

#### F23. Assumption: `@Autowired(required = false)` stepping-stone is safe to commit

Phase 2 uses `@Autowired(required = false)` to make 13 collaborators optional during incremental refactoring. This pattern masks configuration errors: if a collaborator is accidentally left as `required = false` after its replacement is built, the application starts successfully but silently provides `null` to dependent code, producing `NullPointerException` at runtime instead of a fast-fail startup error. The phase plan needs an explicit verification step (automated check or grep) that zero `required = false` annotations remain at the phase gate.

#### F24. Assumption: all four framework tables can be reduced to `CREATE TABLE IF NOT EXISTS`

The synthesis creates four tables: `turnloop_conversation_memory`, `turnloop_session_metadata`, `turnloop_pending_messages`, `turnloop_audit_log`. These replace Magenta's existing schema which evolved through 20 migrations. The assumption is that these four tables capture all necessary state. But the existing schema encodes domain constraints (foreign keys, indices, column types) refined through production use. The synthesis provides no table definitions — no column lists, no types, no indices, no foreign keys. The risk is that `CREATE TABLE IF NOT EXISTS` produces a schema that is correct for v1.0.0 but missing constraints that existing production data relies on, or that the new schema cannot be populated from existing Magenta databases without a migration script that doesn't exist.

#### F25. Scope gap: the framework has no stated concurrency model for memory

`ConversationMemory.append()` and `SessionStore.saveModel()` are called during turns. Multiple virtual threads may call these methods concurrently for different sessions. For the same session, `TurnGate` serializes turns — but what about reads? Can `ConversationMemory.get()` for session A run concurrently with `ConversationMemory.append()` for session A? The `TurnGate` serializes turns, but if a read happens outside the turn (e.g., from a REST endpoint listing conversation history), it may race with an in-progress turn's `append()`. The SPI contract must specify thread-safety guarantees, and the default SQLite implementation must document its concurrency behavior (WAL mode, busy timeout, connection pooling).

#### F26. Scope gap: embedding app's HTTP endpoint responsibility is hand-waved

The synthesis states "The actual HTTP endpoint (controller, `SseEmitter` wiring) is the embedding app's responsibility. The `README.md` provides a copy-paste SSE controller example." A copy-paste example in a README is not a contract. If the SSE controller example doesn't handle edge cases (client disconnect during tool execution, backpressure when the client is slow, error recovery when the `Flux<TurnEvent>` errors), every embedding app independently discovers and fixes these bugs. The framework should provide a production-quality controller base class or adapter in `turnloop-autoconfigure` that handles these cases correctly. Pushing transport edge cases to each embedder is a production gap, not a clean separation.

## Options

### For critical findings (F1-F6)

**F1 (`StreamLifecycle` contradiction):** Move `StreamLifecycle` and `TurnEventRenderer.sse()` to `turnloop-autoconfigure`. Keep `TurnEventRenderer.json()` in `turnloop-core` (it depends only on Jackson, already a required dependency). This preserves the "zero web dependency" guarantee.

**F2 (no schema migration):** Implement a minimal version-tracked migration runner — a single-table `turnloop_schema_version` with a `VERSION` integer, a `Migration` interface, and a `MigrationRunner` that applies pending migrations in order. Even a 50-line implementation is sufficient for v1 and establishes the pattern for v1.1+.

**F3 (DataSource default ambiguity):** Decide: either (a) `turnloop-autoconfigure` provides a `DataSource` auto-configuration for `jdbc:sqlite::memory:` when no `DataSource` bean exists and `sqlite-jdbc` is on the classpath, or (b) the framework requires the embedding app to provide a `DataSource` and documents this as the one non-optional setup step. Option (a) is more aligned with the "works out of the box" promise but means every embedder has `sqlite-jdbc` on their classpath transitively.

**F4 (test deletion):** Clarify that Phase 1 deletes ONLY web-related test packages and resources, not the entire `tests/` directory. Specify which test packages are preserved. If the intent genuinely is to delete all tests, acknowledge that Phases 2-5 proceed without automated regression coverage and add Phase 5.5 ("Reintroduce unit tests for extracted framework internals") before the facade is built.

**F5 (`ask_user` name collision):** Add a `ToolRegistry.register()` contract: registering a tool with the same name as a framework-internal tool throws `ToolException` with a clear message. Alternatively, namespace framework tools under a reserved prefix (e.g., `turnloop.ask_user`), though this changes the model-facing name.

**F6 (question-pending close):** Define the `close()` contract for question-pending turns: either (a) cancel all pending questions with a `TurnEvent.Interrupt` before draining, or (b) `close()` times out after a configurable grace period. Option (a) is simpler and matches user expectation that shutdown means "stop waiting for me."

### For high findings (F7-F13)

**F7 (observability):** Add a `TurnLoopMetrics` SPI with default Micrometer implementation in `turnloop-autoconfigure`. Ship at minimum: turn duration histogram, tool-call counter, active-turns gauge, queue-depth gauge. Add a `TurnLoopHealthIndicator` that pings the default model.

**F8 (`getWindowed` coupling):** Move `getWindowed` to `ContextManager` (internal). `ConversationMemory` keeps only `get(String sessionId)` returning all messages. `ContextManager` uses `ConversationMemory.get()` + `TokenEstimator` internally. Embedding apps implementing custom `ConversationMemory` no longer need to implement token windowing.

**F9 (question + TurnGate):** Model the gate as a state machine, not a bare lock: `IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE`. The gate holds state, not just mutual exclusion. The `answer()` method requires the session to be in `AWAITING_ANSWER` state; `send()` requires `IDLE`. This can be built on a `ReentrantLock` internally but the state transitions must be explicit.

**F10 (model capability):** Define a `ModelCapability` enum (STREAMING, TOOL_CALLING, VISION) and a `ModelCapabilityProvider` SPI. `ModelRouter` queries capabilities and `TurnEngine` fails fast with a clear `TurnLoopException` before invoking the model when a required capability is absent.

**F11 (Spring AI type leak):** Define framework-native equivalents for `ToolCallback` (framework `ToolCallback` interface or keep using the name but define it in `io.mindspice.turnloop`), `ContextUsage` (define `io.mindspice.turnloop.ContextUsage` as a framework record), and tool execution results. Map to Spring AI types internally at the `ModelRouter` boundary — the public API never exposes Spring AI types.

**F12 (constructor anti-pattern):** Use a builder or package-private factory for `DefaultTurnEngine` construction. The 14 dependencies map to the SPIs already defined; `DefaultTurnEngine` takes them as constructor parameters grouped by concern (toolExecution, contextAssembly, responseRepair) or via a builder.

**F13 (Magenta migration path):** Add a section to the phase plan: "Phase 7: Re-integrate Magenta as embedding app." Magenta implements the framework SPIs with its app-specific logic (mode-gated tool access, plan detection, repair strategies) and removes the extracted code in favor of `turnloop-core` dependency. This closes the loop and validates the framework design by consuming it.

## Trade-offs

### T1. Fix the `StreamLifecycle` contradiction vs. accept web dependency in core

**Fix it:** Move SSE handling to `turnloop-autoconfigure`. `turnloop-core` stays web-free. Cost: SSE transport is not available to non-Spring embedding apps from core (they must write their own SSE adapter or depend on autoconfigure). This is the correct trade-off — non-Spring apps wouldn't use `SseEmitter` anyway.
**Accept it:** Allow `spring-boot-starter-web` as a core dependency. Cost: every embedder gets embedded Tomcat transitively, the "lightweight framework" claim is undermined, and non-web CLIs pull in a servlet container. Not recommended.

### T2. Full schema migration runner vs. accept `CREATE TABLE IF NOT EXISTS` for v1

**Full migration runner:** ~100-200 lines of additional framework code. Cost: implementation time in Phase 4. Benefit: v1.1 doesn't break existing installations.
**CREATE TABLE IF NOT EXISTS only:** Zero migration code. Cost: v1.1 must either never change schema (unrealistic), require manual migration (production friction), or introduce a migration runner later with backward-compatibility burden. The synthesis's choice to defer this is a false economy.

### T3. Framework-native types vs. expose Spring AI types

**Framework-native types:** Define `io.mindspice.turnloop.ToolCallback`, `ContextUsage`, etc. Cost: mapping layer between framework types and Spring AI types (~200-400 lines). Benefit: zero Spring AI coupling in public API, framework versioning is independent of Spring AI versioning.
**Expose Spring AI types:** Zero mapping code. Cost: every `TurnLoop` consumer has a transitive Spring AI API dependency, framework major version must bump on every Spring AI breaking change. The synthesis is ambiguous on this point; the ambiguity itself is the problem.

### T4. Two-module structure vs. extract transport now

**Two modules (synthesis choice):** Defer `turnloop-transport-sse`. Cost: SSE utilities live in `turnloop-autoconfigure`, which is Spring Boot-specific. Benefit: simpler build.
**Three modules now:** Extract SSE into `turnloop-transport-sse` now. Cost: one additional POM, module dependency graph. Benefit: the `StreamLifecycle` contradiction is resolved architecturally rather than by shoving it into autoconfigure; non-Spring-Boot SSE consumers (Spring Framework without Boot, manual wiring) can use `turnloop-transport-sse` without pulling in autoconfigure. Given F1, this is worth considering.

## Open Questions

1. **What is the actual test deletion scope in Phase 1?** The synthesis says "delete `tests/` directory" but the phase gate expects tests to pass. This must be resolved before Phase 1 begins.

2. **Does the framework auto-provision a `DataSource` bean or require the embedding app to provide one?** The synthesis claims both zero-config startup and SQLite persistence. These are only compatible if auto-configuration provisions an in-memory SQLite `DataSource`. The design should state this explicitly.

3. **Are `TurnContext`, `TurnOutcome`, `TurnDiagnostic`, `ToolActivityData`, `NoticeKind`, `CompactionContext`, `CompactionResult`, `SessionFilter`, `ConversationSession`, and `TurnMessage` framework-native types or Spring AI types?** Each needs a definition before SPIs can be finalized. Since all eight primary reports presumably defined these, the synthesis's omission is likely accidental rather than a genuine gap — but it prevents verification of the design.

4. **What Spring AI version(s) does the framework target, and has virtual-thread safety been validated for those versions' HTTP clients?** The framework's virtual-thread-by-default posture needs a validated dependency baseline.

5. **Is the `TurnLoopBuilder` standalone (no Spring), or does it require Spring `ApplicationContext`?** The synthesis describes both programmatic builder usage and auto-configuration bean creation. If the builder can produce a working `TurnLoop` without Spring, that's a significant architectural claim needing verification.

6. **What happens to Magenta after extraction?** The phase plan ends at Phase 6 with a framework. Is there a Phase 7 for Magenta re-integration, or is Magenta abandoned in favor of a greenfield consumer? The synthesis scope claims to cover the "full requested user scope" — the user's scope almost certainly includes a working Magenta.

7. **Are example tools (`EchoTool`, `DateTimeTool`) auto-registered or must the embedding app explicitly register them?** If auto-registered, they are not examples — they are framework features.

8. **Is the `CompactionStrategy` called during the turn (synchronously) or asynchronously after the turn?** Synchronous compaction adds latency to every turn that triggers it; asynchronous compaction introduces concurrency with the next turn. The synthesis doesn't specify, and the choice materially affects the `TurnGate` design.

## Recommended Next Step

1. **Resolve the four critical findings (F1-F4 specifically) before accepting the synthesis as an implementation contract.** F1 (dependency contradiction) and F2 (no migration) are outright defects. F3 (DataSource ambiguity) will block Phase 0 scaffolding. F4 (test deletion) will determine whether the migration has any safety net.

2. **Define the ten undefined types** (`TurnContext`, `TurnOutcome`, `TurnDiagnostic`, `ToolActivityData`, `NoticeKind`, `CompactionContext`, `CompactionResult`, `SessionFilter`, `ConversationSession`, `TurnMessage`) and the two undefined interfaces (`PromptAssembler`, `PromptContributor`) at the record/interface level before Phase 4 begins. These are the SPIs' type surface; without them, Phase 4 cannot proceed to a verifiable gate.

3. **Add Phase 7: Magenta re-integration** to the plan, specifying that Magenta becomes an embedding app consuming `turnloop-core` and implementing app-specific SPIs. This validates the framework design end-to-end and ensures the extraction doesn't orphan the app it was extracted from.

4. **Commission a focused follow-up on observability** (F7) and model capability detection (F10) — these are not blockers for Phase 0-2 but should be designed before Phase 4 (framework extraction) so the SPI surface includes them from v1 rather than adding them post-release.

5. **Audit the concrete Spring AI version and its HTTP client for virtual-thread pinning** (F21) before committing to virtual-thread-by-default. A 30-minute investigation now prevents a class of production issues that cannot be fixed within the framework.

6. **Require Javadoc on all public SPI methods** as a Phase 6 gate criterion alongside the markdown documentation. "API documentation complete" should mean "every public method has a Javadoc specifying its contract, thread safety, nullability, and failure modes."

The synthesis is strong on vision and conflict resolution. It is weak on type-level precision, lifecycle edge cases, and production operations concerns. The findings above are not reasons to reject the direction — they are the hardening work that separates a brainstorming synthesis from an executable implementation contract.
