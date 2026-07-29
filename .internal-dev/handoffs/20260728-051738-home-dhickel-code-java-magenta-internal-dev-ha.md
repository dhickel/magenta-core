# Project UI Modernization — Corrected High-Level Handoff

## Context

The current `/projects` surface is a legacy list-and-editor view whose selected project expands into one long form. Validation failures can replace the entire editor, forcing users to reselect the project and re-enter values. The page lacks the tabbed operational-console structure, compact status presentation, and focused HTMX fragments already established by `/agents/{agentId}`.

Projects remain metadata, membership, and attribution records. They do not own workspace roots. Project files exist only as agent-scoped project materializations beneath an agent workspace. A materialization is generation-bound and may be `PROVISIONING`, `READY`, `FAILED`, or `REVOKED`; `READY` means the local container is safely available and may still be empty.

Relevant existing anchors include:

- `OrchestrationController` project page and `/projects/_*` fragments.
- The agent console’s `entity-detail-layout`, `.orch-tabs`, `.orch-panel`, counter cards, metadata cards, and status chips.
- `WorkspaceExplorerFragments` and `WorkspaceExplorerService` for confined file presentation and reads.
- `AgentContextService` and `AgentMaterializationService` for materialization lifecycle truth.
- `AssignmentService`, `JobService`, `AgentProfileService`, and output queries for existing project-scoped operational facts where those facts are already supported.
- `htmx-errors.js` for explicitly marked same-origin non-2xx fragment swaps.

## Objective

Modernize `/projects` into a dense, tabbed operational surface consistent with `/agents/{id}`, while preserving the project list/detail workflow and existing project domain contracts. Users must be able to inspect project health, members, agent-local materialized files, existing project-attributed jobs and outputs, and settings without navigating through one long editor or losing context on validation errors.

## Targets

- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
  - Project page shell, selected-project shell, tab routes, project mutations, and project-scoped read-model composition from existing services.
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java`
  - Reusable, project-materialization-aware read-only presentation entry points without changing agent Files-tab behavior.
- `src/main/resources/static/css/orchestration.css`
  - Project-specific layout additions only where existing operational classes are insufficient.
- `src/main/resources/static/js/orchestration/projects.js`
  - Narrow active-tab affordance only; no CRUD or file transport.
- `src/main/resources/static/js/orchestration/htmx-errors.js`
  - Reuse the existing marked-error contract; extend markers only if project fragments cannot use an existing accepted marker.
- Focused web/controller tests under `src/test/java/io/mindspice/magenta2/api/web/`, plus service tests only if an existing service contract must be adjusted.
- Project-facing documentation and the living web/SimplyPages/API specifications affected by the implemented browser contract.

Existing context, materialization, assignment, job, profile, output, and workspace services are dependencies to consume, not replacement systems to redesign.

## Features

- A selected-project detail shell using `entity-detail-layout`, `.orch-tabs`, and a stable `#project-tab-panel`.
- Six tabs in this order: **Dashboard**, **Members**, **Files**, **Jobs**, **Outputs**, **Settings**.
- **Dashboard**:
  - Project identity and a neutral project-record state.
  - Compact counts for members, active assignments, jobs, and available materializations when authoritative existing queries provide them.
  - An explicit unavailable state for a fact that cannot be obtained from an existing authorized contract; never substitute zero or synthetic data.
  - Member-agent cards showing real agent name/ID, membership role, and agent status.
  - Per-member materialization/workspace-context health using actual lifecycle state.
  - A bounded list of real project-attributed job-run facts or persisted project events only when such records and attribution are already available; otherwise render a truthful empty or unavailable state.
- **Members**:
  - Existing add/remove membership behavior, role selection, truthful empty states, and guarded conflict feedback.
- **Files**:
  - A member/materialization selector when more than one eligible current-member materialization exists.
  - Deterministic default selection only from a current project member and that member’s exact matching project materialization; do not infer identity from a workspace path.
  - A read-only explorer rooted to the selected member agent’s exact project materialization.
  - Explicit states for no materialization record, not provisioned, provisioning, ready-empty, failed, revoked, stale generation, and unavailable.
  - Stale generation means the selected record’s captured generation no longer matches the generation resolved by the materialization authority.
- **Jobs**:
  - Existing project-attributed job definitions and real status where available, with links to existing job detail surfaces and bounded run facts only where existing attribution supports them.
  - A truthful unavailable or empty state when no existing project-scoped job contract supplies the requested fact.
- **Outputs**:
  - Existing project-attributed output records, bounded and linked through existing authorized output behavior.
- **Settings**:
  - Name, description, Owner Agent, Manager Type, Default Model, compact metadata, save status, and inline deletion confirmation.
  - No Git Repo URL control.
- Inline success, validation, conflict, not-found, and infrastructure feedback that updates only the owning form or tab host.
- Direct `/projects/{projectId}` loading continues to open the list/detail shell with that project selected.

## Settled Decisions

- Retain `/projects` as the primary page and preserve the project chooser sidebar. Modernization applies to the selected-project detail region, not to a new standalone project application.
- Existing projects use tab-specific HTMX partials targeting the stable `#project-tab-panel`. New-project creation remains a focused creation form because no project-scoped tabs exist before the record is created.
- The project tab order and labels are fixed: Dashboard, Members, Files, Jobs, Outputs, Settings.
- Reuse the agent console’s visual language, not its controller routes or agent-specific DOM ownership. The agent console and its rendered behavior must remain unchanged.
- Projects do not gain a workspace kind, project root, filesystem lease, or generic workspace identity.
- Files are agent-scoped materializations. The Files tab must select a project member’s materialization and must never imply one shared project filesystem.
- Materialization browsing is read-only on the project page. Before every project-scoped file read, revalidate project membership, materialization source project, lifecycle state, captured generation, selected member identity, and path confinement. It must not expose the rest of the member’s agent workspace.
- The Files tab does not create or provision materializations. It directs users to the owning agent Context surface when provisioning or lifecycle action is required.
- `READY` is shown as safe local availability, not acquired or synchronized content. A ready but empty directory is a valid empty state.
- Dashboard and member status must come from existing records. Because `Project` has no lifecycle status, show a neutral **Tracked** record chip rather than inventing an `ACTIVE` persistence state. Agent, assignment, job, output, and materialization states remain separate.
- Counters and bounded rows use authoritative existing records only. An authoritative query with no matching records may render zero or an empty state; absent telemetry or unsupported attribution renders unavailable or is omitted.
- “Legacy Initial Agent” becomes “Owner Agent.” This is a presentation cleanup, not a new ownership-transfer workflow. Creation may continue to set the existing nullable owner field and owner membership; existing ownership is displayed in Settings, while membership changes remain in Members.
- Remove Git Repo URL from all project browser forms and submissions. Keep the existing database/model/API compatibility field untouched because schema and public API removal are outside this scope; existing values are neither erased nor displayed.
- Use real HTTP statuses: `400` for validation, `404` for scoped absence, `409` for lifecycle/reference conflicts, and `503` or `500` for unavailable/unexpected failures as appropriate. A non-2xx browser response may swap only through the established explicit `htmx-errors.js` marker contract and only into its owning local form or tab host.
- Failed mutations do not issue OOB list, dashboard, or unrelated-region refreshes. Successful mutations may use OOB refresh only for directly affected same-domain regions, such as the project list after save/delete or dashboard counts after membership changes.
- Delete confirmation remains inline through HTMX. A successful delete clears the selected detail and refreshes the project list; a blocked delete leaves the project and settings context visible.
- Standard navigation, CRUD, filtering, and refresh behavior remains HTMX-first. JavaScript is limited to active-tab visual state if needed.

## Constraints

- Build project UI with SimplyPages components/builders and existing reusable fragments. Do not introduce duplicated raw HTML for project-specific layouts.
- Preserve thin controller boundaries: validate scope, compose authorized existing-service values, and render fragments; do not move persistence or filesystem policy into the web layer.
- Do not add backend services, schema migrations, workspace kinds, project roots, materialization creation flows, task/workflow submission behavior, or ownership-transfer semantics.
- Do not modify agent console routes, labels, tab order, targets, or behavior.
- Do not bypass `WorkspaceDirectoryService`, `WorkspacePathLayout`, `AgentContextService`, or `AgentMaterializationService` by constructing roots or deriving authority from a `Path`.
- Do not expose absolute paths, canonical host locations, claim/fence secrets, or unrelated agent workspace contents.
- Keep operational lists bounded and deterministically ordered. Missing telemetry is unavailable or omitted, never rendered as zero or fabricated activity unless zero is an authoritative count.
- Preserve existing REST compatibility under `/api/projects`; browser-only HTMX routes may be added under the existing `/projects/_detail/{projectId}/...` namespace.
- Preserve unrelated edits and existing data, including stored `gitRepoUrl` values.

## Scope

**In scope**

- Project list/detail shell modernization and responsive tab navigation.
- Dashboard read-model composition from existing authorized services and records.
- Members cleanup and inline mutation feedback.
- Read-only per-member project-materialization file browsing.
- Existing project-attributed jobs, supported run facts, outputs, and settings tabs.
- Browser-form label cleanup and omission of Git Repo URL.
- Inline create, update, membership, and delete error handling.
- Focused CSS, narrow tab-state JavaScript, tests, documentation, and living-spec updates required by the browser behavior.

**Out of scope**

- Agent console changes.
- New project lifecycle/status persistence.
- New project filesystem roots or a `PROJECT` workspace kind.
- Materialization provisioning, reconciliation, acquisition, revocation, or import flows.
- Writable project-materialization controls.
- Public REST removal of `gitRepoUrl`, database column removal, or data migration.
- Ownership transfer or a redesigned project-role model.
- New job, assignment, output, task, workflow, event, telemetry, or submission capabilities.
- New aggregate services or persistence queries created solely to manufacture dashboard facts.
- Unrelated operational UI refactors.

## Assumptions

- A project may have no members, and a member may have no materialization for that project.
- Multiple members may each have distinct materializations for the same project; there is no canonical shared copy.
- Materialization records can be filtered by exact `sourceKind=project` and `sourceId=projectId`; membership is checked independently.
- Existing services may provide facts for active assignment counts, project jobs/runs, member profiles, output attribution, and materialization state. Each fact must be confirmed against the actual service contract before it is rendered.
- A dashboard activity row is permitted only for bounded persisted, project-attributed records already supplied by existing services. If none exist or attribution is unavailable, the dashboard shows a truthful empty or unavailable state.
- Existing owner metadata remains nullable and compatibility-oriented. The modernization changes its label and placement only.
- Direct project links and the left project list remain useful navigation contracts and should not be removed.

## Recommended Direction

Create one reusable project detail shell with project-specific tab navigation and a stable tab host. Keep each tab independently loadable and independently replaceable so stale or failed responses cannot overwrite the whole project detail region.

Compose dashboard facts at the web boundary from existing authorized service records only after verifying the relevant service contract. Prefer small reusable project render helpers for identity facts, counters, member cards, empty/unavailable/error states, and bounded rows rather than expanding one monolithic editor method. Do not introduce a project aggregate service or new persistence query to fill an unsupported dashboard field.

For Files, add a project-materialization presentation mode around the existing explorer vocabulary. Resolve the selected member and exact project materialization through context/materialization services, verify current membership, exact source identity, `READY`, captured generation, and confinement for every read. Render only read/navigation/preview affordances; omit create, edit, rename, move, copy, tag, and delete controls. Keep all existing agent explorer entry points behaviorally unchanged.

Give each mutation a local status host and return the owning form or tab with submitted values preserved on failure. Mark non-2xx fragments only through the accepted HTMX error contract. Use OOB refresh only after successful mutations and only for directly affected same-domain regions.

## Validation

- Inspect the existing service and repository contracts before implementation to establish which assignment, job, run, output, profile, materialization, and explorer facts are already authorized and project-attributed. Render unavailable states for unsupported facts rather than adding replacement infrastructure.
- Focused controller tests prove:
  - `/projects` and `/projects/{projectId}` render the list/detail shell.
  - The six tabs exist in the settled order and target only `#project-tab-panel`.
  - Each tab route validates project scope and returns the expected fragment, empty state, or unavailable state.
  - New-project creation transitions to the selected project dashboard and OOB-refreshes the list only after success.
  - Missing names and invalid settings return `400`, preserve submitted values, and leave navigation/context intact.
  - Membership conflicts and blocked deletion return `409` marked local fragments without replacing the full detail shell or issuing OOB refreshes.
  - Not-found project/member/materialization cases return controlled `404` fragments.
  - Browser HTML contains “Owner Agent” and contains neither “Legacy Initial Agent” nor “Git Repo URL.”
- Materialization/file tests prove:
  - Only current project members and exact project materializations are selectable.
  - Default selection is deterministic and never derived from an agent workspace path.
  - No materialization record, non-`READY`, stale-generation, missing directory, failed, revoked, and ready-empty states are distinct.
  - File navigation cannot escape the materialization or reach another member, project, or agent-workspace area.
  - Project Files exposes no mutation controls and performs no materialization or directory creation.
  - Agent Files-tab routes and fragment output remain unchanged.
- Dashboard tests prove that rendered counts and rows are project-scoped, authorized, bounded, and deterministically ordered; authoritative absence is distinct from unavailable data; no placeholder activity is rendered.
- Run focused web/service tests, then the applicable broader test suite and build/type/lint checks.
- Start the Spring Boot application with a bounded smoke test and confirm the application context loads.
- Execute focused Playwright validation on a subagent using `gpt-5.3-codex` with medium reasoning while the application is running. Cover desktop and mobile widths, direct project loading, project selection, all tabs, invalid create/update, member add/remove, blocked delete, Files lifecycle states, and empty/loading/error states.
- Capture screenshots of the changed surfaces and perform an explicit visual critique of alignment, density, hierarchy, wrapping, overflow, first-viewport usefulness, responsive stacking, control affordances, and consistency with `/dashboard` and `/agents/{id}`.
- Verify standard interactions are HTMX-driven and any retained JavaScript is limited to tab-state presentation.
- Review the final diff for accidental agent-console changes, project-root/workspace authority, API/schema removal, invented telemetry or aggregate services, unrelated edits, raw markup duplication, and unbounded queries.

## Open Questions

None blocking. Before rendering each optional operational fact, implementation must confirm that an existing authorized service contract supplies it. Unsupported facts use the defined unavailable state. Ownership transfer, writable materializations, shared project storage, public removal of the legacy Git URL field, and new event or telemetry capabilities are separate product decisions and must not be inferred from this modernization.

## Sign-off

The corrected handoff is ready for implementation when the team accepts this completion bar: `/projects` presents the six-tab operational layout; every rendered fact is real, authorized, and project-scoped or explicitly unavailable; file browsing is read-only and materialization-bound; validation never ejects the user from the active form or tab; legacy browser labels and Git URL controls are absent; the agent console and workspace architecture remain unchanged; and focused automated, startup, and Playwright visual validation pass.
