# Phase 06 API and Agent Console Validator Review

## Scope

Validated `.internal-dev/plans/20260727-195154-home-dhickel-code-java-magenta-internal-dev-ha/phase-06-api-agent-console.md` against its production targets, contracts, tests, isolated startup, and live desktop/mobile browser pass.

## Findings

1. **Blocking — agent scope is not carried into system chat.** `SystemChatController` invokes `SystemChatService.resolveProfile()` and `invoke(message)`, whose implementation selects the configured default agent rather than the `{agentId}` route value. A request for any existing non-default agent can therefore receive and act through the default agent's system-chat conversation. The necessary service-level agent-scoped entry point is absent; this cannot be corrected solely at the phase's web boundary.
2. **Blocking — controllers violate the thin typed-boundary contract.** `AgentContextController` injects `AgentMaterializationRepository` and `WorkspaceDirectoryService`, resolves a descriptor, constructs materialization identity, performs multiple calls per request, ignores supplied `operationKey`, and maps exceptions/messages instead of typed service results. It also renders raw exception messages in fragment responses. This breaks the required service ownership, identity/idempotency, and redaction contracts.
3. **Blocking — proposal confirmation is not exact/idempotent at the request boundary.** `SystemChatController` derives `targetId` by listing proposals and creates a random confirmation key. It does not require the exact persisted proposal target/state, explicit confirmation facts, or a caller operation key. Retries cannot converge and an operation key cannot be checked for contradictory reuse.
4. **Blocking — Files conceptual areas do not preserve boundaries.** `filesAreaNav` declares the intended read/write matrix but never uses it. The generic agent shell exposes create/rename/move/copy/delete controls for `repos` (Stored Items), despite the required descriptor-confined read-only, non-materializing behavior. Projects, Tasks, and Outputs are referral/placeholder panels rather than their owning read models; Scripts and Apps are not restricted by the declared area matrix.
5. **Blocking — SimplyPages policy is not met.** Both new fragment renderers and the conceptual Files navigation build raw HTML strings rather than framework components, despite available `Div`, `Form`, `Button`, `Table`, `Row`, and `Column` builders. The current framework documentation was found at `/home/dhickel/Code/Java/SimplyPages`, not the stale path in the project guide.
6. **Blocking — required focused coverage is absent.** There are no controller/fragment tests named for Agent Context or System Chat. The 900-test Maven suite passes but does not demonstrate malformed identifiers, scope pairing, idempotency, typed conflict/unavailable mapping, error swapping, system-chat denial paths, or all eight Files areas.
7. **Visual quality fails the phase bar.** The isolated Playwright pass showed browser-default controls in the Context, Files, and unavailable System Chat panels rather than the specified compact operational console controls. On desktop the Files table clips its final columns in the browser/inspector split. Mobile stacks without overlap, but the file-table columns are clipped and the long tab strip dominates the first viewport. Screenshots are in `/tmp/magenta-phase06-playwright/` for this validation run.

Validator repairs made within the declared web-target scope:

- Context and System Chat initial tab shells now establish `#agent-context-panel` / `#system-chat-panel`, then load only their own host with `outerHTML`; they no longer use `#agent-tab-panel` as a later stale-response target.
- `SystemChatFragments` now mounts `#system-chat-proposals` for available and unavailable states.
- `system-chat.js` now uses bounded complete-event SSE parsing, validates start agent/conversation identity, renders text via `textContent`, and resets on terminal/error/host replacement.
- `htmx-errors.js` is now a no-transport, marked-request, same-origin non-2xx policy and no longer parses the response through `innerHTML`.

## Risk Assessment

High. The system-chat route can cross the intended agent boundary, proposal confirmation is non-idempotent, and Stored Items can expose mutation controls. These are authorization and lifecycle correctness failures, not presentation-only defects.

## Recommendations

Return the implementation to the Phase 06 implementer for a service-backed redesign. Add agent-scoped typed service entry points before rebuilding the controllers; do not paper over the missing service facts in the web layer. Replace raw fragment strings with SimplyPages builders, implement the eight-area ownership/read-only matrix, add focused tests, then rerun the browser pass after CSS correction.

## Follow-ups

- `mvn -q test`: PASS, 900 tests, 0 failures/errors (the suite still emits unrelated expected MockMvc warning stacks).
- Isolated startup on port 18084 with `/tmp/magenta-phase06-final-startup.sqlite`: PASS.
- Playwright ran directly because this harness exposes no subagent/MCP controls despite the repository policy requiring delegated browser validation. It used the installed Chromium binary on isolated port 18083 and captured desktop/mobile screenshots.
- Verdict: **BLOCKED**.
