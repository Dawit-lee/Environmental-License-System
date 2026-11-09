package com.envpermit.core.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

class AuthApiTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seedUser() {
        users.deleteAll();
        users.save(new User("kim@example.com", encoder.encode("correct-pw"), "김담당", User.Role.USER));
    }

    @Test
    void login_with_valid_credentials_returns_tokens_that_open_protected_api() throws Exception {
        String access = token(login("kim@example.com", "correct-pw").andExpect(status().isOk()), "accessToken");

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("kim@example.com"))
                .andExpect(jsonPath("$.name").value("김담당"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void login_with_wrong_password_is_401_with_message() throws Exception {
        login("kim@example.com", "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        login("nobody@example.com", "correct-pw").andExpect(status().isUnauthorized());
    }

    @Test
    void protected_api_without_token_is_401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void expired_access_token_is_rejected_and_refresh_issues_a_new_one() throws Exception {
        ResultActions res = login("kim@example.com", "correct-pw");
        String access = token(res, "accessToken");
        String refresh = token(res, "refreshToken");

        clock.advance(Duration.ofMinutes(35)); // access TTL 30m (+60s allowed skew), refresh TTL 14d

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());

        String newAccess = token(refresh(refresh).andExpect(status().isOk()), "accessToken");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_rejects_expired_refresh_token_and_access_token() throws Exception {
        ResultActions res = login("kim@example.com", "correct-pw");
        String access = token(res, "accessToken");
        String refresh = token(res, "refreshToken");

        refresh(access).andExpect(status().isUnauthorized());

        clock.advance(Duration.ofDays(15));
        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_token_cannot_be_used_as_access_token() throws Exception {
        String refresh = token(login("kim@example.com", "correct-pw"), "refreshToken");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private static String token(ResultActions res, String field) throws Exception {
        return JsonPath.read(res.andReturn().getResponse().getContentAsString(), "$." + field);
    }
}
