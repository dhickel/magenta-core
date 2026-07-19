## Chat Plan Package

This package owns anonymous chat plan state, saved execution plan/task definitions, and plan lifecycle operations. The shared `PlanMode` enum that represents plan, task, and normal interaction modes is now owned by the `ai.chat.model` package.

### Responsibilities
- Persist anonymous `/chat` execution plans as ad hoc session-local drafts. Anonymous plans collect goal, assumptions/details/expectations/approach, deliverables, steps, and validation criteria; they do not expose structured inputs/outputs, save to `/plans`, or submit to agents.
- Persist saved `/plans` task templates separately from `/api/chat` session architecture. Saved plan chat may reuse chat-like UI, but its messages belong to plan-scoped storage and update `TASK_TEMPLATE` drafts directly.
- Persist saved plan inputs and outputs as typed `PlanFieldDefinition` values. Deliverables are high-level user-visible or operational outcomes; outputs are named structured values intended for workflow chaining, downstream plan inputs, or directed task results.
- Provide the standalone plan-mode system prompt and compact execution-mode runtime instructions.
- Keep plan lifecycle operations explicit and driven by commands or planning-specific API actions.
- Keep plan state separate from transient chat history and generic todo tracking.
- For saved plan/task execution, use the descriptor selected by orchestration context. `projectId` remains membership and attribution metadata and never selects a filesystem root. Workspace identity is specified via `workspaceType`/`workspaceKey`/`workspaceIdentity` (not the retired `workspaceId`).
- Publish declared task outputs directly beneath the selected descriptor's lazy `outputs/` directory; do not create run-local staging or promotion trees.
- Prefer keyed item mutations for draft sections so model tool calls add, replace, or delete one plan element at a time.

### Change guidance
- Do not add general orchestration, scheduling, or subagent coordination here.
- Keep plan schemas small and tied to the current chat workflow.
- Keep the database as the source of truth; model tool calls request state changes but do not own state.
- In PLAN mode, keep the model-visible prompt focused on planning-only behavior. Shell access is allowed for planning research, including local database/schema inspection.
- Prefer compact structured planning state over replaying long planning transcripts.
- Planning turns should end in a queued clarification question or a draft marked ready for approval; ordinary free-form planning text is not a valid terminal state.
- Treat execution as reviewable until validator feedback has marked it complete; do not equate a returned model turn with verified completion.

### Validation
- Add repository tests for schema and persistence changes.
- Add service tests for mode transitions, prompt injection, and context trimming.
