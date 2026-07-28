# Phase 02 — Build Workspace Identity Foundation

## Date

2026-07-19

## Change Summary

Established the closed `AGENT`, `CHAT`, and `USER` workspace identity model, trusted descriptors, authoritative repository-backed entity checks, exact three-kind roots, lazy agent/chat roots, explicit user workspace lifecycle, and descriptor-backed chat file reads. Post-phase validation repaired token whitespace/JSON behavior, SQL error masking, descriptor revalidation, symlink-safe creation, chat listing resource handling, Spring constructor selection, and missing edge coverage.

## Files

- New workspace identity/domain files: `WorkspaceKind.java`, `WorkspaceDescriptor.java`, `WorkspaceResolutionException.java`
- New existence boundary: `ChatConversationExistenceRepository.java`
- Updated workspace layout and services: `WorkspacePathLayout.java`, `WorkspaceDirectoryService.java`, `WorkspaceService.java`
- Updated authoritative repositories: `AgentProfileRepository.java`, `ChatMemoryRepository.java`, `ChatSessionMetadataRepository.java`
- Updated chat file behavior: `ChatFileService.java`
- Added/updated focused identity, directory, existence, path-validation, chat-file, and constructor-caller tests
- Updated directly affected workspace/chat package guides

## Behavioral Impact

- Canonical kinds serialize as `agent`, `chat`, and `user`; parsing is case-normalized but rejects surrounding whitespace and non-kind aliases.
- Trusted roots are exactly `workspace/agent/<id>`, `workspace/chat/<id>`, and `workspace/user/<name>`.
- Agent/chat identity requires authoritative repository existence. Reads do not create missing roots; writes create only the missing root.
- User roots require explicit creation and direct non-symlink directory declaration.
- Resolution creates no starter content, `outputs/`, aliases, migration trees, or scaffolding. `outputs/` is created only by publication resolution.
- Chat file count/list/download uses the chat descriptor root, preserves bounded stable listing, and rejects traversal, backslashes, symlinks, non-files, and confinement failures.

## Specification Impact

Implements the Phase 02 foundation portions of `ARCH-20260719-01`, `SVC-20260719-01` in `services.md`, and `SVC-20260719-02` in `service-graph.md`. No broader Phase 03-08 contract was changed.

## Validation

- Focused required suite: 68 tests passed.
- Full suite: 941 tests passed, 0 failures, 0 errors, 0 skipped.
- Production compile: passed.
- Bounded Spring Boot startup: passed; `Magenta2Application` started in 3.308 seconds with the required cycle-free bean graph.
- Searches: the sole `new WorkspaceDescriptor(...)` invocation is in `WorkspaceDirectoryService`; the removed `WorkspaceDirectoryService(AiConfig)` shape is absent; new chat code has no legacy chat-root call.
- `git diff --check`: passed.

## Git Commit

Current HEAD at validation: `534b2c8c5627ab89a5e7c5f9a1a0fc6489ccaf5c` (Phase 01 baseline). The orchestration owner will create the Phase 02 commit after this validator PASS, per the phase gate ordering.

## Risks

- Legacy layout and workspace methods remain intentionally active for consumers owned by later phases; they are isolated from the new descriptor path and must be removed in their owning phases.
- Filesystem checks necessarily have narrow operating-system race windows, but creation and use paths verify components before and after creation and reject symlinks/collisions.

## Follow-up Items

- Commit the complete Phase 02 implementation and records after validator PASS.
- Phase 03 may begin only after that commit is recorded by orchestration.
