# Standalone consumer fixture

`Consumer.java` is compiled against the built `magenta-core` JAR and its published runtime dependencies. It uses only programmatic builder calls and does not import Spring Boot or create an application context.

From the repository root after `mvn package -DskipTests`:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/consumer-classpath.txt
javac --release 25 -cp "target/magenta-core-0.0.1-SNAPSHOT.jar:$(cat target/consumer-classpath.txt)" \
  -d target/consumer-fixture consumer-fixture/src/main/java/io/example/Consumer.java
java -cp "target/consumer-fixture:target/magenta-core-0.0.1-SNAPSHOT.jar:$(cat target/consumer-classpath.txt)" io.example.Consumer
```

The executable smoke fixture builds and closes one loop from a data root and one from a caller-owned SQLite `DataSource`.
