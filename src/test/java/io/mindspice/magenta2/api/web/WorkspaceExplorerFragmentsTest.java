package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceExplorerFragmentsTest {

    @Test
    void shellUsesNeutralStableTargetsAndServerDerivedTypeKeyUrls() {
        WorkspaceExplorerService.Entry entry = new WorkspaceExplorerService.Entry(
            "note.md", "notes/note.md", false, true, 12, Instant.EPOCH, Instant.EPOCH,
            "Markdown", "12 B", WorkspaceExplorerService.ViewerKind.MARKDOWN, List.of(),
            true, true, true, true, true, true
        );
        WorkspaceExplorerService.DirectoryListing listing =
            new WorkspaceExplorerService.DirectoryListing("notes", List.of(entry));

        String html = WorkspaceExplorerFragments.shell(listing, entry, entry.path(), "agent", "agent-1");

        assertThat(html).contains("id=\"workspace-file-explorer-shell\"")
            .contains("id=\"workspace-file-list-region\"")
            .contains("id=\"workspace-file-inspector\"")
            .contains("id=\"workspace-file-modal\"")
            .contains("/api/workspace/agent/agent-1/")
            .contains("workspace-file-")
            .doesNotContain("/avatar", "avatar-", "work-area", "workArea", "canonicalIdentity", "workspaceRoot");
        assertThat(count(html, "id=\"workspace-file-list-region\"")).isEqualTo(1);
        assertThat(count(html, "id=\"workspace-file-inspector\"")).isEqualTo(1);
        assertThat(count(html, "id=\"workspace-file-modal\"")).isEqualTo(1);
    }

    @Test
    void mutationResponseUsesStableOutOfBandListInspectorAndModalTargets() {
        WorkspaceExplorerService.DirectoryListing listing = new WorkspaceExplorerService.DirectoryListing(".", List.of());

        String html = WorkspaceExplorerFragments.mutationResponse(listing, null, null, "Saved", "user", "docs");

        assertThat(html).contains("id=\"workspace-file-modal\" hx-swap-oob=\"true\"")
            .contains("id=\"workspace-file-list-region\"")
            .contains("id=\"workspace-file-inspector\"");
        assertThat(count(html, "hx-swap-oob=\"true\"")).isEqualTo(3);
    }

    @Test
    void markdownFailureKeepsEscapedRawTextAndVisibleError() {
        String html = WorkspaceExplorerFragments.renderedMarkdownForTest("<script>alert(1)</script>", ignored -> {
            throw new IllegalStateException("renderer failed");
        });

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
            .contains("Markdown render failed")
            .doesNotContain("<script>");
    }

    private int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
