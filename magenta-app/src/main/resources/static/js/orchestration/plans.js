// plans.js — v4 HTMX-first plan editing.
// All plan CRUD, field add/remove, list editing, submit-to-agent, and
// continue-in-chat are handled via HTMX endpoints.
//
// The small textarea autosize helper is intentionally client-side: textarea
// scrollHeight is the browser's source of truth after wrapping, fonts, and HTMX
// swaps have settled.
document.addEventListener("DOMContentLoaded", () => {
    const page = document.querySelector("[data-orchestration-page='plans']");
    if (!page) {
        return;
    }

    function autosizeTextarea(textarea) {
        if (!textarea || textarea.dataset.autosize !== "true") {
            return;
        }
        textarea.style.height = "auto";
        textarea.style.overflowY = "hidden";
        textarea.style.height = textarea.scrollHeight + "px";
    }

    function autosizeAll(root = page) {
        root.querySelectorAll("textarea[data-autosize='true']").forEach(autosizeTextarea);
    }

    autosizeAll();
    let editorDirty = false;

    function markClean() {
        editorDirty = false;
        page.dataset.editorDirty = "false";
    }

    function markDirty(target) {
        if (!target || !target.closest("[data-plan-tab-panel='editor']")) {
            return;
        }
        editorDirty = true;
        page.dataset.editorDirty = "true";
    }

    function requestWillReplaceEditor(requestTarget) {
        if (!requestTarget) {
            return false;
        }
        if (requestTarget.matches("#plan-editor-container")) {
            return true;
        }
        return Boolean(requestTarget.closest("#plan-editor-container"));
    }

    function shouldBypassDirtyGuard(trigger) {
        if (!trigger) {
            return false;
        }
        if (trigger.closest("#plan-editor-container form")) {
            return true;
        }
        return trigger.closest("#plan-modal-container") !== null;
    }

    page.addEventListener("input", (event) => {
        autosizeTextarea(event.target);
        markDirty(event.target);
    });

    document.body.addEventListener("htmx:beforeRequest", (event) => {
        if (!page.contains(event.detail.elt) || !editorDirty) {
            return;
        }
        const target = event.detail.target;
        if (!requestWillReplaceEditor(target) || shouldBypassDirtyGuard(event.detail.elt)) {
            return;
        }
        const confirmed = window.confirm("You have unsaved plan edits. Continue without saving?");
        if (!confirmed) {
            event.preventDefault();
        } else {
            markClean();
        }
    });

    document.body.addEventListener("htmx:afterSwap", (event) => {
        if (!page.contains(event.target)) {
            return;
        }
        if (event.target.id === "plan-editor-container") {
            markClean();
        }
        if (event.target.id === "plan-editor-container" || event.target.id === "plan-modal-container") {
            const modal = document.getElementById("plan-name-modal");
            if (!modal && event.target.id === "plan-editor-container") {
                const modalContainer = document.getElementById("plan-modal-container");
                if (modalContainer) {
                    modalContainer.innerHTML = "";
                }
            }
        }
        window.requestAnimationFrame(() => autosizeAll(event.target));
    });
});
