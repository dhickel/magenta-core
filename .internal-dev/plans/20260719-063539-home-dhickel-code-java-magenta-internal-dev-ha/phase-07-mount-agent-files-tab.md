# Phase 07 — Mount Agent Files Tab

## Context

Phases 02–06 provide the closed workspace identity, typed explorer/API, canonical metadata and actions, execution-selection contract, and removal of obsolete Avatar and workspace layers. This phase mounts those completed capabilities in the agent operational console; it does not add another workspace model or explorer service.

## Goal

Add a neutral, HTMX-first Files tab to `/agents/{agentId}` and reusable execution-selection controls for bound-agent fallback and declared user workspaces.

## In Scope

Edit only the Phase 07 targets from the orchestration ledger:

Production:

- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java` (new)
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceSelectionComponents.java` (new)
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/main/java/io/mindspice/magenta2/api/web/selector/` (only concrete selector classes required by the existing submission forms)
- `src/main/resources/static/css/orchestration.css`

Tests:

- `src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/selector/`

Test fragment rendering through the controller and selector tests rather than adding test files outside the ledger target set.

## Out of Scope

- Changes to `WorkspaceController` or the typed workspace API established in Phase 04.
- A mounted browser for chat or user workspaces.
- User-root rename or deletion, sharing, permissions, quotas, project file browsing, or a replacement personal dashboard.
- New custom JavaScript, compatibility routes, canonical-identity inputs, raw-root inputs, or another workspace-resolution abstraction.
- Phase 08 documentation, package-guide, record-closeout, and repository-wide integration work.

## Dependencies

- `phase-06-retire-obsolete-workspace-layers.md` must have passed its post-phase gate.
- The Phase 04 typed explorer operations and user-workspace collection API must be active.
- The Phase 05 all-or-none `workspaceType`/`workspaceKey` submission contract must be active.

## Constraints

- Keep every edit within the Phase 07 orchestration targets and preserve a valid Spring constructor graph.
- Use SimplyPages components and HTMX for standard interactions; raw HTML is an isolated, tested fallback only.
- Reuse `WorkspaceDirectoryService` and `WorkspaceExplorerService`; do not duplicate workspace resolution, confinement, or mutation policy.
- Use workspace-neutral routes, IDs, classes, labels, and operational-console styling.
- Do not add compatibility endpoints, custom JavaScript transport, roots or canonical identities in browser contracts, or another workspace abstraction.
- Mutations must refresh affected list, inspector, and modal state coherently without duplicate target IDs.

## Invariants and Edge Cases

- The mounted Files tab is fixed to `WorkspaceKind.AGENT` and the bound `{agentId}`. No request parameter may select chat or user content.
- Only `WorkspaceDirectoryService` constructs the descriptor. The controller accepts the agent id, delegates construction and validation, passes the descriptor end to end, and never accepts or reconstructs a root or canonical identity.
- An existing agent with no physical root renders an empty workspace. A nonexistent agent is a controlled not-found response. Successful file-producing mutations may lazily create the root without starter files or scaffolding.
- File-relative paths stay separate from workspace identity. Preserve encoded query values, HTML escaping, normalization, confinement, UTF-8 and preview/download limits, Markdown safety, collision responses, symlink and traversal rejection, symbolic-recursion rejection, and recursive-delete confirmation.
- `outputs/` is readable but publication-only. Explorer create, save, rename, move, copy, and delete forms must not mutate it or descendants. Workspace-root rename, move, copy, and delete remain unavailable.
- Selection submits both `workspaceType` and `workspaceKey` or neither. Neither means bound-agent fallback. Explicit agent selection, if rendered by an existing form contract, is fixed to the bound agent. User selection requires a currently declared user workspace. Chat is never offered.
- Selector options contain only type and key. They never contain a canonical identity, filesystem root, project id as a root selector, or a legacy workspace id.
- User workspaces are sorted by stable name. Empty lists, duplicate-create conflicts, invalid names, disappeared selections, and controlled service errors render actionable fragments without inventing a fallback user workspace.
- Every fragment response has one authoritative in-band target. OOB updates use unique stable IDs. No response inserts `workspace-file-modal` inside an existing `workspace-file-modal`.
- Standard tab loading, CRUD, selection, row actions, forms, errors, and partial refreshes use HTMX. Retain JavaScript only if it already owns a non-transport interaction and remains the demonstrably simpler mechanism.

## Implementation Steps

1. Read the required project and SimplyPages guides. Inspect the current agent-detail tab shell, submission forms under `OrchestrationController`, existing selector classes, Phase 04 explorer routes, and the neutral explorer artifacts left by Phase 03. Record the concrete forms that already expose workspace choice; do not add selection to unrelated forms.

2. Add `WorkspaceExplorerFragments` with SimplyPages components and small composable render methods. Define exactly these stable targets:
   - `agent-files-explorer` — complete Files shell;
   - `workspace-file-list` — breadcrumbs, toolbar, and listing refresh boundary;
   - `workspace-file-inspector` — selected-item details and actions;
   - `workspace-file-modal` — the single viewer/editor/operation modal host.

   Keep a small render-only route-prefix value/helper inside this class so all links and `hx-*` attributes use `/agents/_detail/{encodedAgentId}/files`. It may encode URLs but must not resolve descriptors or filesystem paths.

3. Render the complete preserved explorer interaction set: compact toolbar, breadcrumbs, stable details columns, selected-row state, separate inspector, text/Markdown create and save, directory create, rename, move, copy, recursive-delete preflight and execute, labels, tags, recent actions, preview, Markdown rendered/text modes, image view, and bounded download. Image/download URLs must use `/api/workspace/agent/{encodedAgentId}`. Render controlled empty, missing, collision, validation, limit, and service-error fragments. Do not render mutation actions for the workspace root or `outputs/` subtree.

4. Add agent fragment routes to `OrchestrationController` beneath the existing agent-detail route family:
   - shell/tab, list, inspect, viewer, and modal GET routes;
   - create-directory, create-text/Markdown, save, rename, move, copy, delete-preflight, delete-execute, label, and tag routes using the operation-specific methods established in Phase 04.

   For each route, accept only agent id and normalized file-operation inputs, ask `WorkspaceDirectoryService` for the agent descriptor, invoke `WorkspaceExplorerService`, and render through `WorkspaceExplorerFragments`. Do not duplicate existence, confinement, reserved-output, or mutation policy. Map domain failures to visible fragments and their established HTTP statuses.

5. Add `files` to agent-detail navigation and load `/agents/_detail/{encodedAgentId}/files` into `#agent-tab-panel`. Remove obsolete workspace-tab or Avatar wording from the controller-rendered agent console content and links. Preserve unrelated tabs and operational behavior.

6. Implement mutation responses with one in-band response plus OOB replacements as needed:
   - refresh `workspace-file-list` for structural or metadata changes;
   - replace or clear `workspace-file-inspector` when selection changes, moves, or is deleted;
   - close or replace `workspace-file-modal` without nesting or duplicating its id;
   - preserve the current directory when valid and fall back to the nearest valid parent after a move/delete.

   Error responses must leave unaffected state intact and place the visible error in the requesting target.

7. Add `WorkspaceSelectionComponents`. Render the bound agent fallback, sorted declared user workspaces from `WorkspaceDirectoryService.listUserWorkspaces()`, and the current selection. Use an HTMX-updated type/key selector when needed so a submitted form contains either no workspace fields or a coherent pair. A minimal shape is:

   ```text
   default -> workspaceType omitted, workspaceKey omitted
   agent   -> workspaceType=agent, workspaceKey=<boundAgentId>
   user    -> workspaceType=user,  workspaceKey=<declaredName>
   ```

   Never offer chat. Replace existing plain workspace controls only in task, workflow, job, plan, or direct-agent submission forms found in the scoped controller/selector files and already governed by the Phase 05 contract. Preserve form names expected by request records.

8. Keep `GET|POST /api/workspace/user` unchanged in `WorkspaceController`. In `OrchestrationController`, add only the operational HTMX adapter routes needed to render selector options and explicitly create a user workspace from a submission form. Delegate creation to `WorkspaceDirectoryService`, then return the refreshed reusable selector fragment. Handle invalid names and conflicts visibly; do not add a workspace administration page or alternate JSON API.

9. Finish neutral rules in `orchestration.css`: dense toolbar, compact details table, selected/hover/focus states, inspector grid, visible error treatment, modal/editor sizing, image containment, long-path wrapping, horizontal table fallback, and mobile single-column stacking. Scope selectors to neutral workspace/agent-console classes, remove remaining `.avatar-*` selectors in this file, and preserve operational-console borders, radii, density, and hierarchy.

10. Extend `OrchestrationControllerTest` and scoped selector tests. Cover:
    - Files-tab presence, fixed agent routes, and stable unique IDs;
    - existing-agent empty root versus missing-agent not-found behavior;
    - all rendered operation forms and method/route contracts;
    - list/inspector/modal OOB success behavior and visible error-status fragments;
    - encoded path values, escaped dynamic content, and API image/download URLs;
    - root and `outputs/` mutation controls absent or rejected;
    - no Avatar dependency, chat/user Files-tab selector, raw root, canonical identity, or replacement JavaScript transport;
    - bound-agent omission fallback, coherent explicit agent pair, sorted user pairs, no chat option, no partial pair, invalid/disappeared user handling, create conflict, and selector refresh.

11. Run the focused test targets, then `mvn test` and bounded Spring startup. With the application live and a valid agent, delegate Playwright validation using `gpt-5.3-codex` at medium reasoning. Exercise tab loading, lazy empty state, folder and text/Markdown creation, editing, rename, copy, move, delete confirmation, labels/tags, preview, Markdown modes, image/download, controlled errors, and agent/user selector behavior. Confirm the browser never crosses from the bound agent workspace.

12. Capture desktop and mobile screenshots during delegated Playwright validation. Critique alignment, spacing, density, scan hierarchy, control affordance, text/path wrapping, overflow, useful first-viewport content, modal sizing, focus/selected states, and mobile stacking. Repair every observed functional or visual defect within the scoped files, rerun affected checks, and then run the post-phase validator review-and-repair gate.

## Required Guides

- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/knowledge/workspace-file-explorer-details-list-rewrite.md`
- `.internal-dev/knowledge/htmx-fragment-error-statuses.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/core/02-layout-page-row-column-grid.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/core/07-mobile-rendering-model-and-responsive-behavior.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/patterns/03-htmx-endpoint-and-swap-patterns.md`
- `/home/dhickel/Code/Java/SimplyPages/docs/reference/components-and-modules-catalog.md`

If a listed absolute SimplyPages path is absent, resolve the same document under the repository’s configured SimplyPages documentation root; do not substitute ad hoc markup before checking the authoritative docs and demo.

## Technical Guidance

- Prefer SimplyPages composition for shell, rows, forms, inspector fields, errors, and modal content. Isolate any unavoidable raw fragment, escape every dynamic value, and cover it with a focused safe-rendering assertion.
- URL-encode each path/query value exactly once and HTML-escape rendered attributes and text. Do not infer a workspace identity from a URL string or `Path`.
- Keep controller methods thin: bind transport input, obtain the descriptor from `WorkspaceDirectoryService`, invoke existing explorer/directory behavior, select the fragment/status, and return.
- Build a complete fragment view value before rendering rather than querying services from nested component methods. This keeps OOB fragments consistent within one response.
- Use existing exception/status mappings from Phase 04. Do not convert invalid paths, missing entities, conflicts, oversized content, or reserved-output violations into generic success fragments.

## Validation

- Run `OrchestrationControllerTest` and all tests under `src/test/java/io/mindspice/magenta2/api/web/selector/`.
- Run `mvn test`.
- Run bounded Spring Boot startup without Avatar, Work Area, or obsolete workspace beans.
- Run delegated live Playwright validation and screenshot review on desktop and mobile.
- Inspect browser requests to verify standard CRUD, creation, selector updates, and tab refreshes are HTMX-driven and no custom workspace transport was added.
- Verify Files-tab requests always resolve the bound agent descriptor; selection controls submit both fields or neither and never offer chat.
- Verify read access to `outputs/` remains possible while every explorer mutation beneath it is rejected.
- Review the scoped diff for stale `.avatar-*`, `/avatar`, Work Area, raw-root, canonical-input, duplicate modal-id, placeholder, compatibility, and unrelated changes.
- Run the post-phase validator review-and-repair gate; do not begin Phase 08 until it reports PASS.

## Exit Criteria

- `/agents/{agentId}` exposes a complete neutral HTMX Files tab fixed to the validated bound-agent workspace.
- Existing agents with absent physical roots render an empty state; missing agents and all controlled explorer failures render correct visible status responses.
- Every preserved explorer operation is reachable with stable list, inspector, and single-modal-host updates; root and reserved `outputs/` mutations remain unavailable.
- Submission forms in scope use reusable controls that represent fallback by omitting both selection fields and explicit choices by a valid agent/user pair; chat, roots, and canonical identities are never offered.
- User creation from operational forms uses the established directory-service contract and refreshes selectors without adding another administration or JSON API.
- UI and CSS contain no Avatar branding and remain consistent with the operational console on desktop and mobile.
- Focused tests, `mvn test`, bounded startup, delegated Playwright checks, screenshot critique, and the post-phase validator gate all pass.
