package com.envpermit.schedule.google;

import com.envpermit.core.HttpTimeouts;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Google Calendar REST v3 + OAuth token endpoints with Spring RestClient, no Google SDK. Integration tests replace it; GoogleCalendarClientTest runs it against a local HTTP server. */
@Component
class GoogleCalendarClient implements CalendarPublisher {

    private static final String ZONE = "Asia/Seoul";
    private static final Duration EARLY = Duration.ofMinutes(5); // renew a bit before Google's expiry

    private record Access(String token, Instant expiresAt) {
    }

    private final RestClient http = HttpTimeouts.restClient();
    private final GoogleSettings settings;
    private final Clock clock;
    private final String oauthBase;
    private final String calUrl;
    private final Map<String, Access> accessTokens = new ConcurrentHashMap<>(); // by refresh token (M6)

    @Autowired
    GoogleCalendarClient(GoogleSettings settings, Clock clock) {
        this(settings, clock, "https://oauth2.googleapis.com", "https://www.googleapis.com");
    }

    /** Base URLs are parameters only so tests can point at a local server. */
    GoogleCalendarClient(GoogleSettings settings, Clock clock, String oauthBase, String apiBase) {
        this.settings = settings;
        this.clock = clock;
        this.oauthBase = oauthBase;
        this.calUrl = apiBase + "/calendar/v3/calendars";
    }

    @Override
    public String exchangeCode(String code) {
        JsonNode r = token(Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", settings.redirectUri));
        if (!r.hasNonNull("refresh_token")) {
            throw new IllegalStateException("Google이 refresh token을 주지 않았습니다. 다시 연결해 주세요.");
        }
        return r.get("refresh_token").asText();
    }

    @Override
    public String createCalendar(String refreshToken, String name) {
        return http.post().uri(calUrl).headers(h -> h.setBearerAuth(access(refreshToken)))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("summary", name, "timeZone", ZONE))
                .retrieve().body(JsonNode.class).get("id").asText();
    }

    @Override
    public Ref insert(String refreshToken, String calendarId, GoogleEvent e) {
        return ref(http.post().uri(calUrl + "/{c}/events", calendarId).headers(h -> h.setBearerAuth(access(refreshToken)))
                .contentType(MediaType.APPLICATION_JSON).body(body(e)).retrieve().body(JsonNode.class));
    }

    @Override
    public Ref patch(String refreshToken, String calendarId, String eventId, GoogleEvent e) {
        return ref(http.patch().uri(calUrl + "/{c}/events/{e}", calendarId, eventId)
                .headers(h -> h.setBearerAuth(access(refreshToken)))
                .contentType(MediaType.APPLICATION_JSON).body(body(e)).retrieve().body(JsonNode.class));
    }

    @Override
    public void delete(String refreshToken, String calendarId, String eventId) {
        try {
            http.delete().uri(calUrl + "/{c}/events/{e}", calendarId, eventId)
                    .headers(h -> h.setBearerAuth(access(refreshToken))).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND && ex.getStatusCode() != HttpStatus.GONE) throw ex;
        }
    }

    @Override
    public void deleteCalendar(String refreshToken, String calendarId) {
        http.delete().uri(calUrl + "/{c}", calendarId).headers(h -> h.setBearerAuth(access(refreshToken)))
                .retrieve().toBodilessEntity();
    }

    @Override
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        accessTokens.remove(refreshToken);
        http.post().uri(oauthBase + "/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().toBodilessEntity();
    }

    /** Cached per refresh token until shortly before it expires, so a sync run does not hit the token endpoint per event. */
    private String access(String refreshToken) {
        Instant now = clock.instant();
        Access cached = accessTokens.get(refreshToken);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.token();
        }
        JsonNode r = token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
        Access fresh = new Access(r.get("access_token").asText(),
                now.plusSeconds(r.path("expires_in").asLong(3600)).minus(EARLY));
        accessTokens.put(refreshToken, fresh);
        return fresh.token();
    }

    private JsonNode token(Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        form.add("client_id", settings.clientId);
        form.add("client_secret", settings.clientSecret);
        return http.post().uri(oauthBase + "/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JsonNode.class);
    }

    private static Map<String, Object> body(GoogleEvent e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("summary", e.summary());
        b.put("description", e.description());
        b.put("location", e.location());
        if (e.allDay()) { // Google's all-day end date is exclusive
            b.put("start", Map.of("date", e.start().toLocalDate().toString()));
            b.put("end", Map.of("date", e.end().toLocalDate().plusDays(1).toString()));
        } else {
            DateTimeFormatter f = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            b.put("start", Map.of("dateTime", e.start().format(f), "timeZone", ZONE));
            b.put("end", Map.of("dateTime", e.end().format(f), "timeZone", ZONE));
        }
        return b;
    }

    private static Ref ref(JsonNode n) {
        return new Ref(n.get("id").asText(), n.path("etag").asText(null));
    }
}
