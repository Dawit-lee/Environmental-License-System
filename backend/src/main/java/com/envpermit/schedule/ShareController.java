package com.envpermit.schedule;

import static com.envpermit.core.project.ProjectAccess.userId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 협력사 읽기 전용 공유 (설계 §6.3) and 개인 달력 구독 (§6.4 대안). Partners never log in: a link is a 32-byte random
 * token; only its SHA-256 is stored and looked up (no string compare of secrets). Unknown, expired and revoked tokens
 * all get the same empty 404. Public reads export a fixed field list only — 제목·날짜·장소·분야·협력사명 — of one
 * project and the link's disciplines; the scope is applied in SQL through the link row itself. The public routes are
 * GET only (SecurityConfig); {@link ShareRequestFilter} adds the no-cache/noindex headers and the rate limit.
 */
@RestController
class ShareController {

    static final int ICS_PAST_MONTHS = 6;

    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 365;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ShareController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** disciplineIds: null = every discipline incl. 공통; else at least one. expiresInDays: 1–365, default 90. */
    record LinkRequest(List<Long> disciplineIds, Boolean includeEvents, Integer expiresInDays) {
    }

    record LinkView(long id, List<Long> disciplineIds, boolean includeEvents, Instant expiresAt, Instant revokedAt,
                    String createdByName, Instant createdAt, Instant lastAccessedAt, boolean active) {
    }

    /** The only time the token leaves the server: paths, the client adds its own origin. */
    record CreatedLink(LinkView link, String path, String icsPath) {
    }

    record SharedItem(String kind, String title, LocalDate startDate, LocalDate endDate, String disciplineName,
                      String partnerName) {
    }

    record SharedEvent(String title, LocalDateTime startsAt, LocalDateTime endsAt, boolean allDay, String location,
                       String disciplineName) {
    }

    record SharedSchedule(String projectName, Instant expiresAt, List<SharedItem> items, List<SharedEvent> events) {
    }

    record CalendarTokenView(String icsPath) {
    }

    /** A row for the ICS UID next to what is exported; the id is never in the web response. */
    private record Row<T>(long id, T view) {
    }

    private record Link(long id, String projectName, Instant expiresAt) {
    }

    // --- management (PM · EM · 프로젝트 관리자) -------------------------------------------------------------

    @PostMapping("/api/projects/{pid}/share-links")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedLink create(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @RequestBody LinkRequest req) {
        manager(pid, jwt);
        int days = req.expiresInDays() == null ? DEFAULT_DAYS : req.expiresInDays();
        if (days < 1 || days > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료는 1~365일로 지정해 주세요.");
        }
        List<Long> ids = req.disciplineIds() == null ? null : req.disciplineIds().stream().distinct().toList();
        if (ids != null && (ids.isEmpty() || ids.contains(null) || jdbc.queryForObject("""
                SELECT count(*) FROM discipline d JOIN project p ON p.company_id = d.company_id
                WHERE p.id = ? AND d.id = ANY (?::bigint[])""", Integer.class, pid, pgArray(ids)) != ids.size())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회사의 분야를 하나 이상 골라 주세요.");
        }
        String token = newToken();
        Instant now = clock.instant();
        long id = jdbc.queryForObject("""
                INSERT INTO project_share_link (project_id, token_hash, discipline_ids, include_events, expires_at,
                                                created_by, created_at)
                VALUES (?, ?, ?::bigint[], ?, ?, ?, ?) RETURNING id""", Long.class,
                pid, hash(token), ids == null ? null : pgArray(ids), req.includeEvents() == null || req.includeEvents(),
                Timestamp.from(now.plus(java.time.Duration.ofDays(days))), userId(jwt), Timestamp.from(now));
        LinkView link = links(pid, id).get(0);
        return new CreatedLink(link, "/share/" + token, "/share/" + token + ".ics");
    }

    @GetMapping("/api/projects/{pid}/share-links")
    List<LinkView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid) {
        manager(pid, jwt);
        return links(pid, null);
    }

    /** Revoke (kept for the list). Idempotent; another project's link is 404. */
    @DeleteMapping("/api/projects/{pid}/share-links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long pid, @PathVariable long id) {
        manager(pid, jwt);
        if (jdbc.update("UPDATE project_share_link SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ? AND project_id = ?",
                Timestamp.from(clock.instant()), id, pid) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    // --- public, token only -------------------------------------------------------------------------------

    @GetMapping(value = "/share/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharedSchedule> view(@PathVariable String token) {
        Link link = resolve(token);
        if (link == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new SharedSchedule(link.projectName(), link.expiresAt(),
                items(link.id()).stream().map(Row::view).toList(), events(link.id()).stream().map(Row::view).toList()));
    }

    @GetMapping("/share/{token}.ics")
    ResponseEntity<String> ics(@PathVariable String token) {
        Link link = resolve(token);
        if (link == null) return ResponseEntity.notFound().build();
        List<Ics.Entry> entries = new java.util.ArrayList<>();
        for (Row<SharedItem> r : items(link.id())) {
            SharedItem i = r.view();
            entries.add(new Ics.Entry("item-" + r.id() + "@envpermit", i.title(), i.startDate().atStartOfDay(),
                    i.endDate().atStartOfDay(), true, null, details(i.disciplineName(), i.partnerName())));
        }
        for (Row<SharedEvent> r : events(link.id())) {
            SharedEvent e = r.view();
            entries.add(new Ics.Entry("event-" + r.id() + "@envpermit", e.title(), e.startsAt(), e.endsAt(), e.allDay(),
                    e.location(), details(e.disciplineName(), null)));
        }
        return calendar(Ics.calendar(link.projectName() + " 일정", clock.instant(), entries));
    }

    // --- 개인 구독 (내 참석 이벤트 · 내 담당 마일스톤) ----------------------------------------------------------

    /** Issue or reissue; the previous address stops working. */
    @PostMapping("/api/me/calendar-token")
    CalendarTokenView issueCalendarToken(@AuthenticationPrincipal Jwt jwt) {
        String token = newToken();
        jdbc.update("""
                INSERT INTO user_calendar_token (user_id, token_hash, created_at) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET token_hash = EXCLUDED.token_hash, created_at = EXCLUDED.created_at""",
                userId(jwt), hash(token), Timestamp.from(clock.instant()));
        return new CalendarTokenView("/api/me/calendar.ics?token=" + token);
    }

    @DeleteMapping("/api/me/calendar-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCalendarToken(@AuthenticationPrincipal Jwt jwt) {
        jdbc.update("DELETE FROM user_calendar_token WHERE user_id = ?", userId(jwt));
    }

    /** Projects the user is still a member of only. Same field list as a share link, plus the project name. */
    @GetMapping("/api/me/calendar.ics")
    ResponseEntity<String> myCalendar(@RequestParam(required = false) String token) {
        List<Long> user = token == null ? List.of()
                : jdbc.queryForList("SELECT t.user_id FROM user_calendar_token t JOIN users u ON u.id = t.user_id "
                        + "WHERE t.token_hash = ? AND u.disabled_at IS NULL", Long.class, hash(token)); // M2: not for disabled users
        if (user.isEmpty()) return ResponseEntity.notFound().build();
        long uid = user.get(0);
        LocalDate since = LocalDate.now(clock).minusMonths(ICS_PAST_MONTHS); // L7: recent past only
        List<Ics.Entry> entries = new java.util.ArrayList<>(jdbc.query("""
                SELECT e.id, p.name, e.title, e.starts_at, e.ends_at, e.all_day, e.location, d.name
                FROM project_event e
                JOIN project_event_attendee a ON a.event_id = e.id AND a.user_id = ?
                JOIN project_member m ON m.project_id = e.project_id AND m.user_id = a.user_id
                JOIN project p ON p.id = e.project_id
                LEFT JOIN discipline d ON d.id = e.discipline_id
                WHERE e.ends_at >= ?
                ORDER BY e.starts_at, e.id""",
                (rs, n) -> new Ics.Entry("event-" + rs.getLong(1) + "@envpermit", "[" + rs.getString(2) + "] " + rs.getString(3),
                        rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class), rs.getBoolean(6),
                        rs.getString(7), details(rs.getString(8), null)), uid, since.atStartOfDay()));
        entries.addAll(jdbc.query("""
                SELECT i.id, p.name, i.title, i.end_date, d.name, i.partner_name
                FROM schedule_item i
                JOIN project_member m ON m.project_id = i.project_id AND m.user_id = i.owner_user_id
                JOIN project p ON p.id = i.project_id
                LEFT JOIN discipline d ON d.id = i.discipline_id
                WHERE i.owner_user_id = ? AND i.kind = 'MILESTONE' AND i.deleted_at IS NULL AND i.end_date >= ?
                ORDER BY i.end_date, i.id""",
                (rs, n) -> new Ics.Entry("item-" + rs.getLong(1) + "@envpermit", "[" + rs.getString(2) + "] " + rs.getString(3),
                        rs.getObject(4, LocalDate.class).atStartOfDay(), rs.getObject(4, LocalDate.class).atStartOfDay(),
                        true, null, details(rs.getString(5), rs.getString(6))), uid, since));
        return calendar(Ics.calendar("내 설계 일정", clock.instant(), entries));
    }

    // --- helpers ------------------------------------------------------------------------------------------

    /** Active link for the token, or null (unknown, expired, revoked — not told apart). Records the access. */
    private Link resolve(String token) {
        Instant now = clock.instant();
        List<Link> found = jdbc.query("""
                SELECT l.id, p.name, l.expires_at FROM project_share_link l JOIN project p ON p.id = l.project_id
                WHERE l.token_hash = ? AND l.revoked_at IS NULL AND l.expires_at > ?""",
                (rs, n) -> new Link(rs.getLong(1), rs.getString(2), instant(rs, 3)), hash(token), Timestamp.from(now));
        if (found.isEmpty()) return null;
        jdbc.update("UPDATE project_share_link SET last_accessed_at = ? WHERE id = ?", Timestamp.from(now), found.get(0).id());
        return found.get(0);
    }

    /** The link row carries the scope: its project, and its disciplines unless null. */
    private List<Row<SharedItem>> items(long linkId) {
        return jdbc.query("""
                SELECT i.id, i.kind, i.title, i.start_date, i.end_date, d.name, i.partner_name
                FROM schedule_item i
                JOIN project_share_link l ON l.id = ? AND l.project_id = i.project_id
                LEFT JOIN discipline d ON d.id = i.discipline_id
                WHERE i.deleted_at IS NULL AND i.start_date IS NOT NULL
                  AND (l.discipline_ids IS NULL OR i.discipline_id = ANY (l.discipline_ids))
                ORDER BY d.sort_order NULLS FIRST, i.start_date, i.id""",
                (rs, n) -> new Row<>(rs.getLong(1), new SharedItem(rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getString(6),
                        rs.getString(7))), linkId);
    }

    private List<Row<SharedEvent>> events(long linkId) {
        return jdbc.query("""
                SELECT e.id, e.title, e.starts_at, e.ends_at, e.all_day, e.location, d.name
                FROM project_event e
                JOIN project_share_link l ON l.id = ? AND l.project_id = e.project_id AND l.include_events
                LEFT JOIN discipline d ON d.id = e.discipline_id
                WHERE l.discipline_ids IS NULL OR e.discipline_id = ANY (l.discipline_ids)
                ORDER BY e.starts_at, e.id""",
                (rs, n) -> new Row<>(rs.getLong(1), new SharedEvent(rs.getString(2),
                        rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class), rs.getBoolean(5),
                        rs.getString(6), rs.getString(7))), linkId);
    }

    private List<LinkView> links(long pid, Long id) {
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT l.id, l.discipline_ids, l.include_events, l.expires_at, l.revoked_at, u.name, l.created_at,
                       l.last_accessed_at
                FROM project_share_link l LEFT JOIN users u ON u.id = l.created_by
                WHERE l.project_id = ? AND (?::bigint IS NULL OR l.id = ?)
                ORDER BY l.id DESC""",
                (rs, n) -> {
                    Array a = rs.getArray(2);
                    Instant expires = instant(rs, 4);
                    Instant revoked = instant(rs, 5);
                    return new LinkView(rs.getLong(1), a == null ? null : Arrays.asList((Long[]) a.getArray()),
                            rs.getBoolean(3), expires, revoked, rs.getString(6), instant(rs, 7), instant(rs, 8),
                            revoked == null && expires.isAfter(now));
                }, pid, id, id);
    }

    /** A project member who is the PM, the EM or a project admin. Anyone else (incl. other companies) 403. */
    private void manager(long pid, Jwt jwt) {
        if (!jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM project p JOIN project_member m ON m.project_id = p.id AND m.user_id = ?
                    WHERE p.id = ? AND (m.is_admin OR p.pm_user_id = m.user_id OR p.em_user_id = m.user_id))""",
                Boolean.class, userId(jwt), pid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트 PM·EM·관리자만 공유 링크를 관리할 수 있습니다.");
        }
    }

    private static ResponseEntity<String> calendar(String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .header("Content-Disposition", "inline; filename=\"schedule.ics\"").body(body);
    }

    private static String details(String discipline, String partner) {
        String s = (discipline != null ? "분야: " + discipline : "") + (partner != null ? (discipline != null ? "\n" : "") + "협력사: " + partner : "");
        return s.isEmpty() ? null : s;
    }

    static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pgArray(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}")); // longs only
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
