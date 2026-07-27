# High-Level Handoff: Remove Backward-Compatibility Constructors and Dead Constructor-Only Tests

## Context

The filesystem-first workspace refactor left constructor overloads that conceal canonical dependencies or fill newer record fields with defaults. This cleanup intentionally removes source compatibility for those overloads, with no intended runtime, API, persistence, controller, Spring wiring, or UI behavior change.

Read-only inspection establishes the following source facts:

- `WorkflowDefinition` has a 10-component canonical record constructor containing `schemaVersion`, `maxConcurrency`, `routes`, and `uiLayout`.
- `WorkflowNode` has a 12-component canonical record constructor. Its compatibility overloads omit `inputPorts` and `outputPorts`; they do not omit output/trigger binding fields.
- `WorkflowNodeRun` has an eight-component canonical record constructor whose added field is `routeContext`, not `errorMessages`.
- `WorkflowRunner`, `WorkflowRepository`, `WorkflowService`, `WorkflowController`, `WorkflowRunnerTest`, `WorkflowRepositoryTest`, `OutputArtifactPathSemanticsTest`, and the inspected `OrchestrationTaskContext` callers already use canonical record construction where an earlier inventory expected migrations.
- `AgentProfileService` contains an explicitly labeled compatibility constructor omitted from the earlier inventory. It is in scope because the directive is to remove all identified backward-compatibility constructor shims.
- `PublicRunSubmissionControllerTest` provides distinct assignment-submission, validation, and SSE coverage. It is not dead and must be migrated rather than deleted.
- The inspected checkout points to branch `20260719-filesystem-first-workspaces-avatar-removal`.

The governing intended behavior remains the active filesystem-first workspace and workflow-v2 contracts in `.internal-dev/specifications/architecture.md`, `.internal-dev/specifications/services.md`, and the related workspace/workflow knowledge records.

## Objective

Remove every identified production backward-compatibility constructor shim, migrate every remaining repository caller of a removed signature to an approved retained constructor with explicit values, and retain tests that verify current behavior. Preserve observable behavior and all canonical field values while making omitted dependencies and defaults visible at construction sites.

For record types, callers must use the canonical record constructor. For services that intentionally retain both a minimal constructor and a full Spring constructor, the requirement is removal of the compatibility overload only; it is not a requirement to reduce the service to one constructor.

## Targets

### Production constructor declarations

Remove these ten overloads across eight production classes:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java`
  - Remove the three-argument overload that internally creates `RootRelativePathService`.
  - Retain the four-argument Spring constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationTaskContext.java`
  - Remove the nine-argument convenience constructor.
  - Retain the 13-component canonical record constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/agents/AgentProfileService.java`
  - Remove the three-argument constructor explicitly labeled as test compatibility.
  - Retain the seven-argument Spring constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java`
  - Remove the deprecated seven-argument compatibility constructor.
  - Retain the four-argument minimal constructor and full eight-argument Spring constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java`
  - Remove the deprecated eight-argument compatibility constructor.
  - Retain the existing non-deprecated minimal constructor and full Spring constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowDefinition.java`
  - Remove both deprecated six- and seven-argument overloads.
  - Retain the canonical 10-component record constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowNode.java`
  - Remove both deprecated seven- and 10-argument overloads.
  - Retain the canonical 12-component record constructor.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowNodeRun.java`
  - Remove the deprecated seven-argument overload.
  - Retain the canonical eight-component record constructor.

### Confirmed caller migrations

- `OutputArtifactService` three-argument construction:
  - Twelve sites in `OutputArtifactServiceAttributionTest.java`.
  - One setup site in `WorkflowRunnerTest.java`.
  - One fixture site in `OutputControllerTest.java`.
  - One `super(...)` site in `PublicRunSubmissionControllerTest.java`.
  - `OutputArtifactPathSemanticsTest.java` is already canonical and should remain unchanged unless exact nearby cleanup is required.
- `WorkflowDefinition` compatibility construction:
  - Nine production sites in `OrchestrationController.java`.
  - Two stub-definition sites in `PublicRunSubmissionControllerTest.java`.
- `WorkflowNode` compatibility construction:
  - Two production sites in `OrchestrationController.java`.
- `AgentProfileService` compatibility construction:
  - Direct construction in `RuntimeSettingsServiceTest.java`, `AgentProfileControllerTest.java`, and `AgentProfilePathSegmentValidationTest.java`.
  - Compatibility `super(...)` calls in `PublicRunSubmissionControllerTest.java`, `AgentOrchestrationControllerTest.java`, and `EntityLookupServiceTest.java`.
- Read-only inspection found no remaining callers of the removed `OrchestrationTaskContext`, `AssignmentService`, `OrchestrationRunnerService`, or `WorkflowNodeRun` signatures. Compilation and final targeted searches must confirm this.

## Features

- No backward-compatibility construction path remains for any targeted overload.
- Record callers explicitly use complete canonical record construction.
- `RootRelativePathService` is explicitly supplied wherever `OutputArtifactService` is directly constructed.
- Workflow-v2 schema, concurrency, routes, UI layout, ports, bindings, and route context are explicit at relevant construction sites.
- Approved non-compatibility minimal constructors for `AssignmentService` and `OrchestrationRunnerService` remain intact.
- Deprecated annotations and compatibility comments belonging solely to deleted overloads are removed.
- Meaningful current-product tests remain. A test may be deleted only if its sole assertion is the existence or delegation behavior of a removed constructor and it has no distinct behavior coverage.
- Removed defaults are not recreated through replacement convenience constructors, factories, builders, adapters, or test-only production APIs.

## Settled Decisions

- Current source is authoritative for canonical signatures and field meanings. Stale arity or field descriptions from earlier handoffs must not be copied into code.
- `AgentProfileService`'s labeled compatibility constructor is included so the constructor-shim cleanup is complete.
- New workflow definitions explicitly use `WorkflowDefinition.CURRENT_SCHEMA_VERSION`, `WorkflowDefinition.DEFAULT_MAX_CONCURRENCY`, explicit routes, and explicit `uiLayout`.
- Mutations of an existing workflow definition preserve `current.schemaVersion()`, `current.maxConcurrency()`, `current.routes()`, and `current.uiLayout()` except for the field intentionally changed by that operation. Update paths must not reset existing values to defaults.
- New workflow nodes explicitly provide empty `inputPorts` and `outputPorts` when no ports exist.
- Updates to existing workflow nodes preserve `old.inputPorts()`, `old.outputPorts()`, `old.config()`, `old.inputBindings()`, and every other field not intentionally changed by the request.
- Canonical `WorkflowNodeRun` callers provide the actual route context. A genuinely new pending state with no route context uses an explicit empty list.
- Existing tests with unique workflow, output-attribution, workspace-confinement, submission, validation, SSE, controller, or persistence coverage are migrated, not deleted.
- `PublicRunSubmissionControllerTest.java` and `WorkflowRunnerTest.java` remain.
- No active test found during inspection exists solely to exercise a targeted convenience constructor. No test deletion is expected unless implementation inspection proves otherwise.
- Negative regression tests asserting that Avatar, Work Area, or retired generic-workspace surfaces remain absent are not obsolete-product tests and are outside this deletion request.
- The non-deprecated four-argument `AssignmentService` constructor remains.
- The non-deprecated minimal `OrchestrationRunnerService` constructor remains.
- Package-private minimal test constructors in `AuditService` and `RequestResolver` are not backward-compatibility shims and remain.

## Constraints

- Do not change controller routes, request/response payloads, service behavior, workflow execution semantics, workspace selection, output publication, persistence schema, Spring bean selection, or rendered UI behavior.
- The only intended production signature changes are removal of the explicitly targeted compatibility constructors.
- Do not add `@Deprecated` annotations, constructors, factories, builders, adapters, or helpers that recreate the removed shortcuts.
- Do not replace explicit canonical dependencies with production-side `new` calls or service-location behavior.
- Preserve constructor argument meaning exactly. Do not use positional `null` values that shift fields or erase existing record state.
- Use empty collections or `null` only where they reproduce the removed overload's behavior for a newly created object. Existing-object update paths must copy unchanged canonical fields from that object.
- Preserve the closed `AGENT`/`CHAT`/`USER` workspace model, descriptor authority, root-relative artifact-path behavior, workflow-v2 schema, and route context.
- Keep unrelated edits and tests intact.
- Perform implementation on branch `20260719-filesystem-first-workspaces-avatar-removal`; verify the checked-out branch during implementation rather than relying only on this inspection.
- Complete repository closeout requirements for finalized code changes, including an internal changelog and commit. Record `Specification Impact: none` unless implementation reveals an actual intended-contract change.
- Review relevant `docs/` references. Update technical documentation only if it describes a removed constructor or changed construction contract; otherwise record that documentation impact is absent.
- Do not include product-level deferred features, the prior HTMX webjar fix, pre-existing Playwright failures, or unrelated cleanup.

## Scope

### In scope

- Removing the ten identified compatibility/convenience constructor overloads across the eight production classes.
- Updating direct `new` expressions, subclass `super(...)` calls, test fixtures, anonymous test subclasses, and production workflow-editor construction sites that depend on removed overloads.
- Removing imports, comments, or annotations made unused by this exact cleanup.
- Adding or extending focused regression assertions needed to prove that workflow-editor migrations preserve canonical definition and node state.
- Deleting a constructor-only test only if it has no distinct behavioral assertion after migration analysis.
- Focused tests, full test suite, bounded application startup, static residue checks, documentation-impact review, final diff review, internal changelog, and commit.

### Out of scope

- Changes to runtime behavior, REST/SSE contracts, UI rendering, HTMX interactions, database schemas, workflow validation rules, or filesystem semantics.
- Removal of non-compatibility constructors, including the four-argument `AssignmentService` constructor and the minimal non-deprecated `OrchestrationRunnerService` constructor.
- Broad test pruning or deletion based only on age, naming, use of stubs, or assertions that retired products remain absent.
- Refactoring test architecture beyond the smallest explicit retained-constructor migration and required field-preservation assertions.
- Product deferred features, migration tooling, unrelated deprecation cleanup outside the identified constructor audit, and Playwright remediation.

## Assumptions

- Repository source and tests represent all locally discoverable constructor consumers. Removal is authorized even though it intentionally breaks source compatibility for untracked external callers.
- For newly created objects only, explicit empty collections or `null` values matching a removed constructor's delegation preserve that overload's behavior.
- Existing workflow update paths may contain non-default schema, concurrency, routes, ports, config, bindings, or UI layout. These values must be preserved even if common fixtures use defaults.
- Spring uses the annotated full constructors for the affected services, so deleting unused compatibility overloads does not alter dependency injection.
- No browser-visible output should change. Focused controller tests and startup validation are sufficient unless implementation produces an unintended rendering difference.
- Read-only inspection is a baseline. Implementation must repeat targeted searches because repository contents may differ when work begins.

## Recommended Direction

- Use constructor deletion as a compile-enforced inventory mechanism: remove a shim, migrate every compile failure to an approved retained signature, and do not introduce generalized replacement abstractions.
- For each direct `OutputArtifactService` fixture, construct `RootRelativePathService` from the same `WorkspaceDirectoryService` instance and pass it to the retained four-argument constructor. Test dependency records may carry that explicit dependency when it keeps fixture wiring readable.
- For new workflow definitions, supply canonical defaults explicitly:
  - `WorkflowDefinition.CURRENT_SCHEMA_VERSION`
  - `WorkflowDefinition.DEFAULT_MAX_CONCURRENCY`
  - the intended nodes and routes
  - `Map.of()` for a new empty UI layout
  - the intended creation/update timestamps
- For workflow definition updates, copy all unchanged canonical values from `current`; only replace the field the endpoint is designed to mutate.
- For new workflow nodes, pass explicit empty input/output port lists when no ports exist.
- For workflow node updates, copy all unchanged fields from `old`, including input/output ports, config, bindings, and any message/resume values not supplied by the request.
- Keep positional record construction readable with one argument per line for `OrchestrationTaskContext`, `WorkflowDefinition`, and `WorkflowNode` where practical.
- For `AgentProfileService` test doubles, pass the four optional provider dependencies explicitly as `null` unless a test requires a real provider.
- Search both direct construction and subclass `super(...)` sites. A search limited to `new Type(...)` is incomplete.
- Add or extend focused workflow-editor tests if equivalent assertions do not exist. At minimum, prove that:
  - definition metadata updates preserve non-default schema version, maximum concurrency, routes, and UI layout;
  - node updates preserve input ports, output ports, config, input bindings, and all unmodified fields;
  - create paths use the explicit workflow-v2 defaults intended by the removed overloads.
- Avoid deleting tests unless their assertions exclusively validate a removed overload. Constructor usage alone is not evidence that a test is dead.

## Validation

Acceptance requires all of the following:

- Production compilation succeeds with only retained canonical and approved non-compatibility constructors.
- Static search confirms that every targeted overload declaration, associated `@Deprecated` annotation, and compatibility-only comment is absent.
- Static search confirms no three-argument `OutputArtifactService` direct construction or superclass call remains.
- Static search confirms no three-argument `AgentProfileService` direct construction or superclass call remains.
- Static search plus compilation confirms no caller uses the removed `OrchestrationTaskContext`, `AssignmentService`, `OrchestrationRunnerService`, `WorkflowDefinition`, `WorkflowNode`, or `WorkflowNodeRun` signatures.
- Focused tests pass for:
  - output artifact attribution, path semantics, and output controller behavior;
  - workflow runner, repository, validation, branching, route context, output publication, and workflow editor/controller behavior;
  - public run submission and SSE behavior;
  - agent profile controller, path validation, runtime settings, agent orchestration controller, and entity lookup behavior;
  - orchestration context consumers touched by compilation or migration.
- Focused regression assertions prove preservation of workflow schema version, maximum concurrency, routes, UI layout, node ports, node config, node bindings, and node-run route context. Add the missing controller-level assertions rather than assuming broad workflow tests cover editor mutations.
- `mvn test` passes with all surviving tests.
- The bounded Spring Boot startup succeeds with an ephemeral port using the repository-standard startup check.
- No Playwright run is required for this constructor-only, behavior-neutral change. If rendered behavior changes, correct that defect rather than expanding the cleanup scope.
- Documentation-impact review finds no stale constructor guidance, or any exact stale references are corrected.
- Final diff review confirms no unrelated API growth, no behavior change, no replacement shim, no accidental test loss, and no reset of canonical workflow fields.
- The changelog states specification impact and justifies any test deletion. The implementation and closeout records are committed together.

## Open Questions

- No blocking product or architecture question remains.
- Out-of-repository Java consumers cannot be discovered locally; the explicit removal directive settles that source-compatibility risk.
- If implementation discovers another production constructor explicitly documented or demonstrably implemented as backward compatibility, include it only when it matches this handoff's shim definition. Do not sweep ordinary test-focused or minimal constructors into scope without source evidence. If classification is ambiguous, stop that deletion and resolve the discrepancy rather than broadening scope.

## Sign-off

Approved for implementation under this corrected handoff. Sign-off is complete only when all ten targeted shims are absent, every affected caller uses an approved retained constructor explicitly, workflow-v2 state-preservation assertions pass, meaningful tests remain intact, focused and full validation pass, the application context starts successfully, documentation and specification impact are recorded, and repository closeout plus commit are complete.
