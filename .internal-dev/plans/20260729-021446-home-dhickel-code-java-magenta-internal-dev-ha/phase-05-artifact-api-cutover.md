## Context
Production controllers, services, and tools still invoke deprecated broad `OutputArtifactService` materialization, query, content, path, attribution, or publication-compatibility methods. Canonical publication must remain create-only and write-focused; scoped artifact access must not be replaced by a general legacy query API.

## Goal

remove production dependence on deprecated artifact methods

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/LegacyOutputArtifactReadService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentWorkspaceStatusService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/JobService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolService.java, src/main/java/io/mindspice/magenta2/api/web/DashboardController.java, src/main/java/io/mindspice/magenta2/api/web/JobController.java, src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/LegacyOutputArtifactReadServiceTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/JobServiceTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRunnerLifecycleIntegrationTest.java, src/test/java/io/mindspice/magenta2/ai/chat/tool/orchestration/AgentOperationalToolServiceTest.java, src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java

## Out of Scope

- Route, payload, DTO, response-body, or HTMX fragment changes.
- Legacy-row removal, new authorization semantics, offline backfill, schema or migration changes, FTS changes, UI or JavaScript changes, and the separate runtime workspace-scoping/API drift.
- New generic artifact browsing, querying, or publication APIs.
- Phase 06/07 implementation-inventory edits and final documentation, archive, changelog, review, or commit closeout.

## Dependencies

phase-03-atomic-artifact-extraction.md

## Constraints

- Preserve Spring controller/service/repository ownership. `OutputArtifactRepository` owns persistence details; callers do not construct workspace paths or infer workspace identity from a `Path`.
- Authorize canonical artifact metadata before opening bytes. Canonical reads remain scoped and integrity-checked.
- Keep legacy compatibility reads separately named, bounded, root-confined, regular-file checked, and size-bounded. Do not claim canonical integrity where legacy facts cannot establish it.
- Do not route reads through publication, add routes or payload fields, change schema or migrations, alter UI or JavaScript, or weaken Phase 03 extraction/FTS atomicity.
- Retain deprecated shims only for verified test or non-production consumers.

## Execution Boundary

- Start only after Phase 03 has passed its validator review-and-repair gate. Preserve its atomic extraction-document/external-content-FTS behavior; this phase does not change extraction or FTS DML.
- Exactly one implementation agent owns all listed edits and focused validation for this phase. Do not split caller migration, repository changes, and access tests among concurrent agents.
- This phase must pass its own validator review-and-repair gate before Phase 06 starts.

## Invariants

- `OutputArtifactService` remains create-only and write-focused. It is not a read, query, materialization, attribution-backfill, or compatibility facade.
- Canonical artifact access is scoped, authorization-first, and integrity-checked. Metadata authorization completes before any canonical byte stream or file is opened.
- Legacy compatibility reads are separately and use-case-named; they accept trusted identity/context rather than a caller-provided filesystem path, are root-confined and regular-file checked, and enforce existing applicable size bounds. They do not assert canonical integrity when legacy facts cannot establish it.
- `WorkspacePathLayout` remains the sole structural-path authority and `WorkspaceDirectoryService` remains the sole creator of trusted workspace descriptors. No migrated code infers workspace identity from a `Path`.
- Preserve current ordering, limits, aggregate semantics, DTO values, JSON bodies, and fragment fields. Do not convert scoped absence, authorization, corruption, or I/O outcomes into null-based success paths.
- Repository code owns persistence-only attribution/backfill work; publication ownership is not used for reads or backfill.

## Implementation Steps

1. Inventory every deprecated `OutputArtifactService` member invocation in production and tests, including materialization, query, content, path, attribution, and publication-compatibility calls. Classify each production caller by its exact current use case and required scope, ordering, limit, and output shape. Keep this phase's discovery evidence with its validation work; do not edit the Phase 06/07 implementation inventory.
2. Add or narrow `LegacyOutputArtifactReadService` as a read-only compatibility boundary over the repository and existing scoped canonical access/preview facilities. Expose only the demonstrated callers' bounded use cases: summaries; run/job/agent/project selections; aggregate facts; authorized canonical metadata/bytes; and explicitly named legacy reads. Add bounded repository SQL only where a current use case proves it necessary; avoid N+1 summary expansion and do not add a general query surface.
3. Migrate dashboard, job, and orchestration callers to the bounded summary, selection, aggregate, and read use cases. Preserve their current ordering, limits, DTO values, JSON responses, and HTMX fragment fields. Do not add routes, payload fields, or fragment variants.
4. Migrate operational-tool artifact access so scoped metadata lookup and authorization finish before the compatibility or canonical read path can open bytes. Obtain any access context from authorized persisted facts and trusted workspace values, never from a supplied or discovered `Path`.

   ```text
   metadata = findScopedMetadata(requestedId, trustedScope)
   authorize(metadata, actor)
   return openCanonicalBytesAndVerify(metadata)
   ```

5. Replace workspace-status direct artifact query/path resolution with one bounded summary use case. Move runner attribution backfill to `OutputArtifactRepository` persistence ownership; it must neither publish nor replace an output and must preserve existing lifecycle behavior.
6. Remove unused deprecated shims. Retain a deprecated `OutputArtifactService` shim only when the inventory identifies a verified test or non-production consumer; production source must have no invocation of any deprecated member.
7. Add focused tests for bounded summaries/selections, aggregate facts, canonical scoped metadata and bytes, legacy confinement, corrupt or missing artifacts, authorization-before-read, and attribution backfill. Include rejection of legacy absolute paths, traversal/escape attempts, non-regular files, and oversized reads; include canonical integrity failure without returning bytes. Search production source for zero deprecated `OutputArtifactService` invocations and reconcile the result against Step 1's inventory.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/tool/AGENTS.md
- src/main/java/io/mindspice/magenta2/api/AGENTS.md
- src/main/java/io/mindspice/magenta2/api/web/AGENTS.md

## Technical Guidance

- Reuse existing canonical scoped-access and preview mechanisms rather than routing reads through publication or duplicating authorization/integrity logic.
- A legacy read may resolve only a normalized relative name beneath the already selected trusted root. Confirm confinement after resolution and reject links or any non-regular target before opening it. Do not fall back from canonical failure to a legacy root.
- Keep compatibility methods named for their use case, with inputs that express trusted scope and persisted identifiers. Do not accept raw absolute paths or expose a path-returning escape hatch.
- Preserve Phase 03's repository transaction boundary: extraction and FTS persistence remain unchanged, and later exception classification must not hide partial persistence.
- Do not touch global exception mapping. Retain Phase 04's endpoint-local typed status behavior if a migrated controller observes the same failure.

## Validation

- Run the listed read-service, job-service, runner lifecycle, operational-tool, and orchestration-controller tests, plus existing publication, scoped authorization, preview, and attribution suites affected by the migrated calls.
- In `LegacyOutputArtifactReadServiceTest`, prove bounded result behavior; scope-sensitive metadata selection; aggregate correctness; canonical authorization before a byte opener is invoked; canonical missing/corrupt/digest-failure behavior; and legacy absolute-path, traversal, escape/link, non-regular-file, and size-bound rejection.
- In caller tests, assert preserved ordering, limits, DTO/response fields, and relevant fragment data. In runner lifecycle coverage, assert attribution backfill is repository-owned and does not invoke output publication.
- Compile with deprecation warnings enabled. Statistically search production `src/main/java` for invocations of deprecated `OutputArtifactService` members, reconcile every result with Step 1, and require zero results. Test-only or verified non-production shim consumers remain explicitly identified.
- Review the diff and Git status for unscoped reads, raw-path identity reconstruction, new public API surface, schema/API/UI drift, sensitive content in logs or responses, and unrelated edits.

## Exit Criteria

- Phase 03 is PASS, every listed production caller has migrated by a bounded use case, and production source invokes no deprecated `OutputArtifactService` method.
- Canonical bytes are authorized from metadata before opening and remain integrity-checked; legacy reads remain use-case-named, size-bounded, root-confined, regular-file checked, and do not overclaim canonical integrity.
- Public response/fragment shape, ordering, limits, and lifecycle behavior remain compatible; `OutputArtifactService` remains create-only and write-focused.
- All focused validation passes and the post-phase validator review-and-repair gate reports PASS.
