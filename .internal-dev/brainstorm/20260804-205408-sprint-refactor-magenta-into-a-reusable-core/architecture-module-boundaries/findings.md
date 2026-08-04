
## Prompt

Refactor Magenta (`/home/dhickel/Code/Java/Magenta`, branch `core`) into a reusable core LLM turn-loop framework. Strip all application shell. Keep core turn loop, question-user loop, message queueing, basic example tools, pluggable chat memory, facade API. Remove web UI, orchestration, tasks/plans, projects, workspaces, auth. Generalize naming. Propose artifact/package naming, pom shape, facade API, module layout, tool framework API, memory/persistence abstraction, configuration surface, phased implementation plan, validation strategy. Ground every proposal in actual code inspection.

## Source

- **Repository**: `/home/dhickel/Code/Java/Magenta`, 387 Java source files under `io.mindspice.magenta2`
- **Build**: Spring Boot 4.0.7, Java 25, Spring AI 2.0.0, SQLite, SimplyPages, Ollama + OpenAI + DeepSeek
- **Specifications**: `.internal-dev/specifications/architecture.md`, `services.md`, `service-graph.md`, `api.md`
- **Core files examined**: `ChatService.java` (3009 lines), `ChatModelRouter.java`, `ContextManagementAdvisor.java`, `ChatToolRegistry.java`, `ToolTranscriptService.java`, `ToolArgumentCoercionModule.java`, `ToolLoopGuard.java`, `ChatPendingMessageService.java`, `ChatMemoryRepository.java`, `RepositoryBackedChatMemory.java`, `ChatSessionMetadataRepository.java`, `ConversationTurnCoordinator.java`, `MagentaWorkExecutor.java`, `ActiveTurnRegistry.java`, `AiConfig.java`, `ModelConfig.java`, `PromptContextAssembler.java`, `ToolAccessPolicy.java`, `TurnContext.java`, `TerminalTurnRepair.java`, `TurnAuditWriter.java`, `RequestResolver.java`, `ResolvedChatRequest.java`, `AuditService.java`, `ContextUsageTracker.java`, `ChatStreamEvent.java`, `InteractionQuestionTools.java`, all tool implementations under `ai/chat/tool/*/`, `ChatBeanConfig.java`, `MagentaRootConfiguration.java`, `AiUserConfigConfiguration.java`, `Magenta2Application.java`, all `api/web/**`

## Findings

### 1. The Core Turn Loop Machinery (What Must Be Kept)

**`ChatService`** is a 3009-line monolithic service that mixes four distinct concerns:
- A **turn-loop state machine** (`toolChat` method, ~1200 lines): INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE. This is the framework gold.
- **Plan/task orchestration**: `beginPlan`, `executeSavedPlan`, `submitPlanAnswer`, `executeTaskBlocking`, `streamTaskExecution`, `executePlanFromDefinition`. All app-specific.
- **Session management**: `listSessions`, `renameConversation`, `setConversationFavorite`, `setConversationArchived`, `clearConversation`. Framework-compatible but needs generalization.
- **Rendering/utility**: `renderAssistantMessage`, `conversationTitle`, `history`.

**`ChatModelRouter`** (~220 lines) is nearly pure framework. It resolves model aliases to `ChatModel` instances, caches them, and builds endpoint-specific options (Ollama, OpenAI, DeepSeek). Its only app coupling is the `defaultRemoteModelName()` method that falls back through `RuntimeSettingsService` → agent config → default model. This is trivially adaptable.

**`ToolLoopGuard`** (85 lines) is pure framework. Tracks identical repeated tool calls and error rates, throws `ToolUseAbort`. Zero app dependencies.

**`ChatToolRegistry`** (70 lines) is pure framework. Discovers all `ToolCallback`/`ToolCallbackProvider` beans, indexes by name, resolves approved-tool lists with wildcard support. No app dependencies.

**`ToolTranscriptService`** (300 lines) is pure framework. Wraps tool results in structured transcripts with full/summary prefixes, summaries, truncation, history rendering. Only app coupling is the tool-name-specific summarizers (`fileListSummary`, `shellSummary`, etc.) that can be generalized with a registry pattern.

**`ToolArgumentCoercionModule`** (100 lines) is pure framework. Jackson module that coerces single values into collections for tool arguments. No app dependencies.

**`ContextManagementAdvisor`** (~800 lines) is framework quality but has app couplings: uses `AgentConfig.systemPrompt()` for default prompts, `RuntimeSettingsService` for compaction models. The compaction, token estimation, and context maintenance logic is framework.

**`ConversationTurnCoordinator`** (100 lines) is pure framework. Per-conversation serialization of turns via a deque with `MagentaWorkExecutor` submission. No app dependencies.

**`MagentaWorkExecutor`** (180 lines) is pure framework. Priority-bounded thread-pool lanes for chat turns, delegation, and background jobs. The lane configuration (`CHAT_TURN`, `DELEGATION`, `BACKGROUND_JOB`) is framework-level but the specific lanes can be simplified.

**`ActiveTurnRegistry`** (120 lines) is framework-quality. Registers active turns with secure tokens, interrupt polling, phase tracking. Its `PlanExecutionConflictException` is app-specific but the concept ports cleanly.

**`ChatPendingMessageService`** + **`ChatPendingMessageRepository`** are framework-quality. FIFO enqueue/claim/ack/release pattern for mid-turn message queueing. Needs minor generalization (remove `ChatSessionSurface` from enqueue signature).

**`InteractionQuestionTools`** (100 lines) is framework-quality but has app couplings: ties to `PlanService.askQuestions()` and `TaskService.askTaskQuestions()`. The ask-questions concept generalizes to a framework question-user contract.

**`ChatMemoryRepository`** (170 lines) + **`RepositoryBackedChatMemory`** (35 lines) implement Spring AI's `ChatMemory`/`ChatMemoryRepository` with SQLite. Pure framework. Self-migrating schema.

### 2. What ChatService Transitive Dependencies Are

`ChatService` transitively depends on:

| Dependency | Framework or App-Shell? |
|---|---|
| `ChatMemory` / `ChatMemoryRepository` / `RepositoryBackedChatMemory` | **Framework** — keep |
| `ChatSessionMetadataRepository` | **Mixed** — keep metadata concept, shed origin/surface/project columns |
| `ChatMarkdownRenderer` | **Framework** — keep |
| `AiConfig` / `ModelConfig` / `AgentConfig` | **Mixed** — keep model config, shed agent config |
| `ContextManagementAdvisor` / `ContextUsageTracker` | **Framework** — keep |
| `ChatModelRouter` | **Framework** — keep |
| `ToolCallingManager` (Spring AI) | **Framework** — keep as Spring AI dependency |
| `ChatToolRegistry` | **Framework** — keep |
| `ToolTranscriptService` | **Framework** — keep |
| `PlanService` / `TaskService` / `AgentJobService` | **App-Shell** — delete |
| `ConversationTurnCoordinator` / `MagentaWorkExecutor` | **Framework** — keep |
| `AuditRepository` / `AuditService` | **Framework** — keep |
| `ObjectMapper` | **Framework** — keep |
| `RuntimeSettingsService` | **Mixed** — keep settings concept but shed Magenta-specific fields |
| `RequestResolver` | **Mixed** — keep resolution logic, shed agent/origin/surface |
| `ChatFileService` | **App-Shell** — delete (filesystem-specific) |
| `ChatPendingMessageService` | **Framework** — keep |
| `AgentSkillActivationService` / `AgentSkillRuntimeCatalogService` / `AgentSkillPromptCatalogAssembler` | **App-Shell** — delete |
| `RunMeasurementRepository` | **App-Shell** — delete |
| `AgentContextService` / `WorkspaceDirectoryService` | **App-Shell** — delete |
| `WorkTypeProfileService` / `AgentsMdResolver` | **App-Shell** — delete |

**Critical finding**: `ChatService` is the **single biggest refactoring task**. It must be split into a clean `TurnEngine` (framework) with all plan/task/agent/project/workspace/skill code stripped out. The turn-loop state machine (INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE) is well-implemented and well-guarded — this is the framework's core.

### 3. Package-by-Package Classification

#### `io.mindspice.magenta2` (root, 1 file)

| File | Disposition | Notes |
|---|---|---|
| `Magenta2Application.java` | **DELETE** | Spring Boot main class. Framework uses auto-configuration, no main class needed. Keep a minimal `@SpringBootApplication` in the example app only. |

#### `ai/chat/config/` (1 file)

| File | Disposition | Notes |
|---|---|---|
| `ChatBeanConfig.java` | **ADAPT** | Bean definitions for `ChatMemory`, `TokenCountEstimator`, `ContextManagementAdvisor`. Moves into framework autoconfiguration. |

#### `ai/chat/model/` (21 files)

| File | Disposition | Notes |
|---|---|---|
| `ChatMessage.java` | **KEEP** | Core message type for framework consumers. Fields: role, text, renderedHtml, thinkingHtml. Generalize name to `TurnMessage`. |
| `ChatStreamEvent.java` | **KEEP** | Sealed interface hierarchy: Start, Chunk, Tool, SystemNotice, Interrupt, Context, Done, Error, OutputsChanged. Framework contract. Remove `OutputsChanged` (app-specific). |
| `ChatToolActivity.java` | **KEEP** | Tool result display data. Framework. |
| `ChatRequest.java` | **ADAPT** | Sealed interface with MsgRequest. Simplify to framework-level fields only. |
| `ChatResponse.java` | **ADAPT** | MsgResponse. Simplify. |
| `ChatPlanState.java` | **DELETE** | App-specific plan state. |
| `PlanMode.java` | **ADAPT** | Generalize to `InteractionMode` (NORMAL, PLANNING, EXECUTING). The concept of different turn modes is framework-useful. |
| `ChatSession.java` | **ADAPT** | Keep session concept but shed: `origin`, `agentId`, `fileCount`, `titleJobStatus`. Keep: `conversationId`, `title`, `favorite`, `archived`, `updatedAt`. |
| `ChatSessionOrigin.java` | **DELETE** | App-specific origin tracking (CHAT, AGENT_CHAT, SYSTEM_CHAT, PROJECT_CHAT). |
| `ChatSessionSurface.java` | **DELETE** | App-specific (BROWSER, AVATAR). |
| `ChatSessions.java` | **DELETE** | List wrapper. Not needed. |
| `ChatHistory.java` | **ADAPT** | Simplify to generic conversation history. |
| `ChatFileListing.java` | **DELETE** | App-specific workspace file listing. |
| `ChatFileSummary.java` | **DELETE** | App-specific. |
| `ContextUsage.java` | **KEEP** | Token usage record. Framework. |
| `PendingChatMessage.java` | **KEEP** | Queued message record. Framework. |
| `ClaimedPendingChatMessage.java` | **KEEP** | Claimed message with token. Framework. |
| `PendingMessageAckRequest.java` | **KEEP** | Ack request DTO. Framework. |
| `PendingMessageRequest.java` | **KEEP** | Pending message request DTO. Framework. |

#### `ai/chat/plan/` (22 files)

| All | **DELETE** | PlanService, PlanDefinition, PlanRepository, PlanRun, PlanStep, PlanSection, PlanPrompt, PlanAnswer, PlanChatMessage, PlanChatRepository, PlanCompletionService, PlanCompletionValidator, ChatModelPlanCompletionValidator, PlanFieldDefinition, PlanFieldType, PlanKind, PlanStatus, PlanRunStatus, PlanToolContext, PlanToolExecutionContext, SavedPlanChatService, SavedPlanModelClient, SavedPlanPlanningModelClient, WorkTypeProfile, WorkTypeProfileService. Entirely Magenta app-shell. |

#### `ai/chat/project/` (6 files)

| All | **DELETE** | ProjectChatService, ProjectChatProfile, ProjectChatProfileHolder, ProjectChatProfilePreparationService, ProjectChatBoundedReadService, ProjectChatToolRegistry. |

#### `ai/chat/system/` (6 files)

| All | **DELETE** | SystemChatService, SystemChatBoundedReadService, SystemChatProfileHolder, SystemChatProfilePreparationService, SystemChatActionProposalService, SystemChatToolRegistry. |

#### `ai/chat/task/` (9 files)

| All | **DELETE** | TaskService, TaskDefinition, TaskDraft, TaskDraftStatus, TaskFieldDefinition, TaskRun, TaskRunStatus, TaskStep, TaskValueType. |

#### `ai/chat/rendering/` (1 file)

| File | Disposition | Notes |
|---|---|---|
| `ChatMarkdownRenderer.java` | **KEEP** | CommonMark rendering with GFM tables, HTML sanitization. Framework-quality. |

#### `ai/chat/repository/` (5 files)

| File | Disposition | Notes |
|---|---|---|
| `ChatMemoryRepository.java` | **KEEP** | SQLite-backed implementation of `ChatMemoryRepository`. Framework. |
| `RepositoryBackedChatMemory.java` | **KEEP** | Adapter from `ChatMemoryRepository` to `ChatMemory`. Framework. |
| `ChatSessionMetadataRepository.java` | **ADAPT** | Remove app-specific columns: `origin`, `surface`, `agent_id`, `project_id`, `member_agent_id`, `materialization_key`, `materialization_generation`, `owner_binding_key`, `active_task_run_id`, `planning_model`. Keep: `conversation_id`, `model`, `title`, `favorite`, `archived`, `updated_at`. |
| `ChatPendingMessageRepository.java` | **KEEP** | FIFO queue with claim/ack/release. Pure framework. |
| `AuditRepository.java` | **KEEP** | Audit logging. Framework-quality but currently SQLite-specific. Abstract behind interface. |

#### `ai/chat/service/` (17 files)

| File | Disposition | Notes |
|---|---|---|
| `ChatService.java` | **ADAPT** — Major refactor | 3009 lines. Split into: `TurnEngine` (state machine), `ConversationService` (session management). Strip all plan/task/agent/project/workspace code. This is the single biggest task. |
| `ChatModelRouter.java` | **KEEP** | Model resolution and caching. Generalize `defaultRemoteModelName()` to not depend on agent config. |
| `ContextManagementAdvisor.java` | **KEEP** | Context compaction and management. Remove agent-specific prompt defaults. |
| `ContextUsageTracker.java` | **KEEP** | In-memory usage tracker. Framework. |
| `ChatPendingMessageService.java` | **KEEP** | Pending message queue service. Framework. |
| `ToolLoopGuard.java` | **KEEP** | Pure framework. |
| `ToolUseAbort.java` | **KEEP** | Exception type. Framework. |
| `RequestResolver.java` | **ADAPT** | Remove agent-scoped resolution, context capture/binding, origin/surface tracking, plan/task mode overrides. Keep model resolution and conversation lifecycle. |
| `ResolvedChatRequest.java` | **KEEP** | Resolution result record. Framework. |
| `AuditService.java` | **ADAPT** | Remove AgentJobService dependency (title jobs). Keep audit logging. |
| `ChatFileService.java` | **DELETE** | Filesystem-specific. |
| `AgentChatPromptService.java` | **DELETE** | Agent-specific prompt assembly. Replaced by generalized prompt pipeline. |
| `ChatInvocationProfile.java` | **DELETE** | App-specific. |
| `StoredContextUsage.java` | **KEEP** | Framework record. |
| `TaskExecutionEvent.java` | **DELETE** | App-specific. |
| `TaskExecutionResult.java` | **DELETE** | App-specific. |

#### `ai/chat/service/turn/` (8 files)

| File | Disposition | Notes |
|---|---|---|
| `TurnContext.java` | **KEEP** | Mutable turn carrier. Framework. Remove plan-specific fields (`planCompletionDetected`, `forcedPlanningQuestion`, `validatedFinalMessage`). |
| `TurnPhase.java` | **KEEP** | Enum: PREPARE, INVOKE_MODEL, EVALUATE, EXECUTE_TOOLS, REPAIR, FINALIZE, DONE. Framework. |
| `TurnDiagnostic.java` | **KEEP** | Logging helper. Framework. |
| `TurnAuditWriter.java` | **KEEP** | Audit recording. Framework. |
| `PromptContextAssembler.java` | **ADAPT** — Major | Currently assembles Magenta's fixed 6-layer prompt. Generalize to a pluggable prompt pipeline: `PromptContributor` interface for embedding apps to add their own prompt segments. |
| `ToolAccessPolicy.java` | **ADAPT** | Currently uses Magenta plan/task mode constants. Generalize to `InteractionMode`-based filtering with configurable blocked-tool lists. |
| `TerminalTurnRepair.java` | **ADAPT** | Currently delegates to `PlanService`/`TaskService` for completion checks. Generalize to pluggable completion validators. |
| `TrustedContextPolicyAssembler.java` | **DELETE** | Entirely Magenta-specific trusted context concept. |

#### `ai/chat/tool/` (4 files — core tool framework)

| File | Disposition | Notes |
|---|---|---|
| `ChatToolRegistry.java` | **KEEP** | Rename to `ToolRegistry`. Framework. |
| `ToolTranscriptService.java` | **ADAPT** | Generalize tool-name-specific summarizers. Extract summarizer registry. |
| `ToolArgumentCoercionModule.java` | **KEEP** | Framework. Move to `tool/coercion/`. |
| `InteractionQuestionTools.java` | **ADAPT** | Generalize from plan/task-specific questions to a generic question-user contract. Keep the `@Tool` annotation pattern as example. |

#### `ai/chat/tool/file/` (3 files)

| All | **DELETE** | AgentFileTools, AgentFileToolService, AgentFileToolConfiguration. App-specific filesystem tools. Keep one trivial file-read tool as framework example. |

#### `ai/chat/tool/orchestration/` (5 files)

| All | **DELETE** | AgentOperationalTools, AgentOperationalToolService, AgentOperationalToolConfiguration, AgentOperationalToolResponses, AgentToolAuthorizationService. App-shell. |

#### `ai/chat/tool/plan/` (2 files)

| All | **DELETE** | PlanSaveTools, PlanSaveToolConfiguration. App-specific. |

#### `ai/chat/tool/savedplan/` (2 files)

| All | **DELETE** | SavedPlanTools, SavedPlanToolConfiguration. App-specific. |

#### `ai/chat/tool/shell/` (3 files)

| All | **DELETE** | AgentShellTools, AgentShellToolService, AgentShellToolConfiguration. App-specific. |

#### `ai/chat/tool/skills/` (2 files)

| All | **DELETE** | AgentSkillActivationTools, AgentSkillToolConfiguration. App-specific. |

#### `ai/chat/tool/task/` (2 files)

| All | **DELETE** | TaskTools, TaskToolConfiguration. App-specific. |

#### `ai/chat/tool/web/` (3 files)

| All | **DELETE** | AgentWebTools, AgentWebToolService, AgentWebToolConfiguration. App-specific. |

#### `ai/config/user/` (7 files)

| File | Disposition | Notes |
|---|---|---|
| `AiConfig.java` | **ADAPT** | Remove `agents`, `defaultAgent`, `webSearch`, `unsafeAllowWildcardShellCommands`, `planningModel`, `compactionModel`. Keep: `models`, `defaultModel`, `summaryModel`, `contextBufferPercent`, `dataRoot`. |
| `ModelConfig.java` | **KEEP** | Pure framework. `remoteModelName`, `remoteEndpoint`, `endpointType`, `contextLength`, `thinkLevel`, `apiKey`. |
| `EndpointType.java` | **KEEP** | OLLAMA, OPENAI_COMPATIBLE, DEEPSEEK. Framework. |
| `AgentConfig.java` | **DELETE** | App-specific agent configuration. |
| `WebSearchConfig.java` | **DELETE** | App-specific. |
| `AiUserConfigConfiguration.java` | **ADAPT** | Remove agent references. Keep model loading and data-root resolution. |
| `ExternalAiConfigLoader.java` | **KEEP** | JSON/YAML config loader. Framework. |

#### `ai/execution/` (8 files)

| File | Disposition | Notes |
|---|---|---|
| `ConversationTurnCoordinator.java` | **KEEP** | Per-conversation turn serialization. Framework. |
| `MagentaWorkExecutor.java` | **KEEP** | Priority thread-pool lanes. Framework. Simplify lane configuration. |
| `MagentaWorkKind.java` | **ADAPT** | Reduce to `TURN` and `BACKGROUND`. |
| `MagentaWorkRequest.java` | **KEEP** | Framework. |
| `ActiveTurnRegistry.java` | **KEEP** | Turn registration with interrupt support. Framework. |
| `ActiveTurnPhase.java` | **KEEP** | Enum: MODEL_CALL, TOOL_CALL, TOOL_CHECKPOINT, COMPLETING. Framework. |
| `InterruptResult.java` | **KEEP** | Framework. |
| `InterruptStatus.java` | **KEEP** | Framework. |

#### `ai/orchestration/` — ALL 80+ FILES

| All | **DELETE** | agents/, context/, runtime/, settings/, tasks/, workflow/, workspaces/. Entirely app-shell. Orchestration, projects, jobs, schedules, reactions, workflows, task lifecycle, workspace management, materialization, context binding, output artifacts, skill management, agent profiles — all belong to the Magenta application, not the turn-loop framework. |

#### `ai/skills/` — ALL 30+ FILES

| All | **DELETE** | AgentSkill, skill discovery, trust, activation, snapshots, catalog. App-shell. |

#### `ai/agent/job/` (6 files)

| All | **DELETE** | AgentJob, AgentJobConfig, AgentJobRepository, AgentJobService, AgentJobStatus, AgentJobType. App-shell. |

#### `api/web/` — ALL 47+ FILES

| All | **DELETE** | Every controller, fragment, renderer, stream support, selector, and component. The entire web/SSE transport layer. Framework exposes no web endpoints — embedding apps add their own. |

#### `core/config/` (3 files)

| File | Disposition | Notes |
|---|---|---|
| `MagentaRootConfiguration.java` | **KEEP** | Clock bean, SQLite parent-directory initialization. Framework infrastructure. |
| `MagentaRootProperties.java` | **KEEP** | Data root property. Framework. |
| `ClassicJacksonConfiguration.java` | **KEEP** | Jackson configuration. Framework. |

#### `core/persistence/` (2 files)

| File | Disposition | Notes |
|---|---|---|
| `SchemaMigrationConfiguration.java` | **DELETE** | Magenta's ordered migration system. Framework uses simpler self-migrating repositories. |
| `SchemaMigrationRunner.java` | **DELETE** | App-specific. |

#### `core/util/` (1 file)

| File | Disposition | Notes |
|---|---|---|
| `PlainPathSegmentValidator.java` | **DELETE** | Workspace-path validation. App-specific. |

### 4. Proposed New Artifact Identity

| Aspect | Value |
|---|---|
| **Group ID** | `io.mindspice` |
| **Artifact ID** | `turnloop-core` |
| **Root package** | `io.mindspice.turnloop` |
| **Java version** | 25 (same as current) |
| **Spring Boot** | 4.0.x (same as current) |
| **Spring AI** | 2.0.0 (same as current) |

**Rationale**: "Turn loop" is the accurate description of what the framework does — a chat turn is: receive user message → call model → resolve tool calls → continue until terminal response. `turnloop-core` is unambiguous, distinct from `magenta2`, and not tied to any agent/chat-specific naming.

### 5. Proposed pom.xml Dependency Shape

#### Keep (Framework Dependencies)
```xml
<!-- Spring Boot foundation (no web, no security, no actuator) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- Spring AI model providers -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-deepseek</artifactId>
</dependency>

<!-- SQLite -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Markdown rendering -->
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
</dependency>
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark-ext-gfm-tables</artifactId>
</dependency>

<!-- HTML sanitization for markdown output -->
<dependency>
    <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
    <artifactId>owasp-java-html-sanitizer</artifactId>
</dependency>

<!-- JSON/YAML config loading -->
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>

<!-- Auto-configuration metadata -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

#### Drop (App-Shell Dependencies)
```xml
<!-- DELETE: Web layer -->
spring-boot-starter-web
spring-boot-starter-webmvc-test

<!-- DELETE: UI -->
htmx.org webjar
webjars-locator-core
simplypages

<!-- DELETE: Operations -->
spring-boot-starter-actuator

<!-- DELETE: HTML parsing (was for web fetch tool) -->
jsoup

<!-- DELETE: JNA (was for filesystem operations) -->
jna
```

### 6. Proposed Internal Module/Package Layout

```
io.mindspice.turnloop
├── TurnLoop.java                     // Public facade interface
├── TurnLoopBuilder.java              // Builder for facade configuration
│
├── facade/                           // Public API surface
│   ├── TurnLoop.java                 // Main entry: send, stream, answer, register tools
│   ├── TurnRequest.java              // Input DTO
│   ├── TurnResponse.java             // Output DTO
│   ├── TurnEvent.java                // Sealed event hierarchy (was ChatStreamEvent)
│   ├── TurnMessage.java              // Rendered message (was ChatMessage)
│   ├── ConversationSession.java      // Session metadata (was ChatSession, simplified)
│   ├── ContextUsage.java             // Token usage
│   ├── InteractionMode.java          // NORMAL, PLANNING, EXECUTING
│   ├── PendingMessage.java           // Queued message (was PendingChatMessage)
│   ├── QuestionRequest.java          // Question-user contract
│   └── TurnLoopException.java        // Framework exceptions
│
├── engine/                           // Turn execution
│   ├── TurnEngine.java               // State machine (extracted from ChatService.toolChat)
│   ├── TurnContext.java              // Mutable turn carrier
│   ├── TurnPhase.java                // Enum: PREPARE, INVOKE_MODEL, EVALUATE, EXECUTE_TOOLS, REPAIR, FINALIZE, DONE
│   ├── TurnDiagnostic.java           // Logging helper
│   ├── ToolLoopGuard.java            // Runaway tool detection
│   ├── ToolUseAbort.java             // Guard exception
│   ├── TerminalTurnRepair.java       // Completion repair
│   ├── TurnAuditWriter.java          // Audit recording
│   └── TurnCoordinator.java          // Serialization (was ConversationTurnCoordinator)
│
├── model/                            // Model routing
│   ├── ModelRouter.java              // Model resolution + caching (was ChatModelRouter)
│   ├── ModelConfig.java              // Per-model config
│   ├── EndpointType.java             // OLLAMA, OPENAI_COMPATIBLE, DEEPSEEK
│   ├── AiConfig.java                 // Top-level AI config (models only, no agents)
│   └── AiConfigLoader.java           // Config loading (was ExternalAiConfigLoader)
│
├── tool/                             // Tool framework
│   ├── ToolRegistry.java             // Tool discovery + resolution (was ChatToolRegistry)
│   ├── ToolTranscriptService.java    // Structured tool result transcripts
│   ├── ToolAccessPolicy.java         // Mode-based tool filtering
│   ├── ToolArgumentCoercionModule.java // Jackson coercion
│   └── QuestionUserContract.java     // Interface for question-user tools
│
├── memory/                           // Conversation memory
│   ├── ConversationMemory.java       // Interface (extends Spring AI ChatMemory)
│   ├── SqliteMemoryRepository.java   // SQLite-backed (was ChatMemoryRepository)
│   ├── RepositoryBackedMemory.java   // Adapter (was RepositoryBackedChatMemory)
│   ├── SessionMetadataRepository.java // Simplified session metadata
│   ├── ContextManager.java           // Compaction + token management (was ContextManagementAdvisor)
│   └── ContextUsageTracker.java      // In-memory usage tracker
│
├── event/                            // Event streaming
│   ├── TurnEventPublisher.java       // Event emission contract
│   ├── TurnEvent.java                // Sealed types (Start, Chunk, Tool, Done, Error, etc.)
│   └── StoredContextUsage.java       // Context maintenance result
│
├── queue/                            // Message queueing
│   ├── PendingMessageService.java    // FIFO queue service
│   ├── PendingMessageRepository.java // Queue persistence
│   └── PendingMessage.java           // Queue entry (was PendingChatMessage)
│
├── executor/                         // Work execution
│   ├── WorkExecutor.java             // Priority thread pools (was MagentaWorkExecutor)
│   ├── WorkRequest.java              // Work submission (was MagentaWorkRequest)
│   ├── WorkKind.java                 // TURN, BACKGROUND
│   ├── ActiveTurnRegistry.java       // Turn registration + interrupts
│   └── ActiveTurnPhase.java          // MODEL_CALL, TOOL_CALL, TOOL_CHECKPOINT, COMPLETING
│
├── prompt/                           // Prompt assembly pipeline
│   ├── PromptAssembler.java          // Pluggable prompt composition
│   ├── PromptContributor.java        // Interface for embedding apps to add prompt segments
│   └── SystemPromptResolver.java     // Default system prompt resolution
│
├── audit/                            // Audit logging
│   ├── AuditService.java             // Audit recording
│   └── AuditRepository.java          // Audit persistence interface
│
├── config/                           // Auto-configuration
│   ├── TurnLoopProperties.java       // @ConfigurationProperties
│   ├── TurnLoopAutoConfiguration.java // @AutoConfiguration
│   └── JacksonConfiguration.java     // Jackson setup
│
└── examples/                         // Example tools (documentation, not production)
    ├── GetTimeTool.java              // Simple time tool
    ├── EchoTool.java                 // Simple echo tool
    └── ExampleQuestionTool.java      // Question-user example
```

### 7. Proposed Public Facade API

```java
public interface TurnLoop {

    // ── Core chat ──
    TurnResponse send(TurnRequest request);
    Flux<TurnEvent> stream(TurnRequest request);

    // ── Question-user ──
    TurnResponse answerQuestion(String conversationId, String answer, String questionId);

    // ── Conversation management ──
    List<ConversationSession> listSessions();
    ConversationSession renameConversation(String conversationId, String title);
    void clearConversation(String conversationId);
    List<TurnMessage> history(String conversationId);

    // ── Pending messages (queue) ──
    PendingMessage enqueuePending(String conversationId, String message);
    List<PendingMessage> listPending(String conversationId);
    Optional<ClaimedPendingMessage> claimPending(String conversationId);
    boolean ackPending(String conversationId, String messageId, String claimToken);

    // ── Tool management ──
    void registerTools(List<ToolCallback> tools);
    void registerToolProvider(ToolCallbackProvider provider);

    // ── Lifecycle ──
    void close();
}

// Builder
public class TurnLoopBuilder {
    public TurnLoopBuilder models(AiConfig config);
    public TurnLoopBuilder memoryRepository(MemoryRepository repository);
    public TurnLoopBuilder sessionMetadataRepository(SessionMetadataRepository repository);
    public TurnLoopBuilder promptContributor(PromptContributor contributor);
    public TurnLoopBuilder toolAccessPolicy(ToolAccessPolicy policy);
    public TurnLoopBuilder completionValidator(CompletionValidator validator);
    public TurnLoopBuilder auditRepository(AuditRepository repository);
    public TurnLoopBuilder executorSettings(int turnThreads, int turnQueue);
    public TurnLoop build();
}

// Request/Response
public record TurnRequest(
    String conversationId,  // null for new conversation
    String message,
    String model,           // null for default
    InteractionMode mode    // NORMAL by default
) {}

public record TurnResponse(
    String conversationId,
    String model,
    String response,
    ContextUsage contextUsage
) {}

// Sealed event hierarchy (was ChatStreamEvent)
public sealed interface TurnEvent {
    record Start(String conversationId, String model, String turnId, String interruptToken) implements TurnEvent {}
    record Chunk(String text, String renderedHtml, String thinkingHtml, ContextUsage contextUsage) implements TurnEvent {}
    record ToolActivity(ChatToolActivity activity, ContextUsage contextUsage) implements TurnEvent {}
    record SystemNotice(String text, String renderedHtml, ContextUsage contextUsage) implements TurnEvent {}
    record Interrupt(String text, ContextUsage contextUsage) implements TurnEvent {}
    record Done(String conversationId, String model, String text, String renderedHtml, ContextUsage contextUsage) implements TurnEvent {}
    record Error(String message) implements TurnEvent {}
}
```

### 8. Configuration Surface (application.properties)

```properties
# ── Model Configuration ──
turnloop.ai.config-path=./config/ai-config.json

# ── Data ──
turnloop.data.root=./data

# ── SQLite ──
spring.datasource.url=jdbc:sqlite:${turnloop.data.root}/turnloop.db

# ── Context Management ──
turnloop.context.buffer-percent=10
turnloop.context.compaction-model=local-gemma-26b  # model alias for compaction summaries

# ── Executor ──
turnloop.executor.turn-threads=8
turnloop.executor.turn-queue=200
turnloop.executor.background-threads=2
turnloop.executor.background-queue=100

# ── Model Timeouts ──
turnloop.ai.openai-compatible-read-timeout-seconds=360

# ── Prompt ──
turnloop.prompt.default-system-prompt=You are a helpful assistant.
```

### 9. Memory/Persistence Abstraction

The framework keeps SQLite as the default implementation but exposes interfaces:

```java
// Extends Spring AI's ChatMemoryRepository
public interface MemoryRepository extends ChatMemoryRepository {
    // Framework-specific additions:
    List<String> findConversationIds();
    boolean existsByConversationId(String conversationId);
}

public interface SessionMetadataRepository {
    Optional<String> findModel(String conversationId);
    void saveModel(String conversationId, String model);
    Optional<String> findTitle(String conversationId);
    void saveTitle(String conversationId, String title);
    boolean isFavorite(String conversationId);
    void setFavorite(String conversationId, boolean favorite);
    boolean isArchived(String conversationId);
    void setArchived(String conversationId, boolean archived);
    Optional<String> findUpdatedAt(String conversationId);
    List<String> findConversationIds();
    void deleteByConversationId(String conversationId);
}

public interface PendingMessageRepository {
    PendingMessage enqueue(String conversationId, String message);
    List<PendingMessage> findVisibleByConversationId(String conversationId);
    Optional<ClaimedPendingMessage> claimOldest(String conversationId);
    boolean ack(String conversationId, String messageId, String claimToken);
    boolean release(String conversationId, String messageId, String claimToken);
    void deleteByConversationId(String conversationId);
}

public interface AuditRepository {
    void recordUserMessage(String conversationId, String message, String model);
    void recordAssistantMessage(String conversationId, String text, String metadataJson, String model);
    void recordContext(String conversationId, ContextUsage usage, int messageCount, String model);
    void recordError(String conversationId, String errorType, String errorMessage, String stackTrace, String model);
    void recordToolExecution(ToolTranscriptEntry entry, String conversationId, String model);
}
```

### 10. Generalized Tool Framework API

```java
// Tool registration — either via Spring bean discovery or programmatic
public interface ToolRegistry {
    List<ToolCallback> resolveTools(List<String> approvedNames);
    List<ToolCallback> resolveTools(List<String> approvedNames, List<String> allowedNames);
    void validateToolNames(List<String> approvedNames);
    void registerTool(ToolCallback tool);
}

// Tool access policy — mode-based filtering
public interface ToolAccessPolicy {
    List<ToolCallback> filterTools(List<ToolCallback> tools, InteractionMode mode);
    
    // Default built-in implementations:
    static ToolAccessPolicy allowAll() { ... }
    static ToolAccessPolicy blockList(List<String> blockedTools) { ... }
    static ToolAccessPolicy allowList(List<String> allowedTools) { ... }
    static ToolAccessPolicy modeAware(Map<InteractionMode, List<String>> blockedByMode) { ... }
}

// Question-user contract — for tools that need mid-turn user input
public interface QuestionUserContract {
    /** Called by tool implementations to suspend the turn for user input */
    QuestionResponse askQuestions(String conversationId, List<String> questions);
    
    /** Called by the engine to check if a question is pending */
    boolean hasPendingQuestion(String conversationId);
    
    /** Called by the engine to get the pending questions */
    List<String> getPendingQuestions(String conversationId);
    
    /** Called by the engine when user answers */
    void recordAnswer(String conversationId, String answer, String questionId);
}
```

### 11. Phased Implementation Plan Outline

#### Phase 0: Scaffold new module (1-2 days)
- Create new Maven module `turnloop-core` in the repository (or separate repo)
- Set up pom.xml with minimal dependencies
- Create empty package layout
- Verify empty build passes

#### Phase 1: Extract model/config layer (2-3 days)
- Port `ModelConfig`, `EndpointType`, `AiConfig` (simplified), `AiConfigLoader`
- Port `ChatModelRouter` → `ModelRouter` (remove agent fallback logic)
- Port `ChatBeanConfig` → `TurnLoopAutoConfiguration`
- Add `TurnLoopProperties` with `@ConfigurationProperties`
- **Gate**: `ApplicationContext` starts with only model layer wired

#### Phase 2: Extract memory/persistence layer (2-3 days)
- Port `ChatMemoryRepository` → `SqliteMemoryRepository`
- Port `RepositoryBackedChatMemory` → `RepositoryBackedMemory`
- Port `ChatSessionMetadataRepository` → `SqliteSessionMetadataRepository` (simplified)
- Port `ChatPendingMessageRepository` → `SqlitePendingMessageRepository`
- Port `AuditRepository` → `SqliteAuditRepository`
- Define interfaces for all repositories
- **Gate**: Repository integration tests pass with in-memory SQLite

#### Phase 3: Extract tool framework (2 days)
- Port `ChatToolRegistry` → `ToolRegistry`
- Port `ToolTranscriptService` → generalize summarizers
- Port `ToolArgumentCoercionModule`
- Port `ToolLoopGuard`, `ToolUseAbort`
- Port `ToolAccessPolicy` → generalize to interface with built-ins
- Define `QuestionUserContract`
- Create example tools (`GetTimeTool`, `EchoTool`)
- **Gate**: Tool registration, resolution, transcript, and guard tests pass

#### Phase 4: Extract execution layer (2 days)
- Port `MagentaWorkExecutor` → `WorkExecutor`
- Port `ConversationTurnCoordinator` → `TurnCoordinator`
- Port `ActiveTurnRegistry`
- Port `MagentaWorkRequest` → `WorkRequest`
- **Gate**: Executor submits work, coordinator serializes turns, interrupts work

#### Phase 5: Extract engine layer (4-5 days) — **Biggest phase**
- Extract turn-loop state machine from `ChatService.toolChat`
- Port `TurnContext`, `TurnPhase`, `TurnDiagnostic`
- Port `TerminalTurnRepair` → generalize completion validators
- Port `TurnAuditWriter`
- Port `ContextManagementAdvisor` → `ContextManager`
- Port `ContextUsageTracker`
- Port `ChatMarkdownRenderer` → `MarkdownRenderer`
- Wire `TurnEngine` with all dependencies
- Strip ALL plan/task/agent/project/workspace/skill code — replaced by generalized interfaces
- **Gate**: Engine processes a turn with model call + tool resolution + final response, no app code

#### Phase 6: Build facade (2 days)
- Implement `TurnLoop` interface (backed by `TurnEngine`)
- Implement `TurnLoopBuilder`
- Define public DTOs: `TurnRequest`, `TurnResponse`, `TurnEvent`, `TurnMessage`, `ConversationSession`, `InteractionMode`
- Wire pending message queue into facade
- **Gate**: Full `TurnLoop.send()` and `TurnLoop.stream()` work end-to-end

#### Phase 7: Question-user flow (1-2 days)
- Implement `QuestionUserContract` backed by `TurnEngine`
- Wire `answerQuestion` into facade
- Test: model asks question → turn suspends → user answers → turn resumes
- **Gate**: Question-user round-trip test passes

#### Phase 8: Queueing (1 day)
- Wire pending message enqueue/claim/ack/release through facade
- Test concurrent message queueing during active turn
- **Gate**: Queue tests pass

#### Phase 9: Cleanup, tests, docs (3-4 days)
- Comprehensive unit tests for engine, tool framework, memory, executor
- Integration tests for full turn loop with Ollama
- Auto-configuration tests (context startup)
- Javadoc on all public API
- `README.md` with embedding example
- Delete original Magenta app code (leave only `turnloop-core` on the `core` branch)

#### Phase 10: Validation gates
- `mvn clean verify` passes (compile, test, no app code)
- Auto-configuration smoke test: Spring context starts with only `turnloop-core` on classpath
- End-to-end test: send message → model responds → tools called → final response
- Question-user test: model asks question → turn suspends → answer provided → turn completes
- Queueing test: messages enqueued during active turn, drained after
- Concurrent turn serialization test: two messages for same conversation serialized
- Interrupt test: interrupt token interrupts active tool call

### 12. Validation Strategy

| Level | What | How |
|---|---|---|
| **Unit** | TurnEngine state machine | Mock ChatModel, ToolCallingManager. Verify state transitions for each phase path. |
| **Unit** | ToolRegistry resolution | Test wildcard, named, allowed-filter resolution. Test unknown tool rejection. |
| **Unit** | ToolLoopGuard | Test identical-call detection, error-rate detection, abort behavior. |
| **Unit** | ToolTranscriptService | Test full/summary entry creation, truncation, rendering. |
| **Unit** | ContextManager | Test compaction trigger, summary generation, message truncation. |
| **Unit** | PendingMessageService | Test FIFO ordering, claim/ack/release lifecycle, stale claim recovery. |
| **Unit** | QuestionUserContract | Test question ask/answer cycle, timeout behavior. |
| **Integration** | Full turn loop with Ollama | Real model call, tool call, response. Verify context usage tracking. |
| **Integration** | Question-user flow | Real model asks question, answer resumes turn. |
| **Integration** | Queue drain | Enqueue during active stream, verify drain after completion. |
| **Integration** | Auto-configuration | `ApplicationContextRunner` — context starts with framework only. |
| **Smoke** | Context startup | `timeout 30s mvn spring-boot:run` with example app. |

## Options

### Option A: Monolithic Refactor (Selected)
Keep the existing repository, create `turnloop-core` as a new Maven module, delete everything not in the framework, and refactor in-place on the `core` branch. This is the most practical approach since the code is already understood and the extraction is surgical.

### Option B: Fresh Repository
Create a completely new repository, port code file-by-file. Cleaner git history but harder to trace provenance and lose the organic evolution context.

### Option C: Keep Magenta as Parent
Keep the Magenta application module intact and extract `turnloop-core` as a new module that Magenta depends on. This preserves both codebases but creates a maintenance burden for Magenta.

**Recommendation**: Option A on the `core` branch. The framework is the primary artifact; the app shell is deleted entirely.

## Trade-offs

1. **SQLite as default vs. pluggable**: Keeping SQLite as the default memory store is pragmatic — it's already implemented and tested. The interface abstraction (MemoryRepository, SessionMetadataRepository, etc.) allows embedding apps to swap in Postgres or in-memory stores. Trade-off: the default implementation brings `spring-boot-starter-jdbc` and `sqlite-jdbc` as required runtime dependencies.

2. **Spring AI coupling**: The framework is tightly bound to Spring AI's `ChatModel`, `ToolCallback`, `ToolCallingManager`, and `ChatMemory` abstractions. Making the framework provider-agnostic would require a wrapper layer that adds complexity with no current user need. Trade-off: embedding apps must use Spring AI for model access, which is reasonable since Spring AI is the framework's model abstraction layer.

3. **MarkdownRenderer inclusion**: `ChatMarkdownRenderer` is technically a UI concern (renders HTML from markdown), but it's used in `TurnMessage.renderedHtml`. Embedding apps that serve HTML need this. Trade-off: include it as an optional dependency (embedding apps using plain text can ignore it). The `commonmark` and `owasp-html-sanitizer` dependencies become optional.

4. **Auto-configuration vs. manual wiring**: The framework could be a plain library with manual `@Bean` definitions, but `@AutoConfiguration` with `spring-boot-autoconfigure` is the standard Spring Boot pattern and makes embedding trivial. Trade-off: adds autoconfigure metadata as an optional dependency.

5. **TurnEvent vs. reactive stream**: `TurnEvent` is a sealed interface hierarchy emitted via `Flux<TurnEvent>`. This is simpler than a full reactive event bus and mirrors what `ChatStreamEvent` already does. Trade-off: embedding apps that don't use Project Reactor must adapt. Since Spring AI already uses Reactor for streaming, this is consistent.

6. **Single artifact vs. multi-module**: One `turnloop-core` artifact is simpler than splitting into `turnloop-api` + `turnloop-engine` + `turnloop-memory-sqlite`. Trade-off: embedding apps get SQLite transitively even if they provide their own memory implementation. This is acceptable for v1.

## Open Questions

1. **Should `InteractionQuestionTools` stay as a Spring AI `@Tool` or become a framework-level protocol?** Currently it's a tool the model calls with `@Tool(name = "ask_user_questions")`. This is elegant — the model decides when to ask. But it ties the question flow to tool invocation. Alternative: a framework-level `QuestionUserContract` that tools OR the engine can invoke. Recommend keeping the tool-based approach with a framework contract interface behind it.

2. **Should the framework provide SSE/WebSocket transport or leave it entirely to embedding apps?** The `TurnEvent` sealed hierarchy is the framework's output contract. Embedding apps map it to SSE, WebSocket, or any transport. The framework should provide a utility `Flux<TurnEvent> → Flux<ServerSentEvent<String>>` converter but NOT a controller. Recommend providing a transport-agnostic event model with an optional SSE mapping utility.

3. **What belongs in `InteractionMode`?** The current `PlanMode` has NORMAL, PLAN, TASK, EXECUTE_PLAN, EXECUTE_TASK. These are Magenta-specific. The framework should have NORMAL (default tool set), CUSTOM (app-defined mode with custom tool filtering and prompt assembly). Recommend `enum InteractionMode { NORMAL, CUSTOM_1, CUSTOM_2, CUSTOM_3 }` or a string-keyed mode registry.

4. **Does `ContextManagementAdvisor` stay as a Spring AI Advisor or become a standalone component?** Currently it's a `CallAdvisor` + `StreamAdvisor` which hooks into the `ChatClient` advisor chain. This is the Spring AI way. The framework's `TurnEngine` uses it directly (not through the advisor chain) via `preparePrompt()` and `prepareToolLoopPrompt()`. Recommend keeping the direct-call API and dropping the Advisor interface implementation — the framework engine calls it directly.

5. **Should `AuditService` record tool executions?** Currently it does via `TurnAuditWriter.recordToolExec()`. This is useful for observability. Keep it but make the audit repository interface optional (no-op default).

6. **How should the framework expose model aliasing?** Currently `ChatModelRouter.resolve("local-gemma")` looks up a configured alias, not a raw model name. This is a good pattern. Keep the alias resolution with `ModelConfig.remoteModelName`.

## Recommended Next Step

1. **Create the `turnloop-core` Maven module** on the `core` branch with the minimal pom.xml (Phase 0 dependencies only — Spring Boot starter, no web/Security).
2. **Begin Phase 1**: Port model/config classes first (lowest dependency footprint, highest testability). Verify Spring context starts with only `turnloop-core` classes.
3. **Sequentially execute Phases 2–9** as outlined, with validation gates at each phase.
4. **Delete all Magenta app code** from the `core` branch after the framework is fully functional.
5. **Write the embedding example** (`TurnLoopQuickstart.java`) that demonstrates: create TurnLoop, send a message, receive response, stream events, register a custom tool, handle question-user flow.

**Estimated total effort**: 18–25 engineering days for full extraction, testing, and documentation. The largest risk is Phase 5 (extracting the engine from ChatService's 3009 lines) — this requires surgical precision to preserve the battle-tested turn-loop state machine while removing every app-specific code path.
