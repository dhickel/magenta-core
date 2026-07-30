# Project Chat Validator Review

## Scope

Post-phase review of Phase 07 Project Chat, focusing on restricted capability boundaries, immutable conversation identity, persistence, member selection, origin isolation, and SSE rendering.

## Findings

### Repaired

- Replaced mutable project-origin upsert behavior with create-or-verify immutable project-chat session tuples.
- Captured project, selected member agent, exact materialization key/generation, and server-created owner-link key in metadata; migration 020 now owns the required columns and indexes.
- Removed explorer/filesystem collaborators from the project-chat callback registry. The fixed eight-callback allowlist has no file-write, shell/process, skill, or package capability.
- Revalidated trusted deployment, active project, current member, and exact READY generation at profile preparation, invocation finalization, and every tool callback.
- Persisted bounded visible user/assistant project-chat history in `ai_chat_memory`; history reads now return persisted visible messages.
- Excluded `PROJECT_CHAT` from generic browser session lists and public generic-chat conversation entry points.
- Made project member selection use raw scoped identities while route generation performs one encoding pass.
- Added per-event conversation/operation identity checks, payload caps, `textContent` rendering, cancellation, and stale host/stream guards to `project-chat.js`.

### Blocking remaining gaps

- The implementation does not have the Phase 07 durable turn-operation table/state machine, persisted idempotency key/digest, database fencing, recovery protocol, or cross-process serialization required by the accepted plan. Current serialization is process-local.
- Queue proposal creation, scoped proposal persistence, target/version revalidation, and human confirmation delegation are not implemented for project chat.
- No focused Project Chat tests or delegated live Playwright MCP validation were present. The validator harness has no subagent/Playwright tools, so the required delegated browser pass could not be run.

## Risk Assessment

The repaired restricted profile no longer exposes direct filesystem, process, skill, or package access and has substantially safer session/SSE behavior. However, retries or concurrent traffic across JVM instances can still duplicate/reorder turns, and the required proposal workflow is absent. These are phase exit-criterion failures.

## Recommendations

Keep Phase 07 blocked. Implement the durable turn/proposal authority and focused security/concurrency tests before rerunning the validator and delegated browser validation.

## Follow-ups

- `mvn test` passed: 1,276 tests.
- Fresh bounded Spring Boot startup reached `Started Magenta2Application`; the bounded process was then terminated by `timeout` (exit 124 as expected).
- `git diff --check` passed.
