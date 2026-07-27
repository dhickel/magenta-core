---
schema_version: 1
document_type: web-specification
status: active
owner: web
created: 2026-05-25
---

# Web Specification

## Intended Contract

Magenta web surfaces are operational tools, not marketing pages. They should be dense, readable, HTMX-first where feasible, and validated with focused browser checks when UI behavior changes. The Avatar dashboard and `/avatar` routes are absent. The agent operational console at `/agents/{agentId}` gains a standalone HTMX Files tab bound only to that agent's workspace.

## Agent Files Tab

| page_or_fragment | status | intended_contract | observed_anchors | interaction_rule | validation |
| --- | --- | --- | --- | --- | --- |
| `/agents/{agentId}` Files tab | active | A standalone HTMX tab in the agent operational console that resolves exactly one `WorkspaceDescriptor` through `WorkspaceDirectoryService` for `agent/<agentId>` and therefore binds only to `workspace/agent/<agentId>`. Uses workspace-neutral IDs, classes, routes, and labels. Provides list, inspector, modal, and OOB targets for file operations. | Agent detail tab shell, explorer fragments (repurposed from `WorkAreaExplorerFragments`), operational console visual system | HTMX-first fragments for list, preview, create, edit, rename, move, copy, delete, labels, tags, and action history. Narrow JavaScript only for streaming, local resizing, or path-of-least-resistance behavior. | Focused Playwright screenshots and visual critique: tab loading, CRUD flows, navigation, preview, image view, labels/tags, controlled errors, inspector/modal/OOB updates, empty state, responsive stacking, spacing, density, wrapping, overflow, hierarchy, and operational-console consistency on desktop and mobile. |

### Files Tab Composition

- **Neutral shell:** The tab reuses the agent operational console's tab system. No Avatar branding, no Work Area labels. The tab label is "Files."
- **Explorer list:** A details/list fragment with stable columns (name, size, modified) and a separate inspector panel. Rendered via HTMX fragment swaps.
- **Inspector/modal:** File preview, image view, edit, labels, tags, and delete confirmation use OOB-swapped inspector and modal targets. No page-level navigation for file operations.
- **OOB targets:** List, inspector, and modal refreshes use out-of-band HTMX swaps after mutations.
- **Empty state:** When the agent workspace root has no physical directory, the list shows an empty workspace with a clear empty-state message. No starter files are generated.
- **Error handling:** Controlled `400`, `404`, and `409` responses mapped to user-visible messages in the inspector or modal.
- **Visual language:** Dense panels, compact controls, thin blue-gray borders, small radii, semantic chips, compact headings, and useful first-viewport density. Consistent with the existing `/dashboard` and `/agents` operational console look.

### Files Tab Layout Validation

- Desktop: side-by-side list and inspector with adequate gutters; no stranded columns or excessive dead zones.
- Mobile: list stacks above inspector; controls remain tappable; text does not clip; modals are full-width.
- Spacing, density, wrapping, overflow, hierarchy, and affordances pass visual critique.
- Standard CRUD, filtering, row actions, form submissions, and partial refreshes use HTMX. JavaScript retained only with narrow justification as the simpler interaction mechanism.

### Reusable Controls

User-workspace creation and execution workspace selection controls are reusable SimplyPages fragments mounted only where a submission surface exposes workspace choice. No generic user/chat workspace browser is mounted as a page-level surface.

## Files And Run Operations Composition

The Files tab composes two independent columns inside `#agent-files-explorer`: the confined filesystem browser and `#agent-run-groups`. The run host loads after the filesystem shell and expands a selected row into `#agent-run-detail`. Groups are bounded and deterministic by persisted source kind; every run retains its own canonical id, so duplicate display names or renamed/deleted definitions cannot merge records.

Run rows expose normalized status, summary update time, wall/active elapsed, attempt count, and model/tool measurement counts. Detail exposes source kind, status/outcome, source-supported start/completion timestamps, attempts, safe current-claim availability, retry/replay lineage links, artifact state counts, and at most five recent artifact facts with authorized preview requests for published artifacts. Empty, unavailable, active/partial, legacy-ambiguous, missing, integrity-failed, and error states remain visibly distinct. Raw failure text, absolute paths, claim owner/secret, writer fences, unauthorized counts, and direct artifact byte links are not rendered. Visible `tasks/` and `outputs/` explorer trees remain read-only.

Desktop uses a dense files/run two-column console; narrower layouts stack Files above Runs. Thin blue-gray borders, five-pixel radii, compact tables/controls, semantic chips, bounded overflow, and useful first-viewport density align this surface with `/dashboard` and `/agents`. Standard loading, refresh, expansion, CRUD, filtering, and fragment replacement are HTMX-only; this composition adds no JavaScript.

Focused artifact, skill trust/snapshot, runtime health/history, schedule, and reaction fragments retain their own stable hosts and domain controllers. The Files composition does not reimplement or query those domains through explorer services.

## Focused Operational Surfaces

| surface | hosts and composition | truthful-state contract |
| --- | --- | --- |
| Artifacts | `/artifacts` composes `#artifact-search-results`, `#artifact-detail`, `#artifact-preview`, `#artifact-lineage`, and `#artifact-retention`. Search, detail, preview, lineage, and retention load independently through HTMX. | FTS5 unavailable, unsupported preview, missing bytes, integrity failure, archived/expired state, blockers, and pins remain distinct. No byte action is shown as successful before integrity and scope checks pass. |
| Skills | `/skills` retains list/detail/file/assignment hosts and adds `#skills-trust-panel` plus snapshot history. Trust mutations target only the trust host. | Pending, malformed, stale, unavailable, conflict, rejected, revoked, and approved-snapshot states remain distinct. Approval is never labelled auto-trust or executable authorization; package/executable capability remains visibly unavailable. |
| Runtime | `#runtime-health`, `#runtime-history`, `#runtime-detail`, `#runtime-audit-region`, and `#invocation-detail` provide bounded health, history, repeated call details, and exact-link audit expansion. | Empty denominators, active runs, unavailable/legacy facts, incomplete invocations, and missing elapsed/usage/exit facts remain explicit rather than zero or success. |
| Schedules | Schedule list, simulation preview, `#schedule-firings-panel`, and firing-attempt detail use HTMX requests to typed services/routes. | Simulation is labelled **Non-Executing**; enabled/disabled/deleted, claimed/enqueued/recovered/conflict/exhausted, and linked runtime outcome are separate facts. |
| Reactions | Reaction list, simulation preview, and `#reaction-dispatches-panel` use bounded HTMX audit expansion. | Simulation is non-executing; matched, deduplicated, loop/depth/rate-suppressed, enqueued, and terminal linked-run states are not conflated. |

All operational surfaces use compact tables/panels, thin blue-gray borders, small radii, semantic chips, bounded overflow, and mobile stacking. Standard CRUD, filtering, refresh, pagination, and expansion are HTMX-first. Phase 10 added no transport JavaScript.

## Retained Web Entries

| id | page_or_fragment | status | intended_contract | observed_anchors | interaction_rule | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WEB-20260525-05 | Top navigation | active | Primary shell nav links should be consistent across home, chat, dashboard, and agent shells; full-page shell nav uses full-page anchors unless HTMX swapping is explicitly intended. Avatar link removed. | `AppNavigation` and shell builders | Avoid accidental HTMX route swaps for full document navigation. | Controller tests. | none | `shell-navigation-htmx-vs-full-page.md` |
| WEB-20260525-06 | Agent history tab content | deferred | Replace static history placeholders with real agent run history when the query contract is accepted. | Agent detail history tab | Pagination/date filtering can be separate scope. | Controller/service/browser checks. | none | `agent-detail-workspace-health-pattern.md` |
| WEB-20260525-07 | Browser chat mid-turn queue | active | The `/chat` composer shows server-backed queued cards between the planning panel and input when normal messages are submitted during an active stream. The client must keep `?conversationId=` aligned with the active chat so plain reload restores queued cards, then retry draining until the existing active stream lock clears. Pending-card renders must be scoped to the active conversation and latest pending-list request so stale `CLAIMED` responses cannot repaint a drained queue. | `#chat-queued-messages-panel`, `chat-client.js`, `.planning-question-card` | Planning answers continue through plan answer routes; slash commands submitted mid-turn show wait feedback instead of queueing. Background queued drains may continue and ack after session switch, but must not steal the visible chat session or append messages into another active conversation. | Focused Playwright checks for slow-turn queueing, session-switch/away-and-return stale-card cleanup, reload persistence, FIFO drain, command/planning negatives, desktop/mobile screenshots. | none | `event-delegation-sse-dom-replacement.md`, `chat-planning-composer-architecture.md` |
| WEB-20260525-08 | Browser chat model selectors | active | The `/chat` Agent Model and Planning Model selectors display configured model aliases as the visible option text and submit alias keys as values. Provider `remoteModelName` values remain routing details, not primary chat UI labels. | `FrontendController.chatToolbar()`, `ChatService.availableModelOptions()` | If a selected/default value is only known by remote model name, map it back to the configured alias before selecting the option. | Controller rendering tests and focused browser check when selector behavior changes. | none | `spring-ai-model-options-routing.md` |
| WEB-20260727-01 | Browser chat Outputs panel mid-stream refresh | active | The browser chat Outputs panel (`#chat-session-files-body`) refreshes via `loadActiveFiles()` when an `outputs_changed` SSE event arrives during a stream, in addition to the existing terminal refresh on `done` and error recovery. The event carries only `conversationId`; the client matches it against both the stream conversation and `activeConversationId()` before calling the listing endpoint. | `chat-client.js` SSE event handlers in both `sendMessage` and `executeApprovedPlan` stream callbacks, `loadActiveFiles()`, `fileLoadConversationId` guard. | No loading state or panel clearing; current content remains until the new listing renders. Rapid events serialize through the SSE parser's await-on-callback behavior. Stale or background-conversation events never overwrite the active panel. | Focused Playwright validation: mid-stream file appearance, append/replace refresh without duplicates, conversation switch safety, stream-completion and error-recovery terminal refresh. | none | `chat-file-panel-auto-refresh.md` |
| WEB-20260526-02 | Agent Skills browser/editor and guided creation MVP | active | Provide operational `/skills` management surfaces for root-repository skills and per-agent assignment with HTMX-first fragments. UI must clearly separate implemented MVP behavior from deferred scope and must not imply UI script execution support. | `/skills` operational shell and fragments implemented for list/filter/detail, diagnostics, directory overview, file viewer/editor, add-file flow, optional top-level directory creation, guided scaffold creation, refresh, and agent assignment controls. | Standard CRUD/filter/row actions use HTMX; catalog-affecting mutations return OOB `#skills-list` refreshes; no custom skills JavaScript is required. MVP only manages root `skills/` repository plus agent assignment. | Controller/fragment tests cover shell/nav, list/filter, diagnostics, editor/save, file create, guided creation, assignment fragments, and OOB list refresh after catalog-affecting mutations. Phase 06 reconciled Playwright proof after first-run selector/path-script false negatives and recorded corrected evidence. | `DECISION-20260526-03`, `DECISION-20260526-06` | `agent-skills-specification-reference.md`, `agent-skills-ui-htmx-pattern.md` |

## Removed Web Entries

| id | page_or_fragment | status | notes |
| --- | --- | --- | --- |
| WEB-20260525-01 | Avatar dashboard | removed | Avatar package, `/avatar` routes, `AvatarDashboardController`, and `AvatarDashboardComponents` are deleted in Phase 03. |
| WEB-20260525-02 | Avatar visual contract | removed | Visual contract survives in the agent operational console; Avatar-specific dashboard ownership is removed in Phase 03. |
| WEB-20260525-03 | Avatar shell | removed | Agent-style tabbed shell survives for the operational console; Avatar shell and dedicated chat rail are removed in Phase 03. |
| WEB-20260525-04 | Work Area explorer | removed | Explorer UI fragments repurposed for neutral workspace explorer in the agent Files tab (Phase 07); Work Area ownership removed in Phase 04. |
| WEB-20260526-01 | Workspace and project browser MVP | removed | Superseded by agent Files tab and typed workspace API; Work Area and project-root ownership removed. |

## Ownership Boundary

This file owns page/fragment UX contracts. SimplyPages component mechanics belong in `simplypages.md`; routes/payloads belong in `api.md`.

## Drift/Gaps

| id | status | observed_drift | routing | review_after |
| --- | --- | --- | --- | --- |
| DRIFT-20260525-06 | watching | Some history and observability panels are baseline or placeholder surfaces. | Deferred features before expanding UI claims. | 2026-06-24 |

## Validation Expectations

UI changes require focused Playwright validation under the repo policy, including screenshots and a visual quality critique. Browser checks should cover alignment, spacing, density, overflow, mobile stacking, and whether the page uses available space coherently.

## Related Decisions

See `DECISION-20260719-02`, `DECISION-20260522-07`, `DECISION-20260524-03`.

## Related Knowledge

Search knowledge filenames for `dashboard`, `shell`, `workspace-file`, `htmx`, and `simplypages`.
