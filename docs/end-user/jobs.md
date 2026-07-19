# Jobs

Use `/jobs` to define ordered work made from plans and workflows, configure optional recurrence, start runs, and inspect job outputs and events.

## Page Layout

The page has:

- A sidebar with **New Job**, an agent filter, and job rows.
- An editor for title, summary, owner agent, project, status, manager type, and default model.
- Ordered items for plans and workflows.
- Recurrence settings.
- Run, output, and event panels.

## Create A Job

1. Open `/jobs`.
2. Select **New Job**.
3. Fill **Title** and **Summary**.
4. Choose an **Owner Agent**.
5. Choose an optional **Project**.
6. Set **Status**, **Manager Type**, and **Default Model**.
7. Save.

The project field is selector-backed where available. The owner agent field is currently a plain agent dropdown in the job editor.

Jobs run through an agent and bind to a project when one is selected. Jobs do not own separate workspace directories. Job outputs publish beneath the effective workspace's `outputs/` directory. Project association is metadata and membership context only.

## Add Ordered Items

Job items run in sequence. Each item can point at a plan or workflow.

For each item:

- Enter an item key.
- Choose item type: `PLAN` or `WORKFLOW`.
- Select a plan or workflow.
- Fill optional **bindings JSON** for plan inputs.
- Choose optional model override.
- Set priority.
- Select **Add Item**.

Plan, workflow, and model fields in the add-item form are searchable selectors where available. Type part of the title, ID, summary, or model name and select a match. **Bindings JSON** remains manual because it is user-authored runtime data, not an entity selector.

## Bind Required Plan Inputs

When a plan item references a plan with required inputs, the UI displays required binding guidance. Use JSON object syntax:

```json
{
  "input_name": "value"
}
```

If required bindings are missing, the job item save fails and names the missing fields.

## Recurrence

Each saved job can have an optional recurrence:

- **Cron Expression**: cron schedule.
- **Timezone**: schedule timezone, defaulting to `UTC`.
- **Next Fire Time**: optional ISO timestamp for the next run.

Save recurrence from the job editor. Recurrence config does not prove the scheduler has fired; inspect runs and events for runtime proof.

## Start Or Submit A Job

Saved jobs expose two operational actions:

- **Start Run** creates a job assignment using the job owner agent or the first active agent.
- **Submit to Agent** opens a form where you choose the agent, priority, optional project metadata, an existing declared user workspace override, and an optional model override before submitting.

The submit form shows the job's saved project. Project association is metadata and membership context only.

During execution, outputs publish directly beneath the effective workspace's `outputs/` directory with collision-safe normalized names. No run-owned directory hierarchy is created. Durable attribution remains queryable through `/api/outputs`.

Recurring jobs also start by creating job assignments. Use the run panel to confirm the assignment id and job run id after a recurrence fires.

## Runs, Cancellation, Outputs, Events

The job detail panels show:

- **Runs** with assignment ID, job run ID, status, agent/project context, workspace type/key, output count, created time, and cancel action for non-terminal runs.
- **Recent Outputs** from job runs, including provenance context where available.
- **Run Events** summarizing job run state changes.

Use **Cancel** for a non-terminal run when you want Magenta to stop work. Use `/outputs` to browse and download artifacts across jobs, agents, projects, and runs.

While a job has active assignments or non-terminal runs, execution-affecting edits are blocked. This includes item changes, deletion, project/default agent changes, recurrence changes, and default model changes. Label-only edits such as title and summary may still be allowed.

## Common Errors

- **Title is required**: fill the job title before saving.
- **Agent is required**: choose an agent in the submit form.
- **No active agents available**: create or enable an agent.
- **Missing required bindings**: add each required plan input to bindings JSON.
- **Invalid JSON** or ignored bindings: bindings must be a JSON object.
- **Job not found**: the job was deleted or the page is stale.
- **Active work is using this job**: wait for work to finish, cancel the run, or retry after active assignments clear before making execution-affecting edits.

## Alpha Limits

Job item editing is intentionally simple and ordered. Existing item rows show entity IDs for compact reference. Selector-backed fields help choose new plan, workflow, project, model, and workspace values, but bindings, cron, next-fire timestamps, run IDs, and status strings remain manual fields.
