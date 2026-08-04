# Phase 14 — Magenta Re-Integration

## Context

The `turnloop-core` framework is now a fully-tested, documented, production-quality library. Magenta must be re-integrated as an embedding application that consumes the framework. All extracted code must be deleted from Magenta in favor of framework-provided equivalents. Magenta implements app-specific SPIs to restore its operational assistant behavior (mode-gated tools, plan detection, agent-scoped sessions) without the framework knowing about those concepts.

This phase is split into three sequential subphases (A, B, C). Each lettered subphase maps to exactly one sequential DeepSeek implementation agent session. Subphases must complete in order; phase-level validation and exit criteria apply only after all three subphases pass. There are no intermediate independent validation gates between subphases.

## Goal

Magenta pom.xml depends on turnloop-core; implement app-specific SPI implementations in Magenta packages: mode-gated ToolAccessPolicy plan-detecting CompletionDetector plan/task RepairStrategy agent-scoped SessionStore app-specific PromptContributor; delete all remaining extracted code now provided by framework dependency; update application.yml to framework property namespace; wire Magenta context with framework beans; verify Magenta context starts with framework dependency; verify existing non-web tests pass consuming framework; verify no extracted code remains in Magenta (class absence not import absence)

## In Scope

**Write Targets**: pom.xml, src/main/java/io/mindspice/magenta2/ai/chat, src/main/java/io/mindspice/magenta2/ai/config, src/main/resources/application.yml, src/test

- Add `turnloop-core` and `turnloop-autoconfigure` dependencies to Magenta `pom.xml`
- Delete all Magenta source code now provided by the framework (see Deletion Inventory below)
- Delete all corresponding test files for deleted classes
- Create five Magenta-specific SPI implementations in `src/main/java/io/mindspice/magenta2/ai/chat/` (see SPI Specifications below)
- Update `AiConfig.java` to produce framework `TurnLoopBuilder` and `TurnLoop` beans; remove bean definitions for deleted classes
- Update `AiUserConfigConfiguration.java` to adapt to simplified `AiConfig`
- Update `application.yml` with `turnloop:` configuration block mapping existing model config
- Adapt any surviving Magenta code that references deleted types to use framework equivalents
- Adapt surviving tests to use framework types
- Verify `mvn compile` passes, surviving tests pass, context starts with `TurnLoop` bean, no extracted code remains

## Out of Scope

- Re-creating web UI
- Playwright validation
- Full end-to-end agent workflows (plan execution, task execution, job runs)
- Migration of existing Magenta databases to framework schema
- Updating `ExternalAiConfigLoader` JSON format for framework namespace (existing config files continue to work through `AiConfig` mapping)
- Implementing the `InteractionHandler` SPI (question-user loop restored later when web endpoints return)
- Re-wiring `AgentChatPromptService`, `RequestResolver`, `ResolvedChatRequest`, or `StoredContextUsage` — these survive and adapt minimally

## Dependencies

phase-13-docs-hardening.md

## Constraints

- Magenta must compile against `turnloop-core` as a dependency
- Magenta's SPI implementations must be in `io.mindspice.magenta2.ai.chat` packages, not in the framework
- All extracted code must be absent from Magenta's source tree (verified by class absence, not import absence)
- Surviving non-web tests must continue to pass
- Context must start with auto-configured `TurnLoop` bean
- `AiConfig` must lose `agents`, `defaultAgent`, `planningModel`, `compactionModel`, `unsafeAllowWildcardShellCommands` fields (deleted in Phase 05); remaining constructors must be simplified
- `AiUserConfigConfiguration` must not reference deleted `AiConfig` fields

## Implementation Steps

### Subphase A — Dependency Addition and Code Deletion (one agent session)

Map one sequential DeepSeek implementation agent. After subphase A, Magenta compiles against framework dependencies with all extracted code removed. Do not proceed to subphase B until subphase A compile passes.

#### A.1 Add framework dependencies to Magenta pom.xml

Add under `<dependencies>`:
```xml
<dependency>
    <groupId>io.mindspice</groupId>
    <artifactId>turnloop-core</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>io.mindspice</groupId>
    <artifactId>turnloop-autoconfigure</artifactId>
    <version>${project.version}</version>
</dependency>
```

#### A.2 Delete extracted Magenta source files

Delete each of the following files from `src/main/java/io/mindspice/magenta2/`:

**ai/chat/service/** — ChatService and collaborators replaced by `TurnLoop` facade:
- `ai/chat/service/ChatService.java`
- `ai/chat/service/ChatModelRouter.java`
- `ai/chat/service/ContextManagementAdvisor.java`
- `ai/chat/service/ContextUsageTracker.java`
- `ai/chat/service/AuditService.java`
- `ai/chat/service/ChatPendingMessageService.java`
- `ai/chat/service/ChatFileService.java`
- `ai/chat/service/ChatInvocationProfile.java`
- `ai/chat/service/ToolLoopGuard.java`
- `ai/chat/service/ToolUseAbort.java`
- `ai/chat/service/TaskExecutionEvent.java`
- `ai/chat/service/TaskExecutionResult.java`
- `ai/chat/service/StoredContextUsage.java` (only if wholly duplicative of framework `ContextUsage`; otherwise adapt)

**ai/chat/service/turn/** — Phase 03 temporary SPIs replaced by framework SPIs:
- `ai/chat/service/turn/ToolAccessPolicy.java` (Magenta class, not framework SPI — replaced by framework `ToolAccessPolicy` + Magenta's `MagentaToolAccessPolicy` implementation)
- `ai/chat/service/turn/TerminalTurnRepair.java` (replaced by `MagentaRepairStrategy` implementing framework `RepairStrategy`)
- `ai/chat/service/turn/PromptContextAssembler.java` (replaced by framework `DefaultPromptAssembler` + `MagentaPromptContributor`)
- `ai/chat/service/turn/TrustedContextPolicyAssembler.java` (app-shell, deleted in Phase 05; confirm absent)
- `ai/chat/service/turn/TurnAuditWriter.java` (replaced by framework `AuditWriter` SPI)
- `ai/chat/service/turn/TurnContext.java` (replaced by framework `TurnContext`)
- `ai/chat/service/turn/TurnDiagnostic.java` (replaced by framework `TurnDiagnostic`)
- `ai/chat/service/turn/TurnPhase.java` (replaced by framework `TurnPhase`)

**ai/chat/tool/** — Tool infrastructure replaced by framework:
- `ai/chat/tool/ChatToolRegistry.java` (replaced by framework `DefaultToolRegistry`)
- `ai/chat/tool/ToolTranscriptService.java` (replaced by framework `ToolResultSummarizer` SPI)
- `ai/chat/tool/ToolArgumentCoercionModule.java` (replaced by framework `ToolArgumentCoercionModule`)
- `ai/chat/tool/InteractionQuestionTools.java` (replaced by framework `InteractionHandler` + `ask_user`)

**ai/chat/repository/** — Persistence replaced by framework SQLite defaults:
- `ai/chat/repository/ChatMemoryRepository.java`
- `ai/chat/repository/ChatSessionMetadataRepository.java`
- `ai/chat/repository/ChatPendingMessageRepository.java`
- `ai/chat/repository/AuditRepository.java`
- `ai/chat/repository/RepositoryBackedChatMemory.java`

**ai/chat/rendering/** — Replaced by framework `MarkdownRenderer`:
- `ai/chat/rendering/ChatMarkdownRenderer.java`

**ai/chat/model/** — Types replaced by framework core types. Delete only those with a direct framework equivalent; keep `ChatRequest`, `ChatResponse`, `ChatSession`, `ChatSessions`, `ChatHistory`, `ChatMessage` (adapt to framework types), `ChatFileListing`, `ChatFileSummary`:
- `ai/chat/model/ChatStreamEvent.java` (replaced by framework `TurnEvent`)
- `ai/chat/model/ChatToolActivity.java` (replaced by framework `TurnEvent.ToolActivity` + `ToolActivityData`)
- `ai/chat/model/ContextUsage.java` (replaced by framework `ContextUsage`)
- `ai/chat/model/PendingChatMessage.java` (replaced by framework `PendingMessage`)
- `ai/chat/model/ClaimedPendingChatMessage.java` (replaced by framework `ClaimedMessage`)
- `ai/chat/model/PendingMessageRequest.java` (app-specific, may stay or adapt)
- `ai/chat/model/PendingMessageAckRequest.java` (app-specific, may stay or adapt)
- `ai/chat/model/ChatPlanState.java` (deleted in Phase 05; confirm absent)
- `ai/chat/model/PlanMode.java` (deleted in Phase 05; confirm absent)
- `ai/chat/model/ChatSessionOrigin.java` (deleted in Phase 05; confirm absent)
- `ai/chat/model/ChatSessionSurface.java` (deleted in Phase 05; confirm absent)

**Keep surviving Magenta files** (adapt in subphase B):
- `ai/chat/service/AgentChatPromptService.java`
- `ai/chat/service/RequestResolver.java`
- `ai/chat/service/ResolvedChatRequest.java`
- `ai/chat/model/ChatRequest.java`
- `ai/chat/model/ChatResponse.java`
- `ai/chat/model/ChatSession.java`
- `ai/chat/model/ChatSessions.java`
- `ai/chat/model/ChatHistory.java`
- `ai/chat/model/ChatMessage.java`
- `ai/chat/model/ChatFileListing.java`
- `ai/chat/model/ChatFileSummary.java`

#### A.3 Delete extracted Magenta test files

Delete each of the following from `src/test/java/io/mindspice/magenta2/`:
- `ai/chat/service/ChatServiceTest.java`
- `ai/chat/service/ChatModelRouterTest.java`
- `ai/chat/service/ContextManagementAdvisorTest.java`
- `ai/chat/service/ChatPendingMessageServiceTest.java`
- `ai/chat/service/ChatFileServiceTest.java`
- `ai/chat/service/ToolLoopGuardTest.java`
- `ai/chat/service/turn/PromptContextAssemblerTest.java`
- `ai/chat/service/turn/ToolAccessPolicyTest.java`
- `ai/chat/service/turn/TrustedContextPolicyAssemblerTest.java`
- `ai/chat/tool/ChatToolRegistryTest.java`
- `ai/chat/tool/InteractionQuestionToolsTest.java`
- `ai/chat/tool/ToolTranscriptServiceTest.java`
- `ai/chat/tool/ToolArgumentCoercionConfigTest.java`
- `ai/chat/rendering/ChatMarkdownRendererTest.java`
- `ai/chat/repository/ChatMemoryRepositoryTest.java`
- `ai/chat/repository/ChatPendingMessageRepositoryTest.java`
- `ai/chat/repository/AuditRepositoryTest.java`
- `ai/chat/model/ChatStreamEventSerializationTest.java`

#### A.4 Update application.yml

Replace the existing `spring.ai.ollama.*` and `magenta.ai.*` blocks with a `turnloop:` namespace. Map as follows:

| Magenta config key | Framework config key |
|---|---|
| `spring.ai.ollama.base-url` | `turnloop.models.providers.ollama-local.endpoint` |
| `spring.ai.ollama.chat.options.model` | `turnloop.models.providers.ollama-local.model` |
| `magenta.ai.openai-compatible-read-timeout-seconds` | `turnloop.model-timeout.openai-compatible-read-seconds` |

Add the full configuration block:
```yaml
turnloop:
  data-root: ${magenta.root.path}/turnloop
  models:
    default: ollama-local
    providers:
      ollama-local:
        type: ollama
        endpoint: http://192.168.1.112:11434
        model: qwen3.6:35b
        context-length: 128000
        think-level: 0
  context:
    buffer-percent: 10
  turn:
    max-tool-rounds: 50
    timeout: 10m
  model-timeout:
    openai-compatible-read-seconds: 360
```

Remove from `application.yml`:
- The `spring.ai.ollama.*` block (framework `TurnLoopAutoConfiguration` handles Ollama client creation)
- `magenta.ai.openai-compatible-read-timeout-seconds`

Keep:
- `magenta.root.path` (still used by Magenta's non-chat infrastructure)
- `spring.datasource.*` (Magenta still owns its own SQLite database for non-chat tables)

#### A.5 Subphase A verification

Run `mvn compile -pl .` from the Magenta module. Fix any compilation errors caused by surviving code referencing deleted types (adapt imports to framework equivalents; do not implement SPI logic yet). The compile must pass before subphase B begins.

---

### Subphase B — SPI Implementation and Configuration Wiring (one agent session)

Map one sequential DeepSeek implementation agent. After subphase B, Magenta compiles, its SPI implementations are complete, its configuration produces a `TurnLoop` bean, and surviving non-test code is adapted. Do not proceed to subphase C until subphase B compile and basic bean-wiring pass.

#### B.1 Create MagentaToolAccessPolicy

File: `src/main/java/io/mindspice/magenta2/ai/chat/MagentaToolAccessPolicy.java`

Implements `io.mindspice.turnloop.spi.ToolAccessPolicy`. Must carry forward the mode-gated logic from the deleted `ToolAccessPolicy.filterToolsByMode()`:

- Accept `PlanService` and `TaskService` as constructor dependencies (these survive Phase 05 deletion).
- Accept `AgentSkillRuntimeCatalogService` as optional constructor dependency.
- `filterTools(List<ToolCallback>, TurnContext)` implementation:
  1. Determine `PlanMode` from session metadata in `TurnContext`. Use `TurnContext.getAttribute()` with a Magenta-defined `AttributeKey<PlanMode>`. If absent, fall back to `PlanMode.NORMAL`.
  2. Route to mode-specific allowlist/blocklist:
     - `PLAN`: allow only `PLAN_MODE_TOOLS` names, filter `agent_*` operational tools, hide `activate_skill` when no skills available
     - `TASK`: allow only `TASK_MODE_TOOLS` names, same filtering
     - `EXECUTE_PLAN` / `EXECUTE_TASK`: allow all except `EXECUTION_BLOCKED_TOOLS` names
     - `NORMAL`: allow all except `NORMAL_BLOCKED_TOOLS` names
  3. Return filtered list.
- Port the tool-name allowlists/blocklists from the deleted `ToolAccessPolicy` class verbatim.
- Port `hideSkillActivationWhenUnavailable` and `withoutOperationalTools` helper methods.
- Provide static factory `modeGated(PlanService, TaskService)` for use in `AiConfig`.

#### B.2 Create MagentaCompletionDetector

File: `src/main/java/io/mindspice/magenta2/ai/chat/MagentaCompletionDetector.java`

Implements `io.mindspice.turnloop.spi.CompletionDetector`. Detects plan/task turn completion:

- `isComplete(TurnContext context)` returns `true` when the model's last response has a non-null assistant text AND zero pending tool calls.
- Additionally check TurnContext for Magenta-specific completion signals: if the last tool call was `plan_ready_for_approval`, `plan_complete`, `task_ready_for_approval`, `task_complete`, or `ask_user_questions`, the turn is complete.
- Use `TurnContext` attributes to carry plan/task state across the turn (set by `MagentaPromptContributor` in the prompt phase).

#### B.3 Create MagentaRepairStrategy

File: `src/main/java/io/mindspice/magenta2/ai/chat/MagentaRepairStrategy.java`

Implements `io.mindspice.turnloop.spi.RepairStrategy`. Carries forward `TerminalTurnRepair` logic:

- Accept `PlanService` and `TaskService` as constructor dependencies.
- `repair(TurnContext context, RepairReason reason)`:
  - For `EMPTY_RESPONSE`: return mode-specific empty-final-response control messages (port from `TerminalTurnRepair.emptyFinalResponseControlMessage()`).
  - For `TOOL_LOOP_EXCEEDED`: return tool-use-abort control message (port from `TerminalTurnRepair.toolUseAbortControlMessage()`).
  - For `COMPLETION_NOT_DETECTED`: check if plan/task turn needs repair via `needsPlanTurnRepair()` / `needsExecutionCompletionRepair()` equivalents, return appropriate control message.
  - For `CUSTOM`: return empty list.
- Port control message text verbatim from `TerminalTurnRepair`.

#### B.4 Create MagentaPromptContributor

File: `src/main/java/io/mindspice/magenta2/ai/chat/MagentaPromptContributor.java`

Implements `io.mindspice.turnloop.spi.PromptContributor`. Annotate with `@Order(0)` or implement `Ordered` returning 0. This contributor injects agent identity, workspace context, and `AGENTS.md` content before other contributors:

- Accept `AgentChatPromptService` (surviving Magenta service) as constructor dependency.
- `contribute(TurnContext context)`:
  1. Extract `agentId` and `conversationId` from `TurnContext` attributes or metadata.
  2. Call `AgentChatPromptService` to get agent identity block.
  3. Resolve `AGENTS.md` content from workspace (use surviving `AgentsMdResolver` if available, or delegate).
  4. Return concatenated prompt layer.
- Set Magenta-specific attributes on `TurnContext` that downstream SPIs need: mode (`PlanMode`), agent identity, allowed tool names, conversation id.

#### B.5 Create MagentaSessionStore

File: `src/main/java/io/mindspice/magenta2/ai/chat/MagentaSessionStore.java`

Extends `io.mindspice.turnloop.persistence.SqliteSessionStore`. Adds agent-scoped query method:

- Override `list(SessionFilter filter)` to additionally filter by agent id stored in `metadata` JSON column.
- Add `listByAgent(String agentId, SessionFilter filter)` method.
- The `metadata` column carries `agent_id` and other Magenta-specific keys.

#### B.6 Update AiConfig.java

Simplify `AiConfig` record:
- Remove fields already deleted in Phase 05 (`agents`, `defaultAgent`, `planningModel`, `compactionModel`, `unsafeAllowWildcardShellCommands`). Confirm they are absent; if any survive, delete them.
- Keep: `defaultModel`, `summaryModel`, `summeryModel`, `contextBufferPercent`, `dataRoot`, `webSearch`, `models`.
- Simplify constructors to remove deleted parameters.

Add `@Configuration` class or `@Bean` methods in `AiUserConfigConfiguration.java`:
```java
@Bean
TurnLoopBuilder turnLoopBuilder(ApplicationContext context, AiConfig aiConfig) {
    TurnLoopBuilder builder = TurnLoopBuilder.create(context);
    // Map AiConfig.models to framework ModelConfig and register each
    for (var entry : aiConfig.models().entrySet()) {
        ModelConfig mc = entry.getValue();
        builder.model(entry.getKey(), ModelConfig.builder()
            .type(mc.type())
            .endpoint(mc.endpoint())
            .model(mc.model())
            .contextLength(mc.contextLength())
            .thinkLevel(mc.thinkLevel())
            .apiKey(mc.apiKey() != null ? mc.apiKey() : "")
            .build());
    }
    builder.defaultSystemPrompt("You are a helpful assistant.");
    return builder;
}

@Bean
TurnLoop turnLoop(TurnLoopBuilder builder,
                   MagentaToolAccessPolicy toolAccessPolicy,
                   MagentaCompletionDetector completionDetector,
                   MagentaRepairStrategy repairStrategy,
                   MagentaPromptContributor promptContributor,
                   MagentaSessionStore sessionStore) {
    return builder
        .toolAccessPolicy(toolAccessPolicy)
        .completionDetector(completionDetector)
        .repairStrategy(repairStrategy)
        .promptContributor(promptContributor)
        .sessionStore(sessionStore)
        .build();
}
```

Remove any `@Bean` definitions for deleted classes (`ChatService`, `ChatToolRegistry`, `ChatModelRouter`, etc.).

#### B.7 Adapt surviving Magenta code

For each surviving file, update imports to use framework equivalents:

| Deleted Magenta type | Framework replacement |
|---|---|
| `ChatService` | `TurnLoop` (inject via `@Autowired`) |
| `ChatMessage` / `TurnMessage` (Magenta) | `io.mindspice.turnloop.TurnMessage` |
| `ChatStreamEvent` | `io.mindspice.turnloop.TurnEvent` |
| `ContextUsage` (Magenta) | `io.mindspice.turnloop.ContextUsage` |
| `PendingChatMessage` | `io.mindspice.turnloop.PendingMessage` |
| `ClaimedPendingChatMessage` | `io.mindspice.turnloop.ClaimedMessage` |
| `ChatToolRegistry` | `ToolRegistry` (framework SPI, inject `TurnLoop.tools()`) |
| `ChatMarkdownRenderer` | `io.mindspice.turnloop.MarkdownRenderer` |
| `ToolLoopGuard` (Magenta) | `io.mindspice.turnloop.ToolLoopGuard` |
| `ToolAccessPolicy` (Magenta) | `io.mindspice.turnloop.spi.ToolAccessPolicy` (interface) + `MagentaToolAccessPolicy` (impl) |
| `TerminalTurnRepair` | `io.mindspice.turnloop.spi.RepairStrategy` (interface) + `MagentaRepairStrategy` (impl) |
| `PromptContextAssembler` | `io.mindspice.turnloop.spi.PromptContributor` (interface) + `MagentaPromptContributor` (impl) |
| `TurnContext` (Magenta) | `io.mindspice.turnloop.TurnContext` |
| `TurnDiagnostic` (Magenta) | `io.mindspice.turnloop.TurnDiagnostic` |
| `TurnPhase` (Magenta) | `io.mindspice.turnloop.TurnPhase` |
| `ChatToolActivity` | `io.mindspice.turnloop.ToolActivityData` |
| `ToolTranscriptService` | `ToolResultSummarizer` (framework SPI) |

Key adaptation patterns:
- `AgentChatPromptService`: update to use `TurnLoop` instead of `ChatService` for any chat-related operations, or if it only builds prompt text (likely), keep it as-is and inject into `MagentaPromptContributor`.
- `RequestResolver` / `ResolvedChatRequest`: update any `ChatService` references to `TurnLoop`.
- `ChatSession`, `ChatSessions`, `ChatHistory`: these are Magenta's API response types. They may continue to exist but must adapt how they source data — from `TurnLoop.sessions().list()` instead of `ChatSessionMetadataRepository`.

#### B.8 Subphase B verification

Run `mvn compile -pl .` and fix all compilation errors. Run `mvn test -pl . -Dtest="!*Web*"` (or the surviving test set). Fix test compilation errors (adapt imports) but do not yet fix test logic failures — that's subphase C scope. The surviving source compiles and the `TurnLoop` bean wiring is structurally correct.

---

### Subphase C — Test Adaptation and Final Verification (one agent session)

Map one sequential DeepSeek implementation agent. After subphase C, all phase exit criteria are met.

#### C.1 Adapt surviving tests

For each surviving test file (those not deleted in subphase A.3):
- Update imports from deleted Magenta types to framework equivalents using the replacement table in B.7.
- Rewrite test setup: replace `ChatService` mocking with `TurnLoop` mocking, replace Magenta `TurnContext` with framework `TurnContext`, replace `ChatToolRegistry` with `ToolRegistry`.
- Fix test assertions to use framework types (`TurnMessage.assistant(...)` instead of Magenta's `ChatMessage`).
- Tests that exercised deleted app-shell behavior (`planService.mode()`, `taskService.mode()`) must be adapted to set `TurnContext` attributes instead, or deleted if the behavior is now tested through SPI unit tests (not present in this phase).

Tests likely surviving and needing adaptation:
- `RequestResolverAgentBindingTest.java`
- Any non-web controller tests outside `ai/chat/`

#### C.2 Verify no extracted code remains

Run class-absence grep (not import-absence):
```bash
# Must return zero results for each deleted class
grep -r "class ChatService" src/main/java/
grep -r "class ChatToolRegistry" src/main/java/
grep -r "class ChatModelRouter" src/main/java/
grep -r "class ChatMarkdownRenderer" src/main/java/
grep -r "class ContextManagementAdvisor" src/main/java/
grep -r "class ContextUsageTracker" src/main/java/
grep -r "class AuditService" src/main/java/
grep -r "class ChatPendingMessageService" src/main/java/
grep -r "class ToolTranscriptService" src/main/java/
grep -r "class ChatMemoryRepository" src/main/java/
grep -r "class ChatSessionMetadataRepository" src/main/java/
grep -r "class ChatPendingMessageRepository" src/main/java/
grep -r "class AuditRepository" src/main/java/
grep -r "class RepositoryBackedChatMemory" src/main/java/
grep -r "class TerminalTurnRepair" src/main/java/
grep -r "class ToolLoopGuard" src/main/java/  # Magenta's, not framework's
```

Also verify: `grep -r "io.mindspice.magenta2.ai.chat.service.turn.ToolAccessPolicy" src/main/java/` returns zero (no import of deleted Magenta `ToolAccessPolicy` class — only framework `io.mindspice.turnloop.spi.ToolAccessPolicy` allowed).

#### C.3 Verify surviving tests pass

```bash
mvn test -pl .
```
All surviving tests must pass. If any test fails due to unimplemented SPI behavior, implement the missing SPI logic in the relevant Magenta SPI class (subphase B files). Do not stub or skip.

#### C.4 Verify context starts with TurnLoop bean

```bash
timeout 30s mvn spring-boot:run -pl . -Dspring-boot.run.arguments=--server.port=0
```
Check startup logs:
- `TurnLoopAutoConfiguration` registers beans
- `TurnLoop` bean is created (log message or health indicator)
- `TurnLoopHealthIndicator` reports healthy (or details if Ollama is unreachable — unhealthy on connection refused is acceptable; crash is not)
- No `ClassNotFoundException` or `NoSuchBeanDefinitionException` for deleted classes

#### C.5 Subphase C verification

All of C.2, C.3, and C.4 must pass.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoop.java` — framework facade API
- `turnloop-core/src/main/java/io/mindspice/turnloop/spi/ToolAccessPolicy.java` — SPI interface
- `turnloop-core/src/main/java/io/mindspice/turnloop/spi/CompletionDetector.java` — SPI interface
- `turnloop-core/src/main/java/io/mindspice/turnloop/spi/RepairStrategy.java` — SPI interface
- `turnloop-core/src/main/java/io/mindspice/turnloop/spi/PromptContributor.java` — SPI interface
- `turnloop-core/src/main/java/io/mindspice/turnloop/persistence/SqliteSessionStore.java` — base class for `MagentaSessionStore`
- `turnloop-core/src/main/java/io/mindspice/turnloop/TurnLoopBuilder.java` — builder API
- `turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure/TurnLoopProperties.java` — configuration property keys
- `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md` — Appendix A (SPI signatures), Appendix C (Phase 7 gate inventory)

## Technical Guidance

- Magenta's `AiConfig.models` values are `ModelConfig` records with fields `type`, `endpoint`, `model`, `contextLength`, `thinkLevel`, `apiKey`. Map these directly to framework `ModelConfig` builder fields.
- `MagentaSessionStore` extends `SqliteSessionStore` and overrides `list()` to add `WHERE json_extract(metadata_json, '$.agent_id') = ?` filtering when an agent id is present in the `SessionFilter` metadata or a subclass filter.
- The `TurnLoop` bean is the new entry point for chat turns. Existing `RequestResolver` should produce `TurnRequest` objects instead of the old `ChatRequest` flow.
- `MagentaPromptContributor` must set `TurnContext` attributes during `contribute()` that downstream SPIs (`MagentaToolAccessPolicy`, `MagentaCompletionDetector`, `MagentaRepairStrategy`) consume. Define `AttributeKey` constants in a shared Magenta class, e.g. `MagentaAttributeKeys.PLAN_MODE`, `MagentaAttributeKeys.AGENT_ID`, `MagentaAttributeKeys.CONVERSATION_ID`.
- Framework `TurnLoopBuilder` auto-discovers `PromptContributor` beans from the Spring context via `@Order`. Ensure `MagentaPromptContributor` is annotated `@Component` with `@Order(0)`.
- Some Magenta tests may need `@SpringBootTest` with `turnloop-autoconfigure` on the classpath (it will be via the Maven dependency).
- `AiUserConfigConfiguration` currently reads `app.ai.config-path`. This config file contains the JSON-serialized `AiConfig` with model definitions. Keep this loading mechanism; the `TurnLoopBuilder` bean reads from the loaded `AiConfig` record.

## Validation

- Magenta `pom.xml` depends on `turnloop-core` and `turnloop-autoconfigure`
- Magenta has five SPI implementations in its own packages (`MagentaToolAccessPolicy`, `MagentaCompletionDetector`, `MagentaRepairStrategy`, `MagentaPromptContributor`, `MagentaSessionStore`)
- No extracted code remains in Magenta source tree (verified by class-absence grep, not import-absence)
- `application.yml` has `turnloop:` configuration block with model provider mapping
- `mvn compile -pl .` passes
- Magenta's surviving non-web tests pass (`mvn test -pl .`)
- Magenta context starts with `TurnLoop` bean (`mvn spring-boot:run`)
- `TurnLoopHealthIndicator` reports status (healthy or details; must not crash)

## Exit Criteria

- Magenta compiles, tests pass, and starts as an embedding application consuming turnloop-core
- Magenta's five SPI implementations provide app-specific behavior preserving the operational assistant's mode-gated tool filtering, plan/task completion detection, mode-specific repair messages, agent identity prompt injection, and agent-scoped session queries
- All extracted code deleted from Magenta (class absence verified)
- All deleted classes' tests deleted
- Configuration migrated to `turnloop:` namespace
- `TurnLoop` bean is the chat entry point, wired with Magenta's SPI implementations
