package io.mindspice.magenta2.ai.chat.tool.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.WebSearchConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWebToolServiceTest {
    private static final String PUBLIC_BASE_URL = "http://93.184.216.34";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchesSearxngJsonResults() throws Exception {
        startServer((exchange) -> {
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/search");
            respond(exchange, "application/json", """
                {
                  "results": [
                    {"title":"One","url":"https://example.com/one","content":"First result","engine":"duckduckgo","publishedDate":"2026-01-01"},
                    {"title":"Two","url":"https://example.com/two","content":"Second result","engine":"brave"}
                  ]
                }
                """);
        });
        AgentWebToolService service = service(true);

        AgentWebToolService.WebSearchResult result = service.search("magenta test", 5);

        assertThat(result.query()).isEqualTo("magenta test");
        assertThat(result.results()).hasSize(2);
        assertThat(result.results().getFirst().url()).isEqualTo("https://example.com/one");
        assertThat(result.results().getFirst().engine()).isEqualTo("duckduckgo");
    }

    @Test
    void searchRequiresEnabledConfig() {
        AgentWebToolService service = service(false);

        assertThatThrownBy(() -> service.search("query", 5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not enabled");
    }

    @Test
    void webToolsReturnStructuredErrorWhenSearchDependencyIsUnavailable() {
        AgentWebToolService service = service(true);
        AgentWebTools tools = new AgentWebTools(service, new ObjectMapper());

        String result = tools.search("query", 5);

        assertThat(result)
            .contains("\"error\":\"web_search failed\"")
            .contains("\"message\"");
    }

    @Test
    void fetchExtractsHtmlTextAndTruncates() throws Exception {
        startServer((exchange) -> respond(exchange, "text/html", """
            <html><head><title>Readable Page</title><script>bad()</script></head>
            <body><nav>skip nav</nav><main><h1>Hello</h1><p>Alpha beta gamma delta.</p></main></body></html>
            """));
        AgentWebToolService service = service(true);

        AgentWebToolService.WebFetchResult result = service.fetch(baseUrl() + "/page", 1_000);

        assertThat(result.title()).isEqualTo("Readable Page");
        assertThat(result.text()).contains("Hello").contains("Alpha beta gamma delta");
        assertThat(result.text()).doesNotContain("skip nav").doesNotContain("bad()");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void fetchRejectsUnsupportedSchemes() {
        AgentWebToolService service = service(true);

        assertThatThrownBy(() -> service.fetch("file:///etc/passwd", 1_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http and https");
    }

    @Test
    void fetchRejectsPrivateHostsInProductionPath() {
        FakeHttpClient client = new FakeHttpClient(Map.of());
        AgentWebToolService service = new AgentWebToolService(config(true), new ObjectMapper(), client, false);

        assertThatThrownBy(() -> service.fetch("http://127.0.0.1:8080/private", 1_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("public web hosts");
        assertThat(client.requestUris()).isEmpty();
    }

    @Test
    void fetchRejectsPublicToPrivateRedirects() {
        FakeHttpClient client = new FakeHttpClient(Map.of(
            PUBLIC_BASE_URL + "/start", FakeResponse.redirect("http://127.0.0.1/private")
        ));
        AgentWebToolService service = new AgentWebToolService(config(true), new ObjectMapper(), client, false);

        assertThatThrownBy(() -> service.fetch(PUBLIC_BASE_URL + "/start", 1_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("public web hosts");
        assertThat(client.requestUris()).containsExactly(URI.create(PUBLIC_BASE_URL + "/start"));
    }

    @Test
    void fetchRejectsRedirectLoops() {
        FakeHttpClient client = new FakeHttpClient(Map.of(
            PUBLIC_BASE_URL + "/loop", FakeResponse.redirect("/loop")
        ));
        AgentWebToolService service = new AgentWebToolService(config(true), new ObjectMapper(), client, false);

        assertThatThrownBy(() -> service.fetch(PUBLIC_BASE_URL + "/loop", 1_000))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("maximum redirect count");
        assertThat(client.requestUris()).hasSize(6);
    }

    @Test
    void fetchFollowsValidPublicRedirects() throws Exception {
        FakeHttpClient client = new FakeHttpClient(Map.of(
            PUBLIC_BASE_URL + "/start", FakeResponse.redirect("/final"),
            PUBLIC_BASE_URL + "/final", FakeResponse.ok("""
                <html><head><title>Redirected</title></head><body><main>Public final page.</main></body></html>
                """)
        ));
        AgentWebToolService service = new AgentWebToolService(config(true), new ObjectMapper(), client, false);

        AgentWebToolService.WebFetchResult result = service.fetch(PUBLIC_BASE_URL + "/start", 1_000);

        assertThat(result.url()).isEqualTo(PUBLIC_BASE_URL + "/final");
        assertThat(result.title()).isEqualTo("Redirected");
        assertThat(result.text()).contains("Public final page.");
        assertThat(client.requestUris()).containsExactly(
            URI.create(PUBLIC_BASE_URL + "/start"),
            URI.create(PUBLIC_BASE_URL + "/final")
        );
    }

    @Test
    void fetchRejectsInvalidRedirectTargets() {
        FakeHttpClient client = new FakeHttpClient(Map.of(
            PUBLIC_BASE_URL + "/start", FakeResponse.redirect("http://")
        ));
        AgentWebToolService service = new AgentWebToolService(config(true), new ObjectMapper(), client, false);

        assertThatThrownBy(() -> service.fetch(PUBLIC_BASE_URL + "/start", 1_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("redirect target is invalid");
        assertThat(client.requestUris()).containsExactly(URI.create(PUBLIC_BASE_URL + "/start"));
    }

    private AgentWebToolService service(boolean enabled) {
        return new AgentWebToolService(config(enabled), new ObjectMapper(), HttpClient.newHttpClient());
    }

    private AiConfig config(boolean enabled) {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            new WebSearchConfig(enabled, "searxng", enabled && server != null ? baseUrl() : "http://localhost:1"),
            Map.of(),
            Map.of("magenta", new AgentConfig("model", "prompt", List.of()))
        );
        return config;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (AssertionError error) {
                byte[] body = error.getMessage().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record FakeResponse(int statusCode, Map<String, List<String>> headers, String body) {
        static FakeResponse ok(String body) {
            return new FakeResponse(200, Map.of("Content-Type", List.of("text/html; charset=utf-8")), body);
        }

        static FakeResponse redirect(String location) {
            return new FakeResponse(302, Map.of("Location", List.of(location)), "");
        }
    }

    private static class FakeHttpClient extends HttpClient {
        private final Map<String, FakeResponse> responses;
        private final List<URI> requestUris = new ArrayList<>();

        private FakeHttpClient(Map<String, FakeResponse> responses) {
            this.responses = responses;
        }

        List<URI> requestUris() {
            return requestUris;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("Default SSL context is unavailable", exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requestUris.add(request.uri());
            FakeResponse response = responses.get(request.uri().toString());
            if (response == null) {
                throw new AssertionError("No fake response for " + request.uri());
            }
            @SuppressWarnings("unchecked")
            T body = (T) new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8));
            return new SimpleHttpResponse<>(request, response.statusCode(), response.headers(), body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not used"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not used"));
        }
    }

    private record SimpleHttpResponse<T>(
        HttpRequest request,
        int statusCode,
        Map<String, List<String>> headerValues,
        T body
    ) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headerValues, (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
