package io.mindspice.magenta2.api.web;

import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.simplypages.builders.BannerBuilder;
import io.mindspice.simplypages.builders.ShellBuilder;
import io.mindspice.simplypages.builders.ShellTemplate;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.TextNode;
import io.mindspice.simplypages.components.display.Card;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.Select;
import io.mindspice.simplypages.components.navigation.Link;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import io.mindspice.simplypages.layout.Column;
import io.mindspice.simplypages.layout.Page;
import io.mindspice.simplypages.layout.Row;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

@Controller
public class FrontendController {

    private static final String APP_CSS = "/css/magenta.css?v=5";
    private static final String CHAT_JS = "/js/chat-client.js?v=30";

    private final ChatService chatService;
    private final Component topNavBar;
    private final ShellTemplate pageShell;
    private final ShellTemplate chatShell;

    public FrontendController(ChatService chatService) {
        this.chatService = chatService;
        this.topNavBar = AppNavigation.primaryTopNav();
        this.pageShell = shell("Magenta Portal", "Magenta Portal", "Operational assistant console")
            .buildTemplate();
        this.chatShell = shell("Magenta Chat", "Magenta Chat", "Session-backed assistant workspace")
            .addCustomJs(CHAT_JS)
            .buildTemplate();
    }

    private ShellBuilder shell(String pageTitle, String title, String subtitle) {
        return ShellBuilder.create()
            .withPageTitle(pageTitle)
            .withCustomCss(APP_CSS)
            .withTopBanner(BannerBuilder.create()
                .withLayout(BannerBuilder.BannerLayout.CENTERED)
                .withTitle(title)
                .withSubtitle(subtitle)
                .build())
            .withTopNav(topNavBar);
    }

    @GetMapping("/")
    @ResponseBody
    public String home(
        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
        HttpServletResponse response
    ) {
        return pageShell.renderWithContent(Page.builder()
            .addComponents(new Div().withClass("mag-page mag-home")
                .withChild(pageHeader("Magenta Portal", "Chat, plan, delegate, and monitor operational assistant work."))
                .withChild(new Row()
                    .addColumn(homeCard("Chat", "Continue an assistant conversation with session management.", "/chat"))
                    .addColumn(homeCard("Dashboard", "Full-screen orchestration dashboard for plans, workflows, jobs, and agents.", "/dashboard")))
                .withChild(new Row()
                    .addColumn(homeCard("Plans & Tasks", "Build and run unified plan/task definitions.", "/plans"))
                    .addColumn(homeCard("Workflows", "Compose tasks into ordered workflows with gates and approvals.", "/workflows"))
                    .addColumn(homeCard("Jobs", "Inspect orchestration jobs and run history.", "/jobs")))
                .withChild(new Row()
                    .addColumn(homeCard("Inbox", "User and agent inboxes with approval controls.", "/inbox"))))
            .build());
    }

    @GetMapping("/chat")
    @ResponseBody
    public String chat(
        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
        @RequestParam(value = "conversationId", required = false) String conversationId,
        @RequestParam(value = "startPlanning", required = false) String startPlanning,
        HttpServletResponse response
    ) {
        Component chatContent = new Div()
            .withId("chat-page")
            .withAttribute("data-chat-root", "true")
            .withAttribute("data-chat-surface", "browser")
            .withAttribute("data-active-conversation-id", conversationId == null ? "" : conversationId)
            .withAttribute("data-start-planning", "true".equalsIgnoreCase(startPlanning) ? "true" : "false")
            .withClass("chat-page")
            .withChild(new Div().withClass("chat-layout")
                .withChild(sessionSidebar())
                .withChild(new Div().withClass("chat-main")
                    .withChild(chatToolbar())
                    .withChild(planStatus())
                    .withChild(ChatModuleRenderer.sessionChatModule())
                    .withChild(tokenUsage()))
                .withChild(sessionFilesPanel()))
            .withChild(new Div().withId("chat-error").withAttribute("role", "status").withAttribute("aria-live", "polite"));
        return chatShell.renderWithContent(chatContent);
    }

    private Component sessionSidebar() {
        return new HtmlTag("aside").withClass("chat-sessions")
            .withChild(new HtmlTag("details").withAttribute("open", "open")
                .withChild(new HtmlTag("summary").withChild(new HtmlTag("span").withClass("chat-sessions-label").withInnerText("Sessions")))
                .withChild(new Div().withClass("chat-session-bulk")
                    .withChild(new Div().withClass("chat-session-bulk-actions")
                        .withChild(new HtmlTag("label").withClass("chat-session-select-all").withAttribute("title", "Select all chats")
                            .withChild(new HtmlTag("input", true).withAttribute("type", "checkbox").withId("chat-session-select-all").withAttribute("aria-label", "Select all chats")))
                        .withChild(Button.create("Delete").withAttribute("data-bulk-action", "delete"))
                        .withChild(Button.create("Archive").withAttribute("data-bulk-action", "archive"))
                        .withChild(Button.create("Favorite").withAttribute("data-bulk-action", "favorite"))))
                .withChild(new HtmlTag("ul").withId("chat-session-list")));
    }

    private Component sessionFilesPanel() {
        return new HtmlTag("aside")
            .withId("chat-session-files")
            .withClass("chat-files-panel")
            .withChild(new Div().withClass("chat-files-header")
                .withChild(new HtmlTag("span").withInnerText("Outputs")))
            .withChild(new Div()
                .withId("chat-session-files-body")
                .withClass("chat-files-body")
                .withInnerText("Select a chat to view outputs."));
    }

    private Component chatToolbar() {
        return new Div().withClass("chat-toolbar")
            .withChild(labelInline("Agent Model", modelSelect("chat-model-select", chatService.defaultModel())))
            .withChild(labelInline("Planning Model", modelSelect("chat-planning-model-select", chatService.planningModel())))
            .withChild(new HtmlTag("span").withInnerText("Session"))
            .withChild(new HtmlTag("code").withId("chat-active-session").withInnerText("New chat"));
    }

    private Component planStatus() {
        return new Div().withId("chat-plan-status").withAttribute("aria-live", "polite")
            .withChild(new Div().withClass("chat-plan-header")
                .withChild(new HtmlTag("span").withId("chat-plan-title"))
                .withChild(new HtmlTag("span").withId("chat-plan-hint")))
            .withChild(new HtmlTag("details").withId("chat-plan-document")
                .withChild(new HtmlTag("summary").withInnerText("View plan"))
                .withChild(new Div().withId("chat-plan-document-body")))
            .withChild(new Div().withId("chat-plan-evidence"));
    }

    private Component tokenUsage() {
        return new Div().withId("chat-token-usage").withAttribute("aria-live", "polite")
            .withChild(new Div().withId("chat-token-usage-label")
                .withChild(new HtmlTag("span").withInnerText("Context"))
                .withChild(new HtmlTag("span").withId("chat-token-usage-text").withInnerText("0 / 0 (0%)")))
            .withChild(new Div().withId("chat-token-usage-bar").withChild(new Div().withId("chat-token-usage-fill")));
    }

    private Select modelSelect(String id, String defaultModel) {
        Select select = Select.create(id).withId(id);
        String selectedKey = chatService.modelSelectionKey(defaultModel);
        boolean selected = false;
        for (ChatService.ModelOption model : chatService.availableModelOptions()) {
            boolean isSelected = model.key().equals(selectedKey);
            select.addOption(model.key(), model.key(), isSelected);
            selected = selected || isSelected;
        }
        if (defaultModel != null && !defaultModel.isBlank() && !selected) {
            select.addOption(defaultModel, defaultModel + " (missing)", true);
        }
        return select;
    }

    private Component pageHeader(String title, String subtitle) {
        Div header = new Div().withClass("orch-page-header")
            .withChild(new Div()
                .withChild(Header.H1(title))
                .withChild(new Paragraph(subtitle)));
        return header;
    }

    private Column homeCard(String title, String body, String href) {
        return Column.create().withWidth(4).withChild(Card.create()
            .withClass("home-card")
            .withHeader(title)
            .withBody(new Div()
                .withChild(new Paragraph(body))
                .withChild(new Link(href, "Open"))));
    }

    private Component labelInline(String text, Component input) {
        return new HtmlTag("label").withClass("inline-field").withChild(new TextNode(text)).withChild(input);
    }
}
