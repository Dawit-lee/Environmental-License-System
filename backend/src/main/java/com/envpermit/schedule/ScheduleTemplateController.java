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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The company's schedule templates (설계 §5-1). Everyone reads them; only the company master adds, edits and deletes items. */
@RestController
class ScheduleTemplateController {

    private final JdbcTemplate jdbc;
    private final Org org;

    ScheduleTemplateController(JdbcTemplate jdbc, Org org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    record TemplateItem(long id, String disciplineCode, String kind, String title, String anchor, Integer offsetDays,
                        Integer offsetMonths, Integer durationDays, boolean submission) {
    }

    record Template(long id, String code, String name, List<TemplateItem> items) {
    }

    /**
     * An item's editable fields: exactly one of offsetDays / offsetMonths; a task needs its length. kind and
     * disciplineCode (null = 공통) are read on create only. submission: 제출·보고 milestone (null = keep / false).
     */
    record ItemRequest(@NotBlank @Size(max = 200) String title,
                       @NotNull @Pattern(regexp = ScheduleController.ANCHOR_PATTERN) String anchor,
                       Integer offsetDays, Integer offsetMonths, @Min(1) Integer durationDays,
                       @Pattern(regexp = "TASK|MILESTONE") String kind, @Size(max = 40) String disciplineCode,
                       Boolean submission) {
    }

    @GetMapping("/api/schedule-templates")
    List<Template> list(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.query("SELECT id, code, name FROM schedule_template WHERE company_id = ? ORDER BY id",
                (rs, n) -> new Template(rs.getLong("id"), rs.getString("code"), rs.getString("name"), items(rs.getLong("id"))),
                org.companyOf(jwt));
    }

    /** Added at the end of the template. */
    @PostMapping("/api/schedule-templates/{tid}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    TemplateItem create(@AuthenticationPrincipal Jwt jwt, @PathVariable long tid, @Valid @RequestBody ItemRequest req) {
        master(jwt);
        long company = org.companyOf(jwt);
        if (jdbc.queryForList("SELECT id FROM schedule_template WHERE id = ? AND company_id = ? FOR UPDATE", Long.class,
                tid, company).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (req.kind() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "종류(작업·마일스톤)를 지정하세요.");
        }
        String code = req.disciplineCode() == null || req.disciplineCode().isBlank() ? null : req.disciplineCode();
        if (code != null && jdbc.queryForList("SELECT id FROM discipline WHERE company_id = ? AND code = ?", Long.class,
                company, code).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "없는 분야입니다.");
        }
        boolean milestone = check(req, req.kind());
        long id = jdbc.queryForObject("""
                INSERT INTO schedule_template_item (template_id, discipline_code, kind, title, anchor, offset_days,
                                                    offset_months, duration_days, submission, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                        (SELECT COALESCE(MAX(sort_order), 0) + 10 FROM schedule_template_item WHERE template_id = ?))
                RETURNING id""", Long.class, tid, code, req.kind(), req.title().trim(), req.anchor(), req.offsetDays(),
                req.offsetMonths(), milestone ? null : req.durationDays(),
                milestone && Boolean.TRUE.equals(req.submission()), tid);
        return items(tid).stream().filter(i -> i.id() == id).findFirst().orElseThrow();
    }

    @PatchMapping("/api/schedule-templates/{tid}/items/{id}")
    @Transactional
    TemplateItem update(@AuthenticationPrincipal Jwt jwt, @PathVariable long tid, @PathVariable long id,
                        @Valid @RequestBody ItemRequest req) {
        master(jwt);
        boolean milestone = check(req, kindOf(jwt, tid, id));
        jdbc.update("""
                UPDATE schedule_template_item SET title = ?, anchor = ?, offset_days = ?, offset_months = ?, duration_days = ?,
                       submission = ? AND COALESCE(CAST(? AS BOOLEAN), submission)
                WHERE id = ?""", req.title().trim(), req.anchor(), req.offsetDays(), req.offsetMonths(),
                milestone ? null : req.durationDays(), milestone, req.submission(), id);
        return items(tid).stream().filter(i -> i.id() == id).findFirst().orElseThrow();
    }

    /** Projects that already applied the template keep their items. */
    @DeleteMapping("/api/schedule-templates/{tid}/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long tid, @PathVariable long id) {
        master(jwt);
        kindOf(jwt, tid, id);
        jdbc.update("DELETE FROM schedule_template_item WHERE id = ?", id);
    }

    private void master(Jwt jwt) {
        org.masterCompanyOf(jwt);
    }

    /** 404 unless the item is in this company's template. */
    private String kindOf(Jwt jwt, long tid, long id) {
        List<String> kind = jdbc.queryForList("""
                SELECT ti.kind FROM schedule_template_item ti JOIN schedule_template t ON t.id = ti.template_id
                WHERE ti.id = ? AND t.id = ? AND t.company_id = ?""", String.class, id, tid, org.companyOf(jwt));
        if (kind.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return kind.get(0);
    }

    /** The offset and length rules; true for a milestone. */
    private static boolean check(ItemRequest req, String kind) {
        if ((req.offsetDays() == null) == (req.offsetMonths() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "오프셋은 일 또는 개월 중 하나만 지정합니다.");
        }
        boolean milestone = "MILESTONE".equals(kind);
        if (!milestone && req.durationDays() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작업은 기간(일)이 필요합니다.");
        }
        return milestone;
    }

    private List<TemplateItem> items(long templateId) {
        return jdbc.query("""
                SELECT id, discipline_code, kind, title, anchor, offset_days, offset_months, duration_days, submission
                FROM schedule_template_item WHERE template_id = ? ORDER BY sort_order, id""",
                (rs, n) -> new TemplateItem(rs.getLong("id"), rs.getString("discipline_code"), rs.getString("kind"),
                        rs.getString("title"), rs.getString("anchor"), (Integer) rs.getObject("offset_days"),
                        (Integer) rs.getObject("offset_months"), (Integer) rs.getObject("duration_days"),
                        rs.getBoolean("submission")), templateId);
    }
}
