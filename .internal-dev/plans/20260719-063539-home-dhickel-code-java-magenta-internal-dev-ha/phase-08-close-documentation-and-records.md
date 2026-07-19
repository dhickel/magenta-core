# Phase 08 — Close Documentation and Records

## Context

Phases 01–07 replace Avatar, Work Areas, generic filesystem workspace identity, project roots, filesystem leases, run staging, and aliases with the closed agent/chat/user workspace model. This phase makes current explanatory truth match the accepted contract and validated code, preserves historical material only as clearly historical, and assembles integration-readiness evidence. It does not redefine the Phase 01 contract or perform the orchestration-level Final Integration that follows PASS for all phases.

## Goal

Reconcile current documentation, package guides, living specifications, decisions, knowledge, plan records, and changelog evidence; run the complete Phase 08 validation campaign; and obtain the post-phase validator PASS without changing implementation owned by prior phases.

## In Scope

Exact root and package guides from the orchestration ledger:

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`

Exact documentation files to update:

- `README.md`
- `docs/README.md`
- `docs/api/00-index.md`
- `docs/end-user/00-index.md`
- `docs/end-user/agents.md`
- `docs/end-user/chat.md`
- `docs/end-user/jobs.md`
- `docs/end-user/plans-and-tasks.md`
- `docs/end-user/projects-and-workspaces.md`
- `docs/end-user/quickstart.md`
- `docs/end-user/workflows.md`
- `docs/technical/00-index.md`
- `docs/technical/api-reference.md`
- `docs/technical/architecture.md`
- `docs/technical/chat-planning-tasks.md`
- `docs/technical/configuration-operations.md`
- `docs/technical/data-model.md`
- `docs/technical/frontend-htmx.md`
- `docs/technical/orchestration-runtime.md`
- `docs/technical/services.md`
- `docs/technical/workflow-engine.md`
- `docs/technical/workspaces-tools-outputs.md`

Exact documentation files to delete:

- `docs/end-user/avatar-dashboard.md`
- `docs/technical/avatar-dashboard-fragments.md`
- `docs/technical/avatar-dashboard-layout-persistence.md`
- `docs/technical/avatar-planner-organizer.md`

Exact specifications to reconcile:

- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/specifications/decisions.md`

Exact knowledge files to add or update:

- `.internal-dev/knowledge/filesystem-first-workspaces.md` (new)
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- `.internal-dev/knowledge/plain-path-segment-id-validation.md`
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md`
- `.internal-dev/knowledge/agents-md-specification-reference.md`

Exact knowledge moves, preserving content:

- `.internal-dev/knowledge/avatar-chat-sticky-resize-container.md` → `.internal-dev/knowledge/.archive/avatar-chat-sticky-resize-container.md`
- `.internal-dev/knowledge/avatar-shell-resizable-rail-geometry.md` → `.internal-dev/knowledge/.archive/avatar-shell-resizable-rail-geometry.md`
- `.internal-dev/knowledge/avatar-work-area-ui-refactor.md` → `.internal-dev/knowledge/.archive/avatar-work-area-ui-refactor.md`
- `.internal-dev/knowledge/simplypages-avatar-layout-and-editing.md` → `.internal-dev/knowledge/.archive/simplypages-avatar-layout-and-editing.md`
- `.internal-dev/knowledge/project-workspace-materialized-links.md` → `.internal-dev/knowledge/.archive/project-workspace-materialized-links.md`
- `.internal-dev/knowledge/workspace-api-list-and-agent-tab-operational-pattern.md` → `.internal-dev/knowledge/.archive/workspace-api-list-and-agent-tab-operational-pattern.md`
- `.internal-dev/knowledge/workspace-file-architecture-rules.md` → `.internal-dev/knowledge/.archive/workspace-file-architecture-rules.md`
- `.internal-dev/knowledge/agent-shell-workspace-alias-resolution.md` → `.internal-dev/knowledge/.archive/agent-shell-workspace-alias-resolution.md`
- `.internal-dev/knowledge/workspace-dev-reset-schema-backed-migration.md` → `.internal-dev/knowledge/.archive/workspace-dev-reset-schema-backed-migration.md`

Exact changelog file:

- `.internal-dev/changelogs/filesystem-first-workspaces.md` (new)

Plan-record work is limited to status/evidence updates required by the store guide and archival of records already finalized or superseded. Preserve the active plan’s exact eight phase filenames and leave its directory active for orchestration-level final integration.

## Out of Scope

- Editing any other phase file or changing the eight-file plan membership.
- Repairing production code, resources, schema, tests, or UI owned by Phases 02–07.
- Running or claiming the orchestration-level Final Integration.
- Archiving the active eight-phase plan before orchestration-level final integration passes.
- Rewriting historical changelogs, archived plans, or reviews to remove accurate historical references.
- Adding deferred capability, compatibility endpoints, migration machinery, or unrelated fixes.

## Dependencies

- `phase-07-mount-agent-files-tab.md` with PASS.
- PASS evidence for every transitive dependency, because Phase 08 validates the integrated result.

If validation exposes a defect owned by an earlier phase, stop the affected Phase 08 path, reopen the owning phase, repair and revalidate it there, and invalidate dependent PASS state until the orchestration gates are restored. Resume Phase 08 only with dependencies at PASS.

## Constraints

- One implementation agent owns all Phase 08 edits. Delegated Playwright and the post-phase validator are validation roles and must not make unreviewed implementation changes.
- Current docs must not present `/avatar`, `avatar.sqlite`, Avatar organizer/planner behavior, DB Work Areas, `/api/work-areas`, generic workspace ids, project roots or leases, run staging, old aliases, starter files, or removed payload fields as active.
- The documented workspace model is closed: `AGENT`, `CHAT`, and `USER`, serialized as `agent`, `chat`, and `user`, with canonical identities `agent/<id>`, `chat/<id>`, and `user/<name>`.
- Only `WorkspaceDirectoryService` constructs trusted `WorkspaceDescriptor` values; only `WorkspacePathLayout` owns structural workspace paths. Controllers accept type/key segments and never roots or canonical identity.
- Agent and chat roots are lazy and may read as empty after entity existence checks. User roots require explicit declaration and otherwise return `404`. No operation generates scaffolding or starter `AGENTS.md` content.
- Execution accepts `workspaceType` and `workspaceKey` together. Omission defaults to the bound agent; explicit agent selection must match; existing user selection may override; chat selection is rejected. `projectId` remains metadata only.
- Outputs publish directly beneath the effective workspace’s reserved lazy `outputs/`; explorer mutations cannot modify that subtree. No run-, job-, project-, scratch-, work-, or temporary-root hierarchy is active.
- Legacy user files and obsolete warm-schema structures remain untouched and inactive. Current docs must describe this as non-migration and isolation, not as compatibility APIs.
- Historical records may retain old terms only in archives, clearly superseded material, or inert fixtures that prove non-destructive warm startup and active non-use.
- Do not silently revise accepted intended truth to match defective code. Record specification drift and route the defect to its owning phase.
- The changelog must include specification impact and a Git Commit section. Record the output of `git rev-parse HEAD` at changelog creation as the validated implementation baseline; do not predict the commit containing the changelog or use a placeholder.
- Do not expose secrets, local configuration contents, or unrelated workspace details.

## Implementation Steps

1. Update the exact root and package guides in scope. State that `WorkspacePathLayout` owns structural roots; `WorkspaceDirectoryService` alone constructs trusted three-kind descriptors; explorer metadata and actions use canonical identity; execution selects agent or declared user workspaces with paired fields; projects are metadata only; tools use confined root-relative paths; outputs use the reserved workspace-local directory; and the agent Files tab is the mounted browser surface. Remove active Avatar, Work Area, project-root, filesystem-lease, run-alias, and starter-generation guidance.
2. Delete the four Avatar documentation files and remove their links from `README.md`, `docs/README.md`, and the end-user, technical, and API indexes. Do not remove accurate historical links from archived records.
3. Rewrite the listed end-user pages around exact root semantics, explicit user-workspace creation, selection rules, Files-tab behavior, direct output publication, lazy agent/chat roots, missing-user `404`, project metadata-only behavior, and the non-migration policy. Make clear that root rename/delete, chat execution selection, sharing, permissions, project browsing, and legacy-tree import are unavailable.
4. Rewrite the listed technical architecture, service, runtime, data-model, configuration, workflow, HTMX, and workspace/tool/output pages. Document descriptor fields and trust boundary; low-level agent/chat existence checks; explicit user declaration; fresh- versus warm-schema behavior; canonical metadata/action identity; paired execution fields; project non-ownership; direct collision-safe outputs and attribution; path and symlink confinement; reserved-output mutation rules; direct-relative tools; layered existing-file `AGENTS.md` lookup; and the absence of removed roots, links, leases, aliases, and generated content.
5. Rewrite `docs/technical/api-reference.md` and `docs/api/00-index.md` from validated controller contracts. Enumerate exact method/path pairs under `/api/workspace/{type}/{id}`, `GET /api/workspace/user`, and `POST /api/workspace/user`; preserved explorer operations and payloads; `workspaceType`/`workspaceKey` execution fields; output query attribution; collision, validation, missing-entity, limit, and confinement statuses; rejection of raw roots and client canonical identities; and explicit removal of `/api/work-areas`, `/api/workspaces`, and `/avatar`.
6. Reconcile the seven living specifications with the accepted Phase 01 contract and final validated code. Refresh observed anchors, implementation status, drift entries, validation evidence, affected-spec links, and decision status. Preserve decision history. If code contradicts intended truth, record drift and reopen the owning phase rather than weakening the contract or broadening Phase 08.
7. Create `.internal-dev/knowledge/filesystem-first-workspaces.md` through exclusive creation with the required headings `Topic`, `Source References`, `Key Takeaways`, `Engine Relevance`, and `Open Questions`. Capture reusable lessons on repository-level existence checks without service cycles, descriptor trust, lazy roots, explicit user declaration, canonical metadata/action keys, obsolete-schema isolation, direct output publication, reserved-subtree enforcement, and browser validation.
8. Update the four still-current knowledge files for the new root-relative model: decoded plain-segment validation and encoded-boundary rejection; source real-path and destination symlink confinement; direct artifact-relative paths; and layered `AGENTS.md` lookup only for existing files under the selected descriptor. Remove obsolete aliases, run-root assumptions, and starter-generation instructions without erasing useful general validation lessons.
9. Move the nine obsolete active knowledge files to their exact `.archive` destinations without content loss. Add only a concise superseded notice when the archived text could otherwise be mistaken for current guidance. Do not archive the active eight-phase plan in this step.
10. Create `.internal-dev/changelogs/filesystem-first-workspaces.md` through exclusive creation with headings `Date`, `Change Summary`, `Files`, `Behavioral Impact`, `Specification Impact`, `Risks`, `Follow-up Items`, and `Git Commit`. Explain the superseding specification changes, identify deleted and retained behavior, summarize validation evidence, and record the current `git rev-parse HEAD` value as the validated implementation baseline.
11. Run repository-wide active-reference searches across production code, resources, configuration, tests, current docs, package guides, and active specifications. Classify every match as stale active behavior, explicit current removal/non-migration documentation, inert warm-schema evidence, or historical/archive material. Active surfaces must contain no stale reliance on Avatar, Work Area identity, generic filesystem workspace identity, project roots, filesystem leases, run staging, aliases, old execution fields, or removed routes.
12. Run the complete automated, schema, startup, API, filesystem, and delegated Playwright validation matrix. Phase 08 may repair only defects in its scoped documentation and records. Route implementation failures to the owning phase, restore dependency PASS gates, then rerun the affected checks and the complete Phase 08 gate. Keep browser validation delegated and require screenshots plus visual critique; do not substitute inline browser validation.
13. Review the final Phase 08 diff and records for scope, accurate links, preserved historical evidence, exact plan-file membership, unrelated removals, placeholders, stale active terms, secret exposure, unsupported claims, accidental API growth, or changes outside Phase 08 ownership. Submit to the post-phase medium-reasoning validator review-and-repair gate.

## Required Guides

Read completely before editing:

- `.internal-dev/AGENTS.md`
- `.internal-dev/specifications/AGENTS.md`
- `docs/AGENTS.md`
- `.internal-dev/knowledge/live-chat-mcp-workflow-testing.md`
- `.internal-dev/knowledge/documentation-foundation.md`

Also read each closest in-scope package guide before changing it. Inspect only task-relevant living specifications and knowledge; do not scan `.internal-dev` broadly.

## Technical Guidance

Use current tense only for behavior proven by final code and validation. Treat code as observed truth, the corrected concepts and Phase 01 specifications as intended truth, and documentation/changelogs as explanatory history. Explain that obsolete files and database structures are untouched but inactive; never describe them as compatibility APIs.

Keep canonical workspace identity separate from normalized file-relative paths. Use singular `/api/workspace`, exact method/path pairs, and exact `workspaceType`/`workspaceKey` names. Do not imply that canonical identity, a raw root, `projectId`, a legacy workspace id, or an output field can select a workspace.

The static audit must include at least these terms and symbols, plus renamed equivalents discovered from prior phase inventories:

```text
io.mindspice.magenta2.avatar
/avatar
avatar.sqlite
avatar-schema.sql
avatar_
WorkArea
WorkAreaService
WorkAreaRepository
/api/work-areas
/api/workspaces
selectedWorkAreaId
outputWorkAreaId
outputDirectRelativePath
WorkspacePathLayout.HOME
WorkspacePathLayout.RUNS
WorkspacePathLayout.PROJECTS
materializeAssignmentProjectLink
WorkspaceService
WorkspaceRepository
WorkspaceLease
EffectiveWorkspace
project root
run staging
starter AGENTS.md
```

A textual match is not automatically a defect. Explicit removed-route documentation, archived history, and inert warm-schema fixtures are allowed when their role is unambiguous. Active repository code must not read, write, resolve, alias, link, lease, or own filesystem roots through obsolete identity.

## Validation

Use `openai-codex/gpt-5.6-sol` with medium reasoning for delegated validation.

1. **Documentation and records**
   - Run all repository-provided Markdown link and documentation checks.
   - Verify deleted Avatar pages have no current index links.
   - Verify all knowledge files have required headings, all archive moves preserve content, and the changelog has specification impact plus a concrete Git baseline hash.
   - Verify the active plan still contains exactly the eight declared phase filenames.
2. **Build and wiring**
   - Run `mvn test`.
   - Run bounded Spring Boot startup on an unused port.
   - Confirm the context has no Avatar datasource/schema/bootstrap beans, Avatar product beans, Work Area beans, or obsolete generic filesystem workspace beans.
3. **Schema isolation**
   - Run fresh-schema validation and assert removed Avatar, Work Area, generic filesystem workspace/link/lease, project-root, old metadata/action, and removed execution-routing structures are absent.
   - Run existing-schema startup with sentinel obsolete tables, columns, indexes, rows, and user files. Prove they remain unchanged and active repositories neither read, write, backfill from, nor depend on them.
4. **Workspace identity and layout**
   - Cover agent/chat existence, lazy empty reads, explicit user create/list, valid direct non-symlink filtering, missing-user `404`, invalid/encoded/decoded separators, traversal, drive/absolute syntax, symlinks, confinement, no starter file, no eager subtree creation, and operation-specific lazy root creation.
   - Assert only `workspace/agent/<id>`, `workspace/chat/<id>`, `workspace/user/<name>`, and lazy reserved `outputs/` are active structural paths; no hand-concatenated replacement or legacy tree is created, moved, imported, or deleted.
5. **Execution, tools, and outputs**
   - Cover omitted selection fallback, paired-field rejection, matching explicit agent selection, existing user override, chat rejection, missing entities, project metadata-only behavior, deterministic legacy-agent handling, ambiguous-history non-resumption, and persisted type/key/canonical attribution.
   - Prove file and shell tools bind to one effective descriptor, reject escapes, maintain a confined active path, and resolve layered existing `AGENTS.md` files without aliases or generation.
   - Prove direct lazy publication beneath the selected workspace, normalized collision-safe names, destination-symlink and collision rejection, source real-path confinement, no run hierarchy, durable run/task/job/project/agent/canonical attribution, and output repository independence from generic workspace records.
6. **Explorer, metadata, and API**
   - Exercise list, inspect, preview, image, bounded download, text/Markdown create-save, directory create, rename, move, copy, delete preflight/execute, labels, tags, and recent actions for agent, chat, and user descriptors.
   - Cover empty roots, missing entities, invalid paths, collisions, limits, invalid UTF-8, Markdown safety, traversal, symlinks, symbolic recursion, recursive-delete confirmation, workspace-root mutation, and every explorer mutation against `outputs/` or descendants.
   - Prove metadata and actions use canonical identity plus normalized relative path and do not expose unrelated Work Area-keyed history.
   - Confirm typed workspace routes and user list/create operations work with controlled status mappings; raw roots and client canonical identities are rejected; `/avatar`, `/api/work-areas`, and `/api/workspaces` return not found.
7. **Avatar and obsolete-layer absence**
   - Run the classified static audit. Confirm no active Avatar package, route, datasource, schema initialization, profile bootstrap, planner/organizer model, tool, prompt/policy registration, asset, navigation, current doc, or test remains.
   - Confirm no active Work Area entity/service/repository, owner-root behavior, route/payload field, or schema dependency remains.
   - Confirm generic filesystem workspace layers are retired, or that every Phase 01-approved retained persistence component has a documented unrelated responsibility and cannot determine roots, identity, links, or leases. Preserve assignment queue lease fields that coordinate execution only.
8. **Delegated live browser validation**
   - Start the application and run the documented MCP-first Playwright workflow against the agent Files tab on desktop and mobile.
   - Cover tab loading, navigation, all preserved CRUD, preview, image view, labels/tags, inspector/modal/OOB refreshes, empty state, and controlled failures.
   - Capture screenshots and critique alignment, spacing, density, wrapping, overflow, hierarchy, mobile stacking, first-viewport usefulness, and operational-console consistency.
   - Confirm standard explorer, user-workspace management, execution selection, and tab refresh interactions are HTMX-first; retained JavaScript is narrow and explicitly justified.
9. **Gate**
   - Re-run checks affected by Phase 08 edits and any reopened dependency repair.
   - Run the post-phase validator review-and-repair gate. PASS is required.

This campaign establishes Phase 08 readiness. The orchestration owner runs the separate declared Final Integration only once all phases report PASS.

## Exit Criteria

- Every dependency is at PASS, and the Phase 08 validator reports PASS.
- Current docs, package guides, living specifications, decisions, knowledge, and validated code agree with the corrected three-kind workspace contract.
- Avatar and obsolete workspace behavior appear only in clearly historical/archive material, explicit current removal/non-migration statements, or inert warm-schema fixtures.
- The exact knowledge updates, archive moves, and changelog are complete, correctly routed, and free of placeholders.
- Tests, bounded startup, fresh/warm schema isolation, workspace lifecycle, execution, tools, outputs, explorer, metadata, API, static absence, and delegated browser checks pass.
- Browser evidence includes desktop/mobile screenshots, visual critique, HTMX confirmation, and no unexplained replacement JavaScript transport.
- Validation creates no removed tree, alias, scaffold, or starter file and leaves legacy user files and obsolete schema sentinels unchanged.
- The final Phase 08 diff contains no unrelated edits, unsupported claims, stale active contract, secret exposure, compatibility stub, accidental API growth, or change to another phase file.
- The active plan still contains exactly the eight declared phase files and remains available for orchestration-level Final Integration and subsequent plan archival.
