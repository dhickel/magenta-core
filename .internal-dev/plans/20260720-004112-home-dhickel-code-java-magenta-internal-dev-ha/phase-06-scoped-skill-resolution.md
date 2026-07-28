# Phase 06 — Normalized Scoped Skill Discovery and Exact Assignment

## Context

The current global package catalog and logical-name assignments cannot represent workspace-authored flat skills, provenance-qualified immutable candidate versions, deterministic shadowing, or exact assignment overlays. This phase builds the confined control-plane discovery and resolution foundation only. It does not expand model-visible disclosure or activation; Phase 07 owns approved snapshots, trust, activation, package ingestion, executable authorization, and the Skills UI.

## Goal

deterministic scoped candidates and exact-version assignment

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/skills/SkillScopeDiscoveryService.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillResolutionService.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillCatalogService.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentRepository.java

- Define normalized skill format, scope, provenance, authoritative root reference, immutable candidate version, resolution context, assignment target, and conflict data.
- Add ordered migration 006, fresh-schema parity, candidate-version persistence, exact-version assignment overlays, availability state, and non-destructive legacy diagnostics.
- Adapt global package skills and flat workspace Markdown skills into one deterministic control-plane catalog.
- Keep candidate-derived descriptions, hints, bodies, resource metadata, conflicts, and assignment details out of model-visible catalogs and prompts.
- Add parser, digest, discovery, resolution, assignment, migration, confinement, and disclosure-boundary tests.

## Out of Scope

- Trust approval, revocation, immutable approved snapshots, activation cutover, package installation, executable-resource authorization, scripts, and UI.
- Inventing a project filesystem root or scanning any guessed project location.
- Changing prompt assembly to disclose newly discovered candidates.
- Replacing native workspace identity or constructing workspace identity from a filesystem path.

## Dependencies

phase-03-reserved-tree-confinement.md

## Constraints

- Discovery is read-only and does not create absent `.skills` roots. An explicit control-plane create operation may request a confined `.skills` location through workspace services; discovery itself may not.
- Discover only from the effective non-chat `AGENT` or `USER` workspace, a physically distinct bound-agent workspace, a project root only when an existing authoritative project-root contract is proven, and the global repository.
- Never scan chat workspaces, task-run directories, service-account homes, process working directories, environment-derived homes, or guessed project directories.
- Obtain workspace roots and child locations from trusted workspace services and `WorkspacePathLayout`; never infer workspace kind, key, ownership, or scope from `Path`.
- Candidate-version identity includes logical key, authoritative scope/root identity, provenance, format version, and content digest. Display names and filenames are not identity.
- Persist root-relative paths where a path reference is required; never persist an absolute candidate, package, skill, or workspace path.
- An assignment binds exactly one immutable candidate version and never implies trust, approval, disclosure, activation, or executable authorization.
- A content or package-tree digest change creates a new candidate version. Existing assignments become stale or unavailable and never auto-rebind.
- A higher-precedence discovered candidate occupies its logical key even if malformed, pending, untrusted, stale, or unavailable for loading; do not fall through to a lower candidate. Surface the condition as control-plane resolution data.
- Candidate-derived untrusted descriptions, hints, bodies, resource metadata, conflict text, and assignment metadata never enter model-visible output. Preserve existing disclosure behavior until Phase 07 cuts it over to approved snapshots.
- Malformed, colliding, oversized, unsupported, or unsafe sources may be recorded with bounded diagnostics but are not loadable.
- Candidate history and legacy assignment data are non-destructive. Observation updates availability; it does not delete historical versions.

## Implementation Steps

1. Add ordered migration 006 through the sole migration owner established in Phase 01 and update the fresh schema to the same end state. Persist immutable candidate versions, current observations/availability, exact-version assignment overlays, and legacy conversion diagnostics with uniqueness and foreign-key constraints. Preserve old tables until Phase 07 cutover. Add migration checksum, rerun, interruption/restart, partial-legacy-state, and concurrent-startup coverage.
2. Normalize global package `skill-slug/SKILL.md` and flat workspace `.skills/<filename>.md` sources into one candidate representation. Constrain optional frontmatter to the accepted fields and types; derive bounded safe fallback values without interpreting active content. Record malformed candidates with stable bounded diagnostics, but do not make them loadable or model-visible.
3. Digest exact flat-file bytes and package trees using a documented canonical algorithm: validate relative paths, reject links and non-regular entries, normalize and sort canonical relative entry names, frame path and byte lengths unambiguously, and hash every included byte. Reject traversal, duplicate normalized paths, case/Unicode collisions, invalid encoding where text parsing is required, dangerous metadata, and configured per-file, total-byte, entry-count, and depth violations. Lock digest test vectors.
4. Add separate read-only and explicit control-plane-create `.skills` location resolution for `AGENT` and `USER` through trusted workspace authorities. Read-only lookup returns empty for an absent root and performs no filesystem mutation. Reject `CHAT` and any untrusted descriptor or child path. Recheck confinement at directory open and file read boundaries using the protections established in Phase 03.
5. Discover roots in exact precedence order: effective non-chat workspace; physically distinct bound-agent workspace; authoritative project root only when repository inspection proves an existing trusted authority; global repository. If no project-root authority exists, project discovery remains disabled and is reported as unavailable rather than guessed. Deduplicate existing physical roots before scanning, retain the highest-precedence authorized scope reference, and never let physical deduplication introduce an unauthorized root.
6. Persist each observed immutable candidate version and its provenance without deleting history. Mark previously observed versions unavailable when a completed scan of their authoritative root no longer sees them; do not mark versions unavailable after a failed or incomplete scan. Make repeated identical observations idempotent and ensure diagnostics cannot contain unbounded source content or absolute paths.
7. Support assignment targets `AGENT`, `PROJECT`, `JOB`, `TASK`, `WORKFLOW`, and `CHAT_SESSION` using canonical trusted target identifiers. Resolve specificity in this order: chat session; task or workflow; job; project; agent. At the same specificity, require at most one applicable overlay for a logical key and context; treat conflicting persisted rows as explicit conflicts rather than selecting by timestamp. The most-specific explicit enable or disable wins.
8. Resolve root precedence independently from assignment specificity. For each logical key, the first occupying candidate by root precedence is the only candidate eligible for an overlay; pending, malformed, stale, or unavailable occupancy suppresses lower-root fallback. Return shadow, duplicate-key, equal-specificity, stale-binding, unavailable, malformed, and disabled outcomes as typed control-plane data instead of overwriting map entries or silently falling back.
9. Write overlays against the immutable candidate-version identity, including provenance, digest, target type/key, actor, enabled state, and timestamps. Validate that the candidate and target are known and compatible with the trusted resolution context. Make same-intent writes idempotent where the existing repository contract supplies an operation key; reject contradictory reuse. A new digest never mutates or retargets an existing overlay.
10. Convert a legacy global agent assignment only when its agent and logical key resolve to exactly one unambiguous global candidate version under the migration rules. Preserve the legacy row, record the conversion result, and emit bounded unmatched or ambiguous diagnostics without guessing, deleting, or broadening scope. Rerunning conversion must not duplicate overlays or diagnostics.
11. Build complete resolution context from trusted run, assignment, workflow, job, project-membership, agent, or chat/session metadata. Preserve the distinction between effective workspace and bound agent. Do not infer any identity, project membership, or target key from a path, filename, display name, or current process state. Reject incomplete or contradictory contexts before discovery or assignment resolution.
12. Test root convergence, physical-root deduplication, exact precedence, explicit enable and disable, equal-specificity conflicts, stale and unavailable bindings, duplicate logical keys, malformed flat files, package and flat digest stability, all configured bounds, absent roots, no auto-create, forbidden roots, chat rejection, symlink and replacement attacks, disabled project discovery, failed-scan availability preservation, legacy conversion idempotency, and negative model-disclosure behavior.

## Required Guides

- `AGENTS.md` and the nearest package `AGENTS.md` files for every changed source or test package.
- `.internal-dev/AGENTS.md` and `.internal-dev/specifications/AGENTS.md`.
- Relevant architecture, service, API, workspace, skill, migration, and decision specifications.
- `.internal-dev/knowledge/agent-skills-specification-reference.md`.
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md`.
- Any task-relevant knowledge file found by filename search; do not scan the store broadly.

## Technical Guidance

Use separate deterministic comparators for root precedence and assignment specificity. Do not encode either rule through incidental query order or map insertion order.

A candidate-version key is conceptually:

```text
(logicalKey, scopeType, authoritativeRootId, provenance, formatVersion, sha256)
```

`authoritativeRootId` is a stable trusted identifier, not an absolute path. Resolution should follow this order:

```text
scan each authorized physical root once
retain its highest-precedence authorized scope reference
group candidates by logical key
select the first occupying root candidate
apply the most-specific exact-version overlay
return a typed resolved, disabled, stale, unavailable, malformed, or conflict result
```

Keep persistence rows free of source bodies unless the existing specification explicitly requires bounded storage. Parsing and diagnostics must not make candidate content visible to prompt-building code. Where `AgentSkillCatalogService` currently feeds model-visible data, preserve the pre-phase behavior and expose normalized candidate resolution only through a control-plane boundary that Phase 07 can cut over after snapshot approval.

Use stable enum values and explicit tie-breakers. Database queries must specify ordering, but correctness must also be enforced in service logic and uniqueness constraints. Never use last-write-wins to conceal invalid equal-specificity overlays or duplicate candidates.

## Validation

- Run focused parser, canonical digest, candidate persistence, discovery, resolution, overlay, legacy conversion, and migration tests through the required `gpt-5.3-codex` test subagent at medium reasoning.
- Validate flat skills for an agent workspace and an explicitly declared user workspace without creating absent roots; verify `CHAT` rejection and disabled project discovery when no authority exists.
- Verify global package behavior through the normalized control-plane catalog, including stable package-tree digest vectors and deterministic shadow outcomes.
- Exercise traversal, links, directory replacement, duplicate normalized paths, case/Unicode collisions, malformed encoding/frontmatter, oversized files, excessive package counts/depth, incomplete scans, and forbidden-root attempts.
- Prove assignment specificity, explicit disable, equal-specificity conflict, exact-version binding, digest-change staleness, unavailable-version behavior, and no lower-root fallback for an occupying pending or malformed candidate.
- Inspect persisted legacy conversion diagnostics and prove reruns are idempotent and non-destructive.
- Add negative tests showing candidate-derived untrusted descriptions, hints, bodies, resource metadata, conflicts, and overlays do not enter model-visible catalogs or prompts.
- Run the broader Maven suite and bounded Spring Boot startup. Distinguish unrelated pre-existing failures with evidence; do not mark the phase complete with a blocking startup or execution dependency.
- Have the required `openai-codex/gpt-5.6-sol` validator at medium reasoning review evidence, repair defects, and report PASS before completion.

## Exit Criteria

- Global package and flat workspace skills share one deterministic, confined candidate and resolution pipeline.
- Candidate-version identity and assignment overlays bind exact provenance, authoritative root identity, format version, and digest without implying trust or activation.
- Root precedence, physical deduplication, assignment specificity, stale bindings, disabled outcomes, and conflicts are deterministic and represented as typed data.
- Absent roots remain absent during discovery; forbidden roots are never scanned or created; project discovery remains disabled without an existing trusted authority.
- Historical candidate versions and legacy assignments are preserved with idempotent bounded diagnostics.
- No newly discovered candidate-derived metadata or content reaches model-visible disclosure or activation.
- Migration 006 is restart-safe, checksum-governed, idempotent, and equivalent to the fresh schema.
- Focused tests, the Maven suite, bounded startup, and required security and disclosure-boundary cases pass.
- Post-phase validator review-and-repair reports PASS.
