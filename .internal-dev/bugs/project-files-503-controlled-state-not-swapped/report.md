# Summary

Project Files returns a valid controlled capability-disabled fragment with HTTP 503, but HTMX does not swap it into the project tab panel.

# Scope

Live Chromium Playwright validation at `http://localhost:18085` after creating a fresh project through `POST /api/projects`.

# Reproduction

1. Create a project with no trusted deployment capability.
2. Navigate to `/projects/{projectId}`.
3. Select the **Files** tab.

# Expected

The Files tab renders its controlled capability-disabled/no-materialization state in `#project-tab-panel`, without browser console errors.

# Actual

`GET /projects/_detail/{projectId}/files` returns HTTP 503 and a controlled `project-files-state capability-disabled` fragment. HTMX leaves the existing Queue panel in place and logs a 503 response error. The error-swap handler only permits responses containing classes such as `orch-error`; it does not recognize `project-files-state`.

# Evidence

- Script: `/tmp/playwright-phase05.js`
- Desktop Files screenshot: `/tmp/project-files-tab-v1.png` (still shows Queue after clicking Files)
- Mobile Files screenshot: `/tmp/project-files-mobile-v1.png` (still shows Queue after clicking Files)
- Outputs screenshot: `/tmp/project-outputs-tab-v1.png` (Outputs loads successfully)
- Observed 2026-07-29. The validating project was `d6c5ea8f-81df-4157-b6be-94490238e22a`.

# Impact

Users cannot see the truthful controlled Files state and receive console errors instead. The Outputs tab remains functional.

# Status

Open. Mirrored as [GitHub issue #45](https://github.com/dhickel/Magenta/issues/45).

# Next Action

Make the declared HTMX non-2xx error-swap handler recognize the Project Files controlled-state fragment (or render an existing recognized error marker), then rerun desktop and mobile Playwright validation.
