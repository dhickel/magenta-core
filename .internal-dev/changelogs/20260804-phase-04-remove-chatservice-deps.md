# Changelog — Phase 04: Remove ChatService Dependencies

## Date
2026-08-04

## Git Commit

a76fbda27b61453c06056d1894690e3dcb953c33

## Change Summary
Severed every app-shell collaborator reference from `ChatService` (three subphases), collapsing it to a single canonical constructor that depends only on core services and the Phase 03 SPI interfaces:
- **Subphase A** — deleted the public orchestration API: plan lifecycle methods (`beginPlan*`, `submitPlanAnswer*`, `executeSavedPlan*`, `resolveSavedPlanExecution*`, `handlePlanExecutionStreamFinished*`, `planState`, etc.), task execution methods (`executeTaskBlocking`, `streamTaskExecution`, `resolveTaskExecution`), agent-chat methods (`chatAsAgent`, `markAgentConversation`, `installChatFileContextIfNeeded`, `restoreContext`), and all measurement instrumentation (8 methods + call sites). Simplified `clearConversation()` (memory + session metadata + usage tracker only), `listSessions()` (no mode filter), `session()` (output count → 0), `conversationTitleJobStatus()` (→ null), `conversationTitle()` (no plan fallback). Removed collaborator imports.
- **Subphase B** — removed 13 app-shell fields, `ThreadLocal<TrustedPolicyResult>`, `streamLocks`, `ConversationTurnCoordinator`, 5 overloaded constructors, trusted-policy wiring, `@Autowired(required=false)` setter injection, `await`/`unwrap` helpers, `planningModel()`, `interactionMode()`, plan-specific constants; created the single 17-parameter constructor; simplified `effectiveSystemPrompt()` (system messages from `assembleTurnInstructions`), `chatNow()`/`streamNow()` (no context management), merged stream methods; removed `PlanMode` from `ToolLoopState` (NORMAL implicit) and replaced mode branches with mode-unaware repair handling; added `REPAIR_RETRY_LIMIT = 2`.
- **Subphase C** — `ChatServiceTest` reduced 1442→571 lines: deleted 14 test methods exercising removed APIs and 8 unused inner classes; adapted 7 surviving tests to the new constructor; `listSessionsOnlyIncludesBrowserSurfaceNormalChats` now sets the surface directly instead of via `PlanService.beginPlan()`.

## Files
- Modified: `magenta-app/src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java` (3009→1571 lines)
- Modified: `magenta-app/src/test/java/io/mindspice/magenta2/ai/chat/service/ChatServiceTest.java` (1442→571 lines)
- Validator repairs: removed residual `PlanMode` import from ChatService (inlined `filterApprovedTools` chain and NORMAL empty-response message)

## Behavioral Impact
`ChatService` is now a pure chat turn-loop service: `chat()`, `stream()`, `clearConversation()`, `history()`, `listSessions()`, `session()`, `conversationTitle()`, model/tool selection utilities. All plan/task/agent orchestration behavior is removed; the 6-state turn cycle (PREPARE→INVOKE_MODEL→EVALUATE→EXECUTE_TOOLS→REPAIR→FINALIZE→DONE) is preserved for normal turns. `ChatServiceTest` passes 7/7.

## Specification Impact
None. Intermediate extraction step per the framework plan; the mode-aware branches are intentionally replaced by SPI boundaries that Phases 06–07 realize as the framework-native contract.

## Risks
- Two app-shell files (`WorkflowTaskExecutor`, `OrchestrationRunnerService`) no longer compile (call removed `executeTaskBlocking`) — they are Phase 05 deletion targets; module-level test runs are blocked until then.
- Spring context startup is broken until Phase 05 provides bean definitions for the 4 SPI beans and remaining core collaborators (`ChatBeanConfig` only defines 3 beans today).
- `TerminalTurnRepair` still constructor-depends on `PlanService`/`TaskService` (passed as null; Phase 05 cleans); `ToolAccessPolicy` still exposes `PlanMode` in its API (Phase 06 makes it mode-unaware); `ChatPlanState` import persists via `ChatResponse.MsgResponse` (Phase 06–07 model refactor target); `planCompletionDetected` flag is dead code in NORMAL mode.

## Follow-up Items
- Phase 05: delete app-shell packages (orchestration, skills, plan, task, project, system, job, execution, app-specific tools), migration files, `ChatPlanState`/`PlanMode`, collapse `AiConfig`; fix Spring wiring for the stripped `ChatService`.
