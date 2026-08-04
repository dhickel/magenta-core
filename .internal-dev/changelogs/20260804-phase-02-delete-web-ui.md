# Changelog — Phase 02: Delete Web/UI Surface

## Date
2026-08-04

## Git Commit

fda1b23a2e05b9f1ca1d7442a01c481b24b68fe2

## Change Summary
Removed the entire web/UI surface from the Magenta application (`magenta-app/`) as step two of the mindspice-core framework extraction:
- Deleted `api/web/` (~53 files incl. `selector/`), `src/main/resources/static/`, test `api/web/` tree (~21 files), `playwright.config.js`, `test-results/`, `tests/playwright/`, `artifacts/playwright/`.
- Removed 5 web dependency blocks from `magenta-app/pom.xml` (spring-boot-starter-web, htmx.org, webjars-locator-core, simplypages, spring-boot-starter-webmvc-test); added explicit test-scoped `spring-boot-starter-test` (was transitively dropped by the web removals, breaking surviving `@SpringBootTest` compilation).
- Renamed `ChatStreamEvent` → `TurnEvent` as an intermediate type: removed `ChatPlanState planState` from all 8 subtypes, removed `renderedHtml`/`thinkingHtml` from Chunk/SystemNotice/Done, deleted the `OutputsChanged` subtype, Javadoc marks it as intermediate (replaced by the framework sealed hierarchy in Phase 07). `ChatPlanState.java` preserved (still referenced by surviving code).
- Renamed `ChatStreamEventSerializationTest` → `TurnEventSerializationTest` with adapted assertions (8 subtype tests).
- `application.yml`: removed `server:` block and `spring.web.resources.chain.enabled`; datasource/sql/autoconfigure/ai keys intact.
- Adapted `ScheduleReactionFeatureParitySpringTest` (removed `@AutoConfigureMockMvc`, MockMvc, web imports, one HTMX fragment test method) — minimal fix for still-surviving orchestration code (deleted in Phase 05).

## Files
- Deleted: `magenta-app/src/main/java/io/mindspice/magenta2/api/web/**`, `magenta-app/src/main/resources/static/**`, `magenta-app/src/test/java/io/mindspice/magenta2/api/web/**`, `playwright.config.js`, `test-results/**`, `tests/playwright/**`, `artifacts/playwright/**`, `ChatStreamEvent.java`, `ChatStreamEventSerializationTest.java`
- Created: `TurnEvent.java`, `TurnEventSerializationTest.java`
- Modified: `magenta-app/pom.xml`, `magenta-app/src/main/resources/application.yml`, `ScheduleReactionFeatureParitySpringTest.java`

## Behavioral Impact
Magenta is now a headless Spring Boot service: no embedded Tomcat, no HTTP endpoints, no static resources. Context starts without `TomcatWebServer`/`SseEmitter`/servlet machinery (verified in startup logs). All non-web tests pass: 1041 tests, 0 failures. Turn stream events carry no HTML-rendering or plan-state payloads.

## Specification Impact
None. This is an extraction-phase deletion consistent with the framework plan (`.internal-dev/plans/20260804-212000-home-dhickel-code-java-magenta-internal-dev-ha`); the web surface was application-specific and excluded from the framework contract. Specification updates for the framework shape occur in the phases that create it (06–11).

## Risks
- `TurnEvent` is an intermediate type; Phase 07 creates the real framework sealed hierarchy from scratch (any drift there is expected and planned).
- Stale surefire report filenames reference the old test class name (build artifacts only, regenerated on next test run).
- Full turn-loop execution requires Ollama at http://192.168.1.112:11434 (environment dependency; context startup does not require it).
- The pre-existing flaky `ChatControllerTest` was in the deleted web test tree and is gone.

## Follow-up Items
- Phase 03: create temporary SPI interfaces.
- Phase 05: delete app-shell packages (orchestration, skills, plan, task, project, system, job, execution) and `ChatPlanState`/migrations.
