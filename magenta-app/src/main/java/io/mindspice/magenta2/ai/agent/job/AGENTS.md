## Agent Job Package

This package owns Magenta-managed background agent job orchestration and persistence.

### Responsibilities
- Persist internal agent jobs, status transitions, model selection, input/result JSON, errors, and timestamps.
- Run bounded background work for concrete Magenta workflows.
- Keep job orchestration observable and separate from Spring AI model-call mechanics.

### Change guidance
- Do not expose public job APIs until a user-facing workflow needs them.
- Keep job types explicit and small.
- Keep model calls through existing chat model routing instead of creating separate provider clients.
- Coordinate schema changes with `src/main/resources/schema.sql`.

### Validation
- Add repository and service tests for new job types, status transitions, duplicate prevention, and executor behavior.
