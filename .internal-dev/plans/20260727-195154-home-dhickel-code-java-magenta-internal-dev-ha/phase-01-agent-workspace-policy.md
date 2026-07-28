# Phase 01 — Agent Workspace Policy

## Context

Close ordinary AGENT-root writing without changing the closed `WorkspaceKind` set or granting future materialization, skill, task, output, or storage-area authority. The AGENT root has eight lazy conceptual areas: `.skills`, `scripts`, `apps`, `home`, `repos`, `projects`, `tasks`, and `outputs`. Existing unknown root children retain only reads already permitted by current policy; they are neither migrated nor made ordinarily writable.

## Goal
enforce the closed AGENT root and ordinary writer policy

## In Scope
**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentWorkspaceArea.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ReservedWorkspacePathPolicy.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ConfinedPathMutationService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java

Implement the closed AGENT-area grammar and enforce it at layout, trusted directory, policy, explorer, confined-mutation, and file-tool boundaries. Add focused regression and adversarial coverage for the changed behavior and update an affected package guide only when the implemented package responsibility changes.

## Out of Scope

Materialization records or authority, context bindings, API/UI composition, and new workspace kinds. Do not add a `PROJECT` workspace, project root, generic workspace identity, alias, link, mount, lease, application metadata tree, or content-type-specific behavior for `repos`. CHAT and USER behavior changes only where shared confinement must preserve their existing contract.

## Dependencies
none

## Constraints

Use no raw root, caller-supplied location, data-root fallback, or path-derived identity as authority. Reads and conceptual navigation do not create directories. Use held-handle primitives for all approved ordinary mutations; an unsupported filesystem implementation is unavailable, not weakly confined. Preserve publication create-only semantics and the existing task-run, output, CHAT, USER, and skill contracts outside the explicit policy integration.

## Invariants

- `WorkspacePathLayout` remains the sole structural grammar, and `WorkspaceDirectoryService` remains the sole constructor of trusted `WorkspaceDescriptor` values. Neither identity nor authority is reconstructed from `Path`.
- `AgentWorkspaceArea` contains exactly `.skills`, `scripts`, `apps`, `home`, `repos`, `projects`, `tasks`, and `outputs`. Only descendants of `home`, `scripts`, and `apps` are ordinary personal content; their area roots are not ordinary mutation targets.
- `.skills`, `tasks`, `outputs`, `projects`, and `repos` are reserved to their distinct ownership boundaries. `repos` is a conceptual, lazy area for user-managed storage outside Magenta, not an application-managed content type. Existing descendants may be exposed only through already-permitted descriptor-confined reads; Magenta never provisions or mutates the area. This phase grants no materialization or storage-area write authority. `scripts` and `apps` are storage only and never execution authority.
- Reads and synthetic navigation never create directories. Authorized personal writes create only the required canonical AGENT root/personal-area/descendant chain through held handles. AGENT and CHAT roots remain lazy; USER creation semantics remain unchanged.
- Ordinary AGENT mutation denies the AGENT root, every canonical area root, all unknown root children and descendants, and reserved trees. Application policy does not inspect or interpret content stored beneath `repos`.
- Every mutation independently authorizes source and destination immediately before its held-handle primitive. Traversal, encoded separators, links, hard links, rebinding, stale descriptors, root mutation, and unsupported-filesystem operation fail closed. Rename remains no-replace.
- A file-tool mutation requires a current trusted descriptor or trusted task-run scope. No-context mutation fails before caller-path resolution; a data-root or raw host-path fallback cannot claim workspace confinement. Current task-run writer validation remains required.
- Error, audit, and tool-visible results expose no absolute root, capability secret, or internal path resolution detail.

## Implementation Steps

1. Add `AgentWorkspaceArea` with exactly `.skills`, `scripts`, `apps`, `home`, `repos`, `projects`, `tasks`, and `outputs`. Encode personal ordinary content (`home`, `scripts`, `apps`) versus reserved owner-only content; do not encode materialization, task, skill, output, or content-specific capability in this phase.
2. Add layout factories and segment validation for canonical personal-area paths and stable-key project children. Accept only validated structural segments; reject display-name, current-workspace, project-root, and alias forms. Keep `repos` as a reserved lazy area without assigning application behavior to its descendants.
3. Make `ReservedWorkspacePathPolicy` classify `WorkspaceKind` plus a normalized relative path explicitly. For AGENT, distinguish root, canonical area root, personal descendant, materialization descendant, reserved tree, and unknown root child/subtree; retain established CHAT and USER behavior. Policy is lexical and side-effect-free: it receives no raw root and performs no filesystem or persistence access.
4. Deny ordinary AGENT mutation at the root, all canonical child roots, unknown root children/subtrees, and reserved trees. Allow it only below a personal area after the caller has an authorized trusted descriptor. Treat `projects` and `repos` as reserved and denied here; do not create an interim exception.
5. Give `WorkspaceDirectoryService` an area-specific personal provisioning path that revalidates the descriptor and creates only the canonical personal chain with held handles. Ensure generic resolution cannot create an AGENT root for a target that policy will deny. Do not provision `.skills`, `tasks`, `outputs`, `projects`, or `repos`.
6. Make `WorkspaceExplorerService` render absent conceptual AGENT areas as synthetic, non-mutating entries while retaining permitted reads of existing unknown entries and existing `repos` descendants. `repos` reads remain descriptor-confined and read-only; an absent area stays synthetic. A first authorized personal write provisions only its personal chain. Reauthorize source and destination immediately before move, copy, rename, or delete primitives; do not rely on a preflight result after handles are opened.
7. Remove the mutable data-root fallback from `AgentFileToolService`. Resolve a mutation only from a current trusted descriptor or trusted task-run scope, classify the workspace-relative target, and reject no-context writes before path resolution. Preserve current task-run writer checks and preserve CHAT/USER behavior except for shared confinement enforcement; do not turn a raw context path into workspace authority.
8. Extend `ConfinedPathMutationService` only for required held-handle read/copy support. Copy must validate and open the source without following links, independently validate the destination parent and leaf, check regular-file link count, and preserve create-only/no-replace behavior. Keep typed unsupported-filesystem failure and prohibit a JDK/path-based fallback carrying the confined claim.
9. Inventory production filesystem mutation routes. Prove every ordinary AGENT writer reachable through the declared targets uses this policy and confinement boundary; classify later materialization, skill, task, and output owner-only writers without changing them, and verify that no Magenta writer owns `repos`. If an external ordinary AGENT writer exists, stop exit approval and escalate the missing ledger ownership rather than broadening this phase.

## Required Guides

- `AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md`
- `.internal-dev/knowledge/reserved-tree-mutation-route-matrix.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/AGENTS.md`

## Technical Guidance

Keep structural classification separate from authority. The policy may determine that an AGENT personal descendant is eligible for `ORDINARY_MUTATION`; the service still supplies the trusted descriptor and confined primitive. A minimal decision shape is:

```text
AGENT + personal descendant + ORDINARY_MUTATION -> allow
AGENT + projects/repos descendant + ORDINARY_MUTATION -> deny
AGENT + unknown root child/subtree + ORDINARY_MUTATION -> deny
```

Synthetic explorer entries represent absent canonical areas only. They must not be materialized by listing, preview, search, or policy classification. For a multi-path operation, authorize each exact normalized relative path and revalidate it at the source or destination handle immediately before the relevant primitive.

## Validation

Add or update focused tests covering:

- the exact eight-area set; AGENT root, area-root, personal-descendant, unknown-child/subtree, and reserved-tree decisions;
- stable-key and path-segment rejection for traversal, absolute paths, backslashes, encoded separators, case variants, aliases, and display names;
- absent-area synthetic navigation and the absence of filesystem side effects from all reads;
- lazy personal write provisioning of only the required chain, with no provisioning of `projects`, `repos`, `.skills`, `tasks`, or `outputs`; descriptor-confined reads of existing `repos` descendants; and denial of every Magenta `repos` mutation;
- independent source/destination checks for copy, move, rename, and delete; no-replace collision behavior; links, hard links, rename races, rebinding, stale descriptors, and typed unsupported-filesystem failure;
- file-tool no-context write denial, trusted descriptor/task-run acceptance, current task-run writer rejection, and no mutable data-root fallback;
- unchanged CHAT, USER, task-run, output-publication, and skill behavior under their existing owners.

Run the focused workspace and file-tool tests, the applicable Maven suite, the required forbidden-construct scan for ordinary AGENT writers and data-root mutation fallback, and bounded application startup. The phase validator repairs in-scope defects and records PASS before a dependent phase begins.

## Exit Criteria

- The AGENT root is closed to ordinary mutation: only descendants of `home`, `scripts`, and `apps` can be lazily provisioned and written by ordinary authorized paths.
- Explorer and file-tool routes neither create arbitrary AGENT-root content nor expose ordinary mutation of unknown, reserved, materialization, task, skill, or output paths.
- `projects` and `repos` are reserved without a provisional authority model; `repos` remains user-managed outside Magenta and descriptor-confined read-only from Magenta's perspective, with no application-specific behavior.
- Every declared ordinary AGENT writer is covered by the policy and held-handle boundary, or an external bypass has been escalated and prevents PASS.
- Focused tests, applicable Maven validation, bounded startup, static writer/fallback scan, and post-phase validator review PASS are recorded.
