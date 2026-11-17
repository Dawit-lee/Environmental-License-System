package com.envpermit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.ServletWebRequest;

/** H2·M11: one {code, message} error shape, and no Java type names, SQL or exception text in any error body. */
class ApiErrorsTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ApiErrors errors;
    String token;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        users.save(new User("kim@example.com", encoder.encode("pw"), "김담당", User.Role.USER));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"kim@example.com\",\"password\":\"pw\"}")).andReturn().getResponse().getContentAsString();
        token = JsonPath.read(res, "$.accessToken");
    }

    @Test
    void bad_enum_in_json_is_400_without_java_type_names() throws Exception {
        String body = mvc.perform(post("/api/projects").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"facilityType\":\"NOPE\",\"location\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요."))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("com.envpermit", "FacilityType", "Exception");
    }

    @Test
    void bad_path_variable_is_400_without_java_type_names() throws Exception {
        String body = mvc.perform(get("/api/projects/not-a-number").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.lang", "NumberFormat");
    }

    @Test
    void our_own_reasons_still_reach_the_user() throws Exception {
        mvc.perform(get("/api/projects/999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unexpected_exceptions_are_500_with_a_fixed_message() {
        ResponseEntity<Object> res = errors.unexpected(
                new DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint \"secret_table_pkey\""),
                new ServletWebRequest(new MockHttpServletRequest()));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody()).isEqualTo(Map.of("code", "INTERNAL_SERVER_ERROR",
                "message", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
