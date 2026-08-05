# Changelog — Phase 05: Delete App-Shell Packages

## Date
2026-08-04

## Git Commit

f3af4b7c73507897bfff23f7cbf10f09457c4ad7

## Change Summary
Deleted the entire application shell from `magenta-app/` (two subphases) and simplified configuration to a core chat service:
- **Subphase A (mass deletion, ~373 files)**: 8 app-shell source trees (`orchestration` 147 files, `skills` 35, `plan` 28, `task` 9, `project` 6, `system` 6, `agent/job` 8, `execution` 8); 8 app-specific tool sub-packages (file/shell/web/skills/orchestration/plan/savedplan/task, 23 files); individual types (`ChatSessionOrigin`, `ChatSessionSurface`, `ChatPlanState`, `PlanMode`, `ChatFileService`, `TrustedContextPolicyAssembler`, `InteractionQuestionTools`); migration infrastructure (`db/migrations/` 20 SQL files + `SchemaMigrationRunner`); 6 test trees (64 files), 5 test tool subdirs (7), 4 individual test files. Cataloged 200 compile errors across 19 surviving files.
- **Subphase B (adaptation)**: `AiConfig` collapsed to a 7-component canonical constructor (`defaultModel, summaryModel, summeryModel, contextBufferPercent, dataRoot, webSearch, models`; removed `defaultAgent/planningModel/compactionModel/agents/unsafeAllowWildcardShellCommands`; added `@JsonIgnoreProperties(ignoreUnknown=true)`); `AiUserConfigConfiguration`/`ExternalAiConfigLoader` simplified to model/data-root only; model layer stripped of deleted fields; `ChatSessionMetadataRepository` −11 methods −`ProjectChatSession` −schema columns; pending-message surface removed; `RequestResolver` reduced to core session/model resolution; `AuditService` lost `AgentJobService`; `ChatService` lost `ChatPlanState`/`ChatSessionOrigin` (explicit `MsgResponse` constructors; simplified `rejectSystemChatConversation`/`listSessions`/`session`/`approvedTools`); deleted `ChatInvocationProfile`, `TaskExecutionEvent`, `TaskExecutionResult`, `SchemaMigrationConfiguration` (zero surviving references); `PromptContextAssembler` → 1-arg `(AiConfig)` minimal stub; `ToolAccessPolicy` mode-unaware (`NORMAL_BLOCKED_TOOLS` only); `TerminalTurnRepair` → zero-arg (empty-response + tool-abort control messages only); `TurnContext` stripped of `PlanMode`/`ActiveTurn`; `ChatBeanConfig` added the 4 SPI beans (PromptContextAssembler/ToolAccessPolicy/TerminalTurnRepair/TurnAuditWriter); `ChatModelRouter`/`ContextManagementAdvisor` AiConfig-only. Tests: 8 adapted, 3 deleted (subjects entirely removed: `RequestResolverAgentBindingTest`, `ChatToolRegistryTest`, `PromptContextAssemblerTest`).

## Files
~410 changed: all deletions listed above plus adapted files under `magenta-app/src/main/java/io/mindspice/magenta2/ai/{config/user,chat/model,chat/repository,chat/service,chat/service/turn,chat/config,core/persistence}` and `magenta-app/src/test/java/io/mindspice/magenta2/...`. Representative modified files: `AiConfig.java`, `AiUserConfigConfiguration.java`, `ExternalAiConfigLoader.java`, `ChatHistory.java`, `ChatRequest.java`, `ChatResponse.java`, `ChatSession.java`, `PendingChatMessage.java`, `PendingMessageRequest.java`, `ChatSessionMetadataRepository.java`, `ChatPendingMessageRepository.java`, `ChatPendingMessageService.java`, `RequestResolver.java`, `AuditService.java`, `ChatService.java`, `PromptContextAssembler.java`, `ToolAccessPolicy.java`, `TerminalTurnRepair.java`, `TurnContext.java`, `ChatBeanConfig.java`, `ChatModelRouter.java`, `ContextManagementAdvisor.java`, plus 8 adapted test files.

## Behavioral Impact
Magenta is now a pure core chat service: no agents, plans, tasks, projects, workspaces, skills, system chat, jobs, execution coordination, file/shell/web tools, or migrations. Chat memory (SQLite), session metadata, pending-message queue, model routing, context management, tool registry (generic tool infra only), audit, and the 6-state turn loop remain. `AiConfig` is model/data-root configuration only. 90 tests pass; context starts in ~1.8s.

## Specification Impact
None. Deletion phase per the framework extraction plan; remaining Magenta code is the extraction source for Phases 06–09. The framework's own configuration surface is defined in later phases.

## Risks
- `PromptContextAssembler.defaultSystemPrompt()` returns null (no agent config) — safe (all consumers null-guard) but means system-prompt composition is minimal until framework types land in Phases 07/09.
- `ChatRequest.MsgRequest.contextCapture` is a ghost field (deserialization compat only, never read) — clean in a later phase.
- `agent_id` column retained in session metadata schema (still used by `findAgentId`) — reconcile with plan spec later.
- Dead `TurnContext` fields (`planCompletionDetected`, `validatedFinalMessage`, `forcedPlanningQuestion`) and a stale `AGENTS.md` mention of `PlanMode` — cleaned in Phase 07/09.
- Old config files with agent/planning fields deserialize silently via `@JsonIgnoreProperties(ignoreUnknown=true)` (fast-fail still applies to missing `models`/`defaultModel`).

## Follow-up Items
- Phase 06: create framework core types in `mindspice-core` (`io.mindspice.core`).
- Phase 07: framework events + SPIs (replaces the temporary Phase 03 SPIs).
