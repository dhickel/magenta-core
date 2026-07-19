---
schema_version: 1
document_type: simplypages-specification
status: active
owner: web
created: 2026-05-25
---

# SimplyPages Specification

## Intended Contract

SimplyPages is the default server-rendered frontend composition library for Magenta. Use framework-native components/modules, HTMX fragments, slot keys, row/column layouts, and demo-backed patterns before raw HTML or bespoke JavaScript. Avatar layout-editing and Work Area composition entries are removed; the neutral explorer and workspace selector use reusable SimplyPages composition.

## Neutral Explorer Composition

| component_or_module | status | intended_contract | observed_anchors | reuse_boundary | validation |
| --- | --- | --- | --- | --- | --- |
| Workspace explorer fragments | active | The explorer list, inspector, modal, and OOB targets use reusable SimplyPages composition with workspace-neutral IDs, classes, and routes. Slot-safe render context where shared instances require it. HTMX fragment swaps target stable list, inspector, and modal hosts for list refresh, inspector updates, and modal open/close. OOB list, inspector, and modal refreshes after mutations. | Repurposed `WorkAreaExplorerFragments` (renamed to `WorkspaceExplorerFragments`), agent Files tab shell | Keep Magenta domain editors, lifecycle controls, security wiring, and endpoint names out of SimplyPages core. Standard CRUD, filtering, row actions, form submissions, and partial refreshes use HTMX. | Focused Playwright visual critique: HTMX fragment swaps, OOB refreshes, modal/inspector stability, slot-key correctness where shared instances are used, and no replacement JavaScript transport. |
| Workspace selector control | active | A reusable SimplyPages fragment for execution submission surfaces. It presents the bound-agent default and only declared/existing user-workspace overrides; chat is never selectable. Rendered via HTMX with OOB refresh on user-workspace creation. | New reusable fragment mounted only where task/workflow/job submission exposes workspace choice | Selection interaction stays HTMX-first. JavaScript only for narrow interactions where it is clearly simpler. | Browser validation verifies pair submission, fallback presentation, HTMX viability, and JS justification. |
| User workspace creation control | active | A reusable HTMX form posts the validated name to `POST /api/workspace/user`, renders controlled `400`/`409` feedback, and OOB-refreshes every mounted workspace selector after successful creation. | New reusable fragment colocated with workspace choice controls | No rename, root deletion, generic browser, sharing, or permissions UI. | Controller and browser checks cover success, invalid names, collisions, and selector refresh. |

## Retained SimplyPages Entries

| id | component_or_module | status | intended_contract | observed_anchors | reuse_boundary | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SP-20260525-02 | HTMX default policy | active | CRUD, filtering, row actions, form submissions, and partial refreshes should use HTMX when it is the simpler path. | Dashboard, agent console, orchestration surfaces, explorer, skills browser | JavaScript stays narrow to streaming, local geometry, and interactions where it is clearly simpler. Remove Avatar transport/shell clients. | Browser validation verifies HTMX viability and JS justification for all changed surfaces. | `DECISION-20260522-07` | `operational-ui-htmx-inline-editing-pattern.md` |
| SP-20260525-03 | Reusable module candidates | candidate | Entity selector support, master/detail browser shell, inline editable list helpers, status badges, HTMX tab navigation, polling panels, and transcript/event feed panels may be worth upstreaming. | Magenta frontend surfaces and prior SimplyPages PRs | Keep Magenta domain editors, lifecycle controls, security wiring, and endpoint names out of SimplyPages core. | Upstream PR tests/docs when pursued. | none | `entity-selector-htmx-pattern.md` |
| SP-20260526-02 | Agent Skills browser/editor composition | active | Skill browser/editor and assignment panels should use reusable SimplyPages components/fragments (catalog rows, metadata editor, assignment panel, guided-creation steps) with HTMX-first updates. Slot-key reuse remains optional unless structures are shared across requests. | Implemented `/skills` shell and fragments in `SkillFragments`, shared operational shell/nav, shared `EntitySelectorComponents`, and Work Area details/list-inspired file table/editor. | Avoid page-specific duplicated markup for skill cards/forms across list, detail, and agent assignment contexts. Do not add UI affordances that imply script execution from browser controls. | Controller rendering tests and browser-validation evidence are reconciled in Phase 06; selector/path-script pitfalls from first-run Playwright checks are captured in knowledge for reuse. | `DECISION-20260526-06` | `agent-skills-specification-reference.md`, `agent-skills-ui-htmx-pattern.md` |

## Removed SimplyPages Entries

| id | component_or_module | status | notes |
| --- | --- | --- | --- |
| SP-20260525-01 | Avatar layout editing | removed | Avatar dashboard, layout editing, decorators, add-widget sections, insert-row separators are deleted with the Avatar product in Phase 03. In-place editing pattern knowledge survives for non-Avatar uses. |
| SP-20260525-04 | Work Area explorer | removed | Explorer UI repurposed for neutral workspace explorer (Phase 04/07); Work Area ownership removed. |
| SP-20260526-01 | Work Area/project browser interactions | removed | Superseded by neutral explorer composition and agent Files tab; Work Area and project-root ownership removed. |

## Ownership Boundary

This file owns SimplyPages composition policy. Concrete page contracts live in `web.md`; API contracts live in `api.md`.

## Drift/Gaps

| id | status | observed_drift | routing | review_after |
| --- | --- | --- | --- | --- |
| DRIFT-20260525-07 | watching | Some older operational surfaces may still use JS-heavy or ad hoc structures. | Evaluate during surface-specific refactors. | 2026-06-24 |

## Validation Expectations

Frontend changes should inspect relevant SimplyPages docs and demos before editing. Focused Playwright validation is required for UI behavior/layout changes unless specifically blocked and reported.

## Related Decisions

See `DECISION-20260719-02`, `DECISION-20260522-07`.

## Related Knowledge

Search knowledge filenames for `simplypages`, `htmx`, `entity-selector`, `workspace-file`, and `operational-ui`.
