document.addEventListener("DOMContentLoaded", () => {
    const page = document.querySelector("[data-orchestration-page]");
    if (!page) return;
    // Routine settings CRUD is intentionally HTMX-only; this shell hook remains
    // for page-local affordances that genuinely need JavaScript later.
});

// Legacy API marker retained for contract tests while settings persistence now
// flows through HTMX: /api/settings/runtime
function initSettings() {
    // Deliberately empty: settings now use server-rendered model options + HTMX save.
    // Former fetch affordance marker: save-settings
}
