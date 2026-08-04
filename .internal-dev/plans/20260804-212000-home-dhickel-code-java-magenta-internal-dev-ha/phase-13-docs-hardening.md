# Phase 13 — Documentation and Hardening

## Context

The framework is fully tested. This phase locks in Javadoc coverage on every public type, produces the complete documentation set, enforces JaCoCo line-coverage at >80%, and runs hardening verification scans to confirm zero Spring AI leakage, zero ThreadLocal, and zero dead references to deleted Magenta packages. The phase closes with a root `mvn clean verify` pass — the final framework-only gate before Magenta re-integration.

## Goal

Complete Javadoc on every public SPI method and public type (thread-safety guarantees nullability contracts exception behavior lifecycle invariants); create 10 markdown documentation files: README.md with production-quality SSE controller example docs/getting-started.md docs/turn-loop.md docs/tools.md docs/memory.md docs/streaming.md docs/interaction.md docs/queueing.md docs/configuration.md docs/facade-api.md; run mvn javadoc:jar with zero warnings; hardening verification scans: grep for org.springframework.ai in turnloop-core public API returns zero results grep for ThreadLocal in turnloop-core source returns zero results PMD dead-code scan confirms zero references from retained code to deleted Magenta packages; configure and run JaCoCo coverage check enforcing greater than 80 percent line coverage on io.mindspice.turnloop packages; verify mvn clean verify passes from root

## In Scope

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop, turnloop-core/README.md, turnloop-core/docs, pom.xml

- Complete Javadoc on every public method in `io.mindspice.turnloop` and `io.mindspice.turnloop.spi`: `@param` with `@NonNull`/`@Nullable`, `@return`, `@throws`, thread-safety guarantees, lifecycle invariants
- Verify `mvn javadoc:jar -pl turnloop-core` succeeds with zero warnings
- Create `turnloop-core/README.md`: overview, quick-start (Maven dependency, `application.yml`, inject `TurnLoop`, call `send()`), production-quality SSE controller example (handles client disconnect, backpressure, error recovery), embedding guide, links to docs
- Create `turnloop-core/docs/getting-started.md`: Maven coordinates, minimum configuration, first turn, common recipes
- Create `turnloop-core/docs/turn-loop.md`: 6-state cycle, TurnRequest/TurnResult, streaming vs sync, interrupt lifecycle
- Create `turnloop-core/docs/tools.md`: ToolCallback, ToolRegistry, ToolAccessPolicy, ToolResultSummarizer, ToolLoopGuard, example tools, ask_user
- Create `turnloop-core/docs/memory.md`: TurnMessage, ConversationMemory, SessionStore, TokenEstimator, CompactionStrategy, schema
- Create `turnloop-core/docs/streaming.md`: TurnEvent types, TurnEventRenderer, SSE, StreamLifecycle, backpressure, client disconnect
- Create `turnloop-core/docs/interaction.md`: Question-user loop, InteractionHandler, InteractionStore, AnswerResult, persistence across restarts
- Create `turnloop-core/docs/queueing.md`: PendingMessage, ClaimedMessage, claim/ack/release, stale-claim recovery
- Create `turnloop-core/docs/configuration.md`: Full `application.yml` reference, TurnLoopBuilder programmatic config, TurnLoopProperties, validation rules
- Create `turnloop-core/docs/facade-api.md`: TurnLoop interface methods, TurnLoopBuilder, sub-interfaces, lifecycle
- Configure JaCoCo plugin in root `pom.xml` `<pluginManagement>` with `prepare-agent`, `report`, and `check` executions bound to `verify` phase; >80% line coverage rule on `io.mindspice.turnloop.*` packages
- If JaCoCo check reports coverage below 80%, add targeted tests for uncovered branches in `turnloop-core/src/test` until the gate passes
- Run hardening verification scans:
  1. grep for `org.springframework.ai` in `turnloop-core/src/main/java/io/mindspice/turnloop/` — must return zero results
  2. grep for `ThreadLocal` in `turnloop-core/src/main/java/` — must return zero results
  3. grep for deleted Magenta package references from retained Magenta code — must return zero results
  4. PMD dead-code scan (or equivalent grep covering all deleted package prefixes from Phase 05) confirms zero references from retained code to deleted Magenta packages
  5. grep for `@Autowired(required = false)` in Magenta `src/main/java/` — must return zero results
- Verify `mvn clean verify` passes from root (all tests, JaCoCo check, Enforcer rules)

## Out of Scope

- Playwright or browser-based testing
- Performance benchmarks
- Mutation testing
- OWASP dependency check

## Dependencies

phase-12-tests.md

## Constraints

- JaCoCo >80% line coverage on `io.mindspice.turnloop` packages
- Javadoc `mvn javadoc:jar` succeeds with zero warnings
- grep for `org.springframework.ai` in public API types returns zero results
- grep for `ThreadLocal` in framework source returns zero results
- grep for dead Magenta package references returns zero results
- `mvn clean verify` must pass

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- All SPI interfaces in `turnloop-core` (read to verify Javadoc coverage)

## Technical Guidance

- Javadoc generation on Java 25 may require `-Xdoclint:all,-missing` to suppress missing-param warnings while catching real errors. If additional doclint categories fire, add the necessary `-Xdoclint` exclusions and document the final incantation in `turnloop-core/pom.xml`.
- The SSE controller example in README must be a real code snippet that compiles against `TurnLoop` and `StreamLifecycle` APIs.
- JaCoCo `check` execution is bound to `verify` phase. It enforces the coverage rule.
- If coverage is below 80%, add tests for uncovered branches before proceeding — do not lower the threshold.

## Implementation Steps

This phase is split into two sequential subphases. Subphase A must complete before Subphase B begins. Phase-level validation and exit criteria apply only after both subphases pass. Each subphase maps to exactly one sequential DeepSeek implementation agent.

### Subphase A — Javadoc on Every Public Type

**Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop

#### Steps

1. **Inventory public surface.** Read every file under `turnloop-core/src/main/java/io/mindspice/turnloop/` that defines a public class, interface, enum, or record. Build a working list of types requiring Javadoc. Include:
   - All 20 SPI interfaces in `spi/`
   - `TurnLoop` interface and sub-interfaces (`tools()`, `memory()`, `sessions()` return types)
   - `DefaultTurnLoop`, `TurnLoopBuilder`
   - `TurnEvent` sealed hierarchy (8 subtypes)
   - `TurnPhase` enum
   - Exception hierarchy: `TurnLoopException`, `TurnFailedException`, `ConversationNotFoundException`, `ToolException` (sealed), `ConfigurationException`
   - Core records: `TurnRequest`, `TurnResult`, `TurnMessage`, `ContextUsage`, `TurnDiagnostic`, `ToolActivityData`, `ToolActivityStatus`, `NoticeKind`, `TurnContext`, `AttributeKey`, `CompactionContext`, `CompactionResult`, `SessionFilter`, `ConversationSession`, `PendingMessage`, `ClaimedMessage`, `AnswerResult`, `QuestionPendingContext`, `ModelCapability`
   - Tool types: `ToolCallback`, `ToolDefinition`
   - `DefaultTurnEngine`, `DefaultPromptAssembler`, `ContextManager`
   - `DefaultToolRegistry`, `SerialTurnGate`, `CompositeThinkingExtractor`, `ToolLoopGuard`, `ToolArgumentCoercionModule`
   - `TurnEventRenderer`, `MarkdownRenderer`
   - `Migration`, `MigrationRunner`
   - Internal interfaces: `ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry` (document their contract even if package-private)
   - Persistence classes: `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`, `SpringAiConversationMemoryAdapter`, `InMemoryInteractionStore`

2. **Write Javadoc.** For every public method on every type listed above, add:
   - `@param name` with `@NonNull` or `@Nullable` annotation where the method contract requires it
   - `@return` describing the return value and whether it may be null
   - `@throws Type` for every declared and documented runtime exception
   - Thread-safety guarantee: `This method is thread-safe.` or `Caller must externally synchronize.` or `This type is not thread-safe.`
   - Lifecycle invariants where applicable: e.g., `Must be called before close().`, `May only be called while the gate is in EXECUTING state.`
   - Class-level Javadoc with a one-sentence summary and a `@since` tag

3. **Address SPI contracts specifically.** For every SPI interface, document:
   - The SPI's role in the turn-loop lifecycle
   - Whether the default implementation is suitable for production
   - Any timing or ordering guarantees the engine expects from implementations
   - Whether implementors must be thread-safe

4. **Document sealed hierarchies.** For `TurnEvent`, `ToolException`, `AnswerResult`, and any other sealed type, document each permitted subtype and when the engine emits or throws it.

5. **Run javadoc:jar.** Execute `mvn javadoc:jar -pl turnloop-core -Ddoclint=all,-missing` and fix every warning. If Java 25 doclint produces additional diagnostics beyond `-missing`, add the necessary suppression flags and document the final incantation in a brief comment in `turnloop-core/pom.xml` above the `maven-javadoc-plugin` configuration.

6. **Gate.** `mvn javadoc:jar -pl turnloop-core` exits zero with zero warnings.

### Subphase B — Documentation, JaCoCo, Hardening, and Full Build

**Write Targets**: turnloop-core/README.md, turnloop-core/docs, pom.xml

#### Steps

1. **Create `turnloop-core/README.md`.** Must include:
   - One-paragraph framework overview
   - Quick-start: Maven dependency snippet, minimum `application.yml`, inject `TurnLoop`, call `send()`
   - Production-quality SSE controller example that compiles against `TurnLoop.stream()`, `TurnEventRenderer.sse()`, and `StreamLifecycle` APIs. The example must handle client disconnect, backpressure, and error recovery.
   - Embedding guide: how an app implements SPIs and wires them via `TurnLoopBuilder` or auto-configuration
   - Links to all docs files

2. **Create `turnloop-core/docs/getting-started.md`.** Maven coordinates for both artifacts, minimum Spring Boot configuration, first-turn walkthrough, common recipes (custom ToolAccessPolicy, custom CompactionStrategy, programmatic builder).

3. **Create `turnloop-core/docs/turn-loop.md`.** 6-state cycle diagram or ordered description, TurnRequest fields and defaults, TurnResult structure, streaming (`Flux<TurnEvent>`) vs sync (`TurnResult`), interrupt lifecycle with `interruptToken`.

4. **Create `turnloop-core/docs/tools.md`.** ToolCallback contract, ToolRegistry two-stage registration and collision detection, ToolAccessPolicy static factories and custom implementations, ToolResultSummarizer with truncation, ToolLoopGuard limits and trip behavior, example tools (EchoTool, DateTimeTool), ask_user framework-internal tool.

5. **Create `turnloop-core/docs/memory.md`.** TurnMessage structure and metadata, ConversationMemory contract (insertion-order guarantee), SessionStore CRUD and ConversationFilter, TokenEstimator SPI and JTokkit default, CompactionStrategy async execution and compaction_summary metadata, schema table descriptions.

6. **Create `turnloop-core/docs/streaming.md`.** All 8 TurnEvent subtypes with field descriptions, TurnEventRenderer.json() and .sse(), StreamLifecycle SSE adapter contract, backpressure buffer behavior and SystemNotice(BUFFER_OVERFLOW), client disconnect handling (flux cancellation).

7. **Create `turnloop-core/docs/interaction.md`.** Question-user loop flow, InteractionHandler.onQuestionsAsked and AnswerResult sealed subtypes (Continue, Abort, SingleMessage, MultiMessage), InteractionStore persistence contract, survival across JVM restarts.

8. **Create `turnloop-core/docs/queueing.md`.** PendingMessage and ClaimedMessage records, claim/ack/release lifecycle, stale-claim recovery mechanism, SqliteMessageQueueStore schema.

9. **Create `turnloop-core/docs/configuration.md`.** Full `application.yml` reference with every `turnloop.*` property, TurnLoopBuilder programmatic configuration for non-Spring embeddings, TurnLoopProperties validation rules (model-to-provider references, URL validity, range clamping with warning logs).

10. **Create `turnloop-core/docs/facade-api.md`.** TurnLoop interface complete method inventory, TurnLoopBuilder all setter methods and `create()`, sub-interfaces (`tools()`, `memory()`, `sessions()`), close() lifecycle contract (gate drain, pending question cancellation, resource release).

11. **Configure JaCoCo in root `pom.xml`.** Add `jacoco-maven-plugin` to `<pluginManagement>` with three executions:
    - `prepare-agent` bound to `initialize`
    - `report` bound to `verify`
    - `check` bound to `verify` with rule:
      ```xml
      <rule>
        <element>BUNDLE</element>
        <limits>
          <limit>
            <counter>LINE</counter>
            <value>COVEREDRATIO</value>
            <minimum>0.80</minimum>
          </limit>
        </limits>
        <includes>
          <include>io.mindspice.turnloop.*</include>
        </includes>
      </rule>
      ```

12. **Run coverage check.** Execute `mvn verify -pl turnloop-core`. If JaCoCo check passes, proceed to step 13. If coverage is below 80%:
    - Inspect the JaCoCo report to identify uncovered branches
    - Add targeted unit tests in `turnloop-core/src/test` covering those branches
    - Re-run `mvn verify -pl turnloop-core` until the check passes
    - Do not proceed to hardening scans with a failing coverage gate

13. **Run hardening scans.** Execute every scan and fix any finding before proceeding:
    1. `grep -r "org\.springframework\.ai" turnloop-core/src/main/java/io/mindspice/turnloop/` — must return zero results
    2. `grep -r "ThreadLocal" turnloop-core/src/main/java/` — must return zero results
    3. `grep -r "io\.mindspice\.magenta2\.ai\.(orchestration|skills|chat\.plan|chat\.task|chat\.project|chat\.system|agent\.job|execution|chat\.tool\.(file|shell|web|skills|orchestration|plan|savedplan|task))" src/main/java/` — must return zero results
    4. `grep -r "ChatSessionOrigin\|ChatSessionSurface\|ChatPlanState\|PlanMode\|ChatFileService\|ChatInvocationProfile\|TrustedContextPolicyAssembler\|OrchestrationTaskContext\|InteractionQuestionTools\|SchemaMigrationRunner\|MagentaWorkExecutor" src/main/java/` — must return zero results
    5. `grep -r "@Autowired(required = false)" src/main/java/` — must return zero results

14. **Full build.** Run `mvn clean verify` from root. Fix any failures. All tests, JaCoCo check, and Enforcer rules must pass.

## Validation

- `mvn clean verify` passes from root
- JaCoCo report shows >80% line coverage on `io.mindspice.turnloop` packages
- `mvn javadoc:jar -pl turnloop-core` succeeds with zero warnings
- grep for `org.springframework.ai` in public API types returns zero results
- grep for `ThreadLocal` in framework source returns zero results
- grep for dead Magenta package references returns zero results
- All 10 markdown docs exist with correct content

## Exit Criteria

- >80% line coverage on framework packages
- Javadoc complete with zero warnings
- All 10 docs written
- All hardening scans pass
- `mvn clean verify` passes
