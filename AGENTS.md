<!-- BEGIN INTERNAL-DEV WORKFLOW -->
## `.internal-dev` Development Document Store

`.internal-dev/` is the persistent engineering document store for specifications, plans, bugs, changelogs, reviews, and reusable knowledge.

### Required workflow
- Before non-trivial implementation or planning, read the relevant files in `.internal-dev/specifications/` before changing services, APIs, web pages or fragments, SimplyPages modules, architecture, persistence, workflow behavior, or product contracts.
- Before non-trivial work, list or search `.internal-dev/knowledge/` filenames and read only files whose domain matches the task.
- When lost, blocked by project context, or correcting a false assumption, search `.internal-dev/knowledge/` filenames again, then run a deeper grep across `.internal-dev/knowledge/` before inventing a new explanation.
- Use web or official documentation when the missing information is external framework, library, tool, protocol, or platform behavior and local knowledge is absent or stale.
- Mid-workflow, route intended contracts to specifications, durable tradeoffs to `specifications/decisions.md`, reusable learning to knowledge, prior edit context to changelogs, defects to bugs, and scoped handoffs to plans or reviews.
- When a false assumption, repeated mistake, major correction, important user correction, or repeated reverification reveals reusable context, update a domain-named knowledge file and link the affected specification or changelog when useful.
- User hints like "future", "eventually", "later", or "this will become" go to `specifications/horizon-ideas.md` unless accepted as deferred product capability.
- Accepted future product capability goes to `specifications/deferred-features.md`.
- Durable architecture, design, product, and workflow decisions go to `specifications/decisions.md` with justification, alternatives or tradeoffs when known, caveats, affected specs, source, and review timing.
- After each feature implementation or non-trivial fix, complete the full `.internal-dev` closeout: update affected specifications, knowledge, bugs, changelogs, plans, and reviews; do not route active workflow material to retired focus, notes, or research stores.
- After completing the `.internal-dev` workflow for a task, create a git commit that includes both the implementation and the `.internal-dev` updates unless the user explicitly says not to commit.
- When beginning implementation of a multi-phase plan, create a dedicated git branch for that plan before phase work starts.
- For multi-phase plans, commit completed work at the end of each phase on that dedicated branch.
- For any feature or non-trivial fix, update relevant docs in `docs/`: end-user docs for behavior changes, technical docs for architecture/API/service/schema/config changes, and API docs for route or payload changes.
- Plans and reviews are written to `.internal-dev/plans/` and `.internal-dev/reviews/`.
- Out-of-scope bugs found during work are logged immediately in `.internal-dev/bugs/`.
- If this project has a GitHub repository, every `.internal-dev/bugs/` report must be mirrored directly to the GitHub repository as a GitHub Issue when it is created or compiled.
- When adding or updating a local bug report in a project with a GitHub repository, check for related closed GitHub Issues before finishing; if the corresponding issue is already closed, move the local bug report to `.internal-dev/bugs/.archive/` instead of leaving it active.
- Finalized work gets a changelog entry in `.internal-dev/changelogs/`.
- Move finalized bug/plan artifacts to sibling `.archive/` directories.

### Controlled access
- Do not read `.internal-dev` broadly by default.
- Read only the files required for the active task.

### Reference guide
- Process and templates: `.internal-dev/AGENTS.md`
- Specification routing and schemas: `.internal-dev/specifications/AGENTS.md`

### Email work summary reports
When the user asks for a work summary by email, especially after long-running orchestration plans, multi-hour remediation loops, validation campaigns, or multi-phase implementation work, use the global `email-followup-wait` skill. That skill owns the renderable HTML/plain-text report schema, optional reply-wait workflow, and low-token AgentMail polling cadence.

Magenta-specific report safety still applies: keep credentials, API keys, local secrets, ignored config contents, and unrelated private workspace details out of the report. Include relevant `.internal-dev/changelogs/` context when available so the email can stand alone as a durable closeout artifact.

Inbound AgentMail coordination uses the global direct daemon/wait workflow (`mailctl status`, `mailctl next`, and `mailctl wait`). Do not create or restore a repo-local `.internal-dev/inbox` directory or email ledger for AgentMail instructions.
<!-- END INTERNAL-DEV WORKFLOW -->



## Magenta Project Guide

Magenta is an assistant agent and manager life-helper. It is intended to run on a remote host, expose APIs and a web portal, and help users through chat, notes, reminders, task handoff, tool-assisted work, and eventually coordinated subagents.

This project is currently a Spring Boot and Spring AI application with SQLite-backed chat memory, REST/SSE chat endpoints, a simple browser chat surface, and external AI/agent configuration.


### Frontend (SimplyPages)
The frontend makes use of SimplyPages, SimplyPages is our on self maintained frontend library for server side rendering it includes/focuses on:
- HTMX simplicity over JS
- Modularity via Components which can be composed into modules
- A simple opinioned grid based layout
- A default/exam chat implementation
- A default forum implementation
- A template system for dynamic data in reuseable containers without rerendering everything
- Demos showing how to use components and do advanced operations

You can gain alot about the frontend library from the demos, complex views like the forum and and editing menus give examples on how to do modal outputs and node/card rendering
This is the style we want to take with our UI.

#### SimplyPages Links
- Documentation `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs`
- Demo `/home/hickelpickle/Code/Java/cannasite/java-html-framework/demo`

#### HTMX Default Policy
- Default to HTMX for UI interactions when building with SimplyPages.
- Most CRUD operations (create, read, update, delete), filtering, row actions, form submissions, and partial refreshes should be implemented with HTMX.
- Use JavaScript only when it is clearly the simpler path and path of least resistance for the specific behavior.
- When JavaScript is used, keep it narrowly scoped to that interaction instead of turning the page into a JS-transport surface.

#### Reusability Default Policy (SimplyPages)
- Prefer reusable components and modules over one-off page-level markup for frontend work.
- If functionality appears in multiple places and is more than bare-minimum presentation, treat it as a reusable component/module candidate.
- If views are similar, prefer shared render structures and slot-key based reuse rather than duplicating near-identical view code.
- Use SimplyPages components/modules as reusable building blocks first, and only fall back to ad-hoc page-specific structures when reuse would add unnecessary complexity.

#### SimplyPages Layout and Editing Research Policy
- Before adding or refactoring a SimplyPages UI layout, inspect the relevant SimplyPages docs and demo code first. For dashboard/module editing, start with the dynamic SlotKey/RenderContext docs, editing-system docs, editing API reference, and the demo `EditingDemoController`.
- Diagnose the existing Magenta code against SimplyPages examples before inventing a new UI structure. Identify where current code diverges from reusable `Row`, `Column`, module, HTMX, OOB swap, slot-key, or edit-decorator patterns.
- Prefer in-place decorated editing for layout operations. Moving, resizing, adding rows, adding modules/widgets, and deleting layout elements should happen on the real displayed layout whenever practical, not in a separate layout-only modal.
- Modal or drawer editing is appropriate for deep module-specific iteration, but it must not own dashboard placement, row ordering, or 12-column sizing.
- For dashboard/module editing, the SimplyPages demo pattern is the baseline: real module cards first, small top-corner decorators, centered add-module controls, and low-emphasis insert-row separators. Do not approve large text-heavy row/widget editor blocks that push content down or make the actual page look like an editor form.
- When a scratch page is useful for planning or visual experiments, create it as an internal/dev-only surface, keep it out of normal navigation, and use it only to validate ideas with Playwright. Never treat scratch pages as source-of-truth documentation; extract stable examples and lessons into production components, docs, `.internal-dev/knowledge/`, or `.internal-dev/specifications/`.

#### Agent Files Tab Style Reference
- Before designing agent Files tab or workspace browser surfaces, read `.internal-dev/specifications/web.md` and `.internal-dev/specifications/simplypages.md`.
- Keep workspace browser styling aligned with the existing `/dashboard` and per-agent dashboard operational console: dense panels, compact controls, thin blue-gray borders, small radii, semantic chips, and HTMX-first fragments.
- For workspace layout/editing work, also read `.internal-dev/knowledge/filesystem-first-workspaces.md` and `.internal-dev/knowledge/root-relative-workspace-storage.md`.

*Always use the libraries coding style and practices, do not try to shoehorn functionality or use raw html strings, raw html is a fallback for advanced cases most functionality from css, js, htmx
can be done via functions. The library has a vast set of components and ways to make your own, search the well formated documentation for your operation and read it before any edits, if
still faced with ambiguity, or needing context refer to the demos, if still confused DO NOT DO AD-HOC HACKISH WORKAROUND CONSULT THE USER.*

*Given the modularity of the library, much of our ui can be reused components across pages, always try to reuse and generalize when similar functionalities exit*

*Keep in mind threading, we have slot keys that allow us to re use the same instance of components/modules where static data is pre-rendered, then we can render with dynamic data. While this isn't a performance
bottleneck for us, it is one of the library patterns to keep in mind, and can avoid instancing multiple objects that will be converted to strings anwy-ways. Remember though you must use slot keys when sharing render objects between requests.*

#### When encountering bug/issues with SimplyPages
SimplyPages is develop and maintained by use, any issues and/or bug that are found can be directly address by use, if you find much needed missing functionality we can add it, if there is a bug we can fix it directly.
If you find yourself in a situation where the library cannot do something you reasonably expect decide if it is worth raising to an issue and file and document the issue on our github repository, you dont need to make a PR, but well document your ideas around addressing the issue, your justification, targets and scope.
If you find a bug pull the recent version of the library and directly implement a fix and file a pr.

### Engineering style
- Keep code straightforward, simple, readable, and focused on the domain problem at hand.
- Prefer lean Spring and modern Java patterns over broad enterprise layering.
- Do not add features, abstractions, tools, API surface, queues, schedulers, or orchestration machinery only because they may be useful later.
- Build the smallest complete thing that solves the current task cleanly.
- If a scope expansion or future-facing capability seems important, pause and raise it with the user before adding it.
- Keep names plain and behavior easy to trace from controller to service to repository.

### Current architecture expectations
- Web/API entry points live under `io.mindspice.magenta2.api`.
- AI chat behavior lives under `io.mindspice.magenta2.ai.chat`.
- User-editable AI and agent configuration lives under `io.mindspice.magenta2.ai.config.user`.
- Shared core utilities live under `io.mindspice.magenta2.core`.
- Controllers should stay thin and delegate behavior to services.
- Services should own use-case behavior and avoid persistence or transport details leaking into callers.
- Repositories should own persistence details and keep schema assumptions localized.
- Request/response payloads and internal data carriers should use Java records where practical.
- For workspace, output, project, job, task/plan, and workflow architecture work, read `.internal-dev/specifications/architecture.md`, `.internal-dev/specifications/service-graph.md`, `.internal-dev/specifications/services.md`, and `.internal-dev/specifications/api.md` as the current intended direction before planning or editing. In current code and docs, `task` and `plan` may be used interchangeably; prefer `task` for user-facing executable work while preserving existing compatibility until a deliberate rename is planned.
- Workspace filesystem contracts use centralized application-owned layout helpers. `WorkspacePathLayout` is the sole structural path authority; `WorkspaceDirectoryService` alone constructs trusted `WorkspaceDescriptor` values for the closed three-kind model (`AGENT`, `CHAT`, `USER`). Do not hand-concatenate root/workspace paths or reconstruct identity from `Path` in services, tools, controllers, prompts, tests, or docs.
- Current workspace semantics: the data root contains `workspace/agent/<id>/`, `workspace/chat/<id>/`, and `workspace/user/<name>/`. Agent and chat roots are lazy and may read empty when the entity exists but no physical directory has been created. User roots require explicit creation. Execution carries `workspaceType` and `workspaceKey` together; omission defaults to the bound agent; `chat` selection is rejected; `projectId` is metadata and membership context only. Outputs publish directly beneath the effective workspace's reserved lazy `outputs/` directory. The agent Files tab is the mounted browser surface. Avatar, DB-backed Work Areas, generic filesystem workspace identity, project roots, filesystem leases, run staging, old aliases, and starter files are removed.

### Agent and tool direction
- Treat Magenta as an operational assistant, not a generic framework.
- Add tool, reminder, note, file, calendar, automation, or subagent behavior only for a concrete user-facing workflow.
- Keep agent orchestration bounded, observable, cancellable, and easy to reason about when it is introduced.
- Prefer explicit configuration and small interfaces over hidden global behavior.

### Package guides
- Production Java packages with an `AGENTS.md` define local ownership and conventions for that package.
- Read the closest package guide before changing code in that package.
- Keep package guides updated whenever a change alters that package's domain responsibility, public surface, or local conventions.

### Validation expectations
- After nontrivial code changes, run the relevant automated tests.
- Before considering backend or application-wiring work complete, smoke test that the Spring Boot application context starts successfully. Prefer a bounded startup command such as `timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0` unless the task has a more specific startup path.
- For live chat, browser, SSE, agent/model routing, planning, interruption, chat switching, or concurrent-interaction validation, use the Playwright MCP workflow documented in `.internal-dev/knowledge/live-chat-mcp-workflow-testing.md`. Read that file before running this class of test, follow its MCP-first approach, and update it when you discover better methods, new gotchas, or changed endpoint behavior.
- For UI changes with interactions that can be validated in a small focused pass, run Playwright validation on the changed targets before sign-off.
- For any UI change, capture Playwright screenshots of the changed surfaces for agent-side visual review/debugging, and use them to check regressions, layout breakage, and consistency with good UI/UX design patterns before sign-off.
- For any UI layout change, the Playwright pass must include a visual quality critique, not only a functional click-through. Actively inspect alignment, spacing, gaps, density, scan hierarchy, control affordances, text wrapping, overflow, first-viewport usefulness, mobile stacking, and whether the page uses available space coherently.
- A UI layout is not validated if screenshots show stranded columns, excessive dead zones, cramped controls, clipped text, incoherent gutters, overlapping elements, weak hierarchy, or a design that appears optimized for an editor/modal instead of the actual user-facing surface.
- Screenshots are primarily an internal debugging/review artifact; share them with users only when useful for communication (for example, to pinpoint where an issue appears on screen).
- Run Playwright while the application is running so validation checks both front-end interaction behavior and observable backend behavior tied to those interactions.
- Default Playwright validation scope to focused change-target checks; deep end-to-end or full production-style Playwright integration campaigns require explicit user approval.
- Execute Playwright validation on a subagent, never inline with the main implementation workflow; the subagent should run the checks and report findings back.
- For all testing (including Playwright and non-Playwright validation), use model `gpt-5.3-codex` with reasoning effort `medium`.
- If expected Playwright validation cannot be executed, report the specific blocker and do not mark the work as fully validated.
- During UI validation and review, verify JavaScript usage is explicitly justified as the path of least resistance and that HTMX was used for standard CRUD/interaction flows.
- If startup cannot be run because required local services or secrets are unavailable, report that explicitly with the blocking dependency.
- Do not defer or work around alpha-blocking infrastructure dependencies, including filesystem/workspace-backed execution validation, by treating unit-only coverage as completion.
- If a blocking dependency prevents real execution validation, stop and consult the user immediately before proceeding; do not shoehorn substitute validation paths as a sign-off.
- Any deferred blocker must be explicitly user-approved and recorded as a blocked state, not marked as functionally complete.
