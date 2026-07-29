## Date
2026-07-29

## Change Summary
Established singular assignment authority, gated project safety, stable scoped pagination, and idempotent controls as the foundation for the Project Dashboard and Unified Work Contracts sprint. Replaced generic assignment upsert with insert-only creation and narrow per-field writer methods. Added sealed AgentScope/ProjectScope query contracts, HMAC-signed cursor snapshots, and ProjectOperationsAccessService capability boundary. Added project archive/member-removal guards. 1084 tests pass (24 new). Bounded startup verified.

Specification Impact: Architecture and services specifications updated with new canonical assignment authority contract, sealed query scope types, and ProjectOperationsAccessService capability boundary.

## Files
### New
- `AssignmentControlService.java`
- `AssignmentQueryService.java`
- `ProjectOperationsAccessService.java`
- `014-project-assignment-authority.sql`
- `AssignmentAuthorityFoundationTest.java`

### Modified
- `WorkAssignment.java`, `AssignmentRequest.java`, `OrchestrationRuntimeRepository.java`, `AssignmentService.java`, `Project.java`, `ProjectRepository.java`, `ProjectService.java`, `ProjectController.java`, `SchemaMigrationRunner.java`, `schema.sql`, and 5 test files

## Risks
- Full runner cutover to insert-only: existing saveAssignment retains upsert for backward compatibility; runner fenced paths remain safe
- Job active-runs check in ProjectOperationsAccessService uses nullable jobRepository dependency; to be hardened in Phase 04
- Reassignment (revoke-and-clone) deferred to Phase 04

## Follow-up Items
- Phase 02: Project Queue and console seams
- Phase 04: Job reassignment and jobRepository hardening
