package com.envpermit.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.InMemoryDocumentStorage;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DocumentApiTest extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.7\n협의의견서\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim; // company A, owns the project
    String park; // company B
    String docs; // documents url of kim's permit

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        ((InMemoryDocumentStorage) storage).objects.clear();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        park = login("park@b.com");
        long project = id(call(kim, json(post("/api/projects"), """
                {"name":"하수처리장","facilityType":"SEWAGE","location":"다온"}""")));
        long permit = id(call(kim, json(post("/api/projects/" + project + "/permits"), """
                {"name":"건축허가","category":"CONSTRUCTION","status":"IN_PROGRESS"}""")));
        docs = "/api/projects/" + project + "/permits/" + permit + "/documents";
    }

    @Test
    void upload_list_download_delete() throws Exception {
        long id = id(upload(kim, "협의의견서.pdf", PDF).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("협의의견서.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.size").value(PDF.length)));

        call(kim, get(docs)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uploadedBy").value("김에이"))
                .andExpect(jsonPath("$[0].uploadedAt").value("2026-01-05T00:00:00Z"))
                .andExpect(jsonPath("$[0].sha256").value(
                        "%064x".formatted(new java.math.BigInteger(1,
                                java.security.MessageDigest.getInstance("SHA-256").digest(PDF)))));

        call(kim, get(docs + "/" + id)).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "filename*=UTF-8''%ED%98%91%EC%9D%98%EC%9D%98%EA%B2%AC%EC%84%9C.pdf")));

        call(kim, delete(docs + "/" + id)).andExpect(status().isNoContent());
        call(kim, get(docs)).andExpect(jsonPath("$.length()").value(0));
        call(kim, get(docs + "/" + id)).andExpect(status().isNotFound());
        assertThat(((InMemoryDocumentStorage) storage).objects).isEmpty();
    }

    @Test
    void same_file_uploaded_twice_has_same_hash() throws Exception {
        upload(kim, "a.pdf", PDF).andExpect(status().isCreated());
        upload(kim, "b.pdf", PDF).andExpect(status().isCreated());
        String body = call(kim, get(docs)).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$[0].sha256")).isEqualTo(JsonPath.read(body, "$[1].sha256"));
    }

    @Test
    void rejects_other_types_and_large_files_with_message() throws Exception {
        upload(kim, "setup.exe", new byte[] {'M', 'Z', 0}).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("PDF, JPG, PNG, XLSX 파일만 올릴 수 있습니다."));
        // renamed file: extension says pdf, content is not
        upload(kim, "fake.pdf", new byte[] {'M', 'Z', 0}).andExpect(status().isBadRequest());

        byte[] big = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
        upload(kim, "big.pdf", big).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("파일은 10MB 이하만 올릴 수 있습니다."));

        call(kim, get(docs)).andExpect(jsonPath("$.length()").value(0));
        assertThat(((InMemoryDocumentStorage) storage).objects).isEmpty();
    }

    @Test
    void non_member_gets_403_on_every_document_route() throws Exception {
        long id = id(upload(kim, "a.pdf", PDF));

        upload(park, "a.pdf", PDF).andExpect(status().isForbidden());
        call(park, get(docs)).andExpect(status().isForbidden());
        call(park, get(docs + "/" + id)).andExpect(status().isForbidden());
        call(park, delete(docs + "/" + id)).andExpect(status().isForbidden());
    }

    @Test
    void document_is_not_reachable_through_another_permit() throws Exception {
        long id = id(upload(kim, "a.pdf", PDF));
        String project = docs.substring(0, docs.indexOf("/permits"));
        long other = id(call(kim, json(post(project + "/permits"), """
                {"name":"개발행위허가","category":"LOCATION","status":"NOT_APPLIED"}""")));

        call(kim, get(project + "/permits/" + other + "/documents/" + id)).andExpect(status().isNotFound());
        call(kim, delete(project + "/permits/" + other + "/documents/" + id)).andExpect(status().isNotFound());
    }

    // --- helpers ----------------------------------------------------------

    private ResultActions upload(String token, String name, byte[] bytes) throws Exception {
        return call(token, multipart(docs).file(new MockMultipartFile("file", name, "application/octet-stream", bytes)));
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
