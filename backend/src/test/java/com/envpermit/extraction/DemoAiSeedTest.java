package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The public demo's AI review flow, replayed from the recorded responses and seen by the read-only demo login. */
class DemoAiSeedTest extends IntegrationTest {

    @Autowired DemoAiSeed seed;
    @Autowired CompanyRepository companies;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    long projectId;
    String demo;

    @BeforeEach
    void devSeed() throws Exception {
        truncateAll();
        long co = companies.save(new Company("(가상) 푸른환경엔지니어링")).getId();
        users.save(new User("dev@envpermit.local", encoder.encode("pw"), "개발자", User.Role.ADMIN, co));
        long demoId = users.save(new User("demo@envpermit.local", encoder.encode("pw"), "데모", User.Role.USER, co)).getId();
        String dev = login("dev@envpermit.local");
        projectId = id(call(dev, post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"새벽시 매립가스 자원화시설","facilityType":"LANDFILL","location":"새벽시 동구 매립로 12"}""")));
        call(dev, post("/api/projects/" + projectId + "/members").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + demoId + "}")).andExpect(status().isCreated());
        demo = login("demo@envpermit.local");
    }

    @Test
    void demo_login_sees_accepted_pending_rejected_and_revision_suggestions_without_any_ai_call() throws Exception {
        seed.seed();

        List<Map<String, Object>> rows = JsonPath.read(body(get("/api/projects/" + projectId + "/suggestions")), "$");
        assertThat(rows).extracting(r -> r.get("status")).contains("ACCEPTED", "PENDING", "REJECTED");
        assertThat(rows).filteredOn(r -> "REJECTED".equals(r.get("status"))).singleElement()
                .extracting(r -> (String) r.get("rejectReason")).asString().isNotBlank();
        assertThat(rows).extracting(r -> r.get("kind"))
                .contains("OBLIGATION", "OBLIGATION_REVISION", "SCHEDULE_ITEM", "PROJECT_EVENT");
        assertThat(rows).filteredOn(r -> "OBLIGATION_REVISION".equals(r.get("kind")))
                .extracting(r -> JsonPath.read(r.get("payload"), "$.change_type").toString())
                .contains("CHANGED", "ADDED", "REMOVED");
        assertThat(rows).filteredOn(r -> List.of("SCHEDULE_ITEM", "PROJECT_EVENT").contains(r.get("kind")))
                .extracting(r -> r.get("status")).contains("ACCEPTED", "PENDING");

        List<Object> obligations = JsonPath.read(body(get("/api/projects/" + projectId + "/obligations")), "$");
        assertThat(obligations).hasSizeGreaterThanOrEqualTo(10);
        List<String> runs = JsonPath.read(body(get("/api/projects/" + projectId + "/ai-runs")), "$[*].status");
        assertThat(runs).hasSize(4).containsOnly("SUCCEEDED");
        // the source PDFs are in storage, so the review screen can show the cited page
        call(demo, get("/api/suggestions/" + rows.get(0).get("id") + "/pages/1")).andExpect(status().isOk());
        assertThat(llm.calls).isEmpty();

        seed.seed(); // the server runs it on every start
        assertThat((List<?>) JsonPath.read(body(get("/api/projects/" + projectId + "/suggestions")), "$"))
                .hasSize(rows.size());
    }

    private String body(MockHttpServletRequestBuilder req) throws Exception {
        return call(demo, req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions call(String token, MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String b = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email))).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(b, "$.accessToken");
    }
}
