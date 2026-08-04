## Prompt

Propose a concrete multi-phase implementation plan for refactoring the Magenta project (`/home/dhickel/Code/Java/Magenta`, git branch `core`, 260+ Java files) from a full Spring Boot web application into a reusable core LLM turn-loop framework. The plan must be ordered by dependency removal: delete the web/UI, delete orchestration, delete auth remnants, extract and generalize the core turn loop into framework packages under a new namespace, build a facade API, and produce tests/docs/config. Every phase must cite specific classes, methods, and dependency edges; identify what breaks and what must be resolved before the next phase; identify parallelization opportunities; and define concrete validation gates. The framework must keep working throughout: build passes, context starts, smoke test.

## Source

- `/home/dhickel/Code/Java/Magenta` — 260+ Java files under `io.mindspice.magenta2`
- `pom.xml` — Spring Boot 4.0.7, Java 25, Spring AI 2.0.0, SimplyPages, SQLite
- `.internal-dev/specifications/architecture.md`, `services.md`, `service-graph.md`
- All source packages inspected: `ai/chat/**`, `ai/orchestration/**`, `ai/skills/**`, `ai/execution/**`, `ai/agent/**`, `ai/config/**`, `api/web/**`, `core/**`

## Findings

### Current Package Inventory (260+ Java source files)

| Package tree | File count | Fate |
|---|---|---|
| `api/web/` (controllers, fragments, SSE, selectors) | ~47 | **Delete** — Phase 1 |
| `api/web/selector/` | ~7 | **Delete** — Phase 1 |
| `ai/orchestration/agents/` | 5 | **Delete** — Phase 2 |
| `ai/orchestration/context/` | 4 | **Delete** — Phase 2 |
| `ai/orchestration/runtime/` | ~55 | **Delete** — Phase 2 |
| `ai/orchestration/settings/` | 3 | **Delete** — Phase 2 |
| `ai/orchestration/tasks/` | 4 | **Delete** — Phase 2 |
| `ai/orchestration/workflow/` | ~22 | **Delete** — Phase 2 |
| `ai/orchestration/workspaces/` | ~48 | **Delete** — Phase 2 |
| `ai/skills/` | ~30 | **Delete** — Phase 2 |
| `ai/agent/job/` | 7 | **Delete** — Phase 2 |
| `ai/execution/` | 8 | **Delete** — Phase 2 |
| `ai/chat/plan/` | ~25 | **Delete** — Phase 2 |
| `ai/chat/task/` | 9 | **Delete** — Phase 2 |
| `ai/chat/project/` | 6 | **Delete** — Phase 2 |
| `ai/chat/system/` | 6 | **Delete** — Phase 2 |
| `ai/chat/tool/file/` | 3 | **Delete** — Phase 2 |
| `ai/chat/tool/shell/` | 3 | **Delete** — Phase 2 |
| `ai/chat/tool/web/` | 3 | **Delete** — Phase 2 |
| `ai/chat/tool/skills/` | 2 | **Delete** — Phase 2 |
| `ai/chat/tool/orchestration/` | 5 | **Delete** — Phase 2 |
| `ai/chat/tool/plan/` | 2 | **Delete** — Phase 2 |
| `ai/chat/tool/savedplan/` | 2 | **Delete** — Phase 2 |
| `ai/chat/tool/task/` | 2 | **Delete** — Phase 2 |
| `ai/chat/tool/InteractionQuestionTools.java` | 1 | **Delete** — Phase 2 |
| `ai/chat/config/` | 1 | **Adapt** — Phase 4 |
| `ai/chat/model/` | ~18 | **Adapt** — Phase 4 |
| `ai/chat/rendering/` | 1 | **Adapt** — Phase 4 |
| `ai/chat/repository/` | 5 | **Adapt/Keep** — Phase 4 |
| `ai/chat/service/` | ~17 | **Heavily adapt** — Phase 4 |
| `ai/chat/service/turn/` | 8 | **Heavily adapt** — Phase 4 |
| `ai/chat/tool/ChatToolRegistry.java` | 1 | **Keep/Adapt** — Phase 4 |
| `ai/chat/tool/ToolTranscriptService.java` | 1 | **Keep/Adapt** — Phase 4 |
| `ai/chat/tool/ToolArgumentCoercionModule.java` | 1 | **Keep** — Phase 4 |
| `ai/config/user/` | 7 | **Adapt** — Phase 4 |
| `core/config/` | 3 | **Keep** — Phase 4 |
| `core/persistence/` | 2 | **Keep** — Phase 4 |
| `core/util/` | 1 | **Keep** — Phase 4 |
| `Magenta2Application.java` | 1 | **Adapt** — Phase 4 |

### Key Dependency Edges That Govern Ordering

1. **Web → Core Chat**: All `api/web/` controllers depend on `ChatService`, orchestration services, plan/task services. This edge is **outbound from web only** — core chat doesn't depend on web. Safe to delete web first.

2. **ChatService → Orchestration**: `ChatService` takes `PlanService`, `TaskService`, `AgentJobService`, `ConversationTurnCoordinator`, `AgentSkillActivationService`, `AgentContextService`, `WorkspaceDirectoryService`, `RuntimeSettingsService`, `AgentsMdResolver`, `RunMeasurementRepository`, and `WorkTypeProfileService` all as `@Autowired(required = false)` constructor dependencies. It also directly calls `planService.mode()`, `planService.view()`, `planService.askQuestions()`, `taskService.startChatExecution()`, etc. — deeply entangled.

3. **RequestResolver → Orchestration**: `RequestResolver` depends on `PlanService`, `TaskService`, `RuntimeSettingsService`, `AgentContextService`.

4. **PromptContextAssembler → Orchestration**: Depends on `PlanService`, `TaskService`, `WorkTypeProfileService`, `AgentsMdResolver`, skill prompt catalog services.

5. **ToolAccessPolicy → Orchestration**: Depends on `ChatToolRegistry`, `PlanService`, `TaskService`, `AgentSkillRuntimeCatalogService`.

6. **TerminalTurnRepair → Plan/Task**: References `PlanService`, `TaskService` for plan/task completion detection and control messages.

7. **TurnAuditWriter → Audit/Jobs**: References `AuditService`, `AuditRepository`, and `AgentJobService`.

8. **TrustedContextPolicyAssembler → Workspaces/Context**: References `AgentContextService`, `WorkspaceDirectoryService`, prompt assembler services.

9. **ChatModelRouter → RuntimeSettings**: Uses `RuntimeSettingsService` for default model resolution.

10. **Tool configurations** (file, shell, web, skills, orchestration, plan, task, savedplan): All depend on orchestration services for workspace resolution and context.

11. **No Spring Security remains** — `AlphaSecurityConfiguration.java` was deleted per changelogs from May 2026. `spring-boot-starter-security` is not in the `pom.xml`. Phase 3 (auth deletion) is a cleanup verification pass, not a removal of a live dependency.

### What Must Be Preserved (Core Framework)

- **Turn loop state machine**: The `toolChat()` method in `ChatService` is a mature 6-state dispatcher (`INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE`) with tool-call validation, malformed-argument handling, tool-execution error recovery, compaction notices, and plan/task completion detection. The state machine logic itself is framework-quality but must be **decoupled** from `PlanService`/`TaskService` awareness.

- **ChatModelRouter**: Model resolution, caching, endpoint-type polymorphism (Ollama/OpenAI/DeepSeek), thinking/reasoning-effort configuration. Framework-ready.

- **ChatToolRegistry**: Resolves `ToolCallback` beans by name, validates approved-tool lists, wildcard support. Clean and framework-ready.

- **ToolLoopGuard**: Guards against identical-tool-call loops and tool-error-rate spirals. Framework-ready.

- **ToolTranscriptService**: Tool execution transcription with argument summaries, result previews, truncation, error detection. Framework-ready but entangled with tool names (`file_list`, `shell_exec`, `web_search`, etc.).

- **ToolArgumentCoercionModule**: Jackson module for forgiving collection deserialization. Framework-ready.

- **ChatPendingMessageService + ChatPendingMessageRepository**: FIFO queue with claim/ack/release, stale-claim recovery. Framework-ready.

- **ChatMemoryRepository + RepositoryBackedChatMemory**: SQLite-backed `ChatMemory` implementation with message-type serialization. Framework-ready.

- **ChatSessionMetadataRepository**: Conversation metadata (model, title, favorite, archived, origin, agent). Framework-ready but references `ChatSessionOrigin` (SYSTEM_CHAT, PROJECT_CHAT) and agent id.

- **ContextManagementAdvisor**: Token estimation, context compaction via summary, tool-loop prompt preparation, stored-context maintenance. Framework-ready.

- **ContextUsageTracker**: Tracks token usage per conversation. Framework-ready.

- **AuditRepository + AuditService**: Records user/assistant messages, context snapshots, errors, title jobs. Framework-ready.

- **ChatMarkdownRenderer**: Markdown → HTML rendering. Framework-ready.

- **ChatStreamEvent**: Sealed interface hierarchy for typed SSE events (Start, Chunk, Tool, SystemNotice, Interrupt, Context, Done, Error, OutputsChanged). Framework-ready but references `ChatPlanState`.

- **SseStreamLifecycle**: SseEmitter lifecycle, subscription guard, timeout/error/disconnect handling. Framework-ready.

- **InteractionQuestionTools**: The `ask_user_questions` tool that suspends the model turn for user input. This is the **question-user loop** machinery. Must be generalized from plan/task-specific to a generic interaction-suspension pattern.

- **ChatFileService**: Conversation-scoped file storage. Could be kept as an example or removed.

- **AiConfig / ModelConfig / AgentConfig / EndpointType**: User-editable configuration model. Must be simplified when agent/orchestration concepts are removed.

- **AiUserConfigConfiguration / ExternalAiConfigLoader**: Configuration loading from JSON. Framework-ready but references `MagentaRootProperties`.

- **SchemaMigrationRunner / SchemaMigrationConfiguration**: Ordered schema migration system. Framework-ready.

- **MagentaRootConfiguration / MagentaRootProperties**: Data-root and SQLite parent-directory initialization. Framework-ready.

### Questions Not Yet Resolved by the Codebase

1. **PlanMode removal strategy**: `PlanMode` enum is pervasive — used in `ChatService.toolChat()` state machine, `ToolAccessPolicy`, `TerminalTurnRepair`, `ResolvedChatRequest`, `ChatResponse`, `ChatStreamEvent`, `ChatPlanState`, and many models. The framework turn loop should not know about plan/task/execute modes. The cleanest approach is to replace `PlanMode` with a simpler interaction-mode concept inside the framework and let callers supply custom completion-detection hooks.

2. **ChatPlanState dependency**: `ChatPlanState` flows through `ChatResponse`, `ChatStreamEvent`, and is returned by `ChatService.planState()`. The facade should not return plan state. The response/event models need their own framework-native state carrier.

3. **ChatSessionOrigin**: `SYSTEM_CHAT`, `PROJECT_CHAT`, `AGENT_CHAT` values are rejected in `ChatService.rejectSystemChatConversation()`. This restriction is app-specific and should be removed or made pluggable.

4. **ConversationTurnCoordinator**: The serial turn gate is currently a Spring bean. The framework needs an equivalent concurrency control but as a pluggable component.

5. **TrustedContextPolicyAssembler**: This resolves workspace binding, allowed paths, and trusted policies before each turn. If workspaces and context bindings are deleted (Phase 2), this entire assembly path collapses. The framework turn loop needs a generic "context enrichment" hook instead.

6. **Tool names embedded in ToolTranscriptService**: The `summarizeResult()` switch statement hard-codes tool names (`file_list`, `file_read`, `shell_exec`, `web_search`, etc.). This must become pluggable or removed.

7. **What to do with `ChatFileService`**: Per-conversation file storage is an app feature, not a framework concern. Should be removed in Phase 2.

8. **Title-job submission**: `AuditService.enqueueTitleJobIfFirstTurn()` submits `AgentJobService` title-generation jobs. The framework can keep a hook for "post-turn actions" but `AgentJobService` must be removed.

## Options

### Option A: Big-Bang Rewrite (Not Recommended)

Delete everything, rewrite from scratch. **Rejected**: Violates the instruction to extract and generalize existing code. Loses proven turn-loop logic.

### Option B: Linear Phase Sequence (Recommended)

Execute phases in strict dependency order: Web → Orchestration → Auth Cleanup → Extract/Generalize → Facade → Tests/Docs. Each phase produces a compilable, startable artifact. **Recommended** because it minimizes broken intermediate states and keeps the build passing at every phase boundary.

### Option C: Web + Orchestration in Parallel (Partially Viable)

Phase 1 (web) and Phase 2 (orchestration) touch different packages with no mutual dependency. However, Phase 2's deletion of orchestration would break ChatService, and Phase 1 doesn't depend on orchestration. So Phase 1 can safely execute while Phase 2 planning begins, but the actual orchestration deletion (Phase 2 edits) must come after Phase 1 completes **or** both can be done in the same commit if ChatService is adapted simultaneously. **Verdict**: Phase 1 and the planning/mechanical-deletion parts of Phase 2 can overlap; the ChatService refactoring in Phase 2 is the serialization point.

### Option D: Extract First, Delete Later (Not Recommended)

Extract the core framework while keeping both the web and orchestration layers, then delete them. **Rejected**: The extraction requires knowing exactly what stays and what goes, and the web/orchestration layers create confusing false dependencies during extraction.

## Trade-offs

| Trade-off | Decision | Rationale |
|---|---|---|
| Keep PlanMode vs. replace with hooks | Replace with pluggable hooks | Plan/task modes are app-specific; a framework should not know about them |
| Keep ChatPlanState in response models vs. remove | Remove; add generic metadata map | Framework responses should carry only framework data |
| Keep ToolTranscriptService tool-name switch vs. make pluggable | Make pluggable via a `ToolResultSummarizer` interface | Let applications define how to summarize their own tools |
| Keep ConversationTurnCoordinator as-is vs. generalize | Generalize into a `TurnGate` interface with default serial implementation | Framework should not depend on Spring `@Component` for concurrency control |
| Keep ChatFileService vs. remove | Remove | App-specific feature |
| Keep `@EnableScheduling` on Application vs. remove | Remove from framework, let embedding apps add it | Framework has no scheduled jobs after orchestration is deleted |
| Keep `ChatSessionOrigin` enum vs. remove/replace | Replace with a simple string or pluggable discriminator | Framework doesn't need to know about app-specific origins |
| SQLite as default vs. pluggable abstraction | SQLite as default, `ChatMemoryRepository` as SPI | Changing storage is a separate concern; the current SQLite implementation works |
| New artifact name | `io.mindspice.core:magenta-core` or `io.mindspice:turnloop` | Need user input — suggested below |
| Keep `AuditService` / `AuditRepository` vs. remove | Keep as pluggable audit SPI | Audit trails are useful for framework consumers; make optional |

## Open Questions

1. **New package naming**: Should the framework artifact be `io.mindspice.core:magenta-core` (retaining the Magenta name for continuity), `io.mindspice:turnloop` (describing what it does), or something else entirely?

2. **Should `ChatFileService` and conversation-scoped file storage be kept as an optional module?** The code references `ChatFileService` for file counts in session listings and for `FILE_MUTATING_TOOL_NAMES`. If all file/web/shell tools are deleted, this has no consumer.

3. **What becomes of the `ai/config/user/` package?** `AiConfig`, `ModelConfig`, and `AgentConfig` are the current user-facing configuration model. After orchestration deletion, `AgentConfig` (which references agent profiles, approved tools, default models) becomes vestigial. Should it be simplified to just `ModelConfig` + `FrameworkConfig`?

4. **Should the framework keep `SystemChatService` and the bounded-read pattern?** System chat is an app-specific feature for agent operational consoles. It should be deleted.

5. **Should `WorkspaceDirectoryService` and the filesystem-first workspace model be kept?** The workspace model is deeply app-specific (agent/chat/user workspaces, task-run locations, materializations). The framework itself should not own filesystem layout. If any filesystem access is needed (e.g., for tool examples), the embedding app provides it.

6. **What example tools should ship with the framework?** The prompt says "a small set of basic tools as examples of the tool framework." Candidates: a calculator, a datetime tool, a simple echo tool. These demonstrate `@Tool` annotation usage, argument coercion, and transcript patterns without filesystem/network/process concerns.

7. **Should the `SchemaMigrationRunner` be kept or simplified?** The current runner manages 20+ migrations for orchestration tables. After Phase 2, only `ai_chat_memory`, `ai_chat_session_metadata`, `ai_chat_pending_messages`, and `audit_log` tables remain. The runner should be retained but simplified to manage only framework-owned tables.

## Recommended Next Step

Execute the six-phase plan below. **The plan is designed for the `core` branch** and maintains buildability at every phase boundary. All deletions happen in Phases 1-3; all generalizations happen in Phase 4; the facade is built in Phase 5; tests/docs close out in Phase 6. Phase 1 can begin immediately; Phases 1 and the bulk-deletion parts of Phase 2 are parallelizable.

---

# Phased Migration Plan: Magenta → `magenta-core` Framework

## Phase 1: Delete Web/UI Surface

**Objective**: Remove all web controllers, fragments, static resources, SSR rendering, HTMX, SimplyPages, and webjar dependencies. The application becomes a headless Spring Boot service with only programmatic API entry points.

### Delete Inventory

| Path | Files | Notes |
|---|---|---|
| `src/main/java/io/mindspice/magenta2/api/web/` | ~47 files | All controllers, fragments, SSE support, exception handler, navigation, selectors |
| `src/main/java/io/mindspice/magenta2/api/web/selector/` | ~7 files | Entity selector components |
| `src/main/resources/static/css/` | All CSS | SimplyPages + custom styles |
| `src/main/resources/static/js/` | All JS | HTMX extensions, SimplyPages JS |
| `src/main/resources/static/favicon.ico` | 1 file | Favicon |

### Maven Dependency Removals (pom.xml)

```xml
<!-- REMOVE -->
<dependency>
    <groupId>io.mindspice</groupId>
    <artifactId>simplypages</artifactId>
    <version>1.1.0a</version>
</dependency>
<dependency>
    <groupId>org.webjars.npm</groupId>
    <artifactId>htmx.org</artifactId>
    <version>1.9.10</version>
</dependency>
<dependency>
    <groupId>org.webjars</groupId>
    <artifactId>webjars-locator-core</artifactId>
    <version>0.59</version>
</dependency>
```

Also remove `spring-boot-starter-webmvc-test` (test scope) since there are no web controllers to test. Replace with `spring-boot-starter-test` for core service testing.

### What Breaks

1. **Test files that reference web controllers**: ~17 test files under `src/test/.../api/web/` must be deleted. Tests for `ChatController`, `OrchestrationController`, `FrontendController`, `WorkspaceController`, etc. are all web-specific.

2. **`Magenta2Application.java`**: The `@SpringBootApplication` with `@EnableScheduling` remains. `spring-boot-starter-web` can be replaced with a lighter starter (e.g., just `spring-boot-starter` + manual Jackson/web dependency if SSE streaming is kept for the framework's own transport).

3. **`ChatStreamEvent` and `SseStreamLifecycle`**: These live in `api/web/` but are framework concepts (SSE streaming). They must be **moved** to the chat service package or to a new `io.mindspice.magenta2.ai.chat.stream` package before deleting the rest of `api/web/`. They are consumed by `ChatService.stream()` and `ChatService.streamNow()`.

4. **`ChatMarkdownRenderer`**: Lives in `ai/chat/rendering/` and is NOT in `api/web/`. It stays. It's used by `ChatService.renderAssistantMessage()` to produce HTML from markdown. Keep it.

5. **Spring Boot starter change**: Replace `spring-boot-starter-web` (which pulls in embedded Tomcat, MVC, Jackson) with `spring-boot-starter` + `spring-boot-starter-json` (Jackson) + `spring-boot-starter-webflux` if reactive SSE streaming is desired, or just keep `spring-boot-starter-web` for the embedded server but remove all controllers.

### Validation Gate

```bash
# Delete all web files, update pom.xml
mvn clean compile       # Must pass — no references to deleted controllers
mvn test               # Non-web tests must pass
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0"  # Context starts, no web endpoints
timeout 15s mvn spring-boot:run  # Verify startup reaches "Started Magenta2Application"
```

### Parallelization

**Fully parallelizable** — no other phase depends on web files, and web files don't depend on anything being deleted in later phases. Can be done simultaneously with Phase 2's deletion-planning pass.

---

## Phase 2: Delete Orchestration, Plans, Tasks, Projects, Workspaces, Skills, Agent Management

**Objective**: Remove the entire orchestration layer, plan/task services, project services, workspace services, agent management, skills, execution coordination, and all app-specific tool implementations. This is the largest phase and the one that most deeply impacts `ChatService`.

### Delete Inventory — Orchestration Trees

| Path | Files | Notes |
|---|---|---|
| `ai/orchestration/agents/` | 5 | AgentProfile, repository, seeder, service, status |
| `ai/orchestration/context/` | 4 | AgentContextService, repository, materialization |
| `ai/orchestration/runtime/` | ~55 | Assignment, jobs, projects, lifecycle, events, schedules, measurements, inbox |
| `ai/orchestration/settings/` | 3 | RuntimeSettings, repository, service |
| `ai/orchestration/tasks/` | 4 | WorkTask definitions/repository/service |
| `ai/orchestration/workflow/` | ~22 | Workflow definitions, runs, nodes, ports, runner, validator |
| `ai/orchestration/workspaces/` | ~48 | WorkspaceDescriptor, directory service, explorer, metadata, artifacts, paths |
| `ai/skills/` | ~30 | Skill discovery, resolution, trust, snapshots, activation, catalog |
| `ai/agent/job/` | 7 | AgentJob, repository, service, config, types |
| `ai/execution/` | 8 | ConversationTurnCoordinator, ActiveTurnRegistry, MagentaWorkExecutor, InterruptResult |

### Delete Inventory — Chat Sub-packages

| Path | Files | Notes |
|---|---|---|
| `ai/chat/plan/` | ~25 | PlanDefinition, PlanService, PlanRepository, PlanRun, PlanToolContext, etc. |
| `ai/chat/task/` | 9 | TaskDefinition, TaskService, TaskRun, TaskDraft, etc. |
| `ai/chat/project/` | 6 | ProjectChatService, ProjectChatProfile, etc. |
| `ai/chat/system/` | 6 | SystemChatService, SystemChatToolRegistry, etc. |
| `ai/chat/tool/file/` | 3 | AgentFileTools, configuration, service |
| `ai/chat/tool/shell/` | 3 | AgentShellTools, configuration, service |
| `ai/chat/tool/web/` | 3 | AgentWebTools, configuration, service |
| `ai/chat/tool/skills/` | 2 | AgentSkillActivationTools, configuration |
| `ai/chat/tool/orchestration/` | 5 | AgentOperationalTools, configuration, authorization |
| `ai/chat/tool/plan/` | 2 | PlanSaveTools, configuration |
| `ai/chat/tool/savedplan/` | 2 | SavedPlanTools, configuration |
| `ai/chat/tool/task/` | 2 | TaskTools, configuration |
| `ai/chat/tool/InteractionQuestionTools.java` | 1 | Interaction question tools |
| `ai/chat/service/ChatFileService.java` | 1 | Conversation file storage |
| `ai/chat/service/ChatInvocationProfile.java` | 1 | Agent invocation profile (depends on agent config) |

### Delete Inventory — Config

| Path | Files | Notes |
|---|---|---|
| `ai/config/user/AgentConfig.java` | 1 | Agent configuration model |
| `ai/config/user/WebSearchConfig.java` | 1 | Web search configuration |

### Adapt Inventory — Critical Refactoring Targets

These files are **kept** but must be **surgically refactored** to remove all orchestration/plan/task dependencies:

#### `ChatService.java` (~3000 lines) — The Hardest Refactoring

**Current dependency list to remove:**
- `PlanService planService` — used in ~50+ places
- `TaskService taskService` — used in ~20+ places
- `AgentJobService agentJobService` — used for title jobs
- `ConversationTurnCoordinator turnCoordinator` — serial turn gate
- `AgentSkillActivationService agentSkillActivationService` — skill activation
- `AgentContextService agentContextService` — workspace context binding
- `WorkspaceDirectoryService workspaceDirectoryService` — descriptor resolution
- `RuntimeSettingsService runtimeSettingsService` — model defaults
- `AgentsMdResolver agentsMdResolver` — AGENTS.md resolution
- `RunMeasurementRepository measurementRepo` — run measurements
- `WorkTypeProfileService workTypeProfileService` — work type profiles
- `AgentSkillRuntimeCatalogService skillRuntimeCatalogService` — skill catalog
- `AgentSkillPromptCatalogAssembler skillPromptCatalogAssembler` — skill prompts

**Methods to remove entirely:**
- `beginPlan()`, `beginPlanFromDefinition()`, `exitPlan()`, `submitPlanAnswer()`
- `approvePlan()`, `continuePlanning()`, `executeSavedPlan()`, `resolveSavedPlanExecution()`
- `handlePlanExecutionStreamFinished()`, `recordExecutionFailure()`, `markPlanNeedsReviewResponse()`
- `executeTaskBlocking()`, `streamTaskExecution()`, `resolveTaskExecution()`
- `chatAsAgent()`, `markAgentConversation()`, `listAgentSessions()`
- `installChatFileContextIfNeeded()`, `restoreContext()`
- `rejectSystemChatConversation()` (or make pluggable)
- `planState()`, `isValidatedPlanCompletion()`, `persistControlledReviewMessage()`
- All plan/task-specific repair methods and control messages

**State machine (toolChat) changes:**
- Remove `PlanToolExecutionContext.set()`/`.clear()` calls
- Remove plan-completion detection (`planService.mode()` checks in `handleExecuteTools`)
- Remove task-completion detection (`taskService.mode()` checks)
- Remove `planCompletionDetected` flag and related logic
- Remove `requiresPlanTurnRepair()`, `requiresExecutionCompletionRepair()`
- Remove `invalidPlanTurnControlMessage()`, `invalidExecutionCompletionControlMessage()`
- Replace with generic completion-detection hooks and repair-strategy interfaces
- The 6-state machine (`INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE`) stays structurally identical

**Remaining methods to keep/adapt:**
- `chat(ResolvedChatRequest)` — core turn entry point, keep
- `stream(ResolvedChatRequest)` — streaming entry point, keep
- `chatNow()`, `streamNow()` — internal execution, keep
- `toolChat()` state machine — keep, generalize completion hooks
- `plainChat()` — keep
- `clearConversation()` — keep, remove plan/task/skill cleanup
- `history()`, `contextUsage()`, `maintainContextUsage()` — keep
- `listSessions()`, `listConversationIds()` — keep, simplify
- `session()`, `renameConversation()`, `setConversationFavorite()`, `setConversationArchived()` — keep
- `defaultModel()`, `availableModels()`, `availableModelOptions()` — keep
- `renderAssistantMessage()` — keep

#### `RequestResolver.java`

**Remove dependencies:** `PlanService`, `TaskService`, `RuntimeSettingsService`, `AgentContextService`, `AgentConfig`.

**Methods to remove:** `resolvedPlanningModel()`, context-binding-key resolution, agent-context resolution, planning/task model overrides.

**Keep:** Basic conversation-id resolution, model selection from stored metadata + default, message resolution, `ResolvedChatRequest` construction.

#### `PromptContextAssembler.java`

**Remove dependencies:** `PlanService`, `TaskService`, `WorkTypeProfileService`, `AgentsMdResolver`, `AgentSkillPromptCatalogAssembler`.

**Simplify to:** A system-prompt + mode-intent + conversation-history assembler with a pluggable `PromptEnricher` interface for embedding applications.

#### `ToolAccessPolicy.java`

**Remove dependencies:** `PlanService`, `TaskService`, `AgentSkillRuntimeCatalogService`.

**Simplify to:** A tool-name allowlist/blocklist filter that maps mode names to allowed tool-name sets. Embedding apps register their own mode→tools mappings. Remove hard-coded `PLAN_MODE_TOOLS`, `TASK_MODE_TOOLS`, `NORMAL_BLOCKED_TOOLS`, `EXECUTION_BLOCKED_TOOLS` constants.

#### `TerminalTurnRepair.java`

**Remove dependencies:** `PlanService`, `TaskService`.

**Repurpose as:** A generic "turn did not produce valid output" repair-strategy interface. The framework provides a default `EmptyResponseRepair` implementation. Embedding apps can supply custom repair strategies.

#### `TurnAuditWriter.java`

**Remove dependencies:** `AgentJobService` (title-job submission).

**Keep:** Turn-start recording, assistant-message recording, tool-execution recording, context-usage recording, error recording. Remove `enqueueTitleJob()` — replace with a `TurnLifecycleListener` hook.

#### `TrustedContextPolicyAssembler.java`

**Remove entirely.** Trusted context policy (workspace descriptor binding, allowed-path derivation, AGENTS.md resolution) is an app-level concern, not a framework concern. The framework accepts pre-resolved context information from the embedding application.

#### `ChatModelRouter.java`

**Remove dependency:** `RuntimeSettingsService`.

**Adapt:** Change `defaultRemoteModelName()` to fall back to `AiConfig.defaultModel` or the first configured model. Remove runtime-settings override. Keep the three-endpoint model builder (Ollama, OpenAI-compatible, DeepSeek).

#### `ContextManagementAdvisor.java`

**Remove dependency:** `RuntimeSettingsService`.

**Adapt:** Remove runtime-settings override for compaction thresholds. All thresholds come from `AiConfig`. No other changes needed — this class is framework-ready.

#### Model/Record Adaptations

| File | Change |
|---|---|
| `ChatPlanState.java` | **Remove**. Replace with a generic metadata map (`Map<String, Object>`) in `ChatResponse` and `ChatStreamEvent`. |
| `PlanMode.java` | **Remove**. Replace with a simple `String interactionMode` or a framework-defined `TurnMode` (NORMAL, TOOL_USE, COMPLETION_DETECTED). Actually, remove entirely — modes are app-specific; the framework just runs turns. |
| `ChatResponse.java` | Remove `planState()` field. Add `Map<String, Object> metadata`. |
| `ChatStreamEvent.java` | Remove `ChatPlanState` references. Add `Map<String, Object> metadata`. |
| `ChatSession.java` | Remove `ChatSessionOrigin`. Replace with a `String origin` field. |
| `ChatSessionOrigin.java` | **Remove**. Replace with string. |
| `ChatSessionSurface.java` | **Remove**. App-specific concept (AVATAR, CHAT, etc.). |
| `ResolvedChatRequest.java` | Remove `contextBindingKey()`, `withoutTitleJob()`, `omittingStoredMessages()`. |
| `ChatRequest.java` | Simplify `MsgRequest` — remove `planningModel`, `surface` fields. |
| `PendingChatMessage.java` | Remove `surface` field, `planningModel` field. |
| `ChatToolActivity.java` | No changes needed — framework-ready. |
| `ChatMessage.java` | No changes needed — framework-ready. |
| `ContextUsage.java` | No changes needed — framework-ready. |

### Maven Dependency Changes

No new removals from pom.xml beyond Phase 1 changes. `spring-boot-starter-jdbc` (for SQLite via JdbcTemplate) and `sqlite-jdbc` stay.

### What Breaks

1. **`ChatService` won't compile** after deleting `PlanService`, `TaskService`, etc. — the entire Phase 2 is a refactoring pass on `ChatService` to extract all orchestration references.

2. **Tests:** Delete all test files that reference orchestration, plan, task, project, skills, system-chat, workspaces, agents:
   - `PlanServiceTest`, `PlanRepositoryTest`, `SavedPlanChatServiceTest`, `WorkTypeProfileServiceTest`, `WorkTypeProfileTest`
   - `SystemChatServiceTest`, `SystemChatTransactionIntegrationTest`
   - `AgentShellToolServiceTest`, `AgentWebToolServiceTest`
   - `OrchestrationRuntimeTest`, `AgentContextPersistenceRepositoryTest`, `AgentContextServiceResolutionTest`, `MaterializationKeyTest`
   - `WorkTaskServiceTest`
   - `AgentSkillAssignmentCatalogActivationTest`, `AgentSkillAssignmentRepositoryOverlayTest`, `AgentSkillCatalogServiceTest`, `AgentSkillParserTest`, `SkillResolutionServiceOverlayTest`, `SkillScopeDiscoveryResolutionTest`
   - `ToolArgumentCoercionConfigTest` — keep, it tests framework code
   - `ChatMarkdownRendererTest` — keep
   - `ChatStreamEventSerializationTest` — adapt to new model

3. **`AiConfig.java`**: Remove `agents()` (Map of AgentConfig), `defaultAgent()`, `resolvedDefaultModelKey()`, `resolvedPlanningModelKey()`, `planningModel()`, `summeryModel()`, `compactionModel()`, `unsafeAllowWildcardShellCommands()`. Keep `models()`, `defaultModel()`, `summaryModel()`, `dataRoot()`, `webSearch()`, `contextBufferPercent()`.

4. **`AiUserConfigConfiguration.java`**: Remove `AgentConfig` resolution. Keep model loading.

5. **`ChatBeanConfig.java`**: Remove `RuntimeSettingsService` parameter from `contextManagementAdvisor()` bean. Otherwise keep.

6. **`ExternalAiConfigLoader.java`**: Simplify to load only models, default model, summary model, data root, web search config. Remove agent loading.

### Validation Gate

```bash
mvn clean compile       # Must pass — zero compile errors
mvn test               # All remaining tests pass
timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0"  # Context starts
# Smoke test: programmatic chat call works through a simple main() or test
```

### Parallelization

Phase 2 is the serialization bottleneck. However, **mechanical deletions** (deleting the ~150 orchestration files, ~80 chat-sub-package files, and test files) can begin in parallel with Phase 1. The `ChatService` refactoring is the critical path and must be done sequentially by one engineer (or subagent).

---

## Phase 3: Auth System Cleanup

**Objective**: Verify no auth code remains, remove any residual security configuration, and clean up auth-related dependencies and properties.

### Current State

Per `.internal-dev/changelogs/2026-05-19-remove-leftover-alpha-auth-prompts-runtime.md`, `AlphaSecurityConfiguration.java` was already deleted. `spring-boot-starter-security` is not in `pom.xml`. The auth removal was completed in May 2026.

### Verification Actions

1. **Grep for auth-related code** across all remaining source:
   ```bash
   rg -n "MAGENTA_ALPHA|alpha-access|spring-boot-starter-security|spring-security-test|AlphaSecurity|alpha-security|XSRF|csrf|httpBasic|SecurityFilterChain|UserDetailsService|EnableWebSecurity|Basic realm|mag-auth-error|magenta:security-error" -g '!target/**' -g '!**/.archive/**' -g '!.internal-dev/**' .
   ```
   Expected: zero results.

2. **Check `application.yml`**: Remove any `magenta.alpha-access` or security-related properties if present.

3. **Check for `@EnableMethodSecurity` or `@EnableWebSecurity`**: Expected zero results.

### Delete Inventory

| Path | Condition | Notes |
|---|---|---|
| Any auth config class | If found by grep | Delete immediately |
| `spring-boot-starter-security` in pom.xml | If present | Remove from pom.xml |
| `spring-security-test` in pom.xml | If present | Remove from pom.xml |

### Validation Gate

```bash
mvn clean compile       # No auth class references
mvn test               # Tests pass
timeout 30s mvn spring-boot:run  # No security auto-configuration
```

### Parallelization

Phase 3 is fully parallelizable with Phase 2's mechanical deletions. It's a read-only verification pass in the current codebase, with potential minor edits.

---

## Phase 4: Extract and Generalize Core Framework

**Objective**: Move the surviving core chat machinery into clean framework packages under a new root namespace. Generalize the tool framework, turn loop, memory, queueing, and event streaming. Remove all remaining Magenta-specific naming and concepts.

### New Package Structure

```
io.mindspice.core                    (or user-chosen namespace)
├── CoreApplication.java            (replaces Magenta2Application)
├── config/
│   ├── CoreConfig.java             (replaces AiConfig — simplified)
│   ├── ModelConfig.java            (endpoint config — kept from user/)
│   ├── EndpointType.java           (kept from user/)
│   ├── CoreAutoConfiguration.java  (Spring Boot auto-config)
│   └── CoreProperties.java         (framework application properties)
├── turn/
│   ├── TurnLoop.java               (extracted from ChatService.toolChat() state machine)
│   ├── TurnContext.java            (kept, generalized)
│   ├── TurnPhase.java              (kept)
│   ├── TurnDiagnostic.java         (kept)
│   ├── TurnLifecycleListener.java  (new — hook for audit, title, post-turn actions)
│   ├── CompletionDetector.java     (new — pluggable completion hook, replaces plan/task detection)
│   └── RepairStrategy.java         (new — pluggable repair, replaces TerminalTurnRepair)
├── model/
│   ├── TurnRequest.java            (replaces ChatRequest.MsgRequest — simplified)
│   ├── TurnResponse.java           (replaces ChatResponse.MsgResponse)
│   ├── TurnMessage.java            (replaces ChatMessage)
│   ├── TurnStreamEvent.java        (replaces ChatStreamEvent)
│   ├── ToolActivity.java           (replaces ChatToolActivity)
│   ├── ConversationSession.java    (replaces ChatSession)
│   └── ContextUsage.java           (kept)
├── router/
│   ├── ModelRouter.java            (replaces ChatModelRouter)
│   └── ModelResolution.java        (extracted record)
├── tool/
│   ├── ToolRegistry.java           (replaces ChatToolRegistry)
│   ├── ToolTranscriptService.java  (kept, made pluggable)
│   ├── ToolLoopGuard.java          (kept)
│   ├── ToolAccessPolicy.java       (kept, generalized)
│   ├── ToolArgumentCoercionModule.java  (kept)
│   ├── ToolResultSummarizer.java   (new SPI — replaces hard-coded switch)
│   └── example/
│       ├── CalculatorTools.java    (new example tool)
│       └── DateTimeTools.java      (new example tool)
├── memory/
│   ├── MemoryRepository.java       (replaces ChatMemoryRepository — now an interface)
│   ├── SqliteMemoryRepository.java (SQLite implementation)
│   ├── MemoryManager.java          (replaces ContextManagementAdvisor)
│   ├── MemoryCompactor.java        (extracted compaction logic)
│   └── ConversationMetadataRepository.java (replaces ChatSessionMetadataRepository)
├── queue/
│   ├── PendingMessageQueue.java    (replaces ChatPendingMessageService)
│   └── SqlitePendingMessageQueue.java (SQLite implementation)
├── stream/
│   ├── StreamLifecycle.java        (replaces SseStreamLifecycle)
│   └── StreamEventEmitter.java     (new abstraction over SseEmitter)
├── interaction/
│   ├── InteractionSuspension.java  (new — generic question-user loop)
│   └── InteractionTools.java       (replaces InteractionQuestionTools, generalized)
├── audit/
│   ├── AuditWriter.java            (replaces AuditService + AuditRepository)
│   └── NoOpAuditWriter.java        (default no-op implementation)
├── rendering/
│   └── MarkdownRenderer.java       (replaces ChatMarkdownRenderer)
├── persistence/
│   ├── SchemaMigration.java        (replaces SchemaMigrationRunner, simplified)
│   └── DataRootInitializer.java    (replaces MagentaRootConfiguration)
└── core/
    ├── Core.java                   (THE FACADE — see Phase 5)
    ├── CoreBuilder.java            (builder for the facade)
    └── CoreException.java          (framework exception hierarchy)
```

### Generalization Rules

1. **Every `Chat*` prefix becomes `Turn*` or is named by its domain concept** (e.g., `ChatToolRegistry` → `ToolRegistry`, `ChatMemoryRepository` → `MemoryRepository`).

2. **Every `magenta*` property prefix becomes `core.*`** (e.g., `magenta.root.path` → `core.data-root`, `magenta.ai.openai-compatible-read-timeout-seconds` → `core.model.read-timeout-seconds`).

3. **Spring `@Service` / `@Component` / `@Repository` annotations stay** — the framework is Spring-native. An `@AutoConfiguration` class wires defaults but lets embedding apps override any bean.

4. **`PlanMode` is replaced by a `TurnDirective`**: A simple string or enum that the framework passes through to tool access policy and completion detectors without understanding its semantics. Embedding apps register their directives.

5. **`ChatPlanState` is removed**: `TurnResponse` and `TurnStreamEvent` carry a `Map<String, Object> metadata` field for app-specific state.

6. **Tool transcript tool-name switch is replaced by `ToolResultSummarizer` SPI**:

```java
public interface ToolResultSummarizer {
    String summarize(String toolName, String resultJson);
    boolean looksLikeError(String resultJson);
}
```

The framework ships with a `DefaultToolResultSummarizer` that returns generic "Returned N characters" summaries. Embedding apps register custom summarizers.

7. **Interaction question-user loop is generalized**:

```java
public interface InteractionHandler {
    /** Called when the model calls ask_user_questions. Returns the questions to present. */
    List<String> onQuestionsAsked(String conversationId, List<String> questions);

    /** Called when the user answers. Returns the formatted answer message for the model. */
    String onAnswerReceived(String conversationId, String answer);
}
```

The framework suspends the turn, persists the questions via a pluggable `InteractionStore`, and resumes when `core.answer(conversationId, answer)` is called.

8. **`ConversationTurnCoordinator` becomes a `TurnGate` interface**:

```java
public interface TurnGate {
    <T> CompletableFuture<T> submit(String conversationId, Callable<T> work);
}
```

The framework ships with `SerialTurnGate` (the current deque-based implementation). Embedding apps can provide `NoOpTurnGate` (no serialization) or custom implementations.

9. **`TrustedContextPolicyAssembler` is removed**: Replaced by a `TurnEnricher` interface that allows embedding apps to inject system prompts, tool lists, and context data before each turn:

```java
public interface TurnEnricher {
    EnrichedTurn enrich(TurnRequest request);
    record EnrichedTurn(String systemPrompt, List<String> approvedToolNames,
                        List<Message> contextMessages, Map<String, Object> metadata) {}
}
```

### Maven Changes

```xml
<groupId>io.mindspice</groupId>
<artifactId>magenta-core</artifactId>  <!-- or user-chosen name -->
<version>1.0.0-SNAPSHOT</version>
<name>magenta-core</name>
<description>Reusable LLM turn-loop framework</description>
```

Keep all Spring AI and SQLite dependencies. Remove `spring-boot-starter-web` — the framework doesn't serve HTTP. Use `spring-boot-starter` (core DI/config) + `spring-boot-starter-jdbc` (SQLite) + `spring-boot-starter-json` (Jackson) + `spring-boot-starter-actuator` (optional). The embedding application adds its own `spring-boot-starter-web` or `spring-boot-starter-webflux`.

### Validation Gate

```bash
mvn clean compile       # All new package structure compiles
mvn test               # Adapted + new tests pass
timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0"
# Smoke test: programmatic chat call via Core facade (even if it's a temporary main() method)
```

### Parallelization

Phase 4 cannot be parallelized with Phases 1-3 because it depends on their completion (all deletions done, ChatService refactored). However, sub-packages within Phase 4 can be worked on in parallel by separate engineers: `turn/`, `tool/`, `memory/`, `queue/`, `stream/`, `interaction/` are mostly independent once the shared model/ and router/ packages are stable.

---

## Phase 5: Build the Facade API (`Core`)

**Objective**: Create `Core` and `CoreBuilder` as the single public entry point. Everything the embedding application needs goes through this facade. All internal services are package-private or `protected`.

### Facade API Surface

```java
package io.mindspice.core.core;

public final class Core implements AutoCloseable {

    // === Lifecycle ===
    @Override public void close();  // Shuts down, releases resources

    // === Messaging ===
    TurnResponse send(TurnRequest request);
    Flux<TurnStreamEvent> stream(TurnRequest request);

    // === Interaction (Question-User Loop) ===
    List<PendingQuestion> pendingQuestions(String conversationId);
    TurnResponse answer(String conversationId, String answer);

    // === Conversation Management ===
    String createConversation();
    List<ConversationSession> listConversations();
    List<TurnMessage> history(String conversationId);
    void clearConversation(String conversationId);
    ContextUsage contextUsage(String conversationId);

    // === Tool Registration ===
    void registerTool(Object toolBean);       // @Tool-annotated bean
    void registerToolCallback(ToolCallback callback);
    void registerToolSummarizer(String toolName, ToolResultSummarizer summarizer);

    // === Pending Messages (Queue) ===
    PendingMessage enqueue(String conversationId, String message);
    Optional<ClaimedMessage> claim(String conversationId);
    boolean ack(String conversationId, String messageId, String claimToken);
    boolean release(String conversationId, String messageId, String claimToken);

    // === Builder ===
    static CoreBuilder builder() { return new CoreBuilder(); }
}
```

### CoreBuilder

```java
public final class CoreBuilder {
    public CoreBuilder modelRouter(ModelRouter router);
    public CoreBuilder memoryManager(MemoryManager memoryManager);
    public CoreBuilder turnGate(TurnGate turnGate);
    public CoreBuilder auditWriter(AuditWriter auditWriter);
    public CoreBuilder turnEnricher(TurnEnricher enricher);
    public CoreBuilder completionDetector(CompletionDetector detector);
    public CoreBuilder repairStrategy(RepairStrategy strategy);
    public CoreBuilder toolAccessPolicy(ToolAccessPolicy policy);
    public CoreBuilder interactionHandler(InteractionHandler handler);
    public CoreBuilder markdownRenderer(MarkdownRenderer renderer);
    public CoreBuilder dataRoot(Path dataRoot);
    public CoreBuilder toolResultSummarizer(ToolResultSummarizer summarizer);
    public CoreBuilder registerTool(Object toolBean);
    public Core build();
}
```

If the embedding application uses Spring Boot auto-configuration, `CoreAutoConfiguration` wires sensible defaults from `application.yml` and available beans. For programmatic use, `CoreBuilder` provides explicit wiring.

### Exceptions

```java
public class CoreException extends RuntimeException { ... }
public class TurnRejectedException extends CoreException { ... }
public class ToolExecutionException extends CoreException { ... }
public class ConversationNotFoundException extends CoreException { ... }
// etc.
```

### SSE Streaming API

The framework does NOT serve HTTP directly. `Core.stream()` returns a `Flux<TurnStreamEvent>`. The embedding application's controller converts this to SSE:

```java
@GetMapping("/chat/{conversationId}/stream")
public SseEmitter stream(@PathVariable String conversationId, @RequestParam String message) {
    SseEmitter emitter = StreamLifecycle.createEmitter();
    TurnRequest request = new TurnRequest(conversationId, message);
    Flux<TurnStreamEvent> flux = core.stream(request);
    // Wire flux events to SSE emitter using StreamLifecycle
    return emitter;
}
```

The `StreamLifecycle` utility class remains in the framework for this purpose.

### Validation Gate

```bash
mvn clean compile       # Core.java and CoreBuilder.java compile
mvn test               # Facade integration tests pass
# Write a simple main() that builds a Core, sends a message, prints the response
```

### Parallelization

Phase 5 depends on Phase 4 completion (all internal packages are in place). The facade itself is a thin delegation layer and can be built quickly once internals are ready.

---

## Phase 6: Comprehensive Tests, Configuration Cleanup, Documentation

**Objective**: Achieve production quality with thorough tests, clean configuration, and clear documentation.

### Test Inventory

| Test area | Files to create | What it covers |
|---|---|---|
| Turn loop | `TurnLoopTest.java` | 6-state machine: normal completion, tool-call loop, malformed arguments, empty response repair, tool-use abort, conversation-history persistence |
| Tool framework | `ToolRegistryTest.java` | Registration, resolution, wildcard, unknown-tool rejection, argument coercion (scalar→collection) |
| Tool transcript | `ToolTranscriptServiceTest.java` | Full/summary entry creation, truncation, error detection, expiration, model/history rendering |
| Model router | `ModelRouterTest.java` | Ollama/OpenAI/DeepSeek endpoint resolution, caching, default model fallback |
| Memory | `MemoryManagerTest.java`, `SqliteMemoryRepositoryTest.java` | Message CRUD, compaction (summary/window/tail), token estimation, conversation-id listing |
| Queue | `PendingMessageQueueTest.java` | Enqueue/list/claim/ack/release, stale-claim recovery, ordering |
| Interaction | `InteractionToolsTest.java`, `InteractionSuspensionTest.java` | Question normalization, suspend/resume cycle, answer formatting |
| Stream | `StreamLifecycleTest.java`, `TurnStreamEventSerializationTest.java` | SSE emit/complete/timeout/error, event serialization round-trip |
| Facade | `CoreTest.java`, `CoreBuilderTest.java` | Full turn round-trip, streaming round-trip, question-user cycle, queue cycle, conversation lifecycle |
| Config | `CoreAutoConfigurationTest.java` | Auto-configuration wires defaults, properties override, custom beans override |
| Persistence | `SchemaMigrationTest.java` | Framework-owned tables upgrade/validate |
| Concurrency | `TurnGateTest.java` | Serial turn gate, concurrent submission ordering, cancellation |

### Configuration Surface (application.yml)

```yaml
core:
  data-root: ${user.home}/.magenta-core   # SQLite + data storage
  models:
    - key: default
      endpoint-type: OLLAMA               # OLLAMA | OPENAI_COMPATIBLE | DEEPSEEK
      remote-endpoint: http://localhost:11434
      remote-model-name: qwen3.6:35b
      think-level: 0                      # 0-4 (0=off)
      api-key: ""                         # For OpenAI/DeepSeek
  memory:
    compaction-threshold-tokens: 32000
    context-buffer-percent: 15
    min-tail-messages: 6
  model:
    read-timeout-seconds: 360
  turn:
    default-model: default                # model key reference
```

### Documentation

| Doc | Content |
|---|---|
| `README.md` | Framework overview, quick-start, embedding example |
| `docs/getting-started.md` | Maven/Gradle coordinates, minimal Spring Boot integration, `CoreBuilder` usage |
| `docs/turn-loop.md` | Turn loop architecture, state machine, completion detection, repair strategies |
| `docs/tools.md` | `@Tool` annotation usage, `ToolCallback` registration, argument coercion, transcripts, example tools |
| `docs/memory.md` | Memory abstraction, SQLite default, compaction, `MemoryRepository` SPI |
| `docs/streaming.md` | SSE integration, `Flux<TurnStreamEvent>` consumption, `StreamLifecycle` |
| `docs/interaction.md` | Question-user loop, interaction suspension/resume, `InteractionHandler` |
| `docs/queueing.md` | Pending message queue, claim/ack/release, stale-claim recovery |
| `docs/configuration.md` | All `core.*` properties, `CoreAutoConfiguration`, programmatic wiring |
| `docs/facade.md` | `Core` API reference, `CoreBuilder`, threading model, lifecycle |
| `docs/embedding.md` | How to embed in a Spring Boot app, adding web transport, custom tools |

### Validation Gate

```bash
mvn clean verify      # compile + test + package
mvn test jacoco:report  # Coverage report — aim for >80% on framework packages
timeout 30s mvn spring-boot:run  # Auto-configuration smoke test
# Manual: verify README quick-start produces a working Core instance
```

### Parallelization

Tests and docs can be written in parallel across multiple engineers/subagents by test area. Configuration cleanup is a single-person task.

---

## Dependency Graph

```
Phase 1 (Web Delete) ─────────────────────────────────────────┐
  │  independent of all phases                                │
  └───────────────────────────────────────────────────────────┤
                                                               │
Phase 2 (Orchestration Delete + ChatService Refactor) ────────┤
  │  depends on: Phase 1 (web controllers deleted)            │
  │  critical path: ChatService refactoring                   │
  └───────────────────────────────────────────────────────────┤
                                                               │
Phase 3 (Auth Cleanup) ──────────────────────────────────────┤
  │  independent — read-only verification in current state    │
  └───────────────────────────────────────────────────────────┤
                                                               │
Phase 4 (Extract & Generalize) ──────────────────────────────┤
  │  depends on: Phases 1, 2, 3 all complete                  │
  │  sub-packages parallelizable: turn, tool, memory, queue   │
  └───────────────────────────────────────────────────────────┤
                                                               │
Phase 5 (Facade API) ────────────────────────────────────────┤
  │  depends on: Phase 4 complete                             │
  └───────────────────────────────────────────────────────────┤
                                                               │
Phase 6 (Tests, Config, Docs) ───────────────────────────────┤
  │  depends on: Phase 5 complete                             │
  └───────────────────────────────────────────────────────────┘
```

**Parallelization summary:**
- Phase 1 + Phase 2 mechanical deletions: **parallel**
- Phase 3: **parallel** with both above
- Phase 2 ChatService refactoring: **serial**, must follow deletions
- Phase 4 sub-packages: **parallel** with each other, must follow Phase 2
- Phase 5: **serial**, must follow Phase 4
- Phase 6: **parallel** across test/docs writers, must follow Phase 5

---

## Phase Summary Table

| Phase | Files Deleted | Files Adapted | Files Created | Estimated Risk | Validation |
|---|---|---|---|---|---|
| 1. Web Delete | ~54 | 0 | 0 | Low | Compile, test, startup |
| 2. Orchestration Delete | ~230 | ~15 (ChatService, RequestResolver, etc.) | 0 | **High** — ChatService refactoring is complex | Compile, test, startup, smoke chat |
| 3. Auth Cleanup | 0-2 | 0 | 0 | Very Low | Compile, test, grep verification |
| 4. Extract & Generalize | 0 (remaining old code deleted) | ~40 (all surviving code moves to new packages) | ~10 (new interfaces: CompletionDetector, RepairStrategy, TurnEnricher, etc.) | Medium | Compile, test, startup, smoke chat |
| 5. Facade API | 0 | 0 | ~5 (Core, CoreBuilder, CoreException, CoreAutoConfiguration) | Low | Compile, facade integration test |
| 6. Tests & Docs | 0 | 0 | ~30 test files, ~12 doc files | Medium | Full build, coverage report, doc review |

## Acceptance Criteria for the Complete Plan

1. **Build**: `mvn clean verify` passes with zero failures
2. **Context starts**: `timeout 30s mvn spring-boot:run` reaches a stable running state
3. **Smoke test**: The `Core` facade can send a message to a configured model and receive a response (blocking + streaming)
4. **No dead code**: Zero references to `io.mindspice.magenta2` remain in production source
5. **No app-specific concepts**: Zero references to plans, tasks, projects, workspaces, agents, avatars, dashboards, skills, system-chat, orchestration, workflows
6. **Clean pom.xml**: Only framework-necessary dependencies (Spring Boot starter, Spring AI, SQLite, Jackson, CommonMark, OWASP sanitizer, Jsoup, Actuator)
7. **Comprehensive tests**: >80% line coverage on `turn/`, `tool/`, `memory/`, `queue/`, `interaction/`, `core/` packages
8. **Documentation**: README + 11 docs files covering all framework capabilities
