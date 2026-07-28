## Date

2026-07-20

## Git Commit

Base phase commit: `c6907bdd2192accc3e9dff7017d6be46b43e4172` (remediation commit recorded at final closeout).

## Change Summary

Remediated all seven Phase 03 validator findings. Added a Linux provider mutation boundary using held `openat`-family directory descriptors, no-follow traversal, no-replace rename, and opened-file hard-link checks. Bound writer capabilities to exact trusted run locations, pruned hidden recursive file-tool trees, validated canonical task path structure through `WorkspacePathLayout`, and corrected the route inventory.

## Files

- `ConfinedPathMutationService.java`: race-resistant Linux relative-handle primitives and typed unsupported-provider failure.
- `WorkspaceDirectoryService.java`: canonical workspace/task-run provisioning and canonical agent-root deletion use confined structural primitives.
- `WorkspaceExplorerService.java`: all ordinary write/mkdir/move/copy/delete primitives use the confined boundary.
- `AgentFileToolService.java`: exact run-root resolution, per-entry recursive browse policy, immediate location-bound writer validation, confined writes.
- `TaskRunWriteCapability.java`, `TaskRunLifecycleService.java`: capability now carries and validates workspace kind/key, canonical task key, and run id.
- `ReservedWorkspacePathPolicy.java`, `WorkspacePathLayout.java`: canonical task-key and run-component validation.
- `OutputArtifactService.java`, `AgentShellToolService.java`: location-bound writer validation; shell remains unavailable in active contexts.
- `ConfinedPathMutationServiceTest.java` and focused existing tests: deterministic latch/hook swaps, hard links, provider fail-closed, recursive hidden trees, canonical grammar, and capability mismatch.
- `services.md`, `workspaces-tools-outputs.md`, and `reserved-tree-mutation-route-matrix.md`: intended contract, provider limitations, and accurate route status.
- `pom.xml`: direct JNA dependency for the deployed Linux libc boundary.

## Behavioral Impact

- Ordinary workspace mutations cannot follow replaced parent/destination paths; mutation occurs relative to the held verified descriptor.
- Existing multi-link regular files are denied before overwrite, append, move, copy-read, or deletion.
- Unsupported/non-default providers fail closed instead of using path-based mutation.
- Run writers can mutate only through a capability matching the exact trusted run location and current database fence.
- Recursive file-tool list/search omits and does not descend into `.skills` or publication staging.
- Ambiguous `tasks/<arbitrary>/<arbitrary>` paths are rejected rather than guessed to be task structure.
- Shell execution remains explicitly unavailable for active workspace/task contexts.

## Specification Impact

Updated `.internal-dev/specifications/services.md` to make held-handle mutation, provider fail-closed behavior, hard-link checks, and exact capability-location binding part of the intended service contract.

## Risks

- The confined primitive currently supports the deployed Linux 64-bit default POSIX provider only. Other providers intentionally fail closed until they receive an independently tested implementation.
- `OutputArtifactService` publication still has its Phase 04 publication-boundary work; the corrected route matrix no longer represents it as Phase 03 ordinary-route enforcement.
- Workspace-local `.skills` has no active mutation owner in this phase; the application root skill repository is a different tree.

## Follow-up Items

- Phase 04 must move publication writes to dedicated publication authority and the confined mutation primitive.
- Extend provider support only with deterministic race, no-follow, no-replace, and opened-file link-count tests.
