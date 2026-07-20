# Task-Run and Artifact Lifecycle

## Topic

The complete task-run and artifact lifecycle from reservation through execution, publication, authorized discovery, and preservation-safe retention.

## Source References

- `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/concepts.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `TaskRunLifecycleService`, `WorkspaceDirectoryService`, `OutputArtifactService`, `ArtifactReconciliationService`
- `ArtifactSearchService`, `ArtifactPreviewService`, `TaskRunRetentionService`, `RunSummaryService`

## Key Takeaways

### 1. Reserve identity before dispatch

- Saved task, workflow, and job native run ids are canonical `taskRunId` values; assignment ids remain separate lease identities.
- A submission operation key and canonical request digest are persisted before queue dispatch. Same key and digest returns the recorded result; contradictory reuse conflicts.
- The run is scoped by workspace kind/key and the versioned digest `taskPathKey`. `CHAT` is rejected for tasks.
- Retry/replay allocate a new native/task-run id with typed lineage. Resume retains the run id and opens a new attempt.

### 2. Provision and run through trusted capabilities

- `WorkspaceDirectoryService` alone constructs the trusted `TaskRunLocation` at `tasks/<taskPathKey>/<taskRunId>/`; persisted paths are root-relative.
- Reads do not create missing run, output, staging, or skill directories. Provisioning creates only the canonical run hierarchy.
- Assignment lease fences and writer fences are independent monotonic capabilities. Every mutable run, file, shell, checkpoint, publication, interruption, and terminal boundary must recheck the expected owner/fence and exact workspace/run identity.
- Native plan/workflow/job rows remain status authority. Attempts, claims, lineage, summaries, and measurements are companion facts.

### 3. Publish with no replacement

- Publication reserves a logical artifact/name transactionally and records staging state before bytes are written.
- Bytes are written only beneath the trusted run-local staging/output structure while the writer capability remains current.
- Digest and size are computed and persisted; final promotion is create-only even where atomic move is unavailable. A collision receives deterministic reservation handling, never replacement.
- SQLite and filesystem commits are separate failure domains. `ArtifactReconciliationService` converges staged-without-final, row-without-file, file-without-row, stale index, integrity mismatch, and interrupted transitions or leaves a safe unresolved state.
- Legacy workspace-root `outputs/` files remain in place and retain legacy/non-task attribution; no historical facts or task directories are invented.

### 4. Search, preview, lineage, and bytes are authorization-first

- `ArtifactAccessContext` binds workspace kind/key and optional exact task run before repository counts, rows, snippets, metadata, lineage, or links are computed.
- SQLite FTS5 is runtime-probed. Supported deployments use scoped FTS; unsupported deployments return unavailable and do not perform an unapproved fallback scan.
- Preview is bounded and type-aware. Download/preview opens a bounded operation-scoped byte handle and rechecks scope and integrity at open/read time.
- Digest/size mismatch or an integrity-failed state suppresses preview and download.
- Producer lineage uses persisted exact identifiers; temporal proximity, display names, and shared paths are not attribution.

### 5. Retain safely

- The absence of a policy yields `PRESERVE`: keep indefinitely.
- Soft archive and hard expiry are explicit scoped actions; a policy does not itself bypass safety checks.
- Removal is blocked by active attempts, current claims, active downloads, references, lineage or legal pins, and unresolved reconciliation.
- Retention transitions preserve enough metadata/audit for recovery and operator explanation; they do not rewrite native run history.

### 6. Observe without changing truth

- `RunMeasurementRepository` records each model/tool invocation separately, including incomplete and nullable provider/process facts.
- `RunSummaryService` builds a replaceable read cache from native status and exact companion links. Missing facts remain unavailable, active/unavailable runs stay outside terminal health denominators, and retries are not flattened.
- Files and run metadata are adjacent UI concerns but separate services. `WorkspaceExplorerService` remains filesystem-only.

## Engine Relevance

When implementing or debugging run execution, carry the canonical run id, workspace kind/key, trusted location, attempt, and current writer capability together. Never accept a caller-built path as identity. When exposing artifacts, scope before querying and recheck when opening bytes. When cleaning storage, treat preservation as the default and require every blocker check to pass.

## Open Questions

- Whether task grouping should add a persisted immutable definition id to the summary cache instead of source-kind grouping.
- Whether bounded history should adopt a composite stable cursor when update timestamps collide.
- How operators will repair the incompatible historical `schema_migrations` constraints in the current warm local database.
