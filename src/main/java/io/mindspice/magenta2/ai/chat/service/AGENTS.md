## Chat Service Package

This package owns chat use-case behavior.

### Responsibilities
- Resolve conversation ids, selected models, prompts, context compaction, memory, streaming behavior, and history conversion.
- Consume Agent Skills catalog and activation state from dedicated skill-domain services when skills are enabled.
- Read default runtime agent model, prompt, tool, compaction, and context-buffer choices through orchestration services when they are available.
- Coordinate chat plan-mode entry, exit, and saved-plan execution through the plan service.
- Coordinate model-backed reusable task execution, including task run context registration, task completion enforcement, and streaming task execution events.
- Refresh model-backed runtime prompt context after tool execution when tool services update the active runtime path, so `AGENTS.md` guidance follows actual file/shell targets.
- Route configured chat models to their configured endpoint clients.
- Keep controller logic thin by centralizing chat behavior here.
- Use file configuration from `ai.config.user` for model endpoint definitions without duplicating config parsing rules.
- Preserve context-compaction behavior that keeps activated skill guidance durable when skill tooling marks content as protected.
- Resolve chat files through a trusted `WorkspaceDescriptor` at `workspace/chat/<conversationId>`; an authoritative conversation may have an absent physical root that reads empty.

### Change guidance
- Keep service methods focused on current chat workflows.
- Do not add direct filesystem skill discovery/parsing to chat services; use dedicated skill-domain services.
- Do not introduce general agent orchestration, scheduling, or tool execution here until a concrete chat use case requires it.
- Keep transport details and persistence details behind controller and repository boundaries.
- Chat-file reads must preserve stable bounded listing, reject traversal and symlinks, and propagate identity, confinement, and real I/O failures instead of presenting them as an empty workspace.
- Keep this guide updated when chat behavior, service contracts, or orchestration responsibilities change.

### Validation
- Add focused unit tests for prompt resolution, model selection, context compaction, history conversion, streaming, and rendering behavior.
- Run affected controller tests when service response behavior changes.
