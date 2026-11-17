package com.envpermit.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** GET /api/dashboard: my projects, their permits, open AI reviews and delayed items per discipline in one call. */
class DashboardApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    String lee;
    String park;
    long mine;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        park = login("park@b.com");
        mine = project(kim, "가온 하수처리장");
        project(lee, "나래 소각장"); // same company, kim is not a member
        project(park, "다온 매립장");
        call(kim, json(post("/api/projects/" + mine + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"NOT_APPLIED"}""")).andExpect(status().isCreated());
    }

    @Test
    void summary_covers_only_my_projects() throws Exception {
        long late = item("""
                {"kind":"TASK","title":"기본설계","startDate":"2026-01-01","endDate":"2026-01-03"}""");
        item("""
                {"kind":"TASK","title":"실시설계","startDate":"2026-01-01","endDate":"2026-02-01"}""");
        long runId = jdbc.queryForObject("INSERT INTO ai_run (company_id, project_id, kind, preparer, status, started_at) "
                + "SELECT company_id, id, 'OBLIGATION', 'CLAUDE_PDF_DIRECT', 'SUCCEEDED', now() FROM project WHERE id = ? "
                + "RETURNING id", Long.class, mine);
        for (String status : new String[] {"PENDING", "SUBMITTED", "ACCEPTED"}) {
            jdbc.update("INSERT INTO ai_suggestion (company_id, project_id, run_id, kind, origin, payload, status) "
                    + "SELECT company_id, id, ?, 'OBLIGATION', 'AI', '{}'::jsonb, ? FROM project WHERE id = ?",
                    runId, status, mine);
        }

        call(kim, get("/api/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].name").value("가온 하수처리장"))
                .andExpect(jsonPath("$.permits.length()").value(1))
                .andExpect(jsonPath("$.permits[0].status").value("NOT_APPLIED"))
                .andExpect(jsonPath("$.reviewOpen").value(2))
                .andExpect(jsonPath("$.delayedByDiscipline.length()").value(1))
                .andExpect(jsonPath("$.delayedByDiscipline[0].discipline").value("공통"))
                .andExpect(jsonPath("$.delayedByDiscipline[0].count").value(1));

        call(park, get("/api/dashboard")).andExpect(jsonPath("$.permits.length()").value(0))
                .andExpect(jsonPath("$.reviewOpen").value(0));

        // M6: a progress change is not a Google sync change, a date change is
        jdbc.update("INSERT INTO user_google_link (user_id, encrypted_refresh_token, calendar_id, connected_at) "
                + "SELECT id, 'x', 'cal', now() FROM users WHERE email = 'kim@a.com'");
        jdbc.update("DELETE FROM calendar_sync_outbox");
        jdbc.update("UPDATE schedule_item SET progress = 50 WHERE id = ?", late);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_sync_outbox", Long.class)).isZero();
        jdbc.update("UPDATE schedule_item SET end_date = end_date + 1 WHERE id = ?", late);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_sync_outbox", Long.class)).isPositive();
    }

    @Test
    void no_projects_is_an_empty_summary() throws Exception {
        String nobody = login("x@a.com", true);
        call(nobody, get("/api/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(0))
                .andExpect(jsonPath("$.reviewOpen").value(0))
                .andExpect(jsonPath("$.delayedByDiscipline.length()").value(0));
    }

    private long item(String body) throws Exception {
        return id(call(kim, json(post("/api/projects/" + mine + "/schedule/items"), body)).andExpect(status().isCreated()));
    }

    private long project(String token, String name) throws Exception {
        return id(call(token, json(post("/api/projects"), """
                {"name":"%s","facilityType":"SEWAGE","location":"가온"}""".formatted(name))).andExpect(status().isCreated()));
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String login(String email) throws Exception {
        return login(email, false);
    }

    private String login(String email, boolean create) throws Exception {
        if (create) {
            users.save(new User(email, encoder.encode("pw"), "새사람", User.Role.USER,
                    companies.findAll().get(0).getId()));
        }
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static long id(ResultActions res) throws Exception {
        return ((Number) JsonPath.read(res.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
