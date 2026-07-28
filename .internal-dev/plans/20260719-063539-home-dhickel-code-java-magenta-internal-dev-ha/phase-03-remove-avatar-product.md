# Phase 03 — Remove Avatar Product

## Context

Avatar remains an active package, datasource/schema initializer, reserved profile bootstrap, dashboard and organizer/planner surface, chat-tool suite, static shell, and home card. Reusable explorer behavior is split across `WorkAreaExplorerService`, `WorkAreaExplorerFragments`, Avatar routes, and CSS embedded in `avatar-dashboard.css`. It must move to neutral ownership before the Avatar host is deleted.

## Goal

Create the neutral explorer service, renderer, styles, and regression handoff required by later phases, then remove Avatar as a product without removing normal agent behavior or touching user-owned Avatar data.

## In Scope

Create:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java`
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java`
- focused neutral explorer service and fragment tests

Update:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaExplorerService.java`
- `src/main/java/io/mindspice/magenta2/api/web/FrontendController.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionSurface.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatPendingMessageRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/ToolAccessPolicy.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentToolAuthorizationService.java`
- `AgentOperationalTools.java`, `AgentOperationalToolService.java`, and `AgentOperationalToolResponses.java`
- `src/main/resources/static/css/orchestration.css`
- affected shared frontend, route, chat, repository, tool, and retained context tests

Delete:

- `src/main/java/io/mindspice/magenta2/avatar/` in full, including its package guide
- `AvatarDashboardController.java`, `AvatarDashboardComponents.java`, and—after extraction—`WorkAreaExplorerFragments.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/avatar/` in full
- `src/main/resources/avatar-schema.sql`
- `avatar-dashboard.css`, `avatar-chat.js`, `avatar-layout-edit.js`, and `avatar-shell.js`
- `src/test/java/io/mindspice/magenta2/avatar/` and `src/test/java/io/mindspice/magenta2/ai/chat/tool/avatar/`
- `AvatarDashboardControllerTest.java` after reusable assertions move

Shared tests expected to change include `FrontendControllerTest`, `ChatControllerTest`, `PublicApiRouteBindingTest`, `ChatServiceTest`, `ToolAccessPolicyTest`, the three operational-tool test classes, `AgentToolAuthorizationServiceTest`, `MagentaRootConfigurationTest`, and the pending-message repository test.

## Out of Scope

- Activating `/api/workspace/{type}/{id}` or re-keying metadata/action persistence; Phase 04 owns that cutover.
- Removing Work Area controllers/persistence, project-root browsing, or the transitional explorer adapter; Phases 04 and 06 own those removals.
- Execution/output type-key cutover; Phase 05 owns it.
- Agent Files tab and selectors; Phase 07 owns them.
- Final docs, package-guide, knowledge, and living-record reconciliation; Phase 08 owns final closeout.
- Opening, importing, migrating, modifying, truncating, or deleting existing `avatar.sqlite`.

## Dependencies

- `phase-02-build-workspace-identity-foundation.md` has PASS.
- `WorkspaceKind`, `WorkspaceDescriptor`, `WorkspaceDirectoryService`, and retained `WorkspacePathLayout` factories exist.
- Phase 04 cannot start until this phase receives validator PASS.

## Constraints

- Avatar is removed, not hidden, redirected, feature-flagged, or replaced.
- Do not reserve, rename, disable, or auto-create an `avatar` agent. Removing bootstrap does not delete an existing ordinary agent with that id or name.
- Startup must not discover, open, create, migrate, or delete `avatar.sqlite`.
- `WorkspaceExplorerService` is the sole reusable explorer filesystem owner. Its public boundary uses a trusted `WorkspaceDescriptor` and a distinct normalized relative path; controllers never supply roots or canonical identities.
- A temporary Work Area bridge may remain only as a thin non-public adapter with no duplicate file IO/confinement implementation; Phase 04 removes it.
- Preserve list, inspect, preview, image view, bounded download, text/Markdown create/save, directory create, rename, move, copy, delete preflight/execute, label/tag and recent-action presentation, and OOB list/inspector/modal behavior.
- Preserve preview/edit/download limits, strict UTF-8, safe Markdown, collision handling, recursive-delete confirmation, symlink/symbolic-tree rejection, and confinement.
- Missing agent/chat roots read as empty without creation. Successful file-producing operations may lazily create the descriptor root. User-workspace existence remains owned by `WorkspaceDirectoryService`.
- Explorer mutation rejects the workspace root and `outputs` or descendants; reads may inspect `outputs`.
- Reject absolute/traversal paths, symlink components/escape, symbolic recursive trees, source-descendant move/copy, cross-workspace destinations, and collisions.
- Neutral fragments use `workspace-file-*` IDs/classes and server-derived type/key routes. They emit no `/avatar`, Work Area identity, raw root, or canonical-identity selector.
- Add no Avatar compatibility route, legacy overload, no-op/throwing stub, generalized provider, or replacement JavaScript transport.
- Preserve all normal `agent_*` tools and helpers they still use.
- JSON requests reject unsupported surfaces. Persisted unknown surfaces, including historical `AVATAR`, are non-fatal and are not rewritten.
- Keep each revision compilable and Spring constructor wiring valid.

## Implementation Steps

1. **Extract the neutral explorer before deleting any owner.**
   - Move filesystem algorithms, limits, result models, normalization, confinement, UTF-8/Markdown classification, collision handling, symbolic-tree rejection, and delete logic from `WorkAreaExplorerService` to descriptor-first `WorkspaceExplorerService`.
   - Reduce `WorkAreaExplorerService` to a transitional adapter for current consumers. It may resolve legacy Work Area state and adapt legacy metadata/actions, but it delegates all file behavior and exposes no public raw-`Path` API.
   - Move reusable toolbar, pathbar, table, inspector, modal, viewer/editor, tag, status, and OOB rendering into `WorkspaceExplorerFragments`, based on neutral service results and server-resolved kind/key.
   - Build dormant operation URLs from `/api/workspace/{type}/{key}` but do not register Phase 04 endpoints.
   - Move only explorer-owned CSS into `orchestration.css`; rename moved selectors to `workspace-file-*` and replace Avatar ancestor/utility/variable dependencies with neutral selectors or existing orchestration tokens.
   - Move reusable explorer assertions out of the Avatar controller test. Test neutral IDs/classes/routes and absence of Avatar, Work Area, root, and canonical-identity inputs.

2. **Delete the Avatar domain/persistence package.** Delete `src/main/java/io/mindspice/magenta2/avatar/`, including datasource configuration, schema initializer, bootstrap, records, repository, service, and guide. Remove resulting imports and constructor dependencies without touching disk data.

3. **Delete Avatar web ownership after extraction.** Delete the dashboard controller/components and old fragment renderer. Remove every `/avatar` mapping and Avatar resource URL. Add no redirect, tombstone, or temporary endpoint; leave neutral fragments unmounted.

4. **Delete Avatar assistant tools.** Delete the package and remove explicit provider, registry, configuration, or test wiring. Do not rename those tools into generic tools.

5. **Remove the Avatar chat surface safely.**
   - Remove `AVATAR` from `ChatSessionSurface` while keeping unsupported JSON input invalid.
   - Preserve defensive metadata parsing.
   - Make pending-message persisted-surface decoding treat unknown legacy values as absent/ignored instead of calling `valueOf` unchecked; do not mutate the row.
   - Test supported surfaces, rejected `AVATAR` requests, legacy metadata, and legacy pending rows.

6. **Remove Avatar discovery.** Remove the home card and assertions expecting it. Add no replacement dashboard or navigation entry.

7. **Remove Avatar supervisory operational tools.**
   - Delete every `avatar_*` method/annotation and corresponding service implementation.
   - Remove `requireAvatarSupervisor`, `magenta.avatar.supervisor-agent-id`, and fields/constructor parameters used only by that branch.
   - Remove response records/imports used only by deleted methods; retain anything referenced by `agent_*` tools.
   - Update wiring and tests to prove normal callbacks and authorization remain intact.

8. **Narrow tool policy.** Change operational-tool recognition from `agent_ || avatar_` to `agent_` only. Test planning/task suppression and supported normal/execution behavior, and prove no Avatar callback resolves.

9. **Delete schema/assets after CSS extraction.** Delete the Avatar SQL/CSS/JS files and all references. Confirm the neutral explorer CSS contains no `.avatar-*`, `.avatar-workarea-*`, or Avatar custom-property dependency.

10. **Delete removed-behavior tests after relocating coverage.** Finish shared test updates and preserve negative coverage for route absence, unsupported request input, defensive persisted reads, absent callbacks, normal agent callbacks, and explorer security/limit behavior.

11. **Add retained context/data-safety assertions.**
    - Extend `MagentaRootConfigurationTest` or another retained full-context test, not a recreated Avatar test package.
    - Start against a fresh temporary root and assert `avatar.sqlite` is not created.
    - Start separately with a non-SQLite sentinel named `avatar.sqlite`; compare bytes and relevant attributes after shutdown and prove it was not replaced or deleted.
    - Assert no Avatar package bean, datasource, JDBC template, schema initializer, repository/service/bootstrap, controller, or tool configuration exists by name or type; prove the primary datasource still initializes.

## Required Guides

- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AGENTS.md`
- closest workspace package guide
- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/simplypages.md`

Phase 01's superseding entries govern where retained guides/specifications still describe Avatar or Work Areas.

## Technical Guidance

- Extract by move, not copy. A descriptor-first service plus one package-internal transitional invocation is acceptable; the adapter owns legacy identity/metadata translation only.
- Normalize a relative path once at the neutral service boundary and carry it through results, adaptation, logging, and rendering. Never derive workspace identity from `Path`.
- Keep canonical identity out of fragment URLs/forms. Render only the server-known serialized kind and validated key.
- Preserve one modal host and stable OOB list/inspector/modal targets; duplicate IDs are a regression.
- Safe Markdown retains raw text access and visible non-fatal render errors. List/inspect must not read full content.
- Root and `outputs` guards apply to create/save/rename/move/copy/delete, including parent renames or moves that would create or mutate the reserved subtree.
- Remove dead methods, records, beans, and constructor parameters outright; leave no deprecated aliases, optional deleted-service providers, stubs, or commented registrations.

## Validation

1. Run focused neutral service/fragment tests covering empty lazy roots, missing user policy, create behavior, text/Markdown and download limits, invalid UTF-8, collisions, traversal, absolute paths, symlink escape/components, symbolic trees, descendant move/copy, root mutation, reserved `outputs`, recursive-delete confirmation, and OOB targets.
2. Run focused frontend, route, chat surface/metadata/pending-message, tool policy, authorization, registration, and operational-service tests.
3. Run retained fresh-root and sentinel context tests; confirm primary datasource health and no Avatar bean.
4. Run `mvn test`.
5. Run bounded startup against fresh and sentinel roots and compare sentinel bytes.
6. Verify with route mappings or MockMvc that `/avatar` and representative `/avatar/**` paths are unmapped, not redirected, and not served by fallback.
7. Run focused delegated live browser validation: desktop/mobile home screenshots showing no card, controlled unmapped `/avatar`, and visual-regression critique. No interaction may depend on deleted Avatar JavaScript.
8. Search active production/resources/tests for Avatar packages, `avatar_*`, `magenta.avatar`, `ChatSessionSurface.AVATAR`, supervisor references, `/avatar`, Avatar schema/assets, datasource/JDBC/schema/bootstrap/controller/tool bean names, and Avatar selectors/properties in neutral files. Only explicit negative tests and historical records deferred to Phase 08 may match.
9. Inspect the diff for removed `agent_*` behavior, duplicate explorer logic, public raw-root APIs, compatibility endpoints, placeholders, or unrelated edits.
10. Run the orchestration review-and-repair validator with `openai-codex/gpt-5.6-sol:medium`; Phase 04 remains blocked until PASS.

## Exit Criteria

- `WorkspaceExplorerService` is the descriptor-first reusable filesystem owner; `WorkAreaExplorerService` is only a compile-preserving transitional adapter without duplicate IO/confinement.
- Neutral fragments/styles preserve the explorer handoff using `workspace-file-*`, typed type/key URLs, stable modal/OOB behavior, and no Avatar/Work Area ancestry or root/canonical selector.
- Avatar package, datasource/schema/bootstrap, routes/components, tools/policy branches, assets/references, and dedicated tests are absent.
- `/avatar` and descendants are unmapped and no home/navigation surface links to them.
- Normal `agent_*` tools and supported chat behavior pass.
- `AVATAR` request input is rejected; historical unknown metadata/pending surfaces are non-fatal and unmodified.
- Fresh startup does not create `avatar.sqlite`; a pre-existing sentinel is unchanged; no Avatar bean exists.
- Focused tests, full suite, bounded startup, delegated browser checks, searches, and diff inspection pass.
- Commit the phase on the dedicated branch only after validator PASS, including applicable workflow records and without changing the eight phase filenames or plan-file set.
