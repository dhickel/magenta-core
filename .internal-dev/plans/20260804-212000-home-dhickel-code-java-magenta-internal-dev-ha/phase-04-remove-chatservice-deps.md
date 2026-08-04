# Phase 04 — Remove ChatService Dependencies

## Context

Phase 03 created four temporary SPI interfaces (`CompletionDetector`, `InteractionHandler`, `PromptContributor`, `RepairStrategy`) under `ai.chat.service.turn` and adapted `PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`, and `TurnAuditWriter` to depend only on core services and these SPIs. All plan/task/agent/skills/workspace/measurement references in those helper classes were removed in Phase 03.

`ChatService.java` (currently ~3009 lines) still holds 13 distinct app-shell collaborator fields plus a `ThreadLocal<TrustedPolicyResult>`, a `streamLocks` `ConcurrentHashMap`, and five overloaded constructors with `@Autowired(required = false)` annotations. Public orchestration methods for plan lifecycle, task execution, and agent chat are still present but have no callers (web controllers deleted in Phase 02).

This phase severs every remaining app-shell reference from `ChatService`, collapsing it to a single constructor that depends only on core services and the Phase 03 SPI interfaces.

## Goal

Remove all 13 app-shell collaborator references from ChatService; eliminate @Autowired(required=false) and ThreadLocal; collapse to single constructor; update ChatServiceTest

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/test/java/io/mindspice/magenta2/ai/chat/service/ChatServiceTest.java

### Collaborators to Remove (stay/go disposition)

| Collaborator | Disposition | Replacement |
|---|---|---|
| `PlanService` | Remove | Deleted methods + `CompletionDetector`/`RepairStrategy` SPI calls |
| `TaskService` | Remove | Deleted methods + `CompletionDetector`/`RepairStrategy` SPI calls |
| `AgentJobService` | Remove | Return `null` from `conversationTitleJobStatus()` |
| `AgentContextService` (setter) | Remove | `PromptContributor` SPI (via `PromptContextAssembler`) |
| `WorkspaceDirectoryService` (setter) | Remove | `PromptContributor` SPI (via `PromptContextAssembler`) |
| `AgentSkillActivationService` | Remove | Delete activation-clear call from `clearConversation()` |
| `RunMeasurementRepository` | Remove | Delete all measurement instrumentation methods |
| `TrustedContextPolicyAssembler` / `TrustedPolicyResult` | Remove | `PromptContributor` SPI (via `PromptContextAssembler`) |
| `ChatFileService` | Remove | Return `0` from `session()` output count |
| `ChatPendingMessageService` | Remove | Delete queue-clear call from `clearConversation()` |
| `OrchestrationTaskContext` / `OrchestrationTaskContextHolder` | Remove | Delete all context-install/restore blocks; remove from tool-loop |
| `RuntimeSettingsService` | Remove | Replace with `AiConfig` direct access |
| `AgentsMdResolver` | Remove | Removed via `PromptContextAssembler` (cleaned in Phase 03) |
| `WorkTypeProfileService` | Remove | Removed via `PromptContextAssembler` (cleaned in Phase 03) |
| `AgentSkillRuntimeCatalogService` / `AgentSkillPromptCatalogAssembler` | Remove | Removed via `ToolAccessPolicy`/`PromptContextAssembler` (cleaned in Phase 03) |
| `ConversationTurnCoordinator` | Remove | Simplify `chat()`/`stream()` to direct calls |
| `AuditService` (field) | Remove | `TurnAuditWriter` already wraps audit concerns (cleaned in Phase 03) |
| `RequestResolver` (field) | Remove | Inline `resolve()` logic or keep as simplified core-utils field |
| `ActiveTurn` / `ActiveTurnPhase` (imports) | Remove | Delete `chatAsAgent()`, `streamTaskExecution()` — these are the only callers |

### Collaborators that Stay

- `ChatMemory`, `ChatMemoryRepository`, `ChatSessionMetadataRepository`, `ChatMarkdownRenderer`, `AiConfig`
- `ContextManagementAdvisor`, `ContextUsageTracker`, `ChatModelRouter`, `ToolCallingManager`
- `ChatToolRegistry`, `ToolTranscriptService`, `AuditRepository`, `ObjectMapper`
- Phase 03 SPIs: `PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`, `TurnAuditWriter`
- Phase 03 SPI interfaces: `CompletionDetector`, `InteractionHandler`, `PromptContributor`, `RepairStrategy`

## Out of Scope

- Modifying `PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`, or `TurnAuditWriter` (cleaned in Phase 03)
- Deleting app-shell packages from disk (Phase 05)
- Moving types to `io.mindspice.turnloop` packages (Phase 06+)
- Changing turn-loop state machine behavior (only removing mode-aware branches)
- Implementing the full framework SPIs (these are temporary Phase 03 adapters)
- `RequestResolver` class rewrite (delete the field; inline the minimal `resolve()` logic ChatService needs)

## Dependencies

phase-03-create-spi-interfaces.md

## Constraints

- `ChatServiceTest` must continue to pass after all changes
- Zero `@Autowired(required = false)` annotations must remain in `src/main/java/`
- Zero `ThreadLocal` fields must remain in `ChatService`
- App-shell packages are NOT deleted yet — they still exist on disk but are no longer referenced by `ChatService`
- `ChatService` must have exactly one constructor by end of phase
- `PlanMode`, `ChatPlanState`, `PlanToolContext`, `PlanToolExecutionContext`, `TaskRun`, `TaskRunStatus`, `AgentJobStatus` must have zero imports in `ChatService.java`
- `OrchestrationTaskContext`, `OrchestrationTaskContextHolder`, `TrustedContextPolicyAssembler`, `TrustedPolicyResult` must have zero imports in `ChatService.java`
- `ActiveTurn`, `ActiveTurnPhase`, `ConversationTurnCoordinator`, `MagentaWorkRequest` must have zero imports in `ChatService.java`
- `AgentContextService`, `WorkspaceDirectoryService`, `WorkspaceDescriptor`, `WorkspaceKind` must have zero imports in `ChatService.java`
- The turn-loop state machine (6-state cycle: PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) must preserve its existing behavior except that mode-specific branches are replaced by SPI calls
- `PlanMode` field removal from `ToolLoopState` must not alter the control flow for normal (non-plan) turns

## Implementation Steps

This phase is split into three sequential subphases (A, B, C). Each subphase maps to one sequential DeepSeek implementation agent session. Subphases must complete in order; phase-level validation applies only after all three subphases pass their individual gates.

### Subphase A — Strip Public Orchestration API

Delete all plan/task/agent orchestration public methods and their private helpers. After this subphase, `ChatService` offers only the core chat API: `chat()`, `stream()`, `clearConversation()`, `history()`, `listSessions()`, and utility methods.

#### A.1 Delete plan lifecycle methods

Remove these public methods and all their `private` helpers:
- `beginPlan()` (3 overloads)
- `beginPlanFromDefinition()` (2 overloads)
- `exitPlan()`
- `submitPlanAnswer()` (2 overloads)
- `approvePlan()`
- `continuePlanning()`
- `executeSavedPlan()` (2 public + 1 private overload)
- `resolveSavedPlanExecution()` (2 overloads)
- `handlePlanExecutionStreamFinished()` (2 overloads)
- `recordExecutionFailure()`
- `planState()`
- `requirePlanService()`
- Private helpers: `approvedPlanExecutionMessage()`, `isRecoverablePlanningAnswerConflict()`, `savedAnswerFailureResponse()`, `ensureRecoverablePlanningPrompt()`, `isValidatedPlanCompletion()`, `persistControlledReviewMessage()`, `markPlanNeedsReviewResponse()`, `resolvedPlanningModel()`, `storedConversationPlanningModel()`

#### A.2 Delete task execution methods

Remove:
- `executeTaskBlocking()`
- `streamTaskExecution()`
- `resolveTaskExecution()` (private)
- `requireTaskService()`
- Inner record `ResolvedTaskExecution`

#### A.3 Delete agent-chat methods

Remove:
- `chatAsAgent()`
- `markAgentConversation()`
- `installChatFileContextIfNeeded()` (private, depends on `PlanService` + `OrchestrationTaskContextHolder`)
- `restoreContext()` (private, depends on `OrchestrationTaskContext`)
- `rejectSystemChatConversation()` can stay (depends only on `ChatSessionMetadataRepository` which stays)

#### A.4 Delete measurement instrumentation

Remove all measurement methods (these use `RunMeasurementRepository` + `OrchestrationTaskContextHolder`):
- `reserveMeasurement()`
- `reserveToolMeasurements()`
- `completeModelMeasurement()`
- `completeModelMeasurementFailure()`
- `completeToolMeasurements()`
- `failToolMeasurements()`
- `toolResponseOutcomes()` (private static helper)
- `intFromMap()` (private static helper)

Remove all calls to `reserveMeasurement` / `completeModelMeasurement` etc. from `handleInvokeModel()` and `handleExecuteTools()`. The measurement calls are fire-and-forget, so removing them has zero effect on the turn-loop state machine.

#### A.5 Remove imports for deleted collaborator types

Remove all imports referencing:
- `io.mindspice.magenta2.ai.chat.plan.*` (PlanService, PlanDefinition, PlanStatus, PlanToolContext, PlanToolExecutionContext)
- `io.mindspice.magenta2.ai.chat.task.*` (TaskService, TaskRun, TaskRunStatus)
- `io.mindspice.magenta2.ai.agent.job.*` (AgentJobService, AgentJobStatus)
- `io.mindspice.magenta2.ai.orchestration.runtime.*` (OrchestrationTaskContext, OrchestrationTaskContextHolder, RunMeasurementRepository + inner types)
- `io.mindspice.magenta2.ai.execution.*` (ActiveTurnPhase, ActiveTurn, ConversationTurnCoordinator, MagentaWorkRequest)
- `io.mindspice.magenta2.ai.chat.model.ChatPlanState`, `io.mindspice.magenta2.ai.chat.model.PlanMode`
- `io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin` (only needed by deleted `rejectSystemChatConversation` — keep if still used elsewhere)

#### A.6 Simplify `clearConversation()`

Remove plan/task/skills/pending-message cleanup branches. After this step, `clearConversation()` clears only `chatMemory` + `chatSessionMetadataRepository` + `contextUsageTracker` (all staying core services).

#### A.7 Simplify `listSessions()` and `listAgentSessions()`

Remove `interactionMode()` filter and `PlanMode.NORMAL` check. Remove `listAgentSessions()` (no callers after web deletion). Remove `markAgentConversation()` (already deleted in A.3).

#### A.8 Simplify `session()` and `conversationTitleJobStatus()`

- `session()`: replace `chatFileService.countFiles()` call with `0`
- `conversationTitleJobStatus()`: return `null` (was using `AgentJobService`)
- `conversationTitle()`: remove `planService` fallback for plan-derived titles

#### Subphase A Gate

```bash
mvn compile -pl . 2>&1 | grep -c ERROR
# Must be 0
```
Verify no imports of PlanService, TaskService, AgentJobService, OrchestrationTaskContext, OrchestrationTaskContextHolder, ActiveTurn, ConversationTurnCoordinator, RunMeasurementRepository in ChatService.java.

---

### Subphase B — Strip App-Shell Wiring and Collapse Constructors

Remove ThreadLocal, streamLocks, ConversationTurnCoordinator, trusted-policy wiring, and all `@Autowired(required = false)` fields. Collapse to single canonical constructor. Adapt tool-loop state machine to remove `PlanMode`.

#### B.1 Remove trusted-policy wiring

Delete:
- `ThreadLocal<TrustedPolicyResult> activeTrustedPolicies` field
- `installTrustedPolicy()`
- `restoreTrustedPolicy()`
- `resolveTrustedPolicy()`
- Inner class `TrustedContextUnavailableException`
- `setTrustedContextServices()` setter method
- Fields `agentContextService` and `workspaceDirectoryService`

In `effectiveSystemPrompt()`, replace the `TrustedPolicyResult` branch with a direct call to `promptAssembler.assembleTurnInstructions(request, defaultSystemPrompt())` — the `PromptContributor` SPI (wired via `promptAssembler`) now owns all context injection. Simplified code:

```java
String effectiveSystemPrompt(ResolvedChatRequest request) {
    return promptAssembler.assembleTurnInstructions(request, defaultSystemPrompt())
        .stream()
        .filter(SystemMessage.class::isInstance)
        .map(Message::getText)
        .collect(Collectors.joining("\n"));
}
```

In `chatNow()` and `streamNow()`, remove `OrchestrationTaskContext previousContext` capture, `installChatFileContextIfNeeded()` call, `TrustedPolicyResult previousPolicy` capture, and the `try/finally` restore blocks. After this, `chatNow()` and `streamNow()` are simple delegators with no context management.

#### B.2 Remove streamLocks and ConversationTurnCoordinator

Delete:
- `Map<String, Semaphore> streamLocks` field
- `ConversationTurnCoordinator turnCoordinator` field

In `chat()` (the private `chat(ResolvedChatRequest, List<String>, OrchestrationTaskContext)` overload): remove the `turnCoordinator` branch. Simplify to call `chatNow()` directly without the `await(turnCoordinator.submit(...))` wrapping.

In `stream()` (the `stream(ResolvedChatRequest, ActiveTurn)` overload): remove the `turnCoordinator` + `streamLocks` branch. Simplify to call `streamNow()` directly.

Delete the `await()` and `unwrap()` private helper methods (only used by `turnCoordinator` path).

#### B.3 Remove remaining app-shell fields and simplify constructors

Delete these fields from the class (their usages were already removed in Subphase A or earlier B steps):
- `planService`, `taskService`, `agentJobService`
- `runtimeSettingsService`
- `auditService` (field; `TurnAuditWriter` already owns audit concerns)
- `requestResolver`
- `chatFileService`
- `chatPendingMessageService`
- `agentSkillActivationService`
- `measurementRepo`
- `turnCoordinator` (already removed in B.2)

Delete all five overloaded constructors. Create a single canonical constructor:

```java
public ChatService(
    ChatMemory chatMemory,
    ChatMemoryRepository chatMemoryRepository,
    ChatSessionMetadataRepository chatSessionMetadataRepository,
    ChatMarkdownRenderer chatMarkdownRenderer,
    AiConfig aiConfig,
    ContextManagementAdvisor contextManagementAdvisor,
    ContextUsageTracker contextUsageTracker,
    ChatModelRouter chatModelRouter,
    ToolCallingManager toolCallingManager,
    ChatToolRegistry chatToolRegistry,
    ToolTranscriptService toolTranscriptService,
    AuditRepository auditRepository,
    ObjectMapper objectMapper,
    PromptContextAssembler promptAssembler,
    ToolAccessPolicy toolAccessPolicy,
    TerminalTurnRepair turnRepair,
    TurnAuditWriter turnAuditWriter
) {
    // Direct field assignment. No null-checks transform; nullable fields documented.
}
```

Fields `contextManagementAdvisor`, `contextUsageTracker`, `chatModelRouter`, `toolCallingManager`, `chatToolRegistry`, `toolTranscriptService`, `auditRepository` may be `null` in lightweight test fixtures — keep existing null-guards in calling code. `ObjectMapper` defaults to `new ObjectMapper()` when null.

#### B.4 Adapt `defaultModel()` and `planningModel()` to remove RuntimeSettingsService

Replace `runtimeSettingsService.defaultModel()` / `runtimeSettingsService.planningModel()` with direct `AiConfig` resolution:

```java
public String defaultModel() {
    if (aiConfig != null && StringUtils.hasText(aiConfig.resolvedDefaultModelKey())) {
        return aiConfig.resolvedDefaultModelKey();
    }
    String defaultAgentName = aiConfig.defaultAgent();
    return aiConfig.agents().get(defaultAgentName).model();
}
```

Remove `planningModel()` entirely (only used by deleted plan methods). Remove `modelSelectionKey()` `runtimeSettingsService` branch.

#### B.5 Adapt `approvedTools()` to remove RuntimeSettingsService branch

Remove the `runtimeSettingsService.approvedTools()` branch. Keep the `AiConfig` default-agent path only.

#### B.6 Adapt tool-loop state machine: remove PlanMode

This is the most critical edit. The `ToolLoopState` inner class carries `PlanMode mode` which drives decisions throughout the 6-state cycle. All mode-aware branches must be replaced by SPI calls.

**Remove from `ToolLoopState`:**
- Replace `PlanMode mode` field with a simplified `boolean isExecutionMode` flag (derived from `CompletionDetector`/`ToolAccessPolicy` state, kept for the `EXECUTE_TOOLS` phase's completion-detection paths only — see mapping below)
- Delete `int planTurnRepairRetries` and `int executionCompletionRepairRetries` — repair retries are now unified

**Remove from `toolChat()` entry point:**
- Delete `PlanMode mode = interactionMode(request.conversationId())`
- Delete `PlanToolExecutionContext.set(new PlanToolContext(...))` call and the `finally { PlanToolExecutionContext.clear(); }` block

**Branch mapping table — replace each mode-dependent code path:**

| Current location | Current logic | Replacement |
|---|---|---|
| `EVALUATE`: `requiresPlanTurnRepair(s.request.conversationId(), s.mode)` | Calls `turnRepair.needsPlanTurnRepair()` which checks `planService.mode() == PLAN` | Replace with `turnRepair.needsRepair(s.request.conversationId(), RepairReason.EMPTY_RESPONSE)` — `TurnRepair` SPI already owns this logic (Phase 03) |
| `EVALUATE`: `requiresExecutionCompletionRepair(s.request.conversationId(), s.mode)` | Calls `turnRepair.needsExecutionCompletionRepair()` which checks `planService.mode() == EXECUTE_PLAN` or `taskService.mode() == EXECUTE_TASK` | Replace with `turnRepair.needsRepair(s.request.conversationId(), RepairReason.CUSTOM)` — SPI owns decision |
| `EXECUTE_TOOLS`: `s.mode == PlanMode.EXECUTE_PLAN && planService != null && planService.mode(...) != PlanMode.EXECUTE_PLAN` | Detects plan_complete tool call by observing Mode transition | Replace with `completionDetector.isComplete(turnContext)` call — `CompletionDetector` SPI signals completion |
| `EXECUTE_TOOLS`: `s.mode == PlanMode.EXECUTE_TASK && taskService != null && taskService.mode(...) != PlanMode.EXECUTE_TASK` | Detects task_complete tool call by observing Mode transition | Replace with `completionDetector.isComplete(turnContext)` call — same SPI |
| `EXECUTE_TOOLS`: `s.mode == PlanMode.EXECUTE_TASK && taskService != null ? taskService.runIdForConversation(...) : null` in `PlanToolContext` constructor | Injected runId for task context | Delete — `PlanToolContext` is removed entirely |
| `FINALIZE`: `requiresPlanTurnRepair(...)` | Forces planning question if plan turn incomplete | Replace with `turnRepair.needsRepair(...)` — SPI decision |
| `FINALIZE`: `s.mode == PlanMode.EXECUTE_PLAN && planService.mode(...) == PlanMode.EXECUTE_PLAN` | Marks needs-review when execution didn't complete | Replace with `completionDetector.isComplete(turnContext)` guard — if not complete, mark via `turnRepair` |
| `REPAIR`: `requiresPlanTurnRepair(...)` and `requiresExecutionCompletionRepair(...)` | Adds plan/task-specific repair messages | Replace with unified `turnRepair.repair(turnContext, reason)` call |

**Simplified `ToolLoopState` mode field:**

```java
// Replaces PlanMode mode. Derived once at turn start from SPI calls.
// True when CompletionDetector indicates an "execution" context
// (plan execution / task execution). Used only for completion-detection
// paths in EXECUTE_TOOLS and FINALIZE.
boolean isExecutionMode;
```

**Update helper methods:**

Remove `interactionMode()` method entirely.

Replace `emptyFinalResponseControlMessage(PlanMode mode)` with `emptyFinalResponseControlMessage()` (mode-unaware).

Replace `invalidExecutionCompletionControlMessage(PlanMode mode)` with `invalidExecutionCompletionControlMessage()` (mode-unaware).

#### B.7 Remove plan-specific constants and fields

Delete:
- `PLAN_MODE_TOOLS` and `TASK_MODE_TOOLS` static fields (references to `ToolAccessPolicy` constants — Phase 03 should have removed these)
- `FILE_MUTATING_TOOL_NAMES` static field and all references to it (only used for `outputs_changed` stream signals for file tools — these are app-shell concepts)
- All plan-specific message constants: `EXECUTE_PLAN_MESSAGE`, `EXECUTE_PLAN_CLEAN_MESSAGE`, `PLAN_NEEDS_REVIEW_MESSAGE`, `PLAN_ANSWER_MODEL_FAILURE_MESSAGE`, `PLAN_ANSWER_CONTINUATION_FAILURE_MESSAGE`, `EXECUTE_TASK_MESSAGE`, `BEGIN_PLAN_MESSAGE`, `CONTINUE_PLAN_MESSAGE`
- `EMPTY_FINAL_RESPONSE_RETRY_LIMIT` stays; `PLAN_TURN_REPAIR_RETRY_LIMIT` and `EXECUTION_COMPLETION_REPAIR_RETRY_LIMIT` are replaced by a single `REPAIR_RETRY_LIMIT = 2`

#### B.8 Simplify stream-related methods

Remove `streamNow()` and `streamNowWithContext()` — merge into a single `streamNow(ResolvedChatRequest)` method that directly calls the tool-chat or plain-stream path without context management. The public `stream()` method calls `streamNow()` directly (no `turnCoordinator` or `streamLocks` wrap).

#### Subphase B Gate

```bash
mvn compile -pl . 2>&1 | grep -c ERROR
# Must be 0
grep -r "required = false" src/main/java/ | grep -v ".class"
# Must return zero results
grep "ThreadLocal" src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java
# Must return zero results
grep -c "import io.mindspice.magenta2.ai.orchestration" src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java
# Must be 0
grep -c "import io.mindspice.magenta2.ai.execution" src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java
# Must be 0
grep -c "import io.mindspice.magenta2.ai.skills" src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java
# Must be 0
grep -c "import io.mindspice.magenta2.ai.agent" src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java
# Must be 0
```

---

### Subphase C — Update ChatServiceTest and Final Verification

Delete all plan/task/agent test methods from `ChatServiceTest.java`. Adapt remaining test constructor invocations to the new single constructor. Verify all remaining tests pass.

#### C.1 Delete plan/task/agent test methods

Delete these test methods (they exercise deleted public API):
- `resolvingSavedPlanExecutionPreservesExistingTranscriptRows()`
- `pendingMessagesAreOutsideHistoryUntilSentAndClearDeletesQueue()` (uses `ChatPendingMessageService`)
- `resolvingCleanSavedPlanExecutionFlagsPromptContextWithoutClearingTranscriptRows()`
- `streamExecutionFinalizationMarksStillExecutingPlanNeedsReviewInsteadOfCompleted()`
- `streamExecutionFinalizationPersistsFinalArtifactOnlyAfterValidatedCompletion()`
- `planningAnswerModelFailureReturnsControlledResponseAfterSavingAnswer()`
- `planningAnswerToolFailureReturnsControlledResponseAfterSavingAnswer()`
- `missingPlanningQuestionReturnsRecoverablePromptInsteadOfThrowing()`
- `stalePlanningAnswerIndexReturnsCurrentPromptInsteadOfThrowing()`
- `chatAsAgentUsesAgentModelAndMarksAgentSession()`
- `fileMutatingToolNamesContainsExpectedTools()` (tests deleted `FILE_MUTATING_TOOL_NAMES`)
- `fileWriteToolProducesOutputsChangedStreamSignal()` (tests deleted file-mutating tool path)
- `listSessionsIncludesChatFileOutputCount()` (uses `ChatFileService`)

#### C.2 Delete test helper inner classes

Remove test inner classes that only serve deleted test methods:
- `FileToolPathSwitchChatModel` (used by deleted `toolLoopRefreshesAgentsMdContextFromActualFileToolTargets`)
- `RuntimeFileToolCallingManager` (used by the above)
- `FileWriteThenFinalChatModel` (used by deleted `fileWriteToolProducesOutputsChangedStreamSignal`)
- `SuccessToolCallingManager` (used by the above)

#### C.3 Adapt remaining test constructor invocations

For every remaining test that constructs `ChatService`, update the constructor call to match the new single canonical constructor. Since Phase 03 produced SPI implementations with no-arg or minimal constructors, tests can pass `null` for SPI fields (the `ChatService` constructor treats SPIs as nullable and provides no-op defaults internally, or the test provides mock implementations).

For `modelSelectionKeyPrefersConfiguredDefaultAliasForAmbiguousRemoteNames()`: this test uses a 6-arg constructor. Update to the full constructor, passing `null` for all fields except the ones the test exercises.

For `listSessionsOnlyIncludesBrowserSurfaceNormalChats()`: remove `PlanService` usage. The test currently creates a `PlanService` and calls `beginPlan()` to set up a planning conversation. Replace with a conversation that has a non-BROWSER surface set directly via `metadataRepository`.

For `toolLoopRefreshesAgentsMdContextFromActualFileToolTargets()`: this test uses `OrchestrationTaskContextHolder`, `WorkspaceDescriptor`, `AgentFileToolService`, `AgentsMdResolver`. These are all app-shell types. **Delete this test** — it tests agent workspace context which is out of scope for the stripped `ChatService`.

#### C.4 Verify remaining tests pass

```bash
mvn test -pl . -Dtest=ChatServiceTest
```

All remaining test methods must pass. Expected surviving tests (non-exhaustive):
- `extractsReasoningFromDedicatedDeepSeekMessage()`
- `maintainContextUsageReturnsDegradedUsageWhenMaintenanceThrows()`
- `malformedToolCallArgumentsAreReportedWithoutExecutingTools()`
- `toolExecutionArgumentFailuresAreReportedAndRetried()`
- `wrappedIoFailuresAreRetriedAsTransientModelFailures()`
- `modelSelectionKeyPrefersConfiguredDefaultAliasForAmbiguousRemoteNames()`
- `listSessionsOnlyIncludesBrowserSurfaceNormalChats()` (adapted per C.3)

#### Subphase C Gate

```bash
mvn test -pl . -Dtest=ChatServiceTest
# All tests must PASS
```

---

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/AGENTS.md`
- Read `ChatService.java` in full before any subphase editing begins
- Read `ChatServiceTest.java` in full before Subphase C editing begins
- Read the four Phase 03 SPI interface files before Subphase B: `CompletionDetector.java`, `InteractionHandler.java`, `PromptContributor.java`, `RepairStrategy.java`
- Read Phase 03's adapted `PromptContextAssembler.java`, `ToolAccessPolicy.java`, `TerminalTurnRepair.java`, `TurnAuditWriter.java` to verify they are clean before Subphase A

## Technical Guidance

- The multiple-constructor pattern in `ChatService` is a migration artifact. After this phase, only one constructor should remain.
- `ChatFileService` removal means the `FILE_MUTATING_TOOL_NAMES` set and file-output tracking logic are removed.
- When removing `OrchestrationTaskContext`/`OrchestrationTaskContextHolder`, delete the entire `ThreadLocal` holding pattern — do not carry it forward. `TurnContext` from Phase 03 is the new carrier for turn-scoped data.
- Plan/task mode detection is replaced by `CompletionDetector.isComplete()` and `ToolAccessPolicy.filterTools()`. The framework doesn't know about plan/task concepts.
- The `@Autowired(required = false)` stepping-stone approach is not used in the corrected phase. Instead, delete the methods that use a collaborator first, then remove the collaborator field. This avoids the temporary `@Autowired(required = false)` annotations entirely for collaborators whose methods are deleted in Subphase A.
- Core services that may be null in test fixtures (`contextManagementAdvisor`, `contextUsageTracker`, `chatModelRouter`, `toolCallingManager`, `chatToolRegistry`, `toolTranscriptService`, `auditRepository`) keep their existing null-guards. Do not add `@NonNull` contracts where tests rely on nullability.
- The `TurnAuditWriter` field is kept but its constructor in the single-constructor path now receives it as a parameter. Phase 03 should have already adapted `TurnAuditWriter` to not depend on `AuditService` directly.
- When adapting `effectiveSystemPrompt()`, the simplified version should extract the system prompt from the assembled messages list rather than from a dedicated `TurnContext.systemPrompt()` method. This matches how `ChatClient.prompt().system(...)` consumes it downstream.

## Validation

- `mvn compile` passes after each subphase
- `mvn test -pl . -Dtest=ChatServiceTest` — all remaining test methods pass
- grep for `@Autowired(required = false)` returns zero results (`src/main/java/**`)
- grep for `import.*PlanService` in `ChatService.java` returns zero results
- grep for `import.*TaskService` in `ChatService.java` returns zero results
- grep for `ThreadLocal` in `ChatService.java` returns zero results
- grep for `import io.mindspice.magenta2.ai.orchestration` in `ChatService.java` returns zero results
- grep for `import io.mindspice.magenta2.ai.execution` in `ChatService.java` returns zero results
- grep for `import io.mindspice.magenta2.ai.skills` in `ChatService.java` returns zero results
- grep for `import io.mindspice.magenta2.ai.agent` in `ChatService.java` returns zero results
- `ChatService.java` has exactly one constructor
- Turn-loop state machine compiles and test coverage confirms surviving paths unchanged

## Exit Criteria

- `ChatService` depends only on core services and Phase 03 SPI interfaces
- Zero `@Autowired(required = false)` in codebase
- Zero `ThreadLocal` in `ChatService`
- `ChatServiceTest` passes
- All 13+ app-shell collaborator imports removed from `ChatService.java`
