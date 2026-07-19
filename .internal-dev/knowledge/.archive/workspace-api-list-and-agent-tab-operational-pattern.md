# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

# Workspace API List/Lease + Agent Tab Operational Pattern

## Topic
- Completing workspace operational visibility by pairing API read/list surfaces with dashboard rendering that emphasizes ownership, links, leases, and path hints.

## Source References
- `.internal-dev/plans/alpha-blocking-operational-completion/03-workspace-api-and-agent-tab.md`
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepository.java`
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`

## Key Takeaways
- Keep controllers thin by parsing/validating `ownerType` and mapping status codes locally, while filtering/bounding logic stays in service/repository.
- Bounded list limits (`1..200` with sane default) prevent accidental unbounded scans while preserving flexible operator filtering.
- Lease visibility needs both holder-oriented and workspace-oriented query surfaces; keep both repository methods.
- Workspace tab operational usefulness improves with explicit zero-state rendering for both links and active leases.
- Output-path hints can be derived safely from ownership conventions when direct file browsing is out of scope.

## Engine Relevance
- Provides a reusable implementation pattern for future operational tabs where API additions and HTMX/SSR fragments should move together.
- Reinforces the policy that missing optional-service placeholders should be removed from normal dashboard paths when the dependency is core.

## Open Questions
- Should workspace lease rows be strictly foreign-keyed to `workspaces` instead of `workspace_roots` for tighter read-model cohesion?
- Should the output-path hint move to a dedicated service method to avoid duplicated ownership-to-path mapping logic?
