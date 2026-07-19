# Phase 02 — Build Workspace Identity Foundation

## Context

Phase 01 has locked the superseding contract and consumer inventory. Current code still exposes legacy agent, project, chat-files, Work Area, run, output, and migration paths. This phase adds the trusted three-kind foundation while retaining only legacy methods needed by consumers owned by later phases.

## Goal

Establish closed workspace identity, cycle-free existence checks, trusted descriptors, exact root factories, lazy agent/chat behavior, explicit user lifecycle, and chat-file cutover without an unchecked constructor, duplicate descriptor path, starter generation, or eager children.

## In Scope

Production:

- `WorkspaceKind.java` and `WorkspaceDescriptor.java` (new), `WorkspacePathLayout.java`, `WorkspaceDirectoryService.java`, and constructor-only adaptation in `WorkspaceService.java` under `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/`.
- `AgentProfileRepository.java`.
- `ChatConversationExistenceRepository.java` (new), `ChatMemoryRepository.java`, `ChatSessionMetadataRepository.java`, and `ChatFileService.java`.

Tests:

- New `WorkspaceIdentityTest`, `WorkspaceDirectoryServiceTest`, `WorkspaceDirectoryServiceTestSupport`, and `ChatConversationExistenceRepositoryTest`.
- Existing `WorkspacePathSegmentValidationTest` and `ChatFileServiceTest`.
- Constructor-only adaptations at every production/test call site that directly instantiates `WorkspaceDirectoryService`; preserve later-phase behavior.
- Directly affected package guides and required Phase 02 plan/changelog records only.

## Out of Scope

- Explorer features beyond confinement required here; typed HTTP routes/status mapping; metadata/actions; execution, tools, outputs, project behavior, UI, schema removal, or Avatar removal.
- Removing legacy layout methods before their consumers move.
- Migration, import, aliasing, deletion, or scanning of legacy trees.
- Generated `AGENTS.md`, compatibility directories, eager `outputs/`, or generalized workspace providers.

## Dependencies

- `phase-01-lock-superseding-contracts.md` has validator PASS, including the active-consumer inventory.
- Phase 03 does not start until this phase has validator PASS.

## Constraints

- Exactly one implementation agent owns the phase; preserve the sequential orchestration wave and post-phase review-and-repair gate.
- Keep constructor and chat-root transitions compilable.
- Do not add, rename, or edit another phase file.
- Do not add compatibility endpoints, unchecked constructors, optional dependency paths, migration behavior, or filesystem identity persistence.

## Invariants

- Kinds are exactly `AGENT`, `CHAT`, and `USER`; tokens are `agent`, `chat`, and `user`.
- Keys are decoded plain segments validated before layout composition. Identity is exactly `<token>/<key>` and is never client-supplied.
- Only `WorkspaceDirectoryService` constructs descriptors. Roots are absolute, normalized, and confined beneath the real data root.
- Agent/chat identity requires repository existence; directory presence is not identity.
- A user workspace is declared only by a valid direct non-symlink directory under `workspace/user/` created explicitly.
- Missing valid agent/chat roots read empty. Missing user roots are not found. Writes create only agent/chat roots; user roots are never implicit.
- Resolution creates no starter file, `outputs`, or scaffolding. Only the publication operation creates `outputs`.
- Reject traversal, symlink components/roots, non-directory roots, and escape.
- New descriptor/chat code never calls retained legacy factories.

## Implementation Steps

1. Add `WorkspaceKind` with the exact values and lower-case tokens. Serialize tokens; parse case-normalized with `Locale.ROOT`, rejecting blanks, whitespace, aliases, owner types, and projects. Add Jackson annotations only if this boundary uses Jackson.

2. Add `WorkspaceDescriptor` as an immutable final value class with package-controlled construction, read-only accessors, and value equality. Store non-null kind, validated key, derived identity, and normalized absolute root. Do not expose a `Path` factory. Gate construction with a source search proving only `WorkspaceDirectoryService` invokes it.

3. Add these validated data-root-relative factories while leaving legacy factories behaviorally unchanged:

```text
agentRoot(id) -> workspace/agent/<id>
chatRoot(id)  -> workspace/chat/<id>
userRoot(name)-> workspace/user/<name>
```

Use `PlainPathSegmentValidator.requirePlainSegment`. Phase 06 removes legacy factories and constants.

4. Add low-level existence boundaries. Implement `AgentProfileRepository.existsById`, plus indexed `existsByConversationId` methods in chat memory and session metadata repositories. Compose them in `ChatConversationExistenceRepository`; also probe `plan_definitions.conversation_id` only after confirming that table exists. Missing optional storage is no match; do not create schema/rows or swallow unrelated SQL errors. Blank ids return false.

5. Replace the directory-service graph. Its Spring constructor requires `AiConfig`, `AgentProfileRepository`, and `ChatConversationExistenceRepository`; remove the `AiConfig`-only constructor. Do not use optional providers, field injection, always-true production lookups, or a partially valid instance. Add test-only explicit collaborators and mechanically adapt all direct constructors without changing later-phase behavior. Preserve this acyclic graph:

```text
WorkspaceDirectoryService -> AgentProfileRepository
WorkspaceDirectoryService -> ChatConversationExistenceRepository
ChatFileService            -> WorkspaceDirectoryService
```

6. Add service operations:
   - `resolveForRead(kind,key)`: validate entity/declaration and return a descriptor without creating an agent/chat root; missing user is `NOT_FOUND`.
   - `resolveForWrite(kind,key)`: same validation; create only a missing agent/chat root and require an existing user root.
   - `createUserWorkspace(name)`: create structural parents and exactly one root with `Files.createDirectory`; existing root or lost race is `CONFLICT`.
   - `listUserWorkspaces()`: absent parent returns immutable empty; return sorted descriptors only for valid direct non-symlink directories, ignoring malformed names, files, symlinks, and nested entries.
   - `outputs(descriptor)`: publication-only; revalidate, lazily create an allowed agent/chat root, require user declaration, then create only `<root>/outputs`.
   - Keep `dataRoot()` unchanged. Represent `NOT_FOUND` and `CONFLICT` as transport-neutral domain reasons for Phase 04 mapping.

7. Harden confinement. Resolve structural paths only through `WorkspacePathLayout`. For absent roots, verify lexical confinement and every existing ancestor with `NOFOLLOW_LINKS`. Existing roots must be non-symlink directories whose real paths remain under the real data root. Verify before and after creation. Reject invalid parents, collisions, and races as controlled failures. Add only the descriptor-relative helper needed by chat files: nested relative paths are allowed, but blank item paths, absolute paths, traversal, backslash escape, symlink components/targets, and real-path escape are rejected. Never permit root mutation or mix relative paths with identity fields.

8. Refactor `ChatFileService` to resolve `CHAT` once and use `descriptor.root()`, not `chats/<id>/files`. Count/list return empty only when an authoritative chat root is physically absent and do not create it. Do not catch all runtime/I/O failures as zero. Preserve non-symbolic recursion, the 500-item bound, stable ordering, metadata, and regular-file filtering. Downloads require an existing confined non-symlink regular file. There is no current writer; a later writer must use `resolveForWrite`.

9. Test exact kinds/tokens/identities, inaccessible untrusted construction, layouts, all plain-segment invalid classes, every chat existence source, absent optional plan table, missing entities, lazy reads, write creation, explicit user creation/collision/listing, invalid direct children, symlink/non-directory/race cases, confinement, and no starter/eager children. Test `outputs` creates only root plus reserved child. Test chat absent-root behavior, missing conversation, listing/count limits, downloads, traversal, symlinks, non-files, and real I/O errors. Rewrite owned starter/old-chat assertions; elsewhere change constructor fixtures only.

10. Update only directly affected package guides and required phase plan/changelog records, including specification impact and phase commit hash. Do not perform Phase 08’s broad reconciliation or alter the phase file set.

## Required Guides

- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/repository/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md`
- `.internal-dev/knowledge/plain-path-segment-id-validation.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- Relevant corrected Phase 01 specifications, decisions, and inventory

## Technical Guidance

A valid descriptor may reference an absent agent/chat root; validity and physical existence are separate. Return an empty service result without creating a temporary directory. Use `Files.createDirectory` for the final user root so collision is observable. Scan existing components without following links, then use `toRealPath` only for paths that exist. Keep old and new factories visibly separate; do not implement new roots through renamed legacy helpers.

A public record would expose descriptor construction. Prefer a final class with a package-controlled constructor and enforce the sole-constructor rule by source search. Tests must supply explicit authoritative lookups rather than bypassing validation.

## Validation

1. Run the five focused test classes named in scope.
2. Run compile-focused tests for adapted constructor callers and search for the removed `WorkspaceDirectoryService(AiConfig)` shape.
3. Run `mvn test`.
4. Run bounded Spring startup; verify one cycle-free directory-service bean with required repositories.
5. Inspect isolated focused-test roots for side effects; do not use a shared root after unrelated legacy tests.
6. Search owned code for old chat paths, hand-built new roots, descriptor construction elsewhere, starter generation on new roots, and new legacy-factory calls.
7. Review the diff for optional/unchecked dependencies, transport exceptions, placeholders, unrelated behavior, and other phase edits.
8. Run the orchestration-assigned post-phase validator review-and-repair gate and commit only after PASS.

## Exit Criteria

- Exact kinds, tokens, identities, and roots are proven.
- Only `WorkspaceDirectoryService` constructs trusted descriptors.
- Agent/chat checks are authoritative, side-effect-free, repository-level, and cycle-free.
- Lazy agent/chat and explicit user lifecycle semantics pass, including confinement and filesystem edge cases.
- No new resolution creates starter content, `outputs`, scaffolding, aliases, migration trees, or legacy subtrees.
- Chat files use `workspace/chat/<chatId>` without masking missing identity or confinement failures.
- All direct callers compile through validated collaborators; no unchecked constructor or duplicate descriptor path remains.
- Focused tests, broad tests, startup, isolated filesystem assertions, searches, diff review, records, commit, and validator gate all pass before Phase 03.
