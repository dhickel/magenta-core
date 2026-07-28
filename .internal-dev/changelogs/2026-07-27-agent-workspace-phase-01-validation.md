# Agent Workspace Phase 01 Validation

## Date

2026-07-27

## Change Summary

Validated and repaired Phase 01 closed-AGENT workspace enforcement. Ordinary writes are limited to descendants of `home`, `scripts`, and `apps`; explorer delete now denies reserved and unknown AGENT trees; file-tool writes require trusted context and have no mutable data-root fallback; held-handle mutations reauthorize exact paths at the primitive boundary.

## Files

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ConfinedPathMutationService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java`
- Focused workspace and file-tool tests

## Behavioral Impact

No-context file-tool writes fail before resolving caller paths. Legacy runtime host paths may still scope existing reads, but cannot authorize a mutation. Existing AGENT `projects`, `repos`, and unknown content remain readable only where existing policy permits and cannot be ordinarily mutated.

## Specification Impact

None. The repair implements the existing Phase 01 and workspace specifications without changing their intended contracts.

## Risks

The phase intentionally leaves publication, task-run, skill, and retention writers with their existing owner-specific authority.

## Follow-up Items

None.
