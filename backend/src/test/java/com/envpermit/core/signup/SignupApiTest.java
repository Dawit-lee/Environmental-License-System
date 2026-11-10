package com.envpermit.core.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SignupApiTest extends IntegrationTest {

    @Autowired InMemoryMailer mailer;

    String adminToken;
    String code;

    @BeforeEach
    void newCompany() throws Exception {
        truncateAll();
        mailer.clear();
        adminToken = token(signupCompany("푸른환경", "박관리", "boss@a.com", "password1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN")), "accessToken");
        code = JsonPath.read(mvc.perform(get("/api/company/invite-code").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");
    }

    @Test
    void new_company_signup_makes_an_admin_who_can_log_in() throws Exception {
        login("boss@a.com", "password1").andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("ADMIN"));
        mvc.perform(get("/api/company").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.name").value("푸른환경"));
        assertThat(code).matches("[A-Z2-9]{10}");
    }

    @Test
    void join_request_waits_for_admin_who_gets_mail_and_notification_then_approval_lets_applicant_log_in()
            throws Exception {
        mvc.perform(get("/api/auth/invite/" + code)).andExpect(jsonPath("$.companyName").value("푸른환경"));
        join(code, "이신청", "new@a.com", "password2").andExpect(status().isAccepted());

        login("new@a.com", "password2").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("가입 승인 대기 중입니다. 회사 관리자가 승인하면 로그인할 수 있습니다."));

        assertThat(mailer.sent()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("boss@a.com");
            assertThat(m.body()).contains("이신청", "new@a.com", "http://localhost:5182/join-requests");
        });
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[0].message").value("이신청(new@a.com) 님이 가입을 신청했습니다."))
                .andExpect(jsonPath("$[0].link").value("/join-requests"));

        long id = requestId();
        mailer.clear();
        mvc.perform(post("/api/join-requests/" + id + "/approve").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        String access = token(login("new@a.com", "password2").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER")), "accessToken");
        mvc.perform(get("/api/company").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.name").value("푸른환경"));
        assertThat(mailer.sent()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("new@a.com");
            assertThat(m.subject()).contains("승인");
        });
        // handled: gone from the list, can't be decided again
        mvc.perform(get("/api/join-requests").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/join-requests/" + id + "/approve").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void rejected_applicant_cannot_log_in_and_gets_result_mail() throws Exception {
        join(code, "이신청", "new@a.com", "password2").andExpect(status().isAccepted());
        mailer.clear();
        mvc.perform(post("/api/join-requests/" + requestId() + "/reject").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"외부인\"}"))
                .andExpect(status().isNoContent());

        login("new@a.com", "password2").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("가입 신청이 거절되었습니다."));
        assertThat(mailer.sent()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("new@a.com");
            assertThat(m.body()).contains("외부인");
        });
    }

    @Test
    void pending_request_expires_after_14_days_and_the_email_can_apply_again() throws Exception {
        join(code, "이신청", "new@a.com", "password2").andExpect(status().isAccepted());
        long id = requestId();
        clock.advance(Duration.ofDays(15));
        adminToken = token(login("boss@a.com", "password1"), "accessToken");

        mvc.perform(get("/api/join-requests").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/join-requests/" + id + "/approve").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
        login("new@a.com", "password2").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        join(code, "이신청", "new@a.com", "password2").andExpect(status().isAccepted());
    }

    @Test
    void regenerated_code_replaces_the_old_one() throws Exception {
        String fresh = JsonPath.read(mvc.perform(post("/api/company/invite-code")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");
        assertThat(fresh).isNotEqualTo(code);

        join(code, "이신청", "new@a.com", "password2").andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/invite/" + code)).andExpect(status().isNotFound());
        join(fresh, "이신청", "new@a.com", "password2").andExpect(status().isAccepted());
    }

    @Test
    void only_admins_of_that_company_decide_or_see_the_code() throws Exception {
        join(code, "이신청", "new@a.com", "password2").andExpect(status().isAccepted());
        long id = requestId();
        String otherAdmin = token(signupCompany("다른회사", "최관리", "boss@b.com", "password1"), "accessToken");

        mvc.perform(post("/api/join-requests/" + id + "/approve").header("Authorization", "Bearer " + otherAdmin))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/join-requests/" + id + "/reject").header("Authorization", "Bearer " + otherAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        // a plain member of the same company can't either
        join(code, "김멤버", "member@a.com", "password3").andExpect(status().isAccepted());
        mvc.perform(post("/api/join-requests/" + requestId("member@a.com") + "/approve")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isNoContent());
        String member = token(login("member@a.com", "password3"), "accessToken");
        mvc.perform(post("/api/join-requests/" + id + "/approve").header("Authorization", "Bearer " + member))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/company/invite-code").header("Authorization", "Bearer " + member))
                .andExpect(status().isForbidden());
    }

    @Test
    void validation_duplicate_email_short_password_and_rate_limit() throws Exception {
        signupCompany("또회사", "누구", "boss@a.com", "password1").andExpect(status().isConflict());
        join(code, "누구", "BOSS@a.com", "password1").andExpect(status().isConflict());
        join(code, "누구", "short@a.com", "1234567").andExpect(status().isBadRequest());
        signupCompany("또회사", "누구", "short@b.com", "1234567").andExpect(status().isBadRequest());

        join(code, "일", "1@a.com", "password1").andExpect(status().isAccepted());
        join(code, "일", "1@a.com", "password1").andExpect(status().isConflict()); // already pending
        join(code, "이", "2@a.com", "password1").andExpect(status().isAccepted());
        join(code, "삼", "3@a.com", "password1").andExpect(status().isAccepted());
        join(code, "사", "4@a.com", "password1").andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofMinutes(11));
        join(code, "사", "4@a.com", "password1").andExpect(status().isAccepted());
    }

    private long requestId() throws Exception {
        return requestId("new@a.com");
    }

    private long requestId(String email) throws Exception {
        String body = mvc.perform(get("/api/join-requests").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<java.util.List<Object>>read(body, "$[?(@.email == '" + email + "')].id").get(0))
                .longValue();
    }

    private ResultActions signupCompany(String company, String name, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(
                "{\"companyName\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}"
                        .formatted(company, name, email, password)));
    }

    private ResultActions join(String code, String name, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/join").contentType(MediaType.APPLICATION_JSON).content(
                "{\"code\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}"
                        .formatted(code, name, email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private static String token(ResultActions res, String field) throws Exception {
        return JsonPath.read(res.andReturn().getResponse().getContentAsString(), "$." + field);
    }
}
