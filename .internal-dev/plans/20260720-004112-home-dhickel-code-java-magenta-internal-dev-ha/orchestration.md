# Orchestration

## Scope Size

**Size**: large

## Phase Ledger

- phase-01-contract-path-migration-foundation.md | depends: none | targets: .internal-dev/specifications/architecture.md, src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java, src/main/resources/schema.sql | goal: lock superseding contracts and trusted migration/run-path foundations
- phase-02-run-lifecycle-fencing.md | status: PASS (2026-07-20 senior repair; 671 tests + bounded startup) | depends: phase-01-contract-path-migration-foundation.md | targets: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/TaskRunLifecycleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepository.java | goal: idempotent run operations, attempts, links, assignment fences, and writer claims
- phase-03-reserved-tree-confinement.md | depends: phase-02-run-lifecycle-fencing.md | targets: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ReservedWorkspacePathPolicy.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java, src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java | goal: structural protections and truthful fail-closed executable boundaries
- phase-04-artifact-publication-reconciliation.md | depends: phase-03-reserved-tree-confinement.md | targets: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactReconciliationService.java, src/main/resources/db/migrations/004-artifact-publication.sql | goal: run-local create-only publication with idempotency, integrity, and recovery
- phase-05-artifact-access-search-retention.md | depends: phase-04-artifact-publication-reconciliation.md | targets: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactSearchService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactPreviewService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/TaskRunRetentionService.java, src/main/java/io/mindspice/magenta2/api/web/OutputController.java, src/main/java/io/mindspice/magenta2/api/web/ArtifactFragments.java | goal: authorization-first artifact lifecycle plus its focused HTMX surface
- phase-06-scoped-skill-resolution.md | depends: phase-03-reserved-tree-confinement.md | targets: src/main/java/io/mindspice/magenta2/ai/skills/SkillScopeDiscoveryService.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillResolutionService.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillCatalogService.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentRepository.java | goal: deterministic scoped candidates and exact-version assignment
- phase-07-skill-trust-snapshots-packages.md | depends: phase-03-reserved-tree-confinement.md, phase-06-scoped-skill-resolution.md | targets: src/main/java/io/mindspice/magenta2/ai/skills/SkillTrustService.java, src/main/java/io/mindspice/magenta2/ai/skills/SkillSnapshotRepository.java, src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillActivationService.java, src/main/java/io/mindspice/magenta2/api/web/SkillController.java, src/main/java/io/mindspice/magenta2/api/web/SkillFragments.java | goal: immutable trusted activation and its control-plane UI
- phase-08-runtime-summaries-measurements.md | depends: phase-02-run-lifecycle-fencing.md, phase-04-artifact-publication-reconciliation.md, phase-07-skill-trust-snapshots-packages.md | targets: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunSummaryService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/RunMeasurementRepository.java, src/main/java/io/mindspice/magenta2/ai/chat/service/ChatService.java, src/main/java/io/mindspice/magenta2/api/web/RuntimeController.java, src/main/java/io/mindspice/magenta2/api/web/RuntimeHealthFragments.java | goal: truthful runtime facts, health/audit APIs, and focused UI
- phase-09-schedule-reaction-hardening.md | depends: phase-02-run-lifecycle-fencing.md, phase-08-runtime-summaries-measurements.md | targets: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventReactionService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEventService.java, src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/ScheduleReactionFragments.java | goal: fenced automation semantics and focused management UI
- phase-10-operational-ui-integration-closeout.md | depends: phase-05-artifact-access-search-retention.md, phase-07-skill-trust-snapshots-packages.md, phase-08-runtime-summaries-measurements.md, phase-09-schedule-reaction-hardening.md | targets: src/main/java/io/mindspice/magenta2/api/web/RunOperationsFragments.java, src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java, src/main/resources/static/css/orchestration.css, .internal-dev/specifications, .internal-dev/knowledge, .internal-dev/changelogs, docs | goal: compose the final cross-domain surface and complete repository closeout

## Execution Waves

- wave-01: phase-01-contract-path-migration-foundation.md
- wave-02: phase-02-run-lifecycle-fencing.md
- wave-03: phase-03-reserved-tree-confinement.md
- wave-04: phase-04-artifact-publication-reconciliation.md, phase-06-scoped-skill-resolution.md
- wave-05: phase-05-artifact-access-search-retention.md, phase-07-skill-trust-snapshots-packages.md
- wave-06: phase-08-runtime-summaries-measurements.md
- wave-07: phase-09-schedule-reaction-hardening.md
- wave-08: phase-10-operational-ui-integration-closeout.md

## Model Assignments

- Implementation: deepseek/deepseek-v4-pro:max
- Validation: openai-codex/gpt-5.6-sol:medium
- Implementers: exactly one implementation agent per phase

## Validation Gate

- Gate: post-phase validator review-and-repair must PASS before a phase is complete.
- Dependencies: no dependent phase starts before every dependency has PASS.

## Final Integration

- Integration: after all phases PASS, run final integration validation with openai-codex/gpt-5.6-sol:medium.
