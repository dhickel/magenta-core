# Validation

Run the complete transport-neutral build with Java 25:

```bash
mvn clean verify
```

Useful boundary checks:

```bash
mvn help:evaluate -Dexpression=project.artifactId -DforceStdout
mvn dependency:tree -Dscope=runtime
jar tf target/magenta-core-*.jar
```

The dependency graph must not contain Spring Boot auto-configuration, servlet APIs, MVC/WebFlux starters, embedded servers, Actuator, or application classes. Spring AI provider clients may bring `spring-web`/`spring-webflux` transport libraries transitively; those are client implementation dependencies, not a web application surface. Consumer examples use only the published `magenta-core` dependency and programmatic builder calls.
