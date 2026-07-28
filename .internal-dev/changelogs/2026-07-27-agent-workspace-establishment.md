# 2026-07-27 Agent Workspace Establishment

## Date

2026-07-27

## Change Summary

Seven-phase agent workspace establishment sprint: closed AGENT-root policy with conceptual lazy areas; generation-safe project materializations and versioned context defaults with immutable bindings; trusted prompt/skill composition with fixed provenance order and snapshot-only disclosure; restricted deny-by-default system chat with user-confirmable proposals; thin agent-scoped REST APIs; independent HTMX hosts composing the agent console at `/agents/{agentId}`; and final cross-domain integration validation.

## Files

### Phase 01 — Agent Workspace Policy
- `ARCH-20260727-01` (architecture.md): Agent workspace conceptual areas (`.skills`, `scripts`, `apps`, `home`, `repos`, `projects`, `tasks`, `outputs`, unknown children); lazy reads, owner-specific provisioning; scripts/apps as storage only; repos as user-managed read-only storage.

### Phase 02 — Materialization and Context Persistence
- `ARCH-20260727-02` (architecture.md): Generation-safe materializations (`PROVISIONING`/`READY`/`FAILED`/`REVOKED`); versioned context defaults with expected-version CAS; insert-only immutable bindings; assignment-binding operations.
- `AgentContextService`, `AgentContextRepository`, `AgentMaterializationService`, `AgentMaterializationRepository`, `MaterializationLocation` (new `ai/orchestration/context` package)
- Migration `010-agent-materialization-context.sql`: `agent_materializations`, `context_defaults`, `context_bindings` tables
- Migration `011-materialization-key-constraints.sql`: strict key and operation idempotency constraints

### Phase 03 — Immutable Binding Lifecycle
- Migration `012-assignment-binding-operations.sql`: `assignment_binding_operations` table for durable submission decisions
- Conversation, assignment, native run, resume, retry, replay, workflow child, and job work-item binding capture
- Agent side-panel chat captures a new conversation binding using the server-resolved route agent and rejects existing conversations bound to another agent.

### Phase 04 — Prompts and Skills Context
- `ARCH-20260727-03` (architecture.md): Fixed prompt provenance order; trusted captured binding and allowed-path facts; labelled untrusted `AGENTS.md`/`WORKSPACE.md` guidance; generation-bound materialization skill authority; snapshot-only disclosure.
- `PromptContextAssembler` integration, `MaterializationSkillAuthority`

### Phase 05 — Restricted System Chat
- `ARCH-20260727-04` (architecture.md): Fixed deny-by-default profile; bounded non-secret reads; restricted tool registry; persisted user-confirmable queue-operation proposals; exact confirmation gating.
- `SystemChatService`, `SystemChatBoundedReadService`, `SystemChatToolRegistry`, `SystemChatActionProposalService`, `SystemChatProfileHolder` (new `ai/chat/system` package)
- Migration `013-system-chat-proposals.sql`: `system_chat_action_proposals` table

### Phase 06 — API and Agent Console
- `ARCH-20260727-05` (architecture.md): Independent HTMX hosts (`#agent-context-panel`, `#agent-queue-panel`, `#agent-skills-panel`, `#system-chat-panel`); conceptual Files area navigation; non-materializing Files area delegation.
- `AgentContextController`, `AgentContextFragments`, `SystemChatController`, `SystemChatFragments` (new)
- `WorkspaceExplorerFragments`, `OrchestrationController` (updated)
- `system-chat.js` (new SSE-only JavaScript), `htmx-errors.js` (shared marked-error policy)
- `orchestration.css` (updated)

### Phase 07 — Documentation and Integration (this changelog)
- Updated all `.internal-dev/specifications/`: `architecture.md`, `service-graph.md`, `services.md`, `api.md`, `web.md`, `simplypages.md`, `decisions.md`, `deferred-features.md`
- Updated technical docs: `architecture.md`, `data-model.md`, `security.md`, `workspaces-tools-outputs.md`, `configuration-operations.md`, `orchestration-runtime.md`, and `chat-planning-tasks.md`
- Updated end-user docs: `agents.md`, `projects-and-workspaces.md`, `chat.md`, and `inbox-outputs-settings.md`
- Migration ownership updated to cover contiguous resources 001-013
- `RequestResolver`, `ChatService`, and `RequestResolverAgentBindingTest`: final integration repair for route-agent-bound side-panel chat context capture.
- `knowledge/agent-chat-context-binding-route-agent.md`: reusable route-agent binding invariant.

## Behavioral Impact

### New Capabilities
- **Agent workspace areas:** Nine recognized conceptual areas in the Files tab, each delegating to its domain owner. `scripts`/`apps` are storage-only. `repos` is user-managed read-only storage. Unknown children are read-only.
- **Materialization lifecycle:** Project materializations with `PROVISIONING`/`READY`/`FAILED`/`REVOKED` states and generation-safe identity. Trusted ready-location resolution validates an expected generation; provisioning transitions with the loaded generation. `READY` means safe local availability, not acquired content.
- **Context defaults and bindings:** Versioned context defaults (`MATERIALIZATION` or `PERSONAL`) updated with expected-version CAS; insert-only immutable bindings captured at submission time and never retargeted.
- **Assignment-binding operations:** Single durable submission decision linking context capture, assignment insertion, and lifecycle reservation. Operation-key idempotency.
- **Prompt context integration:** Fixed provenance order (policy → trusted facts → intent → labelled guidance → approved snapshots). `WORKSPACE.md` is optional bounded guidance. Binding failure ends the turn.
- **System chat:** Fixed deny-by-default profile per agent with bounded agent-status, workspace-metadata, and assignment-lifecycle reads, restricted tools, and user-confirmable queue-operation proposals. Runtime settings currently supply only enabled state and model; prompt, callback allowlist, and context bound are server-owned constants. Proposals are non-executing; confirmation requires an exact matching proposal digest and rechecks target state before lifecycle dispatch.
- **Agent console:** Independent HTMX hosts for context, queue, skills, system chat, runs, and Files at `/agents/{agentId}`. Conceptual Files area navigation is non-materializing.

### Modified Behavior
- **Files tab:** Now includes conceptual area navigation; no longer a flat file tree. `repos` area is read-only from Magenta.
- **Migration ownership:** Expanded from 001-011 to 001-013.
- **API surface:** New routes at `/api/agents/{agentId}/context` and `/api/agents/{agentId}/system-chat`.
- **SSE transport:** `system-chat.js` added as the only new JavaScript transport (narrow SSE parsing, safe textContent rendering).

### Preserved Behavior
- Closed `WorkspaceKind` model (`AGENT`, `CHAT`, `USER`) unchanged.
- `WorkspacePathLayout` sole structural authority; `WorkspaceDirectoryService` sole descriptor constructor.
- Projects remain membership/attribution metadata without filesystem roots.
- Skill trust model (snapshots, revocation, executable fail-closed) unchanged.
- Task-run lifecycle, artifact publication, observability, and automation unchanged.
- Existing chat, plan, workflow, job, and output contracts unchanged.

## Specification Impact

All specifications reconciled with observed code:
- `architecture.md`: Five new active entries (ARCH-20260727-01 through -05); migration ownership updated to 001-013; context/binding/system-chat sections added to cross-domain read model; new drift entry for remaining repository DDL.
- `service-graph.md`: Three new service edges (SVC-20260727-01 through -03); updated `SchemaMigrationRunner` interaction.
- `services.md`: Four new service entries (SVC-20260727-01 through -04) for context defaults, materialization lifecycle, prompt/skill composition, and restricted system chat.
- `api.md`: Two new API entries (API-20260727-02, API-20260727-03) for context REST and system chat REST with route tables.
- `web.md`: Agent Console Composition section with host contract table; Conceptual Files Area Navigation section.
- `simplypages.md`: Agent Console Fragment Composition section with context panel, system chat panel, and htmx-errors.js entries; updated cross-domain independent host list.
- `decisions.md`: Four new active decisions (DECISION-20260727-01 through -04) for immutable materialization/bindings, conceptual area delegation, fixed prompt provenance, and deny-by-default system chat.
- `deferred-features.md`: 2026-07-27 reconciliation section documenting implemented capabilities and intentionally unavailable features.

## Validation

- **Full Maven suite:** `mvn test` passed during final integration validation (906 tests, 0 failures).
- **Bounded startup:** `timeout 40s mvn spring-boot:run -Dspring-boot.run.arguments='--server.port=0 --spring.datasource.url=jdbc:sqlite:/tmp/magenta-final-integration.sqlite --magenta.root.path=/tmp/magenta-final-integration-root'` initialized a fresh schema through migration 013 and reached `Started Magenta2Application` in 3.65 seconds before expected timeout shutdown.
- **Schema migration:** Fresh schema and ordered resources 001-013 metadata-equivalent; warm-database upgrade coverage passed in `SchemaMigrationRunnerTest`.
- **Focused tests:** `RequestResolverAgentBindingTest` proves route-agent capture for new agent chats and rejection of an existing cross-agent binding. Existing context, materialization, system-chat, fragment, and migration tests passed.
- **Static review:** Production source declares no project workspace kind. The migration resources remain contiguous through 013, and documentation now states that only system-chat enabled state and model come from runtime settings.
- **Acceptance status:** `BLOCKED` solely by the required delegated live Playwright desktop/mobile validation, which is unavailable in this validator environment. No product, migration, Maven, startup, or static integration failure remains.

### Remaining Known Issues
- `DRIFT-20260720-01` (api.md, blocking): Task-run `/api/runtime/runs/{taskRunId}/*` reads lack workspace scope; schedule firing-attempt JSON exposes claim owner/fence. These pre-date the sprint and are routed to Phase 10 security closeout.
- `DRIFT-20260727-01` (architecture.md, open): Older unrelated domain repositories still use repository-local compatibility DDL outside migration ownership. Routed to explicit later migrations.

## Risks

- **Materialization content acquisition:** `READY` is a safe-availability assertion, not a content guarantee. Content is out-of-band. Operators must understand that an empty `READY` container is normal.
- **System chat authority:** Proposals are non-executing but confirmation gates depend on the ordinary scoped lifecycle control plane which has no principal authentication in the current alpha posture.
- **repos read-only:** User-managed storage is read-only from Magenta. No Magenta-side provisioning, mutation, or interpretation exists. This may surprise operators expecting Magenta to manage this area.
- **JavaScript transport:** `system-chat.js` is narrow SSE parsing with safe textContent rendering. Any future expansion beyond SSE lifecycle would require re-evaluation per HTMX-first policy.

## Follow-up Items

- Deferred routine: materialization content acquisition flow, user-managed repos controls, script/app execution authorization, broader system-chat tooling, cross-agent context browsing.
- Unavailable capability: process confinement for shell/skill executable authorization; FTS5 probe-dependent search.
- Drift repair: `DRIFT-20260720-01` task-run scope and secret-free response DTOs in Phase 10.
- Required delegated Playwright desktop/mobile validation and the Phase 06 PASS gate must be completed before Phase 07 can be accepted.
