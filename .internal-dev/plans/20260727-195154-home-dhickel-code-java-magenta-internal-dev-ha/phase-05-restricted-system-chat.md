# Phase 05 — Restricted System Chat

## Context

Normal chat settings and tool resolution must not grant system-chat authority. Establish a server-created, deny-by-default profile that can return only bounded, secret-free facts and persist non-executing lifecycle proposals.

## Goal
provide fixed-profile read-only system chat and audited queue proposals

## In Scope
**Write Targets**: src/main/resources/db/migrations/012-system-chat-proposals.sql, src/main/resources/schema.sql, src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatInvocationProfile.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatToolRegistry.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatActionProposalService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/settings/RuntimeSettingsService.java

Implement the fixed system-chat profile, its positive tool registry, bounded reads, proposal persistence and confirmation bridge, and migration-011 integration only in these targets.

## Out of Scope

Public routes or fragments, browser/SSE work, file writes, shell or process use, skill discovery/trust/activation, cross-agent browsing, model-executed actions, client-supplied surface authorization, and changes to `RequestResolver`, package guides, or undeclared source files.

## Dependencies
phase-03-immutable-binding-lifecycle.md, phase-04-prompt-skills-context.md

## Constraints

- Require PASS evidence for both dependencies. Consume their immutable binding, trusted prompt, lifecycle, and snapshot contracts; do not duplicate or retarget them.
- Only server code may create a `SYSTEM_CHAT` origin/profile and its conversation metadata. Public chat invocation must reject an INTERNAL/system origin or profile regardless of how request surface was resolved. `SystemChatService` is the sole entry to the internal profile path.
- Keep `SystemChatProfile` as an immutable type owned by `ChatInvocationProfile`, not a new source file. It carries only server-resolved model identity, fixed prompt, fixed callback names, immutable context binding, bound agent, and captured read scope.
- The profile is positive and fixed: runtime settings may enable or disable the capability and select a configured model, but may not supply a prompt, tool names, read scope, skill policy, callback, or fallback authorization. Disabled, missing, or unusable model capability is `UNAVAILABLE`; it never falls back to normal chat configuration.
- Construct tools only from `SystemChatToolRegistry`'s fixed allowlist. Each callback rechecks the server-created profile, bound agent, immutable context, and exact target scope. There is no wildcard, tool-name compatibility fallback, generic registry import, or subtraction-based filtering.
- Reads are bounded and secret-free. They may return only registered status, assignment, lifecycle, and descriptor/context-scoped file facts for the bound agent and captured context. A materialization contributes read-only context and never write/tool authority. Reads reject absent/stale/revoked/non-ready context, arbitrary workspace/agent/chat lookup, links and escape, unapproved skill-source bytes, credentials, tokens, raw failures, claim/fence owners, and absolute paths.
- A proposal is a normalized request for exactly `PAUSE`, `RESUME`, `RETRY`, `REPLAY`, or `CANCEL`; model prose is never authoritative action data. Persist canonical target identity, expected target state or revision, action, canonical payload digest, operation key, proposal state, explicit-confirmation/audit facts, and the linked ordinary control operation identity. Do not persist raw model reasoning as action authority.
- Same proposal operation key and digest converge; contradictory reuse conflicts. Creation validates current target scope. Confirmation revalidates proposal state, bound agent, exact target, expected state or revision, and ordinary control authorization. Stale, unavailable, denied, malformed, or out-of-scope proposals never execute a lifecycle action.
- The model cannot confirm or execute a proposal. Only an explicit user confirmation enters the existing ordinary scoped control plane, which retains its own authorization, idempotency, lifecycle semantics, and audit. A confirmation failure may record a redacted non-executed outcome but must not simulate success.
- Migration resources 001–011 remain unchanged. Migration 012 is contiguous, append-only, checksum-protected, transactionally applied by `SchemaMigrationRunner` alone, and represented equivalently by fresh and upgraded schema metadata. Persistence classes add no constructor DDL.

## Implementation Steps

1. Add append-only migration 012 for system-chat action proposals. Define an application-generated proposal identity; exact system conversation and bound-agent references; canonical target kind and identifier; normalized action; expected target state or revision; canonical payload digest; operation key; proposal state; confirmation and audit facts; and linked ordinary control-operation identity. Add unique constraints that implement operation-key/digest convergence and indexes for bounded conversation/agent proposal reads. Update `schema.sql` and `SchemaMigrationRunner` only as required for the contiguous, checksum-verified migration contract; do not alter migrations 001–010 or add constructor DDL.
2. Add the explicit internal `ChatInvocationProfile` seam. Preserve normal profile behavior, but make an immutable `SystemChatProfile` value within this target carry a server-resolved model, immutable fixed system policy, fixed callback names, immutable binding, bound agent, captured read scope, and a no-skills policy. `ChatService` must assemble the inherited trusted binding policy before system task intent and must exclude normal configurable prompts, untrusted guidance, and skill snapshots from this profile.
3. Construct `SystemChatProfile` positively in `SystemChatService`. Resolve the bound agent and immutable context through dependency-owned services, reject non-ready/stale/revoked resolution, and use a fixed maximum context/read bound. `RuntimeSettingsService` may expose only capability enablement and configured model selection; ignored compatibility tool settings, prompt settings, and fallback model settings must have no authorization effect.
4. Make `SystemChatService` atomically create-or-reuse the server-owned `SYSTEM_CHAT` conversation metadata and its immutable binding/owner relationship using the dependency-established unique creation semantics. In `ChatService`, reject public requests that select INTERNAL or a system origin/profile and expose the system invocation only to `SystemChatService`; do not modify `RequestResolver`. A normal conversation can neither acquire nor retain system callbacks by changing a surface value.
5. Construct `SystemChatToolRegistry` solely from a literal fixed list of bounded status, file-fact, assignment/lifecycle-fact, and proposal callbacks. Pass a server-created invocation object rather than caller-supplied agent, workspace, path, context, or tool authority. Each callback must repeat profile, agent, binding, and scope validation at invocation; unsupported names and every generic file, operational, shell, skill, or user-selected callback are unavailable.
6. Implement bounded read behavior through `SystemChatService` and the registry callbacks. Clamp result count, byte size, depth, and response size; use existing trusted descriptor/context services rather than rebuilding paths or identity; and return typed unavailable/not-found/out-of-scope results without sensitive distinctions. Sanitize every response so it contains no secret, credential-bearing URL, absolute path, internal location, raw failure, claim/fence owner, token, or bytes outside the approved read set.
7. Implement `SystemChatActionProposalService` creation for only the five normalized lifecycle actions. Validate the target belongs to the bound agent and captured scope and that the requested action is currently meaningful; canonicalize the action payload; compute the canonical digest; and persist or converge by operation key. Return a secret-free proposal identifier, action, target display facts, and non-executing state. The service must never call a lifecycle mutation while creating a proposal.
8. Implement confirmation in `SystemChatActionProposalService` by delegating to the existing ordinary scoped control authorization and lifecycle operation path, not by adding a separate authorization service. Require explicit user confirmation, exact pending proposal identity, matching bound agent and target, expected-state/revision revalidation, a confirmation operation key, and ordinary authorization. Persist the linked ordinary operation and redacted audit outcome transactionally with idempotent convergence. A repeated matching confirmation returns its recorded result; contradictory reuse conflicts. Denial, unavailability, cancellation, or stale state leaves the lifecycle action unexecuted.
9. Close the profile boundary in `ChatService` and `SystemChatToolRegistry`: normal tool resolution, generic file/operational tools, process execution, skill trust/activation, and arbitrary user-selected tools cannot enter the system profile by configuration, request fields, persisted conversation metadata, callback name, or direct callback invocation.

## Required Guides

- `AGENTS.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/knowledge/chat-session-surface-scope-filter.md`
- `.internal-dev/knowledge/agent-scoped-assignment-lifecycle.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AGENTS.md`

## Technical Guidance

The internal path is `SystemChatService` → fixed `ChatInvocationProfile.SystemChatProfile` → `SystemChatToolRegistry` → bounded reads or proposal creation. The profile is an authorization input, not a user-selectable chat mode. Proposal confirmation exits that path and enters the existing scoped lifecycle control plane; the proposal service records the relationship but never substitutes for lifecycle authorization.

Treat missing trusted facts as unavailable, never as permission. Recompute trusted locations through existing layout and directory authorities at use, retain only their approved identifiers in persistence, and make every renderer/tool result secret-free. No system-chat operation may create a workspace, materialization, skill snapshot, or file.

## Validation

- Verify migration 012 fresh-schema and upgraded-schema metadata equivalence; contiguous ordering after 011; immutable prior migration checksums; duplicate, malformed, gap, checksum-drift, rollback, partial-state, concurrent-starter, and constructor-DDL failure handling.
- Verify public normal chat rejects forged INTERNAL/system surface or profile selection, while only server-created system metadata reaches the internal profile. Verify concurrent system conversation creation converges on one owner/binding relationship and normal sessions never acquire its callbacks.
- Verify disabled, missing, or unavailable system model capability returns `UNAVAILABLE` with no normal model/prompt/tool fallback. Assert fixed prompt order, no skills/untrusted guidance, and no configuration value can broaden profile authority.
- Assert the registry contains exactly the approved callbacks and no wildcard/fallback import. Attempt direct and prompted file write, shell/process, skill trust/activation, credential, cross-agent, arbitrary-workspace, and generic operational access; all must be denied or unavailable.
- Exercise allowed reads at count, byte, depth, and response limits; absent/stale/revoked/non-ready binding; descriptor escape/link attempts; other-agent and other-chat access; unapproved skill source; and redaction of paths, secrets, credentials, tokens, raw failures, claims, and fences.
- Exercise each normalized proposal action for valid scope, invalid target, invalid current state, same-key/same-digest convergence, contradictory key reuse, concurrent creation, restart recovery, stale expected state/revision, explicit confirmation, repeated confirmation, cancellation, authorization denial, unavailable control capability, and audit-link persistence. Prove creation and every failed confirmation perform no lifecycle mutation.
- Run focused automated tests, applicable Maven validation, migration checks, forbidden-construct and secret/redaction scans, bounded application startup, and the required validator review-and-repair. The validator must record PASS and reject undeclared-scope edits, placeholders, normal-tool fallback, simulated proposal success, or sensitive output.

## Exit Criteria

System chat is a server-created fixed profile that public chat cannot select. It exposes only the positive bounded read/proposal registry for its bound agent and immutable context; no dangerous capability can be registered through settings, metadata, request fields, or fallback resolution. Proposals are canonical, idempotent, auditable, non-executing until explicit ordinary-control confirmation, and fail closed on every stale or unauthorized condition. Migration, focused validation, startup, and validator PASS are recorded.
