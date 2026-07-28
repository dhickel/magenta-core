# Phase 05 Final Revalidation

## Scope

Revalidated `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/phase-05-cut-over-execution-and-outputs.md` against the current branch after the previously reported cleanup findings.

## Findings

### Blocking: runtime tool and instruction context still carries retired multi-root aliases

`OrchestrationTaskContext` still exposes `hostOutputPath`, `hostRunPath`, and `hostRootPath` rather than one resolved descriptor plus one active confined path. `AgentFileToolService`, `AgentShellToolService`, and `AgentsMdResolver` still resolve `root/`, `run/`, `work/`, `projects/<id>`, assignment output directories, project roots, and materialized project links. These are Phase 05-owned active paths and directly conflict with the descriptor-only and reserved-`outputs/` contract.

Representative anchors:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationTaskContext.java:27`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java:416`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/file/AgentFileToolService.java:479`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java:167`
- `src/main/java/io/mindspice/magenta2/ai/chat/tool/shell/AgentShellToolService.java:241`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AgentsMdResolver.java:69`

### Blocking: retired output discovery, temporary-copy, and promotion APIs remain active and tested

`OutputArtifactService` still exposes loose discovery, directory-copy, `copied_temp/*`, and run-local promotion behavior. Active tests assert those retired contracts. Phase 05 implementation step 12 requires these paths to be removed.

Representative anchors:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java:256`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java:340`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java:429`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactServiceAttributionTest.java:423`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactServiceAttributionTest.java:549`

### Blocking: removed routing contracts still have test consumers

`OutputPublicationTarget` still carries `workspaceId`, Work Area selection, output route, and direct output path fields. `OutputDirectoryServiceTest` and `WorkspacePathSegmentValidationTest` still instantiate retired `OutputDirectoryService` and `EffectiveWorkspaceResolver`. Class definitions may remain for Phase 06 deletion, but Phase 05 requires no active consumers and removed-contract tests to be deleted or rewritten.

Representative anchors:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputPublicationTarget.java:14`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputDirectoryServiceTest.java:132`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathSegmentValidationTest.java:163`

### Corrected during revalidation

- Corrected `src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md` so project ids are attribution-only and outputs publish directly under the selected descriptor.
- Removed stale unused imports of `EffectiveWorkspace`, `OutputPublicationTarget`, and `ResolvedOutputDirectory` from `PlanService` and `JobService`.

## Risk Assessment

High. The full suite and startup pass, but tests still validate superseded behavior. Advancing to Phase 06 would delete dependencies still referenced by Phase 05-owned runtime/tool/output code and tests, and the current runtime continues to expose forbidden project/run/output aliases.

## Recommendations

1. Complete the vertical descriptor cutover for `OrchestrationTaskContext`, file/shell tools, `AgentsMdResolver`, and prompt consumers.
2. Remove loose discovery, copied-temp publication, and promotion methods and rewrite output tests around direct declared publication.
3. Remove or rewrite all tests consuming `OutputDirectoryService`, `EffectiveWorkspaceResolver`, Work Area routing, project links, and multi-root context fields.
4. Repeat focused filesystem inspection, stale-reference searches, `mvn test`, and fresh/existing-root startup.

## Follow-ups

- Phase 06 remains blocked until these Phase 05-owned contracts are removed and final revalidation passes.
- Validation evidence: `mvn test` passed 876 tests; fresh and existing-root bounded startups both reached `Started Magenta2Application`.
