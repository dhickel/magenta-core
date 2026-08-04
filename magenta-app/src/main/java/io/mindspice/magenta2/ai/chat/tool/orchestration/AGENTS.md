## Chat Orchestration Tool Package

This package owns model-visible operational tools for agent workspace and orchestration state.

### Responsibilities
- Expose Spring AI tools that wrap existing orchestration, workspace, job, inbox, schedule, project, and output services.
- Keep every normal `agent_*` tool scoped to the current `OrchestrationTaskContext`.
- Return compact JSON-oriented records instead of raw service/domain graphs.
- Keep final authorization checks inside this package even when upstream chat policy filters approved tools.

### Change Guidance
- Do not add raw SQL, generic repository access, generic HTTP calls, broad filesystem browsing, or wildcard operational execution.
- Do not accept a normal-agent `agentId` argument; use the current orchestration context identity.
- Use existing services for mutations so lifecycle checks, events, descriptor confinement, and output publication policy stay centralized.
- Require exact confirmation strings for destructive or high-impact operations.
- If a required service API does not exist, document the integration gap in the lane handoff instead of bypassing the service boundary.

### Validation
- Add focused tests for tool registration, authorization failures, list limit bounding, destructive confirmations, workspace confinement, and representative service wrappers.
