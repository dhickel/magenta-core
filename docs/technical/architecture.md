# Architecture

Magenta Core is one Java 25 Maven JAR. `TurnLoopBuilder` is the programmatic composition root. It creates the storage boundary, initializes the framework schema, constructs provider adapters and repositories, and returns the `TurnLoop` facade.

The public package `io.mindspice.magenta.core` contains records, exceptions, the facade, builder, and justified SPIs. `engine`, `internal`, and `persistence` are implementation boundaries. Spring AI messages, options, tool callbacks, provider clients, retries, and endpoint details terminate inside those internal boundaries.

Execution is serialized per session, but independent sessions can progress concurrently. The state machine assembles durable memory, invokes a selected model, runs bounded tools, supports streaming and interruption, persists audit/lifecycle facts, and can suspend on `ask_user`. A pending interaction is durable and reconstructible after process restart.
