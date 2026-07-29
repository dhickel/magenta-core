# Project UI Modernization Handoff

## Context

The `/projects` page is a legacy flat editor form with no tab structure, no workspace file explorer, and no visual parity with the `/agents/{id}` operational console. The agent dashboard has a sophisticated 15-tab layout (dashboard, profile, queue, inbox, jobs, schedules, reactions, context, files, outputs, system-chat, exec, history, submit) using dense panels, thin blue-gray borders, small radii, semantic chips, and compact controls. Projects have none of this.

Specific defects:
1. **Error handling pushes user to next page** — When a required field like "name" is missing, the error replaces the entire editor container. User must re-navigate and re-fill the form.
2. **No tabbed layout** — Projects render as one long flat form. Should have tabs like agents.
3. **No workspace file explorer** — The primitive exists (`WorkspaceExplorerFragments`) but isn't used on the project page. Materialization/workspace view should show files.
4. **Legacy fields** — "Legacy Initial Agent" label, "Git Repo URL" field (git removed from plan).
5. **No workspace health / agent status** — Should show assigned agents' status, active assignments, recent activity.
6. **Visual mismatch** — Uses generic `orch-panel` styling without the compact card grids, status badges, and tab nav from the agent console.

## Objective

Modernize the `/projects` page to match the `/agents/{id}` operational console visual language and layout. Add a workspace file explorer tab, workspace health dashboard, and inline error handling.

## Settled Decisions

- Use the same `entity-detail-layout` + `tabNav` + `orch-panel` pattern from `/agents/{id}`
- Tabs: Dashboard, Members, Files (workspace explorer), Jobs, Outputs, Settings
- Inline errors: use the `htmx-errors.js` mechanism or inline status messages that don't replace the full editor
- Remove "Legacy Initial Agent" label → just "Owner Agent"
- Remove "Git Repo URL" field entirely
- Files tab uses existing `WorkspaceExplorerFragments` with materialization context
- Dashboard tab shows: project name/status, member agent cards with status badges, active assignment count, recent job runs
- Delete confirmation stays inline (not a separate page)

## Constraints

- Follow SimplyPages component patterns (use builders, not raw HTML)
- Match `orchestration.css` operational console styling
- HTMX-first for all interactions
- Use existing `WorkspaceExplorerFragments` for file browsing
- Use existing `AgentContextService` / `AgentMaterializationService` for workspace context
- Don't touch the agent console; only modify project routes/fragments
- Keep `/projects` as the page route, use HTMX partials for tabs

## Scope

In scope:
- Redesign `/projects` page layout with tabs
- Dashboard tab: project overview, member status cards, workspace health
- Members tab: existing member management (clean up)
- Files tab: workspace file explorer via WorkspaceExplorerFragments
- Jobs tab: active jobs (existing)
- Outputs tab: recent outputs (existing)
- Settings tab: project name, description, owner, model, worktype settings
- Inline form validation errors
- Remove gitRepoUrl field and legacy labels
- Visual styling to match agent dashboard

Out of scope:
- New backend services or schema changes
- Agent detail page changes
- Materialization creation flow changes
- Task/workflow submission changes

## Recommended Direction

Redesign projectEditorFragment to use the tabbed layout pattern from agentDetailLayout. Each tab loads via HTMX from existing or new partial routes. Error handling uses inline status divs with `.orch-status` class without full page replacement.

## Open Questions

None.
