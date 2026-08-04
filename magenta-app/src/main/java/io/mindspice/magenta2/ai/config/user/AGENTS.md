## User AI Configuration Package

This package owns user-editable AI, model, endpoint, and agent configuration.

### Responsibilities
- Load external JSON/YAML AI configuration.
- Represent configured model endpoint definitions, legacy seed/import agents, top-level default model selection, internal summary model selection, planning model selection, context buffer policy, endpoint types, prompts, approved tool names, web search settings, per-agent shell command allowlists, and the explicit unsafe wildcard-shell override.
- Keep config records simple and close to the external file shape.
- Treat external agent `systemPrompt` values as required prompt file paths, resolved relative to the config file directory.

### Change guidance
- Do not add config fields until runtime behavior actually consumes them.
- Keep defaults and validation easy to explain.
- Avoid coupling config loading to chat-specific behavior.
- Do not remove provider `apiKey` configuration for `OPENAI_COMPATIBLE` or `DEEPSEEK` models during HTTP auth/CSRF cleanup; provider credentials are separate from app-layer route auth.
- Return loaded `AgentConfig.systemPrompt()` values as prompt text after resolving the external path.
- Treat external agents as backward-compatible seed/import data; runtime agent profiles in SQLite are the source of truth after startup seeding.
- Keep wildcard shell command execution disabled unless `unsafeAllowWildcardShellCommands` is explicitly true.
- Keep this guide updated when the config file shape, validation rules, supported endpoint types, summary/planning model semantics, web search settings, or wildcard semantics change.

### Validation
- Update loader tests for new or changed config fields.
- Include JSON and YAML coverage when external config compatibility changes.
