package com.envpermit.schedule.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.envpermit.schedule.google.CalendarPublisher.GoogleEvent;
import com.envpermit.support.TestClock;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** The real Google adapter against a local stand-in server (M14): token caching (M6), request shape, error paths. */
class GoogleCalendarClientTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-05T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int eventStatus = 200;
    private GoogleCalendarClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            calls.add(ex.getRequestMethod() + " " + path + (auth == null ? "" : " " + auth));
            int status = 200;
            String reply;
            if (path.equals("/token")) {
                reply = "{\"access_token\":\"at-" + calls.stream().filter(c -> c.contains("/token")).count()
                        + "\",\"expires_in\":3600}";
            } else if (path.endsWith("/events")) {
                status = eventStatus;
                reply = status == 200 ? "{\"id\":\"ev1\",\"etag\":\"\\\"e1\\\"\"}" : "{\"error\":\"x\"}";
            } else {
                status = 404;
                reply = "{}";
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        GoogleSettings settings = new GoogleSettings("cid", "secret", "", "http://localhost");
        client = new GoogleCalendarClient(settings, clock, base, base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static GoogleEvent event() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 10, 0, 0);
        return new GoogleEvent("착수보고", null, null, t, t, true);
    }

    @Test
    void one_access_token_serves_many_calls_until_it_is_about_to_expire() {
        for (int i = 0; i < 3; i++) {
            assertThat(client.insert("rt-1", "cal", event()).id()).isEqualTo("ev1");
        }
        assertThat(calls.stream().filter(c -> c.startsWith("POST /token"))).hasSize(1);
        assertThat(calls).filteredOn(c -> c.contains("/events")).allMatch(c -> c.endsWith("Bearer at-1"));

        clock.advance(Duration.ofMinutes(56)); // 60 min lifetime, renewed 5 min early
        client.insert("rt-1", "cal", event());
        assertThat(calls.stream().filter(c -> c.startsWith("POST /token"))).hasSize(2);
        assertThat(calls.get(calls.size() - 1)).endsWith("Bearer at-2");
    }

    @Test
    void each_refresh_token_gets_its_own_access_token() {
        client.insert("rt-1", "cal", event());
        client.insert("rt-2", "cal", event());
        assertThat(calls.stream().filter(c -> c.startsWith("POST /token"))).hasSize(2);
    }

    @Test
    void google_errors_surface_as_exceptions_for_the_sync_retry() {
        eventStatus = 403;
        assertThatThrownBy(() -> client.insert("rt-1", "cal", event())).isInstanceOf(HttpClientErrorException.class);
        eventStatus = 503;
        assertThatThrownBy(() -> client.insert("rt-1", "cal", event())).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void deleting_an_event_google_no_longer_has_is_fine() {
        client.delete("rt-1", "cal", "gone"); // 404 from the stand-in
        assertThat(calls).anyMatch(c -> c.startsWith("DELETE /calendar/v3/calendars/cal/events/gone"));
    }
}
