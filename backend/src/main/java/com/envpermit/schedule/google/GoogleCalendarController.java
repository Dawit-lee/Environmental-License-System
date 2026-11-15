package com.envpermit.schedule.google;

import static com.envpermit.core.project.ProjectAccess.userId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 내 Google 캘린더 연결 (설계 §6.4). Opt-in per user, only while the company allows it. Feature off (env keys
 * missing) = every route 404. The callback is permitAll: Google redirects the browser there without our token,
 * so the one-time state (10 min) identifies the user.
 */
@RestController
class GoogleCalendarController {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarController.class);
    static final String CALENDAR_NAME = "인허가 일정";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final GoogleSettings settings;
    private final CalendarPublisher google;
    private final Clock clock;

    GoogleCalendarController(JdbcTemplate jdbc, GoogleSettings settings, CalendarPublisher google, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.google = google;
        this.clock = clock;
    }

    record Status(boolean companyEnabled, boolean connected, Instant connectedAt, Instant lastSyncedAt,
                  String lastError, boolean projectMilestones, boolean tasks) {
    }

    record Targets(@NotNull Boolean projectMilestones, @NotNull Boolean tasks) {
    }

    record ConnectUrl(String url) {
    }

    @GetMapping("/api/me/google-calendar")
    Status status(@AuthenticationPrincipal Jwt jwt) {
        requireFeature();
        long uid = userId(jwt);
        boolean company = companyEnabled(uid);
        return jdbc.query("""
                SELECT connected_at, last_synced_at, last_error, sync_project_milestones, sync_tasks
                FROM user_google_link WHERE user_id = ?""",
                (rs, n) -> new Status(company, true, rs.getTimestamp(1).toInstant(),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5)), uid)
                .stream().findFirst().orElse(new Status(company, false, null, null, null, false, false));
    }

    /** The Google consent URL; the page navigates there (a plain link could not carry our bearer token). */
    @GetMapping("/api/me/google-calendar/connect")
    @Transactional
    ConnectUrl connect(@AuthenticationPrincipal Jwt jwt) {
        requireFeature();
        long uid = userId(jwt);
        if (!companyEnabled(uid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회사에서 Google 캘린더 연결을 허용하지 않았습니다.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        jdbc.update("DELETE FROM google_oauth_state WHERE user_id = ? OR created_at < ?",
                uid, Timestamp.from(clock.instant().minus(STATE_TTL)));
        jdbc.update("INSERT INTO google_oauth_state (state, user_id, created_at) VALUES (?, ?, ?)",
                state, uid, Timestamp.from(clock.instant()));
        return new ConnectUrl(UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", settings.clientId)
                .queryParam("redirect_uri", settings.redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", GoogleSettings.SCOPE)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode().build().toUriString());
    }

    /** Google → here. Bad or used state = 400. Then back to the 회사 달력 page with ?google=connected|denied|failed. */
    @GetMapping("/api/me/google-calendar/callback")
    @Transactional
    ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam String state,
                                  @RequestParam(required = false) String error) {
        requireFeature();
        List<Long> owner = jdbc.queryForList(
                "DELETE FROM google_oauth_state WHERE state = ? AND created_at >= ? RETURNING user_id", Long.class,
                state, Timestamp.from(clock.instant().minus(STATE_TTL)));
        if (owner.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "연결 요청이 만료되었거나 올바르지 않습니다. 다시 시도해 주세요.");
        }
        long uid = owner.get(0);
        if (error != null || code == null || !companyEnabled(uid)) {
            return back("denied");
        }
        try {
            String refresh = google.exchangeCode(code);
            String calendarId = google.createCalendar(refresh, CALENDAR_NAME);
            jdbc.update("""
                    INSERT INTO user_google_link (user_id, encrypted_refresh_token, calendar_id, connected_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                        calendar_id = EXCLUDED.calendar_id, connected_at = EXCLUDED.connected_at, last_error = NULL""",
                    uid, settings.encrypt(refresh), calendarId, Timestamp.from(clock.instant()));
            jdbc.update("DELETE FROM calendar_sync_link WHERE user_id = ?", uid); // a new calendar starts empty
            enqueueAll(uid);
            return back("connected");
        } catch (RuntimeException e) {
            log.warn("Google calendar connect failed for user {}: {}", uid, e.toString());
            return back("failed");
        }
    }

    @PutMapping("/api/me/google-calendar")
    @Transactional
    Status setTargets(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Targets req) {
        requireFeature();
        long uid = userId(jwt);
        if (jdbc.update("UPDATE user_google_link SET sync_project_milestones = ?, sync_tasks = ? WHERE user_id = ?",
                req.projectMilestones(), req.tasks(), uid) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Google 캘린더가 연결되어 있지 않습니다.");
        }
        enqueueAll(uid);
        return status(jwt);
    }

    /** Revokes the token and forgets everything. deleteCalendar: also remove "인허가 일정" from Google (user's choice). */
    @DeleteMapping("/api/me/google-calendar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void disconnect(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean deleteCalendar) {
        requireFeature();
        long uid = userId(jwt);
        List<String[]> link = jdbc.query("SELECT encrypted_refresh_token, calendar_id FROM user_google_link WHERE user_id = ?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, uid);
        if (link.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Google 캘린더가 연결되어 있지 않습니다.");
        }
        try { // the rows go regardless: a failed revoke must not keep the user connected
            String refresh = settings.decrypt(link.get(0)[0]);
            if (deleteCalendar) google.deleteCalendar(refresh, link.get(0)[1]);
            google.revoke(refresh);
        } catch (RuntimeException e) {
            log.warn("Google revoke/delete failed for user {}: {}", uid, e.toString());
        }
        jdbc.update("DELETE FROM calendar_sync_outbox WHERE user_id = ?", uid);
        jdbc.update("DELETE FROM calendar_sync_link WHERE user_id = ?", uid);
        jdbc.update("DELETE FROM user_google_link WHERE user_id = ?", uid);
    }

    /** Queue every candidate source and every source already in Google; the job keeps or removes each. */
    private void enqueueAll(long uid) {
        jdbc.update("""
                INSERT INTO calendar_sync_outbox (user_id, source_kind, source_id, op)
                SELECT ?, kind, id, 'UPSERT' FROM (
                    SELECT 'EVENT' AS kind, event_id AS id FROM project_event_attendee WHERE user_id = ?
                    UNION SELECT 'ITEM', i.id FROM schedule_item i
                          JOIN project_member m ON m.project_id = i.project_id AND m.user_id = ?
                          WHERE i.deleted_at IS NULL
                    UNION SELECT source_kind, source_id FROM calendar_sync_link WHERE user_id = ?) s
                ON CONFLICT (user_id, source_kind, source_id) DO NOTHING""", uid, uid, uid, uid);
    }

    private boolean companyEnabled(long uid) {
        return jdbc.queryForList("""
                SELECT c.google_sync_enabled FROM users u JOIN company c ON c.id = u.company_id WHERE u.id = ?""",
                Boolean.class, uid).contains(true);
    }

    private void requireFeature() {
        if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Void> back(String result) {
        String base = settings.redirectUri.substring(0, settings.redirectUri.indexOf("/api/"));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(base + "/calendar?google=" + result)).build();
    }
}
