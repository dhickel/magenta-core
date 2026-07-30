# Phase 05 Materialization, Files, and Outputs Review

## Scope

Post-phase review of immutable project materialization templates/generations, project Files, and durable-link project Outputs.

## Findings

- Repaired mutable-template deletion: template revisions are append-only and deletion now fails.
- Repaired persisted snapshot trust: V2 allocation and provisioning revalidate canonical directory-only JSON before filesystem use.
- Repaired generation descriptor confusion: V2 provision/resolve verifies the generation or key belongs to the resolved agent descriptor.
- Repaired Files state visibility: every current member remains selectable, including no-record and non-ready members; `generation=0` means no selected ready generation and cannot fall forward.
- Repaired Outputs byte authorization: project console preview/download links now use `ProjectOutputController`, which repeats capability, active-project, durable-link, and publication-integrity checks instead of emitting generic artifact URLs. Added the missing output-detail host.
- Added Settings HTMX template revision form and additive V2 provision bridge. Existing V1 bindings remain exact historical bindings and are not retargeted.

## Risk Assessment

No project workspace kind or project-root authority was introduced. V2 roots remain in `materializations/v2/`. Files APIs remain read-only. No filesystem output discovery was added. Warm startup against the developer's existing `~/.magenta` database remains blocked by a pre-existing checksum drift for immutable migration 017; fresh startup passes.

## Recommendations

Run the required Playwright validation with project operation both disabled and enabled before Phase 06. Do not repair the applied migration-017 checksum in place; investigate it as a separate migration-bookkeeping incident.

## Follow-ups

- Browser validation is blocked in this validator session because no Playwright/subagent tool is available.
- A deployment with the affected warm database needs explicit checksum-drift remediation before normal startup can be certified.
