package io.mindspice.magenta2.ai.chat.rendering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMarkdownRendererTest {

    private final ChatMarkdownRenderer renderer = new ChatMarkdownRenderer();

    @Test
    void rendersGithubFlavoredTables() {
        String html = renderer.render("""
            | Day | Timeframe | Activity |
            | --- | --- | --- |
            | Mon | 1:00 PM | Meds reminder |
            """);

        assertThat(html).contains("<table>");
        assertThat(html).contains("<th>Day</th>");
        assertThat(html).contains("<td>Mon</td>");
    }

    @Test
    void sanitizesUnsafeHtmlWhilePreservingTables() {
        String html = renderer.render("""
            | Safe |
            | --- |
            | <script>alert('x')</script> |
            """);

        assertThat(html).contains("<table>");
        assertThat(html).doesNotContain("<script>");
    }
}
