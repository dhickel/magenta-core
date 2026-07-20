# Skill API

Source: [`SkillController`](../../src/main/java/io/mindspice/magenta2/api/web/SkillController.java).

## Repository Management

- `GET|POST /api/skills`, `POST /api/skills/refresh`
- `GET /api/skills/{skillName}` and `/diagnostics`
- `GET /api/skills/{skillName}/files` and `/files/view`
- `PUT /api/skills/{skillName}/files/text`
- `POST /api/skills/{skillName}/files`
- `GET /api/skills/{skillName}/assignments`
- `POST|DELETE /api/skills/{skillName}/assignments/agents/{agentId}`

File routes are root-confined and reject absolute paths, traversal, and symlink escapes. Typical mappings are `400` invalid input, `404` missing skill/path/agent, `409` conflict, and `415` unsupported text/binary operation.

## Candidates And Exact Overlays

- `GET /api/skills/candidates` returns all persisted digest-qualified candidate versions in deterministic key/history order.
- `GET /api/skills/candidates/{logicalKey}` returns candidate history and trust status for one key.
- `POST /api/skills/overlays` binds an exact candidate version to a target type/key using an idempotent operation key.
- `GET /api/skills/overlays` and `/overlays/{logicalKey}` list persisted exact overlays.

Project candidate discovery remains disabled without an explicit trusted project-root authority.

## Trust And Snapshots

- `POST /api/skills/trust/approve` with `candidateVersionId`, `observedDigest`, optional `operationKey`, `actor`, and `reason`.
- `POST /api/skills/trust/reject` with candidate identity and audit fields.
- `POST /api/skills/trust/revoke` with `logicalKey` and audit fields.
- `GET /api/skills/trust/decisions/{logicalKey}`.
- `GET /api/skills/snapshots/{logicalKey}`.
- `GET /api/skills/snapshots/{snapshotId}/resources`.
- `GET /api/skills/snapshots` lists persisted immutable snapshots across logical keys.

Approval re-reads the confined source and verifies the observed digest. It creates immutable snapshot/resource rows. Editing source bytes creates a new candidate and never mutates an existing snapshot. Revocation blocks new activation but preserves historical evidence.

## Activation And Executable State

- `POST /api/skills/activate` with `skillName`, `agentId`, `taskRunId`, and `conversationId`.
- `GET /api/skills/activations/{taskRunId}` returns persisted activation audit for the run.
- `GET /api/skills/exec-auth/{logicalKey}` reports confinement unavailable.
- `POST /api/skills/packages/ingest` returns `503 UNAVAILABLE` and accepts no archive.
- `GET /api/skills/catalog/approved` returns only currently approved immutable snapshot metadata.

Activation uses approved immutable snapshot bytes. Instruction approval is separate from executable authorization. Executable resources and package ingestion fail closed while process confinement is unavailable.

## Browser Fragments

`/skills` and its `_list`, `_detail`, `_files`, `_viewer`, `_directories`, `_assignments`, `_trust`, and `_snapshots` routes provide the HTMX control plane. Trust/snapshot mutations refresh only their stable hosts.
