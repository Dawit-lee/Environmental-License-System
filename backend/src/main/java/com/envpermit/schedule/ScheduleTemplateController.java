package com.envpermit.schedule;

import com.envpermit.core.org.Org;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The company's schedule templates (설계 §5-1). Everyone reads them; only the company master edits an item's offsets. */
@RestController
class ScheduleTemplateController {

    private final JdbcTemplate jdbc;
    private final Org org;

    ScheduleTemplateController(JdbcTemplate jdbc, Org org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    record TemplateItem(long id, String disciplineCode, String kind, String title, String anchor, Integer offsetDays,
                        Integer offsetMonths, Integer durationDays) {
    }

    record Template(long id, String code, String name, List<TemplateItem> items) {
    }

    /** Whole replacement of an item's editable fields: exactly one of offsetDays / offsetMonths; a task needs its length. */
    record ItemRequest(@NotBlank @Size(max = 200) String title,
                       @NotNull @Pattern(regexp = ScheduleController.ANCHOR_PATTERN) String anchor,
                       Integer offsetDays, Integer offsetMonths, @Min(1) Integer durationDays) {
    }

    @GetMapping("/api/schedule-templates")
    List<Template> list(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.query("SELECT id, code, name FROM schedule_template WHERE company_id = ? ORDER BY id",
                (rs, n) -> new Template(rs.getLong("id"), rs.getString("code"), rs.getString("name"), items(rs.getLong("id"))),
                org.companyOf(jwt));
    }

    @PatchMapping("/api/schedule-templates/{tid}/items/{id}")
    @Transactional
    TemplateItem update(@AuthenticationPrincipal Jwt jwt, @PathVariable long tid, @PathVariable long id,
                        @Valid @RequestBody ItemRequest req) {
        if (!"ADMIN".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회사 마스터만 변경할 수 있습니다.");
        }
        List<String> kind = jdbc.queryForList("""
                SELECT ti.kind FROM schedule_template_item ti JOIN schedule_template t ON t.id = ti.template_id
                WHERE ti.id = ? AND t.id = ? AND t.company_id = ?""", String.class, id, tid, org.companyOf(jwt));
        if (kind.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if ((req.offsetDays() == null) == (req.offsetMonths() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "오프셋은 일 또는 개월 중 하나만 지정합니다.");
        }
        boolean milestone = "MILESTONE".equals(kind.get(0));
        if (!milestone && req.durationDays() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업은 기간(일)이 필요합니다.");
        }
        jdbc.update("""
                UPDATE schedule_template_item SET title = ?, anchor = ?, offset_days = ?, offset_months = ?, duration_days = ?
                WHERE id = ?""", req.title().trim(), req.anchor(), req.offsetDays(), req.offsetMonths(),
                milestone ? null : req.durationDays(), id);
        return items(tid).stream().filter(i -> i.id() == id).findFirst().orElseThrow();
    }

    private List<TemplateItem> items(long templateId) {
        return jdbc.query("""
                SELECT id, discipline_code, kind, title, anchor, offset_days, offset_months, duration_days
                FROM schedule_template_item WHERE template_id = ? ORDER BY sort_order, id""",
                (rs, n) -> new TemplateItem(rs.getLong("id"), rs.getString("discipline_code"), rs.getString("kind"),
                        rs.getString("title"), rs.getString("anchor"), (Integer) rs.getObject("offset_days"),
                        (Integer) rs.getObject("offset_months"), (Integer) rs.getObject("duration_days")), templateId);
    }
}
