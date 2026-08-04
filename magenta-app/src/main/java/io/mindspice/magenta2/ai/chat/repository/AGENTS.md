## Chat Repository Package

This package owns chat persistence details.

### Responsibilities
- Persist and retrieve chat memory and session metadata.
- Provide side-effect-free, indexed conversation-existence checks used by the cycle-free workspace identity boundary.
- Preserve assistant message metadata needed by chat features, such as structured model thinking.
- Keep SQL, schema assumptions, and storage-specific behavior localized here.
- Preserve conversation identifiers and model metadata consistently.

### Change guidance
- Do not leak SQLite-specific details into services or controllers.
- Keep repository APIs narrow and named around use cases.
- Existence checks must not create schema or rows as part of the check and must not swallow unrelated SQL failures.
- Coordinate schema changes with `src/main/resources/schema.sql`.
- Keep this guide updated when persistence ownership, schema expectations, or repository contracts change.

### Validation
- Add repository or service tests for persistence behavior changes.
- Verify schema initialization still works for a clean local database.
