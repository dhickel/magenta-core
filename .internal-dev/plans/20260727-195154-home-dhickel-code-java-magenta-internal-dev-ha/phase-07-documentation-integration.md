# Phase 07 — Documentation and Integration

## Context

After Phase 06 has PASS evidence, reconcile the completed workspace, context, lifecycle, prompt/skill, system-chat, API, and agent-console contracts. This phase changes only the declared records and runs validation; it does not add product behavior, source code, tests, migrations, package-guide edits, knowledge files, or plan-file/archive changes.

## Goal
reconcile contracts and run final cross-domain validation

## In Scope
**Write Targets**: .internal-dev/specifications/architecture.md, .internal-dev/specifications/service-graph.md, .internal-dev/specifications/services.md, .internal-dev/specifications/api.md, .internal-dev/specifications/web.md, .internal-dev/specifications/simplypages.md, .internal-dev/specifications/decisions.md, .internal-dev/specifications/deferred-features.md, docs/technical/architecture.md, docs/technical/data-model.md, docs/technical/security.md, docs/technical/workspaces-tools-outputs.md, docs/end-user/agents.md, docs/end-user/projects-and-workspaces.md, docs/end-user/chat.md, .internal-dev/changelogs/2026-07-27-agent-workspace-establishment.md

Reconcile intended specifications, explanatory technical/end-user documentation, durable decisions/deferred capability records, and the final changelog with completed-phase evidence. Run existing cross-domain checks and record only their verified results in the declared records.

## Out of Scope

New or repaired product behavior; routes; authentication; workspace kinds; shared roots; process execution; source, test, migration, package-guide, or knowledge-file edits; and edits, moves, or archives of any phase plan. Do not convert a deferred question into accepted capability.

## Dependencies
phase-06-api-agent-console.md

## Constraints

- Enter only with Phase 06 PASS evidence; its dependency gate establishes the completed prerequisite chain. A failed, blocked, unavailable, or contradictory result stops acceptance and is routed to the owning phase or tracking record rather than documented as success.
- Code is observed truth, specifications are intended truth, and explanatory documentation/changelog text must identify—not conceal—any disagreement. Do not invent behavior from a plan, configuration, cache, or UI label.
- Preserve the closed `WorkspaceKind` model (`AGENT`, `CHAT`, `USER`). `WorkspacePathLayout` remains the sole structural grammar and `WorkspaceDirectoryService` the sole trusted-descriptor constructor. Projects are membership/attribution metadata, never workspace identities, roots, descriptors, aliases, links, mounts, or leases.
- Describe persisted Magenta-owned locations only as data-root-relative assertions verified through trusted layout/directory/root-relative authorities. Do not publish absolute paths, secret references, credential-bearing URLs, internal resolved endpoints, claim owners, fences, tokens, or raw failures.
- State availability truthfully. Missing authority, current binding, generation, lease, fence, confirmation, or safe capability means no mutation and no simulated success.
- Preserve existing contracts except the completed captured-context integrations. Defaults affect only new captures; bindings are insert-only and never retarget; expected-version and expected-generation guards remain distinct from operation-key/request-digest idempotency.
- Keep the existing docs indexes accurate only when their already-declared target is being edited; do not create or edit an unlisted index merely for cross-linking.

## Implementation Steps

1. Verify Phase 06 PASS evidence, its completed dependency evidence, validator findings, and the final implementation diff before editing records. Compare observed code and tests with the shared concepts; for an invariant or public-contract conflict, record the discrepancy in the affected declared specification/changelog and reopen the owning phase instead of normalizing the conflict.
2. Update `architecture.md`, `service-graph.md`, `services.md`, `api.md`, `web.md`, and `simplypages.md` from verified behavior. Cover closed/lazy AGENT-root areas and owner-specific provisioning; selected descriptor plus supplemental captured context; materialization identity/generation and root-relative assertions; the fact that a `READY` project materialization is an empty-or-existing local context container rather than acquired content; immutable bindings/default CAS; prompt/skill, system-chat, and independently scoped agent-console host boundaries.
3. Update `decisions.md` only with durable, verified tradeoffs: immutable bindings and default CAS; confinement and root-relative persistence; snapshot-only skills; and persisted system-chat proposals. Reconcile `deferred-features.md` by retaining unavailable capabilities as deferred or unavailable where supported by evidence; never restore project roots, generic workspace identity, or process execution.
4. Update the declared end-user documents for Files, context selection/conflicts, skill trust limits, queue/run facts, and system-chat limits. Explain `repos` only as user-managed storage outside Magenta: existing permitted reads are descriptor-confined, and Magenta provides no provisioning or mutation controls. Explain that project materialization readiness does not imply content acquisition. Use user-safe state/outcome language: `UNAVAILABLE`, `PROVISIONING`, `READY`, `REVOKED`, `BUSY`, `STALE`, `FAILED`, and `RECONCILED` are not interchangeable. Do not edit package guides.
5. Update the declared technical documents with code-verified migration/data-model ownership; service interaction boundaries; confinement and root-relative persistence; immutable binding and generation checks; cancellation; prompt provenance and immutable skill snapshots; fixed system-chat profile/proposals; request concurrency/idempotency semantics; status/error semantics; bounds; independently replaceable HTMX hosts; and SSE event behavior. Link only to existing, verified documentation locations.
6. Do not create or modify knowledge, API/migration records, package guides, tests, or source files in this phase. If validation reveals a reusable lesson or a missing record outside the declared targets, route it to the owning completed phase or a separately authorized follow-up rather than expanding this phase.
7. Execute and assess existing integration coverage for conversation-to-assignment-to-run binding, default/generation conflict rejection, trusted prompt facts, controller summaries, agent deletion/revocation ordering, and serialized secret-leak assertions. Confirm the checks demonstrate no retargeting, no mutation after stale/revoked/non-ready state, and no sensitive output. Missing or failing coverage reopens its owning phase; do not add coverage here.
8. Run the established focused tests and applicable Maven suite; fresh-schema and 010-to-012 upgrade metadata equivalence with bounded application startup; writer inventory; forbidden construct, dependency, migration-checksum, and secret/redaction scans; cancellation and restart checks; and delegated live Playwright validation with desktop/mobile screenshots and visual findings. Confirm standard interactions remain HTMX-owned and that SSE parsing is the only new JavaScript transport. Treat unavailable required validation as a blocker, not a pass.
9. Update the declared changelog with verified files, behavioral/specification impact, validation outcomes, risks, and any routed blocker. Review final diff/status for placeholders, unrelated edits, migration rewrites, restored workspace abstractions, forbidden roots/paths, credential leakage, and unapproved API growth. Obtain final integration-validator PASS, then create the required closeout commit containing the declared records; do not alter the phase-plan file set.

## Required Guides

- `AGENTS.md`
- `.internal-dev/AGENTS.md`
- `.internal-dev/specifications/AGENTS.md`
- `docs/AGENTS.md`
- `.internal-dev/knowledge/documentation-foundation.md`
- `.internal-dev/knowledge/live-chat-mcp-workflow-testing.md`
- `.internal-dev/knowledge/orchestration-browser-validation-checkpoints.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- `.internal-dev/knowledge/scoped-skill-trust-snapshots.md`

## Technical Guidance

Use the following assertions consistently, only where evidence verifies them:

- `scripts` and `apps` are storage, not execution authority. Unknown AGENT-root children remain unmigrated and are not implicitly writable; reads do not create directories. `repos` is user-managed storage outside Magenta: permitted existing-content reads remain descriptor-confined, and Magenta neither provisions nor mutates it.
- The selected `workspaceType`/`workspaceKey` descriptor remains authoritative. Captured project or materialization context supplements it and never replaces it; a `READY` materialization asserts only safe local context availability, not acquired content; CHAT remains rejected for execution.
- Skill association, discovery, provenance, or assignment is not trust. Only approved immutable snapshots are disclosed or activated; executable skill authority remains independently unavailable unless separately verified.
- System chat is a fixed deny-by-default profile. It has bounded non-secret reads and can persist user-confirmable queue-operation proposals, but cannot write files, invoke a process, approve/activate skills, broaden authority, or execute its own proposal.

## Validation

Validation passes only when the evidence for all of the following is available and successful:

- Phase 06 and its prerequisite gate evidence are PASS, and the final integration validator returns PASS.
- Focused completed-phase tests and the applicable Maven suite pass; bounded application startup succeeds; fresh schema and 010-to-012 upgrade metadata are equivalent; immutable migration and checksum checks remain green.
- Existing tests or scans verify canonical-area and descriptor confinement, lazy roots, root-relative persistence, no project-root identity, immutable binding/default/generation semantics, request-digest conflict behavior, prompt/skill provenance, system-chat denial boundaries, and secret-free values.
- API and fragment checks verify scoped pairing, expected-version/expected-generation conflicts, unavailable/busy/not-ready states, escaping, sensitive-field omission, and stable independent host replacement.
- Delegated live Playwright validates changed agent-console surfaces at desktop and mobile widths, captures screenshots, reports visual quality for density, hierarchy, alignment, overflow, affordance, first-viewport usefulness, and mobile stacking, and confirms HTMX ownership plus the narrow SSE-only JavaScript rationale.
- Final diff/status review finds no placeholder, unrelated edit, migration rewrite, restored workspace abstraction, forbidden root/path write, credential leak, or unapproved API growth.

## Exit Criteria

All declared specifications, documentation, and changelog agree with verified observed behavior and the shared concepts. Every required validation result, including final integration-validator PASS, is recorded. Any discrepancy, failing check, unavailable required check, or out-of-scope repair has been routed to its owner and prevents this phase from being accepted. The closeout commit contains only the declared documentation/changelog changes for this phase and leaves the phase-plan file set unchanged.
