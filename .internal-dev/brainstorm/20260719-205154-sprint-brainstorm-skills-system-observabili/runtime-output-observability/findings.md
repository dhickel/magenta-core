## Prompt

Explore Clusters B and C as one operational lifecycle: task-run directory identity, artifact publication/indexing/lineage/retention, replay and resume lookup, runtime metrics and auditability, scheduling/reaction visibility, and Files-tab navigation. Identify affected layers and signatures, migration choices, test surfaces, and ways the output structure can support rather than constrain later observability capabilities. Stay exploratory; do not implement or edit the project.

## Source

Inspected authoritative specifications, knowledge files, and source anchors:

- `.internal-dev/specifications/architecture.md` — ARCH-20260719-01 (filesystem-first workspace identity), ARCH-20260526-01 (superseded run/output model)
- `.internal-dev/specifications/service-graph.md` — SVC-20260719-04 (output artifact persistence), SVC-20260719-05 (execution workspace selection), active consumer inventory
- `.internal-dev/specifications/services.md` — SVC-20260719-04 (output publication via lazy `outputs/`, no run hierarchy)
- `.internal-dev/specifications/api.md` — typed workspace routes, execution workspace selection, absent routes
- `.internal-dev/specifications/web.md` — agent Files tab, reusable explorer, HTMX-first policy
- `.internal-dev/specifications/deferred-features.md` — DEFERRED-20260525-15 (artifact indexing), 16 (runtime observability), 17 (scheduling UI), 20 (orchestration validation), 21 (workspace/service review)
- `.internal-dev/specifications/decisions.md` — DECISION-20260719-02 (the canonical workspace decision)
- `.internal-dev/knowledge/filesystem-first-workspaces.md` — definitive workspace model
- `.internal-dev/knowledge/output-artifact-attribution-query-and-backfill-pattern.md` — query patterns, backfill approach
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md` — materialization hardening
- `.internal-dev/knowledge/orchestration-runtime-phase-02.md` — scheduler, events, resume
- `.internal-dev/knowledge/agent-detail-workspace-health-pattern.md` — missing-telemetry honesty principle
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java` — current structural paths: only `WORKSPACE` and `OUTPUTS`, three root factories
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDescriptor.java` — kind/key/identity/root, package-constructed
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java` — sole descriptor constructor, `outputs()` method, confinement
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java` — flat `Path outputDir` materialization, `materialize()`, `publishExistingFile()`, `artifactsForRun()`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactRepository.java` — `run_output_artifacts` schema, query, backfill, indexes
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/RunOutputArtifact.java` — attribution record (runId, planId, agentId, jobId, workspaceIdentity, filePath, etc.)
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java` — descriptor resolution, `OrchestrationTaskContext`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java` — schedules, cron, agent-scoped
- `src/main/java/io/mindspice/magenta2/ai/chat/task/TaskRun.java` — task run record (no directory mapping)
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java` — typed workspace routes, explorer delegation
- `src/main/java/io/mindspice/magenta2/api/web/OutputController.java` — artifact query/download/content APIs

## Findings

### Current State: Structural Gaps

1. **No task-run directory identity.** Outputs publish flat under `workspace/agent/<id>/outputs/` via collision-safe file names. The `runId` lives only in the `run_output_artifacts` database row. There is no filesystem directory that represents a task run. This means:
   - You cannot "ls" a run's outputs from the Files tab in a structured way; they're all interleaved with other runs' outputs.
   - Agents cannot write working/intermediate files into a run-scoped directory and have them discoverable.
   - Resume/replay has no filesystem anchor — it must reconstruct context from the database alone.

2. **`OutputArtifactService.materialize()` takes a flat `Path outputDir`.** The caller decides which directory to pass. Currently callers pass the descriptor's `outputs/` directory. No caller creates task-run subdirectories. The service is agnostic to the semantics of the output directory — it just materializes there.

3. **`WorkspacePathLayout` owns no task-run structural constants.** The only structural names are `WORKSPACE` and `OUTPUTS`. The root factories (`agentRoot`, `chatRoot`, `userRoot`) are the only path constructors. There's no `taskRunRoot()`, `taskRunOutputs()`, or similar factory.

4. **`RunOutputArtifact` has no `taskName` or `taskRunId` field distinct from `runId`.** The `runId` field serves as the generic run identifier (plan run, task run, workflow run, job run). There's no first-class task run namespace in the artifact record.

5. **No artifact indexing beyond SQL queries.** The current `OutputArtifactController` lets you query by `runId`, `planId`, `agentId`, `jobId`, etc. There's no full-text search index, no content preview indexing, no rendered Markdown-to-text extraction for search. The `content_json` column stores JSON artifacts inline but is not full-text indexed.

6. **No retention policy mechanism.** Artifacts live forever unless manually deleted. No TTL, no workspace-total quota, no run-completion cleanup policy.

7. **No lineage tracking.** An artifact row knows its run/plan/agent/job but not its input artifacts, its derivation parents, or what produced it. If Run B consumes an output of Run A, there's no artifact-edge record.

8. **Runtime observability is minimal.** `OrchestrationRunnerService` uses leases, heartbeats, and status transitions. There's no persisted latency histogram, no exit-code distribution, no run-time-per-step breakdown, no container lifecycle event log, no health timeline — only status + timestamps. The agent detail history tab in the dashboard is a placeholder (WEB-20260525-06).

9. **Schedule and reaction management has no UI.** `ScheduleService` and `OrchestrationEventService` exist with APIs but no browser surface. Schedules are configured through API calls or direct DB. There's no schedule editor, no reaction rule editor, no simulation/dry-run, and no audit trail of schedule firings.

10. **Files tab is a flat workspace browser.** It mounts at `workspace/agent/<id>/` and navigates the tree. There's no special rendering for `outputs/` (showing artifact metadata alongside file listing), no task-run grouping view, no artifact preview integration, no "recent outputs" dashboard.

### Key Architectural Affordances

11. **`WorkspaceDescriptor` is the universal identity carrier.** It flows from controllers through explorer, metadata, execution, and output services. Any task-run directory scheme must integrate by extending descriptor-relative path construction, not by bypassing the descriptor.

12. **`WorkspacePathLayout` is the sole structural path authority.** New structural constants (`TASKS`, `RUNS` or similar) and factory methods must live here. No hand-concatenation of path segments is permitted outside this class.

13. **`WorkspaceDirectoryService` is the sole descriptor constructor.** Any new directory provisioning (creating task-run directories lazily) must go through this service or a tightly coupled sibling.

14. **`OutputArtifactRepository` independently owns artifact persistence.** Adding new columns (e.g., `task_name`, `parent_artifact_id`, `retention_ttl`) follows the existing additive `addColumnIfMissing` pattern for SQLite compatibility.

15. **The execution workspace selection contract (`workspaceType`/`workspaceKey` pair) stays unchanged.** Task runs nest *within* the resolved workspace descriptor; they don't select a different workspace kind.

16. **HTMX-first UI policy applies.** Any artifact browser, schedule editor, or runtime observability surface should use HTMX fragments, OOB swaps, and SimplyPages composition before adding JavaScript.

17. **The Files tab is implemented as a SimplyPages explorer shell.** It uses `WorkspaceExplorerFragments.shell()` with `WorkspaceExplorerService` for descriptor-first operations. Task-run-aware navigation must extend, not replace, this pattern.

## Options

### Option 1: Minimal Task-Run Nesting (Recommended for C)

Add exactly one new structural layer under agent workspaces:

```
workspace/agent/<id>/
  tasks/
    <taskName>/
      <taskRunId>/
        outputs/        ← reserved, lazy (same semantics as workspace outputs/)
        (agent working files anywhere else)
  outputs/              ← retained for non-task outputs, backwards compat
```

**`WorkspacePathLayout` additions:**
```java
public static final String TASKS = "tasks";
public static Path taskRunRoot(String agentId, String taskName, String taskRunId)
public static Path taskRunOutputs(String agentId, String taskName, String taskRunId)
```

**`WorkspaceDirectoryService` additions:**
- `public Path taskRunOutputs(WorkspaceDescriptor descriptor, String taskName, String taskRunId)` — validates agent descriptor, lazily creates the `tasks/<taskName>/<taskRunId>/outputs/` chain, returns confined absolute path
- Rejects chat and user descriptors (only agents have task runs)

**`OutputArtifactService` changes:**
- `materialize()` already takes `Path outputDir` — no signature change required; callers just pass the deeper path
- Consider adding `materializeToTaskRun()` convenience that accepts `taskName`/`taskRunId` and resolves outputDir internally

**`RunOutputArtifact` changes:**
- Add `taskName` and `taskRunId` as nullable attribution columns (via `addColumnIfMissing`)

**`WorkspaceController` / Files tab:**
- The explorer already navigates relative to descriptor root via `?path=` — no change needed to list `tasks/` subtree
- Add optional special rendering when the viewed path is inside a task run: show artifact metadata card, run status badge, timestamps

**Migration:** Old flat outputs in `workspace/agent/<id>/outputs/` remain inert and accessible. New task runs publish into the nested structure. Old outputs are still queryable by `runId`. No migration of existing files is required.

**Pros:**
- Minimal structural change — one new constant, three new path factories
- `OutputArtifactService` signature unchanged
- Backward compatible with existing flat outputs
- Files tab navigates into task-run directories naturally via existing explorer
- Chat workspace outputs stay flat (`workspace/chat/<id>/outputs/`) — no task nesting there

**Cons:**
- Two output locations for agent workspaces (flat legacy + nested task runs) may confuse agents browsing their own workspace
- Files tab needs optional "task run view" mode to show artifact metadata

### Option 2: Deeper Run-Time Observability Store (B1-B2)

Add a dedicated SQLite table `run_events` for structured runtime telemetry, indexed by `runId`:

```sql
create table run_events (
    id text primary key,
    run_id text not null,
    event_type text not null,   -- EXIT_CODE, LATENCY_SAMPLE, TOOL_CALL, LLM_TURN, CHECKPOINT, ERROR, HEARTBEAT
    event_time text not null,
    step_name text,
    step_index integer,
    payload_json text,          -- flexible type-specific payload
    agent_id text,
    workspace_identity text
);
create index idx_run_events_run on run_events(run_id);
create index idx_run_events_agent_time on run_events(agent_id, event_time);
```

**New service: `RunEventService`** — append-only writer, typed query methods (`exitCodeDistribution()`, `latencyPercentile()`, `recentErrors()`, `stepTimeline()`)

**Agent detail dashboard integrations:**
- Replace placeholder history tab with real timeline from `run_events`
- Add summary cards: "last 10 runs: 8 succeeded, 1 failed, 1 interrupted", "avg task duration: 42s"
- Per-run detail page with step timeline, tool calls, LLM turn latencies

**Note:** This is additive. The existing `OrchestrationRuntimeRepository` status/lease tables remain unchanged. `run_events` is a separate append-only log, not a replacement for status transitions.

**Pros:**
- Append-only event log is cheap and non-blocking
- Flexible `payload_json` avoids schema churn per event type
- Queries become simple: "SELECT event_type, count(*) FROM run_events WHERE agent_id = ? AND event_time > ? GROUP BY 1"
- No change to existing status/lease tables

**Cons:**
- Another table to schema-manage
- `payload_json` not queryable with SQL indexes (acceptable for compute-at-read metrics)
- Need to decide which execution paths emit events (task runs only? job runs? workflows?)

### Option 3: Artifact Full-Text Indexing (B1)

Add a content-extracted text column to `run_output_artifacts` for full-text search:

```sql
alter table run_output_artifacts add column content_text text;
```

Populate on materialization:
- `.md` → strip Markdown, store plain text
- `.txt`, `.csv`, `.log` → store as-is
- `.json` → store key names + string values concatenated
- Binary/other → null

Then add a SQLite FTS5 virtual table or a simple `LIKE`-based search API:

```java
// OutputArtifactRepository
public List<RunOutputArtifact> search(String query, String workspaceIdentity, int limit)
```

**New API route:** `GET /api/outputs/search?q=...&workspaceType=...&workspaceKey=...&limit=...`

**Files tab integration:** A search bar in the agent Files tab when browsing `outputs/` or a task run directory.

**Pros:**
- Low-effort search that doesn't require external indexing infrastructure
- SQLite FTS5 is built-in and sufficient for local-scale artifact volumes
- `content_text` column is cheap to backfill (re-materialize text from file_path for existing artifacts)

**Cons:**
- FTS5 virtual table requires separate maintenance (triggers or manual sync)
- Large binary artifacts won't have text; expected for file_path outputs
- Not a priority before task-run nesting lands (artifact search is more useful when artifacts are organized by run)

### Option 4: Schedule/Reaction Management UI (B3)

A SimplyPages-based management surface at `/orchestration/schedules` and `/orchestration/reactions`:

**New fragments:**
- `ScheduleListFragment` — agent-filtered schedule rows with cron expression, next run, status, edit/delete
- `ScheduleEditorFragment` — inline create/edit form with cron preview (human-readable next N runs), job/agent/template selectors
- `ScheduleAuditFragment` — recent firings (enqueue events) with status
- `ReactionRuleFragment` — event filter editor, action selector, enabled toggle

**New controller: `ScheduleController`** (or extend `OrchestrationController`):
- `GET /api/orchestration/schedules?agentId=...` — list
- `POST /api/orchestration/schedules` — create
- `PUT /api/orchestration/schedules/{id}` — update
- `DELETE /api/orchestration/schedules/{id}` — delete
- `GET /api/orchestration/schedules/{id}/preview` — next N run times
- `GET /api/orchestration/schedules/{id}/history` — firing audit

**Reaction routes similarly:**
- `GET /api/orchestration/reactions`
- `POST /api/orchestration/reactions`
- `PUT /api/orchestration/reactions/{id}`
- `DELETE /api/orchestration/reactions/{id}`
- `POST /api/orchestration/reactions/{id}/simulate` — dry-run against a sample event

**Pros:**
- Fills a clear observability gap — currently schedules are invisible
- Cron preview is high-value UX (answers "when will this actually fire?")
- Audit trail makes schedule behavior verifiable
- Reuses existing `ScheduleService` and `OrchestrationEventService` APIs

**Cons:**
- Additional controller surface that needs Playwright validation
- Schedule editor needs timezone-aware rendering (non-trivial in server-side HTML)
- Reaction rule editor must validate filter patterns against event schema — risk of invalid rules

### Option 5: Artifact Lineage and Retention (B1 extension)

**Lineage:** Add `parent_artifact_ids text` (JSON array) to `RunOutputArtifact`. When a run consumes outputs from prior runs, the orchestrator links them:
```sql
alter table run_output_artifacts add column parent_artifact_ids text;
```

A `GET /api/outputs/{id}/lineage` endpoint traverses parents and children to build a DAG view.

**Retention:** Add `retention_policy text` column (`FOREVER`, `RUN_COMPLETION`, `TTL_<seconds>`, `QUOTA_<bytes>`) to the run or workspace definition. A background cleaner in `OrchestrationRunnerService` (or a separate `@Scheduled` method) prunes expired artifacts:
```sql
alter table run_output_artifacts add column retention_policy text default 'FOREVER';
```

**Pros:**
- Lineage enables "what produced this output?" and "what consumed this output?" queries — critical for debugging multi-step workflows
- Retention prevents unbounded workspace growth

**Cons:**
- Lineage requires every consumer to declare its input artifacts — significant integration work
- Retention cleanup must be careful not to delete artifacts still referenced by lineage
- Both are lower priority than task-run nesting, without which there's no coherent unit of retention

## Trade-offs

### Run-Nested vs. Flat Outputs

| Dimension | Flat (current) | Nested (proposed) |
|---|---|---|
| Agent browsing simplicity | All outputs in one directory | Must navigate `tasks/<name>/<runId>/outputs/` |
| Multi-run collision risk | Collision-safe but interleaved | Naturally isolated |
| Resume/replay | Reconstruct from DB only | Filesystem anchor + DB |
| Files tab UX | Simple list | Needs run-aware grouping view |
| Chat workspace consistency | Same flat model | Chat stays flat vs. agent gains nesting — inconsistency |
| Migration cost | None | Zero code migration; old outputs remain inert |

**Trade-off resolution:** The nesting is clearly correct for task-oriented agents that run many tasks. The inconsistency with chat workspaces is acceptable because chat outputs are fundamentally different (conversation-scoped, not task-run-scoped). The Files tab should grow a "task runs" grouping view that shows `tasks/` entries with status badges and artifact counts, which is strictly more useful than the current flat output listing.

### Append-Only Events vs. Structured Metrics Tables

The `run_events` approach (append-only JSON payload) vs. dedicated metric columns (latency_ms, exit_code, etc.):

- **Append-only wins** for flexibility — new event types don't need schema migrations
- **Structured columns win** for query performance — `SELECT avg(latency_ms) FROM run_events WHERE event_type = 'TASK_COMPLETE'` vs. JSON extraction
- **Hybrid approach:** Keep status transitions in structured columns (they're already there in `work_assignments`), use `run_events` only for detailed timeline/debug data. Latency is computable from `started_at`/`completed_at` on existing records — no new table needed for that. The new table is for intra-run granularity the status transitions don't capture.

**Recommended:** Start with computed metrics from existing timestamps (no new table). Add `run_events` only when concrete observability needs (debugging a stuck run, understanding tool-call latencies) justify it.

### Schedule/Reaction UI Sequencing

Building schedule/reaction UI before task-run nesting is putting the cart before the horse. Schedules trigger task runs. Without task-run directory identity and artifact organization, the observability value of "schedule → run → outputs" is broken in the middle. However, the schedule editor itself is independent of output structure — it's a configuration surface. So:

**Recommendation:** Implement schedule editor (B3) and task-run nesting (C) in parallel or schedule editor slightly after, since the schedule editor's audit trail ("this schedule fired and created assignment X") doesn't need task-run directories — it just needs assignment IDs.

## Open Questions

1. **Should task-run directories use `taskName` or `taskId` as the grouping key?** `taskName` is human-readable and stable across task definition edits. `taskId` is opaque but unambiguous if names collide. Recommendation: use `taskName` (validated with `PlainPathSegmentValidator`) because it's what agents and users see. If name collision is a concern, add a deduplication suffix.

2. **Do user workspaces also get task-run nesting?** The prompt says "Chat workspaces get outputs directly in their root" — what about user workspaces? User workspaces are explicit overrides for agent execution. They should get the same task-run structure as agent workspaces since they host the same task runs. Add `workspace/user/<name>/tasks/<taskName>/<taskRunId>/outputs/`.

3. **How is `taskRunId` generated and who owns it?** Currently `TaskRun.id` is a UUID generated by the task service. Should it be the directory name? Recommendation: use the same `taskRunId` as the directory name for 1:1 traceability. The task service already generates it — just pass it through to `WorkspaceDirectoryService.taskRunOutputs()`.

4. **Does `OutputArtifactService` need a run-directory-aware API or do callers resolve the path?** The current `materialize(runId, planId, outputName, outputType, value, outputDir)` already takes an arbitrary `outputDir`. Callers can resolve the task-run output path themselves via `WorkspaceDirectoryService.taskRunOutputs()` and pass it. No service signature change needed. However, adding a convenience overload `materializeToTaskRun(...)` that takes `descriptor + taskName + taskRunId` internally would reduce caller boilerplate.

5. **Should `tasks/` directories be hidden from normal Files tab browsing?** No — the Files tab is the agent's workspace browser. Agents should see their task runs. But the listing should be enhanced: show `tasks/<name>/` as a grouped entry with run count and latest status rather than as a raw directory tree.

6. **How does the Explorer handle `tasks/<name>/<runId>/outputs/` differently from regular directories?** The explorer currently rejects mutation of `outputs/` and its descendants. This policy should extend to task-run `outputs/` directories. Additionally, the explorer could show artifact metadata (type, size, created time from the DB) alongside file attributes when browsing inside a task-run `outputs/`.

7. **Should the `run_output_artifacts` table gain a `task_name` column or just `runId`?** A `taskName` column (plus `taskRunId` if distinct from `runId`) makes grouping-by-task queries trivial: "show all outputs from the `daily-report` task." Without it, you must join through the task/plan repository to map `runId → taskName`. Recommendation: add both `taskName` and `taskRunId` columns (additive, via `addColumnIfMissing`).

8. **What's the resume/replay contract around task-run directories?** When a task run is resumed, does it reuse the same `taskRunId` directory or get a new one? If the same, output materialization must handle existing directories (already the case with collision-safe names). If new, a `parentRunId` lineage field is needed. Recommendation: reuse the same `taskRunId` directory — the collision-safe naming already handles re-materialization. Resumed runs append to the same directory, which is the intuitive behavior.

9. **Where does retention policy live?** Per-task definition? Per-workspace? Per-artifact-type? Recommendation: per-task definition with a workspace-level default. The task's `TaskDefinition` gains an optional `retentionPolicy` field. The workspace descriptor doesn't own retention — `OutputArtifactRepository` owns cleanup queries, triggered by a scheduled method.

10. **Should the Files tab ever show `.skills/` directories?** For the Skills system (Cluster A): agent-personal `.skills/` directories live at `workspace/agent/<id>/.skills/`. These should be hidden from the Files tab by default (they're configuration, not workspace output), but visible via a "show hidden" toggle or a separate Skills management surface. The explorer should not mutate them through the normal file CRUD routes — skill files go through `SkillController`.

## Recommended Next Step

**Phase the work as a sequenced lifecycle, not three independent clusters:**

1. **Phase 1: Task-Run Directory Identity (C).** Add `TASKS` constant and `taskRunRoot()`/`taskRunOutputs()` factories to `WorkspacePathLayout`. Add `taskRunOutputs()` to `WorkspaceDirectoryService`. Extend `RunOutputArtifact` with `taskName`/`taskRunId` columns. Update callers in `OrchestrationRunnerService` to pass nested output directories. Leave flat outputs inert. This phase establishes the filesystem anchor that all downstream observability depends on.

2. **Phase 2: Files Tab Navigation for Task Runs.** Add run-aware grouping to `WorkspaceExplorerFragments` when browsing inside `tasks/`. Show run status badges, artifact counts, timestamps. Integrate existing `OutputArtifactRepository` queries to enrich directory listings with artifact metadata. This gives immediate user-facing value from Phase 1.

3. **Phase 3: Artifact Indexing and Preview (B1).** Add `content_text` column for full-text extraction. Add `GET /api/outputs/search` endpoint. Integrate search into Files tab when browsing `outputs/`. This is higher value after task-run nesting because search results can be scoped by task name.

4. **Phase 4: Runtime Observability (B2).** Compute metrics from existing timestamps (no new table). Replace placeholder agent history tab with real computed metrics: run counts by status, average duration, recent errors. Add `run_events` table only when intra-run granularity is needed. Wire events from `OrchestrationRunnerService.executeWithLease()` — low-friction because execution already has a lease heartbeat loop.

5. **Phase 5: Schedule/Reaction UI (B3).** Build SimplyPages management surface for schedules and reactions. This can partially overlap with Phase 3/4 since it's independent of output structure. The audit trail ("fired at X, created assignment Y") works against existing `work_assignments` without task-run directories.

6. **Phase 6: Lineage and Retention (B1 extension).** Add `parentArtifactIds` to artifact records. Add retention policy to task definitions. Add background cleanup. This comes last because it depends on task-run directories (the unit of retention) and artifact queries (the mechanism for lineage traversal).

**Cross-cluster dependencies:**
- Cluster A (Skills) intersects with C only if `.skills/` directories appear inside task runs (unlikely — skills are workspace-scoped, not run-scoped). No blocking dependency.
- B4 (orchestration validation coverage) and B5 (workspace/service review) are verification phases best executed after Phases 1-3 are stable.
