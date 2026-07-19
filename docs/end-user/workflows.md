# Workflows

Use `/workflows` to compose saved plans into route-connected workflow graphs. A workflow is saved first, then submitted to an agent.

## Page Layout

The page has:

- A sidebar with **New Workflow**, a filter box, and saved workflow rows.
- An editor with title and summary.
- Node, route, validation, submit, and recent run sections.

## Create A Workflow Draft

1. Open `/workflows`.
2. Select **New Workflow**.
3. Fill **Title** and **Summary**.
4. Save.
5. Add nodes and routes after the workflow exists.

## Nodes

Nodes represent work or control points in the graph. The node editor supports node type, label, optional plan selection, input name, message template, resume policy, and parallel behavior depending on the node type.

Where the selector work has landed, plan-backed fields use searchable selectors. If a node form still shows a plain dropdown or text-like plan field, use the visible options or the saved plan ID shown by the UI.

Common node types include task execution and control/waiting behavior such as approval or message waits. The exact node type list comes from the current runtime and may expand during alpha.

## Routes

Routes connect one node to another. A route can specify:

- Source node.
- Source output name.
- Destination node.
- Destination input name.
- Route type.
- Optional condition.

Use route conditions narrowly. They are evaluated by the workflow runtime, so keep condition text aligned with output names and data the previous node can actually produce.

## Input And Output Mapping

Use node inputs, output names, and route mapping fields to pass data through the graph. A workflow is easiest to debug when each plan node has explicit structured outputs and each downstream node consumes named inputs.

For plan inputs that are complex JSON or arrays, expect to write explicit mapping values or JSON in the appropriate field. Searchable selectors choose entities; they do not author JSON bindings for you.

## Validate The Graph

Before submitting:

1. Select **Validate**.
2. Fix every error.
3. Review warnings.
4. Re-run validation until the result says the workflow is valid or only acceptable warnings remain.

Validation catches structural graph errors and type compatibility issues. A graph can still fail at runtime if a plan's own instructions, model routing, workspace, or external tool fails.

## Submit To Agent

1. Open a saved workflow.
2. Select **Submit to Agent**.
3. Choose an active agent.
4. Optionally choose project metadata, a model override, and an existing declared user workspace override.
5. Submit.

The workspace selector controls which workspace receives output files. By default the bound agent's workspace is used. Declared user workspaces are available as overrides. Project association is metadata only. The agent choice may still be a plain dropdown in this form. If no agent is selected, the UI may fall back to the first active agent; choose explicitly when the result matters.

## Runs And Waiting Work

The **Recent Runs** table shows workflow run ID, status, current node, start time, and action. Runs in `WAITING` state can be resumed from the table when the workflow runtime allows it. Approval and wait messages may also appear in `/inbox`.

Workflow run state is kept while a run is waiting so resume can continue the same run. During execution, outputs publish directly beneath the effective workspace's `outputs/` directory with collision-safe normalized names.

## Common Errors

- **Workflow not found**: the workflow was deleted or the page is stale.
- **Title is required**: the definition needs a title before saving.
- **Validation failed**: fix graph errors before submitting.
- **No active agents available**: create or enable an agent.
- **Node not found**: a route or selected panel points to a deleted node.
- **Plan not found**: a node references a deleted plan; choose a valid saved plan.

## Alpha Limits

Workflow editing is HTMX-first and form-based. It is not a full visual canvas yet. Selectors are being added across entity fields, but some node and route fields remain simple selects or text fields. Approval and wait flows are available, but advanced loop/retry design is still alpha behavior and should be validated with small workflows first.
