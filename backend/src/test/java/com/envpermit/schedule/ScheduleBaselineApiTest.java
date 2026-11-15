package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ticket design-schedule/03: base dates, baseline, delays, templates, paste. Today is 2026-01-05. */
class ScheduleBaselineApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    long companyA;
    long kimId; // project admin (creator)
    long leeId; // plain member
    long projectId;
    String kim;
    String lee;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        companyA = companies.save(new Company("가온엔지니어링")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.USER, companyA)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이가온", User.Role.USER, companyA)).getId();
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 하수처리장 턴키","facilityType":"SEWAGE","location":"가온시","startDate":"2026-02-01",
                 "expectedEndDate":"2026-12-31"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void base_date_move_previews_then_moves_only_open_relative_items() throws Exception {
        anchor("SUBMISSION", "2027-07-12", false);
        long pid = item("{\"kind\":\"MILESTONE\",\"title\":\"P&ID 1차\",\"anchor\":\"SUBMISSION\",\"offsetDays\":-90}");
        long task = item("""
                {"kind":"TASK","title":"공정 기본설계","anchor":"SUBMISSION","offsetDays":-60,"durationDays":85}""");
        long pinned = item("{\"kind\":\"MILESTONE\",\"title\":\"발주처 보고회\",\"endDate\":\"2027-05-20\"}");
        long done = item("{\"kind\":\"MILESTONE\",\"title\":\"합사 개설\",\"anchor\":\"SUBMISSION\",\"offsetDays\":-200}");
        long kickoff = item("{\"kind\":\"MILESTONE\",\"title\":\"착수보고\",\"anchor\":\"KICKOFF\",\"offsetDays\":15}");
        call(lee, json(post(items() + "/" + done + "/complete"), "{\"actualEnd\":\"2026-01-05\"}")).andExpect(status().isOk());
        call(lee, get(schedule()))
                .andExpect(jsonPath(at(pid, "endDate")).value("2027-04-13"))
                .andExpect(jsonPath(at(task, "startDate")).value("2027-02-18"))
                .andExpect(jsonPath(at(kickoff, "endDate")).value("2026-02-16"));
        call(lee, post(schedule() + "/baseline")).andExpect(jsonPath("$.baselineNo").value(1));

        call(lee, json(put(schedule() + "/anchors/SUBMISSION?preview=true"), "{\"date\":\"2027-07-26\"}"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.moves[0].id").value((int) pid))
                .andExpect(jsonPath("$.moves[0].fromEnd").value("2027-04-13"))
                .andExpect(jsonPath("$.moves[0].toEnd").value("2027-04-27"))
                .andExpect(jsonPath("$.moves[1].id").value((int) task));
        call(lee, get(schedule())).andExpect(jsonPath(at(pid, "endDate")).value("2027-04-13")); // preview changes nothing

        call(lee, json(put(schedule() + "/anchors/SUBMISSION?preview=false"), "{\"date\":\"2027-07-26\",\"note\":\"공고 정정\"}"))
                .andExpect(jsonPath("$.count").value(2));
        call(lee, get(schedule()))
                .andExpect(jsonPath(at(pid, "endDate")).value("2027-04-27"))
                .andExpect(jsonPath(at(pid, "baselineEnd")).value("2027-04-13")) // baseline stays
                .andExpect(jsonPath(at(pid, "slipDays")).value(14))
                .andExpect(jsonPath(at(pid, "delayReason")).value("기준일 변경: 입찰(제출)일 7/12 → 7/26"))
                .andExpect(jsonPath(at(task, "endDate")).value("2027-05-27"))
                .andExpect(jsonPath(at(pinned, "endDate")).value("2027-05-20"))
                .andExpect(jsonPath(at(done, "endDate")).value("2026-12-24"))
                .andExpect(jsonPath(at(kickoff, "endDate")).value("2026-02-16"))
                .andExpect(jsonPath("$.anchors[2].anchor").value("SUBMISSION"))
                .andExpect(jsonPath("$.anchors[2].date").value("2027-07-26"))
                .andExpect(jsonPath("$.anchors[2].note").value("공고 정정"));
        assertThat(jdbc.queryForObject("""
                SELECT data->>'reason' FROM schedule_item_event WHERE item_id = ? AND type = 'DATES_CHANGED'""",
                String.class, pid)).isEqualTo("기준일 변경: 입찰(제출)일 7/12 → 7/26");

        // 착수일 on the project form moves the KICKOFF items the same way
        call(kim, json(put("/api/projects/" + projectId), """
                {"name":"가온시 하수처리장 턴키","facilityType":"SEWAGE","location":"가온시","startDate":"2026-02-11",
                 "expectedEndDate":"2026-12-31"}""")).andExpect(status().isOk());
        call(lee, get(schedule())).andExpect(jsonPath(at(kickoff, "endDate")).value("2026-02-26"))
                .andExpect(jsonPath(at(kickoff, "delayReason")).value("기준일 변경: 착수일 2/1 → 2/11"));
        call(lee, json(put(schedule() + "/anchors/BOGUS"), "{\"date\":\"2027-07-26\"}")).andExpect(status().isNotFound());
    }

    @Test
    void pin_and_link_back_keep_the_dates_and_leave_history() throws Exception {
        anchor("SUBMISSION", "2027-07-12", false);
        long id = item("{\"kind\":\"MILESTONE\",\"title\":\"중간보고회\",\"anchor\":\"SUBMISSION\",\"offsetDays\":-60}");
        // [날짜 고정]: anchor cleared, same dates
        call(lee, json(patch(items() + "/" + id), """
                {"kind":"MILESTONE","title":"중간보고회","endDate":"2027-05-13"}"""))
                .andExpect(jsonPath("$.anchor").doesNotExist()).andExpect(jsonPath("$.endDate").value("2027-05-13"));
        anchor("SUBMISSION", "2027-08-12", false);
        call(lee, get(schedule())).andExpect(jsonPath(at(id, "endDate")).value("2027-05-13")); // pinned: did not move
        // [기준일에 연결]: offset computed back from the current date
        call(lee, json(patch(items() + "/" + id), """
                {"kind":"MILESTONE","title":"중간보고회","endDate":"2027-05-13","anchor":"SUBMISSION"}"""))
                .andExpect(jsonPath("$.anchor").value("SUBMISSION")).andExpect(jsonPath("$.offsetDays").value(-91));
        call(lee, json(patch(items() + "/" + id), """
                {"kind":"MILESTONE","title":"x","endDate":"2027-05-13","anchor":"REVIEW"}"""))
                .andExpect(status().isBadRequest()); // 심의일 not set
        assertThat(jdbc.queryForList("SELECT type FROM schedule_item_event WHERE item_id = ? ORDER BY id", String.class, id))
                .containsExactly("CREATED", "PINNED", "ANCHORED");
    }

    @Test
    void after_baseline_a_slip_needs_a_reason_and_plain_edits_keep_the_baseline() throws Exception {
        long id = item("{\"kind\":\"TASK\",\"title\":\"토목 기본도\",\"startDate\":\"2026-02-01\",\"endDate\":\"2026-02-20\"}");
        // before any baseline, no reason is needed
        call(lee, json(patch(items() + "/" + id), task("토목 기본도", "2026-02-25", null))).andExpect(status().isOk());
        call(lee, post(schedule() + "/baseline")).andExpect(status().isOk());

        call(lee, json(patch(items() + "/" + id), task("토목 기본도(개정)", "2026-02-25", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baselineEnd").value("2026-02-25"));
        call(lee, json(patch(items() + "/" + id), task("토목 기본도", "2026-03-04", null))).andExpect(status().isBadRequest());
        call(lee, json(patch(items() + "/" + id), task("토목 기본도", "2026-03-04", "  "))).andExpect(status().isBadRequest());
        call(lee, json(patch(items() + "/" + id), task("토목 기본도", "2026-03-04", "측량 성과 지연")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slipDays").value(7))
                .andExpect(jsonPath("$.delayReason").value("측량 성과 지연"))
                .andExpect(jsonPath("$.baselineEnd").value("2026-02-25"));
        // pulling it earlier needs none
        call(lee, json(patch(items() + "/" + id), task("토목 기본도", "2026-03-01", null))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT baseline_end FROM schedule_item WHERE id = ?", String.class, id))
                .isEqualTo("2026-02-25");
    }

    @Test
    void re_baseline_is_for_the_pm_or_project_admin_with_a_reason() throws Exception {
        long id = item("{\"kind\":\"MILESTONE\",\"title\":\"중간보고\",\"endDate\":\"2026-03-10\"}");
        call(lee, post(schedule() + "/baseline")).andExpect(status().isOk()); // the first one: any member
        call(lee, json(patch(items() + "/" + id), "{\"kind\":\"MILESTONE\",\"title\":\"중간보고\",\"endDate\":\"2026-03-20\",\"reason\":\"발주처 요청\"}"))
                .andExpect(status().isOk());

        call(lee, json(post(schedule() + "/baseline"), "{\"reason\":\"변경 공정표 승인\"}")).andExpect(status().isForbidden());
        call(kim, json(post(schedule() + "/baseline"), "{}")).andExpect(status().isBadRequest());
        call(kim, json(post(schedule() + "/baseline"), "{\"reason\":\"변경 공정표 승인\"}"))
                .andExpect(jsonPath("$.baselineNo").value(2))
                .andExpect(jsonPath(at(id, "baselineEnd")).value("2026-03-20"))
                .andExpect(jsonPath(at(id, "slipDays")).value(0));
        // the PM (not a project admin) may too
        jdbc.update("UPDATE project SET pm_user_id = ? WHERE id = ?", leeId, projectId);
        call(lee, json(post(schedule() + "/baseline"), "{\"reason\":\"3차\"}")).andExpect(jsonPath("$.baselineNo").value(3));
        assertThat(jdbc.queryForList("""
                SELECT data->'previous'->>'end' FROM schedule_item_event WHERE item_id = ? AND type = 'BASELINED' ORDER BY id""",
                String.class, id)).containsExactly(null, "2026-03-10", "2026-03-20");
    }

    @Test
    void delays_are_sorted_by_days_with_successors_and_reasons() throws Exception {
        long a = item("{\"kind\":\"TASK\",\"title\":\"P&ID 1차\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-20\"}");
        long b = item("{\"kind\":\"TASK\",\"title\":\"배치도\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-10\"}");
        item("{\"kind\":\"TASK\",\"title\":\"계측 설계\",\"startDate\":\"2026-02-01\",\"endDate\":\"2026-02-20\",\"predecessorIds\":[" + a + "]}");
        long late = item("{\"kind\":\"TASK\",\"title\":\"측량\",\"startDate\":\"2025-12-01\",\"endDate\":\"2026-01-02\"}");
        call(lee, post(schedule() + "/baseline")).andExpect(status().isOk());
        call(lee, json(patch(items() + "/" + a), task("P&ID 1차", "2026-02-09", "공정 변경"))).andExpect(status().isOk());
        call(lee, json(patch(items() + "/" + b), task("배치도", "2026-01-15", "건축 협의"))).andExpect(status().isOk());

        call(lee, get(schedule() + "/delays")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value((int) a))
                .andExpect(jsonPath("$[0].delayDays").value(20))
                .andExpect(jsonPath("$[0].baselineEnd").value("2026-01-20"))
                .andExpect(jsonPath("$[0].endDate").value("2026-02-09"))
                .andExpect(jsonPath("$[0].reason").value("공정 변경"))
                .andExpect(jsonPath("$[0].successors").value(1))
                .andExpect(jsonPath("$[1].id").value((int) b))
                .andExpect(jsonPath("$[1].delayDays").value(5))
                .andExpect(jsonPath("$[2].id").value((int) late)) // overdue 3 days, no move
                .andExpect(jsonPath("$[2].delayDays").value(3))
                .andExpect(jsonPath("$[2].slipDays").value(0));
        call(lee, get(schedule())).andExpect(jsonPath("$.delayCount").value(3));
    }

    @Test
    void template_items_get_dates_once_the_base_date_is_set() throws Exception {
        List<Integer> ids = JsonPath.read(call(lee, get("/api/schedule-templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("턴키 기본(입찰)설계"))
                .andExpect(jsonPath("$[0].items.length()").value(15))
                .andExpect(jsonPath("$[1].items.length()").value(5))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        call(lee, json(post(schedule() + "/apply-template"), "{\"templateId\":" + ids.get(1) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[?(@.title == '최종보고')].endDate").value("2026-12-16")) // 완료 −15
                .andExpect(jsonPath("$.items[?(@.title == '착수보고')].endDate").value(contains(nullValue()))) // 계약일 not set
                .andExpect(jsonPath("$.items[?(@.title == '착수보고')].status").value("PLANNED"));

        call(lee, json(put(schedule() + "/anchors/CONTRACT?preview=true"), "{\"date\":\"2026-01-31\"}"))
                .andExpect(jsonPath("$.count").value(3));
        anchor("CONTRACT", "2026-01-31", false);
        call(lee, get(schedule()))
                .andExpect(jsonPath("$.items[?(@.title == '과업수행계획서 제출')].endDate").value("2026-02-10"))
                .andExpect(jsonPath("$.items[?(@.title == '착수보고')].endDate").value("2026-02-15"))
                .andExpect(jsonPath("$.items[?(@.title == '중간보고')].endDate").value("2026-05-31")) // +4개월 1/31 → 5/31
                .andExpect(jsonPath("$.items[?(@.title == '중간보고')].disciplineName").value("공통"));

        // the turnkey one: relative to the bid date, with tasks
        anchor("SUBMISSION", "2027-07-12", false);
        call(lee, json(post(schedule() + "/apply-template"), "{\"templateId\":" + ids.get(0) + "}"))
                .andExpect(jsonPath("$.items[?(@.title == 'P&ID 1차')].endDate").value("2027-04-13"))
                .andExpect(jsonPath("$.items[?(@.title == '합사 개설')].endDate").value("2027-02-12"));

        // only the company master edits a template
        long first = ((Number) JsonPath.read(call(lee, get("/api/schedule-templates")).andReturn().getResponse()
                .getContentAsString(), "$[0].items[0].id")).longValue();
        String path = "/api/schedule-templates/" + ids.get(0) + "/items/" + first;
        call(lee, json(patch(path), "{\"title\":\"합사 개설\",\"anchor\":\"SUBMISSION\",\"offsetDays\":-160}")).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", kimId);
        String master = login("kim@a.com");
        call(master, json(patch(path), "{\"title\":\"합사 개설\",\"anchor\":\"SUBMISSION\",\"offsetDays\":-160,\"offsetMonths\":1}"))
                .andExpect(status().isBadRequest());
        call(master, json(patch(path), "{\"title\":\"합사 개설\",\"anchor\":\"SUBMISSION\",\"offsetDays\":-160}"))
                .andExpect(jsonPath("$.offsetDays").value(-160));
    }

    @Test
    void paste_previews_without_touching_the_db_then_confirms() throws Exception {
        String bad = "분야\t작업\t시작\t종료\t담당\t보할\n공정·기계\tP&ID 1차\t2026-01-05\t2026-02-10\t이가온\t60\n없는분야\t배치도\t\t2026-02-20\t\t40\n";
        call(lee, json(post(schedule() + "/paste"), body(bad, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.rows[1].line").value(3))
                .andExpect(jsonPath("$.rows[1].error").value("없는 분야: 없는분야"));
        call(lee, json(post(schedule() + "/paste"), body(bad, true))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_item", Integer.class)).isZero();

        String good = bad.replace("없는분야", "토목");
        call(lee, json(post(schedule() + "/paste"), body(good, false))).andExpect(jsonPath("$.errors").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_item", Integer.class)).isZero();
        call(lee, json(post(schedule() + "/paste"), body(good, true))).andExpect(jsonPath("$.created").value(2));
        call(lee, get(schedule()))
                .andExpect(jsonPath("$.items[0].title").value("P&ID 1차"))
                .andExpect(jsonPath("$.items[0].ownerName").value("이가온"))
                .andExpect(jsonPath("$.items[1].kind").value("MILESTONE"))
                // 공정률: 60 × 1/37 elapsed today, 40 × 0
                .andExpect(jsonPath("$.progress.planned").value(1.6))
                .andExpect(jsonPath("$.progress.actual").value(0.0))
                .andExpect(jsonPath("$.progress.weightTotal").value(100));
    }

    @Test
    void the_main_base_date_is_kickoff_until_changed() throws Exception {
        call(lee, get(schedule())).andExpect(jsonPath("$.base").value("KICKOFF")).andExpect(jsonPath("$.baselineNo").value(0))
                .andExpect(jsonPath("$.anchors[1].date").value("2026-02-01")).andExpect(jsonPath("$.progress").doesNotExist());
        call(lee, json(put(schedule() + "/base"), "{\"base\":\"SUBMISSION\"}")).andExpect(jsonPath("$.base").value("SUBMISSION"));
        call(lee, json(put(schedule() + "/base"), "{\"base\":\"REVIEW\"}")).andExpect(status().isBadRequest());
        String park = otherCompanyUser();
        call(park, get(schedule() + "/delays")).andExpect(status().isForbidden());
        call(park, post(schedule() + "/baseline")).andExpect(status().isForbidden());
        call(park, json(put(schedule() + "/anchors/SUBMISSION"), "{\"date\":\"2027-07-12\"}")).andExpect(status().isForbidden());
        call(park, json(post(schedule() + "/paste"), body("토목\tx\t\t2027-01-01", false))).andExpect(status().isForbidden());
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private String otherCompanyUser() throws Exception {
        long b = companies.save(new Company("다온설계")).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박다온", User.Role.ADMIN, b));
        return login("park@b.com");
    }

    private void anchor(String anchor, String date, boolean preview) throws Exception {
        call(lee, json(put(schedule() + "/anchors/" + anchor + "?preview=" + preview), "{\"date\":\"" + date + "\"}"))
                .andExpect(status().isOk());
    }

    private static String task(String title, String end, String reason) {
        return "{\"kind\":\"TASK\",\"title\":\"%s\",\"startDate\":\"2026-01-01\",\"endDate\":\"%s\"%s}"
                .formatted(title, end, reason == null ? "" : ",\"reason\":\"" + reason + "\"");
    }

    private static String body(String text, boolean confirm) {
        return "{\"text\":\"%s\",\"confirm\":%s}".formatted(text.replace("\t", "\\t").replace("\n", "\\n"), confirm);
    }

    private static String at(long id, String field) {
        return "$.items[?(@.id == %d)].%s".formatted(id, field);
    }

    private long item(String body) throws Exception {
        return id(call(lee, json(post(items()), body)).andExpect(status().isCreated()));
    }

    private String schedule() {
        return "/api/projects/" + projectId + "/schedule";
    }

    private String items() {
        return schedule() + "/items";
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
