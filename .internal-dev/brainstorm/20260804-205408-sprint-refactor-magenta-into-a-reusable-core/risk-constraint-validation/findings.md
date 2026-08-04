## Prompt

Brainstorm risks, constraints, and validation gates for stripping Magenta's application shell to produce a reusable core LLM turn-loop framework. Five areas: (1) Security removal impact, (2) `ChatService` transitive dependency surgery, (3) Configuration surface, (4) Validation strategy, (5) Production quality checklist.

## Source

Inspected: `pom.xml`, all `src/main/java/io/mindspice/magenta2/**`, `.internal-dev/specifications/architecture.md`, `.internal-dev/specifications/services.md`, `.internal-dev/specifications/api.md`, `.internal-dev/specifications/decisions.md`, `application.yml` (main + test), all 80+ test files, full `ChatService.java` (3,000 lines), `ChatModelRouter`, `ContextManagementAdvisor`, `ToolLoopGuard`, `ChatToolRegistry`, `ToolTranscriptService`, `ChatPendingMessageService`, `InteractionQuestionTools`, `ConversationTurnCoordinator`, `RequestResolver`, `ToolAccessPolicy`, `ChatStreamEvent`, `SseStreamLifecycle`, `ChatMemoryRepository`, `AiConfig`, `AiUserConfigConfiguration`, `MagentaRootConfiguration`, `Magenta2Application`, and all tool/controller/agent/skills packages.

## Findings

### 1. Security Removal

**Current state:** Magenta has NO Spring Security dependency, no auth filter chain, no sessions, no principals, no `@EnableWebSecurity`, no `SecurityFilterChain` bean, and no `spring-boot-starter-security` in `pom.xml`. The project runs with zero authentication. The `api/web` controllers are unguarded.

**Risk: Security removal is nearly a no-op, but with caveats.**

- **No Spring Security to remove** — the `pom.xml` has no `spring-boot-starter-security`. There is no `SecurityConfig` class. There is no `Authentication` or `Principal` injection anywhere. The project was designed as an open-alpha local application. *Risk: LOW. Nothing to delete, but also nothing to "disable."*

- **Web layer removal (SimplyPages + 47 controllers + fragments)** — Deleting `api/web/**` (47 controller/fragment files + SSE lifecycle + selectors) will remove ALL HTTP endpoints. The 21 web-layer tests become dead. This is the cleanest cut in the project. *Risk: LOW. Pure deletion without cascading breakage except where `ChatService` references `ChatStreamEvent` and `SseStreamLifecycle`.*

- **`SseStreamLifecycle` is in `api/web` but is purely transport** — It wraps `SseEmitter` lifecycle. The framework still needs SSE-style streaming but via a framework-owned `Flux` or `Publisher`, not `SseEmitter`. The `ChatStreamEvent` sealed hierarchy is currently in `ai/chat/model` (a core model) and must be generalized — it carries `ChatPlanState` in every event variant, which is an app-shell concern. *Risk: MEDIUM. The streaming contract must be reified as framework events without plan-state coupling.*

- **`@EnableScheduling` on `Magenta2Application`** — Powers schedule/reaction automation. This is an app-shell concern. Removing it removes the need for the scheduler, reaction, and all automation services. *Risk: LOW. The annotation can be removed from the framework's main/auto-configuration.*

- **Spring Boot starters that can be dropped:**
  - `spring-boot-starter-web` — removes Tomcat, MVC, all HTTP. Keep only if a demo/example app needs it; the framework artifact excludes it. *Risk: MEDIUM. Removing it requires `ChatStreamEvent` types to be transport-agnostic, and Spring AI's `ChatClient` and `ToolCallingManager` remain on classpath without web.*
  - `io.mindspice:simplypages` — gone.
  - `org.webjars.npm:htmx.org` and `org.webjars:webjars-locator-core` — gone.
  - `spring-boot-starter-webmvc-test` — test-only, replaced with framework integration test dependencies.
  - `spring-boot-starter-actuator` — optional; framework can ship without it.
  - `commonmark` + `commonmark-ext-gfm-tables` — currently used by `ChatMarkdownRenderer` for chat rendering. The framework must either keep markdown rendering as a core concept or make it pluggable. *Risk: MEDIUM. Markdown rendering is deeply coupled to the chat rendering pipeline.*
  - `owasp-java-html-sanitizer` + `jsoup` — currently used for HTML sanitization in web rendering. *Risk: LOW. These are web-layer concerns and can be removed once the web layer is gone.*

- **`spring-boot-starter-validation`** — used by `MagentaRootProperties` validation and controller `@Valid` annotations. Framework will still need validation for its own configuration properties. *Risk: LOW. Keep it.*

- **What breaks if `api/web` is deleted:** The 21 web-layer tests fail (expected). `ChatStreamEvent` types are still referenced by `ChatService` streaming methods. `SseStreamLifecycle` is not directly referenced by `ChatService` but by controllers that wrap the `ChatService.stream()` call. The `ChatService` streaming methods return `Flux<ChatMessage>` which is web-agnostic. *Risk: LOW-MEDIUM. The streaming Flux pipeline is clean; the coupling is in the event types used by controllers, not the core turn loop.*

### 2. Transitive Dependency Surgery — `ChatService` Collaborator Map

`ChatService` has approximately 30 constructor-injected collaborators. Here is the full map with severance decisions:

#### Must Keep (Core Turn Loop)
| Collaborator | Role | Framework Fate |
|---|---|---|
| `ChatMemory` (Spring AI interface) | Chat memory abstraction | Keep; framework facade wraps it |
| `ChatMemoryRepository` | SQLite-backed message persistence | Keep as pluggable backing store; abstract behind interface |
| `ChatSessionMetadataRepository` | Conversation metadata (model, surface, origin, title) | Keep but strip app-shell columns (surface, origin, agent_id, archived/favorite); generalize to framework metadata |
| `ChatMarkdownRenderer` | Markdown → HTML rendering | Keep as pluggable renderer; framework provides default commonmark impl |
| `AiConfig` | User-editable model/agent configuration | Keep; this IS the framework's model configuration record hierarchy |
| `ContextManagementAdvisor` | Token estimation, compaction, trimming, prompt assembly | Keep; core to framework |
| `ContextUsageTracker` | In-memory context usage tracking | Keep |
| `ChatModelRouter` | Model instance factory (Ollama/OpenAI/DeepSeek) | Keep; generalized to accept any Spring AI `ChatModel` |
| `ToolCallingManager` (Spring AI) | Tool execution dispatch | Keep; Spring AI's own tool calling |
| `ChatToolRegistry` | Tool registration and resolution by name | Keep; generalized to framework tool registry |
| `ToolTranscriptService` | Tool result transcript serialization/rendering | Keep; core to tool loop |
| `ConversationTurnCoordinator` | Per-conversation sequential turn queue | Keep; simplifies to turn gate |
| `AuditRepository` | Compaction and error audit logging | Keep as optional; framework logging facade |
| `ObjectMapper` | JSON serialization | Keep |

#### Must Sever (App-Shell Concerns)
| Collaborator | Current Role | Severance Strategy |
|---|---|---|
| `PlanService` | Plan CRUD, planning mode, plan execution, plan approval, saved plans | **DELETE.** All plan/task mode handling removed from turn loop. `ToolAccessPolicy.planModeTools` gone. `ChatService.beginPlan/approvePlan/executeSavedPlan` gone. `InteractionQuestionTools` becomes standalone framework question-asking (see below). |
| `TaskService` | Reusable task execution, task modes, task_complete enforcement | **DELETE.** Same as PlanService. Task-mode tool filtering gone. |
| `AgentJobService` | Background title-generation jobs | **DELETE** or replace with a framework callback/event. Currently only used for `conversationTitleJobStatus()`. |
| `AgentSkillActivationService` | Skill trust/activation lifecycle | **DELETE.** Skills are app-shell. Framework tools are plain Spring AI `ToolCallback` registrations. |
| `AgentSkillRuntimeCatalogService` | Runtime skill availability | **DELETE.** |
| `AgentSkillPromptCatalogAssembler` | Skill prompt assembly | **DELETE.** |
| `WorkTypeProfileService` | Work-type profile for plan/task prompt composition | **DELETE.** |
| `AgentsMdResolver` | AGENTS.md layered resolution from workspace | **DELETE.** Workspace-rooted AGENTS.md is an app-shell concept. |
| `RunMeasurementRepository` | Model/tool invocation measurement persistence | **DELETE** (or make optional/pluggable). Currently tracks each model call and tool invocation with timing. Framework could provide a measurement callback interface instead. |
| `AgentContextService` | Context binding capture, materialization, owner links | **DELETE.** App-shell workspace/agent context management. |
| `WorkspaceDirectoryService` | Descriptor resolution, path layout | **DELETE.** App-shell workspace filesystem. |
| `RuntimeSettingsService` | Runtime-overridden model, prompt, compaction settings | **DELETE** (or simplify). Currently provides runtime overrides for default model, system prompt, compaction model, context buffer percent. Framework should use a simpler property-based config. |
| `AuditService` | Error recording, title job enqueue | **DELETE** (or make optional). Framework should have its own error contract. |
| `ChatFileService` | Chat file directory management | **DELETE.** App-shell concept. |
| `ChatPendingMessageService` | Message queueing while model busy | **KEEP** as a framework concept. This is a core capability: queue user messages during a running turn and claim/ack them. The `PendingChatMessage` → `ClaimedPendingChatMessage` claim/ack flow is well-designed and framework-worthy. |
| `RequestResolver` | Conversation setup, model selection, context binding capture | **REFACTOR HEAVILY.** Core parts (model selection, conversation ID generation, new/existing detection) stay. App-shell parts (agent context capture, surface/origin metadata, plan-mode model override, system-chat rejection) removed. |

#### InteractionQuestionTools — Special Case

`InteractionQuestionTools` depends on `PlanService` and `TaskService`. These must be severed. However, the question-user loop IS a core framework requirement. The solution:

- **Generalize `InteractionQuestionTools` into a framework `QuestionCallback` interface.** The framework owns the question-asking tool contract. The embedding application provides the callback that receives question lists and returns answers. The framework no longer depends on PlanService/TaskService for question routing.
- The framework's question tool (`ask_user_questions`) calls the registered callback. The callback is responsible for persisting questions, presenting them to the user, and returning answers.

#### Turn Sub-Package Dependencies

| Turn Component | Collaborators | Severance |
|---|---|---|
| `PromptContextAssembler` | `AiConfig`, `RuntimeSettingsService`, `PlanService`, `TaskService`, `WorkTypeProfileService`, `AgentsMdResolver`, `AgentSkillPromptCatalogAssembler` | **REWRITE.** All collaborators except `AiConfig` are app-shell. The framework needs a simpler prompt assembler that builds system prompt from framework config + registered instructions. |
| `ToolAccessPolicy` | `ChatToolRegistry`, `PlanService`, `TaskService`, `AgentSkillRuntimeCatalogService` | **SIMPLIFY.** Remove plan/task mode tool filtering. The framework tool access policy is: approved tool names → resolved callbacks, with optional blocklists. No interaction-mode awareness. |
| `TerminalTurnRepair` | `PlanService`, `TaskService` | **DELETE** (or simplify to generic empty-response retry without plan/task awareness). |
| `TurnAuditWriter` | `AuditService`, `AuditRepository` | **REFACTOR** to framework event/listener model. |
| `TrustedContextPolicyAssembler` | `AgentContextService`, `WorkspaceDirectoryService` | **DELETE.** App-shell concern. |

#### Summary of Dependency Surgery

| Category | Count | Action |
|---|---|---|
| Keep as-is | 14 | ChatMemory, ChatMemoryRepository, ChatSessionMetadataRepository (trimmed), ChatMarkdownRenderer, AiConfig, ContextManagementAdvisor, ContextUsageTracker, ChatModelRouter, ToolCallingManager, ChatToolRegistry (generalized), ToolTranscriptService, ConversationTurnCoordinator, ObjectMapper, ChatPendingMessageService |
| Delete entirely | 13 | PlanService, TaskService, AgentJobService, AgentSkillActivationService, AgentSkillRuntimeCatalogService, AgentSkillPromptCatalogAssembler, WorkTypeProfileService, AgentsMdResolver, AgentContextService, WorkspaceDirectoryService, RuntimeSettingsService, AuditService, ChatFileService |
| Generalize/rewrite | 6 | RequestResolver, PromptContextAssembler, ToolAccessPolicy, TerminalTurnRepair, TurnAuditWriter, InteractionQuestionTools |
| Optional/pluggable | 2 | AuditRepository, RunMeasurementRepository |

### 3. Configuration Surface

#### Keep (Framework Properties)

| Property | Justification |
|---|---|
| `magenta.root.path` | Framework data root for SQLite DB and optional local storage |
| `spring.datasource.url` | SQLite JDBC URL (framework default: `jdbc:sqlite:${magenta.root.path}/core.sqlite`) |
| `spring.datasource.driver-class-name` | `org.sqlite.JDBC` |
| `spring.ai.ollama.base-url` / `spring.ai.ollama.chat.options.model` | Spring AI auto-config for Ollama |
| Model configuration via `AiConfig` (from JSON/YAML) | Framework must load model definitions (alias, remote endpoint, API key, endpoint type, think level, context length) |
| Default agent configuration via `AiConfig.agents` | Simplified: framework needs `defaultModel`, `systemPrompt`, `approvedTools` per agent. No workspace, no file directories, no web search config. |
| `magenta.ai.openai-compatible-read-timeout-seconds` | Keep for OpenAI-compatible endpoints |
| `magenta.features.schedules-enabled` / `magenta.features.reactions-enabled` | **DELETE.** App-shell automation. |
| `magenta.plan.execution-stream-timeout-seconds` | **DELETE.** Plan execution is app-shell. |

#### New Framework Configuration Properties

```yaml
core:
  memory:
    compaction:
      buffer-percent: 10          # context window buffer before compaction triggers
      min-tail-messages: 6        # minimum messages retained after compaction
    max-stored-messages: null     # hard cap (null = unlimited)
  
  tool-loop:
    max-consecutive-identical-calls: 5    # ToolLoopGuard threshold
    error-window-size: 8                 # ToolLoopGuard error window
    error-window-limit: 5                # ToolLoopGuard error threshold
    max-tool-iterations: 50              # hard cap on tool loop iterations (new)
  
  models:
    default-model: "default"             # alias key in AiConfig.models
    compaction-model: null               # model for context compaction (falls back to default)
    summary-model: null                  # model for title generation
  
  streaming:
    default-timeout-seconds: 300         # default stream timeout
  
  pending-messages:
    claim-timeout-seconds: 120           # stale claim recovery timeout
    max-queue-size-per-conversation: 100 # prevent unbounded queueing
```

#### Properties to Delete

- `app.ai.config-path` — becomes `core.ai.config-path` or auto-discovered
- `server.port` — removed (no embedded server in framework)
- `spring.web.resources.chain.enabled` — removed
- `spring.autoconfigure.exclude` OpenAi exclusions — keep but document
- All SimplyPages/webjar properties
- All `magenta.plan.*`, `magenta.features.*` properties

### 4. Validation Strategy — Phased Gates

#### Phase 0: Baseline (Before Any Cuts)
- **Gate 0.1:** Full build passes: `mvn clean verify` with all 80+ existing tests
- **Gate 0.2:** Spring context starts clean: `timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0`
- **Risk:** If tests are already failing, the baseline is unreliable. *Mitigation: Run baseline before any changes; fix any pre-existing failures first.*

#### Phase 1: Delete Web Layer + SimplyPages + Security (Nonexistent)
- **Gate 1.1:** All `api/web/**` tests deleted. Remaining 59 tests (non-web) pass.
- **Gate 1.2:** `pom.xml` drops `spring-boot-starter-web`, `simplypages`, `htmx.org`, `webjars-locator-core`, `spring-boot-starter-webmvc-test`, `owasp-java-html-sanitizer`, `jsoup`.
- **Gate 1.3:** Context starts without embedded Tomcat. `Magenta2Application` is renamed/replaced with a framework bootstrap class that does NOT start a web server.
- **Gate 1.4:** `ChatStreamEvent` is generalized to remove `ChatPlanState` coupling. `ChatMessage` (already plan-state-free) becomes the primary stream event carrier.
- **Gate 1.5:** Compilation succeeds. All non-web tests pass.

#### Phase 2: Sever App-Shell Dependencies from ChatService
- **Gate 2.1:** `ChatService` constructor drops 13+ parameters (PlanService, TaskService, AgentJobService, skills services, workspace services, orchestration context services, measurement repository, etc.). All are made `@Autowired(required = false)` first, then removed.
- **Gate 2.2:** `ChatService` unit tests (`ChatServiceTest.java`) pass with new constructor shape. Mock/fake collaborators for remaining core dependencies.
- **Gate 2.3:** `ToolAccessPolicy` simplified to name-based filtering only. `PromptContextAssembler` rewritten to framework prompt assembly. `TerminalTurnRepair` simplified to generic retry.
- **Gate 2.4:** `InteractionQuestionTools` generalized to callback interface. Question-asking unit test passes with mock callback.
- **Gate 2.5:** All remaining plan/task/orchestration/skills packages compile and their tests pass without `ChatService` referencing them. (They may be dead code, but they still compile.)

#### Phase 3: Delete App-Shell Packages
- **Gate 3.1:** Deleted packages: `ai/orchestration/**`, `ai/chat/plan/**`, `ai/chat/task/**`, `ai/chat/project/**`, `ai/chat/system/**`, `ai/chat/tool/file/**`, `ai/chat/tool/shell/**`, `ai/chat/tool/web/**`, `ai/chat/tool/orchestration/**`, `ai/chat/tool/plan/**`, `ai/chat/tool/savedplan/**`, `ai/chat/tool/task/**`, `ai/chat/tool/skills/**`, `ai/skills/**`, `ai/agent/**`, `ai/execution/**` (MagentaWorkExecutor stays if needed for turn coordination, but `MagentaWorkKind`/`OrchestrationTaskContext`/`OrchestrationTaskContextHolder` go).
- **Gate 3.2:** `ai/config/user/AgentConfig` simplified: remove `approvedTools`, `workspaceId`, `webSearch`, `fileDirectories`, `shell`, `skills`. Keep `model`, `systemPrompt`, `name`.
- **Gate 3.3:** `AiConfig` simplified: remove `planningModel`, `summaryModel`, `dataRoot`, `webSearch`, `unsafeAllowWildcardShellCommands`. Keep `models`, `agents`, `defaultAgent`, `defaultModel`, `compactionModel`, `contextBufferPercent`.
- **Gate 3.4:** Compilation + remaining tests pass.

#### Phase 4: Refactor into Framework Artifact
- **Gate 4.1:** Rename artifact from `magenta2` to `magenta-core` (tentative; user will choose final name). Package rename from `io.mindspice.magenta2` to `io.mindspice.core` (or user-chosen package).
- **Gate 4.2:** Create public facade (`MagentaCore` / `AgentCore`). Methods: `sendMessage(...)`, `streamMessage(...)`, `answerQuestion(...)`, `registerTool(...)`, `registerTools(...)`, `configureModel(...)`, `clearConversation(...)`, `close()`. All internal services hidden behind facade.
- **Gate 4.3:** Framework auto-configuration class (`CoreAutoConfiguration`) that creates all required beans when `@EnableMagentaCore` is present.
- **Gate 4.4:** Framework context starts with ONLY the core beans. No web server, no orchestration, no workspace, no skills.
- **Gate 4.5:** Minimal demo `main()` method that embeds the framework, registers a tool, and sends a message.

#### Phase 5: Production Quality Validation
- **Gate 5.1:** Thread safety — `ChatService` uses `ConcurrentHashMap` for stream locks and `ThreadLocal` for trusted policies. `ConversationTurnCoordinator` uses `synchronized` on per-conversation queues. Verify no race conditions in facade under concurrent access via stress test.
- **Gate 5.2:** Graceful lifecycle — `close()` or `@PreDestroy` ensures all in-flight turns complete or are cancelled, stream locks released, pending message claims released.
- **Gate 5.3:** Error handling — framework exceptions have clear hierarchy (`CoreException`, `ModelException`, `ToolException`, `ConfigurationException`). No raw `IllegalStateException` leaks.
- **Gate 5.4:** Dead code scan — PMD or IDE unused-code analysis confirms zero references to deleted packages from retained code.
- **Gate 5.5:** Documentation — README with embedding example, configuration reference, tool registration guide.

### 5. Production Quality Checklist

#### Thread Safety
- **Risk: `ChatService.streamLocks` (`ConcurrentHashMap<String, Semaphore>`)** — Fine-grained per-conversation streaming lock. `Semaphore.tryAcquire()` provides non-blocking check. However, `streamLocks.compute()` in `doFinally` has a subtle race: between `lock.release()` and `compute()`, another thread could insert a new `Semaphore`. *Mitigation: Document that streamLocks is best-effort cleanup; orphaned entries are harmless (they're just Semaphore objects in a map).*
- **Risk: `activeTrustedPolicies` (`ThreadLocal`)** — Correct for the current thread-per-turn model. If framework is used with virtual threads (Java 25), `ThreadLocal` is safe but could leak if threads are pooled and `restoreTrustedPolicy` is not called in finally blocks. *Mitigation: All call sites already have try/finally restore. Audit every path.*
- **Risk: `ConversationTurnCoordinator.queues` (`synchronized` block)** — Correct. The `scheduleNext` callback runs on `CompletableFuture.whenComplete` which may run on any thread, but the synchronized block protects the queue state.
- **Risk: `ChatModelRouter.modelsByKey` (`ConcurrentHashMap`)** — Thread-safe. Models are built once on first access via `computeIfAbsent`.

#### Graceful Lifecycle
- **Risk: No `close()` or shutdown hook on `ChatService`** — Currently there is no cleanup of in-flight turns on shutdown. The framework must provide a `close()` on the facade that: (1) drains/completes all pending turns in `ConversationTurnCoordinator`, (2) releases all stream lock semaphores, (3) releases all pending message claims, (4) calls `ChatMemory.close()` if applicable. *Mitigation: Add `@PreDestroy` on facade bean and a `close()` method that orchestrates shutdown.*
- **Risk: `ChatModelRouter` caches model instances but never closes them** — Spring AI `ChatModel` implementations may hold HTTP clients that need shutdown. *Mitigation: `close()` iterates `modelsByKey` and calls `close()` on any `AutoCloseable` model.*

#### Error Handling Contracts
- **Risk: `ToolUseAbort` is a `RuntimeException`** — It is used for control flow inside the tool loop (caught in `handleEvaluate`). This is an internal implementation detail but must not leak through the facade. *Mitigation: Facade methods catch `ToolUseAbort` and translate to a controlled `ToolLoopExceededException`.*
- **Risk: `NonTransientAiException` propagation** — The tool retry loop catches this to detect tool-unsupported models. The framework must ensure this Spring AI exception doesn't leak raw through the facade. *Mitigation: Wrap in `ModelException`.*
- **Risk: `ChatService.clearConversation()` calls into `planService`, `agentSkillActivationService`, etc.** — After severing, these calls are gone. The framework's clear must only clear memory, pending messages, and context usage.

#### No Dead Code
- **Risk: `ai/config/user/AgentConfig` has many fields that will be deleted** — `approvedTools`, `workspaceId`, `webSearch`, `fileDirectories`, `shell`, `skills`. After deletion, ensure no remaining code references them.
- **Risk: `ChatSessionOrigin` enum has `AGENT_CHAT`, `SYSTEM_CHAT`, `PROJECT_CHAT`** — All become dead after app-shell removal. Only `CHAT` remains.
- **Risk: `ChatSessionSurface` enum** — Dead after web layer removal. Delete entirely.
- **Risk: `PlanMode` enum** — Dead after plan/task removal. Delete entirely.
- **Risk: `ChatPlanState` record** — Dead. Delete.
- **Risk: `MagentaWorkKind`, `MagentaWorkRequest`, `OrchestrationTaskContext`, `OrchestrationTaskContextHolder`** — All dead. Delete.
- **Risk: `RunMeasurementRepository` and related types** — Dead (or optional). Delete the `ChatService` dependency; keep the repository if useful for other consumers.

#### Configuration Validation
- **Risk: `AiConfig` loaded from JSON at startup** — If the JSON file is missing or malformed, startup fails with a cryptic `IOException`. *Mitigation: Framework auto-configuration provides a sensible default `AiConfig` with a single Ollama model pointing to `localhost:11434` and a default agent, so embedding apps start without external config files.*
- **Risk: Model aliases vs. remote names confusion** — `ChatModelRouter.resolve()` accepts either alias keys or remote names. This flexibility is a source of bugs if a remote name happens to collide with an alias. *Mitigation: Framework should strongly prefer alias keys; deprecate remote-name resolution at the facade level.*
- **Risk: `ChatSessionMetadataRepository` DDL (`ensureMetadataColumn` pattern)** — Each repository creates its own table on first access. This works for SQLite but must be reconciled with the framework's schema migration approach. *Mitigation: Framework should own a single `schema.sql` that creates all core tables, or keep the lazy-DDL pattern uniformly.*

#### Test Coverage Gaps
- **Risk: No unit test for the full turn loop** — `ChatServiceTest.java` exists but may not cover the `toolChat` state machine paths exhaustively. *Mitigation: Add focused tests for each `TurnPhase` transition, tool loop abort scenarios, malformed-tool-call recovery, and tool-unsupported-model fallback.*
- **Risk: No integration test for question-user flow end-to-end** — `InteractionQuestionToolsTest.java` exists but tests only the tool call, not the full suspend-resume lifecycle. *Mitigation: Add integration test that sends a message, receives a question event, answers it, and receives the final response.*
- **Risk: No stress/concurrency test** — *Mitigation: Add test that sends 10 concurrent messages to the same conversation and verifies serialization via `ConversationTurnCoordinator`.*
- **Risk: No test for pending message claim/ack flow under contention** — *Mitigation: Add concurrent claim test where two threads try to claim the same message.*

## Options

### Option A: Maximalist Cut (Recommended)
Delete everything not in the core turn loop. `ChatService` becomes `TurnLoopEngine` with ~15 dependencies. Facade is `MagentaCore` with 8 public methods. All tool packages except `ChatToolRegistry`, `ToolTranscriptService`, `ToolArgumentCoercionModule`, `InteractionQuestionTools` (generalized) are deleted.

**Pros:** Cleanest result. Smallest surface to test and maintain. Clear framework boundaries.
**Cons:** More upfront work. Higher risk of deleting something that turns out to be needed.

### Option B: Gradual Cut (Safer)
Phase 1: Make all app-shell dependencies `@Autowired(required = false)`. Keep them compiling but unused. Phase 2: Delete packages one by one, verifying compilation at each step. Phase 3: Remove `required = false` and delete constructor parameters.

**Pros:** Lower risk of catastrophic breakage. Easier to bisect if something goes wrong.
**Cons:** Slower. Intermediate states have dead code paths. Harder to reason about what's actually used.

### Option C: Keep AiConfig as the Configuration Hub
`AiConfig` is already the user-facing configuration model. Rather than add many new `@ConfigurationProperties`, the framework could keep `AiConfig` as the primary config surface and add framework-specific fields to it or a companion `CoreConfig` record.

**Pros:** Single configuration object. Familiar to existing Magenta users.
**Cons:** `AiConfig` carries app-shell baggage (webSearch, unsafeShell, etc.). Needs trimming.

**Recommendation: Option A with Option C's config approach (trimmed AiConfig + CoreConfig).**

## Trade-offs

1. **SQLite vs. pluggable persistence** — SQLite is the only supported store. Making memory/pending-message persistence pluggable (JPA, JDBC interface) adds abstraction but enables non-SQLite deployments. *Trade-off: Keep SQLite as default, expose `ChatMemoryRepository` interface for alternate implementations. Accept that the framework ships opinionated for SQLite.*

2. **Spring AI 2.0.0 coupling** — The framework deeply depends on Spring AI's `ChatModel`, `ToolCallback`, `ToolCallingManager`, `ChatMemory`, `ChatClient`. This is both a strength (leverage Spring AI ecosystem) and a weakness (version lock). *Trade-off: Accept Spring AI as the model abstraction layer. Framework version tracks Spring AI version. Embedding apps get Spring AI compatibility for free.*

3. **ChatMemory abstraction** — Spring AI provides `ChatMemory` and `ChatMemoryRepository` interfaces. The framework currently uses `ChatMemoryRepository` (SQLite-backed) directly. The facade should accept a `ChatMemory` from the embedding app. *Trade-off: Framework auto-configures a SQLite `ChatMemoryRepository` by default. Apps can override with a custom `ChatMemory` bean. The `ContextManagementAdvisor` depends on `ChatMemoryRepository` (not `ChatMemory`) — this must change to use the Spring AI abstraction.*

4. **Streaming contract** — Currently `ChatService.stream()` returns `Flux<ChatMessage>`. The `ChatMessage` type carries `text`, `renderedHtml`, `thinkingHtml` — all markdown-rendered. Framework should return a `Flux<CoreStreamEvent>` that carries raw text chunks, tool activities, context usage updates, and question prompts. Rendering is the embedding app's responsibility. *Trade-off: Framework returns raw events; example app shows markdown rendering. Increases upfront work but produces a cleaner framework.*

5. **Pending message queue** — Currently uses SQLite-backed `ChatPendingMessageRepository` with claim/ack flow. This is a strong pattern but ties queueing to SQLite. *Trade-off: Keep the claim/ack pattern but expose a `PendingMessageStore` interface. Default implementation uses SQLite.*

## Open Questions

1. **Artifact naming:** User's eventual framework intent is unclear. Tentative: `io.mindspice.core:magenta-core`. Confirm with user: groupId, artifactId, primary package name.

2. **Agent abstraction:** The directive says "keep a small set of basic tools as examples." Should the framework ship with ANY built-in tools? If so, which? Candidates: `ask_user_questions` (question-user loop tool), `echo` (test tool), `get_time` (simple utility). Recommendation: Ship with `ask_user_questions` as a built-in tool, plus an `ExampleTools` class demonstrating how to register tools.

3. **ChatModelRouter generalization:** Currently builds Ollama/OpenAI/DeepSeek models from `AiConfig`. Should the framework accept any pre-built `ChatModel` bean from the embedding app, or should it own model construction? Recommendation: Both. Framework auto-configures models from `AiConfig` (convenience), but embedding apps can also provide `ChatModel` beans directly (override).

4. **Compaction model:** Currently uses a separate "compaction model" for summarization. Should the framework require a dedicated compaction model, or should it fall back to the primary model? Recommendation: Default to primary model if compaction model not configured.

5. **Schema migration:** The current project has 20 ordered `NNN-kebab.sql` migration files managed by `SchemaMigrationRunner`. The framework will need a subset of these (the `ai_chat_memory` table, chat metadata, pending messages). Should the framework keep the ordered migration pattern or simplify? Recommendation: Simplify to a single `schema.sql` that creates all core tables idempotently. The framework has fewer tables and doesn't need 20 migration steps.

6. **`ToolArgumentCoercionModule`** — Currently wraps `ToolCallingManager` argument coercion. Is this framework-critical or can it be replaced by Spring AI's built-in argument resolution? Recommendation: Keep as a thin wrapper; it provides useful error messages for malformed tool-call arguments.

7. **`MagentaWorkExecutor`** — Currently wraps a `ThreadPoolTaskExecutor` for background work (chat turns run on a thread pool). The framework needs some execution strategy. Should it use virtual threads (Java 25) or keep the thread pool? Recommendation: Use virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` as the default. This simplifies lifecycle (no pool sizing) and works well for I/O-bound model calls. Provide a configuration property to use a fixed thread pool as an alternative.

## Recommended Next Step

1. **Confirm naming and scope with user** — artifact ID, package name, which tools to keep as examples.
2. **Run baseline validation** (Phase 0 gates) to confirm all existing tests pass before any cuts.
3. **Begin with Phase 1** — Delete `api/web/**` entirely, drop web dependencies from pom.xml, generalize `ChatStreamEvent`, rename `Magenta2Application` to framework bootstrap. This is the lowest-risk phase and produces immediate clarity on what's left.
4. **Proceed to Phase 2** — Sever `ChatService` dependencies in order: plan/task services first (these are the deepest coupling), then workspace/context services, then skills, then measurement/audit services. Keep `required = false` at each step to maintain compilation.
5. **After Phase 2, the "skeleton" is ready** — the remaining phases (3-5) are deletion, refactoring, and quality hardening. These can be parallelized where safe.
