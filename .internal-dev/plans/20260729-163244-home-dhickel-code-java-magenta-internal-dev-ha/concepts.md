# Project Dashboard and Unified Work Contracts

## Architecture

- `WorkAssignment` is the canonical authority for submitted-work identity, queue membership, priority, assignment lifecycle, lease state, and assignment ownership. Creation is insert-only; generic saves must never rewrite immutable attribution.
- Native execution remains explicitly split: `JobRun` owns native run identity, native-run state, and native result; `WorkAssignment` owns assignment lifecycle and queue state. A Phase 01 field-level authority matrix must identify every field, writer, repository method, and consumer across assignments, Job definitions, Job runs, task attempts, transcripts, outputs, cancellation, retries, and terminal results. No mutation is enabled until that matrix establishes one writer per field.
- Immutable assignment attribution includes, at minimum, agent, project, assignment type, executable source-definition link, workspace selection, and immutable context binding. The matrix determines whether additional persisted facts are immutable or writer-owned; unknown fields default to no generic mutation path. Operator, runner, and lease writes remain narrow and disjoint. Lease heartbeats do not advance the assignment business version.
- Agent and project queues are projections of the same assignment row through one `AssignmentQueryService`. Server-created sealed `AgentScope` and `ProjectScope` make route scope structurally mandatory. Bounded snapshots fix result membership and traversal order; display and actions re-resolve canonical scoped rows.
- The public Job write model is selected only after repository evidence confirms the authority matrix. The intended target is `ai.orchestration.runtime.JobDefinition`/`JobRun` through `JobService`/`JobRepository`; `ai.agent.job.AgentJob` remains an internal conversation-title mechanism and is excluded from project APIs and views. A legacy adapter may provide bounded reads but cannot create dual writes or a permanent union authority.
- `WorkTaskDefinition` is a lightweight tracking definition distinct from `PlanDefinition`. A defined idempotent promotion contract creates or records exactly one executable Plan link without silently converting the Task. Assignments execute the linked Plan and retain Work Task submission origin; task state is derived from the designated canonical attempt rather than copied lifecycle state.
- Project materialization uses logical heads and immutable generation rows. Lifecycle CAS changes a lifecycle version, never a generation identity. Historical bindings resolve their exact generation; `GENERATION_V2` roots remain outside legacy roots. Legacy import is permitted only after repository evidence proves a trusted, byte-preserving mapping.
- Schedules, reactions, and events extend existing revisioned engines with typed immutable project attribution. `CRON` and `ONE_TIME` are mutually exclusive modes; revision heads select the sole current definition for polling and dispatch.
- Project chat is a restricted profile over existing chat metadata and `ai_chat_memory`; it introduces no transcript store. A conversation fixes its project, selected member agent, and exact ready materialization generation. Projects never become workspace authorities, and workspace kinds remain `AGENT`, `CHAT`, and `USER`.

## Conceptual Approach

1. Establish evidence-backed assignment and Job authority, immutable attribution, capability gating, archive/member guards, structurally scoped queries, idempotency, and migration support.
2. Extract only the proven project-console seam and demonstrate that agent and project Queue views expose the same canonical assignments under different mandatory scopes.
3. Add lightweight Tasks and public Jobs as distinct definitions that submit canonical assignments, preserving executable source separately from submission origin and avoiding hidden dependency on either domain.
4. Preserve filesystem confinement through immutable directories-only template generations and authorize project Files and Outputs through exact trusted bindings and durable links.
5. Extend revisioned automation and trusted event attribution rather than creating project schedulers, dispatchers, event stores, or inferred membership scope.
6. Add restricted project chat with a fixed server-owned profile, exact generation revalidation, serialized idempotent turns, bounded reads, confirmable Queue proposals, and safe SSE rendering.
7. Integrate grouped navigation and isolated HTMX hosts only after each domain contract passes its own authority, migration, security, and behavior proof.

## Features

- Shared secret-minimized assignment summaries; deterministic active and history Queue filters; authenticated opaque bounded cursors; exact-link transcript and diagnostics access; priority and cancellation controls when capability and lifecycle gates pass.
- Lightweight Task CRUD, project-local pinning, default priority, explicit preferred agent, explicit Plan promotion, canonical attempt lineage, retry, and reassignment lineage when a Task-attributed predecessor exists.
- Project Job definition and run drill-down, future-submission preferred-agent updates, and queued/unleased revoke-and-clone reassignment.
- Directories-only materialization templates, exact-generation read-only Files, and read-only Outputs resolved through durable assignment/run/publication relations.
- Project-scoped schedules, reactions, simulation, firing and dispatch audit, and trusted event audit.
- Restricted project chat with an explicitly selected current member, immutable generation, bounded memory, safe SSE streaming, and human-confirmable Queue proposals.
- Grouped navigation: Overview/Dashboard; Work/Queue, Tasks, Jobs, Outputs; Context/Files, Chat; Automation/Schedules, Events; Administration/Members, Settings.

## Constraints

- The server-owned trusted-deployment gate defaults false. It is a capability decision, not authentication: it trusts reachable callers only when enabled. Each service entry point for a sensitive read or mutation enforces the same capability decision; controllers map its result and disabled UI states expose no active control. Resource scope alone never authorizes access.
- Archived projects preserve historical records and reject new work, membership changes, materializations, controls, automation activity, turns, and proposals. Archive and member removal are atomic guarded operations: they reject while active assignments, designated Task attempts, Job runs, enabled automation, provisioning materializations, in-flight turns, or live proposals would be stranded. Historical access remains capability-gated and never recomputes attribution from current membership.
- Project-attributed assignment submission validates an active project and a current eligible executor. JSON `input.projectId`, caller paths, event payloads, filenames, and historical membership are never authoritative project identity.
- Transcript lookup uses exact persisted assignment/conversation links. Project routes never use time-window, name, timestamp, or filesystem inference.
- List and error DTOs exclude raw input, raw output, evidence, unrestricted errors, internal paths, lease owners, fences, checkpoints, secrets, and unneeded sensitive content. Titles, descriptions, filenames, transcript text, and output metadata receive only the disclosure required by the authorized view.
- Operation keys and canonical digests bind a server-defined caller/actor context, action, route scope, resource, expected revision, and canonical request semantics. The same key and digest converge; changed semantics conflict. A client-supplied actor value is never trusted. Where a trustworthy caller binding is absent, sensitive capability remains disabled.
- Priority requires queued, nonterminal, unleased state and expected assignment row version. Cancellation follows the established direct-cancel versus cancellation-request lifecycle and never bypasses writer fencing.
- Job reassignment is limited to queued, unleased assignments without an allocated native `JobRun`. It is a durable revoke-and-clone operation with persisted request, source, destination, successor, lineage, and outcome. It must complete atomically or recover to one explicit terminal outcome; it must not leave a permanently revoked source without a recorded resolution. Running transfer, in-place agent mutation, and predecessor workspace/artifact copying are unavailable.
- Pinning changes presentation only. Default priority is copied only into new assignments. Preferred-agent selection is explicit: a project-bound preference is a current project member, and an unbound preference satisfies the established named-agent submission eligibility rule. Neither form infers a fallback executor.
- Directory templates contain normalized relative directory paths only. They reject absolute, blank, dot, traversal, backslash, symlink or symlink-component use, file/content or executable directives, overwrite directives, duplicates, and prefix conflicts. Provisioning is create-only, no-follow, confined, fully verified, and never `READY` after partial application.
- Output list, metadata, preview, and download use the same authorization resolver over exact durable assignment, task-attempt, native-run, lineage, and publication links. They never authorize from denormalized project text or filesystem scans and never replace canonical publication ownership.
- Standard controls use HTMX. The only new JavaScript transport is `project-chat.js`, limited to bounded safe SSE parsing and rendering, cancellation, and autoscroll. It validates event and conversation identity, uses inert text rendering, and is reset safely on host replacement.
- Project chat has a fixed server-owned prompt, model routing, and deny-by-default tool profile. It permits only bounded service-authorized metadata and exact-link reads; it denies filesystem writes, process execution, skill activation, package ingestion, unrestricted file reads, and ordinary-chat tool access. Generic chat list, lookup, history, and stream routes exclude project-chat origin.

## Assumptions

- `owner_agent_id` is the intended single stored Job preferred-executor field. Payload aliases such as `preferredAgentId` map to it only when non-contradictory; repository evidence must confirm compatibility behavior.
- Work Tasks have no direct runtime family. They execute only through an explicitly linked executable Plan, and the promotion contract must be proven idempotent without Plan/Task dual-state synchronization.
- Project association is attribution and context metadata, not workspace selection. Agent execution uses the selected agent workspace unless an already-valid explicit `USER` workspace is allowed by the established submission contract.
- Unstarted revocation is available only if lifecycle evidence proves that no native run or fenced writer can start or commit after revocation. Otherwise reassignment remains unavailable for that state.
- Legacy materialization rows may become trusted `LEGACY_V1` generations only if migration inspection proves exact historical resolution without moving bytes. Future generations use recomputable `GENERATION_V2` roots.
- Existing chat memory may store project-chat visible messages only if metadata identity, conversation ownership, serialization, bounded reads, operation replay, and provider-failure recovery are proven. No database transaction spans provider inference.
- Migration identifiers `014` through `019` remain contiguous only after migration-runner discovery and supported-upgrade inspection confirm the immutable `001`–`013` baseline, checksum rules, and constructor-DDL behavior.

### Evidence Gates

- Record repository evidence for the field-authority matrix, public Job write owner, legacy-read boundary, access/caller binding, schema/index feasibility, archive/member-removal guards, unbound preferred-agent eligibility, and writer-quiescence mechanism.
- Contradictory evidence stops the affected capability and requires contract escalation; it does not permit a parallel authority, optional scope, compatibility write, or page-local exception.

## Cross-phase Guidance

- Create a dedicated implementation branch before Phase 01. Each accepted phase commit contains its implementation, tests, documentation, and ready internal-development updates.
- Treat code as observed truth and specifications as intended truth. Escalate the affected contract when repository evidence contradicts it; do not resolve contradictions by adding a second authority, optional scope, compatibility write, or page-local exception.
- Keep migrations additive or carefully rebuilding, warm-safe, and failure-safe. For each migration phase, update `schema.sql`, `SchemaMigrationRunner`, fresh-install coverage, supported-upgrade coverage, checksum coverage, partial-structure rejection, and metadata-equivalence coverage. Constructor DDL must not recreate superseded shapes.
- Preserve executable source versus submission origin. Source identifies Plan, Workflow, or Job. Origin identifies Work Task, schedule revision, reaction revision, reassignment operation, or manual submission. Cross-domain consumers use durable links and treat an absent optional origin as absent rather than inventing state.
- Reuse trusted workspace, context, lifecycle, output, revision, and fenced services. Never construct workspace identity from a `Path`, authorize from caller-controlled project data, or infer historical project membership.
- The Job phase is correct independently of Task provenance. When a predecessor has verified Task origin, reassignment appends and designates the successor attempt atomically; when it does not, Job reassignment retains its own canonical lineage. No phase may require an optional prior-domain capability without a verified seam.
- Build project fragments as stateless renderers over authorized redacted DTOs. Stable hosts replace only themselves, OOB swaps remain inside the owning domain, and inspectors re-resolve canonical scoped resources.
- Inspect current SimplyPages documentation and matching demos before every UI phase. If configured documentation is unavailable, stop the UI edit rather than substituting raw-markup or broad JavaScript workarounds.
- Each phase validator inspects its final diff for whole-row writes, duplicate authorities, unbounded queries, sensitive DTOs, unauthorised capability paths, unjustified JavaScript, placeholders, and unrelated edits.
- Complete internal-development closeout only when the store is ready; never initialize or repair it implicitly.

## Final Validation Criteria

- Fresh and supported warm schemas apply migrations `001`–`019`, are metadata-equivalent, preserve required historical reachability, and reject checksum, partial-structure, foreign-key/index, and revision-shape drift. Repeated application and failed migration paths leave no ambiguous schema state.
- The verified authority matrix proves immutable assignment attribution cannot change. Creation, operator, runner, lease, Job definition, Job run, Task-attempt, and automation writers affect only their owned fields; no production path performs an assignment whole-row update.
- Agent and project Queue overlap shows identical assignment identity and canonical assignment status. Scope cannot widen through filters, cursors, resource IDs, or inspector links; snapshot pagination has no duplicate or skipped snapshot members under concurrent changes, and canonical re-resolution reports changed or unavailable rows safely.
- Gate-disabled, unauthorized, archived, missing, cross-scope, and capability-unavailable routes fail closed without leaking sensitive content. Archive and member removal reject stranding dependencies while preserving historical assignments, transcripts, outputs, lineage, and audit.
- Task concurrency preserves one active attempt, explicit executable Plan linkage, immutable historic project binding, copied default priority, named-agent eligibility, and no automatic executor fallback. Task and Plan edits remain independent after promotion.
- Public Job write authority is singular; definition state is not overwritten by runs; native-run state remains distinct from assignment lifecycle; queued reassignment is atomic or recoverably resolved, idempotent, fenced, lineage-complete, and leaves historical predecessor artifacts attached to the predecessor.
- Historical materialization bindings resolve exact generations. Invalid or partial template application never reaches `READY`; no read creates a workspace directory; Files remain single-member, confined, and read-only; project Outputs use durable-link authorization with identical list/metadata/preview/download scope behavior and no scans.
- Revision heads select append-only current automation definitions. One-time schedules fire once across restart; duplicate claims and effects honor defined idempotency; project/member/archive/capability failures audibly audit; spoofed payload identity never matches project reactions; simulation is pure.
- Project chat preserves immutable conversation/project/agent/generation identity, gate and membership revalidation, durable idempotent serialized turns, bounded memory, recoverable provider failure, deny-by-default tools, safe SSE, proposal target verification, and generic-route exclusion.
- Focused tests, relevant broader Maven checks, static checks, enabled and disabled bounded Spring Boot startup, and live-app Playwright subagent validation pass for every changed surface. Browser evidence covers functional controls, HTMX host isolation, SSE cancellation and replacement, deep links, empty/error/disabled states, desktop and mobile visual quality, keyboard/focus/ARIA behavior, alignment, density, hierarchy, wrapping, overflow, and first-viewport usefulness.
