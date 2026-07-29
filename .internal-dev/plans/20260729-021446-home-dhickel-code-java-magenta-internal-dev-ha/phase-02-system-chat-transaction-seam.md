## Context

`SystemChatService.invoke` currently holds a transaction across `ChatClient.call()` and self-invokes profile resolution, so annotation changes within the class cannot form a reliable Spring boundary. Preparation must commit the fixed profile, binding, origin, and owner-link facts before remote inference. A rejected pre-existing origin or owner link must not leave a newly captured binding behind.

## Goal

commit preparation before transaction-free inference

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatProfilePreparationService.java, src/test/java/io/mindspice/magenta2/ai/chat/system/SystemChatServiceTest.java, src/test/java/io/mindspice/magenta2/ai/chat/system/SystemChatTransactionIntegrationTest.java

## Out of Scope

- Prompt, callback, proposal, SSE, model-selection, redaction, general-chat transaction, repository, migration, controller, route, payload, UI, and JavaScript changes.
- Changing the blocking runtime workspace-scoping drift in `api.md`.

## Dependencies

none

## Constraints

- Preserve the server-owned fixed prompt, callback allowlist, maximum read context, agent-only scope, owner-link checks, bounded-read/proposal-only authority, response redaction, and fail-closed unavailable result.
- The preparation collaborator owns the only preparation transaction. It must not catch persistence failures after writes begin.
- Disabled, blank, and pre-existing mismatched states return unavailable without writing. Any mismatch discovered by post-write verification aborts the transaction rather than committing partial preparation state.
- Invocation orchestration uses `NOT_SUPPORTED`; no SQLite transaction may enclose client acquisition, callback execution, or model/provider work, even under an ambient caller transaction.
- `SystemChatProfileHolder.clear()` executes on every invocation return path. Logs and results omit messages, model output, binding paths, persistence details, credentials, tokens, owners, and fence facts.

## Implementation Steps

1. Add `SystemChatProfilePreparationService` as a narrow Spring-managed collaborator. Give it one public `@Transactional` preparation method returning the existing `SystemChatResult` shape; move only runtime enabled/model checks, agent lookup, descriptor resolution, binding/origin/owner preparation, and fixed-profile construction into it. Keep `SystemChatService` responsible for orchestration, client use, holder lifecycle, redaction, and outer unavailable conversion.

2. Before any preparation write, validate the request and all readable conflict facts: feature enabled, nonblank agent and configured model, existing agent, trusted `AGENT` descriptor, existing conversation origin/agent pair, existing owner link, and an existing binding when present. Disabled, blank, missing, stale, non-ready, wrong-agent, non-system-origin, wrong-origin-agent, or wrong-owner-binding states return unavailable. Do not overwrite, retarget, or infer any of these facts.

3. Only after that preflight, create the default-or-personal binding when absent, resolve it against the trusted descriptor, construct the fixed profile, save the system origin if absent, and create the conversation owner link if absent. Re-read origin/agent and owner-link facts after their writes; accept only `SYSTEM_CHAT`, the requested agent, and the exact deterministic binding key. Preserve the existing deterministic keys:

   ```text
   bindingKey      = system-chat:<agentId>
   conversationId  = system-chat:<agentId>
   owner operation = system-chat-owner-link:<agentId>
   ```

4. If a post-write re-read is unavailable or mismatched, throw a narrow internal rollback signal from the collaborator. It must leave the transactional proxy uncaught so Spring rolls back binding, origin, and owner-link writes together. Do not return unavailable from inside the transaction after a write unless the transaction is explicitly doomed; do not broadly catch runtime persistence failures.

5. Refactor `SystemChatService.resolveProfile` to delegate to the collaborator and convert a returned unavailable result or an exception that has already crossed the transactional proxy into the existing unavailable result. Remove its transaction annotation and obsolete preparation dependencies. Keep `toolRegistry.requireFixedProfile(profile)` before callbacks are obtained, so a prepared profile still proves the fixed callback allowlist.

6. Mark `SystemChatService.invoke` with `@Transactional(propagation = Propagation.NOT_SUPPORTED)`. Enclose validation, preparation delegation, client acquisition, holder set, model call, redaction, and all early returns in one `try`/`finally`; clear the holder in that `finally`. Convert model/provider/router and already-escaped preparation failures to the existing unavailable result outside the preparation transaction.

   ```java
   @Transactional(propagation = Propagation.NOT_SUPPORTED)
   InvocationResult invoke(...) {
       try {
           var prepared = resolveProfile(agentId); // prepare() has committed or rolled back
           // obtain client, set holder, call model, redact
       } finally {
           SystemChatProfileHolder.clear();
       }
   }
   ```

7. Update `SystemChatServiceTest` for the collaborator constructor seam. Assert route-agent identity and the unchanged fixed prompt, callback list, context bound, descriptor, and conversation id. Cover disabled/blank and incompatible origin/owner results without preparation writes; cover outer unavailable conversion, no client call on unavailable preparation, and holder cleanup after success, model failure, and unavailable preparation.

8. Add `SystemChatTransactionIntegrationTest` with a compact Spring context using `@EnableTransactionManagement`, a real SQLite transaction manager, and proxied preparation and invocation beans. Use a file-backed or otherwise shared SQLite database and query prepared rows through a newly acquired non-transaction-bound connection. A non-network model callback records transaction state and reads the prepared facts; it must observe committed origin, binding, and owner-link facts and `TransactionSynchronizationManager.isActualTransactionActive() == false`.

9. In that integration test, invoke the proxied service from an ambient `TransactionTemplate` transaction and assert the same transaction-free model callback. Inject one test-only failure after the fixture has written binding, origin, and owner-link facts but before preparation returns. Assert the invocation is unavailable, the callback was never reached, and an independent connection finds none of those durable facts. Do not add a production test hook or change persistence/schema resources.

10. Inspect every system-chat model-call path to confirm no transaction annotation or template encloses `ChatClient.call()`. Run the focused service, transaction, fragment, bounded-read, proposal, and system-chat security tests available in the repository; run the complete Maven suite, configured compile/package checks, and bounded ephemeral-port Spring Boot startup. No browser validation is expected because this phase preserves browser-visible behavior; treat any observed fragment or SSE change as scope drift.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md

## Technical Guidance

- The test must call Spring proxies; direct construction cannot prove annotation interception.
- Keep the collaborator internal and small. It owns preparation atomicity, not a new chat layer or a general transaction framework.
- The model callback is the assertion boundary: check committed facts through an independent connection and check transaction activity there, not before `invoke` returns.
- The failure fixture may be test-only, but it must perform its writes through the transaction-bound connection before throwing. Mock invocation counts alone do not establish rollback.
- Do not change `SystemChatController` despite its separate profile lookups. `invoke` must prepare its own profile so inference never relies on a prior web lookup.

## Validation

- `SystemChatServiceTest` proves fixed-profile facts, unavailable behavior, no inference after preparation failure, and holder cleanup.
- `SystemChatTransactionIntegrationTest` proves Spring proxy use, committed separate-connection visibility before inference, ambient-transaction suspension, model callback transaction absence, rollback of all preparation facts, and suppressed inference on rollback.
- Existing system-chat fragment, bounded-read, proposal, and security tests remain green; their fixed callback, bounded-context, agent-scope, proposal-only, redaction, and fail-closed assertions must not regress.
- Static inspection finds no system-chat `ChatClient.call()` under a transaction annotation or template. The Maven suite, compile/package checks, and bounded startup establish the broader baseline; report any non-baseline failure through the project workflow.

## Exit Criteria

- Preparation either commits the complete compatible binding/origin/owner-link fact set before inference or rolls back without model invocation; no partial binding remains after a rejected or raced preparation.
- The model callback has no active transaction, including when `invoke` is called from an ambient transaction.
- Fixed-profile security, redaction, unavailable behavior, and unconditional holder cleanup remain intact.
- Focused validation and the post-phase validator review-and-repair gate report PASS.
