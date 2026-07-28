# Security

Current alpha access is intentionally open at the application layer. Magenta does not currently enforce built-in HTTP authentication, authorization, or CSRF checks on web/API routes.

## Access Rules

`GET`, `HEAD`, and `OPTIONS` routes are public, and unsafe methods (`POST`, `PUT`, `PATCH`, `DELETE`) currently reach controller/service validation directly without an app-layer auth gate.

This keeps local/trusted alpha operation simple, but it is not a multi-user security model.

## Error Shape

Request failures are primarily domain and lifecycle failures:

- Validation/input failures generally return `400`.
- Missing records generally return `404`.
- Lifecycle/state conflicts generally return `409`.
- Unexpected failures return controller or framework `5xx` errors.

## Public Read Surface

Public alpha reads include, but are not limited to:

- Browser pages and fragments.
- Chat history/session reads.
- Model summaries.
- Dashboard summary.
- Plan/task/workflow/job/project/workspace/output reads.
- Agent profile and runtime state reads.
- Selector options/selected/validation GET routes.

Do not put sensitive secrets, raw credentials, or unrestricted filesystem content behind public `GET` routes. If a new read route exposes sensitive content, it needs a different security policy before implementation.

## Mutation Surface

Unsafe routes include:

- Chat turns, commands, interrupts, and conversation metadata changes.
- Plan/task/workflow/job/project/agent/settings CRUD.
- Assignment lifecycle controls.
- Schedule/reaction changes.
- Confined workspace file writes (project links and generic workspace routes are absent).
- Inbox read/handled/respond actions.
- Artifact retention, skill trust/revocation/overlay, schedule, and reaction state changes.

Because there is no app-layer auth gate, these routes rely on controller/service validation and bounded runtime behavior for safety.

## Authorization Scoping Patterns

Application-level login/role authorization is not implemented, but domain reads still enforce resource scope before data access:

- Workspace APIs resolve a closed `agent|chat|user` kind plus validated key through `WorkspaceDirectoryService`.
- Task/runtime APIs accept only task-capable `agent|user` scopes; chat selection is rejected.
- Agent run fragments verify the agent, query only `agent/<agentId>`, then recheck each run's persisted workspace pair.
- Artifact search/list/detail applies workspace kind/key and optional task-run scope before counts, snippets, rows, lineage, links, or bytes. Byte open/stream rechecks scope and integrity.
- Schedule/reaction reads are agent-scoped before audit is rendered.

These are domain-scoping controls, not principal authentication. Unauthorized or out-of-scope records should use non-disclosing missing responses rather than reveal counts or existence.

## Writer Claims And Fencing

Assignment lease fences and task-run writer claims are separate monotonic capabilities. Guarded lifecycle transitions verify the expected assignment owner/fence. Filesystem/publication mutations additionally bind workspace kind/key, task-path key, run id, attempt, writer owner, and writer fence to a trusted run location and recheck immediately at the mutation boundary.

The security contract forbids claim secrets, owners used as capabilities, fence generations, lease tokens, and operation handles in API/fragment payloads, user-facing logs, prompts, or examples. A stale or superseded writer must affect zero guarded rows and cannot publish, checkpoint, finalize, or clean task-owned staging.

**Known blocking drift:** Phase 10 security closeout cannot pass until all of these are repaired:

- `GET /api/agents/{agentId}/schedules/{scheduleId}/firings/{firingId}/attempts` serializes the persistence-facing `ScheduleFiringAttempt`, including `claimOwner` and `claimFence`, and does not prove the firing belongs to the path schedule/agent.
- Task-run-specific `/api/runtime/runs/{taskRunId}/*` JSON reads accept only the run ID, without workspace kind/key scope, before returning summary, measurements, normalized status, or composed audit.
- `/api/agents/{agentId}/event-reactions/dispatches/by-root-event/{rootEventId}` does not filter returned dispatches by the path agent.

These endpoints need secret-free, scope-qualified response paths. The scoped agent Files run fragments and HTMX attempt tables do not render claim/fence fields, but that does not repair the JSON routes.

## Context And Binding Immutability

Context defaults use expected-version CAS: concurrent updates conflict, and stale-version updates are rejected. Bindings are insert-only immutable snapshots; they are never retargeted or updated after capture. Assignment-binding operations provide a single durable decision point (`RESERVED` → `COMPLETED`) that links context capture, assignment insertion, and lifecycle reservation. Reusing an operation key with the same canonical request digest converges; contradictory reuse conflicts without mutation.

Trusted ready-location resolution validates an expected generation, and provisioning's state transition uses the loaded generation for CAS. Stale generation, revoked, non-ready, or failed state prevents mutation. Provisioning uses descriptor-confined held-handle primitives; a persisted root-relative path assertion verifies a location but never authorizes it.

## Snapshot Immutability And Revocation

Skill approval is digest-qualified. The service re-reads the confined candidate, verifies identity and digest, and stores immutable snapshot metadata/resource bytes. Runtime disclosure and activation read the approved snapshot, never the mutable candidate source. Source edits create a new pending candidate. Revocation blocks new activation while preserving historical activation evidence and allowing only explicitly recorded same-run resume rehydration.

Instruction approval is not executable authorization. Executable resources and package ingestion remain disabled while process confinement and process-tree revocation are unproven.

## Identity And Path Non-Disclosure

Persisted new filesystem locations are `(workspaceKind, workspaceKey, rootRelativePath)`. Services must not:

- persist an absolute workspace, run, artifact, staging, package, or skill path;
- accept a canonical workspace identity or root from callers;
- derive workspace identity, authorization, task lineage, or project membership from a `Path`;
- treat project metadata as filesystem authority;
- expose absolute paths in API, HTMX, prompt, audit, or error responses.

`WorkspacePathLayout` owns structural path grammar and `WorkspaceDirectoryService` alone constructs trusted descriptors/locations.

## Tool Safety

Agent tools are controlled outside HTTP security:

- Approved tool names are validated through `ChatToolRegistry`.
- Shell commands are constrained by per-agent allowlists.
- Wildcard shell allowlists from legacy file config are ignored unless the explicit unsafe wildcard override is enabled.
- File tools resolve paths through trusted descriptors/run locations and recheck writer capability at mutation boundaries.
- Ordinary filesystem mutation uses held no-follow relative directory handles on the supported Linux/default-provider capability path; unsupported providers fail closed.
- Host shell/process execution and skill executable authorization remain disabled when process confinement/process-tree revocation cannot be proven.
- Artifact preview/download rechecks workspace/task-run scope and verifies digest plus size for the exact bytes returned. Integrity failure suppresses byte access.

These checks should stay in tool/workspace services, not controllers.

## System Chat Boundary

System chat (`SystemChatService`) is a fixed deny-by-default profile per agent. It is server-created; runtime settings currently supply enabled state and model, while the system prompt, callback allowlist, and context bound are server-owned constants. It cannot be selected or created through the public `INTERNAL` surface.

- **Bounded reads:** `SystemChatBoundedReadService` provides non-secret agent-status counts, workspace metadata facts (type/size only), and assignment lifecycle facts. It returns no file content, paths, secrets, project attribution, or cross-agent data.
- **Restricted tools:** `SystemChatToolRegistry` allows only read-only query tools and proposal creation. File write, shell execution, skill approval/activation, and cross-agent access are denied at the tool boundary.
- **Proposal confirmation gating:** Proposals (`PAUSE`, `RESUME`, `RETRY`, `REPLAY`, `CANCEL` on assignments) are persisted with exact target, action, canonical payload digest, and operation key. They are non-executing. Confirmation passes through the ordinary scoped lifecycle control plane requiring exact matching proposal, target, state, confirmation, operation key, and authorization. A proposal cannot execute itself.
- **SSE render safety:** `system-chat.js` uses bounded event parsing, `textContent`-only rendering, verifies returned conversation belongs to the mounted agent, and cleanly cancels/resets on terminal, error, abort, or host replacement. No `innerHTML`, generic fetch helpers, or HTML injection.

## Additional Safety Controls

- Plain path segment validation for filesystem/path-derived ids.
- Inert rendering of user-authored workflow graph text.
- Assignment lifecycle route-agent scoping.
- Agent run fragments validate the agent, then recheck summary workspace type/key before audit expansion; unavailable/missing responses disclose no cross-scope counts or run facts.
- Artifact FTS5 queries include scope predicates before counts/snippets; unsupported FTS5 has no broad fallback.
- Skill approval binds an exact candidate digest to immutable snapshot bytes. Instruction approval is not executable authorization, and pending/untrusted/changed-digest/revoked candidate content is not model-visible.
- Schedule/reaction operation-key digest idempotency, claim fencing, revision-qualified identities, and causation depth/rate/loop suppression.
- Simulations are capability-free and create no assignment, run, firing/dispatch audit row, tool call, artifact, event, or external mutation.

## Current Limitations

Alpha mode is not a multi-user authorization model. Deploy behind trusted network boundaries and add a dedicated auth/authorization layer before exposing sensitive operational surfaces broadly.
