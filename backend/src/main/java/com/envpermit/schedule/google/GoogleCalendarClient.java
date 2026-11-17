package com.envpermit.schedule.google;

import com.envpermit.core.HttpTimeouts;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Google Calendar REST v3 + OAuth token endpoints with Spring RestClient, no Google SDK. Never called in tests. */
@Component
class GoogleCalendarClient implements CalendarPublisher {

    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String CAL = "https://www.googleapis.com/calendar/v3/calendars";
    private static final String ZONE = "Asia/Seoul";

    private final RestClient http = HttpTimeouts.restClient();
    private final GoogleSettings settings;

    GoogleCalendarClient(GoogleSettings settings) {
        this.settings = settings;
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
        return http.post().uri(CAL).headers(h -> h.setBearerAuth(access(refreshToken)))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("summary", name, "timeZone", ZONE))
                .retrieve().body(JsonNode.class).get("id").asText();
    }

    @Override
    public Ref insert(String refreshToken, String calendarId, GoogleEvent e) {
        return ref(http.post().uri(CAL + "/{c}/events", calendarId).headers(h -> h.setBearerAuth(access(refreshToken)))
                .contentType(MediaType.APPLICATION_JSON).body(body(e)).retrieve().body(JsonNode.class));
    }

    @Override
    public Ref patch(String refreshToken, String calendarId, String eventId, GoogleEvent e) {
        return ref(http.patch().uri(CAL + "/{c}/events/{e}", calendarId, eventId)
                .headers(h -> h.setBearerAuth(access(refreshToken)))
                .contentType(MediaType.APPLICATION_JSON).body(body(e)).retrieve().body(JsonNode.class));
    }

    @Override
    public void delete(String refreshToken, String calendarId, String eventId) {
        try {
            http.delete().uri(CAL + "/{c}/events/{e}", calendarId, eventId)
                    .headers(h -> h.setBearerAuth(access(refreshToken))).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND && ex.getStatusCode() != HttpStatus.GONE) throw ex;
        }
    }

    @Override
    public void deleteCalendar(String refreshToken, String calendarId) {
        http.delete().uri(CAL + "/{c}", calendarId).headers(h -> h.setBearerAuth(access(refreshToken)))
                .retrieve().toBodilessEntity();
    }

    @Override
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        http.post().uri("https://oauth2.googleapis.com/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().toBodilessEntity();
    }

    // shortcut: a fresh access token per call (no cache); fine at one job per minute, cache it if volume grows
    private String access(String refreshToken) {
        return token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken))
                .get("access_token").asText();
    }

    private JsonNode token(Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        form.add("client_id", settings.clientId);
        form.add("client_secret", settings.clientSecret);
        return http.post().uri(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
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
