## Context

Phase 06 establishes the bounded execution/workspace injection-and-catch inventory. This phase extends that inventory only for its declared chat, planning, skill, tool, audit, and web-host targets; it removes active optional production behavior and makes the remaining bounded dispositions observable. `AgentJobServiceTest` contains fixed waits that must synchronize on job completion instead.

## Goal

require active chat/web hosts and make agent-job tests deterministic

## In Scope

**Write Targets**: .internal-dev/plans/code-quality-sprint/implementation-inventory.md, src/main/java/io/mindspice/magenta2/core/config/RequiredProductionWiringConfiguration.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillCatalogService.java, src/main/java/io/mindspice/magenta2/ai/agent/job/AgentJobService.java, src/main/java/io/mindspice/magenta2/ai/chat/config/ChatBeanConfig.java, src/main/java/io/mindspice/magenta2/ai/chat/service/AuditService.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatModelRouter.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ContextManagementAdvisor.java, src/main/java/io/mindspice/magenta2/ai/chat/service/RequestResolver.java, src/main/java/io/mindspice/magenta2/ai/chat/task/TaskService.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanCompletionService.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanService.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/SavedPlanChatService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/InteractionQuestionTools.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/plan/PlanSaveTools.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatBoundedReadService.java, src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatActionProposalService.java, src/main/java/io/mindspice/magenta2/api/web/ChatController.java, src/main/java/io/mindspice/magenta2/api/web/DashboardController.java, src/main/java/io/mindspice/magenta2/api/web/GlobalExceptionHandler.java, src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/PlanController.java, src/main/java/io/mindspice/magenta2/api/web/ScheduleReactionFragments.java, src/main/java/io/mindspice/magenta2/api/web/SystemChatController.java, src/test/java/io/mindspice/magenta2/RequiredProductionWiringTest.java, src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java, src/test/java/io/mindspice/magenta2/api/web/SystemChatControllerTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleReactionFeatureParitySpringTest.java, src/test/java/io/mindspice/magenta2/ai/agent/job/AgentJobServiceTest.java

## Out of Scope

- Repository-wide controller cleanup, controller decomposition, fragment/UI redesign, JavaScript, new feature flags, schema or migration changes, routes or payload changes, and direct plan-completion regression behavior.
- Phase 02 preparation/inference implementation, Phase 04 status-contract redesign, Phase 05 artifact migration, Phase 06 execution/workspace cleanup, and Phase 08 completion-validation behavior.

## Dependencies

phase-02-system-chat-transaction-seam.md, phase-04-truthful-http-errors.md, phase-05-artifact-api-cutover.md, phase-06-execution-wiring-and-observability.md

## Constraints

- Start only after every dependency has a validator PASS. Extend, do not reopen, the Phase 06 inventory.
- Production-active collaborators are required. A retained provider requires a documented concrete cycle or existing specification-backed capability seam; active use retrieves the required bean after startup verification. A disabled capability may still require its service bean.
- Existing feature flags and typed unavailable outcomes own capability gating; nullability, an empty provider, or a broad catch does not.
- Preserve Phase 02: preparation persistence remains committed before inference, inference remains outside any transaction including an ambient caller transaction, and fixed profile, callback allowlist, bounded reads, proposal-only mutation, owner checks, redaction, fail-closed behavior, and profile-holder cleanup remain intact.
- Preserve Phase 04: validation is `400`, scoped absence is `404`, concrete state conflict/staleness is `409`, size bounds remain `413`, unexpected failure is `500`, and temporary dependency/capability unavailability is `503` where the endpoint contract permits it. Do not restore a global `IllegalStateException` conflict mapping. Keep existing JSON and HTMX fragment bodies and targets.
- System-chat bounded tool responses remain secret-free and path-free. Logs, errors, and durable facts omit model/user payloads, tokens, raw untrusted content, absolute paths, claim owners, and fence values.
- `AgentJobServiceTest` contains no fixed sleep, sleep polling, unbounded wait, or elapsed-time assertion. Test-only construction may remain isolated from Spring production wiring and must not create an active production fallback.

## Invariants and Edge Cases

- An agent missing from its scoped service is not the same outcome as a failed agent lookup. Preserve `404` only for proven scoped absence; render or return the existing safe unavailable outcome for lookup/service failure.
- For system-chat tool callbacks, malformed/untrusted tool input, an absent or invalid bound profile, a cross-agent target, a stale/idempotent proposal, and a temporary dependency failure stay fail-closed and disclose no additional facts. A proposal is never executed during creation.
- Expected JSON/number parse misses, stale/idempotent cleanup, and client disconnects may remain quiet only when their exact exception and safe result are inventory-recorded and tested. A failed proposal transition, persistence read/write, profile resolution, or fragment reload is not silently converted to empty data.
- Confirmation conflict or stale target state retains its endpoint-local conflict outcome; failed profile/proposal infrastructure is unavailable rather than conflict. A failed best-effort cleanup must not replace or conceal the primary failure.
- Workspace/context/tool code keeps `WorkspaceDirectoryService` as the sole trusted descriptor constructor. This phase neither infers workspace identity from a path nor weakens artifact authorization or integrity checks.
- Phase 08 retains ownership of validator input, parsing, feedback, and completion transitions. Phase 07 only makes its active collaborators mandatory and proves the wiring.

## Implementation Steps

1. Extend the Phase 06 implementation inventory before any Phase 07 production edit. For every in-scope optional injection, provider, fallback/null branch, and catch, record the source/member, active-path contract, current disposition, replacement or retained rationale, exact expected exception/control-flow reason when quiet, capability or cycle proof when retained, focused test, and reconciliation status. Retain Phase 06 rows unchanged; do not add unlisted sites.
2. Convert listed production constructors and bean factories to required injection and remove reachable null-degradation paths across chat, planning, task, skill, tool, audit, workspace/context, and web-host collaborators. Preserve only explicitly non-Spring test construction, and make test fixtures supply or safely avoid dependencies without encoding a production fallback. Keep `PlanCompletionService`/`PlanSaveTools` changes limited to mandatory wiring, not completion behavior.
3. Convert listed web-host fields to constructor dependencies without decomposing controllers. Retain an `ObjectProvider` only when the inventory proves a cycle; register that exact seam with the fixed startup verifier in `RequiredProductionWiringConfiguration`, prove missing-bean context failure and complete-context startup, and use required retrieval on every active path. Remove empty-provider/null host behavior from production paths.
4. In bounded system-chat reads, proposals, and controller/SSE logic, give each catch one named disposition: malformed input or expected parse miss, scoped absence, typed stale/idempotent result, temporary unavailable result, expected client disconnect, or propagation/safe contextual logging for operational failure. Keep proposal creation non-executing; preserve exact profile/agent/target/state/digest checks; do not let a failed lookup become `404`, a failed persistence operation become an empty proposal list, or a failed cleanup conceal the original outcome. Do not alter Phase 02 transaction ownership or profile-holder cleanup.
5. In schedule/reaction fragments and the inventoried orchestration lookup paths, preserve empty/missing rendering only for a typed documented absence. Treat malformed form/JSON input as controlled validation, preserve submitted fragment state where the current fragment does so, and map dependency failure to the established unavailable fact or controlled safe status. Preserve the Phase 04 status contract and existing fragment/OOB host shape; remove no broad global exception mapping by substituting another null-based mapping.
6. Add focused coverage only in the declared test targets: missing and complete production contexts; malformed parse/input; missing scoped entity; service/infrastructure failure; stale/idempotent proposal behavior; cleanup failure without primary-failure loss; expected disconnect; and schedule/orchestration/system-chat fragment reload behavior. Assert both status and safe existing body/fragment shape, including that unavailable is not rendered as an ordinary empty or missing result.
7. Replace agent-job fixed waits with a completion latch or future that the test double completes in `finally` after the background job path has reached its terminal observation point. Submit the job, await that event through the test’s diagnostic bounded-wait helper, then assert the durable completed job record and selected model. Release blocking workers and shut down executors in `finally`; restore interruption when an await is interrupted. Do not use a sleep, polling loop, or elapsed-time assertion.
8. Reconcile the inventory after implementation and validation. Every retained quiet catch names its exact exception and safe behavior; every retained provider, fallback, or test-only null path identifies its capability gate, cycle, or non-production reason and focused evidence. Search only the inventoried files for optional injection, provider availability calls, null degradation, broad/empty catches, and sleep polling; resolve or explicitly reconcile every match.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/agent/job/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/config/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/tool/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/skills/AGENTS.md
- src/main/java/io/mindspice/magenta2/api/AGENTS.md
- src/main/java/io/mindspice/magenta2/api/web/AGENTS.md
- src/main/java/io/mindspice/magenta2/core/AGENTS.md

## Technical Guidance

- The inventory is the phase boundary, not a source-code TODO list. A provider is valid only for a proven cycle; a feature flag is valid only for its documented capability outcome.
- Use typed service outcomes or narrow helpers at the controller boundary. Do not classify Java exception classes globally by message or use `IllegalStateException` as a general conflict signal.
- Preserve safe HTMX failure rendering: the relevant controller sets the accepted status locally while returning the same marked fragment body and only the affected host/OOB refresh.
- Use `CountDownLatch`, `CompletableFuture`, barriers, or direct synchronous assertions. Test cleanup belongs in `finally`, and interrupted tests restore the interrupt flag before failing.

## Validation

- Run `RequiredProductionWiringTest`, `SystemChatControllerTest`, `ScheduleReactionFeatureParitySpringTest`, `OrchestrationControllerTest`, and repeated `AgentJobServiceTest` execution.
- Run affected chat, planning, tool, skill, audit, pending-message, and system-chat suites, then the configured compile/package checks and bounded ephemeral-port Spring Boot startup.
- Verify production-context failure when each required active dependency is absent, complete-context success, and each retained provider seam’s verifier result.
- Verify controller/fragment results for validation, scoped absence, concrete conflict/staleness, temporary unavailability, unexpected failure, and expected disconnect without sensitive response/log content or changed fragment shape.
- Search the inventoried files for optional injection, `getIfAvailable`/empty providers, null degradation, broad or silent catches, fixed sleeps, and sleep polling; reconcile every result to the inventory.

## Exit Criteria

- Active chat/web hosts and listed collaborators fail fast when required production dependencies are absent; every retained provider or gate is justified, verified, and exercised.
- Every bounded catch has an explicit, tested safe disposition. Scoped absence, unavailable dependency, conflict/staleness, malformed input, and client disconnect remain distinct without sensitive disclosure.
- `AgentJobServiceTest` synchronizes on completion events, asserts terminal durable state, cleans up executors, and preserves interruption without fixed waits or polling.
- Phase 02 transaction/profile guarantees and Phase 04 status/body guarantees remain intact; no route, payload, fragment target, UI interaction, JavaScript, schema, or artifact/workspace authority changes.
- The inventory reconciles all Phase 07 sites and the validator review-and-repair gate reports PASS.
