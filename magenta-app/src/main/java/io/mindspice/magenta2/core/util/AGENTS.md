## Core Utility Package

This package owns small, general-purpose utilities.

### Responsibilities
- Keep utilities tiny, dependency-light, and broadly applicable.
- Prefer standard Java and Spring utilities before adding new helpers.
- Keep helper behavior obvious from the name and signature.

### Change guidance
- Do not add utility code for one-off convenience when package-local code would be clearer.
- Avoid clever generic abstractions.
- Keep this guide updated when utility conventions or shared helper behavior changes.

### Validation
- Add unit tests for utility behavior that has branching, error handling, or non-obvious semantics.
