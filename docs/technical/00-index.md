# Technical Documentation Index

These docs explain Magenta internals for contributors. Code remains the source of truth; each page links back to the current controller, service, repository, schema, or static asset areas it describes.

## Guides

- [Architecture](architecture.md)
- [API Reference](api-reference.md)
- [Data Model](data-model.md)
- [Services](services.md)
- [Agent Skills](agent-skills.md)
- [Chat, Planning, and Tasks](chat-planning-tasks.md)
- [Orchestration Runtime](orchestration-runtime.md)
- [Workflow Engine](workflow-engine.md)
- [Workspaces, Tools, and Outputs](workspaces-tools-outputs.md)
- [Frontend HTMX](frontend-htmx.md)
- [Security](security.md)
- [Configuration and Operations](configuration-operations.md)
- [Schema migration foundation (001–009)](migrations-001-009.md); current contiguous migration ownership through 018 is summarized in [Data Model](data-model.md).

## Owner Notes

- Architecture, API, service, schema, persistence, frontend, security, and configuration changes must update the relevant page.
- Keep this index current when pages are added, renamed, or removed.
- Keep route and payload summaries aligned with [`../api/00-index.md`](../api/00-index.md).
- Ordered migration resources are contiguous through 018. The historical migration guide documents the immutable schedule/reaction version 008 and no-schema warm-bookkeeping marker at version 009; Data Model records versions 010-018.
