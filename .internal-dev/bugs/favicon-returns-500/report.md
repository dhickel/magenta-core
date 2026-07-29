# Summary

The project-console browser load requests `/favicon.ico`, which responds with HTTP 500 and creates a browser console error.

# Scope

Focused Playwright validation of `http://localhost:18081/projects` on 2026-07-29.

# Reproduction

1. Open `/projects` in Chromium.
2. Inspect browser console or request `GET /favicon.ico`.

# Expected

The favicon is served successfully or absent with a non-server-error response, leaving the console clean.

# Actual

`GET /favicon.ico` returns HTTP 500. Chromium reports `Failed to load resource: the server responded with a status of 500`.

# Evidence

- `curl http://localhost:18081/favicon.ico` returned status 500.
- `/tmp/playwright-phase02.js` captured the console error at `http://localhost:18081/favicon.ico`.

# Impact

The project console remains usable, but normal page loads produce an avoidable browser error and do not meet a zero-console-error standard.

# Status

Open. Mirrored as [GitHub issue #43](https://github.com/dhickel/Magenta/issues/43).

# Next Action

Trace favicon resource handling and return a valid icon or a non-500 absent-resource response.
