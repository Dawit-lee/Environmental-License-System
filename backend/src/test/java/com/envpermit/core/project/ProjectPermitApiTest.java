package com.envpermit.core.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ProjectPermitApiTest extends IntegrationTest {

    static final String PROJECT = """
            {"name":"부산 하수처리장 증설","facilityType":"SEWAGE","location":"부산 강서구",
             "capacity":150,"capacityUnit":"톤/일","startDate":"2026-03-15"}""";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim; // company A, creates projects
    String lee; // company A
    String park; // company B
    long leeId;
    long parkId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a)).getId();
        parkId = users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b)).getId();
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        park = login("park@b.com");
    }

    @Test
    void creator_gets_both_approval_flags_and_sees_project_in_list() throws Exception {
        long id = createProject(kim);

        call(kim, get("/api/projects/" + id + "/members")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("김에이"))
                .andExpect(jsonPath("$[0].isAdmin").value(true))
                .andExpect(jsonPath("$[0].canSelfApprove").value(true))
                .andExpect(jsonPath("$[0].canBulkApprove").value(true));
        call(kim, get("/api/projects")).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].permitCount").value(0));
        call(lee, get("/api/projects")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void other_company_user_gets_403_on_every_project_route() throws Exception {
        long id = createProject(kim);
        long permit = createPermit(kim, id, "IN_PROGRESS");
        String p = "/api/projects/" + id;

        call(park, get(p)).andExpect(status().isForbidden());
        call(park, json(put(p), PROJECT)).andExpect(status().isForbidden());
        call(park, delete(p)).andExpect(status().isForbidden());
        call(park, get(p + "/members")).andExpect(status().isForbidden());
        call(park, json(post(p + "/members"), "{\"userId\":" + parkId + "}")).andExpect(status().isForbidden());
        call(park, get(p + "/permits")).andExpect(status().isForbidden());
        call(park, get(p + "/permits/" + permit)).andExpect(status().isForbidden());
        call(park, json(post(p + "/permits"), permitJson("NOT_APPLIED"))).andExpect(status().isForbidden());
        call(park, json(put(p + "/permits/" + permit), permitJson("EXPIRED"))).andExpect(status().isForbidden());
        call(park, delete(p + "/permits/" + permit)).andExpect(status().isForbidden());
        call(park, get(p + "/permits/" + permit + "/history")).andExpect(status().isForbidden());
        call(park, get("/api/projects")).andExpect(jsonPath("$.length()").value(0));

        // and the admin cannot pull them in either
        call(kim, json(post(p + "/members"), "{\"userId\":" + parkId + "}")).andExpect(status().isBadRequest());
    }

    @Test
    void added_member_starts_without_flags_and_only_admin_can_change_them() throws Exception {
        long id = createProject(kim);
        String members = "/api/projects/" + id + "/members";

        call(kim, json(post(members), "{\"userId\":" + leeId + "}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.isAdmin").value(false))
                .andExpect(jsonPath("$.canSelfApprove").value(false))
                .andExpect(jsonPath("$.canBulkApprove").value(false));

        // a member can read but not grant themselves rights
        call(lee, get("/api/projects/" + id)).andExpect(status().isOk());
        call(lee, json(patch(members + "/" + leeId), "{\"canSelfApprove\":true}")).andExpect(status().isForbidden());

        call(kim, json(patch(members + "/" + leeId), "{\"canSelfApprove\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.canSelfApprove").value(true))
                .andExpect(jsonPath("$.canBulkApprove").value(false));
    }

    @Test
    void last_admin_cannot_be_demoted_or_removed() throws Exception {
        long id = createProject(kim);
        long kimId = users.findByEmail("kim@a.com").orElseThrow().getId();
        String me = "/api/projects/" + id + "/members/" + kimId;

        call(kim, json(patch(me), "{\"isAdmin\":false}")).andExpect(status().isConflict());
        call(kim, delete(me)).andExpect(status().isConflict());
    }

    @Test
    void permit_crud_with_dday_and_counts() throws Exception {
        long id = createProject(kim);
        String permits = "/api/projects/" + id + "/permits";
        // START is 2026-01-05 (KST 09:00); expiry 2026-01-15 → D-10
        long permit = createPermit(kim, id, "NEAR_EXPIRY");

        call(kim, get(permits)).andExpect(jsonPath("$[0].name").value("건축허가"))
                .andExpect(jsonPath("$[0].category").value("CONSTRUCTION"))
                .andExpect(jsonPath("$[0].approvalDate").value("착공 30일 전"))
                .andExpect(jsonPath("$[0].dDay").value(10));
        call(kim, get("/api/projects/" + id)).andExpect(jsonPath("$.permitCount").value(1))
                .andExpect(jsonPath("$.nearExpiryCount").value(1))
                .andExpect(jsonPath("$.acquiredCount").value(0));

        call(kim, delete(permits + "/" + permit)).andExpect(status().isNoContent());
        call(kim, get(permits)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void status_changes_are_recorded_in_history() throws Exception {
        long id = createProject(kim);
        long permit = createPermit(kim, id, "NOT_APPLIED");
        String url = "/api/projects/" + id + "/permits/" + permit;

        clock.advance(Duration.ofMinutes(10));
        call(kim, json(put(url), permitJson("IN_PROGRESS"))).andExpect(status().isOk());
        call(kim, json(put(url), permitJson("IN_PROGRESS"))).andExpect(status().isOk()); // no status change
        clock.advance(Duration.ofMinutes(10));
        call(kim, json(put(url), permitJson("COMPLETED"))).andExpect(status().isOk());

        call(kim, get(url + "/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].fromStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].toStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].changedBy").value("김에이"))
                .andExpect(jsonPath("$[0].changedAt").value("2026-01-05T00:20:00Z"))
                .andExpect(jsonPath("$[2].fromStatus").doesNotExist())
                .andExpect(jsonPath("$[2].toStatus").value("NOT_APPLIED"));
    }

    @Test
    void permit_of_another_project_is_not_reachable_through_my_project() throws Exception {
        long mine = createProject(kim);
        long theirs = createProject(park);
        long theirPermit = createPermit(park, theirs, "IN_PROGRESS");

        call(kim, get("/api/projects/" + mine + "/permits/" + theirPermit)).andExpect(status().isNotFound());
        call(kim, delete("/api/projects/" + mine + "/permits/" + theirPermit)).andExpect(status().isNotFound());
    }

    @Test
    void invalid_project_is_400() throws Exception {
        call(kim, json(post("/api/projects"), "{\"name\":\"\",\"facilityType\":\"SEWAGE\",\"location\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ----------------------------------------------------------

    private long createProject(String token) throws Exception {
        return id(call(token, json(post("/api/projects"), PROJECT)).andExpect(status().isCreated()));
    }

    private long createPermit(String token, long projectId, String status) throws Exception {
        return id(call(token, json(post("/api/projects/" + projectId + "/permits"), permitJson(status)))
                .andExpect(status().isCreated()));
    }

    private static String permitJson(String status) {
        return """
                {"name":"건축허가","category":"CONSTRUCTION","regulation":"건축법 제11조",
                 "authority":"세움터","approvalDate":"착공 30일 전","expiryDate":"2026-01-15","status":"%s"}"""
                .formatted(status);
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static long id(ResultActions res) throws Exception {
        return ((Number) JsonPath.read(res.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
