# Topic

HTMX mutation fragments should use HTTP error status codes on failed operations.

# Source References

- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/test/java/io/mindspice/magenta2/api/web/OrchestrationControllerTest.java`
- `.internal-dev/plans/public-alpha-remediation/06-operational-ui-htmx-mobile/subplan-03-htmx-error-statuses.md`
- `.internal-dev/bugs/public-alpha-quality-review/bug-20-medium-fragment-errors-return-200/report.md`

# Key Takeaways

For operational UI mutations, returning an error fragment with `200 OK` hides failures from HTMX-aware automation and operators inspecting network behavior. Keep the fragment body useful, but set the response status locally in the controller before returning the rendered component.

HTMX 1.9 defaults `shouldSwap=false` for `>=400` responses. When a failed mutation returns a useful server-rendered fragment, the browser layer needs a narrow `htmx:beforeSwap` opt-in rather than a JSON/JavaScript transport rewrite. In Magenta this behavior belongs in the active shell/client helper for operational fragments and should remain limited to same-origin non-401/403 responses that have an existing target and known operational fragment markers such as `.orch-error`, `.orch-status-error`, or `.agent-lifecycle-panel`.

Use `400` for validation or user-correctable request errors, `409` when the current lifecycle state conflicts with the requested mutation, `404` for scoped not-found or wrong-owner cases when the existing lifecycle helper maps that way, `503` for a recognized typed infrastructure failure (for example, `DataAccessException`), and `500` for unexpected failures.

Fragments must use stable safe text rather than exception messages. Do not log or audit raw exception messages or stack traces at this HTTP boundary; record only a non-sensitive classification. `IllegalStateException` is not a conflict classifier.

# Engine Relevance

This pattern fits SimplyPages and HTMX-first controller fragments: the controller remains thin, returns the same component markup, and avoids introducing JavaScript transport solely to surface failure state.

# Open Questions

Browser-origin validation should continue checking that target swaps remain visible for non-2xx HTMX responses on the changed operational pages.
