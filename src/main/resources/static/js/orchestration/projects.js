// Project console tab affordance only; HTMX owns navigation and all transport.
(function () {
    "use strict";

    function activate(button) {
        const tabs = button.closest(".project-tabs");
        if (!tabs) return;
        tabs.querySelectorAll("[data-project-tab]").forEach((candidate) => {
            candidate.classList.toggle("active", candidate === button);
            candidate.setAttribute("aria-selected", candidate === button ? "true" : "false");
        });
    }

    document.addEventListener("click", (event) => {
        const button = event.target.closest("[data-project-tab]");
        if (button) activate(button);
    });
})();
