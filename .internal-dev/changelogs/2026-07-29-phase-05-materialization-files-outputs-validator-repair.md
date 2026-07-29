# Phase 05 Materialization, Files, and Outputs Validator Repair

## Date

2026-07-29

## Change Summary

Validated Phase 05 and repaired template immutability/canonical snapshot checks, generation-agent binding checks, Files-state rendering, project-scoped output byte links, and the missing Outputs detail host. Added Settings HTMX template revision editing and additive V2 materialization provisioning for templated projects.

## Files

- Materialization template/generation services, context bridge, and project console
- New `ProjectOutputController`
- Project console/template tests and technical/end-user documentation
- Architecture, service, and API specifications

## Behavioral Impact

Template revisions cannot be deleted. Every persisted V2 directory snapshot is revalidated before provisioning. Files preserves non-ready member states rather than hiding them. Project output preview/download repeats durable project-lineage authorization and does not use generic artifact links.

## Specification Impact

Updated `architecture.md`, `services.md`, and `api.md` for migration 018, V2 generation ownership, template revisions, and scoped output byte routes.

## Risks

Warm startup against the local existing database fails before application wiring because its recorded checksum for immutable migration 017 differs from the bundled resource. Fresh startup succeeds. Browser/Playwright validation was unavailable in this session.

## Follow-up Items

Run the required Playwright desktop/mobile checks with trusted deployment disabled and enabled. Investigate the existing migration-017 checksum drift without modifying an applied migration.
