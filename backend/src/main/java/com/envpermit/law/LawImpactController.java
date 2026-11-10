package com.envpermit.law;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** "법령 개정" screen: impacts in the caller's projects. Every route checks project membership. */
@RestController
class LawImpactController {

    private static final String SELECT = """
            SELECT i.id, i.level, i.status, p.project_id, pr.name AS project_name, i.permit_item_id,
                   p.name AS permit_name, a.law_catalog_id, c.name AS law_name, a.revision_type, a.promulgated_on,
                   a.enforced_on, a.revision_type LIKE '%%폐지%%' AS replacement_needed, i.created_at, i.reviewed_at
            FROM permit_law_impact i
            JOIN law_amendment a ON a.id = i.amendment_id JOIN law_catalog c ON c.id = a.law_catalog_id
            JOIN permit_item p ON p.id = i.permit_item_id JOIN project pr ON pr.id = p.project_id
            JOIN project_member m ON m.project_id = p.project_id AND m.user_id = ?
            WHERE %s
            ORDER BY i.level, a.enforced_on NULLS LAST, i.id""";

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    LawImpactController(JdbcTemplate jdbc, ProjectAccess access, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    /** replacementNeeded: the law was repealed (폐지), the link must be moved to the new law by hand. */
    record ImpactView(long id, String level, String status, long projectId, String projectName, long permitItemId,
                      String permitName, long lawCatalogId, String lawName, String revisionType,
                      LocalDate promulgatedOn, LocalDate enforcedOn, boolean replacementNeeded,
                      OffsetDateTime createdAt, OffsetDateTime reviewedAt) {
    }

    record ArticleView(String articleNo, String changeReason, LocalDate enforcedOn, boolean mine) {
    }

    record OldNewRow(String old, String next) {
    }

    /**
     * @param linkedArticles articles this permit links on the amended law itself (null element = whole law);
     *                       empty = affected only through the linked 법률's 시행령·시행규칙
     * @param oldNew         신구조문 rows; null = not fetched yet ("준비 중")
     * @param deadline       registered 시행일 deadline, if any
     */
    record DetailView(ImpactView impact, String reason, List<ArticleView> articles, List<String> linkedArticles,
                      List<OldNewRow> oldNew, String sourceUrl, LocalDate deadline, String note,
                      String reviewedByName, String source) {
    }

    record ReviewRequest(@NotBlank(message = "확인 메모를 적어 주세요.") String note) {
    }

    @GetMapping("/api/law-impacts")
    List<ImpactView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long projectId,
                          @RequestParam(required = false) ImpactRule.Level level,
                          @RequestParam(required = false) Status status) {
        List<String> where = new ArrayList<>(List.of("TRUE"));
        List<Object> args = new ArrayList<>(List.of(userId(jwt)));
        if (projectId != null) {
            access.member(projectId, jwt);
            where.add("p.project_id = ?");
            args.add(projectId);
        }
        if (level != null) {
            where.add("i.level = ?");
            args.add(level.name());
        }
        if (status != null) {
            where.add("i.status = ?");
            args.add(status.name());
        }
        return query(String.join(" AND ", where), args.toArray());
    }

    enum Status { NEW, REVIEWED }

    /** Header bell: NEW impacts on the projects I am 책임자 (admin) of — permit items have no assignee yet. */
    @GetMapping("/api/law-impacts/count")
    Map<String, Integer> count(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", jdbc.queryForObject("""
                SELECT count(*) FROM permit_law_impact i JOIN permit_item p ON p.id = i.permit_item_id
                JOIN project_member m ON m.project_id = p.project_id AND m.user_id = ? AND m.is_admin
                WHERE i.status = 'NEW'""", Integer.class, userId(jwt)));
    }

    @GetMapping("/api/law-impacts/{id}")
    DetailView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return detail(visible(id, jwt));
    }

    @PostMapping("/api/law-impacts/{id}/review")
    @Transactional
    DetailView review(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ReviewRequest req) {
        visible(id, jwt);
        int n = jdbc.update("UPDATE permit_law_impact SET status = 'REVIEWED', reviewed_by = ?, reviewed_at = ?, note = ? "
                + "WHERE id = ? AND status = 'NEW'", userId(jwt), java.sql.Timestamp.from(clock.instant()),
                req.note().strip(), id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 확인 처리했습니다.");
        }
        return detail(visible(id, jwt)); // the 시행일 deadline stays
    }

    /** Not found and not a member are both 403, so other companies' ids are not revealed. */
    private ImpactView visible(long id, Jwt jwt) {
        List<ImpactView> rows = query("i.id = ?", userId(jwt), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return rows.get(0);
    }

    private List<ImpactView> query(String where, Object... args) {
        return jdbc.query(SELECT.formatted(where), new DataClassRowMapper<>(ImpactView.class), args);
    }

    private DetailView detail(ImpactView v) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT a.id AS amendment_id, a.mst, a.reason, a.old_new_raw::text AS raw, i.note, u.name AS reviewer,
                       (SELECT due_date FROM deadline_event WHERE kind = ? AND target_id = i.id) AS deadline
                FROM permit_law_impact i JOIN law_amendment a ON a.id = i.amendment_id
                LEFT JOIN users u ON u.id = i.reviewed_by WHERE i.id = ?""", LawImpacts.DEADLINE_KIND, v.id());
        List<String> linked = jdbc.queryForList("SELECT article_no FROM permit_law_link WHERE permit_item_id = ? "
                + "AND law_catalog_id = ? ORDER BY id", String.class, v.permitItemId(), v.lawCatalogId());
        List<String> mine = linked.stream().map(ArticleNo::joCode).filter(Objects::nonNull).toList();
        List<ArticleView> articles = jdbc.query("""
                SELECT article_no, jo_code, change_reason, article_enforced_on FROM law_amendment_article
                WHERE amendment_id = ? ORDER BY jo_code""",
                (rs, n) -> new ArticleView(rs.getString("article_no"), rs.getString("change_reason"),
                        rs.getObject("article_enforced_on", LocalDate.class), mine.contains(rs.getString("jo_code"))),
                row.get("amendment_id"));
        return new DetailView(v, (String) row.get("reason"), articles, linked, oldNew((String) row.get("raw")),
                "https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=" + row.get("mst"),
                row.get("deadline") == null ? null : ((java.sql.Date) row.get("deadline")).toLocalDate(),
                (String) row.get("note"), (String) row.get("reviewer"), LawImpacts.SOURCE);
    }

    /** OldAndNewService.{구조문목록, 신조문목록}.조문[] (one row comes as an object), paired by position. */
    private List<OldNewRow> oldNew(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode s = json.readTree(raw).path("OldAndNewService");
            List<String> olds = texts(s.path("구조문목록").path("조문"));
            List<String> news = texts(s.path("신조문목록").path("조문"));
            List<OldNewRow> rows = new ArrayList<>();
            for (int i = 0; i < Math.max(olds.size(), news.size()); i++) {
                rows.add(new OldNewRow(i < olds.size() ? olds.get(i) : "", i < news.size() ? news.get(i) : ""));
            }
            return rows;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("stored old_new_raw is not JSON", e);
        }
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : n.isArray() ? n : List.of(n)) {
            if (!e.isMissingNode()) {
                out.add(e.path("content").asText());
            }
        }
        return out;
    }
}
