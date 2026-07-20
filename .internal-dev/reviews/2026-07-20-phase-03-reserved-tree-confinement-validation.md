# Phase 03 Reserved-Tree Confinement Validation

## Scope

Independent validation of `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-03-reserved-tree-confinement.md`, covering the reserved-path policy, explorer, file tool, shell behavior, mutation-route claims, adversarial coverage, full Maven suite, and bounded startup.

## Findings

### Blocking findings

1. **Race-resistant mutation primitives are not implemented.** `WorkspaceExplorerService` and `AgentFileToolService` perform path checks followed by ordinary path-based `Files.write*`, `Files.move`, `Files.copy`, `Files.delete`, and `Files.create*` calls. They do not use `SecureDirectoryStream`, relative directory handles, parent identity rechecks at the primitive, or an explicit unsupported-provider fail-closed result. The implementation changelog itself acknowledges the TOCTOU window. This directly violates Phase 03 steps 8-9 and the exit criteria.

2. **Hard-link alias protection is absent.** No link-count (`unix:nlink` or equivalent) inspection exists in either mutation boundary. An ordinary-path hard link can therefore alias protected bytes and be edited through an ordinary route. The required hard-link adversarial test is absent.

3. **Adversarial coverage claims exceed the tests.** `ReservedTreeExplorerAdversarialTest` has 22 passing tests, but no coordinated destination-link swap, parent replacement between check/use, hard-link alias, stale/expired/mismatched capability, interrupted batch, unsupported-provider fail-closed, or real check/use race test. The destination-symlink test creates the symlink before validation, not between validation and mutation.

4. **Run-writer validation does not bind the capability to the trusted location at the mutation boundary.** `AgentFileToolService.requireCurrentWriterForMutation()` calls `TaskRunLifecycleService.requireCurrentWriter(capability)`, whose database query checks only capability tuple currency. It does not compare the capability task-run id to `context.taskRunLocation().taskRunId()` or accept the expected owner/location as required by the phase contract. A current capability can therefore be paired with a different trusted run location in context. Validation also occurs before path resolution and other work rather than immediately before each write primitive.

5. **The route matrix is not an enforcement matrix for all claimed routes.** Production references to `ReservedWorkspacePathPolicy` exist only in `WorkspaceExplorerService` and `AgentFileToolService`. `OutputArtifactService` and `AgentSkillManagementService` are listed as deferred follow-ups rather than routed through publication/Skills policy authority. The matrix states 19 routes and “no unguarded bypass,” but its own open questions defer two application-managed mutation owners. This does not satisfy the phase requirement that every existing application-managed filesystem mutation route be enforced in this phase.

6. **Policy classification accepts structurally ambiguous task paths.** `ReservedWorkspacePathPolicy` classifies any second and third task-path segments as task key/run roots without validating canonical task-key or run-id grammar. This conflicts with the contract to reject paths that cannot be classified without guessing and to consume normalized paths without creating a permissive parallel grammar.

7. **Recursive file-tool reads bypass hidden-tree browse decisions.** `AgentFileToolService.list()` and `search()` authorize only the requested ancestor and then traverse with `Files.walk()` without classifying/filtering each discovered path. Listing or searching `.` can therefore disclose workspace `.skills` descendants, and traversing a run root can disclose publication staging descendants, despite direct access being denied.

### Passing observations

- `mvn test`: 780 tests, 0 failures/errors/skips.
- Focused policy/explorer/file/shell/lifecycle suite: 172 tests, 0 failures/errors/skips.
- Clean-root bounded Spring Boot startup reached `Started Magenta2Application` in 3.63 seconds and shut down cleanly at timeout.
- Shell execution in active orchestration contexts returns `confinementUnavailable=true`, `executionType="unavailable"`, no exit code, and the explicit confinement-unavailable message.
- The 9 enum classifications and all 54 classification/operation matrix combinations are exercised by the policy test, but structural-input validation remains incomplete as noted above.

## Risk Assessment

**Critical / phase-blocking.** The lexical policy blocks straightforward reserved-tree mutations, but the implementation does not prove confinement against the race, hard-link, capability-location mismatch, and route-coverage adversaries explicitly required by the phase contract. Passing unit tests do not compensate for absent required controls and absent deterministic adversarial tests.

The default local-root startup also failed because the existing local `schema_migrations` table was incompatible; a clean temporary root started successfully. This appears environment-state-specific and is not the basis of the Phase 03 verdict.

## Recommendations

1. Implement one race-resistant mutation boundary using provider-supported relative directory handles; fail closed with a typed unavailable result when the provider cannot prove the required semantics.
2. Add existing-file hard-link count checks before every mutation and deterministic tests proving protected bytes remain unchanged.
3. Bind each writer check to the exact expected `TaskRunLocation`, owner, attempt, and fence immediately before every mutation primitive.
4. Apply browse policy to every recursively discovered file/directory and add root-recursive list/search tests proving `.skills` and staging remain hidden.
5. Add latch/hook-driven destination and parent replacement tests, unsupported-provider tests, and stale/mismatched/cross-workspace capability tests for every write-like file-tool operation.
6. Reconcile the route matrix with actual policy references and route all current publication and Skills mutation owners through their dedicated authorities, or obtain an explicit phase-scope revision.
7. Validate task key and run-id segments using the existing canonical validators/layout contract rather than accepting arbitrary segments.

## Remediation Validation

The seven blocking findings were rechecked against the remediated repository state:

1. Explorer and file-tool mutation primitives now use held Linux `openat`-family directory descriptors with no-follow traversal; mkdir, write, move, copy destination creation, and delete occur relative to held handles. Unsupported providers raise typed `ConfinementUnavailableException` without path fallback.
2. Existing regular files are checked through opened-file `fstat.st_nlink`; deterministic hard-link tests prove an ordinary alias cannot modify protected bytes.
3. Deterministic hook/latch parent and destination swaps, hard links, non-default-provider fail-closed, recursive hidden-tree, canonical grammar, and capability mismatch tests pass.
4. `TaskRunWriteCapability` carries workspace kind/key, canonical task-path key, and run id; mutation validation matches all location fields and current attempt/owner/generation/token immediately before the primitive.
5. Recursive file-tool traversal uses `walkFileTree`, classifies every entry, and returns `SKIP_SUBTREE` for `.skills` and publication staging.
6. The route matrix no longer claims 19 uniformly enforced routes. It identifies controller delegation, structural directory ownership, the absent upload/import/archive routes, the separate global skill repository, and the explicit Phase 04 publication gap accurately.
7. Task key and run components are validated by `WorkspacePathLayout`; arbitrary or reserved ambiguous segments are rejected.

Validation evidence:

- Focused confinement/lifecycle/service/controller suites: PASS.
- Full `mvn test`: 792 tests, 0 failures, 0 errors, 0 skipped.
- Clean-root bounded startup: `Started Magenta2Application` in 3.752 seconds; timeout exit 124 after successful startup.
- Final `git diff --check`: PASS.

## Follow-ups

- Phase 04 owns the explicitly documented publication mutation cutover; it is no longer misstated as Phase 03 ordinary-route enforcement.
- Provider support remains intentionally limited to tested Linux 64-bit default POSIX filesystems and must fail closed elsewhere.

**VERDICT: PASS**
