# Standalone consumer fixture

`Consumer.java` is an executable Java 25 consumer smoke fixture. It compiles against the built `magenta-core` JAR and its published runtime dependencies, uses no Spring Boot or application context, and starts a deterministic JDK HTTP provider seam so no external model service or credential is required.

The fixture:

- pattern-matches every `Progress` and `Outcome` subtype, including `Message`, `Questions`, `Queued`, `Interrupted`, `TimedOut`, `QuestionsCancelled`, and `TurnCancelled`;
- executes direct send and cold stream calls;
- persists and answers a durable question;
- enqueues and drains queued work;
- constructs loops through both `dataRoot(Path)` and caller-owned `DataSource` paths; and
- verifies the JSON `type` discriminator for a response.

From the repository root after building the artifact:

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

Successful execution prints `consumer-ok`.
