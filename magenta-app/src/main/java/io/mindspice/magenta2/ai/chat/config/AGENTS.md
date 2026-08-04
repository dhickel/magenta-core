## Chat Configuration Package

This package owns Spring bean wiring for chat and chat memory behavior.

### Responsibilities
- Configure Spring AI chat clients, advisors, token estimation, memory, and related beans.
- Keep wiring explicit and readable.
- Leave chat use-case behavior in the service package.

### Change guidance
- Do not hide feature logic inside bean factories.
- Prefer small configuration methods with clear bean names.
- Avoid adding configuration branches for future models, tools, or orchestration until a concrete workflow needs them.
- Keep this guide updated when chat wiring responsibilities or conventions change.

### Validation
- Add or update wiring-focused tests when bean creation behavior changes.
- Run `mvn test` for changes that affect Spring context construction.
