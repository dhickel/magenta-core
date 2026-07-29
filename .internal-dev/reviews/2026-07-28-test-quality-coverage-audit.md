# Test Quality and Coverage Audit

## Scope

Static audit of `src/test/java/` against `src/main/java/` (109 test files, 357 production Java files), plus a full Maven test run. `pom.xml` has no JaCoCo/coverage-report plugin, so this review reports direct-test absence/minimality rather than executable line coverage.

## Findings

### Missing or minimal coverage

| Severity | Production/test path | Finding and recommendation |
| --- | --- | --- |
| high | `src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanService.java`; `src/test/java/io/mindspice/magenta2/ai/chat/plan/PlanServiceTest.java` | A 1,966-line lifecycle service has one reflection test asserting its field types. Add behavior tests for plan/task CRUD, approval/state transitions, question and answer sequencing, invalid IDs/blank inputs, execution reservation/failure cleanup, output publication, and null optional collaborators. |
| high | `src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanCompletionService.java` | No direct test references this 638-line fail-closed completion validator, including its `validateResponseForTesting` seam. Add unit tests for empty/malformed JSON, schema omissions, invalid statuses, no evidence, missing/duplicate criteria, empty final message, artifact escape/read failure, no validator/configuration, and only a fully verified result marking the plan completed. |
| high | `src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatActionProposalService.java`; `src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatBoundedReadService.java` | No tests exercise proposal confirmation or bounded read callbacks. Add tests for cross-agent denial, absent/stale binding, malformed callback JSON, invalid action/state transitions, digest/idempotency collision, concurrent confirmation, lifecycle failure, depth/limit/response-byte caps, and guarantee no file bytes or paths are disclosed. |
| high | `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillManagementService.java` | No direct test references a 483-line filesystem-mutating service. Add temp-directory tests for traversal/symlink rejection, missing/wrong file type, invalid UTF-8, BOM and CRLF preservation, size limits, duplicate file/directory conflicts, optional-directory allowlist, refresh on `SKILL.md`, and assignment error translation. |
| high | `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssembler.java`; `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceMdResolver.java` | No direct tests cover trusted prompt-policy construction or `WORKSPACE.md` confinement. Add matrix tests for every terminal context outcome, CHAT descriptor rejection, personal/materialization policy facts, policy bounds, materialization mismatch, absent file, traversal, symlink escape, non-regular file, oversized content, and no authority leakage from untrusted content. |
| medium | `src/main/java/io/mindspice/magenta2/api/web/AgentContextController.java`; `src/main/java/io/mindspice/magenta2/api/web/AgentContextFragments.java` | No direct controller/fragment tests cover 250 lines of routes and 236 lines of UI rendering. Add MockMvc/controller tests for not-found, stale-version conflict, invalid selection parameters, unavailable-service 503, materialization provision outcomes, JSON payload shape, HTML escaping, and HTMX error/OOB fragments. |
| medium | `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunMeasurementRepository.java`; `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunSummaryService.java` | No direct repository tests and no dedicated summary-service test. Add SQLite repository tests for reserve/complete idempotency, identity mismatch, invalid records, scoped queries, null values, and aggregation; add pure service tests for every native-status mapping, unavailable lifecycle lookup, denominator rules, pagination bounds, and elapsed-time edge cases. |
| medium | `src/main/java/io/mindspice/magenta2/api/web/SystemChatController.java`; `src/main/java/io/mindspice/magenta2/api/web/WorkflowStreamSupport.java` | No direct web/SSE tests cover system-chat routes or workflow stream event mapping. Add focused controller tests for agent/profile/message validation, unavailable profile, proposal ownership/confirmation, and SSE terminal/error events; add pure event mapping tests for every terminal status and null output/error cases. |
| medium | `pom.xml` | No coverage report or threshold is configured, so direct-reference analysis cannot establish line/branch coverage. Add JaCoCo XML/HTML reporting in CI and ratchet branch coverage for safety-critical packages (`chat.system`, `chat.plan`, `skills`, `context`, `workspaces`). |

### Flaky/time-dependent tests

| Severity | File path | Finding and recommendation |
| --- | --- | --- |
| high | `src/test/java/io/mindspice/magenta2/ai/agent/job/AgentJobServiceTest.java` | Two tests use `Thread.sleep(100)` after async submission. Replace sleep with a completion latch/future exposed by the test double, await it with a bounded timeout, and always shut down the executor in `finally`. |
| high | `src/test/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRunnerTest.java` | Parallelism is asserted with a 200-ms worker sleep and `<900 ms` wall-clock threshold; terminal/wait polling uses 50-ms sleeps. Use latches/barriers to prove simultaneous worker start and a condition-based completion signal from the runner instead of CPU/scheduler timing. |
| medium | `src/test/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolServiceTest.java` | Interruption waits a fixed 500 ms for `sleep` to begin. Inject a process-launch abstraction or use a test command that signals readiness through a latch/file, then interrupt after readiness. |
| low | `src/test/java/io/mindspice/magenta2/ai/skills/AgentSkillCatalogServiceTest.java` | The edit test sleeps 5 ms solely to force timestamp ordering. Assert the changed digest/content and a nondecreasing scan time, or inject a `Clock` for deterministic scan timestamps. |

### Weak assertions and implementation-detail tests

| Severity | File path | Finding and recommendation |
| --- | --- | --- |
| high | `src/test/java/io/mindspice/magenta2/ai/chat/plan/PlanServiceTest.java` | The sole test checks private field types by reflection and includes the tautology `assertThat(WorkspaceDirectoryService.class).isNotNull()`. Replace it with behavior assertions described above; retain a dependency-boundary test only if it verifies an observable contract. |
| low | `src/test/java/io/mindspice/magenta2/ai/config/user/ExternalAiConfigLoaderTest.java` | Uses JUnit `assertNotNull`/`assertTrue` for parsed records and collections despite AssertJ elsewhere. Use chained AssertJ assertions (`containsKey`, `extracting`, `containsExactly`) to identify the failed configuration field directly. Most other `assertTrue` uses in the suite are exact boolean predicates and are not findings. |

### Edge-case gaps

| Severity | File path | Finding and recommendation |
| --- | --- | --- |
| medium | `src/test/java/io/mindspice/magenta2/ai/chat/repository/ChatMemoryRepositoryTest.java` | One test covers only assistant metadata round-trip. Add user/system round trips, multiple-message ordering, replacement semantics, delete, empty/missing conversation, sorted IDs, legacy schema missing metadata column, invalid message type, and malformed metadata JSON. |
| medium | `src/test/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepositoryTest.java` | Three tests focus on migration and one successful attribution persistence. Add definition/run not-found, delete/cascade, invalid JSON, null optional fields, status transitions, multi-run ordering, and corrupted/partially migrated row behavior. |

### Slow tests that can be narrowed

| Severity | File path | Finding and recommendation |
| --- | --- | --- |
| medium | `src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleReactionFeatureParitySpringTest.java` | A full `@SpringBootTest` runs direct schedule/reaction validation and dispatch assertions alongside one MVC fragment test. Keep the fragment route test integrated, but move the direct service validation/dispatch cases to focused service/repository tests; reserve the Spring context for wiring and route coverage. |

### Test-only utility review

No confirmed duplicate test helper was found. `OutputArtifactTestSchema` intentionally executes migration-owned schema for repository tests, and `WorkspaceDirectoryServiceTestSupport` supplies construction collaborators not available as a production utility. Do not move either into production solely for tests.

### Naming and orphan review

No confirmed orphan test file was found. Basename mismatches are deliberate aggregate/contract tests (for example, route binding, lifecycle integration, and workspace identity) and still target live production behavior. Test method names are generally descriptive; no broad naming violation was identified.

## Risk Assessment

The most material blind spots are fail-closed plan completion, restricted system-chat authority, skill filesystem mutation, and trusted prompt context. They lack direct tests despite security and lifecycle impact. Timing-based async tests introduce intermittent CI risk even though the current full run passed.

## Recommendations

1. Add behavior tests for the high-severity services before their next feature work.
2. Replace all four fixed sleeps/time-based parallelism checks with deterministic synchronization.
3. Add JaCoCo reporting and a ratcheted CI threshold.
4. Split direct service assertions out of the schedule/reaction full-context test.

## Follow-ups

- `mvn test` passed: 913 tests, 0 failures/errors/skips, 29.137 seconds.
- This audit made no production or test-code changes.
