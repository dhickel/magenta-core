# Phase 04 — Cut Over Explorer and API

## Context

Phase 03 leaves reusable explorer behavior under workspace-neutral ownership so Avatar can be removed without losing file-management capability. This phase completes that cutover: it removes the remaining Work Area adapter and routes, changes explorer calls to trusted `WorkspaceDescriptor` inputs, and replaces generic-workspace/Work-Area metadata and action persistence with canonical workspace identity.

The old annotation and action tables cannot serve the new contract because their keys, foreign keys, and repository bootstrap DDL depend on generic workspace, owner, or Work Area identity. Existing rows have no reliable canonical mapping and remain inert.

## Goal

Preserve the complete explorer operation set for agent, chat, and declared user workspaces through one descriptor-based service, canonical annotation/action persistence, user workspace collection operations, and the typed `/api/workspace/{type}/{id}` API family.

## In Scope

Delete the remaining Work Area explorer/API ownership:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkAreaExplorerService.java`, if Phase 03 retained it as a temporary adapter
- `src/main/java/io/mindspice/magenta2/api/web/WorkAreaController.java`

Create or update production files required by the cutover:

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceExplorerService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileException.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileMetadataRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileMetadataService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileActionLogRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileActionRecord.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileActionType.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileLabelAssignment.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceFileLabel.java` only where its active label/tag contract requires adjustment
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceController.java`
- `src/main/resources/schema.sql`

Replace or update focused tests:

- replace `WorkAreaExplorerServiceTest` with `WorkspaceExplorerServiceTest`
- replace `WorkAreaControllerTest` with `WorkspaceControllerTest`
- update `WorkspaceFileMetadataRepositoryTest`
- update `WorkspaceFileMetadataServiceTest`
- update `WorkspaceFileActionLogRepositoryTest`
- update `PublicApiRouteBindingTest`

## Out of Scope

- Agent Files tab fragments, CSS composition, and browser mounting.
- Execution selection, runtime/tool binding, and output publication.
- Removal of Work Area or generic workspace persistence still referenced by execution code.
- User workspace root rename or deletion.
- Migration or translation of historical annotations or actions.
- Compatibility routes, generalized workspace providers, sharing, permissions, quotas, or project browsing.

## Dependencies

- `phase-03-remove-avatar-product.md` must PASS.
- Its required postcondition is a compilable `WorkspaceExplorerService` containing the reusable explorer behavior. Phase 04 may remove a temporary Work Area adapter but must not recreate Avatar-owned fragments or repeat the Phase 03 extraction.
- It consumes `WorkspaceKind`, `WorkspaceDescriptor`, `WorkspaceDirectoryService`, `WorkspacePathLayout`, entity-existence checks, and explicit user-workspace lifecycle behavior from Phase 02.

## Constraints

- Only `WorkspaceDirectoryService` constructs descriptors. Controllers accept type and key path segments, resolve one descriptor per request, and never accept or return a root or client-supplied canonical identity.
- Public explorer methods accept a `WorkspaceDescriptor` and file-relative inputs. They do not accept `WorkArea`, `workAreaId`, generic `workspaceId`, owner type/id, project id, or raw root.
- Canonical identity and normalized file-relative path remain separate values. Normalize each file-relative input once at the explorer service boundary to forward-slash form before filesystem or repository use.
- A missing agent/chat physical root is a valid empty workspace: root list and root inspection succeed without creating it. Nested reads and operations requiring existing targets return not found. A missing user root is always not found except for the explicit create operation.
- Validation failures, collisions, and failed existing-target operations do not create roots. Successful create-directory, create-text, and create-Markdown operations may lazily create an agent/chat root, but may not create unrequested parent directories. Save edits only an existing regular file.
- Root rename, move, copy, and delete are forbidden.
- `outputs` is reserved. Reads, preview, image view, download, labels, and action inspection may address it. Explorer operations must reject every destination in `outputs/` and must reject save, rename, move, or delete when the source is `outputs/` or a descendant. Copying out may read a confined non-symlink source beneath `outputs/`, but its destination must be outside the reserved subtree.
- Existing targets must pass lexical confinement, component-level `NOFOLLOW_LINKS` checks, and real-path confinement beneath the descriptor root. Missing targets require a confined, existing, non-symlink parent. Recursive copy/delete rejects every symbolic entry rather than following or silently traversing it.
- Preserve text, Markdown, image, preview, download, UTF-8, collision, recursive-delete confirmation, label/tag, recent-action, and safe-rendering limits and semantics. Do not expose absolute paths in exceptions, logs returned to clients, DTOs, headers, or bodies.
- Existing Work Area/generic-workspace annotation and action tables and rows remain untouched. Active repositories neither query nor mutate them and contain no bootstrap DDL that recreates them for a fresh database.

## Implementation Steps

1. **Introduce the explorer error boundary.** Implement `WorkspaceFileException` with a small reason enum for invalid request/policy, not found, and conflict. Preserve causes for server logging, but use path-free client messages. Map invalid type/key/path, traversal, symlink, root mutation, reserved-output mutation, unsupported type, invalid UTF-8, limit, and confirmation failures to `400`; missing entity/root/target to `404`; and destination/name collisions to `409`. Unexpected IO is not converted into a misleading domain result.

2. **Complete the neutral service cutover.** Update the `WorkspaceExplorerService` produced by Phase 03 so every public operation accepts one `WorkspaceDescriptor` plus file-relative input. Remove `WorkAreaExplorerService`, owner-root overloads, nested mark/unmark operations, Home/system checks, marked-descendant checks, active Work Area reference checks, compatibility constructors, and optional metadata/action dependencies. Required Spring dependencies must be explicit.

3. **Preserve the full operation and response model.** Retain list, inspect, preview, image source, bounded download, save text, create directory, create text, create Markdown, rename, move, copy, delete preflight/execute, labels, tags, and recent actions. Replace `DirectoryListing.workArea()` and Work Area fields in entries with a path-free workspace summary containing only kind, key, and canonical identity where needed. No API-facing record may contain `Path` or a descriptor root.

4. **Centralize path setup and operation policy.** Resolve the descriptor once in the controller and normalize relative paths once in the service. Implement shared read/existing-target/new-target helpers that enforce the invariants above. Validate source and destination policy before IO; create a lazy agent/chat root only as part of a valid create operation. Preserve root-empty behavior, exact parent requirements, collision checks, recursive symbolic-entry rejection, and the reserved-output source/destination distinction.

5. **Cut metadata to canonical annotations.** Retain `workspace_file_labels` as the global label/tag definition table, but stop bootstrapping or actively using Work Area-specific system labels. Replace active assignment persistence with:

```text
workspace_file_annotations(
  id, workspace_identity, file_relative_path, label_id,
  metadata_json, created_at, updated_at,
  unique(workspace_identity, file_relative_path, label_id)
)
```

Change `WorkspaceFileLabelAssignment` and repository/service signatures to canonical identity plus normalized file-relative path; remove generic workspace, owner, root-relative, and Work Area fields. Move, copy, and delete metadata by canonical subtree. Escape `%`, `_`, and the SQL escape character in prefix predicates and use an explicit `ESCAPE` clause. Keep label/tag creation and assignment behavior, metadata JSON, timestamps, and deterministic ordering.

6. **Cut action logging to canonical identity.** Replace active action persistence with:

```text
workspace_file_action_log(
  id, workspace_identity, actor_type, actor_id, action_type,
  source_relative_path, target_relative_path,
  result, payload_json, created_at
)
```

Remove generic workspace, owner, and Work Area fields from `WorkspaceFileActionRecord`, repository methods, indexes, and active action types. Record and query actions by descriptor canonical identity and normalized paths. Preserve actor/result/payload attribution and the bounded recent-action order.

7. **Make fresh and warm schema behavior explicit.** In `schema.sql`, declare `workspace_file_labels`, `workspace_file_annotations`, `workspace_file_action_log`, their foreign key from annotation to label, and indexes for canonical path/subtree and canonical recent-action queries. Remove old annotation/action table declarations from the fresh schema. Update or remove repository `ensureSchema()` methods so they create only the new active tables and never create, alter, drop, query, or write obsolete tables. A warm database retains old tables and rows unchanged; no migration or backfill runs.

8. **Repurpose the controller to the typed API.** Change `WorkspaceController` to `@RequestMapping("/api/workspace")`, remove generic metadata/link/lease handlers, and delete `WorkAreaController`. Add path-free user collection operations `GET /user` and `POST /user` using the Phase 02 declaration lifecycle. Under `/{type}/{id}`, preserve operation-specific methods and payload semantics for:

   - `GET /files`, `/files/inspect`, `/files/preview`, `/files/view`, and `/files/download`
   - `PUT /files/text`
   - `POST /directories`, `/files/text`, `/files/markdown`, `/files/rename`, `/files/move`, and `/files/copy`
   - `GET /files/delete/preflight` and `POST /files/delete`
   - `GET|POST|DELETE /files/labels`, `POST /tags`, and `GET /files/actions/recent`

   Do not retain the old direct recursive-delete compatibility handler or any `/api/work-areas` or `/api/workspaces` binding. User summaries contain type, key/name, and canonical identity only; duplicate creation is a conflict, and listing returns only valid confined direct non-symlink directories.

9. **Validate identity at the web boundary.** Parse `type` through the closed `WorkspaceKind` enum. Before descriptor resolution, reject case-insensitive encoded separators and encoded traversal/dot-segment syntax in the identity route segments, including forms that remain percent-encoded after one decode. Validate the decoded key with `PlainPathSegmentValidator`. Reject missing/extra identity alternatives, raw roots, and canonical identity fields. Resolve the descriptor once, pass it unchanged to the explorer, and map only `WorkspaceFileException` reasons to controlled `400`, `404`, or `409` responses.

10. **Preserve bounded binary responses and disclosure safety.** Resolve a fresh confined file resource for each image/download request. Keep image media-type allowlisting, inline versus attachment disposition, file-name handling, content length, and existing size bounds. Handle disappearance or replacement races without following symlinks or returning host paths. Keep controllers thin; confinement, limits, and file eligibility remain service policy.

11. **Rewrite focused tests around the completed contract.** Cover all three workspace kinds and every preserved operation. Include missing/existing entities, virtual empty roots, explicit user create/list, invalid direct-child filtering, unsuccessful-operation no-creation, exact-parent creation, collisions, invalid type/key/path, encoded separators/traversal, absolute/traversal paths, symlink components and escape, symbolic recursive trees, root mutation, every reserved-output source/destination case, invalid UTF-8, text/Markdown/image/download limits, safe Markdown behavior, labels/tags, escaped subtree metadata paths, canonical action rows, bounded recent actions, path-free responses, status mappings, and absence of old routes and dependencies.

## Required Guides

- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/knowledge/workspace-file-explorer-details-list-rewrite.md`
- `.internal-dev/knowledge/plain-path-segment-id-validation.md`
- `.internal-dev/knowledge/htmx-fragment-error-statuses.md`

Treat the Phase 01 superseding specifications as intended truth where the currently stale package guides still describe Work Areas or generic filesystem workspaces.

## Technical Guidance

Keep the service flow direct:

```text
parse type/key -> resolve descriptor once -> normalize relative input once
-> enforce operation policy -> perform confined IO -> update canonical metadata/log
```

Use exact equality plus an escaped descendant-prefix predicate for subtree metadata operations; never use an unescaped `LIKE normalized + '/%'`. Perform filesystem success before committing corresponding metadata path changes and action rows, and keep those database changes in one transaction. Do not claim filesystem/database atomicity; tests must prove that ordinary successful move/copy/delete operations leave filesystem, annotations, and action records aligned.

For absent agent/chat roots, only root list and root inspection return the virtual empty directory. All nested reads are not found. A create operation may create the root and the explicitly requested direct target; save, rename, move, copy-source, and delete still require their source to exist.

## Validation

- Run the six focused classes: `WorkspaceExplorerServiceTest`, `WorkspaceFileMetadataRepositoryTest`, `WorkspaceFileMetadataServiceTest`, `WorkspaceFileActionLogRepositoryTest`, `WorkspaceControllerTest`, and `PublicApiRouteBindingTest`.
- Run `mvn test`.
- Run a bounded Spring Boot application-context startup and confirm the controller/service/repository constructor graph is valid without `WorkAreaController` or `WorkAreaExplorerService`.
- Initialize a fresh SQLite database and assert the new label, annotation, action, and index contracts exist without old annotation/action tables or foreign keys to generic workspace identity.
- Initialize a warm fixture containing obsolete Work Area/generic-workspace annotations and actions; execute new operations and prove old schemas and rows are unchanged while new rows use canonical identity.
- Enumerate route bindings and prove `/api/workspace/user` and the typed operation family are present while `/api/work-areas` and `/api/workspaces` are absent.
- Search Phase 04-owned production and test files for `WorkArea`, `workAreaId`, generic filesystem `workspaceId`, owner-root methods, raw-root parameters, old table names, obsolete route families, compatibility constructors, and client canonical-identity inputs. Classify only fixture SQL that intentionally proves inert warm-schema behavior as allowed.
- Review the final diff for scope, constructor wiring, accidental API growth, compatibility stubs, path disclosure, placeholders, and unrelated edits.
- Run the post-phase validator review-and-repair gate with the orchestration-assigned validation model. The phase is complete only on PASS.

## Exit Criteria

- Every preserved explorer operation works through one resolved descriptor for agent, chat, and declared user workspaces.
- Lazy root creation, symlink confinement, recursive-tree rejection, root immutability, and reserved `outputs/` behavior match the stated invariants.
- Active annotations and actions use only canonical workspace identity plus normalized relative paths; records, repositories, and indexes contain no Work Area, owner-root, or generic workspace identity.
- Fresh schema declares only the new active explorer persistence contract, while warm obsolete tables and rows remain unchanged and unread by active repositories.
- `GET|POST /api/workspace/user` and the complete `/api/workspace/{type}/{id}` operation family expose path-free responses with controlled `400`, `404`, and `409` behavior.
- No active Phase 04 explorer/API code or tests reference `WorkArea`, raw roots, `/api/work-areas`, `/api/workspaces`, or the removed compatibility delete route.
- Focused tests, full tests, application startup, schema checks, route enumeration, stale-reference searches, and final diff review pass.
- The post-phase validator reports PASS; only then may the phase commit be finalized.
