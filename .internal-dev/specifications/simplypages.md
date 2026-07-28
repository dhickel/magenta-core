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

## Operational Run Fragment Composition

`RunOperationsFragments` is a reusable stateless SimplyPages renderer over `RunSummary` and `RunAudit` values. It owns no persistence or scope resolution. `OrchestrationController` supplies authorized bounded data, while `WorkspaceExplorerFragments` supplies only the stable independent host. Component-built headings, tables, buttons, chips, facts, and states are preferred; no transport JavaScript or raw-path markup is introduced.

Stable hosts are `#agent-run-groups` for bounded groups, `#agent-run-detail` for expansion, and `#artifact-preview` inside the selected run. Refresh OOB-replaces only the groups host; expansion and lineage navigation replace only detail; artifact preview delegates to the artifact-owned authorized fragment. Filesystem list/inspector/modal OOB behavior remains separate, preventing file mutations or stale run responses from replacing unrelated domain panels. Responsive CSS stacks the independently useful panels without changing server composition.

## Agent Console Fragment Composition

| component_or_module | status | intended_contract | observed_anchors | reuse_boundary | validation |
| --- | --- | --- | --- | --- | --- |
| Agent context panel | active | `AgentContextFragments` renders `#agent-context-panel` with versioned defaults, materialization summaries (state, availability, empty-READY truthfulness), and immutable binding summaries. Uses SimplyPages component builders for compact tables, semantic chips, and bounded summaries. HTMX-first reads and expected-version default-update forms with OOB refresh of own host only; materialization summaries display their captured generation. | `AgentContextFragments`, `AgentContextController` | No cross-agent browsing; scoped to one agent descriptor. Context mutations forward facts, not authority. | Controller/fragment tests for malformed ids, generation conflicts, empty-READY vs unavailable states, host isolation. |
| System chat panel | active | `SystemChatFragments` renders `#system-chat-panel` with `#system-chat-history` and `#system-chat-proposals`. Server-created fixed-profile conversation per agent. Proposals render semantic state chips (`PENDING`, `CONFIRMING`, `CONFIRMED`, `DENIED`, `CANCELLED`, `STALE`, `UNAVAILABLE`) with explicit confirmation controls. SSE stream rendered through `system-chat.js` using safe `textContent` and bounded event parsing. | `SystemChatFragments`, `SystemChatController`, `system-chat.js` | No `innerHTML`, generic fetch helpers, HTMX error policy, CRUD, or tool selection in the JS module. Proposal confirmation OOB-refreshes only same-domain hosts. | Controller/fragment tests for INTERNAL denial, fixed-profile routing, proposal confirmation idempotency, tool denial, secret-free output. Playwright functional/visual validation. |
| `htmx-errors.js` shared policy | active | A no-transport `htmx:beforeSwap` listener for explicitly marked same-origin operational fragments. Permits original non-2xx status and declared target to render; rejects unmarked or cross-origin responses. Never infers a target, issues a request, rewrites body content, or broadens an OOB swap. | `htmx-errors.js` | Shared policy, not a broad status-rewriter or transport adapter. | Controller/fragment marked-error tests verifying 400/404/409/503 fragments swap visibly without status rewriting. |

## Cross-Domain Fragment Patterns

- **Independent stable hosts:** artifact search/detail/preview/lineage/retention, skill trust/snapshots, runtime health/history/detail/audit, schedule firing/attempt audit, reaction dispatch audit, agent context, agent queue, and system-chat panels each own a stable host. A response replaces only its domain host; mutation OOB swaps never refresh the entire Files surface.
- **Stateless renderers over authorized values:** reusable fragment classes render service-provided records and own no repositories, filesystem roots, claims, or scope derivation. Controllers validate scope and bounds before rendering.
- **Truthful state components:** semantic chips and compact state panels distinguish unavailable, legacy, partial, integrity-failed, pending, revoked, suppressed, simulated, enqueued, and terminal states. Missing facts render as unavailable, never numeric zero or implied success.
- **Bounded master/detail expansion:** list rows load on-demand detail into a sibling host; pagination and refresh preserve workspace/agent scope and bounded limits. Stale responses cannot overwrite unrelated panels.
- **HTMX-first control plane:** filters, refresh, CRUD, trust decisions, simulation previews, pagination, and audit expansion use `hx-get`/`hx-post`/`hx-put`/`hx-delete`, explicit targets, and OOB swaps where multiple same-domain hosts change. Phase 10 adds no JavaScript transport.
- **Raw markup exception:** most operational renderers use SimplyPages components. `ScheduleReactionFragments` uses narrowly contained escaped table markup for dense audit rows; inputs are escaped and transport remains HTMX. This is a local renderer choice, not a general raw-HTML pattern.

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
