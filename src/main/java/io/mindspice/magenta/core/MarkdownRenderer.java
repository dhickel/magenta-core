package io.mindspice.magenta.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional Markdown-to-HTML renderer with graceful degradation.
 *
 * <p>At construction time, the renderer probes the classpath for
 * {@code org.commonmark.parser.Parser}. If commonmark is absent,
 * {@link #render(String)} returns the input unmodified and logs a
 * one-time WARN. When commonmark is available, parsed HTML is
 * optionally sanitized through owasp-java-html-sanitizer (also
 * probed at construction time).</p>
 *
 * <p>All direct references to optional libraries are confined to
 * lazily loaded inner classes so that {@code MarkdownRenderer}
 * itself loads successfully regardless of which optional
 * dependencies are present at runtime.</p>
 */
public final class MarkdownRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(MarkdownRenderer.class);

    private static volatile boolean warned = false;

    private final boolean commonmarkAvailable;
    private final CommonmarkDelegate delegate;
    private final SanitizerDelegate sanitizer;

    public MarkdownRenderer() {
        boolean cmAvailable = false;
        CommonmarkDelegate d = null;
        SanitizerDelegate s = null;

        try {
            Class.forName("org.commonmark.parser.Parser");
            cmAvailable = true;
        } catch (ClassNotFoundException e) {
            if (!warned) {
                warned = true;
                LOG.warn("commonmark not on classpath; MarkdownRenderer.render() will return input unmodified");
            }
        }

        if (cmAvailable) {
            d = new CommonmarkDelegate();
        }

        try {
            Class.forName("org.owasp.html.PolicyFactory");
            s = new SanitizerDelegate();
        } catch (ClassNotFoundException e) {
            // silent — sanitization is best-effort
        }

        this.commonmarkAvailable = cmAvailable;
        this.delegate = d;
        this.sanitizer = s;
    }

    /**
     * Render Markdown to safe HTML.
     *
     * @param markdown raw Markdown string (may be null)
     * @return sanitized HTML when commonmark is on the classpath;
     *         otherwise the input string unmodified
     */
    public String render(String markdown) {
        if (!commonmarkAvailable || delegate == null) {
            return markdown == null ? "" : markdown;
        }

        String source = markdown == null ? "" : markdown;

        try {
            String html = delegate.render(source);
            if (sanitizer != null) {
                return sanitizer.sanitize(html);
            }
            return html;
        } catch (Exception e) {
            LOG.warn("Markdown rendering failed, returning input unmodified", e);
            return source;
        }
    }

    // ── lazy delegates: only loaded after classpath probe succeeds ──

    /** Confined to method bodies; never appears as field or signature type. */
    private static final class CommonmarkDelegate {
        private final org.commonmark.parser.Parser parser;
        private final org.commonmark.renderer.html.HtmlRenderer renderer;

        @SuppressWarnings("unchecked")
        CommonmarkDelegate() {
            java.util.List<org.commonmark.Extension> extensions = java.util.List.of();
            try {
                // GFM tables extension — probe via reflection to avoid compile-time dependency
                Class<?> tablesExtClass = Class.forName("org.commonmark.ext.gfm.tables.TablesExtension");
                Object tablesExt = tablesExtClass.getMethod("create").invoke(null);
                extensions = java.util.List.of((org.commonmark.Extension) tablesExt);
            } catch (Exception ignored) {
                // tables extension not on classpath — proceed without it
            }
            this.parser = org.commonmark.parser.Parser.builder()
                .extensions(extensions)
                .build();
            this.renderer = org.commonmark.renderer.html.HtmlRenderer.builder()
                .extensions(extensions)
                .build();
        }

        String render(String markdown) {
            return renderer.render(parser.parse(markdown));
        }
    }

    /** Confined to method bodies; never appears as field or signature type. */
    private static final class SanitizerDelegate {
        private final org.owasp.html.PolicyFactory policy;

        SanitizerDelegate() {
            this.policy = new org.owasp.html.HtmlPolicyBuilder()
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
        }

        String sanitize(String html) {
            return policy.sanitize(html);
        }
    }
}
