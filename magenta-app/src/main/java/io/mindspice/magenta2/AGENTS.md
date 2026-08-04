## Application Root Package

This package owns the Spring Boot application entry point for Magenta.

### Responsibilities
- Keep application bootstrap small and conventional.
- Put feature behavior in domain packages instead of the application class.
- Use Spring configuration classes for bean wiring when setup grows beyond simple bootstrapping.
- Keep Agent Skills bootstrap wiring in dedicated skill-domain configuration and services; do not place parser/discovery or assignment logic in the app entry package.

### Change guidance
- Do not add cross-cutting runtime behavior here unless it truly affects the whole application.
- Prefer package-local configuration and services for feature work.
- Keep this guide updated when bootstrap responsibilities or application-wide conventions change.

### Validation
- Run focused tests for affected packages.
- For startup or wiring changes, run `mvn test` or a Spring context test that covers the new wiring.
