# Filesystem-first Workspaces and Avatar Removal

## Architecture

Magenta uses one application-owned workspace tree:

```text
<dataRoot>/
  workspace/
    agent/<agentId>/
    chat/<chatId>/
    user/<name>/
```

`WorkspaceKind` is a closed enum containing `AGENT`, `CHAT`, and `USER`, serialized as `agent`, `chat`, and `user`. `WorkspaceDescriptor` is the trusted internal value passed across filesystem, explorer, metadata, action logging, execution, tool, and output boundaries. It contains the kind, validated key, canonical identity, and normalized trusted root. Canonical identities are exactly `agent/<id>`, `chat/<id>`, and `user/<name>`.

Only `WorkspaceDirectoryService` constructs descriptors. It validates decoded keys with `PlainPathSegmentValidator`, checks agent and chat existence through low-level repositories that do not create service cycles, enforces explicit user-workspace declaration, and resolves roots through `WorkspacePathLayout`. Controllers accept type and key path segments, never roots, canonical identities, or arbitrary paths.

`WorkspacePathLayout` remains the sole structural path authority. Its retained public structural names are `WORKSPACE` and `OUTPUTS`; its retained root factories produce only agent, chat, and user workspace roots. The data root remains configurable. Home, Work Areas, `work/`, `runs/`, `projects/`, scratch, job roots, temporary execution trees, aliases, migration helpers, and starter files are removed from active root resolution and creation.

Path-identified workspaces are not generic database workspace records. `WorkArea`, `WorkAreaRepository`, `WorkAreaService`, owner-root marking, Work Area links, and filesystem workspace leases are retired. Generic `Workspace`, `WorkspaceService`, `WorkspaceRepository`, link, and lease code is removed when repository inspection confirms it has no demonstrated non-Work-Area product responsibility. Any retained persistence component must have an explicit unrelated responsibility and must not select, resolve, alias, link, lease, or own a filesystem root. Output-artifact persistence is extracted into `OutputArtifactRepository` before deletion of mixed-purpose workspace persistence. Assignment queue lease fields remain because they coordinate assignment execution rather than filesystem ownership.

Requests and active execution persistence carry `workspaceType` and `workspaceKey` together. Canonical identity is derived internally from the resolved descriptor and is stored where canonical filesystem attribution is needed, including file metadata, action logs, and output artifact records. Clients never submit canonical identity as an alternative selection mechanism.

Fresh schema contains only active canonical workspace attribution and execution-selection fields. Existing databases may retain obsolete tables, columns, indexes, and rows, but active repositories neither depend on nor mutate those structures.

## Conceptual Approach

The phase file set remains exactly:

1. `phase-01-lock-superseding-contracts.md`
2. `phase-02-build-workspace-identity-foundation.md`
3. `phase-03-remove-avatar-product.md`
4. `phase-04-cut-over-explorer-and-api.md`
5. `phase-05-cut-over-execution-and-outputs.md`
6. `phase-06-retire-obsolete-workspace-layers.md`
7. `phase-07-mount-agent-files-tab.md`
8. `phase-08-close-documentation-and-records.md`

The phases implement these concepts:

1. Lock the superseding intended contract in living specifications and decisions; inventory all active Avatar, Work Area, generic workspace, explorer, execution, output, schema, documentation, and test consumers.
2. Add the closed workspace identity, trusted descriptor construction, path-layout factories, entity-existence boundaries, and user-workspace lifecycle without service cycles.
3. Separate reusable explorer presentation and service behavior from Avatar ownership, then remove the Avatar product while keeping the revision compilable and preserving the explorer features required by the handoff.
4. Re-key the explorer, metadata, labels, tags, and action log to canonical workspace identity; expose typed workspace routes and user workspace collection operations.
5. Cut assignment, task, workflow, job, runner, tool, output publication, persistence, read-model, and submission contracts to `workspaceType` plus `workspaceKey` with one resolved descriptor end to end.
6. Remove obsolete generic workspace, project-root, filesystem lease, link, alias, migration, and Work Area layers only when their active consumers have moved and output persistence has independent ownership.
7. Mount the neutral explorer as the agent console Files tab and render reusable user-workspace creation and execution-selection controls where submissions expose workspace choice.
8. Reconcile current docs, package guides, knowledge, living specifications, decisions, and changelog records; archive finalized plan material as required; run complete integration validation.

The target is not a generic workspace framework. It is a small closed model for three concrete Magenta workflows.

## Features

- **Agent workspace:** `workspace/agent/<agentId>`. The agent record must exist. A missing physical directory reads as an empty workspace. Successful file-producing and execution operations may create the root without scaffolding subtrees or starter content.
- **Chat workspace:** `workspace/chat/<chatId>`. The conversation must exist through an authoritative low-level existence check. A missing physical directory reads as empty. Successful file-producing operations may create the root.
- **User workspace:** `workspace/user/<name>`. It is created only by the explicit create operation. Missing roots are `404` for reads, mutations, and selection. Listing returns only valid, confined, direct non-symlink directories. Names are stable identities; root rename and destructive root deletion are excluded.
- **Execution selection:** payloads use `workspaceType` and `workspaceKey` together. Omission of both fields defaults to the bound agent. Supplying only one field is invalid. Explicit agent selection must match the bound agent. An explicitly selected existing user workspace overrides the fallback. Chat selection is rejected.
- **Project association:** `projectId` remains metadata and membership context but never selects a root, creates a directory, acquires a filesystem lease, or materializes a link.
- **Outputs:** publication writes beneath the effective descriptor’s lazy `outputs/` directory using collision-safe normalized relative names. Source files must resolve to real files confined beneath that same trusted workspace root. Durable task, job, run, project, agent, and canonical workspace attribution remains queryable. No `runs/<runId>` hierarchy or job-owned root is created.
- **Explorer:** list, inspect, preview, image view, bounded download, text and Markdown create/save, directory create, rename, move, copy, delete preflight/execute, labels, tags, and recent actions remain available through trusted descriptors.
- **API:** item operations use one route family rooted at `/api/workspace/{type}/{id}`. User workspace collection operations are `GET /api/workspace/user` and `POST /api/workspace/user`. File operation methods and payload semantics remain compatible where they do not preserve Work Area identity.
- **UI:** `/agents/{agentId}` gains a standalone HTMX Files tab with workspace-neutral IDs, classes, routes, labels, inspector, list, and modal behavior. It resolves only the bound agent workspace.
- **Avatar removal:** the Avatar package, datasource and schema initialization, profile bootstrap, planner and organizer models, web routes, tools, prompt and policy references, assets, tests, navigation, and current documentation are removed rather than hidden or disabled.
- **Runtime instructions:** layered `AGENTS.md` resolution remains for files that actually exist beneath the selected descriptor root. Magenta does not generate starter `AGENTS.md` content.

## Constraints

- Do not migrate, import, open, create, or delete `avatar.sqlite` as part of the cutover. Legacy user-owned Avatar files remain untouched.
- Do not relocate, migrate, delete, alias, or reactivate legacy Home, Work Area, project, run, job, scratch, chat, or workspace trees.
- Do not translate or expose historical Work Area-keyed labels, annotations, tags, or actions when no reliable canonical mapping exists.
- Fresh schema must omit Avatar, Work Area, generic filesystem workspace/link/lease, project-root, Work Area-keyed metadata/action, and removed execution-routing contracts. A generic table may remain only when Phase 01 records a demonstrated unrelated responsibility and the table cannot determine filesystem identity.
- Existing databases may retain obsolete tables, columns, indexes, and rows. Active repositories must not read or write obsolete Work Area identity, generic filesystem workspace identity, root-link, or filesystem-lease fields.
- Existing active execution rows may derive a deterministic agent workspace selection only from authoritative `agent_id` when compatibility is required. No backfill or runtime fallback may consult Work Area or generic workspace tables, infer a user workspace, or guess a root. Rows without deterministic selection remain historical and are not resumed through guessed paths.
- Both workspace selection fields are required together. Canonical identity is derived internally and is never accepted from a client.
- Reject encoded separators and encoded traversal syntax at the web boundary. Validate decoded keys with `PlainPathSegmentValidator` semantics: nonblank, trimmed, non-dot-only, non-absolute, and without slash, backslash, drive syntax, or encoded path syntax.
- File-relative paths remain distinct from workspace identity and are normalized beneath the trusted descriptor root. Reject absolute paths, traversal, symlink path components, symlink escape, symbolic recursive trees, and workspace-root mutation.
- `outputs` is reserved for publication. Explorer reads may inspect it, but publication is the only writer beneath that subtree; explorer create, save, rename, move, copy, and delete operations must not create or mutate it or its descendants.
- Preserve preview, edit, image, and download limits; UTF-8 validation; Markdown rendering safety; collision responses; recursive-delete confirmation; labels; tags; and audit records.
- Controllers remain thin. Services own descriptor resolution, entity and declaration existence policy, confinement, mutations, metadata, logging, execution selection, tool binding, and output publication.
- Standard explorer, user-workspace, selection, and Files-tab interactions remain HTMX-first. Do not add a replacement JavaScript transport. Retained JavaScript must be narrowly justified as the simpler interaction mechanism.
- Preserve unrelated edits and behavior. Do not add compatibility endpoints, legacy overloads, migration engines, generalized workspace providers, root rename/delete, sharing, permissions, quotas, synchronization, project file browsing, or a replacement Avatar product.
- Preserve project records, membership validation, project events, and project attribution while removing project filesystem allocation, materialized links, and filesystem lease behavior.
- Preserve assignment queue coordination fields that are independent of filesystem workspace leases.
- Create the dedicated implementation branch before phase work. Commit each phase only when its validator gate passes, including applicable `.internal-dev` and `docs/` changes.

## Assumptions

- `AgentProfileRepository.findById` or an equivalent low-level repository operation is authoritative for agent existence.
- A low-level chat repository can determine conversation existence from chat memory, session metadata, or saved-task conversation records without depending on `ChatService` or task/plan services.
- Agent, chat, and user keys fit one decoded URL path segment and satisfy existing plain-segment validation.
- A valid bound agent always supplies the default execution identity even when its physical root has not been created.
- User workspace names are stable identities and a user workspace must already be declared for file operations or execution selection.
- Existing project associations remain useful metadata after project filesystem behavior is removed.
- Assignment queue leasing is independent of deleted filesystem workspace leasing.
- Existing output records retain durable run and execution attribution independently of run-owned filesystem directories.
- `RootRelativePathService` remains useful only for portable artifact file paths and does not determine workspace identity or authorize roots.
- Repository inspection can determine whether any generic workspace persistence has a concrete non-Work-Area responsibility; absence of such evidence authorizes retirement, not compatibility preservation.

## Cross-phase Guidance

- Treat code as observed truth and the authoritative handoff plus revised living specifications as intended truth. Record conflicts rather than silently retaining superseded behavior.
- Phase 01 must supersede active Avatar and DB-backed Work Area specification and decision entries, preserve decision history, and record the disposition of generic workspace persistence based on concrete consumers.
- Keep every phase revision compilable and its Spring constructor graph valid. Foundation work may coexist with untouched callers, but no compatibility endpoint, overload, no-op method, throwing stub, or duplicate identity path may survive the owning phase’s exit gate.
- Phase 03 must identify and move reusable explorer service, fragment, style, and HTMX behavior to workspace-neutral ownership before deleting the Avatar-only owner. Avatar assets or controllers may not remain merely to host the explorer.
- Use `WorkspaceDescriptor` end to end. Never reconstruct identity from `Path`, concatenate kind roots outside `WorkspacePathLayout`, pass a raw root from a controller, or resolve the same request independently in multiple layers.
- Keep canonical workspace identity and normalized file-relative path in separate fields. Normalize a file-relative path once at the service boundary before repository calls.
- Add fresh active metadata and action schemas keyed by canonical workspace identity rather than forcing writes through obsolete schemas with `workspace_id NOT NULL`, `work_area_id`, owner-root, or generic workspace foreign-key assumptions. Existing obsolete rows remain inert.
- Extract all `run_output_artifacts` schema, queries, writes, deletion behavior, and attribution mapping into `OutputArtifactRepository` before removing the mixed-purpose `WorkspaceRepository`.
- Cut execution selection as one vertical contract: request records, controllers, submission services, assignment/task/workflow/job records, runtime contexts, persistence repositories, runners, file and shell tools, prompt context, output publication, queries, and UI selectors must agree on type/key semantics.
- Resolve the effective descriptor once from the bound agent and optional type/key selection. Persist the pair and internally derived canonical identity where required; never allow `projectId`, a legacy workspace id, or an output route field to override it.
- Keep project membership validation, project records, project events, and project attribution while removing project filesystem allocation, root creation, links, and leases.
- Preserve layered `AGENTS.md` resolution from the selected workspace root to the active confined tool path. Remove starter generation, old aliases, and traversal outside the descriptor root.
- Use collision-safe output naming without run-owned directories. Validate lexical destination confinement, reject destination symlinks and collisions, validate source real paths against the effective descriptor root, and preserve durable attribution.
- Update constructor graphs and focused tests with each moved boundary. Remove optional Spring dependencies when the new active path requires the dependency, and remove providers for deleted services.
- For UI work, use SimplyPages components, stable HTMX targets, and OOB list, inspector, and modal refreshes. Raw HTML is a fallback only when existing component APIs cannot safely express the fragment.
- At every phase gate, search the files owned by that phase for stale old-contract references. Phase 08 performs repository-wide searches across active production code, resources, configuration, tests, current docs, package guides, and living specifications while allowing clearly historical or superseded records.
- Do not alter the eight phase filenames or add, remove, merge, or split phase files.

## Final Validation Criteria

- `mvn test` passes with removed-behavior tests deleted and affected tests rewritten for the new contract.
- A bounded Spring Boot startup succeeds without Avatar beans, Avatar datasource or schema initialization, Work Area beans, obsolete generic filesystem workspace beans, or missing dependencies.
- Fresh-schema validation creates no Avatar tables, `work_areas`, generic filesystem `workspaces`, workspace links, filesystem workspace leases, Work Area-keyed metadata/action tables, project-root contracts, or removed execution-routing fields. Active metadata, action, execution-selection, and output schemas use the new canonical/type-key contract without foreign keys to obsolete workspace identity.
- Existing-schema startup leaves obsolete Avatar and Work Area-era tables, columns, indexes, rows, and user files intact. Instrumented repository or integration tests prove active behavior does not read, write, backfill from, or depend on them.
- Layout tests assert only `workspace/agent/<id>`, `workspace/chat/<id>`, and `workspace/user/<name>` roots plus lazy reserved `outputs/`; no removed structural constants, factories, aliases, or hand-concatenated replacements remain.
- Directory and descriptor tests cover agent/chat entity existence, lazy agent/chat reads, explicit user create/list, direct-child filtering, missing-user behavior, invalid keys, encoded and decoded separators, traversal, symlinks, confinement, no starter file, no eager subtree creation, and operation-specific lazy root creation.
- Execution tests cover omission fallback, paired-field validation, existing user override, chat rejection, explicit agent-key match, missing entities, project metadata-only behavior, no project/job/run/work/scratch/temp trees, deterministic legacy-agent handling, non-resumable ambiguous history, and persisted type, key, and canonical identity.
- Tool and runtime-context tests prove file and shell operations bind to the effective descriptor, reject escaped paths, update the active confined path, and preserve layered `AGENTS.md` resolution without generated starter content or legacy aliases.
- Output tests prove direct lazy publication beneath the effective workspace, collision safety, destination symlink rejection, source real-path confinement to that workspace, durable attribution and queryability by run/task/job/project/agent/canonical workspace, repository independence from generic workspace records, and absence of a run hierarchy.
- Explorer service and API tests cover every preserved operation for agent, chat, and user kinds, including valid empty roots and relevant invalid, missing, collision, limit, UTF-8, traversal, symlink, symbolic-recursion, recursive-delete, reserved-output, and root-mutation cases.
- Metadata and action-log tests prove labels, tags, and recent actions use canonical workspace identity plus normalized file-relative paths and do not expose unrelated historical Work Area-keyed rows.
- API tests prove `GET|POST /api/workspace/user` and the `/api/workspace/{type}/{id}` operation family are active, operation-specific methods and status mappings remain controlled, raw roots and client canonical identities are rejected, and `/api/work-areas`, `/api/workspaces`, and `/avatar` are absent.
- Avatar validation finds no active Avatar package, controllers, components, datasource configuration, schema initialization, tools, reserved profile bootstrap, prompt/policy registration, assets, tests, navigation, routes, or current documentation.
- Work Area validation finds no active `WorkArea`, `WorkAreaService`, `WorkAreaRepository`, owner-root marking, Home/mark/unmark/nested-mark behavior, Work Area route, active Work Area payload field, or active Work Area schema dependency.
- Generic-layer validation either proves complete retirement or identifies each retained class/table’s documented unrelated responsibility and proves it cannot determine filesystem identity, roots, links, or leases.
- Delegated Playwright validation passes against the live agent Files tab on desktop and mobile. It covers tab loading, CRUD, navigation, preview, image view, labels/tags, controlled errors, inspector/modal/OOB updates, empty state, responsive stacking, spacing, density, wrapping, overflow, hierarchy, and operational-console consistency, with screenshots and visual critique.
- Browser validation confirms standard CRUD, user workspace management, selection interactions, and tab updates use HTMX and no replacement JavaScript transport was introduced.
- Final repository-wide active-reference searches distinguish allowed historical records and inert user artifacts from active code and current docs. Final diff inspection finds no placeholders, compatibility stubs, accidental API growth, phase-file changes, or unrelated removals.
