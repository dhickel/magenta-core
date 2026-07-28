# Phase 05 — Authorized Artifact Lifecycle and Focused HTMX Surface

## Context

Stable publication enables authorization-first metadata and byte access, bounded extraction/search/preview, explicit lineage, recoverable retention, and a focused artifact fragment without coupling persistence into the filesystem explorer.

## Goal

authorization-first artifact lifecycle plus its focused HTMX surface

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactSearchService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactPreviewService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/TaskRunRetentionService.java, src/main/java/io/mindspice/magenta2/api/web/OutputController.java, src/main/java/io/mindspice/magenta2/api/web/ArtifactFragments.java

- Add access policy/handle, index, search, preview, lineage, retention, and migration 005.
- Replace output routes with explicitly scoped access and lifecycle routes.
- Implement reusable artifact search/detail/preview/lineage/retention fragments using SimplyPages and HTMX.
- Add service, repository, controller, fragment, and focused browser tests.

## Out of Scope

- Rich binary extraction, archive preview, OCR, or active embedded content.
- A general authentication platform.
- Automatic deletion without explicit policy.
- Cross-domain run/Files composition, which belongs to Phase 10.

## Dependencies

phase-04-artifact-publication-reconciliation.md

## Constraints

- Authorization precedes counts, snippets, rows, lineage, metadata, and links and is rechecked before bytes open.
- The local/open-alpha posture is not described as multi-tenant authentication.
- Search documents bind to exact artifact digests.
- Retention cannot remove active, claimed, downloading, referenced, legally pinned, or reconciliation-pending runs.
- Standard UI interactions are HTMX-first and integrity-failed artifacts expose no preview/download link.

## Implementation Steps

1. Add migration 005 and fresh schema for extraction documents, lineage edges, retention policies, pins, and bounded active-download registration.
2. Build trusted `ArtifactAccessContext` and repository authorization predicates. Reject unscoped service access; never post-filter materialized unrestricted results.
3. Implement an `AutoCloseable` authorized byte handle that revalidates metadata/state/digest, registers a bounded read, opens with no-follow behavior, and unregisters on close.
4. Verify media from bounded signatures and UTF-8 checks. Preview only allowed text formats and return explicit missing, unavailable, integrity-failed, truncated, unsupported, and reconciliation states.
5. Render Markdown with raw HTML, active content, unsafe schemes, remote images, and tracking disabled under strict byte/line/parser limits.
6. Probe deployed SQLite FTS5 using compile/runtime checks. Persist explicit unavailable state without an unapproved scan fallback.
7. Add resumable digest-keyed extraction/backfill, synchronized index updates, rebuild, and stale-document reconciliation.
8. Apply authorization inside search SQL before counts/snippets and return bounded pages plus capability state.
9. Record only producer-declared lineage, validating endpoints, authorization, cycles, duplicates, cross-workspace rules, and retention pinning.
10. Implement preserve-by-default retention with deterministic workspace/task policy and hard safety pins. Use idempotent tombstone/delete operations that reconciliation can resume.
11. Replace output content/download behavior with scoped search/detail/preview/lineage/download/retention endpoints using authorized handles and controlled status codes.
12. Implement `ArtifactFragments` with scoped filters, pagination, safe preview, metadata/download, lineage, retention confirmation, integrity disposition, and explicit empty/error/unavailable states. Use stable OOB targets.
13. Test unauthorized inference, authorization change before open, malformed/large content, false extensions, unsafe Markdown, both FTS branches, rebuild interruption, lineage cycles, all retention pins, interrupted deletion, fragment scope preservation, and HTMX swaps.

## Required Guides

- `AGENTS.md`
- Relevant service, API, web, and SimplyPages specifications
- Workspace, API, and rendering package guides
- Artifact security and Markdown knowledge files
- SimplyPages HTMX endpoint, swap, component, and module documentation and relevant demos

## Technical Guidance

The access flow is scoped query, authorized metadata, state/digest recheck, bounded no-follow open, and close registration. Fragment counts and links come only from authorized service results.

## Validation

- Run access, search, preview, lineage, retention, controller, and fragment tests.
- Exercise both FTS5 branches and allowed/denied scopes.
- Inject retention failures and reconcile.
- Run focused Playwright for artifact search, preview, download, lineage, and retention with desktop/mobile screenshots and visual critique.
- Run bounded startup and the Maven suite through the required test subagent.

## Exit Criteria

- Unauthorized callers cannot infer artifact existence or aggregates.
- Preview/download trust verified state and bytes.
- Search is bounded, digest-synchronized, and capability-truthful.
- Retention is explicit, pinned, idempotent, and recoverable.
- The artifact fragment is scoped, reusable, responsive, and HTMX-first.
- Post-phase validator review-and-repair reports PASS.
