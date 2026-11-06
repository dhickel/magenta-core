# Magenta Core README positioning

## Date

2026-11-06

## Git Commit

a58078069f095f3b1b61a50b17ed2938e12dce0d

## Change Summary

Rewrote the root README to explain why Magenta Core exists, what it provides, how a turn flows, the caller-facing API style, and the concrete improvements it provides over assembling raw Spring AI primitives. The README now presents Magenta Core as a transport-neutral Java 25 library for streamlining agentic pipelines into applications, with programmatic construction through `TurnLoopBuilder`, persistent turn orchestration, typed response protocols, tools, durable questions, queueing, lifecycle controls, and explicit transport ownership.

The quick-start example was updated to the current `ModelConfig` variant builder and exhaustive `TurnResponse.Outcome` hierarchy. Installation, persistence, provider, SPI, transport-boundary, and documentation links were aligned with the current `0.2.0` artifact and repository docs.

## Files

- `README.md`

## Behavioral Impact

Documentation-only change. No runtime behavior, public signatures, provider routing, persistence schema, or dependency behavior changed.

## Specification Impact

Specification Impact: none. The change documents the existing branch-specific framework contract and does not alter intended behavior.

## Validation

- `mvn clean verify` — 130 tests passed, 0 failures, 0 errors, 0 skipped.
- Standalone `consumer-fixture` compiled against the built JAR and printed `consumer-ok`.
- Root README relative-link targets resolved.
- `git diff --check -- README.md` passed.
- README stale-symbol scan found no removed API names or legacy documentation paths.

## Risks

The README intentionally describes current branch behavior and the `0.2.0` coordinate. Future public API or release changes must update the README and linked documentation together.

## Follow-up Items

None.
