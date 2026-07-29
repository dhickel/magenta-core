## Context

`PlanCompletionService` records the execution report, performs deterministic completion preflight, collects validator-safe artifact facts, constructs a clean validator request, parses the reply, records feedback, and alone decides whether to mark a saved plan complete. Current behavior leaves fail-closed gaps around evidence labels, artifact realpath confinement and disclosure, model resolution, provider failure, and strict response acceptance. `PlanCompletionValidator` remains the narrow model boundary.

## Goal

prove and wire fail-closed clean-context completion validation

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanCompletionService.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanCompletionValidator.java, src/test/java/io/mindspice/magenta2/ai/chat/plan/PlanCompletionServiceTest.java

## Out of Scope

- Planning UI/routes, prompt changes outside completion validation, new model settings, validator-interface removal, execution-model fallback, chat-history replay, schema changes, and changes to Phase 07 production wiring.

## Dependencies

phase-07-chat-web-wiring-and-observability.md

## Constraints

- Resolve validator models only through the planning-validator path. A configured planning alias or compatible persisted planning value must resolve to a configured model with a nonblank remote name; an unresolved or invalid planning selection fails closed. Never inspect or select the execution or default model.
- Validator input contains only approved-plan facts, required checklist, persisted execution evidence, accepted artifact facts/content, prior validation feedback, and the proposed final message. Broad chat history and unrelated conversation content are excluded.
- Execution reports and artifact content are untrusted. Artifact failures, configuration failures, parser failures, and validator/provider failures record generic safe feedback and cannot call `markCompleted`.
- Retain Phase 07 required production wiring. Do not add optional injection, null-based availability behavior, or a test-only production fallback.
- Preserve public tool/API behavior and response text shape except for making failure feedback safe and fail closed. Do not expose raw artifact bytes, absolute paths, exception messages, credentials, or raw validator output in feedback or logs.

## Implementation Steps

1. Add `PlanCompletionServiceTest` with a direct fixture: a controlled executing `PlanDefinition`, mocked/recorded `PlanService` transitions, a recording `PlanCompletionValidator`, real `ObjectMapper`, real `AiConfig` model map, and a temporary data root. Make the fixture return the reported plan state after `recordExecutionReport`, capture validator requests, and record feedback/completion calls. Do not start Spring or invoke Spring AI.

2. Make evidence preflight deterministic after `recordExecutionReport` and before artifact/model work. Require a nonempty approved validation-criteria list and exactly one well-formed `Criterion: <exact criterion> | Evidence: <nonblank proof>` entry for each required criterion. Reject missing, malformed, unknown, and duplicate labels; reject reported deviations and unmet criteria. Trim surrounding whitespace only; do not case-fold or loosely match labels. A preflight rejection skips the validator, records safe remediation, and cannot complete the plan. Test absent criteria, missing/malformed/duplicate labels, exact-label success, deviations, unmet criteria, and the no-validator-call invariant.

3. Collect artifact references from persisted `Artifact:` evidence first, then the current completion request; trim, remove blanks, deduplicate while preserving first occurrence, and validate every reference before model invocation. Accept only normalized relative candidates: the data-root candidate and, for a session plan, its trusted chat-root candidate. Reject absolute paths, traversal, missing/nonregular/unreadable files, symlink leaves, symlink-component escapes, invalid path syntax, and any real path outside its selected real root. Bound content with the established truncation limit. Return structured internal read outcomes rather than classifying bracketed error strings; feedback names only a generic artifact-validation failure and contains neither artifact bytes nor absolute paths. Cover persisted/current ordering, dedupe, session-plan candidates, all rejected path classes, truncation, and safe feedback.

4. Build validator input from the recorded plan and accepted artifacts only. Preserve labelled sections for approval Markdown, the exact required checklist, execution evidence, artifact facts/content, prior validation feedback, and proposed final message. Through the captured request, assert required sections and expected facts are present, while broad chat history, unrelated conversation text, execution-model values, and rejected artifact content are absent.

5. Centralize validator-model selection in the planning-validator path. Resolve the configured planning alias first; retain the persisted planning-model fallback only when it resolves by the same configured alias/remote-name compatibility rule. Require a configured `ModelConfig` and nonblank remote model name for either candidate. Test configured planning alias resolution, valid persisted planning fallback, absent/unknown/blank planning selections, and the invariant that an execution model is never selected or sent to the validator.

6. Parse validator replies as strict JSON. Permit either a bare JSON object or one complete JSON code fence; reject surrounding prose, malformed JSON, missing keys, wrong root/key/value types, invalid statuses, missing required criterion fields, and non-string list entries. For completion, require a one-to-one, exactly-once representation of every required deliverable and validation criterion, with no duplicate, missing, or unexpected criterion and nonblank evidence for every passed criterion. Test complete and incomplete replies plus null, empty, fenced, malformed, missing-key, wrong-type, invalid-status, invalid-evidence, duplicate, and remediation cases. Never copy raw validator output into feedback.

7. Keep `PlanCompletionValidator` as the service boundary and catch validator/provider/runtime failures at the completion-service boundary. Convert them into a generic failed `ValidationResult`, record feedback, and return the ordinary incomplete result; logging, if retained, is contextual and excludes payloads, paths, and exception details unsafe for durable records. Require the validator and configuration in production according to Phase 07; the direct fixture supplies them explicitly. Test a throwing validator and unresolved configuration without allowing an exception, validator fallback, or completion.

8. Preserve transition order on every handled outcome: record execution report, perform preflight/artifact/model validation, record validation feedback, then call `markCompleted` only for a fully valid result. Assert feedback exists on every rejection, completion occurs once only on success, incomplete responses never transition status, and accepted responses preserve exactly-once criterion representation. The essential control flow is:

```text
reported = recordExecutionReport(...)
attempt = preflightOrArtifactFailure(reported) ?? validate(reported)
recordValidationFeedback(...)
if (attempt is fully valid) markCompleted(...)
```

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md
- .internal-dev/knowledge/plan-completion-validator-boundary.md
- .internal-dev/knowledge/spring-ai-model-options-routing.md

## Technical Guidance

- Assert through `PlanCompletionValidator.ValidationRequest`; do not mock or inspect Spring AI client internals.
- Use `toRealPath` containment against the selected trusted root after rejecting symlink/nonregular candidates. A normalized lexical prefix is not a confinement proof.
- Treat parser and provider failures as ordinary incomplete validation attempts, not exceptions that bypass feedback. Preserve the original cause only where safe for internal diagnostic logging; never render it.
- Keep completion authority in `PlanCompletionService`. `PlanCompletionValidator` returns untrusted raw model content and does not persist state or select models.

## Validation

- Run `mvn -Dtest=PlanCompletionServiceTest,PlanServiceTest,PlanSaveToolsTest,ChatModelRouterTest test`.
- Inspect captured validator requests for the clean-context allowlist, accepted artifact ordering, rejected-artifact exclusion, planning-only model routing, and no execution-model selection.
- Verify direct tests cover each preflight, artifact, configuration, parser, and provider failure with feedback recorded and `markCompleted` absent; verify the valid path records feedback before exactly one completion transition.
- Run the phase validator review-and-repair gate and require PASS before handing evidence to Phase 10.

## Exit Criteria

- `PlanCompletionServiceTest` directly proves deterministic evidence preflight, artifact collection/rejection and realpath confinement, clean validator context, planning-only model routing, strict response parsing, safe feedback, and complete/incomplete transitions.
- No configuration, artifact, parse, validator, or provider failure can mark a plan complete; no execution model is selected; raw artifact/model data and path details are absent from feedback.
- Phase 07 required wiring remains intact, all focused validation passes, and the validator review-and-repair gate reports PASS.
