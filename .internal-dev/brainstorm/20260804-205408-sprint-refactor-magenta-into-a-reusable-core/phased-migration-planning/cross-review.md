## Prompt

Synthesize four worker reports on refactoring Magenta (`/home/dhickel/Code/Java/Magenta`, branch `core`) into a reusable core LLM turn-loop framework. Compare ideas, conflicts, omissions, feasibility, and trade-offs across the Phased Migration Strategist, Architecture Module Boundaries, Internal Abstractions Design, and Risk/Constraint Validation reports. Identify the strongest proposals from each, resolve conflicts, fill gaps, and recommend a unified path forward.

## Source

Four reports submitted through the sprint workflow engine:

1. **Phased Migration Strategist** — Ground-up inventory of all ~260 Java files, 6-phase plan ordered by dependency removal, detailed ChatService collaborator map, validation gates at each phase boundary.

2. **Architecture Module Boundaries** — Package-by-package classification of all production files (keep/delete/adapt), proposed artifact `turnloop-core` / `io.mindspice.turnloop`, 10-phase plan, TurnOrchestrator/Memory/Queue interfaces.

3. **Internal Abstractions Design** — Detailed API designs for tool framework, memory/session, turn-loop, question-user loop, event streaming, and queueing. Proposed artifact `mindspice-loom` / `io.mindspice.loom`, multi-module split, LoomCore facade with hybrid API.

4. **Risk/Constraint Validation** — Security removal impact (none — no Spring Security exists), ChatService transitive dependency surgery map (14 keep, 13 delete, 6 generalize, 2 optional), 5 validation phases, thread safety analysis, dead code risks, production quality checklist.

All reports inspected the same code: `ChatService.java` (3009 lines), `ChatModelRouter`, `ContextManagementAdvisor`, `ToolLoopGuard`, `ChatToolRegistry`, `ToolTranscriptService`, `ToolArgumentCoercionModule`, `ChatPendingMessageService`, `InteractionQuestionTools`, `ConversationTurnCoordinator`, full orchestration tree (~150 files), `api/web/` (~47 files), all tool sub-packages, `pom.xml`, `application.yml`, specifications.

## Findings

### 1. Areas of Strong Agreement

All four reports converge on these points:

**A. ChatService is the critical refactoring target.** All identify the 3009-line monolith with ~30 collaborators as the single hardest piece of work. The state machine (`toolChat` with phases INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) is framework-quality and must be extracted intact. Every report reaches the same conclusion: ChatService must be split into a clean `TurnEngine` with all plan/task/agent/workspace/skill code removed.

**B. Web/UI deletion is trivially safe.** All agree `api/web/**` (~47 files) depends on core services but not vice versa. Deletion carries zero risk of cascading breakage beyond test files.

**C. No Spring Security exists to remove.** Risk Validation confirmed through grep and pom.xml inspection: `spring-boot-starter-security` is absent, `AlphaSecurityConfiguration.java` was already deleted. Phase 3 (auth deletion) is verification-only.

**D. The tool loop guard, tool registry, and tool transcript service are framework-ready.** `ToolLoopGuard`, `ChatToolRegistry`, `ToolTranscriptService`, and `ToolArgumentCoercionModule` are identified by all reports as pure framework code with minimal-to-zero app dependencies.

**E. Pending message queueing (claim/ack/release) is a core framework capability.** All reports keep `ChatPendingMessageService` + `ChatPendingMessageRepository` as essential framework components, with minor generalization (remove `ChatSessionSurface` from enqueue signature).

**F. SQLite stays as the default persistence.** All agree to keep SQLite-backed repositories with interface abstractions for pluggability.

**G. PlanService/TaskService must be entirely deleted, not adapted.** No report argues for keeping any plan or task infrastructure. The InteractionQuestionTools dependency on these services must be broken by introducing a question-user callback.

### 2. Conflicts

#### Conflict 1: Artifact Naming

| Report | Group ID | Artifact ID | Root Package |
|---|---|---|---|
| Phased Strategist | `io.mindspice` | `magenta-core` | `io.mindspice.core` |
| Architecture Boundaries | `io.mindspice` | `turnloop-core` | `io.mindspice.turnloop` |
| Internal Abstractions | `io.mindspice` | `mindspice-loom` | `io.mindspice.loom` |

**Analysis:** The Phased Strategist's `magenta-core` retains continuity with the existing project name but is generic. Architecture Boundaries' `turnloop-core` is the most descriptive of what the framework actually does. Internal Abstractions' `loom` is the most distinctive and brandable but introduces a new name with zero existing recognition. All agree on `io.mindspice` as the group ID.

**Resolution:** `turnloop-core` is the most descriptive and unambiguous. A name should communicate what the artifact does. "Loom" requires explanation; "magenta-core" suggests it's a subset of Magenta rather than a standalone framework. Recommend `io.mindspice:turnloop-core` with root package `io.mindspice.turnloop`.

#### Conflict 2: TurnMode / PlanMode Handling

| Report | Approach |
|---|---|
| Phased Strategist | Remove PlanMode entirely. Replace with `TurnDirective` — a simple string passed through to tool access policy without framework understanding. |
| Architecture Boundaries | Keep `TurnMode` enum: NORMAL, PLAN, TASK, EXECUTE_PLAN, EXECUTE_TASK. Framework ships with these modes but apps can ignore them. |
| Internal Abstractions | Keep `TurnMode` as optional on `TurnRequest` with NORMAL default. Modes gate tools and repair logic. Apps that don't need plan/execute never touch it. |

**Analysis:** The Phased Strategist's pure-string approach is the cleanest framework design — the framework should not know about plan/task/execute concepts. However, Architecture Boundaries correctly observes that the current `ToolAccessPolicy` and `TerminalTurnRepair` have mode-specific behavior that must be preserved for the question-user loop and tool gating to work. The Internal Abstractions middle ground (optional enum with NORMAL default) is practical but still bakes app concepts into the framework.

**Resolution:** The Phased Strategist's approach is correct for a pure framework, but the framework must still support differentiated tool access. The solution is to **remove TurnMode/PlanMode from the framework entirely** and replace it with a `ToolAccessPolicy` interface that the embedding app implements. The framework ships with a `DefaultToolAccessPolicy` that allows all tools. Apps that need plan/task mode gating provide their own policy implementation. The framework carries zero knowledge of plan/task/execute modes.

#### Conflict 3: Module Structure

| Report | Structure |
|---|---|
| Phased Strategist | Single artifact. All code in one module. |
| Architecture Boundaries | 4 modules: `loom-core`, `loom-transport-sse`, `loom-autoconfigure`, `loom-example` |
| Internal Abstractions | Same 4-module approach |

**Analysis:** The multi-module split is architecturally cleaner — it prevents `loom-core` from pulling in Spring Web MVC (for SSE transport) or auto-configuration metadata. The Phased Strategist's single-module approach is simpler to develop initially but forces all transport bindings into the same artifact. For a v1 framework, the single-module approach is more practical; the transport separation can be introduced in v2 when there are multiple transport bindings. However, the auto-configuration separation is standard Spring Boot practice and costs very little — the `spring-boot-autoconfigure` dependency is already `optional`.

**Resolution:** **Two modules for v1**: `turnloop-core` (framework + SQLite defaults + SSE transport utilities) and `turnloop-autoconfigure` (Spring Boot auto-configuration). The `loom-transport-sse` module is deferred until a second transport binding (WebSocket) justifies the split. The `loom-example` module is deferred — embed a `main()` example in `src/test` instead. This keeps the build simple while preserving the auto-configuration separation.

#### Conflict 4: MarkdownRenderer Fate

| Report | Approach |
|---|---|
| Phased Strategist | Keep as framework component |
| Architecture Boundaries | Keep as pluggable renderer |
| Internal Abstractions | Rendering is app responsibility; framework returns raw events |
| Risk Validation | Markdown rendering "deeply coupled" — risk MEDIUM |

**Analysis:** `ChatMarkdownRenderer` is used by `ChatService.renderAssistantMessage()` to produce `ChatMessage.renderedHtml`. If the framework returns `TurnEvent` with only raw text (Internal Abstractions' approach), markdown rendering is entirely the embedding app's concern. If the framework returns rendered HTML alongside raw text (Phased Strategist's approach), the renderer must stay. The Risk Validation correctly flags this as a medium-risk decision because the current streaming contract depends on it.

**Resolution:** The framework should **not** own HTML rendering. `TurnEvent` carries raw text. The framework ships a `MarkdownRenderer` utility class in a `util` package that embedding apps can optionally use. The `TurnMessage` record carries `text` (raw) and an optional `renderedText` field that the embedding app can populate using whatever renderer it chooses. This removes `commonmark`, `commonmark-ext-gfm-tables`, `owasp-java-html-sanitizer`, and `jsoup` from required dependencies — they become optional.

#### Conflict 5: Question-User Loop Contract

| Report | Contract Design |
|---|---|
| Phased Strategist | `InteractionHandler` interface: `onQuestionsAsked()`, `onAnswerReceived()` |
| Architecture Boundaries | `UserQuestionTool` + `QuestionUserContract`: framework tool calls callback, callback handles persistence |
| Internal Abstractions | `UserQuestionHandler` with `onQuestionsAsked()` + `awaitAnswer()` returning `CompletableFuture<String>` |

**Analysis:** All three propose a callback/handler pattern to decouple from PlanService/TaskService. The Internal Abstractions' `CompletableFuture` approach is elegant for async workflows but ties the framework to Java's concurrency model. The Architecture Boundaries' callback approach is simplest — the tool calls a method, the handler does whatever it wants (block, queue, return immediately), and the framework just records that questions were asked. The Phased Strategist's approach has the framework own the question lifecycle (suspend, await answer, resume).

**Resolution:** The framework should own the question lifecycle. The turn loop needs to know when a question is pending so it can suspend and emit `TurnEvent.UserQuestionPending`. The `UserQuestionHandler` receives questions, persists them (via the embedding app's mechanism), and the framework's `TurnEngine` suspends the turn. When `LoomCore.answer(sessionId, answer)` is called, the turn resumes. The handler is a simple callback; the framework owns suspend/resume. This matches the Phased Strategist's design.

#### Conflict 6: In-Memory vs SQLite Default

| Report | Default |
|---|---|
| Phased Strategist | SQLite (keep existing) |
| Architecture Boundaries | In-memory for `LoomCore.builder()`, SQLite for auto-configuration |
| Internal Abstractions | In-memory default, SQLite as module |

**Analysis:** The current codebase is SQLite-only. Adding an in-memory implementation is new work. However, for a framework, an in-memory default reduces the barrier to entry — embedding apps don't need a DataSource to get started. The Architecture Boundaries' hybrid approach (in-memory for programmatic builder, SQLite for auto-configuration) is pragmatic.

**Resolution:** **SQLite as the only shipped default for v1.** Adding in-memory support is scope creep. The framework ships `SqliteConversationMemory`, `SqliteSessionStore`, and `SqliteMessageQueueStore`. The interfaces (`ConversationMemory`, `SessionStore`, `MessageQueueStore`) are defined so embedding apps can provide their own implementations. An in-memory implementation can be added in a follow-up. This avoids building, testing, and documenting a second persistence backend during the extraction.

### 3. Omissions Across All Reports

#### Omission 1: Schema Migration Strategy

**None of the reports address the 20 ordered migration files** (`src/main/resources/db/migrations/001-*.sql` through `020-*.sql`) managed by `SchemaMigrationRunner.java`. After Phase 2 deletion, only 4 tables remain framework-relevant: `ai_chat_memory`, `ai_chat_session_metadata`, `ai_chat_pending_messages`, and `audit_log`. The other ~16 tables and all their migration steps are dead.

**Recommendation:** Extract the framework-owned DDL into a single `schema.sql` in the framework resources. The framework's `SqliteSchemaManager` creates these 4 tables idempotently (matching the current `ensureMetadataColumn` / `ensureSchema` pattern used by individual repositories). The ordered `SchemaMigrationRunner` is deleted entirely. The migration files are deleted. This simplifies the framework's startup to a single self-migrating schema check.

#### Omission 2: ChatSessionMetadataRepository Column Surgery

All reports say "remove app-specific columns" from `ChatSessionMetadataRepository` but none specify the migration path. Current columns to remove: `origin`, `surface`, `agent_id`, `project_id`, `member_agent_id`, `materialization_key`, `materialization_generation`, `owner_binding_key`, `active_task_run_id`, `planning_model`. Current columns to keep: `conversation_id`, `model`, `title`, `favorite`, `archived`, `updated_at`.

**Recommendation:** Since the framework gets a fresh `schema.sql`, the new `session_metadata` table is created with only framework columns. If an embedding app migrates from Magenta, it runs its own data migration. The framework does not handle Magenta schema upgrade — that's an application concern.

#### Omission 3: `MagentaWorkExecutor` vs Virtual Threads

Risk Validation raises this: Java 25 supports virtual threads. `MagentaWorkExecutor` wraps a `ThreadPoolTaskExecutor`. The framework could use `Executors.newVirtualThreadPerTaskExecutor()` instead. No other report addresses the execution strategy.

**Recommendation:** Replace `MagentaWorkExecutor` with a simpler `TurnExecutor` that defaults to virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`. This eliminates pool sizing configuration, simplifies lifecycle, and is appropriate for I/O-bound model calls. Provide a `turnloop.executor.strategy=virtual|fixed` property for apps that prefer fixed thread pools.

#### Omission 4: `ChatStreamEvent` Redesign Detail

All reports agree `ChatStreamEvent` must be generalized to remove `ChatPlanState`, but only Internal Abstractions provides a complete redesigned sealed hierarchy. The Phased Strategist and Architecture Boundaries say "generalize" without specifying the new types.

**Recommendation:** Adopt Internal Abstractions' redesigned `TurnEvent` hierarchy with these modifications:
- Drop `ThinkingChunk` and `ContentChunk` separation — the current code separates thinking from visible text via metadata extraction, not separate event types. Use a single `Chunk` event with an optional `thinking` field.
- Drop `ToolCallStarted` — the current code doesn't emit tool-call-started events. Keep only `ToolCallCompleted`.
- Add `Interrupt` event (from current `ChatStreamEvent.Interrupt`).
- Add `OutputsChanged` event (from current `ChatStreamEvent.OutputsChanged`) OR drop it — it's tied to file-mutating tools. If file tools are deleted, this event has no consumer, so drop it.
- Keep `Start`, `Chunk`, `ToolActivity`, `SystemNotice`, `Interrupt`, `ContextUsage`, `Done`, `Error`.
- Every event carries `ContextUsage` where applicable (matching current behavior).

#### Omission 5: `AuditService` / `AuditRepository` Disposition

Phased Strategist says "keep as pluggable audit SPI." Architecture Boundaries says "keep; make audit repository interface optional (no-op default)." Risk Validation says "DELETE (or make optional)." Internal Abstractions doesn't address audit directly.

**Recommendation:** Keep as an optional SPI with a no-op default. The `AuditWriter` interface has methods: `recordUserMessage`, `recordAssistantMessage`, `recordToolExecution`, `recordContextUsage`, `recordError`. The framework ships `NoOpAuditWriter` as the default. Apps that want SQLite audit logs provide `SqliteAuditWriter`. This avoids pulling audit tables into the framework's required schema while preserving the capability.

#### Omission 6: `.internal-dev/` Store Migration

None of the reports address what happens to `.internal-dev/specifications/`, `.internal-dev/knowledge/`, `.internal-dev/changelogs/`, `.internal-dev/plans/`, `.internal-dev/reviews/`, and `.internal-dev/bugs/`. After the extraction, most specifications (architecture.md, services.md, service-graph.md, api.md, web.md) describe the Magenta application, not the framework.

**Recommendation:** The `.internal-dev/` store stays with the Magenta repository. The framework repository (or the `core` branch after cleanup) gets its own fresh `.internal-dev/` store with framework-specific specifications. The Magenta `.internal-dev/` history is preserved in the main branch.

#### Omission 7: `tests/` Directory (Playwright)

The project root has a `tests/` directory and `playwright.config.js` for browser-based testing. None of the reports address this.

**Recommendation:** Delete `tests/`, `playwright.config.js`, `package.json`, `package-lock.json`, `node_modules/` — all browser/Playwright testing infrastructure. The framework has no UI.

#### Omission 8: `config/` Directory

The project root has `config/ai-config.example.json`. None of the reports address what happens to the external config file.

**Recommendation:** The framework ships a default `AiConfig` with a single Ollama model pointing to `localhost:11434`. External config loading via `ExternalAiConfigLoader` is preserved for production use. An example config file ships in `src/test/resources` for integration tests. The `config/` directory at the project root is deleted — embedding apps manage their own config.

#### Omission 9: `docs/` Directory

The project has a `docs/` directory with end-user and technical documentation. None of the reports address doc migration.

**Recommendation:** Delete Magenta-specific docs (`docs/end-user/`, Magenta-specific technical docs). The framework gets fresh docs in Phase 6: README.md, getting-started.md, configuration.md, tools.md, memory.md, streaming.md, facade-api.md. The Magenta docs stay on the main branch.

#### Omission 10: ConversationTurnCoordinator's Framework Role

The Phased Strategist says "keep" but doesn't specify how it changes. Architecture Boundaries says "keep; simplifies to turn gate." Internal Abstractions doesn't address it. Risk Validation confirms it's thread-safe.

**Recommendation:** `ConversationTurnCoordinator` becomes `TurnGate` — a framework-internal component that serializes turns per conversation. It's not exposed through the facade. The facade's `stream()` and `send()` methods internally submit work through the turn gate. The implementation stays the same (deque per conversation, synchronized access). The dependency on `MagentaWorkExecutor` is replaced with the new `TurnExecutor` (virtual threads).

#### Omission 11: `application.yml` Test Resources

The project has `src/test/resources/application.yml` with test-specific configuration. None of the reports address this.

**Recommendation:** The framework's test resources include a minimal `application-test.yml` that configures an in-memory SQLite database (`jdbc:sqlite::memory:`) and a default Ollama model. The old test config is deleted with the Magenta code.

### 4. Feasibility Assessment

#### Feasible with Current Scope

- **Web layer deletion (Phase 1):** Trivially feasible. Zero risk. ~47 files deleted, pom.xml cleaned.
- **Orchestration deletion (Phase 2):** Mechanically feasible — ~230 files deleted. The ChatService refactoring within this phase is the hard part.
- **ChatService tool-loop extraction:** Feasible because the state machine is already well-structured. The 3009 lines include ~1200 lines of state machine, ~800 lines of plan/task methods to delete, ~600 lines of session management, and ~400 lines of rendering/utilities. The actual extraction is surgical: delete plan/task methods, replace plan/task-aware collaborators with generalized interfaces, keep the state machine intact.
- **Tool framework generalization:** Feasible. `ChatToolRegistry` is already clean. `ToolTranscriptService` needs only the summarizer switch extracted to an interface. `ToolAccessPolicy` needs mode constants replaced with configurable blocklists.
- **Memory abstraction:** Feasible. `ChatMemoryRepository`, `ChatSessionMetadataRepository`, `ChatPendingMessageRepository` are already well-isolated. Defining interfaces and renaming is mechanical.
- **Question-user generalization:** Feasible. `InteractionQuestionTools` is 100 lines. Two `PlanService`/`TaskService` calls to replace with a callback. Then it's framework-ready.

#### At Feasibility Boundary

- **TurnEvent redesign:** The current `ChatStreamEvent` sealed hierarchy has 9 variants carrying `ChatPlanState` and `renderedHtml`. Removing these requires updating every emission site in `ChatService.stream()` and the blocking path. This is ~20 emission sites. Feasible but tedious.
- **MarkdownRenderer removal from framework core:** `ChatService.renderAssistantMessage()` constructs `ChatMessage.renderedHtml` via `ChatMarkdownRenderer`. Removing this requires deciding who produces rendered HTML. The cleanest approach: the framework produces `TurnMessage.text` (raw) and the embedding app renders it. But this changes the contract for every existing consumer. The compromise: `TurnMessage` has `text` (raw) + `renderedText` (optional). The framework ships a `MarkdownRenderer` utility. The `CoreBuilder` accepts an optional `Function<String, String> renderer`. If provided, `renderedText` is populated; otherwise null.

#### Potentially Infeasible Without User Decisions

- **New artifact naming** requires user approval.
- **Single vs multi-module** depends on user's distribution preferences.
- **Whether to keep any built-in example tools** requires user input. The directive says "small set of basic tools as examples" — but which ones?

### 5. Strengths of Each Report

| Report | Strongest Contribution |
|---|---|
| Phased Strategist | **Dependency removal ordering and validation gates.** The 6-phase sequence with concrete per-phase file inventories, dependency edges, and what-breaks analysis is the most actionable implementation plan. The ChatService collaborator map (30 collaborators with keep/delete/adapt decisions) is the definitive reference for Phase 2. |
| Architecture Boundaries | **Package-level classification and ToolAccessPolicy design.** The keep/delete/adapt classification of every file in every package is the most exhaustive inventory. The tool framework API design (ToolRegistry, ToolAccessPolicy with mode-based built-ins, ToolTranscriptStrategy) is the most complete of the three. |
| Internal Abstractions | **API contract designs and TurnEvent hierarchy.** The detailed interface definitions for ConversationMemory, SessionStore, ContextManager, CompactionStrategy, MessageQueue, TurnOrchestrator, ModelProvider, and the redesigned TurnEvent sealed hierarchy are the most thorough specification of what the framework's public surface should look like. |
| Risk Validation | **Production quality gaps and thread-safety analysis.** The identification of missing close()/shutdown, stream lock race conditions, error-propagation leaks, dead code risks, and test coverage gaps is essential hardening work that the other reports gloss over. The virtual-threads recommendation for Java 25 is a forward-looking insight. |

### 6. Unified Recommendations

#### Naming and Structure

| Decision | Recommendation | Source |
|---|---|---|
| Group ID | `io.mindspice` | All agree |
| Artifact ID | `turnloop-core` | Architecture Boundaries (most descriptive) |
| Root package | `io.mindspice.turnloop` | Architecture Boundaries |
| Modules | Two: `turnloop-core` + `turnloop-autoconfigure` | Hybrid (v1 pragmatism) |
| Facade class | `TurnLoop` (interface) + `DefaultTurnLoop` (impl) + `TurnLoopBuilder` | Hybrid of Internal Abstractions + Architecture Boundaries |

#### Phase Plan (Unified)

| Phase | Description | Gates |
|---|---|---|
| **Phase 0** | Baseline validation. Run all tests, verify context starts. | Full build passes |
| **Phase 1** | Delete `api/web/**`, static resources, web dependencies from pom, Playwright tests, `tests/` directory. Generalize `ChatStreamEvent` → `TurnEvent`. Move SSE utilities to framework package. | Compile, non-web tests pass, context starts |
| **Phase 2** | Sever `ChatService` from PlanService, TaskService, skills, workspace, orchestration, measurement, agent context. Use `@Autowired(required = false)` stepping-stone pattern. Generalize `InteractionQuestionTools`, `PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`. | Compile, ChatService tests pass with mock collaborators |
| **Phase 3** | Delete `ai/orchestration/**`, `ai/chat/plan/**`, `ai/chat/task/**`, `ai/chat/project/**`, `ai/chat/system/**`, `ai/skills/**`, `ai/agent/**`, all tool sub-packages except core tool framework. Simplify `AiConfig`/`AgentConfig`. Delete 16 dead migration files. | Compile, remaining tests pass |
| **Phase 4** | Extract into framework packages under `io.mindspice.turnloop`. Create `TurnEngine`, `ModelRouter`, `ToolRegistry`, `ConversationMemory`, `SessionStore`, `ContextManager`, `MessageQueue`, `TurnEvent` hierarchy. Create fresh `schema.sql`. Replace `MagentaWorkExecutor` with virtual-thread `TurnExecutor`. | Compile, unit tests pass, context starts with framework beans only |
| **Phase 5** | Build `TurnLoop` facade + `TurnLoopBuilder`. Wire all internal components behind facade. Add `close()` lifecycle with in-flight turn draining, stream lock release, pending claim release. Create `turnloop-autoconfigure` module. | Facade integration tests pass, lifecycle tests pass |
| **Phase 6** | Comprehensive tests (turn loop state machine paths, tool execution, question-user round-trip, queue drain, concurrency, error handling). Configuration cleanup (`application.yml` → `turnloop.*` properties). Documentation (README, getting-started, configuration reference, tool guide, API docs). Delete remaining Magenta dead code and docs. | Full build passes, >80% coverage on framework packages, context smoke test, docs review |

#### Key Design Decisions (Resolved Conflicts)

| Decision | Resolution |
|---|---|
| TurnMode/PlanMode | **Remove entirely.** Replace with `ToolAccessPolicy` interface. Framework ships `DefaultToolAccessPolicy` (allow-all). Apps with mode gating implement custom policy. |
| MarkdownRenderer | **Make optional.** `TurnMessage` has `text` (raw) + optional `renderedText`. Framework ships `MarkdownRenderer` utility. `TurnLoopBuilder` accepts optional `Function<String,String> renderer`. `commonmark` becomes optional dependency. |
| Memory default | **SQLite only for v1.** Interfaces defined for pluggability. In-memory implementation deferred. |
| Module structure | **Two modules for v1:** `turnloop-core` + `turnloop-autoconfigure`. SSE transport stays in core. |
| Question-user contract | **Framework owns lifecycle.** `UserQuestionHandler` callback receives questions; framework suspends turn. `TurnLoop.answer(sessionId, answer)` resumes. |
| Event hierarchy | **Adopt Internal Abstractions' design** with simplifications: single `Chunk` event with optional thinking field, drop `ToolCallStarted`, keep `Interrupt`. |
| Audit | **Optional SPI with no-op default.** `AuditWriter` interface, `NoOpAuditWriter` default, `SqliteAuditWriter` optional. |
| Executor | **Virtual threads default.** `TurnExecutor` uses `Executors.newVirtualThreadPerTaskExecutor()`. Configurable fallback to fixed pool. |
| Schema migration | **Single `schema.sql`** with 4 framework tables. Ordered `SchemaMigrationRunner` deleted. |

## Options

### Option A: Unified Plan with Sequential Phases (Recommended)

Execute the unified 7-phase plan (0-6) in strict sequence. Each phase gates on build + tests + context startup. The ChatService refactoring (Phase 2) is the only serial bottleneck. All other phases have no intra-phase dependencies that prevent parallel work by subagents.

**Pros:** Cleanest path. Every phase produces a compilable, testable artifact. Risk is contained within each phase.

**Cons:** Phase 2 is the long pole. If ChatService refactoring hits unexpected complexity, the whole plan stalls.

### Option B: Extract-First Approach

Instead of deleting web/orchestration first, extract the turn loop into the new framework package immediately. Keep old code compiling alongside framework code. Delete old code only after the framework is proven.

**Pros:** Framework exists sooner. Old code acts as a safety net. Can run comparison tests (old ChatService vs new TurnEngine with same inputs).

**Cons:** Two parallel codebases to maintain. Risk of copying bugs instead of extracting clean abstractions. New framework code references old app code during transition.

### Option C: Scaffold-and-Port

Create the framework module structure and interfaces first (design phase), then port code from Magenta into the framework package by package, deleting the old code as each piece is ported and tested.

**Pros:** Cleanest framework design. Interfaces designed before implementation. Zero dead-code intermediate states.

**Cons:** Most upfront design work. Risk of over-engineering interfaces before porting reveals real constraints.

**Recommendation: Option A.** The sequential deletion-first approach is the most practical because it forces the extractor to understand exactly what the framework needs by removing everything it doesn't need. The Phase 2 stepping-stone pattern (`@Autowired(required = false)`) provides a safety net during ChatService surgery.

## Trade-offs

| Trade-off | Decision | Rationale |
|---|---|---|
| Two modules vs four | Two for v1 | Reduces build complexity. Multi-module split for transport adds value only when there are multiple transports. |
| SQLite only vs in-memory too | SQLite only for v1 | Avoids scope creep. Interfaces are defined; in-memory implementation is straightforward follow-up work. |
| Virtual threads vs thread pool | Virtual threads default | Java 25 is the target. Virtual threads simplify lifecycle and eliminate pool sizing. Fixed pool option for apps that need it. |
| Single artifact ID naming | `turnloop-core` | Most descriptive. "Loom" is clever but requires explanation. "magenta-core" implies Magenta dependency. |
| Markdown in framework vs app | App responsibility, framework utility | Framework should not dictate rendering format. But providing a utility reduces friction for the common case. |
| Audit as required vs optional | Optional SPI with no-op | Framework core should not require audit persistence. Apps that need it provide their own. |
| TurnMode removal vs optional enum | Remove entirely | Framework should not carry plan/task concepts. ToolAccessPolicy is the correct extension point. |

## Open Questions

1. **Should `@EnableScheduling` be removed from the framework's auto-configuration?** Yes — the framework has no scheduled jobs. Embedding apps add their own scheduling if needed.

2. **What example tools should ship with the framework?** Recommend three: `EchoTool` (demonstrates basic `@Tool` usage), `DateTimeTool` (demonstrates parameterized tool), and the built-in `ask_user` (demonstrates question-user loop). These are documentation-quality examples, not production tools.

3. **Should the framework auto-discover `@Tool` beans from the embedding app's context, or require explicit registration?** Both. Auto-discovery via `ToolCallback` and `ToolCallbackProvider` beans (current behavior) for Spring Boot convenience. Explicit `TurnLoopBuilder.registerTool(Object)` for programmatic use.

4. **Should `ContextManagementAdvisor` stay as a Spring AI Advisor or be called directly?** Called directly. The framework's `TurnEngine` invokes `preparePrompt()` and `prepareToolLoopPrompt()` directly, not through the Advisor chain. The Advisor interface implementation is dropped. This simplifies the contract.

5. **What happens to `ChatSessionMetadataRepository` favorite/archived columns?** Keep them. Session management (favorite, archived, title, model) is genuinely useful for any chat application. These are framework-worthy.

6. **What about `ChatFileService`?** Delete entirely. Conversation-scoped file storage is an app feature. Framework has no filesystem operations.

7. **Should `ChatSessionOrigin` be kept as a string field on sessions?** Yes. Replace the enum with a `String origin` field. The framework defines constants (`TURNLOOP_CHAT`) but embedding apps can use any string. This preserves the origin-tracking concept without the enum.

8. **What is the artifact's Java module name (JPMS)?** `io.mindspice.turnloop`. Add `Automatic-Module-Name` to the MANIFEST.

## Recommended Next Step

1. **Confirm naming:** `io.mindspice:turnloop-core` with root package `io.mindspice.turnloop`.

2. **Begin Phase 0 immediately:** Run full build baseline. Fix any pre-existing test failures before any deletions.

3. **Execute Phase 1:** Delete `api/web/**`, web dependencies, Playwright infrastructure. This is the lowest-risk phase and produces immediate clarity on what remains.

4. **Prepare for Phase 2:** Before touching ChatService, write focused unit tests for the turn-loop state machine paths that must survive. These tests become the safety net during refactoring.

5. **Phase 2 execution strategy:** Use the `@Autowired(required = false)` stepping-stone pattern. Remove one collaborator at a time, verify compilation and tests, commit. Do NOT attempt to remove all 13 collaborators in one change.

6. **After Phase 3, the framework shape is visible** — Phases 4-6 are package reorganization, facade construction, and hardening. These can be parallelized where safe (tool framework extraction is independent of memory abstraction extraction).
