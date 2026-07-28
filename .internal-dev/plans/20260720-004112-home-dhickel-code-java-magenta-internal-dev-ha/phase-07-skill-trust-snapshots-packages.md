# Phase 07 — Immutable Skill Trust, Activation, and Control Plane

## Context

Phase 06 resolves digest-qualified candidates and exact assignment overlays, but candidates still point at mutable source content. This phase makes approved immutable snapshots the sole source of model-visible disclosure and activation, adds revocation and controlled package ingestion, keeps executable-resource authority separate, and exposes the Skills control plane.

## Goal

immutable trusted activation and its control-plane UI

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/skills/SkillTrustService.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillSnapshotRepository.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillActivationService.java, src/main/java/io/mindspice/magenta2/api/web/SkillController.java, src/main/java/io/mindspice/magenta2/api/web/SkillFragments.java

- Add migration 007 and fresh-schema equivalents for trust decisions, immutable snapshots and resources, activations, executable authorizations, package-ingestion operations, and resource executions.
- Cut runtime disclosure and activation over to exact approved snapshots.
- Add operator routes and reusable fragments for candidates, conflicts, trust, assignments, snapshots, controlled editing, packages, and executable capability.
- Add security, prompt-leakage, lifecycle, repository, controller, fragment, startup, and browser validation.

## Out of Scope

- Marketplace discovery, dependency resolution, or generalized plugin execution.
- Treating package source, scope, ownership, signature evidence, assignment, or `allowed-tools` as trust or permission authority.
- Cross-domain run/Files composition or exposing `.skills` through the Files browser.
- Enabling executable resources when Phase 03 did not prove the required process confinement.

## Dependencies

phase-03-reserved-tree-confinement.md, phase-06-scoped-skill-resolution.md

## Constraints

- Approval binds the exact Phase 06 candidate identity, provenance, canonical content digest, and immutable resource manifest. Approval re-reads confined source bytes and rejects a changed, missing, linked, or structurally invalid source before committing.
- Snapshot identity and digest use one documented canonical encoding over normalized relative paths, resource types, lengths, and bytes. Ordering is deterministic; absolute paths, traversal, duplicate normalized paths, case-colliding paths, links, devices, and unsupported entry types are rejected.
- Snapshot rows and resource bytes are immutable. A source edit creates a new candidate digest and pending decision; it never updates an existing snapshot or activation.
- Candidate names, descriptions, hints, bodies, and resource metadata are model-invisible until copied into an approved snapshot. Pending shadow candidates continue to suppress lower-precedence fallback without disclosing pending text.
- Assignment selection, instruction trust, snapshot activation, and executable authorization are separate checks. `allowed-tools` is display-only metadata.
- Revocation blocks every new activation. Only a same-run resume may rehydrate the exact snapshot from an activation already persisted for that canonical `taskRunId`; retry, replay, and any new run require current approval.
- Trust, revocation, package-ingestion, and package-install operations use an operation key and request digest. Reuse with the same digest returns the recorded result; reuse with different input conflicts.
- Package acceptance exposes validated installed content only as candidates. It does not approve instructions, activate a skill, or authorize an executable resource.
- Executable authorization remains unavailable unless Phase 03 recorded a proven confinement capability. No UI action, API default, assignment, or trust decision may bypass that verdict.
- Skill execution requires the exact approved snapshot and resource digest, explicit executable authorization, an active canonical run, current writer owner and fence, bounded arguments/environment/process policy, and persisted actor/run outcome audit.
- Filesystem locations remain root-relative under trusted layout/directory services. No absolute path is persisted and no workspace identity is inferred from `Path`.
- `.skills` remains hidden and structurally protected. Controlled editing uses the reserved-path service boundary and supports only the established flat workspace-skill shape.
- HTMX owns standard control-plane mutations and partial refreshes. Any JavaScript must be narrow, justified, and unrelated to transport.

## Implementation Steps

1. **Add persistence contracts and migration 007.** Inspect the Phase 01 migration owner and Phase 06 candidate keys, then add trust decisions, immutable snapshots, snapshot resources, activation records, executable authorizations, ingestion/install operations, and resource-execution audit. Add foreign keys and uniqueness for exact candidate identity/digest, snapshot digest, activation context, operation key, and exact resource authorization. Keep `schema.sql` equivalent and do not recreate migrated structures in repositories. Cover interrupted migration, rerun, checksum, and representative legacy database behavior.

2. **Implement digest-bound approval.** In `SkillTrustService`, accept the exact resolved candidate identity plus the caller-observed digest. Re-open the source through trusted confined locations, validate the tree, canonicalize and hash all approved content, and reject if either candidate identity or digest changed. Persist snapshot metadata/resources and the actor, reason, scope, decision, and digest as one logical operation; compensate or reconcile any filesystem/database split. Global policy remains explicit and digest-qualified.

   ```text
   approve(operationKey, candidateId, observedDigest):
     source = confinedReread(candidateId)
     manifest = canonicalManifest(source)
     require manifest.digest == observedDigest == candidateId.digest
     snapshot = insertImmutableSnapshot(manifest)
     recordDecision(APPROVE, candidateId, snapshot.id, actor, reason)
   ```

3. **Keep executable authority independent.** Model executable authorization as an exact snapshot-resource decision with actor, reason, state, and confinement capability. Instruction approval never grants shell, file, network, or process capability. Reject attempts to authorize a missing, non-executable, digest-mismatched, revoked, or unsupported resource; keep `allowed-tools` informational.

4. **Cut disclosure over to approved snapshots.** Update catalog construction so overlay resolution selects an exact candidate first, then trust resolution selects an approved immutable snapshot. Model-visible catalogs and tool schemas read only snapshot-owned name, description, and activation identifier. Pending/rejected/revoked candidates, conflict details, live source text, and live resource metadata remain absent. A pending higher-precedence shadow suppresses lower fallback without revealing pending content or a misleading trusted fallback.

5. **Activate only immutable approved snapshots.** In `AgentSkillActivationService`, verify exact assignment, resolved candidate, current approval, and snapshot integrity; persist the activation audit before returning instruction bytes or resource descriptors. Never re-read live skill files during activation. If audit persistence fails, return no content.

6. **Implement activation identity and revocation semantics.** Deduplicate by canonical activation context and snapshot, with uniqueness sufficient to make concurrent requests return one recorded activation. A same-run resume may return the exact previously activated snapshot after revocation only when the persisted activation belongs to that `taskRunId`. Internal attempts without a recorded activation, operator retry, replay, and new runs perform current trust checks and cannot inherit the exemption.

7. **Add control-plane mutations.** Add narrowly scoped endpoints for exact assignment; candidate and shadow-conflict inspection; approve, reject, and revoke; snapshot history; controlled flat-file create/update/delete; package ingest/install status; and executable capability decisions. Validate actor and exact IDs server-side, use operation keys on retryable mutations, return bounded fragments, and avoid representing the local/open-alpha posture as multi-tenant authentication. Controlled edits create a new candidate digest in pending state and cannot modify snapshots.

8. **Implement bounded package ingestion.** Stream archives into an application-owned staging location outside active skill roots. Enforce compressed and expanded byte limits, entry-count and per-entry limits, expansion-ratio limits, canonical relative paths, supported regular-file types, and duplicate/case-collision rejection before extraction. Reject absolute/traversal names, malformed names, links, devices, sparse or otherwise unsupported entries, and writes through replaced parents. Record request digest and validation evidence. After explicit package acceptance, use create-only final placement or an atomic no-replace equivalent; never merge into or replace an installed version. Reconcile staged-without-row, row-without-final-content, interrupted install, and final-content collision states. Installed content enters Phase 06 discovery as untrusted candidates.

9. **Gate executable resource execution.** Read the persisted Phase 03 confinement verdict first. When unavailable, reject authorization and execution and render a truthful disabled reason. When available, require the exact snapshot/resource digest, current executable authorization, non-revoked activation rules, active run, current writer owner/fence, and bounded process policy at the execution boundary. Persist actor, run, attempt where applicable, snapshot, resource, fence, start/result state, and unavailable exit facts without manufacturing values. Recheck authorization and fence immediately before launch; stale or revoked decisions fail closed.

10. **Expose activation without implicit execution.** Register `activate_skill` only when resolution produces an exact currently approved snapshot for the request context. Its schema and result contain snapshot-owned disclosure only. Activation may return authorized resource descriptors but never executes a script or grants a shell/file capability implicitly.

11. **Build reusable HTMX Skills fragments.** Implement `SkillFragments` for scope and provenance, digest/version, precedence, stale exact bindings, pending decisions, shadow conflicts, decision history, immutable snapshots, controlled editing, package operation state, and executable capability. Keep untrusted content confined to explicitly operator-only views and escape it as data. Use HTMX forms and OOB swaps for mutations, stable slot keys for reused components, bounded expansion/history, clear unavailable and error states, compact desktop/mobile layouts, and no broad JavaScript transport.

12. **Complete focused and adversarial validation.** Add repository/service/controller/fragment tests for approval races, canonical manifest stability, operation-key replay/conflict, concurrent activation dedupe, source mutation, snapshot immutability, exact assignment, shadow suppression, prompt and tool-schema leakage, revocation across resume/retry/replay/new-run, package attacks and reconciliation, authority separation, stale writer claims, resource digest changes, unavailable confinement, output audit, escaped operator content, and HTMX/OOB behavior. Repair all phase-owned failures before validator handoff.

## Edge Cases to Preserve

- Candidate source disappears, changes type, changes bytes with the same display version, or swaps a parent/link between discovery and approval.
- Two approvals race for the same candidate/digest; the result is one immutable snapshot and deterministic recorded decisions, not divergent copies.
- Unicode normalization, case-only names, reserved names, duplicate archive entries, very long paths, empty files, malformed text, huge lines, and binary resources cannot alias or bypass limits.
- A pending high-precedence candidate shadows an approved lower candidate without exposing pending text or silently activating the lower candidate.
- Revocation races activation: only a fully persisted pre-revocation activation may support same-run resume; partial or later activation fails closed.
- Snapshot resources remain reproducible after source deletion or editing; an integrity mismatch suppresses activation and execution and surfaces a safe operator error.
- Same operation key with the same request digest is idempotent; the same key with different candidate, archive, decision, or actor input conflicts.
- Package extraction cannot write through symlinks or replaced parents, overwrite an installed version, merge case-colliding entries, or leave staging content active after failure.
- Stale assignment, stale writer fence, inactive run, revoked executable decision, mismatched resource digest, or absent confinement prevents launch.
- Missing process exit facts remain unavailable rather than becoming success, failure, or zero.
- Error text, counts, labels, conflict rows, response attributes, and OOB fragments do not leak model-invisible candidate-derived content.

## Required Guides

- `AGENTS.md` and the nearest package `AGENTS.md` files for every changed package.
- Relevant architecture, service graph, services, API, web, and SimplyPages specifications.
- Skills, tool, API, migration, Agent Skills UI, package-ingestion, and shell-confinement knowledge relevant to the inspected code.
- SimplyPages HTMX/component, dynamic slot/render-context, and editing documentation plus the relevant demos before UI edits.

## Technical Guidance

- Snapshot at approval, never at activation. Activation is a read of immutable persisted bytes plus current audit state.
- Keep repository writes explicit enough to show the state machine: candidate decision, snapshot commit, activation commit, package staging/install, and resource execution. Do not hide cross-domain compensation behind a generic transaction abstraction.
- Reuse Phase 06 exact candidate and assignment identities and Phase 03 capability/fence contracts; do not introduce alternate path, trust, or execution identities.
- Keep operator inspection separate from model disclosure. The control plane may show safely escaped pending content only where explicitly required for a trust decision; catalog and activation services must have no pending-content fallback.
- Controlled edits and accepted packages produce candidates. They do not mutate snapshots or skip the approval path.

## Validation

- Run focused migration, trust, snapshot, catalog, activation, package, executable-resource, controller, and fragment suites through the required `gpt-5.3-codex` medium-reasoning test subagent.
- Mutate, delete, rename, and link-swap source content across discovery, approval, activation, resume, and execution boundaries.
- Inject failures after each persisted package and snapshot transition; prove reconciliation converges or reports a safe unresolved state with no active partial content.
- Exercise concurrent approvals and activations, operation-key replay/conflict, revocation races, resume, internal attempt, operator retry, replay, and new-run behavior.
- Inspect rendered prompts and tool schemas to prove that no pending, rejected, revoked, shadowed, or otherwise untrusted derived metadata appears.
- Test package traversal, absolute paths, duplicate and case-colliding names, malformed archives, links/devices, parent replacement, oversized entries, aggregate limits, expansion ratio, final collision, and interrupted installation.
- Test executable authorization and launch with available and unavailable confinement, stale assignment/writer fences, inactive runs, revoked decisions, resource digest mismatch, and missing exit facts.
- Run focused Playwright on a running application for Skills management on desktop and mobile, capturing screenshots and an explicit visual critique of hierarchy, density, alignment, wrapping, overflow, controls, first-viewport usefulness, and mobile stacking. Confirm CRUD and partial refreshes are HTMX-first and record any narrow JavaScript justification.
- Run the broader Maven suite and bounded Spring startup. Treat missing required execution infrastructure as a reported blocker under repository policy, not as a unit-only pass.
- Have the required `openai-codex/gpt-5.6-sol` medium-reasoning validator review evidence, repair phase-owned defects, and report PASS.

## Exit Criteria

- Model-visible skill names, descriptions, instructions, hints, and resources originate only from exact approved immutable snapshots; leakage tests cover prompts, tool schemas, fragments, errors, and shadow conflicts.
- Approval is digest-bound and race-safe; snapshots and resources are immutable, reproducible after source changes, and auditable by actor, reason, scope, provenance, and digest.
- Assignment, instruction trust, activation, and executable authorization remain distinct in persistence, services, routes, and UI.
- Concurrent activation is idempotent; revocation blocks new activation; only an already recorded same-run activation supports resume after revocation; retry, replay, and new runs require current approval.
- Package ingestion is bounded, idempotent, create-only at final placement, recoverable after injected failures, and yields untrusted candidates rather than implicit approval.
- Executable resources remain disabled without proven Phase 03 confinement and otherwise require exact authorization, active run, current writer fence, resource digest, bounded policy, and complete audit.
- Controlled edits create new pending candidates, `.skills` remains absent from Files, and no live edit mutates a snapshot.
- Skills management is truthful, reusable, responsive, escaped, bounded, and HTMX-first on desktop and mobile.
- Focused tests, adversarial and recovery tests, Maven suite, bounded startup, required browser evidence, and final diff review pass with no unreported blocker.
- Post-phase validator review-and-repair reports PASS.
