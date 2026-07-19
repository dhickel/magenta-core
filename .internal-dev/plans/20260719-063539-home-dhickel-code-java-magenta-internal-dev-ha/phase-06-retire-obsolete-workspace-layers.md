# Phase 06 — Retire Obsolete Workspace Layers

## Context

Phases 03–05 move explorer, metadata, execution, tools, and output publication to trusted `WorkspaceDescriptor` identities. Phase 06 removes superseded filesystem-identity layers after their consumers have moved. It must not delete the active output-artifact boundary, assignment queue coordination, project metadata behavior, or generic persistence that Phase 01 proved has a concrete unrelated responsibility.

## Goal

Leave one closed workspace model—agent, chat, and explicitly declared user workspaces—with `WorkspacePathLayout` as the sole structural path authority and `WorkspaceDirectoryService` as the sole descriptor constructor. Retire Work Areas, generic filesystem workspace identity, project roots, links, aliases, migration helpers, starter generation, obsolete output-directory routing, and filesystem workspace leases without touching legacy database rows or filesystem trees.

## In Scope

Delete these production files after caller removal:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/Workspace.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceLease.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceLeaseService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/LeaseMode.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceLink.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceLinkType.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceOwnerType.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRoot.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkArea.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputDirectoryService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputDirectoryKind.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ResolvedOutputDirectory.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/EffectiveWorkspace.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/EffectiveWorkspaceResolver.java`

Delete the generic `Workspace*` persistence files when the Phase 01 inventory confirms no demonstrated non-Work-Area responsibility. If Phase 01 documented a concrete unrelated responsibility, retain only the minimum renamed or narrowed persistence component for it and prove it cannot determine filesystem identity, select or own roots, create links, or acquire filesystem leases. `OutputArtifactRepository` and `OutputPublicationTarget` are active Phase 05 boundaries and remain independent of generic workspace persistence.

Also delete inventory-confirmed alias, legacy-root, starter, migration, project-link, and filesystem-lease helpers whose only responsibility is the retired model.

Update these production files:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentWorkspaceStatus.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentWorkspaceStatusService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentsMdResolver.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/agents/AgentProfileService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectRepository.java`
- `src/main/java/io/mindspice/magenta2/api/web/ProjectController.java`
- `src/main/java/io/mindspice/magenta2/api/web/AgentProfileController.java`
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/main/java/io/mindspice/magenta2/api/web/selector/EntityKind.java`
- `src/main/java/io/mindspice/magenta2/api/web/selector/EntityLookupService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/PromptContextAssembler.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalTools.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolResponses.java`
- `src/main/resources/schema.sql`

Delete these obsolete tests:

- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaServiceTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceLeaseServiceTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepositoryAttributionTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepositorySchemaMigrationTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceServicePathTest.java`

Update these tests:

- `src/test/java/io/mindspice/magenta2/ai/orchestration/agents/AgentProfileWorkspaceStarterTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentsMdResolverTest.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectServiceTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/AgentProfileControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/selector/EntityLookupServiceTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/selector/EntitySelectorComponentsTest.java`
- `src/test/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolServiceTest.java`
- `src/test/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolServiceTest.java`
- `src/test/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolConfigurationTest.java`
- `src/test/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolServiceTest.java`

Add or update focused repository, schema, and wiring tests needed to prove retained active persistence is independent of obsolete tables.

## Out of Scope

- Deleting or changing assignment queue lease columns or coordination behavior.
- Implementing the agent Files tab or reusable workspace-selection UI; Phase 07 owns both.
- Dropping, migrating, importing, backfilling, aliasing, relocating, or deleting obsolete database structures or legacy filesystem trees.
- Replacing Avatar, adding compatibility endpoints, or creating a generalized workspace provider.
- User-workspace root rename or destructive root deletion.
- Removing project records, membership validation, project events, project attribution, network gating, or active assignment/job guards.

## Dependencies

- `phase-05-cut-over-execution-and-outputs.md` must have passed its validator gate.
- The Phase 01 active-consumer inventory and generic-persistence disposition govern conditional deletion.

## Execution Ownership

- Runs as wave 06 with exactly one implementation agent. That agent owns all ordered edits, constructor-graph repair, tests, phase closeout records, and validation repairs.
- Phase 07 cannot start until the medium post-phase validator reports PASS.

## Constraints

- `WorkspaceKind` remains closed to `AGENT`, `CHAT`, and `USER`; canonical identities remain `agent/<id>`, `chat/<id>`, and `user/<name>`.
- Only `WorkspaceDirectoryService` constructs descriptors. No caller reconstructs identity from a `Path`, accepts a raw root, or hand-concatenates structural paths.
- `WorkspacePathLayout` retains only `WORKSPACE`, `OUTPUTS`, and agent/chat/user root factories. The configurable data root is not an alternate workspace-selection API.
- Missing agent and chat directories read as empty and are created only by successful file-producing or execution operations. Profile create, enable, read, and status do not scaffold roots or starter files.
- User workspaces remain explicitly declared. This phase does not weaken their missing-root, direct-child, non-symlink, or stable-name rules.
- `outputs/` is reserved. Explorer and allowed read paths may inspect it; output publication is its only writer. File-tool mutations and shell working targets must not create, modify, rename, move, copy, or delete it or descendants.
- Agent hard delete may remove only the confined canonical `workspace/agent/<agentId>` root, through the directory service, after the existing confirmation and reference-purge contract. It must not follow symlinks or touch legacy trees.
- File and shell paths are normalized workspace-relative paths. Blank and `.` mean the selected root. Absolute paths, traversal, symlink components or escapes, symbolic recursive trees, root mutation, and retired aliases are rejected.
- Layered `AGENTS.md` resolution uses only user-authored files from the selected descriptor root through the confined active path. No starter file is generated and no alias escapes the root.
- Project ids remain metadata and membership context. They never select, allocate, link, lease, or delete a filesystem root.
- Assignment queue `lease_owner` and `lease_expires_at` remain because they coordinate assignment execution rather than filesystem ownership.
- Existing obsolete tables, columns, indexes, rows, and files remain untouched. Active repositories do not read, write, backfill from, or fall back to obsolete workspace or Work Area identity.
- Existing active execution compatibility may derive an agent selection only from authoritative `agent_id`; ambiguous historical rows remain non-resumable. No retired repository may be consulted.
- Every intermediate revision remains compilable with a valid Spring constructor graph. No no-op, throwing, deprecated, duplicate-path, or compatibility shim may survive the phase.

## Implementation Steps

1. Confirm Phase 05 moved all `run_output_artifacts` schema, queries, writes, deletion behavior, and attribution mapping into `OutputArtifactRepository`, and confirm `OutputArtifactService` has no `WorkspaceRepository` dependency. Apply the Phase 01 persistence disposition. Delete the listed generic filesystem workspace, link, lease, owner, root, Work Area, effective-workspace, and obsolete output-directory routing classes after removing their callers. Retain `OutputPublicationTarget`. If an unrelated generic persistence responsibility was proven, isolate it under an accurate name and remove every filesystem-identity capability.
2. Reduce `WorkspacePathLayout` to public structural names `WORKSPACE` and `OUTPUTS` and root factories for agent, chat, and user workspaces. Delete Home, Work Area, `work/`, `runs/`, `projects/`, scratch, job, temporary execution, metadata, chat-file, migration, alias, and relative-string helpers. Replace active callers with descriptor roots rather than hand-built paths.
3. Reduce `WorkspaceDirectoryService` to trusted descriptor resolution, explicit user list/create, operation-specific lazy root and output creation, confinement, and any confirmed canonical agent hard-delete helper. Expose the configured data root only where a demonstrated non-workspace structural responsibility requires it; never as workspace identity or authorization. Remove migration, starter, project-link, assignment/job/temp, cleanup, scratch/work/run scaffolding, and legacy overloads.
4. Refactor `AgentProfileService`: create and enable validate and persist profiles without creating directories; disable preserves files in place; confirmed hard delete may remove only the canonical agent root after existing reference purge; generic workspace dependencies and `.archive` workspace behavior are removed. Rewrite the starter test to assert no root, subtree, or generated `AGENTS.md` appears.
5. Simplify `ProjectService` and `ProjectRepository` to project records, membership, network gating, events, attribution, and active assignment/job guards. Remove directory allocation, root fields and queries, workspace summaries, materialized links, filesystem lease checks, and release requests. Remove only corresponding filesystem-oriented `ProjectController` endpoints; preserve project CRUD and domain behavior.
6. Remove generic workspace payloads and endpoints from `AgentProfileController`. Do not add a compatibility route; the typed `/api/workspace/{type}/{id}` family established in Phase 04 is authoritative.
7. Simplify `AgentWorkspaceStatusService` to resolve the canonical agent descriptor without creating its root. Derive output counts from `OutputArtifactRepository`, last activity from active canonical action/output data, and assignment state from assignment persistence—not obsolete workspace tables or directory scans. Report canonical relative identity and a truthful missing-root/writability state. Remove lease, link, project-root, owner-root, and legacy-path fields from `AgentWorkspaceStatus` and its callers.
8. Remove `WORKSPACE` and `WORK_AREA` from selector entity kinds and lookup branches while retaining selectors for real domain records. Remove old workspace/Work Area controls, routes, status blocks, and diagnostics from `OrchestrationController`; do not implement the Phase 07 Files tab or replacement selection controls here.
9. Rewrite `AgentFileToolService` and `AgentShellToolService` around the already-resolved descriptor root plus one normalized relative path. Blank or `.` selects the root; ordinary relative paths stay beneath it. Treat `outputs/` as the real reserved child, not an alias, and preserve Phase 05’s publication-only write rule. Reject `workspace/`, `root/`, `run/`, `work/`, `projects/`, scratch, job, project-link, and other retired prefixes rather than remapping them. Preserve symlink confinement and active-path tracking.
10. Simplify `AgentsMdResolver` and `PromptContextAssembler` to the selected descriptor root and direct active relative path. Walk ancestors only from that root to the confined target, layering existing user-authored `AGENTS.md` files in order. Remove generated-starter assumptions, old aliases, and traversal to parent or legacy roots.
11. Remove operational tools and response fields for workspace links, project workspace release, filesystem leases, and workspace-blocked requeue. Keep agent status, assignment queue, project metadata, job, schedule, and output tools only where they use active repositories and the descriptor contract. Repair Spring registration and configuration tests rather than leaving optional providers for deleted services.
12. Remove fresh-schema DDL for `workspaces`, `workspace_links`, `workspace_leases`, `work_areas`, their indexes and foreign keys, project-root contracts, Work Area-keyed metadata/action structures, and retired execution-routing fields. Preserve active canonical metadata/action, type-key execution selection, output artifacts, project attribution, and assignment queue coordination. Add no `DROP`, migration, trigger, backfill, or startup write against obsolete structures.
13. Delete tests that assert removed behavior. Rewrite retained tests for lazy roots, no starter content, canonical hard-delete confinement, direct-relative tool paths, reserved-output write rejection, retired-alias rejection, descriptor-confined `AGENTS.md` layering, metadata-only projects, simplified status, retained assignment leases, and absent old selectors/endpoints. Add fresh-schema and populated-existing-schema tests proving active repositories are independent of obsolete structures.
14. Search phase-owned production code, resources, configuration, and tests for every deleted type, retired `WorkspacePathLayout` member, Work Area or generic workspace field, `/api/workspaces`, `/api/work-areas`, old alias, project-link operation, filesystem lease operation, starter generator, and migration helper. Remove the active caller or stale test instead of adding a shim. Distinguish assignment queue leases and allowed historical records from active filesystem-lease references.

## Required Guides

- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/knowledge/agent-shell-workspace-alias-resolution.md`
- `.internal-dev/knowledge/project-workspace-materialized-links.md`
- `.internal-dev/knowledge/agents-md-specification-reference.md`

Read only task-relevant additional package guides and knowledge records discovered from files actually changed.

## Technical Guidance

Use one path model throughout:

```text
WorkspaceDescriptor.root() + normalized workspace-relative path
```

Pass the descriptor or a value already bound to it; do not pass a controller-supplied root or reconstruct kind/key from a path. Normalize the relative path once at the service boundary. Project ids never participate in path construction. `outputs` is a reserved physical child whose mutations flow only through output publication.

For a missing agent root, status and reads remain side-effect free. Do not create the directory to test writability. Report root existence separately and, if the status contract needs creatability, evaluate the nearest trusted existing parent without materializing a path.

## Validation

1. Run the focused tests listed in scope plus new schema, repository-independence, status, and constructor-wiring tests.
2. Run `mvn test`.
3. Run bounded Spring Boot startup against a fresh database. Assert no Avatar, Work Area, generic filesystem workspace/link/lease, project-root, or retired execution-routing schema is created and no deleted bean is required.
4. Run bounded startup and focused integration tests against an existing database populated with sentinel obsolete workspace, Work Area, link, lease, metadata/action, and routing values. Assert value-equivalent sentinel preservation and prove active repositories neither query nor mutate those structures.
5. Verify agent create, enable, read, disable, and status do not create roots or starter content. Verify confirmed hard delete is confined to the canonical agent root and does not follow symlinks or touch legacy paths.
6. Verify project CRUD, membership, network gating, events, attribution, and active assignment/job guards still work while no project root, link, or filesystem lease is allocated.
7. Exercise file and shell services with blank, `.`, normal nested paths, traversal, absolute paths, symlinks, retired aliases, and `outputs/`. Confirm direct paths stay confined, aliases reject, active-path tracking remains correct, and publication remains the only writer under `outputs/`.
8. Verify layered `AGENTS.md` resolution from the selected root to a nested confined target, including missing files and symlink rejection, with no generated starter or parent-root lookup.
9. Run static searches from Step 14 and compile the result into validator evidence. Any retained generic persistence class or table must have its Phase 01 documented unrelated responsibility and a test proving it cannot determine filesystem identity, roots, links, or leases.
10. Review the final diff for unrelated removals, compatibility stubs, accidental API growth, obsolete schema writes, changed assignment queue semantics, and changes belonging to Phase 07.
11. Complete required phase records and commit the phase only with the implementation and applicable `.internal-dev`/`docs` updates after validation succeeds.
12. Run the configured post-phase review-and-repair validator with `openai-codex/gpt-5.6-sol` at medium. Repair findings and require PASS.

## Exit Criteria

- `WorkspacePathLayout`, `WorkspaceDirectoryService`, and `WorkspaceDescriptor` are the only active structural path, descriptor-construction, and trusted workspace-identity authorities.
- Work Area, project-root, link, alias, migration, starter-generation, obsolete output-directory routing, and filesystem workspace lease code is absent.
- Generic filesystem workspace persistence is deleted, or every retained component has a Phase 01-documented unrelated responsibility and cannot select, resolve, alias, link, lease, or own a filesystem root.
- `OutputArtifactRepository` and `OutputPublicationTarget` remain active and independent of generic workspace records.
- Fresh schema omits obsolete structures. Existing schema and legacy files remain untouched, and tests prove active code does not read, write, backfill from, or depend on them.
- Agent lifecycle and status preserve lazy roots; project domain behavior remains; tools and prompts use one descriptor root with direct confined paths; only publication writes beneath `outputs/`.
- Assignment queue coordination remains intact and is not confused with deleted filesystem leases.
- Focused tests, `mvn test`, fresh and existing database startup, static searches, and final diff review pass.
- No compatibility stub, retired alias, duplicate identity path, missing Spring dependency, placeholder, or Phase 07 implementation remains.
- The medium post-phase validator reports PASS, permitting Phase 07 to begin.
