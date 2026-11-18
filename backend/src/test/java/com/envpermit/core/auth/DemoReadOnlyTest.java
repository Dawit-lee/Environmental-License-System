package com.envpermit.core.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** The shared public demo login may read but never write; auth endpoints still work and its logout spares other visitors. */
class DemoReadOnlyTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        truncateAll();
        users.save(new User(DemoReadOnly.EMAIL, encoder.encode("demo1234!"), "데모 사용자", User.Role.USER));
        users.save(new User("kim@example.com", encoder.encode("correct-pw"), "김담당", User.Role.USER));
    }

    @Test
    void demo_user_reads_but_every_write_is_403_with_a_clear_message() throws Exception {
        ResultActions login = login(DemoReadOnly.EMAIL, "demo1234!").andExpect(status().isOk());
        String access = field(login, "accessToken");

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        mvc.perform(get("/api/projects").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        for (MockHttpServletRequestBuilder write : new MockHttpServletRequestBuilder[] {
                post("/api/projects"), put("/api/projects/1"), patch("/api/projects/1/modules"), delete("/api/projects/1")}) {
            mvc.perform(write.header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("데모 계정은 조회만 가능합니다."));
        }

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(field(login, "refreshToken"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
        // another visitor's session on the same demo login survives that logout
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
    }

    @Test
    void other_users_writes_are_not_affected() throws Exception {
        String access = field(login("kim@example.com", "correct-pw"), "accessToken");
        mvc.perform(post("/api/projects").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()); // reaches the controller's validation
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private static String field(ResultActions res, String name) throws Exception {
        return JsonPath.read(res.andReturn().getResponse().getContentAsString(), "$." + name);
    }
}
