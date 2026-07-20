# About project

This project is an agentic orchestration end-user frame work, in the vain of Openclaw, but with its own approach.
This project aims to let users control a team of agents, configure reoccuring tasks, and be an "AI Platform" at home.
This project will act as an assistant to the user and their household.



## Library Documentation
- Simply Pages (UI/Web framework): `/home/hickelpickle/Code/Java/cannasite/java-html-framework/docs`
- Spring AI `https://docs.spring.io/spring-ai/reference/index.html`

## `.internal-dev` Development Document Store

Follow the repository-level `.internal-dev` workflow in the top-level `AGENTS.md` and the detailed process/templates in `.internal-dev/AGENTS.md`.

Core-package work still participates in the same document-store contract:

- Run the required beginning pass before non-trivial implementation or planning.
- Write changelog, knowledge, bug, specification, plan, and review updates when the top-level workflow requires them.
- Keep `.internal-dev` access controlled; read only files needed for the active task.
- Write plans and reviews to `.internal-dev/plans/` and `.internal-dev/reviews/`.
- Keep package AGENTS guides aligned when core responsibilities or conventions change.


## Core Package

This package owns shared application services and cross-domain building blocks.

### Responsibilities
- Hold shared code only when more specific packages would otherwise duplicate real behavior.
- Keep core abstractions small and justified by current callers.
- Own ordered application-wide SQL migration execution under `core.persistence`; migration resources remain domain-specific and immutable.
- Avoid turning this package into a catch-all.

### Change guidance
- Prefer domain packages over `core` for feature-specific behavior.
- Add shared abstractions only when they simplify existing code now.
- Keep names concrete and easy to understand.
- Keep this guide updated when shared responsibilities or core conventions change.

### Validation
- Add focused tests for reusable behavior introduced here.
- Migration changes require fresh/upgrade, checksum/bookkeeping, interruption, independent concurrency, schema-equivalence, and bounded startup checks.
- Run tests for all packages that call changed core APIs.
