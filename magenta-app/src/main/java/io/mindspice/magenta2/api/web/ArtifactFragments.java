package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactAccessContext;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactPreviewService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactPreviewService.PreviewResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService.LineageEdge;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService.LineageResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService.SearchResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService.SearchResultItem;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationState;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService.RetentionBatchResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService.RetentionPin;
import io.mindspice.simplypages.builders.BannerBuilder;
import io.mindspice.simplypages.builders.ShellBuilder;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * HTMX-first artifact fragment surface for search, detail, preview,
 * lineage, retention controls, and integrity disposition.
 *
 * <p>Uses SimplyPages components with stable OOB targets for
 * partial-page swaps. Every fragment checks authorization before
 * rendering data. Integrity-failed artifacts expose no preview or
 * download link. Empty/error/unavailable states are explicit.</p>
 */
@Controller
public class ArtifactFragments {
    private static final String SEARCH_ID = "artifact-search-results";
    private static final String DETAIL_ID = "artifact-detail";
    private static final String PREVIEW_ID = "artifact-preview";
    private static final String LINEAGE_ID = "artifact-lineage";
    private static final String RETENTION_ID = "artifact-retention";
    private static final String LIST_ID = "artifact-list";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final ArtifactSearchService searchService;
    private final ArtifactPreviewService previewService;
    private final OutputArtifactRepository artifactRepository;
    private final TaskRunRetentionService retentionService;

    public ArtifactFragments(ArtifactSearchService searchService,
                              ArtifactPreviewService previewService,
                              OutputArtifactRepository artifactRepository,
                              TaskRunRetentionService retentionService) {
        this.searchService = searchService;
        this.previewService = previewService;
        this.artifactRepository = artifactRepository;
        this.retentionService = retentionService;
    }

    // ── Page shell ──

    @GetMapping("/artifacts")
    @ResponseBody
    public String artifactsPage() {
        Component body = new Div()
                .withId("artifacts-page")
                .withClass("orch-page artifacts-page")
                .withAttribute("data-orchestration-page", "artifacts")
                .withChild(Header.H1("Artifacts"))
                .withChild(new Paragraph("Search, preview, and manage published output artifacts."))
                .withChild(new Div().withClass("browser-layout browser-layout-wide artifacts-browser-layout")
                        .withChild(new Div().withClass("browser-sidebar artifacts-sidebar")
                                .withChild(new Div().withClass("browser-sidebar-header")
                                        .withChild(Header.H2("Search")))
                                .withChild(searchForm())
                                .withChild(new Div().withId(SEARCH_ID)
                                        .withClass("entity-list artifacts-search-list")
                                        .withChild(new Paragraph("Enter a workspace scope and query to search artifacts."))))
                        .withChild(new Div().withClass("browser-detail")
                                .withChild(emptyDetail())));

        return ShellBuilder.create()
                .withPageTitle("Magenta Artifacts")
                .withCustomCss(AppNavigation.OPERATIONAL_CSS)
                .withTopBanner(BannerBuilder.create()
                        .withLayout(BannerBuilder.BannerLayout.CENTERED)
                        .withTitle("Magenta Operations")
                        .withSubtitle("Orchestration dashboard")
                        .build())
                .withTopNav(AppNavigation.primaryTopNav())
                .withSideNav(AppNavigation.operationalSideNav("/artifacts"), true)
                .buildTemplate()
                .renderWithContent(body);
    }

    // ── Search ──

    @GetMapping("/artifacts/_search")
    @ResponseBody
    public String searchFragment(@RequestParam String query,
                                  @RequestParam String workspaceKind,
                                  @RequestParam String workspaceKey,
                                  @RequestParam(required = false) String taskRunId,
                                  @RequestParam(defaultValue = "0") int page) {
        ArtifactAccessContext access = StringUtils.hasText(taskRunId)
                ? ArtifactAccessContext.forTaskRun(workspaceKind, workspaceKey, taskRunId)
                : ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);

        if (!searchService.ftsAvailable()) {
            return searchUnavailable().render();
        }
        if (!StringUtils.hasText(query)) {
            return searchEmpty("Enter a search query.").render();
        }

        SearchResult result = searchService.search(query, access, page, 25);
        return renderSearchResults(result, query, workspaceKind, workspaceKey, taskRunId, page).render();
    }

    private Component searchForm() {
        return Form.create().withClass("artifact-search-form")
                .withHxGet("/artifacts/_search")
                .withHxTarget("#" + SEARCH_ID)
                .withHxSwap("outerHTML")
                .withChild(hidden("page", "0"))
                .withChild(new HtmlTag("label").withChild(new HtmlTag("span").withInnerText("Workspace Kind"))
                        .withChild(TextInput.create("workspaceKind")
                                .withPlaceholder("agent")
                                .required()))
                .withChild(new HtmlTag("label").withChild(new HtmlTag("span").withInnerText("Workspace Key"))
                        .withChild(TextInput.create("workspaceKey")
                                .withPlaceholder("agent-id")
                                .required()))
                .withChild(new HtmlTag("label").withChild(new HtmlTag("span").withInnerText("Task Run (optional)"))
                        .withChild(TextInput.create("taskRunId")
                                .withPlaceholder("run-id")))
                .withChild(new HtmlTag("label").withChild(new HtmlTag("span").withInnerText("Query"))
                        .withChild(TextInput.create("query")
                                .withPlaceholder("search terms...")
                                .required()))
                .withChild(Button.submit("Search").withClass("orch-primary"));
    }

    private Component renderSearchResults(SearchResult result, String query,
                                           String workspaceKind, String workspaceKey,
                                           String taskRunId, int page) {
        Div container = new Div().withId(SEARCH_ID).withClass("entity-list artifacts-search-list");

        // Count header
        container.withChild(new Div().withClass("artifacts-count-strip")
                .withChild(new HtmlTag("span").withInnerText(result.totalCount() + " results")));

        if (result.items().isEmpty()) {
            container.withChild(new Div().withClass("tool-item").withInnerText("No artifacts match your search."));
            return container;
        }

        for (SearchResultItem item : result.items()) {
            container.withChild(searchResultRow(item, workspaceKind, workspaceKey));
        }

        // Pagination
        if (result.hasMore() || page > 0) {
            Div pagination = new Div().withClass("artifacts-pagination");
            if (page > 0) {
                pagination.withChild(Button.create("Previous")
                        .withAttribute("hx-get", "/artifacts/_search"
                                + "?query=" + url(query)
                                + "&workspaceKind=" + url(workspaceKind)
                                + "&workspaceKey=" + url(workspaceKey)
                                + (StringUtils.hasText(taskRunId) ? "&taskRunId=" + url(taskRunId) : "")
                                + "&page=" + (page - 1))
                        .withAttribute("hx-target", "#" + SEARCH_ID)
                        .withAttribute("hx-swap", "outerHTML"));
            }
            if (result.hasMore()) {
                pagination.withChild(Button.create("Next")
                        .withAttribute("hx-get", "/artifacts/_search"
                                + "?query=" + url(query)
                                + "&workspaceKind=" + url(workspaceKind)
                                + "&workspaceKey=" + url(workspaceKey)
                                + (StringUtils.hasText(taskRunId) ? "&taskRunId=" + url(taskRunId) : "")
                                + "&page=" + (page + 1))
                        .withAttribute("hx-target", "#" + SEARCH_ID)
                        .withAttribute("hx-swap", "outerHTML"));
            }
            container.withChild(pagination);
        }

        return container;
    }

    private Component searchResultRow(SearchResultItem item, String workspaceKind, String workspaceKey) {
        HtmlTag open = new HtmlTag("button")
                .withAttribute("type", "button")
                .withClass("tool-item artifact-search-row")
                .withAttribute("hx-get", "/artifacts/_detail/" + urlPath(item.artifactId())
                        + "?workspaceKind=" + url(workspaceKind)
                        + "&workspaceKey=" + url(workspaceKey))
                .withAttribute("hx-target", "#" + DETAIL_ID)
                .withAttribute("hx-swap", "outerHTML")
                .withChild(new Div().withClass("artifact-row-header")
                        .withChild(new HtmlTag("strong").withClass("artifact-row-title")
                                .withInnerText(item.logicalName()))
                        .withChild(new HtmlTag("span").withClass("artifact-row-media")
                                .withInnerText(item.mediaType() != null ? item.mediaType() : "unknown")))
                .withChild(new HtmlTag("span").withClass("artifact-row-snippet")
                        .withUnsafeHtml(item.snippet() != null ? item.snippet() : ""))
                .withChild(new Div().withClass("artifact-row-meta")
                        .withChild(new HtmlTag("span").withInnerText(sizeLabel(item.verifiedSize())))
                        .withChild(new HtmlTag("span").withInnerText(item.lineCount() + " lines"))
                        .withChild(new HtmlTag("span").withInnerText(
                                item.extractionState() != null ? item.extractionState().toLowerCase(Locale.ROOT) : "unknown")));
        return new Div().withClass("artifact-search-card").withChild(open);
    }

    // ── Detail ──

    @GetMapping("/artifacts/_detail/{artifactId}")
    @ResponseBody
    public String detailFragment(@PathVariable String artifactId,
                                  @RequestParam String workspaceKind,
                                  @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return errorDetail("Artifact not found.").render();
        }
        return renderDetail(record, workspaceKind, workspaceKey).render();
    }

    private Component renderDetail(PublicationRecord record, String workspaceKind, String workspaceKey) {
        Div detail = new Div().withId(DETAIL_ID).withClass("orch-panel artifact-detail-panel")
                .withChild(new Div().withClass("artifact-detail-header")
                        .withChild(new Div()
                                .withChild(Header.H2(record.logicalName()))
                                .withChild(new Paragraph(record.mediaType() != null ? record.mediaType() : "unknown")))
                        .withChild(stateBadge(record.publicationState())));

        Div grid = new Div().withClass("artifact-summary-grid");
        grid.withChild(summaryItem("Artifact ID", record.artifactId()));
        grid.withChild(summaryItem("Size", record.verifiedSize() != null ? sizeLabel(record.verifiedSize()) : "unknown"));
        grid.withChild(summaryItem("SHA-256", record.contentSha256() != null
                ? record.contentSha256().substring(0, 16) + "..." : "none"));
        grid.withChild(summaryItem("State", record.publicationState().name()));
        grid.withChild(summaryItem("Reconciliation", record.reconciliationState() != null
                ? record.reconciliationState() : "none"));
        grid.withChild(summaryItem("Task Run", record.taskRunId() != null ? record.taskRunId() : "none"));
        grid.withChild(summaryItem("Published", record.publishedAt() != null
                ? TIME_FORMAT.format(record.publishedAt()) : "not published"));
        grid.withChild(summaryItem("Workspace", record.workspaceIdentity()));
        detail.withChild(grid);

        // Action buttons
        Div actions = new Div().withClass("artifact-detail-actions");
        if (record.isReadable()) {
            actions.withChild(Button.create("Preview")
                    .withAttribute("hx-get", "/artifacts/_preview/" + urlPath(record.artifactId())
                            + "?workspaceKind=" + url(workspaceKind)
                            + "&workspaceKey=" + url(workspaceKey))
                    .withAttribute("hx-target", "#" + PREVIEW_ID)
                    .withAttribute("hx-swap", "outerHTML"));
            actions.withChild(Button.create("Download")
                    .withClass("orch-secondary")
                    .withAttribute("hx-get", "/api/artifacts/" + urlPath(record.artifactId())
                            + "/download?workspaceKind=" + url(workspaceKind)
                            + "&workspaceKey=" + url(workspaceKey))
                    .withAttribute("hx-target", "_blank"));
        }
        actions.withChild(Button.create("Lineage")
                .withAttribute("hx-get", "/artifacts/_lineage/" + urlPath(record.artifactId())
                        + "?workspaceKind=" + url(workspaceKind)
                        + "&workspaceKey=" + url(workspaceKey))
                .withAttribute("hx-target", "#" + LINEAGE_ID)
                .withAttribute("hx-swap", "outerHTML"));
        actions.withChild(Button.create("Retention")
                .withAttribute("hx-get", "/artifacts/_retention/" + urlPath(record.artifactId())
                        + "?workspaceKind=" + url(workspaceKind)
                        + "&workspaceKey=" + url(workspaceKey))
                .withAttribute("hx-target", "#" + RETENTION_ID)
                .withAttribute("hx-swap", "outerHTML"));
        detail.withChild(actions);

        // Sub-panels
        detail.withChild(new Div().withId(PREVIEW_ID).withClass("artifact-sub-panel")
                .withChild(new Paragraph("Select Preview to view content.")));
        detail.withChild(new Div().withId(LINEAGE_ID).withClass("artifact-sub-panel")
                .withChild(new Paragraph("Select Lineage to view relationships.")));
        detail.withChild(new Div().withId(RETENTION_ID).withClass("artifact-sub-panel")
                .withChild(new Paragraph("Select Retention to manage lifecycle.")));

        return detail;
    }

    // ── Preview ──

    @GetMapping("/artifacts/_preview/{artifactId}")
    @ResponseBody
    public String previewFragment(@PathVariable String artifactId,
                                   @RequestParam String workspaceKind,
                                   @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PreviewResult result = previewService.preview(artifactId, access);
        return renderPreview(result, workspaceKind, workspaceKey).render();
    }

    private Component renderPreview(PreviewResult result, String workspaceKind, String workspaceKey) {
        Div panel = new Div().withId(PREVIEW_ID).withClass("artifact-preview-panel");

        panel.withChild(new Div().withClass("artifact-section-header")
                .withChild(Header.H3("Preview"))
                .withChild(new HtmlTag("span").withInnerText(result.state().name())));

        switch (result.state()) {
            case OK -> {
                Div info = new Div().withClass("artifact-preview-info");
                info.withChild(new HtmlTag("span").withInnerText(
                        sizeLabel(result.fileSize()) + " · " + result.lineCount() + " lines"));
                if (result.mediaType() != null) {
                    info.withChild(new HtmlTag("span").withInnerText(result.mediaType()));
                }
                if (result.truncated()) {
                    info.withChild(new HtmlTag("span").withClass("orch-warning")
                            .withInnerText("Preview truncated"));
                }
                panel.withChild(info);

                if (result.markdownAvailable() && result.safeHtml() != null) {
                    panel.withChild(new HtmlTag("div").withClass("artifact-preview-rendered markdown-body")
                            .withUnsafeHtml(result.safeHtml()));
                } else if (result.content() != null) {
                    panel.withChild(new HtmlTag("pre").withClass("artifact-preview-text")
                            .withInnerText(result.content()));
                }
            }
            case MISSING -> panel.withChild(new Paragraph("Artifact not found or not accessible."));
            case INTEGRITY_FAILED -> panel.withChild(new Div().withClass("orch-error")
                    .withInnerText("Integrity verification failed. Preview and download are unavailable."));
            case UNAVAILABLE -> panel.withChild(new Paragraph("Artifact is not in a readable state."));
            case UNSUPPORTED -> panel.withChild(new Paragraph(
                    "Preview is not available for this media type: " + result.mediaType()));
            case ERROR -> panel.withChild(new Div().withClass("orch-error")
                    .withInnerText("Preview failed: " + (result.mediaType() != null ? result.mediaType() : "unknown error")));
        }

        return panel;
    }

    // ── Lineage ──

    @GetMapping("/artifacts/_lineage/{artifactId}")
    @ResponseBody
    public String lineageFragment(@PathVariable String artifactId,
                                   @RequestParam String workspaceKind,
                                   @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        LineageResult result = searchService.lineage(artifactId, access);
        return renderLineage(result, workspaceKind, workspaceKey).render();
    }

    private Component renderLineage(LineageResult result, String workspaceKind, String workspaceKey) {
        Div panel = new Div().withId(LINEAGE_ID).withClass("artifact-lineage-panel");

        panel.withChild(new Div().withClass("artifact-section-header")
                .withChild(Header.H3("Lineage")));

        if (result.artifactId() == null) {
            panel.withChild(new Paragraph("Artifact not found."));
            return panel;
        }

        // Parents
        panel.withChild(Header.H4("Parents (" + result.parents().size() + ")"));
        if (result.parents().isEmpty()) {
            panel.withChild(new Paragraph("No parent artifacts."));
        } else {
            for (LineageEdge edge : result.parents()) {
                panel.withChild(lineageRow(edge, "parent", workspaceKind, workspaceKey));
            }
        }

        // Children
        panel.withChild(Header.H4("Children (" + result.children().size() + ")"));
        if (result.children().isEmpty()) {
            panel.withChild(new Paragraph("No child artifacts."));
        } else {
            for (LineageEdge edge : result.children()) {
                panel.withChild(lineageRow(edge, "child", workspaceKind, workspaceKey));
            }
        }

        return panel;
    }

    private Component lineageRow(LineageEdge edge, String direction, String workspaceKind, String workspaceKey) {
        String otherId = direction.equals("parent") ? edge.parentArtifactId() : edge.childArtifactId();
        return new Div().withClass("lineage-row")
                .withChild(new HtmlTag("span").withClass("lineage-kind")
                        .withInnerText(edge.lineageKind()))
                .withChild(new HtmlTag("span").withClass("lineage-direction")
                        .withInnerText(direction.equals("parent") ? "←" : "→"))
                .withChild(Button.create(otherId.substring(0, Math.min(16, otherId.length())) + "...")
                        .withAttribute("hx-get", "/artifacts/_detail/" + urlPath(otherId)
                                + "?workspaceKind=" + url(workspaceKind)
                                + "&workspaceKey=" + url(workspaceKey))
                        .withAttribute("hx-target", "#" + DETAIL_ID)
                        .withAttribute("hx-swap", "outerHTML"))
                .withChild(new HtmlTag("small").withInnerText(
                        edge.createdAt() != null ? TIME_FORMAT.format(edge.createdAt()) : ""));
    }

    // ── Retention ──

    @GetMapping("/artifacts/_retention/{artifactId}")
    @ResponseBody
    public String retentionFragment(@PathVariable String artifactId,
                                     @RequestParam String workspaceKind,
                                     @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return retentionError("Artifact not found.").render();
        }
        return renderRetention(record, workspaceKind, workspaceKey).render();
    }

    private Component renderRetention(PublicationRecord record, String workspaceKind, String workspaceKey) {
        Div panel = new Div().withId(RETENTION_ID).withClass("artifact-retention-panel");

        panel.withChild(new Div().withClass("artifact-section-header")
                .withChild(Header.H3("Retention")));

        String blocker = retentionService.removalBlocker(record.artifactId());
        List<RetentionPin> pins = retentionService.listPins(record.artifactId(), null);

        Div status = new Div().withClass("retention-status");
        if (blocker == null) {
            status.withChild(new HtmlTag("span").withClass("orch-status")
                    .withInnerText("Artifact can be removed"));
        } else {
            status.withChild(new HtmlTag("span").withClass("orch-warning")
                    .withInnerText("Removal blocked: " + blocker));
        }
        panel.withChild(status);

        // Pins
        if (!pins.isEmpty()) {
            Div pinList = new Div().withClass("retention-pin-list");
            for (RetentionPin pin : pins) {
                pinList.withChild(new Div().withClass("retention-pin-row")
                        .withChild(new HtmlTag("span").withClass("pin-kind").withInnerText(pin.pinKind()))
                        .withChild(new HtmlTag("span").withInnerText(pin.reason()))
                        .withChild(new HtmlTag("small").withInnerText(
                                pin.pinnedAt() != null ? TIME_FORMAT.format(pin.pinnedAt()) : "")));
            }
            panel.withChild(pinList);
        }

        // Action buttons
        if (blocker == null) {
            Div actions = new Div().withClass("retention-actions");
            HtmlTag softArchiveBtn = Button.create("Soft Archive")
                    .withClass("orch-warning-action")
                    .withAttribute("hx-post", "/api/artifacts/" + urlPath(record.artifactId())
                            + "/retention/soft-archive?workspaceKind=" + url(workspaceKind)
                            + "&workspaceKey=" + url(workspaceKey))
                    .withAttribute("hx-target", "#" + RETENTION_ID)
                    .withAttribute("hx-swap", "outerHTML");

            HtmlTag hardExpireBtn = Button.create("Hard Expire")
                    .withClass("orch-danger")
                    .withAttribute("hx-post", "/api/artifacts/" + urlPath(record.artifactId())
                            + "/retention/hard-expire?workspaceKind=" + url(workspaceKind)
                            + "&workspaceKey=" + url(workspaceKey))
                    .withAttribute("hx-target", "#" + RETENTION_ID)
                    .withAttribute("hx-swap", "outerHTML")
                    .withAttribute("hx-confirm", "Permanently delete this artifact? This cannot be undone.");

            actions.withChild(softArchiveBtn);
            actions.withChild(hardExpireBtn);
            panel.withChild(actions);
        }

        return panel;
    }

    private Component retentionError(String message) {
        return new Div().withId(RETENTION_ID).withClass("artifact-retention-panel")
                .withChild(new Div().withClass("orch-error").withInnerText(message));
    }

    // ── Empty / error states ──

    private Component emptyDetail() {
        return new Div().withId(DETAIL_ID).withClass("orch-panel empty-detail")
                .withChild(new Paragraph("Search for artifacts or select one to view details."));
    }

    private Component errorDetail(String message) {
        return new Div().withId(DETAIL_ID).withClass("orch-panel empty-detail")
                .withChild(new Div().withClass("orch-error").withInnerText(message));
    }

    private Component searchEmpty(String message) {
        return new Div().withId(SEARCH_ID).withClass("entity-list artifacts-search-list")
                .withChild(new Div().withClass("tool-item").withInnerText(message));
    }

    private Component searchUnavailable() {
        return new Div().withId(SEARCH_ID).withClass("entity-list artifacts-search-list")
                .withChild(new Div().withClass("orch-error")
                        .withInnerText("FTS5 search is not available on this deployment."));
    }

    // ── Helpers ──

    private Component stateBadge(PublicationState state) {
        String label = state.name().toLowerCase(Locale.ROOT);
        String style = switch (state) {
            case PUBLISHED -> "is-approved";
            case STAGED -> "is-draft";
            case INTEGRITY_FAILED -> "is-rejected";
            case FAILED -> "is-neutral";
            default -> "";
        };
        return new HtmlTag("span")
                .withClass("plan-status-badge artifact-state-badge " + style)
                .withInnerText(label);
    }

    private Component summaryItem(String label, String value) {
        return new Div().withClass("artifact-summary-item")
                .withChild(new HtmlTag("span").withInnerText(label))
                .withChild(new HtmlTag("strong").withInnerText(value));
    }

    private Component hidden(String name, String value) {
        return new HtmlTag("input", true)
                .withAttribute("type", "hidden")
                .withAttribute("name", name)
                .withAttribute("value", value == null ? "" : value);
    }

    private String sizeLabel(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
    }

    private String rawHtml(String html) {
        // SimplyPages innerHtml helper
        return html;
    }

    private String urlPath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
