# Project Operation Scope Discipline

## Topic

Project lifecycle controls must not gain a global runtime toggle unless an explicit product requirement establishes one.

## Source References

- `.internal-dev/changelogs/2026-07-30-remove-project-operations-gate.md`
- `ProjectService`
- Project task, chat, output, and materialization services/controllers

## Key Takeaways

- Keep project operations available under their ordinary lifecycle and membership rules.
- Preserve archive and member-removal guards, durable-link authorization, bounded chat tools, and operation-key idempotency as independently justified constraints.
- Do not transform an open-alpha access posture into an undocumented deployment-wide runtime switch.

## Engine Relevance

Keeps project behavior aligned with explicit user workflows while preserving narrowly scoped lifecycle and reliability protections.

## Open Questions

None.
