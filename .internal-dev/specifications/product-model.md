---
schema_version: 1
document_type: product-model-specification
status: target
owner: product
created: 2026-08-09
---

# Target Product Model and Terminology Contract

## Purpose

This file formalizes the accepted target product model for Magenta, sourced from the user product braindump on 2026-08-09. It is the intended direction for a rectification sprint: bring terminology, filesystem layout, feature set, UI, and application behavior inline with this model. Where current code or existing specifications conflict, the rectification sprint resolves them; known conflicts are flagged in [Conflicts with Current State](#conflicts-with-current-state) and must be detailed with evidence during planning.

## Intended Contract

### Terminology Contract

| term | meaning | usage rules |
| --- | --- | --- |
| workspace | The container concept for chats, agents, and projects. | The word "workspace" is used consistently for these three kinds; no other meaning should leak into UI, docs, or code naming. |
| task | A discrete executable unit. Created in chat or assigned to an agent/project. Has a preset format: schema in, schema out, and a set of criteria. | Anywhere "task" is mentioned, it is this concept. Tasks can be assigned to an agent or a project interchangeably, with a visual tag showing which. |
| plan | Planning done inline in chat with an agent. | Plans share most of the task machinery except the schema-constraint parts. |
| workflow | A node-based interface that composes tasks. | Workflows are themselves tasks, but are always referred to as "workflows" (with a visual tag). |
| project | A contained workspace with assigned agents and tasks. | Projects own bookkeeping, run, and schedule authority (see Project Model). |
| agent | An assistant identity with its own home workspace. | Agents can be assigned to projects and perform project work. |
| job | (current-code concept) | Must be mapped into the task/workflow family during the rectification sprint; decide whether it survives as a distinct user-facing term or is unified. |

Wording rule: user-facing UI, API payloads, docs, and code names must agree on these terms. Any remaining "plan/task/workflow/job" conflation is a defect of the rectification sprint.

### Workspace and Filesystem Model

- `chats/`: a top-level folder containing one folder per chat.
  - Chat outputs to the user go to `outputs/`.
  - Scripts or agent-facing files go in the chat root directory.
  - Agents must always share files with users via `outputs/`, and the user should see this updated in the chat UI.
- Agents: each agent has its own folder, which is its home workspace:
  - `home/`: the agent's own directory, for the agent to do as it pleases.
  - `scripts/`, `skills/`, and an `agents.md` file that is loaded from the agent's workspace and layered onto any project workspace the agent works in.
- Projects: each project is its own contained workspace:
  - Root level: an `agents.md` that describes the project, plus `skills/` and `scripts/` folders. These layer together with the agent's own files when agents work.
  - `project/`: the folder inside the workspace holding the actual project files. This is the root level for all agents except the orchestrating agent; the orchestrating agent has the real root (one directory above `project/`).
- Outputs: user-facing outputs live in the effective workspace's `outputs/`; agents share with users only through outputs.

### Task Model

- Tasks have a preset format with schemas in and out and sets of criteria.
- Tasks can be created in chat and can be assigned to an agent or a project (interchangeably, with a visual tag).
- Plans are done inline in chat with an agent; most task machinery exists on plans too, except the schema constraints.
- Each task added to a project carries a label denoting its state, e.g. active, scheduled.

### Workflow Model

- Workflows are a node-based interface composing tasks.
- Workflows are themselves tasks but are referred to as "workflows" everywhere (with a visual tag).

### Scheduling Model

- Scheduling is NOT cron-based.
- Three cadence types:
  1. a future date (one-time),
  2. a repeatable daily time,
  3. a repeatable interval with selectors for days, hours, minutes, and seconds.
- These are used to schedule tasks by agents: an agent can schedule either a task or a repeatable prompt.

### Project Model

- Projects are contained workspaces.
- Projects can be assigned agents.
- Tasks can be assigned to a project for bookkeeping, to allow agents to run them, or to schedule them.
- Only agents and tasks assigned to a project can be run (within that project).
- Every project has a project owner: the orchestrating agent, who can be chatted to in the drop-down. This defaults to `magenta` (the default agent) but is replaceable.
- The orchestrating agent (project owner) is allowed extra API controls such as scheduling tasks and workspace/project management. It is a root agent; most workflows run around subagents that do not have the tools exposed to interact with the project and the core workspace.
- All work happening in a project is tracked, along with which agent is performing it.

### Agent Model

- Each agent has its own folder as its home workspace.
- `home/` is the agent's personal directory.
- `scripts/`, `skills/`, and `agents.md` are loaded from the agent's workspace and layered onto any project workspace the agent works in.

### UI Requirements

- Editing UI for both the agent and project `agents.md`, reachable from their dashboards.
- Ability to view and edit skill directories and files.
- These interfaces should use the existing file explorer primitives and markdown editor.

## Constraints

- Scheduling must not be cron-based; only future-date, daily, and interval (days/hours/minutes/seconds) cadences are accepted.
- The project owner defaults to the `magenta` default agent and must be replaceable.
- Subagents must not have tools exposed that let them interact with the project or the core workspace; orchestrating (root) agents alone hold scheduling and workspace/project-management API controls.
- Agents share files with users only through `outputs/`; users see updates in the chat UI.
- The orchestrating agent's root is one level above `project/`; every other agent's root in a project is `project/`.
- Existing plan machinery is reused for tasks wherever it applies; schema in/out and criteria constraints are the differentiator added for tasks.

## Decisions

| id | decision | status | owner | source | decided_on | justification | alternatives_or_tradeoffs | caveats | affected_specs | knowledge_ref | review_after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DECISION-20260809-01 | Accepted target product model supersedes inconsistent current terminology and workspace semantics; a rectification sprint brings code, UI, and behavior inline. | accepted | product | user product braindump | 2026-08-09 | The current system conflates plan/task/workflow/job, lacks project filesystem roots, and uses a scheduling model that must be audited for cron; a single accepted model removes duplication and wording drift. | Keep current semantics and only patch wording was rejected: the user requires behavioral and filesystem changes (chats/ layout, layered workspaces, non-cron scheduling, project run authority). | Current specs (e.g. "projects have no filesystem root authority", workspace kind model) intentionally conflict and are superseded by this file for rectification scope. | `architecture.md`, `services.md`, `api.md`, `web.md`, `simplypages.md` | (synthesis handoff to be created) | 2026-08-16 |

## Validation

The rectification sprint must bring the feature set, UI, and application behavior inline with this model. Validation follows the sprint plan: focused automated tests, bounded Spring Boot startup, and Playwright browser validation for changed UI surfaces, including screenshots and a visual-quality critique per project validation expectations.

## Conflicts with Current State

Known conflicts to be confirmed and detailed during the rectification sprint (exploration + planning must produce evidence for each):

| target requirement | current state (known) |
| --- | --- |
| Project workspaces with layered root (`agents.md`, `skills/`, `scripts/`, `project/`) | Specs state projects currently have no filesystem root authority; project context is materialized under agent workspaces. |
| `chats/` folder with per-chat folders and `outputs/` | Current workspace kinds are `AGENT`/`CHAT`/`USER` under `workspace/`; chat roots are lazy. |
| Agent `home/`, `scripts/`, `skills/`, `agents.md` layering onto projects | Partial: conceptual areas (`.skills`, `scripts`, `home`…) exist as lazy areas; no project-root layering. |
| Non-cron scheduling (future date / daily / interval) | Current scheduling model must be audited (cron usage, cadence fields, reaction model). |
| Project owner agent (default `magenta`, replaceable) with extra API controls | Project owner semantics and tool exposure must be audited. |
| Only project-assigned agents/tasks runnable | Current run authority must be audited. |
| Task labels (active, scheduled, …) and per-project work tracking | Current status enums and run summaries must be audited. |
| Task schema in/out + criteria | Currently schema constraints exist on none or only some families; plans share machinery. |
| Unified task/plan/workflow terminology in UI, API, docs, code | Current code/DB/UI use plan/workflow/job families with known conflation (e.g. `plan_definitions` as task definitions). |
| Dashboard editing of agent/project `agents.md` and skills | Currently no such editing surface; file explorer + markdown editor primitives exist. |

## Open Questions

- How does the current `job` concept map into the task/workflow family (rename, unify, or keep with a visual tag)?
- Which existing schedule/reaction machinery is reused vs replaced by the three-cadence model?
- How do project materializations and immutable context bindings (current architecture) coexist with real project filesystem roots?
- Where exactly does the "visual tag" for task assignment (agent vs project) and workflow-as-task render in existing list/queue surfaces?
- What happens to `USER` workspaces and the `workspace/user/<name>` model under the new layout?
