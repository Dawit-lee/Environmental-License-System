package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * H4: behind the Cloudflare tunnel every request comes from a local proxy. Tomcat (server.forward-headers-strategy)
 * must take the client from X-Forwarded-For, so one guesser blocks only itself. Needs a real server: MockMvc skips Tomcat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.jwt.secret=test-secret-test-secret-test-secret-0123456789", "app.scheduling=false"})
class ClientIpTest extends IntegrationTest {

    @LocalServerPort int port;
    @Autowired ShareRequestFilter filter;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        filter.reset();
    }

    @Test
    void the_share_guess_limit_is_per_forwarded_client_not_per_proxy() throws Exception {
        for (int i = 0; i < ShareRequestFilter.FAILED_LIMIT_PER_MINUTE; i++) {
            assertThat(guess("203.0.113.1")).isEqualTo(404);
        }
        assertThat(guess("203.0.113.1")).isEqualTo(429);
        // a forged entry on the left does not help: the proxy appends the real client on the right
        assertThat(guess("198.51.100.7, 203.0.113.1")).isEqualTo(429);
        assertThat(guess("203.0.113.2")).isEqualTo(404); // another visitor through the same tunnel
    }

    private int guess(String forwardedFor) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/share/" + ShareController.newToken()))
                .header("X-Forwarded-For", forwardedFor).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
