package com.envpermit.operation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * F2 §3.1 운영 탭: emission points and their permit limits. Limits are entered by people (no AI path) and never
 * edited: a change closes the row and adds a new one, so the limit in force on any past date stays answerable.
 */
@RestController
class EmissionController {

    private static final String POINTS = """
            SELECT e.id, e.project_id, e.permit_item_id, p.name AS permit_name, e.medium, e.name, e.active,
                   (SELECT count(*) FROM permit_limit l WHERE l.emission_point_id = e.id AND l.needs_review) AS needs_review
            FROM emission_point e LEFT JOIN permit_item p ON p.id = e.permit_item_id
            """;

    private static final String LIMITS = """
            SELECT l.id, l.emission_point_id, e.project_id, l.pollutant, pl.name AS pollutant_name, l.unit, l.limit_value,
                   l.warn_ratio, l.obligation_id, o.summary AS obligation_summary, l.basis_text, l.effective_from,
                   l.effective_to, l.needs_review
            FROM permit_limit l
            JOIN emission_point e ON e.id = l.emission_point_id
            JOIN pollutant pl ON pl.code = l.pollutant
            LEFT JOIN permit_obligation o ON o.id = l.obligation_id
            """;

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;

    EmissionController(JdbcTemplate jdbc, ProjectAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    record PollutantView(String code, String medium, String name, String unit) {
    }

    record PointView(long id, long projectId, Long permitItemId, String permitName, String medium, String name,
                     boolean active, int needsReviewCount) {
    }

    record PointRequest(Long permitItemId, @NotBlank @Pattern(regexp = "AIR|WATER") String medium,
                        @NotBlank @Size(max = 100) String name) {
    }

    /** Whole replacement of the editable fields; the medium is fixed because the limits depend on it. */
    record PointUpdate(Long permitItemId, @NotBlank @Size(max = 100) String name, boolean active) {
    }

    record LimitView(long id, long emissionPointId, String pollutant, String pollutantName, String unit,
                     BigDecimal limitValue, BigDecimal warnRatio, Long obligationId, String obligationSummary,
                     String basisText, LocalDate effectiveFrom, LocalDate effectiveTo, boolean needsReview) {
    }

    /** replacesLimitId: close that open limit the day before effectiveFrom in the same transaction (한도 변경). */
    record LimitRequest(@NotBlank String pollutant, @NotNull @Positive BigDecimal limitValue, BigDecimal warnRatio,
                        Long obligationId, @Size(max = 500) String basisText, @NotNull LocalDate effectiveFrom,
                        LocalDate effectiveTo, Long replacesLimitId) {
    }

    record CloseRequest(@NotNull LocalDate effectiveTo) {
    }

    @GetMapping("/api/pollutants")
    List<PollutantView> pollutants() {
        return jdbc.query("SELECT code, medium, name, unit FROM pollutant ORDER BY sort", (rs, i) -> new PollutantView(
                rs.getString("code"), rs.getString("medium"), rs.getString("name"), rs.getString("unit")));
    }

    @GetMapping("/api/projects/{projectId}/emission-points")
    List<PointView> points(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        access.member(projectId, jwt);
        return points("e.project_id = ? ORDER BY e.active DESC, e.medium, e.id", projectId);
    }

    @PostMapping("/api/projects/{projectId}/emission-points")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PointView createPoint(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                          @Validated @RequestBody PointRequest req) {
        access.member(projectId, jwt);
        checkPermit(projectId, req.permitItemId());
        Long id = jdbc.queryForObject("INSERT INTO emission_point (project_id, permit_item_id, medium, name) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, projectId, req.permitItemId(), req.medium(), req.name().trim());
        return points("e.id = ?", id).get(0);
    }

    @PatchMapping("/api/emission-points/{id}")
    @Transactional
    PointView updatePoint(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Validated @RequestBody PointUpdate req) {
        PointView p = point(id, jwt);
        checkPermit(p.projectId(), req.permitItemId());
        jdbc.update("UPDATE emission_point SET permit_item_id = ?, name = ?, active = ? WHERE id = ?",
                req.permitItemId(), req.name().trim(), req.active(), id);
        return points("e.id = ?", id).get(0);
    }

    /** All limits of the point (history included), or with {@code on} only those in force that day. */
    @GetMapping("/api/emission-points/{id}/limits")
    List<LimitView> limits(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        point(id, jwt);
        String order = " ORDER BY pl.sort, l.effective_from DESC";
        return on == null ? limits("l.emission_point_id = ?" + order, id)
                : limits("l.emission_point_id = ? AND l.effective_from <= ?::date "
                + "AND (l.effective_to IS NULL OR l.effective_to >= ?::date)" + order, id, on, on);
    }

    @PostMapping("/api/emission-points/{id}/limits")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    LimitView createLimit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Validated @RequestBody LimitRequest req) {
        PointView p = point(id, jwt);
        jdbc.queryForList("SELECT id FROM emission_point WHERE id = ? FOR UPDATE", id); // one writer per point: the overlap check below cannot race
        String unit = jdbc.queryForList("SELECT unit FROM pollutant WHERE code = ? AND medium = ?", String.class,
                req.pollutant(), p.medium()).stream().findFirst().orElseThrow(() -> unprocessable(
                "이 배출구(" + (p.medium().equals("AIR") ? "대기" : "수질") + ")에 쓸 수 없는 측정 항목입니다: " + req.pollutant()));
        BigDecimal ratio = req.warnRatio() == null ? new BigDecimal("0.80") : req.warnRatio();
        if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) >= 0) {
            throw unprocessable("근접 비율은 0보다 크고 1보다 작아야 합니다.");
        }
        if (req.effectiveTo() != null && req.effectiveTo().isBefore(req.effectiveFrom())) {
            throw unprocessable("유효 종료일이 시작일보다 빠릅니다.");
        }
        if (req.obligationId() != null && jdbc.queryForObject("SELECT count(*) FROM permit_obligation o "
                        + "JOIN permit_item i ON i.id = o.permit_item_id WHERE o.id = ? AND i.project_id = ?",
                Integer.class, req.obligationId(), p.projectId()) == 0) {
            throw unprocessable("근거 의무사항은 이 프로젝트의 의무사항이어야 합니다.");
        }
        if (req.replacesLimitId() != null && jdbc.update("UPDATE permit_limit SET effective_to = ?::date - 1, needs_review = FALSE "
                        + "WHERE id = ? AND emission_point_id = ? AND pollutant = ? AND effective_to IS NULL AND effective_from < ?::date",
                req.effectiveFrom(), req.replacesLimitId(), id, req.pollutant(), req.effectiveFrom()) == 0) {
            throw unprocessable("새 한도의 시작일은 기존 한도의 시작일보다 뒤여야 합니다.");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM permit_limit WHERE emission_point_id = ? AND pollutant = ? "
                        + "AND daterange(effective_from, effective_to, '[]') && daterange(?::date, ?::date, '[]')",
                Integer.class, id, req.pollutant(), req.effectiveFrom(), req.effectiveTo()) > 0) {
            throw unprocessable("같은 항목의 유효기간이 겹칩니다. 기존 한도를 닫거나 [한도 변경]을 쓰세요.");
        }
        Long limitId = jdbc.queryForObject("INSERT INTO permit_limit (emission_point_id, pollutant, unit, limit_value, warn_ratio, "
                        + "obligation_id, basis_text, effective_from, effective_to, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?) RETURNING id", Long.class,
                id, req.pollutant(), unit, req.limitValue(), ratio, req.obligationId(), req.basisText(),
                req.effectiveFrom(), req.effectiveTo(), userId(jwt));
        return limits("l.id = ?", limitId).get(0);
    }

    @PostMapping("/api/limits/{id}/close")
    @Transactional
    LimitView close(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Validated @RequestBody CloseRequest req) {
        LimitView l = limit(id, jwt);
        if (l.effectiveTo() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 닫힌 한도입니다.");
        }
        if (req.effectiveTo().isBefore(l.effectiveFrom())) {
            throw unprocessable("유효 종료일이 시작일보다 빠릅니다.");
        }
        jdbc.update("UPDATE permit_limit SET effective_to = ?::date, needs_review = FALSE WHERE id = ?", req.effectiveTo(), id);
        return limits("l.id = ?", id).get(0);
    }

    /** "한도 확인 필요" acknowledged: the person looked at the revised condition; the value itself is untouched. */
    @PostMapping("/api/limits/{id}/reviewed")
    @Transactional
    LimitView reviewed(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        limit(id, jwt);
        jdbc.update("UPDATE permit_limit SET needs_review = FALSE WHERE id = ?", id);
        return limits("l.id = ?", id).get(0);
    }

    // --- lookups: 404 unknown, 403 not a project member ----------------------------------------------------------

    private PointView point(long id, Jwt jwt) {
        PointView p = points("e.id = ?", id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.member(p.projectId(), jwt);
        return p;
    }

    private LimitView limit(long id, Jwt jwt) {
        Long projectId = jdbc.queryForList("SELECT e.project_id FROM permit_limit l JOIN emission_point e "
                        + "ON e.id = l.emission_point_id WHERE l.id = ? FOR UPDATE OF l", Long.class, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.member(projectId, jwt);
        return limits("l.id = ?", id).get(0);
    }

    private void checkPermit(long projectId, Long permitItemId) {
        if (permitItemId != null && jdbc.queryForObject("SELECT count(*) FROM permit_item WHERE id = ? AND project_id = ?",
                Integer.class, permitItemId, projectId) == 0) {
            throw unprocessable("근거 인허가 항목은 이 프로젝트의 항목이어야 합니다.");
        }
    }

    private static ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private List<PointView> points(String where, Object... args) {
        return jdbc.query(POINTS + " WHERE " + where, (rs, i) -> new PointView(rs.getLong("id"), rs.getLong("project_id"),
                rs.getObject("permit_item_id", Long.class), rs.getString("permit_name"), rs.getString("medium"),
                rs.getString("name"), rs.getBoolean("active"), rs.getInt("needs_review")), args);
    }

    private List<LimitView> limits(String where, Object... args) {
        return jdbc.query(LIMITS + " WHERE " + where, (rs, i) -> new LimitView(rs.getLong("id"), rs.getLong("emission_point_id"),
                rs.getString("pollutant"), rs.getString("pollutant_name"), rs.getString("unit"), rs.getBigDecimal("limit_value"),
                rs.getBigDecimal("warn_ratio"), rs.getObject("obligation_id", Long.class), rs.getString("obligation_summary"),
                rs.getString("basis_text"), rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), rs.getBoolean("needs_review")), args);
    }
}
