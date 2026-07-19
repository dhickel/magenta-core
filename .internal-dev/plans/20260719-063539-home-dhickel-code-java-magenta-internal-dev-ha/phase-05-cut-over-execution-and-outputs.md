# Phase 05 — Cut Over Execution and Outputs

## Context

Phase 04 provides typed descriptor-based workspace APIs. Execution still carries generic workspace ids, Work Area output routing, project/run filesystem choices, and mixed output persistence. This phase cuts assignment, task, plan, workflow, job, runner, tool, output, repository, stream, and submission behavior to one resolved `WorkspaceDescriptor`.

## Goal

Persist and execute one agent-or-user `workspaceType`/`workspaceKey` selection, derive canonical identity internally, preserve task/job/run/project/agent attribution and resumable workflows, and publish declared outputs directly beneath the selected workspace’s lazy `outputs/` directory.

## In Scope

- Output boundaries: `OutputArtifactRepository` (new), `OutputArtifactService`, `OutputArtifactContext`, `OutputArtifactQuery`, `OutputPublicationTarget`, `RunOutputArtifact`, `AgentsMdResolver`, `EffectiveWorkspace`, `EffectiveWorkspaceResolver`, `OutputDirectoryService`, and `ResolvedOutputDirectory` under `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/`.
- Runtime boundaries under `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/`, including assignment requests/models/services, task context, runtime repository/runner, jobs, and their read models.
- `src/main/java/io/mindspice/magenta2/ai/chat/plan/`, `TaskService`, and workflow models/repositories/runners/services under `ai/orchestration/workflow/`.
- File and shell tools under `src/main/java/io/mindspice/magenta2/ai/chat/tool/file/` and `.../tool/shell/`, plus prompt/runtime-context consumers of workspace roots and active paths.
- `AgentOrchestrationController`, `PlanController`, `TaskController`, `WorkflowController`, `JobController`, `PublicRunSubmissionController`, `TaskStreamSupport`, `WorkflowStreamSupport`, `OutputController`, and `OrchestrationController`.
- `src/main/resources/schema.sql` and all focused runtime, job, plan, workflow, output, tool, stream, and controller tests affected by the contract.

## Out of Scope

- Final deletion of generic workspace, Work Area, link, alias, output-directory, project-root, and filesystem-lease classes; Phase 06 owns deletion after active consumers move.
- The reusable execution selector and agent Files tab; Phase 07 owns them.
- Migration, relocation, deletion, aliasing, or reactivation of legacy files or trees.
- Translation of historical Work Area/generic workspace selection into user workspaces.
- Dual request shapes, compatibility endpoints, legacy overloads, or client-supplied canonical identity.

## Dependencies

- `phase-04-cut-over-explorer-and-api.md` must have PASS.
- This is wave 05 with exactly one implementation agent.
- The dedicated plan branch already exists; commit this phase only after its gate passes.
- The post-phase validator uses `openai-codex/gpt-5.6-sol` at medium. Phase 06 remains blocked until PASS.

## Constraints

- Type and key are both absent or both present. Absence selects the bound agent. Explicit `agent` must equal the bound agent and exist; `user` must already be declared; `chat` is invalid.
- `WorkspaceDirectoryService` resolves once. Downstream code receives the descriptor and never reconstructs identity from a `Path` or resolves another root.
- Canonical identity is internal and exactly `kind.token() + "/" + key`. Clients submit neither identity nor host paths.
- Active persistence carries type/key and derived identity where required for execution snapshots and attribution.
- `projectId` remains membership and attribution metadata only. It creates no root, link, directory, or filesystem lease.
- Assignment queue coordination remains; filesystem workspace lease behavior is removed from execution.
- `WAITING` persists its exact selection and re-resolves it on resume without fallback.
- Existing active rows may derive agent selection only from authoritative `agent_id` or `owner_agent_id`. No compatibility code reads Work Area/generic workspace routing. Ambiguous rows remain historical and non-executable.
- Publication is the only writer beneath `outputs/`. Explorer, file, and shell mutations cannot create or alter it or descendants.
- Publication creates `outputs/` only after validation succeeds and a write will occur. Reads and failures create nothing.
- Output names are normalized relative paths beneath `outputs/`; no run/job/task/project/work/scratch/temp hierarchy is synthesized.
- Sources resolve to real regular files under the same descriptor root. Destinations and parents cannot be symlinks; collisions never overwrite.
- Durable artifacts retain applicable run, task/plan, workflow, job, assignment, project, agent, and canonical workspace attribution.
- Layered `AGENTS.md` lookup stays between descriptor root and active confined path and generates no starter content.

## Implementation Steps

1. Add `OutputArtifactRepository`. Move all active `run_output_artifacts` bootstrap/compatibility, insert, update, query, delete, and row mapping from `WorkspaceRepository`; switch every artifact consumer before removing old methods. Add only new columns and deterministic agent derivation. Preserve consistency between successful file publication and the artifact row, including cleanup/rollback on failure.
2. Replace artifact `workspaceId` fields with `workspaceType`, `workspaceKey`, and internal `workspaceIdentity` in `RunOutputArtifact`, `OutputArtifactContext`, and `OutputArtifactQuery`. Preserve all attribution. Store the published path in portable data-root-relative form derived from the trusted destination; never use that path for authorization.
3. Replace `AssignmentRequest` fields `workspaceId`, `selectedWorkAreaId`, `outputRouteType`, `outputWorkAreaId`, and `outputDirectRelativePath` with nullable-together type/key. Update template parsing and remove old constructors, constants, aliases, and deserialization paths.
4. Replace corresponding fields on `WorkAssignment`, `OrchestrationTaskContext`, job definitions/runs/summaries, plan runs, and workflow runs. Persist type/key/identity. In-process context carries the resolved descriptor and confined active path; repositories and public models carry no host root.
5. Centralize selection in `AssignmentService`:

   ```text
   neither field -> resolve agent/<boundAgentId>
   one field     -> reject
   agent/key     -> require key == boundAgentId; resolve agent
   user/key      -> resolve declared user workspace
   chat/key      -> reject
   ```

   Persist descriptor values. Validate project membership independently without altering the root.
6. Update active SQL and row mapping in runtime, job, plan, and workflow repositories to `workspace_type`, `workspace_key`, and `workspace_identity`. Add missing new columns to existing tables. Derive only from a valid new pair or authoritative agent attribution. Never select, join, infer from, or mutate obsolete routing fields. Exclude ambiguous rows from lease/resume while retaining history.
7. Update fresh `schema.sql` definitions for assignments, jobs/runs, plan runs, workflow runs, and artifacts. Omit Work Area/generic workspace foreign keys, output routes, direct output paths, project/run roots, links, and filesystem workspace leases. Existing obsolete columns and rows remain physically untouched.
8. Replace `EffectiveWorkspaceResolver` with the centralized descriptor result. Prefer `WorkspaceDescriptor` directly. If `EffectiveWorkspace` remains until Phase 06, it only wraps the already-resolved descriptor and has no project/work/run/scratch/alias/lease/output-routing fields. Move callers off output-directory routing; leave final class deletion to Phase 06 without stubs or TODOs.
9. Refactor `OrchestrationRunnerService`: remove project workspace acquisition, materialized links, filesystem lease heartbeat/release, and workspace-blocked requeue semantics. Retain queue coordination, cancellation, project membership/events, and attribution. Bind one descriptor to execution.
10. Refactor plan, completion, task, workflow, and job services together. Remove run/temp root creation and cleanup. Persist waiting selection and re-resolve it on resume. Bind file/shell tools to descriptor root and active confined path, enforce reserved-`outputs` protection, and remove legacy aliases. Resolve layered instructions only from existing `AGENTS.md` files under that path.
11. Publish through `WorkspaceDirectoryService.outputs(descriptor)`. Normalize the declared relative name once; reject absolute, empty, dot, traversing, or symlinked destinations; verify lexical confinement; and choose a collision-safe non-overwriting name atomically. Validate source real path and regular-file status against the same workspace before lazy directory creation. Preserve declared nested names, but add no attribution-based directory layers.
12. Remove run/temp artifact discovery, copied-temp promotion, project-root scans, and route dispatch. Keep explicit declared scalar/value materialization and controlled file-source publication. Reject republishing a reserved-output source onto itself.
13. Update all listed controller records, submission mappers, and stream adapters to type/key. Return controlled client errors for one-field, invalid, missing-user, chat, and agent-mismatch selections. Reject identity/roots and remove legacy fields rather than ignoring them.
14. Update output queries and responses to expose/filter type, key, identity, and existing attribution. Filters never select a root; project, run, artifact path, and canonical identity are not routing inputs.
15. In `OrchestrationController`, remove Work Area/output-route parameters, controls, and labels. Bound-agent forms omit both fields. Do not add temporary raw selectors; Phase 07 supplies the reusable selector. Keep submissions HTMX-first without replacement JavaScript transport.
16. Rewrite focused tests for every selection branch, persistence, existing-schema handling, deterministic agent compatibility, ambiguous history, waiting/resume, tool confinement, active paths, layered instructions, reserved outputs, direct publication, nested names, concurrent collisions, symlink and real-path confinement, failure consistency, attribution/querying, and absence of project/run/job/work/scratch/temp trees. Delete removed-contract tests.

## Required Guides

- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- Closest guides for tool packages and every additionally discovered active consumer
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- `.internal-dev/knowledge/task-workflow-schema-and-run-snapshots.md`
- `.internal-dev/knowledge/plan-execution-stream-finalization.md`

## Technical Guidance

Treat code as observed truth and the Phase 01 inventory/specifications as intended truth. Include any newly discovered active consumer in this vertical cutover rather than adding compatibility.

Validate edge cases explicitly: invalid/encoded keys; missing or symlinked user roots; lazy absent agent roots; user removal before resume; rows with pair but missing identity; rows with no deterministic selection; historical artifacts lacking canonical attribution; concurrent same-name publication; nested names; destination symlinks; missing/non-regular/cross-workspace sources; and database failure after file creation. Do not claim an artifact row for an unpublished file or leave an untracked published file after a failed record write.

## Validation

1. Run focused repository/model tests with fresh and existing-schema fixtures.
2. Instrument existing-schema tests to prove obsolete Work Area/generic workspace columns and rows are not selected, joined, updated, or used as fallback.
3. Run service, stream, and controller tests for all selection branches, legacy-field rejection, and controlled errors.
4. Run file/shell/runtime-context/`AgentsMdResolver` tests for descriptor binding, active-path confinement, reserved outputs, no aliases, and no generated starter content.
5. Run output tests for lazy publication, normalized nested names, concurrent collisions, source real-path confinement, destination symlinks, failure consistency, attribution, project-filtered queries, and no run hierarchy.
6. Execute representative task, plan, workflow, `WAITING`/resume, and job paths. Inspect the data root: only the selected agent/user root and successful lazy `outputs/` may appear; no project/run/job/work/scratch/link/temp tree may appear.
7. Search phase-owned paths for old workspace ids, Work Area selection, output routes, direct paths, project/run roots, filesystem workspace leases, and mixed artifact persistence. Phase 06 class definitions may remain only without active callers/providers.
8. Run all affected tests, then `mvn test`.
9. Run bounded Spring startup.
10. Inspect the final diff for dual contracts, stubs, TODO deferral, hand-built structural paths, accidental schema deletion, unrelated edits, and Phase 06/07 scope leakage.
11. Run the post-phase medium review-and-repair validator; repair findings and repeat until PASS.

## Exit Criteria

- Every active assignment, task, plan, workflow, job, runner, stream, tool, output, repository, query, and submission path uses one typed selection and one descriptor.
- Selection rules are centralized and covered; ambiguous history cannot lease or resume.
- Active repositories use only new fields and deterministic agent compatibility never consults obsolete identity.
- `OutputArtifactRepository` independently owns artifact persistence and no artifact consumer depends on generic workspace persistence.
- Projects retain records, membership, events, and attribution but select no root or filesystem lease.
- Waiting workflows re-resolve their exact persisted selection without guessing.
- Tools, prompts, active paths, and layered instructions are descriptor-confined and cannot mutate reserved `outputs/`.
- Outputs publish lazily, collision-safely, and attributably beneath the selected root with no run/project/job hierarchy.
- No active consumer remains for classes Phase 06 will delete.
- Focused tests, `mvn test`, bounded startup, filesystem inspection, stale-reference searches, and diff review pass.
- The medium validator reports PASS; Phase 06 is then unblocked.
