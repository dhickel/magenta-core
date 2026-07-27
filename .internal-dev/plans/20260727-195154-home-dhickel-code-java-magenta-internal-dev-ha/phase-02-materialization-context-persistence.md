# Phase 02 — Materialization and Context Persistence

## Context

Phase 01 closes ordinary AGENT-root writers and supplies the closed-area policy. This phase adds durable project-materialization identity, versioned defaults, and immutable context-binding snapshots without changing execution selection: `workspaceType` and `workspaceKey` remain authoritative, while a captured materialization context is supplemental.

## Goal
persist generation-safe materializations, defaults, and immutable binding snapshots

## In Scope
**Write Targets**: src/main/resources/db/migrations/010-agent-materialization-context.sql, src/main/resources/schema.sql, src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java, src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentContextService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentContextRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentMaterializationService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/context/AgentMaterializationRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/MaterializationLocation.java

Create typed materialization and context aggregates within these targets; append migration 010; add fresh/upgraded schema validation; persist versioned defaults and insert-only binding snapshots; and resolve ready project context containers only through the trusted workspace-location authority. Provisioning establishes an empty-or-existing canonical container; it does not acquire or populate project content.

## Out of Scope

Conversation, assignment, task-run, workflow-child, job-work-item, resume, retry, and replay adoption; APIs and web adapters; project-content acquisition/import/editing; and changes to Phase 01 policy or layout grammar. This phase does not assign behavior to `repos` or provision anything outside a project materialization beneath `projects/`.

## Dependencies
phase-01-agent-workspace-policy.md

## Required Guides
- `AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`
- `.internal-dev/knowledge/schema-data-ownership-validation.md`
- `src/main/java/io/mindspice/magenta2/core/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`

## Implementation Preconditions

- Phase 01 validator PASS evidence must prove that `WorkspacePathLayout` owns canonical `projects/<stable-key>` grammar, ordinary writers cannot mutate `projects` or `repos`, and the held-handle confinement boundary can create an exact owner-approved canonical chain without a path-based fallback.
- This phase explicitly owns the `WorkspaceDirectoryService` integration that constructs `MaterializationLocation` from a resolved AGENT descriptor, key, generation, and expected canonical child. The location constructor remains package-private in the workspace package; no context service may construct it or hand-build its path.
- Focused test companions and any responsibility-driven nearest package-guide update follow the orchestration companion-edit policy; they do not authorize another production target.

## Constraints

- Migrations 001–009 and their checksums are immutable. Migration 010 is a contiguous append-only resource, and `SchemaMigrationRunner` remains the sole migration owner. New persistence classes contain no constructor DDL.
- `WorkspaceKind` remains closed to `AGENT`, `CHAT`, and `USER`. Materializations belong to an AGENT; project membership is validation and attribution metadata, never a workspace root, descriptor, alias, link, mount, or lease.
- `MaterializationLocation` is a trusted value, not a persisted-path wrapper. It carries only the materialization identity, captured generation, verified data-root-relative assertion, and confined path constructed by `WorkspaceDirectoryService`. No service reconstructs it from a `Path`, `Path.of(storedValue)`, a root string, or a caller-supplied location.
- Persist only data-root-relative assertions. Compare a stored assertion with the location recomputed from trusted identity before use. Absolute paths, link targets, and untrusted external facts are not stored or returned.
- Bindings are insert-only. Defaults change only with expected-version CAS. Persisted materialization states are exactly `PROVISIONING`, `READY`, `FAILED`, and `REVOKED`; every lifecycle transition changes generation only with expected-generation CAS. A stale, revoked, non-ready, failed, missing-owner, or location-mismatch fact fails closed without filesystem or database mutation. `READY` asserts only safe canonical availability, never imported or synchronized content.
- A stateful registration, binding capture, or owner-link request uses an operation type, canonical request digest, and operation key: same type/key/digest returns the recorded result; a changed digest conflicts. This supplements, never replaces, expected-version and expected-generation guards.
- Personal selections are the closed values `HOME`, `SCRIPTS`, and `APPS`; each maps only to its Phase 01 canonical area. They are not aliases, root selections, or execution authority. A materialization selection is explicit and captures a ready materialization key plus generation.

## Persistence Matrix

| fact | durable identity | location assertion | allowed state in this phase |
| --- | --- | --- | --- |
| project materialization | agent id + `PROJECT` + existing project id | required only after canonical project-area provisioning | exactly `PROVISIONING`, `READY`, `FAILED`, or `REVOKED` |
| personal binding selection | agent id + one of `HOME`, `SCRIPTS`, `APPS` | none | immutable binding/default snapshot only |
| materialization binding selection | agent id + materialization key + captured generation | recomputed and verified only when resolved | binding exists only for a ready captured generation |

The serialized materialization key is `v1-<kind-token>-<lowercase-sha256>`. Digest the following unambiguous frame, with each text field encoded as UTF-8 and each length an unsigned two-byte big-endian value:

```text
0x01 | kind-token | u16(agent-id) | agent-id | u16(source-kind) | source-kind | u16(source-id) | source-id
```

`PROJECT` uses the existing project id as `source-id`. Reject unsupported kind/source-kind pairs before hashing. Literal test vectors cover empty-field rejection, non-ASCII UTF-8 byte lengths, kind separation, and metadata renames. Titles, display names, timestamps, and path strings are never frame inputs.

## Implementation Steps

1. Define the typed domain surface within the declared context targets: a versioned `MaterializationKey`; materialization kind, source kind, the closed lifecycle state, selection kind, and typed outcome values; immutable materialization/default/binding/owner-link records; and canonical digest input records. Keep public domain types nested in their owning declared service or persistence target when a separate source file is not ledger-authorized. Validate frame inputs before hashing, retain literal vectors, and make no model type accept an absolute path or workspace root.
2. Add immutable migration `010-agent-materialization-context.sql` and its exact fresh-schema counterpart. Create materialization, context-default, context-binding, typed binding-owner-link, and operation-idempotency tables plus only their needed indexes. SQL must enforce closed enums, SHA-256 digest length, positive version/generation, unique `(agent_id, materialization_key)`, one default per agent, unique `(owner_type, owner_id)`, and null-pair rules for personal versus materialization selections. Use row-local checks only; validate project membership, owner existence, source-kind compatibility, and materialization/binding generation agreement in transactional services. Do not add an absolute-path, generic-workspace, project-root, alias, or lease column.
3. Extend `SchemaMigrationRunner` through owned version 010. Add the 010 expected table, column, index, and constraint checks to the versioned validation path, preserve discovery and exact-byte bookkeeping rules, and make a fresh initialization record all 001–010 resources only after the fresh schema validates. A 009-to-010 upgrade must apply just 010; malformed, duplicate, gapped, checksum-drifted, partial, rolled-back, and concurrent starts must remain fail-closed and atomic.
4. Implement `AgentMaterializationRepository` and `AgentContextRepository` as the only SQL owners for this domain. Use bounded exact-identity reads; insert-only binding and owner-link inserts; default update predicates containing the expected version; lifecycle update predicates containing the expected generation and permitted source state; and transactional operation-result lookup/insert. On duplicate operation identity, return the recorded typed result only if the digest matches; otherwise return conflict. Never turn a duplicate owner link into a retargeted binding, and never issue constructor DDL.
5. Implement registration and lifecycle orchestration in `AgentMaterializationService`. Verify the agent, project existence, and agent membership before a project materialization is registered; project association remains metadata. Insert a `PROVISIONING` row transactionally with its authoritative key and operation result, then perform filesystem work outside that transaction. On an interrupted or failed provision, re-read the same row, revalidate its generation and canonical location, and reconcile by guarded transition; do not delete bytes as cleanup. Registration never imports, copies, or otherwise populates project content.
6. Implement `MaterializationLocation` as an immutable workspace-package value with a package-private constructor and update `WorkspaceDirectoryService` as its sole production constructor. Resolve it by loading the binding and materialization row, requiring identical agent id, materialization key, and captured generation, requiring `READY`, then asking `WorkspaceDirectoryService` to recompute the canonical location from the resolved AGENT descriptor and key. Compare the stored data-root-relative assertion exactly, and reject absent owner, stale generation, link, escape, missing canonical child, assertion mismatch, or every non-ready/terminal state before a caller receives a location.
7. Provision only a project materialization's canonical `projects/<stable-key>` directory through `AgentMaterializationService`, `WorkspaceDirectoryService`, and the Phase 01 confined primitive. Revalidate the exact destination at the held-handle mutation boundary, create only missing canonical ancestors, reject links/hard links/reserved names/rebinding, and leave `home`, `scripts`, `apps`, `repos`, `tasks`, `outputs`, `.skills`, unknown children, and the workspace root untouched. Persist the verified relative assertion and transition to `READY` only with the expected generation; an empty container is valid, and personal selections persist no materialization path.
8. Implement `AgentContextService` capture and resolution. A new capture reads a default once or accepts an explicit closed selection, validates any selected materialization is ready, and inserts a binding snapshot containing the selected materialization key and generation or the constrained personal selection. Default changes affect only later captures. Binding resolution never rereads a default, never retargets a binding, never selects an execution workspace, and returns a typed unavailable/stale/non-ready result rather than a fallback location. Expose typed owner-link operations for later transactional callers, but do not attach conversation, assignment, or task-run owners in this phase.

## Technical Guidance

A project provision follows `register(PROVISIONING, generation=1) -> confined filesystem create -> CAS(READY, generation=2, verified assertion)`. If the CAS loses, return `STALE` and leave the filesystem untouched for explicit reconciliation; never infer readiness from directory presence alone. `READY` says nothing about project content population or freshness.

Resolve a binding in this order: immutable binding -> exact materialization row -> agent/key/generation/state check -> resolved AGENT descriptor -> workspace-owned location factory -> assertion comparison -> no-follow confined child verification. No step may substitute a project path, selected workspace, default, or persisted string for a prior trusted fact.

## Validation

Under the orchestration companion-edit policy, add focused tests for:

- literal key vectors; non-ASCII framing; kind/source separation; and invariance under title, display-name, timestamp, and path changes;
- default CAS conflicts; lifecycle CAS conflicts; same-key/same-digest convergence; contradictory key reuse; duplicate versus contradictory owner links; immutable binding insert rejection; and no binding retarget after a default change;
- project membership rejection; `PROVISIONING` recovery; exact `PROVISIONING`/`READY`/`FAILED`/`REVOKED` state enforcement; absent, revoked, failed, busy, and stale outcomes; and proof that an empty `READY` container is truthful;
- lazy project-area provisioning; denial of root, unknown-child, reserved-area, traversal, encoded-separator, link, hard-link, rebinding, and assertion-mismatch paths; no absolute-path column or user-safe absolute-path output; and no personal-selection escape from `home`, `scripts`, or `apps`;
- fresh-schema versus 009-upgrade owned metadata equivalence through 010; discovery gaps, malformed/duplicate names, checksum drift, rollback, partial state, and independent concurrent starters; and proof that the new persistence classes contain no DDL;
- the applicable Maven suite, bounded application startup, forbidden-construct scans for raw path reconstruction and constructor DDL, and validator review-and-repair PASS.

## Exit Criteria

Migration 010 and fresh schema are metadata-equivalent; materialization and context records are durable, typed, path-independent, immutable where required, and protected by CAS plus canonical idempotency; only `WorkspaceDirectoryService` constructs usable locations; project-context provisioning is confined, generation-safe, and truthfully empty-or-existing without implied acquisition; focused tests, broader validation, startup, and validator PASS evidence are recorded. This phase cannot claim PASS while an implementation precondition remains unresolved.
