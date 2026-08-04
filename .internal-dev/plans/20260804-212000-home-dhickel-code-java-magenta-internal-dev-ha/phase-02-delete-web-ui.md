# Phase 02 — Delete Web/UI Surface

## Context

Magenta's web layer (`api/web/`, `src/main/resources/static/`, SimplyPages, HTMX, webjars, Playwright tests) is application-specific and must be deleted before the framework extraction. This phase removes all web controllers, static resources, SimplyPages components, HTMX assets, webjar dependencies, web-related test code, and Playwright artifacts. It also renames `ChatStreamEvent` to `TurnEvent` as an intermediate step — stripping web-only fields (`ChatPlanState`, `renderedHtml`, `thinkingHtml`, `OutputsChanged` subtype) in preparation for the framework's `TurnEvent` sealed hierarchy created from scratch in Phase 07. The application continues to exist as a non-web Spring Boot service after this phase.

## Goal

Remove all web controllers, static resources, SimplyPages, HTMX, and web test packages; drop web dependencies from POM; rename ChatStreamEvent to TurnEvent

## In Scope

**Write Targets**: pom.xml, src/main/java/io/mindspice/magenta2/api/web, src/main/resources/static, src/test/java/io/mindspice/magenta2/api/web, playwright.config.js, test-results, src/main/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEvent.java, src/main/java/io/mindspice/magenta2/ai/chat/model/TurnEvent.java, src/main/resources/application.yml

- Delete `src/main/java/io/mindspice/magenta2/api/web/` directory and all contents (~50 files including `selector/` sub-package)
- Delete `src/main/resources/static/` directory and all contents (`css/`, `js/`, `favicon.ico`)
- Delete `src/test/java/io/mindspice/magenta2/api/web/` directory and all contents (~28 test files including `selector/` test sub-package)
- Delete `playwright.config.js`
- Delete `test-results/` directory
- Delete `tests/playwright/` directory (`public-alpha-harness.spec.js`, `README.md`)
- Delete `artifacts/playwright/` directory (historical Playwright run evidence)
- Remove from `pom.xml`: `spring-boot-starter-web`, `spring-boot-starter-webmvc-test`, `simplypages`, `webjars-locator-core`, `htmx.org` (5 dependency blocks)
- Rename `ChatStreamEvent` → `TurnEvent`: create `TurnEvent.java`, delete `ChatStreamEvent.java`, update all surviving references
- Strip web-only fields from the renamed `TurnEvent` sealed interface: remove `ChatPlanState planState` from all subtypes, remove `String renderedHtml` and `String thinkingHtml` from subtypes that carry them, remove the `OutputsChanged` subtype entirely
- Update `ChatStreamEventSerializationTest.java` (at `src/test/java/io/mindspice/magenta2/ai/chat/model/` — survives deletion) to match renamed class and stripped fields
- Remove web-specific Spring configuration from `application.yml`: `server.port`, `spring.web.resources.chain.enabled`
- Fix any compilation errors from removed web imports in retained code (none expected; inspection confirms zero cross-imports from `ai/` or `core/` into `api/web/`)

## Out of Scope

- Modifying non-web Java source files except as needed to fix compilation after web deletion
- Deleting `ChatPlanState.java` — it survives Phase 02 and is deleted in Phase 05
- Deleting `ChatHistory.java`, `ChatResponse.java`, or any other model class that references `ChatPlanState`
- Creating any framework types
- Changing `ChatService` or its dependencies
- Deleting app-shell packages (orchestration, skills, plan, task, etc.) — that is Phase 05
- Deleting Magenta migration files or `SchemaMigrationRunner` — that is Phase 05

## Dependencies

phase-01-scaffold-baseline.md

## Constraints

- All preserved non-web tests must continue to pass
- Context must start without embedded Tomcat (verify: no `TomcatWebServer` in startup logs)
- `Magenta2Application.java` has no `@EnableWebMvc` or web-specific configuration (confirmed by inspection); no modification needed
- Surviving code must compile after all deletions
- `ChatPlanState.java` must remain on disk and unchanged
- `ChatStreamEventSerializationTest.java` must be updated (renamed, adapted), not deleted
- The renamed `TurnEvent` is a temporary intermediate; it does not need to match the final framework `TurnEvent` sealed hierarchy

## Implementation Steps

### 1. Remove POM dependencies

Delete the following five dependency blocks from `pom.xml`:

| Dependency | GroupId | ArtifactId |
|------------|---------|------------|
| `spring-boot-starter-web` | `org.springframework.boot` | `spring-boot-starter-web` |
| `htmx.org` | `org.webjars.npm` | `htmx.org` |
| `webjars-locator-core` | `org.webjars` | `webjars-locator-core` |
| `simplypages` | `io.mindspice` | `simplypages` |
| `spring-boot-starter-webmvc-test` | `org.springframework.boot` | `spring-boot-starter-webmvc-test` |

### 2. Delete web source directories and artifacts

Delete the following directories and files from disk (use `rm -rf` for directories):

```
src/main/java/io/mindspice/magenta2/api/web/
src/main/resources/static/
src/test/java/io/mindspice/magenta2/api/web/
tests/playwright/
artifacts/playwright/
test-results/
playwright.config.js
```

### 3. Fix compilation errors

Run `mvn compile` and fix any compilation errors. Based on codebase inspection:

- Zero surviving files in `src/main/java/io/mindspice/magenta2/ai/` or `src/main/java/io/mindspice/magenta2/core/` import from `io.mindspice.magenta2.api.web`. No cross-reference fixes are expected.
- `Magenta2Application.java` has no `@EnableWebMvc`, `WebMvcConfigurer`, or web-specific configuration. No application class changes are needed.
- If unexpected compilation errors arise from deleted web dependencies affecting surviving Spring components (e.g., `@RestController` annotations on non-web beans), resolve them by removing the unused Spring MVC annotations.

### 4. Rename ChatStreamEvent → TurnEvent

This is the core transformation step. Work carefully: the sealed interface is rewritten, not mechanically refactored.

**4a. Create `TurnEvent.java`** at `src/main/java/io/mindspice/magenta2/ai/chat/model/TurnEvent.java` with the following content:

- Package: `io.mindspice.magenta2.ai.chat.model`
- Sealed interface named `TurnEvent` (rename from `ChatStreamEvent`)
- Same subtypes with the following field removals applied:

| Subtype | Remove these fields | Keep these fields |
|---------|-------------------|-------------------|
| `Start` | `ChatPlanState planState` | `String conversationId`, `String model`, `String turnId`, `String interruptToken` |
| `Chunk` | `String renderedHtml`, `String thinkingHtml`, `ChatPlanState planState` | `String text`, `ContextUsage contextUsage` |
| `Tool` | `ChatPlanState planState` | `ChatToolActivity toolActivity`, `ContextUsage contextUsage` |
| `SystemNotice` | `String renderedHtml`, `ChatPlanState planState` | `String text`, `ContextUsage contextUsage` |
| `Interrupt` | `ChatPlanState planState` | `String text`, `ContextUsage contextUsage` |
| `Context` | `ChatPlanState planState` | `ContextUsage contextUsage` |
| `Done` | `String renderedHtml`, `ChatPlanState planState` | `String conversationId`, `String model`, `String text`, `ContextUsage contextUsage` |
| `Error` | *(none)* | `String message` |
| `OutputsChanged` | **Remove entire subtype** — tied to deleted file-mutating tools; no equivalent in framework | *(none)* |

- Remove the import for `ChatPlanState` (no longer needed by any subtype)
- Update the Javadoc: `Typed stream event hierarchy. Intermediate form; replaced by framework TurnEvent in Phase 07.`

**4b. Delete `ChatStreamEvent.java`.**

**4c. Update surviving references.** The only surviving reference to `ChatStreamEvent` outside the deleted `api/web/` tree is:

- `src/test/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEventSerializationTest.java`

Update this test file:
- Rename class to `TurnEventSerializationTest`
- Replace all `ChatStreamEvent.` references with `TurnEvent.`
- Remove all `ChatPlanState.normal()` constructor arguments from event instantiations
- Remove `renderedHtml` and `thinkingHtml` assertions from `Chunk`, `SystemNotice`, `Done` test methods
- Remove `planState` existence assertions from all test methods
- Remove the `outputsChangedEventSerializesConversationId` test method entirely (subtype deleted)
- Remove the `import io.mindspice.magenta2.ai.chat.model.ChatPlanState` import if present (should drop naturally with `ChatPlanState.normal()` removal)
- Remove `renderedHtml` field assertions from the `chunkEventSerializesFieldsReadByBrowser`, `systemNoticeEventSerializesFieldsReadByBrowser`, and `doneEventSerializesFieldsReadByBrowser` test methods

**⚠️ Do NOT delete `ChatPlanState.java`.** It is still referenced by `ChatService.java`, `ChatHistory.java`, `ChatResponse.java`, `PlanService.java`, and `TerminalTurnRepair.java`. It will be deleted in Phase 05.

### 5. Adapt Spring Boot configuration

Edit `src/main/resources/application.yml`:

- Remove the `server.port: 8080` line (and the enclosing `server:` block if it becomes empty)
- Remove the `spring.web.resources.chain.enabled: true` block. If the `spring:` key becomes empty except for `datasource`, `sql`, `autoconfigure`, and `ai` sub-keys, keep those sub-keys intact.

Result: the application becomes headless. Spring Boot will not start an embedded server because `spring-boot-starter-web` and `spring-boot-starter-webflux` are both absent from the classpath.

### 6. Verify

Run each verification step and confirm PASS before proceeding:

**6a. Compile:** `mvn compile -pl .` (root module only; turnloop-core/autoconfigure modules from Phase 01 are not yet populated with source)

**6b. Test:** `mvn test -pl .` — all surviving non-web tests must pass. The renamed `TurnEventSerializationTest` must pass.

**6c. Deletion audit:**
```bash
test ! -d src/main/java/io/mindspice/magenta2/api/web
test ! -d src/main/resources/static
test ! -d src/test/java/io/mindspice/magenta2/api/web
test ! -f playwright.config.js
test ! -d test-results
test ! -d tests/playwright
test ! -d artifacts/playwright
test ! -f src/main/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEvent.java
test -f src/main/java/io/mindspice/magenta2/ai/chat/model/TurnEvent.java
```

**6d. Rename audit:** `grep -r 'ChatStreamEvent' src/main/java/ src/test/java/` must return zero results.

**6e. Cross-reference audit:** `grep -r 'import io.mindspice.magenta2.api.web' src/main/java/io/mindspice/magenta2/{ai,core}/` must return zero results.

**6f. POM audit:** `grep -E 'spring-boot-starter-web|htmx\.org|webjars-locator-core|simplypages|spring-boot-starter-webmvc-test' pom.xml` must return zero results.

**6g. Context startup smoke test:** Start the application (`timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0` or equivalent) and confirm:
- Application context initializes successfully
- `TomcatWebServer` does NOT appear in startup log output
- No `SseEmitter` or servlet-related errors appear

**6h. ChatPlanState preservation:** `test -f src/main/java/io/mindspice/magenta2/ai/chat/model/ChatPlanState.java` must confirm the file still exists.

## Required Guides

- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/AGENTS.md`

## Technical Guidance

- After removing `spring-boot-starter-web`, Spring Boot will not start an embedded server. The application should still start, initialize its `@Service` beans, and either stay alive (scheduler/actuator keeps it up) or exit cleanly. Both outcomes are acceptable.
- The `ChatStreamEvent` → `TurnEvent` rename is a temporary intermediate step. The final `TurnEvent` sealed hierarchy will be created from scratch in Phase 07 per the handoff Appendix A. Do not attempt to match the final framework contract in this phase.
- `ChatStreamEventSerializationTest.java` is at `src/test/java/io/mindspice/magenta2/ai/chat/model/` — do NOT delete it. Update it in place.
- `ChatPlanState.java` must not be deleted. It is still a dependency of surviving code (`ChatService`, `ChatHistory`, `ChatResponse`, `PlanService`, `TerminalTurnRepair`).

## Validation

- `src/main/java/io/mindspice/magenta2/api/web` does not exist on disk
- `src/main/resources/static` does not exist on disk
- `src/test/java/io/mindspice/magenta2/api/web` does not exist on disk
- `tests/playwright/`, `artifacts/playwright/`, `test-results/` do not exist on disk
- `playwright.config.js` does not exist on disk
- `pom.xml` contains none of the five web dependencies
- `ChatStreamEvent.java` does not exist; `TurnEvent.java` exists without `ChatPlanState` references, `renderedHtml`, `thinkingHtml`, or `OutputsChanged` subtype
- `ChatPlanState.java` still exists on disk (preserved for surviving references)
- `ChatStreamEventSerializationTest.java` is updated and renamed to `TurnEventSerializationTest`
- Zero grep results for `ChatStreamEvent` in `src/`
- Zero grep results for `import io.mindspice.magenta2.api.web` in retained `ai/` and `core/` packages
- `mvn compile` succeeds
- Surviving non-web tests pass
- Application context starts without `TomcatWebServer` in log output

## Exit Criteria

- Web layer fully removed from source tree, POM, test tree, and Playwright artifacts
- Surviving code compiles
- Surviving tests pass (including renamed `TurnEventSerializationTest`)
- Application starts as a headless service (no embedded Tomcat)
- `ChatStreamEvent` fully replaced by intermediate `TurnEvent`; zero stale references remain
