## Workspace Package

This package owns filesystem-first workspace identity, descriptor resolution, confined child locations, file behavior, and output publication.

### Active layout
- Agent: `workspace/agent/<agentId>/`
- Chat: `workspace/chat/<conversationId>/`
- User: `workspace/user/<name>/`
- Legacy/non-task publication child: `<workspace>/outputs/`
- Task-capable child: `<agent-or-user>/tasks/<taskPathKey>/<taskRunId>/`
- Lazy task children: `<run>/outputs/`, `<run>/staging/`
- Lazy workspace skill child: `<agent-or-user>/.skills/`

### Responsibilities
- `WorkspaceDirectoryService` alone constructs trusted `WorkspaceDescriptor`, `TaskRunLocation`, and `RunOutputLocation` values.
- `WorkspacePathLayout` is the sole structural segment authority.
- Task identities use immutable definition ids and exact versioned `TaskPathKey` encoding. Display names never select or rename paths.
- Task runs are children of `AGENT` or `USER`; `CHAT` is not task-selectable and `WorkspaceKind` remains closed.
- Reads tolerate absent agent/chat roots and create no task, skill, run, output, or staging trees. User roots require explicit creation. Task-run provisioning creates only the canonical run hierarchy; publication remains a later owner.
- Existing workspace-root `outputs/` data remains legacy/non-task data until later publication phases cut over.
- Stored filesystem locations are data-root-relative and paired with workspace kind/key. Paths never authorize or reconstruct identity.

### Change guidance
- Pass trusted descriptors and child-location values across filesystem boundaries; never infer identity from `Path` or hand-concatenate reserved segments.
- Validate canonical identity/key agreement, path components, traversal, containment, existing links, and mutation-boundary parent state.
- Do not add workspace kinds, generic workspace ids, project roots, Work Areas, aliases, leases, or eager scaffolding.
- `TaskRunWriteCapability` is non-authorizing until lifecycle services verify persisted owner/fence facts at mutation boundaries.
- Applied migration resources are immutable. Schema corrections use a new ordered resource.

### Validation
- Cover AGENT and USER resolution/provision, CHAT rejection, key mismatch, relocation, absent roots, idempotency, UTF-8 limits, reserved names, traversal, symlinks, and trusted constructor visibility.
- Prove reads create no `tasks/`, `.skills/`, run, output, or staging directory.
- Cover migration discovery, checksum/bookkeeping drift, partial legacy state, interruption/restart, independent concurrent starters, and fresh/migrated metadata equivalence.
