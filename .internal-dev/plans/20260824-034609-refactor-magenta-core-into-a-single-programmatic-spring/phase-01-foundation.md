# Phase 01: Single-module foundation

## Context
The accepted job plan replaces the transitional three-module Spring Boot reactor with a root-level framework JAR. The existing branch contains the extracted core plus deleted-product residue and no substantive tests.

## Goal
Establish the root Maven library and migrate the surviving framework source into the canonical package without retaining application or auto-configuration surfaces.

## In Scope
- Root `pom.xml` as `io.mindspice:magenta-core` JAR on Java 25.
- Move core sources/resources to root `src/main` and rename packages to `io.mindspice.magenta.core`.
- Bundle supported Spring AI provider dependencies without web/servlet runtime dependencies.
- Remove `magenta-app`, `mindspice-core-autoconfigure`, and the old child module.

## Out of Scope
- Runtime builder, provider, persistence, interaction, and engine repairs, which follow in later phases.
- Product application restoration or compatibility shims.

## Implementation Steps
1. Rewrite the root POM and enforce the no-web boundary.
2. Migrate core Java/resources and package references.
3. Delete obsolete modules and app-only Java surfaces.
4. Compile/package to expose remaining runtime defects.

## Validation
- Root artifact resolves as `magenta-core` with no Maven modules.
- Java source/resource package scans show only `io.mindspice.magenta.core`.
- Focused Maven compile/package and dependency boundary checks.

## Exit Criteria
The root project is the only Maven module and the migrated framework source compiles far enough to begin runtime redesign.
