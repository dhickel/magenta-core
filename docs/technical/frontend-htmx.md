# Frontend HTMX

Magenta's frontend is server-rendered and HTMX-first. SimplyPages components/modules should be the default way to build reusable UI. Raw HTML strings are a fallback for advanced cases only after checking the SimplyPages docs and demos.

Source anchors:

- Web controllers/fragments: [`api/web`](../../src/main/java/io/mindspice/magenta2/api/web)
- Main CSS: [`magenta.css`](../../src/main/resources/static/css/magenta.css)
- Orchestration CSS: [`orchestration.css`](../../src/main/resources/static/css/orchestration.css)
- Chat client JS: [`chat-client.js`](../../src/main/resources/static/js/chat-client.js)
- Shared shell JS: [`static/js`](../../src/main/resources/static/js)
- Orchestration JS islands: [`static/js/orchestration`](../../src/main/resources/static/js/orchestration)

## Page and Fragment Controllers

`FrontendController` owns the basic shell routes such as `/` and `/chat`.

`FrontendFragmentController` owns small chat fragments:

- `/api/fragments/chat/transcript`
- `/api/fragments/chat/sessions`
- `/api/fragments/chat/planning`

`OrchestrationController` owns the operational UI routes and HTMX fragments for dashboard, plans, workflows, jobs, projects, inbox, outputs, agents, and settings.

JSON API controllers provide the route families consumed by both HTMX fragments and JavaScript islands.

## HTMX Default Policy

Use HTMX for:

- CRUD form submissions.
- List/detail refreshes.
- Tab and panel swaps.
- Filtering and row actions.
- Modal body refreshes.
- Validation fragments.
- Submit-to-agent forms.
- Project membership add/remove controls.
- Assignment workspace-blocked requeue actions.
- Delete/confirm actions.

HTMX routes should return server-rendered HTML fragments and preserve consistent status/markup behavior for failed requests so operational panels show actionable errors instead of silent failures.

For modal hosts, keep one stable target element in the page and return modal content for `innerHTML` swaps, or return the full target with `outerHTML`. Do not return a second element with the same id inside an `innerHTML` target. The workspace explorer uses `#workspace-file-modal` as its stable host and returns modal content without duplicating that id.

## JavaScript Islands

Existing JavaScript is justified where persistent browser state, SSE, or client-side graph/editor interaction is simpler than pure HTMX.

- `chat-client.js`: chat SSE, active stream state, incremental token rendering, server-backed mid-turn queue cards/drain, session-card rendering, selected-session output file refreshes, and chat-specific browser behavior.
- shared shell helper(s): HTMX error-swap and small shell-level behavior that is simpler than reproducing in every page/module.
- `orchestration/api.js` and `dom.js`: small shared helpers for operational islands.
- `orchestration/dashboard.js`: dashboard refresh/poll style behavior.
- `orchestration/agent-chat.js`: agent side-panel SSE chat behavior.
- `orchestration/agents.js`: agent detail/editor behaviors that need local UI state.
- `orchestration/plans.js`: richer plan/workflow editor interactions where client-side state simplifies repeated node/field operations.
- `orchestration/projects.js`: project detail interactions that coordinate fragment updates.

Do not turn ordinary CRUD into a JavaScript transport surface. If an interaction can be a form plus HTMX target/swap cleanly, keep it HTMX.

The chat outputs panel remains in `chat-client.js` because the `/chat` session list, active conversation state, and SSE completion refreshes are already JS-owned. Standard CRUD and operational UI interactions elsewhere remain HTMX-first.

Anonymous `/chat` planning questions are also handled inside `chat-client.js` because the chat composer is already SSE/client-state owned. Saved plan chat keeps HTMX submission in `#plan-chat-form`; both surfaces share the same prompt-card classes for visual consistency.

Normal browser `/chat` messages submitted while a stream is active are queued through `/api/chat/{conversationId}/pending-messages` and rendered in `#chat-queued-messages-panel` between the planning panel and the form. This remains a JavaScript island because the behavior depends on active SSE state, claim/ack/release retry safety, active-conversation URL state, and FIFO drain after the current stream completes. When a page reload sees pending rows but no local stream callback, the client retries the claim/send cycle and releases the claim if the server reports the existing conversation stream is still active. Pending-card reloads are guarded by active conversation and request generation so older `CLAIMED` responses cannot repaint stale `Sending...` cards after the final ack clears the queue. Planning answers and saved plan chat stay on their existing routes, and slash commands submitted mid-turn are rejected with wait feedback instead of queueing.

## SimplyPages Reuse

When adding or changing UI:

- Prefer reusable components/modules over page-local markup.
- Use shared render structures and slot keys when the same structure renders request-specific data.
- Keep controllers thin; rendering helpers/components should assemble view state, while services own domain behavior.
- Follow existing orchestration page patterns before introducing new markup style.
- For dense operational explorers, use details/list tables, stable row actions, a separate inspector, and HTMX fragments before reaching for decorative card grids.

If SimplyPages lacks a reasonable primitive, inspect `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs` and `/home/hickelpickle/Code/Java/cannasite/java-html-framework/demo`. If the library truly has a bug or missing primitive, document or fix the library rather than adding brittle page-level workarounds.

## Static Assets

CSS is split by surface:

- `magenta.css` for shared shell/chat styling.
- `orchestration.css` for operational UI styling.

Static JavaScript is loaded as browser assets from `src/main/resources/static/js`. Keep modules narrowly scoped and avoid hidden global behavior except where an existing island already establishes a small shared helper.

## Entity Selectors

Concurrent selector work lives under [`api/web/selector`](../../src/main/java/io/mindspice/magenta2/api/web/selector). Selector routes are read-only GET endpoints under `/selectors/{kind}` and should be treated as reusable UI infrastructure for choosing plans, tasks, workflows, jobs, agents, projects, workspaces, and other operational entities.

When selector behavior is reused across pages, prefer the shared selector components/configuration rather than duplicating lookup UI in each page.

## Validation Expectations

For UI changes, validate against a running app with focused Playwright checks on the changed target. For documentation-only work, safe validation is limited to link/file inventory, source-reference checks, and whitespace checks.

For project/job/output/assignment UX changes, Playwright validation should inspect the changed `/projects`, `/jobs`, `/outputs`, plan submit, workflow submit, and agent submit/history surfaces with screenshots. Use isolated runtime data when possible, capture console/network diagnostics, and treat blocked browser validation as an explicit blocker unless the user approves a fallback.

During UI review, explicitly check that JavaScript use is justified, standard CRUD remains HTMX-first, labels distinguish `workspaceType`/`workspaceKey` from `projectId`, and provenance fields fit without hiding primary actions.
