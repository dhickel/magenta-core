# Single-module Magenta Core refactor

## Date

2026-08-24

## Git Commit

8961a36

## Change Summary

Converted the transitional three-module Spring Boot reactor into one root-level `io.mindspice:magenta-core` Java 25 library. Migrated the framework package to `io.mindspice.magenta.core`, removed the application and auto-configuration modules, made construction programmatic, added direct provider adapters, implemented fresh durable persistence and interaction state, repaired turn-loop prompt/memory/queue/cancellation behavior, and replaced stale product documentation/assets. A follow-up lifecycle repair added token-qualified execution fencing so cancellation and timeout cannot authorize stale persistence or release a successor turn's gate.

## Files

- Root `pom.xml` and migrated `src/main/**` framework sources/resources.
- `src/test/**` builder, provider, persistence, tool, and interaction tests.
- `consumer-fixture/**` standalone consumer compilation example.
- `README.md`, `docs/**`, and framework-focused `AGENTS.md`.
- Relevant `.internal-dev/specifications/**`, `.internal-dev/knowledge/**`, and plan phase records.
- Deleted `magenta-app`, `mindspice-core`, `mindspice-core-autoconfigure`, and app-only scripts/configuration/runtime assets.

## Behavioral Impact

- Consumers use `TurnLoopBuilder.create()` with exactly one `dataRoot` or `DataSource`, immutable model registration, and no `ApplicationContext`.
- Ollama, OpenAI-compatible, and DeepSeek provider construction is internal and bundled transitively.
- Framework persistence uses fresh `magenta_core_*` tables; historical `turnloop_*` tables remain untouched.
- User messages enter prompts and durable memory once; question suspension is restart-safe, supports ordered multiple questions and combined answers, auto-queues pending-session submissions, and supports cancellation.
- Sync/stream lifecycle, interruption, metrics, audit, context sizing, graceful close, durable answer/cancel CAS, restart-safe queue claims, and bounded session coordination are wired through transport-neutral contracts.
- Subscriber cancellation and gate timeout revoke execution authority before gate release; stale execution memory, interaction, failure-cleanup, audit/lifecycle, and compaction writes are fenced, while authoritative cancellation cleanup completes under the session boundary. Late cleanup is qualified by both turn ID and gate token.
- Timeout scanning invokes the facade's token-aware interrupt fence before changing gate state. Default builder-owned scanners stop on `TurnLoop.close()`; caller-supplied gates remain caller-owned and open.

## Specification Impact

The branch-specific single-module framework contract and `DECISION-20260824-01` supersede the former product/application and two-module extraction direction for this branch while preserving historical evidence. `architecture.md`, `decisions.md`, and `core-extraction-branch-topology.md` record the conflict and supersession explicitly. The lifecycle repair changes no intended specification; it completes the existing cancellation, timeout, serialization, and graceful-close contract.

## Validation

- Focused cancellation, timeout, interaction, and builder lifecycle tests passed: 25 tests, 0 failures/errors.
- `mvn clean verify` passed: 38 tests, 0 failures/errors.
- `mvn help:evaluate -Dexpression=project.artifactId -DforceStdout` resolved `magenta-core`.
- Maven dependency ban passed; no Boot application/auto-configuration, servlet, Actuator, or Micrometer adapter is present.
- JAR manifest contains `Automatic-Module-Name: io.mindspice.magenta.core`; forbidden old package/module entries are absent.
- Standalone consumer fixture compiled with only the built artifact and runtime dependency classpath.
- `git diff --check` passed after concurrency, provider-visibility, and cancellation/timeout-fencing repairs.

## Risks

Spring AI provider libraries bring transport client implementation dependencies and Micrometer observation libraries transitively, but no web server, servlet adapter, Boot auto-configuration, Actuator, or Micrometer integration is exposed by Magenta Core. DeepSeek request-side thinking levels are rejected clearly because the pinned Spring AI 2.0.0 API does not expose them. Provider or tool code may ignore thread interruption and continue computing after cancellation/timeout, but revoked execution authority prevents it from committing framework-owned state; executor shutdown remains bounded by configured close behavior.

## Follow-up Items

- An optional live Ollama profile can be documented when a deterministic local service is available.
- Revisit DeepSeek request-side thinking only when the pinned provider API exposes a supported typed option.

## Git Provenance

Git commit containing the finalized implementation and closeout records is recorded in the final commit metadata; preceding implementation phase commits are `013ad56`, `f843b72`, `cf684bc`, and `c8fab55`.
