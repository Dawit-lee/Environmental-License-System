package com.envpermit.core.project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Project-level lists behind the 의무사항 and 문서 tabs. */
class ProjectTabsApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim; // member
    String lee; // same company, not a member
    String park; // other company

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
    }

    @Test
    void obligations_and_documents_of_all_permits_in_one_list() throws Exception {
        long project = id(call(kim, json(post("/api/projects"),
                "{\"name\":\"가람 하수처리장\",\"facilityType\":\"SEWAGE\",\"location\":\"가람시\"}")));
        long p1 = permit(project, "건축허가");
        long p2 = permit(project, "대기배출시설 설치허가");
        obligation(p1, "소음 측정", "2026-03-01");
        obligation(p2, "배출가스 측정", "2026-02-01");
        obligation(p2, "운영일지 보존", null);
        upload(project, p1, "a.pdf");
        upload(project, p2, "b.pdf");

        String base = "/api/projects/" + project;
        call(kim, get(base + "/obligations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].summary").value("배출가스 측정")) // nearest due first, undated last
                .andExpect(jsonPath("$[0].permitItemId").value(p2))
                .andExpect(jsonPath("$[2].summary").value("운영일지 보존"));
        call(kim, get(base + "/documents")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fileName").value("b.pdf"))
                .andExpect(jsonPath("$[0].permitItemId").value(p2))
                .andExpect(jsonPath("$[1].uploadedBy").value("김에이"));

        for (String who : new String[] {lee, park}) {
            call(who, get(base + "/obligations")).andExpect(status().isForbidden());
            call(who, get(base + "/documents")).andExpect(status().isForbidden());
        }
    }

    // --- helpers ----------------------------------------------------------

    private long permit(long project, String name) throws Exception {
        return id(call(kim, json(post("/api/projects/" + project + "/permits"), """
                {"name":"%s","category":"FACILITY","regulation":"법","authority":"기관","status":"IN_PROGRESS"}"""
                .formatted(name))).andExpect(status().isCreated()));
    }

    private void obligation(long permit, String summary, String nextDue) {
        jdbc.update("""
                INSERT INTO permit_obligation (permit_item_id, requirement_text, summary, type, category, frequency,
                                               next_due_date, status)
                VALUES (?, ?, ?, 'MEASURE_SURVEY', 'AIR_QUALITY', 'MONTHLY', ?::date, 'ACTIVE')""",
                permit, summary, summary, nextDue);
    }

    private void upload(long project, long permit, String name) throws Exception {
        clock.advance(java.time.Duration.ofMinutes(1)); // distinct uploadedAt → stable order
        call(kim, multipart("/api/projects/" + project + "/permits/" + permit + "/documents")
                .file(new MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 test".getBytes())))
                .andExpect(status().isCreated());
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
