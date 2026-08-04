# Changelog — Phase 01: Maven Module Scaffolding

## Date
2026-08-04

## Git Commit

336eb5f0ccc1199f5ad360982e1412504a6022cc

## Change Summary
Converted the Magenta repository into a Maven multi-module reactor in preparation for extracting a reusable LLM turn-loop framework (branch `core`):
- Root `pom.xml` is now a pom-packaging aggregator with `<modules>`: `mindspice-core`, `mindspice-core-autoconfigure`, `magenta-app`.
- `mindspice-core/` created: framework core module with Maven Enforcer `bannedDependencies` (blocks spring-boot-starter-web, spring-web, spring-webmvc, jakarta.servlet-api, spring-boot-starter-webmvc-test) and `maven-jar-plugin` manifest `Automatic-Module-Name: io.mindspice.core`.
- `mindspice-core-autoconfigure/` created: depends on mindspice-core, spring-boot-autoconfigure, sqlite-jdbc 3.50.3.0, optional spring-boot-starter-actuator.
- `magenta-app/` created: all existing Magenta sources moved 1:1 from root `src/` (387 main + 119 test Java files, verified exact), all original dependencies preserved.
- `start.sh` updated to `mvn -pl magenta-app spring-boot:run` with working directory pinned to the repo root.

Naming override (user-approved): framework identity is `io.mindspice.core` / `mindspice-core` / `mindspice-core-autoconfigure` (plan's original `io.mindspice.turnloop` / `turnloop-core` / `turnloop-autoconfigure` superseded).

## Files
- `pom.xml` (modified: aggregator + modules)
- `start.sh` (modified: -pl magenta-app)
- `magenta-app/pom.xml` (new)
- `mindspice-core/pom.xml` (new)
- `mindspice-core-autoconfigure/pom.xml` (new)
- `magenta-app/src/**` (moved from root `src/**`, unchanged content)
- `.gitkeep` files in the four empty source trees of both framework modules (8 total)

## Behavioral Impact
No runtime behavior change. Build layout only: `mvn clean verify` at root now builds all three modules; `mvn spring-boot:run` for the app requires `-pl magenta-app` (start.sh handles this). Build verified: 1271/1272 tests pass (1 pre-existing flaky ChatControllerTest passes in isolation); context startup verified.

## Specification Impact
None for behavior. This is the first structural step of the framework extraction plan (`.internal-dev/plans/20260804-212000-home-dhickel-code-java-magenta-internal-dev-ha`). Later phases will update `specifications/architecture.md`, `services.md`, `service-graph.md`, and `api.md` to the framework shape; deferred to the phase where those contracts actually change.

## Risks
- Later plan phases reference `src/main/java/...` paths that now require the `magenta-app/` prefix.
- `magenta-app` submodule was a required deviation: Maven rejects jar packaging on a POM that declares `<modules>`.
- Pre-existing flaky test: `ChatControllerTest.outputsChangedSideChannelIsNotPlanExecutionFinalMessage` (async race, passes in isolation).

## Follow-up Items
- Phase 02 begins deletion of the web UI and static resources.
- Record the `magenta-app/` path-prefix consequence in every subsequent phase brief.
