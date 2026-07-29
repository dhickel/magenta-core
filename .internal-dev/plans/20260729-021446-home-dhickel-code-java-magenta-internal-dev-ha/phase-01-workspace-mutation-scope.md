## Context

`WorkspaceExplorerService` keeps descriptor kind in `heldMutationKind` so the confined primitive hook can reauthorize each exact relative path immediately before I/O. Current validation helpers set that state without an operation-wide cleanup boundary. A reused thread can therefore carry AGENT or USER classification into a later descriptor operation or a package-private root bridge.

## Goal

isolate mutation authorization state to one operation

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerServiceTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/ReservedTreeExplorerAdversarialTest.java

- Descriptor-backed ordinary filesystem mutations: save text; create text, Markdown, and directories; rename; move; copy; and execute-delete.
- The `heldMutationKind` lifecycle and the primitive-hook reauthorization that consumes it.
- Focused regressions for reused-thread cleanup and AGENT/USER reserved-tree classification.

## Out of Scope

- Reserved-tree classification rules, confined primitive implementation, workspace identity/layout, read operations, metadata label/tag operations, delete preflight, new workspace kinds, routes, payloads, schema, UI, and JavaScript.
- Changing descriptor-free package-private root bridges into descriptor-authorized APIs.

## Dependencies

none

## Constraints

- `WorkspacePathLayout` remains the structural-path authority and `WorkspaceDirectoryService` remains the sole trusted `WorkspaceDescriptor` constructor. Do not infer identity from a `Path`.
- Every descriptor-backed mutation must hold only its own descriptor kind from the start of its body through validation, root/area provisioning, confined primitives, metadata dispatch, decoration, and action logging. It must clear state on success, validation rejection, primitive failure, metadata/action failure, and unexpected failure.
- Never restore a prior thread-local value. A nested or reused thread must not inherit authorization from an earlier operation.
- Reauthorize each exact primitive path immediately before the primitive. Rename, move, and copy must independently authorize source and destination; copy must preserve authorization for every source read and destination write/create primitive.
- Preserve fail-closed confined mutation behavior, link checks, no-replace semantics, descriptor confinement, and all reserved-tree decisions. A descriptor mutation must not reach the primitive hook without held kind; existing package-private root bridges remain descriptor-free and gain no descriptor authority.
- Preserve delete preflight as a read-only validation path. Execute-delete, including its preflight and post-delete metadata/action work, is within the mutation wrapper.

## Implementation Steps

1. Make ordinary-mutation and source/target validation pure. Remove every `heldMutationKind.set(...)` from `requireOrdinaryMutationAllowed(...)` and `reauthorizeSourceAndTarget(...)`; retain their current kind-aware classification, rejection messages, and source/destination independence.
2. Add one private generic operation wrapper for descriptor-backed mutations. Before setting state, require non-null descriptor, descriptor kind, and body. Set the current kind, execute the entire supplied body, and call `heldMutationKind.remove()` in `finally`; do not read or restore an old value.

   ```java
   private <T> T withHeldMutationKind(WorkspaceDescriptor descriptor, Supplier<T> body) {
       WorkspaceKind kind = Objects.requireNonNull(descriptor, "descriptor").kind();
       heldMutationKind.set(Objects.requireNonNull(kind, "workspace kind"));
       try { return body.get(); }
       finally { heldMutationKind.remove(); }
   }
   ```

3. Wrap each top-level descriptor mutation exactly once, before its first validation or provisioning call: `saveText`, `createDirectory`, `createTextFile`, `createMarkdownFile`, `rename`, `move`, `copy`, and `delete`. Keep all existing validation, root/area creation, primitive calls, metadata updates, action logging, and return decoration inside that wrapper. Do not wrap descriptor reads, label/tag operations, delete preflight, or package-private `Path` bridges.
4. Keep primitive reauthorization fail-closed and descriptor-scoped. The hook reads the held kind and invokes a pure kind-aware ordinary-mutation check for the exact relative primitive path. `reauthorizeSourceAndTarget` continues to validate both logical endpoints before rename/move/copy; the hook rechecks each actual primitive endpoint. A null held kind may bypass descriptor reauthorization only for the existing package-private root bridges, never for a descriptor-backed mutation.
5. Add reused-thread regressions using one single-thread executor and explicit `Future` synchronization. For each exit path—success, validation rejection, injected primitive failure through the confined mutation hook, and injected post-primitive metadata/action failure—run a descriptor mutation and then a kind-sensitive descriptor-free root-bridge mutation on the same worker. The second operation must behave under its own kind-agnostic bridge policy, not under stale AGENT or USER state. Shut down the executor in cleanup, await termination, and preserve interruption.
6. Extend adversarial coverage for both AGENT and USER descriptors. Assert that AGENT `home/<child>` retains personal-area mutation behavior while the direct `home` area root and unknown children remain denied; USER `home` and unknown children use ordinary-content behavior. For both kinds, assert denial for `outputs`, `tasks`, and `.skills`, including source and destination cases for rename/move/copy where applicable. Verify that a prior opposite-kind operation cannot change any of those outcomes.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md

## Technical Guidance

- Keep the thread-local solely as a local lifecycle carrier for the existing primitive hook; do not broaden it into an authorization mechanism or pass `Path`-derived identity through it.
- A direct root bridge must still use only its existing kind-agnostic validation. The test must prove that it cannot observe stale descriptor authority after any prior descriptor mutation exit.
- Use injected failures at the actual boundaries under test. A primitive-hook failure must occur after wrapper entry; a metadata/action failure must occur after primitive success, so both prove `finally` cleanup rather than only pre-validation cleanup.
- Do not weaken a test by calling both actions on the test thread or by using sleeps/polling. The same executor worker is the evidence of thread-local reuse.

## Validation

- Run `WorkspaceExplorerServiceTest` and `ReservedTreeExplorerAdversarialTest`, including the new single-worker success, validation-failure, primitive-failure, metadata/action-failure, AGENT, and USER cases.
- Run `ReservedWorkspacePathPolicyTest` and `ConfinedPathMutationServiceTest` to confirm unchanged classification and primitive guarantees.
- Search production `WorkspaceExplorerService` for `heldMutationKind.set`; the wrapper must be the sole setter and its `finally` must unconditionally call `heldMutationKind.remove()`. Confirm no validation helper writes the thread-local.
- Review the diff for changes outside the three write targets and for path-layout, descriptor-authority, provider-fallback, reserved-policy, API, schema, or UI drift.
- Run the applicable bounded Spring Boot startup smoke after focused tests. No browser validation is expected because the phase preserves browser-visible behavior; any observed UI change is scope drift.

## Exit Criteria

- Every in-scope descriptor-backed mutation installs only its current descriptor kind for its complete operation and leaves no ambient kind on every exit path.
- Primitive reauthorization remains immediate, kind-aware, and independent for multi-path operations; root bridges remain descriptor-free.
- Reused-thread tests prove that success, validation rejection, primitive failure, and post-primitive metadata/action failure cannot leak AGENT or USER policy into a later operation.
- AGENT/USER `home` and unknown-child distinctions, and `outputs`, `tasks`, and `.skills` denials, pass without changing reserved-tree policy.
- Focused tests and bounded startup smoke pass, the phase diff remains within the ledger write targets, and the validator review-and-repair gate reports PASS.
