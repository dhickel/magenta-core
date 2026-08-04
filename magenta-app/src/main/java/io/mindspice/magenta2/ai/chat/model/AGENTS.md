## Chat Model Package

This package owns chat request, response, history, session, message, context usage, stream event payloads, and shared interaction modes.

### Responsibilities
- Keep DTOs small, stable, and easy to serialize, including the shared `PlanMode` enum for plan, task, and normal interaction modes, plus minimal plan state and structured tool activity returned to chat clients.
- Use Java records for request/response and data-carrier types where practical.
- Keep transport shape explicit; avoid adding fields for speculative future clients.

### Change guidance
- Treat changes here as API surface changes when the type is returned from or accepted by controllers.
- Prefer adding only fields required by the current workflow.
- Keep model names direct and aligned with the API concept they represent.
- Keep this guide updated when payload responsibilities or compatibility expectations change.

### Validation
- Update controller or service tests when request/response shapes change.
- Check browser client usage when API payloads change.
