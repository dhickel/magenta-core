# Agent Workspace Establishment Plan

## Architecture

Magenta retains the closed `WorkspaceKind` set: `AGENT`, `CHAT`, and `USER`. `WorkspacePathLayout` is the sole structural path grammar. `WorkspaceDirectoryService` alone constructs trusted `WorkspaceDescriptor` values and trusted workspace-child locations. Projects remain membership and attribution metadata; they never become workspace identities, roots, descriptors, aliases, links, mounts, or leases.

The existing execution selection remains authoritative: `workspaceType` and `workspaceKey` are paired; omission selects the bound agent; an explicit `AGENT` must match that agent; an existing `USER` workspace may override it; `CHAT` is rejected for execution. Captured materialization context is supplemental to that selected descriptor and never replaces it.

Each AGENT workspace has conceptual lazy areas: `.skills`, `scripts`, `apps`, `home`, `repos`, `projects`, `tasks`, and `outputs`. Unknown existing root children retain only the reads already permitted by existing policy and are not implicitly migrated, relocated, deleted, or made writable. Personal content is limited to `home`, `scripts`, and `apps`; scripts and apps are storage only, not execution authority. Materialization services own `projects`. `repos` remains a conceptual, lazy area for user-managed storage outside Magenta: permitted existing-content reads stay descriptor-confined, while Magenta never provisions, mutates, interprets, or assigns application behavior to the area. Existing lifecycle, publication, and skill owners retain their reserved trees.

## Conceptual Approach

Build inward-out: first close ordinary AGENT-root writers and preserve CHAT/USER behavior; then persist materialization identity, defaults, and insert-only bindings; then adopt those bindings across chats and executable lifecycle; then add trusted prompt and skill composition, restricted system chat, and thin web adapters.

Materializations use only trusted descriptor-relative locations beneath `projects/<stable-key>/`. A project materialization is an agent-scoped local context container, not a source-acquisition mechanism. Provisioning may produce an empty container; `READY` means only that its canonical location is safely available. Content, when present, is supplied by out-of-band local administration rather than a Magenta acquisition or editing flow in this plan. Provisioning and reconciliation use verified confined primitives; a persisted root-relative assertion verifies a location but never authorizes it. Human-authored guidance remains untrusted, bounded, visibly labelled, and confined to its owning trusted root. Skills remain snapshot-only and do not gain executable authority.

## Features

- Closed AGENT-root policy with lazy conceptual navigation and owner-specific provisioning.
- Stable, framed, identity-derived materialization keys; closed `PROVISIONING`, `READY`, `FAILED`, and `REVOKED` lifecycle states; data-root-relative verified location assertions; and generation-safe project context materializations.
- Versioned dashboard defaults that affect only new captures, plus immutable context-binding snapshots for conversations, assignments, native runs, resume, retry, replay, workflow children, and job work items.
- Deterministic trusted prompt facts from captured bindings; labelled and bounded untrusted `AGENTS.md` and `WORKSPACE.md` guidance; exact generation-bound materialization skill authority; and immutable-snapshot-only skill disclosure and activation.
- Fixed-profile, deny-by-default system chat with bounded non-secret reads and persisted, user-confirmable queue-operation proposals; it cannot execute a proposal or broaden its authority.
- Thin secret-free agent-scoped APIs and separate Files, context, queue, run, skill, and system-chat HTMX hosts in `/agents/{agentId}`. SSE parsing in system chat is the only new JavaScript transport.

## Constraints

- Never add `PROJECT`, project roots or descriptors, generic workspace identity, aliases, links, bind mounts, filesystem leases, shared writable roots, or a physical or virtual `/current-workspace/` path.
- Never reconstruct identity from `Path`, hand-concatenate structural roots, persist absolute Magenta-owned paths, accept caller-supplied workspace roots, or treat a persisted location string as path authority.
- Derive canonical locations at use from trusted identity and captured generation. Persist only data-root-relative assertions where needed and recompute and verify them through the layout, directory, and root-relative path authorities.
- Reads never create directories. Authorized writes create only their owner-approved canonical chain. AGENT and CHAT roots remain lazy; USER roots require explicit creation.
- Bindings are insert-only and never retarget. Default changes use expected-version CAS. Materialization lifecycle operations use expected-generation CAS. Stale, revoked, non-ready, failed, or out-of-bound work fails closed without mutation.
- Externally retriable submissions and named stateful operations use a canonical request digest and operation key: same key and digest converge; contradictory reuse conflicts. This does not replace the expected-version or expected-generation guards required for defaults and lifecycle transitions.
- Assignment leases and task-run writer fences remain independent current capabilities.
- Migrations 001–009 are immutable. Migrations 010–011 are contiguous append-only resources, solely owned by `SchemaMigrationRunner`, checksum-protected, transactionally applied, and represented equivalently by fresh and upgraded schema metadata. Persistence classes add no constructor DDL for these domains.
- No records, prompts, APIs, fragments, logs, audits, exceptions, or test-visible user-safe output may expose secret values or references, credential-bearing URLs, absolute paths, internal resolved endpoints, claim owners, fences, tokens, or raw failures.
- Publication keeps its existing create-only/no-replace semantics. Existing task-run, output, CHAT, USER, and skill contracts remain unchanged except where an explicit captured-context integration is required.

## Assumptions

The supported Linux/default-filesystem held-handle confinement boundary is available for writable capability; unsupported filesystem implementations fail closed. Existing unknown AGENT-root content remains in place and read-only where current reads permit it. Existing project association or skill association grants neither filesystem authority nor skill trust.

## Cross-phase Guidance

- Each implementer reads its listed guides and completed dependency evidence, changes only the declared primary targets plus orchestration-authorized focused test/package-guide companions, and escalates an architecture or public-contract conflict rather than silently absorbing it.
- Persisted materialization states are exactly `PROVISIONING`, `READY`, `FAILED`, and `REVOKED`. Typed operation outcomes distinguish at least `UNAVAILABLE`, `BUSY`, `STALE`, `FAILED`, and `RECONCILED` where applicable. Missing facts never mean ready, authorized, or successful.
- Materialization registration, binding capture, assignment creation, run reservation, owner links, and idempotent-operation records are transactional where their consistency requires it. Filesystem provisioning remains outside database transactions and is reconciled explicitly.
- Every filesystem mutation authorizes and revalidates the exact source and destination immediately at the held-handle primitive. It rejects traversal, encoded separators, links, hard links, control names, rebinding, stale descriptors, generation mismatch, and scope escape.
- Prompt order is fixed: immutable Magenta policy; trusted captured binding and derived allowed-path facts; mode and task intent; labelled untrusted human guidance; approved immutable skill snapshots. Allowed write/tool paths derive only from the independently selected execution descriptor and current lifecycle capability; a materialization remains supplemental read-only context. Prompt ordering clarifies provenance but grants no authority. Binding or trusted-resolution failure ends the turn before model or tool use.
- `AGENTS.md` remains descriptor-confined and ancestor-inclusive with closest-file conflict precedence. `WORKSPACE.md` is optional bounded human guidance, not configuration. Candidate discovery, association, assignment, approval, activation, and executable authority remain separate skill decisions.
- System chat is constructed positively from a fixed bounded-read and proposal registry. It cannot be selected through a public INTERNAL surface, write files, invoke a process, approve or activate skills, reveal secrets, or access another agent. Proposal confirmation passes through the ordinary scoped control plane with exact target, idempotency, confirmation, authorization, and audit.
- Renderers receive only authorized secret-free values. Every fragment independently rechecks agent and context scope. HTMX owns standard interactions and same-domain OOB refreshes; stale responses do not replace unrelated hosts.
- A validator repairs in-scope defects and records PASS before a dependent phase begins. An unavailable capability is a valid truthful outcome only when its callers remain unavailable rather than simulating success.

## Final Validation Criteria

- Focused confinement tests cover AGENT canonical-area allowlists, synthetic absent-area navigation, lazy creation, root and unknown-child denial, reserved names, source/destination enforcement, traversal, encoded separators, links, hard links, rename races, rebinding, stale descriptors, and unsupported-filesystem failure. CHAT, USER, task-run, output-publication, and skill behavior remain green.
- Migration tests prove immutable 001–009 resources and a contiguous 009-to-011 upgrade are metadata-equivalent to fresh schema. Gaps, malformed resources, duplicate names, checksum drift, rollback, partial state, constructor DDL, and concurrent starters fail safely.
- Conversation, assignment, native run, workflow/job child, restart, resume, retry, and replay tests prove immutable binding semantics. Default changes never retarget existing work; stale defaults, stale generations, ambiguous legacy rows, and contradictory operation reuse have no mutation side effect.
- Prompt, guidance, skill, and system-chat tests prove deterministic order, provenance labels, bounds, terminal trusted-resolution failure, descriptor confinement, generation authority, exact digest approval, immutable snapshots, revocation, deny-by-default registration, bounded reads, exact proposal confirmation, and secret-free output.
- API and fragment tests cover scoped pairing, expected-version and expected-generation conflicts, binding and operation identity, unavailable/busy/not-ready states, escaping, omission of sensitive fields, truthful empty project-context containers, and independent stable host replacement.
- Delegated live Playwright validation captures desktop and mobile screenshots and critiques density, hierarchy, alignment, overflow, affordance, first-viewport usefulness, and mobile stacking across Files, context, queue, runs, skills, and system chat. It verifies HTMX ownership and the narrow SSE-only JavaScript justification.
- Focused tests, the applicable Maven suite, bounded application startup, schema checks, forbidden-construct and dependency scans, secret/redaction scans, cancellation/restart/reconciliation checks, and final diff/status review pass. The final review rejects placeholders, unrelated API growth, restored workspace abstractions, migration rewrites, unapproved execution capability, and unacknowledged defects.
