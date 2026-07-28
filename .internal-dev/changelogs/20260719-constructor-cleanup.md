# Remove 10 backward-compatibility constructors and migrate all callers

## Date

2026-07-19



## Git Commit

eb1f302396f75b3127a763da3a9f78af295446d2



## Change Summary

Removed 10 backward-compatibility constructor overloads across 8 production classes and migrated all callers to retained canonical constructors. All removed overloads either concealed canonical dependencies or filled newer record fields with defaults. No behavioral, API, persistence, or UI changes.

### Removed production constructors

- `OutputArtifactService(Repository, WorkspaceDirectoryService, ObjectMapper)` — now always requires explicit `RootRelativePathService`
- `OrchestrationTaskContext(agentId, agentName, jobId, projectId, workspaceType, workspaceKey, workspaceIdentity, runType, hostWorkspacePath)` — convenience ctor omitted `jobAssignmentId`, `jobRunId`, `runDisplayName`, `activeRuntimePath`
- `AgentProfileService(Repository, AiConfig, ObjectProvider<ChatToolRegistry>)` — test compatibility ctor omitted 4 ObjectProvider dependencies
- `AssignmentService(Repository, AgentProfileService, RuntimeSettingsService, JobService, AuditRepository, PlanService, WorkflowService)` — deprecated 7-arg overload
- `OrchestrationRunnerService(Repository, AssignmentService, JobService, ChatService, AgentProfileService, WorkspaceDirectoryService, ProjectService, MagentaWorkExecutor)` — deprecated 8-arg overload
- `WorkflowDefinition(id, title, summary, nodes, routes, createdAt, updatedAt)` — deprecated 7-arg overload omitted `schemaVersion`, `maxConcurrency`, `uiLayout`
- `WorkflowDefinition(id, title, summary, nodes, createdAt, updatedAt)` — deprecated 6-arg overload also omitted `routes`
- `WorkflowNode(key, type, planId, label, inputName, config, parallel, inputBindings, messageTemplate, resumePolicy)` — deprecated 10-arg overload omitted `inputPorts`, `outputPorts`
- `WorkflowNode(key, type, planId, inputBindings, messageTemplate, resumePolicy, parallel)` — deprecated 7-arg overload also omitted `label`, `inputName`, `config`
- `WorkflowNodeRun(nodeKey, type, status, inputValues, outputValues, startedAt, completedAt)` — deprecated 7-arg overload omitted `routeContext`

### Caller migrations

- 15 `OutputArtifactService` construction sites (12 in `OutputArtifactServiceAttributionTest`, 1 in `WorkflowRunnerTest`, 1 in `OutputControllerTest`, 1 in `PublicRunSubmissionControllerTest`) now pass explicit `RootRelativePathService`
- 4 production `OrchestrationTaskContext` callers (in `OrchestrationRunnerService`, `ChatService` x2, `OrchestrationController`) now use the canonical 13-component constructor with explicit `null` for omitted fields
- 9 `WorkflowDefinition` construction sites in `OrchestrationController`: create paths now use `CURRENT_SCHEMA_VERSION`, `DEFAULT_MAX_CONCURRENCY`, `Map.of()` for `uiLayout`; update paths preserve `current.schemaVersion()`, `current.maxConcurrency()`, `current.uiLayout()`
- 2 `WorkflowNode` construction sites in `OrchestrationController`: create path supplies empty `inputPorts`/`outputPorts`; update path preserves `old.inputPorts()` and `old.outputPorts()`
- 2 `WorkflowDefinition` stub sites in `PublicRunSubmissionControllerTest` migrated to canonical 10-component
- All `AgentProfileService` test fixtures (across 6 test files) migrated to 7-arg constructor

## Files



## Behavioral Impact

None. All callers now explicitly pass the same values that were previously injected by the removed compatibility constructors. Workflow update paths preserve existing canonical values (schema version, concurrency, routes, UI layout, ports, config, bindings) except for the field being intentionally changed. No controller routes, request/response payloads, service behavior, workflow execution semantics, workspace selection, output publication, persistence schema, Spring bean selection, or rendered UI behavior changed.

## Behavioral Impact



## Specification Impact

None. The removed constructors were backward-compatibility shims, not governed by any current specification contract. The canonical record constructors enforced by this cleanup are already the documented intended shape in `.internal-dev/specifications/architecture.md` and `.internal-dev/specifications/services.md`.

## Specification Impact



## Risks

Source-compatibility break for any out-of-repository code that directly constructs the affected classes via the removed overloads. This is intentional and authorized by the handoff directive. All in-repository callers have been migrated.

## Risks



## Follow-up Items

None.

## Validation Results

- **Focused tests**: 88/88 pass (OutputArtifactServiceAttributionTest, WorkflowRunnerTest, OutputControllerTest, PublicRunSubmissionControllerTest, AgentProfileControllerTest, AgentProfilePathSegmentValidationTest, RuntimeSettingsServiceTest, AgentOrchestrationControllerTest, EntityLookupServiceTest)
- **Full test suite**: 637/637 pass, 0 failures
- **Bounded startup**: Spring Boot context starts successfully on ephemeral port (3.9s)
- **Static residue search**: No removed constructor signatures found in production or test source; no @Deprecated annotations remain in orchestration package
- **Documentation review**: No docs files reference the removed constructors

## Follow-up Items
