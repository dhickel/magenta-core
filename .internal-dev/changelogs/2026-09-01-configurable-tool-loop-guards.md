# Date
2026-09-01

# Change Summary
Replaced the successful-call and sliding-error-window guards with a result-aware consecutive repeated-error guard, exposed both tool-loop limits through `TurnLoopBuilder`, made the maximum iteration boundary a hard tool-free repair bound, and closed every retained parallel tool-call batch before repair.

# Files
- `src/main/java/io/mindspice/magenta/core/ToolLoopGuard.java`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnEngine.java`
- `src/main/java/io/mindspice/magenta/core/TurnLoopBuilder.java`
- `src/test/java/io/mindspice/magenta/core/ToolLoopGuardTest.java`
- `src/test/java/io/mindspice/magenta/core/TurnLoopBuilderTest.java`
- `src/test/java/io/mindspice/magenta/core/DefaultTurnLoopInteractionTest.java`
- `docs/reference/api.md`
- `docs/reference/configuration.md`
- `docs/how-to/register-tools.md`
- `docs/explanation/compatibility.md`
- `docs/explanation/turn-lifecycle.md`
- `docs/tutorials/tool-use.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/decisions.md`
- `.internal-dev/knowledge/tool-loop-guard-semantics.md`
- `.internal-dev/knowledge/java25-pmd-unused-code-scan-limitation.md`
- `.internal-dev/knowledge/java-artifact-validation.md`
- `.internal-dev/reviews/2026-09-01-parallel-tool-call-guard-protocol-review.md`

# Behavioral Impact
The configured Nth consecutive identical call-and-error failure now trips the repeated-error guard. Successes and unrelated calls/errors do not. A maximum of N tool iterations permits N-1 tool-bearing responses to execute and blocks response N before any of its calls run, followed by one tool-free repair opportunity. Multi-call abort responses remain provider-valid: every retained call receives one ordered response before repair. Earlier batch results are preserved, triggering/trailing repeated-error calls receive typed `LOOP_EXCEEDED` results, and every hard-boundary call receives the same result without executing. Synthetic unexecuted records omit original arguments from result and audit metadata.

# Specification Impact
The branch-specific framework contract in `architecture.md` and durable decision register now define the public builder seam, identities, reset behavior, exact iteration boundary, and hard repair behavior.

# Risks
Legacy `ToolLoopGuard` two-step methods remain source-compatible but require calls and responses to be paired. Embedding applications must explicitly select higher product limits when Core's conservative defaults are unsuitable. Provider adapters differ in wire shape, so strict prompt-level pairing tests cover the shared Spring AI message invariant while existing transport tests continue to cover adapter conversion.

# Follow-up Items
None.
