# 2026-07-27 Phase 03 — Immutable Binding Lifecycle

## Date
2026-07-27

## Change Summary
Partial Phase 03 work added a chat request capture field, context-key carriage, and partial assignment/task-run owner-link calls. Validator review removed an undeclared `ai_chat_session_metadata.context_binding_key` column and now reads conversation binding association only from `binding_owner_links`.

## Files
- `src/main/java/io/mindspice/magenta2/ai/chat/model/ChatRequest.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/RequestResolver.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/ResolvedChatRequest.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationTaskContext.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunner.java`

## Behavioral Impact
Incomplete. Existing tests continue to pass, but binding capture and lifecycle enforcement do not meet the Phase 03 contract.

## Specification Impact
The intended specifications are unchanged. The implementation diverges from the Phase 03 plan; see `.internal-dev/reviews/2026-07-27-phase-03-immutable-binding-lifecycle-review.md`.

## Risks
Assignments may be created without bindings; lifecycle retries can orphan binding records; runners do not validate binding links or stale generation/state before provisioning; children have no durable binding owner links.

## Follow-up Items
Phase 03 is blocked pending the repairs recorded in the review. Do not start dependent phases.
