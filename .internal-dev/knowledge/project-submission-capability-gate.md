# Project Submission Capability Gate

## Topic

Project-attributed assignment submission must enforce trusted deployment at the shared service boundary.

## Source References

- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectOperationsAccessService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java`
- `.internal-dev/specifications/services.md`
- `.internal-dev/changelogs/2026-07-29-project-dashboard-integration-capability-gate.md`

## Key Takeaways

- Membership and active-project checks alone are insufficient for a sensitive project submission.
- `requireEligibleSubmission` is the shared enforcement point used by direct assignments, Work Tasks, and job reassignment; it must require trusted deployment before resolving member eligibility.
- Tests that intentionally exercise enabled project submission must enable the server-owned capability explicitly instead of relying on a membership fixture.

## Engine Relevance

This prevents a new project submission path from bypassing the Phase 01 capability boundary while preserving one canonical assignment authority.

## Open Questions

None.
