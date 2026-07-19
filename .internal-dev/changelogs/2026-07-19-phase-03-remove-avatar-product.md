# phase-03-remove-avatar-product

## Date

2026-07-19

## Git Commit

5e6a3213293320c7e8805610163fa51927dd6e9c

## Change Summary

Phase 03 of the filesystem-first workspaces plan. Extracted reusable explorer service and fragment behavior to neutral ownership (`WorkspaceExplorerService`, `WorkspaceExplorerFragments`), moved explorer CSS to `orchestration.css`, then removed the Avatar product including its packages, controllers, tools, resources, and dedicated tests. Post-phase validation repaired the initial partial extraction by moving all reusable filesystem IO, confinement, limits, result models, and reserved-output enforcement into the descriptor-first service; neutral fragments now consume neutral result types.

## Files

### Created
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java` — Descriptor-first reusable filesystem explorer owner
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java` — Neutral workspace-file-* fragment renderer (dormant until Phase 07)

### Modified
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaExplorerService.java` — Reduced to Work Area identity/metadata/action adaptation over package-private neutral explorer operations, with no duplicate filesystem implementation or public raw-`Path` API
- `src/main/java/io/mindspice/magenta2/api/web/WorkAreaController.java` — Consumes bounded explorer download content rather than reading a service-exposed filesystem path
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionSurface.java` — Removed AVATAR enum value
- `src/main/java/io/mindspice/magenta2/api/web/FrontendController.java` — Removed Avatar home card
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/ToolAccessPolicy.java` — Narrowed isOperationalTool to agent_ prefix only
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalTools.java` — Removed all avatar_* tool methods
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolService.java` — Removed all avatar_* service methods, agentItem helper, SystemOverviewItem usage; cleaned imports
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolResponses.java` — Removed SystemOverviewItem record
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentToolAuthorizationService.java` — Removed requireAvatarSupervisor method, supervisorAgentId field, and @Value dependency
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatPendingMessageRepository.java` — Defensive surface() decoding for unknown legacy values
- `src/main/resources/static/css/orchestration.css` — Added workspace-file-* neutral explorer CSS (extracted from avatar-dashboard.css with renamed selectors)

### Deleted
- `src/main/java/io/mindspice/magenta2/avatar/` — Entire package (28 files)
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/avatar/` — Entire package (5 files)
- `src/main/java/io/mindspice/magenta2/api/web/AvatarDashboardController.java`
- `src/main/java/io/mindspice/magenta2/api/web/AvatarDashboardComponents.java`
- `src/main/java/io/mindspice/magenta2/api/web/WorkAreaExplorerFragments.java`
- `src/main/resources/avatar-schema.sql`
- `src/main/resources/static/css/avatar-dashboard.css`
- `src/main/resources/static/js/avatar-chat.js`
- `src/main/resources/static/js/avatar-layout-edit.js`
- `src/main/resources/static/js/avatar-shell.js`
- `src/test/java/io/mindspice/magenta2/avatar/` — Entire test package (5 files)
- `src/test/java/io/mindspice/magenta2/ai/chat/tool/avatar/` — Entire test package (1 file)
- `src/test/java/io/mindspice/magenta2/api/web/AvatarDashboardControllerTest.java`

### Updated Tests
- `AgentToolAuthorizationServiceTest.java` — Removed avatar supervisor test; updated constructor
- `AgentOperationalToolServiceTest.java` — Removed avatarAgentList test; updated constructor
- `AgentOperationalToolConfigurationTest.java` — Removed avatar tool names from registry test
- `ChatControllerTest.java` — Updated surface test: AVATAR now rejected
- `ChatServiceTest.java` — Replaced AVATAR surface with INTERNAL in test data
- `FrontendControllerTest.java` — Changed assertion to not contain /avatar
- `WorkAreaExplorerServiceTest.java` — Preserved transitional adapter, metadata, action-log, confinement, collision, and mutation regressions; proves no public raw-`Path` API
- `WorkspaceExplorerServiceTest.java` — Added descriptor-first lazy-root, user-root, confinement, symlink, collision, limit, reserved-output, descendant, and delete-confirmation coverage
- `WorkspaceExplorerFragmentsTest.java` — Added neutral type/key URL, stable ID/OOB, forbidden identity input, and Markdown fallback coverage
- `ToolAccessPolicyTest.java` — Proves normal `agent_*` callbacks remain available and are suppressed in planning mode
- `ChatPendingMessageRepositoryTest.java` — Proves historical unknown/`AVATAR` values decode non-fatally without row mutation
- `PublicApiRouteBindingTest.java` — Proves `/avatar` and representative descendants are unmapped and not redirected
- `MagentaRootConfigurationTest.java` — Proves fresh startup creates no `avatar.sqlite`, a pre-existing sentinel remains byte/mtime-identical, the primary datasource is healthy, and no Avatar bean is present

## Behavioral Impact

- AVATAR chat surface removed; requests with surface "AVATAR" now throw IllegalArgumentException
- Historical pending messages with AVATAR surface decode as null (non-fatal, not rewritten)
- Avatar supervisor operational tools no longer exist
- avatar_* tool prefix no longer recognized as operational in tool access policy
- Avatar dashboard, organizer, planner, chat rail, and layout editing removed
- /avatar and all descendant routes unmapped (400/404 from Spring, no redirect or tombstone)
- Home page no longer shows Avatar card
- Explorer behavior is owned by descriptor-first `WorkspaceExplorerService`; `WorkAreaExplorerService` remains a thin package bridge for legacy identity/metadata only
- Explorer root and `outputs/` mutations are rejected; missing agent/chat roots read empty and file-producing operations create them lazily; user roots are never implicitly created
- Bounded neutral downloads return defensive-copy bytes rather than exposing a raw filesystem path, including through the transitional Work Area controller path
- `WorkspaceExplorerFragments` uses neutral result types, `workspace-file-*` IDs/classes, typed type/key routes, and stable list/inspector/modal OOB targets, but remains unmounted
- Explorer CSS moved to orchestration.css with workspace-file-* selectors

## Specification Impact

Specification Impact: Phase 03 removes Avatar as a product per the filesystem-first workspaces handoff. 
- `web.md` — Avatar entries already marked as "removed" (Phase 01); no active entries changed
- `services.md` — WorkspaceExplorerService now owns explorer filesystem algorithms; WorkAreaExplorerService is transitional adapter
- `simplypages.md` — Neutral explorer composition entry reflects WorkspaceExplorerFragments
- `api.md` — Absent routes section already reflects /avatar absence (Phase 01)
- Package guides in avatar/ and ai/chat/tool/avatar/ deleted with packages

## Risks

- Phase 04 must cut over explorer routing and remove the transitional adapter before explorer fragments can be tested end-to-end
- Browser evidence covers the changed home and removed `/avatar` surface; the neutral explorer remains intentionally unmounted until Phase 07

## Follow-up Items

- Phase 04: Cut over explorer and API to typed workspace routes
- Phase 07: Mount WorkspaceExplorerFragments as agent Files tab
- Phase 08: Final repository-wide active-reference sweep
