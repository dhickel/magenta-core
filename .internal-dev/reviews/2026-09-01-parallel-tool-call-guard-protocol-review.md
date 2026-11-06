# Scope

Reviewed the configurable tool-loop guard implementation in `DefaultTurnEngine`, its durable/live Spring AI message projections, provider adapters, Spring AI 2.0.0 message/tool execution source, focused tests, documentation/specifications, and Toast-It’s immutable vendored dependency pins in both the repository root and `toast-it-installer` mirror.

# Findings

## Confirmed and repaired: incomplete parallel tool-call pairing

Spring AI represents one assistant response as an `AssistantMessage` containing every `ToolCall`, followed by `ToolResponseMessage` entries keyed by call id. Its default tool manager executes every call and emits one response per call. OpenAI and DeepSeek map each response to a tool message with the matching `tool_call_id`; Magenta’s Responses adapter maps each to a `function_call_output`. Existing Magenta history comments and Responses tests document provider rejection when call/output references are incomplete.

The guard paths violated that invariant:

- the maximum-iteration boundary retained all calls but synthesized a result only for the last call;
- a repeated-error trip stopped execution after the triggering call and left every trailing retained call unanswered.

The engine now closes either retained batch before repair. The maximum boundary executes zero calls and emits one ordered typed `LOOP_EXCEEDED` result per call. A repeated-error batch preserves earlier successful/failing results, executes the configured Nth matching failure, converts that triggering result to `LOOP_EXCEEDED`, executes no later calls, and emits ordered `LOOP_EXCEEDED` results for every trailing call. Synthetic unexecuted tool-result/audit records omit original arguments; the assistant call item retains its original arguments because provider protocol and durable replay require them.

Strict scripted-model tests reject any assistant batch whose ordered call ids/names do not exactly match its responses. The tests also prove callback counts, progress order, durable tool-message order, audit order/category, repair-prompt pairing, and absence of blocked-call secrets from tool result/progress/audit projections.

## Confirmed and repaired: stale lifecycle explanation

`docs/explanation/turn-lifecycle.md` still described the superseded identical-call and sliding-error-window guards. It and the current tool how-to/tutorial/configuration reference now describe result-aware repeated errors, the N-1/N hard boundary, tool-free repair, and parallel-call closure.

## Other in-scope guard correctness

No additional material runtime defect was confirmed in the configurable guard paths. Builder wiring, positive validation, per-turn guard isolation, canonical call/error identity, success/difference reset behavior, exact Nth-failure execution, zero execution at the Nth tool-bearing response, tool-free repair, and failure on a further tool-bearing response remain unchanged and covered.

# Risk Assessment

Residual risk is low for the reviewed guard paths. Validation uses Spring AI source inspection plus deterministic strict prompt/provider seams rather than live external credentials. Provider wire formats differ, but all supported adapters consume the same complete Spring AI batch and existing transport tests cover their conversion.

The original `0.0.1-toastit-pinned-d7c146c5` bytes remain unchanged. New Core bytes are published only under `io.mindspice:magenta-core:0.0.1-toastit-pinned-7138a41d`:

- JAR SHA-256: `7138a41d84af12566f53105d829cc09fd0dae6dab38352f531d094524df4bebf`
- pinned POM SHA-256: `2b361eca68ef82db1dec290cfce60899d60d10dcf59421f4dac7e5250e7fee1f`
- upstream POM SHA-256: `b272c1b05b84260d74eb49f7f326641d0218d2938e729ec7156f44e359d9d00f`

Maven Dependency Plugin 3.7.0 cannot run `dependency:analyze` on Java 25 classfile major version 69. The enforcer, explicit dependency tree scan, JAR/package scan, `jdeps`, compile, tests, and consumer fixture provide the feasible dependency/package evidence; the tooling limitation is recorded in knowledge.

# Recommendations

- Keep the strict multi-call tests whenever tool-loop state transitions or message flushing changes.
- Preserve the invariant that a retained assistant call batch is fully and order-preservingly closed before any model reinvocation.
- Continue deriving immutable Toast-It pin suffixes from the exact Core JAR SHA-256 prefix; never replace bytes under an existing coordinate.

# Follow-ups

None in scope. Validation completed with 123 passing Core tests, 30 passing engine interaction tests including both new multi-call cases, five compiled tutorial programs, a `consumer-ok` standalone fixture, clean dependency/JAR/manifest scans, 40 focused passing tests in each Toast-It mirror, successful integrity-gated compile/package in each mirror, exact vendor/package hash checks, and clean `git diff --check` in both repositories.
