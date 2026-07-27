# Remove Backward-Compat Constructors and Dead Tests

## Context

The 8-phase workspace refactor (branch `20260719-filesystem-first-workspaces-avatar-removal`) deferred two sets of backward-compatibility constructors to a follow-up sprint. In addition, pre-existing `@Deprecated` constructors from earlier sprints remain in the codebase with no remaining callers or only test callers.

The user's directive: remove all backward-compat shims. If affected tests are low-value (e.g., dashboard tests for removed products, workflow tests that only exercise compat paths), delete them rather than rewriting.

## Targets — Three Tiers

### Tier 1: Sprint-Deferred Convenience Constructors (not @Deprecated, but shims)

These were deferred during Phase 05 to avoid rewriting ~18 test files:

**1A. `OutputArtifactService` secondary constructor**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java`
- Lines ~63-68: 3-arg constructor `(OutputArtifactRepository, WorkspaceDirectoryService, ObjectMapper)` that creates its own `new RootRelativePathService(directoryService)` internally
- Remove it. Update **15 call sites** across 4 test files to use the 4-arg canonical constructor:
  - `OutputArtifactServiceAttributionTest.java` (12 calls)
  - `WorkflowRunnerTest.java` (1 call)
  - `OutputArtifactPathSemanticsTest.java` (1 call)
  - `OutputControllerTest.java` (1 call)
- Change pattern: `new OutputArtifactService(repo, dirSvc, mapper)` → `new OutputArtifactService(repo, dirSvc, new RootRelativePathService(dirSvc), mapper)`

**1B. `OrchestrationTaskContext` convenience constructor**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationTaskContext.java`
- Lines 33-45: 9-arg constructor (omits `jobAssignmentId`, `jobRunId`, `runDisplayName`, `activeRuntimePath`)
- Remove it. Update all call sites across **9 test files** to use the canonical 13-arg record constructor, passing `null` for the 4 omitted fields:
  - `ChatServiceTest.java`
  - `PromptContextAssemblerTest.java`
  - `AgentFileToolServiceTest.java`
  - `AgentOperationalToolServiceTest.java`
  - `AgentToolAuthorizationServiceTest.java`
  - `AgentShellToolServiceTest.java`
  - `JobServiceTest.java`
  - `WorkflowRunnerTest.java`
  - `AgentsMdResolverTest.java`

### Tier 2: Pre-Existing @Deprecated Constructors With No Remaining Callers

These are safe to delete immediately after verifying no non-Spring callers exist:

**2A. `AssignmentService` deprecated 9-arg constructor**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java`
- Line 56-65: `@Deprecated` constructor taking `AuditRepository, PlanService, WorkflowService`
- No test callers found. Spring uses `@Autowired` canonical constructor.
- **Delete it.**

**2B. `OrchestrationRunnerService` deprecated 8-arg constructor**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java`
- Line 99-113: `@Deprecated` constructor taking `ChatService, AgentProfileService, WorkspaceDirectoryService, ProjectService, MagentaWorkExecutor`
- No test callers found. Spring uses `@Autowired` canonical constructor.
- **Delete it.**

### Tier 3: Pre-Existing @Deprecated Constructors WITH Active Callers

These require caller updates before removal:

**3A. `WorkflowDefinition` — two @Deprecated constructors**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowDefinition.java`
- 6-arg (line 42): missing `schemaVersion`, `maxConcurrency`, `routes`, `config`
- 7-arg (line 53): missing `schemaVersion`, `maxConcurrency`, `config`
- Callers to update with full canonical 9-arg constructor (adding `CURRENT_SCHEMA_VERSION`, `DEFAULT_MAX_CONCURRENCY`, `List.of()`, `Map.of()`):
  - `PublicRunSubmissionControllerTest.java` (2 calls — test-only, can delete if low value)
  - `OrchestrationController.java` (~9 calls — PRODUCTION, must update)
  - `WorkflowRunnerTest.java` (~18 calls — test, update or delete if redundant)
  - `WorkflowController.java` (1 call — PRODUCTION, must update)
  - `WorkflowRepositoryTest.java` (1 call — test)
  - `WorkflowService.java` (1 call — PRODUCTION, must update)
  - `WorkflowRepository.java` (2 calls — PRODUCTION, must update)

**3B. `WorkflowNode` — two @Deprecated constructors**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowNode.java`
- 10-arg (line 42): missing `outputBindings`, `triggerBindings`
- 7-arg (line 54): missing `label`, `inputName`, `config`, `outputBindings`, `triggerBindings`
- Callers to update (all must pass `List.of()` for missing binding lists):
  - `OrchestrationController.java` (~2 calls — PRODUCTION)
  - `WorkflowRunnerTest.java` (~12 calls — test)

**3C. `WorkflowNodeRun` — one @Deprecated constructor**
- File: `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowNodeRun.java`
- 7-arg (line 39): missing `errorMessages`
- Callers to update (add `List.of()` for errorMessages):
  - `WorkflowRunner.java` (~9 calls — **PRODUCTION**, critical path)
  - `WorkflowRepository.java` (1 call — PRODUCTION)

### Tier 4: Test Files to Delete (Not Rewrite)

Per user directive: "if they are dashboard tests and the like most of those can be removed."

- **Avatar dashboard tests**: already removed in Phase 03. None remain.
- **WorkArea tests**: already removed in Phase 06.
- **OrchestrationTaskContext-only tests**: check if any test file exists solely to exercise the convenience constructor. If a test file's only purpose was validating the convenience path and it adds no unique coverage beyond what the canonical path tests, delete it.
- **PublicRunSubmissionControllerTest.java**: the 2 `WorkflowDefinition` calls using the deprecated constructor — if these test paths are already covered elsewhere, remove the tests rather than updating.

### Default Strategy for Test Updates

For each test file using a deprecated/removed constructor:
1. If the test class is **entirely about removed products** (Avatar, WorkArea, generic Workspace) → **delete the file**
2. If the test class adds **no unique coverage** beyond what other tests already cover with the canonical constructor → **delete the file**
3. Otherwise → **update call sites** to use the full canonical constructor

## Constraints

- Do NOT change any production behavior signatures, controller logic, or service behavior
- Do NOT introduce new @Deprecated annotations
- Do NOT add new constructors or factory methods
- All surviving tests must pass with `mvn test`
- Application must start successfully (`mvn spring-boot:run`)
- Commit on branch `20260719-filesystem-first-workspaces-avatar-removal`

## Out of Scope

- Product-level deferred features (`.internal-dev/specifications/deferred-features.md`)
- Removing the 4-arg `AssignmentService` constructor (not @Deprecated, still needed)
- htmx webjar fix (already committed)
- Playwright test failures (pre-existing)
- Any behavior or API changes
