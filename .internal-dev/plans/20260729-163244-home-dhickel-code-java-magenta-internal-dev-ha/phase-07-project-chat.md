# Phase 07 — Project Chat

## Context

Project chat is a restricted profile over existing chat metadata and `ai_chat_memory`. It fixes a project, selected current member agent, and exact ready materialization generation for the life of a conversation. It is not a project workspace, an ordinary-chat variant, or a new transcript authority.

## Goal

add immutable-generation restricted project chat over existing chat memory

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionOrigin.java, src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatSessionMetadataRepository.java, src/main/java/io/mindspice/magenta2/ai/chat/project/ProjectChatProfile.java, src/main/java/io/mindspice/magenta2/ai/chat/project/ProjectChatProfilePreparationService.java, src/main/java/io/mindspice/magenta2/ai/chat/project/ProjectChatToolRegistry.java, src/main/java/io/mindspice/magenta2/ai/chat/project/ProjectChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatActionProposalService.java, src/main/java/io/mindspice/magenta2/api/web/ProjectChatController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectChatFragments.java, src/main/resources/static/js/orchestration/project-chat.js, src/main/resources/db/migrations/019-project-chat.sql, src/main/resources/schema.sql

## Out of Scope

Ordinary-chat tools or routes, filesystem writes, shell/process execution, skill activation, package ingestion, plan mode, client model choice, unrestricted file reads, automatic agent switching, project workspace authority, a parallel transcript store, navigation consolidation, and documentation/specification closeout.

## Dependencies

phase-06-project-automation-and-events.md

## Constraints

- Begin only after the Phase 06 validator has passed. Phase 06 establishes the immediately preceding migration state and the passed project/archive/member/access contracts that this phase consumes. Phase 08 may consume this phase only after this phase passes.
- Read the closest package `AGENTS.md` files and the current SimplyPages documentation and matching demos before web edits. Read the listed project specifications and knowledge records when the internal-development store is available; do not initialize or repair that store implicitly.
- `PROJECT_CHAT` is a distinct metadata origin. The server alone creates a conversation identity and its immutable `(conversation, project, selected agent, materialization key/generation, owner link)` binding. A different selected agent or generation requires a different conversation.
- Project association is attribution and fixed chat context, never workspace selection. The selected agent's established execution context remains authoritative; no project workspace kind is introduced.
- Visible user and assistant messages use existing `ai_chat_memory` only. Hidden policy, tool state, provider internals, and operation records are reconstructed or stored through the bounded metadata/operation authority, not exposed as transcript messages.
- Every sensitive read and mutation independently enforces the trusted-deployment capability decision, active-project guard, required current member relationship, exact metadata/owner link, and exact bound `READY` generation. Historical membership, route IDs, client project IDs, and current generation heads are never substitutes.
- All project-chat reads are bounded and redacted. They expose only the disclosure required by the authorized view and never raw input/output, evidence, unrestricted errors, paths, file bytes, claims, lease/fence data, checkpoints, secrets, or arbitrary transcript/file access.
- Generic chat list, lookup, history, and stream paths exclude `PROJECT_CHAT` at the metadata repository boundary. If evidence finds a generic route that bypasses this boundary, stop the affected capability and escalate rather than relying on page-local filtering.

## Implementation Steps

A. **Establish metadata, operation, proposal, and preparation authority.**
   1. Inspect the existing chat metadata schema, `ai_chat_memory` ownership conventions, generic chat query paths, migration-runner behavior, project/member access services, and exact materialization-generation resolver. Record the evidence needed to use the existing metadata authority. If the listed target boundary cannot represent or protect the immutable tuple, do not create a second authority; escalate the contradiction.
   2. Add `PROJECT_CHAT` to `ChatSessionOrigin`. In migration `019` and `schema.sql`, add only the additive structures needed for: immutable project-chat metadata binding and owner link; project/origin lookup indexes; durable turn-operation identity and state; and scoped proposal identity. Preserve the established migration checksum, fresh-install, supported-upgrade, partial-structure rejection, and metadata-equivalence conventions.
   3. Make `ChatSessionMetadataRepository` expose narrow create-if-absent and exact-tuple verification/read methods. Creation converges only when the existing immutable tuple is identical; a conflicting tuple fails. Generic metadata mutation cannot rewrite project, selected agent, materialization key/generation, origin, or owner link. Repository queries used by generic chat explicitly exclude `PROJECT_CHAT` rather than treating it as an ordinary session.
   4. Define operation/proposal uniqueness and canonical-digest behavior. A turn operation binds the server-derived caller/actor context, action, conversation and project route scope, operation key, and canonical request semantics. The same key and digest replays its durable outcome; a changed digest conflicts. Proposal records bind conversation, project, proposed canonical assignment, allowed control, and expected assignment version so a target cannot be swapped after generation.
   5. Implement `ProjectChatProfilePreparationService` as a short transactional preflight. It accepts only an authorized project route scope and an explicitly selected eligible current member, resolves an exact `READY` materialization generation through the trusted materialization service, and creates or verifies the immutable metadata tuple and owner link. It never accepts a client-owned conversation, project, generation, or workspace identity as authority. Revalidation failure leaves no partially usable conversation.

B. **Implement the fixed restricted profile and authorized reads.**
   1. Define `ProjectChatProfile` as server-owned fixed prompt, model-routing choice, and deny-by-default capability profile. Clients cannot override its prompt, model, agent, generation, tools, or context.
   2. Implement `ProjectChatToolRegistry` as an explicit allowlist of service-backed callbacks. Each callback rechecks the profile scope and returns bounded, secret-free DTOs only for authorized project metadata, current members, exact materialization state, assignment/Job summaries, automation summaries, output metadata, and exact persisted-link transcript reads.
   3. Do not pass repositories, filesystem paths, unrestricted chat history, raw model tools, or ordinary-chat tools to the provider. Deny filesystem writes, process execution, skills, package ingestion, unrestricted file reads, and all unlisted capabilities by construction. Use uniform unavailable/not-authorized results where detail would disclose protected state.
   4. Extend `SystemChatActionProposalService` only for the explicit Queue controls already supported by `AssignmentControlService`. A generated proposal is inert intent, not an action. Confirmation revalidates capability, project/activity, current membership, conversation tuple, proposal binding, canonical scoped assignment, lifecycle, and expected revision, then delegates to `AssignmentControlService`. It never trusts model-provided target IDs or bypasses assignment writer fencing.

C. **Implement serialized, durable, idempotent turns.**
   1. In `ProjectChatService`, revalidate the complete immutable conversation scope before claiming work. Claim the canonical-digest operation and a durable, fenced per-conversation serialization position. Do not permit a later turn to invoke or append while an earlier operation is unresolved.
   2. Load only the configured bounded memory window from `ai_chat_memory`, build hidden fixed policy/tool context server-side, and invoke the provider through the restricted profile. No database transaction may span provider inference.
   3. Use durable operation states that distinguish completed replay, digest conflict, retryable provider failure, and stale/denied revalidation. A provider failure records a recoverable outcome without appending a fabricated assistant message. Retrying the same semantic operation must not create duplicate visible messages; uncertain provider invocation may be retried only under the durable operation/fence protocol and remains invisible until finalization.
   4. Before finalizing, recheck gate, project archive state, selected-agent membership, metadata/owner tuple, and the same exact bound `READY` generation. In one short transaction, atomically finalize the successful operation and append exactly one visible user/assistant pair to existing memory. If final revalidation fails, append neither message and return the durable scoped failure. Fencing prevents a stale claimant from finalizing after recovery.
   5. Return bounded replay-safe response data. Concurrent same-key calls converge, conflicting same-key calls fail, and distinct concurrent turns preserve conversation order without lost or interleaved visible messages.

D. **Add scoped fragments, routes, and narrow SSE behavior.**
   1. Implement `ProjectChatController` routes for panel loading, profile creation/selection, bounded history, turn start/stream, proposal display, and proposal confirmation. Each route resolves the project scope first and maps capability-disabled, archived, missing, cross-project, stale-generation, and unavailable states without sensitive detail. Generic chat routes must not gain a project-chat exception.
   2. Build `ProjectChatFragments` as stateless SimplyPages renderers over authorized redacted DTOs: an isolated collapsible panel host, selector/status host, bounded history host, stream host, and proposal host. Standard create/select/confirm interactions use HTMX. Keep swaps within their owning host and re-resolve canonical resources for all inspector/action fragments.
   3. Add only `project-chat.js` for SSE transport. It must use a strict event allowlist and bounded payload handling; validate conversation and operation identity on every event; render text solely with inert `textContent`; expose cancellation via abort; and autoscroll only the owned stream container. Never inject provider text or event data as HTML, selectors, attributes, or HTMX instructions.
   4. Register event handling once through safe delegation and abort/reset the active stream when its host is replaced or the conversation changes. Ignore malformed, oversized, unknown, mismatched, stale, duplicate-final, and post-abort events. Server stream events contain only the bounded redacted data required by that event type.

E. **Complete the phase proof after A–D are implemented.**
   1. Add and run focused coverage for metadata immutability/create convergence, origin exclusion, tuple/owner mismatch rollback, project/agent/generation changes, gate/member/archive invalidation, exact-generation rather than head resolution, bounded/redacted history, denied tools, and proposal target/version mismatch.
   2. Prove duplicate and conflicting operation-key behavior, per-conversation concurrency ordering, stale-fence rejection, provider failure/retry recovery, no transaction during inference, no duplicate visible messages, and replay after reload.
   3. Run project-chat tests plus relevant system-chat and ordinary-chat regressions; migration fresh/warm/checksum/partial-structure/metadata-equivalence coverage; and enabled and disabled bounded Spring Boot startup.
   4. Delegate the live-app Playwright MCP validation to the required subagent. Capture screenshots and verify panel create/select/history, stream, cancel, host replacement, reload/replay, proposal confirmation, invalidation and generic-route denial, empty/error/disabled states, HTMX host isolation, keyboard/focus/ARIA behavior, and desktop/mobile visual quality. Inspect the screenshots for density, hierarchy, wrapping, overflow, alignment, and first-viewport usefulness.
   5. Review the final diff for alternate metadata/transcript authorities, whole-row metadata writes, unbounded queries, sensitive DTOs, capability bypasses, unjustified JavaScript, placeholders, and edits outside this phase. The post-phase validator review-and-repair gate must pass before the phase is complete.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/knowledge/event-delegation-sse-dom-replacement.md`
- `.internal-dev/knowledge/live-chat-mcp-workflow-testing.md`

## Technical Guidance

Do not invoke ordinary `ChatService` or `SystemChatService.invoke`. Reuse only proven low-level safe collaborators and rendering/SSE patterns, while making project-chat profile, scope validation, tool policy, and operation authority explicit. Preserve visible Spring AI user/assistant messages in existing memory; reconstruct hidden context server-side. No provider call occurs inside a database transaction.

## Validation

After all implementation steps complete, run the phase-level checks in Step E: focused project-chat security/concurrency tests, relevant ordinary/system-chat regressions, migration fresh/warm/checksum/partial-structure/metadata-equivalence checks, enabled/disabled bounded startup, and the live-app Playwright MCP subagent pass with screenshot review. Repair failures caused by this phase before the validator gate.

## Exit Criteria

- The ledger dependency has passed and the post-phase validator review-and-repair gate passes.
- Project-chat conversation identity, project, selected member agent, exact materialization generation, origin, and owner link are immutable and independently revalidated at every sensitive entry point.
- Existing `ai_chat_memory` is the only visible-message store; turns are bounded, serialized, durable, idempotent, fenced, replay-safe, and recover from provider failure without a provider-spanning transaction or duplicate visible messages.
- Tools are fixed, bounded, redacted, service-authorized, and deny-by-default. Queue proposals remain human-confirmed, scope/version-bound intent delegated through `AssignmentControlService`.
- Generic chat routes and repository queries exclude project-chat origin. SSE is identity-checked, bounded, abortable, host-replacement-safe, and inertly rendered; standard controls remain HTMX.
- Required migration, focused/regression, startup, and Playwright validations pass, with no unrelated edits or unapproved authority expansion.
