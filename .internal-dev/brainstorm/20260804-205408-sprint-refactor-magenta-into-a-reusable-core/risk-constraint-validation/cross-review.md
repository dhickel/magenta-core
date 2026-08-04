## Prompt

Cross-review the three other brainstorm reports — Architecture-Module-Boundaries, Internal-Abstractions-Design, and Phased-Migration-Planning — against the Risk, Constraint & Validation Guardian findings. Identify conflicts, omissions, feasibility gaps, and trade-offs. Ground every observation in the actual codebase. Preserve a risk-first lens. Produce a unified synthesis that makes the combined plan stronger without merely defending the first answer.

## Source

All three reports are inline above. Primary codebase references from the earlier risk-gathering pass: `ChatService.java` (3009 lines), `ChatBeanConfig.java`, `TurnContext.java`, `TerminalTurnRepair.java`, `ChatModelRouter.java`, `ContextManagementAdvisor.java`, `ToolAccessPolicy.java`, `PromptContextAssembler.java`, `ChatToolRegistry.java`, `ToolTranscriptService.java`, `ToolLoopGuard.java`, `InteractionQuestionTools.java`, `RequestResolver.java`, `ConversationTurnCoordinator.java`, `MagentaWorkExecutor.java`, `ActiveTurnRegistry.java`, `ChatMemoryRepository.java`, `ChatPendingMessageRepository.java`, `ChatSessionMetadataRepository.java`, `ChatStreamEvent.java`, `SseStreamLifecycle.java`, `AiConfig.java`, `AgentConfig.java`, `ModelConfig.java`, `AiUserConfigConfiguration.java`, `MagentaRootConfiguration.java`, `pom.xml`, `application.yml`.

## Findings

### 1. Conflicts Between Reports

#### 1.1 Artifact Naming (Three-Way Split)

| Report | Artifact ID | Root Package | Rationale |
|---|---|---|---|
| Architecture | `turnloop-core` | `io.mindspice.turnloop` | Descriptive, unambiguous |
| Abstractions | `mindspice-loom` | `io.mindspice.loom` | Memorable, short, domain-communicative |
| Migration | `magenta-core` | `io.mindspice.core` | Continuity with Magenta brand |

**Risk assessment:** The naming is a user decision, but the wrong choice has downstream consequences. `magenta-core` would collide if Magenta-the-app is later rebuilt on top of the framework. `turnloop-core` is technically accurate but verbose. `loom` is distinctive but creates a new brand with no existing recognition. The framework's internal design is independent of the name.

#### 1.2 `TurnMode` vs `InteractionMode` vs Removal

| Report | Approach |
|---|---|
| Architecture | `InteractionMode` enum: NORMAL, PLANNING, EXECUTING |
| Abstractions | `TurnMode` enum: NORMAL, PLAN, TASK, EXECUTE_PLAN, EXECUTE_TASK |
| Migration | Remove enum entirely; use pluggable `CompletionDetector` + `RepairStrategy` hooks |

**Code-grounding:** The current `TurnContext` carries `PlanMode interactionMode` which flows through `ToolAccessPolicy.filterToolsByMode()`, `TerminalTurnRepair.requiresPlanTurnRepair()`, and `ChatService.toolChat()` plan-completion detection. `TerminalTurnRepair` uses mode-specific repair messages. The mode enum is deeply embedded in the state machine's EVALUATE and REPAIR phases.

**Risk assessment:** Architecture's `InteractionMode` (3 values) loses TASK/EXECUTE_TASK distinction. Abstractions' `TurnMode` (5 values) is the cleanest migration from `PlanMode` but carries app concepts into the framework. Migration's removal is the purest framework design but requires the most refactoring work. The `CompletionDetector` interface must replicate logic currently spread across three classes.

**Recommendation:** Use string-keyed modes with framework-defined constants (`"NORMAL"`, `"PLAN"`, `"TASK"`, `"EXECUTE_PLAN"`, `"EXECUTE_TASK"`). `ToolAccessPolicy` and `RepairStrategy` are registered per mode key. Embedding apps define additional modes. This gives the migration ease of the enum approach with the extensibility of the hook approach.

#### 1.3 Facade API Shape

| Report | Style |
|---|---|
| Architecture | Single `TurnLoop` interface (flat, ~12 methods) |
| Abstractions | Hybrid `LoomCore` with direct methods + `tools()`, `memory()`, `sessions()` sub-interfaces |
| Migration | Concrete `Core` class with `CoreBuilder`; `Core` is `final` + `AutoCloseable` |

**Code-grounding:** `ChatService` is currently a `@Service` class with ~40 public methods, not an interface. The existing code has no facade pattern — controllers call `ChatService` directly. The `ChatService` constructor takes 28 parameters (many `@Autowired(required = false)`).

**Risk assessment:** Architecture's single-interface approach is simplest but becomes a God-object. Abstractions' hybrid addresses the God-object concern but adds navigational complexity. Migration's concrete-class approach is Spring-idiomatic but `final class Core` cannot be mocked in tests without a wrapper.

**Recommendation:** Interface `Core` with `DefaultCore` implementation. `CoreBuilder.build()` returns `Core` (the interface). Spring auto-configuration creates a `DefaultCore` bean exposed as `Core`. For testability, internal services (`TurnEngine`, `ToolRegistry`, `MemoryManager`) are interfaces mocked directly. The facade is the composition root, not the unit under test.

#### 1.4 Module Structure

| Report | Modules |
|---|---|
| Architecture | 1: `turnloop-core` |
| Abstractions | 4: `loom-core`, `loom-transport-sse`, `loom-autoconfigure`, `loom-example` |
| Migration | 1: `magenta-core` |

**Code-grounding:** The `SseStreamLifecycle` class currently lives in `api/web/` but wraps `SseEmitter` from `spring-boot-starter-web`. After Phase 1 deletes `api/web/`, `SseStreamLifecycle` must be moved to core (pulling in web dependency) or dropped. Abstractions correctly identifies this as a module-boundary decision.

**Risk assessment:** Single module is simplest but couples transport to core. Multi-module is cleaner but adds build complexity. **Critical detail missed by Architecture and Migration:** If core stays web-free, `SseStreamLifecycle` cannot live in core.

**Recommendation:** Multi-module for production, single module for the initial extraction (Phase 4). After Phase 5 (facade), split into modules as a Phase 5.5 step. This avoids multi-module overhead during the riskiest refactoring phases.

#### 1.5 `ChatPlanState` Handling

All three reports agree: delete `ChatPlanState` from the framework. Abstractions and Migration both propose `Map<String, Object> metadata` as replacement. Architecture removes it without replacement. **No conflict — unified agreement.**

#### 1.6 Tool Framework Generalization Depth

| Report | Tool SPI Depth |
|---|---|
| Architecture | `ToolRegistry` + `ToolAccessPolicy` + `QuestionUserContract` (3 interfaces) |
| Abstractions | `ToolRegistry` + `ToolArgumentCoercer` + `ToolTranscriptStrategy` + `ToolAccessPolicy` + `UserQuestionTool` + `ToolResultSummarizer` (6 interfaces) |
| Migration | `ToolRegistry` + `ToolResultSummarizer` + pluggable `InteractionHandler` (3 interfaces) |

**Code-grounding:** `ToolTranscriptService.summarizeResult()` has a 12-case `switch` on tool names (`"file_list"`, `"shell_exec"`, `"web_search"`, etc.). This switch IS the problem that `ToolResultSummarizer` solves.

**Risk assessment:** Abstractions' 6-interface design is thorough but over-engineers. `ToolArgumentCoercer` as a separate interface adds little value — the existing Jackson module approach is sufficient. Architecture misses `ToolResultSummarizer` which is needed to replace the hard-coded switch. Migration hits the sweet spot.

**Recommendation:** Migration's 4-interface approach (`ToolRegistry`, `ToolAccessPolicy`, `ToolResultSummarizer`, `InteractionHandler`) with `ToolLoopGuard` as a concrete class and `ToolArgumentCoercionModule` as a Jackson module registered via service loader.

### 2. Omissions — What No Report Covered

#### 2.1 `ChatBeanConfig.java` Adaptation

**Omitted by:** Architecture, Abstractions, Migration. Risk report flagged the `RuntimeSettingsService` dependency but didn't detail the bean wiring.

**Reality:** `ChatBeanConfig` defines `contextManagementAdvisor` bean with `RuntimeSettingsService` parameter. After Phase 2 deletes `RuntimeSettingsService`, the bean method must switch to the 7-parameter constructor of `ContextManagementAdvisor`.

**Mitigation:** During Phase 2, change the bean method to use the constructor without `RuntimeSettingsService`.

#### 2.2 `ChatService` Constructor Chain Collapse

**Omitted by:** Architecture and Abstractions. Migration partially covers it.

**Reality:** `ChatService` has 5 constructors forming a delegation chain (5-parameter lightweight, 14-parameter test, 22-parameter test, 23-parameter, 28-parameter production). After Phase 2, all must collapse to a single 10-14 parameter constructor.

**Risk:** `ChatServiceTest.java` uses the 14-parameter constructor. After Phase 2, every test must be updated. If tests are not adapted during Phase 2, the build breaks and Phases 2-6 have no safety net.

**Mitigation:** Phase 2 must include test adaptation: delete plan/task test methods, update constructor invocation, add mock implementations for any new framework interfaces.

#### 2.3 `ChatServiceTest.java` Adaptation

**Omitted by:** All three other reports.

**Reality:** `ChatServiceTest.java` is a 500+ line test file that constructs `ChatService` with mock collaborators. It likely tests plan-related methods that must be deleted. The remaining test methods must survive.

**Mitigation:** Phase 2 must adapt `ChatServiceTest` incrementally — delete plan/task test methods, update constructor, verify remaining tests pass.

#### 2.4 `SchemaMigrationRunner` Simplification

**Omitted by:** Architecture and Abstractions.

**Reality:** `SchemaMigrationRunner` manages 20 ordered migrations. After Phase 2, only 4 tables remain (`ai_chat_memory`, `ai_chat_session_metadata`, `ai_chat_pending_messages`, `audit_log`). The runner validates contiguous gap-free migrations and hashes exact bytes — deleting or renaming migrations breaks validation.

**Mitigation:** During Phase 4, replace with `SqliteSchemaInitializer` that uses `CREATE TABLE IF NOT EXISTS` for the 4 remaining tables. The current self-migrating pattern in `ChatMemoryRepository.ensureMetadataColumn()` proves this works.

#### 2.5 `AiConfig` Constructor Proliferation

**Omitted by:** All three other reports.

**Reality:** `AiConfig` has 5 constructors. After removing `agents`, `defaultAgent`, `planningModel`, `compactionModel`, `unsafeAllowWildcardShellCommands`, the record shrinks from 12 fields to ~7. The convenience constructors no longer make sense.

**Mitigation:** In Phase 2, collapse `AiConfig` to a simple record with one canonical constructor. Update `AiUserConfigConfiguration` and `ExternalAiConfigLoader`.

#### 2.6 `ConversationTurnCoordinator` Fate

**Omitted by:** Architecture.

**Reality:** The sync chat path uses `ConversationTurnCoordinator` for turn serialization. The streaming path uses a per-conversation `Semaphore` in `streamLocks`. These are two different serialization mechanisms for the same purpose.

**Risk:** The `Semaphore` approach has a subtle race condition in `streamLocks.compute()` (identified in the Risk report). A unified `TurnGate` interface eliminates this.

**Recommendation:** Both paths use `TurnGate` (the generalized coordinator). The `Semaphore` approach is removed.

#### 2.7 `AgentConfig` Removal Impact on `ChatModelRouter`

**Omitted by:** Architecture.

**Reality:** `ChatModelRouter.defaultRemoteModelName()` falls through: `RuntimeSettingsService.defaultModel()` → `AiConfig.agents().get(aiConfig.defaultAgent()).model()` → exception. After both are deleted, the method must fall through differently.

**Mitigation:** Change `defaultRemoteModelName()` to use `AiConfig.defaultModel` if set, first model in `AiConfig.models()` if not empty, or throw `IllegalStateException`. Validate on load that `models()` is non-empty.

#### 2.8 Thread Safety and Graceful Shutdown

**Omitted by:** Architecture, Abstractions, and Migration — all three treat this as a Phase 5/6 concern without specifying it.

**Risk report flagged 4 thread-safety issues:** streamLocks race, activeTrustedPolicies ThreadLocal leak, ConversationTurnCoordinator correctness, ChatModelRouter correctness. Also: `close()` must drain in-flight turns, release stream lock semaphores, release pending message claims, close HTTP clients in `ChatModelRouter`.

**Recommendation:** Add a `LifecycleManager` component to the Phase 4 package structure. It's owned by the facade's `close()` method. Internal services register close hooks during construction. This is a Phase 4 concern, not Phase 6.

#### 2.9 `JTokkitTokenCountEstimator` Availability

**Omitted by:** All three other reports.

**Reality:** `ChatBeanConfig` creates a `JTokkitTokenCountEstimator` bean. This is a Spring AI transitive dependency. The framework should make this bean conditional on class availability.

**Recommendation:** `@ConditionalOnClass` on the `TokenCountEstimator` bean, falling back to a character-count-based estimator.

#### 2.10 `ChatSessionMetadataRepository` Column Cleanup

**Omitted by:** Architecture and Abstractions.

**Reality:** After Phase 2, columns `origin`, `surface`, `agent_id`, `project_id`, `member_agent_id`, `materialization_key`, `materialization_generation`, `owner_binding_key`, `active_task_run_id`, `planning_model` are dead but remain in the schema.

**Recommendation:** During Phase 4, simplify to a clean `CREATE TABLE IF NOT EXISTS` with only 6 columns: `conversation_id`, `model`, `title`, `favorite`, `archived`, `updated_at`.

### 3. Feasibility Gaps

#### 3.1 The `PromptContextAssembler` Problem

**Gap:** All reports agree `PromptContextAssembler` must be generalized, but its current 6-layer fixed-order prompt IS the Magenta product behavior. After removal, what replaces it?

**Code-grounding:** `PromptContextAssembler.assemble()` builds: immutable policy → trusted binding → mode intent → AGENTS.md guidance → skill snapshots → turn instructions. If all 6 layers are Magenta-specific, the framework has no prompt assembly logic.

**Recommendation:** The framework ships with a `DefaultPromptAssembler` that builds a 3-layer prompt: (1) system prompt from config, (2) mode-specific instructions (if mode is set), (3) turn instructions. Embedding apps provide a `PromptContributor` SPI to inject additional layers. This is the "pluggable pipeline" from Abstractions.

#### 3.2 The `ToolAccessPolicy` Constants Problem

**Gap:** `ToolAccessPolicy` has 4 hard-coded `List<String>` constants (`PLAN_MODE_TOOLS`, `TASK_MODE_TOOLS`, `NORMAL_BLOCKED_TOOLS`, `EXECUTION_BLOCKED_TOOLS`). Every report says "generalize" but none say HOW.

**Reality:** These lists encode app-specific tool names like `"plan_set_goal"`, `"task_complete"`, `"ask_user_questions"`. A framework `ToolAccessPolicy` cannot hard-code these.

**Recommendation:** The framework's `ToolAccessPolicy` interface takes a `Map<String, Set<String>>` (mode → allowed tool names). The default implementation is `ToolAccessPolicy.allowAll()`. Embedding apps register their own mode→tools mappings.

#### 3.3 The `TurnAuditWriter` Title-Job Problem

**Gap:** `TurnAuditWriter.enqueueTitleJob()` calls `AgentJobService` for background title generation. After Phase 2, this path has a gap.

**Reality:** Title generation is the only remaining use of `AgentJobService`.

**Recommendation:** Replace with a `TurnLifecycleListener` interface. The framework fires `onTurnCompleted(TurnResult)` after each turn. Embedding apps implement title generation as a listener. Framework ships with `NoOpTurnLifecycleListener`.

#### 3.4 Test Coverage During Refactoring

**Gap:** Phase 2 deletes ~80 test files and must adapt ~20 remaining ones. The remaining tests must provide sufficient coverage to validate the ChatService refactoring.

**Risk:** If `ChatServiceTest.java` is gutted AND the constructor changes break remaining methods, Phase 2 has zero working tests for the core turn loop.

**Mitigation:** Split Phase 2 into 2a (mechanical deletions, `ChatService` unchanged, tests adapted to new constructor) and 2b (ChatService logic refactoring). Step 2a is a safe intermediate state; step 2b is the actual refactoring.

### 4. Unified Package Structure

Synthesizing the best of all four reports:

```
io.mindspice.loom                      (or user-chosen namespace)
├── Core.java                          (public interface — facade)
├── DefaultCore.java                   (implementation)
├── CoreBuilder.java                   (builder)
│
├── api/                               (public API types — Abstractions design)
│   ├── TurnRequest.java
│   ├── TurnResponse.java
│   ├── TurnEvent.java                 (sealed hierarchy — Abstractions)
│   ├── TurnMessage.java
│   ├── ConversationSession.java
│   ├── ContextUsage.java
│   ├── PendingMessage.java
│   ├── ClaimedMessage.java
│   └── TurnDiagnostic.java
│
├── engine/                            (turn loop — from Migration structure)
│   ├── TurnEngine.java               (interface)
│   ├── DefaultTurnEngine.java        (state machine from ChatService.toolChat)
│   ├── TurnContext.java              (mutable carrier, generalized)
│   ├── TurnPhase.java
│   ├── ToolLoopGuard.java            (concrete class — Risk report)
│   ├── ToolUseAbort.java
│   └── LifecycleManager.java         (Risk report — shutdown hooks)
│
├── tool/                              (tool framework — Abstractions SPI depth)
│   ├── ToolRegistry.java
│   ├── DefaultToolRegistry.java
│   ├── ToolAccessPolicy.java
│   ├── ToolResultSummarizer.java     (replaces ToolTranscriptService switch)
│   ├── ToolTranscriptService.java
│   ├── ToolArgumentCoercionModule.java
│   └── InteractionHandler.java       (replaces InteractionQuestionTools)
│
├── model/                             (model routing — from Migration)
│   ├── ModelRouter.java
│   ├── DefaultModelRouter.java
│   ├── ModelConfig.java
│   ├── EndpointType.java
│   └── CoreConfig.java               (simplified, replaces AiConfig)
│
├── memory/                            (memory — Abstractions split)
│   ├── ConversationMemory.java       (interface)
│   ├── SessionStore.java             (interface)
│   ├── ContextManager.java           (interface)
│   ├── DefaultContextManager.java
│   ├── TokenEstimator.java           (interface)
│   ├── ContextUsageTracker.java
│   └── sqlite/
│       ├── SqliteConversationMemory.java
│       ├── SqliteSessionStore.java
│       └── SqliteSchemaInitializer.java
│
├── queue/                             (queue — Architecture design)
│   ├── MessageQueue.java
│   ├── DefaultMessageQueue.java
│   └── sqlite/
│       └── SqliteMessageQueueStore.java
│
├── prompt/                            (prompt — Abstractions pipeline)
│   ├── PromptAssembler.java
│   ├── DefaultPromptAssembler.java
│   └── PromptContributor.java        (SPI for embedding apps)
│
├── repair/                            (repair — Migration hooks)
│   ├── RepairStrategy.java
│   └── CompletionDetector.java
│
├── gate/                              (concurrency — Risk report)
│   ├── TurnGate.java
│   ├── SerialTurnGate.java
│   └── NoOpTurnGate.java
│
├── event/                             (streaming — Abstractions)
│   └── StreamLifecycle.java
│
├── audit/                             (audit — Migration)
│   ├── TurnLifecycleListener.java
│   ├── AuditWriter.java
│   └── NoOpAuditWriter.java
│
├── rendering/                         (markdown — all agree)
│   └── MarkdownRenderer.java
│
├── config/                            (Spring auto-config — Migration)
│   ├── CoreProperties.java
│   ├── CoreAutoConfiguration.java
│   └── JacksonConfiguration.java
│
└── exception/                         (exceptions — Risk report)
    ├── CoreException.java
    ├── TurnFailedException.java
    ├── ContextExhaustedException.java
    ├── ToolNotFoundException.java
    └── ConfigurationException.java
```

### 5. Merged Configuration Surface

```yaml
core:
  data-root: ${user.home}/.core-data
  
  models:
    default: default-model
    compaction: default-model
    providers:
      default-model:
        type: ollama                     # ollama | openai-compatible | deepseek
        endpoint: http://localhost:11434
        model: qwen3.6:35b
        context-length: 128000
        think-level: 0
        api-key: ""
      gpt-4o:
        type: openai-compatible
        endpoint: https://api.openai.com/v1
        model: gpt-4o
        context-length: 128000
        think-level: 0
        api-key: ${OPENAI_API_KEY}
  
  context:
    buffer-percent: 10
    compaction:
      min-tail-messages: 6
    trimming:
      enabled: true
  
  turn:
    max-tool-rounds: 50
    max-empty-response-retries: 2
    tool-guard:
      identical-call-limit: 5
      error-window-size: 8
      error-window-limit: 5
  
  queue:
    stale-claim-age: 10m
    max-queue-size: 100
  
  stream:
    timeout: 0
  
  executor:
    turn-threads: 8
    turn-queue-size: 200
    background-threads: 2
  
  model-timeout:
    openai-compatible-read-seconds: 360
```

### 6. Refined Validation Gates

Adding risk-specific gates to the Migration plan:

| Phase | Additional Gate |
|---|---|
| 1 | Grep for `simplypages`, `htmx.org`, `webjars` — zero references |
| 2a | `ChatServiceTest` passes with all `required = false` deps still present |
| 2b | All remaining tests pass; zero `PlanService`/`TaskService` imports in `ChatService` |
| 3 | Grep for `spring-boot-starter-security`, `SecurityFilterChain`, `@EnableWebSecurity` — zero results |
| 4 | `TurnEngine` unit test covers all 6 state-machine phases with mock model |
| 5 | Facade integration test: `Core.send(TurnRequest)` → `TurnResponse` round-trip with real Ollama model |
| 6 | Concurrency stress: 10 concurrent turns serialize correctly; `LifecycleManager.close()` releases all resources |

## Options

### Option A: Maximal Convergence (Recommended)
Adopt the unified package structure, configuration surface, and refined phased plan above. Combines Abstractions' rich SPI design, Migration's concrete phased approach, Architecture's file-level deletion inventory, and the Risk report's thread-safety and lifecycle hardening.

**Pros:** Most comprehensive. Addresses all identified omissions. Clean separation of concerns.
**Cons:** Largest scope. Requires the most engineering effort (~25-30 days).

### Option B: Minimal Convergence (Faster)
Adopt Architecture's single-module approach with Abstractions' facade API. Skip multi-module split and deep SPI interfaces (`TokenEstimator`, `CompactionStrategy`, `ToolResultSummarizer`). Ship concrete classes.

**Pros:** Faster to deliver (~15-20 days). Simpler for users.
**Cons:** Harder for embedding apps to customize. Less separation between transport and core.

### Option C: Staged Convergence (Pragmatic)
Follow Option A's phased plan but defer multi-module split and some SPI interfaces to v1.1. The v1.0 framework ships as a single module with concrete classes; SPI interfaces added when real embedding apps need them.

**Pros:** Balances speed and quality. Ships a working framework sooner.
**Cons:** v1.0 → v1.1 migration may break SPI consumers if the concrete-class approach changes.

**Recommendation:** Option C — Staged Convergence. The `ToolResultSummarizer` interface is the only SPI that MUST be in v1.0 because the hard-coded `switch` in `ToolTranscriptService` must be replaced during extraction.

## Trade-offs

### Single Module vs Multi-Module

| Factor | Single Module | Multi-Module |
|---|---|---|
| Build complexity | Low — one pom.xml | Medium — 4 pom.xml files |
| Dependency hygiene | Core pulls in nothing unnecessary after Phase 1 | Core has zero transport deps |
| User onboarding | One dependency | Must choose modules |
| Internal coupling risk | Higher | Lower — compilation prevents cross-module deps |
| Release versioning | One version | Multiple versions to align |

**Decision:** Multi-module for production, single module for initial extraction (Phase 4). Split into modules as Phase 5.5.

### TurnMode String Keys vs. Pluggable Hooks

| Factor | String-keyed Mode | Pluggable Hooks |
|---|---|---|
| Migration effort | Low — `PlanMode` → string is search-and-replace | High — every mode check becomes hook invocation |
| Framework purity | Carries mode constants into framework | Framework has no opinion |
| Out-of-box usefulness | Ships with sensible defaults | Every app must configure from scratch |
| Tool filtering | Direct: `filterToolsByMode(key, tools)` | Indirect: app provides policy per mode |

**Decision:** String-keyed modes with framework-defined constants as default. Apps override or extend. Balances ease of migration with framework cleanliness.

### Interface Facade vs Concrete Class

| Factor | Interface | Concrete Class | Hybrid |
|---|---|---|---|
| Testability | Easy to mock | Must mock internals | Both levels testable |
| API evolution | Adding methods breaks implementations | Backward-compatible | Sub-interface additions backward-compatible |
| Spring integration | `@Bean` returns interface | `@Bean` returns concrete | `@Bean` returns interface |

**Decision:** Interface `Core` with `DefaultCore` implementation. `CoreBuilder.build()` returns `Core`. Spring auto-config creates `DefaultCore` bean as `Core`.

### SQLite as Default vs Pluggable Persistence

| Factor | SQLite Default Only | Pluggable Interfaces |
|---|---|---|
| Implementation effort | Low — keep existing code | Medium — interface + adapter |
| User flexibility | Limited to SQLite | Any store (Postgres, Redis, in-memory) |
| Testing simplicity | Real SQLite integration tests | Can test with in-memory mocks |
| Runtime dependencies | Always pulls sqlite-jdbc | Only if using SQLite default |

**Decision:** Pluggable interfaces (`ConversationMemory`, `SessionStore`, `MessageQueueStore`) with SQLite as the shipped default. In-memory implementations for testing. This is the approach from Abstractions' memory design.

### ChatPlanState: Delete vs Metadata Map

| Factor | Delete | Map<String, Object> |
|---|---|---|
| Framework purity | Cleanest — zero app state | Carries opaque app data |
| Embedding app needs | App must track state separately | App state flows through framework |
| API surface | Smaller | One extra field on response types |

**Decision:** `Map<String, Object> metadata`. All reports agree. No conflict.

## Open Questions

1. **Framework name**: `turnloop-core`, `mindspice-loom`, or `magenta-core`? The user owns this decision. The design is independent of the name.

2. **TurnMode constants**: Should the framework ship with `"PLAN"`, `"TASK"`, `"EXECUTE_PLAN"`, `"EXECUTE_TASK"` as built-in mode strings, or should mode be completely app-defined?

3. **Model configuration format**: Should `CoreConfig` use YAML properties under `core.models.providers.*` (Spring-idiomatic) or keep the JSON file approach from `ExternalAiConfigLoader`?

4. **`JTokkitTokenCountEstimator` fallback**: Should the framework ship a simple character-count-based token estimator when JTokkit is unavailable, or should token estimation be mandatory?

5. **`InteractionQuestionTools` generalization**: Should the question-user tool stay as a Spring AI `@Tool` or become a framework-level protocol where the engine suspends the turn independently of tool invocation?

6. **Multi-module timing**: Should the `loom-transport-sse` module be created in Phase 4 or deferred to Phase 5.5?

7. **Test fixtures**: What fixtures are needed for `TurnEngine` state-machine tests? Mock `ChatModel` that returns predetermined responses with/without tool calls?

8. **`ChatSessionMetadataRepository` dead columns**: Should the framework's `SqliteSchemaInitializer` use `CREATE TABLE IF NOT EXISTS` with only 6 columns, or preserve the wider schema for backward compatibility?

## Recommended Next Step

1. **Resolve naming with user** — present three options with trade-offs.
2. **Begin Phase 1 (Web Delete)** — lowest risk, clears the ground, can start immediately.
3. **Begin Phase 2a (Orchestration mechanical deletions) in parallel with Phase 1** — independent and purely subtractive.
4. **Phase 2b (ChatService refactoring) is the serial bottleneck** — allocate the strongest engineer or subagent. The Risk report's dependency map and the Migration report's deletion inventory are the primary references.
5. **After Phase 2b completes** — Phases 3-6 can proceed with independent sub-packages developed in parallel (turn engine, tool framework, memory, queue, events, prompt, audit) once the shared API types in `api/` are stable.
6. **Defer multi-module split to Phase 5.5** — keep extraction in a single module during the high-risk refactoring, then split when stable.
