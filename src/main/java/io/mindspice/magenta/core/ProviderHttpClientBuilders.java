package io.mindspice.magenta.core;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Builds bounded JDK-backed HTTP clients for providers without timeout options. */
final class ProviderHttpClientBuilders {

    private ProviderHttpClientBuilders() {
    }

    static ClientBuilders forTimeout(Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();

        JdkClientHttpRequestFactory restRequestFactory =
            new JdkClientHttpRequestFactory(httpClient);
        restRequestFactory.setReadTimeout(timeout);

        JdkClientHttpConnector webConnector = new JdkClientHttpConnector(httpClient);
        webConnector.setReadTimeout(timeout);

        return new ClientBuilders(
            RestClient.builder().requestFactory(restRequestFactory),
            WebClient.builder().clientConnector(webConnector));
    }

    record ClientBuilders(RestClient.Builder restClient, WebClient.Builder webClient) {
    }
}
