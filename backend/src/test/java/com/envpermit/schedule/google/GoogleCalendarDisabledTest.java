package com.envpermit.schedule.google;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Default test context has no GOOGLE_* keys: the whole feature answers 404 (the UI hides on that). */
class GoogleCalendarDisabledTest extends IntegrationTest {

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    @Test
    void every_route_is_404_without_the_env_keys() throws Exception {
        truncateAll();
        long a = companies.save(new Company("가온엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김가온", User.Role.ADMIN, a));
        String token = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"kim@a.com\",\"password\":\"pw\"}")).andReturn().getResponse()
                .getContentAsString(), "$.accessToken");
        for (String path : new String[] {"/api/me/google-calendar", "/api/me/google-calendar/connect"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/me/google-calendar/callback").param("state", "x")).andExpect(status().isNotFound());
    }
}
