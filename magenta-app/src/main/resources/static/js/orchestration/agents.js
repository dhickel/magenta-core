// HTMX handles all agent CRUD, tab loading, editor saves, and submit-to-agent.
// JS is used only for tab active-state affordance.
document.addEventListener("DOMContentLoaded", () => {
    const page = document.querySelector("[data-orchestration-page='agents']");
    if (!page) return;

    const tabButtons = () => Array.from(document.querySelectorAll("[data-tab-button='true']"));
    const setActive = (tabName) => {
        tabButtons().forEach((btn) => {
            if (btn.dataset.tab === tabName) {
                btn.classList.add("active");
            } else {
                btn.classList.remove("active");
            }
        });
    };

    setActive("dashboard");
    document.body.addEventListener("click", (event) => {
        const button = event.target.closest("[data-tab-button='true']");
        if (!button) return;
        setActive(button.dataset.tab || "");
    });
});
