# Scoped Skill Trust and Snapshots

## Topic

How a skill moves from confined discovery through candidate resolution, exact assignment, explicit approval, immutable snapshot creation, model disclosure, activation, and revocation.

## Source References

- `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/concepts.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `SkillScopeDiscoveryService`, `SkillResolutionService`, `AgentSkillAssignmentRepository`
- `SkillTrustService`, `SkillSnapshotRepository`, `AgentSkillActivationService`

## Key Takeaways

### 1. Discovery creates candidates, not trusted skills

- Read-only discovery checks the effective non-chat `AGENT`/`USER` workspace, a physically distinct bound-agent workspace, a project root only when an explicit trusted authority exists, and the global package repository.
- It never scans chat workspaces, task-run directories, guessed project roots, process homes, or working directories, and it never creates an absent `.skills` root.
- A candidate identity binds logical key, trusted scope/root authority, provenance, format version, and canonical content digest. Source edits create a new candidate version.
- Malformed, unavailable, duplicate, colliding, stale, and shadow states are control-plane facts. Candidate-derived descriptions, instructions, hints, resources, and conflict text stay out of model-visible output.

### 2. Resolution separates root precedence from assignment specificity

- Root precedence is effective workspace, distinct bound agent, trusted project authority, then global repository.
- The highest-precedence occupying candidate owns the logical key even when pending, malformed, stale, or unavailable; resolution does not fall through and disclose a lower candidate.
- Exact overlays bind one immutable candidate version. Specificity is chat session, task/workflow, job, project, then agent. The most-specific explicit enable/disable wins; equal-specificity conflicts are explicit.
- Assignment, provenance, operator ownership, source location, or signatures do not imply approval.

### 3. There is no auto-trust path

- Global/operator-managed packages are not automatically trusted. Every model-visible skill requires an explicit approval of the exact observed digest.
- `SkillTrustService.approve` reopens the source through trusted confined authorities, validates the manifest, and requires candidate digest = observed digest = reread manifest digest.
- Changed, missing, linked, malformed, or structurally unsafe source fails approval.

### 4. Approval creates immutable state

- Approval writes immutable snapshot metadata, instruction bytes, and resource bytes/digests, then records actor/reason/scope and operation identity.
- Snapshot identity uses canonical path/type/length/byte framing. Existing snapshot rows/resources are never edited when live source changes.
- Controlled edits and accepted packages can only create new pending candidates; they cannot mutate or implicitly approve a snapshot.
- Reusing an operation key with the same canonical request converges; contradictory reuse conflicts.

### 5. Disclosure and activation read snapshots only

- Model-visible catalog entries and `activate_skill` content come only from exact approved snapshot-owned fields/bytes.
- Activation verifies exact assignment/resolution/current approval and persists activation audit before returning instructions/resources.
- Concurrent activation deduplicates by canonical context and snapshot.
- Revocation blocks every new activation. A same-run resume may rehydrate only an exact snapshot activation already persisted for that canonical task run; retry, replay, and new runs require current approval.

### 6. Executable capability is independent and unavailable

- Instruction approval, assignment, activation, and exact-resource executable authorization are separate decisions.
- `allowed-tools` is informational and grants nothing.
- Current process confinement is unavailable, so executable authorization/execution and package ingestion fail closed. The package endpoint's `NOT_IMPLEMENTED` response is an unavailable capability, not a successful no-op.
- Project discovery likewise remains unavailable until a trusted project-root authority exists.

## Engine Relevance

Prompt and tool-schema builders must depend on approved snapshot catalogs, never discovery candidates or live files. Trust UI/API work must require exact candidate ids/digests and must not label assignment or global provenance as approval. Execution work must check the independent confinement and resource-authorization contract even after instruction activation succeeds.

## Open Questions

- What tested sandbox/process-tree revocation capability will satisfy executable authorization?
- Which authenticated principal model will replace open-alpha actor labels for trust decisions?
- Will a future trusted project-root authority be added without changing the closed workspace-kind model?
- When confinement exists, what bounded archive service and recovery loop will replace the current fail-closed package endpoint?
