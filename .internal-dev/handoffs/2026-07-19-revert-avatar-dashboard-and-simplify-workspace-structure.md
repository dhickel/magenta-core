# Revert Avatar Dashboard and Simplify Workspace Structure

## Directive

Remove the out-of-scope avatar/user dashboard features while preserving the file explorer. Replace the complex directory structure and the separate avatar SQLite database with a simple flat workspace layout.

## New Workspace Structure

```
<dataRoot>/
  workspace/
    agent/<agentId>/        -- agent's own persistent workspace (flat, agent organizes itself)
    chat/<chatId>/          -- chat files workspace (lazy, created only when chat has files)
    user/<name>/            -- user-created workspaces agents can be assigned to
```

- Workspace path IS identity. No DB-backed WorkArea records needed.
- Agent workspaces are flat — no auto-created subdirectories.
- Chat workspaces are created lazily only when a chat needs files.
- User workspaces are explicitly created by users.

## Removal Scope

### 1. Avatar Database — `avatar/` package (entire directory)
Remove all ~31 files:
- `AvatarDataConfiguration.java` — separate SQLite datasource (`avatar.sqlite`)
- `AvatarSchemaInitializer.java` — schema initialization for avatar DB
- `AvatarRepository.java` (55KB) — JDBC repository for all avatar tables
- `AvatarService.java` — service layer
- Domain models: `AvatarProfile.java`, `AvatarDashboardRow.java`, `AvatarDashboardRowWidget.java`, `AvatarDashboardWidget.java`, `AvatarNote.java`, `AvatarTodo.java`, `AvatarEvent.java`, `AvatarCalendarItem.java`, `AvatarDailyTask.java`, `AvatarFact.java`, `AvatarSnapshot.java`, `AvatarPreference.java`, `AvatarPriority.java`
- Enums: `AvatarCalendarStatus.java`, `AvatarFactStatus.java`, `AvatarTaskStatus.java`, `AvatarTodoStatus.java`
- Planner models: `PlannerTask.java`, `PlannerSubtodo.java`, `PlannerCalendarProjection.java`, `PlannerRecurrence.java`, `PlannerRecurrenceMode.java`, `PlannerTaskLink.java`, `PlannerTaskStatus.java`
- `AvatarAgentProfileBootstrap.java`
- `AGENTS.md` (package guide)

### 2. Avatar Web Controllers
- `api/web/AvatarDashboardController.java` (1,347 lines)
- `api/web/AvatarDashboardComponents.java` (1,880 lines)

### 3. Avatar Tool Package — `ai/chat/tool/avatar/` (entire directory)
- `AvatarAssistantToolService.java`
- `AvatarAssistantTools.java`
- `AvatarAssistantToolConfiguration.java`
- `AvatarAssistantToolResponses.java`
- `AvatarAssistantToolAuthorizationService.java`

### 4. Avatar Static Resources
- `resources/static/css/avatar-dashboard.css` (30KB)
- `resources/static/js/avatar-chat.js`
- `resources/static/js/avatar-layout-edit.js`
- `resources/static/js/avatar-shell.js`

### 5. Avatar Schema Resource
- `resources/avatar-schema.sql`

### 6. Nav Link
- Remove the Avatar home card link in `FrontendController.java`

### 7. Test Files
Remove all tests that correspond to removed production code:
- `test/.../avatar/` directory (all tests)
- `test/.../api/web/AvatarDashboardControllerTest.java`
- `test/.../ai/chat/tool/avatar/AvatarToolsTest.java`

## Refactor Scope

### 1. `WorkspacePathLayout.java`
Simplify to the new flat structure. Remove:
- `AGENTS`, `PROJECTS`, `HOME`, `WORKAREAS`, `WORK`, `RUNS`, `ROOT_ALIAS`, `RUN_ALIAS`, `FILES`
- All legacy paths (`LEGACY_RUNTIME`, `LEGACY_TASK_RUNS`, `LEGACY_WORKFLOW_RUNS`, `LEGACY_JOBS`, `LEGACY_SCRATCH`)
- All methods referencing removed constants

Keep only:
```java
public static final String WORKSPACE = "workspace";
public static final String OUTPUTS = "outputs";

// New simple paths:
static Path agentWorkspace(String agentId)     -> workspace/agent/<agentId>
static Path chatFiles(String conversationId)   -> workspace/chat/<conversationId>
static Path userWorkspace(String name)         -> workspace/user/<name>
```

### 2. `WorkspaceDirectoryService.java`
Radically simplify:
- Remove legacy migration (`migrateLegacyAgentDirs`)
- Remove project links (`materializeAssignmentProjectLink`, etc.)
- Remove legacy agent paths (`agentHome`, `agentOutputRoot`)
- Remove job compat (`jobWorkspace`, `jobOutput`, `jobAssignmentOutput`, `jobAssignmentWorkspace`)
- Remove task/workflow temp (`taskTemp`, `workflowTemp`)
- Remove `agentScratch`, `scratchDir`, `agentProjectLinks`, `agentWorkspaceOutputs`, `workDir`, `outputsDir`, `runsDir`
- Remove AGENTS.md starter file generation
- Remove `resolveInputPath`, `deleteTempDir`, `requireAssignmentProjectLinkTarget`, `removeAssignmentProjectLink`

Keep only:
- `dataRoot` field and constructor
- Confinement helpers (`confined`, `confinedChild`)
- `ensureDir`
- New simple methods: `agentWorkspace(String agentId)`, `chatFiles(String conversationId)`, `userWorkspace(String name)`
- `dataRoot()` accessor

### 3. Remove WorkArea DB Entity
Delete these files:
- `WorkArea.java` — DB record
- `WorkAreaRepository.java` — JDBI repository
- `WorkAreaService.java` — DB-backed CRUD service

### 4. Refactor `WorkAreaExplorerService.java`
Currently resolves WorkArea via `workAreaService.get(workAreaId)` to get the directory root path. Refactor to accept a workspace root path directly from the controller. The controller now resolves `workspace/agent/<id>/`, `workspace/chat/<id>/`, or `workspace/user/<name>/` via `WorkspaceDirectoryService` and passes the `Path` to the explorer service.

Method signature changes:
- Replace `String workAreaId` parameters with `Path workspaceRoot`
- Remove dependency on `WorkAreaService`
- Keep all file operations (list, preview, create, rename, move, copy, delete, labels, tags)

### 5. Refactor `WorkAreaController.java`
Change routes from `/api/work-areas/{workAreaId}/files/...` to workspace-type-based routes:
- `GET    /api/workspace/agent/{agentId}/files` — list agent workspace
- `GET    /api/workspace/chat/{chatId}/files` — list chat workspace  
- `GET    /api/workspace/user/{name}/files` — list user workspace
- Plus all file operations (preview, create, rename, move, copy, delete, labels, view, download, save)
- Remove WorkArea owner-type operations (ensureHome, mark, unmark, markNested, list by owner)

Or alternatively, use a unified approach:
- `GET    /api/workspace/files?type=agent&id=<agentId>&path=.`
- `GET    /api/workspace/files?type=chat&id=<chatId>&path=.`
- `GET    /api/workspace/files?type=user&id=<name>&path=.`

### 6. Repurpose `WorkAreaExplorerFragments.java`
Rewrite the HTMX fragments for the file explorer to be standalone (not avatar-bound). Remove all `avatar-` prefixed CSS classes and IDs. The file explorer UI should mount as a new tab on the existing agent operational dashboard under the agent detail shell (the `AppNavigation`-based agent console at `/agents/{id}`).

- The agent dashboard already has a tabbed shell (Overview, History, etc.). Add a "Files" tab.
- The files tab loads the file explorer fragment via HTMX.
- Fragment IDs change from `avatar-workarea-explorer-*` to something like `agent-file-explorer-*`.

### 7. `WorkspaceService.java`
Simplify or remove. Currently wraps `WorkspaceDirectoryService` with assignment-aware methods. After removal of WorkArea DB entities, evaluate if this service still has purpose. If it only delegates to `WorkspaceDirectoryService`, remove it and have consumers call `WorkspaceDirectoryService` directly.

### 8. Update references to removed code
Search and update all imports/references to:
- `AvatarService`, `AvatarRepository`, `AvatarDataConfiguration`, `AvatarSchemaInitializer`
- `AvatarDashboardController`, `AvatarDashboardComponents`
- `WorkArea`, `WorkAreaService` (the DB one), `WorkAreaRepository`
- `WorkspacePathLayout` legacy methods
- `WorkspaceDirectoryService` removed methods

Key consumers that import from these:
- `ChatService` — may reference avatar tools
- `PromptContextAssembler` — may reference avatar context
- `ToolAccessPolicy` — may reference avatar tools
- `AgentOperationalToolService` — may reference avatar
- `PlanService` — may reference WorkArea
- `OrchestrationRunnerService` — may reference workspace services
- `JobService` — may reference workspace legacy methods
- `AgentOrchestrationController` — may reference avatar

### 9. Test File Updates
- Remove: `test/.../avatar/`, `AvatarDashboardControllerTest.java`, `AvatarToolsTest.java`
- Refactor: `WorkAreaControllerTest.java`, `WorkAreaExplorerServiceTest.java`, `WorkAreaServiceTest.java`
- Update: `WorkspaceFileActionLogRepositoryTest.java`, `WorkspaceFileMetadataRepositoryTest.java`, `WorkspaceFileMetadataServiceTest.java`, `WorkspacePathSegmentValidationTest.java`, `WorkspaceRepositorySchemaMigrationTest.java`
- Update any other test that references removed classes/methods: `ChatServiceTest.java`, `PromptContextAssemblerTest.java`, `ToolAccessPolicyTest.java`, `AgentOperationalToolServiceTest.java`, `AgentToolAuthorizationServiceTest.java`, `AgentOperationalToolConfigurationTest.java`, `AssignmentContextServiceTest.java`, `AgentsMdResolverTest.java`, `OutputDirectoryServiceTest.java`

### 10. Specification and Knowledge Updates
- Update `architecture.md` — remove ARCH entries for avatar data ownership, avatar dashboard, avatar runtime boundary, Work Area DB
- Update `services.md` — remove avatar service entries, update workspace service entries
- Update `web.md` — remove avatar dashboard, avatar shell, avatar visual contract entries. Add agent file explorer tab entry.
- Update `simplypages.md` — remove avatar layout editing entry
- Update `decisions.md` — mark avatar-related decisions as superseded
- Archive avatar-related knowledge files or mark as superseded
- Update `index.md` if needed

## What Stays Intact

- `WorkAreaExplorerService.java` (refactored to use paths, not DB WorkAreas)
- `WorkspaceFileMetadataRepository.java` and `WorkspaceFileMetadataService.java`
- `WorkspaceFileActionLogRepository.java` and `WorkspaceFileActionRecord.java`
- `WorkspaceDirectoryService.java` (simplified, confinement helpers preserved)
- `WorkspacePathLayout.java` (simplified)
- Chat service, chat memory, chat sessions
- Agent profiles, agent orchestration
- Skills system
- All non-avatar controllers
- SimplyPages library usage

## Validation

- Run `mvn test` to confirm all tests pass
- Run `mvn spring-boot:run` with timeout to confirm application context starts
- Verify no references to `avatar/` package remain
- Verify no references to `WorkArea` DB entity remain
- Verify `WorkspacePathLayout` only has the three new path methods
- Verify `WorkspaceDirectoryService` only has the simplified method set
- Verify file explorer API works against the new workspace paths
