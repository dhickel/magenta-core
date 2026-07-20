# Agent Skills (Phases 02-06 Backend/API/UI/Closeout)

This document captures the implemented Agent Skills foundation from Phase 02 through Phase 06 closeout.

## Scope Boundary

- **Implemented in Phase 02:** root `skills/` repository resolution, `SKILL.md` parsing/validation diagnostics, skill discovery/catalog metadata persistence, optional directory visibility flags, path confinement checks, and refresh-after-edit hashing.
- **Implemented in Phase 03:** DB-backed agent skill assignments, runtime catalog filtering by assigned+enabled+loadable skills, prompt-time catalog disclosure, dedicated `activate_skill` loading with resource listing, and activation deduplication per conversation.
- **Implemented in Phase 04:** `/api/skills` list/detail/refresh/create/diagnostics routes, root-confined file tree/view/save/create routes, assignment add/remove/list routes, and minimal `/skills` shell/fragments for phase-05 UI handoff.
- **Implemented in Phase 05:** full `/skills` operational browser/editor with list/filter/detail, diagnostics, directory overview, file viewer/editor, add-file flow, optional top-level directory creation, guided scaffold creation, and HTMX assignment controls.
- **Implemented in scoped-resolution Phase 06:** read-only flat `.skills/<name>.md` discovery for effective AGENT/USER and distinct bound-agent workspaces, normalized global-package candidates, immutable digest-qualified versions, exact AGENT/PROJECT/JOB/TASK/WORKFLOW/CHAT_SESSION overlays, and explicit malformed/stale/unavailable/disabled/conflict/shadow outcomes. Project discovery remains disabled unless a trusted authority is explicitly supplied.
- **Implemented in trust/snapshot Phase 07:** digest-bound approve/reject/revoke decisions, canonical confined manifests, immutable snapshot/resource bytes, snapshot-only model-visible activation, persisted activation rehydration, executable authorization records, and HTMX trust/snapshot fragments.
- **Out of scope/unavailable:** user-home/client-native scopes, guessed project roots, general registry ingestion, and executable resources when process-confinement capability is unavailable.

## Repository Root

MVP uses a Magenta-owned repository rooted at:

```text
MagentaRootProperties.path()/skills
```

This is an intentional client policy choice. Agent Skills format is still aligned with official `SKILL.md` structure and optional directories.

## Skill Record Contract

Persisted metadata currently includes:

- `name`, `directory_slug`, `description`
- optional `license`, `compatibility`, `metadata`, experimental `allowed-tools`
- `skill_root_relative_path`, `skill_md_root_relative_path`
- `status`, `diagnostics_json`
- `has_scripts`, `has_references`, `has_assets`
- `content_hash`, `discovered_at`, `last_scanned_at`, `created_at`, `updated_at`

Phase 03 adds legacy `agent_skill_assignments` metadata. Migration 004 adds control-plane `skill_candidate_versions`, `skill_candidate_observations`, `skill_exact_assignments`, and bounded legacy-conversion diagnostics. Exact overlays bind a persisted immutable candidate id and support AGENT, PROJECT, JOB, TASK, WORKFLOW, and CHAT_SESSION target identifiers. Task and workflow overlays share one specificity level so contradictory applicable rows surface as a conflict.

## Service Ownership (Implemented)

Current ownership boundary:

- `AgentSkillRepositoryService`: root path resolution and confined skill-path access.
- `AgentSkillParser`: frontmatter/body extraction, YAML parse fallback, validation, and stable diagnostics.
- `AgentSkillCatalogService`: discovery scan, malformed isolation, optional-directory flags, and metadata refresh persistence.
- `AgentSkillRepository`: SQLite persistence for discovered skill metadata and diagnostics.
- `AgentSkillAssignmentRepository` + `AgentSkillAssignmentService`: assignment persistence and lookup independent from tool approval metadata.
- `AgentSkillRuntimeCatalogService`: assigned/enabled/loadable catalog construction for the effective agent context.
- `AgentSkillPromptCatalogAssembler`: concise `available_skills` prompt section injection.
- `AgentSkillActivationService`: dedicated activation lookup, assignment checks, skill-body loading, resource listing, and dedupe tracking.
- `AgentSkillManagementService`: API-facing skill lookup, diagnostics, root-confined file operations, skill creation scaffold, and status-classified route-safe errors.
- `SkillTrustService`: revalidates confined candidate identity/digest, records decisions, creates immutable snapshots, and handles revocation without trusting live source bytes after approval.
- `SkillSnapshotRepository`: persists immutable resources, trust decisions, activations, executable authorizations, package operations, and resource execution audit.
- `SkillFragments`: server-rendered `/skills` shell and HTMX fragments for catalog browsing, guided creation, file editing, optional-directory creation, assignment controls, trust decisions, and snapshot history.

Chat/web/API layers consume this domain surface; they do not own direct parser/discovery logic.

## Parser/Discovery Validation Behavior

Current behavior:

1. Discovery scans immediate child directories under root `skills/`.
2. A valid or warning skill is persisted with metadata and diagnostics.
3. Malformed skills are persisted as `INVALID` with diagnostics and never crash refresh.
4. Optional `scripts/`, `references/`, and `assets/` are flagged by existence only; resources are not loaded.
5. Refresh recomputes hash/metadata and preserves first-discovered timestamp.

Lenient validation currently follows the client implementation guide:

- directory/name mismatch and name-shape issues warn but load,
- missing description or unparseable YAML mark the skill invalid/skip for runtime use.

## API and Fragment Contract (Implemented)

Implemented surfaces:

- `GET /api/skills`, `POST /api/skills/refresh`, `POST /api/skills`
- `GET /api/skills/{skillName}`, `GET /api/skills/{skillName}/diagnostics`
- `GET /api/skills/{skillName}/files`, `GET /api/skills/{skillName}/files/view`
- `PUT /api/skills/{skillName}/files/text`, `POST /api/skills/{skillName}/files`
- `GET /api/skills/{skillName}/assignments`
- `POST|DELETE /api/skills/{skillName}/assignments/agents/{agentId}`
- Candidate/trust/snapshot/activation APIs: `/api/skills/candidates/{logicalKey}`, `/trust/approve|reject|revoke`, `/trust/decisions/{logicalKey}`, `/snapshots/{logicalKey}`, `/snapshots/{snapshotId}/resources`, `/activate`, `/exec-auth/{logicalKey}`, and exact `/overlays` mutation.
- Candidate/snapshot collections, candidate history, exact overlay listing, activation history, and the approved snapshot catalog query persisted control-plane repositories. Package ingest returns `503 UNAVAILABLE` and accepts no archive; executable authorization reports confinement unavailable.
- HTMX-facing shell/fragments:
  - `/skills`
  - `/skills/_list`
  - `/skills/_refresh`
  - `/skills/_create`
  - `/skills/_detail/{skillName}`
  - `/skills/_detail/{skillName}/refresh`
  - `/skills/_files/{skillName}`
  - `/skills/_viewer/{skillName}`
  - `/skills/_directories/{skillName}`
  - `/skills/_assignments/{skillName}`
  - `/skills/_trust/{logicalKey}` and trust mutation routes
  - `/skills/_snapshots/{logicalKey}`

Status behavior for file/assignment routes is explicit:

- `400` invalid input/path format
- `404` missing skill/path/agent
- `409` collision or write conflict
- `415` unsupported text/binary operation

Path safety rules:

- no absolute paths
- no traversal outside skill root
- no symlink path segments or symlink escapes
- no binary/script execution routes
- all file operations confined to `<magenta-root>/skills/<skill>/...`
- browser optional-directory creation is limited to top-level `scripts/`, `references/`, and `assets/`

## Browser UI Contract (Implemented)

`/skills` uses the operational shell and side navigation shared with `/plans`, `/jobs`, `/projects`, and related surfaces. The browser is HTMX-first:

- filter and refresh replace the skill list fragment;
- row selection replaces the detail fragment;
- file table navigation and viewer selection replace only their target fragments;
- `SKILL.md` editor saves and detail refresh/revalidate actions re-render the selected detail plus an out-of-band list refresh;
- add-file actions re-render the selected skill detail;
- guided creation returns the new detail plus an out-of-band list refresh;
- assignment/unassignment updates the assignment panel plus an out-of-band list refresh.

The assignment form reuses the shared `EntitySelectorComponents` agent selector. Trust decisions bind an exact observed digest; the UI shows snapshot history and revocation independently from assignment. No custom JavaScript was added for skills; standard SimplyPages/HTMX behavior and the existing selector validation hook cover the interaction.

See [Skill API](../api/skills.md) for payloads and truthful unavailable capability states.

The page intentionally avoids project-local skill claims, user-home scope claims, layered assignment controls, and browser-driven script execution.

## Trust, Snapshots, And Execution Safety

Approval is bound to the exact candidate identity and digest after a confined source re-read. Snapshot manifests and resource bytes are immutable. A source edit creates a changed-digest candidate and cannot mutate an approved snapshot. Pending, malformed, rejected, changed-digest, or revoked candidates disclose no candidate-derived model content.

Activation reads approved snapshot bytes only and persists the snapshot identity used by a task run. Revocation blocks new activation while same-run resume may rehydrate an already persisted activation. Instruction approval and executable authorization are separate records. Because host process confinement is not proven, executable authorization and package/resource execution fail closed; no browser affordance should imply otherwise. Project scope is disabled unless an explicit trusted project authority is supplied, because projects own no filesystem root.

## Runtime Catalog And Activation Contract (Implemented)

- Runtime catalog is metadata-only and contains `name` + `description` for assigned+enabled+loadable skills.
- Unassigned, disabled, invalid, malformed, or missing skills are hidden from the runtime catalog.
- If no skills are available for the conversation/agent context, prompt catalog disclosure is omitted.
- `activate_skill` is hidden from mode-filtered tool exposure when no available skills exist for that conversation.
- Dedicated activation returns **body-only** instructions (frontmatter stripped) wrapped in `<skill_content ...>` with:
  - skill directory hint (`skills/<slug>`)
  - `<skill_resources>` listing for files under `scripts/`, `references/`, and `assets/`
  - no eager resource file reads
- Repeated activation of the same skill in the same conversation returns `ALREADY_ACTIVE` and does not re-inject duplicate content.

## Validation Evidence

Phase 02-06 evidence:

- Parser/discovery/path-confinement service tests.
- Assignment/catalog/activation tests.
- Phase-04 controller tests for success and required negatives:
  - malformed skill remains listed with diagnostics
  - `SKILL.md` save refreshes catalog metadata
  - traversal/symlink path attempts are rejected
  - unknown agent/skill assignment fails safely
  - duplicate assignment is idempotent
- Phase 05 controller/rendering tests for shell/nav, list filtering, diagnostics, directory overview, `SKILL.md` editing, optional directory creation, add-file flow, assignment selector updates, and guided scaffold creation.
- Focused skill-domain tests in Phase 06 closeout: `mvn -Dtest='*AgentSkill*Parser*,*AgentSkill*Validation*,*AgentSkill*Catalog*' test` (15 tests, 0 failures).
- Full integration gate in Phase 06 closeout: `mvn test` (888 tests, 0 failures, 0 errors, 0 skipped).
- Startup gate in Phase 06 closeout: `timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0` started successfully on ephemeral port `36065` and shut down gracefully on timeout.
- Browser validation evidence reconciliation in Phase 06 closeout:
  - first Playwright runs had false negatives from broad row targeting, wrong reference-file path setup, and HTMX swap timing in the validation script;
  - final browser evidence is the reconciled repo-local artifact set at `artifacts/playwright/agent-skills-phase-05-revalidation/`, especially `summary.json`, `validate.mjs`, and `app.log`;
  - older failed/timeout artifacts under `artifacts/playwright/agent-skills-phase-05/` and `/tmp/magenta-phase05-artifacts/` are superseded for sign-off and retained only for traceability;
  - the requested `gpt-5.2` medium Playwright validator model was not exposed by the available orchestration tool schema, so the revalidation records that tooling constraint instead of claiming normal model compliance.
