package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.skills.AgentSkill;
import io.mindspice.magenta2.ai.skills.AgentSkillActivationService;
import io.mindspice.magenta2.ai.skills.AgentSkillAssignment;
import io.mindspice.magenta2.ai.skills.AgentSkillAssignmentRepository;
import io.mindspice.magenta2.ai.skills.AgentSkillDiagnostic;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillApiException;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillCatalog;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillFileEntry;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillFileTree;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillFileView;
import io.mindspice.magenta2.ai.skills.AgentSkillStatus;
import io.mindspice.magenta2.ai.skills.CandidateVersion;
import io.mindspice.magenta2.ai.skills.SkillSnapshotRepository;
import io.mindspice.magenta2.ai.skills.SkillTrustService;
import io.mindspice.magenta2.api.web.selector.EntityKind;
import io.mindspice.magenta2.api.web.selector.EntitySelectorComponents;
import io.mindspice.magenta2.api.web.selector.EntitySelectorConfig;
import io.mindspice.simplypages.builders.BannerBuilder;
import io.mindspice.simplypages.builders.ShellBuilder;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.TextArea;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SkillFragments {
    private static final String LIST_ID = "skills-list";
    private static final String DETAIL_ID = "skills-detail";
    private static final String FILE_REGION_ID = "skills-file-region";
    private static final String VIEWER_ID = "skills-file-viewer";
    private static final String ASSIGNMENT_ID = "skills-assignment-panel";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private final AgentSkillManagementService managementService;
    private final AgentProfileService agentProfileService;
    private final EntitySelectorComponents selectorComponents;
    private final SkillTrustService trustService;
    private final SkillSnapshotRepository snapshotRepository;
    private final AgentSkillActivationService activationService;
    private final AgentSkillAssignmentRepository assignmentRepository;

    public SkillFragments(
        AgentSkillManagementService managementService,
        AgentProfileService agentProfileService,
        EntitySelectorComponents selectorComponents,
        SkillTrustService trustService,
        SkillSnapshotRepository snapshotRepository,
        AgentSkillActivationService activationService,
        AgentSkillAssignmentRepository assignmentRepository
    ) {
        this.managementService = managementService;
        this.agentProfileService = agentProfileService;
        this.selectorComponents = selectorComponents;
        this.trustService = trustService;
        this.snapshotRepository = snapshotRepository;
        this.activationService = activationService;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping("/skills")
    @ResponseBody
    public String skillsPage() {
        Component body = new Div()
            .withId("skills-page")
            .withClass("orch-page skills-page")
            .withAttribute("data-orchestration-page", "skills")
            .withChild(Header.H1("Skills"))
            .withChild(new Paragraph("Root skill repository browser, editor, diagnostics, and agent assignments."))
            .withChild(new Div().withClass("browser-layout browser-layout-wide skills-browser-layout")
                .withChild(new Div().withClass("browser-sidebar skills-sidebar")
                    .withChild(new Div().withClass("browser-sidebar-header")
                        .withChild(Header.H2("Catalog"))
                        .withChild(Button.create("Guided Create")
                            .withClass("orch-primary")
                            .withAttribute("hx-get", "/skills/_create")
                            .withAttribute("hx-target", "#" + DETAIL_ID)
                            .withAttribute("hx-swap", "outerHTML")))
                    .withChild(new Div().withClass("skills-sidebar-tools")
                        .withChild(TextInput.search("skillFilter")
                            .withId("skill-filter")
                            .withPlaceholder("Filter skills")
                            .withAttribute("hx-get", "/skills/_list")
                            .withAttribute("hx-trigger", "keyup changed delay:300ms")
                            .withAttribute("hx-target", "#" + LIST_ID)
                            .withAttribute("hx-swap", "outerHTML")
                            .withAttribute("hx-include", "#skill-filter"))
                        .withChild(Button.create("Refresh")
                            .withAttribute("hx-post", "/skills/_refresh")
                            .withAttribute("hx-target", "#" + LIST_ID)
                            .withAttribute("hx-swap", "outerHTML")
                            .withAttribute("hx-include", "#skill-filter")))
                    .withChild(new Div().withId(LIST_ID)
                        .withClass("entity-list skills-list")
                        .hxGet("/skills/_list")
                        .hxTrigger("load")
                        .hxSwap("outerHTML")
                        .withChild(new Paragraph("Loading skills..."))))
                .withChild(new Div().withClass("browser-detail")
                    .withChild(emptyDetail())))
            .withChild(new Div().withId("skills-modal-container"));

        return ShellBuilder.create()
            .withPageTitle("Magenta Skills")
            .withCustomCss(AppNavigation.OPERATIONAL_CSS)
            .withTopBanner(BannerBuilder.create()
                .withLayout(BannerBuilder.BannerLayout.CENTERED)
                .withTitle("Magenta Operations")
                .withSubtitle("Orchestration dashboard")
                .build())
            .withTopNav(AppNavigation.primaryTopNav())
            .withSideNav(AppNavigation.operationalSideNav("/skills"), true)
            .buildTemplate()
            .renderWithContent(body);
    }

    @GetMapping("/skills/_list")
    @ResponseBody
    public String listFragment(@RequestParam(value = "skillFilter", required = false) String filter) {
        return listFragment(filter, false).render();
    }

    @PostMapping("/skills/_refresh")
    @ResponseBody
    public String refreshList(@RequestParam(value = "skillFilter", required = false) String filter) {
        managementService.refreshSkills();
        return listFragment(filter, false).render();
    }

    @GetMapping("/skills/_create")
    @ResponseBody
    public String createForm() {
        return createForm(null, Map.of()).render();
    }

    @PostMapping("/skills/_create")
    @ResponseBody
    public String createSkill(@RequestParam Map<String, String> params) {
        try {
            validateOptionalFileName(params.get("referenceFileName"), params.get("referenceContent"));
            validateOptionalFileName(params.get("scriptFileName"), params.get("scriptContent"));
            validateOptionalFileName(params.get("assetFileName"), params.get("assetContent"));

            AgentSkill created = managementService.createSkill(params.get("skillName"), params.get("description"));
            String skillName = key(created);
            managementService.saveText(skillName, "SKILL.md", guidedSkillMarkdown(params));
            createOptionalDirectory(skillName, "references", params.containsKey("createReferences"));
            createOptionalDirectory(skillName, "scripts", params.containsKey("createScripts"));
            createOptionalDirectory(skillName, "assets", params.containsKey("createAssets"));
            createOptionalTextFile(skillName, "references", params.get("referenceFileName"), params.get("referenceContent"));
            createOptionalTextFile(skillName, "scripts", params.get("scriptFileName"), params.get("scriptContent"));
            createOptionalTextFile(skillName, "assets", params.get("assetFileName"), params.get("assetContent"));
            managementService.refreshSkills();
            return renderDetail(skillName, "SKILL.md")
                + listFragment(null, true).render();
        } catch (RuntimeException exception) {
            return createForm(errorMessage(exception), params).render();
        }
    }

    @GetMapping("/skills/_detail/{skillName}")
    @ResponseBody
    public String detailFragment(
        @PathVariable String skillName,
        @RequestParam(value = "selectedPath", required = false) String selectedPath
    ) {
        try {
            return renderDetail(skillName, selectedPath);
        } catch (RuntimeException exception) {
            return errorDetail(errorMessage(exception)).render();
        }
    }

    @PostMapping("/skills/_detail/{skillName}/refresh")
    @ResponseBody
    public String refreshDetail(
        @PathVariable String skillName,
        @RequestParam(value = "selectedPath", required = false) String selectedPath,
        @RequestParam(value = "skillFilter", required = false) String filter
    ) {
        try {
            managementService.refreshSkills();
            return renderDetail(skillName, selectedPath)
                + listFragment(filter, true).render();
        } catch (RuntimeException exception) {
            return errorDetail(errorMessage(exception)).render();
        }
    }

    @GetMapping("/skills/_files/{skillName}")
    @ResponseBody
    public String filesFragment(
        @PathVariable String skillName,
        @RequestParam(defaultValue = ".") String path,
        @RequestParam(value = "selectedPath", required = false) String selectedPath
    ) {
        try {
            return fileRegion(skillName, path, selectedPath).render();
        } catch (RuntimeException exception) {
            return fileRegionError(errorMessage(exception)).render();
        }
    }

    @GetMapping("/skills/_viewer/{skillName}")
    @ResponseBody
    public String viewerFragment(
        @PathVariable String skillName,
        @RequestParam String path
    ) {
        try {
            return fileViewer(skillName, path).render();
        } catch (RuntimeException exception) {
            return viewerError(errorMessage(exception)).render();
        }
    }

    @PutMapping("/skills/_files/{skillName}/text")
    @ResponseBody
    public String saveTextFile(
        @PathVariable String skillName,
        @RequestParam String path,
        @RequestParam(defaultValue = "") String content,
        @RequestParam(value = "skillFilter", required = false) String filter
    ) {
        try {
            managementService.saveText(skillName, path, content);
            return renderDetail(skillName, path)
                + skillListRefresh(path, filter);
        } catch (RuntimeException exception) {
            return detailWithMessage(skillName, path, errorMessage(exception), true);
        }
    }

    @PostMapping("/skills/_files/{skillName}")
    @ResponseBody
    public String createTextFile(
        @PathVariable String skillName,
        @RequestParam(defaultValue = ".") String parentPath,
        @RequestParam String fileName,
        @RequestParam(defaultValue = "") String content
    ) {
        try {
            SkillFileEntry created = managementService.createTextFile(skillName, parentPath, fileName, content);
            return renderDetail(skillName, created.path());
        } catch (RuntimeException exception) {
            return detailWithMessage(skillName, parentPath, errorMessage(exception), true);
        }
    }

    @PostMapping("/skills/_directories/{skillName}")
    @ResponseBody
    public String createDirectory(
        @PathVariable String skillName,
        @RequestParam String directoryName
    ) {
        try {
            managementService.createOptionalDirectory(skillName, directoryName);
            return renderDetail(skillName, directoryName);
        } catch (RuntimeException exception) {
            return detailWithMessage(skillName, "SKILL.md", errorMessage(exception), true);
        }
    }

    @PostMapping("/skills/_assignments/{skillName}")
    @ResponseBody
    public String assignAgent(
        @PathVariable String skillName,
        @RequestParam String agentId,
        @RequestParam(value = "skillFilter", required = false) String filter
    ) {
        try {
            managementService.assignToAgent(skillName, agentId, true);
            return assignmentPanel(skillName, "Assigned " + agentLabel(agentId) + ".", false).render()
                + listFragment(filter, true).render();
        } catch (RuntimeException exception) {
            return assignmentPanel(skillName, errorMessage(exception), true).render();
        }
    }

    @DeleteMapping("/skills/_assignments/{skillName}/{agentId}")
    @ResponseBody
    public String unassignAgent(
        @PathVariable String skillName,
        @PathVariable String agentId,
        @RequestParam(value = "skillFilter", required = false) String filter
    ) {
        try {
            managementService.unassignFromAgent(skillName, agentId);
            return assignmentPanel(skillName, "Unassigned " + agentLabel(agentId) + ".", false).render()
                + listFragment(filter, true).render();
        } catch (RuntimeException exception) {
            return assignmentPanel(skillName, errorMessage(exception), true).render();
        }
    }

    private Component listFragment(String filter, boolean oob) {
        SkillCatalog catalog = managementService.listSkills();
        List<AgentSkill> skills = catalog.skills();
        if (StringUtils.hasText(filter)) {
            String needle = filter.trim().toLowerCase(Locale.ROOT);
            skills = skills.stream()
                .filter(skill -> contains(skill.name(), needle)
                    || contains(skill.directorySlug(), needle)
                    || contains(skill.description(), needle)
                    || contains(skill.status() == null ? null : skill.status().name(), needle))
                .toList();
        }

        Div list = new Div().withId(LIST_ID).withClass("entity-list skills-list");
        if (oob) {
            list.withAttribute("hx-swap-oob", "true");
        }
        list.withChild(new Div().withClass("skills-count-strip")
            .withChild(statusPill("valid", String.valueOf(catalog.validCount())))
            .withChild(statusPill("warning", String.valueOf(catalog.warningCount())))
            .withChild(statusPill("invalid", String.valueOf(catalog.invalidCount()))));

        if (skills.isEmpty()) {
            list.withChild(new Div().withClass("tool-item").withInnerText("No skills match."));
            return list;
        }
        for (AgentSkill skill : skills) {
            list.withChild(skillListRow(skill));
        }
        return list;
    }

    private Component skillListRow(AgentSkill skill) {
        String skillKey = key(skill);
        int assignmentCount = assignmentCount(skillKey);
        HtmlTag open = new HtmlTag("button")
            .withAttribute("type", "button")
            .withClass("tool-item skill-list-open")
            .withAttribute("hx-get", "/skills/_detail/" + urlPath(skillKey))
            .withAttribute("hx-target", "#" + DETAIL_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withChild(new Div().withClass("skill-row-header")
                .withChild(new HtmlTag("strong").withClass("skill-row-title").withInnerText(displayName(skill)))
                .withChild(statusBadge(skill.status())))
            .withChild(new HtmlTag("span").withClass("skill-row-summary")
                .withInnerText(first(skill.description(), "No description")))
            .withChild(new Div().withClass("skill-row-meta")
                .withChild(new HtmlTag("span").withInnerText(assignmentCount + " assigned"))
                .withChild(new HtmlTag("span").withInnerText(skill.diagnostics().size() + " diagnostics"))
                .withChild(new HtmlTag("span").withInnerText(skill.directorySlug())));
        return new Div().withClass("skill-list-card").withChild(open);
    }

    private String renderDetail(String skillName, String selectedPath) {
        AgentSkill skill = managementService.getSkill(skillName);
        String path = StringUtils.hasText(selectedPath) ? selectedPath : "SKILL.md";
        Div detail = new Div().withId(DETAIL_ID).withClass("orch-panel skills-detail")
            .withChild(new Div().withClass("skill-detail-header")
                .withChild(new Div()
                    .withChild(Header.H2(displayName(skill)))
                    .withChild(new Paragraph(first(skill.description(), "No description"))))
                .withChild(new Div().withClass("skill-detail-actions")
                    .withChild(statusBadge(skill.status()))
                    .withChild(Button.create("Refresh")
                        .withAttribute("hx-post", "/skills/_detail/" + urlPath(key(skill)) + "/refresh?selectedPath=" + url(path))
                        .withAttribute("hx-target", "#" + DETAIL_ID)
                        .withAttribute("hx-swap", "outerHTML")
                        .withAttribute("hx-include", "#skill-filter"))))
            .withChild(new Div().withClass("skill-summary-grid")
                .withChild(summaryItem("Directory", skill.directorySlug()))
                .withChild(summaryItem("License", first(skill.license(), "none")))
                .withChild(summaryItem("Compatibility", first(skill.compatibility(), "none")))
                .withChild(summaryItem("Allowed tools", first(skill.allowedTools(), "not enforced"))))
            .withChild(directoryOverview(skill))
            .withChild(diagnosticsPanel(skill.diagnostics()))
            .withChild(new Div().withClass("skill-detail-grid")
                .withChild(new Div().withClass("skill-main-column")
                    .withChild(fileRegion(key(skill), directoryOf(path), path))
                    .withChild(assignmentPanel(key(skill), null, false)))
                .withChild(new Div().withClass("skill-side-column")
                    .withChild(fileViewer(key(skill), path))));
        return detail.render();
    }

    private String detailWithMessage(String skillName, String selectedPath, String message, boolean error) {
        try {
            return renderDetail(skillName, selectedPath)
                + statusOob(message, error).render();
        } catch (RuntimeException exception) {
            return errorDetail(message + ": " + errorMessage(exception)).render();
        }
    }

    private Component directoryOverview(AgentSkill skill) {
        Div overview = new Div().withClass("skill-directory-overview");
        overview.withChild(directoryChip(key(skill), "scripts", skill.hasScripts(), "Executable resources; Magenta does not run them from this UI."));
        overview.withChild(directoryChip(key(skill), "references", skill.hasReferences(), "Reference files are opened only when selected."));
        overview.withChild(directoryChip(key(skill), "assets", skill.hasAssets(), "Static resources and templates."));
        return overview;
    }

    private Component directoryChip(String skillName, String directory, boolean present, String detail) {
        Div chip = new Div().withClass("skill-dir-chip " + (present ? "present" : "absent"))
            .withChild(new HtmlTag("strong").withInnerText(directory + "/"))
            .withChild(new HtmlTag("span").withInnerText(present ? "present" : "absent"))
            .withChild(new HtmlTag("small").withInnerText(detail));
        if (!present) {
            chip.withChild(Form.create().withClass("skill-dir-create-form")
                .withHxPost("/skills/_directories/" + urlPath(skillName))
                .withHxTarget("#" + DETAIL_ID)
                .withHxSwap("outerHTML")
                .withChild(hidden("directoryName", directory))
                .withChild(Button.submit("Create " + directory + "/")));
        }
        return chip;
    }

    private Component diagnosticsPanel(List<AgentSkillDiagnostic> diagnostics) {
        Div panel = new Div().withClass("skill-diagnostics-panel");
        panel.withChild(Header.H3("Diagnostics"));
        if (diagnostics.isEmpty()) {
            panel.withChild(new Paragraph("No diagnostics."));
            return panel;
        }
        Div list = new Div().withClass("skill-diagnostics-list");
        for (AgentSkillDiagnostic diagnostic : diagnostics) {
            list.withChild(new Div().withClass("skill-diagnostic " + diagnostic.severity().name().toLowerCase(Locale.ROOT))
                .withChild(new HtmlTag("strong").withInnerText(diagnostic.severity().name() + " " + diagnostic.code().name()))
                .withChild(new HtmlTag("span").withInnerText(diagnostic.message()))
                .withChild(new HtmlTag("small").withInnerText(first(diagnostic.sourcePath(), "SKILL.md"))));
        }
        panel.withChild(list);
        return panel;
    }

    private Component fileRegion(String skillName, String path, String selectedPath) {
        SkillFileTree tree = managementService.listFiles(skillName, path);
        Div panel = new Div().withId(FILE_REGION_ID).withClass("skill-file-region")
            .withChild(new Div().withClass("skill-section-header")
                .withChild(Header.H3("Files"))
                .withChild(new HtmlTag("code").withInnerText(tree.path())));
        panel.withChild(fileToolbar(skillName, tree.path(), selectedPath));
        panel.withChild(fileTable(skillName, tree, selectedPath));
        panel.withChild(addFileForm(skillName, tree.path()));
        return panel;
    }

    private Component fileToolbar(String skillName, String path, String selectedPath) {
        Div toolbar = new Div().withClass("skill-file-toolbar");
        if (!".".equals(path)) {
            toolbar.withChild(Button.create("Up")
                .withAttribute("hx-get", "/skills/_files/" + urlPath(skillName)
                    + "?path=" + url(parentPath(path)) + selectedQuery(selectedPath))
                .withAttribute("hx-target", "#" + FILE_REGION_ID)
                .withAttribute("hx-swap", "outerHTML"));
        }
        toolbar.withChild(Button.create("Refresh Files")
            .withAttribute("hx-get", "/skills/_files/" + urlPath(skillName)
                + "?path=" + url(path) + selectedQuery(selectedPath))
            .withAttribute("hx-target", "#" + FILE_REGION_ID)
            .withAttribute("hx-swap", "outerHTML"));
        return toolbar;
    }

    private Component fileTable(String skillName, SkillFileTree tree, String selectedPath) {
        HtmlTag table = new HtmlTag("table").withClass("skill-file-table");
        table.withChild(new HtmlTag("thead").withChild(new HtmlTag("tr")
            .withChild(new HtmlTag("th").withInnerText("Name"))
            .withChild(new HtmlTag("th").withInnerText("Type"))
            .withChild(new HtmlTag("th").withInnerText("Size"))
            .withChild(new HtmlTag("th").withInnerText("Modified"))
            .withChild(new HtmlTag("th").withInnerText("Actions"))));
        HtmlTag body = new HtmlTag("tbody");
        if (tree.entries().isEmpty()) {
            body.withChild(new HtmlTag("tr").withChild(new HtmlTag("td")
                .withAttribute("colspan", "5")
                .withInnerText("No files in this directory.")));
        }
        for (SkillFileEntry entry : tree.entries()) {
            body.withChild(fileRow(skillName, entry, selectedPath));
        }
        table.withChild(body);
        return table;
    }

    private Component fileRow(String skillName, SkillFileEntry entry, String selectedPath) {
        String selected = entry.path().equals(selectedPath) ? " selected" : "";
        HtmlTag nameAction = new HtmlTag("button")
            .withAttribute("type", "button")
            .withClass("skill-file-name-button")
            .withAttribute("hx-get", entry.directory()
                ? "/skills/_files/" + urlPath(skillName) + "?path=" + url(entry.path()) + selectedQuery(selectedPath)
                : "/skills/_viewer/" + urlPath(skillName) + "?path=" + url(entry.path()))
            .withAttribute("hx-target", entry.directory() ? "#" + FILE_REGION_ID : "#" + VIEWER_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withInnerText(entry.name());
        HtmlTag row = new HtmlTag("tr").withClass("skill-file-row" + selected)
            .withChild(new HtmlTag("td").withClass("skill-file-name").withChild(nameAction))
            .withChild(new HtmlTag("td").withInnerText(entry.directory() ? "Directory" : "Text"))
            .withChild(new HtmlTag("td").withInnerText(entry.directory() ? "-" : sizeLabel(entry.size())))
            .withChild(new HtmlTag("td").withInnerText(entry.modifiedAt() == null ? "unknown" : TIME_FORMAT.format(entry.modifiedAt())));
        if (!entry.directory()) {
            row.withChild(new HtmlTag("td").withClass("skill-file-actions")
                .withChild(Button.create("View")
                    .withAttribute("hx-get", "/skills/_viewer/" + urlPath(skillName) + "?path=" + url(entry.path()))
                    .withAttribute("hx-target", "#" + VIEWER_ID)
                    .withAttribute("hx-swap", "outerHTML")));
        } else {
            row.withChild(new HtmlTag("td").withClass("skill-file-actions")
                .withChild(Button.create("Open")
                    .withAttribute("hx-get", "/skills/_files/" + urlPath(skillName) + "?path=" + url(entry.path()))
                    .withAttribute("hx-target", "#" + FILE_REGION_ID)
                    .withAttribute("hx-swap", "outerHTML")));
        }
        return row;
    }

    private Component addFileForm(String skillName, String parentPath) {
        Form form = Form.create().withClass("skill-add-file-form")
            .withHxPost("/skills/_files/" + urlPath(skillName))
            .withHxTarget("#" + DETAIL_ID)
            .withHxSwap("outerHTML")
            .withChild(hidden("parentPath", parentPath))
            .withChild(label("File name", TextInput.create("fileName")
                .withPlaceholder("notes.md")
                .required()))
            .withChild(label("Content", TextArea.create("content")
                .withRows(4)
                .withPlaceholder("Text content")))
            .withChild(Button.submit("Add File"));
        return new Div().withClass("skill-add-file-panel")
            .withChild(Header.H3("Add File"))
            .withChild(form);
    }

    private Component fileViewer(String skillName, String path) {
        SkillFileView view = managementService.viewFile(skillName, path);
        Div viewer = new Div().withId(VIEWER_ID).withClass("skill-viewer-panel")
            .withChild(new Div().withClass("skill-section-header")
                .withChild(Header.H3(view.path()))
                .withChild(new HtmlTag("span").withClass("skill-file-kind").withInnerText(view.kind() + " - " + sizeLabel(view.size()))));
        viewer.withChild(statusHost());
        if (!view.text()) {
            viewer.withChild(new Paragraph("This file cannot be edited as UTF-8 text."));
            return viewer;
        }
        if (view.warning()) {
            viewer.withChild(new Paragraph("This file is large. Inline content preview is omitted."));
            return viewer;
        }
        Form form = Form.create().withClass("skill-editor-form")
            .withHxPut("/skills/_files/" + urlPath(skillName) + "/text?path=" + url(view.path()))
            .withHxTarget("#" + DETAIL_ID)
            .withHxSwap("outerHTML")
            .withChild(TextArea.create("content")
                .withRows("SKILL.md".equals(view.path()) ? 22 : 14)
                .withClass("skill-editor-textarea")
                .withValue(view.content() == null ? "" : view.content()))
            .withChild(new Div().withClass("skill-editor-actions")
                .withChild(Button.submit("Save").withClass("orch-primary"))
                .withChild(Button.create("Refresh File")
                    .withAttribute("hx-get", "/skills/_viewer/" + urlPath(skillName) + "?path=" + url(view.path()))
                    .withAttribute("hx-target", "#" + VIEWER_ID)
                    .withAttribute("hx-swap", "outerHTML")));
        form.withAttribute("hx-include", "#skill-filter");
        viewer.withChild(form);
        return viewer;
    }

    private Component assignmentPanel(String skillName, String message, boolean error) {
        Div panel = new Div().withId(ASSIGNMENT_ID).withClass("skill-assignment-panel")
            .withChild(new Div().withClass("skill-section-header")
                .withChild(Header.H3("Agent Assignments")));
        List<AgentSkillAssignment> assignments = managementService.listAgentAssignments(skillName);
        if (assignments.isEmpty()) {
            panel.withChild(new Paragraph("No agents assigned."));
        } else {
            Div list = new Div().withClass("skill-assignment-list");
            for (AgentSkillAssignment assignment : assignments) {
                list.withChild(new Div().withClass("skill-assignment-row")
                    .withChild(new Div()
                        .withChild(new HtmlTag("strong").withInnerText(agentLabel(assignment.targetId())))
                        .withChild(new HtmlTag("span").withInnerText(assignment.enabled() ? "enabled" : "disabled")))
                    .withChild(Button.create("Unassign")
                        .withAttribute("hx-delete", "/skills/_assignments/" + urlPath(skillName) + "/" + urlPath(assignment.targetId()))
                        .withAttribute("hx-target", "#" + ASSIGNMENT_ID)
                        .withAttribute("hx-swap", "outerHTML")
                        .withAttribute("hx-include", "#skill-filter")));
            }
            panel.withChild(list);
        }

        Form form = Form.create().withClass("skill-assignment-form")
            .withHxPost("/skills/_assignments/" + urlPath(skillName))
            .withHxTarget("#" + ASSIGNMENT_ID)
            .withHxSwap("outerHTML")
            .withChild(selectorComponents.selector(new EntitySelectorConfig(
                "agentId",
                EntityKind.AGENT,
                "",
                "Agent",
                "Search agents",
                true,
                Map.of()
            ), null))
            .withChild(Button.submit("Assign").withClass("orch-primary"));
        form.withAttribute("hx-include", "#skill-filter");
        panel.withChild(form);
        if (StringUtils.hasText(message)) {
            panel.withChild(status(message, error));
        }
        return panel;
    }

    private Component createForm(String message, Map<String, String> values) {
        Form form = Form.create().withClass("skill-guide-form")
            .withHxPost("/skills/_create")
            .withHxTarget("#" + DETAIL_ID)
            .withHxSwap("outerHTML")
            .withChild(label("Skill name", TextInput.create("skillName")
                .withPlaceholder("data-cleanup")
                .withValue(value(values, "skillName"))
                .withPattern("[a-z0-9]+(-[a-z0-9]+)*")
                .withMaxLength(64)
                .required()))
            .withChild(label("When to use", TextArea.create("description")
                .withRows(4)
                .withMaxLength(1024)
                .withPlaceholder("Use when...")
                .withValue(value(values, "description"))
                .required()))
            .withChild(label("Workflow instructions", TextArea.create("instructions")
                .withRows(10)
                .withPlaceholder("1. Inspect the input...\n2. Apply the workflow...")
                .withValue(value(values, "instructions"))
                .required()))
            .withChild(optionalDirectoryField("createReferences", "references/", values))
            .withChild(optionalTextFileFields("referenceFileName", "referenceContent", "Reference file", "REFERENCE.md", values))
            .withChild(optionalDirectoryField("createScripts", "scripts/", values))
            .withChild(optionalTextFileFields("scriptFileName", "scriptContent", "Script note file", "README.md", values))
            .withChild(optionalDirectoryField("createAssets", "assets/", values))
            .withChild(optionalTextFileFields("assetFileName", "assetContent", "Asset note file", "README.md", values))
            .withChild(Button.submit("Create Skill").withClass("orch-primary"));
        Div panel = new Div().withId(DETAIL_ID).withClass("orch-panel skills-detail skill-guide-panel")
            .withChild(new Div().withClass("skill-detail-header")
                .withChild(new Div()
                    .withChild(Header.H2("Guided Skill Creation"))
                    .withChild(new Paragraph("Create a valid root-repository skill scaffold."))))
            .withChild(form);
        if (StringUtils.hasText(message)) {
            panel.withChild(status(message, true));
        }
        return panel;
    }

    private Component optionalDirectoryField(String name, String label, Map<String, String> values) {
        HtmlTag checkbox = new HtmlTag("input", true)
            .withAttribute("type", "checkbox")
            .withAttribute("name", name)
            .withAttribute("value", "true");
        if (values.containsKey(name)) {
            checkbox.withAttribute("checked", "checked");
        }
        return new HtmlTag("label").withClass("skill-checkbox-row")
            .withChild(checkbox)
            .withChild(new HtmlTag("span").withInnerText("Create " + label));
    }

    private Component optionalTextFileFields(
        String nameField,
        String contentField,
        String label,
        String placeholderName,
        Map<String, String> values
    ) {
        return new Div().withClass("skill-guide-subgrid")
            .withChild(label(label + " name", TextInput.create(nameField)
                .withPlaceholder(placeholderName)
                .withValue(value(values, nameField))))
            .withChild(label(label + " content", TextArea.create(contentField)
                .withRows(4)
                .withPlaceholder("Optional starter content")
                .withValue(value(values, contentField))));
    }

    private Component emptyDetail() {
        return new Div().withId(DETAIL_ID).withClass("orch-panel empty-detail")
            .withChild(new Paragraph("Select a skill or create a new scaffold."));
    }

    private Component errorDetail(String message) {
        return new Div().withId(DETAIL_ID).withClass("orch-panel empty-detail")
            .withChild(status(message, true));
    }

    private Component fileRegionError(String message) {
        return new Div().withId(FILE_REGION_ID).withClass("skill-file-region")
            .withChild(status(message, true));
    }

    private Component viewerError(String message) {
        return new Div().withId(VIEWER_ID).withClass("skill-viewer-panel")
            .withChild(status(message, true));
    }

    private Component statusHost() {
        return new Div().withId("skill-editor-status").withClass("skill-editor-status");
    }

    private Component statusOob(String message, boolean error) {
        Div status = new Div().withId("skill-editor-status").withClass("skill-editor-status");
        status.withAttribute("hx-swap-oob", "true");
        status.withChild(status(message, error));
        return status;
    }

    private String skillListRefresh(String path, String filter) {
        return "SKILL.md".equals(path) ? listFragment(filter, true).render() : "";
    }

    private Component status(String message, boolean error) {
        return new Div().withClass(error ? "orch-error" : "orch-status").withInnerText(message);
    }

    private Component statusPill(String label, String value) {
        return new HtmlTag("span").withClass("skill-count-pill " + label).withInnerText(label + " " + value);
    }

    private Component statusBadge(AgentSkillStatus status) {
        String label = status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT);
        String style = switch (status == null ? AgentSkillStatus.INVALID : status) {
            case VALID -> "is-approved";
            case WARNING -> "is-draft";
            case INVALID -> "is-neutral";
        };
        return new HtmlTag("span")
            .withClass("plan-status-badge skill-status-badge " + style)
            .withInnerText(label);
    }

    private Component summaryItem(String label, String value) {
        return new Div().withClass("skill-summary-item")
            .withChild(new HtmlTag("span").withInnerText(label))
            .withChild(new HtmlTag("strong").withInnerText(value));
    }

    private Component label(String label, Component input) {
        return new HtmlTag("label").withChild(new HtmlTag("span").withInnerText(label)).withChild(input);
    }

    private Component hidden(String name, String value) {
        return new HtmlTag("input", true)
            .withAttribute("type", "hidden")
            .withAttribute("name", name)
            .withAttribute("value", value == null ? "" : value);
    }

    private void createOptionalDirectory(String skillName, String directoryName, boolean requested) {
        if (requested) {
            managementService.createOptionalDirectory(skillName, directoryName);
        }
    }

    private void createOptionalTextFile(String skillName, String parentPath, String fileName, String content) {
        if (!StringUtils.hasText(fileName) && !StringUtils.hasText(content)) {
            return;
        }
        if (!StringUtils.hasText(fileName)) {
            throw new SkillApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "optional file name is required when content is provided");
        }
        managementService.createOptionalDirectory(skillName, parentPath);
        managementService.createTextFile(skillName, parentPath, fileName, content == null ? "" : content);
    }

    private void validateOptionalFileName(String fileName, String content) {
        if (!StringUtils.hasText(fileName) && !StringUtils.hasText(content)) {
            return;
        }
        if (!StringUtils.hasText(fileName)) {
            throw new SkillApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "optional file name is required when content is provided");
        }
        String clean = fileName.trim();
        if (clean.contains("/") || clean.contains("\\") || ".".equals(clean) || "..".equals(clean)) {
            throw new SkillApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "optional file names must be plain file names");
        }
    }

    private String guidedSkillMarkdown(Map<String, String> params) {
        String skillName = params.get("skillName").trim();
        String description = params.get("description").trim();
        String instructions = params.get("instructions") == null ? "" : params.get("instructions").trim();
        return """
            ---
            name: %s
            description: %s
            ---
            # %s

            ## When To Use
            %s

            ## Workflow
            %s
            """.formatted(skillName, yamlQuoted(description), skillName, description, instructions);
    }

    private int assignmentCount(String skillName) {
        try {
            return managementService.listAgentAssignments(skillName).size();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String agentLabel(String agentId) {
        return agentProfileService.list().stream()
            .filter(agent -> agent.id().equals(agentId))
            .findFirst()
            .map(AgentProfile::name)
            .filter(StringUtils::hasText)
            .orElse(agentId);
    }

    private String key(AgentSkill skill) {
        return StringUtils.hasText(skill.name()) ? skill.name() : skill.directorySlug();
    }

    private String displayName(AgentSkill skill) {
        return first(skill.name(), skill.directorySlug());
    }

    private String first(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String value(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String directoryOf(String path) {
        if (!StringUtils.hasText(path) || ".".equals(path) || !path.contains("/")) {
            return ".";
        }
        return path.substring(0, path.lastIndexOf('/'));
    }

    private String parentPath(String path) {
        if (!StringUtils.hasText(path) || ".".equals(path) || !path.contains("/")) {
            return ".";
        }
        return path.substring(0, path.lastIndexOf('/'));
    }

    private String selectedQuery(String selectedPath) {
        return StringUtils.hasText(selectedPath) ? "&selectedPath=" + url(selectedPath) : "";
    }

    private String sizeLabel(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
    }

    private String errorMessage(Throwable exception) {
        return exception.getMessage() == null ? "request failed" : exception.getMessage();
    }

    private String yamlQuoted(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private String urlPath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 07: Trust / Approval / Snapshot fragments
    // ════════════════════════════════════════════════════════════════

    private static final String TRUST_PANEL_ID = "skills-trust-panel";

    @GetMapping("/skills/_trust/{logicalKey}")
    @ResponseBody
    public String trustPanel(@PathVariable String logicalKey) {
        Div panel = new Div().withId(TRUST_PANEL_ID).withClass("orch-panel skills-trust-panel")
            .withChild(new Div().withClass("skill-section-header")
                .withChild(Header.H3("Trust & Approval \u2014 " + logicalKey)));

        List<CandidateVersion> candidates = assignmentRepository.findCandidatesByLogicalKey(logicalKey);
        if (candidates.isEmpty()) {
            panel.withChild(new Paragraph("No candidates found for this skill key."));
            return panel.render();
        }

        boolean isApproved = snapshotRepository.isCurrentlyApproved(logicalKey);
        boolean isRevoked = snapshotRepository.isRevoked(logicalKey);
        String trustStatus = isRevoked ? "REVOKED" : isApproved ? "APPROVED" : "PENDING";
        String trustClass = isRevoked ? "is-neutral" : isApproved ? "is-approved" : "is-draft";

        panel.withChild(new Div().withClass("skill-trust-status")
            .withChild(new HtmlTag("span").withClass("plan-status-badge " + trustClass)
                .withInnerText(trustStatus))
            .withChild(new HtmlTag("span").withInnerText(
                isRevoked ? "Revoked \u2014 no new activations allowed."
                    : isApproved ? "Approved \u2014 activation available."
                    : "Pending \u2014 requires operator approval.")));

        Div candidateList = new Div().withClass("skill-candidate-list");
        for (CandidateVersion cv : candidates) {
            boolean hasSnapshot = snapshotRepository.snapshotExistsForCandidate(cv.id());
            var decisions = snapshotRepository.findDecisionsByCandidate(cv.id());

            Div row = new Div().withClass("skill-candidate-row")
                .withChild(new HtmlTag("code").withInnerText(cv.contentDigest().substring(0, 16) + "..."))
                .withChild(new HtmlTag("span").withInnerText(cv.scopeType().name() + " / " + cv.provenance().name()))
                .withChild(new HtmlTag("small").withInnerText(hasSnapshot ? "Snapshot exists" : "No snapshot"));

            if (!hasSnapshot && !isRevoked) {
                Form approveForm = Form.create().withClass("skill-inline-form")
                    .withHxPost("/skills/_trust/approve")
                    .withHxTarget("#" + TRUST_PANEL_ID)
                    .withHxSwap("outerHTML")
                    .withChild(hidden("candidateVersionId", String.valueOf(cv.id())))
                    .withChild(hidden("observedDigest", cv.contentDigest()))
                    .withChild(hidden("logicalKey", logicalKey))
                    .withChild(Button.submit("Approve").withClass("orch-primary"));
                row.withChild(approveForm);

                Form rejectForm = Form.create().withClass("skill-inline-form")
                    .withHxPost("/skills/_trust/reject")
                    .withHxTarget("#" + TRUST_PANEL_ID)
                    .withHxSwap("outerHTML")
                    .withChild(hidden("candidateVersionId", String.valueOf(cv.id())))
                    .withChild(hidden("logicalKey", logicalKey))
                    .withChild(Button.submit("Reject").withClass("orch-secondary"));
                row.withChild(rejectForm);
            }

            if (!decisions.isEmpty()) {
                Div history = new Div().withClass("skill-decision-history");
                for (var d : decisions) {
                    history.withChild(new HtmlTag("span")
                        .withClass("skill-decision-item " + d.decision().toLowerCase(Locale.ROOT))
                        .withInnerText(d.decision() + " by " + (d.actor() != null ? d.actor() : "system")
                            + " at " + (d.decidedAt() != null ? TIME_FORMAT.format(d.decidedAt()) : "unknown")));
                }
                row.withChild(history);
            }

            candidateList.withChild(row);
        }
        panel.withChild(candidateList);

        if (isApproved && !isRevoked) {
            Form revokeForm = Form.create().withClass("skill-revoke-form")
                .withHxPost("/skills/_trust/revoke")
                .withHxTarget("#" + TRUST_PANEL_ID)
                .withHxSwap("outerHTML")
                .withChild(hidden("logicalKey", logicalKey))
                .withChild(TextInput.create("reason").withPlaceholder("Reason for revocation"))
                .withChild(Button.submit("Revoke Approval").withClass("orch-danger"));
            panel.withChild(revokeForm);
        }

        var snapshots = snapshotRepository.findByLogicalKey(logicalKey);
        if (!snapshots.isEmpty()) {
            Div snapshotPanel = new Div().withClass("skill-snapshot-panel")
                .withChild(Header.H3("Immutable Snapshots"));
            for (var s : snapshots) {
                Div snapRow = new Div().withClass("skill-snapshot-row")
                    .withChild(new HtmlTag("code").withInnerText(s.snapshotDigest().substring(0, 16) + "..."))
                    .withChild(new HtmlTag("span").withInnerText(s.skillName()))
                    .withChild(new HtmlTag("small").withInnerText(
                        s.createdAt() != null ? "Created " + TIME_FORMAT.format(s.createdAt()) : ""));

                var resources = snapshotRepository.findResourcesBySnapshotId(s.id());
                snapRow.withChild(new HtmlTag("span").withInnerText(resources.size() + " resources"));

                boolean hasScript = resources.stream().anyMatch(r -> "SCRIPT".equals(r.resourceType()));
                if (hasScript) {
                    snapRow.withChild(new HtmlTag("span").withClass("skill-exec-status")
                        .withInnerText("Executables: Phase 03 confinement required"));
                }

                snapshotPanel.withChild(snapRow);
            }
            panel.withChild(snapshotPanel);
        }

        return panel.render();
    }

    @PostMapping("/skills/_trust/approve")
    @ResponseBody
    public String approveCandidate(@RequestParam Map<String, String> params) {
        try {
            Long cvId = Long.parseLong(params.get("candidateVersionId"));
            String observedDigest = params.get("observedDigest");
            String logicalKey = params.get("logicalKey");
            String operationKey = UUID.randomUUID().toString();

            var result = trustService.approve(operationKey, cvId, observedDigest,
                "operator", params.get("reason"));

            return trustPanel(logicalKey) + statusOob(
                "Approved snapshot " + result.snapshotId(), false).render();
        } catch (Exception e) {
            return trustPanel(params.get("logicalKey")) + statusOob(
                "Approval failed: " + e.getMessage(), true).render();
        }
    }

    @PostMapping("/skills/_trust/reject")
    @ResponseBody
    public String rejectCandidate(@RequestParam Map<String, String> params) {
        try {
            Long cvId = Long.parseLong(params.get("candidateVersionId"));
            String logicalKey = params.get("logicalKey");
            String operationKey = UUID.randomUUID().toString();

            trustService.reject(operationKey, cvId, "operator", params.get("reason"));
            return trustPanel(logicalKey) + statusOob("Candidate rejected.", false).render();
        } catch (Exception e) {
            return trustPanel(params.get("logicalKey")) + statusOob(
                "Rejection failed: " + e.getMessage(), true).render();
        }
    }

    @PostMapping("/skills/_trust/revoke")
    @ResponseBody
    public String revokeApproval(@RequestParam Map<String, String> params) {
        try {
            String logicalKey = params.get("logicalKey");
            String operationKey = UUID.randomUUID().toString();

            trustService.revoke(operationKey, logicalKey, "operator", params.get("reason"));
            return trustPanel(logicalKey) + statusOob("Approval revoked.", false).render();
        } catch (Exception e) {
            return trustPanel(params.get("logicalKey")) + statusOob(
                "Revocation failed: " + e.getMessage(), true).render();
        }
    }

    @GetMapping("/skills/_snapshots/{logicalKey}")
    @ResponseBody
    public String snapshotHistory(@PathVariable String logicalKey) {
        Div panel = new Div().withClass("skill-snapshot-history-panel")
            .withChild(Header.H3("Snapshot History \u2014 " + logicalKey));

        var snapshots = snapshotRepository.findByLogicalKey(logicalKey);
        if (snapshots.isEmpty()) {
            panel.withChild(new Paragraph("No immutable snapshots exist for this skill."));
            return panel.render();
        }

        for (var s : snapshots) {
            Div row = new Div().withClass("skill-snapshot-history-row")
                .withChild(new HtmlTag("code").withInnerText(s.snapshotDigest().substring(0, 20) + "..."))
                .withChild(new HtmlTag("strong").withInnerText(s.skillName()))
                .withChild(new HtmlTag("span").withInnerText(
                    s.createdAt() != null ? TIME_FORMAT.format(s.createdAt()) : ""));
            panel.withChild(row);
        }

        return panel.render();
    }
}
