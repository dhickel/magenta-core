package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;

import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.ArtifactFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.AttemptFact;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.NormalizedRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunAudit;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunSummary;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunSummaryCompleteness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunOperationsFragmentsTest {

    private final RunOperationsFragments fragments = new RunOperationsFragments();

    @Test
    void refreshUsesBoundedOobHostAndExpansionStaysAgentScoped() {
        String html = fragments.groups("agent one", List.of(summary()), 25, true).render();

        assertThat(html).contains("id=\"agent-run-groups\"")
            .contains("hx-swap-oob=\"outerHTML\"")
            .contains("/agents/_detail/agent%20one/runs/run-1")
            .contains("oob=true")
            .contains("hx-swap=\"none\"");
    }

    @Test
    void detailShowsTruthfulStatesAndNeverRendersClaimOrFailureSecrets() {
        RunAudit audit = new RunAudit(
            "run-1",
            summary(),
            List.of(new AttemptFact(1, "FAILED", Instant.EPOCH, Instant.EPOCH.plusSeconds(2),
                "claim-token=/home/operator/private")),
            List.of(),
            List.of(),
            List.of(
                new ArtifactFact("artifact-ok", "report.md", "PUBLISHED", "digest", 1,
                    Instant.EPOCH, "agent", "agent one"),
                new ArtifactFact("artifact-bad", "broken.bin", "INTEGRITY_FAILED", "digest", 1,
                    Instant.EPOCH, "agent", "agent one")),
            List.of()
        );

        String html = fragments.detail(audit).render();

        assertThat(html).contains("Current Claim", "Not exposed", "Failure recorded")
            .contains("/artifacts/_preview/artifact-ok?workspaceKind=agent&amp;workspaceKey=agent%20one")
            .contains("Preview suppressed", "Integrity-failed artifact bytes are unavailable")
            .contains("/agents/_detail/agent%20one/runs/parent-run")
            .doesNotContain("claim-token", "/home/operator", "digest");
    }

    private RunSummary summary() {
        return new RunSummary(
            "run-1", NormalizedRunStatus.PARTIAL, 1, 2, 3, 2_000, 1_500,
            RunSummaryCompleteness.PARTIAL, "agent", "agent one", "TASK", "parent-run", "RETRY",
            Instant.EPOCH, Instant.EPOCH.plusSeconds(2));
    }
}
