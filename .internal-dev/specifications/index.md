---
schema_version: 1
document_type: specifications-index
status: active
owner: internal-dev
created: 2026-05-25
---

# Specifications Index

## Store Rules

Update existing living specification files by default. Create a new specification file only for a genuinely new specification class and update this index in the same change with the file's ownership boundary, status, owner, and review rule.

The store is flat. Do not create nested directories for architecture, API, services, web, SimplyPages, decisions, deferred capability, or horizon ideas.

## Living Files

| file | status | owner | domain boundary | review rule |
| --- | --- | --- | --- | --- |
| `AGENTS.md` | active | internal-dev | Local operating guide for this specification store. | Review when specification workflow rules change. |
| `index.md` | active | internal-dev | Routing map and allowed-file inventory. | Update before or with any genuinely new specification class. |
| `workflow.md` | active | internal-dev | Workflow gates, closeout rules, and migration/drop audit. | Review when `.internal-dev` process changes. |
| `schema.md` | active | internal-dev | Entry schemas and compact examples for all spec/register types. | Review when schemas or ID patterns change. |
| `architecture.md` | active | architecture | System architecture, runtime boundaries, persistence ownership, and architectural drift. | Review on architecture-affecting work. |
| `service-graph.md` | active | architecture | Service dependency graph and allowed interaction edges. | Review when service dependencies or runtime paths change. |
| `services.md` | active | services | Service use-case contracts and service-owned behavior. | Review when service behavior or ownership changes. |
| `api.md` | active | api | REST/SSE/API routes, payloads, status codes, and compatibility expectations. | Review when API behavior or payload contracts change. |
| `web.md` | active | web | Web pages, shells, fragments, UX contracts, and browser validation expectations. | Review when pages/fragments/navigation change. |
| `simplypages.md` | active | web | SimplyPages components, modules, HTMX patterns, layout/editing rules, and reusable UI direction. | Review when SimplyPages usage or module policy changes. |
| `decisions.md` | active | internal-dev | Durable architecture, design, product, and workflow decisions. | Review before making or revising durable decisions. |
| `deferred-features.md` | active | product | Accepted future product capability that is out of current scope. | Review when deferring accepted capability or planning deferred work. |
| `horizon-ideas.md` | active | product | Future product direction that is not accepted deferred capability. | Review when triaging "future/eventually/later" context. |
| `product-model.md` | target | product | Accepted target product model: terminology contract, workspace/filesystem layout, task/plan/workflow model, non-cron scheduling, project ownership and run authority, agent home workspaces, and dashboard editing of `agents.md` and skills. | Review when the rectification sprint scope changes or the model is revised. |

## New File Policy

A new specification file is allowed only when the current files cannot own a genuinely new class of intended truth. Splitting by subsystem, route, service package, page, or feature is not enough. Improve headings and anchors in the existing file first.
