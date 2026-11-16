package com.envpermit.schedule;

import com.envpermit.review.AiSuggestion;
import com.envpermit.review.Kind;
import com.envpermit.review.SuggestionApplier;
import com.envpermit.schedule.ScheduleController.ItemRequest;
import com.envpermit.schedule.ScheduleSuggestion.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * SCHEDULE_ITEM (MILESTONE/TASK rows of a document) → schedule_item, through {@link ScheduleController}'s own create
 * and update rules. matchId (or, unless createNew, a similar item found now) is updated instead of adding one; an
 * update past the baseline gets the row's reason or "문서 반영: {문서명}". History: APPLIED_FROM_SUGGESTION.
 */
@Component
class ScheduleItemApplier implements SuggestionApplier {

    private final ScheduleController schedule;
    private final ScheduleSuggestions suggestions;
    private final ObjectMapper json;
    private final Validator validator;

    ScheduleItemApplier(ScheduleController schedule, ScheduleSuggestions suggestions, ObjectMapper json,
                        Validator validator) {
        this.schedule = schedule;
        this.suggestions = suggestions;
        this.json = json;
        this.validator = validator;
    }

    @Override
    public Kind kind() {
        return Kind.SCHEDULE_ITEM;
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
        if (r.type() != Type.MILESTONE && r.type() != Type.TASK) {
            throw ScheduleSuggestion.unprocessable("type: 일정 항목은 MILESTONE 또는 TASK입니다.");
        }
        if (r.anchor() != null) {
            if (!ScheduleController.ANCHORS.contains(r.anchor())) {
                throw ScheduleSuggestion.unprocessable("anchor: 알 수 없는 기준일입니다.");
            }
            if ((r.offsetDays() == null) == (r.offsetMonths() == null)) {
                throw ScheduleSuggestion.unprocessable("offsetDays: 기준일 규칙은 일 또는 개월 오프셋 하나가 필요합니다.");
            }
            if (r.type() == Type.TASK && r.durationDays() == null) {
                throw ScheduleSuggestion.unprocessable("durationDays: 기준일에 연결된 작업은 기간(일)이 필요합니다.");
            }
        } else {
            if (r.date() == null) {
                throw ScheduleSuggestion.unprocessable("date: 날짜 또는 기준일 규칙이 필요합니다.");
            }
            if (r.type() == Type.TASK && r.startDate() == null && r.durationDays() == null) {
                throw ScheduleSuggestion.unprocessable("startDate: 작업은 시작일 또는 기간(일)이 필요합니다.");
            }
        }
        if (r.durationDays() != null && r.durationDays() < 1) {
            throw ScheduleSuggestion.unprocessable("durationDays: 1 이상이어야 합니다.");
        }
        return r;
    }

    @Override
    public long apply(AiSuggestion s, JsonNode payload, ApplyContext ctx) {
        ScheduleSuggestion r = parse(payload);
        long pid = s.getProjectId();
        boolean ms = r.type() == Type.MILESTONE;
        LocalDate start = r.anchor() != null || ms ? null
                : r.startDate() != null ? r.startDate() : r.date().minusDays(r.durationDays() - 1);
        Long target = r.matchId() != null ? r.matchId()
                : Boolean.TRUE.equals(r.createNew()) ? null : suggestions.similar(pid, r);
        long id;
        if (target == null) {
            id = schedule.create(pid, ctx.actorId(), new ItemRequest(suggestions.disciplineId(pid, r.discipline()),
                    null, r.type().name(), r.title(), start, r.date(), r.anchor(), r.offsetDays(), r.offsetMonths(),
                    ms ? null : r.durationDays(), null, null, null, null, null, null, null, null));
        } else {
            ItemRequest current;
            try {
                current = schedule.requestOf(pid, target);
            } catch (ResponseStatusException e) {
                throw ScheduleSuggestion.unprocessable("갱신할 기존 항목이 없습니다. 새 항목으로 승인하세요.");
            }
            boolean currentMs = current.milestone();
            Integer duration = r.durationDays() != null ? r.durationDays() : current.durationDays();
            if (r.anchor() != null && !currentMs && duration == null) {
                // an item with fixed dates gets a relative rule: keep its length
                duration = current.endDate() == null ? 1
                        : (int) ChronoUnit.DAYS.between(current.startDate(), current.endDate()) + 1;
            }
            LocalDate newStart = currentMs || r.anchor() != null ? null
                    : start != null ? start : current.startDate() != null && !current.startDate().isAfter(r.date())
                    ? current.startDate() : r.date();
            schedule.update(pid, target, ctx.actorId(), current.withDates(newStart, r.date(), r.anchor(),
                    r.offsetDays(), r.offsetMonths(), currentMs ? null : duration, ScheduleSuggestions.reason(r)));
            id = target;
        }
        schedule.applied(id, ctx.actorId(), s.getId(), target == null, r.documentName());
        return id;
    }
}
