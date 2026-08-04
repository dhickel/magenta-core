package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.ArtifactFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.AttemptFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunAudit;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunSummary;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.display.Table;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

/**
 * Reusable HTMX run-operation fragments for the agent Files surface.
 *
 * <p>The renderer consumes the runtime summary/audit read models. It does not
 * query persistence, inspect workspace paths, derive missing historical facts,
 * or expose lease and writer-fence material.</p>
 */
@org.springframework.stereotype.Component
public final class RunOperationsFragments {
    static final String GROUPS_ID = "agent-run-groups";
    static final String DETAIL_ID = "agent-run-detail";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    Component groups(String agentId, List<RunSummary> summaries, int limit) {
        return groups(agentId, summaries, limit, false);
    }

    Component groups(String agentId, List<RunSummary> summaries, int limit, boolean oob) {
        Div panel = new Div().withId(GROUPS_ID)
            .withClass("run-operations-panel");
        if (oob) {
            panel.withAttribute("hx-swap-oob", "outerHTML");
        }
        panel.withChild(new Div().withClass("run-operations-heading")
                .withChild(Header.H2("Task Runs"))
                .withChild(refreshButton(agentId, limit)));

        if (summaries == null) {
            return panel.withChild(state("Run metadata is unavailable.", "unavailable"))
                .withChild(detailHost("Select a run to inspect attempts and artifacts."));
        }
        if (summaries.isEmpty()) {
            return panel.withChild(state("No task, workflow, or job runs are recorded for this agent workspace.", "empty"))
                .withChild(detailHost("Run details will appear here after execution metadata is available."));
        }

        Map<String, List<RunSummary>> grouped = new LinkedHashMap<>();
        summaries.forEach(summary -> grouped.computeIfAbsent(groupKey(summary.sourceKind()), ignored -> new java.util.ArrayList<>())
            .add(summary));
        for (Map.Entry<String, List<RunSummary>> entry : grouped.entrySet()) {
            panel.withChild(group(entry.getKey(), entry.getValue(), agentId));
        }
        panel.withChild(new Paragraph("Showing up to " + limit + " most recently updated runs. Missing facts remain unavailable.")
            .withClass("run-operations-footnote"));
        panel.withChild(detailHost("Select a run to inspect attempts, lineage, and artifact state."));
        return panel;
    }

    Component detail(RunAudit audit) {
        if (audit == null || audit.summary() == null) {
            return new Div().withId(DETAIL_ID).withClass("run-detail-panel")
                .withChild(state("Run metadata is missing, unavailable, or outside this agent scope.", "unavailable"));
        }

        RunSummary summary = audit.summary();
        Div panel = new Div().withId(DETAIL_ID).withClass("run-detail-panel")
            .withChild(new Div().withClass("run-detail-heading")
                .withChild(Header.H3("Run " + summary.taskRunId()))
                .withChild(statusChip(summary.normalizedStatus().name())))
            .withChild(summaryGrid(summary, audit.attempts()))
            .withChild(currentClaim(audit.attempts()))
            .withChild(attempts(audit.attempts()))
            .withChild(artifacts(summary, audit.artifactFacts()));

        if (summary.lineageParentTaskRunId() != null) {
            panel.withChild(new Div().withClass("run-lineage")
                .withChild(new HtmlTag("strong").withInnerText("Lineage"))
                .withChild(new HtmlTag("span").withInnerText(label(summary.lineageMode()) + " parent"))
                .withChild(Button.create(shortId(summary.lineageParentTaskRunId()))
                    .withClass("button-link small run-lineage-open")
                    .withAttribute("title", summary.lineageParentTaskRunId())
                    .withAttribute("hx-get", "/agents/_detail/" + e(summary.workspaceKey())
                        + "/runs/" + e(summary.lineageParentTaskRunId()))
                    .withAttribute("hx-target", "#" + DETAIL_ID)
                    .withAttribute("hx-swap", "outerHTML")));
        } else {
            panel.withChild(state("No retry or replay parent lineage is recorded.", "empty"));
        }

        if (summary.sourceKind() == null || summary.sourceKind().isBlank()) {
            panel.withChild(state("Legacy run: canonical source metadata is unavailable and is not inferred.", "unavailable"));
        }
        if (summary.completeness() == null || "UNAVAILABLE".equals(summary.completeness().name())) {
            panel.withChild(state("Historical run completeness is unavailable; absent values are not interpreted as zero.", "unavailable"));
        } else if ("PARTIAL".equals(summary.completeness().name())) {
            panel.withChild(state("This run is active or only partially reconciled. Elapsed and artifact facts may be incomplete.", "partial"));
        }
        return panel;
    }

    Component error(String message) {
        return new Div().withId(GROUPS_ID).withClass("run-operations-panel")
            .withChild(state(message == null || message.isBlank() ? "Run metadata could not be loaded." : message, "error"))
            .withChild(detailHost("Run details are unavailable."));
    }

    private Component group(String title, List<RunSummary> runs, String agentId) {
        Div group = new Div().withClass("run-task-group")
            .withChild(new Div().withClass("run-task-group-heading")
                .withChild(Header.H3(title))
                .withChild(new HtmlTag("span").withClass("orch-chip").withInnerText(runs.size() + " shown")));
        Table table = Table.create()
            .withHeaders("Run", "Status", "Updated", "Elapsed", "Attempts", "Measurements")
            .withClass("data-table compact run-groups-table");
        for (RunSummary run : runs) {
            table.addRow(
                Button.create(shortId(run.taskRunId()))
                    .withClass("button-link small run-open")
                    .withAttribute("title", run.taskRunId())
                    .withAttribute("hx-get", "/agents/_detail/" + e(agentId) + "/runs/" + e(run.taskRunId()))
                    .withAttribute("hx-target", "#" + DETAIL_ID)
                    .withAttribute("hx-swap", "outerHTML"),
                statusChip(run.normalizedStatus().name()),
                text(run.updatedAt() == null ? "Unavailable" : TIME_FORMAT.format(run.updatedAt())),
                text(elapsed(run)),
                text(String.valueOf(run.attemptCount())),
                text(run.modelCallCount() + " model · " + run.toolCallCount() + " tool")
            );
        }
        return group.withChild(table);
    }

    private Component summaryGrid(RunSummary summary, List<AttemptFact> attempts) {
        Instant started = attempts == null ? null : attempts.stream()
            .map(AttemptFact::startedAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant completed = attempts == null ? null : attempts.stream()
            .map(AttemptFact::completedAt).filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        return new Div().withClass("run-summary-grid")
            .withChild(fact("Source", label(summary.sourceKind())))
            .withChild(fact("Outcome", outcome(summary)))
            .withChild(fact("Started", started == null ? "Unavailable" : TIME_FORMAT.format(started)))
            .withChild(fact("Completed", completed == null ? "Unavailable" : TIME_FORMAT.format(completed)))
            .withChild(fact("Wall elapsed", duration(summary.wallElapsedMs())))
            .withChild(fact("Active elapsed", duration(summary.activeElapsedMs())))
            .withChild(fact("Completeness", summary.completeness() == null ? "Unavailable" : label(summary.completeness().name())));
    }

    private Component currentClaim(List<AttemptFact> attempts) {
        AttemptFact current = attempts == null || attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        Div section = new Div().withClass("run-detail-section").withChild(Header.H4("Current Claim"));
        if (current == null) {
            return section.withChild(state("Claim information is unavailable for this run.", "unavailable"));
        }
        return section.withChild(new Div().withClass("run-claim-summary")
            .withChild(fact("Attempt", "#" + current.attemptNumber()))
            .withChild(fact("Attempt status", label(current.status())))
            .withChild(fact("Holder", "Not exposed"))
            .withChild(fact("Lease details", "Not exposed")));
    }

    private Component attempts(List<AttemptFact> attempts) {
        Div section = new Div().withClass("run-detail-section").withChild(Header.H4("Attempts"));
        if (attempts == null || attempts.isEmpty()) {
            return section.withChild(state("Attempt history is unavailable for this run.", "unavailable"));
        }
        Table table = Table.create().withHeaders("#", "Status", "Started", "Completed", "Outcome")
            .withClass("data-table compact run-attempt-table");
        for (AttemptFact attempt : attempts) {
            table.addRow(
                text(String.valueOf(attempt.attemptNumber())),
                statusChip(attempt.status()),
                text(attempt.startedAt() == null ? "Unavailable" : TIME_FORMAT.format(attempt.startedAt())),
                text(attempt.completedAt() == null ? "Unavailable" : TIME_FORMAT.format(attempt.completedAt())),
                text(attempt.errorText() == null || attempt.errorText().isBlank() ? "No recorded failure" : "Failure recorded")
            );
        }
        return section.withChild(table);
    }

    private Component artifacts(RunSummary summary, List<ArtifactFact> artifacts) {
        Div section = new Div().withClass("run-detail-section").withChild(Header.H4("Artifacts"));
        if (artifacts == null || artifacts.isEmpty()) {
            return section.withChild(state("No run-local artifacts are recorded. This does not imply that legacy history is complete.", "empty"));
        }
        long published = artifacts.stream().filter(a -> "PUBLISHED".equals(a.publicationState())).count();
        long integrityFailed = artifacts.stream().filter(a -> "INTEGRITY_FAILED".equals(a.publicationState())).count();
        long unavailable = artifacts.size() - published - integrityFailed;
        section.withChild(new Div().withClass("run-artifact-counts")
            .withChild(chip("Published " + published, "ok"))
            .withChild(chip("Integrity failed " + integrityFailed, integrityFailed > 0 ? "error" : "neutral"))
            .withChild(chip("Other/unavailable " + unavailable, unavailable > 0 ? "partial" : "neutral")));
        Div recent = new Div().withClass("run-recent-artifacts");
        artifacts.stream().limit(5).forEach(artifact -> {
            Div row = new Div().withClass("run-artifact-row")
                .withChild(new HtmlTag("strong").withInnerText(artifact.logicalName()))
                .withChild(statusChip(artifact.publicationState()))
                .withChild(new HtmlTag("span").withInnerText(
                    artifact.publishedAt() == null ? "Publication time unavailable" : TIME_FORMAT.format(artifact.publishedAt())));
            if ("PUBLISHED".equals(artifact.publicationState())) {
                row.withChild(Button.create("Preview")
                    .withClass("button-secondary small")
                    .withAttribute("hx-get", "/artifacts/_preview/" + e(artifact.artifactId())
                        + "?workspaceKind=" + e(summary.workspaceType())
                        + "&workspaceKey=" + e(summary.workspaceKey()))
                    .withAttribute("hx-target", "#artifact-preview")
                    .withAttribute("hx-swap", "outerHTML"));
            } else if ("INTEGRITY_FAILED".equals(artifact.publicationState())) {
                row.withChild(new HtmlTag("span").withClass("run-artifact-suppressed")
                    .withInnerText("Preview suppressed"));
            }
            recent.withChild(row);
        });
        if (integrityFailed > 0) {
            recent.withChild(state("Integrity-failed artifact bytes are unavailable for preview or download.", "error"));
        }
        recent.withChild(new Div().withId("artifact-preview").withClass("run-artifact-preview")
            .withChild(state("Select a published artifact to load its authorized preview.", "empty")));
        return section.withChild(recent);
    }

    private Component detailHost(String message) {
        return new Div().withId(DETAIL_ID).withClass("run-detail-panel")
            .withChild(new Paragraph(message).withClass("run-state run-state-empty"));
    }

    private Component refreshButton(String agentId, int limit) {
        return Button.create("Refresh")
            .withClass("button-secondary small")
            .withAttribute("hx-get", "/agents/_detail/" + e(agentId) + "/runs?limit=" + limit + "&oob=true")
            .withAttribute("hx-target", "this")
            .withAttribute("hx-swap", "none");
    }

    private Component fact(String name, String value) {
        return new Div().withClass("run-summary-fact")
            .withChild(new HtmlTag("span").withInnerText(name))
            .withChild(new HtmlTag("strong").withInnerText(value));
    }

    private Component statusChip(String status) {
        String normalized = status == null ? "UNAVAILABLE" : status.toUpperCase(Locale.ROOT);
        String kind = switch (normalized) {
            case "SUCCEEDED", "COMPLETED", "PUBLISHED" -> "ok";
            case "FAILED", "CANCELLED", "INTEGRITY_FAILED", "TIMED_OUT" -> "error";
            case "ACTIVE", "RUNNING", "QUEUED" -> "active";
            case "PARTIAL", "INTERRUPTED", "STAGED" -> "partial";
            default -> "neutral";
        };
        return chip(label(normalized), kind);
    }

    private Component chip(String value, String kind) {
        return new HtmlTag("span").withClass("run-chip run-chip-" + kind).withInnerText(value);
    }

    private Component state(String message, String kind) {
        return new Paragraph(message).withClass("run-state run-state-" + kind);
    }

    private Component text(String value) {
        return new HtmlTag("span").withInnerText(value);
    }

    private String outcome(RunSummary summary) {
        return switch (summary.normalizedStatus()) {
            case SUCCEEDED -> "Succeeded";
            case FAILED, CANCELLED, TIMED_OUT, INTERRUPTED, ABANDONED -> label(summary.normalizedStatus().name());
            case PARTIAL -> "Partially reconciled";
            case ACTIVE -> "Not terminal";
            case UNAVAILABLE -> "Unavailable";
        };
    }

    private String elapsed(RunSummary summary) {
        if (summary.wallElapsedMs() == null && summary.activeElapsedMs() == null) return "Unavailable";
        return duration(summary.wallElapsedMs()) + " wall / " + duration(summary.activeElapsedMs()) + " active";
    }

    private String duration(Integer millis) {
        if (millis == null) return "Unavailable";
        Duration duration = Duration.ofMillis(millis);
        long seconds = duration.toSeconds();
        if (seconds < 1) return millis + " ms";
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60;
        return minutes + " m " + (seconds % 60) + " s";
    }

    private String groupKey(String sourceKind) {
        return switch (sourceKind == null ? "" : sourceKind.toUpperCase(Locale.ROOT)) {
            case "TASK", "PLAN" -> "Task runs";
            case "WORKFLOW" -> "Workflow runs";
            case "JOB" -> "Job runs";
            default -> "Legacy or unavailable runs";
        };
    }

    private String label(String value) {
        if (value == null || value.isBlank()) return "Unavailable";
        String normalized = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) return "Unavailable";
        return id.length() <= 14 ? id : id.substring(0, 12) + "…";
    }

    private String e(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
