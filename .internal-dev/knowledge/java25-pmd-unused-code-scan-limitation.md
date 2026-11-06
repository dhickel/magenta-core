## Topic
PMD unused-code scanning limitation on Java 25 classfiles

## Source References
- `mvn -q org.apache.maven.plugins:maven-pmd-plugin:3.26.0:pmd ...`
- `mvn dependency:analyze` with `maven-dependency-plugin:3.7.0`
- Build-time error: `Unsupported class file major version 69`

## Key Takeaways
- PMD and Maven Dependency Plugin 3.7.0 analysis fail during bytecode resolution because this repository emits Java 25 classfile major version 69.
- For this repo/runtime, neither PMD nor `dependency:analyze` can be relied on for dead-code or used/unused dependency discovery without updating to a Java 25-compatible analyzer stack.
- Conservative fallbacks are explicit `dependency:tree` plus banned-coordinate checks for dependency review, symbol-usage analysis for private members, and compile/test/package validation.

## Engine Relevance
When asked for dead-code or dependency cleanup in this repo, prefer Java-25-compatible analyzers first; otherwise use conservative symbol/dependency-tree checks and verify with full tests, package scans, and startup/consumer fixtures.

## Open Questions
- Which analyzer/toolchain version should be standardized for Java 25 dead-code scanning in CI and local runs?
