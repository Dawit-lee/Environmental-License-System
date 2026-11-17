package com.envpermit.core.org;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OrgApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String master; // company A master
    String lee; // company A user
    String park; // company B master
    long masterId;
    long leeId;
    long parkId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        masterId = users.save(new User("boss@a.com", encoder.encode("pw"), "최마스터", User.Role.ADMIN, a)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a)).getId();
        parkId = users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.ADMIN, b)).getId();
        master = login("boss@a.com");
        lee = login("lee@a.com");
        park = login("park@b.com");
    }

    @Test
    void master_manages_departments_and_teams_others_only_read() throws Exception {
        long dept = id(call(master, json(post("/api/departments"), "{\"name\":\"에너지환경사업부\"}"))
                .andExpect(status().isCreated()));
        long team1 = createTeam(dept, "1팀");
        createTeam(dept, "2팀");

        call(master, json(post("/api/departments/" + dept + "/teams"), "{\"name\":\"1팀\"}"))
                .andExpect(status().isConflict());
        call(master, json(put("/api/teams/" + team1), "{\"name\":\"1팀(수처리)\"}")).andExpect(status().isOk());

        call(lee, get("/api/departments")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("에너지환경사업부"))
                .andExpect(jsonPath("$[0].teams.length()").value(2))
                .andExpect(jsonPath("$[0].teams[0].name").value("1팀(수처리)"));
        call(lee, json(post("/api/departments"), "{\"name\":\"x\"}")).andExpect(status().isForbidden());
        call(lee, delete("/api/teams/" + team1)).andExpect(status().isForbidden());

        // another company sees nothing and cannot touch A's rows
        call(park, get("/api/departments")).andExpect(jsonPath("$.length()").value(0));
        call(park, json(put("/api/departments/" + dept), "{\"name\":\"x\"}")).andExpect(status().isNotFound());
        call(park, delete("/api/teams/" + team1)).andExpect(status().isNotFound());
        call(park, json(post("/api/departments/" + dept + "/teams"), "{\"name\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void master_assigns_users_to_teams_of_own_company() throws Exception {
        long dept = id(call(master, json(post("/api/departments"), "{\"name\":\"사업부\"}")));
        long team = createTeam(dept, "1팀");

        call(master, json(put("/api/company/users/" + leeId + "/team"), "{\"teamId\":" + team + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.teamId").value(team));
        call(lee, get("/api/company/users")).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == " + leeId + ")].teamId").value(org.hamcrest.Matchers.contains((int) team)));
        call(park, json(put("/api/company/users/" + leeId + "/team"), "{\"teamId\":null}")).andExpect(status().isNotFound());
        call(lee, json(put("/api/company/users/" + leeId + "/team"), "{\"teamId\":null}")).andExpect(status().isForbidden());
    }

    @Test
    void project_carries_team_pm_em_and_company_scope_list_is_read_only_for_non_members() throws Exception {
        long dept = id(call(master, json(post("/api/departments"), "{\"name\":\"에너지환경사업부\"}")));
        long team = createTeam(dept, "2팀");
        String body = """
                {"name":"가람 하수처리장","facilityType":"SEWAGE","location":"가람시",
                 "teamId":%d,"pmUserId":%d,"emUserId":%d}""";

        long id = id(call(master, json(post("/api/projects"), body.formatted(team, masterId, leeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamName").value("2팀"))
                .andExpect(jsonPath("$.departmentName").value("에너지환경사업부"))
                .andExpect(jsonPath("$.pmName").value("최마스터"))
                .andExpect(jsonPath("$.emName").value("이에이"))
                .andExpect(jsonPath("$.member").value(true)));

        // lee is not a member: sees it company-wide and can read, but not edit
        call(lee, get("/api/projects")).andExpect(jsonPath("$.length()").value(0));
        call(lee, get("/api/projects?scope=company")).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].member").value(false));
        call(lee, get("/api/projects/" + id)).andExpect(status().isOk());
        call(lee, get("/api/projects/" + id + "/permits")).andExpect(status().isOk());
        call(lee, json(put("/api/projects/" + id), body.formatted(team, leeId, leeId))).andExpect(status().isForbidden());

        // another company: nothing, and A's team/users cannot be used
        call(park, get("/api/projects?scope=company")).andExpect(jsonPath("$.length()").value(0));
        call(park, get("/api/projects/" + id)).andExpect(status().isForbidden());
        call(park, json(post("/api/projects"), body.formatted(team, parkId, parkId))).andExpect(status().isBadRequest());
        call(master, json(put("/api/projects/" + id), body.formatted(team, parkId, leeId))).andExpect(status().isBadRequest());

        // deleting the team only unassigns the project
        call(master, delete("/api/teams/" + team)).andExpect(status().isNoContent());
        call(master, get("/api/projects/" + id)).andExpect(jsonPath("$.teamId").doesNotExist())
                .andExpect(jsonPath("$.pmName").value("최마스터"));
    }

    // --- helpers ----------------------------------------------------------

    private long createTeam(long dept, String name) throws Exception {
        return id(call(master, json(post("/api/departments/" + dept + "/teams"), "{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()));
    }

    @Test
    void master_disables_a_user_whose_tokens_stop_working_at_once() throws Exception { // M2
        call(lee, get("/api/me")).andExpect(status().isOk());
        call(lee, json(put("/api/company/users/" + masterId + "/disabled"), "{\"disabled\":true}"))
                .andExpect(status().isForbidden());
        call(park, json(put("/api/company/users/" + leeId + "/disabled"), "{\"disabled\":true}"))
                .andExpect(status().isNotFound());
        call(master, json(put("/api/company/users/" + masterId + "/disabled"), "{\"disabled\":true}"))
                .andExpect(status().isBadRequest());

        call(master, json(put("/api/company/users/" + leeId + "/disabled"), "{\"disabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(true));
        call(lee, get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"lee@a.com\",\"password\":\"pw\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("비활성화된 계정입니다. 회사 관리자에게 문의해 주세요."));

        call(master, json(put("/api/company/users/" + leeId + "/disabled"), "{\"disabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(false));
        call(lee, get("/api/me")).andExpect(status().isUnauthorized()); // the old token stays dead
        call(login("lee@a.com"), get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void master_rights_follow_the_db_role_not_the_token_claim() throws Exception { // M12
        call(master, get("/api/company/invite-code")).andExpect(status().isOk());
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", masterId); // demoted; the token still says ADMIN
        call(master, get("/api/company/invite-code")).andExpect(status().isForbidden());
        call(master, json(post("/api/departments"), "{\"name\":\"x\"}")).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", leeId); // promoted; the token still says USER
        call(lee, json(post("/api/departments"), "{\"name\":\"x\"}")).andExpect(status().isCreated());
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
