# Phase 05 Execution and Outputs Cutover Remediation Handoff

## Context

The authoritative review, `.internal-dev/reviews/2026-07-19-phase-05-final-revalidation-review.md`, records a **BLOCKED** verdict for Phase 05 — Cut Over Execution and Outputs at reviewed Git HEAD `eddde09f2f48d040330e403b2aba76e75be57b2d`.

The baseline is deceptively healthy: `mvn test` passed with 891 tests, and Spring started against existing and fresh roots. Those checks do not establish the intended contract because production code, schema bootstrap, tests, web forms, and filesystem behavior still exercise the superseded generic workspace, Work Area, run-output, and filesystem-lease model. The review found 651 stale-contract matches across 48 phase-owned production files, active recreation of obsolete job columns, legacy workflow persistence and resume behavior, mixed output persistence, old orchestration selectors, and creation of `root/workspace/<uuid>/outputs` on a fresh root.

The governing contract is the filesystem-first workspace model in `DECISION-20260719-02`, `ARCH-20260719-01`, `SVC-20260719-04`, `SVC-20260719-05`, the API execution-selection contract, and `.internal-dev/plans/20260719-063539-home-dhickel-code-java-magenta-internal-dev-ha/phase-05-cut-over-execution-and-outputs.md`. Phase 06 remains gated until this cutover receives a validator PASS.

## Objective

Complete the Phase 05 vertical cutover so every active assignment, task, plan, workflow, job, runner, stream, tool, output, query, persistence, and submission path uses the same typed execution-selection contract. Each execution attempt resolves one `WorkspaceDescriptor` from `workspaceType` plus `workspaceKey` at its execution boundary and passes that descriptor through the attempt without downstream re-resolution.

The completed behavior must eliminate active dependence on generic workspace ids, Work Areas, output routes, persisted host paths, project/run roots, output promotion, and filesystem workspace leases while preserving executable-work behavior, resumability, output attribution, project metadata, assignment queue coordination, and existing-schema safety.

## Targets

- **Assignment and runtime:** `AssignmentRequest`, `WorkAssignment`, `AssignmentService`, `OrchestrationTaskContext`, `OrchestrationRuntimeRepository`, `OrchestrationRunnerService`, assignment summaries/read models, runtime-context propagation, and assignment submission/template parsing.
- **Jobs:** `OrchestrationJob`, `OrchestrationJobService`, `JobDefinition`, `JobRun`, job summaries/read models, `JobRepository`, `JobService`, `JobController`, and job-related operational tool responses.
- **Plans, tasks, and workflows:** `PlanRun`, `PlanRepository`, `PlanService`, `TaskService`, task/plan stream support, `WorkflowRun`, `WorkflowRepository`, `WorkflowRunner`, workflow services, and their controllers and submission DTOs.
- **Output boundary:** `OutputArtifactRepository`, `OutputArtifactService`, `OutputArtifactContext`, `OutputArtifactQuery`, `OutputPublicationTarget`, `RunOutputArtifact`, `OutputController`, and every artifact query/publication consumer.
- **Execution filesystem and prompt context:** file and shell tool packages, active runtime-path handling, `AgentsMdResolver`, prompt/context consumers, and only the `WorkspaceDirectoryService` execution calls needed to remove generic, run, work, scratch, job, and staging-root behavior.
- **Web/API submissions:** `AgentOrchestrationController`, `PlanController`, `TaskController`, `WorkflowController`, `JobController`, `PublicRunSubmissionController`, `OrchestrationController`, stream adapters, template mappers, selectors, and validation/error mapping touched by execution selection.
- **Persistence and tests:** `src/main/resources/schema.sql`; focused runtime, job, plan, workflow, output, tool, stream, controller, fresh-schema, existing-schema, startup, and filesystem tests. Existing tests that assert the retired contract are replacement targets, not evidence to preserve it.
- **Closeout records and technical/API documentation:** update only material affected by the completed cutover, following repository workflow rules. Do not change the accepted eight-phase plan structure.

## Features

- **Typed execution selection:** Submission surfaces accept `workspaceType` and `workspaceKey` as a nullable-together pair. Both omitted selects the bound agent; exactly one supplied is invalid. Retired routing fields are absent from accepted models and are rejected at affected request boundaries rather than ignored. If application-wide unknown-property handling is permissive, affected endpoints must still reject named retired fields without imposing unrelated API changes.
- **Single descriptor binding:** Selection policy runs at the submission/assignment boundary, while descriptor resolution runs once at the execution-attempt boundary through `WorkspaceDirectoryService`. The resulting descriptor is carried through execution, tools, prompts, and publication without reconstructing identity from a path or resolving another root.
- **Durable selection snapshots:** New active execution records persist the resolved type, key, and internally derived canonical identity, never a host path or in-memory descriptor. Workflow `WAITING` state retains the exact type/key selection. Resume re-resolves that persisted selection once for the resumed execution attempt and does not derive a replacement from mutable agent or project state.
- **Descriptor-confined execution:** File and shell tools operate under the selected descriptor root and one normalized active relative path. Layered `AGENTS.md` lookup remains confined between that root and the active path and uses only existing user-authored files. General file, shell, and explorer mutation paths reject `outputs/` and its descendants.
- **Direct output publication:** Declared outputs publish directly beneath the selected workspace’s lazy `outputs/` subtree using normalized relative names, collision-safe non-overwriting destinations, and full execution attribution. No run-, job-, task-, project-, work-, scratch-, staging-, or discovery hierarchy is synthesized.
- **Independent artifact persistence:** `OutputArtifactRepository` is the sole owner of artifact schema compatibility, writes, reads, updates, deletes, mapping, and attribution. `OutputArtifactService` has no generic workspace persistence constructor or dependency.
- **Legacy isolation:** Existing obsolete database structures and filesystem trees remain untouched, but no active Phase 05 code reads, joins, writes, recreates, aliases, leases, or falls back through them. Dormant Phase 06-owned definitions may remain only when they have no active Phase 05 provider or consumer.
- **Clean submission UI:** Old generic workspace, Work Area, project-root, output-route, direct-output, and filesystem-lease controls and labels disappear from active orchestration forms. Bound-agent submissions may omit both selection fields. No replacement selector is introduced in Phase 05.

## Settled Decisions

- The passing suite and successful startup are baseline observations only; the Phase 05 verdict remains blocked until contract-focused validation passes.
- Execution targets support `agent` and explicitly declared `user` workspaces only. `chat` remains a valid workspace kind elsewhere but is invalid for execution selection.
- Both selection fields omitted means the bound agent. Exactly one field is invalid. Explicit `agent` selection must match the bound agent and refer to an existing agent. Explicit `user` selection must refer to an existing declared user workspace.
- Canonical identity is always internal and exactly `kind.token() + "/" + key`. Clients submit neither canonical identity nor host paths.
- Project ids remain membership and attribution metadata only. They cannot select, create, link, lease, release, or delete a filesystem root.
- Assignment queue lease fields remain. Filesystem workspace leases and workspace-blocked requeue semantics do not.
- New writes persist the resolved type/key/identity contract. Existing active rows may derive an agent selection only from authoritative `agent_id` or `owner_agent_id`; ambiguous historical rows produce controlled non-executable and non-resumable outcomes and never fall back through retired routing fields.
- Existing obsolete columns, indexes, rows, and filesystem trees are not dropped, rewritten, migrated, imported, relocated, aliased, or deleted. Fresh Phase 05-owned execution and artifact schema omits retired execution-routing fields, and compatibility logic must not recreate them. Phase 06-owned obsolete structures may remain defined but inert.
- `outputs/` is lazy and reserved. Reads and failed validation create nothing. Publication is its only writer; explorer, file, and shell mutations cannot alter it or descendants.
- Output source authorization uses a trusted descriptor and real-path confinement, never a persisted artifact path. Stored artifact paths are portable data-root-relative values derived from trusted destinations.
- Phase 06 owns final class/schema deletion for obsolete generic workspace, Work Area, link, lease, and output-directory layers. Phase 05 must remove every active consumer/provider and compatibility path that would make deletion unsafe. A retained `EffectiveWorkspace` may only wrap an already-resolved descriptor and may expose none of the retired routing fields.
- Phase 07 owns the reusable execution selector and agent Files tab. This remediation removes stale controls without implementing Phase 07 UI.
- Filesystem and SQLite changes are not represented as crash-atomic. For detected publication or persistence failure, the operation must compensate, surface cleanup failure explicitly, and leave no successful artifact result. No generalized reconciliation or migration subsystem is added in Phase 05.

## Constraints

- Do not retain dual request shapes, deprecated constructors, legacy overloads, aliases, deserialization fallbacks, optional Spring dependencies, or compatibility endpoints for the retired execution contract.
- Do not hand-concatenate structural workspace paths. `WorkspacePathLayout` remains the structural path authority and `WorkspaceDirectoryService` remains the descriptor authority.
- Downstream services receive the resolved descriptor; they do not independently resolve type/key, infer identity from `Path`, or use `workspaceId`, `workAreaId`, `projectId`, run ids, or artifact paths as root authorization.
- Preserve task/job/run/workflow/project/agent/assignment attribution and task/workflow snapshot semantics.
- Preserve project CRUD, membership validation, project events, network gating, active assignment/job guards, and project attribution.
- Preserve queue coordination, cancellation, interruption, stream finalization, and successful execution semantics unrelated to workspace routing.
- Output names must be non-empty normalized relative paths. Reject absolute, dot-only, traversing, escaping, or symlinked destinations. Destination parents cannot be symlinks; collisions never overwrite.
- File output sources must resolve to real regular files beneath the same selected descriptor root. A source under `outputs/` cannot be republished onto itself or used to bypass reserved-subtree rules.
- Publication must use collision-safe destination creation and repository transaction boundaries. If artifact-row persistence fails after file creation, compensating deletion is mandatory for the detected failure path; cleanup failure must be explicit and must fail the operation. Do not claim unconditional cross-store crash atomicity.
- No generated starter content, run staging, loose artifact discovery, copied-file promotion, project-root scans, or attribution-based directory layers remain active.
- Keep controllers thin, services responsible for use-case policy, and repositories responsible for schema and persistence details.
- Standard web interactions remain HTMX-first. Any retained JavaScript must be narrowly justified and must not become a replacement transport.
- Preserve unrelated edits and behavior. Do not add generalized workspace providers, migration machinery, reconciliation machinery, or speculative APIs.
- No stubs, TODO deferrals, no-op adapters, or throwing compatibility shells satisfy the cutover.

## Scope

### In scope

- Repair all active Phase 05 consumers identified by the review, plus discovered consumers required to make the same vertical contract coherent.
- Replace stale DTO/model fields, repository columns and row mappings, runner/service dependencies, stream data, output publication paths, controller parameters, template fields, and tests.
- Add compatibility handling only for new typed columns and deterministic agent attribution on existing active rows; prove obsolete routing values are not consulted.
- Remove phase-owned runtime creation of generic UUID workspace roots and eager `outputs/` directories.
- Repair Spring constructor graphs and package guidance affected by the cutover.
- Complete required tests, startup checks, filesystem/database inspection, classified static searches, documentation, internal development records, validator review, and commit closeout.

### Out of scope

- Final deletion of Phase 06-owned generic workspace, Work Area, project-root, link, alias, lease, migration, and output-directory class families or old database structures.
- The Phase 07 Files tab and reusable workspace-selection component.
- Legacy data/file migration, backfill from Work Areas or generic workspaces, root relocation, aliasing, destructive cleanup, or historical-row reactivation.
- Root rename/delete, sharing, permissions, quotas, synchronization, project file browsing, or a generalized workspace framework.
- Unrelated orchestration hardening, SSE redesign, workflow redesign, or terminology renaming.
- A cross-store transaction coordinator, artifact reconciliation subsystem, or broader crash-recovery redesign.

## Assumptions

- The Phase 04 typed descriptor foundation and API cutover remain valid dependencies.
- The review’s match count is a diagnostic snapshot, not an allowed residual count. Acceptance is based on semantic elimination of active stale contracts, including consumers discovered outside the original list.
- The existing-schema startup repair that places the workspace-identity artifact index behind `OutputArtifactRepository` compatibility handling is valid and remains under that repository’s ownership.
- Authoritative agent attribution is sufficient for deterministic compatibility where present. No user workspace can be inferred from historical rows.
- A valid bound agent can supply the default execution identity even when its physical workspace root is absent; successful execution may create that root without scaffolding or eager `outputs/`.
- User workspaces are selected only when already declared and physically valid under the established user-workspace contract.
- `RootRelativePathService` remains applicable only to portable artifact path storage, not workspace identity or authorization.
- The implementation environment can run the complete Maven suite, fresh- and existing-root startup, database/filesystem inspection, and delegated focused browser validation.

## Recommended Direction

- Treat the repair as one vertical contract rather than isolated class fixes. Align request models, persisted models, repositories, runners, tools, output services, controllers, and tests around the same type/key/descriptor vocabulary.
- Centralize selection policy at submission/assignment boundaries. Persist a path-free selection snapshot, resolve it once when an execution attempt begins, and pass the trusted descriptor and normalized active relative path through that attempt.
- Make repository schemas explicit and narrow: typed columns for active selection and identity, deterministic compatibility from agent attribution only, no SQL reads or writes against retired routing columns, and no obsolete-column creation by Phase 05 compatibility code.
- Simplify output publication to declared values and controlled same-workspace file sources. Keep all artifact persistence in `OutputArtifactRepository`; remove discovery and promotion compatibility instead of adapting it.
- Enforce the reserved `outputs/` subtree at every general filesystem mutation boundary, not only in the output service.
- Remove stale web selectors and payload fields at their boundaries. Return controlled client errors for malformed, partial, mismatched, unsupported, or retired selections.
- Rewrite tests around intended behavior. Delete tests whose sole purpose is to preserve `workspaceId`, absolute workflow paths, Work Area routing, run output directories, or generic workspace roots.
- Use classified static searches and fresh-root inspection as architecture tests. A successful compile with dormant active legacy behavior is insufficient.

## Validation

- **Static contract scan:** Search all phase-owned production code, resources, tests, templates, and technical docs for `workspaceId`/`workspace_id`, `workspace_path`, `output_dir`, Work Area selection, output-route/direct-output fields, `EffectiveWorkspace`, `OutputDirectoryService`, generic workspace services, project/run/staging roots, filesystem workspace leases, loose discovery, copied-file publication, and legacy constructors. Classify every match by file and symbol. Active Phase 05 callers, providers, tests, templates, DTO fields, and schema writes are forbidden; historical records, attribution-only terminology, and dormant Phase 06 definitions require an explicit evidence-backed allowlist.
- **Selection tests:** Cover omitted pair fallback, one-field rejection, explicit matching agent, agent mismatch, missing agent, existing user override, missing user, chat rejection, invalid or encoded keys, client canonical identity/path rejection, and named legacy-field rejection with controlled errors. Verify rejection even if generic unknown-property handling is permissive.
- **Persistence tests:** Use fresh and populated existing schemas. Prove typed pair/identity storage, deterministic agent compatibility, controlled exclusion of ambiguous rows, exact `WAITING` selection persistence, no obsolete-column creation by Phase 05 code, no obsolete-table joins or updates, and sentinel preservation.
- **Execution tests:** Exercise representative assignment, task, saved plan, workflow, `WAITING` resume, and job paths. Confirm each execution attempt resolves once and uses one descriptor throughout, resume uses the persisted selection snapshot, project association is metadata-only, tools remain confined, active paths update correctly, and layered `AGENTS.md` behavior remains intact.
- **Output tests:** Cover scalar/value materialization, same-workspace file sources, nested declared names, lazy `outputs/`, source real-path confinement, non-regular/missing/cross-workspace source rejection, reserved-subtree mutation rejection, destination symlinks, concurrent same-name collisions, self-republication rejection, artifact attribution/query filters, repository transaction failure, compensating deletion, and explicit cleanup-failure behavior.
- **Filesystem inspection:** On a fresh data root, confirm only the selected `workspace/agent/<id>` or `workspace/user/<name>` root appears as required. Confirm `outputs/` appears only following successful publication. No generic UUID root or project/run/job/work/scratch/staging/link tree may appear.
- **Repository independence:** Prove `OutputArtifactRepository` owns artifact behavior without `WorkspaceRepository`, and prove active output operations function with obsolete generic workspace tables absent or inert.
- **Controller/stream tests:** Verify all affected submission and read surfaces expose type/key/identity and attribution correctly, omit legacy routing fields, reject retired input fields, preserve stream finalization/error behavior, and maintain controlled HTTP/HTMX responses.
- **Focused and broad automation:** Run affected repository, service, controller, tool, stream, and schema suites, followed by the complete `mvn test` suite. Passing tests that assert retired behavior must not remain.
- **Application wiring:** Start Spring against both fresh and representative existing roots. Require successful application-context startup with the repaired constructor graph and no active dependency on Phase 06 deletion targets.
- **Browser validation:** Because orchestration forms change, run focused Playwright validation on a subagent using the repository-mandated model and reasoning setting. Verify stale controls are absent, visible bound-agent submission succeeds, HTMX remains the transport, and desktop/mobile screenshots pass visual-quality review. Test explicit selection and malformed payloads at controller/API level unless an existing visible Phase 05 surface already exposes those fields; do not add a Phase 07 selector for validation.
- **Diff and closeout review:** Check for dual contracts, obsolete schema writes, placeholders, accidental API growth, unrelated changes, or Phase 06/07 leakage. Update affected docs and internal records and include them in the required commit.
- **Gate:** Run the configured medium post-phase review-and-repair validator. Phase 05 is complete only with a PASS and clean evidence for every preceding validation category.

## Open Questions

No product decision is required to execute this handoff. The following are repository-discovery questions with settled resolution rules:

- Regenerate the exact stale-reference inventory from the implementation tree; every semantically active Phase 05 consumer is in scope even if absent from the review’s 48-file snapshot.
- Classify apparent stale matches explicitly rather than weakening the search. Active callers, providers, tests, templates, DTO fields, and schema writes are not exempt. Historical records, attribution-only names, and dormant Phase 06 definitions require recorded justification.
- Existing rows lacking the typed pair are executable only when authoritative agent attribution yields one unambiguous agent descriptor. Every other case remains historical and non-resumable and must fail through a controlled domain outcome.
- If an `EffectiveWorkspace` shell is necessary for compilation, it may contain only an already-resolved descriptor and must have no active legacy resolver, routing field, or alternate identity path.
- If repository inspection reveals a governing specification conflict rather than an implementation detail, stop the affected path and seek an approved contract correction; do not silently preserve a dual model.

## Sign-off

Status: **BLOCKED**.

Sign-off requires all active Phase 05 paths to use the typed selection and single-descriptor-per-execution-attempt contract; independent artifact persistence; exact workflow resume selection; direct lazy output publication; reserved-subtree enforcement; no generic UUID/eager-output filesystem behavior; rewritten contract-valid tests; classified static-search evidence; successful fresh/existing startup; clean filesystem and database evidence; focused browser proof for changed visible forms; and a post-phase validator PASS.

Any active stale consumer, obsolete schema mutation, legacy fallback, descriptor bypass, unconfined output path, stale-contract test, unexplained fresh-root artifact, unclassified residual scan match, or failed required validation keeps the handoff blocked. A PASS unblocks Phase 06 only; it does not authorize Phase 06 deletion work or Phase 07 UI work within this scope.

Explicit sign-off: **BLOCKED pending validator PASS and complete clean evidence for every Phase 05 acceptance category.**
