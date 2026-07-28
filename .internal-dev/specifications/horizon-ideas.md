---
schema_version: 1
document_type: horizon-ideas-register
status: active
owner: product
created: 2026-05-25
---

# Horizon Ideas

## Curated Targets

| id | idea | status | owner | source | implied_capability | expected_value | review_after | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HORIZON-20260525-01 | Rectify Avatar dashboard issues and iterate on the Avatar feature set. | superseded | user | `FOCUS-20260525-01` | Historical product direction only; the product was removed. | Preserves provenance without implying active capability. | 2026-07-19 | Superseded by `DECISION-20260719-02`. |
| HORIZON-20260525-02 | Preserve optional prior-chat context in planning mode while keeping clean-context execution as default. | candidate | unassigned | `IDEA-20260525-01` | Planning mode can optionally include prior conversation context. | Improves planning continuity without weakening clean execution defaults. | 2026-06-24 | Needs explicit product decision. |
| HORIZON-20260525-03 | Keep using git commits as explicit handoff inputs/outputs for implementation and validation phases. | candidate | unassigned | `IDEA-20260525-02` | Commits become phase boundary evidence for handoffs. | Makes validation and remediation more deterministic. | 2026-06-24 | Already partly reflected in repo workflow policy. |
| HORIZON-20260525-04 | Add a scheduler-style wait primitive for long-running agent coordination with clear main-thread status updates. | candidate | unassigned | `IDEA-20260525-03` | Long-running waits become explicit and inspectable. | Reduces ad hoc polling during remote work/orchestration. | 2026-06-24 | Evaluate against current AgentMail daemon/wait workflow. |
| HORIZON-20260525-05 | Improve Avatar layout widget catalog empty-state flow when every first-party widget already exists. | superseded | unassigned | `IDEA-20260523-01` | Historical product idea only; the product was removed. | Preserves provenance without implying active capability. | 2026-07-19 | Superseded by `DECISION-20260719-02`. |
| HORIZON-20260525-06 | Upstream reusable SimplyPages module candidates. | candidate | unassigned | `simplypages-upstream-module-candidates.md` | Entity selector endpoint support, master/detail browser shell, inline editable lists, status badges, HTMX tab navigation, polling panel, and transcript/event feed panel. | Improves reuse across Magenta and SimplyPages without encoding Magenta domains. | 2026-07-24 | Exclude Magenta domain editors, lifecycle controls, security wiring, and endpoint names. |
| HORIZON-20260525-07 | Collapse frontend shell controllers only if duplication creates a concrete maintenance problem. | candidate | unassigned | note migration | Shared router/page-builder delegation could reduce shell duplication. | Lower maintenance cost when warranted. | 2026-07-24 | Not accepted current work. |
| HORIZON-20260525-09 | Consider making jobs one-off project work units and adding duties as persistent agent responsibilities. | candidate | user | user request on 2026-05-25 | Jobs could become one-off work units run against a project, while duties describe ongoing or repeatable responsibilities an agent has, such as a code-reviewer duty. | Clarifies the conceptual boundary between project-scoped execution and longer-lived agent responsibilities before future job/agent architecture work. | 2026-06-24 | Needs architecture/product design before changing job, project, or agent contracts. |
| HORIZON-20260526-01 | Add user-managed locked-in constants for durable assistant context. | candidate | user | user brain dump on 2026-05-26 | Users can maintain a locked-in system-context message or structured constants that are always included where appropriate, such as stable preferences, personal facts, project conventions, or non-negotiable operating rules. | Gives users a first-class way to pin durable context without repeatedly restating it in chats or scattering it across prompts. | 2026-06-25 | Needs product design around scope, precedence, edit history, visibility, conflict handling, and whether constants are global, per-agent, per-project, per-workspace, or per-chat. |

## Parking Lot

| id | idea | status | owner | source | implied_capability | expected_value | review_after | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HORIZON-20260525-08 | Consider persistent per-agent containers. | watching | unassigned | container runtime note migration | Reuse per-agent exec sessions or long-running shell loop. | Faster repeated agent work if safe. | 2026-07-24 | Requires security/runtime design. |

## Review Log

| reviewed_on | reviewer | outcome | notes |
| --- | --- | --- | --- |
| 2026-05-25 | implementation-worker | migrated | Moved product-directional focus and note ideas into this register. |
