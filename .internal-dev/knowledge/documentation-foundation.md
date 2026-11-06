# Documentation Foundation

## Topic

Documentation structure, currency, and update governance for the transport-neutral library branch.

## Source References

- `docs/AGENTS.md`
- `docs/README.md`
- `.internal-dev/changelogs/2026-08-24-diataxis-documentation.md`

## Key Takeaways

- `docs/` uses the Diátaxis structure: `tutorials/`, `how-to/`, `reference/`, and `explanation/`, indexed by `docs/README.md`.
- The former `docs/api/`, `docs/technical/`, and `docs/end-user/` trees are deleted; do not add pages there or link to them.
- Documentation is part of the change, not a follow-up: a public signature, builder option, provider variant, schema, or behavior change updates the affected pages and `README.md` in the same change.
- Reference pages own exact API, configuration, protocol, and schema facts; tutorials carry `<!-- tutorial-compile: Name.java -->` markers so their complete programs compile against the built JAR.
- Dependency versions and JAR filenames in examples track `pom.xml`; a version bump updates the README, tutorial commands, and consumer-fixture commands.
- Validation after documentation changes: `mvn clean verify`, tutorial program compilation, relative-link and anchor checking, and a stale-symbol scan.

## Engine Relevance

Agents implementing Magenta Core changes must treat stale examples, removed symbols, outdated variant or option lists, and mismatched artifact versions as defects and repair them while working in the area.

## Open Questions

- Markdown link checking and tutorial compilation are still repository-scripted rather than Maven- or CI-enforced.
