// Agent chat: live SSE chat panel for the operational UI.
// JavaScript is used here because live SSE stream initialization and
// panel lifecycle management are the path of least resistance vs HTMX.
// HTMX remains the default for all CRUD, tabs, filters, and forms.

import { consumeSse, renderError } from "./api.js";
import { $, escapeHtml } from "./dom.js";

let activeConversationId = null;

export function initAgentChat(root = document) {
    const host = $("[data-agent-chat-panel]", root);
    if (!host || host.dataset.agentChatInitialized === "true") return;

    const accordion = host.closest(".agent-chat-accordion");
    if (!$("#agent-chat-panel", host)) {
        host.innerHTML = `
            <section class="agent-chat-panel" id="agent-chat-panel">
                ${accordion ? "" : `
                <div class="agent-chat-header">
                    <strong>Agent Chat</strong>
                    <div>
                        <button type="button" data-agent-chat-toggle>Collapse</button>
                    </div>
                </div>`}
                <div class="agent-chat-body" id="agent-chat-messages"></div>
                <form class="agent-chat-form" id="agent-chat-form">
                    <input id="agent-chat-input" placeholder="Ask this agent">
                    <button type="submit">Send</button>
                </form>
            </section>`;
    }
    host.dataset.agentChatInitialized = "true";

    const panel = $("#agent-chat-panel", host);
    const toggle = $("[data-agent-chat-toggle]", panel);
    const messages = $("#agent-chat-messages", panel);
    const form = $("#agent-chat-form", panel);
    const input = $("#agent-chat-input", panel);
    const page = $("[data-orchestration-page]");
    const context = host.dataset.pageContext || page?.dataset.orchestrationPage || "orchestration page";

    toggle?.addEventListener("click", () => {
        panel.classList.toggle("collapsed");
        toggle.textContent = panel.classList.contains("collapsed") ? "Open" : "Collapse";
    });

    form.addEventListener("submit", async event => {
        event.preventDefault();
        const agentId = host.dataset.agentId
            || page?.dataset.agentId
            || $("#jobs-agent-select")?.value
            || $("#settings-default-agent-id")?.value;
        const message = input.value.trim();
        if (!agentId || !message) return;
        append(messages, "user", message);
        input.value = "";
        try {
            const response = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/stream`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ conversationId: activeConversationId, message, pageContext: context })
            });
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || `${response.status} ${response.statusText}`);
            }
            await consumeSse(response, {
                start: data => append(messages, "system", `Connected to ${data.agentId || data.agentName || agentId}`),
                chunk: data => append(messages, "assistant", data.message || data.response || ""),
                done: data => {
                    activeConversationId = data.conversationId || activeConversationId;
                    append(messages, "assistant", data.message || data.response || "Done.");
                },
                error: data => append(messages, "system", data.error || "Agent chat failed.")
            });
        } catch (error) {
            append(messages, "system", error.message || "Agent chat request failed");
        }
    });
}

function append(root, role, text) {
    root.insertAdjacentHTML("beforeend", `<div class="agent-chat-message"><strong>${escapeHtml(role)}</strong><br>${escapeHtml(text)}</div>`);
    root.scrollTop = root.scrollHeight;
}

// Auto-initialize on full page load
document.addEventListener("DOMContentLoaded", () => initAgentChat());

// Reinitialize after HTMX tab/content swaps
document.addEventListener("htmx:afterSettle", () => initAgentChat());
