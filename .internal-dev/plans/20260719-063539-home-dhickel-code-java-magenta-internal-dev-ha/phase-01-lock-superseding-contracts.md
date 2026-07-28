# Phase 01 — Lock Superseding Contracts

## Context

The living specifications still endorse Avatar, DB-backed Work Areas, project-owned roots, run staging, aliases, and generic workspace identity. The corrected shared concepts supersede those contracts. Implementation must not proceed while intended truth is contradictory or active consumers lack a later-phase owner.

## Goal

Make the living architecture, service, API, web, SimplyPages, and decision records consistently describe the closed three-kind filesystem-first model and accepted removals. Record a concrete active-consumer inventory and the evidence-backed disposition of generic workspace persistence so phases 02–08 have complete cutover ownership.

## In Scope

Exact edit targets:

- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/specifications/decisions.md`

Targeted repository inspection is required to establish the inventory, but it does not authorize edits outside these seven files.

## Out of Scope

- Production code, schema, tests, current product docs, knowledge, changelog, or other phase-file edits.
- Preserving Avatar or Work Area behavior as deprecated functionality.
- Implementing migrations, compatibility routes, overloads, aliases, or transitional identity paths.
- Designing workspace kinds beyond agent, chat, and user.
- Root rename/delete, sharing, permissions, quotas, synchronization, project browsing, or an Avatar replacement.

## Dependencies

None.

## Constraints

- Runs as wave 01 with exactly one implementation agent owning all ordered edits.
- No dependent phase starts until the medium post-phase validator review-and-repair gate reports PASS.
- Use the dedicated implementation branch required by the shared plan. Commit this phase only after PASS, without changing the eight-file plan set.
- Preserve decision history by superseding entries rather than erasing them.
- Keep each normative contract in its owning specification and link rather than duplicate it.
- Use `task` for user-facing executable work while preserving unrelated existing terminology.
- Do not add speculative migration, permissions, sharing, project browsing, root lifecycle, generalized provider, or Avatar-replacement behavior.

## Invariants

- `WorkspaceKind` is closed to `AGENT`, `CHAT`, and `USER`, serialized as `agent`, `chat`, and `user`.
- Canonical identities are exactly `agent/<id>`, `chat/<id>`, and `user/<name>` and are derived internally.
- Only `WorkspaceDirectoryService` constructs `WorkspaceDescriptor`; controllers accept decoded type/key path segments, never roots, canonical identities, or arbitrary paths.
- Descriptor construction validates keys with `PlainPathSegmentValidator`, checks agent/chat existence through low-level repositories without service cycles, and enforces explicit user-workspace declaration.
- `WorkspacePathLayout` is the sole structural path authority. Active roots are only `workspace/agent/<id>`, `workspace/chat/<id>`, and `workspace/user/<name>`; retained structural names are `WORKSPACE` and `OUTPUTS`.
- Missing physical agent/chat roots read as empty and are created only by successful file-producing or execution operations. User roots exist only after explicit creation; missing user roots are `404` for read, mutation, and selection.
- Execution requests carry `workspaceType` and `workspaceKey` together. Omitting both selects the bound agent; one alone is invalid; explicit agent selection must match the bound agent; an existing user workspace may override the default; chat selection is rejected.
- Project association remains metadata and membership context. It never selects or creates a root, link, or filesystem lease. Assignment queue coordination fields remain independent.
- `outputs/` is a reserved, lazy publication subtree. Explorer may read it but may not mutate it. Publication accepts confined real source files from the effective descriptor root and collision-safe normalized relative names without a run-owned directory.
- Fresh schema contains only active type/key and canonical attribution. Existing obsolete schema and user-owned files remain untouched and inert; active repositories neither depend on nor mutate obsolete identity structures.
- Output-artifact persistence becomes independently owned by `OutputArtifactRepository` before mixed-purpose generic workspace persistence is removed.
- File-relative paths remain separate from workspace identity and are normalized once beneath the trusted descriptor root.

## Implementation Steps

1. Inspect targeted production, resource, schema, test, and current-documentation references for Avatar, Work Area, generic workspace, explorer, execution, output, and filesystem identity consumers. Determine whether generic workspace persistence has any demonstrated non-Work-Area responsibility and identify mixed output-artifact ownership. Then update `.internal-dev/specifications/decisions.md` with one active superseding decision recording:
   - the closed workspace kinds, exact canonical identities, and sole descriptor-construction boundary;
   - low-level agent/chat existence checks, explicit user declaration, and absence of service cycles;
   - deletion of Avatar and DB-backed Work Areas;
   - deletion of project roots, materialized links, generic filesystem identity/link/lease behavior, run staging, aliases, migration helpers, starter files, and obsolete roots;
   - paired execution selection, bound-agent fallback, explicit-selection rules, and direct reserved `outputs/` publication;
   - preservation of project metadata/membership/events, assignment queue coordination, and durable output attribution;
   - non-destructive treatment of legacy files and existing obsolete schema, with no migration, import, obsolete-identity backfill, or guessed root;
   - an evidence-backed disposition for every generic workspace persistence component: retire it if no unrelated responsibility is demonstrated; otherwise name that responsibility and prohibit filesystem identity, root, link, or lease behavior;
   - extraction of all output-artifact schema, queries, writes, deletion behavior, and attribution into `OutputArtifactRepository` before mixed-purpose persistence is deleted.
   Move applicable Avatar, Work Area, project-root, run-staging, alias, migration, and generic-filesystem decisions to the superseded table using the new decision as `superseded_by`. Preserve identifiers, rationale, and historical text.

2. Replace active Avatar/Work Area architecture entries in `architecture.md` with one filesystem-first entry. Define `WorkspaceKind`, `WorkspaceDescriptor`, `WorkspaceDirectoryService`, `WorkspacePathLayout`, the three exact roots, lifecycle rules, confinement boundary, reserved `outputs/`, fresh/existing schema behavior, and removal boundaries. State that project association is metadata-only, assignment queue leases are not filesystem leases, and ambiguous legacy execution rows remain historical rather than resumed through guessed paths. Close the old workspace drift item as superseded without deleting its history.

3. Rewrite `service-graph.md` so controllers parse type/key and delegate; `WorkspaceDirectoryService` resolves one descriptor; explorer, metadata, action logging, execution, tools, runtime instructions, and output services consume it without reconstructing identity from `Path`. Remove project-root and Avatar edges. Add a concise active-consumer inventory grouped by Avatar, Work Area, generic workspace, explorer/metadata/actions, execution/tools, outputs, schema/configuration, UI/assets, tests, and current documentation. For every concrete class, package, resource, table, route, payload field, or document anchor found in step 1, record its current responsibility and exactly one owning phase from 02–08. Do not inventory a name-only match as an active consumer; classify false positives and historical-only records explicitly.

4. Rewrite workspace/output entries in `services.md` around `WorkspaceDirectoryService`, `WorkspaceExplorerService`, canonical metadata/action logging, execution selection, and `OutputArtifactRepository`. Specify low-level agent/chat existence boundaries, explicit user create/list behavior, direct-child non-symlink listing, operation-specific lazy root creation, reserved-output mutation rejection, and one descriptor resolution across execution. Preserve `RootRelativePathService` only for portable artifact-relative paths, never identity or root authorization. Remove planner recurrence and Avatar service entries without active anchors. Reflect the generic-persistence disposition and prohibit retained components from determining filesystem identity.

5. Replace Work Area API entries in `api.md` with:
   - `GET /api/workspace/user` for valid, confined, direct non-symlink user roots;
   - `POST /api/workspace/user` for explicit creation with collision handling;
   - the `/api/workspace/{type}/{id}` item-operation family, where `{id}` is the workspace key;
   - paired `workspaceType`/`workspaceKey` execution payloads and omission/default, mismatch, user-override, and chat-rejection rules;
   - controlled `400`, `404`, and `409` mappings for malformed pairs/keys/paths, missing entities/declarations, and collisions.
   State that encoded separators/traversal are rejected at the web boundary and decoded keys receive plain-segment validation. Preserve operation methods and payload semantics only where they do not preserve Work Area identity. Mark `/avatar`, `/api/work-areas`, and `/api/workspaces` absent, not deprecated. Reject client roots and canonical identities.

6. Rewrite `web.md` so `/agents/{agentId}` owns a standalone HTMX Files tab bound only to that agent workspace. Define neutral shell, list, inspector, modal, and OOB targets; preserved explorer operations and controlled errors; operational-console consistency; desktop/mobile stacking; and checks for spacing, density, wrapping, clipping, overflow, hierarchy, and affordances. State that no generic user/chat browser is mounted. User-workspace creation and execution selection are reusable controls only where a submission surface exposes workspace choice.

7. Rewrite `simplypages.md` so the neutral explorer and selector use reusable SimplyPages composition, slot-safe render context where shared instances require it, HTMX fragment swaps, stable modal hosts, and OOB list/inspector/modal refreshes. Standard CRUD, user-workspace management, selection, filtering, and tab updates remain HTMX-first. Retained JavaScript must be narrowly justified as the simpler mechanism and must not become a replacement transport. Remove Avatar layout-editing and Work Area composition entries.

8. Cross-check all seven files for one meaning of `WorkspaceKind`, `WorkspaceDescriptor`, canonical identity, lazy agent/chat roots, explicit user roots, paired execution selection, project metadata-only association, reserved `outputs/`, output-artifact ownership, generic-persistence disposition, and non-destructive old-schema handling. Verify each inventory item has one later-phase owner and no owning specification duplicates a conflicting full contract.

## Edge Cases

- Invalid workspace keys include blank, untrimmed, dot-only, absolute, slash/backslash, drive-syntax, encoded-separator, and encoded-traversal forms.
- A valid agent/chat entity with no directory is empty; a missing entity is `404`.
- A physically present but undeclared or invalid user directory is not selectable; listing excludes nested entries and symlinks.
- User-create collisions are `409`; root rename and destructive root deletion are unavailable.
- Explorer operations reject absolute/traversing paths, symlink components/escape, symbolic recursive trees, workspace-root mutation, and mutation of `outputs/` or descendants.
- Preview/edit/image/download limits, UTF-8 checks, Markdown safety, recursive-delete confirmation, collision behavior, labels, tags, and action records remain required.
- Existing execution rows may derive only deterministic agent selection from authoritative `agent_id`; no Work Area/generic lookup, user inference, or guessed root is allowed.
- Obsolete metadata/actions are not translated or exposed without reliable canonical mapping.
- Output destinations reject symlinks and collisions; source real paths remain inside the same effective descriptor root.
- Legacy `avatar.sqlite` and old Home, Work Area, project, run, job, scratch, chat, and workspace trees are not opened, migrated, aliased, moved, or deleted.

## Required Guides

Read before editing:

- `.internal-dev/AGENTS.md`
- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/handoffs/20260719-062922-home-dhickel-code-java-magenta-internal-dev-ha.md`

Read only task-relevant specification sections and knowledge filenames needed to classify consumers. Treat code as observed truth, the corrected shared concepts as intended truth, and conflicts as inventory evidence rather than reasons to preserve superseded behavior.

## Technical Guidance

Keep one concise normative contract in its owning specification and link elsewhere. Use exact class, route, field, table, and phase names in the inventory so later implementers can search mechanically. Do not turn observations into compatibility behavior or hand-concatenate roots. The identity flow is:

```text
(type, key) -> WorkspaceDirectoryService -> WorkspaceDescriptor
             -> explorer | metadata/actions | execution/tools | outputs
```

Canonical identity and normalized file-relative path remain separate. A controller never supplies a root, and downstream services never independently resolve the same request.

## Validation

1. Confirm only the seven in-scope specifications changed and the orchestration plan plus eight phase filenames are unchanged.
2. Search the seven files for active support claims involving `Avatar`, `/avatar`, `avatar.sqlite`, `WorkArea`, `/api/work-areas`, `/api/workspaces`, `selectedWorkAreaId`, `outputWorkAreaId`, project roots, run staging, aliases, migration helpers, starter files, generic filesystem identity, links, or leases.
3. Classify every match as an active removal/absence/non-dependence contract or preserved superseded history. Fail for any active support requirement or ambiguity.
4. Verify the inventory covers every required category, names concrete inspected anchors, assigns exactly one owning phase to each active consumer, and classifies false positives/historical-only records without implementation ownership.
5. Verify generic persistence has an evidence-backed disposition and mixed output persistence is assigned to extraction before deletion.
6. Verify route, payload, descriptor, lifecycle, persistence, output, UI, and schema terminology agrees across all seven files, including fresh-schema omission and inert existing-schema behavior.
7. Verify prior decisions remain visible with the superseding decision linked through `superseded_by`.
8. Run the post-phase validator review-and-repair gate with `openai-codex/gpt-5.6-sol` at medium. Resolve every in-scope finding and repeat until PASS.

## Exit Criteria

- The seven specifications express one non-contradictory contract with the stated invariants and edge cases.
- The inventory is concrete, complete across required categories, and gives every active consumer exactly one owner in phases 02–08.
- Generic persistence has an evidence-backed disposition; any retained component has a named unrelated responsibility and cannot determine filesystem identity, roots, links, or leases.
- Prior decisions remain visible and explicitly superseded.
- No active specification requires Avatar, Work Areas, project roots, run staging, aliases, old workspace payloads, generic filesystem identity, or guessed legacy roots.
- Only the seven in-scope files changed, and the eight-file plan set is unchanged.
- The medium validator reports PASS; only then may the phase commit be created and phase 02 begin.
