package com.envpermit.core;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** RestClient with connect and read timeouts: a hung remote must never pin a scheduler thread (H3). */
public final class HttpTimeouts {

    public static final Duration CONNECT = Duration.ofSeconds(5);
    public static final Duration READ = Duration.ofSeconds(30);

    private HttpTimeouts() {
    }

    public static RestClient restClient() {
        return restClient(CONNECT, READ);
    }

    public static RestClient restClient(Duration connect, Duration read) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(factory).build();
    }
}
