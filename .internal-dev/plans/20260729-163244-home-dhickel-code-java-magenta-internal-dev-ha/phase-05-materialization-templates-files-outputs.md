# Phase 05 — Materialization Templates, Files, and Outputs

## Context

Project materialization must preserve the exact historical context selected at binding time. A mutable generation row or a shared legacy root cannot do that safely. Project Files remains a single-member, read-only view over an exact ready generation; project Outputs is a project-scoped projection of canonical publications, never a replacement publication system.

## Goal

deliver immutable template generations, read-only Files, and durable-link Outputs without publication replacement

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectMaterializationTemplate.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectMaterializationTemplateService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectOutputService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentMaterializationRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentMaterializationService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java, src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectOutputController.java, src/main/resources/db/migrations/017-project-materialization-generations.sql, src/main/resources/schema.sql

This phase establishes append-only project template revisions and immutable materialization generations; creates and resolves only trusted V2 locations; exposes project Files as a revalidated read-only view; and exposes canonical outputs through one durable-link authorization resolver. It includes the focused implementation tests and migration coverage required to prove those contracts.

## Out of Scope

New workspace kinds or project roots; project file editing; merged member filesystems; starter or seed files; file/content/executable template directives; artifact retention changes; publication ownership changes; filesystem scans as output discovery; moving legacy bytes; project chat; and automation behavior.

## Dependencies

phase-04-project-jobs-and-reassignment.md

## Constraints

- Logical heads select immutable `(agent, key, generation)` rows. Template snapshot identity, source binding, root generation kind, and generation identity never change after creation. Lifecycle transitions use a separate lifecycle version and CAS; they cannot rewrite immutable facts.
- `GENERATION_V2` roots are outside every legacy materialization subtree. `WorkspacePathLayout` alone defines structural locations, and `WorkspaceDirectoryService` alone constructs trusted workspace descriptors/locations. Project attribution never creates a project workspace kind.
- Templates contain canonical directories-only snapshots. They reject blank, absolute, dot, traversal, backslash, duplicate, prefix-conflict, content/file, executable, and overwrite forms. Provisioning also rejects symlinks and symlink components; lexical validation is not a substitute for no-follow checks.
- Provisioning is create-only, no-follow, confined, and fully verified. A partial generation is never `READY`; failed lifecycle data is bounded and redacted. Reads and authorization never create a workspace directory.
- A historical binding resolves its persisted exact generation and never falls forward to a current head. `LEGACY_V1` is usable only when inspection proves exact historical resolution without moving bytes.
- Files authorization revalidates capability, project state, current member access, exact project source, ready generation, and confined read-only path resolution. It is a one-member view and never infers a member or workspace from a request path.
- Outputs remain owned and published by their existing canonical subsystem. List, metadata, preview, and download use the same durable-link resolver over persisted assignment, attempt/native-run, reassignment lineage where applicable, and publication links. They do not authorize from denormalized project text, membership history, filenames, or filesystem scans.

## Implementation Steps

All subphases A–F form one contiguous implementation sequence. Complete every subphase before running phase-level validation; no subphase has an independent validation or exit gate.

### A. Establish repository evidence and additive generation storage

1. Read the applicable package guides and required workspace/output knowledge. Inspect existing materialization repository/service, layout and directory services, publication model, assignment/attempt/native-run relations, migration runner, migrations `001`–`016`, `schema.sql`, and Phase 04 reassignment lineage.
2. Identify the materialization state writer, immutable source/binding fields, lifecycle CAS writer, legacy-row shape, publication owner, and durable publication-to-assignment/run/attempt lineage. Do not add a parallel materialization, output, or workspace authority.
3. Prove whether each legacy row maps to a stable historical location without moving bytes. If proof is absent or contradictory, do not manufacture `LEGACY_V1` rows, a synthetic root, or a fall-forward read; stop that import capability for contract escalation.
4. Add migration `017` and fresh-install schema definitions for logical heads, immutable generation rows, template revision/snapshot facts, lifecycle-versioned state, and only proven indexes/foreign keys. Preserve runner conventions, historical reachability, and constructor-DDL consistency.
5. Cover migration checksum, partial-structure rejection, repeated application, failure safety, and fresh/warm metadata equivalence. A proved legacy mapping may classify a row as `LEGACY_V1`; no migration moves directories or rewrites a legacy root as V2.

### B. Implement immutable directories-only template revisions and generation allocation

1. Define template/service APIs around immutable revision snapshots, not a mutable directory list. Create or edit normalizes the full set, derives canonical ordered representation and digest, and creates a new revision/head selection rather than changing an in-use revision.
2. Validate before persistence: reject every prohibited form in Constraints, including duplicate normalized values and ancestor/descendant prefix conflicts. Semantically identical accepted input must produce the same ordered snapshot and digest.

   ```text
   normalized = validateEveryRelativeDirectory(input)
   rejectDuplicatesAndPrefixConflicts(normalized)
   snapshot = canonicalOrder(normalized)
   digest = digest(snapshot)
   ```

3. Snapshot selected revision/digest and immutable source context at allocation. Allocation creates a new immutable `PROVISIONING` generation under its logical head; it never mutates a prior generation to represent new content or binding.
4. Use expected lifecycle version only for lifecycle changes. Do not expose generic save/whole-row updates. Repeated allocation/provisioning requests must converge under the established operation/idempotency contract rather than producing ambiguous ready generations.

### C. Provision and resolve trusted V2 generations

1. Add V2 layout factories to `WorkspacePathLayout` with roots outside legacy roots. `WorkspaceDirectoryService` receives trusted identifiers/bindings and returns the authoritative location/descriptor; callers never concatenate roots or rebuild identity from `Path`.
2. From `PROVISIONING`, apply exactly the immutable snapshot. Create directories only below the trusted V2 location with create-only/no-follow operations; reject symlinks and symlink components and continuously verify root confinement.
3. Verify all expected directories and confinement before CAS to `READY`. On any failure retain immutable facts, record only bounded safe failure information, and CAS to `FAILED`; never label a partial tree ready.
4. Resolve stored bindings by exact generation id and kind, never by current head or current template. Missing physical directories, failed generations, and legacy mappings must not create directories during resolution.

### D. Preserve the exact-generation, read-only Files view

1. Trace the existing Files route through `ProjectConsoleController` and materialization/workspace services. Before UI edits, inspect applicable SimplyPages documentation and matching demos; if unavailable, stop the UI edit rather than substituting raw markup or broad JavaScript.
2. Route Files lookup through a server-owned resolver checking project-operation capability, active/non-archived project, authorized current member, exact project source, exact `READY` generation, and trusted recomputed location. Request data cannot supply authoritative project/member/generation/root identity.
3. Preserve one selected member/source view; do not merge trees or infer a fallback. Resolve relative read paths through established no-follow/real-path confinement, reject traversal and symlink escape, and provide no mutation, upload, delete, rename, or create operation.
4. Return cross-project, cross-member, unavailable-generation, and confined-path failures without filesystem detail leakage. Limit names and errors to the authorized Files disclosure policy.

### E. Add the durable-link project Outputs projection

1. Implement `ProjectOutputService` as the sole project-output authorization/query boundary. Starting from canonical publication identity, prove the durable relation to scoped project through persisted assignment attribution and applicable task-attempt/native-run/reassignment-lineage links. Do not copy, re-publish, or replace the original publication.
2. Use one resolver and redaction policy for list membership, metadata, preview, and download. Controllers may render or stream an authorized publication but cannot reimplement a looser scope test.

   ```text
   authorizedOutput(projectScope, publicationId):
       publication = canonicalPublication(publicationId)
       require durableProjectLineage(projectScope.projectId, publication)
       return redactForProjectView(publication)
   ```

3. Scope list queries by durable relations from the start, with bounded ordering/pagination consistent with project-console conventions. Never scan workspace/output directories. Treat absent Task origin as absent; valid Job-originated output must not require invented Task provenance.
4. Preserve reassignment predecessor ownership: predecessor artifacts stay attached to predecessor publication/run relations. A successor or current preferred executor cannot authorize unrelated predecessor artifacts.
5. Add `ProjectOutputController` list, detail/metadata, preview, and download routes that invoke the common service. Map cross-scope/non-existent output identity to the established non-disclosing absence response; gate and project/member failures remain fail-closed capability decisions.

### F. Render constrained HTMX surfaces and prepare complete phase proof

1. In `ProjectConsoleController`, compose authorized redacted template/Files state with reusable SimplyPages components. Add template create/edit as standard HTMX form flows with stable isolated hosts and server-side validation feedback. Do not add JavaScript, generic artifact links, raw-markup workarounds, or page-local authorization.
2. Render Outputs through `ProjectOutputController` and existing console composition patterns. Use opaque scoped links; preserve host/OOB ownership; make disabled, empty, failed-generation, unavailable, and cross-scope states non-disclosing and usable.
3. Build focused fixtures for immutable revision/head/generation behavior, canonical publications, assignment/run/attempt/reassignment links, and V2 plus proved legacy locations. Exercise trusted service/controller seams rather than mocking away confinement or durable-link checks.
4. Review the whole diff for whole-row writes, mutable generation facts, direct path construction, read-created directories, inferred project identity, output scans, duplicate authorization paths, sensitive DTO fields, placeholders, unjustified JavaScript, and unrelated edits.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/knowledge/filesystem-first-workspaces.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md`

Read only task-relevant internal-development records when the store is available; do not initialize or repair a store implicitly.

## Technical Guidance

`MaterializationLocation` remains package-controlled and directory-service-owned. Keep lifecycle writes narrow and separate from immutable facts. Reuse existing canonical publication storage and streaming behavior after authorization; this phase adds a safe project projection only. Standard controls remain HTMX; no new JavaScript transport is justified.

## Validation

Run this complete validation only after A–F are complete. The orchestration-designated post-phase validator must review and repair the completed phase and pass before Phase 06 can start.

1. **Template/generation:** prove canonical normalization/digest, rejection of all invalid forms, immutable revisions/snapshot/source facts, lifecycle CAS, idempotent allocation, and exact historical resolution without fall-forward.
2. **Filesystem/materialization:** prove V2/legacy isolation; create-only/no-follow confinement; symlink and symlink-component rejection; full verification before `READY`; partial failure never ready; bounded failure data; no-read-creates-directory; missing/failed/revoked/empty Files states; exact member selection; and read-only traversal/real-path confinement.
3. **Migration:** run `017` fresh-install, supported-upgrade, checksum, partial-structure, failure/retry, foreign-key/index, constructor-DDL, and metadata-equivalence coverage. Exercise `LEGACY_V1` only with a proved byte-preserving mapping and verify no migration moves bytes.
4. **Outputs:** prove identical list/metadata/preview/download decisions; reject cross-project, cross-member, guessed-publication, unrelated assignment/run/attempt, and reassignment-lineage escapes; preserve predecessor artifacts; accept valid Job-originated output without Task provenance; and prove no route scans or replaces publication ownership.
5. **Web:** with the application running, have the required Playwright subagent validate template, Files, and Outputs flows with enabled/disabled capability, archived/member-loss, empty, failed, missing, cross-scope, deep-link, and error states. Capture desktop/mobile screenshots and critique alignment, density, hierarchy, wrapping, overflow, keyboard/focus/ARIA behavior, HTMX host isolation, and first-viewport usefulness. Confirm no new JavaScript transport.
6. **Broader checks:** run relevant Maven tests/static checks and bounded Spring Boot startup with capability disabled and enabled where local configuration permits. Inspect final diffs and distinguish proven pre-existing failures from phase-caused failures.

## Exit Criteria

- Logical heads and immutable generations preserve exact historical template/source bindings; lifecycle CAS never changes generation identity; V2 roots remain outside legacy roots; legacy generations exist only with a proved byte-preserving mapping.
- Invalid, symlinked, escaped, or partial templates/generations never reach `READY`; provisioning is confined/create-only/no-follow/fully verified; and no read creates a directory.
- Project Files is an authorized, single-member, exact-ready-generation, confined read-only view with no fallback or project workspace authority.
- Project Outputs uses the common durable-link resolver for every representation, has no scan or denormalized-project authorization path, and leaves canonical publication ownership and predecessor artifacts intact.
- Migration and behavior coverage, bounded startup, live browser validation and visual critique, final diff review, and the orchestration validator all pass. Documentation integration is handed to Phase 08 after this passed contract is available.
