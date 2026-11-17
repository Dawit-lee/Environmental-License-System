package com.envpermit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** H3: a remote that accepts the connection but never answers must fail after the read timeout, not hang. */
class HttpTimeoutsTest {

    @Test
    void a_silent_server_times_out() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(10_000); // never answer
                } catch (Exception ignoredToo) {
                    // closed by the test
                }
            });
            t.setDaemon(true);
            t.start();
            long started = System.nanoTime();
            assertThatThrownBy(() -> HttpTimeouts.restClient(Duration.ofSeconds(1), Duration.ofMillis(300)).get()
                    .uri("http://127.0.0.1:" + server.getLocalPort() + "/").retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
