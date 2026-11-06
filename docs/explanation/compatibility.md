# Clean-break compatibility and tradeoffs

Magenta Core is source- and schema-incompatible with the earlier pre-release
turn-loop API by design. This page explains each incompatibility and the
tradeoff behind it.

## What changed

The caller protocol is one sealed `TurnResponse` hierarchy. Separate result,
event, role-string, endpoint-discriminator, and interaction-state models are
not retained. Current code uses:

- `TurnResponse.Progress` and `TurnResponse.Outcome`;
- sealed `TurnMessage` role variants;
- sealed `ToolResult` and `ToolActivityData`;
- sealed `ModelConfig` provider variants;
- sealed `InteractionStore.Interaction` and `MutationResult` variants.

There are no deprecated constructors, aliases, compatibility packages, Maven
relocations, or default branches intended to hide future ADT variants.
Consumers should use exhaustive Java 25 pattern matching so protocol additions
become compile-time review points.

## Why failures are not outcomes

Interruption, timeout, queue acceptance, question state, and cancellation are
expected operational results and belong in `TurnResponse.Outcome`. Provider,
persistence, and internal execution failures are unexpected and use
`TurnFailedException` (with safe `TurnFailure` facts) or the Reactor error
channel. Mixing both classes of result would force callers to treat
infrastructure failure as successful protocol completion — and would make
exhaustive handling meaningless, because an "error outcome" could arrive on any
stream for reasons the application cannot act on.

The cost is a second failure channel that callers must wire (error handler or
try/catch). The benefit is that outcome handling is *complete by
construction*: every value in the ADT is something the application can respond
to with normal flow logic.

## Why queue claims are hidden

A queue receipt proves durable acceptance. It does not transfer processing
ownership. Keeping claim tokens, acknowledgement, release, and lease recovery
behind `MessageQueueStore` lets the framework preserve oldest-first and
question-recovery rules. The tradeoff is an at-least-once guarantee rather than
a caller-managed exactly-once claim protocol. Callers who need exactly-once
side effects must bring their own idempotency keys; callers who want to
inspect or manage the queue get no partial control (only session-level
deletion).

## Why interactions have their own IDs

An execution turn can ask questions, but the durable interaction has a separate
lifetime and compare-and-set boundary. Distinct IDs prevent a stale answer,
stale cancellation, queued message, or UI delivery receipt from being confused
with execution identity. The interaction UUID is persisted with the full
`Questions` snapshot before publication; answers and cancellations are
ID-qualified CAS operations with typed rejection outcomes, so concurrent or
stale clients lose cleanly and observably.

## Why the streaming and blocking paths share one protocol

`send` is the same engine stream as `stream`, reduced through the same
accumulator. A separate blocking "result" type would allow the two paths to
drift (for example, a blocking call reporting `Message` while a stream reports
`Interrupted` for the same execution). One protocol makes that impossible and
keeps the sealed ADT the single source of truth for callers.

## Why subscriber cancellation is not an interrupt

Disposing a Reactor subscription stops *that subscriber*; the execution is
fenced and lifecycle observers see `TurnCancelled`, but the cancelling
subscription receives no synthetic terminal value. `interrupt` is a separate,
durable control that terminates the active stream with `Interrupted`. Mixing
them would make subscriber disposal a two-phase protocol with ambiguous
termination. The tradeoff: an application that cancels a subscription must
rely on the lifecycle listener or a later drain to observe the cancellation.

## Why schema reuse is strict

SQLite `CREATE IF NOT EXISTS` can silently accept an old table with the right
name and wrong shape. Magenta therefore checks identity, version, exact schema
hash, and required structure before running the schema script. It preserves
incompatible `magenta_core_*` state for explicit operator handling and ignores
historical `turnloop_*` structures.

This favors deterministic startup over automatic migration of an unsupported
pre-release contract. Applications that need old data must perform an
explicit, application-owned export/import rather than pointing the new library
at incompatible Magenta Core tables. The strictness also protects future
versions: a schema change bumps the hash, and mismatched library/database
combinations fail fast instead of corrupting state.

## Why provider types remain internal

The public model configuration is closed to the provider families actually
implemented: Ollama, OpenAI-compatible, DeepSeek, and the Responses protocol.
Spring AI remains an
implementation dependency rather than an application API. This keeps consumer
code stable across provider-client changes and preserves the library's
transport-neutral boundary. The tradeoff is that provider-specific options are
not individually configurable through the public API; supported options
(thinking level, context length, API key rules) are modeled per variant with
constructor validation.

## Why credentials are redacted but serialized

`ModelConfig` JSON includes `apiKey` so configurations can round-trip through
the same sealed ADT — useful for persisted or transmitted configuration. To
limit exposure, `toString`, framework diagnostics, and validation errors redact
the value. The remaining risk (a serialized configuration file containing a
key) is documented, and callers choose whether to treat such files as secrets.
The alternative — excluding the key from JSON — would break round-tripping and
force callers to maintain parallel secret stores.

## Why the tool-loop guard exists

A model stuck repeating one failing tool request could otherwise spin side
effects forever. The result-aware guard trips only on the configured Nth
consecutive execution with both the same disclosure-safe canonical call and
the same typed failure identity. Successful repetition remains valid, while a
success or different call/error identity resets or restarts the streak. A
separate configurable iteration maximum blocks all calls in the Nth
tool-bearing model response. Either guard permits one repair response with no
tool definitions; another tool request fails the turn.

The tradeoff is that the Nth repeated failure must execute before the engine can
know its result matches the streak. The hard iteration maximum provides the
pre-execution side-effect bound. Canonical argument and error-detail digests
avoid retaining or reporting raw values, at the cost that unstable failure text
can conservatively restart a streak rather than produce a false match.

## Related

- [Turn lifecycle, serialization, and durable state](turn-lifecycle.md)
- [Architecture](architecture.md)
- [Persistence schema reference](../reference/persistence-schema.md)
- [Configure persistence how-to](../how-to/configure-persistence.md)
