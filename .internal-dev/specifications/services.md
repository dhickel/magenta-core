---
schema_version: 1
document_type: services-specification
status: active
owner: services
created: 2026-05-25
---

# Services Specification

## Intended Contract

Services own use-case behavior and hide persistence, transport, filesystem, and model-provider details from callers. Add service behavior only for concrete user-facing workflows.

## Service Entries

| id | service_area | status | intended_contract | observed_anchors | ownership_boundary | drift_gaps | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SVC-20260525-06 | Workspace file explorer | active | Own confined create, rename, move, copy, preview/save, delete preflight/execute, labels, and action log behavior. | `WorkAreaExplorerService`, metadata/log services | Controllers only map request/response and fragments. | Typed domain errors are deferred. | Service/controller tests. | `DECISION-20260524-03` | `workspace-file-explorer-details-list-rewrite.md` |
| SVC-20260525-07 | Shell command line parser | deferred | Strengthen `shell_exec` command line validation before wider exposure, explicitly handling or rejecting shell operators and per-command argument policy. | Shell tool service and command parsing | Process execution remains confined by workspace policy. | Current parser only handles whitespace, quotes, and backslash escapes. | Shell tool unit and security tests. | `DECISION-20260522-06` | `shell-tool-confinement-pattern.md` |
| SVC-20260525-08 | Web search/fetch | active | Web tools may use SearXNG for search and controlled fetch behavior for current-information tasks. | Web search/fetch services and config | Operational deployment details belong in docs/knowledge, not specs. | SearXNG host deployment is environment-specific. | Tool tests and live checks when config changes. | none | `web-fetch-redirect-validation-pattern.md` |
| SVC-20260525-09 | Task execution SSE | deferred | Consider native reactive or explicit executor-backed streaming if live task runs need higher concurrency. | Task execution SSE controller/service paths | Current blocking bridge is acceptable for current behavior. | HTTP threading scalability is deferred. | SSE tests and startup. | none | `plan-execution-stream-finalization.md` |
| SVC-20260525-10 | Planner recurrence | deferred | Planner tasks store recurrence and projections but do not automate reminders, user contact, wait-for-input, or assignments until separately designed. | Avatar planner organizer | Automation is out of v1 organizer scope. | Automation product policy unresolved. | Service/UI tests when accepted. | `DECISION-20260523-02` | `avatar-work-area-ui-refactor.md` |
| SVC-20260525-11 | Workspace leases | deferred | Current runtime uses exclusive writable project leases; read leases are future product/service decisions. Job-owned workspaces are legacy compatibility only and must not be extended as active product behavior. | Project workspace lease services | Read leases are not current runtime contract. | Future read-lease policy unresolved. | Lease service tests and runtime validation. | none | `project-workspace-lease-runtime-pattern.md` |
| SVC-20260525-12 | Browser chat pending message queue | active | Chat pending-message service owns validation, FIFO enqueue/list/claim/ack/release, stale claim recovery, and clear-conversation cleanup for normal `/chat` mid-turn messages. | `ChatPendingMessageService`, `ChatPendingMessageRepository`, `ChatService.clearConversation()` | Controllers delegate queue use cases; queue persistence stays outside `ActiveTurnRegistry` and `ai_chat_memory`. | Saved plan chat and agent side-panel chat are out of scope for this queue. | Repository/service/controller tests, browser drain validation. | none | `chat-context-management-and-tools.md`, `chat-planning-composer-architecture.md` |
| SVC-20260525-13 | Chat model defaults and aliases | active | File-configured `defaultModel` is the primary anonymous chat fallback before legacy default-agent model fallback. Model selector values are configured aliases; remote model names are resolved at the model-router boundary. `DEEPSEEK` routes through Spring AI's dedicated DeepSeek model so reasoning content survives explicit tool-call turns. DeepSeek `thinkLevel` 1-3 maps to `high`, level 4 maps to `max`, and zero/unset preserves the API default. | `AiConfig.defaultModel`, `RuntimeSettingsService`, `RequestResolver`, `ChatService`, `ChatModelRouter` | Preserve compatibility accepting either alias keys or remote names on request payloads. Provider-specific model and option types stay inside the router. | Config loader, provider routing/reasoning tests, service/controller tests, startup smoke when dependencies or config change. | `DECISION-20260719-01` | `spring-ai-model-options-routing.md`, `spring-ai-thinking-metadata-locations.md`, `summary-title-model-selection.md` |
| SVC-20260526-01 | Workspace path layout and run output routing | active | Workspace services own application structural paths, Work Area disk path creation, run-local output staging, final output promotion targets, and compatibility handling for old path fields. `WorkspacePathLayout` is the source for static segments and aliases; `WorkspaceDirectoryService` remains responsible for data-root confinement and directory creation. | Workspace services, file/shell tools, task/plan execution, workflow runner, job service, output artifact service | Model-facing `outputs/` means current run-local `runs/<runId>/outputs/`; final destinations are written only by backend completion, validation, or promotion logic. Jobs bind to agent/project/Work Area and do not own directories. | Unit/service tests for layout helpers, Work Area id paths, run staging, output promotion, retention, and legacy compatibility. | `DECISION-20260526-01` | `workspace-file-architecture-rules.md`, `file-tool-workspace-scope-pattern.md` |
| SVC-20260526-02 | Runtime `AGENTS.md` resolver, starter generation, and prompt/context integration | active | Workspace/runtime services own first-create-only starter `AGENTS.md` generation for new agent workspaces, never overwrite existing files, resolve applicable files from bound root to active runtime target path, and provide ordered layers for prompt/context injection where closest wins only on conflict and ancestor guidance remains active. File and shell tool services publish the active runtime path from their confined target/working-directory resolution; prompt assembly consumes that service-owned path and does not traverse independently. | Workspace creation flows, runtime context services, file/shell tool services, prompt context assembly | Resolution must fail closed outside bound root and omit `AGENTS.md` context when no bound root exists. | Service tests for starter no-overwrite behavior, resolver matrix, tool-owned active path propagation, and prompt/context layering/de-emphasis behavior. | `DECISION-20260526-02` | `agents-md-specification-reference.md`, `workspace-file-architecture-rules.md` |
| SVC-20260526-03 | Agent Skills catalog, parsing, assignment, activation, and file-management services | active | Skill-domain services own root `skills/` discovery, `SKILL.md` parsing, metadata persistence, assignment metadata, runtime catalog disclosure, activation state, and root-confined API-facing file operations. MVP activation is agent-scoped only and uses dedicated activation tooling with dedupe. Script execution policies, project/user scope loading, layered assignment, and registry/package trust are deferred. | Implemented phase-02-06 `ai.skills` services and web consumers: `AgentSkillRepositoryService`, `AgentSkillParser`, `AgentSkillCatalogService`, `AgentSkillRepository`, `AgentSkillAssignmentRepository`, `AgentSkillAssignmentService`, `AgentSkillRuntimeCatalogService`, `AgentSkillPromptCatalogAssembler`, `AgentSkillActivationService`, `AgentSkillManagementService`, `SkillController`, `SkillFragments` | Controllers/chat remain consumers. Prompt assembly appends metadata-only catalog when assigned+enabled+loadable skills exist. Dedicated `activate_skill` returns parsed body-only instructions and resource listing without eager reads. File routes resolve skill slug/name through catalog metadata, enforce root confinement through repository service path guards, block traversal/symlink escapes, and map unsupported text operations distinctly. Browser optional-directory creation is limited to top-level `scripts`, `references`, and `assets`. `allowed-tools` remains experimental and must not be treated as enforced policy in MVP. | Parser/discovery/repository tests, assignment persistence/filtering tests, activation/dedupe tests, prompt-context tests, tool-registry/tool-access tests, controller/API/UI tests for file confinement/status mapping/fragments, full-suite `mvn test`, bounded startup, and reconciled Playwright browser evidence. | `DECISION-20260526-04`, `DECISION-20260526-05`, `DECISION-20260526-06` | `agent-skills-specification-reference.md`, `agent-skills-ui-htmx-pattern.md` |

## Ownership Boundary

This file owns service behavior. API status codes belong in `api.md`; UI rendering belongs in `web.md`; dependency direction belongs in `service-graph.md`.

## Drift/Gaps

| id | status | observed_drift | routing | review_after |
| --- | --- | --- | --- | --- |
| DRIFT-20260525-04 | open | Workspace controller currently relies on message-based error mapping for some file explorer responses. | `DEFERRED-20260525-05` typed domain errors. | 2026-06-23 |

## Validation Expectations

Service changes require focused service tests. If Spring wiring or runtime dependencies change, run bounded startup. If the service is user-visible through web/API surfaces, add controller/browser validation as appropriate.

## Related Decisions

See `decisions.md`.

## Related Knowledge

Search knowledge filenames for `services`, `workspace`, `shell`, `web-fetch`, `task`, `lease`, `docker`, and `orchestration`.
