# Testing and consumer validation

The core test suite uses deterministic model doubles and temporary SQLite databases. It covers builder diagnostics, provider option construction, prompt/memory insertion, ordered questions, restart restoration, queued outcomes, cancellation, stream cancellation, audit/metrics/lifecycle hooks, fresh-schema coexistence, and tool annotation coercion.

Run the suite:

```bash
mvn clean verify
```

Compile the standalone consumer fixture without Spring Boot:

```bash
mvn -q package -DskipTests
mvn -q dependency:build-classpath -Dmdep.outputFile=target/consumer-classpath.txt
javac --release 25 \
  -cp "target/magenta-core-0.0.1-SNAPSHOT.jar:$(cat target/consumer-classpath.txt)" \
  -d target/consumer-fixture \
  consumer-fixture/src/main/java/io/example/Consumer.java
```
