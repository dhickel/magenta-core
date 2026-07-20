package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementCompleteness;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementKind;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementOutcome;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.RunMeasurement;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.ArtifactFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.AttemptFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.CausationFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.NormalizedRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunAudit;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunHealth;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunSummary;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.SkillActivationFact;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.TextNode;
import io.mindspice.simplypages.components.display.Table;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.core.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Reusable, slot-safe HTMX fragments for runtime health, bounded history,
 * repeated call measurement rows, and on-demand audit expansion.
 *
 * <p>All interactions are HTMX-first with no JavaScript. Empty, partial,
 * loading, forbidden, and error states are preserved. Absent numeric facts
 * are displayed as &ldquo;unavailable&rdquo; rather than zero.
 */
@Controller
public class RuntimeHealthFragments {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static final int MAX_PAGE_SIZE = 100;

    private final RunSummaryService summaryService;
    private final RunMeasurementRepository measurementRepo;

    public RuntimeHealthFragments(RunSummaryService summaryService,
                                   RunMeasurementRepository measurementRepo) {
        this.summaryService = summaryService;
        this.measurementRepo = measurementRepo;
    }

    // ── Health Summary ──

    @GetMapping("/runtime/workspaces/{workspaceType}/{workspaceKey}/health")
    @ResponseBody
    public String healthSummary(
            @PathVariable String workspaceType,
            @PathVariable String workspaceKey) {
        RuntimeController.requireAgentOrUser(workspaceType);

        try {
            RunHealth health = summaryService.computeHealth(workspaceType, workspaceKey);
            return healthPanel(health, workspaceType, workspaceKey).render();
        } catch (Exception e) {
            return errorPanel("Failed to compute health: " + e.getMessage()).render();
        }
    }

    private Component healthPanel(RunHealth health, String workspaceType, String workspaceKey) {
        String ctx = workspaceType + "/" + workspaceKey;

        Div div = new Div().withId("runtime-health")
                .withClass("runtime-panel runtime-health-panel")
                .withChild(Header.H2("Run Health").withClass("panel-title"))
                .withChild(new Paragraph("Workspace: " + ctx).withClass("panel-context"));

        // Metrics row
        Div metrics = new Div().withClass("health-metrics");
        metrics.withChild(metricBox("Succeeded", String.valueOf(health.succeededCount()), "health-succeeded"));
        metrics.withChild(metricBox("Terminal Total", String.valueOf(health.terminalDenominator()), "health-terminal"));
        metrics.withChild(metricBox("Active", String.valueOf(health.activeCount()), "health-active"));
        metrics.withChild(metricBox("Unavailable", String.valueOf(health.unavailableCount()), "health-unavailable"));
        metrics.withChild(metricBox("Legacy", String.valueOf(health.legacyCount()), "health-legacy"));
        div.withChild(metrics);

        // Percentiles row
        Div percentiles = new Div().withClass("health-percentiles");
        percentiles.withChild(metricBox("Median Wall", health.medianWallMs() != null
                ? formatDuration(health.medianWallMs()) : "\u2014", "health-median"));
        percentiles.withChild(metricBox("P95 Wall", health.p95WallMs() != null
                ? formatDuration(health.p95WallMs()) : "\u2014", "health-p95"));
        percentiles.withChild(metricBox("Sample Size", String.valueOf(health.sampleCount()), "health-samples"));
        div.withChild(percentiles);

        // Terminal breakdown
        div.withChild(terminalBreakdownTable(health.terminalBreakdown()));

        // Refresh button
        div.withChild(Button.create("Refresh")
                .withClass("button-secondary small")
                .withAttribute("hx-get", "/runtime/workspaces/"
                        + e(workspaceType) + "/" + e(workspaceKey) + "/health")
                .withAttribute("hx-target", "#runtime-health")
                .withAttribute("hx-swap", "outerHTML"));

        return div;
    }

    private Component metricBox(String label, String value, String cssClass) {
        return new Div().withClass("metric-box " + cssClass)
                .withChild(new Paragraph(label).withClass("metric-label"))
                .withChild(new Paragraph(value).withClass("metric-value"));
    }

    private Component terminalBreakdownTable(Map<NormalizedRunStatus, Long> breakdown) {
        if (breakdown.isEmpty()) {
            return new Div().withClass("empty-state")
                    .withChild(new Paragraph("No terminal runs."));
        }
        Table table = Table.create()
                .withHeaders("Status", "Count")
                .withClass("data-table compact");
        for (var entry : breakdown.entrySet()) {
            table.addRow(statusBadgeLabel(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return table;
    }

    // ── Bounded History ──

    @GetMapping("/runtime/workspaces/{workspaceType}/{workspaceKey}/history")
    @ResponseBody
    public String boundedHistory(
            @PathVariable String workspaceType,
            @PathVariable String workspaceKey,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "25") int limit) {
        RuntimeController.requireAgentOrUser(workspaceType);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

        try {
            List<RunSummary> summaries = summaryService.findSummariesByWorkspace(
                    workspaceType, workspaceKey, after, boundedLimit);
            if (summaries.isEmpty() && !StringUtils.hasText(after)) {
                return new Div().withId("runtime-history")
                        .withClass("empty-state")
                        .withChild(new Paragraph("No run history for this workspace."))
                        .render();
            }
            if (summaries.isEmpty()) {
                return new Div().withId("runtime-history")
                        .withClass("empty-state")
                        .withChild(new Paragraph("No more runs."))
                        .render();
            }
            return historyTable(summaries, workspaceType, workspaceKey).render();
        } catch (Exception e) {
            return errorPanel("Failed to load history: " + e.getMessage()).render();
        }
    }

    private Component historyTable(List<RunSummary> summaries,
                                    String workspaceType, String workspaceKey) {
        Table table = Table.create()
                .withHeaders("Run", "Status", "Attempts", "Model", "Tool",
                        "Wall", "Active", "")
                .withClass("data-table compact");

        String lastTime = null;
        for (RunSummary s : summaries) {
            table.addRow(
                    s.taskRunId(),
                    statusBadgeLabel(s.normalizedStatus()),
                    String.valueOf(s.attemptCount()),
                    String.valueOf(s.modelCallCount()),
                    String.valueOf(s.toolCallCount()),
                    s.wallElapsedMs() != null ? formatDuration(s.wallElapsedMs()) : "\u2014",
                    s.activeElapsedMs() != null ? formatDuration(s.activeElapsedMs()) : "\u2014",
                    Button.create("Details")
                            .withClass("button-link small")
                            .withAttribute("hx-get", "/runtime/runs/"
                                    + e(s.taskRunId()) + "/measurements-fragment")
                            .withAttribute("hx-target", "#runtime-detail")
                            .withAttribute("hx-swap", "innerHTML")
                            .render());
            if (s.updatedAt() != null) lastTime = s.updatedAt().toString();
        }

        Div div = new Div().withId("runtime-history").withClass("runtime-history-panel");
        div.withChild(table);
        if (lastTime != null) {
            div.withChild(Button.create("Load More")
                    .withClass("button-secondary small")
                    .withAttribute("hx-get", "/runtime/workspaces/"
                            + e(workspaceType) + "/" + e(workspaceKey)
                            + "/history?after=" + e(lastTime))
                    .withAttribute("hx-target", "#runtime-history")
                    .withAttribute("hx-swap", "outerHTML"));
        }
        return div;
    }

    // ── Measurement Fragments ──

    @GetMapping("/runtime/runs/{taskRunId}/measurements-fragment")
    @ResponseBody
    public String runMeasurementsFragment(@PathVariable String taskRunId) {
        RuntimeController.requireTaskRunId(taskRunId);

        try {
            RunSummary summary = summaryService.findSummary(taskRunId)
                    .orElseGet(() -> summaryService.rebuildSummary(taskRunId));
            List<RunMeasurement> measurements = measurementRepo.findByTaskRun(taskRunId);

            return measurementDetailPanel(summary, measurements, taskRunId).render();
        } catch (Exception e) {
            return errorPanel("Failed to load measurements: " + e.getMessage()).render();
        }
    }

    private Component measurementDetailPanel(RunSummary summary,
                                               List<RunMeasurement> measurements,
                                               String taskRunId) {
        Div div = new Div().withId("runtime-detail")
                .withClass("runtime-detail-panel")
                .withChild(Header.H3("Run " + taskRunId).withClass("detail-title"))
                .withChild(new Div().withClass("summary-meta")
                        .withChild(new Paragraph("Status: " + statusBadgeLabel(summary.normalizedStatus())
                                + " | Source: " + summary.sourceKind()
                                + " | Attempts: " + summary.attemptCount()
                                + " | Completeness: " + summary.completeness())));

        if (measurements.isEmpty()) {
            div.withChild(new Paragraph("No measurements recorded for this run.")
                    .withClass("empty-state"));
        } else {
            Table table = Table.create()
                    .withHeaders("Invocation", "Kind", "Call ID", "Outcome",
                            "Elapsed", "Tokens", "Complete")
                    .withClass("data-table compact measurements-table");

            for (RunMeasurement m : measurements) {
                String kind = m.kind() == MeasurementKind.MODEL ? "Model" : "Tool";
                String name = m.kind() == MeasurementKind.MODEL
                        ? (m.modelName() != null ? m.modelName() : "\u2014")
                        : (m.toolName() != null ? m.toolName() : "\u2014");
                String elapsed = m.elapsedMs() != null
                        ? formatDuration(m.elapsedMs()) : "\u2014";
                String tokens = m.totalTokenCount() != null
                        ? String.valueOf(m.totalTokenCount()) : "\u2014";
                String completeness = m.completeness() == MeasurementCompleteness.COMPLETE
                        ? "complete" : m.completeness() == MeasurementCompleteness.INCOMPLETE
                        ? "incomplete" : "unavailable";

                table.addRow(
                        Button.create(m.invocationId())
                                .withClass("button-link small mono")
                                .withAttribute("hx-get", "/runtime/runs/"
                                        + e(taskRunId) + "/measurements/" + e(m.invocationId()))
                                .withAttribute("hx-target", "#invocation-detail")
                                .withAttribute("hx-swap", "innerHTML")
                                .render(),
                        kind + " / " + name,
                        m.callId(),
                        outcomeBadgeLabel(m.outcome()),
                        elapsed,
                        tokens,
                        completeness);
            }
            div.withChild(table);
        }

        // Audit expansion
        div.withChild(new Div().withId("invocation-detail"));
        div.withChild(Button.create("View Audit")
                .withClass("button-secondary small")
                .withAttribute("hx-get", "/runtime/runs/"
                        + e(taskRunId) + "/audit-fragment")
                .withAttribute("hx-target", "#runtime-audit-region")
                .withAttribute("hx-swap", "innerHTML"));
        div.withChild(new Div().withId("runtime-audit-region"));

        return div;
    }

    // ── Audit Fragment ──

    @GetMapping("/runtime/runs/{taskRunId}/audit-fragment")
    @ResponseBody
    public String auditFragment(@PathVariable String taskRunId) {
        RuntimeController.requireTaskRunId(taskRunId);

        try {
            RunAudit audit = summaryService.composeAudit(taskRunId).orElse(null);
            if (audit == null) {
                return new Div().withId("runtime-audit-region")
                        .withClass("audit-panel")
                        .withChild(new Paragraph("Audit data is unavailable for this run.")
                                .withClass("empty-state"))
                        .render();
            }
            return auditPanel(audit).render();
        } catch (Exception e) {
            return errorPanel("Failed to load audit: " + e.getMessage()).render();
        }
    }

    private Component auditPanel(RunAudit audit) {
        Div div = new Div().withId("runtime-audit-region")
                .withClass("audit-panel")
                .withChild(Header.H4("Audit").withClass("section-title"));

        // Attempts
        if (!audit.attempts().isEmpty()) {
            Div section = new Div().withClass("audit-section")
                    .withChild(Header.H5("Attempts").withClass("subsection-title"));
            Table t = Table.create()
                    .withHeaders("#", "Status", "Started", "Completed", "Error")
                    .withClass("data-table compact");
            for (AttemptFact a : audit.attempts()) {
                t.addRow(
                        String.valueOf(a.attemptNumber()),
                        a.status(),
                        a.startedAt() != null ? TIME_FORMAT.format(a.startedAt()) : "\u2014",
                        a.completedAt() != null ? TIME_FORMAT.format(a.completedAt()) : "\u2014",
                        a.errorText() != null ? a.errorText() : "\u2014");
            }
            section.withChild(t);
            div.withChild(section);
        }

        // Skill Activations
        if (!audit.skillActivations().isEmpty()) {
            Div section = new Div().withClass("audit-section")
                    .withChild(Header.H5("Skill Activations").withClass("subsection-title"));
            Table t = Table.create()
                    .withHeaders("Skill", "Context", "Trust", "Digest")
                    .withClass("data-table compact");
            for (SkillActivationFact s : audit.skillActivations()) {
                t.addRow(
                        s.logicalKey(),
                        s.activationContext(),
                        s.trustDecision() != null ? s.trustDecision() : "\u2014",
                        s.snapshotDigest() != null ? s.snapshotDigest().substring(0, 12) + "..." : "\u2014");
            }
            section.withChild(t);
            div.withChild(section);
        }

        // Artifacts
        if (!audit.artifactFacts().isEmpty()) {
            Div section = new Div().withClass("audit-section")
                    .withChild(Header.H5("Artifacts").withClass("subsection-title"));
            Table t = Table.create()
                    .withHeaders("ID", "Name", "State", "Published")
                    .withClass("data-table compact");
            for (ArtifactFact a : audit.artifactFacts()) {
                t.addRow(
                        a.artifactId(),
                        a.logicalName(),
                        a.publicationState(),
                        a.publishedAt() != null ? TIME_FORMAT.format(a.publishedAt()) : "\u2014");
            }
            section.withChild(t);
            div.withChild(section);
        }

        // Causation
        if (!audit.causationFacts().isEmpty()) {
            Div section = new Div().withClass("audit-section")
                    .withChild(Header.H5("Causation").withClass("subsection-title"));
            Table t = Table.create()
                    .withHeaders("Kind", "Source", "Firing/Event", "Fired At")
                    .withClass("data-table compact");
            for (CausationFact c : audit.causationFacts()) {
                t.addRow(
                        c.causationKind(),
                        c.sourceId() != null ? c.sourceId() : "\u2014",
                        c.firingOrEventId() != null ? c.firingOrEventId() : "\u2014",
                        c.firedAt() != null ? TIME_FORMAT.format(c.firedAt()) : "\u2014");
            }
            section.withChild(t);
            div.withChild(section);
        }

        return div;
    }

    // ── Measurement Invocation Detail ──

    @GetMapping("/runtime/runs/{taskRunId}/measurements/{invocationId}")
    @ResponseBody
    public String invocationDetail(
            @PathVariable String taskRunId,
            @PathVariable String invocationId) {
        RuntimeController.requireTaskRunId(taskRunId);

        try {
            RunMeasurement m = measurementRepo.findById(invocationId).orElse(null);
            if (m == null || !m.taskRunId().equals(taskRunId)) {
                return new Div().withClass("empty-state")
                        .withChild(new Paragraph("Measurement not found."))
                        .render();
            }
            return invocationDetailPanel(m).render();
        } catch (Exception e) {
            return errorPanel("Failed to load invocation: " + e.getMessage()).render();
        }
    }

    private Component invocationDetailPanel(RunMeasurement m) {
        Div div = new Div().withId("invocation-detail")
                .withClass("invocation-detail-panel")
                .withChild(Header.H4("Invocation " + m.invocationId()).withClass("detail-title"));

        Div meta = new Div().withClass("invocation-meta");
        meta.withChild(metaLine("Kind", m.kind().name()));
        meta.withChild(metaLine("Call ID", m.callId()));
        meta.withChild(metaLine("Correlation ID", m.correlationId() != null ? m.correlationId() : "\u2014"));
        meta.withChild(metaLine("Outcome", outcomeBadgeLabel(m.outcome())));
        meta.withChild(metaLine("Completeness", m.completeness().name()));

        if (m.kind() == MeasurementKind.MODEL) {
            meta.withChild(metaLine("Provider", m.provider() != null ? m.provider() : "\u2014"));
            meta.withChild(metaLine("Model", m.modelName() != null ? m.modelName() : "\u2014"));
        } else {
            meta.withChild(metaLine("Tool", m.toolName() != null ? m.toolName() : "\u2014"));
        }

        meta.withChild(metaLine("Started", m.startedAt() != null ? TIME_FORMAT.format(m.startedAt()) : "\u2014"));
        meta.withChild(metaLine("Completed", m.completedAt() != null ? TIME_FORMAT.format(m.completedAt()) : "\u2014"));
        meta.withChild(metaLine("Elapsed", m.elapsedMs() != null ? formatDuration(m.elapsedMs()) : "\u2014"));
        meta.withChild(metaLine("Input Tokens", m.inputTokenCount() != null ? String.valueOf(m.inputTokenCount()) : "\u2014"));
        meta.withChild(metaLine("Output Tokens", m.outputTokenCount() != null ? String.valueOf(m.outputTokenCount()) : "\u2014"));
        meta.withChild(metaLine("Total Tokens", m.totalTokenCount() != null ? String.valueOf(m.totalTokenCount()) : "\u2014"));

        if (m.timeoutMs() != null) meta.withChild(metaLine("Timeout", formatDuration(m.timeoutMs())));
        if (m.exitCode() != null) meta.withChild(metaLine("Exit Code", String.valueOf(m.exitCode())));
        if (m.truncationKind() != null) meta.withChild(metaLine("Truncation", m.truncationKind()));
        if (m.interruptionKind() != null) meta.withChild(metaLine("Interruption", m.interruptionKind()));
        if (m.rejectionReason() != null) meta.withChild(metaLine("Rejection", m.rejectionReason()));
        if (m.failureDetail() != null) meta.withChild(metaLine("Failure", m.failureDetail()));

        div.withChild(meta);
        return div;
    }

    // ── Helpers ──

    private static Component metaLine(String key, String value) {
        return new Paragraph(key + ": " + value).withClass("meta-line");
    }

    private static String statusBadgeLabel(NormalizedRunStatus status) {
        return status.name();
    }

    private static String outcomeBadgeLabel(MeasurementOutcome outcome) {
        return outcome.name();
    }

    private static Component errorPanel(String message) {
        return new Div().withClass("error-panel")
                .withChild(new Paragraph("Error: " + message).withClass("error-text"));
    }

    private static String formatDuration(long millis) {
        if (millis < 1000) return millis + "ms";
        if (millis < 60_000) return String.format("%.1fs", millis / 1000.0);
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + "m " + seconds + "s";
    }

    private static String e(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
