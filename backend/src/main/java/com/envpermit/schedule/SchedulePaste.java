package com.envpermit.schedule;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for a block copied from Excel (설계 §5-3): tab-separated 분야 | 작업 | 시작 | 종료 | 담당 | 보할.
 * Every non-blank line becomes a row; a bad line carries its error instead of failing the whole block.
 * No start (or start = end) is a milestone. A first line whose 작업 column reads "작업" is a header and skipped.
 */
final class SchedulePaste {

    record Row(int line, String discipline, Long disciplineId, String kind, String title, LocalDate start, LocalDate end,
               String owner, Long ownerUserId, BigDecimal weight, String error) {
    }

    private static final Pattern DATE = Pattern.compile("(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})\\.?");

    private SchedulePaste() {
    }

    /** disciplines and members: name → id. */
    static List<Row> parse(String text, Map<String, Long> disciplines, Map<String, Long> members) {
        List<Row> rows = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].isBlank()) continue;
            String[] c = lines[n].split("\t", -1);
            String discipline = col(c, 0);
            String title = col(c, 1);
            if (rows.isEmpty() && "작업".equals(title)) continue;
            String owner = col(c, 4);
            List<String> errors = new ArrayList<>();
            Long disciplineId = discipline.isEmpty() ? null : disciplines.get(discipline);
            if (!discipline.isEmpty() && disciplineId == null) errors.add("없는 분야: " + discipline);
            if (title.isEmpty()) errors.add("작업명이 없습니다");
            if (title.length() > 200) errors.add("작업명은 200자까지입니다");
            LocalDate start = date(col(c, 2), "시작일", errors);
            LocalDate end = date(col(c, 3), "종료일", errors);
            if (end == null && col(c, 3).isEmpty()) errors.add("종료일이 없습니다");
            if (start != null && end != null && start.isAfter(end)) errors.add("시작일이 종료일보다 늦습니다");
            Long ownerUserId = owner.isEmpty() ? null : members.get(owner);
            if (!owner.isEmpty() && ownerUserId == null) errors.add("프로젝트 멤버가 아닌 담당: " + owner);
            BigDecimal weight = weight(col(c, 5), errors);
            boolean milestone = start == null || start.equals(end);
            rows.add(new Row(n + 1, discipline, disciplineId, milestone ? "MILESTONE" : "TASK", title,
                    milestone ? end : start, end, owner, ownerUserId, weight,
                    errors.isEmpty() ? null : String.join(", ", errors)));
        }
        return rows;
    }

    private static String col(String[] c, int i) {
        return i < c.length ? c[i].trim() : "";
    }

    private static LocalDate date(String s, String label, List<String> errors) {
        if (s.isEmpty()) return null;
        Matcher m = DATE.matcher(s);
        try {
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
        } catch (DateTimeException e) {
            // falls through to the error below
        }
        errors.add(label + " 형식 오류(YYYY-MM-DD): " + s);
        return null;
    }

    private static BigDecimal weight(String s, List<String> errors) {
        if (s.isEmpty()) return null;
        try {
            BigDecimal w = new BigDecimal(s.replace("%", "").trim());
            if (w.signum() >= 0 && w.compareTo(BigDecimal.valueOf(100)) <= 0 && w.scale() <= 2) return w;
        } catch (NumberFormatException e) {
            // falls through
        }
        errors.add("보할은 0~100 숫자입니다: " + s);
        return null;
    }
}
