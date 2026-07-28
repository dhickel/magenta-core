package io.mindspice.magenta2.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendControllerTest {

    private static ChatService stubChatService() {
        return new StubChatService();
    }

    private static void assertPrimaryTopNav(String html) {
        int home = html.indexOf("<a href=\"/\" class=\"navbar-item\">Home</a>");
        int dashboard = html.indexOf("<a href=\"/dashboard\" class=\"navbar-item\">Dashboard</a>");
        int chat = html.indexOf("<a href=\"/chat\" class=\"navbar-item\">Chat</a>");

        assertThat(home).isGreaterThanOrEqualTo(0);
        assertThat(dashboard).isGreaterThan(home);
        assertThat(chat).isGreaterThan(dashboard);
        assertThat(html).doesNotContain("<a href=\"/avatar\" class=\"navbar-item\">Avatar</a>");
    }

    @Test
    void homePageRendersWithDashboardLinks() {
        FrontendController controller = new FrontendController(stubChatService());
        String html = controller.home(null, null);

        assertThat(html).contains("/css/magenta.css?v=5");
        assertThat(html).doesNotContain("/js/alpha-security.js?v=1");
        assertThat(html).contains("Magenta Portal");
        assertThat(html).contains("/chat");
        assertThat(html).doesNotContain("/avatar");
        assertThat(html).contains("/dashboard");
        assertPrimaryTopNav(html);
        assertThat(html).contains("/webjars/htmx.org/dist/htmx.min.js");
        assertThat(html).doesNotContain("hx-get=\"/chat\"");
        assertThat(html).doesNotContain("/js/avatar-chat.js");
        assertThat(html).doesNotContain("hx-get=\"/dashboard\"");
        assertThat(html).doesNotContain("<style>");
    }

    @Test
    void chatPageRendersSimplyPagesChatShell() {
        FrontendController controller = new FrontendController(stubChatService());

        String html = controller.chat(null, null, null, null);

        assertThat(html).contains("/css/magenta.css?v=5");
        assertThat(html).doesNotContain("/js/alpha-security.js?v=1");
        assertThat(html).contains("id=\"chat-token-usage\"");
        assertThat(html).contains("id=\"chat-plan-evidence\"");
        assertThat(html).contains("id=\"magenta-chat-module\"");
        assertThat(html).contains("data-sp-chat=\"true\"");
        assertThat(html).contains("/api/fragments/chat/transcript");
        assertThat(html).contains("/js/chat-client.js?v=30");
        assertThat(html).doesNotContain("/js/avatar-chat.js");
        assertThat(html).contains("id=\"chat-planning-panel\"");
        assertThat(html).contains("id=\"chat-session-files\"");
        assertThat(html).contains("class=\"chat-files-panel\"");
        assertThat(html).contains("id=\"chat-session-files-body\"");
        assertThat(html).contains("Outputs");
        assertThat(html).contains("Select a chat to view outputs.");
        assertThat(html).contains("class=\"chat-session-bulk-actions\"");
        assertThat(html).doesNotContain(".chat-session-hash-chip");
        assertThat(html).contains("id=\"chat-session-select-all\"");
        assertThat(html).contains("data-bulk-action=\"delete\"");
        assertThat(html).contains("<option value=\"alias-qwen\" selected>alias-qwen</option>");
        assertThat(html).contains("<option value=\"alias-planner\" selected>alias-planner</option>");
        assertThat(html).doesNotContain(">qwen3.6:35b<");
        assertThat(html).doesNotContain("id=\"chat-session-bulk-list\"");
        assertThat(html).contains("data-active-conversation-id");
        assertThat(html).contains("<code id=\"chat-active-session\">New chat</code>");
        assertThat(html).contains("/webjars/htmx.org/dist/htmx.min.js");
        assertPrimaryTopNav(html);
        assertThat(html).doesNotContain("<style>");
    }

    @Test
    void chatPageIsolatesFromOrchestrationScripts() {
        FrontendController controller = new FrontendController(stubChatService());
        String html = controller.chat(null, null, null, null);

        // Chat page must NOT load orchestration dashboard scripts
        assertThat(html).doesNotContain("/js/orchestration/dashboard.js");
        assertThat(html).doesNotContain("/js/orchestration/plans.js");
        assertThat(html).doesNotContain("/js/orchestration/workflows.js");
        assertThat(html).doesNotContain("/js/orchestration/inbox.js");
        assertThat(html).doesNotContain("data-orchestration-page");
    }

    @Test
    void chatClientHandlesUnsavedConversationState() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/chat-client.js"));

        assertThat(js).contains("return value ? value : null;");
        assertThat(js).contains("activeEl.textContent = title || conversationId || 'New chat';");
        assertThat(js).contains("renderSessions(data.sessions || data.conversationIds);");
        assertThat(js).contains("pollConversationTitle(completedConversationId);");
        assertThat(js).contains("data-rename-id");
        assertThat(js).contains("data-delete-id");
        assertThat(js).contains("data-favorite-id");
        assertThat(js).contains("data-archive-id");
        assertThat(js).contains("data-bulk-select");
        assertThat(js).contains("data-bulk-action");
        assertThat(js).contains("shortConversationLabel");
        assertThat(js).contains("syncSelectAllCheckbox");
        assertThat(js).contains("slice(0, 8)");
        assertThat(js).contains("chat-session-inline-hash");
        assertThat(js).contains("chat-session-title-label");
        assertThat(js).contains("chat-session-title-text");
        assertThat(js).contains("function loadActiveFiles()");
        assertThat(js).contains("function renderSessionFiles(listing)");
        assertThat(js).contains("/files/download?path=");
        assertThat(js).contains("chat-session-output-row");
        assertThat(js).contains("chat-session-output-badge");
        assertThat(js).contains(" Outputs</span>");
        assertThat(js).doesNotContain("Outputs: ' + outputCount");
        assertThat(js).contains("outputCount");
        assertThat(js).contains("fileLoadConversationId");
        assertThat(js).contains("clearSessionFiles();");
        assertThat(js).doesNotContain("chat-session-hash-chip");
        assertThat(js).contains("selectedSessionIds.clear();");
        assertThat(js).contains("Delete chat");
        assertThat(js).contains("Archive chat");
        assertThat(js).contains("method: 'PATCH'");
        assertThat(js).contains("method: 'DELETE'");
        assertThat(js).contains("pendingMessagesLoadGeneration");
        assertThat(js).contains("loadGeneration !== pendingMessagesLoadGeneration");
        assertThat(js).contains("activeConversationId() !== requestedConversationId");
        assertThat(js).contains("const shouldUpdateConversationUi = function()");
        assertThat(js).contains("return !sendOptions.queuedClaim");
        assertThat(js).contains("if (shouldUpdateConversationUi()) {\n                await loadHistory(completedConversationId);");
        assertThat(js).contains("if (!conversationId) {");
        assertThat(js).contains("renderHistory([]);");
        assertThat(js).contains("data-planning-approval-preview");
        assertThat(js).contains("approvalHtml");
        assertThat(js).contains("Planning mode received.");
        assertThat(js).contains("Approve And Exec");
        assertThat(js).contains("/plan/execute/stream");
        assertThat(js).contains("'Accept': 'text/event-stream, application/json'");
        assertThat(js).doesNotContain("/plan/execute',");
        assertThat(js).doesNotContain("Execution request received.");
        assertThat(js).doesNotContain("Execute now");
        assertThat(js).contains("data-transient-assistant");
        assertThat(js).contains("function clearPlanningPanel()");
        assertThat(js).contains("clearPlanningPanel();");
    }

    @Test
    void chatSessionFragmentRendersOutputCountBadge() {
        ChatService service = new StubChatService() {
            @Override
            public List<ChatSession> listSessions() {
                return List.of(new ChatSession("conversation-1234", "Output Chat", null, false, false, null, null, null, 3));
            }
        };
        FrontendFragmentController controller = new FrontendFragmentController(service);

        String html = controller.chatSessions();

        assertThat(html).contains("chat-session-output-row");
        assertThat(html).contains("chat-session-output-badge");
        assertThat(html).contains("3 Outputs");
        assertThat(html).doesNotContain("Outputs: 3");
    }

    @Test
    void chatStylesUseFullWidthPageAndNormalizedGrid() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/magenta.css"));

        assertThat(css).contains(".mag-page,\n.orch-page {");
        assertThat(css).contains(".chat-page {\n    box-sizing: border-box;\n    width: 100%;\n    max-width: none;");
        assertThat(css).contains("grid-template-columns: minmax(14rem, 25fr) minmax(32rem, 60fr) minmax(14rem, 25fr);");
        assertThat(css).contains(".chat-sessions details {\n    border: 1px solid var(--mg-border);");
        assertThat(css).contains("width: 100%;");
        assertThat(css).contains(".chat-session-output-badge");
        assertThat(css).contains("@media (max-width: 1180px)");
    }

    private static class StubChatService extends ChatService {
        StubChatService() {
            super(null, null, null, null, null);
        }

        @Override
        public String newConversationId() {
            throw new AssertionError("/chat should not allocate a conversation id");
        }

        @Override
        public String defaultModel() {
            return "qwen3.6:35b";
        }

        @Override
        public String planningModel() {
            return "alias-planner";
        }

        @Override
        public List<String> availableModels() {
            return List.of("qwen3.6:35b", "planner-remote");
        }

        @Override
        public List<ModelOption> availableModelOptions() {
            return List.of(
                new ModelOption("alias-qwen", "alias-qwen (qwen3.6:35b)"),
                new ModelOption("alias-planner", "alias-planner (planner-remote)")
            );
        }

        @Override
        public String modelSelectionKey(String model) {
            return "qwen3.6:35b".equals(model) ? "alias-qwen" : model;
        }
    }
}
