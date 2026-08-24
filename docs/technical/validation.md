# Validation

Run the complete transport-neutral build with Java 25:

```bash
mvn clean verify
```

Useful boundary checks:

```bash
mvn -q help:evaluate -Dexpression=project.artifactId -DforceStdout
mvn -q dependency:tree -Dscope=runtime
jar tf target/magenta-core-*.jar
```

The dependency graph must not contain Spring Boot auto-configuration, servlet APIs, MVC/WebFlux application starters, embedded servers, Actuator, or application classes. Spring AI provider clients may bring transport libraries transitively; they remain implementation dependencies rather than a web application surface. The public JAR exposes programmatic construction, Magenta-owned response records and SPIs, and JSON rendering only. Embedding applications own HTTP or other transport framing.

Finish with the standalone fixture validation in [Testing](testing.md), a forbidden-old-symbol scan, and `git diff --check`.
