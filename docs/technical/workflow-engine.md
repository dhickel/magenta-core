# Workflow Engine

The workflow engine lives in [`ai/orchestration/workflow`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow) and is exposed through [`WorkflowController`](../../src/main/java/io/mindspice/magenta2/api/web/WorkflowController.java) and `/workflows` operational fragments in [`OrchestrationController`](../../src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java).

## Definition Model

`WorkflowDefinition` stores:

- `id`
- `schemaVersion`
- `title`
- `summary`
- `maxConcurrency`
- `nodes`
- `routes`
- `uiLayout`
- timestamps

Definitions persist in `workflow_definitions`; nodes/routes/layout are JSON columns.

## Nodes

`WorkflowNode` is the node record. Node type values are defined by `WorkflowNodeType`. Current engine concepts include task execution, approval/wait behavior, output mapping, and control/routing semantics represented through node type, config, inputs, outputs, and bindings.

`WorkflowNodeRun` and `WorkflowNodeRunStatus` capture per-node execution status, input values, output values, and timestamps. Node runs are stored both as JSON in `workflow_runs.node_runs_json` and as queryable rows in `workflow_node_runs`.

## Routes

Workflow v2 uses explicit `WorkflowRoute` records instead of legacy `inputBindings`.

Routes include:

- Route id.
- Source and target node keys.
- Source and target ports.
- Route type from `WorkflowRouteType`.
- Optional condition/config fields.

`WorkflowValidator` rejects legacy `inputBindings` and validates that route endpoints reference known nodes and valid graph structure.

## Validation

Validation is exposed at:

- `POST /api/workflows/validate`
- `POST /api/workflows/{workflowId}/validate`
- Operational editor validation fragments under `/workflows/_editor/{workflowId}/validate`

`WorkflowValidator.ValidationResult` returns `errors`, `warnings`, and a `valid()` convenience result. Controllers translate invalid run submission into `400` with joined validation errors.

## Run Model

`WorkflowRun` stores:

- Run id and workflow id.
- Status from `WorkflowRunStatus`.
- Current node index.
- Node runs JSON.
- Workspace type, key, and internally derived canonical identity.
- Full workflow snapshot JSON.
- Final outputs JSON and artifact ids JSON.
- Final/error messages and timestamps.

Runs persist in `workflow_runs`; per-node rows persist in `workflow_node_runs`.

Assignment-owned workflow execution publishes task artifacts through the trusted canonical run-local output location, current writer capability, staged verification, and create-only promotion. Standalone workflow execution without that capability does not fall back to arbitrary publication. Durable artifact attribution is preserved in `output_publications`; legacy/non-task rows remain isolated compatibility data.

## Submission and Execution

Public API submission routes do not run workflows inline:

- `POST /api/workflows/{workflowId}/runs`
- `POST /api/workflows/{workflowId}/runs/stream`

They validate the saved definition, resolve/default an active agent, and create a durable `AssignmentType.WORKFLOW_RUN` assignment through `AssignmentService`. The SSE variant emits `submitted` or `failed` and completes.

`OrchestrationRunnerService` later executes workflow assignments through `WorkflowRunner`.

## Workflow Runner

`WorkflowRunner` executes a definition snapshot. Its responsibilities include:

- Initializing run/node state.
- Resolving bindings through `BindingResolver`.
- Executing nodes in route order.
- Delegating task nodes through `WorkflowTaskExecutor`.
- Handling approval/wait nodes and resume policy.
- Capturing node outputs and final outputs.
- Recording artifact ids from materialized outputs.
- Propagating orchestration task context into async task-node execution.
- Marking terminal status and errors.

`WorkflowExecutionObserver` provides observation hooks for execution events.

## Approval and Resume

Workflow approvals use workflow-owned `InboxService` and `inbox_messages`.

Routes:

- `GET /api/users/inbox`
- `POST /api/users/inbox/{messageId}/respond`
- `POST /api/workflow-runs/{runId}/resume`

Approval messages store response JSON, responded timestamp, handled timestamp, and metadata such as workflow run id. `ResumePolicy` controls how waiting nodes continue.

Assignment-backed workflow runs preserve `WAITING` assignment status while waiting for approval or resume. Resuming continues the original workflow run instead of starting a replacement run.

## Output Mapping

Workflow final output metadata is stored in `workflow_runs.final_outputs_json`. During execution, workflow and child task runs publish with create-only placement beneath their canonical `tasks/<taskPathKey>/<taskRunId>/outputs/` directories. `OutputArtifactRepository` owns publication and compatibility records with durable run, assignment, project, agent, and workspace attribution.

Task nodes can produce task output artifacts via the task/plan execution path. The workflow runner gathers relevant output values and artifact ids into the workflow run record.

## Job Integration

Jobs can contain workflow work items through `JobWorkItemType.WORKFLOW`. Public job run submission creates a job assignment; `OrchestrationRunnerService` executes each job item and calls the workflow path for workflow items.

Job item retry count and continue-on-failure policy determine how workflow item failures affect the surrounding job.

## Frontend Editor

Operational workflow editing is server-rendered through `/workflows` and `/workflows/_editor/*` fragments in `OrchestrationController`. HTMX handles standard definition/node/route CRUD. JavaScript in `static/js/orchestration/plans.js` and related orchestration helpers is used for richer client-side editor interactions where browser-side state makes the routed graph UI simpler than raw HTMX alone.
