# Remove Project Operations Gate

## Date

2026-07-30

## Change Summary

Removed the project-wide deployment capability gate and its service. Project operations now use their normal lifecycle, archive, membership, durable-link, and idempotency checks without a separate runtime toggle.

## Files

- Deleted `ProjectOperationsAccessService`.
- Updated project services, controllers, project chat, materialization reads, tests, specifications, technical documentation, and historical planning records.

## Behavioral Impact

Project task controls, materialization Files reads, templates, project chat, outputs, assignment controls, submissions, and reassignment no longer return a capability-disabled response. Archive and member-removal guards remain enforced by `ProjectService`; project chat retains its bounded read-only tool profile.

## Specification Impact

Removed the obsolete project-wide deployment gate from the active API and service contracts. Existing system-chat and project-chat bounded-tool contracts are unchanged.

## Risks

The alpha application remains open at the HTTP layer; this change intentionally does not add or replace authentication/authorization.

## Follow-up Items

None.
