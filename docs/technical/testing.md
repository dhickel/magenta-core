# Testing and consumer validation

The core test suite uses deterministic model seams and temporary SQLite databases. It covers sealed protocol invariants and JSON tags, sync/stream parity, ordered questions, restart restoration, queue receipts and drain, cancellation, lifecycle/failure hooks, schema incompatibility, provider transport, and tool behavior.

Run the full suite:

```bash
mvn clean verify
```

Validate the standalone consumer fixture from the repository root. It compiles only against the built `magenta-core` artifact plus its published runtime dependencies and runs without Spring Boot or an application context:

```bash
mvn -q package -DskipTests
mvn -q dependency:build-classpath -Dmdep.outputFile=target/consumer-classpath.txt
rm -rf target/consumer-fixture
javac --release 25 \
  -cp "target/magenta-core-0.0.1-SNAPSHOT.jar:$(cat target/consumer-classpath.txt)" \
  -d target/consumer-fixture \
  consumer-fixture/src/main/java/io/example/Consumer.java
java -cp "target/consumer-fixture:target/magenta-core-0.0.1-SNAPSHOT.jar:$(cat target/consumer-classpath.txt)" \
  io.example.Consumer
```

The fixture starts a deterministic JDK HTTP provider seam, pattern-matches every progress and outcome subtype, exercises questions, queue drain, and both storage construction paths.
