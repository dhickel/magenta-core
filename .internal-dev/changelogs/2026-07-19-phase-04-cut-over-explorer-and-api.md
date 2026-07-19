# phase-04-cut-over-explorer-and-api

## Date

2026-07-19

## Git Commit

2cf9c0fdafd662910ecec2ba28ab702ec0c67a86

## Change Summary

Cut over the workspace file explorer, metadata, and action logging to canonical workspace identity. Removed the Work Area adapter and controller. Created a typed `/api/workspace/{type}/{id}` API family with user workspace collection operations. Replaced active annotation and action-log persistence with new canonical-identity-keyed tables while leaving obsolete tables inert for warm databases.

## Files

### Created
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileException.java` — domain exception with INVALID/NOT_FOUND/CONFLICT reasons
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java` — typed `/api/workspace/{type}/{id}` controller with user collection and file operations

### Deleted
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaExplorerService.java`
- `src/main/java/io/mindspice/magenta2/api/web/WorkAreaController.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaExplorerServiceTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/WorkAreaControllerTest.java`

### Updated
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java` — integrated metadata/action logging and typed errors; validator repair added exact-parent creation, no-root-on-invalid safeguards, virtual root inspection, strict traversal handling, root-copy rejection, label target validation, transactional metadata/action updates, package-private raw-root operations, and one deprecated transitional `ProjectBridge` without a public raw-`Path` service method
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileMetadataRepository.java` — new `workspace_file_annotations` table, canonical `workspace_identity`, escaped subtree queries, removed WORK_AREA_LABEL bootstrap
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileMetadataService.java` — descriptor-based API, canonical identity throughout
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileActionLogRepository.java` — new `workspace_file_action_log` table, canonical identity
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileActionRecord.java` — removed Work Area fields, added workspaceIdentity
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileLabelAssignment.java` — removed Work Area fields, added workspaceIdentity
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java` — switched from WorkAreaExplorerService to WorkspaceExplorerService + WorkspaceDirectoryService
- `src/main/resources/schema.sql` — added `workspace_file_annotations` and `workspace_file_action_log` tables and indexes; removed obsolete annotation/action declarations from fresh schema while warm tables remain untouched

### Tests
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerServiceTest.java` — descriptor boundary and full operation coverage across agent/chat/user, lazy root, exact-parent creation, reserved outputs, symlink rejection, limits, collisions, labels/tags/actions, and metadata follow
- `src/test/java/io/mindspice/magenta2/api/web/WorkspaceControllerTest.java` — CRUD, labels, actions, image view, error mapping, encoded identity rejection, and path-free response DTO coverage
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileMetadataRepositoryTest.java` — 4 tests for schema, CRUD, identity isolation, escaped subtree queries
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileMetadataServiceTest.java` — 2 tests for add/remove action logs and follow helpers
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileActionLogRepositoryTest.java` — 2 tests for record attribution and identity scoping
- `src/test/java/io/mindspice/magenta2/api/web/PublicApiRouteBindingTest.java` — updated to use `/api/workspace/agent/{id}/files` and `/api/workspace/user`
- `src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java` — updated project file test to use new services
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryServiceTestSupport.java` — added descriptor factory method

## Behavioral Impact

- Explorer operations use canonical workspace identity (`agent/<id>`, `chat/<id>`, `user/<name>`) for annotations and action logging
- `/api/workspace/{type}/{id}` routes with user collection at `GET/POST /api/workspace/user`
- `/api/work-areas/*` routes removed
- Generic `/api/workspaces/*` routes (link/lease/workspace management) removed
- WorkspaceFileException maps INVALID→400, NOT_FOUND→404, CONFLICT→409
- Old annotation/action tables remain inert and are absent from fresh schema declarations; new rows write to `workspace_file_annotations` and `workspace_file_action_log`
- API response DTOs omit canonical workspace identity and roots while persistence records retain canonical attribution
- Invalid or missing-parent create requests do not create agent/chat roots
- Work Area label bootstrapping removed; only `note` system label seeded
- Project file browsing in OrchestrationController now uses WorkspaceExplorerService + WorkspaceDirectoryService

## Specification Impact

- `api.md`: `/api/workspace/{type}/{id}` routes are active; `/api/work-areas/*` and `/api/workspaces/*` absent
- `services.md`: Explorer, metadata, and action logging use canonical identity
- `schema.sql`: New canonical tables added

## Risks

- Project-file browsing retains one explicitly authorized deprecated `ProjectBridge` carrying its legacy project root. `WorkspaceExplorerService` itself exposes no public method accepting `Path`; the bridge must be removed with project-root browsing in Phase 06 and must not be expanded.
- Execution and output services still reference Work Area identity (Phase 05 handles this).

## Follow-up Items

- Phase 05: Cut over execution selection, outputs, and tool binding to workspaceType/workspaceKey.
- Phase 06: Remove project-root browsing and the deprecated `ProjectBridge`.
