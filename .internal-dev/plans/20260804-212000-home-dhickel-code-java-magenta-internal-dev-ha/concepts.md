# TurnLoop Core — Framework Extraction Plan

## Architecture

`turnloop-core` is a standalone, embeddable Java framework under `io.mindspice.turnloop` that owns the AI chat turn-loop lifecycle. It is extracted from Magenta's `ChatService.toolChat()` state machine into two Maven artifacts:

- **`turnloop-core`** (group `io.mindspice`): the framework JAR containing the turn engine, all SPIs, SQLite-backed defaults, SSE transport utilities, `MarkdownRenderer`, example tools, and internal wiring. Zero dependency on servlet APIs or Spring MVC.
- **`turnloop-autoconfigure`** (group `io.mindspice`): Spring Boot auto-configuration with `TurnLoopAutoConfiguration`, `TurnLoopProperties`, `DataSource` auto-provisioning, `StreamLifecycle` SSE adapter, `TurnLoopHealthIndicator`, and `TurnLoopMetrics` (Micrometer). Also serves as `spring-boot-starter-turnloop` meta-starter.

Magenta after re-integration becomes an embedding app depending on `turnloop-core`, implementing app-specific SPIs and removing all extracted code.

The framework architecture is layered bottom-up:
1. Core types and records (data carriers with zero framework dependencies)
2. SPI interfaces (pluggable extension points, each with a production-quality default)
3. Framework-internal interfaces (ModelRouter, TurnExecutor, ActiveTurnRegistry — map to Spring AI internals)
4. Persistence layer (SQLite-backed defaults, self-migrating schema, `MigrationRunner`)
5. Engine (DefaultTurnEngine — 6-state machine: PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE)
6. Facade (TurnLoop interface + DefaultTurnLoop + TurnLoopBuilder)
7. Auto-configuration (Spring Boot integration, properties binding, health, metrics, SSE adapter)

The `ModelRouter` boundary is the sole mapping point between framework-native types and Spring AI types. No Spring AI type appears in any public API signature.

This plan refines the authoritative handoff's seven recommended phases into fourteen implementation phases. All phase number references in this document use the fourteen-phase scheme. The mapping from handoff phases is: handoff Phase 0 → plan Phase 01, handoff Phase 1 → plan Phase 02, handoff Phase 2 → plan Phase 04, handoff Phase 3 → plan Phase 05, handoff Phase 4 → plan Phases 06–09, handoff Phase 5 → plan Phases 10–11, handoff Phase 6 → plan Phases 12–13, handoff Phase 7 → plan Phase 14. The authoritative handoff with complete type definitions, SPI signatures, database DDL, and gate inventories is at `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`.

## Conceptual Approach

**Extract, don't rewrite.** The turn-loop state machine in `ChatService.toolChat()` is production-hardened. It is extracted intact, with app-specific branches replaced by SPI calls. The 6-state cycle, tool-loop guard, context management, compaction, repair, and streaming event emission preserve their current behavior.

**Delete aggressively, verify continuously.** Each phase removes a vertical slice of Magenta's app-shell (web, orchestration, skills, plan, task, project, system, job, execution, app-specific tools), verifies the remaining code compiles and passes tests, then proceeds. No partial or stubbed deletions.

**Framework-native types throughout.** All public API types are defined in `io.mindspice.turnloop`. The `ModelRouter` maps internally to Spring AI's `ChatModel` and `ToolCallback`. Verification: automated grep in Phase 13 confirms zero `org.springframework.ai` imports in public API types.

**SPI-first with production defaults.** Every one of 20 SPIs ships with a default implementation. `TurnLoop.builder().build()` produces a working instance with in-memory SQLite and default Ollama config — zero external files or configuration required.

**Sequential phases.** Fourteen sequential phases with no parallelism. Each phase is a cohesive, single-agent-implementable unit. Validation gates enforce PASS-before-dependent barriers.

## Scope

### In Scope
- Extract `ChatService.toolChat()` turn-loop state machine into `DefaultTurnEngine`
- Define all 20 SPIs with framework-native types and production-quality defaults (full signatures in handoff Appendix A)
- Build `TurnLoop` facade and `TurnLoopBuilder`
- Create `turnloop-core` and `turnloop-autoconfigure` Maven modules
- Implement SQLite-backed defaults for `ConversationMemory`, `SessionStore`, `MessageQueueStore`
- Implement `SqliteAuditWriter` as optional component with audit table (handoff Appendix B DDL)
- Implement minimal version-tracked `MigrationRunner`
- Implement `TurnLoopMetrics` Micrometer default and `TurnLoopHealthIndicator`
- Implement `MarkdownRenderer` utility with optional dependencies and graceful degradation
- Implement typed `TurnContext` attribute accessor with `AttributeKey<T>`
- Implement stateful `TurnGate` with timeout and drain semantics
- Implement `StreamLifecycle` SSE adapter in autoconfigure with backpressure and disconnect handling
- Implement `ThinkingExtractor` SPI with composite default
- Implement `ToolLoopGuard` with identical-call and error-window tracking
- Delete all web, orchestration, skill, plan, task, project, system, and app-specific tool packages from Magenta
- Simplify `AiConfig` and `AiUserConfigConfiguration`
- Delete Magenta-specific types listed in the handoff Targets
- Delete 20 migration files and `SchemaMigrationRunner` from Magenta
- Rename surviving Magenta classes to framework names
- Create framework-owned `schema.sql` with five tables (four data + version)
- Re-integrate Magenta as embedding app (Phase 14)
- Javadoc on every public SPI method
- Markdown documentation (README + 9 docs files)
- Full unit test suite for every SPI and state-machine transition
- Integration tests as specified in handoff Validation
- Auto-configuration smoke test
- Concurrency stress test
- Dead code scan

### Out of Scope
- `turnloop-transport-sse` module (deferred)
- `turnloop-example` module (deferred; example tools in core are sufficient)
- WebSocket or gRPC transport bindings
- In-memory (non-SQLite) `ConversationMemory` implementation
- Multi-module build beyond two modules
- Any web UI, SimplyPages, HTMX, or browser surface in the framework
- Authentication, authorization, or multi-tenancy
- Process execution, shell tools, file tools, or workspace filesystem operations
- Task, plan, workflow, job, orchestration, or agent lifecycle
- Skill discovery, trust, snapshots, or activation
- Project materialization or context binding
- System chat or bounded-read profiles
- Artifact search, preview, retention, or publication
- Schedule or reaction automation
- Agent console or operational UI
- Playwright or browser-based validation
- CLI or non-Spring embedding support
- Migrating existing Magenta databases to the framework schema

## Features

1. **6-state turn engine**: PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE, extracted intact from `ChatService.toolChat()`
2. **Dual execution modes**: `Flux<TurnEvent> stream(TurnRequest)` for SSE streaming, `TurnResult execute(TurnRequest)` for synchronous calls
3. **Interrupt support**: `ActiveTurnRegistry` polling pattern with `interruptToken` on `TurnEvent.Started`
4. **Single serialization gate**: `SerialTurnGate` using `ReentrantLock` replaces Magenta's dual semaphore + turn coordinator, eliminating the `streamLocks` race
5. **Zero ThreadLocal**: `activeTrustedPolicies` becomes a turn-scoped carrier in `TurnContext`
6. **No TurnMode/PlanMode**: Removed entirely; replaced by `ToolAccessPolicy`, `CompletionDetector`, `RepairStrategy` SPIs
7. **20 pluggable SPIs**: ToolRegistry, ToolAccessPolicy, ToolResultSummarizer, InteractionHandler, InteractionStore, ConversationMemory, SessionStore, TokenEstimator, CompactionStrategy, CompletionDetector, RepairStrategy, TurnGate, MessageQueue, MessageQueueStore, AuditWriter, PromptContributor, ModelCapabilityProvider, TurnLoopMetrics, TurnLifecycleListener, ThinkingExtractor
8. **Transport-agnostic events**: `TurnEvent` sealed hierarchy with 8 subtypes, `TurnEventRenderer.json()` and `.sse()`, `StreamLifecycle` SSE adapter in autoconfigure
9. **SQLite-backed defaults**: In-memory SQLite, self-migrating `schema.sql` with four data tables + version table (handoff Appendix B), `MigrationRunner`
10. **Tool framework**: Two-stage `ToolRegistry`, name-collision detection, `ToolAccessPolicy` static factories, `ToolResultSummarizer` with truncation, `ToolLoopGuard`, `ToolArgumentCoercionModule`
11. **Message queueing**: Claim/ack/release with stale-claim recovery
12. **Question-user loop**: `InteractionHandler` callback + `InteractionStore` persistence; `ask_user` auto-registered as framework-internal tool
13. **Prompt assembly**: `DefaultPromptAssembler` with 3-layer structure from ordered `PromptContributor` implementations
14. **Observability**: `TurnLoopMetrics` (Micrometer default), `TurnLoopHealthIndicator`, `TurnLifecycleListener`
15. **Graceful shutdown**: `TurnLoop.close()` drains gate, cancels pending questions, releases resources, closes models
16. **Sealed exception hierarchy**: `TurnLoopException` → `TurnFailedException`, `ConversationNotFoundException`, `ToolException` (sealed), `ConfigurationException`
17. **Thinking extraction**: `CompositeThinkingExtractor` handles Ollama metadata, DeepSeek reasoningContent, `<think>` tag fallback
18. **Example tools**: `EchoTool`, `DateTimeTool` in `turnloop-core` (opt-in, not auto-registered)
19. **Configuration validation**: `TurnLoopProperties` validates model-to-provider references (throws `ConfigurationException` on missing default model), endpoint URL validity, and clamps out-of-range values (buffer-percent 0–50, max-tool-rounds ≥1, timeout ≥1s, buffer-capacity ≥1, max-result-chars ≥100) with warning logs

## Constraints

1. `turnloop-core` POM must not depend on `spring-boot-starter-web`, `SseEmitter`, or any servlet API. Enforced by Maven Enforcer `bannedDependencies`.
2. No Spring AI type in any public API signature. All mapping at `ModelRouter` boundary.
3. No `ThreadLocal` usage in framework source. Verified by grep in Phase 13.
4. `TurnLoop.builder().build()` must produce a working instance with zero external configuration.
5. Framework must survive process restart for question-pending turns via `InteractionStore`.
6. Schema must be evolvable via `MigrationRunner` before v1.0.0 ships.
7. Every SPI ships with a production-quality default.
8. Java 25 target; virtual threads default.
9. Existing Magenta non-web tests must survive the extraction phases.
10. Zero `@Autowired(required = false)` annotations remain after Phase 04 (remove-chatservice-deps).
11. `ToolRegistry` name collisions with framework-internal tools fail with clear exception.
12. `TurnContext` uses typed `AttributeKey<T>` access.
13. `InteractionHandler.onAnswerReceived` returns `AnswerResult`, not raw `String`.
14. `ToolResultSummarizer` enforces configurable `maxResultChars` with truncation suffix.
15. Example tools are NOT auto-registered.
16. `CompactionStrategy` called asynchronously after turn completion.
17. `TurnGate` is a state machine (IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE), not a bare lock.
18. `MarkdownRenderer` has optional Maven dependencies with graceful degradation when absent.

## Assumptions

1. Spring AI's HTTP client is virtual-thread-safe for the target version. Audit required during Phase 09 extraction; the target version must be decided before the audit (see Open Questions).
2. All five framework tables can be reduced from Magenta's 20-migration schema. DDL in handoff Appendix B captures equivalent state.
3. Phase 02 test deletion is scoped to `api/web/**` tests only. Surviving non-web test list must be produced before Phase 02 begins (see Open Questions).
4. `TurnLoopBuilder` requires Spring `ApplicationContext`.
5. Embedding apps provide their own HTTP endpoints; framework provides `StreamLifecycle` and `TurnEventRenderer` utilities.
6. Question-user loop is single-question-per-turn.
7. SQLite is the only v1 default persistence.
8. JTokkit token estimation is approximate; `TokenEstimator` SPI exists for provider-specific estimation.
9. Magenta re-integration (Phase 14) is required for framework validation.
10. JPMS `Automatic-Module-Name: io.mindspice.turnloop` in framework MANIFEST.
11. `turnloop-autoconfigure` serves as `spring-boot-starter-turnloop` meta-starter.

## Open Questions

The following questions from the handoff are unresolved and must be settled before or during the implementation phases indicated:

1. **Final artifact name approval.** `io.mindspice.turnloop` / `turnloop-core` is used throughout. Alternative `io.mindspice.loom` / `mindspice-loom` is a search-and-replace. Decide before Phase 06 (core types).
2. **Example tool selection.** `EchoTool` and `DateTimeTool` proposed. Easily changed in Phase 09.
3. **JPMS module name.** Should the framework declare `Automatic-Module-Name: io.mindspice.turnloop`? This document assumes yes. Confirm before Phase 01.
4. **Model configuration file format.** Should the framework support `turnloop-models.json` classpath auto-discovery alongside YAML and programmatic config? Decide before Phase 11 (autoconfigure).
5. **Test preservation scope.** Exact file list for Phase 02 (delete-web-ui) test preservation vs. deletion must be scoped before Phase 02 begins.
6. **Target Spring AI version.** Which version(s) does the framework target? Virtual-thread safety audit in Phase 09 depends on this. Decide before Phase 06.
7. **`MarkdownRenderer` module placement.** Kept in `turnloop-core` with optional dependencies. Revisit if dependency conflicts arise during Phase 09.
8. **Audit table retention.** The `turnloop_audit_log` table can grow unboundedly. Should the framework include a configurable retention cleaner, or is this the embedding app's responsibility? Decide before Phase 08 (persistence layer).

## Cross-phase Guidance

**Incremental verification.** Every phase must pass its gate before the next phase begins. Gates include compile, relevant tests pass, and context startup smoke tests where applicable. No phase may start with a broken predecessor.

**Deletion discipline.** When a package is deleted, a follow-up grep must confirm zero references from retained code. This grep is part of the phase gate, not an afterthought.

**Framework-native type discipline.** Every type created in `turnloop-core` must be framework-native. The `ModelRouter` is the sole internal mapping boundary to Spring AI types. New framework types must not import `org.springframework.ai.*`.

**SPI default quality.** Every SPI default must be production-quality, not a stub. The default `ConversationMemory` (SQLite) must survive concurrent access. The default `SerialTurnGate` must handle virtual threads correctly. The default `ThinkingExtractor` must handle all three extraction strategies.

**Test adaptation.** During Phases 02–05, surviving Magenta tests must be adapted incrementally as dependencies are removed. Don't delete a test that can be adapted to the new SPI interfaces. Tests for deleted packages are deleted alongside the packages.

**Schema ownership.** The framework owns `schema.sql` and `MigrationRunner`. Magenta's `SchemaMigrationRunner` is deleted in Phase 05. The framework's `MigrationRunner` is a minimal version-tracked implementation, not a full port of Magenta's 20-migration system.

**Exception wrapping.** All internal exceptions (`ToolUseAbort`, `NonTransientAiException`) must be caught at the facade boundary and wrapped in the public exception hierarchy. No raw `RuntimeException` or Spring AI exception may leak through `TurnLoop` methods.

**Virtual-thread audit.** Before the `TurnExecutor` virtual-thread implementation is committed in Phase 09, audit the target Spring AI version's HTTP client for virtual-thread pinning. If pinning is found, document the finding and use a fixed thread pool until the upstream fix lands. The target Spring AI version must be determined before this audit can produce a meaningful result (see Open Question #6).

**Per-phase gate inventories.** The handoff Appendix C defines exact artifact inventories for each phase gate. Implementers must cross-reference these inventories when validating phase completion.

## Final Validation Criteria

After all 14 phases PASS, the following must hold:

1. `mvn clean verify` passes from the root POM, building both `turnloop-core` and `turnloop-autoconfigure` plus Magenta.
2. JaCoCo reports >80% line coverage on `io.mindspice.turnloop` packages.
3. `mvn javadoc:jar` succeeds with zero warnings on `turnloop-core`.
4. grep for `org.springframework.ai` in `turnloop-core/src/main/java/io/mindspice/turnloop/` returns zero results.
5. grep for `ThreadLocal` in `turnloop-core/src/main/java/` returns zero results.
6. grep for references to deleted Magenta packages from retained Magenta code returns zero results.
7. `TurnLoop.builder().build()` returns a working instance (integration test).
8. Full turn loop: message → model response → tool calls → terminal `Completed` event (integration test with real Ollama).
9. Question-user loop: suspend on `ask_user`, `QuestionPending` emitted, `answer()` resumes, terminal response received.
10. `close()` during question-pending turn: `Interrupt` emitted, gate drained, resources released.
11. Concurrent turns on same session: `SerialTurnGate` enforces state transitions, second `send()` rejected.
12. Interrupt mid-turn via `interruptToken`: turn terminates with `Interrupt` event.
13. Model capability mismatch: `stream()` on non-streaming model fails fast with `TurnLoopException`.
14. Queue claim/ack/release with stale-claim recovery.
15. Tool name collision with framework-internal tool throws `ToolException`.
16. Turn timeout: expired turn emits `Failed`, gate released.
17. Tool loop guard trip: 6th identical call → `LoopExceeded` → REPAIR phase → `SystemNotice(TOOL_LOOP_WARNING)`.
18. `ToolResultSummarizer` truncation at configurable limit.
19. `MarkdownRenderer` graceful degradation without optional dependencies.
20. `ConversationMemory.getMessages()` returns insertion order (oldest first).
21. `CompactionStrategy` result stored as system message with `metadata.type = "compaction_summary"`.
22. `InteractionStore` survives JVM restart.
23. `TurnLoop.stream()` backpressure: buffer overflow emits `SystemNotice(BUFFER_OVERFLOW)`.
24. Client disconnect during stream: `SseEmitter` completes, `Flux` cancelled, turn continues without crash.
25. Magenta context starts and existing non-web tests pass consuming the framework.

The handoff Appendix C provides per-phase gate artifact inventories that are the implementation-level bar for each individual phase. The criteria above are the final, cross-cutting validation that all phases together satisfy the full contract.
