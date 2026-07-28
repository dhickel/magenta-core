# Handoff: Revert Avatar Dashboard and Simplify Workspace Structure

## Context

The authoritative directive replaces the Avatar dashboard and DB-backed Work Area design with a filesystem-first workspace model.

Current code and living specifications still describe the superseded design:

- `src/main/java/io/mindspice/magenta2/avatar/` owns Avatar profile, organizer, planner, dashboard, and separate `avatar.sqlite` persistence.
- `AvatarDashboardController`, `AvatarDashboardComponents`, Avatar tools, static assets, `/avatar` navigation, prompts, policies, and orchestration references are active.
- `WorkArea`, `WorkAreaRepository`, and `WorkAreaService` use opaque database ids and `work_areas` records to resolve filesystem roots.
- `WorkspacePathLayout` and `WorkspaceDirectoryService` expose Home, Work Areas, runs, outputs, projects, scratch, legacy paths, project links, and starter-file generation.
- Assignment, task, workflow, job, output, selector, dashboard, metadata, and action-log contracts contain Work Area ids.
- The file explorer is valuable but coupled to Avatar routes, Avatar assets, and DB-backed Work Areas.
- Living specifications, package guides, knowledge, and user/technical documentation still endorse Avatar and the prior workspace hierarchy.

The directive below is intended truth. Existing code is evidence of the dependency surface, not the desired end state.

## Objective

Remove the Avatar product surface and replace the layered workspace/Work Area model with three path-identified workspace kinds:

```text
<dataRoot>/
  workspace/
    agent/<agentId>/
    chat/<chatId>/
    user/<name>/
```

Preserve the standalone file explorer and integrate it into the existing agent operational console. Keep chat, agent profiles, orchestration, skills, file metadata, action logging, and unrelated product behavior functional while removing dependencies on Avatar classes, Avatar persistence, DB-backed Work Areas, legacy workspace scaffolding, and project-owned filesystem links.

## Targets

- **Avatar production code:** remove the complete `io.mindspice.magenta2.avatar` package, including planner records/enums and its package guide.
- **Avatar web surface:** remove `AvatarDashboardController`, `AvatarDashboardComponents`, `/avatar` routes/fragments, dedicated shell/chat/layout behavior, and the Avatar home card in `FrontendController`.
- **Avatar tools:** remove `ai/chat/tool/avatar/` and all registrations, authorization, tool-policy, prompt, chat, and orchestration references that exist solely for Avatar.
- **Avatar resources:** remove `avatar-schema.sql`, `avatar-dashboard.css`, `avatar-chat.js`, `avatar-layout-edit.js`, and `avatar-shell.js`, plus all active references.
- **Avatar tests and docs:** remove tests dedicated to deleted behavior and remove or rewrite current Avatar documentation. No current documentation may present `/avatar`, `avatar.sqlite`, or Avatar organizer/planner behavior as active.
- **Workspace path contract:** reduce `WorkspacePathLayout` to the `WORKSPACE` and `OUTPUTS` structural names and validated factories for agent, chat, and user roots. Remove Home, Work Areas, work, runs, projects, aliases, file wrappers, and legacy path factories/constants.
- **Workspace directory service:** retain data-root setup, confinement, child confinement, directory creation, root resolution for all three workspace kinds, user-workspace list/create behavior, and `dataRoot()`. Remove migration, starter files, project links, assignment/job/temp helpers, scratch/work/run scaffolding, and retired compatibility paths.
- **Work Area persistence:** remove active use of `WorkArea`, `WorkAreaRepository`, `WorkAreaService`, `work_areas`, owner-root marking, Home creation, mark/unmark operations, and Work Area foreign-key assumptions.
- **Workspace execution contracts:** replace `selectedWorkAreaId`, `outputWorkAreaId`, and equivalent references with a closed workspace kind/key contract. Persist canonical identity as `agent/<id>` or `user/<name>` where execution selection is stored. Chat workspaces are not execution targets.
- **Workspace wrapper layer:** remove `WorkspaceService` if its remaining role is path delegation or obsolete Work Area/project-link behavior. Retain generic workspace metadata, links, or leases only for a demonstrated non-Avatar behavior; they must not determine filesystem identity.
- **File explorer service:** preserve list, inspect, preview, image view, download, text/Markdown create and save, folder create, rename, move, copy, delete preflight/execute, labels/tags, and recent actions. Refactor operations around a trusted workspace descriptor resolved by `WorkspaceDirectoryService`.
- **File metadata and action logs:** preserve active repository/service behavior and re-key new active records to canonical workspace identity plus file-relative path. Remove active `WorkArea` parameters and `workAreaId` fields/index assumptions.
- **Workspace API:** replace `/api/work-areas/...` with typed path-identified workspace routes. Remove Work Area list/Home/mark/unmark/nested-mark endpoints.
- **Agent UI:** add a standalone **Files** tab to `/agents/{agentId}` and its HTMX tab shell. Reuse explorer behavior through agent/workspace-neutral IDs, classes, routes, labels, and fragments.
- **Specifications and records:** revise affected living specifications and package guides, supersede Avatar/DB-backed Work Area decisions, archive or clearly mark Avatar-specific knowledge as superseded, update workspace knowledge, and add required changelog and user/technical/API documentation.
- **Tests:** delete tests for removed behavior and refactor workspace, explorer, metadata, schema, controller, chat/tool, assignment, output, and orchestration tests that encode removed paths or Work Area ids.

## Features

- **Workspace descriptor:** use a small internal value containing closed kind, validated key, canonical identity, and trusted resolved root. Canonical identities are exactly `agent/<id>`, `chat/<id>`, and `user/<name>`. Raw client paths never become workspace roots, and services do not reverse-engineer identity from a `Path`.
- **Agent workspaces:** `workspace/agent/<agentId>` is the agent’s persistent filesystem root. Validate that the referenced agent exists. A read/list may behave as empty when its directory is absent; a successful file-producing or execution operation may create it. Do not generate Home, Work Areas, work, runs, projects, scratch, or starter `AGENTS.md` content.
- **Chat workspaces:** `workspace/chat/<chatId>` holds conversation files. Validate that the referenced chat exists. A read/list with no directory behaves as empty; a successful file-producing operation may create the root.
- **User workspaces:** `workspace/user/<name>` is created only by the explicit user-facing create operation. Its validated name is its stable identity. Listing includes only direct, non-symlink child directories with valid names. Selection or file operations against an undeclared/missing user workspace return not found and do not implicitly create it.
- **Workspace selection:** execution defaults to the bound agent’s workspace. An explicitly selected existing user workspace overrides that default. Selection payloads and persisted active references use `workspaceType` plus `workspaceKey`; only `agent` and `user` are valid execution types. The selected agent key must match the execution’s bound agent. Chat workspaces are never assignment, task, workflow, or job targets.
- **Path identity:** filesystem and annotation identity is the canonical normalized relative root beneath `workspace/`, never an opaque database id. File annotations combine canonical workspace identity with a normalized root-relative file path.
- **Flat layout:** Magenta does not scaffold application-owned subtrees inside agent or user roots. `outputs` is the only reserved runtime directory and is created lazily only by output publication. Removed `runs/`, `work/`, `workareas/`, `projects/`, Home, and scratch hierarchies must not return under aliases.
- **Output continuity:** task, workflow, job, and orchestration output remains confined and queryable beneath `<effectiveWorkspace>/outputs/`. Existing execution/output persistence retains durable task/job/run attribution, and publication uses collision-safe relative names without creating a `runs/<runId>` workspace hierarchy or job-owned roots.
- **Preserved explorer behavior:** retain current safety behavior, including size/type limits, UTF-8 checks, Markdown rendering, image viewing, download limits, collision handling, recursive-delete confirmation, symlink rejection, labels, tags, and audit records.
- **Standalone explorer API:** use one route family rooted at `/api/workspace/{type}/{id}`. File operations remain under `/files` and corresponding `/preview`, `/view`, `/download`, `/text`, `/markdown`, `/rename`, `/move`, `/copy`, `/delete`, `/labels`, `/tags`, and `/actions/recent` paths. Preserve existing operation-specific HTTP methods and payload semantics where compatible. `{type}` accepts only `agent`, `chat`, or `user`; `{id}` is one validated decoded path segment. Workspace-root rename, move, copy, and delete are forbidden.
- **Agent Files tab:** resolve only `workspace/agent/<agentId>`, load through HTMX, use the operational-console visual language, and retain the list/details/inspector/modal interaction model without Avatar branding.
- **User workspace management:** provide the minimum list, create, and execution-selection behavior needed for named user workspaces. Rename and destructive root deletion remain out of scope.
- **Projects:** retain project records and unrelated behavior, but projects no longer own filesystem roots or materialized links. Project association is metadata; the effective root is the selected user workspace or agent fallback.

## Settled Decisions

- The directive supersedes active Avatar and DB-backed Work Area specifications and decisions.
- Avatar is deleted, not hidden, disabled, or feature-flagged.
- Remove reserved Avatar agent-profile bootstrap behavior; do not create or reserve an `avatar` agent.
- Do not migrate `avatar.sqlite` into `magenta.sqlite`, delete user-owned `avatar.sqlite` files, or open/create them at application startup.
- Do not migrate legacy filesystem trees. Leave them untouched and exclude them from active root resolution.
- Resolve workspace roots centrally from a closed `WorkspaceKind` and validated key. Controllers never accept an absolute root.
- Use `/api/workspace/{type}/{id}/...`, not duplicated controllers or a query-string type/id multiplexer.
- Remove Work Area list, Home, mark, unmark, nested-mark, owner-type, and active-reference guard behavior.
- Preserve labels and action logging as current product capabilities. New active data uses canonical workspace identity, not a Work Area object/id.
- Do not attempt to expose old Work Area-keyed label/action history through the new roots when no reliable canonical mapping exists. Existing obsolete rows may remain physically present and inactive; startup must not depend on or mutate them.
- Pass the trusted workspace descriptor through explorer, metadata, and logging boundaries. Do not derive canonical identity from raw filesystem paths.
- `WorkspacePathLayout` remains the sole source of structural root paths. Callers do not hand-concatenate workspace-kind roots.
- Flat means no eager subtree scaffolding. Directories required by a successful file or output operation may be created lazily.
- Breaking removal of `/avatar`, `/api/work-areas`, Avatar tools, and Work Area-id payload fields is accepted. Do not retain compatibility endpoints or overloads that keep the removed model alive.
- Keep standard explorer CRUD and tab interactions HTMX-first. Retain JavaScript only for a narrow interaction where it is clearly simpler; remove Avatar transport/shell clients.
- Fresh databases omit Avatar and Work Area schema. Existing obsolete tables, columns, indexes, and rows may remain inert when required for non-destructive compatibility, but active repositories and runtime behavior must neither read nor write them.

## Constraints

- Preserve unrelated user edits and non-Avatar behavior.
- Keep controllers thin. Parse workspace kind and map HTTP statuses at the web boundary; keep root resolution, existence rules, confinement, mutations, metadata, and logging in services.
- Validate every decoded workspace key with `PlainPathSegmentValidator` semantics: nonblank, trimmed, non-dot-only, non-absolute, and without `/`, `\`, or decoded separator/traversal syntax. Reject encoded separators at the web boundary.
- Keep every file-relative path under its resolved root after normalization and applicable real-path checks. Reject traversal, symlink escape, symbolic directory trees in recursive operations, and mutation of the workspace root.
- Never expose or accept a raw filesystem root as a replacement for Work Area ids.
- Do not reintroduce Home, Work Areas, project links, job workspaces, assignment work directories, runtime temp trees, scratch, or run aliases through another service.
- Do not auto-generate `AGENTS.md` or starter content.
- Keep `WorkspaceFileMetadataRepository`, `WorkspaceFileMetadataService`, `WorkspaceFileActionLogRepository`, and `WorkspaceFileActionRecord` capabilities, adapted to canonical identity and schema.
- Preserve preview/edit/download limits and controlled `400`, `404`, and `409` responses where distinguishable.
- A valid existing agent/chat with no physical root has an empty readable workspace. A missing agent/chat entity is `404`. A missing user workspace is `404` for reads, mutations, and selection.
- Keep SimplyPages composition and the existing agent operational shell. Avoid raw page-level HTML where reusable components/fragments fit.
- Do not add a generalized workspace framework, migration engine, compatibility abstraction, or speculative API.
- Preserve unrelated `magenta.sqlite` data. Startup must work for a fresh database and for an existing database containing obsolete Avatar/Work Area-era schema without relying on that schema.
- Update end-user docs for workspace and Files-tab behavior, technical docs for path/schema/runtime changes, and API docs for replacement routes and payloads.

## Scope

**In scope**

- Complete Avatar package, web, tools, resources, tests, navigation, docs, configuration, datasource, and schema-initializer removal.
- Three-kind workspace layout, descriptor, centralized resolution, and lifecycle rules.
- Removal of active DB-backed Work Areas and Work Area API/UI behavior.
- Refactoring all active Work Area-id consumers required to keep chat, agents, tasks, workflows, jobs, outputs, selectors, and orchestration functional.
- Standalone explorer API/service/fragments, metadata, logging, and agent Files tab.
- Minimal user workspace list/create/selection behavior.
- Fresh-schema and non-destructive existing-schema compatibility.
- Living specifications, decisions, knowledge, package guides, changelog, and end-user/technical/API docs.
- Focused tests, broad tests, startup validation, API validation, and delegated browser validation.

**Out of scope**

- Migration/import of Avatar profile, organizer, planner, note, todo, calendar, fact, event, or dashboard data.
- Automatic relocation of files from Home, Work Area, project, run, job, scratch, or old chat layouts.
- Canonicalization or reactivation of historical Work Area-keyed metadata/action rows without a reliable mapping.
- A replacement personal dashboard, organizer, planner, Avatar chat client, or Avatar tool suite.
- User-workspace rename, root deletion, sharing, permissions, quotas, versioning, or synchronization.
- A mounted generic browser UI for every workspace kind; the required mounted UI is the agent Files tab, while the typed API supports all three kinds.
- Broad redesign of projects, jobs, tasks, workflows, chat, skills, or the agent console beyond required workspace-contract changes.
- SimplyPages changes unless a demonstrated library defect blocks the explorer UI and is handled through the project’s library workflow.

## Assumptions

- Agent and chat records have authoritative existence checks independent of directory existence.
- `agentId`, `chatId`, and user workspace `name` can be represented as validated URL-decoded single segments under existing validator semantics.
- Agent workspace fallback is always available for a valid bound agent; a user workspace must be explicitly created before selection.
- Existing project associations remain useful metadata despite removal of project-owned roots and links.
- Generic `Workspace` records, links, and leases are implementation details, not required filesystem identity. They remain only for demonstrated non-WorkArea use.
- Preserving labels and action logs means preserving the active feature and unrelated database data; it does not require making obsolete Work Area-keyed history addressable through unrelated new canonical roots.
- Obsolete Avatar and Work Area artifacts may remain in user-owned databases/filesystems, but active code, schema initialization, routes, prompts, and UI do not depend on them.
- Existing execution/output records can retain run attribution independently of a run-owned filesystem hierarchy.
- “All non-Avatar controllers stay intact” preserves their product responsibilities, not obsolete request fields or internal Work Area calls.

## Recommended Direction

- Introduce one closed workspace scope/descriptor used from web parsing through service execution. Validate kind/key once, resolve through `WorkspaceDirectoryService`, and keep raw `Path` values inside trusted boundaries.
- Make `WorkspacePathLayout` expose only validated structural root factories and the retained structural names. Route every consumer through it.
- Refactor `WorkAreaExplorerService` into a workspace-neutral explorer service, preserving operation records and safety rules while removing owner-root and nested-mark behavior. Rename Work Area-branded fragments/classes where they remain active.
- Re-key metadata and action-log APIs to canonical workspace identity plus root-relative path. Fresh schema contains canonical columns/indexes only; existing obsolete structures remain tolerated and inactive.
- Consolidate HTTP handling beneath `/api/workspace/{type}/{id}` and use the same descriptor for API responses and HTMX fragments.
- Replace every active `avatar-*` explorer id/class/route with workspace-neutral naming and mount the agent-scoped fragment through `/agents/{id}` tabs.
- Implement user workspace creation as validated direct-directory creation beneath `workspace/user/`, with collision handling and no implicit creation during selection. List only valid, confined, non-symlink direct children.
- Route execution to an existing selected user root or the bound agent fallback. Remove Work Area, project-link, job, temp, and run-root assumptions from assignment, runner, output, file, shell, prompt, and diagnostics code.
- Publish outputs beneath the effective root’s lazy `outputs/` directory with collision-safe paths, while retaining execution attribution in durable output/execution records.
- Delete dead wrappers after consumers move to centralized resolution. Do not retain no-op or throwing legacy methods.
- Update living specifications and current docs to the new contract. Preserve decision history by marking prior Avatar/Work Area decisions superseded rather than erasing it.

## Validation

- `mvn test` passes after deleting removed-behavior tests and rewriting affected tests.
- A bounded `mvn spring-boot:run` smoke check starts the application context without Avatar datasource/schema beans or missing workspace dependencies.
- Fresh-schema tests prove no Avatar schema initialization, active `work_areas` table contract, or Work Area foreign key is required or created.
- Existing-schema tests prove startup and active behavior work with obsolete Avatar/Work Area-era tables/columns present, without active reads/writes or destructive changes to unrelated data.
- Static searches find no active production-code, resource, configuration, test, current-doc, or living-specification references to the Avatar package/controllers/components/tools/assets/schema, `/avatar`, `avatar.sqlite`, `WorkArea`, `WorkAreaService`, `WorkAreaRepository`, `/api/work-areas`, or removed layout methods/constants. Clearly superseded historical records and inert legacy database artifacts are allowed.
- `WorkspacePathLayout` tests assert exactly these workspace roots:
  - `workspace/agent/<agentId>`
  - `workspace/chat/<chatId>`
  - `workspace/user/<name>`
- `WorkspaceDirectoryService` tests cover all roots, entity/existence behavior, explicit user creation, direct-child listing, invalid segments, traversal, symlink confinement, no starter `AGENTS.md`, no eager subdirectories, and lazy agent/chat behavior.
- Explorer service tests cover list, inspect, preview, create, save, rename, move, copy, delete preflight/execute, labels, logs, collisions, invalid UTF-8, limits, traversal, symlink escape, recursive symbolic trees, and root-mutation rejection through trusted descriptors.
- API tests cover every workspace type and preserved operation, invalid type/id/path, missing entities/roots/files, empty lazy roots, undeclared user workspaces, collision status, download limits, encoded separators, and absence of Work Area management routes.
- Assignment/task/workflow/job/output tests prove Work Area ids are absent from active contracts; agent fallback and existing user selection resolve correctly; chat selection is rejected; no project/job/run hierarchy is recreated; and output publication remains confined, collision-safe, attributable, and queryable.
- UI/controller tests prove `/agents/{id}` exposes an HTMX Files tab with workspace-neutral IDs/classes/routes and no Avatar asset dependency.
- Delegated Playwright validation runs against the live application with focused desktop and mobile screenshots of the Files tab. It covers tab loading, navigation, create/edit/rename/copy/move/delete/tag flows, empty state, errors, modal/inspector updates, mobile stacking, alignment, density, wrapping, overflow, hierarchy, and operational-console consistency.
- Browser validation confirms standard file CRUD is HTMX-driven and any retained JavaScript has a narrow documented justification.
- Final diff review confirms no unrelated removals, placeholders, compatibility stubs, accidental API growth, or incomplete specification/documentation/changelog closeout.

## Open Questions

No blocking product questions remain. Implementation discovery must answer these repository-specific checks under the settled rules:

- Identify whether generic `Workspace`, link, and lease records have a concrete non-WorkArea consumer. Retain only demonstrated behavior; canonical path identity remains authoritative.
- Identify obsolete Work Area-era tables, columns, indexes, and rows that must be tolerated for existing-database startup. Do not reactivate them or destructively remove unrelated data.
- Identify every output consumer that assumes run, job, project-link, or Work Area roots. Refactor each to the effective agent/user workspace and lazy `outputs/` contract while preserving durable execution attribution.
- Identify where user workspace selection belongs in existing assignment/agent configuration persistence. Use the settled kind/key contract rather than adding another identity model.

## Sign-off

This handoff is approved for implementation only under the contracts above. Completion requires Avatar to be absent from active code, routes, resources, configuration, tests, navigation, and current documentation; DB-backed Work Areas to be absent from active schema and runtime behavior; all active roots to follow the three-kind path contract; the explorer and agent Files tab to remain safe and functional; execution and output behavior to remain confined and attributable; specifications and decisions to reflect the superseding direction; and all required automated, startup, API, and delegated browser validation to pass without unresolved defects in the changed path.

**Explicit sign-off: Corrective review complete; this corrected handoff is the authoritative implementation handoff.**
