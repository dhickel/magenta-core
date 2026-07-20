# Artifact API

Source: [`OutputController`](../../src/main/java/io/mindspice/magenta2/api/web/OutputController.java).

Every route requires `workspaceKind` and `workspaceKey`. Search/list may add `taskRunId`; run-retention routes inherently select the task run. Scope is checked before rows, counts, snippets, lineage, links, or bytes are returned. Project IDs and absolute paths are not accepted as authority.

## List And Search

- `GET /api/artifacts?workspaceKind=&workspaceKey=&taskRunId=&page=0&pageSize=50`
- `GET /api/artifacts/search?query=&workspaceKind=&workspaceKey=&taskRunId=&page=0&pageSize=25`

Search returns `items`, `totalCount`, paging facts, and `ftsAvailable`. Blank query is `400`. When SQLite FTS5 is unavailable, search returns `503`; metadata listing remains available.

## Detail, Preview, Download, Lineage

- `GET /api/artifacts/{artifactId}?workspaceKind=&workspaceKey=`
- `GET /api/artifacts/{artifactId}/preview?workspaceKind=&workspaceKey=`
- `GET /api/artifacts/{artifactId}/download?workspaceKind=&workspaceKey=`
- `GET /api/artifacts/{artifactId}/lineage?workspaceKind=&workspaceKey=`

Detail returns publication/reconciliation state, logical/media facts, verified size/digest, task/native run and attempt linkage, timestamps, readability, canonical workspace identity, and retention blocker state. It never returns a filesystem path.

Preview returns an explicit state: `OK`, `MISSING`, `INTEGRITY_FAILED`, `UNAVAILABLE`, `UNSUPPORTED`, or `ERROR`, plus bounded text/Markdown facts when available. Download reopens bytes through an operation-scoped authorized handle and revalidates digest/size. Integrity failure is `422` and suppresses bytes.

## Retention

Artifact routes:

- `GET /api/artifacts/{artifactId}/retention?workspaceKind=&workspaceKey=`
- `GET /api/artifacts/{artifactId}/pins?workspaceKind=&workspaceKey=`
- `POST /api/artifacts/{artifactId}/retention/soft-archive?workspaceKind=&workspaceKey=`
- `POST /api/artifacts/{artifactId}/retention/hard-expire?workspaceKind=&workspaceKey=`

Task-run routes:

- `GET|POST /api/runs/{taskRunId}/retention/policy?workspaceKind=&workspaceKey=&taskPathKey=&policyType=&retainDays=&maxArtifactCount=&maxTotalBytes=`
- `POST /api/runs/{taskRunId}/retention/soft-archive?workspaceKind=&workspaceKey=`
- `POST /api/runs/{taskRunId}/retention/hard-expire?workspaceKind=&workspaceKey=`

Retention returns blockers instead of bypassing active attempts, claims, sessions/downloads, references, lineage/legal pins, or unresolved reconciliation. Artifact mutation returns `404` for missing/out-of-scope and `409` when a known artifact is blocked.

## Browser Fragments

`/artifacts`, `/_search`, `/_detail/{artifactId}`, `/_preview/{artifactId}`, `/_lineage/{artifactId}`, and `/_retention/{artifactId}` are server-rendered HTMX routes under the `/artifacts` prefix. They preserve scoped hosts and explicit unavailable/integrity states.
