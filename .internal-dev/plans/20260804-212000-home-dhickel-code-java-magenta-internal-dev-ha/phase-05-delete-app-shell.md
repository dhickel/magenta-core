# Phase 05 — Delete App-Shell Packages

## Context

With `ChatService` fully decoupled from app-shell collaborators (Phase 04), the app-shell packages are now dead code. This phase deletes all orchestration, skills, plan, task, project, system, job, execution, and app-specific tool packages. It also simplifies `AiConfig` and `AiUserConfigConfiguration`, removes Magenta-specific types, deletes migration files and `SchemaMigrationRunner`, and adapts surviving source and test files that reference deleted types.

## Goal

Delete all app-shell packages, Magenta-specific types, migration files; collapse AiConfig to single canonical constructor

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration, src/main/java/io/mindspice/magenta2/ai/skills, src/main/java/io/mindspice/magenta2/ai/chat/plan, src/main/java/io/mindspice/magenta2/ai/chat/task, src/main/java/io/mindspice/magenta2/ai/chat/project, src/main/java/io/mindspice/magenta2/ai/chat/system, src/main/java/io/mindspice/magenta2/ai/agent/job, src/main/java/io/mindspice/magenta2/ai/execution, src/main/java/io/mindspice/magenta2/ai/chat/tool/file, src/main/java/io/mindspice/magenta2/ai/chat/tool/shell, src/main/java/io/mindspice/magenta2/ai/chat/tool/web, src/main/java/io/mindspice/magenta2/ai/chat/tool/skills, src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration, src/main/java/io/mindspice/magenta2/ai/chat/tool/plan, src/main/java/io/mindspice/magenta2/ai/chat/tool/savedplan, src/main/java/io/mindspice/magenta2/ai/chat/tool/task, src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionOrigin.java, src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionSurface.java, src/main/java/io/mindspice/magenta2/ai/chat/model/ChatPlanState.java, src/main/java/io/mindspice/magenta2/ai/chat/model/PlanMode.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatFileService.java, src/main/java/io/mindspice/magenta2/ai/chat/model/ChatInvocationProfile.java, src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssembler.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/InteractionQuestionTools.java, src/main/resources/db/migrations, src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java, src/main/java/io/mindspice/magenta2/ai/config/user/AiConfig.java, src/main/java/io/mindspice/magenta2/ai/config/user/AiUserConfigConfiguration.java

- Delete 16 app-shell directory trees and 8 app-specific tool sub-packages
- Delete 6 Magenta-specific type files and 2 service/tool files
- Delete `src/main/resources/db/migrations/` directory and `SchemaMigrationRunner.java`
- Delete corresponding test directories and individual test files
- Adapt surviving source files that reference deleted types (`ChatSessionMetadataRepository`, `ChatPendingMessageRepository`, `ChatPendingMessageService`, `RequestResolver`, `AuditService`)
- Adapt surviving test files that reference deleted types (`ChatServiceTest`, `ChatPendingMessageServiceTest`, `ChatPendingMessageRepositoryTest`, `ToolAccessPolicyTest`, `PromptContextAssemblerTest`, `ChatStreamEventSerializationTest`)
- Simplify `AiConfig.java`: collapse 5 constructors to 1, remove `agents`, `defaultAgent`, `planningModel`, `compactionModel`, `unsafeAllowWildcardShellCommands` fields and related resolver methods
- Simplify `AiUserConfigConfiguration.java`: collapse to a single `AiConfig` bean factory using the simplified constructor
- Verify zero references from retained code to deleted packages via grep

## Out of Scope

- Creating any framework types
- Renaming or moving surviving Magenta code (Phase 09)
- Creating framework schema or migration runner (Phase 08)
- Redesigning surviving repositories or services beyond removing deleted-type references

## Dependencies

phase-04-remove-chatservice-deps.md

## Constraints

- All surviving tests must pass after adaptation
- Context must start with the simplified `AiConfig`
- Zero references from retained code to deleted packages (verified by grep)
- Surviving code must compile
- The `src/test/java/io/mindspice/magenta2/ai/chat/tool/` directory's surviving test files must not be deleted

## Implementation Steps

This phase is split into two sequential subphases. **Subphase A maps to one agent session. Subphase B maps to one sequential agent session.** Both must complete in order before the phase-level validation and exit criteria apply.

---

### Subphase A — Mass Deletion

**Agent session**: one DeepSeek implementation agent. This subphase performs all mechanical deletion operations and a first-pass compilation check to identify remaining references for Subphase B.

#### A1. Delete app-shell source directory trees

```bash
rm -rf src/main/java/io/mindspice/magenta2/ai/orchestration
rm -rf src/main/java/io/mindspice/magenta2/ai/skills
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/plan
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/task
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/project
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/system
rm -rf src/main/java/io/mindspice/magenta2/ai/agent/job
rm -rf src/main/java/io/mindspice/magenta2/ai/execution
```

#### A2. Delete app-specific tool sub-packages

```bash
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/file
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/shell
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/web
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/skills
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/plan
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/savedplan
rm -rf src/main/java/io/mindspice/magenta2/ai/chat/tool/task
```

Do NOT delete the parent `src/main/java/io/mindspice/magenta2/ai/chat/tool/` directory — it contains surviving files.

#### A3. Delete individual Magenta-specific type files

```bash
rm src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionOrigin.java
rm src/main/java/io/mindspice/magenta2/ai/chat/model/ChatSessionSurface.java
rm src/main/java/io/mindspice/magenta2/ai/chat/model/ChatPlanState.java
rm src/main/java/io/mindspice/magenta2/ai/chat/model/PlanMode.java
rm src/main/java/io/mindspice/magenta2/ai/chat/model/ChatInvocationProfile.java
rm src/main/java/io/mindspice/magenta2/ai/chat/service/ChatFileService.java
rm src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssembler.java
rm src/main/java/io/mindspice/magenta2/ai/chat/tool/InteractionQuestionTools.java
```

#### A4. Delete migration infrastructure

```bash
rm -rf src/main/resources/db/migrations
rm src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java
```

#### A5. Delete test directory trees

```bash
rm -rf src/test/java/io/mindspice/magenta2/ai/orchestration
rm -rf src/test/java/io/mindspice/magenta2/ai/skills
rm -rf src/test/java/io/mindspice/magenta2/ai/execution
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/plan
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/system
rm -rf src/test/java/io/mindspice/magenta2/ai/agent/job
```

#### A6. Delete test tool subdirectories (surgical — preserve parent)

```bash
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/tool/file
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/tool/orchestration
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/tool/plan
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/tool/shell
rm -rf src/test/java/io/mindspice/magenta2/ai/chat/tool/web
```

#### A7. Delete individual test files

```bash
rm src/test/java/io/mindspice/magenta2/ai/chat/service/ChatFileServiceTest.java
rm src/test/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunnerTest.java
rm src/test/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssemblerTest.java
rm src/test/java/io/mindspice/magenta2/ai/chat/tool/InteractionQuestionToolsTest.java
```

#### A8. First-pass compilation check

Run `mvn compile` and capture all errors. This produces the catalog of surviving files referencing deleted types for Subphase B. Do NOT fix errors in this subphase — only catalog them.

#### A9. Catalog surviving references

Run grep for each deleted package prefix from `src/main/java/` and `src/test/java/`. Save the output. Confirm the catalog includes at minimum:

| Deleted type | Surviving source references | Surviving test references |
|---|---|---|
| `ChatSessionSurface` | `ChatSessionMetadataRepository`, `ChatPendingMessageRepository`, `ChatPendingMessageService`, `RequestResolver` | `ChatServiceTest`, `ChatPendingMessageServiceTest`, `ChatPendingMessageRepositoryTest` |
| `ChatSessionOrigin` | `ChatSessionMetadataRepository` | `ChatServiceTest` |
| `ChatPlanState` | — | `ChatStreamEventSerializationTest` |
| `PlanMode` | — | `ToolAccessPolicyTest`, `PromptContextAssemblerTest` |
| `ChatFileService` | — | `ChatServiceTest` |
| `ChatInvocationProfile` | — | (in deleted `system/` test tree) |
| `AgentJobService` | `AuditService` | — |
| `TrustedContextPolicyAssembler` | `ChatService` (Phase 04 pre-condition) | — |

---

### Subphase B — Config Simplification and Compilation Fixes

**Agent session**: one sequential DeepSeek implementation agent. This subphase adapts all surviving files, simplifies AiConfig and AiUserConfigConfiguration, fixes compilation errors, and runs full validation.

#### B1. Phase 04 pre-condition check

Verify `ChatService.java` contains zero references to `TrustedContextPolicyAssembler`:

```bash
grep -c "TrustedContextPolicyAssembler" src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java
```

Must return `0`. If non-zero, stop and escalate — Phase 04 is incomplete.

#### B2. Simplify AiConfig

Edit `src/main/java/io/mindspice/magenta2/ai/config/user/AiConfig.java`:

**Fields to KEEP**: `defaultModel`, `summaryModel`, `summeryModel` (legacy alias for `summaryModel`), `contextBufferPercent`, `dataRoot`, `webSearch`, `models`

**Fields to REMOVE**: `defaultAgent`, `planningModel`, `compactionModel`, `agents`, `unsafeAllowWildcardShellCommands`

**Constructors**: Collapse all 5 constructors to a single canonical compact constructor on the record. The canonical constructor is the record declaration itself with no body — remove all auxiliary constructors.

**Methods to KEEP**: `resolvedContextBufferPercent()`, `resolvedDefaultModelKey()`, `resolvedSummaryModelKey()`

**Methods to REMOVE**: `resolvedPlanningModelKey()`, `resolvedCompactionModelKey()`, `unsafeAllowWildcardShellCommandsEnabled()`

After editing, the record should have exactly 7 components: `(String defaultModel, String summaryModel, String summeryModel, Integer contextBufferPercent, Path dataRoot, WebSearchConfig webSearch, Map<String, ModelConfig> models)`.

#### B3. Simplify AiUserConfigConfiguration

Edit `src/main/java/io/mindspice/magenta2/ai/config/user/AiUserConfigConfiguration.java`:

- Remove all agent resolution logic, skill configuration beans, agent profile beans
- Collapse to a single `@Bean AiConfig aiConfig(...)` method that loads from the external JSON config path, resolves the data root, and returns the simplified AiConfig
- The `withResolvedDataRoot` helper must be updated to construct `AiConfig` with the new 7-component canonical constructor (drop all deleted field arguments)
- If the class becomes trivially simple (just the one bean method), preserve the `@Configuration` class structure; do not inline into another config class

#### B4. Adapt ChatSessionMetadataRepository

Edit `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatSessionMetadataRepository.java`:

- Remove imports: `ChatSessionSurface`, `ChatSessionOrigin`
- **Remove these methods entirely**: `saveOriginIfAbsent`, `findOrigin`, `saveSurfaceIfAbsent`, `findSurface`, `findAgentConversationIds`, `findBrowserConversationIds`, `createProjectChatSession`, `findProjectChatSession`, `saveAgentOrigin`, `findProjectId`, `findMemberAgentId`
- **Remove the `ProjectChatSession` inner record**
- Keep all other methods (`existsByConversationId`, `saveModel`, `saveTitle`, `updateTitle`, `saveTitleIfAbsent`, `findModel`, `saveActiveTaskRunId`, `findActiveTaskRunId`, `clearActiveTaskRunId`, `savePlanningModel`, `findPlanningModel`, `findTitle`, `isFavorite`, `isArchived`, `findUpdatedAt`, `setFavorite`, `setArchived`, `deleteByConversationId`, `ensureSchema`, `booleanValue`, `updateFlag`, `now`)
- From `ensureSchema()`, remove the ALTER TABLE statements for `origin`, `surface`, `agent_id`, `project_id`, `member_agent_id`, `materialization_key`, `materialization_generation`, `owner_binding_key` (keep only: `model`, `title`, `favorite`, `archived`, `updated_at`, `planning_model`, `active_task_run_id`)
- From the CREATE TABLE in `ensureSchema()`, remove `origin text,`, `surface text,`, `agent_id text,`

#### B5. Adapt ChatPendingMessageRepository

Edit `src/main/java/io/mindspice/magenta2/ai/chat/repository/ChatPendingMessageRepository.java`:

- Remove import: `ChatSessionSurface`
- In `enqueue()` method signature, remove the `ChatSessionSurface surface` parameter — drop the `surface` column from the INSERT
- In `PendingMessageRow` record, remove the `surface` field
- Remove the `surface(String value)` private helper method
- Update `ensureSchema()` to drop the `surface` column if present, or remove the ALTER TABLE for it

#### B6. Adapt ChatPendingMessageService

Edit `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatPendingMessageService.java`:

- Remove import: `ChatSessionSurface`
- In `enqueue()` method, remove the `ChatSessionSurface surface` parameter — pass no surface to the repository call

#### B7. Adapt RequestResolver

Edit `src/main/java/io/mindspice/magenta2/ai/chat/service/RequestResolver.java`:

- Remove import: `ChatSessionSurface`
- Remove the `ChatSessionSurface surface` parameter from any method signatures where it appears
- Remove any surface-based logic in the method bodies; default to internal/null surface semantics

#### B8. Adapt AuditService

Edit `src/main/java/io/mindspice/magenta2/ai/chat/service/AuditService.java`:

- Remove import: `AgentJobService` (`io.mindspice.magenta2.ai.agent.job.AgentJobService`)
- Remove the `AgentJobService agentJobService` field
- Remove the `AgentJobService` parameter from all constructors
- Remove any method body that calls `agentJobService` (title-job submission logic)

#### B9. Adapt surviving test files

**ChatServiceTest.java**: Remove imports of `ChatSessionSurface`, `ChatFileService`, `ChatSessionOrigin`. Remove method calls that pass `ChatSessionSurface.BROWSER` — replace with `null` or remove the parameter if the adapted method no longer accepts it. Remove `ChatFileService` construction and related assertions.

**ChatPendingMessageServiceTest.java**: Remove `ChatSessionSurface` import. Remove `ChatSessionSurface.BROWSER` from `enqueue` calls.

**ChatPendingMessageRepositoryTest.java**: Remove `ChatSessionSurface` import. Remove `ChatSessionSurface.BROWSER` from `enqueue` calls and assertions that check the surface field.

**ToolAccessPolicyTest.java**: Remove `PlanMode` import. Rewrite test methods that call `filterToolsByMode(…, PlanMode.…)` — replace with the plan-mode-agnostic equivalent or remove mode-specific test cases.

**PromptContextAssemblerTest.java**: Remove `PlanMode` import. Rewrite test methods that call `mergeModePrompt(PlanMode.…)` — replace with the mode-agnostic equivalent or remove mode-specific test cases.

**ChatStreamEventSerializationTest.java**: Remove `ChatPlanState` import. Remove `ChatPlanState.normal()` from event constructors — replace with `null` or remove the `planState` parameter if the adapted event no longer accepts it.

#### B10. Fix remaining compilation errors

Run `mvn compile` and `mvn test-compile`. Fix all remaining compilation errors. Common sources:

- Surviving `@Configuration` classes that reference deleted beans — remove the `@Bean` method or the entire class if it's now empty
- Surviving `@Service` or `@Repository` classes that inject deleted types — remove the field and constructor parameter
- Test files that import deleted types beyond those listed in B9 — adapt or delete
- Any file that imported `PlanMode`, `ChatPlanState`, `ChatSessionSurface`, `ChatSessionOrigin`, or `ChatInvocationProfile` transitively

#### B11. Verify zero references to deleted packages and types

Run grep for every deleted package prefix and type name. All must return zero results from `src/main/java/`:

```bash
grep -r "io.mindspice.magenta2.ai.orchestration" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.skills" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.chat.plan" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.chat.task" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.chat.project" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.chat.system" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.agent.job" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "io.mindspice.magenta2.ai.execution" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "ChatSessionOrigin" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "ChatSessionSurface" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "ChatPlanState" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "PlanMode" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "ChatFileService" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "ChatInvocationProfile" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "TrustedContextPolicyAssembler" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "InteractionQuestionTools" src/main/java/ && echo "FAIL" || echo "PASS"
grep -r "SchemaMigrationRunner" src/main/java/ && echo "FAIL" || echo "PASS"
```

Also grep for deleted type references in surviving test files — these should have been adapted in B9:

```bash
grep -r "ChatSessionSurface\|ChatSessionOrigin\|ChatPlanState\|PlanMode\|ChatInvocationProfile" src/test/java/ && echo "REMAINING" || echo "PASS"
```

If any test reference remains, adapt or delete the test.

#### B12. Run surviving tests

```bash
mvn test -pl .
```

All surviving tests must pass. Investigate and fix any failures — they indicate either incomplete adaptation or a hidden dependency on deleted code.

#### B13. Verify context startup

```bash
timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0
```

Context must start without errors. If startup fails, the error identifies a surviving Spring configuration reference to a deleted type — trace and remove.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/specifications/services.md`

## Technical Guidance

- **Do not delete the parent `tool/` directories** — only the named sub-packages. The surviving tool infrastructure (`ChatToolRegistry`, `ToolTranscriptService`, `ToolArgumentCoercionConfig`) must remain.
- **AiConfig constructor collapse**: After removing 4 fields, the 7 remaining fields become the canonical record constructor. Delete all auxiliary constructors. Update all callers (search for `new AiConfig(`) to use the canonical constructor.
- **AiUserConfigConfiguration's `withResolvedDataRoot`**: Must be updated to construct `AiConfig` without `defaultAgent`, `planningModel`, `compactionModel`, `agents`, `unsafeAllowWildcardShellCommands` arguments.
- **`ChatSessionMetadataRepository` method removal**: The removed methods (`saveOriginIfAbsent`, `findSurface`, etc.) may have callers in surviving code — grep for each method name after editing and remove the calls from surviving files.
- **`AuditService` has `@Autowired(required = false) AgentJobService`** — this field and import must be removed. Title-job submission logic that calls `agentJobService` becomes dead code and must be removed.
- **`ConversationTurnCoordinator`** is deleted with the `execution/` package — verify no surviving file references it.
- **Surviving test adaptation priority**: Tests that reference only deleted types in imports (not in logic) need import removal only. Tests that construct deleted types or call methods with deleted-type parameters need local logic changes. Do not delete tests that can be adapted.

## Validation

- All 16 deleted directories do not exist on disk
- All individual deleted files do not exist on disk
- `mvn compile` passes
- `mvn test -pl .` — all remaining tests pass
- grep for deleted package references and deleted type names from retained `src/main/java/` returns zero results
- grep for deleted type references from retained `src/test/java/` returns zero results (after adaptation)
- `AiConfig` has single 7-component canonical constructor without `defaultAgent`, `planningModel`, `compactionModel`, `agents`, `unsafeAllowWildcardShellCommands`
- `AiUserConfigConfiguration` has no agent resolution logic, no skill beans, no agent profile beans
- Application context starts (`timeout 30s mvn spring-boot:run`)

## Exit Criteria

- App-shell packages fully deleted
- Magenta-specific types deleted
- Migration files and SchemaMigrationRunner deleted
- Surviving source and test files adapted to compile without deleted types
- `AiConfig` simplified to single canonical constructor with model/data-root configuration only
- `AiUserConfigConfiguration` simplified to essential AiConfig bean factory
- All remaining tests pass
- Zero references to deleted packages and types from retained code
- Context starts successfully
