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

## Risks

- This validator performed code-level and startup validation only; it did not run the broader Phase 07 delegated Playwright campaign because the validator task requested Maven, startup, and contract inspection checks.

## Follow-up Items

- Phase 08 owns repository-wide documentation and record reconciliation.

## Git Commit

Current HEAD (pre-change): `a056eee`
