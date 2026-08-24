# Phase 02: Programmatic construction and providers

## Context
The root JAR is established and the migrated source compiled, but the builder still depended on Spring context discovery and reflective provider setup.

## Goal
Make construction explicit and provider routing direct while keeping Spring AI internal.

## In Scope
- No-context `TurnLoopBuilder` with exactly one storage source.
- Immutable ordered model registration and deterministic selection.
- Direct Ollama/OpenAI-compatible/DeepSeek provider adapters, timeout/options/tool adaptation.
- Builder validation diagnostics and safe model close.

## Out of Scope
- Web/application integration, old package compatibility, runtime queue/interaction semantics beyond construction wiring.

## Implementation Steps
1. Remove context/scan/reflection dependencies from builder/router.
2. Validate storage, model aliases/configuration, schema initialization, and provider options.
3. Add focused builder/provider tests and inspect JAR/dependency boundaries.

## Validation
`mvn clean verify`; provider and builder tests; dependency and JAR scans; standalone consumer compilation.

## Exit Criteria
A consumer can construct the framework through Java calls only, with no Spring Boot or `ApplicationContext`.
