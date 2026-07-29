# Phase 04 Truthful HTTP Errors Validator Repair

## Date

2026-07-29

## Change Summary

Repaired Phase 04 error classification and redaction gaps found during validator review. Recognized data-access failures return safe `503` responses/fragments; unexpected runtime failures return safe `500`; concrete context conflicts remain `409`; and raw exception reasons, stack traces, and audit-stack payloads are not emitted at the HTTP boundary.

## Files

- `src/main/java/io/mindspice/magenta2/api/web/AgentContextController.java`
- `src/main/java/io/mindspice/magenta2/api/web/GlobalExceptionHandler.java`
- `src/test/java/io/mindspice/magenta2/api/web/AgentContextControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/ChatFileControllerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/GlobalExceptionHandlerTest.java`
- `src/test/java/io/mindspice/magenta2/api/web/PublicApiRouteBindingTest.java`
- `.internal-dev/knowledge/htmx-fragment-error-statuses.md`
- `.internal-dev/reviews/20260729-phase-04-truthful-http-errors-review.md`

## Behavioral Impact

Endpoint status semantics are now truthful for the reviewed paths, and error bodies/fragments no longer disclose exception-supplied operational details. Public route-binding coverage now expects the generic-handler `500` payload for unmapped static and unsupported-method requests, and the redacted `400` payload for a missing chat plan.

## Specification Impact

None. This repair implements the existing Phase 04/API error-status contract without changing routes, payload fields, or response shapes.

## Risks

Clients that depended on arbitrary exception text now receive stable safe messages; response status codes and response object shapes are preserved.

## Follow-up Items

None.
