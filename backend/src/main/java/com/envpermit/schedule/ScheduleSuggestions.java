package com.envpermit.schedule;

import com.envpermit.schedule.ScheduleSuggestion.Type;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Server side of document → schedule rows (설계 §4.1 4): "기존 항목과 비슷함" (same kind + similar title + date ±3
 * days → matchId, so approval updates instead of adding) and the 확인 필요 labels. Used at extraction time and again
 * at approval, so two runs over the same document never add the same item twice.
 */
@Component
public class ScheduleSuggestions {

    static final int NEAR_DAYS = 3;
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final JdbcTemplate jdbc;
    private final ScheduleController schedule;

    ScheduleSuggestions(JdbcTemplate jdbc, ScheduleController schedule) {
        this.jdbc = jdbc;
        this.schedule = schedule;
    }

    /** matchId (when similar to a live item or event) and 확인 필요 labels added to an extracted row. */
    public ScheduleSuggestion annotate(long pid, ScheduleSuggestion r) {
        Map<String, LocalDate> bases = schedule.anchorDates(pid);
        List<String> checks = new ArrayList<>();
        if (r.yearInferred()) checks.add(ScheduleSuggestion.YEAR_INFERRED);
        if (r.anchor() != null) checks.add(ScheduleSuggestion.RELATIVE_RULE);
        if (r.anchor() != null && bases.get(r.anchor()) == null) checks.add(ScheduleSuggestion.BASE_DATE_MISSING);
        return r.annotated(similar(pid, r, bases), checks);
    }

    /** The live item/event this row would duplicate, or null. RECURRING rows are matched per occurrence instead. */
    Long similar(long pid, ScheduleSuggestion r) {
        return similar(pid, r, schedule.anchorDates(pid));
    }

    private Long similar(long pid, ScheduleSuggestion r, Map<String, LocalDate> bases) {
        if (r.type() == Type.RECURRING) return null;
        if (r.type() == Type.EVENT) {
            return similarEvent(pid, r.kind(), r.title(), r.startsAt() != null ? r.startsAt().toLocalDate() : r.date());
        }
        LocalDate end = r.anchor() == null ? r.date() : resolvedEnd(r, bases.get(r.anchor()));
        return jdbc.query("""
                SELECT id, title, end_date, anchor, offset_days, offset_months FROM schedule_item
                WHERE project_id = ? AND kind = ? AND deleted_at IS NULL ORDER BY id""", (rs, n) -> {
            if (!similarTitle(r.title(), rs.getString(2))) return null;
            LocalDate itemEnd = rs.getObject(3, LocalDate.class);
            boolean sameRule = r.anchor() != null && r.anchor().equals(rs.getString(4))
                    && Objects.equals(r.offsetDays(), rs.getObject(5)) && Objects.equals(r.offsetMonths(), rs.getObject(6));
            return sameRule || near(end, itemEnd) ? rs.getLong(1) : null;
        }, pid, r.type().name()).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** A live event of this kind with a similar title within ±3 days, or null (also one RECURRING occurrence). */
    Long similarEvent(long pid, String kind, String title, LocalDate day) {
        return jdbc.query("""
                SELECT id, title, starts_at::date FROM project_event WHERE project_id = ? AND kind = ? ORDER BY id""",
                (rs, n) -> similarTitle(title, rs.getString(2)) && near(day, rs.getObject(3, LocalDate.class))
                        ? rs.getLong(1) : null, pid, kind).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** A discipline named in the document, by exact name in the project's company; null = 공통. */
    Long disciplineId(long pid, String name) {
        if (name == null || name.isBlank()) return null;
        return jdbc.queryForList("""
                SELECT d.id FROM discipline d JOIN project p ON p.company_id = d.company_id
                WHERE p.id = ? AND d.name = ? ORDER BY d.id""", Long.class, pid, name.trim()).stream().findFirst().orElse(null);
    }

    /** checkTarget of both appliers: the suggestion hangs off its own project. */
    static void checkTarget(long projectId, Long targetParentId) {
        if (targetParentId == null || targetParentId != projectId) {
            throw ScheduleSuggestion.unprocessable("이 프로젝트의 일정 제안이 아닙니다.");
        }
    }

    /** Default 연기 사유 when an update moves past the baseline (설계 §4.1 5). */
    static String reason(ScheduleSuggestion r) {
        return r.reason() != null && !r.reason().isBlank() ? r.reason().trim()
                : "문서 반영: " + Objects.requireNonNullElse(r.documentName(), "제안");
    }

    private static LocalDate resolvedEnd(ScheduleSuggestion r, LocalDate base) {
        ScheduleDates.Range range = ScheduleDates.resolve(base, r.offsetDays(), r.offsetMonths(), 1, true);
        return range == null ? null : range.end();
    }

    private static boolean near(LocalDate a, LocalDate b) {
        return a != null && b != null && Math.abs(ChronoUnit.DAYS.between(a, b)) <= NEAR_DAYS;
    }

    /** Same words ignoring spaces and punctuation, or one contains the other ("중간보고회" ~ "중간 보고회 개최"). */
    static boolean similarTitle(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        return x.length() >= 2 && y.length() >= 2 && (x.contains(y) || y.contains(x));
    }

    private static String norm(String s) {
        return s == null ? "" : NOT_WORD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
