# Topic
Result-aware and disclosure-safe tool-loop guard semantics

# Source References
- `src/main/java/io/mindspice/magenta/core/ToolLoopGuard.java`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnEngine.java`
- `src/main/java/io/mindspice/magenta/core/TurnLoopBuilder.java`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/decisions.md` (`DECISION-20260901-01`)

# Key Takeaways
- Repeated tool use is not itself a loop defect. Guard the consecutive combination of canonical call identity and typed failure identity only after execution returns a failure.
- Sliding failure windows conflate unrelated requests. A success resets the streak; a different call or failure identity starts a new streak at one.
- Canonical call identity must normalize JSON object order and retain only a digest. Typed failure category should lead error identity, with a normalized message digest used only to distinguish failures in the same category.
- Provider-loop iteration count advances once per model response containing one or more tool calls, not once per individual call. At a maximum of N, iterations 1 through N-1 may execute and the calls in response N are blocked.
- A repair prompt must remove tool definitions, and an additional tool-bearing response must fail the turn. Otherwise a nominal maximum can become an unbounded repair loop.
- Provider protocol pairing is bidirectional: retaining a parallel assistant call batch requires one ordered response for every call before repair or model reinvocation. Hard-boundary calls and trailing calls after a repeated-error trip remain unexecuted but need synthetic typed results to close the batch. Do not duplicate blocked-call arguments into synthetic result or audit metadata.

# Engine Relevance
Keep repeated-error and maximum-iteration configuration independent in `TurnLoopBuilder`. Tests should exercise exact thresholds, all reset paths, canonical JSON equivalence, unrelated failures, successful repetitions, the N-1/N iteration boundary, builder wiring, an uncooperative model after tool-free repair, and strict multi-call call/result cardinality and ordering for both abort paths.

# Open Questions
None for the current public contract.
