---
schema_version: 1
document_type: service-graph-specification
status: active
owner: architecture
created: 2026-05-25
---

# Service Graph Specification

## Intended Contract

Controllers parse type/key path segments and delegate to services. `WorkspaceDirectoryService` resolves one `WorkspaceDescriptor`; downstream explorer, metadata, action logging, execution, tool, runtime instruction, and output services consume that descriptor without reconstructing identity from `Path`. Project association is metadata-only without filesystem edges. Avatar and Work Area edges are removed.

## Identity Flow

```text
(type, key) -> WorkspaceDirectoryService -> WorkspaceDescriptor
             -> explorer | metadata/actions | execution/tools | legacy outputs
             -> TaskIdentity + expected TaskPathKey + native run id
             -> trusted TaskRunLocation / RunOutputLocation
```

Canonical identity and normalized file-relative path remain separate. A controller never supplies a root, and downstream services never independently resolve the same request.

## Service Graph Entries

| id | from | to | status | allowed_interaction | boundary_rule | validation | related_decisions |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SVC-20260719-01 | Web/API controllers | `WorkspaceDirectoryService` | active | Typed workspace item controllers decode type/key from path segments, call `WorkspaceDirectoryService.resolve(kind, key)`, and pass the returned `WorkspaceDescriptor` to downstream services. Execution controllers pass the paired optional selection to the submission service, which performs the single descriptor resolution after applying bound-agent selection rules. | Controllers must not resolve roots, check entity existence, or construct canonical identity. | Controller tests plus descriptor-resolution tests. | `DECISION-20260719-02` |
| SVC-20260719-02 | `WorkspaceDirectoryService` | Low-level agent/chat repositories | active | `WorkspaceDirectoryService` checks agent existence via `AgentProfileRepository.findById` (or equivalent) and chat existence via a low-level repository query that does not create service cycles. | Must not depend on `ChatService`, task/plan services, or any service that would create a cycle. | Descriptor-resolution tests for existing/missing entities. | `DECISION-20260719-02` |
| SVC-20260719-03 | Explorer, metadata, action-log, execution, tools | `WorkspaceDirectoryService` (via `WorkspaceDescriptor`) | active | After the item boundary or execution-submission boundary performs the single resolution, downstream services accept that pre-resolved `WorkspaceDescriptor` and never call `WorkspaceDirectoryService` independently for the same request. `WorkspaceExplorerService` uses the descriptor root for confinement. `WorkspaceFileMetadataService` and `WorkspaceFileActionLogRepository` use canonical identity plus file-relative path. | Descriptor is the single source of workspace identity; no service may reconstruct identity from `Path`, `workspaceId`, or `workAreaId`. | Service tests with resolved descriptors. | `DECISION-20260719-02` |
| SVC-20260719-04 | Prompt/context assembly | `AGENTS.md` resolver via descriptor root | active | Runtime `AGENTS.md` resolution walks from the descriptor root downward to the active confined tool path. | No starter `AGENTS.md` generation; resolution stays inside descriptor root. | Resolver and prompt/context tests. | `DECISION-20260526-02` |
| SVC-20260719-05 | Execution submission | `WorkspaceDirectoryService` and output services | active | Submission resolves one descriptor from `workspaceType`/`workspaceKey` (or bound-agent fallback), persists the pair plus canonical identity, and passes the descriptor to runners, tools, and output publication. | Chat selection is rejected; agent explicit match required; user workspace must exist. | Execution selection and output tests. | `DECISION-20260719-02` |
| SVC-20260719-06 | Output publication | `WorkspaceDescriptor` and `OutputArtifactRepository` | active | Current publication writes legacy/non-task artifacts beneath the descriptor's lazy workspace-root `outputs/`. Later task publication consumes only the trusted run-local output location after Phases 02-04. `OutputArtifactRepository` remains persistence owner. | Source real paths stay descriptor-confined. No generic `runs/<runId>` or caller-built path is allowed. | Output publication and artifact repository tests. | `DECISION-20260719-02`, `DECISION-20260720-01` |
| SVC-20260720-01 | `WorkspaceDirectoryService` | Trusted task-run child values | active | For `AGENT` or `USER`, recompute `TaskPathKey` from `TaskIdentity`, validate the native run id, and resolve/provision the canonical child. Resolve run output and workspace `.skills/` lazily. | Reject `CHAT`, key mismatch, links, escape, arbitrary roots, path-derived identity, and eager output/staging creation. | Task identity, layout, directory, relocation, and lazy-read tests. | `DECISION-20260720-01` |
| SVC-20260525-01 | Web/API controllers | Domain services | active | Controllers map HTTP/HTMX/SSE requests into service calls and response payloads. | Controllers should not own filesystem, schema, or runtime policy. | Controller tests plus service tests for changed behavior. | `DECISION-20260524-03` |
| SVC-20260525-04 | Agent operational tools | Spring AI tool registry and current orchestration context | active | Agent tools resolve exact approved-tool names and current run context. | PLAN/TASK drafting modes exclude operational agent tools; `avatar_` tools are removed. | Tool registry and chat/orchestration tests. | `DECISION-20260522-06` |
| SVC-20260525-05 | Task/workflow execution | Assignment/runtime/workspace/output services | active | Executable work routes through bounded, observable runtime services. | Planner organizer recurrence is non-automated until separate automation is accepted. | Runtime tests and live validation when required. | `DECISION-20260523-02` |
| SVC-20260526-02 | Runtime prompt/context assembly | Workspace `AGENTS.md` resolver services | active | Prompt/context assembly consumes runtime `AGENTS.md` layers from workspace/runtime services scoped to descriptor roots and active runtime paths supplied by confined file/shell tool resolution. | Prompt assembly must not perform ad-hoc filesystem traversal or independent root resolution; confinement, active path capture, and resolution stay service-owned. | Prompt/context tests plus resolver, file tool, and shell tool tests for path-change behavior and conflict precedence messaging. | `DECISION-20260526-02` |
| SVC-20260727-01 | Agent context and materialization | `WorkspaceDirectoryService`, layout authorities | active | `AgentContextService` owns versioned `MATERIALIZATION`/`PERSONAL` defaults, insert-only immutable binding snapshots, and context capture for conversations/assignments/runs/children. `AgentMaterializationService` owns generation-safe `PROVISIONING`/`READY`/`FAILED`/`REVOKED` lifecycle, materialization-key derivation, descriptor-confined provisioning, and reconciliation. Assignment-binding operations link capture, assignment insertion, and lifecycle reservation into one durable submission decision. | Do not resolve materialization roots outside descriptor confinement or treat `READY` state as acquired content. | Context/binding service tests and migration integrity tests. | `DECISION-20260727-01`, `DECISION-20260727-03` |
| SVC-20260727-02 | System chat service | Agent profile, bounded reads, proposal persistence | active | `SystemChatService` uses a fixed deny-by-default profile per agent; runtime settings currently supply enabled state and model, while the prompt, callback allowlist, and context bound remain server-owned constants. `SystemChatBoundedReadService` provides bounded non-secret agent-status, workspace-metadata, and assignment-lifecycle reads. `SystemChatToolRegistry` restricts tool access. `SystemChatActionProposalService` persists user-confirmable queue-operation proposals and gates confirmation through ordinary scoped lifecycle authorization. | System chat cannot write files, invoke processes, approve/activate skills, broaden authority, or execute its own proposal. Confirmation requires exact target/state/operation-key. | Bounded-read, tool-denial, proposal-lifecycle, and confirmation-idempotency tests. | `DECISION-20260727-04` |
| SVC-20260727-03 | Agent console web adapters | Agent context, materialization, system chat, runtime, skills, explorer | active | `OrchestrationController` composes stable independent HTMX hosts. `AgentContextFragments` renders `#agent-context-panel`; `SystemChatFragments` renders `#system-chat-panel`, `#system-chat-history`, and `#system-chat-proposals`. `AgentContextController` and `SystemChatController` provide thin REST adapters for context/materialization summaries and system-chat lifecycle. | Fragment hosts are independently loaded; a response replaces only its own host. System-chat SSE is the only new JavaScript transport. | Controller/fragment host-isolation, scoped-agent, and Playwright functional/visual validation tests. | `DECISION-20260727-02` |

## Active Consumer Inventory

Each active consumer identified during repository inspection is listed with its current responsibility, concrete anchors, and the owning phase (02-08) for cutover. False positives and historical-only records are classified without implementation ownership.

### Avatar

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `io.mindspice.magenta2.avatar` package (29 classes) | `AvatarProfile`, `AvatarRepository`, `AvatarService`, `AvatarSchemaInitializer`, `AvatarDataConfiguration`, `AvatarAgentProfileBootstrap`, planner records/enums | Avatar profile, organizer, planner, `avatar.sqlite` persistence | Phase 03 |
| `AvatarDashboardController` | `/avatar` routes, dashboard fragments | Avatar web surface | Phase 03 |
| `AvatarDashboardComponents` | Avatar shell, layout, widget rendering | Avatar UI components | Phase 03 |
| `io.mindspice.magenta2.ai.chat.tool.avatar` (5 classes) | `AvatarAssistantToolService`, `AvatarAssistantTools`, `AvatarAssistantToolAuthorizationService`, `AvatarAssistantToolConfiguration`, `AvatarAssistantToolResponses` | Avatar agent tools | Phase 03 |
| `avatar-schema.sql` | `src/main/resources/avatar-schema.sql` | Avatar schema initialization | Phase 03 |
| `avatar-dashboard.css` | `src/main/resources/static/css/avatar-dashboard.css` | Avatar styles | Phase 03 |
| `avatar-chat.js`, `avatar-layout-edit.js`, `avatar-shell.js` | `src/main/resources/static/js/avatar-*.js` | Avatar JavaScript | Phase 03 |
| Dedicated Avatar tests (6 files) | four `src/test/java/io/mindspice/magenta2/avatar/*Test.java` files, `AvatarDashboardControllerTest`, `AvatarToolsTest` | Avatar product test coverage | Phase 03 |
| Cross-cutting Avatar assertions | `ChatServiceTest`, `ToolAccessPolicyTest`, `AgentOperationalToolConfigurationTest`, `AgentOperationalToolServiceTest`, `AgentToolAuthorizationServiceTest`, `ChatControllerTest`, `FrontendControllerTest`, `OrchestrationControllerTest` | Avatar surface/tool/policy behavior embedded in otherwise retained tests | Phase 03 |
| Avatar docs (4 files) | `docs/end-user/avatar-dashboard.md`, `docs/technical/avatar-dashboard-*.md`, `docs/technical/avatar-planner-organizer.md` | Avatar documentation | Phase 08 |
| Agent operational tools Avatar references | `AgentOperationalToolService` Avatar methods, `AgentOperationalTools` `avatar_*` registrations, `AgentToolAuthorizationService`, `ToolAccessPolicy` | Avatar tool registration, supervisor identity checks, and authorization | Phase 03 |
| Chat Avatar surface integration | `ChatSessionSurface.AVATAR`, `ChatService` Avatar surface handling, `ChatController` Avatar-oriented assertions/requests | Avatar-specific chat surface classification and policy | Phase 03 |
| `FrontendController` Avatar home card | `AppNavigation`, `DashboardController` references | Avatar navigation card | Phase 03 |

### Work Area (DB-backed)

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `WorkArea` record | `ai/orchestration/workspaces/WorkArea.java` | DB-backed Work Area identity (id, ownerType, ownerId, workspaceId, rootRelativePath, home, active) | Phase 06 |
| `WorkAreaRepository` | `ai/orchestration/workspaces/WorkAreaRepository.java` | Work Area CRUD, owner-root queries, home marking, active-flag | Phase 06 |
| `WorkAreaService` | `ai/orchestration/workspaces/WorkAreaService.java` | Work Area lifecycle, owner-root descriptor resolution, mark/home/nested-mark | Phase 06 |
| `WorkAreaController` | `api/web/WorkAreaController.java`, `/api/work-areas` with ~20 routes | Work Area REST API | Phase 04 |
| `WorkAreaExplorerFragments` | `api/web/WorkAreaExplorerFragments.java` | Work Area/Avatar-owned file-browser HTMX fragments | Phase 03 (extract neutral `WorkspaceExplorerFragments`, then delete old renderer) |
| `WorkAreaExplorerService` filesystem algorithms | `ai/orchestration/workspaces/WorkAreaExplorerService.java` | File listing, preview, create, save, rename, move, copy, delete, limits, and confinement mixed with Work Area lookup | Phase 03 (extract descriptor-first `WorkspaceExplorerService`; leave only a temporary thin adapter) |
| `AssignmentRequest.selectedWorkAreaId` / `outputWorkAreaId` | `ai/orchestration/runtime/AssignmentRequest.java` | Work Area execution selection and output routing | Phase 05 |
| `AssignmentService` Work Area usage | `ai/orchestration/runtime/AssignmentService.java` | Work Area resolution during assignment | Phase 05 |
| `OrchestrationRunnerService` Work Area usage | `ai/orchestration/runtime/OrchestrationRunnerService.java` | Work Area in orchestration context | Phase 05 |
| `JobService` Work Area usage | `ai/orchestration/runtime/JobService.java` | Work Area in job binding | Phase 05 |
| `OrchestrationTaskContext` Work Area | `ai/orchestration/runtime/OrchestrationTaskContext.java` | Work Area in task runtime context | Phase 05 |
| `WorkflowRunner` Work Area | `ai/orchestration/workflow/WorkflowRunner.java` | Work Area in workflow execution | Phase 05 |
| `PlanService` Work Area | `ai/chat/plan/PlanService.java` | Work Area in plan execution | Phase 05 |
| `OutputDirectoryService` Work Area routing | `ai/orchestration/workspaces/OutputDirectoryService.java` | Work Area-based output path resolution | Phase 05 |
| `OutputPublicationTarget` Work Area | `ai/orchestration/workspaces/OutputPublicationTarget.java` | Work Area output target | Phase 05 |
| Work Area explorer/API tests | `WorkAreaControllerTest`, `WorkAreaExplorerServiceTest`, `WorkspaceFileActionLogRepositoryTest`, `WorkspaceFileMetadataRepositoryTest`, `WorkspaceFileMetadataServiceTest` | Work Area-keyed explorer, metadata, action, and route coverage | Phase 04 |
| Work Area execution tests | `AssignmentContextServiceTest`, `OrchestrationControllerTest`, `JobServiceTest`, `WorkflowRunnerTest`, `PlanServiceTest` | Work Area selection/output behavior in executable work | Phase 05 |
| Work Area persistence tests | `WorkAreaServiceTest`, `WorkspacePathSegmentValidationTest` Work Area cases | DB-backed Work Area lifecycle and identity validation | Phase 06 |
| `work_areas` schema table | `src/main/resources/schema.sql: work_areas` | Work Area table with workspace FK, owner_type, owner_id, active_flag, home, root_relative_path | Phase 06 |

### Generic Workspace Persistence

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `WorkspaceRepository` — `workspaces` table ops | `workspaces` CRUD, `findByOwner`, `save`, `deleteByOwner` | Generic workspace identity records (ownerType, ownerId, rootRelativePath, displayName) | Phase 06 (retire) |
| `WorkspaceRepository` — `workspace_links` table ops | `links()`, `saveLink()`, `findLink()`, `deleteLink()` | Project materialized links | Phase 06 (retire) |
| `WorkspaceRepository` — `workspace_leases` table ops | `saveLease()`, `insertWritableLease()`, `releaseLease()`, `releaseExpiredLeases()`, `findActiveLease*()` | Filesystem workspace writable-lease lifecycle | Phase 06 (retire) |
| `WorkspaceRepository` — `run_output_artifacts` table ops | `saveArtifact()`, `findArtifactsByRunId()`, `findArtifactById()`, `findArtifactsByPlanId()`, `findArtifacts(OutputArtifactQuery)`, artifact deletion inside `deleteByOwner()`, and `ensureSchema()` for `run_output_artifacts` | **Mixed output-artifact ownership** — artifact persistence embedded in generic workspace repository | Phase 05 (extract all schema, queries, writes, deletion, and attribution to `OutputArtifactRepository`) |
| `WorkspaceRepository` — `WorkspaceRoot` deprecated adapters | `findRootById()`, `findRootByOwner()`, `saveRoot()` | Deprecated legacy compatibility delegation | Phase 06 (retire) |
| `WorkspaceService` | `get()`, `agentWorkspace()`, `projectWorkspace()`, `create()`, `ensureWorkspace()`, `ownerWorkspace()`, workspace link CRUD | Generic workspace lifecycle, agent/project workspace or-create, link management | Phase 06 (retire) |
| `Workspace` record | `ai/orchestration/workspaces/Workspace.java` | Generic workspace identity (id, ownerType, ownerId, rootRelativePath, displayName, metadataJson) | Phase 06 (retire) |
| `WorkspaceOwnerType` enum | `AGENT`, `PROJECT`, `AVATAR` values | Generic owner classification | Phase 06 (retire; replaced by `WorkspaceKind`) |
| `WorkspaceRoot` record | `ai/orchestration/workspaces/WorkspaceRoot.java` | Legacy root identity (delegates to Workspace) | Phase 06 (retire) |
| `WorkspaceLeaseService` | `acquireWritableLease()`, `releaseLease()`, `releaseExpired()` | Project/job writable workspace lease acquisition | Phase 06 (retire; assignment queue coordination fields remain independent) |
| `EffectiveWorkspaceResolver` | `resolve(agentId, projectId)` depends on `WorkspaceService` | Durable workspace selection; generates/retrieves generic workspace records | Phase 05 (replace with descriptor-only resolution) |
| `EffectiveWorkspace` record | `ai/orchestration/workspaces/EffectiveWorkspace.java` | Resolved workspace with workspaceId, ownerType, root | Phase 05 (replace with `WorkspaceDescriptor`) |
| `WorkspacePathLayout` legacy constants/factories | `HOME`, `WORKAREAS`, `WORK`, `RUNS`, `PROJECTS` (as project roots), `AGENTS`, `CHATS` (old style), `ROOT_ALIAS`, `RUN_ALIAS`, legacy runtime/jobs/scratch factories, agentHome, workArea, runRoot, projectRoot, agentProjectLinks, legacy* methods | Retired structural constants and factories | Phase 02 (reduce to `WORKSPACE`, `OUTPUTS`, three-kind factories) |
| `WorkspaceDirectoryService` legacy methods | `migrateLegacyAgentDirs()`, starter `AGENTS.md` generation, project workspace root, legacy job/task/workflow/scratch/chat path methods | Retired migration, starter, and legacy path methods | Phase 02 (reduce to three-kind resolution) |
| `ChatFileService` | `ai/chat/service/ChatFileService.java` legacy chat-directory resolution | Chat attachments/files currently resolve through old chat path helpers | Phase 02 |
| `AgentProfileController.workspace()` | `api/web/AgentProfileController.java`, `GET /api/agents/{agentId}/workspace` returning generic `Workspace` | Old generic workspace payload for an agent | Phase 04 (remove or replace with typed descriptor-backed behavior; no generic payload) |
| `ProjectService` filesystem methods | `runtime/ProjectService.java` dependency on `WorkspaceService`, project workspace/link allocation and release | Project-root and materialized-link lifecycle mixed with retained project metadata/membership/events | Phase 06 (delete only filesystem responsibilities; preserve metadata/membership/events) |
| `WorkspaceLease` and `LeaseMode` | `WorkspaceLease.java`, `LeaseMode.java` | Filesystem lease identity and mode | Phase 06 (retire) |
| `WorkspaceLink` and `WorkspaceLinkType` | `WorkspaceLink.java`, `WorkspaceLinkType.java` | Materialized project link identity and type | Phase 06 (retire) |
| `WorkspaceController` | `api/web/WorkspaceController.java`, `/api/workspaces` routes | Generic workspace list/get, link CRUD | Phase 04 (replace with typed routes) |
| `WorkspaceServicePathTest`, `WorkspaceRepositorySchemaMigrationTest`, `WorkspaceRepositoryAttributionTest`, `WorkspaceLeaseServiceTest` | Test files | Generic workspace persistence test coverage | Phase 06 |
| `workspaces`, `workspace_links`, `workspace_leases` schema tables | `src/main/resources/schema.sql` | Generic workspace identity, link, and lease tables | Phase 06 (retire from fresh schema; tolerate in existing) |
| `AgentProfileService` workspace owner ops | `ai/orchestration/agents/AgentProfileService.java` | Agent workspace owner-type resolution | Phase 05 (replace with descriptor resolution) |
| `OutputArtifactService` depends on `WorkspaceRepository` | `ai/orchestration/workspaces/OutputArtifactService.java` | Output artifact materialization uses `WorkspaceRepository.saveArtifact()` / `findArtifacts*()` | Phase 05 (switch to independently owned `OutputArtifactRepository`) |
| `OutputArtifactContext`, `OutputArtifactQuery`, `RunOutputArtifact`, `OutputPublicationTarget` | `ai/orchestration/workspaces/` records | Output publication, query, and durable attribution types | Phase 05 |

### Explorer / Metadata / Actions

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `WorkspaceFileMetadataRepository` | `saveLabel()`, `findLabel()`, `assignLabel()`, `removeLabel()`, `findByLabel()`, `WORK_AREA_LABEL` constant | File label CRUD; `WORK_AREA_LABEL` system label | Phase 04 (re-key to canonical identity; remove `WORK_AREA_LABEL`) |
| `WorkspaceFileMetadataService` | Label assignment, file metadata CRUD | File metadata use cases | Phase 04 |
| `WorkspaceFileActionLogRepository` | `record(WorkArea, ...)` method signatures, `ensureSchema()` | File action logging keyed by `WorkArea` | Phase 04 (re-key to canonical identity; remove `WorkArea` parameter) |
| `WorkspaceFileActionRecord` | `workAreaId` field | Action record payload with Work Area reference | Phase 04 (replace with canonical identity) |
| `WorkspaceFileLabel`, `WorkspaceFileLabelAssignment`, `WorkspaceFileActionType` | Label/tag definitions, file-to-label mapping, and action types | Explorer metadata/action domain records | Phase 04 |
| Transitional `WorkAreaExplorerService` adapter | Work Area resolution plus metadata/action adaptation after descriptor-first filesystem extraction | Temporary compatibility adapter for typed API cutover; no duplicate file IO/confinement | Phase 04 (remove adapter and all Work Area identity) |

### Execution / Tools

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `AgentFileToolService` | File tool workspace binding, path confinement | Agent file operations bound to workspace root | Phase 05 |
| `AgentShellToolService` | Shell tool workspace binding, command execution in workspace | Agent shell operations bound to workspace root | Phase 05 |
| `OrchestrationRunnerService` | Task/plan run execution with Work Area context | Orchestration run lifecycle | Phase 05 |
| `WorkflowRunner` | Workflow execution with Work Area context | Workflow run lifecycle | Phase 05 |
| `BindingResolver` | Workflow input binding resolution | Workflow variable resolution | Phase 05 |
| `AgentFileTools`, `AgentOperationalTools`, `PlanSaveTools`, `SavedPlanTools`, `TaskTools` | Tool registrations | Agent tool definitions | Phase 05 |
| `PromptContextAssembler` | Runtime prompt assembly with workspace/AGENTS.md context | Chat prompt construction | Phase 05 |
| `AgentsMdResolver`, `AgentsMdLayer`, `AgentsMdResolution` | `AGENTS.md` file resolution from descriptor root | Runtime instruction layering | Phase 05 |
| `RootRelativePathService` | Portable stored artifact file paths used by plan/workflow/output persistence | Artifact-relative path portability only; it must not determine workspace identity, select/authorize roots, links, or leases | Phase 05 (remove identity/root call sites while retaining only demonstrated artifact-relative responsibility) |
| `AgentWorkspaceStatusService` | Workspace health/status for agent dashboard | Obsolete generic/project-root diagnostics | Phase 06 |
| Execution request/web adapters | `AgentOrchestrationController`, `OrchestrationController`, `PlanController`, `TaskController`, `WorkflowController`, `JobController`, `TaskStreamSupport`, `WorkflowStreamSupport` | Accept and forward `workspaceId`, Work Area, and output-route fields | Phase 05 |
| Execution records and parsers | `AssignmentRequest`, `WorkAssignment`, `OrchestrationTaskContext`, `OrchestrationRunContext`, `JobDefinition`, `JobRun`, `OrchestrationJob`, `WorkflowRun`, `PlanRun`, `AssignmentTemplateParser` | Carry old generic/Work Area identity and host/root/output path fields | Phase 05 |
| Execution repositories | `OrchestrationRuntimeRepository`, `JobRepository`, `WorkflowRepository`, `PlanRepository` | Persist/read old workspace ids, effective workspace kinds, Work Area fields, and run/temp/output paths | Phase 05 |
| Generic/project workspace execution consumers | `AgentProfileService`, `AssignmentService`, `JobService`, `OrchestrationJobService`, `OrchestrationRunnerService`, `AgentOperationalToolService` non-Avatar methods | Create/read generic agent/project workspaces, links, leases, and root-derived diagnostics in execution flows | Phase 05 |
| Selector consumers | `EntityKind.WORK_AREA`, `EntityLookupService`, `EntitySelectorComponents` | Expose generic workspace and Work Area options in submission UI | Phase 07 (replace with declared user workspace controls) |

### Outputs

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `OutputArtifactService` | Materializes output values, writes/copies files, persists `RunOutputArtifact` via `WorkspaceRepository` | Output artifact lifecycle; mixed persistence ownership | Phase 05 (publish through descriptor and switch persistence to `OutputArtifactRepository`) |
| `OutputDirectoryKind`, `OutputDirectoryService`, `ResolvedOutputDirectory` | Resolve output directories using Work Area/run/project routing | Obsolete output routing consumed by active execution/output flows | Phase 05 (move callers to descriptor publication; leave no active consumer for final class deletion) |
| `OutputController` | `api/web/OutputController.java`, output query/download routes | Output query API | Phase 05 |
| `RunOutputArtifact` record | Artifact identity (runId, planId, agentId, jobId, workspaceId, outputName, filePath, etc.) | Output artifact row type | Phase 05 |
| `OutputArtifactQuery` | Query parameters for artifact search | Artifact query DTO | Phase 05 |
| `OutputArtifactContext` | Materialization context | Materialization DTO | Phase 05 |
| `ResolvedOutputDirectory` | Resolved output path with routing info | Output path resolution result | Phase 05 |
| `run_output_artifacts` table and indexes | `src/main/resources/schema.sql` plus `WorkspaceRepository.ensureSchema()` | Output artifact table (currently owned by `WorkspaceRepository`) | Phase 05 (transfer schema and full behavior to `OutputArtifactRepository`; replace generic `workspace_id` with canonical attribution) |
| `JobExecutionSummary` | Job run completion summary with output references | Job run summary | Phase 05 |
| `PlanService`, `PlanDefinition`, `TaskDefinition`, `TaskDraft`, `TaskService` | Plan/task definitions with output type references | Plan/task output contracts | Phase 05 |

### Schema / Configuration

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `src/main/resources/schema.sql` — explorer identity | `workspace_file_label_assignments.workspace_id/root_relative_path`, `workspace_file_actions.workspace_id/work_area_id` and their generic-workspace FKs/indexes | Work Area/generic-keyed metadata and action schema | Phase 04 (replace fresh schema with canonical identity plus normalized file-relative path) |
| `src/main/resources/schema.sql` — execution identity | `plan_runs`, `work_assignments`, `workflow_runs`, `job_definitions`, `job_runs` workspace/work-area/output/temp/path columns | Old execution selection, guessed/effective identity, and staged path persistence | Phase 05 (fresh schema uses type/key and canonical attribution only) |
| `src/main/resources/schema.sql` — generic identity | `workspaces`, `workspace_links`, `workspace_leases`, `work_areas` tables, indexes, FKs | Fresh database schema for obsolete identity, links, leases, and Work Areas | Phase 06 (remove from fresh schema; tolerate inert structures in existing databases) |
| Explorer repository-local schema initialization | `WorkspaceFileMetadataRepository.ensureSchema()`, `WorkspaceFileActionLogRepository.ensureSchema()` | Can recreate or depend on obsolete Work Area/generic metadata and action columns outside `schema.sql` | Phase 04 |
| Execution repository-local schema initialization | `OrchestrationRuntimeRepository`, `JobRepository`, `WorkflowRepository`, `PlanRepository` inline `CREATE`/`ALTER` paths | Can recreate or depend on obsolete execution identity and staged-path columns outside `schema.sql` | Phase 05 |
| Generic/Work Area repository-local schema initialization after output extraction | remaining `WorkspaceRepository.ensureSchema()` generic/link/lease DDL and `WorkAreaRepository.ensureSchema()` | Can recreate obsolete generic workspace, link, lease, and Work Area structures outside `schema.sql` | Phase 06 |
| `src/main/resources/avatar-schema.sql` | Avatar tables | Avatar database schema | Phase 03 |
| `AvatarDataConfiguration` | Avatar datasource bean, `avatar.sqlite` path | Avatar datasource wiring | Phase 03 |
| `MagentaRootConfiguration` | Data root configuration | Application root path config | Phase 02 |

### UI / Assets

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| `WorkAreaExplorerFragments` and Avatar-embedded explorer CSS | HTMX fragments and styles for the Work Area file browser | Reusable explorer presentation mixed with Avatar ownership | Phase 03 (extract neutral fragments/styles, then delete old owner) |
| `AvatarDashboardComponents` | Avatar shell, layout, widget components | Avatar dashboard UI | Phase 03 |
| `AppNavigation` | Top navigation links including Avatar | Shell navigation | Phase 03 |
| `FrontendController` | Home page, chat toolbar, Avatar home card | Frontend shell and home page | Phase 03 |
| `DashboardController` | `/dashboard`, `/agents` operational console | Agent operational console and target Files-tab shell | Phase 07 |
| `WorkspaceSelectionComponents` / selector package | planned neutral component plus `EntityKind`, `EntityLookupService`, `EntitySelectorComponents` | Reusable user creation and execution selection controls | Phase 07 |
| `orchestration.css` | `src/main/resources/static/css/orchestration.css` | Operational-console styling and destination for neutral explorer styles | Phase 03 (receive reusable explorer styles before Avatar CSS deletion) |

### Current Documentation

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| Current end-user workspace/Avatar/execution docs | `docs/end-user/avatar-dashboard.md`, `agents.md`, `chat.md`, `dashboard.md`, `inbox-outputs-settings.md`, `jobs.md`, `plans-and-tasks.md`, `projects-and-workspaces.md`, `quickstart.md`, `workflows.md`, and `docs/end-user/00-index.md` | Present Avatar, Work Area, workspace, output, or execution behavior to users | Phase 08 |
| Current technical workspace/Avatar/execution docs | `docs/technical/avatar-dashboard-fragments.md`, `avatar-dashboard-layout-persistence.md`, `avatar-planner-organizer.md`, `architecture.md`, `services.md`, `data-model.md`, `workspaces-tools-outputs.md`, `orchestration-runtime.md`, `workflow-engine.md`, `frontend-htmx.md`, `chat-planning-tasks.md`, `security.md`, `configuration-operations.md`, and `docs/technical/00-index.md` | Present active architecture, persistence, API/UI, security, and runtime behavior | Phase 08 |
| Current API and documentation indexes | `docs/technical/api-reference.md`, `docs/api/00-index.md`, `docs/README.md` | Route/payload reference and current-doc navigation | Phase 08 |
| `.internal-dev/knowledge/` Avatar/WorkArea files | `avatar-*.md`, `workspace-file-*.md`, `simplypages-avatar-*.md`, `project-workspace-*.md`, `root-relative-*.md` | Reusable knowledge (some superseded, some retained) | Phase 08 |
| `src/main/java/io/mindspice/magenta2/avatar/AGENTS.md` | Avatar package guide deleted with its package | Package-level Avatar conventions | Phase 03 |
| `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md` Avatar references | Active web package guide | Package-level web conventions | Phase 08 |

### Tests (active consumers only)

| consumer | anchors | current responsibility | owning phase |
| --- | --- | --- | --- |
| Dedicated and cross-cutting Avatar tests | `AvatarDashboardControllerTest`, `AvatarToolsTest`, four `avatar/*Test.java` files, `ChatServiceTest`, `ToolAccessPolicyTest`, `AgentOperationalToolConfigurationTest`, `AgentOperationalToolServiceTest`, `AgentToolAuthorizationServiceTest`, `ChatControllerTest`, `FrontendControllerTest`, `OrchestrationControllerTest` | Avatar product and embedded integration assertions | Phase 03 |
| Work Area explorer/API tests | `WorkAreaControllerTest`, `WorkAreaExplorerServiceTest`, `WorkspaceFileActionLogRepositoryTest`, `WorkspaceFileMetadataRepositoryTest`, `WorkspaceFileMetadataServiceTest` | Work Area-keyed explorer/API coverage | Phase 04 |
| Work Area execution tests | `AssignmentContextServiceTest`, `OrchestrationControllerTest`, `JobServiceTest`, `WorkflowRunnerTest`, `PlanServiceTest` | Work Area selection/output coverage | Phase 05 |
| Work Area/generic persistence tests | `WorkAreaServiceTest`, `WorkspaceServicePathTest`, `WorkspaceRepositorySchemaMigrationTest`, `WorkspaceRepositoryAttributionTest`, `WorkspaceLeaseServiceTest`, `WorkspacePathSegmentValidationTest` | Obsolete identity, link, lease, and schema coverage | Phase 06 |
| Output publication/persistence/API tests | `OutputArtifactServiceAttributionTest`, `OutputArtifactPathSemanticsTest`, `OutputDirectoryServiceTest`, `OutputControllerTest` | Output routing, confinement, attribution, repository, and route coverage | Phase 05 |
| Execution tests (5+ files) | `AssignmentContextServiceTest`, `OrchestrationRuntimeTest`, `JobServiceTest`, `WorkflowRunnerTest`, `OrchestrationControllerTest` | Execution/assignment test coverage | Phase 05 |
| Explorer/metadata tests (4 files) | `WorkspaceFileMetadataRepositoryTest`, `WorkspaceFileMetadataServiceTest`, `WorkspaceFileActionLogRepositoryTest`, `WorkspacePathSegmentValidationTest` (explorer keys) | Explorer/metadata test coverage | Phase 04 |
| Tool tests (4 files) | `AgentFileToolServiceTest`, `AgentShellToolServiceTest`, `AgentOperationalToolServiceTest`, `AgentOperationalToolConfigurationTest` | Agent tool test coverage | Phase 05 |
| Avatar shell/navigation tests | `FrontendControllerTest` Avatar assertions | Avatar card/navigation removal | Phase 03 |
| Explorer route binding tests | `PublicApiRouteBindingTest`, `ChatFileControllerTest`, `AgentProfileControllerTest` workspace-route assertions | Typed workspace and removed-route coverage | Phase 04 |
| Execution submission tests | `PublicRunSubmissionControllerTest`, execution portions of `OperationalUiContractControllerTest` | Type/key submission contract | Phase 05 |
| Selector and Files-tab tests | `EntitySelectorComponentsTest`, Files-tab portions of `OperationalUiContractControllerTest`, `OrchestrationControllerTest` UI assertions | User workspace controls and agent Files tab | Phase 07 |

## False Positives and Historical-Only Records

- **`DECISION-20260522-01`** (focus/notes superseded): Historical, no active consumer.
- **`DECISION-20260523-04`** (email ledger superseded): Historical, no active consumer.
- **`PlannerRecurrence` / `PlannerRecurrenceMode`**: Removed with Avatar in Phase 03; no independent product responsibility.
- **`WorkspaceFileMetadataRepository.WORK_AREA_LABEL` constant**: Removed in Phase 04; no independent behavior beyond labeling.
- **`WorkspaceOwnerType.AVATAR` enum value**: Removed with Avatar in Phase 03.
- **Legacy `workspaces` table rows with obsolete `owner_type` values**: Historical data; no active repository dependency after Phase 06.
- **`docs/maestro/` directory**: Historical review artifact; no active consumer.

## Phase 01-09 Service Additions

| owner | depends on | allowed interaction | prohibited interaction |
| --- | --- | --- | --- |
| `SchemaMigrationRunner` | migration resources, SQLite datasource | Apply checksum-governed contiguous resources 001-013 before dependent repositories initialize, including strict materialization-key validation, context-operation idempotency, assignment-binding operations, and system-chat proposals. | Repository-local duplicate DDL or accepting checksum/partial-state drift. |
| `TaskRunLifecycleService` | native run repositories, assignment lease CAS, `WorkspaceDirectoryService` | Reserve native/task-run identity, attempts, operation digests, lineage, and independent writer claims; provision only trusted canonical run locations. | Treat assignment id as run id, infer identity from a path, or expose owners/fences. |
| reserved-tree mutation policy and shell boundary | `WorkspacePathLayout`, trusted descriptor/run capability | Classify reserved structures and verify current workspace/run/fence facts at each mutation boundary. | Path-only authorization or enabling workspace-bound process execution while confinement is unavailable. |
| `OutputArtifactService` / `ArtifactReconciliationService` | `OutputArtifactRepository`, trusted run output, current writer capability | Reserve, stage, digest, create-only publish, reconcile, and preserve legacy/non-task rows honestly. | Replace final bytes, publish from arbitrary paths, or make repositories own DDL. |
| `ArtifactSearchService` / `ArtifactPreviewService` / `TaskRunRetentionService` | artifact access context, repository, verified byte handles | Scope before search/counts/metadata; recheck at byte open; probe FTS5; default retention to preserve; honor blockers and pins. | Unscoped counts/snippets/bytes, preview after integrity failure, or automatic deletion under absent policy. |
| `SkillScopeDiscoveryService` / `SkillResolutionService` | trusted workspace skill locations, global repository, exact overlays | Discover without creating roots, persist digest-qualified candidates, and resolve root precedence separately from assignment specificity. | Guess project roots, fall through an occupying pending candidate, or disclose candidate content. |
| `SkillTrustService` / `SkillSnapshotRepository` / `AgentSkillActivationService` | candidate repository, confined source reread, immutable snapshot rows | Explicitly approve exact digests, revoke, activate snapshot bytes, and preserve same-run resume identity. | Auto-trust based on source/scope/assignment, activate live mutable bytes, or grant executable authority with instruction approval. |
| `RunMeasurementRepository` / `RunSummaryService` | native status, attempts, exact measurements and domain links | Record repeated typed calls and build replaceable summaries, health, and bounded audits. | Become status authority, flatten retries, fabricate null facts, or join by proximity. |
| `ScheduleService` | assignment/lifecycle operation boundary, supplied clock, persisted claim/firing audit | Own revisioned definitions, cron/timezone semantics, fenced claims, idempotent firings, attempts, misfire recovery, and pure simulation. | Equate enqueue with completion, let stale owners mutate, or create execution-like simulation rows. |
| `EventReactionService` / `OrchestrationEventService` | event snapshots, lifecycle operation boundary, causation ledger | Own revision-qualified dispatch identity, dedupe, loop/depth/rate suppression, append-only attempts, and pure simulation. | Drop suppressed events silently, infer outcomes, or bypass causation checks. |

## Operational Composition Edges

| from | to | allowed interaction | prohibited interaction |
| --- | --- | --- | --- |
| `OrchestrationController` | agent existence scope, `RunSummaryService`, `RunOperationsFragments` | Validate the agent boundary, request at most 100 workspace summaries, recheck run workspace type/key before audit composition, then render secret-free fragments. | Query claims/fences/paths in the renderer or reveal cross-workspace aggregate facts. |
| `WorkspaceExplorerFragments` | stable run host | Render `#agent-run-groups` beside the filesystem browser and independently load `/agents/_detail/{agentId}/runs`. | Inject repositories, summary services, artifact services, or skill/schedule services into `WorkspaceExplorerService`. |
| `RunSummaryService` | native lifecycle, measurements, skill activations, artifacts, causation | Build rebuildable summary/audit facts through exact persisted identifiers. | Become native status authority or infer attribution from display names, paths, or temporal proximity. |
| `ArtifactFragments`, `SkillFragments`, `RuntimeHealthFragments`, `ScheduleReactionFragments` | each owning domain service | Render independent scoped HTMX hosts and update only the affected host. | Broaden scope through stale/OOB responses or move domain persistence into the Files explorer. |

## Ownership Boundary

This spec owns the allowed direction of dependencies and the active-consumer inventory. Individual service behavior belongs in `services.md`; HTTP contracts belong in `api.md`.

## Drift/Gaps

| id | status | observed_drift | routing | review_after |
| --- | --- | --- | --- | --- |
| DRIFT-20260525-03 | watching | Controller consolidation has been suggested for shell/page builders, but current split remains acceptable until concrete duplication creates maintenance risk. | Horizon idea, not current implementation. | 2026-06-24 |

## Validation Expectations

Changing a service edge requires focused tests for both caller and callee expectations, plus startup validation for application wiring.

## Related Decisions

See `DECISION-20260719-02`, `DECISION-20260526-02`, `DECISION-20260522-06`.

## Related Knowledge

Search knowledge filenames for `services`, `workspace`, `orchestration`, `tool`, and `workflow`.
