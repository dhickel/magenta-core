# Scope

Absolute final validation of Phase 05 against `phase-05-cut-over-execution-and-outputs.md` and `concepts.md`, including the full Maven suite, bounded fresh/existing-root startups, filesystem inspection, stale-contract scans, and exit-criteria review.

# Findings

- **PASS:** `mvn test` completed with 850 tests, 0 failures, 0 errors, and 0 skipped.
- **PASS:** Fresh-root bounded startup reached `Started Magenta2Application`; expected timeout exit 124. The root contained only `magenta.sqlite` and empty `root/`; no UUID workspace and no eager `outputs/` appeared.
- **PASS:** Existing-root bounded startup reached `Started Magenta2Application`; expected timeout exit 124. The inspected filesystem tree was unchanged.
- **BLOCKING:** `OrchestrationTaskContext` does not carry the required single `WorkspaceDescriptor`. It carries a host path and retains deprecated constructors accepting `hostOutputPath`, `hostRunPath`, and `hostRootPath` (`OrchestrationTaskContext.java:51-95`).
- **BLOCKING:** File and shell tools still actively interpret retired aliases and routes: `root/`, `run/`, `work/`, `projects/<id>`, and output aliases (`AgentFileToolService.java:416-481`; `AgentShellToolService.java:167-243`). `AgentsMdResolver` has the same active alias routing (`AgentsMdResolver.java:66-104`).
- **BLOCKING:** `ProjectService` still injects and calls `WorkspaceService` and `WorkspaceLeaseService`, creates/looks up project workspaces in `workspaceSummary`, exposes lease release, and gates deletion/membership removal on filesystem leases (`ProjectService.java:26-55, 221-288`). `ProjectController` still exposes project workspace/lease routes.
- **BLOCKING:** `OrchestrationController` still consumes `WorkspaceService` and `WorkAreaService`, and actively browses project filesystem roots through `WorkspaceDirectoryService.projectWorkspace` (`OrchestrationController.java:144-249, 4610-4709`).
- **BLOCKING:** `OutputArtifactService` removed discovery/promotion methods but retained deprecated compatibility constructors with the retired `looseArtifactDiscoveryEnabled` parameter (`OutputArtifactService.java:65-86`), violating the no-dual-contract/no-compatibility-overload rule.
- **BLOCKING:** Active production consumers remain for Phase 06 deletion targets, including generic `WorkspaceService`, `WorkspaceLeaseService`, `WorkAreaService`, and `WorkspaceRepository` outside the inert Phase 06-owned definitions.
- **PASS with classification:** Phase 05 fresh schema tables use `workspace_type`, `workspace_key`, and `workspace_identity`. Generic workspace/Work Area schema remains as Phase 06-owned definitions, but active Phase 05 consumers listed above make deletion unsafe.

# Risk Assessment

High. Phase 06 cannot safely delete obsolete workspace, Work Area, link, lease, project-root, and alias layers while Phase 05 runtime tools, context resolution, project services/controllers, and orchestration UI still call those contracts. Passing tests and clean startup do not establish the required descriptor-only vertical cutover because current tests preserve compatibility paths.

# Recommendations

1. Replace the host-path field and deprecated multi-root constructors in `OrchestrationTaskContext` with one resolved `WorkspaceDescriptor` plus active confined path; update all producers and consumers.
2. Remove retired alias handling from file/shell tools and `AgentsMdResolver`; bind every operation directly to the context descriptor root and reject reserved `outputs/` mutations.
3. Remove project filesystem summary/release/lease behavior and project-root browsing routes; retain project records, membership, events, and attribution only.
4. Remove compatibility constructors from `OutputArtifactService` and update tests to the direct publication contract.
5. Remove all remaining active consumers/providers of Phase 06 deletion targets, then repeat the full suite, both startups, filesystem inspection, and stale scan.

# Follow-ups

- **Verdict: BLOCKED.**
- No code edits were made: the remaining issues are coupled contract-remediation work, not small final-validator repairs.
- Validation root: `/tmp/magenta-phase05-final-OYXXQx`.
