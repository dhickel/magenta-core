# Cross-Report Review: Internal Domain Abstraction Designer's Synthesis

## Prompt

Review the three other worker reports — Architecture Module Boundaries, Risk Constraint Validation, and Phased Migration Planning — against the Internal Domain Abstraction Designer's findings. Compare and reconcile ideas, identify conflicts, fill omissions, and produce a unified recommendation covering all five design areas plus naming, phasing, and quality gates. The result must preserve feature completeness and production quality across the full scope.

## Source

Four reports were produced by parallel workers analyzing the same codebase (`/home/dhickel/Code/Java/Magenta`, branch `core`):

1. **Internal Domain Abstraction Designer** (this worker) — Focused on public API contracts: tool framework, memory/session, turn-loop mechanics, question-user/queueing, event streaming. Proposed `io.mindspice.loom` naming, `LoomCore` hybrid facade, transport-agnostic `TurnEvent` sealed hierarchy, 4-module split (core, transport-sse, autoconfigure, example), 10-phase plan extracting model types first.

2. **Architecture Module Boundaries** — Focused on file-level classification of all 387 Java sources, pom.xml dependency audit, and a new `io.mindspice.turnloop` package layout. Proposed `TurnLoop` monolithic facade, `turnloop-core` artifact, detailed per-file keep/delete dispositions.

3. **Risk Constraint Validation** — Focused on security removal impact (none — no Spring Security), `ChatService` transitive dependency surgery (30 collaborators, 13 to delete, 6 to generalize), configuration surface pruning, validation gates, thread safety audit, lifecycle gaps, test coverage gaps.

4. **Phased Migration Planning** — Focused on execution ordering with dependency analysis. Proposed 6-phase plan: Delete Web → Delete Orchestration → Auth Cleanup → Extract/Generalize → Facade → Tests/Docs. Detailed method-level `ChatService` refactoring plan and concrete validation gates.

## Findings

### Area-by-Area Consolidation

#### 1. Naming and Package Identity

Three proposals exist:

| Report | Artifact ID | Root Package | Facade Name |
|---|---|---|---|
| Abstraction Designer | `mindspice-loom` | `io.mindspice.loom` | `LoomCore` |
| Architecture | `turnloop-core` | `io.mindspice.turnloop` | `TurnLoop` |
| Phased Migration | `magenta-core` | `io.mindspice.core` | `Core` |

**Conflict:** Three different names. None is wrong; each has different trade-offs:
- `loom`: Distinctive, memorable, short. But a new name with zero provenance.
- `turnloop`: Accurate description. But "TurnLoop" as a class name is awkward as a facade (it's a loop, not a service).
- `magenta-core` / `io.mindspice.core`: Retains Magenta provenance. But "core" is generic and `io.mindspice.core` could collide with other projects.

**Resolution recommendation:** Use `io.mindspice.loom` as the package root with artifact `mindspice-loom`. The facade class should be `Loom` (not `LoomCore` — the package provides context). Rationale:
- A standalone framework deserves a distinctive name. `io.mindspice.core` is too generic and invites namespace collision.
- `turnloop` is descriptive but "TurnLoop" reads as a component, not a facade. "Loom" communicates "weaving" turns together.
- The name is searchable, short, and has no existing Java ecosystem collisions.
- The existing Magenta `io.mindspice` group ID is preserved.

**Unresolved:** User must approve the name. Until then, all reports should use a consistent placeholder.

#### 2. Tool Framework

All four reports converge on the same core design:
- `ToolRegistry` interface for registration/discovery (from `ChatToolRegistry`)
- `ToolAccessPolicy` interface for mode-gating (from `ToolAccessPolicy`)
- `ToolLoopGuard` kept as-is (pure framework, zero app deps)
- `ToolArgumentCoercionModule` kept as-is (Jackson module)

**Points of agreement:**
- Keep Spring AI `ToolCallback` as the native tool type — no wrapping
- The tool-name-specific summarizer switch in `ToolTranscriptService` must become pluggable

**Points of divergence:**
- Abstraction Designer proposes `ToolTranscriptStrategy` as a full replacement interface. Architecture proposes `ToolResultSummarizer` SPI. These are the same concept at different granularity. The Abstraction Designer's version is richer (it handles full transcript message creation + compaction + display rendering). **Recommend: Adopt the richer `ToolTranscriptStrategy` interface** since `ToolTranscriptService` owns all three concerns today.

- Architecture proposes `QuestionUserContract` as a simple callback. Abstraction Designer proposes `UserQuestionTool` + `UserQuestionHandler` as a split: the tool side (registered as a `ToolCallback`) and the handler side (embedding app receives questions). **Recommend: Adopt the split design.** The tool side is a framework-owned `ToolCallback` that invokes the handler. This cleanly separates framework plumbing from app behavior.

- **Omission across all reports:** No report specifies how the framework's built-in `ask_user` tool gets auto-registered. The Abstraction Designer says "the framework registers this internally" but doesn't detail the mechanism. **Resolution:** The `ToolRegistry` should have a `registerFrameworkTool(ToolCallback)` method that marks tools as framework-owned (visible only internally, not to embedding apps). The `LoomAutoConfiguration` registers `ask_user` via this path.

- **Omission: Tool error taxonomy.** The Risk report identifies that `ToolUseAbort` is a `RuntimeException` used for control flow. None of the reports define a clean exception hierarchy for tool errors. **Resolution:** Add `ToolException` hierarchy:
```java
public sealed abstract class ToolException extends LoomException {
    public static final class ArgumentCoercionFailed extends ToolException { ... }
    public static final class ExecutionFailed extends ToolException { ... }
    public static final class LoopExceeded extends ToolException { ... }
    public static final class AccessDenied extends ToolException { ... }
}
```

#### 3. Memory / Session Abstraction

**Major divergence:** Two different abstraction strategies.

| Approach | Abstraction Designer | Architecture |
|---|---|---|
| Message storage | `ConversationMemory` (own interface with windowed queries) | `MemoryRepository` (extends Spring AI `ChatMemoryRepository`) |
| Session metadata | `SessionStore` (separate interface) | `SessionMetadataRepository` (simplified version) |
| Context management | `ContextManager` (orchestrates memory + token estimation + compaction) | `ContextManagementAdvisor` kept largely as-is |
| Token estimation | `TokenEstimator` interface | Uses Spring AI's `TokenCountEstimator` |
| Compaction | `CompactionStrategy` interface | Inline in `ContextManagementAdvisor` |

**Analysis:**
- The Abstraction Designer's approach is cleaner but introduces new interfaces that embedding apps must learn.
- The Architecture's approach retains Spring AI compatibility but couples the framework to Spring AI's limited `ChatMemory` contract (no windowed queries, no compaction hooks).

**Resolution recommendation: Hybrid.**
- Define `ConversationMemory` as the framework's own interface (richer than Spring AI's `ChatMemory`).
- Ship `SpringAiConversationMemoryAdapter` that wraps a Spring AI `ChatMemory` for backward compatibility.
- The `ContextManager` remains framework-internal (not a public SPI). It coordinates `ConversationMemory` + `TokenEstimator` + `CompactionStrategy`.
- `TokenEstimator` is a framework SPI (apps can provide custom estimators).
- `CompactionStrategy` is a framework SPI (apps can provide custom compaction logic).
- `SessionStore` is a separate interface for metadata (title, model, favorites, etc.).

**Conflict note:** The Risk report correctly identifies that `ChatSessionMetadataRepository` contains app-shell columns (origin, surface, agent_id, project_id, materialization_key, etc.). The Architecture report says "remove app-specific columns." The Abstraction Designer says "attributes map as extension point." **Recommend: Remove all app-specific columns from the core schema. Add a `metadata TEXT` JSON column for app-specific extensions.** This avoids schema migration pain while letting apps store whatever they need.

**Omission: No report addresses multi-tenancy or user isolation.** The current code has no user concept (open-alpha local app). The framework should also not have one — session IDs are the only namespace. Embedding apps add user isolation at their transport layer. This is the correct design; just needs explicit documentation.

#### 4. Turn-Loop Mechanics

**Points of agreement:**
- The 6-state machine (PREPARE → INVOKE_MODEL → EVALUATE → EXECUTE_TOOLS → REPAIR → FINALIZE → DONE) is the framework's crown jewel and stays structurally identical.
- `ChatService.toolChat()` becomes the framework's `DefaultTurnOrchestrator`.
- Plan/task-specific completion detection is replaced by pluggable hooks.
- All four reports identify `TerminalTurnRepair`, `PromptContextAssembler`, `ToolAccessPolicy` as needing generalization.

**Points of divergence:**

| Aspect | Abstraction Designer | Architecture | Phased Migration |
|---|---|---|---|
| TurnEvent shape | Transport-agnostic (no HTML) | Keeps `renderedHtml` in events | Keeps `renderedHtml` |
| TurnMode enum | `TurnMode` (NORMAL, PLAN, TASK, EXECUTE_PLAN, EXECUTE_TASK) | `InteractionMode` (NORMAL, CUSTOM_1, CUSTOM_2, CUSTOM_3) | Remove entirely; use hooks |
| PlanMode replacement | `TurnMode` enum with attributes map | `InteractionMode` enum with string-keyed registry | No mode concept; completion detectors |

**Resolution recommendation:**
- **TurnEvent is transport-agnostic.** The `renderedHtml` field is a UI rendering concern. The framework emits raw text. Embedding apps render it. This keeps `loom-core` free of `commonmark`, `owasp-html-sanitizer`, and any HTML concern. The `TurnEvent` hierarchy becomes:
  ```java
  public sealed interface TurnEvent {
      record Started(String sessionId, String model, String turnId) {}
      record Thinking(String text) {}
      record Content(String text) {}
      record ToolCallStarted(String toolName, String callId) {}
      record ToolCallCompleted(ToolActivity activity) {}
      record SystemNotice(String text, NoticeKind kind) {}
      record QuestionPending(List<String> questions) {}
      record UsageUpdate(ContextUsage usage) {}
      record Completed(String text, ContextUsage usage, List<ToolActivity> activities) {}
      record Failed(String message, TurnOutcome outcome) {}
  }
  ```
  Markdown rendering is a post-processing step applied by the embedding app. The `MarkdownRenderer` is a framework-provided utility in `loom-rendering` (optional module), not in core events.

- **TurnMode is removed.** The Phased Migration report is correct: the framework should not know about plan/task/execute modes. Mode is an app concern. The framework provides:
  - `ToolAccessPolicy` — app-defined tool filtering
  - `CompletionDetector` — app-defined "is the turn done?" check
  - `RepairStrategy` — app-defined "what to do when turn is incomplete?"
  These three interfaces replace PlanMode, ToolAccessPolicy.PLAN_MODE_TOOLS, TerminalTurnRepair, and all mode-gating logic.

- **CompletionDetector** (new interface, not in any report):
  ```java
  public interface CompletionDetector {
      /** Called after each model response. Returns true if the turn is complete. */
      boolean isComplete(TurnState state);
      
      /** Called when completion is detected without tool calls. */
      Optional<String> completionMessage(TurnState state);
  }
  ```

- **RepairStrategy** (generalizes TerminalTurnRepair):
  ```java
  public interface RepairStrategy {
      /** Check if repair is needed for this response. */
      boolean needsRepair(TurnState state);
      
      /** Produce control message(s) to guide the model. */
      List<Message> repairMessages(TurnState state);
      
      /** How many repair retries before giving up. */
      int maxRetries();
  }
  ```
  
  The framework ships with `EmptyResponseRepair` (handles empty assistant responses) and `NoOpRepair` (no repair). The plan/task repair logic stays in Magenta as app-specific implementations.

**Omission: Turn cancellation lifecycle.** The Phased Migration report keeps `ActiveTurnRegistry` and interrupts. No report details the cancel/interrupt flow from facade → turn loop → model call. **Resolution:** The `TurnOrchestrator` interface should expose:
```java
boolean interrupt(String sessionId, String interruptToken, String reason);
```
The implementation calls `ActiveTurnRegistry.interrupt()` which sets a flag polled at tool-checkpoint boundaries in the state machine. This is exactly the current pattern, just exposed through the facade.

#### 5. Question-User Loop and Queueing

All reports converge. The key design:
- `InteractionTools` registers `ask_user` as a framework `ToolCallback`
- `InteractionHandler` is the app-provided callback for when questions are asked
- `MessageQueue` + `MessageQueueStore` use the existing claim/ack/release pattern

**Points of agreement:**
- The claim/ack/release pattern with stale-claim recovery is production-quality and should be preserved exactly.
- The queue and question-user loop are separate mechanisms (one is a turn-suspension protocol, one is a message buffer).

**Conflict:** The Abstraction Designer keeps `PendingMessage` as a record with `Map<String, String> metadata`. The Architecture report strips `ChatSessionSurface` from the pending message. The Phased Migration report strips `surface` and `planningModel`.

**Resolution:** Strip all app-specific fields. `PendingMessage` becomes:
```java
public record PendingMessage(
    String id,
    String sessionId,
    String messageText,
    String status,
    int position,
    int total,
    Instant createdAt,
    Instant updatedAt,
    Map<String, String> metadata     // extension point
) {}
```
The `metadata` map replaces `model`, `planningModel`, `surface` — any app-specific routing hints go there.

**Omission: No report addresses what happens when a question is abandoned.** If a turn is suspended waiting for a user answer and the user never answers (timeout, session clear, application restart), what happens? **Resolution:** Add `InteractionStore` interface with a `timeout()` method. Stale questions are auto-cleared on session clear. The default implementation times out after a configurable duration.

#### 6. Event Streaming

**Points of agreement:**
- `TurnEvent` sealed hierarchy is the framework's streaming contract
- `StreamLifecycle` provides SSE utility
- The framework does NOT own HTTP transport

**Points of divergence:**
- Abstraction Designer proposes a separate `loom-transport-sse` module. Architecture keeps SSE in the core. Phased Migration keeps `StreamLifecycle` in core.
- Abstraction Designer proposes `TurnEventTransport` as a binding interface. Other reports don't formalize this.

**Resolution recommendation:** Separate module is correct. The core framework must have zero dependency on `spring-boot-starter-web`, `SseEmitter`, or any HTTP concept. `loom-transport-sse` is a thin module that provides:
```java
public final class SseTransport {
    public static SseEmitter bind(Flux<TurnEvent> events, Duration timeout);
    // Uses TurnEventRenderer.sse() to convert events to SSE named events
}
```
This keeps the core clean and makes alternative transports (WebSocket, gRPC streaming) possible without forking.

**Omission: No report defines a `TurnEventRenderer` for JSON serialization.** Embedding apps that want REST APIs (not SSE) need to serialize `TurnEvent` to JSON. The Abstraction Designer proposed this but didn't detail it. **Resolution:** Ship `TurnEventRenderer.json(ObjectMapper)` as a default in `loom-core`. The SSE renderer goes in `loom-transport-sse`.

#### 7. Configuration Surface

All reports propose a `core.*` (or `loom.*`) property namespace. Key agreement:
- Model configuration via properties + optional JSON/YAML config file
- Compaction thresholds via properties
- Tool loop guard thresholds via properties
- Pending message claim timeout via properties

**Conflict:** Architecture report keeps `AiConfig` as the primary config object. Abstraction Designer proposes a builder-first approach with properties as secondary. Phased Migration proposes `CoreProperties` as the primary.

**Resolution:** Both. `LoomProperties` (`@ConfigurationProperties(prefix = "loom")`) is the Spring Boot native config path. `LoomBuilder` is the programmatic path. The auto-configuration uses `LoomProperties` when available, falling back to sensible defaults. The builder is for code-first embedding without Spring Boot auto-configuration.

**Omission: Model config file format.** The current project uses `ExternalAiConfigLoader` to load `AiConfig` from JSON. No report specifies the framework's model config format. **Resolution:** The framework supports:
1. `application.yml` properties: `loom.models.<alias>.endpoint=...`
2. A `loom-models.json` file (auto-discovered from classpath or configured path)
3. Programmatic `LoomBuilder.model(alias, config)`

All three produce the same `ModelConfig` records internally.

### Cross-Cutting Issues

#### Phase Ordering Conflict

| Report | Approach |
|---|---|
| Abstraction Designer | Extract model types first → tools → memory → engine → queue → facade → delete |
| Phased Migration | Delete web → delete orchestration → auth cleanup → extract → facade → tests |

**Analysis:** The Abstraction Designer extracts first (safe, incremental, always compilable). The Phased Migration deletes first then extracts (faster, avoids intermediate dead code).

**Resolution: Adopt the Phased Migration's ordering** because:
- Phase 1 (web delete) is safe and independent — ~54 files deleted, zero impact on `ChatService`.
- Phase 2 (orchestration delete + ChatService refactor) is the hard part. The Phased Migration's method-level refactoring plan for `ChatService` is more detailed than any other report.
- The Abstraction Designer's "extract model types first" approach would require maintaining both old and new packages simultaneously, creating confusion.
- The 6-phase plan (Web → Orchestration → Auth → Extract → Facade → Tests/Docs) is simpler and has clearer gates.

**But:** The Abstraction Designer's Phase 1 (create new module + port model types) should be inserted as Phase 0. Create `loom-core` as a new Maven module in the repository immediately, before any deletions. This provides a clean target for extraction. The module starts empty; code moves into it during Phase 4.

#### Module Split

The Abstraction Designer proposed 4 modules. Other reports propose a single artifact. **Recommendation: Two modules initially.**
1. `loom-core` — the framework with zero web/UI dependencies
2. `loom-transport-sse` — optional SSE binding

The `loom-autoconfigure` and `loom-example` modules can be added later. Keep the initial ship small.

#### Thread Safety Gaps (from Risk Report)

The Risk report identified three concerns not addressed elsewhere:
1. **`streamLocks` race in `doFinally`:** Orphaned `Semaphore` entries are harmless but untidy. **Fix:** Use `ConcurrentHashMap.remove(key, value)` atomically.
2. **No `close()` lifecycle:** No cleanup of in-flight turns, stream locks, pending claims, or model HTTP clients. **Fix:** All four reports now have `close()` on the facade. Specific implementation from Risk report should be adopted.
3. **`ThreadLocal` with virtual threads:** Java 25 virtual threads make `ThreadLocal` safe but can leak. **Fix:** Audit all `ThreadLocal` usage (there are two: `activeTrustedPolicies` and `OrchestrationTaskContextHolder`). Both are already in try/finally blocks. Document the constraint. After orchestration deletion, `OrchestrationTaskContextHolder` is removed; only `activeTrustedPolicies` remains (which becomes a turn-scoped carrier, not a ThreadLocal).

#### SQLite Schema Migration

**Omission:** No report fully addresses schema migration. The current project has 20+ ordered migration files managed by `SchemaMigrationRunner`. After framework extraction, only these tables remain:
- `ai_chat_memory` (→ `loom_conversation_memory`)
- `ai_chat_session_metadata` (→ `loom_session_metadata`)
- `ai_chat_pending_messages` (→ `loom_pending_messages`)
- `audit_log` (→ `loom_audit_log`)

**Resolution:** Simplify to a single `schema.sql` in `loom-core/src/main/resources/` that creates all four tables idempotently (`CREATE TABLE IF NOT EXISTS`). The `SchemaMigrationRunner` is deleted. Framework uses the same self-migrating pattern as `ChatMemoryRepository.ensureMetadataColumn()` — each repository ensures its own table on first access. This works for SQLite and is simple.

#### Virtual Thread Compatibility

**Omission:** No report addresses Java 25 virtual threads. The Phased Migration report briefly mentions using `Executors.newVirtualThreadPerTaskExecutor()` but doesn't analyze compatibility.

**Analysis:**
- `ChatService.streamLocks` uses `Semaphore` — virtual-thread-compatible (doesn't pin).
- `ConversationTurnCoordinator` uses `synchronized` — **NOT virtual-thread-friendly** (pins the carrier thread). After framework extraction, this uses the `TurnGate` interface with a `SerialTurnGate` default implementation.
- Spring AI model calls (HTTP I/O) — virtual-thread-compatible.

**Resolution:** The `SerialTurnGate` default implementation should use `ReentrantLock` instead of `synchronized` to avoid virtual thread pinning. This is a one-line change from the current `synchronized (queue) { ... }` to `lock.lock(); try { ... } finally { lock.unlock(); }`.

#### Markdown Rendering

**Conflict:** Abstraction Designer says TurnEvent is transport-agnostic (no HTML). Architecture says events carry `renderedHtml`. Phased Migration keeps `ChatMarkdownRenderer`.

**Resolution:** Markdown rendering is not a core framework concern. The `TurnEvent` types carry raw text only. The `MarkdownRenderer` is not included in `loom-core`. It moves to a separate `loom-rendering` optional module (or is simply documented as "use CommonMark to render responses"). The embedding app decides whether to render markdown to HTML.

### Production Quality Checklist (Consolidated)

Taking the Risk report's thorough checklist and merging with other reports' quality concerns:

| Concern | Status | Resolution |
|---|---|---|
| Thread safety — stream locks | **Risk identified** | Use atomic `ConcurrentHashMap.remove()` |
| Thread safety — virtual threads + `synchronized` | **Risk identified** | Replace `synchronized` with `ReentrantLock` in `SerialTurnGate` |
| Thread safety — `ThreadLocal` leak | **Risk identified** | Audit + document; remove `OrchestrationTaskContextHolder`; keep only turn-scoped carrier |
| Graceful lifecycle — no `close()` | **Risk identified** | Add `Loom.close()` that drains turns, releases locks, releases claims, closes models |
| Error handling — `ToolUseAbort` leaks | **Risk identified** | Wrap in `ToolException.LoopExceeded` at facade boundary |
| Error handling — `NonTransientAiException` leaks | **Risk identified** | Wrap in `ModelException` at facade boundary |
| Dead code — post-deletion references | **Risk identified** | PMD unused-code scan as Phase 6 gate |
| Test coverage — turn loop | **Risk identified** | Add focused tests per `TurnPhase` transition (Phase 6) |
| Test coverage — question-user E2E | **Risk identified** | Add suspend/resume lifecycle test (Phase 6) |
| Test coverage — concurrency | **Risk identified** | Add concurrent turn serialization test (Phase 6) |
| Test coverage — queue contention | **Risk identified** | Add concurrent claim test (Phase 6) |
| Configuration validation — missing config | **Risk identified** | Framework starts with sensible defaults; no external config required |
| Model alias vs remote name confusion | **Risk identified** | Facade accepts only aliases; remote-name resolution is internal |
| Schema migration complexity | **Risk identified** | Replace 20 migrations with single `schema.sql` per repository |
| No user isolation | **Not a risk** | Framework is single-tenant by design; apps add isolation |

### Unified Phase Plan

Consolidating the Abstraction Designer's "extract model types first" safety with the Phased Migration's efficient deletion ordering:

**Phase 0: Scaffold** (from Abstraction Designer)
- Create `loom-core` Maven module in repository
- Set up minimal pom.xml (Spring Boot starter, Spring AI, SQLite — no web, no UI)
- Create empty package layout
- Verify empty build passes

**Phase 1: Delete Web/UI** (from Phased Migration)
- Delete `api/web/**` (~54 files), SimplyPages, HTMX, webjars
- Move `SseStreamLifecycle` to `loom-core` temporarily (moves to `loom-transport-sse` later)
- Drop `spring-boot-starter-web`, web test dependencies
- Gate: compile, test, startup

**Phase 2: Delete Orchestration + Refactor ChatService** (from Phased Migration + Risk)
- Delete ~230 orchestration/plan/task/project/system/skills/agent files
- Delete all app-specific tool packages (file, shell, web, skills, orchestration, plan, savedplan, task)
- Surgically refactor `ChatService` — remove 13 collaborators, 20+ methods
- Refactor `RequestResolver`, `PromptContextAssembler`, `ToolAccessPolicy`, `TerminalTurnRepair`, `TurnAuditWriter`
- Gate: compile, test, startup, smoke chat

**Phase 3: Auth Cleanup** (from Phased Migration)
- Grep verification — no auth code remains
- Gate: grep returns zero results

**Phase 4: Extract to `loom-core`** (merged from all reports)
- Move surviving code into `io.mindspice.loom` packages
- Generalize: `ToolRegistry`, `ToolTranscriptStrategy`, `ToolAccessPolicy`, `ToolLoopGuard`
- Create `ConversationMemory`, `SessionStore`, `ContextManager`, `TokenEstimator`, `CompactionStrategy`
- Create `TurnOrchestrator`, `TurnRequest`, `TurnResult`, `TurnEvent` sealed hierarchy
- Create `MessageQueue`, `MessageQueueStore`, `InteractionHandler`
- Create `ModelProvider` (generalized `ChatModelRouter`)
- Gate: compile, test, startup, smoke chat with new package names

**Phase 5: Facade** (from Abstraction Designer + Phased Migration)
- Implement `Loom` facade and `LoomBuilder`
- Wire all internal services behind package-private visibility
- Implement `LoomAutoConfiguration` with `LoomProperties`
- Gate: facade integration test passes

**Phase 6: Tests, Docs, Quality** (from all reports)
- Unit tests per Phase 6 test inventory (Risk report + Phased Migration)
- Concurrency tests (virtual thread + serial turn gate + queue contention)
- Auto-configuration smoke test
- Documentation: README + 11 docs files
- PMD dead code scan
- Gate: `mvn clean verify` passes, >80% coverage, zero dead references

### Accepted Scope Boundary

All four reports agree on what stays and what goes. The unified boundary:

**Framework owns:**
- Turn loop (state machine, model calling, tool resolution, repair, finalization)
- Tool registration, discovery, argument coercion, transcript generation
- Conversation memory (message storage, compaction, token estimation)
- Session metadata (model, title, favorites)
- Message queue (enqueue, claim, ack, release)
- Question-user interaction (suspend turn, await answer, resume)
- Model routing (Ollama, OpenAI-compatible, DeepSeek)
- Markdown rendering (as optional utility)
- Event streaming (transport-agnostic TurnEvent types)
- Audit logging (optional, SPI-based)

**Framework does NOT own:**
- HTTP transport (controllers, SSE endpoints, WebSocket)
- Web UI (HTML, CSS, JS, SimplyPages, HTMX)
- Authentication/authorization
- Agent profiles and orchestration
- Plan/task design and execution
- Project management
- Workspace filesystem management
- Skills and skill activation
- Web/file/shell tools (except simple examples)
- Scheduled jobs and reactions
- Workflow graphs and execution

## Options

### Option A: Adopt All Abstraction Designer Designs (API-first)
Take the Abstraction Designer's detailed interface sketches as the canonical design. Use the Phased Migration's execution order. Fill gaps from Risk report.
- **Pros:** Rich, detailed interfaces. Clear separation between SPI and framework internals.
- **Cons:** More interfaces to implement. Risk of over-abstraction.

### Option B: Adopt Minimal Interfaces (from Architecture report)
Keep interfaces close to current code. Extend Spring AI types where possible. Minimize new abstractions.
- **Pros:** Faster to implement. Less new code.
- **Cons:** Coupled to Spring AI version. Less flexibility for non-Spring AI apps.

### Option C: Hybrid (Recommended)
Adopt the Abstraction Designer's interface designs for the 5 core areas (they are production-quality contracts). Adopt the Phased Migration's execution order (it has the most thorough dependency analysis and validation gates). Adopt the Risk report's quality checklist. Reconcile naming to `io.mindspice.loom`.
- **Pros:** Best of all reports. Rich contracts, safe execution, thorough validation.
- **Cons:** Requires reconciling ~5 conflicts noted above.

## Trade-offs

1. **Transport-agnostic TurnEvent vs. HTML-in-events:** Transport-agnostic is cleaner but means every embedding app must render markdown. **Decision: Transport-agnostic.** The rendering is trivial (one `MarkdownRenderer.render()` call) and belongs in the app layer. The framework provides the renderer as an optional utility.

2. **Own memory interface vs. Spring AI ChatMemory:** Own interface is more flexible but adds a new concept for Spring AI users. **Decision: Own interface with Spring AI adapter.** `ConversationMemory` is the framework contract. `SpringAiConversationMemoryAdapter` bridges for Spring AI users.

3. **TurnMode enum vs. pluggable detectors:** Pluggable detectors is cleaner (framework doesn't know about modes) but harder to discover. **Decision: Pluggable detectors.** The framework ships with `EmptyResponseRepair` as a default. Apps that need plan/task modes implement `CompletionDetector` + `RepairStrategy`. The Phased Migration is correct that the framework should not know about such modes.

4. **Single artifact vs. multi-module:** Single is simpler. Multi-module is cleaner (SSE transport doesn't pollute core). **Decision: Two modules initially** (`loom-core` + `loom-transport-sse`). Add more later as needed.

5. **SQLite default vs. pluggable stores:** SQLite is pragmatic. Pluggable is flexible. **Decision: SQLite default with SPI interfaces.** The `ConversationMemory` and `SessionStore` interfaces allow swapping. SQLite ships as the default implementation. The auto-configuration wires SQLite when a DataSource is present.

## Open Questions

1. **Name approval:** All four reports propose different names. User must confirm `io.mindspice.loom` or choose an alternative before Phase 0 begins.

2. **Should `ChatMarkdownRenderer` stay in core, move to optional module, or be removed?** The transport-agnostic decision pushes it to optional. But it's used internally by `ChatService.renderAssistantMessage()` for the `TurnMessage` type. **Recommendation:** Remove `renderedHtml` from `TurnEvent`. Keep `TurnMessage` as a convenience type with `renderedHtml` for apps that want pre-rendered messages. The `MarkdownRenderer` stays as a framework utility in `loom-core` but is not wired into the event stream.

3. **What example tools ship with the framework?** The Abstraction Designer proposes none (the framework owns the tool framework, not tools). The Architecture report proposes `GetTimeTool` and `EchoTool`. The Phased Migration proposes `CalculatorTools` and `DateTimeTools`. **Recommendation:** Ship `EchoTool` as the single example. It demonstrates `@Tool` annotation usage, argument passing, and transcript generation. Additional examples go in documentation.

4. **What happens to `ConversationTurnCoordinator`?** The Phased Migration keeps it as `TurnGate`. The Abstraction Designer keeps it as internal turn serialization. The Architecture report keeps it. **Recommendation:** Keep as `TurnGate` interface with `SerialTurnGate` default (using `ReentrantLock` for virtual thread compatibility). Embedding apps can provide `NoOpTurnGate` for concurrent sessions.

5. **Should the framework support multiple active conversations concurrently?** Yes. The current code already does (per-conversation semaphores + turn coordinator queues). The framework preserves this. The `TurnGate` interface controls per-conversation serialization; cross-conversation concurrency is unlimited.

6. **What becomes of `modelSelectionKey()` and model alias resolution?** The Abstraction Designer's `ModelProvider` interface handles this. Aliases are first-class; remote names are internal. The facade (`Loom.send()`) accepts alias keys. Remote name fallback is deprecated at the facade level.

7. **Should `ToolArgumentCoercionModule` be auto-discovered or explicitly registered?** The current Jackson service-loader approach works. Keep it. Add documentation for apps that need custom coercion modules.

8. **What persistence does the framework require vs. what's optional?** Required: `ConversationMemory` (message storage). Optional: `SessionStore` (metadata — can be in-memory), `MessageQueueStore` (queue — can be in-memory if queueing not used), `AuditWriter` (audit — no-op by default), `InteractionStore` (question persistence — in-memory by default).

## Recommended Next Step

1. **User confirms naming** (`io.mindspice.loom`), module count (2 initially), and example tools (EchoTool only).

2. **Execute Phase 0 immediately** — create the `loom-core` Maven module with minimal pom.xml. This takes ~30 minutes and sets the target for all subsequent work.

3. **Begin Phase 1** — delete web/UI. This is fully parallelizable with Phase 0 and is the lowest-risk deletion.

4. **Plan Phase 2 ChatService refactoring** — the Risk report's collaborator map and the Phased Migration's method-level refactoring plan should be combined into a single detailed spec before implementation begins. This is the highest-risk phase and benefits from upfront planning.

5. **Use the consolidated quality checklist** as the acceptance criteria for Phase 6. Every item must be checked off before the framework is considered production-ready.
