# ModelConfig variant builder and documentation currency

## Date

2026-09-17

## Git Commit

a58078069f095f3b1b61a50b17ed2938e12dce0d

## Change Summary

Added a variant-scoped fluent builder to the sealed `ModelConfig` family: `ModelConfig.ollama()`, `ModelConfig.openAiCompatible()`, `ModelConfig.deepSeek()`, and `ModelConfig.responses()` return the shared public `ModelConfig.Builder`, whose `build()` returns the typed variant and delegates all construction validation to the existing record constructors. `reasoningEffort` is rejected immediately on the `Ollama` and `DeepSeek` builders because those variants carry no such component, so the builder cannot silently drop the value. Direct record construction remains public and equivalent.

Added the documentation-currency rule to `AGENTS.md` (documentation is part of the change; stale examples and outdated variant/option/provider lists are defects) and the matching version-sync checkpoint to `docs/AGENTS.md`.

Reviewed the recent uncommitted edits (sealed ADT consolidation, Responses provider, Diátaxis documentation, configurable compaction, safe provider failure messages, configurable tool-loop guards) against the documentation and repaired the drift found:

- Provider/variant lists missing the `Responses` variant in `README.md`, `docs/tutorials/getting-started.md`, `docs/how-to/configure-models.md`, `docs/explanation/compatibility.md`, `docs/reference/response-protocol.md`, and the `docs/explanation/architecture.md` adapter diagram.
- Engine notice emissions missing `COMPACTION` in `docs/reference/api.md`, `docs/reference/response-protocol.md`, and `docs/how-to/stream-responses.md`.
- The configurable compaction threshold still documented as a fixed 75% in `docs/how-to/manage-sessions-and-memory.md`, `docs/how-to/observe-and-audit.md`, `docs/reference/spis.md`, and `docs/explanation/architecture.md`.
- `TurnFailure.message` usefulness/redaction/bound undocumented in `docs/reference/api.md` and `docs/explanation/turn-lifecycle.md`.
- Public type inventory gaps in `docs/reference/api.md`: `CompactionContext`, `CompactionResult`, `ExecutorStrategy`, and `ModelConfig.Builder` (with its factory entry points).
- Artifact version references still `0.0.1-SNAPSHOT` after the `pom.xml` bump to `0.1.0` in `README.md`, `docs/tutorials/getting-started.md`, and `consumer-fixture/README.md`.
- Tutorial and README model examples now use the variant builder; `consumer-fixture/Consumer.java` builds its configs with the builder and exercises the previously omitted `Responses` variant at runtime.

## Files

- `src/main/java/io/mindspice/magenta/core/ModelConfig.java`
- `src/test/java/io/mindspice/magenta/core/ModelConfigBuilderTest.java` (new)
- `src/test/java/io/mindspice/magenta/core/TurnLoopBuilderTest.java`
- `consumer-fixture/src/main/java/io/example/Consumer.java`
- `consumer-fixture/README.md`
- `README.md`, `AGENTS.md`
- `docs/AGENTS.md`, `docs/README`-linked pages: `docs/how-to/configure-models.md`, `docs/how-to/stream-responses.md`, `docs/how-to/manage-sessions-and-memory.md`, `docs/how-to/observe-and-audit.md`, `docs/reference/api.md`, `docs/reference/configuration.md`, `docs/reference/response-protocol.md`, `docs/reference/spis.md`, `docs/explanation/architecture.md`, `docs/explanation/compatibility.md`, `docs/explanation/turn-lifecycle.md`, and the five tutorial pages.
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/knowledge/documentation-foundation.md`, `.internal-dev/knowledge/java-artifact-validation.md`

## Behavioral Impact

Additive public API. `ModelConfig.Builder` and the four variant factory methods are new; no record component, constructor, JSON tag, validation rule, or runtime behavior changed. The builder produces values equal to the direct record constructors and fails with the same exceptions. Callers that still use `new ModelConfig.Ollama(...)` and friends are unaffected.

Documentation behavior changed only in accuracy: examples now show the builder, provider lists include `Responses`, notice emissions and the configurable compaction threshold are stated correctly, and version-pinned commands match `pom.xml` 0.1.0.

## Specification Impact

Updated the branch-specific framework contract in `.internal-dev/specifications/architecture.md` to record the `ModelConfig` variant builder seam, its delegation of validation to the variant record constructors, and the immediate `reasoningEffort` rejection for variants without that component. No product-level specification changed.

## Validation

- `mvn clean verify`: 130 tests, 0 failures, 0 errors, 0 skips (includes the 7 new `ModelConfigBuilderTest` tests and the existing `TurnLoopBuilderTest` suite now registering builder-constructed configs).
- All five tutorial programs extracted via `tutorial-compile` markers compiled with `javac --release 25` against `target/magenta-core-0.1.0.jar` and the published runtime classpath.
- Standalone `consumer-fixture` compiled against the built JAR and printed `consumer-ok`.
- Relative Markdown link/anchor check: 157 links across 25 files, all resolve.
- Stale-symbol scan for removed ADT and documentation names: no hits.

## Risks

The builder adds a second construction path and must stay aligned with the record constructors; the delegation of validation to the constructors is the guard against drift, and `ModelConfigBuilderTest` pins per-variant defaults, validation, and `reasoningEffort` behavior. `ExecutorStrategy` remains public but internal-wiring-only and is now classified as such in the API reference.

## Follow-up Items

None.
