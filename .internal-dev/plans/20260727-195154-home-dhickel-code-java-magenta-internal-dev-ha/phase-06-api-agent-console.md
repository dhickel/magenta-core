# Phase 06 — API and Agent Console

## Context

Phase 04 provides prompt/skill use of the captured context established by Phases 02–03, and Phase 05 provides the fixed system-chat profile. This phase exposes those capabilities without moving authorization, filesystem identity, or prompt authority into the web layer.

## Goal
expose thin versioned APIs and independent HTMX agent-console hosts

## In Scope
**Write Targets**: src/main/java/io/mindspice/magenta2/api/web/AgentContextController.java, src/main/java/io/mindspice/magenta2/api/web/SystemChatController.java, src/main/java/io/mindspice/magenta2/api/web/AgentContextFragments.java, src/main/java/io/mindspice/magenta2/api/web/SystemChatFragments.java, src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java, src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java, src/main/resources/static/css/orchestration.css, src/main/resources/static/js/orchestration/htmx-errors.js, src/main/resources/static/js/orchestration/system-chat.js

Add agent-scoped REST and HTMX adapters for captured context and restricted system chat; compose their independent hosts into the agent console; refine the Files surface to honor conceptual-area ownership; and add focused companion web tests under the orchestration companion-edit policy.

## Out of Scope

Cross-agent administration or browsing, generic/project workspace routes, a project root or descriptor, application-specific controls for user-managed storage, a monolithic dashboard replacement, JavaScript CRUD/context/queue/skill/file transport, shell/process execution, and changes to domain services or dependency phases.

## Dependencies
phase-04-prompt-skills-context.md, phase-05-restricted-system-chat.md

## Constraints

- Start only with PASS evidence for all dependencies and a reviewed list of their typed service outcomes. If a required service fact is absent, unavailable, or conflicts with this phase, escalate; do not derive it from a path, display string, or UI state.
- The orchestration companion-edit policy authorizes directly corresponding web tests, and this phase explicitly owns `htmx-errors.js` because the application has no shared marked-error handler. The asset may opt marked same-origin non-2xx fragments into their declared HTMX target only; it performs no transport, broad status rewriting, or unrelated-host swap. Do not return 200 for conflicts, and do not broaden `system-chat.js` beyond SSE.
- Controllers parse and validate request shape, resolve the agent boundary, delegate one service call, and map typed outcomes. Fragments render only authorized secret-free records. Neither may resolve filesystem roots, construct descriptors, infer bindings, or implement authorization.
- Existing `/api` routing remains the route prefix. “Versioned” means explicit expected default version and materialization generation, not a new ungoverned URL version.
- Every mutation carries the exact service-owned identity facts it needs: agent id; relevant context or materialization id; expected default version or expected materialization generation; captured binding id where the operation is binding-scoped; and operation key. The server computes the canonical request digest. Reusing a key with a different digest conflicts without mutation.
- Do not accept a workspace root, canonical identity, relative path for context operations, materialization location, credential, token, claim owner, fence, or raw error. The web layer forwards facts, never authority.
- Map malformed identifiers or invalid fact combinations to `400`; missing or out-of-scope agent/resource to indistinguishable `404`; stale generation/version, contradictory key reuse, busy/not-ready state to `409`; and required service or dependency unavailability to `503`. All rendered and JSON error bodies are bounded, semantic, and secret-free.
- System chat is exclusively agent-scoped and server-created. A caller cannot select `INTERNAL`, submit a system conversation id, register a tool, or convert a proposal into a mutation. Confirmation requires the exact persisted proposal, target, state, confirmation, operation key, and ordinary scoped authorization.
- Render only semantic identifiers, state, bounded summaries, display labels, and sanctioned timestamps. Never render absolute or root-relative paths, credential-bearing URLs, internal endpoints, raw errors, secrets or references, claim owners, fences, tokens, or untrusted candidate bytes.
- Standard reads, forms, row actions, refreshes, and fragment replacement use HTMX. A marked non-2xx fragment is displayed only through the authorized shared error-swap behavior. `system-chat.js` is limited to system-chat SSE lifecycle, bounded parsing, cancellation, and safe text-node rendering.

## Implementation Steps

1. Verify Phase 04–05 PASS evidence, inspect their service result records and existing route collisions, and confirm the companion test scope plus the explicit `htmx-errors.js` boundary. Confirm the applicable SimplyPages docs and demo location from the project guide; stop for clarification if it cannot be found. Define local request/response records in the new controllers rather than exposing domain records.
2. Add `AgentContextController` routes beneath `/api/agents/{agentId}/context` for context/materialization summaries, default reads and expected-version default updates, authorized project-materialization provision or reconciliation, and immutable binding summaries. Context mutations require their materialization id, expected generation, and operation key; binding summaries are read-only.
3. Add `SystemChatController` routes beneath `/api/agents/{agentId}/system-chat` for server-resolved conversation/history, SSE stream, proposal listing/creation, and explicit proposal confirmation. The stream and every proposal call `SystemChatService` or its proposal boundary directly; they never enter public chat surface selection or normal agent-chat tooling.
4. Keep REST and fragment adapters parallel only at the transport boundary: both map local records to the same domain service, and neither calls the other controller. Return a semantic state/reason/message envelope for unavailable, conflict, and non-ready results; do not serialize domain exceptions or persistence records.
5. Add `AgentContextFragments` and `SystemChatFragments` with stable root hosts: `#agent-context-panel`, `#system-chat-panel`, `#system-chat-history`, and `#system-chat-proposals`. A response replaces its own host only. A proposal confirmation may OOB-refresh `#system-chat-proposals` and its same-domain status host, never the Files, queue, run, skill, or context host.
6. Update `WorkspaceExplorerFragments` and `OrchestrationController` to compose independent agent-console hosts for context, assignment queue, runs, skills, Files list/inspector/modal, and system chat. Preserve `#agent-files-explorer`, `#workspace-file-list`, `#workspace-file-inspector`, `#workspace-file-modal`, `#agent-run-groups`, and `#agent-run-detail`; add `#agent-queue-panel` and `#agent-skills-panel` as separate hosts. An initial tab load may establish the host shell, but later requests must not replace `#agent-tab-panel` as a broad stale-response target.
7. Replace the misleading dashboard System Chat referral to ordinary `/chat`; mount the restricted surface only through the agent-scoped system-chat host. Keep ordinary agent chat separate and unable to acquire system-chat authority.
8. Add conceptual Files-area navigation for Skills, Scripts, Apps, Home, Stored Items, Projects, Tasks, and Outputs without materializing a directory. Skills opens the skill-owned view; Projects opens captured context/materialization facts and truthfully distinguishes an empty `READY` local context container from unavailable content; Tasks and Outputs use lifecycle/publication read models; Scripts, Apps, and Home use only descriptor-confined explorer behavior permitted by Phase 01. Stored Items represents the reserved `repos` area for user-managed storage outside Magenta: existing descendants may be listed/read only through the already-permitted descriptor-confined read path, while controls that create, change, delete, interpret, or provision content are absent. An absent conceptual area renders a controlled unprovisioned/empty state and performs no write.
9. Enrich existing assignment-queue rows only with authorized captured context and project attribution. Do not create a second queue, query runtime persistence from `WorkspaceExplorerService`, infer missing bindings, or turn display state into authorization.
10. Implement `system-chat.js` as an incremental SSE reader scoped to `#system-chat-panel`: accept only the documented event set, bound buffered input and event payload sizes, verify the returned conversation belongs to the mounted agent, render all model/user text with `textContent`, and cancel/reset cleanly on terminal, error, abort, or host replacement. No `innerHTML`, generic fetch helper, HTMX error policy, CRUD, or tool selection belongs in this module.
11. Implement `htmx-errors.js` as a no-transport `htmx:beforeSwap` policy for explicitly marked same-origin operational fragments. Permit the original non-2xx status and declared target to render, reject unmarked or cross-origin responses, and never infer a target, issue a request, rewrite body content, or broaden an OOB swap.
12. Apply compact operational-console styling in `orchestration.css`: thin blue-gray borders, small radii, semantic state chips, bounded tables and summaries, clear unavailable and destructive states, useful first viewport density, and mobile stacking. Use SimplyPages component builders and slot-safe render context for new reusable structures; do not add raw string markup where a framework component expresses the structure.
13. Add focused controller and fragment tests under the orchestration companion-edit policy, then run the delegated live browser pass against a running isolated application. Capture desktop and mobile screenshots for the changed agent console and record functional plus visual findings.

## Required Guides

- `AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md`
- `.internal-dev/knowledge/agent-skills-ui-htmx-pattern.md`
- `.internal-dev/knowledge/agent-lifecycle-htmx-targets.md`
- `.internal-dev/knowledge/htmx-fragment-error-statuses.md`
- `.internal-dev/knowledge/event-delegation-sse-dom-replacement.md`
- `.internal-dev/knowledge/live-chat-mcp-workflow-testing.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- The applicable SimplyPages HTMX swap, layout, RenderContext/SlotKey, safe-rendering documentation and corresponding demo, located through the project guide before editing

## Technical Guidance

Use one primary host per endpoint. Context targets `#agent-context-panel`; assignment queue targets `#agent-queue-panel`; runs target their existing run hosts; skills target `#agent-skills-panel`; Files mutations target only Files list/inspector/modal hosts; and system proposals target `#system-chat-proposals`.

For a context mutation, the web boundary forwards facts, not authority:

```text
(agentId, materializationId, expectedGeneration, bindingId, operationKey)
    -> context service -> typed outcome -> redacted DTO or fragment
```

For SSE, keep framing incremental and rendering inert: parse complete bounded events, reject unexpected event names or agent/conversation mismatches, and append text nodes only. Server-rendered system-chat history and proposal controls remain HTMX fragments.

## Validation

- Controller and fragment tests cover malformed/encoded identifiers, wrong agent-resource pairing, missing/out-of-scope equivalence, omitted or contradictory required facts, expected-version and expected-generation conflicts, stale binding, busy/not-ready/unavailable outcomes, and same-key/different-digest conflict without service mutation.
- Context tests prove default changes affect only future captures; bindings are immutable summaries; project-materialization provision/reconciliation cannot select a root or path.
- System-chat tests prove no public `INTERNAL` selection, no caller-selected system conversation, fixed-profile routing, exact proposal target/state/confirmation/idempotency behavior, denial of file/shell/skill/cross-agent authority, bounded history/stream payloads, and redaction.
- Fragment tests prove stable unique host IDs; same-domain OOB refresh only; no delayed response replaces an unrelated host; the shared marked-error mechanism visibly swaps `400`, `409`, `404`, and `503` fragments without changing their HTTP status.
- Files tests cover every conceptual area in present, absent, and denied states; absent selection performs no filesystem mutation; dedicated skill, materialization, lifecycle, and publication ownership remains intact; an empty `READY` project context is not presented as imported content; and Stored Items remains non-materializing, descriptor-confined read-only storage without application-specific controls.
- Delegated Playwright validation runs against the live application with isolated state. Exercise context conflict, system-chat stream/proposal/confirmation denial paths, queue/run/skill composition, Files area navigation, standard HTMX interaction ownership, and SSE-only JavaScript behavior. Capture desktop and mobile screenshots and critique alignment, hierarchy, density, overflow, affordance, first-viewport usefulness, and mobile stacking.
- Run focused tests, the applicable Maven suite, bounded application startup, forbidden-construct/dependency scans, and final diff/status review. Treat unavailable external capability as valid only when the UI remains explicitly unavailable rather than simulating success.

## Exit Criteria

- The explicit production targets and companion-edit policy cover the required implementation and focused tests, all dependencies have PASS, and the phase validator records PASS.
- Agent-scoped REST and fragment adapters are thin, typed, concurrency-aware, and secret-free; no request can supply root, path, or other internal authority.
- `/agents/{agentId}` composes stable independent context, queue, run, skill, Files, and restricted system-chat hosts without cross-host stale replacement.
- Conceptual Files navigation is non-materializing and preserves dedicated skill, materialization, lifecycle, and publication ownership; Stored Items remains user-managed storage outside Magenta and descriptor-confined read-only from Magenta's perspective, without application-specific behavior.
- System chat is reachable only through its dedicated server-created fixed profile; its stream is safely rendered, and proposals remain non-executing until ordinary scoped confirmation.
- Focused automated coverage, delegated live functional and visual validation, startup, redaction checks, and final review pass without placeholders, generic workspace restoration, project-root browsing, unapproved JavaScript transport, or acknowledged defects.
