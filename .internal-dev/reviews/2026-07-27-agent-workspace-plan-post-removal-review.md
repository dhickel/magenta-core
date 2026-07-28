# Agent Workspace Plan Post-Removal Review

## Scope

Reviewed every file under `.internal-dev/plans/20260727-195154-home-dhickel-code-java-magenta-internal-dev-ha/` after removal of the three source-control phases. The review covered phase numbering, ledger/header/dependency agreement, cross-references, migrations, removed-domain vocabulary, project materializations, `/repos`, execution waves, and remaining implementation handoffs.

## Findings

- **Phase numbering:** PASS. The directory contains phases 01–07 only; filenames, H1 headers, phase ledger entries, and waves agree.
- **Dependencies:** PASS after review. Phase 03 depends only on Phase 02; Phase 04 depends only on Phase 03; Phase 06 depends on Phases 04 and 05. Phase 05 retains the correct direct lifecycle and prompt dependencies.
- **Cross-references:** PASS. No deleted phase, old phase-07–10 filename, or old phase number remains.
- **Migrations:** PASS. The only new migration resources are `010-agent-materialization-context.sql` and `011-system-chat-proposals.sql`; migration 011 follows 010, and no 012–014 reference remains.
- **Removed source-control scope:** PASS. No source-control implementation, local/remote operation, provider, or deleted control-storage concept remains in the plan.
- **Materialization cohesion:** Corrected. Phase 02 now explicitly owns the `WorkspaceDirectoryService` integration needed to construct `MaterializationLocation`; its former precondition no longer required Phase 01 to create a type or authority that Phase 01 excluded. Project materializations are defined as agent-scoped local context containers. `READY` means safe canonical availability, not acquired or populated content, and lifecycle states are closed to `PROVISIONING`, `READY`, `FAILED`, and `REVOKED`.
- **Prompt/tool authority:** Corrected. Materializations remain supplemental read-only context. Tool/write authority comes only from the independently selected execution descriptor and current lifecycle capability. `AGENTS.md` follows that descriptor hierarchy; `WORKSPACE.md` follows the captured context root.
- **`/repos`:** Corrected and preserved. It remains a lazy area for user-managed storage outside Magenta. Existing permitted reads are descriptor-confined; Magenta does not provision, mutate, interpret, or expose application-specific controls for it.
- **Phase executability:** Corrected. The orchestration now authorizes direct focused test/package-guide companions and explicitly lists the narrow Phase 06 HTMX error-swap asset. This removes prior ledger admission blockers without broadening production ownership.
- **Execution waves:** PASS. Seven ordered waves map one-to-one to phases 01–07 and respect all dependencies.

## Risk Assessment

Low for plan structure after correction. The main intentional limitation is now explicit: this sprint does not acquire, synchronize, or edit project materialization content. Empty `READY` context containers are valid and must not be presented as populated. Implementation remains security-sensitive around confined filesystem operations, immutable bindings, prompt provenance, and system-chat proposal confirmation, all of which retain validator gates.

## Recommendations

Use the corrected seven-phase plan as the implementation contract. Validators should enforce the new companion-edit policy, exact materialization states, read-only supplemental materialization authority, and Magenta-read-only `/repos` behavior.

## Follow-ups

No plan-level blocker remains. Each implementation phase must still produce its required focused evidence and validator PASS before its dependent wave starts.
