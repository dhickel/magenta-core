# Architecture

Magenta Core is one transport-neutral Java 25 Maven JAR. `TurnLoopBuilder` is the programmatic composition root: it validates one storage source, initializes the framework schema, constructs provider adapters and persistence collaborators, and returns the `TurnLoop` facade. It does not use application-context discovery, scanning, YAML, or JSON configuration.

The public package `io.mindspice.magenta.core` contains Magenta-owned records, exceptions, the facade, builder, and justified SPIs. Provider messages, options, callbacks, clients, and endpoint details remain behind engine, internal, and persistence boundaries. There is no Spring Boot application, auto-configuration, servlet, HTTP route, Actuator, metrics adapter, or UI surface.

The caller-visible protocol is one sealed `TurnResponse` root with nested sealed `Progress` and `Outcome` families. A single internal stream-to-outcome reducer gives blocking calls, streams, lifecycle observation, audit, and metrics the same terminal meaning. Unexpected failures use typed `TurnFailure` facts and the exception/stream error channel rather than an additional response variant.

Execution is serialized per session while independent sessions can progress concurrently. Direct work, question continuations, and framework-managed queue drain use typed origins. Durable questions and queued caller intent survive reconstruction; drain owns queue claim, lease, acknowledgement, release, and retry policy while callers observe only responses.
