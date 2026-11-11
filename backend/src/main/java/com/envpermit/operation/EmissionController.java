package com.envpermit.operation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * F2 §3.1 운영 탭: emission points, their permit limits and measured values. Limits are entered by people (no AI
 * path) and never edited: a change closes the row and adds a new one, so the limit in force on any past date
 * stays answerable. A measurement stores the limit and the judgement of the day it was entered ({@link Judgement}).
 */
@RestController
class EmissionController {

    private static final String POINTS = """
            SELECT e.id, e.project_id, e.permit_item_id, p.name AS permit_name, e.medium, e.name, e.active,
                   (SELECT count(*) FROM permit_limit l WHERE l.emission_point_id = e.id AND l.needs_review) AS needs_review
            FROM emission_point e LEFT JOIN permit_item p ON p.id = e.permit_item_id
            """;

    private static final String LIMITS = """
            SELECT l.id, l.emission_point_id, e.project_id, l.pollutant, pl.name AS pollutant_name, l.unit, l.limit_lower,
                   l.limit_value,
                   l.warn_ratio, l.obligation_id, o.summary AS obligation_summary, l.basis_text, l.effective_from,
                   l.effective_to, l.needs_review
            FROM permit_limit l
            JOIN emission_point e ON e.id = l.emission_point_id
            JOIN pollutant pl ON pl.code = l.pollutant
            LEFT JOIN permit_obligation o ON o.id = l.obligation_id
            """;

    private static final String MEASUREMENTS = """
            SELECT m.id, m.emission_point_id, m.pollutant, pl.name AS pollutant_name, m.measured_on, m.value, m.unit,
                   m.below_detection, m.source, m.task_id, m.limit_id, m.limit_lower, m.limit_value, m.warn_ratio, m.level,
                   u.name AS entered_by_name, m.entered_at
            FROM measurement m
            JOIN pollutant pl ON pl.code = m.pollutant
            LEFT JOIN users u ON u.id = m.entered_by
            WHERE m.deleted_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final Clock clock;
    private final ExceedanceAlerts alerts;

    EmissionController(JdbcTemplate jdbc, ProjectAccess access, Clock clock, ExceedanceAlerts alerts) {
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    /** rangeLimit: the limit of this item is a range (lower ~ upper), e.g. pH. */
    record PollutantView(String code, String medium, String name, String unit, boolean rangeLimit) {
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

    /** limitLower: lower bound of a range limit (limitValue is then the upper bound); null otherwise. */
    record LimitView(long id, long emissionPointId, String pollutant, String pollutantName, String unit,
                     BigDecimal limitLower, BigDecimal limitValue, BigDecimal warnRatio, Long obligationId, String obligationSummary,
                     String basisText, LocalDate effectiveFrom, LocalDate effectiveTo, boolean needsReview) {
    }

    /** replacesLimitId: close that open limit the day before effectiveFrom in the same transaction (한도 변경). */
    record LimitRequest(@NotBlank String pollutant, @PositiveOrZero BigDecimal limitLower,
                        @NotNull @Positive BigDecimal limitValue, BigDecimal warnRatio,
                        Long obligationId, @Size(max = 500) String basisText, @NotNull LocalDate effectiveFrom,
                        LocalDate effectiveTo, Long replacesLimitId) {
    }

    record CloseRequest(@NotNull LocalDate effectiveTo) {
    }

    /** limitId / limitValue / warnRatio: the limit in force on measuredOn when the value was entered (null: none). */
    record MeasurementView(long id, long emissionPointId, String pollutant, String pollutantName, LocalDate measuredOn,
                           BigDecimal value, String unit, boolean belowDetection, String source, Long taskId,
                           Long limitId, BigDecimal limitLower, BigDecimal limitValue, BigDecimal warnRatio,
                           Judgement.Level level, String enteredByName, Instant enteredAt) {
    }

    /** belowDetection (불검출): value carries the quantitation limit and the judgement is NORMAL. */
    record MeasurementRow(@NotNull LocalDate measuredOn, @NotBlank String pollutant, @NotNull @PositiveOrZero BigDecimal value,
                          @NotBlank String unit, boolean belowDetection) {
    }

    /** taskId: the open task these values fulfil (완료 조건 O2). source defaults to MANUAL. */
    record MeasurementRequest(@NotEmpty @Size(max = 500) List<@Valid MeasurementRow> rows, Long taskId,
                              @Pattern(regexp = "MANUAL|PASTE") String source) {
    }

    record PasteRequest(@NotNull @Size(max = 100_000) String text) {
    }

    /** One pasted line. error == null: the row would be saved with this unit, limit and level. */
    record PasteRow(int line, String raw, LocalDate measuredOn, String pollutant, String pollutantName, BigDecimal value,
                    String unit, BigDecimal limitLower, BigDecimal limitValue, BigDecimal warnRatio, Judgement.Level level,
                    String error) {
    }

    record DeleteRequest(@NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/api/pollutants")
    List<PollutantView> pollutants() {
        return jdbc.query("SELECT code, medium, name, unit, range_limit FROM pollutant ORDER BY sort", (rs, i) -> new PollutantView(
                rs.getString("code"), rs.getString("medium"), rs.getString("name"), rs.getString("unit"),
                rs.getBoolean("range_limit")));
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
        Map<String, Object> item = jdbc.queryForList("SELECT unit, range_limit FROM pollutant WHERE code = ? AND medium = ?",
                req.pollutant(), p.medium()).stream().findFirst().orElseThrow(() -> unprocessable(
                "이 배출구(" + (p.medium().equals("AIR") ? "대기" : "수질") + ")에 쓸 수 없는 측정 항목입니다: " + req.pollutant()));
        String unit = (String) item.get("unit");
        if ((boolean) item.get("range_limit") != (req.limitLower() != null)) { // an upper-only pH limit would pass any acid
            throw unprocessable(req.limitLower() == null ? "이 항목은 범위 한도입니다. 하한과 상한을 모두 넣으세요."
                    : "이 항목은 상한만 있는 한도입니다. 하한을 빼세요.");
        }
        if (req.limitLower() != null && req.limitLower().compareTo(req.limitValue()) >= 0) {
            throw unprocessable("하한은 상한보다 작아야 합니다.");
        }
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
        Long limitId = jdbc.queryForObject("INSERT INTO permit_limit (emission_point_id, pollutant, unit, limit_lower, limit_value, warn_ratio, "
                        + "obligation_id, basis_text, effective_from, effective_to, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?) RETURNING id", Long.class,
                id, req.pollutant(), unit, req.limitLower(), req.limitValue(), ratio, req.obligationId(), req.basisText(),
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

    // --- measurements ----------------------------------------------------------------------------------------------

    /** Saves all rows or none. Each row is judged against the limit in force on its own measured day. */
    @PostMapping("/api/emission-points/{id}/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    List<MeasurementView> addMeasurements(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                          @Validated @RequestBody MeasurementRequest req) {
        PointView p = point(id, jwt);
        jdbc.queryForList("SELECT id FROM emission_point WHERE id = ? FOR UPDATE", id); // a limit change cannot slip in between lookup and insert
        if (req.taskId() != null && jdbc.queryForList("SELECT id FROM obligation_task WHERE id = ? AND project_id = ? "
                + "AND status = 'OPEN' FOR UPDATE", req.taskId(), p.projectId()).isEmpty()) {
            throw unprocessable("측정값은 이 프로젝트의 열린 업무에만 연결할 수 있습니다.");
        }
        Timestamp now = Timestamp.from(clock.instant());
        List<Long> ids = new ArrayList<>();
        for (MeasurementRow r : req.rows()) {
            String seedUnit = jdbc.queryForList("SELECT unit FROM pollutant WHERE code = ? AND medium = ?", String.class,
                    r.pollutant(), p.medium()).stream().findFirst().orElseThrow(() -> unprocessable(
                    "이 배출구(" + (p.medium().equals("AIR") ? "대기" : "수질") + ")에 쓸 수 없는 측정 항목입니다: " + r.pollutant()));
            LimitView limit = limits("l.emission_point_id = ? AND l.pollutant = ? AND l.effective_from <= ?::date "
                    + "AND (l.effective_to IS NULL OR l.effective_to >= ?::date)", id, r.pollutant(), r.measuredOn(),
                    r.measuredOn()).stream().findFirst().orElse(null);
            String unit = limit == null ? seedUnit : limit.unit();
            if (!unit.equals(r.unit().trim())) { // v1 has no unit conversion
                throw unprocessable("단위가 다릅니다: %s은(는) %s 단위로 입력하세요.".formatted(r.pollutant(), unit));
            }
            BigDecimal lower = limit == null ? null : limit.limitLower();
            BigDecimal limitValue = limit == null ? null : limit.limitValue();
            BigDecimal ratio = limit == null ? null : limit.warnRatio();
            ids.add(jdbc.queryForObject("INSERT INTO measurement (emission_point_id, pollutant, measured_on, value, unit, "
                            + "below_detection, source, task_id, limit_id, limit_lower, limit_value, warn_ratio, level, entered_by, entered_at) "
                            + "VALUES (?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                    id, r.pollutant(), r.measuredOn(), r.value(), unit, r.belowDetection(),
                    req.source() == null ? "MANUAL" : req.source(), req.taskId(), limit == null ? null : limit.id(),
                    lower, limitValue, ratio, Judgement.judge(r.value(), lower, limitValue, ratio, r.belowDetection()).name(),
                    userId(jwt), now));
        }
        alerts.raise(p.projectId(), id, ids, userId(jwt)); // 초과 → 조치 업무 + 알림, 근접 → 알림 (ticket 06)
        return ids.stream().map(m -> measurements("AND m.id = ?", m).get(0)).toList();
    }

    /** 붙여넣기 미리보기 (§3.4): the pasted lines with the judgement or the error each would get. Saves nothing. */
    @PostMapping("/api/emission-points/{id}/measurements/preview")
    List<PasteRow> previewPaste(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Validated @RequestBody PasteRequest req) {
        return pasteRows(point(id, jwt), req.text());
    }

    /** Saves the pasted lines that have no error, all or none, as source PASTE; the error lines are left out. */
    @PostMapping("/api/emission-points/{id}/measurements/paste")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    List<MeasurementView> savePaste(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Validated @RequestBody PasteRequest req) {
        PointView p = point(id, jwt);
        jdbc.queryForList("SELECT id FROM emission_point WHERE id = ? FOR UPDATE", id); // the limits read here are the ones the save judges with
        List<MeasurementRow> rows = pasteRows(p, req.text()).stream().filter(r -> r.error() == null)
                .map(r -> new MeasurementRow(r.measuredOn(), r.pollutant(), r.value(), r.unit(), false)).toList();
        if (rows.isEmpty()) {
            throw unprocessable("저장할 수 있는 행이 없습니다.");
        }
        return addMeasurements(jwt, id, new MeasurementRequest(rows, null, "PASTE"));
    }

    /** Parser errors, plus the one the parser cannot know: no limit of this point in force on the measured day. */
    private List<PasteRow> pasteRows(PointView p, String text) {
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT code, name FROM pollutant WHERE medium = ?", rs -> {
            names.put(rs.getString("code"), rs.getString("name"));
        }, p.medium());
        List<PasteParser.Row> parsed;
        try {
            parsed = PasteParser.parse(text, names);
        } catch (IllegalArgumentException e) {
            throw unprocessable(e.getMessage());
        }
        List<LimitView> limits = limits("l.emission_point_id = ?", p.id());
        return parsed.stream().map(r -> {
            String name = names.get(r.pollutant());
            if (r.error() != null) {
                return new PasteRow(r.line(), r.raw(), r.measuredOn(), r.pollutant(), name, r.value(), null, null, null, null, null, r.error());
            }
            LimitView l = limits.stream().filter(x -> x.pollutant().equals(r.pollutant()) && !x.effectiveFrom().isAfter(r.measuredOn())
                    && (x.effectiveTo() == null || !x.effectiveTo().isBefore(r.measuredOn()))).findFirst().orElse(null);
            return l == null
                    ? new PasteRow(r.line(), r.raw(), r.measuredOn(), r.pollutant(), name, r.value(), null, null, null, null, null,
                    "측정일에 유효한 한도가 없는 항목")
                    : new PasteRow(r.line(), r.raw(), r.measuredOn(), r.pollutant(), name, r.value(), l.unit(), l.limitLower(),
                    l.limitValue(), l.warnRatio(), Judgement.judge(r.value(), l.limitLower(), l.limitValue(), l.warnRatio(), false), null);
        }).toList();
    }

    /** Live (not deleted) values of the point, newest measured day first. */
    @GetMapping("/api/emission-points/{id}/measurements")
    List<MeasurementView> measurements(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                       @RequestParam(required = false) String pollutant,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        point(id, jwt);
        return measurements("AND m.emission_point_id = ? AND (?::varchar IS NULL OR m.pollutant = ?) "
                        + "AND (?::date IS NULL OR m.measured_on >= ?::date) AND (?::date IS NULL OR m.measured_on <= ?::date) "
                        + "ORDER BY m.measured_on DESC, m.id DESC", id, pollutant, pollutant, from, from, to, to);
    }

    /** 오입력: soft delete with a reason; the row leaves every list and count. Correcting = delete + enter again. */
    @DeleteMapping("/api/measurements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteMeasurement(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Validated @RequestBody DeleteRequest req) {
        Long projectId = jdbc.queryForList("SELECT e.project_id FROM measurement m JOIN emission_point e "
                        + "ON e.id = m.emission_point_id WHERE m.id = ? AND m.deleted_at IS NULL FOR UPDATE OF m", Long.class, id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.member(projectId, jwt);
        jdbc.update("UPDATE measurement SET deleted_at = ?, deleted_by = ?, delete_reason = ? WHERE id = ?",
                Timestamp.from(clock.instant()), userId(jwt), req.reason().trim(), id);
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
                rs.getString("pollutant"), rs.getString("pollutant_name"), rs.getString("unit"), rs.getBigDecimal("limit_lower"),
                rs.getBigDecimal("limit_value"), rs.getBigDecimal("warn_ratio"), rs.getObject("obligation_id", Long.class), rs.getString("obligation_summary"),
                rs.getString("basis_text"), rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), rs.getBoolean("needs_review")), args);
    }

    private List<MeasurementView> measurements(String and, Object... args) {
        return jdbc.query(MEASUREMENTS + and, (rs, i) -> new MeasurementView(rs.getLong("id"), rs.getLong("emission_point_id"),
                rs.getString("pollutant"), rs.getString("pollutant_name"), rs.getObject("measured_on", LocalDate.class),
                rs.getBigDecimal("value"), rs.getString("unit"), rs.getBoolean("below_detection"), rs.getString("source"),
                rs.getObject("task_id", Long.class), rs.getObject("limit_id", Long.class), rs.getBigDecimal("limit_lower"), rs.getBigDecimal("limit_value"),
                rs.getBigDecimal("warn_ratio"), Judgement.Level.valueOf(rs.getString("level")),
                rs.getString("entered_by_name"), rs.getTimestamp("entered_at").toInstant()), args);
    }
}
