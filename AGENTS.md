# Magenta Core engineering guide

Magenta Core is a transport-neutral Java 25 library. The repository root is the sole Maven project and publishes `io.mindspice:magenta-core`.

## Boundaries

- Public API lives under `io.mindspice.magenta.core` and uses Magenta-owned records, exceptions, tool callbacks, and SPIs.
- Spring AI messages/options/provider clients and all implementation details stay in `engine`, `internal`, and provider/persistence packages.
- Do not add Spring Boot application, auto-configuration, servlet, MVC/WebFlux, Actuator, Micrometer adapters, HTTP routes, or UI assets.
- `TurnLoopBuilder` is the programmatic composition root. It must not use `ApplicationContext`, scanning, bean discovery, YAML, or JSON configuration loading.
- Persistence is configured with exactly one `dataRoot` or caller-owned `DataSource`. Framework tables use `magenta_core_*`; legacy `turnloop_*` tables are untouched.

## Implementation style

Keep controllers and framework boundaries thin, direct, and testable. Prefer records for data carriers, package-private implementation classes, explicit lifecycle/ownership, and race-safe per-session serialization. Do not add speculative public APIs, compatibility shims, stubs, TODOs, or unsupported providers.

## Validation

Run focused tests for changed behavior and finish with `mvn clean verify`, dependency/package scans, JAR inspection, and a standalone consumer fixture. Test provider routing with deterministic compatible servers or seams; live external credentials are optional. Record durable architecture decisions, reusable lessons, phase evidence, and changelog provenance in `.internal-dev`.
