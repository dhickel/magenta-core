# Phase 04 — Prompt and Skills Context

## Context

Phase 03 supplies immutable context bindings. This phase makes those facts non-replaceable at prompt assembly, confines human-authored guidance, and replaces generic project-root skill authority with the exact captured materialization and generation. It does not create a workspace kind, root, alias, path authority, executable skill capability, or UI surface.

## Goal
inject trusted binding policy and generation-bound untrusted guidance and skills

## In Scope
**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssembler.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/PromptContextAssembler.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentsMdResolver.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceMdResolver.java, src/main/java/io/mindspice/magenta2/ai/skills/MaterializationSkillAuthority.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillResolutionContext.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillScopeDiscoveryService.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillTrustService.java

One implementation agent owns the listed changes and focused evidence. It consumes passed Phase 03 bindings; it does not add schema, controller, fragment, package-guide, or dependency changes.

## Out of Scope

Trust derived from project membership or association; executable resources or package ingestion; prompt text as authorization; UI fragments; workspace identity, roots, descriptors, aliases, links, mounts, or leases; and disclosure of internal locations.

## Dependencies
phase-03-immutable-binding-lifecycle.md

## Constraints

- The fixed prompt provenance order is immutable Magenta policy, trusted captured binding and derived allowed-path facts, mode/task intent, labelled untrusted `AGENTS.md` and `WORKSPACE.md` guidance, then approved immutable skill snapshots. Allowed write/tool paths come only from the independently selected execution descriptor and current lifecycle capability; materialization facts remain supplemental read-only context. Ordering conveys provenance; it grants no authority.
- NORMAL, PLAN, TASK, and execution modes all retain immutable policy and trusted binding facts. Mode or worktype text may add intent but never replace an earlier segment.
- A missing or non-ready/revoked/stale binding or materialization, owner/generation/location mismatch, trusted descriptor-resolution failure, invalid active target, or guidance integrity failure is terminal before model or tool use.
- Only an absent optional `AGENTS.md` layer or absent optional root `WORKSPACE.md` resolves to no guidance. Read, UTF-8, link, regular-file, confinement, limit, or other integrity failures do not degrade to absence.
- Prompt facts, diagnostics, logs, and test-visible output contain no secret, credential-bearing URL, absolute path, internal endpoint, claim owner, fence, token, or raw failure. Guidance truncation markers are path-free.
- `AGENTS.md`, `WORKSPACE.md`, and skill source are untrusted and visibly labelled. `WORKSPACE.md` is human guidance only and is never parsed as configuration. User/task instructions retain their established precedence over untrusted guidance.
- `AGENTS.md` remains confined to the independently selected execution descriptor and ancestor-inclusive from that descriptor root to the service-supplied active target. Root-to-leaf layers remain active; the closest layer wins only on conflicts. It does not switch to a supplemental materialization root.
- `MaterializationSkillAuthority` is a trusted value, not a persisted or caller-supplied path. It binds owner, materialization id, captured generation, materialization kind, and trusted root-relative identity. Projects remain attribution and assignment metadata only.
- Discovery, assignment, approval, disclosure, activation, and executable authority remain separate. Only approved immutable snapshot bytes are model-visible or activatable. Revocation blocks new activation, global and agent overlays remain intact, and executable resources/package ingestion remain unavailable.

## Implementation Steps

1. Build `TrustedContextPolicyAssembler` from the immutable binding and trusted descriptor/materialization resolution supplied by Phase 03. Derive only bounded identity, generation, workflow, and context facts. Derive allowed-path and write/tool-limit facts exclusively from the selected execution descriptor plus current lifecycle capability, never from a materialization. Resolve each trusted input once through its owning service; do not accept a caller root, reconstruct identity from a path or string, or expose a resolved path. Return a typed terminal result for missing, stale, revoked, non-ready, mismatched, or otherwise unresolvable trusted context.
2. Compose immutable policy and captured facts before mode/task intent and prevent PLAN, TASK, worktype, or execution text from replacing them. Prompt assembly must consume the typed policy result before any model/tool preparation and propagate a terminal result through the existing turn-assembly boundary without catch-and-continue behavior. Then append labelled untrusted guidance and approved snapshot-only skill material in fixed order. Only genuinely absent optional guidance contributes an empty segment.

   ```text
   trusted-policy -> trusted-binding-facts -> mode/task-intent
                  -> untrusted-guidance -> approved-snapshots
   ```

3. Preserve confined root-to-target `AGENTS.md` layering, but change resolution to receive the trusted selected execution descriptor and the active path already normalized by confined tool services. Do not use the supplemental materialization root, host-root strings, or independent traversal from prompt assembly. For every candidate layer, reject links, non-regular files, escape, invalid UTF-8, and unsafe active-path ancestry; read bounded bytes with per-layer and aggregate limits. Emit root-to-leaf untrusted labels, preserve closest-layer conflict precedence, and use explicit path-free truncation markers. Missing files are skipped; any discovered-file integrity or I/O failure is terminal.
4. Add one `WorkspaceMdResolver` for the single root selected by the immutable captured context: the authorized materialization root when the binding captures a materialization, otherwise the captured personal-area root (`home`, `scripts`, or `apps`). A materialization may be a valid empty local context container; an absent `WORKSPACE.md` then contributes no guidance without implying failed acquisition. Do not scan both roots, ancestors, siblings, associations, or guessed project locations, and do not fall through from an invalid selected root. Read only a bounded UTF-8 regular `WORKSPACE.md` without links or escape, label it human-authored untrusted guidance, and never parse it as settings, policy, tool permission, or authority.
5. Replace `ProjectRootAuthority` with `MaterializationSkillAuthority` in `SkillResolutionContext`. Keep project id only for assignment specificity and attribution. Make the authority contain the captured owner, materialization id, generation, kind, and root-relative identity required to re-resolve the exact materialization; reject null, CHAT, malformed, or internally inconsistent facts at construction. No skill service may recover authority by parsing `project/<id>`, a workspace identity string, a filename, display name, URL, or `Path`.
6. Resolve materialization skill authority through the materialization and directory services on every use. Verify owner, materialization id, captured generation, lifecycle readiness, revocation state, kind, and root-relative location against the immutable binding before opening `.skills`. A mismatch, stale generation, missing location, revoked/non-ready state, or confinement failure is typed unavailable or terminal as appropriate and never silently substitutes the effective workspace, bound-agent root, project metadata, or a lower-precedence root.
7. Discover materialization-scoped `.skills` only through the exact permitted captured authority; preserve existing effective-workspace, distinct bound-agent, and global overlays without granting them materialization authority. Keep precedence explicit: an observed higher-precedence candidate that becomes stale, unavailable, malformed, disabled, or otherwise occupies its logical key blocks lower-precedence disclosure for that key. Do not fabricate candidates for an absent root, disclose candidate-derived content from non-approved states, create missing `.skills`, or scan chat, task-run, process-home, working-directory, or guessed roots.
8. At approval, re-resolve the candidate’s materialization authority and re-open the source through the same confined authority path before parsing or snapshotting. Verify candidate digest, observed digest, and canonical reread manifest digest all match; reject changed, missing, linked, malformed, escaped, stale-generation, wrong-owner, or wrong-location source. Retain immutable authority facts required to repeat that verification, preserve canonical operation-key/request-digest convergence and contradictory-reuse conflict, then persist immutable snapshot bytes and resources only after validation. Preserve existing global/agent overlay, assignment, rejection, revocation, activation, and same-run snapshot rehydration behavior; do not make instruction approval executable authority.

## Required Guides

- `AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/knowledge/agents-md-specification-reference.md`
- `.internal-dev/knowledge/scoped-skill-trust-snapshots.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/skills/AGENTS.md`

## Technical Guidance

Keep filesystem authority at the descriptor, materialization, and directory-service boundary. `PromptContextAssembler` orchestrates typed results; it neither resolves roots nor turns resolver failures into empty strings. `TrustedContextPolicyAssembler` is the only new source of trusted prompt facts. Resolver results carry bounded content plus provenance labels, never raw filesystem locations.

Keep source reread and snapshot creation on the trust-service path. A candidate id, logical key, digest, assignment, or project association alone is insufficient to reopen a materialization-scoped source. Revalidation must use the captured authority facts, then apply the existing manifest framing, immutable snapshot, revocation, and activation rules.

## Validation

- Run focused prompt tests for NORMAL, PLAN, TASK, and execution composition; assert exact provenance order, non-replaceability, labels, bounded/path-safe facts, and no model or tool invocation after every terminal trusted-resolution outcome.
- Run resolver tests for root-only, nested, sibling-target change, root-to-leaf conflict precedence, absent optional files, per-layer and aggregate limits, path-free truncation, traversal, encoded separator, absolute path, link, non-regular file, invalid UTF-8, unreadable source, and active-target escape. Verify that only absence is empty and every integrity failure is terminal.
- Run skill discovery and trust tests for wrong owner, non-ready/revoked materialization, stale generation, root-relative location mismatch, generic project association without authority, no `.skills` creation, materialization-only discovery, higher-precedence unavailable/stale occupancy, and no model disclosure from pending, malformed, rejected, changed, or revoked candidates.
- Run approval race tests in which discovery is followed by generation change, owner change, source replacement, link insertion, location change, digest change, or deletion. Assert no snapshot is created unless authority and all three digests agree; assert operation-key convergence for identical requests and conflict for contradictory reuse.
- Run affected Maven tests, bounded application startup, forbidden-construct scans for project-root authority, path-derived workspace reconstruction, host-root prompt resolution, and executable-skill enablement, plus secret/redaction scans. The post-phase validator repairs only in-scope defects and records PASS.

## Exit Criteria

- Every supported turn receives immutable trusted binding/security context in the fixed order or terminates before model and tool use.
- `AGENTS.md` comes only from the selected execution descriptor hierarchy, while `WORKSPACE.md` comes only from the immutable captured context root. Both are confined, bounded, UTF-8 validated, visibly untrusted, and never silently omitted after an integrity failure.
- Materialization-scoped skill discovery and approval revalidate exact owner, materialization, generation, kind, and root-relative identity; project metadata grants no filesystem or skill authority.
- Model disclosure and activation use only approved immutable snapshots; revoked, stale, malformed, unavailable, and unapproved candidates remain non-disclosable, and executable capability remains unavailable.
- Focused validation, bounded startup, required scans, and post-phase validator PASS are recorded without unlisted product, API, UI, schema, or documentation work.
