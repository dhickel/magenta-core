## Context

`TrustedContextPolicyAssembler` and `WorkspaceMdResolver` define execution authority, provenance, terminal-outcome, and `WORKSPACE.md` confinement behavior. This independent correction proves those boundaries directly and repairs only demonstrated defects.

## Goal

prove terminal trusted-context and guidance-confinement boundaries

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssembler.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceMdResolver.java, src/test/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssemblerTest.java, src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceMdResolverTest.java

## Out of Scope

- Prompt-order redesign, new context outcomes, personal areas, skill policy, UI/JavaScript, schema, routes, payload changes, and closure of runtime workspace-scoping drift.
- Changes outside demonstrated assembler/resolver gaps, including broad workspace-path refactors or new public APIs.

## Dependencies

none

## Constraints

- Write/tool authority derives solely from an independently selected AGENT or USER execution descriptor; CHAT is rejected.
- Materialization context is supplemental, read-only, and generation-bound.
- Only proven absent optional `WORKSPACE.md` is empty. Integrity, confinement, encoding, link, or I/O failure is terminal with no root fallback.
- Guidance is bounded untrusted text, never configuration or authority.
- Do not change routes, payloads, schema, UI, JavaScript, ordered migrations, or the blocking runtime workspace-scoping drift.

## Orchestration Alignment

- This is an independent wave-01 phase with exactly one implementation agent.
- Its post-phase validator review-and-repair gate must PASS before its evidence is used by integration closeout.

## Preconditions

Read the required guides, applicable trusted-context/workspace specifications, and task-relevant knowledge before editing. Inspect the current assembler and resolver APIs before choosing fixtures or seams. Preserve `WorkspacePathLayout` as structural-path authority and `WorkspaceDirectoryService` as the trusted-descriptor constructor; neither component may infer workspace identity from a `Path`.

## Invariants

- Write/tool authority comes only from the independently selected AGENT or USER execution descriptor. CHAT is rejected and materialization never supplies execution authority.
- Materialization is supplemental, read-only, generation-bound context. A malformed available result or mismatched generation/location is an integrity failure, not absence.
- Policy/provenance uses canonical identity and never absolute host roots. Guidance remains bounded untrusted text, never authority or configuration.
- Only a proven absent optional root or `WORKSPACE.md` produces empty guidance. Integrity, encoding, confinement, link, regular-file, or I/O failure is terminal and never falls through to another root.
- Preserve existing output bounds and do not disclose absolute paths, owners, fence values, or raw untrusted content in results or logs.

## Implementation Steps

1. Inspect the assembler and resolver contracts and add fixture builders in `TrustedContextPolicyAssemblerTest` for trusted descriptors, bindings, locations, and every context outcome. Builders must make AGENT, USER, CHAT, personal-area, and materialization inputs explicit without manufacturing authority from filesystem paths.

2. Add assembler tests for AGENT and USER authority and CHAT rejection. For `UNAVAILABLE`, `STALE`, and `NON_READY`, assert a terminal result: no partial policy, binding, location, descriptor, or trusted-context text is emitted. Assert exact canonical key, generation, and location provenance for valid personal HOME/SCRIPTS/APPS and materialization inputs.

3. Extend assembler tests to prove that authority is derived only from the execution descriptor; personal or materialization bindings cannot widen it. Cover a materialization available result with malformed data or mismatched generation/location as terminal integrity failure, read-only materialization wording, identity without absolute roots, and the existing output bound.

4. Add resolver tests for proven absent roots/files, empty and nonempty guidance, personal and materialization provenance, truncation, and hard byte-limit rejection. Separately assert invalid UTF-8 and NUL-byte content are terminal failures rather than empty guidance.

5. Add resolver confinement tests for a symlinked root, file, and intermediate component; nonregular file; candidate/realpath escape; and realpath failure. Assert trusted search candidates and their resolved real paths are validated against the selected authority root, with links rejected at every relevant component rather than only at the leaf.

6. Add resolver terminal-path tests for read/I/O failure and every malformed available candidate. Instrument lookup order through existing collaborators or test-visible behavior and assert no alternate personal or materialization root is consulted after each terminal failure. Treat only positively established nonexistence as optional absence; an indeterminate existence check is failure.

7. Repair only failures demonstrated by these tests in the two production targets. Keep candidate and realpath validation rooted in the selected trusted authority descriptor; reject links, confinement violations, invalid encoding, nonregular files, and I/O errors before fallback can occur. If a portable I/O failure cannot otherwise be induced, introduce only a package-private, class-local NIO seam in `WorkspaceMdResolver`; do not add a general filesystem abstraction or public surface.

8. Re-run the focused tests after repairs and inspect the diff for authority broadening, fallback after terminal failure, absolute-path disclosure, output-bound regression, new public API, and changes beyond the four write targets.

## Required Guides

- AGENTS.md
- .internal-dev/AGENTS.md
- .internal-dev/specifications/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/chat/service/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md
- src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md

## Technical Guidance

- Use real trusted descriptors created by the approved directory-service path or existing test support; never reconstruct a descriptor from a `Path`.
- Distinguish absence from failure explicitly. `Files.notExists(path)` alone is insufficient when I/O status is indeterminate.
- A terminal result must terminate the resolver decision tree, not merely return empty content that an outer caller can reinterpret as a reason to try another root.
- Keep assertions at the observable boundary: canonical provenance, terminal outcome, rendered trusted-context content, selected root, and lookup non-consultation. Avoid coupling tests to incidental prompt formatting except where wording establishes read-only materialization semantics.

## Validation

- Run `TrustedContextPolicyAssemblerTest` and `WorkspaceMdResolverTest`.
- Run the focused prompt/context assembly, materialization/context, workspace-directory, and skill snapshot/activation suites that exercise these contracts.
- Confirm no test depends on host-specific absolute roots or permissive symlink behavior.
- Review the final diff and Git status for scope drift, authority broadening, alternate-root fallback, path disclosure, added public surface, placeholders, and unrelated edits.

## Exit Criteria

- Direct tests prove AGENT/USER-only execution authority, CHAT rejection, terminal `UNAVAILABLE`/`STALE`/`NON_READY` outcomes, canonical provenance, read-only generation-bound materialization, and output bounds.
- Direct resolver tests prove proven absence behavior plus terminal integrity, encoding, confinement, link, nonregular-file, realpath, and I/O failures, with no alternate-root consultation after failure.
- Production repairs are limited to demonstrated gaps, preserve trusted workspace ownership, and introduce no route, payload, schema, UI, JavaScript, or public-surface change.
- The post-phase validator review-and-repair gate reports PASS.
