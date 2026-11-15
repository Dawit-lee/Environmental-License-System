package com.envpermit.schedule;

import com.envpermit.review.AiSuggestion;
import com.envpermit.review.Kind;
import com.envpermit.review.SuggestionApplier;
import com.envpermit.schedule.EventController.EventRequest;
import com.envpermit.schedule.ScheduleSuggestion.Interval;
import com.envpermit.schedule.ScheduleSuggestion.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * PROJECT_EVENT (EVENT/RECURRING rows of a document) → project_event, through {@link EventController}'s own rules.
 * EVENT: matchId (or a similar event found now, unless createNew) is moved instead of adding one; no automatic
 * milestone (the document's own MILESTONE rows are reviewed separately). RECURRING (정기 보고): one event per
 * occurrence inside the 과업기간 (the row's startDate..date, else the project's 착수일..완료일), at most 24; an
 * occurrence that already exists (a second run over the same document) is kept, not added again.
 */
@Component
class ProjectEventApplier implements SuggestionApplier {

    static final int MAX_OCCURRENCES = 24;

    private final EventController events;
    private final ScheduleSuggestions suggestions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Validator validator;

    ProjectEventApplier(EventController events, ScheduleSuggestions suggestions, JdbcTemplate jdbc, ObjectMapper json,
                        Validator validator) {
        this.events = events;
        this.suggestions = suggestions;
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
    }

    @Override
    public Kind kind() {
        return Kind.PROJECT_EVENT;
    }

    @Override
    public void checkTarget(long projectId, Long targetParentId) {
        ScheduleSuggestions.checkTarget(projectId, targetParentId);
    }

    @Override
    public void validate(JsonNode payload) {
        parse(payload);
    }

    private ScheduleSuggestion parse(JsonNode payload) {
        ScheduleSuggestion r = ScheduleSuggestion.parse(json, validator, payload);
        if (r.type() != Type.EVENT && r.type() != Type.RECURRING) {
            throw ScheduleSuggestion.unprocessable("type: 이벤트는 EVENT 또는 RECURRING입니다.");
        }
        if (r.kind() == null || !r.kind().matches(EventController.KINDS)) {
            throw ScheduleSuggestion.unprocessable("kind: 알 수 없는 이벤트 종류입니다.");
        }
        if (r.type() == Type.EVENT && r.startsAt() == null && r.date() == null) {
            throw ScheduleSuggestion.unprocessable("startsAt: 일시 또는 날짜가 필요합니다.");
        }
        if (r.type() == Type.RECURRING && r.interval() == null) {
            throw ScheduleSuggestion.unprocessable("interval: 정기 보고는 주기가 필요합니다.");
        }
        if (r.startsAt() != null && r.endsAt() != null && r.endsAt().isBefore(r.startsAt())) {
            throw ScheduleSuggestion.unprocessable("endsAt: 종료가 시작보다 이릅니다.");
        }
        return r;
    }

    @Override
    public long apply(AiSuggestion s, JsonNode payload, ApplyContext ctx) {
        ScheduleSuggestion r = parse(payload);
        long pid = s.getProjectId();
        if (r.type() == Type.RECURRING) {
            List<LocalDate> days = occurrences(pid, r);
            LocalTime time = r.startsAt() == null ? null : r.startsAt().toLocalTime();
            long first = -1;
            for (int n = 0; n < days.size(); n++) {
                LocalDateTime at = time == null ? days.get(n).atStartOfDay() : days.get(n).atTime(time);
                String title = r.title() + " (" + (n + 1) + "회)";
                Long same = suggestions.similarEvent(pid, r.kind(), title, days.get(n)); // approved before: keep it
                long id = same != null ? same : events.create(pid, ctx.actorId(), request(pid, r, title, at, at, time == null));
                if (same == null) events.applied(id, s.getId());
                first = first < 0 ? id : first;
            }
            return first;
        }
        boolean allDay = r.startsAt() == null;
        LocalDateTime start = allDay ? r.date().atStartOfDay() : r.startsAt();
        LocalDateTime end = r.endsAt() != null ? r.endsAt() : start;
        Long target = r.matchId() != null ? r.matchId()
                : Boolean.TRUE.equals(r.createNew()) ? null : suggestions.similar(pid, r);
        long id;
        if (target == null) {
            id = events.create(pid, ctx.actorId(), request(pid, r, r.title(), start, end, allDay));
        } else {
            EventRequest c;
            try {
                c = events.requestOf(pid, target);
            } catch (ResponseStatusException e) {
                throw ScheduleSuggestion.unprocessable("갱신할 기존 이벤트가 없습니다. 새 이벤트로 승인하세요.");
            }
            events.update(pid, target, new EventRequest(c.kind(), c.subkind(), c.title(), start, end, allDay,
                    r.location() != null ? r.location() : c.location(), c.counterpartOrg(), c.purpose(),
                    c.disciplineId(), c.checklist(), c.attendees(), false, null));
            id = target;
        }
        events.applied(id, s.getId());
        return id;
    }

    private EventRequest request(long pid, ScheduleSuggestion r, String title, LocalDateTime start, LocalDateTime end,
                                 boolean allDay) {
        return new EventRequest(r.kind(), r.subkind(), title, start, end, allDay, r.location(), r.counterpartOrg(),
                r.purpose(), suggestions.disciplineId(pid, r.discipline()), null, null, false, null);
    }

    /** First occurrence one interval after the period start (월 1회 = 1개월 뒤부터), then every interval to its end. */
    private List<LocalDate> occurrences(long pid, ScheduleSuggestion r) {
        Map<String, Object> p = jdbc.queryForMap("SELECT start_date, expected_end_date FROM project WHERE id = ?", pid);
        LocalDate from = r.startDate() != null ? r.startDate() : toLocalDate(p.get("start_date"));
        LocalDate to = r.date() != null ? r.date() : toLocalDate(p.get("expected_end_date"));
        if (from == null || to == null) {
            throw ScheduleSuggestion.unprocessable("정기 보고를 펼치려면 과업기간(착수일·완료일)이 필요합니다.");
        }
        List<LocalDate> days = new ArrayList<>();
        for (int n = 1; days.size() < MAX_OCCURRENCES; n++) {
            LocalDate d = step(from, r.interval(), n); // from the start each time, so 1/31 + 1개월 stays month-end
            if (d.isAfter(to)) break;
            days.add(d);
        }
        if (days.isEmpty()) {
            throw ScheduleSuggestion.unprocessable("과업기간 안에 정기 보고 회차가 없습니다.");
        }
        return days;
    }

    static LocalDate step(LocalDate from, Interval interval, int n) {
        return switch (interval) {
            case WEEKLY -> from.plusWeeks(n);
            case BIWEEKLY -> from.plusWeeks(2L * n);
            case MONTHLY -> from.plusMonths(n);
            case QUARTERLY -> from.plusMonths(3L * n);
        };
    }

    private static LocalDate toLocalDate(Object sqlDate) {
        return sqlDate == null ? null : ((java.sql.Date) sqlDate).toLocalDate();
    }
}
