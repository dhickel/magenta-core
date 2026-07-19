---
schema_version: 1
document_type: specification-schema
status: active
owner: internal-dev
created: 2026-05-25
---

# Specification Schemas

Use compact tables and stable IDs. Prefer these prefixes: `SPEC-YYYYMMDD-NN`, `ARCH-YYYYMMDD-NN`, `SVC-YYYYMMDD-NN`, `API-YYYYMMDD-NN`, `WEB-YYYYMMDD-NN`, `SP-YYYYMMDD-NN`, `DECISION-YYYYMMDD-NN`, `DEFERRED-YYYYMMDD-NN`, `HORIZON-YYYYMMDD-NN`, and `DRIFT-YYYYMMDD-NN`.

Required schema anchors: specification entry, architecture entry, service entry, API entry, web entry, decision row, deferred-feature row, horizon-idea row, drift record, no-impact note.

## Specification Entry

| id | file | status | owner | intended_contract | observed_anchors | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SPEC-20260525-01 | `services.md` | active | services | Services own use-case behavior and hide persistence/transport details. | Service classes under `io.mindspice.magenta2.*` | Focused tests plus startup when behavior changes. | `DECISION-20260522-05` | `services-ux-architecture-rules.md` |
| SPEC-20260525-02 | `schema.sql` | active | persistence | Browser chat pending messages are stored separately from chat memory until claimed and sent as normal turns. | `ai_chat_pending_messages`, `ChatPendingMessageRepository` | Repository FIFO, claim/ack/release, stale recovery, and clear-conversation tests. | none | `chat-context-management-and-tools.md` |

## Architecture Entry

| id | area | status | intended_contract | observed_anchors | drift_gaps | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ARCH-20260719-01 | Filesystem-first workspace identity | active | Workspace identity is closed to agent, chat, and explicitly declared user roots. | `WorkspaceDirectoryService`, `WorkspacePathLayout` | None currently recorded. | Service tests, startup, and focused browser checks for UI-affecting work. | `DECISION-20260719-02` | `2026-07-19-filesystem-first-workspaces.md` |

## Service Entry

| id | service_area | status | intended_contract | observed_anchors | ownership_boundary | drift_gaps | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SVC-20260719-02 | Workspace file explorer | active | Service layer owns descriptor-confined path validation and filesystem mutation. | `WorkspaceExplorerService` | Controllers map requests and responses only. | Typed error mapping is deferred. | Focused service/controller tests. | `DECISION-20260719-02` | `workspace-file-explorer-details-list-rewrite.md` |

## Service Graph Entry

| id | from | to | status | allowed_interaction | boundary_rule | validation | related_decisions |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SVC-20260719-03 | Agent Files tab | Workspace directory and explorer services | active | The agent Files tab delegates identity and confined file behavior to workspace services. | The web layer never constructs roots or canonical identity. | Focused service/controller/browser checks. | `DECISION-20260719-02` |

## API Entry

| id | route_or_surface | status | intended_contract | payload_or_status | compatibility_rule | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| API-20260719-01 | Typed workspace file routes | active | Return controlled status codes for validation, missing paths, and conflicts. | `400`/`404`/`409` where distinguishable. | Accept only type/key identity and confined file-relative paths. | Controller tests and API docs updates. | `DECISION-20260719-02` | `2026-07-19-filesystem-first-workspaces.md` |

## Web Entry

| id | page_or_fragment | status | intended_contract | observed_anchors | interaction_rule | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WEB-20260719-01 | `/agents/{agentId}` Files tab | active | The operational agent shell mounts a dense descriptor-bound file explorer. | `OrchestrationController`, `WorkspaceExplorerFragments` | HTMX-first fragments; narrow JS only where it is clearly simpler. | Focused Playwright with screenshots for UI changes. | `DECISION-20260719-02` | `2026-07-19-filesystem-first-workspaces.md` |

## SimplyPages Component/Module Entry

| id | component_or_module | status | intended_contract | observed_anchors | reuse_boundary | validation | related_decisions | related_knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SP-20260719-01 | Neutral workspace explorer | active | Use reusable list, inspector, modal, and OOB fragments with stable workspace-neutral targets. | `WorkspaceExplorerFragments`, SimplyPages components | CRUD and partial refreshes remain HTMX-first. | Browser visual critique and HTMX behavior checks. | `DECISION-20260719-02` | `2026-07-19-filesystem-first-workspaces.md` |

## Decision Row

| id | decision | status | owner | source | decided_on | justification | alternatives_or_tradeoffs | caveats | affected_specs | knowledge_ref | review_after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DECISION-20260719-02 | Filesystem-first workspace identity replaces database-backed filesystem identity. | active | architecture | corrected handoff | 2026-07-19 | A closed three-kind model matches concrete product workflows. | Generic persistence and compatibility aliases were rejected. | Preserve descriptor construction and path confinement boundaries. | `architecture.md`, `services.md`, `api.md`, `web.md`, `simplypages.md` | `2026-07-19-filesystem-first-workspaces.md` | 2026-10-19 |

## Deferred-Feature Row

| id | capability | status | owner | source | accepted_scope | out_of_scope_reason | likely_targets | validation_expectation | review_after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DEFERRED-20260525-01 | Planner recurrence automation | deferred | unassigned | `UNFINISHED-20260523-02` | Reminders, wait-for-input, assignment creation, or user contact driven by planner tasks. | V1 organizer records are storage-only. | Planner services, scheduler, approval gates. | Service tests, startup, browser validation if UI changes. | 2026-06-22 |

## Horizon-Idea Row

| id | idea | status | owner | source | implied_capability | expected_value | review_after | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HORIZON-20260525-01 | Preserve optional prior-chat context in planning mode. | candidate | unassigned | migrated idea | User can opt into prior conversation context while clean-context execution remains default. | Better planning continuity without compromising execution isolation. | 2026-06-24 | Needs product decision. |

## Drift Record

| id | spec | status | observed_drift | impact | routing | source | review_after |
| --- | --- | --- | --- | --- | --- | --- | --- |
| DRIFT-20260525-01 | `services.md` | open | Workspace controller maps some errors by exception message. | Status mapping can drift if messages change. | Deferred feature for typed domain errors. | `UNFINISHED-20260524-03` | 2026-06-23 |

## No-Impact Note

Use this changelog form when a change does not alter intended contracts:

| field | value |
| --- | --- |
| heading | `Specification Impact` |
| required_text | `Specification Impact: none` |
| explanation | One sentence explaining why no service, API, web, architecture, workflow, or product contract changed. |
