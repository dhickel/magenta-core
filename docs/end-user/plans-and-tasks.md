# Plans And Tasks

Use `/plans` to create saved plan/task definitions and submit them to agents. In the current UI, plans are the user-facing editor for structured executable task definitions.

## Page Layout

The page has:

- A sidebar with **New Plan**, **New Plan Chat**, a filter box, and plan cards.
- A detail panel for the selected plan with top **Editing Details** and **Planning Chat** tabs. Only the selected tab's window is rendered in the detail area.
- In-editor sections for scalar fields, deliverables, inputs, outputs, steps, validation criteria, assumptions, evidence, feedback, questions, model choices, and submission.

## Create A Plan

1. Open `/plans`.
2. Select **New Plan**.
3. Enter the plan name in the modal.
4. Fill **Summary**, **Goal**, and **Notes** in the **Editing Details** tab.
5. Save the plan.
6. Add structured sections after the draft exists.

Use **New Plan Chat** when you want Magenta to interview you and build a saved task draft in `/plans`. Enter the draft name in the modal; Magenta creates the saved draft and opens the **Planning Chat** tab. This chat is plan-scoped, does not appear in the `/chat` session sidebar, and is separate from anonymous in-chat planning.

## Structured Plan Sections

- **Deliverables**: high-level user-visible or operational outcomes such as code/file edits, reports, state changes, management operations, or transient updates.
- **Inputs**: structured runtime fields the agent must receive when the plan runs.
- **Outputs**: named structured values intended for workflow chaining, downstream plan inputs, or highly specific directed task results.
- **Steps**: ordered execution instructions.
- **Validation Criteria**: checks that prove the task is complete.
- **Assumptions**: explicit defaults or constraints.
- **Evidence**: proof collected while executing or reviewing.
- **Feedback**: validation or review feedback.
- **Questions**: open questions that still need user input.

Inputs and outputs have a name, type, required flag, array flag, description, and optional schema. Use simple names that will be easy to map in jobs and workflows.

## Saved Plan Chat

The embedded saved plan chat starts new chat drafts with four opening questions:

1. Whether the plan has runtime inputs, including field names, types, required flags, array flags, schemas, and examples, or “no inputs”.
2. Goal.
3. High-level deliverables, with structured outputs handled separately.
4. Specific structured outputs for workflow chaining or downstream use, or “no outputs”.

After those answers are collected, Magenta gives them to the planning model as seed context. The model synthesizes concise plan fields, updates the selected saved draft through saved-plan tools, and asks follow-up questions until the plan is clear enough for approval. Opening answers are not copied directly into input names, deliverables, or outputs.

Saved plan chat updates the selected saved draft. The manual editor remains authoritative for direct edits. Open the **Planning Chat** tab on an existing draft to continue from the current plan state. Open it on an approved plan to start from “What do you need to change in this plan?”

When you save manual edits and the plan already has chat history, Magenta appends a concise context message to the saved plan chat describing the edited fields or sections. This keeps the next chat turn aware of changes made outside the transcript without treating the edit notice as an answer to the current prompt.

## Models And Manager Type

The editor exposes:

- **Manager Type** for the work profile.
- **Planning Model** for plan drafting.
- **Execution Model** for running the plan.

Model fields are populated from configured models. If the current model is no longer available, the UI may still show it with a warning or as a current value.

## Finalize A Plan

Finalize only after the plan has enough detail for an agent to execute without guessing:

- Clear goal.
- Concrete deliverables.
- Required inputs and expected outputs.
- Ordered steps.
- Validation criteria.
- Important assumptions.

Finalize changes the plan status from draft to approved. It does not execute the plan.

## Submit To Agent

Each submission reserves a distinct run before the agent starts. Transport retries can reuse the submission operation key, but two intentional submissions with identical inputs remain separate runs. Resume keeps the same run and opens a new attempt; operator retry/replay creates a linked sibling run.

1. Open an existing plan.
2. Select **Submit to Agent**.
3. Choose an agent.
4. Optionally choose project metadata, a model override, and an existing declared user workspace override.
5. Set priority.
6. Fill runtime inputs generated from the plan's input definitions.
7. Submit.

The workspace selector controls which workspace receives output files. By default the bound agent's workspace is used. Declared user workspaces are available as overrides. Project association is metadata only. The agent field in the plan submit panel may still be a plain dropdown.

After submit, Magenta creates an assignment. Open the linked agent page and use the queue, diagnostics, transcript, history, workspace, and outputs tabs to follow execution.

## Runs And History

Plan submissions create agent assignments and may create task run records. The plan page focuses on definition authoring and submission. For runtime state, use:

- The agent **Queue** tab for active assignments.
- The agent **History** tab for terminal assignments, diagnostics, and transcripts.
- `/outputs` for artifacts.
- `/jobs` when the plan runs as part of a job.

During execution, outputs publish directly beneath the effective workspace's `outputs/` directory with collision-safe normalized names. Ordinary chat files remain separate from these output artifacts. Durable attribution is queryable through `/api/outputs`.

## Anonymous Chat Plan Execution

Anonymous `/chat` plans run inside the chat conversation instead of creating saved `/plans` runs. During execution, Magenta records evidence and validation feedback in the chat plan status area.

Start an anonymous chat plan with `/plan`. You can also type `/plan <topic or instruction>`; Magenta treats the inline text as the answer to the first goal prompt and moves to the next planning question.

When you answer an anonymous chat planning question, Magenta saves the answer before asking the configured planning model to continue. If the model request fails, a planning tool dependency is unavailable, or the turn is interrupted after draft edits, the chat shows a controlled notice and keeps your answer in the transcript. If no next prompt is available, Magenta queues a recovery clarification so you can keep planning after fixing the model configuration or unavailable service.

If the browser submits an old planning answer after the server has already moved on or advanced to a later question, Magenta refreshes the planning prompt instead of leaving the chat at a stale error.

Only one anonymous plan execution can run for a chat conversation at a time. If another execution is already active, Magenta rejects the second request instead of attaching it to the in-progress run.

If the browser stream closes during anonymous execution, Magenta records the disconnect separately from the plan result. A closed tab or broken SSE connection should not by itself turn the plan into `NEEDS_REVIEW`; the plan still needs the execution model and validator to finish successfully before it shows as completed.

If the model provider closes a response stream during execution, Magenta retries the turn when that failure is recognized as transient. If repeated provider failures still prevent validated completion, the plan moves to `NEEDS_REVIEW` with evidence explaining what failed.

Completion is validator-gated. The execution model must call `plan_complete` with evidence for each validation criterion and a proposed final message. Magenta then validates that evidence and referenced artifacts before showing the final completion as trusted. Artifacts previously saved with `plan_report` are carried into completion validation automatically. Validation feedback shows whether a planning validator model ran, which model was used, whether a deterministic preflight rejected the completion before a model call, or whether no validator model was available. Completion validation does not fall back to the execution model. If validation fails, the remediation remains internal execution feedback and the model must continue working or call `plan_complete` again.

For anonymous chat plans, artifact names such as `report.md` are checked in the chat's file directory during validation, so files created during execution can be cited without a full internal path.

If the model emits a tool call with malformed JSON arguments, Magenta records a compact tool diagnostic and asks the model to retry or choose the next action. The malformed tool call is not executed.

If a tool call has valid JSON but the arguments do not match the tool's expected shape, Magenta records the same kind of diagnostic and keeps the planning or execution turn moving instead of ending the chat turn with a server error. Planning questions may be supplied as plain text or simple question objects; Magenta extracts the user-visible question text before showing them in the prompt card.

Execution evidence may be supplied as plain text or simple evidence objects with fields such as criterion and evidence. Magenta normalizes those entries before validation, and accepts `artifacts` as another name for artifact paths.

If automatic retries are exhausted, the plan becomes `NEEDS_REVIEW`. In that state the chat does not reopen planning controls or show **Cancel planning**. Review the execution evidence, validation feedback, and tool transcript before trusting or revising the result.

## Common Validation Failures

- **Title is required**: every plan must have a title.
- **Cannot determine field index**: a structured field row did not submit its expected form data. Refresh and retry the row edit.
- **Field index out of range**: another tab or user changed the field list. Refresh before editing.
- **No active agents available**: create or enable an agent.
- **Plan not found**: the plan was deleted or the page is stale.
- **Required runtime input omitted**: fill every required input before submitting, especially when the plan is used inside a job.

## Alpha Limits

Plans are saved definitions, not direct execution buttons. Submit saved work to an agent. Anonymous `/chat` plans are separate ad hoc sessions and are not saved into `/plans`. Concurrent edits can make list indexes stale, so refresh before editing the same plan in multiple tabs.
