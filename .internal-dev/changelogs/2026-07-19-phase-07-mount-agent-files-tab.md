# Phase 07 — Mount Agent Files Tab

## Date

2026-07-19

## Change Summary

Mounted a neutral, HTMX-first Files tab in the agent operational console and added reusable workspace selection controls to task, workflow, job, and direct-agent submission forms. Validator repairs completed the recent-actions and bounded-download affordances, hid mutation controls for the publication-only `outputs/` subtree, preserved the current directory after copy/move, prevented duplicate inspector/modal targets, validated selector pairs, and kept selector errors actionable.

## Files

- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java`
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceSelectionComponents.java`
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/main/java/io/mindspice/magenta2/api/web/selector/EntitySelectorComponents.java`
- `src/main/resources/static/css/orchestration.css`
- `src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java`

## Behavioral Impact

- `/agents/{agentId}` now exposes a Files tab fixed to `WorkspaceKind.AGENT` and the bound agent id.
- Stable fragment targets are `agent-files-explorer`, `workspace-file-list`, `workspace-file-inspector`, and `workspace-file-modal`.
- List, inspect, preview, image view, bounded download, text/Markdown create and save, directory create, rename, copy, move, recursive delete, tags/labels, and recent actions are reachable through HTMX-first controls.
- Explorer mutation controls are absent for the workspace root and `outputs/` subtree; service-level mutation rejection remains authoritative.
- Execution selectors submit both `workspaceType` and `workspaceKey`, or neither for bound-agent fallback. Chat is never offered; declared user workspaces are sorted and validated.
- Agent selector changes refresh the associated workspace selector through HTMX.

## Specification Impact

Implements the active Agent Files Tab and reusable workspace-selector contracts in `.internal-dev/specifications/web.md` and `.internal-dev/specifications/simplypages.md`. No specification text changed because those living specifications already describe the accepted Phase 07 behavior.

## Validation

- `mvn test`: 637 tests, 0 failures, 0 errors, 0 skipped.
- Bounded Spring startup: application reached `Started Magenta2Application`; timeout then stopped the process as intended.
- Focused transient render check: stable IDs were unique, selector fallback omitted both workspace fields, no chat option rendered, and `outputs/` mutation controls were absent. The transient test file was removed afterward and is not part of the 637-test suite.
- Final desktop/mobile evidence is stored under `artifacts/playwright/final-integration/`. It confirms the neutral stable IDs, responsive list/inspector stacking, usable controls, ten HTMX-attributed interactive elements, no Avatar fragment references, and successful file creation through the POST endpoint.

## Risks

- `/webjars/htmx.org/dist/htmx.min.js` returned `404` in the validation environment. This pre-existing serving issue is outside the workspace refactor; live client-side swaps remain dependent on resolving it.

## Follow-up Items

- Track the HTMX WebJar serving issue separately from the Files-tab refactor.

## Git Commit

Committed Phase 07/08 baseline: `93f7728b7259861e1b21e71fe4302daead0c871b`.
