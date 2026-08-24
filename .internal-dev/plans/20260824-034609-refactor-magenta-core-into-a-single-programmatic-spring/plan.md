# Refactor Magenta Core into a Single Programmatic Spring AI Framework

## Feature

Replace the transitional three-module reactor with one root-level `io.mindspice:magenta-core` Java library that encapsulates Spring AI provider construction and exposes a straightforward, programmatic turn-loop facade with model routing, tools, streaming, persistent conversations, complete user-question suspension/resumption, queueing, interruption, compaction, lifecycle hooks, and advanced SPIs. Remove the application and Spring Boot auto-configuration surfaces entirely.

## Required Behavior

- The repository root itself is the sole publishable JAR: `io.mindspice:magenta-core`; there is no aggregator-only parent and no child Maven module.
- Rename the canonical public package from `io.mindspice.core` to `io.mindspice.magenta.core`, with coherent `spi`, `persistence`, and internal package boundaries.
- Delete `magenta-app` and `mindspice-core-autoconfigure` completely; do not preserve an executable application, Boot auto-configuration metadata, configuration properties, Actuator health adapter, Micrometer adapter, servlet/SSE emitter integration, or compatibility artifact.
- Provide a clean-break API with no deprecated `io.mindspice.core` shims and no Maven relocation artifact.
- Use Java 25 and retain a transport-neutral library contract with no servlet, MVC, or embedded-server dependency.
- Make `TurnLoopBuilder` fully programmatic and independent of `ApplicationContext`, component scanning, bean discovery, `@ConfigurationProperties`, and automatic Spring bean registration.
- Require callers to configure storage through either a data-root path, from which the framework creates/owns file-backed SQLite, or a caller-provided JDBC `DataSource`; validate conflicting or missing choices clearly.
- Have the builder initialize/load all framework-owned SQL repositories and fresh schema before returning a usable `TurnLoop`.
- Use new `magenta_core_*` tables and ignore legacy `turnloop_*` tables; make no pre-release data migration promise and never delete or rewrite legacy tables.
- Include Ollama, OpenAI-compatible, and DeepSeek Spring AI providers transitively so all supported `EndpointType` values work from the single Maven dependency.
- Keep provider construction, Spring AI messages/options/tool adapters, retries, and provider-specific details internal; public facade and data contracts must use Magenta-owned types.
- Use immutable builder-time model registration through `ModelConfig`; select a requested turn alias first, then the session's stored alias when applicable, then the first registered model as the default.
- Keep caller-supplied session IDs and lazily establish session metadata/history on first valid turn; no explicit session-creation call is required.
- Retain the complete facade capability set: synchronous turns, streaming, tools, model routing, question suspension/resumption, SQL conversation memory, session metadata, persistent queueing, interruption, context estimation/compaction, audit support, metrics/lifecycle SPIs, and graceful close.
- Keep supported advanced SPIs public while reducing accidental API surface by making engines, routers, executors, composition internals, and default implementation details package-private where feasible.
- Support both Magenta-owned annotated tool beans and explicit Magenta `ToolCallback` registration without requiring consumers to use Spring AI annotations or callback types.
- Implement the built-in `ask_user` tool as a complete one-or-more-question lifecycle: persist pending state, emit questions, suspend the session, survive process restart, accept one combined answer string, inject the questions and answer into resumed model context and durable memory, and clear state deterministically on completion, cancellation, interruption, session deletion, or failure.
- While a session awaits an answer, automatically persist new direct submissions in its queue rather than executing them; synchronous submission returns a `TurnResult` with a `QUEUED` diagnostic and queued-message identity, while streaming emits an explicit queued terminal event.
- Add `cancelQuestion(sessionId, reason)` to cancel pending interaction state, release the session gate, preserve already queued messages, and record the cancellation in memory/audit as appropriate.
- Repair observed core defects rather than carrying them into the new framework: ensure current user messages enter prompts and persistence, answers enter resumed prompts, multi-question data is not collapsed, audit and metrics hooks are actually invoked, model context length drives usage/compaction, configured endpoint/API-key values reach provider clients, default/session model selection is consistent, and duplicate migration mechanisms are removed.
- Preserve per-session serialization and make `send`, `stream`, `answer`, queueing, cancellation, interruption, and shutdown race-safe, including restart and subscriber-cancellation paths.
- Replace stale application documentation and runtime assets with framework-focused getting-started, configuration, model, turn-loop, tool, interaction, persistence, queue, streaming, lifecycle, and API documentation containing copy-paste programmatic examples.
- Add comprehensive automated tests because the current core and auto-configuration modules have no real tests.

## Targets

- `pom.xml`: Convert the root from a three-module Spring Boot reactor into the sole `magenta-core` JAR; set Java 25, Spring AI dependency management, direct required provider/JDBC/Reactor/Jackson dependencies, test tooling, JAR metadata, and a web/servlet dependency ban.
- `mindspice-core/src/main/** -> src/main/**`: Move the surviving framework source/resources to the root library layout, rename packages to `io.mindspice.magenta.core`, and remove the obsolete child module after migration.
- `mindspice-core-autoconfigure/**`: Delete the entire module and all auto-configuration, property binding, health, Micrometer, web/SSE-emitter, and registration metadata.
- `magenta-app/**`: Delete the residual Spring Boot launcher, application configuration, app DTO/config packages, schemas, resources, and tests.
- `src/main/java/io/mindspice/magenta/core/TurnLoop.java and TurnLoopBuilder.java`: Define the streamlined facade and programmatic construction contract, including explicit storage, immutable models, default selection, pending-question cancellation, queued outcomes, and no ApplicationContext.
- `src/main/java/io/mindspice/magenta/core/internal model/provider code`: Replace reflective/application-context provider setup with directly tested internal Ollama, OpenAI-compatible, and DeepSeek construction from `ModelConfig`; keep Spring AI types internal.
- `src/main/java/io/mindspice/magenta/core engine and interaction code`: Repair prompt/memory flow and implement restart-safe multi-question suspend/resume, combined answers, auto-queueing during pending interaction, explicit cancellation, interruption, streaming, and serialization semantics.
- `src/main/java/io/mindspice/magenta/core/spi and tool APIs`: Retain justified advanced extension points, introduce Magenta-owned tool annotations/callback adaptation, remove dead or unsupported methods, and hide implementation-only classes.
- `src/main/java/io/mindspice/magenta/core/persistence and src/main/resources schema`: Consolidate repository initialization into one fresh-schema mechanism using `magenta_core_*` tables for memory, sessions, pending interactions, queued messages, audit, and schema identity; support framework-owned SQLite or supplied DataSource without touching legacy tables.
- `src/test/**`: Create unit, integration, provider-adapter, SQL, restart, concurrency, lifecycle, and consumer-usage tests for the complete public contract.
- `README.md, docs/**, AGENTS.md, docs/AGENTS.md`: Replace stale application/product guidance with library architecture, usage, extension, validation, and contribution documentation.
- `start.sh, db-ui-probe.js, package.json, package-lock.json, config/**, identifier.sqlite, application screenshots and other tracked app-only runtime assets`: Remove assets whose only purpose was the deleted web/application harness, after verifying no framework build or documentation reference remains.
- `.internal-dev/specifications/architecture.md, .internal-dev/specifications/decisions.md, .internal-dev/knowledge/core-extraction-branch-topology.md and relevant extraction records`: Record the user-approved single-module decision, supersede the earlier two-module/reintegration direction without rewriting historical evidence, document branch-specific framework truth, and close/archive superseded active extraction planning as required by project workflow.
- `.internal-dev/changelogs/<date>-single-module-magenta-core.md`: Record final code, documentation, specification, validation, risk, and Git provenance for the completed refactor.

## Constraints

- Planning only in this job; implementation begins only after the user invokes execution.
- Do not restore the deleted full Magenta product from another branch; this branch becomes framework-only.
- Do not add Spring Boot auto-configuration back into the surviving JAR under another name.
- Do not expose Spring AI `ChatModel`, `Message`, tool callback, provider option, or application-context types in the primary public API.
- Do not add HTTP endpoints, web UI, servlet adapters, Actuator, embedded server, authentication, orchestration, agents, jobs, plans, tasks, workspaces, or other deleted product features.
- Do not provide YAML/JSON configuration loading; builder calls are the only framework configuration path.
- Do not add runtime model registration/update/removal; model registry is immutable after `build()`.
- Do not migrate, rename, delete, or reinterpret old `turnloop_*` tables; new schema may coexist alongside them.
- Preserve unrelated repository history and `.internal-dev` evidence; archive/supersede records through the documented workflow rather than deleting historical artifacts.
- Use a dedicated implementation branch and phase commits because this is a multi-phase architectural refactor.
- Keep the solution direct and hobbyist-friendly: common usage should require one Maven dependency, a builder, SQL location/DataSource, model registration, optional tools/interaction callback, and `build()`.
- No stubs, unsupported convenience methods, fake defaults, TODOs, or untested placeholder providers may remain in the public surface.

## Assumptions

- The artifacts are unreleased `0.0.1-SNAPSHOT`, so a clean package/artifact break is acceptable.
- Group ID remains `io.mindspice`; artifact ID becomes `magenta-core`; automatic module name becomes `io.mindspice.magenta.core`.
- The first model registered is the default unless a request/session selects another alias.
- A `dataRoot` configuration creates a persistent file-backed SQLite database rather than the current misleading in-memory URL.
- A supplied DataSource may target SQLite-compatible JDBC behavior; broader database portability is not promised unless tests establish it.
- Queued messages remain available for explicit `claim`/`send`/`ack` processing after the pending question is answered or cancelled; this change does not introduce an automatic background queue worker.
- A combined answer is formatted with the persisted ordered question list so the model receives unambiguous question/answer context.
- Real external API credentials or a locally running Ollama service are not assumed for the default test suite; deterministic provider-compatible test servers and internal seams supply repeatable validation, with an optional live profile documented separately.
- Historical Magenta product specifications remain historical evidence; the implementation updates only the records necessary to make the `core` branch's new framework contract explicit.

## Settled Decisions

- Use one root-level library module, not a parent plus child.
- Rename both artifact and packages to `magenta-core` / `io.mindspice.magenta.core`.
- Use programmatic builder configuration only.
- Use framework-owned `ModelConfig` rather than requiring consumers to construct Spring AI models.
- Bundle all three currently supported providers.
- Retain the complete facade and justified advanced SPIs, while hiding internals.
- Configure persistence with either a data root or DataSource through the builder.
- Use caller-supplied session IDs with lazy session creation.
- Use a combined string answer for one-or-more pending questions.
- Automatically queue new submissions while questions are pending and expose queued outcomes normally rather than through exceptions.
- Provide explicit pending-question cancellation.
- Use fresh new schema tables and ignore old tables.
- Keep Java 25.
- Keep models immutable after builder construction.
- Make a clean break with no compatibility shims.

## Implementation Approach

1. Create a dedicated refactor branch, capture a clean baseline, inventory tracked app-only files and public framework symbols, and record the old extraction plan as superseded by the new single-module decision before changing code.
2. Convert `pom.xml` into the root `magenta-core` JAR build. Add direct, compatible Spring AI provider libraries and required core dependencies, remove module declarations and Boot application packaging, configure Java 25/test plugins/JAR metadata, and enforce the no-web/no-servlet dependency boundary.
3. Move `mindspice-core/src/main` into the root `src/main`, perform the clean package rename to `io.mindspice.magenta.core`, update service-loader resources and schema resource paths, then delete the now-empty child module plus `mindspice-core-autoconfigure` and `magenta-app`. Compile immediately to expose stale package and module assumptions.
4. Redesign `TurnLoopBuilder` as a plain Java composition root: remove `ApplicationContext` and bean discovery; require exactly one storage choice; support ordered immutable `ModelConfig` registration; configure system prompt, timeouts, SPIs, tools, and listeners explicitly; initialize repositories; build provider/router/engine/facade components; and produce precise `ConfigurationException` diagnostics.
5. Simplify model management behind an internal router. Replace provider reflection and context lookup with direct provider integrations, apply endpoint, API key, remote model, context length, thinking/reasoning, timeout, streaming, and tool options correctly, cache by alias, select request/session/default aliases deterministically, and close owned clients safely.
6. Rework tool registration around Magenta-owned annotations and callbacks. Generate schemas with the shared ObjectMapper, retain access policy/loop guard/result summarization, reserve built-in names, adapt to Spring AI internally, and test coercion, duplicate names, tool failures, provider calls, and hidden framework tools.
7. Consolidate SQL persistence into one fresh schema/init path. Add namespaced tables and repositories for conversation messages, session metadata/model choice, queue claims, pending interaction state sufficient for restart, audit, and schema identity. Remove the empty `MigrationRunner`/self-init duplication, ensure transactions and foreign-key/index behavior are coherent, and leave any legacy tables untouched.
8. Repair the core turn state machine end to end: add the incoming user message to prompt and durable memory exactly once; use configured context lengths; persist tool/assistant/repair activity; invoke audit, metrics, and lifecycle hooks; enforce per-session gating; and make sync/stream behavior and terminal diagnostics consistent.
9. Complete the ask-user state machine. Accept and persist one-or-more ordered questions with turn/session/model/continuation data, emit `QuestionPending`, transition the gate to awaiting-answer, restore state after process restart, format/inject the combined answer, resume the same logical conversation, and atomically clear pending state on success. Add stale-answer, duplicate-answer, missing-session, storage-failure, model-failure, and repeated-restart handling.
10. Implement pending-session submission policy and cancellation. Auto-enqueue new `send`/`stream` requests while awaiting an answer; return a `QUEUED` `TurnResult` with message identity or emit a queued terminal event; add `cancelQuestion`; preserve queued messages; and coordinate cancellation with `interrupt`, `clearSession`, session deletion, and `close`.
11. Review and reduce API visibility. Keep the facade, records, exceptions, supported annotations, and advanced SPIs public; make router/engine/executor/context orchestration/default internals package-private where possible; remove dead contracts such as unsupported async factories or unused answer variants; update Javadocs to match actual threading, persistence, exception, and lifecycle behavior.
12. Build a comprehensive test suite using deterministic fake/internal seams and temporary SQLite databases. Cover builder validation, all endpoint types, model precedence, prompt/memory correctness, tools, sync/stream parity, question restart/resume/cancel, queued outcomes and claims, session operations, compaction, interrupts, audit/metrics/listeners, SQL coexistence with legacy tables, concurrency, subscriber cancellation, and idempotent graceful close.
13. Add a consumer-fixture test that depends only on the built `magenta-core` artifact and demonstrates data-root and DataSource construction without Spring Boot or an application context. Add provider-compatible mock-server tests and an optional documented live Ollama profile.
14. Delete stale application-only scripts, Node/browser assets, configuration, screenshots, databases, and product docs after reference checks. Rewrite the root README and focused framework docs with copy-paste Maven, builder, multi-model, tool, streaming, ask-user, queue, persistence, and shutdown examples.
15. Run dependency analysis, API/package scans, full `mvn clean verify`, JAR content inspection, and the consumer smoke fixture. Confirm no deleted modules, `io.mindspice.core`, `io.mindspice.magenta2`, auto-configuration metadata, Boot application classes, servlet/web dependencies, or stale product links remain.
16. Update the living architecture/decision/knowledge records, archive or supersede the unfinished old extraction plan according to store rules, create the required changelog with specification impact and Git hash, review the final diff for accidental scope, and commit each completed phase plus final integration evidence on the dedicated branch.

## Validation Criteria

- The repository contains one Maven project and `mvn help:evaluate -Dexpression=project.artifactId` resolves to `magenta-core`; no `<modules>` remain.
- `mvn clean verify` passes on Java 25 with all unit and integration tests.
- The built JAR has `Automatic-Module-Name: io.mindspice.magenta.core` and contains no `io/mindspice/core`, `io/mindspice/magenta2`, or auto-configuration registration entries.
- Dependency-tree validation shows no servlet API, Spring MVC/WebFlux starter, embedded server, Actuator, or Spring Boot auto-configuration dependency in the framework runtime graph.
- A standalone consumer fixture compiles and runs with only the `magenta-core` dependency, programmatic builder calls, a temporary data root/DataSource, model configuration, and no Spring application context.
- Builder tests prove missing/conflicting storage, missing models, invalid aliases, duplicate aliases/tools, bad paths/DataSources, and unavailable schema fail with clear framework exceptions.
- Provider adapter tests prove Ollama, OpenAI-compatible, and DeepSeek configurations apply endpoint, key, remote model, options, context length, streaming, and tool callbacks without leaking Spring AI exceptions through `TurnLoop`.
- Sync and stream tests prove the current user message is included once in the prompt and persisted once, prior history is preserved, assistant/tool messages are durable, and request/session/first-model precedence is correct.
- Question-loop tests prove multiple ordered questions are persisted and emitted, one combined answer resumes with explicit question/answer context, pending state survives reconstructing `TurnLoop` from the same database, stale/duplicate answers reject safely, and success clears pending state.
- Pending-session tests prove new sync and stream submissions are queued, expose normal queued outcomes with IDs, do not invoke the model, and remain claimable after answer or cancellation.
- Cancellation/interruption tests prove `cancelQuestion`, `interrupt`, `clearSession`, session deletion, failure, and `close` release gates and clean pending state without losing unrelated queued work.
- Fresh-schema tests prove all new tables use the `magenta_core_*` namespace and an existing database containing `turnloop_*` tables is neither modified nor rejected.
- Concurrency tests prove one active turn per session, independent sessions can progress concurrently, queue claims are safe, close is idempotent, and stream cancellation does not leak gates or active-turn registrations.
- Audit, metrics, lifecycle, compaction, context-usage, session, and queue tests prove every retained facade/SPI capability is genuinely wired rather than merely present as an interface.
- Source/API scans show primary public contracts contain no Spring AI, Spring context, Boot, servlet, or provider-specific types, while internal adapters are the only Spring AI boundary.
- Documentation examples are compiled/tested or backed by the consumer fixture, and all stale app/web/API links and run commands are removed.
- Final `.internal-dev` records state that the one-module directive supersedes the former auto-configure/reintegration plan while preserving historical phase evidence.
- Final diff review finds no placeholders, unsupported public factories, dead auto-configuration remnants, accidental compatibility shims, or unrelated edits.

## Out of Scope

- Rebuilding or reintegrating the deleted Magenta web/product application.
- Spring Boot starter or auto-configuration support of any kind.
- HTTP, REST, SSE controller/emitter, WebSocket, CLI, or UI transport implementations.
- Runtime model registry mutation or remote configuration reload.
- Automatic background draining/execution of queued messages.
- Migration of existing `turnloop_*` data into the new schema.
- Compatibility wrappers for old Java packages or Maven artifacts.
- Provider support beyond Ollama, OpenAI-compatible, and DeepSeek.
- Authentication, authorization, multi-tenant isolation, orchestration, agents, planning, tasks, jobs, workflows, workspaces, file/shell/web tools, or product-specific prompts.
- Generic multi-database portability beyond the supported SQL/DataSource behavior proven by this implementation.
- Restoring app-specific docs, scripts, screenshots, databases, or frontend assets.

## Planning Record

- Interactive rounds completed: 7
- User questions answered: 21
- Remaining consequential open questions: none
