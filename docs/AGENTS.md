# Documentation guide

This guide governs `docs/`.

## Source of truth

Code and tests define observed behavior. Repository specifications define intended behavior. Documentation must describe only the current transport-neutral Java library. If these sources conflict, report and route the drift through `.internal-dev`; do not guess.

## Diátaxis structure

- `tutorials/`: guided learning with prerequisites and an observable result.
- `how-to/`: focused procedures for a concrete task.
- `reference/`: exact public API, configuration, protocol, schema, and SPI facts.
- `explanation/`: architecture, design rationale, and tradeoffs.
- `README.md`: documentation index and audience routing.

Every new page must be linked from `docs/README.md` or a linked category page. Prefer relative links.

## Boundaries and style

- Document Java 25 and the current `io.mindspice.magenta.core` API.
- Do not document Spring Boot applications, auto-configuration, HTTP routes, UI, unsupported providers, or provider-specific types as caller API.
- Use current sealed variants instead of role strings, discriminator switches, default switch branches, compatibility shims, or removed types.
- Keep examples concise, explicit about prerequisites, and clear about expected outcomes.
- Tutorials teach: each tutorial needs prerequisites, complete runnable code (or clearly delimited snippets matching current signatures), expected behavior, and links to the relevant how-to/reference pages. A complete tutorial program must compile against the built JAR; mark the fenced block with `<!-- tutorial-compile: FileName.java -->` so the validation script can extract and compile it.
- Update reference pages when public signatures, schema, provider support, or serialized type tags change.
- Remove stale duplication instead of preserving it as historical documentation.
- Sequence diagrams and step-by-step flows are welcome in `explanation/` when they clarify ordering or races; keep caveats accurate.
- After documentation changes, run `mvn clean verify`, compile the tutorial programs, and check relative links and stale symbols.
- Keep dependency versions and JAR filenames in examples synchronized with `pom.xml`; a version bump is a documentation change.
