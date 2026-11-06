# Sealed TurnResponse protocol implementation lessons

## Topic
Clean-break response, durable interaction, and framework-managed queue protocol in Magenta Core.

## Source References
- `.internal-dev/plans/20260824-134927-replace-turn-results-and-events-with-a-sealed-turnrespon/plan.md`
- `src/main/java/io/mindspice/magenta/core/TurnResponse.java`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnLoop.java`
- `src/main/resources/schema.sql`

## Key Takeaways
- Keep progress and expected terminal outcomes in one sealed hierarchy, but reduce both blocking and streaming paths from the same internal response stream.
- Interaction UUIDs, execution turn IDs, queued message IDs, and internal claim tokens have distinct ownership and must never be substituted for one another.
- Persist the exact Questions snapshot before emitting it; pending rehydration must be read-only and answer/cancel operations must qualify both session and interaction ID. Final context usage can be computed against durable history plus the asking turn's not-yet-flushed messages so the insert-only interaction snapshot is exact on its first write.
- Queue receipts are durable acceptance outcomes. Claim, acknowledgement, release, and lease recovery remain framework-owned store operations; crash recovery is at-least-once rather than exactly-once. Never clear an in-memory claim reference before `ack` succeeds; an acknowledgement failure must stop processing and preserve or release the same claim for oldest-first retry.
- Revoking persistence authority is not enough to implement a timeout: the caller-visible execution stream needs a shared forced-terminal signal that cancels its provider subscription and reduces through the same accumulator. This lets blocking and streaming callers receive one `TimedOut` outcome even when provider code ignores interruption, while registry fencing suppresses late writes.
- Drain recovery must inspect exact `ANSWERED` interaction markers before claiming queued work. Otherwise a crash after answer CAS can reorder the continuation behind later caller intent.
- A schema name change is not enough for a clean break: initialization must reject incompatible existing Magenta tables before `CREATE IF NOT EXISTS` can reuse them, while unrelated historical tables remain untouched.
- Existing tests and examples commonly encode old constructor arity and table names. Update those assertions rather than adding compatibility constructors or aliases.
- Do not replace unexpected provider errors with category-only text. Build `TurnFailure.message` from the unwrapped cause, redact the exact configured credential plus capability/approval secrets, strip controls, bound it to 500 characters, and retain the original throwable only as the internal exception cause. Keep the category-generic message solely as the no-usable-cause fallback. Apply the same sanitizer in both engine and facade fallback paths so outer reactive failures cannot bypass the disclosure boundary.
- `DefaultTurnLoop` stops an execution stream at the first `Outcome`. Any progress fact produced by successful post-turn maintenance, including a compaction notice, must therefore be emitted before the terminal outcome even though the maintenance runs after final usage is calculated and messages are persisted.

## Project Relevance
The public facade must not expose provider message types, Spring context types, synthetic queued turn results, or old response classes. Internal streaming state may still carry provider-specific streaming flags; that flag belongs in `TurnContext`, not `TurnRequest`.

## Open Questions
None for the accepted pre-release branch contract.
