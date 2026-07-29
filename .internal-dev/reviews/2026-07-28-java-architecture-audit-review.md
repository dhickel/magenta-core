# Java Architecture Audit Review

## Scope

Static audit of `src/main/java` against `.internal-dev/specifications/architecture.md`, `service-graph.md`, `services.md`, and `api.md`; Maven compilation and isolated Spring startup were also checked.

## Findings

1. Web adapters inject and call persistence repositories directly for artifact, skill, and runtime read paths, bypassing services.
2. Several service classes use `JdbcTemplate` and inline SQL rather than repositories; artifact extraction updates multiple persistence structures without one transaction.
3. `WorkspaceExplorerService` retains `heldMutationKind` in a singleton `ThreadLocal` without clearing it after a mutation.
4. System-chat model invocation runs under `@Transactional`, holding a database transaction across a remote model call.
5. Chat/plan services produce rendered markdown HTML, and controllers construct domain aggregates/assignment requests.
6. Error mapping conflates infrastructure failures with not-found/conflict responses in agent context and chat-file paths; the global handler maps all `IllegalStateException` values to `409`.
7. Feature/executor/tool configuration is split among repeated `@Value` injection and direct system-property reads.
8. API creation and response-status behavior is inconsistent. Workspace create routes documented as `201` default to `200`; several other resource creates/deletes also rely on framework defaults.

## Risk Assessment

High: transaction held across remote system-chat invocation; stale `ThreadLocal` authorization context.

Medium: service/repository boundary drift, raw SQL in services, non-atomic FTS persistence, error misclassification, and API-contract status drift.

Low: configuration and response-style sprawl; oversized controller composition increases maintenance risk.

No Spring dependency cycle was observed in the isolated application startup. The project uses JDBC rather than JPA/Hibernate, so JPA lazy-loading findings do not apply. No bean was identified that requires prototype scope.

## Recommendations

- Add service-owned query/read-model APIs and repositories for direct web and inline-SQL persistence paths.
- Scope `heldMutationKind` with `try/finally` and clear it after each operation.
- Move the remote system-chat call outside the transactional boundary.
- Use typed domain exceptions/status mapping; classify missing files separately from I/O failures.
- Consolidate operational settings in validated `@ConfigurationProperties` records.
- Standardize REST response/status conventions, beginning with documented workspace create routes.

## Follow-ups

- Add focused tests for ThreadLocal cleanup, FTS update rollback, system-chat transaction scope, I/O status mapping, and `201` workspace creation responses.
- Split route/domain mapping from `PlanController`, `TaskController`, and the large `OrchestrationController` incrementally without broad route changes.
