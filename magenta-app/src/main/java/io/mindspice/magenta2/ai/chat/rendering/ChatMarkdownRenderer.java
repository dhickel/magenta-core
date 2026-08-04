package io.mindspice.magenta2.ai.chat.rendering;

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatMarkdownRenderer {

    private static final PolicyFactory HTML_POLICY = new HtmlPolicyBuilder()
        .allowElements(
            "p", "br", "hr",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "em", "code", "pre", "blockquote",
            "ul", "ol", "li",
            "table", "thead", "tbody", "tr", "th", "td",
            "a"
        )
        .allowAttributes("href", "title").onElements("a")
        .allowUrlProtocols("http", "https", "mailto")
        .requireRelNofollowOnLinks()
        .toFactory();

    private static final List<Extension> MARKDOWN_EXTENSIONS = List.of(TablesExtension.create());

    private final Parser parser = Parser.builder()
        .extensions(MARKDOWN_EXTENSIONS)
        .build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
        .extensions(MARKDOWN_EXTENSIONS)
        .build();

    public String render(String markdown) {
        String source = markdown == null ? "" : markdown;
        String rendered = renderer.render(parser.parse(source));
        return HTML_POLICY.sanitize(rendered);
    }
}
