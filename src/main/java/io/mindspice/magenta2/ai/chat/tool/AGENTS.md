## Chat Tool Package

This package owns chat-scoped tool execution support.

### Responsibilities
- Resolve configured Spring AI tools for chat agents.
- Represent tool activity as Magenta-owned chat context messages.
- Keep tool output retention and truncation policy local to chat tooling.
- Own chat-approved file tools that operate inside the active orchestration context aliases or agent/chat fallback scope.
- Own chat-approved shell execution for explicitly allowed Linux commands inside the active orchestration context aliases or configured fallback scope.
- Own chat-approved public web search and fetch tools backed by configured web search services.
- Own lightweight keyed chat planning tools that mutate Magenta-owned plan state through services.
- Own compact plan execution evidence reporting through `plan_report` and validator-gated completion through `plan_complete`.

### Change guidance
- Do not add separate durable tool-result storage unless a concrete workflow requires it.
- Avoid replay, approval, or orchestration behavior without an explicit user-facing tool use case.
- Keep model-visible tool context concise and easy to inspect.
- Keep file tool names and arguments plain, predictable, and friendly to smaller local models.
- Prefer `file_append` for accumulating notes, outlines, reports, or logs; use `file_write` only when writing the complete desired file content.
- Keep file path confinement centralized and reject traversal or symlink escapes before file IO.
- During orchestration execution, resolve ordinary file and shell paths directly beneath the effective `WorkspaceDescriptor` root. Former alias names such as `workspace`, `root`, `run`, `work`, and `projects` have no special meaning and may be ordinary child names. `outputs/` and descendants are reserved for output publication: file tools may inspect them but may not mutate them, and shell execution may not use them as a working directory.
- File and shell tools that resolve a confined runtime target also record the active runtime path on `OrchestrationTaskContextHolder` so subsequent model-backed prompt/context assembly can refresh `AGENTS.md` layers for the real target path.
- Keep shell command execution structured; do not accept raw shell command strings.
- Keep web tool names and outputs compact, citation-friendly, and explicit about failures/truncation.
- Keep planning tools narrow; they should set the goal/current planning task, add/replace/delete one keyed plan item, queue free-response questions, mark approval readiness, or request completion validation, not orchestrate execution.
- Keep plan execution evidence concise, user-auditable, and tied to the active saved plan.
- For Agent Skills MVP, keep activation tooling focused on skill instruction loading and metadata/resource disclosure. Do not embed project/user scope discovery or script-trust policy enforcement in ad hoc tool code.
- Treat `allowed-tools` metadata as experimental until an explicit enforcement policy is accepted in specs.

### Validation
- Add focused tests for tool registry resolution, transcript rendering, and truncation policy changes.
- Add focused tests for file path confinement, chunked reading, search output, and anchored edits.
- Add focused tests for shell command allowlists, wildcard override gating, working-directory confinement, timeout handling, and output truncation.
