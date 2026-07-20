# Phase 03 — Reserved Trees and Executable Confinement

## Context

Task-run and skill roots must be visible or hidden according to purpose without allowing ordinary explorer, file-tool, shell, or adjacent application mutation paths to corrupt task structure, publication-owned output or staging state, or workspace skill state. Phase 02 supplies trusted task-run locations and monotonic writer capabilities; this phase makes those capabilities effective at filesystem mutation boundaries and determines whether executable behavior can be exposed truthfully.

## Goal

structural protections and truthful fail-closed executable boundaries

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ReservedWorkspacePathPolicy.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java

- Centralize reserved-path classification and operation decisions without creating another workspace identity or path grammar.
- Apply the policy at explorer, file-tool, controller-delegated, and every other existing application-managed filesystem mutation route found by targeted repository inspection.
- Validate the current writer capability at each run-working mutation boundary and make shell/script capability claims match the process boundary actually proved.
- Add adversarial policy, explorer, file-tool, shell, and tool-access tests, including link and check/use races.

## Out of Scope

- Inventing absent upload, archive, import, or generic mutation services.
- Implementing artifact publication, artifact access, retention, or reconciliation.
- Implementing skill trust, activation, packages, snapshots, or executable skills.
- Adding a generalized container/sandbox platform or redesigning the UI.
- Changing the closed `WorkspaceKind` model or constructing task-run workspace descriptors.

## Dependencies

phase-02-run-lifecycle-fencing.md

## Constraints

- `WorkspacePathLayout` remains the sole owner of structural names and factories; `WorkspaceDirectoryService` remains the sole trusted `WorkspaceDescriptor` constructor. Policy receives trusted descriptors/locations plus normalized root-relative paths, never caller-supplied roots or path-derived identity.
- Persisted or transported filesystem locations remain `(workspaceKind, workspaceKey, rootRelativePath)`; this phase introduces no absolute persisted path.
- Lexical normalization and `toRealPath()` checks followed by ordinary path mutation are not race-resistant confinement. Link components, destination replacement, parent replacement, and check/use races must be handled at the mutation primitive or the affected operation must fail closed.
- Assignment fences and run-writer fences are distinct. A run-working mutation requires the expected writer owner and monotonic fence from Phase 02; publication and Skills control-plane mutations use their dedicated authorities and cannot borrow a run-writer capability.
- A database fence cannot revoke an already running process. Without a demonstrated process boundary that prevents writes outside the allowed run-working area and terminates stale process trees before ownership handoff, workspace-bound shell/script behavior remains disabled.
- Task trees are structurally visible but immutable to ordinary routes. Workspace and run output trees are visible but publication-only. Publication staging is protected and not an ordinary working area. `.skills` is hidden and control-plane-only.
- `CHAT` remains non-task-selectable. This phase does not broaden chat execution or alter legacy workspace-root output placement.
- Preserve unrelated edits. If a discovered mutation bypass cannot be closed through the declared write targets, stop and request an approved orchestration scope revision; do not silently edit undeclared production targets or leave the bypass unguarded.

## Implementation Steps

1. Implement a closed classification in `ReservedWorkspacePathPolicy` for ordinary content, workspace outputs, tasks root, task-key root, run root, run working content, run outputs, publication staging, and `.skills`. Derive segments from `WorkspacePathLayout`; reject traversal, empty/ambiguous segments, encoded-separator bypasses at the transport boundary, and paths that cannot be classified without guessing. Keep classification lexical and side-effect free; it does not by itself prove filesystem safety.
2. Define explicit decisions for browse, ordinary mutation, structural mutation, publication mutation, Skills mutation, and run-writer mutation. Browsing may expose task structure and output content but not `.skills` or staging. Ordinary mutation cannot alter any reserved tree. Structural, publication, and Skills mutations require their owning internal path. Run-writer mutation is limited to run working content and requires a current matching capability; it never authorizes run outputs, staging, sibling runs, task-key structure, or `.skills`. Default unknown combinations to deny.
3. Use the single repository-backed Phase 02 capability-validation operation at the filesystem mutation boundary. Validate expected run identity, owner, fence token, active attempt/claim state, and location immediately before every write primitive; do not cache authorization across calls, batches, retries, or process lifetime. Return a typed stale/denied result before touching the filesystem when validation fails.
4. Refactor explorer create/save/rename/move/copy/delete paths through the policy and confined mutation boundary. Classify both source and destination for two-path operations. Permit browsing of task trees and outputs according to visibility policy, deny ordinary mutation inside reserved trees, reject `.skills` and staging access, and prevent deletion or replacement of workspace/task/run roots. Ensure controller-delegated operations cannot select a weaker service path.
5. Resolve active file-tool operations from the trusted `TaskRunLocation` beneath the run root, never from an arbitrary path or the containing workspace root. Pass the current `TaskRunWriteCapability` for each create, overwrite, append, rename, move, copy, or delete. Restrict writes to run working content; reads still obey visibility and hidden-root rules. Remove or narrow legacy host-path inputs only after every consumer uses the trusted values.
6. Search controllers, services, tools, upload/import/archive handlers, copy/move helpers, and direct `Files` mutation calls to enumerate every existing application-managed mutation route. Record a route matrix containing operation, source/destination classification, required authority, enforcement point, and focused test. Route each existing path through the declared policy/service boundary. Record genuinely absent routes rather than adding placeholders or speculative APIs; escalate any required out-of-target production edit as a phase-scope conflict.
7. Inspect and test the actual process-launch boundary used by `AgentShellToolService`. Enable workspace-bound shell only if the boundary proves all of the following: writable access is limited to the authorized run-working area; task structure, sibling runs, outputs, staging, and `.skills` are read-only or unreachable; child processes cannot escape the boundary; and revocation terminates the complete process tree and confirms termination before a new writer takes ownership. Otherwise remove workspace shell exposure and return an explicit confinement-unavailable result. Record the unavailable capability for Phase 07 rather than adding a generalized container layer or claiming a launch-time fence is sufficient.
8. Implement race-resistant path handling at each mutation primitive: reject symlink components and destination links; walk from a trusted workspace/run parent using provider-supported relative directory handles; verify the opened parent identity and target classification; and perform the mutation relative to that handle. Recheck source and destination parent identity for two-path operations. Reject mutation of multi-link files wherever link-count support exists, including ordinary-path aliases that could reference protected bytes. Document provider capability and fail closed for affected operations when equivalent race/link safety cannot be proved; repeated real-path checks are not an acceptable protected-tree fallback.
9. Add adversarial tests for lexical traversal, encoded separators, absolute paths, Unicode/case edge cases accepted by the deployed provider, symlink components, destination-link swaps, parent replacement, hard-link aliases, cross-boundary copy/move, root deletion/replacement, stale or expired capabilities, mismatched owner/fence/location, stale subprocesses, and every route in the mutation matrix. Coordinate swap tests with latches/hooks so the replacement occurs between validation and mutation rather than relying on timing. Run provider-specific link/race tests on a supporting filesystem and assert explicit fail-closed behavior for unsupported providers.

## Invariants and Edge Cases

- Classification never grants authority; the operation decision and trusted authority must both match.
- No ordinary route can create, replace, rename, move, copy into, copy out through mutation, or delete task structure, outputs, staging, or `.skills`.
- Run-writer authority is valid only for its exact workspace, task key, run ID, active owner, and fence. Resume uses the new attempt/fence for the same run location; retry/replay capabilities cannot write their parent or sibling run.
- Source and destination are independently classified. A permitted source does not make a protected destination writable, and a protected readable source does not make copy-out mutation implicitly valid.
- Existing final artifacts and legacy root outputs are not modified by ordinary routes. This phase does not move legacy files or create historical task directories.
- A symlink-free lexical path is still denied when its trusted parent changed before mutation. A real-path check is evidence only for the instant it ran, not a substitute for a relative-handle mutation.
- Hard-link protection applies before mutating an existing file because an apparently ordinary file may alias protected bytes. Unsupported link-count inspection yields the documented denied/unavailable behavior where safety cannot otherwise be established.
- Batched writes validate each mutation. A capability becoming stale between items stops remaining items; no previous success authorizes later writes.
- Shell launch authorization does not authorize the process indefinitely. Failure to prove isolation and revocation produces a stable confinement-unavailable result and leaves affected shell/script and future executable-skill behavior disabled.
- Denials reveal no hidden `.skills` contents and do not weaken policy based on whether a protected path currently exists.

## Required Guides

- `AGENTS.md`, `.internal-dev/AGENTS.md`, and `.internal-dev/specifications/AGENTS.md`
- Relevant architecture, service, API, workspace, tool, and API package guides
- `.internal-dev/knowledge/file-tool-workspace-scope-pattern.md`
- `.internal-dev/knowledge/shell-tool-confinement-pattern.md`
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md`

## Technical Guidance

Keep `ReservedWorkspacePathPolicy` a small, closed decision component. It should consume a normalized workspace-relative path and trusted context, classify by `WorkspacePathLayout` grammar, and return allow/deny plus the required authority; it should not discover workspace identity, construct roots, or perform database access.

Keep filesystem safety in the mutation boundary used by explorer and file tools. A permitted decision is only input to a mutation attempted relative to a verified trusted parent. Prefer provider primitives such as `SecureDirectoryStream` when they supply the required relative operations; feature-probe rather than assume support. Do not hide a path-based fallback behind the same “confined” result when it cannot resist parent replacement.

Keep capability validation narrow and repository-backed. The service performing the write supplies the expected owner/fence and trusted run identity, receives a current/denied result, then performs exactly one guarded mutation. Publication and Skills authorities remain separate even if they later reuse the same classifier.

For process execution, distinguish three outcomes: proved confined and revocable, explicitly unavailable, or configuration/error failure. Only the first may expose workspace-bound shell behavior. A clear disabled result is preferable to an unenforced safety claim.

## Validation

- Use the required `gpt-5.3-codex` medium-reasoning test subagent for focused policy, explorer, file-tool, shell, and access-policy tests.
- Exercise every route in the mutation matrix against every applicable classification, including both source and destination for copy, move, and rename.
- Run deterministic link/parent-swap tests on a supporting filesystem and explicit unsupported-provider fail-closed tests. Verify no protected bytes or structure change after denial.
- Verify fresh, stale, mismatched, revoked, resume, retry, and replay writer capabilities at each write-like operation and across interrupted batches.
- Either prove shell isolation and revocation with real subprocess/process-tree tests or prove workspace shell is unavailable and cannot be reached through alternate tool paths. Do not replace this gate with unit-only claims.
- Run the relevant focused tests, broader Maven suite, and bounded Spring Boot startup. Follow repository blocker policy if real execution requires unavailable infrastructure.
- Review the final diff for bypassing `Files` mutations, authorization caching, caller-supplied roots, absolute persisted paths, path-derived identity, speculative APIs, weakened provider fallbacks, placeholders, and unrelated edits.
- Complete required specification, knowledge, changelog, and phase-plan closeout records without changing other phase files, then submit evidence to the required `openai-codex/gpt-5.6-sol` medium-reasoning validator for review-and-repair.

## Exit Criteria

- One closed policy owns reserved-tree classifications and operation decisions, and the mutation-route matrix shows every existing application-managed route enforcing it.
- Ordinary routes cannot mutate task structure, workspace/run outputs, publication staging, or `.skills`, including through traversal, links, parent replacement, two-path operations, or unsupported-provider fallback.
- Every run-working mutation requires a fresh, current, matching writer owner and fence at the mutation boundary; stale, mismatched, revoked, sibling, parent, and cross-workspace capabilities fail before filesystem change.
- Workspace-bound shell behavior is enabled only when real isolation and process-tree revocation are proved. Otherwise all affected shell/script behavior returns the explicit confinement-unavailable result, and Phase 07 receives a truthful disabled executable boundary.
- Focused adversarial tests, the mutation-route matrix, broader Maven suite, bounded startup, and required real process/provider checks pass with no unapproved blocker.
- Internal-development records and relevant contracts describe the implemented boundary and provider limitations without claiming unavailable protection.
- Post-phase validator review-and-repair reports PASS before Phase 04, Phase 06, or Phase 07 begins.
