# Summary

Project Dashboard and Members render a controlled **not yet available** fragment instead of their expected operational content.

# Scope

Live browser validation of the project console at `http://localhost:18081` on a fresh SQLite database, using a newly created project.

# Reproduction

1. Open `/projects` and create or select a project.
2. Open `/projects/{projectId}?tab=dashboard`.
3. Select **Dashboard**, then **Members**.

# Expected

Dashboard and Members load their working project-scoped panels. Only Tasks, Chat, Schedules, and Events are controlled unavailable/coming-soon states in this validation scope.

# Actual

Both tabs render the controlled unavailable fragment, respectively:

- `Dashboard is not yet available in this release.`
- `Members is not yet available in this release.`

The live console registry marks only Queue available, so the Dashboard and Members tab routes return unavailable content.

# Evidence

- Playwright script: `/tmp/playwright-phase02.js`
- Screenshots: `/tmp/magenta-project-dashboard.png`, `/tmp/magenta-project-mobile.png`
- Observed on 2026-07-29 during the requested browser validation.

# Impact

The project console fails two explicitly required operational tabs. The visual screenshots also show the resulting Dashboard unavailable state instead of usable project information.

# Status

Open. Mirrored as [GitHub issue #42](https://github.com/dhickel/Magenta/issues/42).

# Next Action

Enable and validate the existing Dashboard and Members routes in the project tab registry, or reconcile the requested contract with the current Queue-only phase scope before changing behavior.
