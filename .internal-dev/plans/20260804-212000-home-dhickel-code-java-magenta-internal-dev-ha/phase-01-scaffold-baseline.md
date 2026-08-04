# Phase 01 — Scaffold and Baseline

## Context

Before extraction begins, the project needs two new Maven modules with correct POM configurations, dependency management, and build-enforcement rules. The existing Magenta codebase must continue to build and pass tests unchanged.

## Goal

Create Maven module scaffolding with Enforcer rules; verify unchanged Magenta builds

## In Scope

**Write Targets**: pom.xml, turnloop-core/pom.xml, turnloop-autoconfigure/pom.xml

- Create `turnloop-core` module directory with POM, Maven Enforcer `bannedDependencies` rule, and empty source trees
- Create `turnloop-autoconfigure` module directory with POM depending on `turnloop-core`, `spring-boot-autoconfigure`, `sqlite-jdbc`, and optional `spring-boot-starter-actuator`
- Add `<modules>` section to root `pom.xml` listing both modules
- Verify `mvn clean verify` passes on unchanged Magenta codebase
- Smoke test context startup

## Out of Scope

- Creating any Java source files in the new modules
- Modifying Magenta source code
- Adding framework dependencies beyond the minimal scaffolding set
- Test execution for the new modules (they are empty)
- Deciding JPMS module name (the open question is settled per the accepted assumption: `Automatic-Module-Name: io.mindspice.turnloop` goes in `turnloop-core`)

## Dependencies

none

## Constraints

- Root POM must continue to build Magenta as before
- New module POMs inherit from root parent POM (`spring-boot-starter-parent` 4.0.7, `io.mindspice:magenta2:0.0.1-SNAPSHOT`)
- `turnloop-core` POM must use Maven Enforcer `bannedDependencies` to block servlet/Spring MVC dependencies from the start
- Empty source directories must be explicitly created with `.gitkeep` files so Git tracks them and Maven recognizes the modules as Java projects
- `turnloop-core` MANIFEST must include `<Automatic-Module-Name>io.mindspice.turnloop</Automatic-Module-Name>` (accepted assumption from handoff)

## Implementation Steps

### 1. Create turnloop-core module

Create directory `turnloop-core/` at project root with these empty source directories, each containing a `.gitkeep` file:

```
turnloop-core/src/main/java/.gitkeep
turnloop-core/src/main/resources/.gitkeep
turnloop-core/src/test/java/.gitkeep
turnloop-core/src/test/resources/.gitkeep
```

Create `turnloop-core/pom.xml` with:
- Parent `io.mindspice:magenta2` (version inherited)
- Artifact `turnloop-core`
- Empty `<dependencies>`
- Maven Enforcer plugin with `bannedDependencies` rule:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-enforcer-plugin</artifactId>
            <executions>
                <execution>
                    <id>ban-web-dependencies</id>
                    <goals><goal>enforce</goal></goals>
                    <configuration>
                        <rules>
                            <bannedDependencies>
                                <excludes>
                                    <exclude>org.springframework.boot:spring-boot-starter-web</exclude>
                                    <exclude>org.springframework:spring-web</exclude>
                                    <exclude>org.springframework:spring-webmvc</exclude>
                                    <exclude>jakarta.servlet:jakarta.servlet-api</exclude>
                                    <exclude>org.springframework.boot:spring-boot-starter-webmvc-test</exclude>
                                </excludes>
                            </bannedDependencies>
                        </rules>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Add the JPMS `Automatic-Module-Name` to the MANIFEST via the `maven-jar-plugin`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <configuration>
        <archive>
            <manifestEntries>
                <Automatic-Module-Name>io.mindspice.turnloop</Automatic-Module-Name>
            </manifestEntries>
        </archive>
    </configuration>
</plugin>
```

### 2. Create turnloop-autoconfigure module

Create directory `turnloop-autoconfigure/` at project root with the same four empty source directories plus `.gitkeep` files.

Create `turnloop-autoconfigure/pom.xml` with:
- Parent `io.mindspice:magenta2` (version inherited)
- Artifact `turnloop-autoconfigure`
- Dependencies:
  - `io.mindspice:turnloop-core` (version `${project.version}`)
  - `org.springframework.boot:spring-boot-autoconfigure`
  - `org.xerial:sqlite-jdbc` (version `3.50.3.0`)
  - `org.springframework.boot:spring-boot-starter-actuator` (optional)

### 3. Modify root pom.xml

Add a `<modules>` section before the existing `<dependencies>` section:

```xml
<modules>
    <module>turnloop-core</module>
    <module>turnloop-autoconfigure</module>
</modules>
```

### 4. Verify baseline

Run `mvn clean verify` from project root. The build must succeed with all existing Magenta tests passing — the new empty modules must not break anything.

Run `timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0` and confirm the application context starts without errors.

Inspect `git status` to verify all new directories and `.gitkeep` files are tracked.

## Required Guides

- `.internal-dev/specifications/AGENTS.md` — specification routing
- `.internal-dev/AGENTS.md` — workflow contracts

## Technical Guidance

- The root POM uses `spring-boot-starter-parent` 4.0.7. New module POMs inherit this parent transitively through `io.mindspice:magenta2`.
- Maven Enforcer `bannedDependencies` fails the build if any banned dependency appears in `turnloop-core`'s dependency tree (direct or transitive).
- The `excludes` entries use `groupId:artifactId` format without version — the rule matches any version.
- The `spring-boot-starter-webmvc-test` banned coordinate matches the artifact declared in the root POM. If this artifact name is later determined to be non-standard, correct the ban list in Phase 12 when test dependencies are added to `turnloop-core`.
- `sqlite-jdbc` version `3.50.3.0` matches the version already used in the root POM.
- `.gitkeep` files are zero-byte placeholders that cause Git to track otherwise-empty directories. Maven compiles empty source directories without errors.

## Validation

- `turnloop-core/pom.xml` exists with correct GAV (`io.mindspice:turnloop-core`), empty `<dependencies>`, Maven Enforcer `bannedDependencies` plugin, `maven-jar-plugin` with `Automatic-Module-Name`, and four empty source trees with `.gitkeep` files
- `turnloop-autoconfigure/pom.xml` exists with dependency on `io.mindspice:turnloop-core:${project.version}`, `spring-boot-autoconfigure`, `sqlite-jdbc`, and optional `spring-boot-starter-actuator`, plus four empty source trees with `.gitkeep` files
- Root `pom.xml` contains `<modules>` section with `<module>turnloop-core</module>` and `<module>turnloop-autoconfigure</module>` before `<dependencies>`
- `mvn clean verify` returns exit code 0 and all existing Magenta tests pass
- Context startup smoke test passes (no errors in log, Tomcat starts normally for Magenta)
- `git status` shows all new files and directories tracked

## Exit Criteria

- `turnloop-core/` and `turnloop-autoconfigure/` directories exist with correct POMs, source trees, and `.gitkeep` files on disk
- `mvn clean verify` passes from project root
- Context starts without errors
