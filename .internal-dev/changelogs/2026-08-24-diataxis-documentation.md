# Diátaxis documentation for Magenta Core

## Date

2026-08-24

## Git Commit

Working tree on top of `a58078069f095f3b1b61a50b17ed2938e12dce0d` (HEAD; changes
uncommitted by request).

## Change Summary

Expanded the Diátaxis documentation from a concise first pass to
production/release quality, treating current code, tests, public API, and
repository instructions as authoritative. No production Java behavior was
modified.

### Tutorials (5, learning-oriented with complete runnable programs)

- `tutorials/getting-started.md` — rewritten: first blocking turn, exhaustive
  sealed `TurnResponse.Outcome` switch, compile failure demo for
  non-exhaustive matching, expected output, prerequisites, links.
- `tutorials/streaming-turns.md` — new: cold `Flux` consumption of content,
  thinking, tool activity, and notices; terminal outcomes; failure and edge
  behavior table; blocking convenience forms.
- `tutorials/durable-questions.md` — new: full `ask_user` flow with fresh and
  restart modes (rehydration), ordered answers, cancellation, and the typed
  rejection table.
- `tutorials/tool-use.md` — new: explicit `ToolCallback` and annotated `@Tool`
  beans, typed `ToolResult` success/failure, failure taxonomy, safe summaries,
  per-turn selection, access policy, tool-loop guard.
- `tutorials/queueing-and-persistence.md` — new: explicit and implicit
  queueing, oldest-first drain with typed origins, restart recovery, `dataRoot`
  versus caller-owned `DataSource`, restart expectations table.

Each tutorial has prerequisites, expected behavior, and cross-links; every
complete program is marked with `<!-- tutorial-compile: Name.java -->` and
compiles against the built Java 25 JAR.

### How-to guides (9, exact procedures with failure/edge behavior)

- `how-to/stream-responses.md` — emission contract, ordering, dispose vs
  interrupt vs cancel, timeouts, error channel, deliberate non-exposure.
- `how-to/handle-questions.md` — answer validation table, CAS rejection matrix,
  stale/duplicate/cross-session cases, rehydration, interruption, close.
- `how-to/queue-and-drain.md` — enqueue, implicit reasons, drain step order,
  retry/recovery matrix, lease tuning, ordering, claim-token non-exposure.
- `how-to/register-tools.md` — both registration styles, schema rules,
  exception-to-category mapping, selection semantics, loop protection, safe
  summaries.
- `how-to/configure-models.md` — alias rules, resolution precedence, per-variant
  constraints, capability provider, streaming gates, credential redaction.
- `how-to/configure-persistence.md` — both storage modes, clean-break check
  order, restart expectations, caller migrations.
- `how-to/lifecycle-and-cancellation.md` — interrupt, subscription cancel,
  question cancel, timeouts and gate fencing, clear/delete deferral, close
  order.
- `how-to/manage-sessions-and-memory.md` — new: `SessionFilter`, session
  updates/deletion, history/context usage, context maintenance behavior.
- `how-to/observe-and-audit.md` — new: lifecycle listener, metrics, audit
  writer, strategy seams, deliberate non-exposure.

### Reference (5, exhaustive and signature-checked)

- `reference/api.md` — full facade contract per method with error keys,
  sub-interfaces, complete public type inventory (including visibility
  distinctions), exception hierarchy, deliberately non-public list.
- `reference/response-protocol.md` — variant hierarchy, component-level
  invariants, JSON tags, stream invariant, renderer, related sealed ADTs.
- `reference/configuration.md` — every builder option with defaults and
  `ConfigurationException` detail keys, `ModelConfig` validation rules,
  collaborator default table, engine constants.
- `reference/persistence-schema.md` — per-table column/check/index detail,
  identity check order, queue and interaction constraints, migrations.
- `reference/spis.md` — per-SPI contracts, thread safety, error contracts,
  ownership notes, default implementations.

### Explanation (3, deeper rationale)

- `explanation/architecture.md` — deepened: runtime boundary diagram, execution
  flow, per-session serialization, durability, transport neutrality.
- `explanation/turn-lifecycle.md` — new: seven-phase state machine, session
  locks/gate/registry interplay and timeout fencing, queue claim/drain
  protocol, interaction CAS races and crash recovery, typed protocol
  projections, tool-loop protection, secret-redaction choices, clean-break
  rationale, model-resolution rationale.
- `explanation/compatibility.md` — deepened: why failures are not outcomes,
  hidden claim tokens, distinct interaction IDs, shared blocking/streaming
  protocol, subscriber-cancel vs interrupt, strict schema reuse, internal
  providers, redaction tradeoffs, loop guard.

### Navigation and hygiene

- `docs/README.md` — full index with tutorial order, all how-tos, reference,
  explanation, and executable-example sections.
- `README.md` — documentation links updated.
- `docs/AGENTS.md` — added tutorial-compile marker convention, compilation and
  link-check validation requirements.
- Removed stale and contradictory material (superseded `docs/api/`,
  `docs/end-user/`, `docs/technical/` pages were already deleted in the first
  pass); no role-string, endpoint-discriminator, or default-switch examples
  remain; no Spring Boot/HTTP/UI claims except explicit negatives.

## Behavioral Impact

Documentation-only. Production Java behavior and public contracts were not
changed. All claims were cross-checked against `src/main/java`, `src/test`,
`src/main/resources/schema.sql`, and the public API before writing; examples
match Java 25 signatures and current sealed ADTs.

## Validation Evidence

- `mvn clean verify` passed: 96 tests, 0 failures, 0 errors, 0 skips.
- All five tutorial programs (`FirstTurn`, `StreamingConsumer`, `DeployWizard`,
  `Toolbox`, `QueueDemo`) were extracted from the Markdown via the
  `tutorial-compile` markers and compiled with `javac --release 25` against the
  built `target/magenta-core-0.0.1-SNAPSHOT.jar` and published runtime
  classpath.
- The standalone consumer fixture compiled and ran successfully against the
  deterministic JDK provider seam, printing `consumer-ok`.
- Repository-local relative Markdown link checker: 156 relative links and
  anchors across 25 files all resolve (scripted Python check).
- Stale-symbol scan: no removed ADT names (`EndpointType`,
  `InteractionState`, `ToolActivityStatus`), old documentation paths, or
  default branches over sealed ADTs; the only `default` branches are string
  switches in tutorial programs.
- `git diff --check`: clean (no whitespace errors).

## Specification Impact

None. The documentation restates the active branch-specific framework, API,
schema, and architecture contracts without changing intended behavior;
`docs/AGENTS.md` now requires the tutorial-compile marker and validation steps
for future doc changes.

## Risks

- The repository has no dedicated Markdown link or snippet-validation plugin;
  link checking and tutorial compilation were performed with local validation
  commands. The `tutorial-compile` marker convention makes future compilation
  checks scriptable.
- Narrative snippets other than the five complete tutorial programs remain
  manually reviewed fragments rather than independently compiled source files.
- Live model behavior (exact model output, whether a model chooses to call
  `ask_user` or tools) is inherently model-dependent; tutorials state expected
  *framework* behavior and note the model-dependent parts.
- `NoticeKind` declares five kinds but the current engine emits two
  (`TOOL_LOOP_WARNING`, `REPAIR_APPLIED`); reference pages document all five as
  protocol surface and state which are currently emitted.

## Follow-up Items

- Add automated Markdown link checking if a repository-wide documentation
  validation mechanism is adopted.
- Keep the reference tables synchronized when sealed variants, builder options,
  schema identity, or SPI ownership contracts change.
