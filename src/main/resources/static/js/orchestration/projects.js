// Project tab presentation and browser-history coordination only; HTMX owns transport.
(function () {
    "use strict";

    function activateTab(button) {
        var tabs = button.closest(".project-tabs");
        if (!tabs) return;
        tabs.querySelectorAll("[data-project-tab]").forEach(function (candidate) {
            var active = candidate === button;
            candidate.classList.toggle("active", active);
            candidate.setAttribute("aria-selected", active ? "true" : "false");
        });
    }

    document.addEventListener("click", function (event) {
        var button = event.target.closest("[data-project-tab]");
        if (!button) return;
        activateTab(button);

        var shell = document.querySelector("[data-project-id]");
        if (!shell || !button.dataset.projectTab || !window.history.replaceState) return;
        window.history.replaceState(null, "", "/projects/" + encodeURIComponent(shell.dataset.projectId)
            + "?tab=" + encodeURIComponent(button.dataset.projectTab));
    });

    document.addEventListener("htmx:afterSwap", function (event) {
        var target = event.detail.target;
        if (!target || (target.id !== "project-tab-panel" && target.id !== "project-detail-container")) return;
        var path = event.detail.pathInfo && event.detail.pathInfo.requestPath;
        if (!path) return;
        var match = path.match(/\/projects\/_detail\/[^/]+\/(?:queue(?:\/history)?|[^/?]+)/);
        if (!match) return;
        var tab = path.includes("/queue") ? "queue" : path.split("/").pop().split("?")[0];
        var button = document.querySelector("[data-project-tab='" + tab + "']");
        if (button) activateTab(button);
    });
}());
