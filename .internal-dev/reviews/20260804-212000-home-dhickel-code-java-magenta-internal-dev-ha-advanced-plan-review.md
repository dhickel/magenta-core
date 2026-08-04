# Advanced Plan Corrective Review Summary

## Scope

### decomposition.md

This review corrects the prior rejected 8-phase extra-large plan for extracting Magenta's chat turn-loop into the reusable `turnloop-core` framework. The handoff at `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md` is the authoritative design contract. The prior submission failed plan validation with 25 findings across phase-budget, target-safety, integration-contract, shape, phase-metadata, and dependency categories. This review diagnoses each failure class, prescribes fixes, and delivers the complete corrected 14-phase plan set.

### concepts.md

This review evaluates `concepts.md` against the authoritative handoff at `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md` and the 14 plan phase files. It checks architecture consistency, cross-phase guidance fidelity, constraint completeness, final validation coverage, and production-scope enforcement. It does not audit the phase files themselves beyond their alignment with `concepts.md`.

### orchestration.md

Corrective review of the TurnLoop Core framework extraction orchestration plan — a 14-phase, extra-large migration that extracts Magenta's `ChatService.toolChat()` turn-loop state machine into a standalone `turnloop-core` framework with companion `turnloop-autoconfigure` Spring Boot starter, then re-integrates Magenta as an embedding consumer. The plan covers Maven module scaffolding, aggressive deletion of web/UI and app-shell packages, 20 SPI interfaces with production-quality SQLite defaults, a 6-state turn engine, a `TurnLoop` facade with builder, auto-configuration with health/metrics/SSE adapters, comprehensive test suites, documentation with Javadoc, and final reintegration. Fourteen sequential phases with strict dependency ordering and per-phase PASS gates.

### phase-01-scaffold-baseline.md

Corrective review of `phase-01-scaffold-baseline.md` against the orchestration ledger, shared `concepts.md` definitions, handoff Appendix C gate inventory, and the review criteria: one-agent executability, metadata alignment, production-scope enforcement, implementation guidance, validation, and exit criteria.

### phase-02-delete-web-ui.md

**Reviewed phase**: `phase-02-delete-web-ui.md`  
**Reviewed against**: `concepts.md`, `orchestration.md` Phase Ledger, actual Magenta codebase (`src/main/java/io/mindspice/magenta2/`, `pom.xml`, `application.yml`)

**Phase intent**: Delete all web controllers, static resources, SimplyPages, HTMX, webjar dependencies, Playwright config, and web test packages from Magenta. Rename `ChatStreamEvent` to `TurnEvent` as an intermediate step before the framework's `TurnEvent` sealed hierarchy is created from scratch in Phase 07. Strip web-only fields (`ChatPlanState`, `renderedHtml`, `thinkingHtml`) from the renamed class. Surviving non-web code must compile and non-web tests must pass.

**Ledger alignment**: The Goal, Dependencies, and Write Targets in the corrected phase file match the orchestration ledger verbatim.

### phase-03-create-spi-interfaces.md

Phase 03 creates four temporary SPI interfaces (`InteractionHandler`, `PromptContributor`, `CompletionDetector`, `RepairStrategy`) in `ai/chat/service/turn/` with signatures aligned to handoff Appendix A, and adapts three existing components (`PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`) to implement them. The phase is a pure bridge step: it introduces SPI contracts without modifying `ChatService` internals, enabling Phase 04 to sever the 13 app-shell collaborator references behind typed interfaces.

The stated write targets (7 files) match the orchestration ledger exactly. The scope is correct and well-bounded.

### phase-04-remove-chatservice-deps.md

Reviewed `phase-04-remove-chatservice-deps.md` against the corrected `concepts.md`, `orchestration.md`, the handoff at `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`, and the live `ChatService.java` (3009 lines) plus `ChatServiceTest.java` (1443 lines).

### phase-05-delete-app-shell.md

Phase 05 deletes 16 app-shell directory trees, 6 Magenta-specific type files, `TrustedContextPolicyAssembler`, `InteractionQuestionTools`, 20 migration files, `SchemaMigrationRunner`, and corresponding test files. It then simplifies `AiConfig` (5→1 constructors, removal of agent/skill/plan/shell fields) and `AiUserConfigConfiguration` (removal of agent resolution and skill beans). The phase is exclusively deletion and surgical simplification — no framework types are created.

The scope is complete and correctly matches the concepts.md mandate and the handoff target inventory. The ledger targets (28 entries) are canonical and verified for this phase.

### phase-06-core-types.md

Phase 06 creates the core data-carrier records, enums, and minimal dependency types in `turnloop-core` per handoff Appendix A. The phase assigns one unsplit agent session, which is correct: 19 files averaging ~25–40 lines each, totaling well under 1000 lines of straightforward record/class/enum definitions with no branching logic. The agent can validate each file against the handoff appendix as it writes.

Three cross-phase dependency types (TurnPhase, ToolCallback, ToolDefinition) must also be created in this phase because TurnDiagnostic and TurnContext reference them. These are trivial files (a 7-value enum, a two-method interface, a one-line record) and do not expand the phase beyond its practical token budget.

### phase-07-events-spis.md

This review covers exactly one corrected phase: `phase-07-events-spis.md`. Scope assessment checks metadata alignment with the canonical orchestration ledger, one-agent executability, dependency correctness, implementation guidance completeness, validation gate adequacy, and exit criteria against full production scope (no stubs, placeholders, or deferred work). The review does not edit any other phase file.

### phase-08-internal-persistence.md

Corrective review of `phase-08-internal-persistence.md` against the canonical orchestration ledger in the planning-review-draft (`orchestration.md`), the shared concepts (`concepts.md`), and the authoritative handoff (`20260804-211102-...br.md`) Appendix A (internal interfaces) and Appendix B (DDL). Three metadata validation failures were flagged by the workflow engine; this review diagnoses and corrects all three, assesses scope fit for single-agent execution, and produces a corrected phase file.

### phase-09-engine-utilities.md

Phase 09 is the largest implementation phase in the plan. It must produce the `DefaultTurnEngine` 6-state cycle faithfully extracted from `ChatService.toolChat()`, the `DefaultPromptAssembler` and `ContextManager` engine companions, four concrete SPI implementations (`DefaultToolRegistry`, `SerialTurnGate`, `CompositeThinkingExtractor`, `InMemoryInteractionStore`), two utilities (`MarkdownRenderer`, `TurnEventRenderer`), two example tools, POM updates, and a full Magenta code migration merging ~10 surviving Magenta classes into their framework equivalents while simplifying `ChatService`. The phase has 14 write targets listed in the ledger, plus the implicit engine-package companions not in the write-targets slice (the handoff specifies an engine builder and the orchestration goal names `DefaultPromptAssembler` and `ContextManager`).

The current phase file has several deficiencies:

1. **Truncated goal**: The goal line says "Create DefaultTurnEngine with 6-state cycle, MarkdownRenderer, TurnEventRenderer, example tools; move surviving Magenta code into framework packages; add optional dependencies to turnloop-core POM" — this omits `DefaultPromptAssembler`, `ContextManager`, `DefaultToolRegistry`, `SerialTurnGate`, `CompositeThinkingExtractor`, `InMemoryInteractionStore`, `MagentaWorkExecutor` deletion, `ToolTranscriptService` switch deletion, and the independent-compilation verification gate. The orchestration ledger goal must be copied verbatim.

2. **Write-targets drift**: The current `**Write Targets**` line uses directory-level paths (`engine/`, `example/`) and is missing the explicit per-file paths that the ledger records. The ledger targets are 14 individual `.java` file paths plus the POM plus two Magenta-source paths. The In Scope section must list the ledger targets verbatim.

3. **Missing engine companions**: `DefaultPromptAssembler` and `ContextManager` are not called out in the current Implementation Steps, In Scope, or Goal, yet the orchestration ledger explicitly requires them: the goal text names "DefaultPromptAssembler with 3-layer priority structure" and "ContextManager for windowed queries and token-budget management." These are first-class deliverables of this phase, not incidental.

4. **One-agent overload**: The phase as written would require creating ~2000–3500 lines of production code across ~14 new files, PLUS reading and merging/adapting ~10 surviving Magenta classes into framework equivalents created in prior phases, PLUS simplifying `ChatService.java` (a 2900-line file), PLUS POM updates, PLUS a virtual-thread audit, PLUS cross-module compilation verification. This easily exceeds the practical single-agent token ceiling of ~200K–300K. The engine extraction alone requires reading and faithfully replicating a ~540-line state-machine method from `ChatService.java` plus its ~400-line `ToolChatState` inner class and five handler methods. Combined with the code migration (which requires reading and understanding ~10 additional Magenta classes), the total context load is unreasonable for one session.

5. **Vague code migration guidance**: The current phase says "Move surviving Magenta code into framework packages" with a bullet list of class-to-class mappings, but gives no implementation-level detail on what merging means (extract, delete old, adapt imports, verify), no ordering for the merges, and no specific verification steps. For an implementation agent to execute this, each merge needs explicit guidance: which methods to extract from the Magenta class, which framework target to modify, what invariants to preserve, and what to delete afterward.

6. **Missing `TurnEventRenderer` signature detail**: The handoff specifies `TurnEventRenderer.json(ObjectMapper)` (accepts an ObjectMapper) and `TurnEventRenderer.sse()`. The current phase only says `json()` and `sse()`.

7. **Virtual-thread audit blocker**: Open Question #6 in concepts.md (target Spring AI version) is unresolved, and this phase's guidance says "audit virtual-thread safety of target Spring AI HTTP client before committing." This is a dependency on an unresolved decision. The phase should state that the engine commits with the `TurnExecutor` strategy configurable (virtual vs. fixed) and the audit finding determines the default, rather than blocking the phase on an upstream decision.

### phase-10-facade.md

Corrective review of `phase-10-facade.md` — the TurnLoop facade phase in the `turnloop-core` framework extraction plan. This phase sits at wave 10 (dependency: phase-09-engine-utilities.md) and must produce the `TurnLoop` interface, `DefaultTurnLoop` implementation, and `TurnLoopBuilder`. The review examines metadata alignment with the orchestration ledger, one-agent executability, implementation guidance depth, edge-case coverage, validation, and exit criteria.

### phase-11-autoconfigure.md

Reviewed phase-11-autoconfigure.md against the orchestration phase ledger, concepts.md, and the authoritative handoff (`.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`). The phase creates `turnloop-autoconfigure` Spring Boot auto-configuration: `TurnLoopProperties` with full nested config, `TurnLoopAutoConfiguration` wiring all 20 SPIs, `DataSource` auto-provisioning, `StreamLifecycle` SSE adapter, `TurnLoopHealthIndicator`, and `TurnLoopMetrics` Micrometer default.

**Verdict**: Scope is correct and well-bounded. The phase is a single-agent unit — 6 Java files + 1 config file + 1 POM update, all in one module. Tight coupling between Properties → AutoConfiguration → StreamLifecycle/Metrics/Health argues against splitting. No subphases required.

### phase-12-tests.md

Phase 12 aims to deliver the full test suite for `turnloop-core` and `turnloop-autoconfigure`: unit tests for all ~20 core types, all SPI default implementations, the `DefaultTurnEngine` state machine, utilities, integration tests with real Ollama, an auto-configuration smoke test, a concurrency stress test, `TurnLoopHealthIndicator` tests, `StreamLifecycle` SSE tests, and `TurnLoopProperties` validation tests.

### Missing targets

The current phase omits unit tests for framework components created in Phases 09–10 that must ship with test coverage:

- **`DefaultPromptAssembler`** (Phase 09) — 3-layer priority structure with ordered `PromptContributor` resolution
- **`ContextManager`** (Phase 09) — windowed queries, token-budget management, compaction triggering
- **`DefaultTurnLoop`** (Phase 10) — facade wiring, SPI delegation, `close()` lifecycle drain/cancel/force-release, `interrupt()` dispatch, `answer()` resume
- **`TurnLoopBuilder`** (Phase 10) — all setter methods, `build()` validation, missing-required-SPI detection
- **`SpringAiConversationMemoryAdapter`** (Phase 08) — bridging `TurnMessage` ↔ Spring AI `Message`

These are added to the corrected phase under subphase D.

### Scope boundaries

The current phase correctly excludes Javadoc, documentation, hardening verification scans, and JaCoCo enforcement — those are Phase 13's responsibility. The phase also correctly scopes unit tests to in-memory SQLite and integration tests to `turnloop-autoconfigure/src/test/`.

### phase-13-docs-hardening.md

Phase 13 is the documentation and hardening closeout: comprehensive Javadoc on every public SPI method and public type, 10 markdown documentation files, JaCoCo coverage enforcement at >80% line, and four hardening verification scans culminating in a passing `mvn clean verify` from root. It is the final framework-only phase before Magenta re-integration in Phase 14.

### phase-14-magenta-reintegration.md

Single-phase review of `phase-14-magenta-reintegration.md` — the final re-integration phase where Magenta becomes an embedding application consuming the completed `turnloop-core` framework. The review assesses metadata alignment against the orchestration ledger, one-agent executability, schedule consistency, implementation specificity, validation coverage, and exit criteria completeness against the shared concepts in `concepts.md` and the authoritative handoff at `.internal-dev/handoffs/20260804-211102-home-dhickel-code-java-magenta-internal-dev-br.md`.

## Findings

### decomposition.md

1. **Phase budget violation.** Extra-large scope requires 11–20 phases per `PHASE_BUDGETS["extra-large"]`. The prior plan had 8 phases. Root cause: lettered subphases within a single phase file do not increase the phase count for budget validation. Fix: split phases 03, 05, 06, and 07 across natural implementation boundaries, yielding 14 standalone phase files.

2. **Unsafe write targets.** Every target prefixed with `[DELETE]` triggered `assertSafeRelativePath` rejection because the bracket-wrapped token and trailing slashes on directory paths produced invalid path components. Root cause: `[DELETE] src/main/java/.../directory/` parsed as a single target string with embedded spaces and an empty trailing component after the final `/`. Fix: remove the `[DELETE]` prefix entirely; list every target as a plain canonical project-relative path with no trailing slash. Deletion intent is conveyed by the phase description, not by target syntax.

3. **Missing Final Integration structured contract.** The `Final Integration` section must contain exactly one line matching the pattern `- Integration: after all phases PASS, run final integration validation with <provider>/<model>:<level>.`. The prior plan omitted this section entirely. Fix: add the exact required line with `openai-codex/gpt-5.6-terra:high` per the orchestrate skill's fixed model contract.

4. **Orchestration section shape violation.** `orchestration.md` may contain only the six required level-two headings in order: Scope Size, Phase Ledger, Execution Waves, Model Assignments, Validation Gate, Final Integration. The prior plan had additional sections. Fix: strip all non-required sections.

5. **Phase metadata cross-consistency drift.** Every phase's `## Goal` first line, `## Dependencies` section, and `## In Scope` `**Write Targets**:` line must exactly match the orchestration Phase Ledger entries. The prior plan had mismatches between phase files and the ledger across all 8 phases. Fix: rewrite every phase with exact string alignment; every goal string, dependency string, and target list is now identity-matched between ledger and phase file.

6. **Dependencies format.** The `## Dependencies` section must contain exactly one non-code line that matches the ledger's `depends` value. The prior plan had multi-line or narrative dependency sections. Fix: each phase's Dependencies section now contains exactly one literal line (e.g., `phase-04-remove-chatservice-deps.md` or `none`).

7. **Write Targets marker count.** The `## In Scope` section must contain exactly one `**Write Targets**:` line. Some prior phases had zero or multiple such markers. Fix: every phase now has exactly one `**Write Targets**:` line with the canonical comma-space-delimited target list.

### concepts.md

### F1 — Premature Spring AI Version Pin (Assumption #1)

**Severity:** High

`concepts.md` pins Spring AI to version 2.0.0: _"Spring AI's HTTP client is virtual-thread-safe for version 2.0.0. Audit required during Phase 09 extraction."_ The handoff explicitly leaves the target version as Open Question #6 and does not name a version: _"Which version(s) does the framework target? Virtual-thread safety audit depends on this."_ Pinning a version before the audit is self-defeating — the audit is supposed to discover the safe version, not rubber-stamp a pre-selected one. If 2.0.0 turns out to pin carrier threads, implementers will have baked an unsafe assumption into code that was written before the audit.

### F2 — Phase Numbering Ambiguity (Constraints #9–#10, Assumptions #3, #9)

**Severity:** Medium

`concepts.md` uses plan phase numbers (02, 04, 05, 09, 13, 14) throughout constraints and assumptions, but the handoff uses a 7-phase (0–7) scheme. The plan refines the handoff's 7 phases into 14 more granular phases — this is a deliberate design decision — but `concepts.md` provides no mapping or acknowledgment of the refinement. A reader only holding the handoff will see phase numbers (e.g., "Phase 04") that do not exist in their reference. The mapping is:
- Handoff Phase 1 → Plan Phase 02 (delete-web-ui)
- Handoff Phase 2 → Plan Phase 04 (remove-chatservice-deps)
- Handoff Phase 3 → Plan Phase 05 (delete-app-shell)
- Handoff Phase 4 → Plan Phases 06–09 (core types through engine)
- Handoff Phase 5 → Plan Phases 10–11 (facade through autoconfigure)
- Handoff Phase 6 → Plan Phases 12–13 (tests through docs)
- Handoff Phase 7 → Plan Phase 14 (magenta-reintegration)

### F3 — Missing Open Questions

**Severity:** Medium

The handoff declares eight open questions that are not acknowledged in `concepts.md`:
1. Final artifact name approval (`turnloop-core` vs `mindspice-loom`)
2. Example tool selection
3. JPMS module name declaration
4. Model configuration file format (YAML, programmatic, JSON auto-discovery)
5. Phase 1 test preservation exact file list
6. Target Spring AI version
7. `MarkdownRenderer` module placement revisit
8. Audit table retention strategy

All eight are design-relevant and several (artifact naming, target Spring AI version, model config format) materially affect implementation. Silently omitting them implies they are settled or irrelevant when they are not.

### F4 — SPI Count Ambiguity

**Severity:** Low

`concepts.md` Feature #7 says _"~17 pluggable SPIs"_ but enumerates a list of 20 names. The handoff's Objective says _"~15 pluggable SPIs"_ while its Feature #3 SPI table lists 20 interfaces. Count variations across the three documents: handoff Objective ~15, handoff table 20, concepts.md ~17. This imprecision does not affect implementation — the SPI table and Appendix A are authoritative — but it weakens the document's credibility as a reference. The actual count of distinct SPI interfaces is 20.

### F5 — Configuration Validation Rules Not Called Out

**Severity:** Low

The handoff defines explicit validation rules for `TurnLoopProperties` (models.default must reference a provider, endpoint must be a valid URL, buffer-percent clamped 0–50, max-tool-rounds must be ≥1, etc.). `concepts.md` Feature #12 lists the YAML surface but does not call out validation behavior. The `TurnLoopBuilder.build()` validation is mentioned conceptually ("TurnLoop.builder().build() produces a working instance") but the property-binding validation rules deserve explicit mention as they form the first line of defense against misconfiguration.

### F6 — Missing Scope and Out-of-Scope Sections

**Severity:** Low

The handoff contains detailed In/Out scope lists that define the framework boundary. `concepts.md` omits a scope section. While `concepts.md` is a higher-level overview, scope boundaries are essential reference material. The out-of-scope list in the handoff (no WebSocket, no gRPC, no multi-tenancy, no workspace filesystem, no CLI embedding, etc.) prevents feature creep and defines what the framework explicitly does not own.

### F7 — Handoff Appendix C (Gate Inventories) Not Referenced

**Severity:** Low

The handoff's Appendix C defines per-phase gate artifact inventories — explicit lists of what must exist and pass at each phase boundary. `concepts.md` lists validation criteria but does not reference these inventories. The per-phase gate inventories are the concrete "what must be true" checklists; `concepts.md` would be stronger if it pointed implementers to them rather than re-stating a subset.

### F8 — Production Scope Confirmed

**Finding:** Pass

No mocks, stubs, placeholders, deferred work, partial implementations, or bare-minimum shortcuts appear in `concepts.md`. The document consistently uses language such as "production-quality default," "working instance," and "real Ollama." Every SPI is required to ship with a working default. The validation criteria include genuine end-to-end integration tests (real Ollama turn loop, JVM restart survival for InteractionStore, SSE backpressure with buffer overflow, client disconnect handling). No validation criterion accepts a simulated or partial state. This finding confirms the document does not compromise on production scope.

### F9 — Cross-Phase Guidance Alignment

**Finding:** Pass

The eight cross-phase guidance items in `concepts.md` align with handoff discipline:
- Incremental verification (gates before successors)
- Deletion discipline (grep confirmation)
- Framework-native types (ModelRouter boundary)
- SPI default quality (must be production, not stub)
- Test adaptation (don't delete adaptable tests)
- Schema ownership (framework owns schema, Magenta's deleted)
- Exception wrapping (facade boundary)
- Virtual-thread audit (before Phase 09 commit)

All eight are faithful to the handoff. The virtual-thread audit guidance should note the finding from F1 above — the target Spring AI version must be decided before the audit can produce a trustworthy result.

### F10 — Final Validation Criteria Completeness

**Finding:** Pass

`concepts.md` Final Validation lists 25 criteria. Every one of the handoff's 20 key validation scenarios is covered, plus five additional criteria (mvn verify, JaCoCo, Javadoc, grep checks, Magenta re-integration). All 25 criteria are concrete, verifiable, and reject mock/substitute validation.

### orchestration.md

### Phase Ledger format violation (primary error)

The submitted orchestration Phase Ledger intermixed markdown headings (`### phase-06-core-types.md (subphases A, B)`) and indented subphase bullets (`- subphase A | depends: ...`) with flat phase entries. The machine-readable contract requires exactly one flat `- phase-NN-slug.md | depends: ... | targets: ... | goal: ...` line per phase. Subphase decomposition is an implementation detail belonging to per-phase plan files, not the orchestration ledger. This caused 14+ shape validation failures and cascading parse errors that also triggered spurious wave-format complaints.

### Phase dependency ordering is correct

The dependency chain is sound: scaffold → delete web UI → create SPI interfaces → sever ChatService deps → delete app shell → core types → events/SPIs → internal/persistence → engine/utilities → facade → autoconfigure → tests → docs/hardening → reintegration. Each phase depends only on phases that logically precede it. No circular dependencies exist. The handoff's conceptual phase-to-plan-phase mapping (handoff Phase 0→plan Phase 01 through handoff Phase 7→plan Phase 14) is faithfully reflected.

### Phase sizing assessment

Phases 01–05 (scaffolding through app-shell deletion) are well-sized as unsplit phases — each is a focused deletion or refactoring operation within the existing Magenta codebase. Phases 06–14 involve creating the framework from scratch and are correctly identified as needing lettered subphases (A, B, C, D). The largest phase, 09 (engine/utilities, 4 subphases), is appropriately decomposed: subphase A (engine/prompt/context), B (tool registry/gate/thinking/in-memory store), C (renderers/example tools/optional deps), D (move surviving Magenta code). Each subphase targets a cohesive, independently validatable set of artifacts and should fit within a single implementation agent session.

### Execution Waves structure is valid

The waves are contiguous `wave-01` through `wave-14`, each mapping to exactly one phase in order. No phase is duplicated or omitted. After fixing the Phase Ledger, the wave entries will pass shape validation. Each wave represents one sequential implementation step; no parallelism is attempted or warranted given the strict dependency ordering.

### Model assignments are appropriate

Implementation uses `deepseek/deepseek-v4-pro:max` for code generation and `openai-codex/gpt-5.6-terra:high` for post-phase validation review-and-repair. The implementer model has sufficient context capacity for the file volumes in each phase/subphase. The validation model has strong reasoning capability for gate-level correctness checks. One implementation agent per unsplit phase (or one sequential agent per subphase for split phases) is the correct granularity.

### Validation Gate design is correct

The post-phase validator review-and-repair PASS gate precedes any dependent phase start. This enforces the contract that no broken state propagates forward. The final integration validation with `openai-codex/gpt-5.6-terra:high` provides an adequate cross-cutting check after all phases PASS.

### Open Questions remain unresolved

The handoff identifies 8 open questions (artifact name, example tools, JPMS module name, model config format, test preservation scope, target Spring AI version, MarkdownRenderer placement, audit table retention) that must be settled before or during implementation. The orchestration plan correctly notes these but does not resolve them — they are pre-implementation decisions for the user.

### Handoff-to-plan fidelity

The orchestration plan faithfully captures all handoff requirements: 20 SPIs with defaults, SQLite persistence with 5 tables (4 data + version), `MigrationRunner`, `ToolLoopGuard`, `TurnEvent` sealed hierarchy with 8 subtypes, `TurnLoop` facade with sub-interfaces, `TurnLoopBuilder`, auto-configuration with health/metrics/SSE, and the constraint that no Spring AI type appears in public API signatures. The 20 final validation criteria and per-phase gate inventories from handoff Appendices A–C are all addressed.

### phase-01-scaffold-baseline.md

### F1 — Non-standard banned dependency artifact name (`spring-boot-starter-webmvc-test`)

The root `pom.xml` declares `<artifactId>spring-boot-starter-webmvc-test</artifactId>` as a test dependency. This is not a standard Spring Boot artifact ID. The standard web MVC test support lives inside `spring-boot-starter-test` or `spring-boot-starter-webflux-test`. The phase mirrors this non-standard name into the `bannedDependencies` rule for `turnloop-core`. If the actual artifact in the dependency tree is `spring-boot-starter-test`, the Enforcer rule will silently fail to match it — the ban becomes a no-op for the real test starter. This does not block the phase (the ban list is prophylactic for a module that starts empty), but the rule should target real artifact coordinates before Phase 12 tests are added to `turnloop-core`.

### F2 — Missing `.gitkeep` guidance for empty source directories

The phase says "Create empty source directory structure" for both modules. Git does not track empty directories. Without `.gitkeep` files (or equivalent placeholder files), the directories will not survive a fresh clone. The implementation agent will need to create `.gitkeep` files in each empty `src/main/java/`, `src/main/resources/`, `src/test/java/`, and `src/test/resources/` directory.

### F3 — Maven Enforcer configuration specified only in prose

The phase describes the Enforcer rule requirements in words but provides no XML fragment. The `bannedDependencies` rule has specific syntax (`<excludes><exclude>groupId:artifactId</exclude></excludes>`) that is easy to misconfigure. A concise XML example materially reduces implementation ambiguity.

### F4 — JPMS `Automatic-Module-Name` open question unresolved

Open Question #3 in `concepts.md` states "Should the framework declare `Automatic-Module-Name: io.mindspice.turnloop`? This document assumes yes. Confirm before Phase 01." The phase file does not address this. The corrected phase should either include the `Automatic-Module-Name` manifest entry in the `turnloop-core` POM (per the assumption) or note that the open question must be settled before proceeding.

### F5 — Metadata alignment

The phase goal, dependencies, and write targets match the orchestration ledger exactly. No divergence.

### F6 — One-agent executability

This phase is trivially within one agent session. Creating two POM files, modifying one, creating eight empty directories with `.gitkeep` files, and running two Maven commands. No subphase splitting required.

### F7 — Production scope

The phase creates real, production-ready POMs with enforceable dependency rules. There are no mocks, stubs, placeholders, or deferred paths. Exit criteria require successful build and context startup — these are verifiable production gates.

### F8 — Validation and exit criteria quality

The validation checklist (5 items) and exit criteria (3 items) are concrete and gatable. The context startup smoke test (`timeout 30s mvn spring-boot:run`) is appropriate for verifying that adding empty modules does not disturb the Spring Boot application context.

### phase-02-delete-web-ui.md

### F1 — Missing Playwright deletion targets (Scope gap)

The `tests/playwright/` directory (contains `public-alpha-harness.spec.js` and `README.md`) and `artifacts/playwright/` directory (contains historical Playwright run evidence across ~15 subdirectories) exist in the repository but are not included in the phase's deletion steps or Write Targets. These are web-specific test artifacts with no purpose after the web layer is deleted. The `playwright.config.js` root file is correctly listed but the adjacent test source and artifact directories are not.

**Evidence**: `ls tests/playwright/` shows `public-alpha-harness.spec.js` and `README.md`; `ls artifacts/playwright/` shows ~15 named output directories.

### F2 — Serialization test location not identified (Implementation guidance gap)

`ChatStreamEventSerializationTest.java` lives at `src/test/java/io/mindspice/magenta2/ai/chat/model/ChatStreamEventSerializationTest.java` — which is **not** under the deleted `src/test/java/io/mindspice/magenta2/api/web/` tree. The phase says "update the serialization test" in Implementation Step 4 but does not specify its path. An implementer could mistakenly assume it is deleted with the web test package.

**Evidence**: `find ChatStreamEventSerializationTest.java` confirms its location is in `ai/chat/model/` test tree, not `api/web/`.

### F3 — ChatPlanState.java preservation timing ambiguous (Implementation guidance gap)

The phase correctly says "Remove `ChatPlanState` and HTML rendering fields from the renamed `TurnEvent` class" but does not explicitly state that `ChatPlanState.java` itself must **not** be deleted. `ChatPlanState.java` is still referenced by `ChatService.java`, `ChatHistory.java`, `ChatResponse.java`, `PlanService.java`, and `TerminalTurnRepair.java` — all of which survive Phase 02. Deletion of `ChatPlanState.java` is scoped to Phase 05.

**Evidence**: grep for `ChatPlanState` in `src/main/java` shows 7 surviving reference sites outside `api/web/` and `ChatStreamEvent.java`.

### F4 — Field removal list not enumerated (Implementation guidance gap)

The phase says "remove all ChatPlanState references and HTML rendering fields" but does not enumerate which fields to remove from which subtypes. The sealed interface has 9 subtypes with varying field sets. An implementer could miss `renderedHtml` on `SystemNotice`, `thinkingHtml` on `Chunk`, or leave the `OutputsChanged` subtype that is tied to deleted file-mutating tools.

**Evidence**: `ChatStreamEvent.java` has `ChatPlanState planState` on 7 of 9 subtypes, `renderedHtml` on 3 subtypes, `thinkingHtml` on 1 subtype, and the `OutputsChanged` subtype references deleted functionality.

### F5 — Cross-reference validation grep missing (Validation gap)

Phase 05 (delete-app-shell) includes a cross-reference grep in its validation gates. Phase 02 should also verify that zero retained code imports `io.mindspice.magenta2.api.web` after deletion, since retained code that references deleted web packages would produce latent compilation errors in downstream phases.

**Evidence**: Inspection confirms no such imports exist today, but the validation step should be formalized.

### F6 — One-agent executability confirmed (Schedule consistency)

The phase's total work is: delete ~4 directory trees, remove 5 POM dependency blocks, rewrite one ~65-line sealed interface, update one ~130-line test, and edit `application.yml`. All deletions are simple directory removals. No new types are created. The rename touches exactly one file and one test. Reference resolution is bounded: only `ChatStreamEventSerializationTest.java` retains references after web package deletion. This comfortably fits in one implementation agent session. No subphase splitting is required.

### phase-03-create-spi-interfaces.md

1. **InteractionHandler signature mismatch (MEDIUM).** The current phase defines `onQuestionsAsked(String sessionId, String turnId, List<String> questions)` but the handoff Appendix A uses `onQuestionsAsked(QuestionPendingContext context)` with a typed record carrier. The corrected phase adopts the Appendix A signature so Phase 07 replacement is a one-to-one file move rather than a signature rework. This also requires defining `QuestionPendingContext` as a nested or co-located record.

2. **AnswerResult sealed hierarchy underspecified (MEDIUM).** The current phase says "permitting Continue, Abort, SingleMessage, MultiMessage" but does not define the record types, their fields, or the `sealed` permits clause. The corrected phase includes the full definition from Appendix A.

3. **PromptContextAssembler adaptation guidance is imprecise (HIGH).** The current phase says "Rename assemble() to contribute()" and "Update callers" but `assemble()` returns `void` and sets `ctx.systemPrompt()` / `ctx.turnInstructions()` as side effects, while `PromptContributor.contribute()` returns `String`. The corrected phase specifies that `contribute(TurnContext)` extracts mode and conversationId from the context and delegates to `mergeModePrompt()`, returning the assembled prompt string. The existing `assemble(TurnContext, TrustedPolicyResult)` method remains (bridge retained), updated to call `contribute()` internally for the prompt text before setting context fields. The `assemble(TurnContext)` backward-compat overload likewise delegates. Callers in `ChatService` at line 2437 are not changed in this phase (Phase 04 owns ChatService modification).

4. **ToolAccessPolicy static factory scope is ambiguous (LOW).** The current phase says "Generalize ToolAccessPolicy with allowAll(), allowList(Set<String>), blockList(Set<String>) static factories; preserve existing constants." This correctly adds new capability without converting the class to a `@FunctionalInterface` (that happens in Phase 07). The corrected phase clarifies that the class remains a concrete stateful type and the factory methods are additive only.

5. **TerminalTurnRepair interface delegation missing (MEDIUM).** The current phase says "Adapt TerminalTurnRepair to implement both RepairStrategy and CompletionDetector" but does not specify the method-to-interface mapping. The corrected phase maps:
   - `CompletionDetector.isComplete(ctx)` → delegates to `hasNoContentOrToolCalls()` + `needsExecutionCompletionRepair()` logic
   - `RepairStrategy.repair(ctx, reason)` → switch on `RepairReason`: `EMPTY_RESPONSE` → `emptyFinalResponseControlMessage()`, `TOOL_LOOP_EXCEEDED` → tool abort message, `COMPLETION_NOT_DETECTED` → completion repair control messages, `CUSTOM` → no-op or delegating placeholder

6. **No subphase split needed.** The phase scope is ~300 lines of new/changed code across 7 files. This fits comfortably within one agent session.

### phase-04-remove-chatservice-deps.md

### F1. Scope underestimation — the phase is ~2× larger than described

The current Implementation Steps (6 numbered items) are a high-level sketch that drastically understates the edit volume. `ChatService.java` is 3009 lines with 13 distinct collaborator removal chains, each of which requires surgical removal of public API methods (~15 plan/task/agent orchestration methods), private helper methods (~20), measurement instrumentation (~150 lines), trusted-policy wiring (~100 lines), and the `PlanMode` field from the inner `ToolLoopState` class plus all mode-check branches in the 6-state machine. The phase needs three sequential subphases to fit within one agent session per subphase.

### F2. The tool-loop state machine adaptation is the highest-risk edit and is under-specified

The current phase says "Replace plan/task mode detection with `CompletionDetector` + `ToolAccessPolicy` SPI calls" but gives zero guidance on how `PlanMode mode` in `ToolLoopState` maps to the replacement. The `ToolLoopState` carries `PlanMode mode` which drives branch decisions in EVALUATE, EXECUTE_TOOLS, REPAIR, and FINALIZE. The repair delegation (`requiresPlanTurnRepair`, `requiresExecutionCompletionRepair`) currently routes through `TerminalTurnRepair` which itself depends on `PlanService`/`TaskService`. The corrected phase must prescribe exactly how mode-aware branches get simplified to SPI calls.

### F3. Phase 03 output dependency is unvalidated

The phase assumes Phase 03 produced clean SPI interfaces (`CompletionDetector`, `InteractionHandler`, `PromptContributor`, `RepairStrategy`) that already have their app-shell dependencies severed. But the current live `ToolAccessPolicy.java` still imports `PlanService`, `TaskService`, `AgentSkillRuntimeCatalogService`, and `TerminalTurnRepair.java` still imports `PlanService`, `TaskService`. If Phase 03 doesn't produce SPI-only versions of these helpers, Phase 04 will fail at the `PromptContextAssembler`/`ToolAccessPolicy`/`TerminalTurnRepair`/`TurnAuditWriter` constructor calls in ChatService's `@Autowired` constructor.

### F4. Collaborator count is imprecise

The goal says "13 app-shell collaborators" but the actual injection points include at least 15 constructor parameters plus 2 setter-injected services. Some are core services that stay (e.g., `AuditRepository`, `ObjectMapper`), but at least 13 are app-shell and must be removed. The corrected phase lists the exact removal set with stay/go disposition.

### F5. Orchestration metadata is correct but incomplete

The ledger goal, dependency, and targets match the canonical orchestration.md. However the In Scope section only lists the two Write Targets, missing the explicit collaborator removal list, and the Out of Scope is too brief — it should explicitly state the SPI helper classes (PromptContextAssembler, ToolAccessPolicy, TerminationTurnRepair, TurnAuditWriter) are NOT in scope for this phase (they were handled in Phase 03).

### F6. Implementation Step 1 ordering issue

The current Step 1 says "apply `@Autowired(required = false)` as a temporary stepping stone, verify compilation, then remove the collaborator and its usages entirely." This approach works for fields that are nullable-guarded, but some collaborators (like `PlanService`) are used unconditionally in public methods that must be deleted FIRST before the field can be removed. The corrected phase prescribes deletion order: first delete the public orchestration methods, then remove the fields that become dead.

### phase-05-delete-app-shell.md

### F1: Surviving source files reference deleted types — not enumerated in phase

After deletion, at minimum these surviving source files will fail compilation. The current phase says "Fix all compilation errors" without listing them:

- `ChatSessionMetadataRepository.java` — imports and uses both `ChatSessionSurface` and `ChatSessionOrigin` in 6+ public methods (`saveOriginIfAbsent`, `findOrigin`, `saveSurfaceIfAbsent`, `findSurface`, `findAgentConversationIds`, `findBrowserConversationIds`, `createProjectChatSession`, `saveAgentOrigin`) and the `ProjectChatSession` inner record
- `ChatPendingMessageRepository.java` — imports and uses `ChatSessionSurface` in `enqueue()`, `surface()`, `PendingMessageRow`
- `ChatPendingMessageService.java` — imports and uses `ChatSessionSurface` in `enqueue()`
- `RequestResolver.java` — imports and uses `ChatSessionSurface` in method signatures
- `AuditService.java` — imports `AgentJobService` from the deleted `ai.agent.job` package
- `ChatService.java` — imports and uses `TrustedContextPolicyAssembler` (should be resolved by Phase 04, but verified present as of review)

### F2: Surviving test files reference deleted types — not enumerated

- `ChatServiceTest.java` — references `ChatSessionSurface`, `ChatFileService`, `ChatSessionOrigin`
- `ChatPendingMessageServiceTest.java` — references `ChatSessionSurface`
- `ChatPendingMessageRepositoryTest.java` — references `ChatSessionSurface`
- `ToolAccessPolicyTest.java` — references `PlanMode` in filter calls
- `PromptContextAssemblerTest.java` — references `PlanMode` in `mergeModePrompt` calls
- `ChatStreamEventSerializationTest.java` — constructs `ChatPlanState.normal()`

### F3: Test directory deletion is imprecise

The `src/test/java/io/mindspice/magenta2/ai/chat/tool/` directory contains both deletable subdirectories (`file/`, `shell/`, `web/`, `skills/`, `orchestration/`, `plan/`, `savedplan/`) and surviving test files (`ChatToolRegistryTest.java`, `ToolArgumentCoercionConfigTest.java`, `ToolTranscriptServiceTest.java`). The phase must specify subdirectory-level deletion, not wholesale directory removal.

Additionally, individual test files outside the deleted package trees must be explicitly listed: `ChatFileServiceTest.java`, `SchemaMigrationRunnerTest.java`, `TrustedContextPolicyAssemblerTest.java`, `InteractionQuestionToolsTest.java`.

### F4: AiConfig simplification lacks field-level specificity

Current AiConfig has 5 constructors and 11 record components. The phase says "Remove agent, skill, plan, and shell-command configuration fields" but doesn't enumerate the exact fields to retain (`defaultModel`, `summaryModel`, `summeryModel` legacy alias, `contextBufferPercent`, `dataRoot`, `webSearch`, `models`) or which resolved-method methods to remove (`resolvedPlanningModelKey`, `resolvedCompactionModelKey`, `unsafeAllowWildcardShellCommandsEnabled`).

### F5: AiUserConfigConfiguration simplification lacks specificity

The current `AiUserConfigConfiguration` creates an `AiConfig` bean from an external JSON file and resolves the data root. After simplification, it must only create the `AiConfig` bean with the collapsed constructor — the exact wiring depends on Phase 05's final AiConfig shape.

### F6: Cross-phase dependency fragility

`ChatService.java` line 49 imports `TrustedContextPolicyAssembler` and line 2500 calls `TrustedContextPolicyAssembler.create(…).assemble()`. Phase 04 must excise this call. If Phase 04 leaves it or partially removes it, Phase 05 inherits a ChatService that won't compile. The corrected phase notes this as a pre-condition check.

### phase-06-core-types.md

### F1. Goal text does not match the orchestration ledger verbatim

The submitted phase goal reads:

> Create all core data-carrier types and records in turnloop-core per handoff Appendix A; add framework dependencies to turnloop-core POM

The orchestration ledger goal reads:

> Create all core data-carrier records and enums per handoff Appendix A including TurnContext with typed AttributeKey, CompactionContext/Result, and session/queue records; add framework dependencies to turnloop-core POM

**Severity**: Medium. The submitted text omits the explicit callouts for TurnContext, CompactionContext/Result, and session/queue records. The corrected phase must use the ledger text verbatim.

### F2. Write Targets are directory-level instead of the canonical file-level list from the ledger

The submitted phase lists:

> **Write Targets**: turnloop-core/src/main/java/io/mindspice/turnloop, turnloop-core/pom.xml

The orchestration ledger provides an explicit 17-item file-level list. Using a directory-level target is ambiguous and hides from the implementer exactly which files must exist at gate time. The corrected phase must use the ledger's canonical comma-separated file list verbatim.

### F3. TurnDiagnostic references TurnPhase, which is created in Phase 07

`TurnDiagnostic` in the handoff Appendix A carries a `TurnPhase finalPhase` field. `TurnPhase` is a 7-value enum (PREPARE, INVOKE_MODEL, EVALUATE, EXECUTE_TOOLS, REPAIR, FINALIZE, DONE) defined in the handoff's TurnEvent section but not in the core-types section. The orchestration assigns TurnEvent and TurnPhase to Phase 07. Without TurnPhase, TurnDiagnostic cannot compile.

**Severity**: High (compilation blocker). The corrected phase must create `TurnPhase.java` as a dependency type. It is a trivial enum and does not pull in any Phase 07 complexity.

### F4. TurnContext references ToolCallback and ToolDefinition, both created in Phase 07

`TurnContext` in the handoff defines `activeTools()` returning `List<ToolCallback>` and a setter accepting `List<ToolCallback>`. `ToolCallback` is a framework-owned interface (two methods: `getToolDefinition()` and `call(String)`) and `ToolDefinition` is a one-field record. These are defined in the handoff under the SPI section but are simple enough to be created as dependency types in this phase.

**Severity**: High (compilation blocker). The corrected phase must create `ToolCallback.java` and `ToolDefinition.java`. These are trivial files that do not expand the phase scope in any meaningful way.

### F5. The phase plan mentions types absent from the ledger targets

The submitted phase describes `QuestionPendingContext`, `AnswerResult`, `ThinkingResult`, and `AssistantMessageContent` as in-scope records, but the orchestration ledger does not include them in its targets list for Phase 06. These four types are defined in the handoff under the InteractionHandler and ThinkingExtractor SPI sections and are correctly placed in Phase 07 (events-spis), where their owning SPIs are created. The corrected phase removes them from implementation guidance and exit criteria.

### F6. TurnContext thread-safety guidance is misleading

The submitted phase states TurnContext "is NOT thread-safe — accessed only by the turn's owning thread" but the handoff Appendix A shows `private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>()`. The class is turn-scoped (one instance per turn, writes from the turn thread) but uses ConcurrentHashMap to safely tolerate reads from asynchronous compaction or lifecycle-listener callbacks. The guidance should be corrected to reflect this nuance.

### F7. POM dependency list is reasonable but lacks a version-property note

The submitted phase lists hard-coded versions for `sqlite-jdbc` (3.50.3.0), `jtokkit` (1.1.0), `commonmark` (0.24.0), and `owasp-java-html-sanitizer` (20240325.1). This is precise but fragile: the implementer should be reminded to check for the latest compatible versions at implementation time since the plan was written. The corrected phase moves these to a guidance note rather than hard-coding them in the dependency block.

### F8. Handoff Appendix A naming inconsistency: ConversationFilter vs SessionFilter

The handoff prose uses `ConversationFilter` in some places but the code block uses `SessionFilter`. The ledger targets use `SessionFilter.java`. The corrected phase resolves this to `SessionFilter` to match both the code block and the ledger.

### phase-07-events-spis.md

### F1 — Goal truncated (metadata misalignment)

The current phase goal reads: "Create TurnEvent sealed hierarchy, TurnPhase enum, exception hierarchy, and all SPI interfaces in turnloop-core." The canonical ledger goal is substantially more detailed: it explicitly enumerates "all 8 subtypes and full field specifications," lists every exception in the hierarchy, and spells out all 20 SPI categories. The corrected phase carries the ledger goal verbatim per the metadata-alignment contract.

### F2 — Write Targets are directory-level instead of file-level (metadata misalignment)

The current phase uses two directory-level targets (`turnloop-core/src/main/java/io/mindspice/turnloop, turnloop-core/src/main/java/io/mindspice/turnloop/spi`). The canonical ledger enumerates 32 specific file paths covering every type this phase must create. Directory targets give the implementer no enumerated checklist; the corrected phase uses the ledger's exact comma-separated file list.

### F3 — SPI count says "~17" but must be 20

The current phase body says "all ~17 SPI interfaces" while the ledger goal says "all 20 SPI interfaces." The handoff Appendix A defines exactly 20 pluggable SPIs: ToolRegistry, ToolAccessPolicy, ToolResultSummarizer, InteractionHandler, InteractionStore, ConversationMemory, SessionStore, TokenEstimator, CompactionStrategy, CompletionDetector, RepairStrategy, TurnGate, MessageQueue, MessageQueueStore, AuditWriter, PromptContributor, ModelCapabilityProvider, TurnLoopMetrics, TurnLifecycleListener, ThinkingExtractor. Supporting types (ToolCallback, ToolDefinition, AnswerResult, QuestionPendingContext, ModelCapability) are separate files, not counted among the 20. The corrected phase uses "20."

### F4 — Type overlap with phase 06: AnswerResult and QuestionPendingContext

Phase 06's In Scope section lists `QuestionPendingContext` and `AnswerResult` as types to create. The phase 07 ledger targets also include `AnswerResult.java` and `QuestionPendingContext.java`. If both phases independently create these files with identical fully-qualified names, the second agent's compilation will fail on duplicate type definitions. The corrected phase 07 includes these files per the ledger but adds a coordination note instructing the implementer to verify whether phase 06 already created them and to skip recreation if signatures match.

### F5 — Missing ContextExhaustedException subtype

The handoff Appendix A exception hierarchy shows `TurnFailedException` as sealed with two subtypes: `ContextExhaustedException` and a general `TurnFailedException` carrier. The current phase mentions neither subtype. The corrected phase enumerates `ContextExhaustedException` as a sealed subtype of `TurnFailedException`.

### F6 — TurnEvent field specification incomplete

The handoff's TurnEvent sealed hierarchy defines common accessor methods on the interface (`turnId()`, `timestamp()`, `contextUsage()`) and every inner record carries an `Instant timestamp` field. The current phase mentions field presence generically ("exact fields from handoff Appendix A") but doesn't call out the timestamp field or the common-accessor pattern. The corrected phase provides explicit field-by-field guidance for each of the 8 subtypes.

### F7 — Nested types not enumerated for SPI interfaces

Several SPI interfaces carry nested records, enums, or sealed interfaces that must live in the same `.java` file. The current phase does not enumerate them. The corrected phase lists each:
- `ToolResultSummarizer`: nested `ToolExecution` record, `SummarizeResult` record
- `RepairStrategy`: nested `RepairReason` enum (`EMPTY_RESPONSE`, `TOOL_LOOP_EXCEEDED`, `COMPLETION_NOT_DETECTED`, `CUSTOM`)
- `TurnGate`: nested `AcquireResult` sealed interface (`Acquired`, `Rejected`, `Queued`), `TurnGateMode` enum (`SEND`, `STREAM`, `ANSWER`), `GateState` enum (`IDLE`, `EXECUTING`, `AWAITING_ANSWER`)
- `AuditWriter`: nested `ToolExecutionRecord` record, `TurnError` record
- `InteractionHandler`: `async()` static method

### F8 — TurnGate.acquire mode parameter omitted

The handoff declares `AcquireResult acquire(String sessionId, TurnGateMode mode)`. The current phase does not mention the `TurnGateMode` parameter. The corrected phase includes it.

### F9 — ThinkingResult and AssistantMessageContent placement

`ThinkingResult` and `AssistantMessageContent` are nested records on `ThinkingExtractor` in the handoff Appendix A. The phase 06 plan also lists them as core types. The ledger phase 07 targets do not include separate file paths for them, which is correct — they live inside `ThinkingExtractor.java`. If phase 06 creates them as standalone files, the corrected phase 07's `ThinkingExtractor.java` will reference them. The corrected phase adds a coordination note about this.

### phase-08-internal-persistence.md

### F1: Goal mismatch (metadata — blocking)

The submitted phase Goal reads: "Create framework-internal interfaces, ToolLoopGuard, ToolArgumentCoercionModule, all SQLite persistence defaults, Migration system, and schema.sql". The planning-review-draft ledger entry for phase-08 reads: "Create framework-internal interfaces (ModelRouter, TurnExecutor, ActiveTurnRegistry); implement ToolLoopGuard concrete final class with identical-call and error-window tracking; implement ToolArgumentCoercionModule with service-loader registration; implement all SQLite-backed SPI defaults with full CRUD and concurrent-access safety; implement SpringAiConversationMemoryAdapter bridging to Spring AI ChatMemory; implement MigrationRunner with Migration interface; create schema.sql with five tables per handoff Appendix B DDL". The submitted goal is a lossy summary; the ledger goal is the canonical artifact descriptor and must be copied verbatim as the first line under `## Goal`.

### F2: Write Targets mismatch (metadata — blocking)

The submitted In Scope `**Write Targets**` line uses package-level globs (`internal/*`, `persistence/*`) plus out-of-order concrete files. The ledger specifies 15 individual file paths in canonical order: three `internal/` interfaces, `ToolLoopGuard.java`, `ToolArgumentCoercionModule.java`, the service-loader registration file, five `persistence/` classes, `Migration.java`, `MigrationRunner.java`, `schema.sql`, and `pom.xml`. The submitted targets omit several concrete files and collapse directories that the ledger lists individually. The `**Write Targets**` line must match the ledger targets exactly in canonical order.

### F3: Internal interface scope error (content — blocking)

The submitted phase lists six items under "framework-internal interfaces" in `io.mindspice.turnloop.internal`: `ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`, `ContextManager`, `DefaultPromptAssembler`, and `SpringAiConversationMemoryAdapter`. The handoff Appendix A defines only three framework-internal interfaces (`ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`). `ContextManager` and `DefaultPromptAssembler` are Phase 09 (engine-utilities) targets per the ledger — they live in `engine/`, not `internal/`. `SpringAiConversationMemoryAdapter` is a persistence-layer adapter (it bridges `ConversationMemory` to Spring AI `ChatMemory`), not an internal interface; the ledger places it in `persistence/` alongside the SQLite implementations.

### F4: Missing NoOpAuditWriter (content — advisory)

The handoff Feature §5 table explicitly states the `AuditWriter` default is `NoOpAuditWriter` and `SqliteAuditWriter` is optional. The submitted phase mentions `NoOpAuditWriter` only in the Technical Guidance section. While the ledger targets do not list it as a separate write target file, the implementation guidance should explicitly instruct creating it alongside `SqliteAuditWriter` so the default wiring path is production-complete.

### F5: Single-agent scope risk (planning judgment)

The phase creates 15 distinct source files plus a service-loader registration file and a `schema.sql`. The SQLite implementations (`SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`) each require: `JdbcTemplate` or raw JDBC wiring, `CREATE TABLE IF NOT EXISTS` self-migration, full CRUD method bodies with parameter binding and result-set mapping, concurrent-access safety (the handoff requires `SqliteConversationMemory.addMessages()` use batch insert and `SqliteMessageQueueStore.claimAtomic()` use `UPDATE ... WHERE claim_token IS NULL`), exception handling, and adherence to the SPI contracts defined in Phase 07. `SpringAiConversationMemoryAdapter` requires mapping between framework-native `TurnMessage` and Spring AI `Message` types. `ToolLoopGuard` requires sliding-window logic for both identical-call detection and error-window tracking. `MigrationRunner` requires version-ordered transactional application. The combined implementation load across all 15 files, after reading and internalizing the Phase 07 SPI contracts, is likely to push against a 200K–300K token session ceiling. This phase should be split into two contiguous lettered subphases to keep each implementation agent focused and within budget.

### phase-09-engine-utilities.md

### Positive
- Correct dependency declaration: `phase-08-internal-persistence.md`
- Correct constraint: engine must be package-private
- Correct constraint: `MarkdownRenderer` graceful degradation with optional dependencies
- Correct constraint: example tools NOT auto-registered
- Correct constraint: no `ThreadLocal` in any framework source
- Correctly identifies the two Magenta delete targets: `MagentaWorkExecutor` and hard-coded `ToolTranscriptService` switch
- Technical guidance section captures key invariants (ask_user suspension, tool-loop-guard check, completion detection, compaction trigger)

### Deficiencies
- Goal text diverges from orchestration ledger goal
- Write-targets path format diverges from ledger (directories vs. explicit file paths)
- Missing `DefaultPromptAssembler` and `ContextManager` as explicit deliverables
- No subphase decomposition despite extreme phase size
- Code migration section lacks per-class merge/delete instructions
- `TurnEventRenderer.json()` parameter missing
- Unresolved virtual-thread audit dependency not addressed with a safe default
- No guidance on the `DefaultTurnEngine` package-private builder grouping dependencies by concern (as specified in handoff Decision 13)

### Gate Inventory Cross-Reference

Comparing against the handoff Appendix C Phase 4 Gate Inventory (which covers plan phases 06–09 collectively): the gate expects all types from Appendix A, all SPI interfaces, `TurnEvent` sealed hierarchy, `SerialTurnGate`, `DefaultToolRegistry`, `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`, `Migration`/`MigrationRunner`/`schema.sql`, `MarkdownRenderer`, `TurnEventRenderer`, `ThinkingExtractor`/`CompositeThinkingExtractor`, `ToolLoopGuard`, `ToolResultSummarizer`, `TurnExecutor`, and `ActiveTurnRegistry`. By the end of Phase 09, the following from that inventory should be complete: `SerialTurnGate`, `DefaultToolRegistry`, `MarkdownRenderer`, `TurnEventRenderer`, `CompositeThinkingExtractor`, `DefaultTurnEngine`, `DefaultPromptAssembler`, `ContextManager`, `InMemoryInteractionStore`, example tools. The persistence defaults, `MigrationRunner`, `schema.sql`, `ToolLoopGuard`, `ActiveTurnRegistry`, and `TurnExecutor` were completed in Phase 08. This distribution is correct across the two phases.

### phase-10-facade.md

### F1: Goal line does not match orchestration ledger (BLOCKING)

The current `## Goal` reads: `Build TurnLoop interface, DefaultTurnLoop implementation, and TurnLoopBuilder with all SPI wiring`. The orchestration ledger requires: `Create TurnLoop interface with full method signatures per handoff Appendix A including send stream answer enqueue claim ack release clearSession interrupt close and sub-interfaces (ToolSubinterface MemorySubinterface SessionSubinterface); implement DefaultTurnLoop orchestrating DefaultTurnEngine TurnGate InteractionStore and all SPI wiring; implement TurnLoopBuilder with all SPI setter methods and ApplicationContext-accepting create() factory; wire close() lifecycle draining gate cancelling pending questions releasing resources and closing models`. This is the exact error flagged by the phase-metadata validator.

### F2: No subphase structure despite multi-session scope

The phase creates three substantial compilation units: `TurnLoop.java` (interface with 14 methods + 3 sub-interfaces, ~80-100 lines), `DefaultTurnLoop.java` (full orchestrator wiring engine, gate, queue, all SPIs, close() lifecycle, sub-interface inner implementations, ~400-600 lines), and `TurnLoopBuilder.java` (builder with 20+ SPI setter methods, ApplicationContext auto-discovery, build() validation, ~300-400 lines). DefaultTurnLoop alone requires implementing `send()`, `stream()`, `answer()`, `enqueue()`, `claim()`, `ack()`, `release()`, `clearSession()`, `interrupt()`, `close()`, plus three inner class implementations for the sub-interfaces — each with gate coordination, exception wrapping, context construction, and resource management. A single agent session cannot hold all type contracts, SPI wiring, error paths, and edge-case details needed to produce production-quality implementations of all three files. The current plan has four flat implementation steps that collapse this into an unrealistic single-session unit.

### F3: Implementation steps are skeletal

The four steps (`1. Create TurnLoop interface`, `2. Create DefaultTurnLoop`, `3. Create TurnLoopBuilder`, `4. Verify compilation`) provide no implementable detail. Step 2 says `send()`: acquires TurnGate for SEND mode, constructs TurnContext, calls engine.execute(), releases gate — but omits: how gate rejection is surfaced, which exceptions are caught and wrapped, what happens when the session doesn't exist, how the interrupt token is bound to the active turn, and what TurnContext attributes are populated from TurnRequest. Step 3 says `build()` validates configuration — but doesn't specify which validations (default model existence, model-to-provider references, data root writability, required vs optional SPIs).

### F4: close() lifecycle is underspecified

The handoff defines an exact 7-step ordered close() sequence: (1) cancel AWAITING_ANSWER turns with Interrupt, (2) wait for EXECUTING turns up to drain timeout, (3) force-release remaining EXECUTING turns, (4) release stream resources, (5) release pending claims, (6) close AutoCloseable ChatModel instances, (7) shut down TurnExecutor. The current phase mentions `close()` only in passing — no ordered steps, no default timeout value (30s), no idempotency requirement, no mention of what happens to pending message claims during drain.

### F5: Exception wrapping guidance is incomplete

The phase says `Exception wrapping at every public method boundary` but doesn't enumerate the internal exception types that must be caught (`ToolUseAbort`, `NonTransientAiException`, Spring AI exceptions from ModelRouter boundary, gate rejection exceptions, queue exceptions). It doesn't specify which public exception each internal type maps to, nor the rule that `Release`/`Ack` operations on stale claims must not throw but log a warning.

### F6: Missing TurnLoopBuilder validation rules

`build()` must perform at minimum: default model alias references an existing provider (throws `ConfigurationException` otherwise), at least one model configured, data root set or defaulted. The phase file doesn't list these validations, leaving implementers to guess what constitutes a valid configuration.

### F7: Sub-interface implementation pattern not specified

The `tools()`, `memory()`, and `sessions()` accessors need lightweight implementations backed by the `DefaultTurnLoop` instance. The phase doesn't specify whether these should be inner classes, anonymous classes, or records; doesn't note that they must share the same exception-wrapping discipline; and doesn't mention that `SessionSubinterface` methods delegate to `SessionStore` and may need to validate `SessionFilter` before querying.

### F8: No dependency inventory for DefaultTurnLoop constructor

The phase doesn't enumerate what `DefaultTurnLoop`'s constructor must accept from TurnLoopBuilder: DefaultTurnEngine, TurnGate, MessageQueue, InteractionStore, SessionStore, ConversationMemory, ActiveTurnRegistry, TurnExecutor, ModelRouter (or ChatModel map), TurnLifecycleListener, TurnLoopMetrics, ObjectMapper, default system prompt, drain timeout, and the tool/memory/session sub-interface wiring. This omission risks ad-hoc construction that misses SPI instances.

### F9: Exit criteria are not independently verifiable at phase level

`DefaultTurnLoop delegates correctly to engine, gate, queue` — this is an integration behavior validated in Phase 12 tests. At Phase 10's gate, only compilation can be verified. The exit criteria should reflect this: compilation passes, all three files exist, the interface matches Appendix A signatures, and a grep confirms zero `org.springframework.ai` imports in the facade package.

### phase-11-autoconfigure.md

### F1. Goal truncated (metadata alignment)
The ledger goal is detailed and specific; the current phase goal is a one-line summary. The corrected phase uses the ledger goal verbatim.

### F2. Write Targets uses directory instead of individual files (metadata alignment)
Current: `turnloop-autoconfigure/src/main/java/io/mindspice/turnloop/autoconfigure` (directory). The ledger lists individual Java file paths. The corrected phase matches the ledger's canonical target list.

### F3. TurnLoopProperties lacks nested config specification
Implementation step 1 says "nested config classes matching the YAML schema" but does not enumerate which nested classes map to which YAML path, their types, or their validation rules. The handoff §12 defines an exact YAML schema with specific clamp ranges and exception messages. The corrected phase includes typed nested record definitions for every configuration subsection and explicit validation rules with clamp ranges and ConfigurationException messages.

### F4. TurnLoopAutoConfiguration missing SPI auto-discovery pattern
The phase says "auto-discover SPI beans via ObjectProvider with @Order sorting" but does not specify which SPIs are discovered through `List<T>` injection (ordered contributors like `PromptContributor`) vs `ObjectProvider<T>` (singular SPIs like `ToolRegistry`), how the `ApplicationContext` reaches `TurnLoopBuilder.create()`, or that the `TurnLoop` bean must carry `@Bean(destroyMethod = "close")`.

### F5. StreamLifecycle missing backpressure, disconnect, and error-recovery specifics
The phase says "handle client disconnect", "configure backpressure", and "error recovery with onErrorResume" but gives no concrete guidance. The handoff specifies: `onBackpressureBuffer(capacity, BufferOverflowStrategy.DROP_OLDEST)` with a discard handler that emits `TurnEvent.SystemNotice(BUFFER_OVERFLOW)`, `emitter.onCompletion()` and `emitter.onError()` cancel the Flux subscription, and `onErrorResume` maps errors to `TurnEvent.Failed`. The corrected phase includes these concrete patterns.

### F6. TurnLoopHealthIndicator missing model-ping details
The phase says "call default model via ChatModel.call() with health check prompt" but does not specify timeout configuration, error handling, or how the default model alias is resolved from `TurnLoopProperties`.

### F7. TurnLoopMetrics missing exact metric specifications
Current says "timer, counter, and gauges" without specifics. The handoff specifies: `turnloop.turn.duration` (histogram, tags: model, outcome), `turnloop.tool.calls` (counter, tags: tool_name, status), `turnloop.turns.active` (gauge), `turnloop.queue.depth` (gauge, tag: session_id). The corrected phase includes these exact definitions.

### F8. POM dependency specification insufficient
Current says "add dependencies" without listing them. The autoconfigure POM must depend on `turnloop-core` (transitive for meta-starter), `spring-boot-autoconfigure`, `sqlite-jdbc` (required), optional `spring-boot-starter-web` (for SseEmitter), and optional `micrometer-core`.

### F9. Missing meta-starter responsibility
Concepts.md Assumption 11 states `turnloop-autoconfigure` serves as `spring-boot-starter-turnloop` meta-starter. The POM must pull in `turnloop-core` transitively so embedding apps need only one dependency.

### F10. Missing @PreDestroy / lifecycle wiring
Concepts.md Features §15 requires `TurnLoop.close()` on shutdown. The autoconfigure must either use `@Bean(destroyMethod = "close")` or declare a `@PreDestroy` method. The corrected phase specifies this explicitly.

### F11. Validation and exit criteria too weak
Current validation checks file existence and compile only. Exit criteria say "Auto-configuration module complete" — not a production-quality bar. The handoff Phase 5 gate inventory lists specific behavioral expectations. The corrected phase includes concrete validation checks per file and references the handoff gate inventory.

### F12. Technical Guidance section thin
Missing: in-memory SQLite DataSource wiring, how `TurnLoopBuilder.create(ApplicationContext)` works with Spring, the `@ConditionalOnClass(MeterRegistry.class)` pattern, and the `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` format (one FQN per line). The corrected phase expands Technical Guidance to cover these patterns.

### F13. TurnLoopProperties validation rule omission: drain-timeout
Handoff §12 specifies `turn.drain-timeout` must be ≥ 0 (no clamp, accepts 0 — meaning no wait, force-release). The current phase only mentions "clamp out-of-range values" without distinguishing which properties clamp vs. throw.

### phase-12-tests.md

### F1: Phase exceeds single-agent token budget (~40+ test classes)

The current phase groups ~40+ distinct test classes under four coarse implementation steps. A single DeepSeek implementation agent with an assumed 200,000–300,000 token ceiling cannot write, compile, and iterate on that many test files while maintaining quality. The phase must be split into lettered subphases.

### F2: Five framework components lack explicit unit-test targets

`DefaultPromptAssembler`, `ContextManager`, `DefaultTurnLoop`, `TurnLoopBuilder`, and `SpringAiConversationMemoryAdapter` are all production components built in prior phases that need unit tests. Their absence in the current In Scope list is a coverage gap.

### F3: Implementation steps are too coarse

Four steps ("Create unit tests", "Create integration tests", "Create concurrency stress test", "Run all tests") give no meaningful ordering, handoff structure, or incremental verification path. Each subphase needs ordered, concrete steps with specific test classes named.

### F4: Technical guidance under-specified

The current guidance mentions `@BeforeEach`/`@AfterEach` for SQLite and Mockito for `ModelRouter`, but omits:
- Concrete SQLite table-creation fixture pattern (execute DDL from `schema.sql` on `jdbc:sqlite::memory:`)
- Test framework defaults (JUnit 5 + AssertJ + Mockito)
- How integration tests skip when Ollama is unavailable (exact `@EnabledIf` expression or `Assumptions` pattern)
- `@SpringBootTest` configuration class for auto-configuration tests
- `CountDownLatch` + virtual-thread `ExecutorService` pattern for concurrency tests

### F5: Validation gate incomplete

"All tests pass" is a necessary but not sufficient gate. The phase must also verify that every SPI interface method has at least one test and every `TurnPhase` transition is exercised. The corrected phase adds a coverage checklist to the exit criteria.

### F6: No reference to handoff Appendix C gate inventory

The handoff Appendix C Phase 6 gate inventory lists specific artifacts that the test phase must satisfy. The phase should cross-reference Appendix C for implementer clarity.

### phase-13-docs-hardening.md

### F1. Metadata misalignment with orchestration ledger

The **Goal** text in the current phase file is a three-clause summary. The ledger defines a single long-form goal enumerating every sub-deliverable: the Javadoc contract details, all 10 documentation file paths, the specific hardening scan descriptions, the JaCoCo enforcement target, and the root `mvn clean verify` pass. The phase file must carry the ledger goal verbatim.

The **Write Targets** line omits `turnloop-core/src/main/java/io/mindspice/turnloop`, the source tree that receives Javadoc additions. The ledger lists four targets in canonical order; the current phase lists three.

### F2. Phase size warrants subphase split

The phase packs four dense work clusters into a single implementation-step list: (a) complete Javadoc on 20 SPI interfaces plus all public types, engine classes, facade interface, exception hierarchy, and event sealed hierarchy — likely 150+ method-level Javadoc blocks, each requiring `@param`, `@return`, `@throws`, thread-safety, and lifecycle documentation; (b) 10 markdown files including a production-quality SSE controller code example; (c) JaCoCo plugin configuration plus potential coverage-gap test authoring; (d) four hardening grep/scan passes plus root `mvn clean verify` remediation. Two sequential agents are the safer allocation.

### F3. PMD dead-code scan listed in Out of Scope but required by ledger goal

The current Out of Scope says "PMD dead-code scan" yet the ledger goal text explicitly requires "PMD dead-code scan confirms zero references from retained code to deleted Magenta packages." Implementation Step 4 only mentions grep scans. The dead-reference check is in scope and must appear in implementation steps.

### F4. Doc file enumeration is implicit in implementation steps

Implementation Step 2 says "Create `README.md` and all 9 docs files" without enumerating them. The concrete file list (`getting-started.md`, `turn-loop.md`, `tools.md`, `memory.md`, `streaming.md`, `interaction.md`, `queueing.md`, `configuration.md`, `facade-api.md`) only appears in the Goal text. The implementation step should repeat the explicit list so the implementing agent does not need to derive it.

### F5. JaCoCo plugin placement is underspecified

The step says "Configure JaCoCo plugin in root `pom.xml`" but the root POM is Magenta's. The JaCoCo `check` rule targets `io.mindspice.turnloop.*` packages which live in `turnloop-core`. The implementing agent must decide whether to place the plugin in the root POM (aggregator) or in `turnloop-core/pom.xml`. The phase should direct: configure in root `pom.xml` as a `<pluginManagement>` entry with `<executions>` that apply to `turnloop-core`.

### F6. Coverage gap resolution is under-scoped

The Technical Guidance says "If coverage is below 80%, add tests for uncovered branches before proceeding." This is the correct directive, but it is buried in guidance rather than elevated to an explicit implementation step with a decision gate. If Phase 12 delivered ≥80%, this is a verification step. If not, significant test authoring could be required and the implementing agent must own that outcome.

### phase-14-magenta-reintegration.md

### Finding 1: Goal mismatch with orchestration ledger (BLOCKING)

The phase's `## Goal` first line reads:
> `Magenta becomes embedding app consuming turnloop-core; implement app-specific SPIs; delete remaining extracted code; verify context starts and surviving tests pass`

The orchestration ledger requires:
> `Magenta pom.xml depends on turnloop-core; implement app-specific SPI implementations in Magenta packages: mode-gated ToolAccessPolicy plan-detecting CompletionDetector plan/task RepairStrategy agent-scoped SessionStore app-specific PromptContributor; delete all remaining extracted code now provided by framework dependency; update application.yml to framework property namespace; wire Magenta context with framework beans; verify Magenta context starts with framework dependency; verify existing non-web tests pass consuming framework; verify no extracted code remains in Magenta (class absence not import absence)`

The phase Goal is a lossy summary. It omits the explicit SPI implementation inventory (mode-gated, plan-detecting, plan/task, agent-scoped, app-specific), the `application.yml` migration to framework namespace, the wiring of framework beans, and the class-absence verification rule. The corrected phase must use the ledger goal verbatim as its first `## Goal` line.

### Finding 2: Phase size exceeds single-agent session budget (ACTIONABLE)

The phase involves five distinct work clusters that together are likely to exceed a single implementation agent's context window, file/detail load, and focused edit chain:

1. **Deletion cluster** (~40+ source files + ~30+ test files): `ChatService.java`, all SPI interfaces from Phase 03, `ChatModelRouter`, `ContextManagementAdvisor`, `ContextUsageTracker`, `AuditService`, `ChatPendingMessageService`, `ChatToolRegistry`, `ToolTranscriptService`, `ToolArgumentCoercionModule`, repository classes (`ChatMemoryRepository`, `ChatSessionMetadataRepository`, `ChatPendingMessageRepository`, `AuditRepository`, `RepositoryBackedChatMemory`), `ChatMarkdownRenderer`, Magenta's temporary `TurnEvent` and related model classes, `ChatStreamEvent`, and all corresponding tests.

2. **SPI implementation cluster** (5 non-trivial classes): `MagentaToolAccessPolicy` (mode-gated, referencing `PlanMode`, `PlanService`, `TaskService`), `MagentaCompletionDetector` (plan/task completion detection via tool call inspection), `MagentaRepairStrategy` (specialized repair messages for plan execution gaps), `MagentaPromptContributor` (agent identity, workspace context, `AGENTS.md` injection), `MagentaSessionStore` (extends framework `SqliteSessionStore` with agent-scoped queries).

3. **Configuration cluster**: rewrite `AiConfig.java` to produce framework `TurnLoopBuilder` and `TurnLoop` beans, remove deleted class bean definitions, update `application.yml` with `turnloop:` namespace.

4. **Adaptation cluster**: update all surviving Magenta code referencing deleted types to use framework equivalents (`TurnLoop` instead of `ChatService`, `TurnMessage` instead of `ChatMessage`, framework SPI types instead of Magenta temporary SPIs).

5. **Verification cluster**: compile, test suite, context startup, class-absence grep, import-absence grep.

The phase must be split into lettered subphases A, B, C with clear ownership boundaries.

### Finding 3: Implementation steps lack specific deletion and creation inventories

Current Implementation Steps are narrative-level descriptions without exact file lists. An implementer would need to discover the deletion inventory by inspecting the codebase. The corrected phase must include:

- Exact source files to delete (class-named, not just package-globbed)
- Exact test files to delete
- Exact class-to-class mapping for what framework type replaces each deleted Magenta type
- Specific guidance for each SPI implementation referencing the current Magenta behavior that must be preserved

### Finding 4: SPI implementation guidance is underspecified

The five Magenta SPI implementations are described in a single sentence each. Given that these are the only Magenta-specific code surviving the extraction, they must carry forward Magenta's operational assistant behavior. The corrected phase must include:

- **MagentaToolAccessPolicy**: must replicate the existing `ToolAccessPolicy.filterToolsByMode()` logic — PLAN/TASK/EXECUTE_PLAN/EXECUTE_TASK/NORMAL mode detection, `PLAN_MODE_TOOLS`/`TASK_MODE_TOOLS` allowlists, `NORMAL_BLOCKED_TOOLS`/`EXECUTION_BLOCKED_TOOLS` blocklists, `agent_*` operational tool filtering, `activate_skill` hiding when unavailable
- **MagentaCompletionDetector**: must detect plan/task completion by checking whether the model called terminal tools (`plan_ready_for_approval`, `plan_complete`, `task_ready_for_approval`, `task_complete`) or `ask_user_questions`
- **MagentaRepairStrategy**: must carry forward the `TerminalTurnRepair` logic — empty-final-response control messages per mode, invalid-plan-turn control messages, execution-completion repair, and tool-use-abort control messages
- **MagentaPromptContributor**: must inject agent identity, workspace context, runtime `AGENTS.md` resolution, mode/task intent — carrying forward the `PromptContextAssembler` layering
- **MagentaSessionStore**: must extend framework `SqliteSessionStore` to add agent-scoped queries filtering sessions by agent ownership

### Finding 5: Dependency and write-target metadata are correct

The `## Dependencies` line (`phase-13-docs-hardening.md`) matches the ledger. The `## In Scope` / `**Write Targets**` line (`pom.xml, src/main/java/io/mindspice/magenta2/ai/chat, src/main/java/io/mindspice/magenta2/ai/config, src/main/resources/application.yml, src/test`) matches the ledger's `targets:` field. No corrections needed for these fields.

### Finding 6: Configuration migration path is underspecified

The phase says "update `application.yml` with `turnloop:` configuration block" but doesn't specify the mapping from existing Magenta config (`app.ai.config-path`, `spring.ai.ollama.*`, `magenta.ai.*`) to the framework namespace (`turnloop.models.providers`, `turnloop.models.default`, etc.). The corrected phase must provide the mapping table.

## Risk Assessment

### decomposition.md

- **Phase count expansion from 8 to 14** introduces additional overhead but each phase is now a cohesive, single-agent-implementable unit. No phase creates types in more than two conceptual categories. The serial wave topology (one phase per wave) eliminates all parallel-overlap risk.
- **Target list verbosity** for phase 05 (28 deletion targets) is mechanically required but adds no functional risk — the phase is a pure deletion pass with one configuration refactor.
- **Concept file drift** from the original handoff is minimal. The concepts.md is condensed from the handoff's Architecture, Conceptual Approach, Features, Constraints, Assumptions, Cross-phase Guidance, and Final Validation Criteria sections. No design authority is altered.

### concepts.md

| ID | Risk | Severity | Probability | Mitigation |
|---|---|---|---|---|
| R1 | Spring AI version pinned prematurely (F1) | High | High — implementers will code to 2.0.0 API surface before audit | Strike "2.0.0" from assumption; pin only after audit |
| R2 | Phase numbering confusion between handoff and plan (F2) | Medium | Medium — handoff readers will not find referenced phases | Add phase mapping note to concepts or reference plan phases only |
| R3 | Open questions left unresolved into implementation (F3) | Medium | Medium — implementers will make local decisions that diverge | Promote open questions into concepts; require resolution before relevant phase start |
| R4 | Model config file format divergence (F3-Q4) | Medium | Low — handoff supports YAML, programmatic, and JSON auto-discovery | Decide format before Phase 06 (core types) so types are designed for it |
| R5 | Audit table unbounded growth (F3-Q8) | Medium | Medium — production deployments will fill disk over months | Either add retention to framework or document as embedding-app concern before v1 |
| R6 | SPI count ambiguity misleads external consumers (F4) | Low | Low — SPI table and Appendix A are authoritative | Use exact count in concepts |
| R7 | Missing scope documentation invites scope creep (F6) | Low | Medium — contributors may add out-of-scope features | Add scope section to concepts |

### orchestration.md

### High risk: Phase 09 size and complexity

With 4 subphases and targets spanning the entire engine, tool framework, renderers, example tools, and Magenta code migration, Phase 09 is the plan's integration point. Risk: subphase D (moving surviving Magenta code into framework packages) may discover hidden coupling that requires backtracking into earlier phases. Mitigation: the strict sequential subphase ordering with validation after each subphase prevents cascade failure; subphase D can be executed last and independently verified.

### High risk: Virtual-thread safety of target Spring AI version

The plan assumes virtual-thread compatibility of Spring AI's HTTP client. If the audit in Phase 09 finds pinning, the `TurnExecutor` must fall back to a fixed thread pool, which is an acceptable but scope-changing outcome. The target Spring AI version (Open Question #6) must be decided before Phase 06 so the audit produces a meaningful result.

### Medium risk: Test preservation during Phases 02–05

Phases 02–05 delete large swaths of Magenta code while preserving non-web tests for `ChatService`, `ChatMemoryRepository`, `ChatToolRegistry`, `ChatModelRouter`, and `ConversationTurnCoordinator`. The exact test file list (Open Question #5) must be scoped before Phase 02 begins to prevent accidental deletion of surviving test coverage.

### Medium risk: Schema migration from Magenta's 20-migration system

The framework replaces Magenta's complex `SchemaMigrationRunner` with a minimal version-tracked `MigrationRunner`. Risk: the framework DDL (Appendix B) may miss edge cases handled by Magenta's migration history. Mitigation: the framework starts fresh with `CREATE TABLE IF NOT EXISTS` — no data migration from legacy Magenta DBs is in scope, and the handoff explicitly excludes this.

### Low risk: Optional dependency graceful degradation

`MarkdownRenderer` depends on optional `commonmark` and `owasp-html-sanitizer` libraries. Risk: classpath conflicts in embedding apps. Mitigation: the plan correctly scopes these as optional Maven dependencies; graceful degradation (return input unmodified) is tested in Phase 09.

### Low risk: Phase 14 reintegration coupling

Magenta reintegration depends on every prior phase passing. Risk: if Phase 14 discovers that a framework SPI is insufficient for Magenta's app-specific needs, earlier phases may need revision. Mitigation: the 20-SPI surface was designed from Magenta's actual usage patterns; the handoff's Appendix A signatures were red-team reviewed for this exact concern.

### phase-01-scaffold-baseline.md

**Risk: Low.** This is a scaffolding phase with zero code changes to Magenta. The worst-case failure modes are:

1. Maven Enforcer misconfiguration causes `turnloop-core` POM to fail during `mvn verify` — easily diagnosable and fixable within the phase.
2. Empty directory structure not tracked by git — caught during implementation if the agent inspects `git status`.
3. The non-standard `spring-boot-starter-webmvc-test` coordinate in the ban list is harmless for now but must be corrected before Phase 12 when actual test dependencies arrive in `turnloop-core`.

No risk to existing Magenta functionality. The `mvn clean verify` gate confirms the unchanged codebase still builds.

### phase-02-delete-web-ui.md

| Risk | Severity | Mitigation |
|------|----------|------------|
| Implementer deletes `ChatPlanState.java` alongside removing its references from `TurnEvent` | Medium — causes compilation failures in `ChatService`, `ChatHistory`, `ChatResponse`, `PlanService`, `TerminalTurnRepair` | Corrected phase explicitly states `ChatPlanState.java` must not be deleted; add `git status` verification step |
| Implementer overlooks `tests/playwright/` and `artifacts/playwright/` | Low — these are non-compilation artifacts; no build impact | Corrected phase lists them as deletion targets |
| Implementer deletes `ChatStreamEventSerializationTest.java` thinking it is under `api/web/` | Medium — surviving test coverage is lost | Corrected phase states the exact file path and its survival requirement |
| `OutputsChanged` subtype retained in renamed `TurnEvent` | Low — it compiles fine; dead code removed in Phase 07 anyway | Corrected phase explicitly lists it for removal now to minimize confusion |
| Web dependency removal breaks transitive dependency needed by surviving code | Low — `spring-boot-starter-web` is the only potentially transitive provider; surviving code uses Spring AI, JDBC, Jackson, all independently declared | Phase 01 scaffold already verified Magenta builds unchanged; post-deletion `mvn dependency:tree` diff recommended |

### phase-03-create-spi-interfaces.md

- **Low risk**: Creating four new interfaces with no callers is inherently safe. The only compilation risk is from the three adaptations, which are additive (adding `implements` clauses and new methods) and preserve all existing public APIs.
- **Medium risk on PromptContextAssembler**: The `contribute()` method must correctly extract `PlanMode` and `conversationId` from the Magenta `TurnContext` without introducing null-path crashes. The corrected phase includes explicit null-guard guidance.
- **Low risk on caller breakage**: No ChatService callers are changed in this phase. All existing `assemble()` methods are preserved as bridge entry points.

### phase-04-remove-chatservice-deps.md

| Risk | Severity | Mitigation |
|------|----------|------------|
| Tool-loop state machine regression from PlanMode removal | High | Subphase B includes exact branch-mapping table for each PlanMode-dependent code path |
| Phase 03 output incomplete — SPI helpers still reference app-shell types | Medium | Subphase A gates on `mvn compile` before proceeding to B |
| Single constructor collapse introduces null-guard gaps for nullable core services | Medium | Explicit nullable/required annotation contract in corrected phase constraints |
| ChatServiceTest breakage from deleted public methods exceeding test deletion scope | Low | Subphase C validates `mvn test -pl . -Dtest=ChatServiceTest` passes |
| Remaining `@Autowired(required = false)` in other files missed by grep | Low | Validation grep scoped to `src/main/java/`; Phase 05 will also sweep |

### phase-05-delete-app-shell.md

| Risk | Severity | Rationale |
|------|----------|----------|
| Surviving source file adaptations (F1) | High | 6 files need non-trivial edits; `ChatSessionMetadataRepository` alone has 6+ methods touching deleted types and an inner record to remove. If incorrectly adapted, ChatService and other surviving services break. |
| Surviving test file adaptations (F2) | Medium | 6 tests need adaptation. `ToolAccessPolicyTest` and `PromptContextAssemblerTest` are heavily coupled to `PlanMode`. Simple import removal won't suffice — tests may need restructuring. |
| Imprecise tool/ test deletion (F3) | Medium | Accidental deletion of `ChatToolRegistryTest.java` or sibling files would lose viable tests. |
| AiConfig constructor collapse (F4) | Low | Straightforward once fields are enumerated. Risk is in callers that construct AiConfig with deleted constructors. |
| Phase 04 dependency (F6) | Medium | If Phase 04 fails to remove TrustedContextPolicyAssembler from ChatService, Phase 05 agent must diagnose and fix 2500+ line ChatService — well beyond this phase's scope. |
| Compilation fix pass volume | Medium | After deletions + adaptations, the agent may face cascading secondary errors from bean wiring, test compilation, and configuration. Iterative fix loop could consume significant context. |

### phase-06-core-types.md

**Low risk overall.** The phase creates data carriers with zero external dependencies (no Spring, no servlets, no persistence). The only risk is the cross-phase compilation dependency on TurnPhase, ToolCallback, and ToolDefinition. The corrected phase resolves this by including those three files as dependency types. All other risks are editorial.

**No split needed.** 19 files of records, enums, and simple classes fit comfortably within one agent session at roughly 20% of the assumed token budget. Subphase letters are not warranted.

### phase-07-events-spis.md

- **Overlap collision (medium)**: The AnswerResult/QuestionPendingContext overlap with phase 06 is the highest operational risk. If both agents write the same file, the second one will hit a compilation failure or overwrite. Mitigation: the corrected phase adds an explicit coordination note.
- **Nested-type omission (low-medium)**: An implementer working from the old phase text might create `TurnGate.java` without `AcquireResult`, `TurnGateMode`, or `GateState`, producing an interface that doesn't match the handoff and won't work with later phases. The corrected phase enumerates all nested types explicitly.
- **Timestamp omission (low)**: The `TurnEvent` sealed hierarchy would compile without `timestamp` fields but would be incorrect against the handoff contract. Corrected with explicit field enumeration.
- **Schema consistency (low)**: All types are pure interface/record/enum definitions with no implementation logic. There is no schema, persistence, or threading risk at this layer.
- **One-agent capacity (pass)**: Creating ~34 files of well-specified interface and record definitions by transcription from the handoff fits comfortably within a single agent session. No subphase splitting needed. The TurnEvent file (~200 lines) is the largest single unit; the remaining 31 files average ~30 lines each.

### phase-08-internal-persistence.md

| Risk | Severity | Mitigation |
|------|----------|------------|
| Metadata divergence causes workflow engine rejection | High | Corrected phase copies ledger goal, dependencies, and targets verbatim |
| Internal interface list includes out-of-scope items causes compile dependency on Phase 09 types | High | Removed ContextManager and DefaultPromptAssembler; moved SpringAiConversationMemoryAdapter to persistence |
| Single-agent token budget exceeded during implementation | Medium | Split into subphases A and B with sequential DeepSeek agents |
| SQLite concurrency bugs in persistence layer | Medium | Phase 12 test suite validates concurrent access; immediate correctness gates are compile-only in this phase |
| `ModelRouter` leak of Spring AI types into non-internal visibility | Medium | Guidance reinforced: package-private, no `public` modifier on the interface |
| `schema.sql` divergence from handoff Appendix B DDL | Low | DDL must be transcribed exactly; validation step confirms table/index count |

### phase-09-engine-utilities.md

### High: Token/context overload without subphase split
If one agent attempts this phase unsplit, it will consume >100K tokens just reading the necessary Magenta source files (ChatService alone is 2900 lines), plus all framework interfaces from prior phases, before writing any new code. With 14 new files and ~10 merge operations, the agent will run out of context before completing half the work. **Mitigation**: Split into 4 sequential subphases (A–D).

### High: Engine extraction fidelity
`ChatService.toolChat()` is a production-hardened 540-line state machine with complex state transitions, interrupt handling, question-user loop suspension, tool-loop guard integration, thinking extraction, and async compaction. Extracting it faithfully means preserving every transition path, every error-handling branch, and every concurrency contract. A mistake in any transition can break the turn loop. **Mitigation**: Subphase A is entirely dedicated to the engine + its companions, giving an agent focused context to read, understand, and replicate the full state machine without distraction.

### Medium: Magenta code merge conflicts
Several merges modify files created in Phase 08 (e.g., `SqliteConversationMemory`, `SqliteSessionStore`, `SqliteAuditWriter`) or files created earlier in this same phase (e.g., `ContextManager`, `MarkdownRenderer`). This creates a dependency chain within Subphase D where merge ordering matters. **Mitigation**: Explicit ordering in the implementation steps — read all Magenta sources first, then merge into framework targets one at a time, verifying compilation after each merge batch.

### Medium: Unresolved virtual-thread audit dependency
Open Question #6 in concepts.md asks which Spring AI version is targeted. The engine defaults to virtual threads. If the target version's HTTP client pins carrier threads, the default is wrong. **Mitigation**: Commit the engine with the `turnloop.executor.strategy` configuration property defaulting to `virtual` but with a `fixed` fallback. The audit finding can change the default via config, not code.

### Low: Optional dependency resolution
`MarkdownRenderer` depends on `commonmark` and `owasp-html-sanitizer` as optional Maven dependencies. Adding these to the POM with `<optional>true</optional>` is straightforward, but the agent must also handle the classpath detection correctly (try-catch `Class.forName` or `ServiceLoader`). **Mitigation**: Explicit code pattern in technical guidance.

### phase-10-facade.md

**High risk — scope blowout without subphases.** DefaultTurnLoop is the single largest implementation unit in the entire plan. It orchestrates every SPI, manages the close() lifecycle, wraps all exceptions, and implements three sub-interfaces. A single agent tackling this without subphase structure will either produce a skeleton that passes compilation but lacks edge-case handling, or will exhaust its context before completing all three files.

**Medium risk — close() ordering defects.** The 7-step close() sequence has strict ordering: cancel questions before draining, drain before force-release, force-release before closing models, close models before shutting down executor. A wrong order produces leaked threads, orphaned claims, or silent resource retention. Without explicit ordered steps in the phase, the implementer is likely to reorder or collapse steps.

**Medium risk — exception leak at answer().** The `answer()` method resumes an engine from suspended state and loads from `InteractionStore`. If the stored context is stale (session cleared after suspend), the engine may throw an internal exception that isn't caught. The phase doesn't call out this specific wrapping path.

**Low risk — TurnLoopBuilder SPI overload.** With 20+ builder methods, an implementer may accidentally omit one SPI. The phase should include a checklist confirming every SPI from the handoff has a corresponding builder setter.

### phase-11-autoconfigure.md

**Low risk overall.** The phase builds on the stable `TurnLoop` facade and `TurnLoopBuilder` from Phase 10. The autoconfigure module is a Spring Boot convention layer with well-understood patterns (`@AutoConfiguration`, `@ConfigurationProperties`, `@ConditionalOnMissingBean`).

**Moderate risk: `TurnLoopProperties` validation.** The handoff specifies both clamping (with warning log) and throwing `ConfigurationException`. Getting the distinction right — clamp for range violations, throw for missing model reference and invalid URLs — matters for startup safety. The corrected phase enumerates each rule with its action.

**Moderate risk: `StreamLifecycle` backpressure/disconnect wiring.** SSE emitters and reactive backpressure interact subtly. The handoff's concrete patterns (DROP_OLDEST + SystemNotice, cancel on completion/error) reduce this risk to manageable. The corrected phase embeds these patterns.

**Low risk: `TurnLoopMetrics` Micrometer implementation.** Straightforward metric registration behind `@ConditionalOnClass`. The corrected phase adds the exact metric names from the handoff.

### phase-12-tests.md

| Risk | Severity | Mitigation |
|------|----------|------------|
| Agent context overflow causing incomplete test coverage | High | Split into 5 sequential subphases A–E |
| Missing component tests discovered in Phase 13 coverage scan | Medium | Added `DefaultPromptAssembler`, `ContextManager`, `DefaultTurnLoop`, `TurnLoopBuilder`, `SpringAiConversationMemoryAdapter` to subphase D |
| Integration tests fail because Ollama not present in CI | Medium | Explicit `@EnabledIf` skip mechanism with clear log message |
| Concurrency test flakiness from shared SQLite state | Low | Use distinct session IDs per thread and in-memory DB per test class |
| Subphase handoff inconsistency (agent B writes tests for code agent A wrote) | Low | Each subphase has a concrete file manifest; later subphases extend, never rewrite |

### phase-13-docs-hardening.md

- **Coverage shortfall (medium)**. If Phase 12 tests left coverage below 80%, Subphase B absorbs non-trivial test authoring that crosses the agent boundary from documentation to implementation. The subphase must treat this as a hard gate, not a best-effort attempt.
- **Javadoc doclint on Java 25 (low)**. New doclint rules may surface warnings beyond `-Xdoclint:all,-missing`. The phase already provides the flag; if it proves insufficient the agent must adjust and document the final incantation.
- **PMD tooling availability (low)**. The phase assumes PMD is available on the classpath or via Maven plugin. If not, the grep-based dead-reference scan is an acceptable substitute as long as it covers all deleted package prefixes enumerated in Phase 05.

### phase-14-magenta-reintegration.md

| Risk | Severity | Mitigation |
|------|----------|------------|
| Incomplete code deletion leaves extracted code in Magenta, causing classpath conflicts with framework equivalents | High | Subphase A ends with automated grep verifying class absence (not import absence); Subphase C repeats this check as exit criterion |
| Magenta SPI implementations are stubs rather than production-quality, losing operational assistant behavior | High | Detailed implementation guidance in the corrected phase ties each SPI to its current Magenta behavioral source; subphase B validator checks for stub/mock patterns |
| Configuration migration breaks model routing because provider names or endpoint formats differ | Medium | Explicit mapping table in corrected phase; subphase B validator verifies `TurnLoop` bean creates successfully |
| Surviving Magenta code has hidden references to deleted types that only surface at runtime | Medium | Compile check in subphase A, test run in subphase C, context startup in subphase C |
| `AiConfig` simplification interacts with other Spring beans (`AiUserConfigConfiguration`, `ExternalAiConfigLoader`) that also need updating | Low | Subphase B includes `AiUserConfigConfiguration` adaptation; `ExternalAiConfigLoader` stays as config-file reader |
| Test adaptation scope is larger than expected because Magenta tests use `ChatService` directly | Medium | Subphase C explicitly scoped for test adaptation; validator checks all surviving test files compile and pass |

## Recommendations

### decomposition.md

1. Accept the corrected 14-phase plan as the implementation contract. Every phase passes the plan validator's structural and cross-consistency checks.
2. Before execution, verify that `deepseek/deepseek-v4-pro` and `openai-codex/gpt-5.6-terra` are available and authenticated in the orchestration environment.
3. Confirm Ollama is running at `localhost:11434` before phases 12 (integration tests) and 14 (Magenta re-integration validation).

### concepts.md

1. **Strike the Spring AI version pin.** Replace _"for version 2.0.0"_ in Assumption #1 with _"for the target version (to be determined; see Open Questions)"_. The audit finding should dictate the version, not the reverse.

2. **Add a phase scheme note.** Include a short paragraph in the Architecture section stating that the 14-phase implementation plan refines the handoff's 7 recommended phases, and that all phase number references in `concepts.md` use the 14-phase scheme.

3. **Promote open questions to the concepts document.** Add an _Open Questions_ section that replicates the handoff's eight questions. This ensures anyone reading `concepts.md` as the implementation contract sees what is still unresolved.

4. **Add a Scope summary.** Include abbreviated In/Out scope lists keyed to the handoff. This prevents feature creep during implementation and gives phase implementers clear boundary definitions.

5. **Use the exact SPI count.** Replace _"~17"_ with the exact count from the handoff's SPI table. The 20 interfaces are: ToolRegistry, ToolAccessPolicy, ToolResultSummarizer, InteractionHandler, InteractionStore, ConversationMemory, SessionStore, TokenEstimator, CompactionStrategy, CompletionDetector, RepairStrategy, TurnGate, MessageQueue, MessageQueueStore, AuditWriter, PromptContributor, ModelCapabilityProvider, TurnLoopMetrics, TurnLifecycleListener, ThinkingExtractor.

6. **Reference configuration validation rules.** In Feature #12, add a sentence noting that `TurnLoopProperties` includes validation rules (model-to-provider reference, valid URL, clamp ranges) with concrete `ConfigurationException` messages.

7. **Reference handoff Appendix C.** In the Final Validation section, note that Appendix C of the handoff provides per-phase gate artifact inventories that are the implementation-level bar for each phase.

8. **Resolve open question #5 before Phase 02.** The "exact file list for test preservation vs. deletion" must be scoped before the delete-web-ui phase begins. This is a blocking dependency for Phase 02.

9. **Resolve open question #8 (audit retention) before Phase 08.** The audit table DDL and writer implementation are created in Phase 08. Decide whether the framework or the embedding app owns retention before the schema is created.

### orchestration.md

1. **Fix the Phase Ledger format.** Use exactly one flat `- phase-NN-slug.md | depends: ... | targets: ... | goal: ...` entry per phase. Consolidate subphase targets and goals into the single per-phase line. The corrected orchestration.md below implements this.

2. **Resolve Open Questions before Phase 01.** The artifact name, JPMS module name, target Spring AI version, and test preservation file list are gating decisions. Without them, implementers will make arbitrary choices that may require rework.

3. **Create per-phase plan files for phases 06–14.** Each of these phases needs a dedicated plan file (e.g., `phase-06-core-types.md`) containing the subphase breakdown (A, B, etc.) with exact targets per subphase, acceptance criteria, and gate inventory. The orchestration ledger references these by filename; the plan files own the subphase detail.

4. **Preserve the subphase structure for large phases.** Do not flatten phases 06–14 into single monolithic implementation sessions. The subphase decomposition (A through D for phase 09) is necessary for implementation agents to stay within context limits. Each subphase should have its own gate checkpoint within the phase plan file.

5. **Audit Spring AI virtual-thread safety before Phase 06.** The Open Question about target version must be resolved, then the audit conducted, before the `TurnExecutor` virtual-thread implementation is committed in Phase 09. Document the finding and fallback plan.

6. **Run a dry-run gate check after Phase 01.** Verify that the Maven Enforcer `bannedDependencies` rule works correctly (catches `spring-boot-starter-web`, `SseEmitter`, servlet API) before depending on it through all subsequent phases.

### phase-01-scaffold-baseline.md

1. Add `.gitkeep` creation to implementation steps for all eight empty source directories.
2. Include a concise Maven Enforcer `<plugin>` XML fragment in the Technical Guidance section showing correct `<excludes>` syntax for `bannedDependencies`.
3. Address JPMS open question #3 explicitly: either add `<Automatic-Module-Name>io.mindspice.turnloop</Automatic-Module-Name>` to the `turnloop-core` POM `MANIFEST` entries (per the accepted assumption), or flag the question as blocking before the phase proceeds.
4. Note the `spring-boot-starter-webmvc-test` coordinate concern as a deferred fix for Phase 12, not a blocker for Phase 01.

### phase-02-delete-web-ui.md

1. **Accept corrected phase as-is.** All six findings (F1–F6) are addressed in the corrected phase file submitted alongside this review. No structural changes to the plan are needed.

2. **Add `tests/playwright/` and `artifacts/playwright/` to the detailed deletion list** in the Implementation Steps, even though the ledger's Write Targets line cannot change. The corrected phase does this.

3. **Enumerate the exact field removal per subtype.** The corrected phase includes a table mapping each `ChatStreamEvent` subtype to its removal set.

4. **Explicitly state `ChatPlanState.java` survival.** Added as a bolded warning in the corrected phase's Implementation Step 4.

5. **Add cross-reference grep to validation.** Added as validation step in corrected phase.

6. **Identify the surviving serialization test by exact path.** Added to Implementation Step 4.

### phase-03-create-spi-interfaces.md

1. Accept the corrected phase as written. The adaptation plan preserves all existing behavior while introducing the four SPI contracts at Appendix A-aligned signatures.
2. During Phase 04 implementation, the `PromptContextAssembler.assemble()` bridge methods will be refactored to remove side effects and delegate purely to `contribute()`. Phase 03 should not attempt this; it only establishes the `implements` relationship.
3. After Phase 05 deletes `PlanMode` and app-shell types, the `PromptContextAssembler` is deleted entirely. The temporary SPIs created here are replaced by framework-native SPIs in Phase 07. This is an intentional scaffold-and-replace pattern.

### phase-04-remove-chatservice-deps.md

1. **Split into three subphases A, B, C.** Each subphase maps to one sequential DeepSeek implementation agent session. Subphases must complete in order before phase-level validation applies.
2. **Add exact branch-mapping table** for tool-loop mode handling in Subphase B.
3. **Add explicit collaborator removal order** with stay/go disposition in Subphase A.
4. **Add pre-flight check** that Phase 03 SPI helpers are clean of app-shell imports before Subphase A begins.
5. **Preserve corrected phase exactly** with ledger-aligned Goal, Dependencies, and Write Targets.

### phase-05-delete-app-shell.md

1. **Split into two subphases.** Subphase A does all deletion (directories, files, tests, migrations). Subphase B does all config simplification, surviving-file adaptation, compilation fixes, and verification. Each subphase is one agent session.

2. **Enumerate affected surviving files.** The corrected phase explicitly lists every surviving source and test file that references deleted types, with adaptation guidance for each.

3. **Specify exact test deletions.** Replace "delete all corresponding test files" with explicit directory paths, subdirectory paths, and individual file paths.

4. **Provide AiConfig field retention list.** Explicitly name fields to keep and fields+methods to delete.

5. **Add Phase 04 pre-condition check.** The first step of Subphase B verifies that `ChatService.java` contains zero references to `TrustedContextPolicyAssembler` before proceeding.

6. **Add surviving test adaptation guidance.** For tests that reference `PlanMode`, `ChatSessionSurface`, `ChatPlanState`, or `ChatInvocationProfile`, provide specific edit instructions: remove the `ChatSessionSurface` parameter from method calls rather than deleting the tests; replace `PlanMode` enum references in test assertions with plan-agnostic equivalents; remove `ChatPlanState` from event constructors.

### phase-06-core-types.md

1. Copy the orchestration ledger goal, dependencies, and write targets verbatim into the corrected phase.
2. Add `TurnPhase.java`, `ToolCallback.java`, and `ToolDefinition.java` to the implementation steps as dependency types. These are not ledger targets but are required for compilation; note them as such.
3. Remove `QuestionPendingContext`, `AnswerResult`, `ThinkingResult`, and `AssistantMessageContent` from implementation guidance — they belong to Phase 07.
4. Correct the TurnContext thread-safety note to mention ConcurrentHashMap usage.
5. Replace hard-coded dependency versions with a note to verify latest compatible versions at implementation time.
6. Rename any `ConversationFilter` references to `SessionFilter` for consistency with the code blocks and ledger targets.
7. Retain the single-agent, unsplit structure. No subphase letters are needed.

### phase-07-events-spis.md

1. Apply the corrected `phase-07-events-spis.md` as written. The implementer should keep the handoff Appendix A open as the single source of truth for every method signature.
2. Before executing phase 07, inspect whether phase 06 created `AnswerResult.java` and `QuestionPendingContext.java`. If yes, verify their signatures match the handoff and skip recreation. If no, create them per the corrected phase.
3. The implementer should create files in the cross-reference order given in the corrected Implementation Steps: TurnEvent first (it depends on TurnDiagnostic, NoticeKind, ToolActivityStatus, ContextUsage from phase 06), then the exception hierarchy, then supporting types, then SPI interfaces.
4. The post-phase validator should confirm all 32 specific file paths from the ledger exist on disk, not just the two parent directories.

### phase-08-internal-persistence.md

1. **Fix metadata alignment (mandatory):** Copy the ledger goal as the first line under `## Goal`. Set `## Dependencies` to `phase-07-events-spis.md`. Set `## In Scope` `**Write Targets**` to the exact comma-separated file list from the ledger in canonical order.

2. **Correct internal interface scope (mandatory):** Remove `ContextManager` and `DefaultPromptAssembler` from this phase entirely (they are Phase 09). Move `SpringAiConversationMemoryAdapter` from the internal interfaces list to the persistence implementations list, placed in `persistence/` package alongside the SQLite classes.

3. **Split into sequential subphases A and B (recommended):**
   - **Subphase A**: Framework-internal interfaces (`ModelRouter`, `TurnExecutor`, `ActiveTurnRegistry`), `ToolLoopGuard`, `ToolArgumentCoercionModule` with service-loader registration, `Migration` interface, `MigrationRunner`, `schema.sql`, and `pom.xml` dependency additions. One sequential DeepSeek implementation agent.
   - **Subphase B**: All SQLite persistence implementations (`SqliteConversationMemory`, `SqliteSessionStore`, `SqliteMessageQueueStore`, `SqliteAuditWriter`), `NoOpAuditWriter`, and `SpringAiConversationMemoryAdapter`. One sequential DeepSeek implementation agent (must run after A completes).
   - Both subphases must complete before the phase-level validation and exit criteria apply. No intermediate independent validation gate between A and B; subphase B may assume A's artifacts exist for compilation.

4. **Add NoOpAuditWriter guidance (recommended):** Include explicit instruction to create `NoOpAuditWriter` (all methods no-op, implements `AuditWriter`) alongside `SqliteAuditWriter` in subphase B.

### phase-09-engine-utilities.md

1. **Replace the goal text** with the verbatim orchestration ledger goal.

2. **Replace the In Scope write targets** with the verbatim ledger comma-separated file paths.

3. **Split into four subphases**:
   - **Subphase 9-A: Engine Package** — `DefaultTurnEngine` (package-private, 6-state cycle faithfully replicated from `ChatService.toolChat()`, with interrupt, ask_user, loop guard, repair, thinking extraction, context monitoring, async compaction), `DefaultPromptAssembler` (3-layer priority structure), `ContextManager` (windowed queries, token-budget management, buffer-percent). These three are tightly coupled; the engine uses both assembler and manager.
   - **Subphase 9-B: Concrete SPI Implementations** — `DefaultToolRegistry` (two-stage registration, name-collision detection, `resolveForTurn` merging), `SerialTurnGate` (ReentrantLock state machine IDLE→EXECUTING→AWAITING_ANSWER, timeout, drain), `CompositeThinkingExtractor` (Ollama metadata→DeepSeek reasoningContent→think tag fallback), `InMemoryInteractionStore` (ConcurrentHashMap).
   - **Subphase 9-C: Utilities, Examples, POM** — `MarkdownRenderer` (optional deps with graceful degradation), `TurnEventRenderer.json(ObjectMapper)` and `.sse()`, `EchoTool`, `DateTimeTool` (NOT auto-registered), POM updates for optional dependencies.
   - **Subphase 9-D: Magenta Code Migration** — Read surviving Magenta sources, merge into framework equivalents (explicit per-class instructions), simplify `ChatService.java` (strip to thin wrapper or delete), delete `MagentaWorkExecutor` and `ToolTranscriptService` switch, verify `turnloop-core` and Magenta compile independently.

4. **Add per-class merge instructions** for Subphase D, listing each Magenta source file, its framework target, the merge direction (extract methods vs. inline logic vs. delete-and-replace), and the post-merge cleanup (what to delete from Magenta).

5. **Explicitly state the subphase agent contract**: each lettered subphase maps to one sequential DeepSeek implementation agent session; all subphases complete in order before the phase-level validation and exit criteria apply; no intermediate independent validation gates.

6. **Remove the blocker phrasing on virtual-thread audit** and replace with: "Commit engine with ExecutorStrategy enum (VIRTUAL, FIXED) controlled by configuration; default to VIRTUAL; the audit finding determines whether the default changes or documentation is added."

7. **Add explicit compilation validation steps** for both `mvn compile -pl turnloop-core` and Magenta `mvn compile`, gating Subphase D exit on both passing.

### phase-10-facade.md

1. **Fix Goal to match the ledger verbatim.** Copy the exact goal text from the orchestration Phase Ledger.
2. **Split into three lettered subphases A/B/C**, each mapped to one sequential agent session. Subphase A produces TurnLoop.java. Subphase B produces DefaultTurnLoop.java. Subphase C produces TurnLoopBuilder.java. Phase-level validation gate applies after C.
3. **Add ordered close() steps** to Subphase B's implementation guidance with the exact 7-step sequence and default 30s drain timeout.
4. **Enumerate exception wrapping pairs** in Subphase B: every internal type and its public wrapper.
5. **Add TurnLoopBuilder validation checklist** to Subphase C: default model exists, at least one model configured, data root non-null.
6. **Specify sub-interface implementation pattern** — package-private inner classes in DefaultTurnLoop.java, delegating to the enclosing instance's fields.
7. **Add constructor dependency inventory** to Subphase B so the implementer knows exactly which fields to declare and how they arrive.
8. **Align exit criteria with phase-level verifiability** — compilation, file existence, signature audit against Appendix A, no Spring AI imports in the facade package.

### phase-11-autoconfigure.md

1. **Accept the corrected phase.** All findings are addressable in the rewrite. No architectural concerns requiring broader plan revision.
2. **Do not split into subphases.** The autoconfigure classes are tightly coupled — Properties feeds into AutoConfiguration which wires StreamLifecycle, Metrics, and Health. Splitting would create awkward half-built states with no intermediate validation gate.
3. **The Phase 10 implementer must produce a stable `TurnLoopBuilder` API before this phase starts.** Specifically: builder methods for all 20 SPIs, `create(ApplicationContext)` static factory, and `build()` returning `TurnLoop`. The autoconfigure phase wires these but does not change them.

### phase-12-tests.md

1. **Split into five subphases A–E** with concrete file manifests per subphase.
2. **Add the five missing component test targets** to subphase D (Engine and Facade Unit Tests).
3. **Specify test framework defaults** (JUnit 5, AssertJ, Mockito) and concrete SQLite fixture pattern in Technical Guidance.
4. **Add integration-test skip mechanism** using `@EnabledIf("ollamaAvailable")` with a static method that checks `localhost:11434`.
5. **Add a coverage checklist to Exit Criteria** mapping each SPI and engine transition to its test class.
6. **Add a Required Guides reference** to the handoff Appendix C gate inventory.
7. **Preserve the corrected goal, dependencies, and write targets** exactly from the orchestration ledger.

### phase-13-docs-hardening.md

1. Copy the ledger goal verbatim into the corrected phase file.
2. Add `turnloop-core/src/main/java/io/mindspice/turnloop` to Write Targets in canonical ledger order.
3. Split into two subphases under a unified `## Implementation Steps` heading, each mapping to one sequential implementation agent:
   - **Subphase A**: Javadoc on every public SPI method and public type; `mvn javadoc:jar` zero warnings.
   - **Subphase B**: Markdown documentation, JaCoCo configuration and coverage enforcement, hardening scans, PMD dead-reference check, root `mvn clean verify`.
4. Move PMD dead-code scan from Out of Scope into implementation steps and exit criteria.
5. Enumerate all 10 documentation file paths in the implementation step.
6. Elevate the coverage-gap resolution into an explicit implementation step with a hard gate.
7. Clarify JaCoCo plugin placement (root `pom.xml` `<pluginManagement>`).

### phase-14-magenta-reintegration.md

1. **Fix the Goal** (mandatory): Replace the phase Goal with the exact ledger goal text.
2. **Split into three subphases** (mandatory):
   - **A**: Add framework dependencies, delete all extracted code, update `application.yml` — exit at compile pass
   - **B**: Create five SPI implementations, update `AiConfig` and `AiUserConfigConfiguration`, adapt surviving Magenta code — exit at compile + surviving tests pass
   - **C**: Adapt surviving tests, run full verification suite (class-absence grep, import-absence grep, context startup, `TurnLoop` bean check) — exit at all criteria met
3. **Add explicit deletion inventories** with class-named file lists for both source and test deletions.
4. **Add SPI implementation specifications** referencing the behavioral source in current Magenta code.
5. **Add configuration migration table** mapping Magenta config keys to `turnloop:` namespace keys.
6. **Add framework-type-to-Magenta-type replacement table** so the implementer knows what import to use for each deleted class.

## Follow-ups

### decomposition.md

1. Phase 02 deletes `playwright.config.js` and `test-results/` — confirm these paths exist before deletion to avoid noisy errors.
2. Phase 09 moves surviving Magenta code into framework packages. The exact mapping list (ChatModelRouter → ModelRouter, etc.) should be finalized from the handoff Appendix A during implementation.
3. The handoff's open question #5 (exact Phase 1 test preservation scope) remains unresolved. The phase 02 implementation agent must audit `src/test/` before deleting web test packages.
4. Virtual-thread safety audit of the target Spring AI HTTP client is required during phase 09 before committing the `TurnExecutor` virtual-thread implementation.

### concepts.md

1. **Target Spring AI version decision.** Before Phase 06 (core types), the project must select and document the target Spring AI version(s). The virtual-thread audit in Phase 09 depends on this.

2. **Artifact name confirmation.** The user must approve `io.mindspice.turnloop` / `turnloop-core` (or select `io.mindspice.loom` / `mindspice-loom`). This is a search-and-replace cost that grows with each phase; decide before Phase 06.

3. **Model config file format.** Decide whether `turnloop-models.json` classpath auto-discovery is supported alongside YAML and programmatic config. This affects `TurnLoopBuilder` and auto-configuration in Phase 11.

4. **JPMS module declaration.** Confirm `Automatic-Module-Name: io.mindspice.turnloop` in the framework MANIFEST. Add to turnloop-core POM in Phase 01 if confirmed.

5. **Test preservation scope.** Before Phase 02 begins, produce and review the exact file list of Magenta tests to preserve vs. delete.

6. **MarkdownRenderer placement revisit.** If optional dependency conflicts arise during Phase 09, revisit whether `MarkdownRenderer` should move to a separate module.

7. **Audit retention strategy.** Decide before Phase 08 whether the framework includes a configurable retention cleaner or delegates to the embedding app.

8. **Phase gate artifact inventories.** During Phase 06 gate validation, cross-reference the handoff Appendix C inventories to ensure every listed artifact is present.

### orchestration.md

1. User must resolve the 8 Open Questions from the handoff, particularly: final artifact name, target Spring AI version, and test preservation file list.
2. After Phase 01, verify `mvn clean verify` passes and Maven Enforcer bans are functional.
3. After Phase 05, verify zero references from retained code to deleted packages via grep.
4. After Phase 09, verify `turnloop-core` compiles independently (no dependency on Magenta `src/main/java`).
5. After Phase 12, run JaCoCo coverage check and verify >80% line coverage on `io.mindspice.turnloop`.
6. After Phase 13, run all hardening scans: zero `org.springframework.ai` in public API, zero `ThreadLocal` in framework, zero dead references, Javadoc zero warnings.
7. After Phase 14, verify Magenta context starts and existing non-web tests pass consuming the framework.
8. After all phases PASS, run the 25 final validation criteria from concepts.md as a comprehensive cross-cutting check.

### phase-01-scaffold-baseline.md

- **Phase 12 (tests)**: Audit the `bannedDependencies` rule coordinates against actual test dependencies added to `turnloop-core`. Replace `spring-boot-starter-webmvc-test` with the correct artifact ID if it differs.
- **General**: If JPMS `Automatic-Module-Name` is deferred past Phase 01, track it as an explicit follow-up to add before Phase 13 (docs/hardening) where the MANIFEST entry is verified.

### phase-02-delete-web-ui.md

- **Phase 05**: When `ChatPlanState.java` is deleted, the 5 surviving reference sites in `ChatService`, `ChatHistory`, `ChatResponse`, `PlanService`, `TerminalTurnRepair` must be addressed. The corrected Phase 02 preserves these references intact.
- **Phase 07**: The intermediate `TurnEvent` (renamed `ChatStreamEvent` with fields stripped) is replaced from scratch by the framework's sealed `TurnEvent` hierarchy per the handoff Appendix A. The Phase 02 intermediate state only needs to compile and pass tests; it does not need to match the final framework contract.
- **Final validation (Phase 13)**: The grep for `org.springframework.ai` in public API types does not apply to Phase 02's renamed `TurnEvent` since `TurnEvent` is still in Magenta's namespace (`io.mindspice.magenta2`), not in the framework namespace. This is expected.

### phase-03-create-spi-interfaces.md

- Phase 04 must update `ChatService` to inject the four SPIs as constructor dependencies, replacing direct references to the three adapted classes.
- Phase 07 must replace all four temporary SPI files with framework-native versions under `io.mindspice.turnloop.spi`.
- The `PromptContextAssembler` will be deleted in Phase 05 as part of the app-shell deletion since it depends on `PlanService`, `TaskService`, and other orchestration types.

### phase-04-remove-chatservice-deps.md

- After Phase 05 (delete-app-shell), verify zero references from ChatService to deleted packages remain. This is Phase 05 validation, not Phase 04.
- The `PlanMode` enum and `ChatPlanState` class are still on disk after Phase 04 (deleted in Phase 05). ChatService must not import them.
- `TurnAuditWriter` currently depends on `AuditService` and `AuditRepository`. Phase 03 should have cleaned this; if not, Phase 04 will need to adapt the constructor call or the TurnAuditWriter itself.

### phase-05-delete-app-shell.md

1. **Phase 04 must fully excise `TrustedContextPolicyAssembler`** from `ChatService.java`. If Phase 04 review didn't catch the line-2500 reference, Phase 04 needs a corrective amendment before Phase 05 executes.

2. **`ChatSessionMetadataRepository` survival scope is unclear.** The repository's `ProjectChatSession` inner record and `createProjectChatSession`/`findBrowserConversationIds`/`findAgentConversationIds` methods are app-shell specific. The Magenta re-integration (Phase 14) may re-add equivalent functionality. The Phase 05 adaptation removes the deleted-type references without redesigning the repository; Phase 14 owns the final shape.

3. **`AuditService` has an `AgentJobService` dependency** that becomes unresolvable after deletion. If `AuditService` survives the extraction (it is in the `ai.chat.service` package), the `@Autowired(required = false) AgentJobService` field and import must be removed. Phase 05 should handle this; Phase 14 may re-add title-job orchestration.

4. **`ChatToolRegistryTest` and sibling test survival** in `src/test/java/io/mindspice/magenta2/ai/chat/tool/` should be verified before Phase 05 executes. If these tests depend on deleted tool sub-packages, they may need adaptation beyond simple import cleanup.

### phase-06-core-types.md

1. **Orchestration ledger review**: The ledger targets omit TurnPhase, ToolCallback, and ToolDefinition. If the user prefers these to be ledger-tracked, re-run the orchestration review to add them to the phase-06 targets entry.
2. **Phase 07 awareness**: The Phase 07 implementer should be aware that TurnPhase, ToolCallback, and ToolDefinition already exist in `turnloop-core` and should verify signature compatibility rather than creating them from scratch.
3. **POM review at Phase 08**: The `spring-boot-starter-jdbc` and `sqlite-jdbc` dependencies added in this phase are not needed until Phase 08. If the Phase 01 scaffold POM already includes them, this step becomes a no-op. The Phase 08 implementer must verify the POM state.

### phase-07-events-spis.md

- **Phase 06 correction**: Remove `AnswerResult` and `QuestionPendingContext` from phase 06's scope (or remove them from the phase 07 ledger targets). These types are tightly coupled to `InteractionHandler` and logically belong in phase 07. This requires a separate orchestration-review pass since changing the ledger affects the metadata contract.
- **ThinkingResult/AssistantMessageContent**: Decide whether these are standalone core types (phase 06) or nested inside `ThinkingExtractor.java` (phase 07). Current handoff defines them as top-level records inside `ThinkingExtractor`'s section; the ledger does not assign them separate file paths, confirming they should be nested. If phase 06's implementer misinterprets the handoff and creates them as standalone files, they should be deleted and recreated as nested types in phase 07.

### phase-08-internal-persistence.md

- None. After the corrected phase is accepted, implementation proceeds with the sequential subphase A → B → phase-level validation pipeline.

### phase-09-engine-utilities.md

- The target Spring AI version audit (Open Question #6) must be resolved before Phase 12 tests run against the real Ollama integration. The Phase 09 virtual-thread audit produces findings but does not block compilation or unit testing.
- The `MarkdownRenderer` module placement (Open Question #7) is locked by this phase — if dependency conflicts arise, they manifest here and must be escalated.
- Audit table retention (Open Question #8) is out of scope for Phase 09; the `SqliteAuditWriter` was created in Phase 08 without a retention cleaner.
- The `ToolResultSummarizer` default from Phase 07 may need refinement after Subphase D merges `ToolTranscriptService` logic — log a changelog note if the default behavior changes materially.

### phase-10-facade.md

- Phase 12 tests must exercise every close() step independently (cancel pending questions, drain timeout, force-release).
- Phase 12 integration tests must verify that TurnLoopBuilder.build() with only defaults produces a working instance.
- If the handoff Appendix A TurnLoop interface changes before Phase 10 begins, update the subphase A implementation steps to match.
- The `TurnLoopMetrics` SPI is wired in DefaultTurnLoop but its Micrometer default lives in autoconfigure (Phase 11). Subphase B should wire the SPI interface, not the autoconfigure implementation.

### phase-11-autoconfigure.md

- Phase 12 (tests) owns the autoconfigure smoke test (`@SpringBootTest` verifying `TurnLoop` bean exists and is functional), `TurnLoopHealthIndicator` healthy/unhealthy tests, `StreamLifecycle` SSE integration test, and `TurnLoopProperties` validation tests. The corrected phase moves these test expectations out of its own validation section and into an explicit "validated in Phase 12" reference.
- Phase 12 must verify that `TurnLoopProperties` validation actually throws `ConfigurationException` on missing default model and invalid URLs, not just that the class exists.
- Phase 13 (docs/hardening) owns the `README.md` SSE controller example — not this phase.

### phase-12-tests.md

- Phase 13 must verify JaCoCo >80% line coverage across the test suite built in Phase 12. Any gaps found then should be logged as bugs, not silently patched.
- If Ollama-dependent integration tests prove unreliable in CI, consider a dedicated `TurnLoopIntegrationTest` profile gating them behind `-Dollama.available=true`.
- The `DefaultTurnEngine` state-machine test is the highest-risk single test class; the subphase D agent should prioritize it before `DefaultPromptAssembler` or `ContextManager`.

### phase-13-docs-hardening.md

- After Phase 14, run a cross-phase documentation consistency check: verify README SSE example still compiles against the re-integrated Magenta's actual controller implementations.
- If JaCoCo coverage enforcement reveals systemic gaps in error-path or edge-case coverage, log individual bugs in `.internal-dev/bugs/` rather than expanding Phase 13 scope.

### phase-14-magenta-reintegration.md

- After Phase 14 passes, the full 14-phase integration validation (orchestration.md Final Integration) must run against the complete system.
- The `AiConfig` record's `agents` and `defaultAgent` fields are deleted in Phase 05; if any residual references survive in `AgentConfig.java` or other config types, they must be cleaned up in this phase.
- The `ExternalAiConfigLoader` JSON format may need a parallel `turnloop:` mapping so existing config files continue to work; this is a deferred embedding-app concern and out of scope for this phase, but should be noted in the changelog.
