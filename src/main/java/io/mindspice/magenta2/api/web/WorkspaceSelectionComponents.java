package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

/** Reusable, HTMX-first execution workspace selection controls. */
final class WorkspaceSelectionComponents {

    static final String SELECTOR_ID = "workspace-selection";
    static final String SELECTOR_LIST_ID = "workspace-selection-list";

    private WorkspaceSelectionComponents() { }

    static Component selectorControl(
        String agentId,
        List<WorkspaceDescriptor> userWorkspaces,
        String selectedType,
        String selectedKey,
        String formContext
    ) {
        return selectorControl(agentId, userWorkspaces, selectedType, selectedKey, formContext, null);
    }

    static Component selectorControl(
        String agentId,
        List<WorkspaceDescriptor> userWorkspaces,
        String selectedType,
        String selectedKey,
        String formContext,
        String error
    ) {
        return selectorControl(agentId, userWorkspaces, selectedType, selectedKey, formContext, error, false);
    }

    static Component selectorControlOob(
        String agentId,
        List<WorkspaceDescriptor> userWorkspaces,
        String formContext
    ) {
        return selectorControl(agentId, userWorkspaces, null, null, formContext, null, true);
    }

    private static Component selectorControl(
        String agentId,
        List<WorkspaceDescriptor> userWorkspaces,
        String selectedType,
        String selectedKey,
        String formContext,
        String error,
        boolean oob
    ) {
        boolean explicitAgent = "agent".equals(selectedType) && agentId.equals(selectedKey);
        boolean explicitUser = "user".equals(selectedType) && selectedKey != null;

        Div root = new Div().withId(SELECTOR_ID).withClass("workspace-selection");
        if (oob) {
            root.withAttribute("hx-swap-oob", "outerHTML");
        }
        root.withChild(new HtmlTag("div").withClass("workspace-selection-current")
            .withChild(new HtmlTag("span").withClass("orch-chip").withInnerText(
                explicitUser ? "Workspace: user/" + selectedKey
                    : explicitAgent ? "Workspace: agent/" + agentId
                    : "Workspace: bound agent (default)")));

        if (explicitAgent || explicitUser) {
            root.withChild(hidden("workspaceType", selectedType));
            root.withChild(hidden("workspaceKey", selectedKey));
        }
        if (error != null && !error.isBlank()) {
            root.withChild(new Div().withClass("workspace-file-status-error").withInnerText(error));
        }

        Div list = new Div().withId(SELECTOR_LIST_ID).withClass("workspace-selection-options");
        list.withChild(option(
            "Bound Agent (default)", agentId,
            selectUrl(agentId, null, null, formContext),
            !explicitAgent && !explicitUser));
        list.withChild(option(
            "Agent Workspace", "agent/" + agentId,
            selectUrl(agentId, "agent", agentId, formContext),
            explicitAgent));

        userWorkspaces.stream()
            .sorted(Comparator.comparing(WorkspaceDescriptor::key))
            .forEach(workspace -> list.withChild(option(
                workspace.key(), "user/" + workspace.key(),
                selectUrl(agentId, "user", workspace.key(), formContext),
                explicitUser && workspace.key().equals(selectedKey))));

        if (!userWorkspaces.isEmpty()) {
            list.withChild(new HtmlTag("hr").withClass("workspace-selection-divider"));
        }
        list.withChild(userCreateForm(agentId, formContext));
        root.withChild(list);
        return root;
    }

    private static Component hidden(String name, String value) {
        return new HtmlTag("input", true)
            .withAttribute("type", "hidden")
            .withAttribute("name", name)
            .withAttribute("value", value);
    }

    private static Component option(String label, String detail, String route, boolean active) {
        HtmlTag button = new HtmlTag("button")
            .withAttribute("type", "button")
            .withClass("workspace-selection-option" + (active ? " active" : ""))
            .withAttribute("hx-get", route)
            .withAttribute("hx-target", "#" + SELECTOR_ID)
            .withAttribute("hx-swap", "outerHTML");
        button.withChild(new HtmlTag("span").withClass("workspace-selection-option-label").withInnerText(label));
        button.withChild(new HtmlTag("code").withInnerText(detail));
        return button;
    }

    private static Component userCreateForm(String agentId, String formContext) {
        String route = "/selectors/workspace/create?agentId=" + url(agentId) + "&context=" + url(formContext);
        Div form = new Div().withClass("workspace-selection-create");
        form.withChild(new HtmlTag("label")
            .withClass("workspace-selection-create-label")
            .withInnerText("New user workspace:"));
        form.withChild(TextInput.create("name")
            .withPlaceholder("workspace-name"));
        form.withChild(Button.create("Create")
            .withAttribute("type", "button")
            .withAttribute("hx-post", route)
            .withAttribute("hx-target", "#" + SELECTOR_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("hx-include", "previous input")
            .withClass("orch-primary small"));
        return form;
    }

    private static String selectUrl(String agentId, String type, String key, String formContext) {
        String route = "/selectors/workspace/select?agentId=" + url(agentId) + "&context=" + url(formContext);
        if (type != null) {
            route += "&type=" + url(type) + "&key=" + url(key);
        }
        return route;
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
