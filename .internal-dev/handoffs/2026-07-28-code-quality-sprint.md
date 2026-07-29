# Code Quality Sprint Handoff

## Context

After a sustained period of feature development across multiple sprints (workspace establishment, skills, observability, task runs, project UI), four independent audit agents analyzed the Magenta codebase for structural, style, test, and architecture issues. This handoff synthesizes their findings into a prioritized, phased improvement plan.

## Objective

Address high-impact, low-effort correctness and hygiene issues first, then tackle structural debt in later phases. The sprint delivers safer production behavior, reliable test infrastructure, and cleaner boundaries before large-scale decomposition.

## Findings Summary

### Quick Wins (High Impact, Small Effort)

1. **Clear `heldMutationKind` ThreadLocal reliably** — Stale mutation context can leak across reused threads in `WorkspaceExplorerService`.
2. **Shorten `SystemChatService.invoke` transaction boundary** — `@Transactional` currently spans a remote model call, holding SQLite locks.
3. **Make artifact relational/FTS extraction atomic** — `ArtifactSearchService` updates FTS and extraction documents without a transaction boundary.
4. **Fix false-404 and blanket-409 exception mappings** — Agent-context unavailability and all chat-file I/O errors become 404. `IllegalStateException` globally maps to 409.
5. **Make production-required dependencies mandatory** — `@Autowired(required=false)` on beans required in production quietly selects null behavior.
6. **Resolve silent exception swallowing** — 15+ catch blocks with empty `{}` and no logging.
7. **Resolve `OutputArtifactService` deprecation mismatch** — 17 deprecated methods still called from 8 files. Either migrate callers or un-deprecate.
8. **Replace `Thread.sleep` with deterministic synchronization** — `AgentJobServiceTest` and `WorkflowRunnerTest` use sleep-based timing.
9. **Add `PlanCompletionService` direct tests** — Zero test coverage on a 638-line service.
10. **Add trust-boundary tests** — `TrustedContextPolicyAssembler` and `WorkspaceMdResolver` have no direct tests.

### Structural Improvements (High Impact, Large Effort)

11. **Enforce workspace-scoped runtime run endpoints** — Known API drift: run reads lack workspace scope validation.
12. **Decompose `OrchestrationController`** — 8,780 lines, dozens of endpoint groups and fragment renderers.
13. **Stabilize and decompose `ai.chat.plan`** — 5 circular package dependencies, `PlanService` at 1,966 lines with 1 reflection-only test.
14. **Restore controller/service/repository boundaries** — 5+ controllers/fragments bypass services and call repositories directly. Raw JdbcTemplate in services.
15. **Build critical-service regression suite** — Safety net before large refactors.

### Medium Priority (Do Later)

- Centralize 16 fragmented `ensureSchema()` methods into `SchemaBootstrapper`
- Extract HTML generation from `ChatService` and `PlanService` into rendering components
- Add route/fragment tests for 6 uncovered web classes
- Configure JaCoCo coverage reporting
- Replace 68 magic role string literals with constants
- Return HTTP 201 for workspace creation
- Consolidate config under Spring configuration properties
- Remove dead code: 19 unused private methods, `BindingResolver`, `WorkflowExecutionObserver`
- Logger naming consistency, unused imports (incremental)

## Settled Decisions

- Fix correctness and safety issues before structural refactors
- Each quick win is independently completable and testable
- Large structural items (controller decomposition, plan subsystem) form their own later sprint
- Tests added as part of fixes, not as a separate pass
- No new features introduced — pure quality/correctness work

## Constraints

- 913 existing tests must continue to pass (1 known pre-existing flaky test: `ChatControllerTest` ordering issue tracked as GitHub #41)
- No schema or API contract changes beyond the documented drift fixes
- HTMX-first, SimplyPages, dashboard visual language remain unchanged
- Follow project AGENTS.md policies

## Scope

In scope: Top 10 quick wins, focused tests for uncovered critical services, error mapping fixes, transaction boundary fixes, dependency wiring fixes.

Out of scope: Large refactors (controller decomposition, plan subsystem stabilization, boundary restoration), new features, UI changes, schema migrations.
