package com.envpermit.operation;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.Project;
import com.envpermit.core.project.ProjectRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 이행 보고서 초안 (operations-compliance design §4): one query service for the preview and the XLSX, so the file
 * always has the rows the preview showed. It lists what was recorded and concludes nothing. Never reads
 * {@code ai_suggestion}.
 */
@Service
public class ComplianceReport {

    static final String DRAFT = "초안 — 제출 전 법정 서식 확인";
    static final int MAX_ROWS = 5000;
    static final String TOO_MANY = "한 시트가 " + MAX_ROWS + "행을 넘습니다. 기간이나 배출구를 줄이세요.";
    static final String OTHER_POINT = "이 인허가 항목의 배출구만 고를 수 있습니다.";

    private static final Map<Project.FacilityType, String> FACILITY = Map.of(
            Project.FacilityType.INCINERATION, "소각시설", Project.FacilityType.LANDFILL, "매립시설",
            Project.FacilityType.BIOGAS, "통합바이오가스", Project.FacilityType.SEWAGE, "하수처리",
            Project.FacilityType.WASTEWATER, "폐수처리");

    /** Every column is text or a number (dates are yyyy-MM-dd text), so JSON and XLSX carry the same values. */
    private static final RowMapper<List<Object>> ROW = (ResultSet rs, int i) -> {
        List<Object> row = new ArrayList<>();
        for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
            Object v = rs.getObject(c);
            row.add(v == null ? "" : v);
        }
        return row;
    };

    /** rows: one list per line, in header order. */
    public record Sheet(String name, String note, List<String> headers, List<List<Object>> rows) {
    }

    public record Report(String fileName, LocalDate from, LocalDate to, List<Sheet> sheets) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectRepository projects;
    private final PermitItemRepository permits;
    private final UserRepository users;
    private final Clock clock;

    ComplianceReport(JdbcTemplate jdbc, ProjectRepository projects, PermitItemRepository permits, UserRepository users,
                     Clock clock) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.permits = permits;
        this.users = users;
        this.clock = clock;
    }

    /** @param pointIds emission points to include; null = every point of the permit item */
    @Transactional(readOnly = true)
    public Report load(long projectId, long permitId, LocalDate from, LocalDate to, List<Long> pointIds, long userId) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일이 종료일보다 늦습니다.");
        }
        PermitItem permit = permits.findByIdAndProjectId(permitId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Project project = projects.findById(projectId).orElseThrow();
        Map<Long, String> points = jdbc.query(
                "SELECT id, name FROM emission_point WHERE project_id = ? AND permit_item_id = ? ORDER BY id",
                rs -> {
                    Map<Long, String> m = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        m.put(rs.getLong(1), rs.getString(2));
                    }
                    return m;
                }, projectId, permitId);
        if (pointIds != null) {
            if (!points.keySet().containsAll(pointIds)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, OTHER_POINT);
            }
            points.keySet().retainAll(new HashSet<>(pointIds));
        }
        // ids are longs read back from the table, so inlining them is safe
        String in = points.isEmpty() ? "NULL" : points.keySet().stream().map(String::valueOf).collect(Collectors.joining(","));
        LocalDate today = LocalDate.now(clock);
        String zone = clock.getZone().getId();

        List<List<Object>> overview = List.of(
                List.of("프로젝트", project.getName()),
                List.of("시설유형", FACILITY.get(project.getFacilityType())),
                List.of("용량", project.getCapacity() == null ? ""
                        : project.getCapacity().stripTrailingZeros().toPlainString() + " " + text(project.getCapacityUnit())),
                List.of("위치", text(project.getLocation())),
                List.of("인허가 항목", permit.getName()),
                List.of("승인기관", text(permit.getAuthority())),
                List.of("기간", from + " ~ " + to),
                List.of("포함 배출구", String.join(", ", points.values())),
                List.of("생성일", today.toString()),
                List.of("생성자", users.findById(userId).map(User::getName).orElse("")));

        // cycles are counted by due date; a cancelled cycle is not a cycle. 미이행 = still open after its due date.
        List<List<Object>> obligations = rows("""
                SELECT COALESCE(o.citation_raw, ''), o.summary,
                       CASE o.frequency WHEN 'ONCE' THEN '1회' WHEN 'MONTHLY' THEN '매월' WHEN 'QUARTERLY' THEN '분기'
                            WHEN 'SEMIANNUAL' THEN '반기' WHEN 'ANNUAL' THEN '매년' ELSE COALESCE(o.frequency_text, '기타') END,
                       count(t.id),
                       count(*) FILTER (WHERE t.status = 'DONE' AND x.done_on <= t.due_date),
                       count(*) FILTER (WHERE t.status = 'DONE' AND x.done_on > t.due_date),
                       count(*) FILTER (WHERE t.status = 'OPEN' AND t.due_date < ?),
                       count(*) FILTER (WHERE t.status = 'OPEN' AND t.due_date >= ?),
                       COALESCE(string_agg(x.done_on::text, ', ' ORDER BY t.due_date) FILTER (WHERE t.status = 'DONE'), ''),
                       COALESCE((SELECT string_agg(d.file_name, ', ' ORDER BY e.added_at)
                                 FROM task_evidence e JOIN document d ON d.id = e.document_id
                                 JOIN obligation_task et ON et.id = e.task_id
                                 WHERE et.obligation_id = o.id AND et.kind = 'OBLIGATION' AND et.status <> 'CANCELLED'
                                   AND et.due_date BETWEEN ? AND ?), '')
                FROM permit_obligation o
                LEFT JOIN obligation_task t ON t.obligation_id = o.id AND t.kind = 'OBLIGATION'
                     AND t.status <> 'CANCELLED' AND t.due_date BETWEEN ? AND ?
                LEFT JOIN LATERAL (SELECT (t.done_at AT TIME ZONE ?)::date AS done_on) x ON TRUE
                WHERE o.permit_item_id = ? AND (o.status = 'ACTIVE' OR t.id IS NOT NULL)
                GROUP BY o.id ORDER BY o.id
                """, today, today, from, to, from, to, zone, permitId);

        // limit, % and 판정 are the snapshot stored with the value, not today's limit
        List<List<Object>> measurements = rows("""
                SELECT ep.name, p.name, m.measured_on::text, m.value, m.unit, m.limit_lower, m.limit_value,
                       CASE WHEN m.limit_value IS NULL OR m.below_detection OR m.limit_lower IS NOT NULL THEN NULL
                            ELSE round(m.value * 100 / m.limit_value, 1) END,
                       CASE m.level WHEN 'NORMAL' THEN '정상' WHEN 'NEAR' THEN '근접' WHEN 'EXCEEDED' THEN '초과'
                            ELSE '한도 없음' END,
                       CASE WHEN m.below_detection THEN '불검출(값은 정량한계)' ELSE '' END
                FROM measurement m JOIN emission_point ep ON ep.id = m.emission_point_id
                JOIN pollutant p ON p.code = m.pollutant
                WHERE m.emission_point_id IN (%s) AND m.deleted_at IS NULL AND m.measured_on BETWEEN ? AND ?
                ORDER BY ep.name, ep.id, p.sort, m.measured_on, m.id
                """.formatted(in), from, to);

        // by the day of the exceeding value; a cancelled task or a deleted (mis-entered) value is left out
        List<List<Object>> correctives = rows("""
                SELECT ep.name, p.name, m.measured_on::text, m.value, m.limit_lower, m.limit_value, m.unit, t.title,
                       CASE WHEN t.status = 'DONE' THEN '완료' WHEN t.due_date < ? THEN '기한 경과' ELSE '열림' END,
                       t.due_date::text, COALESCE((t.done_at AT TIME ZONE ?)::date::text, ''), COALESCE(t.note, '')
                FROM obligation_task t JOIN measurement m ON m.id = t.measurement_id
                JOIN emission_point ep ON ep.id = m.emission_point_id JOIN pollutant p ON p.code = m.pollutant
                WHERE t.project_id = ? AND t.kind = 'CORRECTIVE' AND t.status <> 'CANCELLED' AND m.deleted_at IS NULL
                  AND m.emission_point_id IN (%s) AND m.measured_on BETWEEN ? AND ?
                ORDER BY m.measured_on, t.id
                """.formatted(in), today, zone, projectId, from, to);

        // revision_implementation has no created date: finished in the period, or still open with a target up to its end
        List<List<Object>> implementations = rows("""
                SELECT COALESCE(o.citation_raw, ''), o.summary, COALESCE(a.name, ''), COALESCE(r.target_date::text, ''),
                       COALESCE((r.done_at AT TIME ZONE ?)::date::text, ''), COALESCE(r.note, '')
                FROM revision_implementation r JOIN permit_obligation o ON o.id = r.obligation_id
                LEFT JOIN users a ON a.id = r.assignee_id
                WHERE o.permit_item_id = ? AND ((r.done_at AT TIME ZONE ?)::date BETWEEN ? AND ?
                      OR (r.done_at IS NULL AND (r.target_date IS NULL OR r.target_date <= ?)))
                ORDER BY r.suggestion_id
                """, zone, permitId, zone, from, to, to);

        String period = from.toString().replace("-", "") + "-" + to.toString().replace("-", "");
        String permitName = permit.getName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return new Report(
                "이행보고서초안_" + permitName.substring(0, Math.min(permitName.length(), 100)) + "_" + period + ".xlsx",
                from, to, List.of(
                new Sheet("개요", DRAFT, List.of("항목", "내용"), overview),
                new Sheet("의무사항 이행 내역", DRAFT, List.of("근거", "요약", "주기", "기간 내 회차", "완료", "지연 완료",
                        "미이행", "기한 전", "완료일", "증빙 파일명"), obligations),
                new Sheet("측정 결과", DRAFT, List.of("배출구", "항목", "측정일", "값", "단위", "한도 하한", "한도", "한도 대비 %",
                        "판정", "비고"), measurements),
                new Sheet("초과 및 조치", DRAFT, List.of("배출구", "항목", "측정일", "초과 측정값", "한도 하한", "한도", "단위",
                        "조치 업무", "상태", "기한", "조치 완료일", "메모"), correctives),
                new Sheet("현장 반영", DRAFT, List.of("근거", "요약", "담당", "목표일", "완료일", "메모"), implementations)));
    }

    /** Row 0 = the draft note, row 1 = headers, then the data. Streamed (100 rows in memory); rows are capped in {@link #load}. */
    public byte[] xlsx(Report report) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Sheet s : report.sheets()) {
                var sheet = wb.createSheet(s.name());
                sheet.createRow(0).createCell(0).setCellValue(s.note());
                Row header = sheet.createRow(1);
                for (int c = 0; c < s.headers().size(); c++) {
                    header.createCell(c).setCellValue(s.headers().get(c));
                    sheet.setColumnWidth(c, 20 * 256); // fixed: autosizing needs fonts the server image does not have
                }
                int n = 2;
                for (List<Object> values : s.rows()) {
                    Row row = sheet.createRow(n++);
                    for (int c = 0; c < values.size(); c++) {
                        Cell cell = row.createCell(c);
                        if (values.get(c) instanceof Number number) {
                            cell.setCellValue(number.doubleValue());
                        } else {
                            cell.setCellValue(safe(values.get(c).toString()));
                        }
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Formula injection: text a spreadsheet would run as a formula is stored as plain text behind an apostrophe. */
    static String safe(String text) {
        return !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    private List<List<Object>> rows(String sql, Object... args) {
        List<List<Object>> rows = jdbc.query(sql + " LIMIT " + (MAX_ROWS + 1), ROW, args);
        if (rows.size() > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, TOO_MANY);
        }
        return rows;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
