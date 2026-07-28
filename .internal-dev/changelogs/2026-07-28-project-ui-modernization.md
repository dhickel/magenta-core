# Date

2026-07-28

# Change Summary

Modernized `/projects` from a long editor into a chooser-backed six-tab operational console. Added Dashboard, Members, read-only materialized Files, Jobs, Outputs, and Settings fragments; local HTMX error handling; Owner Agent presentation; authorized output links; and operational-console styling.

# Files

- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java`
- `src/main/resources/static/css/orchestration.css`
- `src/main/resources/static/js/orchestration/projects.js`
- Focused controller, fragment, and explorer tests under `src/test/java/`
- Project/web technical and end-user documentation
- Web, SimplyPages, API, service, and service-graph living specifications
- Workspace package guides and filesystem-first workspace knowledge

# Behavioral Impact

Project selection now opens a stable tab shell consistent with the agent operational console. Browser forms omit Git repository URL, label the compatibility owner as Owner Agent, preserve submitted values on validation failure, and keep deletion confirmation local. Project Files browses only current-member, exact-source, current-generation `READY` materializations through trusted read-only confinement and presents lifecycle/empty/error states without provisioning or mutation.

# Specification Impact

Updated `web.md`, `simplypages.md`, `api.md`, `services.md`, and `service-graph.md` to define project tab hosts, HTMX error ownership, read-only materialization authority, status mappings, and allowed service edges.

# Risks

Focused tests, package build, and bounded startup pass. Playwright visual/interaction validation is blocked because this harness exposes no subagent controls, while repository policy requires Playwright to run on a `gpt-5.3-codex` medium subagent. The full Maven suite also exposes an unrelated order-dependent `ChatControllerTest` failure tracked in GitHub Issue #41; the failing test passes in isolation.

# Follow-up Items

- Run the required focused desktop/mobile Playwright campaign from a harness with subagent controls and capture screenshots/visual critique.
- Resolve GitHub Issue #41 so the full suite is deterministic.
