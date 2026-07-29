## Date
2026-07-28

## Change Summary
Phase 01 — Isolated `heldMutationKind` ThreadLocal lifecycle to one operation boundary in `WorkspaceExplorerService`. Removed thread-local sets from validation helpers (`requireOrdinaryMutationAllowed`, `reauthorizeSourceAndTarget`). Added `withHeldMutationKind` private generic wrapper that sets the descriptor kind, executes the body, and unconditionally removes state in `finally`. Wrapped all eight descriptor-backed mutation methods (`saveText`, `createDirectory`, `createTextFile`, `createMarkdownFile`, `rename`, `move`, `copy`, `delete`). Added reused-thread regression tests (success, validation rejection, primitive failure, metadata failure) using single-thread executor with `Future` synchronization. Extended adversarial coverage with AGENT/USER area classification tests and cross-kind non-interference tests.

## Files
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java` — production mutation wrapper
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerServiceTest.java` — reused-thread cleanup regressions (4 tests)
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/ReservedTreeExplorerAdversarialTest.java` — AGENT/USER area classification + cross-kind non-interference (12 new tests)

## Behavioral Impact
- No observable behavior change for callers or browsers
- Primitive reauthorization remains fail-closed, kind-aware, and independent for multi-path operations
- Root bridges remain descriptor-free
- `heldMutationKind` can no longer leak across operations on reused threads
- All existing reserved-tree policy decisions unchanged

## Specification Impact
none — the phase is a pure internal lifecycle fix; no specification or contract changed

## Risks
low — localized to ThreadLocal management in one class; all existing focused tests pass; startup smoke passes

## Follow-up Items
none
