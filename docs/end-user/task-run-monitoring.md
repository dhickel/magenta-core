# Task Run Monitoring

Use the selected agent's operational console to inspect execution without confusing queue assignments, task runs, attempts, and artifacts.

## Where To Look

- **Queue**: active assignment state, pause/resume/cancel, diagnostics, transcript, and force-interrupt controls.
- **Files → Task Runs**: bounded recent task, workflow, and job run groups with on-demand detail.
- **History**: retained terminal assignments and transcripts.
- **Artifacts**: scoped publication state, preview/download, lineage, pins, and retention.
- **Runtime health/history**: summary and measurement views when mounted by the operational surface.

## Reading A Run

A run detail can show:

- normalized status and completeness;
- source-supported start/end timestamps;
- wall and active elapsed time;
- attempt count;
- model and tool invocation counts;
- retry/replay parent lineage;
- artifact counts, integrity state, and recent outputs.

Native task, workflow, or job records remain status authority. `run_summaries` is a rebuildable read cache. Missing historical facts remain `Unavailable`; active or incompletely reconciled data may be `Partial`.

## Run, Assignment, And Attempt Identity

- A **task run** is the stable execution resource and owns the canonical run directory.
- An **assignment** is queue/dispatch identity and is not a filesystem path.
- An **attempt** records one try within a run.
- **Resume** creates a new attempt on the same run.
- **Retry** and **replay** create new runs with typed parent lineage.

Do not compare display names to infer lineage. Use the lineage shown by the runtime/artifact records.

## Outputs And Integrity

Published outputs use create-only placement. Artifact detail may show `PUBLISHED`, missing, reconciliation, archived/expired, or `INTEGRITY_FAILED` states. Integrity failure blocks preview and download. A successful publication record does not prove the producing run succeeded; inspect run status separately.

Search, detail, preview, download, lineage, and retention are scoped to workspace kind/key and optionally an exact task run. FTS5 search can be unavailable while metadata reads remain available.

## Health And Measurements

Health excludes active runs and records whose terminal facts are unavailable from terminal denominators. Measurements are per model/tool invocation and may include elapsed time, token counts, timeout, exit, truncation, interruption, rejection, and completeness facts. Missing provider or process facts remain null/unavailable rather than becoming zero.

## Retention

Retention defaults to preservation. Archive/expire actions can be blocked by active attempts, writer claims, downloads, references, lineage or legal pins, and unresolved reconciliation. Read the reported blocker instead of deleting files manually.
