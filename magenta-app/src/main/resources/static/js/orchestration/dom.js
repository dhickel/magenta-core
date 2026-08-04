export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

export function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

export function chip(value) {
    const normalized = String(value || "unknown").toLowerCase();
    return `<span class="orch-status-chip ${escapeHtml(normalized)}">${escapeHtml(value || "unknown")}</span>`;
}

export function modelOptions(models, selected) {
    const values = [...new Set([selected, ...(models || [])].filter(Boolean))];
    return values.map(model => `<option value="${escapeHtml(model)}" ${model === selected ? "selected" : ""}>${escapeHtml(model)}</option>`).join("");
}

export function bindTabs(root, render) {
    $$(".orch-tabs button", root).forEach(button => {
        button.addEventListener("click", () => {
            $$(".orch-tabs button", root).forEach(item => item.classList.remove("active"));
            button.classList.add("active");
            render(button.dataset.tab);
        });
    });
}

export function formValue(root, name) {
    const node = $(`[name="${name}"]`, root);
    if (!node) return null;
    if (node.type === "checkbox") return node.checked;
    if (node.type === "number") return node.value === "" ? null : Number(node.value);
    return node.value;
}
