package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.notification.NotificationJob;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ticket design-schedule/02. Today is 2026-01-05 (START, Asia/Seoul). */
class ScheduleApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired NotificationJob job;

    long companyA;
    long kimId; // project admin (creator), company ADMIN
    long leeId; // member
    long choId; // same company, not a member
    long projectId;
    String kim;
    String lee;
    String cho;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        companyA = companies.save(new Company("가온엔지니어링")).getId();
        long b = companies.save(new Company("다온설계")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.ADMIN, companyA)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이가온", User.Role.USER, companyA)).getId();
        choId = users.save(new User("cho@a.com", encoder.encode("pw"), "조가온", User.Role.USER, companyA)).getId();
        users.save(new User("park@b.com", encoder.encode("pw"), "박다온", User.Role.ADMIN, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        cho = login("cho@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 하수처리장 턴키","facilityType":"SEWAGE","location":"가온시"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
    }

    // --- disciplines ----------------------------------------------------------------------------------------

    @Test
    void every_company_gets_the_eleven_default_disciplines_and_only_admin_edits() throws Exception {
        call(lee, get("/api/disciplines")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[0].name").value("공통"))
                .andExpect(jsonPath("$[1].name").value("공정·기계"))
                .andExpect(jsonPath("$[10].name").value("성과품(편집·인쇄)"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discipline", Integer.class)).isEqualTo(22);

        call(lee, json(post("/api/disciplines"), "{\"name\":\"소방\"}")).andExpect(status().isForbidden());
        long civil = disciplineId("토목");
        call(lee, json(patch("/api/disciplines/" + civil), "{\"active\":false}")).andExpect(status().isForbidden());

        long fire = id(call(kim, json(post("/api/disciplines"), "{\"name\":\"소방\"}")).andExpect(status().isCreated()));
        call(kim, json(post("/api/disciplines"), "{\"name\":\"소방\"}")).andExpect(status().isConflict());
        call(kim, json(patch("/api/disciplines/" + fire), "{\"name\":\"소방·방재\",\"sortOrder\":5}"))
                .andExpect(jsonPath("$.name").value("소방·방재")).andExpect(jsonPath("$.active").value(true));
        call(kim, json(patch("/api/disciplines/" + civil), "{\"active\":false}")).andExpect(jsonPath("$.active").value(false));
        call(lee, get("/api/disciplines")).andExpect(jsonPath("$[0].name").value("소방·방재"));
        // another company's admin cannot touch ours
        call(login("park@b.com"), json(patch("/api/disciplines/" + civil), "{\"active\":true}")).andExpect(status().isNotFound());
    }

    // --- items ----------------------------------------------------------------------------------------------

    @Test
    void create_update_complete_and_delete_an_item() throws Exception {
        long process = disciplineId("공정·기계");
        long item = id(call(lee, json(post(items()), """
                {"disciplineId":%d,"kind":"TASK","title":"P&ID 1차","startDate":"2026-01-02","endDate":"2026-01-20",
                 "ownerUserId":%d,"partnerName":"가온구조"}""".formatted(process, leeId))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.disciplineName").value("공정·기계"))
                .andExpect(jsonPath("$.ownerName").value("이가온"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(0)));

        // milestone: one day, startDate ignored
        call(lee, json(post(items()), """
                {"kind":"MILESTONE","title":"중간보고회","startDate":"2026-01-01","endDate":"2026-02-10"}"""))
                .andExpect(jsonPath("$.startDate").value("2026-02-10")).andExpect(jsonPath("$.status").value("PLANNED"));
        call(lee, json(post(items()), """
                {"kind":"TASK","title":"거꾸로","startDate":"2026-02-10","endDate":"2026-02-01"}""")).andExpect(status().isBadRequest());
        call(lee, json(post(items()), """
                {"kind":"TASK","title":"담당 비멤버","startDate":"2026-02-01","endDate":"2026-02-03","ownerUserId":%d}"""
                .formatted(choId))).andExpect(status().isUnprocessableEntity());

        // a Gantt drag sends the full item back with new dates
        call(lee, json(patch(items() + "/" + item), """
                {"disciplineId":%d,"kind":"TASK","title":"P&ID 1차","startDate":"2026-01-05","endDate":"2026-01-23",
                 "ownerUserId":%d,"partnerName":"가온구조","version":0}""".formatted(process, leeId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endDate").value("2026-01-23"))
                .andExpect(jsonPath("$.version").value(1));
        call(lee, json(patch(items() + "/" + item), """
                {"kind":"TASK","title":"P&ID 1차","startDate":"2026-01-05","endDate":"2026-01-30","version":0}"""))
                .andExpect(status().isConflict()); // stale version

        call(lee, json(post(items() + "/" + item + "/complete"), "{\"actualEnd\":\"2026-01-06\"}"))
                .andExpect(status().isBadRequest()); // future
        call(lee, json(post(items() + "/" + item + "/complete"), "{\"actualEnd\":\"2026-01-04\"}"))
                .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.progress").value(100));
        call(lee, json(post(items() + "/" + item + "/complete"), "{\"actualEnd\":\"2026-01-04\"}"))
                .andExpect(status().isConflict());

        call(lee, delete(items() + "/" + item)).andExpect(status().isNoContent());
        call(lee, get(schedule())).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(jdbc.queryForList("SELECT type FROM schedule_item_event WHERE item_id = ? ORDER BY id", String.class, item))
                .containsExactly("CREATED", "DATES_CHANGED", "COMPLETED", "DELETED");
    }

    @Test
    void parent_is_one_level_only() throws Exception {
        long group = item("{\"kind\":\"TASK\",\"title\":\"기본설계 보고서\",\"startDate\":\"2026-02-01\",\"endDate\":\"2026-03-31\"}");
        long child = item("{\"kind\":\"TASK\",\"title\":\"초안\",\"startDate\":\"2026-02-01\",\"endDate\":\"2026-02-20\",\"parentId\":" + group + "}");

        call(lee, json(post(items()), """
                {"kind":"TASK","title":"손자","startDate":"2026-02-01","endDate":"2026-02-05","parentId":%d}""".formatted(child)))
                .andExpect(status().isUnprocessableEntity());
        // a group cannot itself go under another item
        long other = item("{\"kind\":\"TASK\",\"title\":\"실시설계\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-05-31\"}");
        call(lee, json(patch(items() + "/" + group), """
                {"kind":"TASK","title":"기본설계 보고서","startDate":"2026-02-01","endDate":"2026-03-31","parentId":%d}"""
                .formatted(other))).andExpect(status().isUnprocessableEntity());
        call(lee, get(schedule())).andExpect(jsonPath("$.items[?(@.id == %d)].parentId".formatted(child)).value((int) group));
    }

    @Test
    void dependencies_are_same_project_and_acyclic() throws Exception {
        long a = item("{\"kind\":\"TASK\",\"title\":\"공정 계획\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-20\"}");
        long b = item("{\"kind\":\"TASK\",\"title\":\"배치 계획\",\"startDate\":\"2026-01-21\",\"endDate\":\"2026-02-10\",\"predecessorIds\":[" + a + "]}");
        long c = item("{\"kind\":\"MILESTONE\",\"title\":\"중간보고\",\"endDate\":\"2026-02-15\",\"predecessorIds\":[" + b + "]}");

        call(lee, json(patch(items() + "/" + a), """
                {"kind":"TASK","title":"공정 계획","startDate":"2026-01-05","endDate":"2026-01-20","predecessorIds":[%d]}"""
                .formatted(c))).andExpect(status().isUnprocessableEntity());
        call(lee, get(schedule())).andExpect(jsonPath("$.dependencies.length()").value(2))
                .andExpect(jsonPath("$.dependencies[0].predecessorId").value((int) a))
                .andExpect(jsonPath("$.dependencies[0].successorId").value((int) b));

        long otherProject = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 소각장","facilityType":"INCINERATION","location":"가온시"}""")));
        call(kim, json(post("/api/projects/" + otherProject + "/schedule/items"), """
                {"kind":"TASK","title":"남의 선행","startDate":"2026-01-05","endDate":"2026-01-06","predecessorIds":[%d]}"""
                .formatted(a))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void status_and_delay_days_follow_the_clock() throws Exception {
        long late = item("{\"kind\":\"TASK\",\"title\":\"토목 기본도\",\"startDate\":\"2025-12-01\",\"endDate\":\"2026-01-02\"}");
        item("{\"kind\":\"TASK\",\"title\":\"오늘 마감\",\"startDate\":\"2025-12-20\",\"endDate\":\"2026-01-05\"}");
        item("{\"kind\":\"MILESTONE\",\"title\":\"심의\",\"endDate\":\"2026-01-12\"}");

        call(lee, get(schedule())).andExpect(jsonPath("$.today").value("2026-01-05"))
                .andExpect(jsonPath("$.items[0].status").value("DELAYED"))
                .andExpect(jsonPath("$.items[0].delayDays").value(3))
                .andExpect(jsonPath("$.items[1].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[1].delayDays").value(0))
                .andExpect(jsonPath("$.items[2].status").value("PLANNED"));

        clock.advance(Duration.ofDays(10)); // 2026-01-15
        lee = login("lee@a.com"); // the old token has expired
        call(lee, json(post(items() + "/" + late + "/complete"), "{\"actualEnd\":\"2026-01-09\"}"))
                .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.delayDays").value(7));
        call(lee, get(schedule()))
                .andExpect(jsonPath("$.items[1].delayDays").value(10))
                .andExpect(jsonPath("$.items[2].status").value("DELAYED"));
    }

    @Test
    void history_is_append_only() throws Exception {
        long item = item("{\"kind\":\"TASK\",\"title\":\"전기 단선도\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-20\"}");
        call(lee, json(patch(items() + "/" + item), """
                {"kind":"TASK","title":"전기 단선도","startDate":"2026-01-05","endDate":"2026-01-27","ownerUserId":%d}"""
                .formatted(kimId))).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT type FROM schedule_item_event WHERE item_id = ? ORDER BY id", String.class, item))
                .containsExactly("CREATED", "DATES_CHANGED", "OWNER_CHANGED");
        assertThat(jdbc.queryForObject("SELECT data->'to'->>'end' FROM schedule_item_event WHERE type = 'DATES_CHANGED'",
                String.class)).isEqualTo("2026-01-27");
        assertThatThrownBy(() -> jdbc.update("UPDATE schedule_item_event SET type = 'X'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM schedule_item_event")).hasMessageContaining("append-only");
    }

    @Test
    void non_members_get_403() throws Exception {
        long item = item("{\"kind\":\"TASK\",\"title\":\"조경 계획\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-20\"}");
        String park = login("park@b.com");
        for (String token : List.of(cho, park)) { // same company non-member, other company
            call(token, get(schedule())).andExpect(status().isForbidden());
            call(token, json(post(items()), "{\"kind\":\"MILESTONE\",\"title\":\"x\",\"endDate\":\"2026-01-20\"}"))
                    .andExpect(status().isForbidden());
            call(token, json(post(items() + "/" + item + "/complete"), "{\"actualEnd\":\"2026-01-05\"}"))
                    .andExpect(status().isForbidden());
            call(token, delete(items() + "/" + item)).andExpect(status().isForbidden());
        }
    }

    @Test
    void reads_never_touch_ai_suggestion() throws Exception {
        item("{\"kind\":\"TASK\",\"title\":\"인허가 협의\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-20\"}");
        jdbc.execute("ALTER TABLE ai_suggestion RENAME TO ai_suggestion_hidden");
        try {
            call(lee, get(schedule())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        } finally {
            jdbc.execute("ALTER TABLE ai_suggestion_hidden RENAME TO ai_suggestion");
        }
    }

    // --- reminders ------------------------------------------------------------------------------------------

    @Test
    void schedule_item_reminds_owner_at_d7_and_d1_then_overdue_and_tells_successor_owner_once() throws Exception {
        long pred = item("""
                {"kind":"TASK","title":"P&ID 1차","startDate":"2026-01-05","endDate":"2026-03-31","ownerUserId":%d}"""
                .formatted(leeId));
        item("""
                {"kind":"TASK","title":"계측 설계","startDate":"2026-04-01","endDate":"2026-04-30","ownerUserId":%d,
                 "predecessorIds":[%d]}""".formatted(kimId, pred));
        // a permit expiring the same day still gets the D-90 reminder (regression of the old kinds)
        call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"건축허가","category":"CONSTRUCTION","expiryDate":"2026-03-31","status":"COMPLETED"}"""))
                .andExpect(status().isCreated());

        for (int day = 0; day <= 90; day++) { // 2026-01-05 … 2026-04-05
            clock.set(START.plus(Duration.ofDays(day)));
            job.createDueNotifications();
        }

        assertThat(offsets(leeId, "SCHEDULE_ITEM")).containsExactly(-1, 1, 7); // no D-90/60/30
        assertThat(offsets(kimId, "SCHEDULE_ITEM")).containsExactly(-1); // 선행 지연, once
        assertThat(jdbc.queryForObject("""
                SELECT n.message FROM notification n JOIN deadline_event e ON e.id = n.deadline_event_id
                WHERE n.user_id = ? AND e.kind = 'SCHEDULE_ITEM'""", String.class, kimId))
                .isEqualTo("선행 지연 · 가온시 하수처리장 턴키 · P&ID 1차 (2026-03-31)");
        assertThat(offsets(kimId, "PERMIT_EXPIRY")).containsExactly(7, 30, 60, 90);
    }

    @Test
    void completed_item_is_not_reminded() throws Exception {
        long item = item("""
                {"kind":"MILESTONE","title":"기본설계 제출","endDate":"2026-01-06","ownerUserId":%d}""".formatted(leeId));
        call(lee, json(post(items() + "/" + item + "/complete"), "{\"actualEnd\":\"2026-01-05\"}")).andExpect(status().isOk());
        job.createDueNotifications();
        assertThat(offsets(leeId, "SCHEDULE_ITEM")).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private List<Integer> offsets(long userId, String kind) {
        return jdbc.queryForList("""
                SELECT n.offset_days FROM notification n JOIN deadline_event e ON e.id = n.deadline_event_id
                WHERE n.user_id = ? AND e.kind = ? ORDER BY n.offset_days""", Integer.class, userId, kind);
    }

    private long disciplineId(String name) {
        return jdbc.queryForObject("SELECT id FROM discipline WHERE company_id = ? AND name = ?", Long.class, companyA, name);
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
