# Runtime Observability API

Source: [`RuntimeController`](../../src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java).

JSON routes use the `/api/runtime` prefix. Task-capable workspace reads accept only `agent` or `user`; `chat` is forbidden. Page limits are clamped to 1–200.

## Status, Summaries, And Health

- `GET /api/runtime/status`
- `GET /api/runtime/runs/{taskRunId}/summary`
- `GET /api/runtime/runs/{taskRunId}/rebuild`
- `GET /api/runtime/workspaces/{workspaceType}/{workspaceKey}/summaries?after=&limit=25`
- `GET /api/runtime/workspaces/{workspaceType}/{workspaceKey}/health`
- `GET /api/runtime/runs/{taskRunId}/normalized-status`

`run_summaries` is a rebuildable cache. Native run tables remain status truth. Health excludes active/unavailable records from terminal denominators and preserves missing facts as unavailable.

The rebuild operation is currently exposed as `GET`; consumers should treat it as an operational recomputation endpoint, not a cache-free status authority.

## Measurements

- `GET /api/runtime/runs/{taskRunId}/measurements?kind=MODEL|TOOL`
- `GET /api/runtime/runs/{taskRunId}/attempts/{attemptNumber}/measurements?kind=`
- `GET /api/runtime/workspaces/{workspaceType}/{workspaceKey}/measurements?kind=&after=&limit=25`

Rows identify one invocation and may include provider/model/tool, outcome, elapsed, token counts, timeout, exit, truncation, interruption, rejection/failure, and completeness. Unreported values remain null/unavailable.

## Audit

- `GET /api/runtime/runs/{taskRunId}/audit`

Audit composes exact attempt, measurement, skill snapshot/activation, artifact, and causation links. Missing audit is `404`. Typed read models omit claim secrets, writer fences, tokens, and absolute paths.

**Authorization drift:** run-specific JSON routes currently accept only `taskRunId`; they do not require workspace kind/key and therefore do not satisfy Phase 10 authorization-first scoping. Treat these routes as blocked for external consumption until the controller resolves and verifies an authorized workspace scope before returning summary, status, measurements, rebuild results, or audit. The agent Files run fragments perform additional agent/workspace checks.

## Browser Fragments

HTML adapters use a separate `/runtime` prefix:

- `/runtime/workspaces/{workspaceType}/{workspaceKey}/health`
- `/runtime/workspaces/{workspaceType}/{workspaceKey}/history`
- `/runtime/runs/{taskRunId}/measurements-fragment`
- `/runtime/runs/{taskRunId}/audit-fragment`
- `/runtime/runs/{taskRunId}/measurements/{invocationId}`

Agent Files uses `/agents/_detail/{agentId}/runs` and `/runs/{taskRunId}` for bounded groups/detail. These adapters recheck agent workspace scope before rendering run facts.
