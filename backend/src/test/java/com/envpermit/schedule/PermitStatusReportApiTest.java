package com.envpermit.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** design-schedule/08: kim is a member of the A project; park (company B) is not. Today is 2026-01-05. */
class PermitStatusReportApiTest extends IntegrationTest {

    static final String LONG_ISSUE = "가".repeat(95);

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    long projectId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A설계")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"가온시 자원회수시설","facilityType":"INCINERATION","location":"가온","capacity":300,"capacityUnit":"톤/일"}""")));
    }

    @Test
    void the_report_groups_completed_first_and_fits_one_a4_portrait_page() throws Exception {
        permit("도시계획시설 결정", "COMPLETED", "가온시청 도시과", "\"acquisitionDate\":\"2025-11-20\"");
        long building = permit("건축허가", "IN_PROGRESS", "가온시청 건축과",
                "\"applicationDate\":\"2025-12-10\",\"progressNote\":\"가온시 건축과 검토 중(12/21)\","
                        + "\"expectedCompletion\":\"1월 중순\",\"issueNote\":\"=소방 동의 선행 필요\"");
        permit("개발행위허가", "COMPLETED", "가온시청 도시과", "\"acquisitionDate\":\"2025-10-02\"");
        permit("대기배출시설 설치허가", "IN_PROGRESS", "한강유역환경청",
                "\"applicationDate\":\"2025-12-01\",\"issueNote\":\"" + LONG_ISSUE + "\"");
        permit("폐기물처리시설 사용개시 신고", "NOT_APPLIED", null, "\"issueNote\":\"착공 인허가와 무관\""); // left out
        call(kim, get("/api/projects/" + projectId + "/permits/" + building))
                .andExpect(jsonPath("$.progressNote").value("가온시 건축과 검토 중(12/21)"))
                .andExpect(jsonPath("$.expectedCompletion").value("1월 중순"))
                .andExpect(jsonPath("$.issueNote").value("=소방 동의 선행 필요"));

        byte[] file = call(kim, get(report())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "260105%20%EC%9D%B8%ED%97%88%EA%B0%80%20%EC%A7%84%ED%96%89%20%ED%98%84%ED%99%A9.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            XSSFSheet sheet = wb.getSheetAt(0);
            assertThat(cells(sheet.getRow(0))).first().isEqualTo("가온시 자원회수시설");
            assertThat(cells(sheet.getRow(1))).first().isEqualTo("인허가 진행 현황");
            assertThat(cells(sheet.getRow(2)).get(4)).isEqualTo("2026. 01.");
            assertThat(cells(sheet.getRow(3))).containsExactly("구분", "인허가 항목", "완료 여부", "예상완료 시점", "현안사항");
            assertThat(sheet.getLastRowNum()).isEqualTo(7); // 4 rows: the NOT_APPLIED one without a note is out
            assertThat(cells(sheet.getRow(4))).containsExactly("완료", "개발행위허가\n가온시청 도시과", "완료", "-", "");
            assertThat(cells(sheet.getRow(5)).subList(1, 3)).containsExactly("도시계획시설 결정\n가온시청 도시과", "완료");
            assertThat(cells(sheet.getRow(6))).containsExactly("계속 진행", "대기배출시설 설치허가\n한강유역환경청",
                    "진행중 (접수 12/01)", "", "가".repeat(90) + "…");
            assertThat(cells(sheet.getRow(7))).containsExactly("계속 진행", "건축허가\n가온시청 건축과",
                    "가온시 건축과 검토 중(12/21)", "1월 중순", "'=소방 동의 선행 필요");
            assertThat(sheet.getMergedRegions().stream().map(m -> m.formatAsString()))
                    .contains("A5:A6", "A7:A8");
            // explicit heights from the wrapped line count: name + authority = 2 lines, the long issue = 7
            for (int r = 4; r <= 7; r++) {
                assertThat(sheet.getRow(r).getCTRow().getCustomHeight()).isTrue();
            }
            assertThat(sheet.getRow(4).getHeightInPoints()).isEqualTo(2 * PermitStatusReport.LINE_POINTS + 3);
            assertThat(sheet.getRow(6).getHeightInPoints()).isEqualTo(7 * PermitStatusReport.LINE_POINTS + 3);

            PrintSetup print = sheet.getPrintSetup();
            assertThat(print.getPaperSize()).isEqualTo(PrintSetup.A4_PAPERSIZE);
            assertThat(print.getLandscape()).isFalse();
            assertThat(print.getFitWidth()).isEqualTo((short) 1);
            assertThat(print.getFitHeight()).isEqualTo((short) 1);
            assertThat(sheet.getFitToPage()).isTrue();
            assertThat(sheet.getFooter().getCenter()).isEqualTo("- &P -");
        }
    }

    @Test
    void a_noted_not_applied_permit_is_in_and_more_than_22_rows_fold_the_completed_group() throws Exception {
        for (int i = 1; i <= 20; i++) {
            permit("완료 항목 " + i, "COMPLETED", null, "\"acquisitionDate\":\"2025-01-%02d\"".formatted(i));
        }
        permit("건축허가", "IN_PROGRESS", null, "");
        permit("경관심의", "COMPLETED", null, "\"expiryDate\":\"2026-01-20\""); // 만료 D-15 → 만료임박
        permit("소방시설 착공신고", "NOT_APPLIED", null, "\"progressNote\":\"접수 준비 중\"");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                call(kim, get(report())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(7); // 1 folded 완료 row + 3 계속 진행 rows
            assertThat(cells(sheet.getRow(4)).get(1)).startsWith("완료 20건: 완료 항목 1, 완료 항목 2").endsWith("…");
            assertThat(cells(sheet.getRow(5)).subList(1, 3)).containsExactly("건축허가", "진행중");
            assertThat(cells(sheet.getRow(7)).subList(1, 3)).containsExactly("소방시설 착공신고", "접수 준비 중");
        }
    }

    @Test
    void another_companys_user_gets_403() throws Exception {
        call(login("park@b.com"), get(report())).andExpect(status().isForbidden());
    }

    @Test
    void a_note_over_its_column_limit_is_rejected() throws Exception {
        long id = permit("건축허가", "IN_PROGRESS", null, "");
        call(kim, json(put("/api/projects/" + projectId + "/permits/" + id), """
                {"name":"건축허가","category":"CONSTRUCTION","status":"IN_PROGRESS","expectedCompletion":"%s"}"""
                .formatted("가".repeat(51)))).andExpect(status().isBadRequest());
    }

    private String report() {
        return "/api/projects/" + projectId + "/permit-status-report.xlsx";
    }

    private long permit(String name, String status, String authority, String extra) throws Exception {
        String auth = authority == null ? "" : ",\"authority\":\"" + authority + "\"";
        return id(call(kim, json(post("/api/projects/" + projectId + "/permits"),
                "{\"name\":\"%s\",\"category\":\"CONSTRUCTION\",\"status\":\"%s\"%s%s}"
                        .formatted(name, status, auth, extra.isEmpty() ? "" : "," + extra)))
                .andExpect(status().isCreated()));
    }

    private static List<String> cells(Row row) {
        DataFormatter f = new DataFormatter();
        List<String> values = new ArrayList<>();
        for (int c = 0; c < 5; c++) {
            values.add(row.getCell(c) == null ? "" : f.formatCellValue(row.getCell(c)));
        }
        return values;
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
