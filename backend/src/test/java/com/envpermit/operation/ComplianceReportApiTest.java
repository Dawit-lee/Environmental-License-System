package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * operations-compliance/08: 이행 보고서 초안. kim is a member of the A project; lee (same company) and park
 * (company B) are not. The permit has a stack (CO limit 30 ppm from 2026-01-01) and an outfall whose name looks
 * like a formula. Today is 2026-01-05.
 */
class ComplianceReportApiTest extends IntegrationTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String STACK = "소각로 1호기 굴뚝";
    static final String OUTFALL = "=2+3 방류구";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    long projectId;
    long permitId;
    long stack;
    long outfall;
    String reports;
    int files;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        long b = companies.save(new Company("B엔지니어링")).getId();
        users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a));
        users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, a));
        users.save(new User("park@b.com", encoder.encode("pw"), "박비", User.Role.USER, b));
        kim = login("kim@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명","capacity":300,"capacityUnit":"톤/일"}""")));
        permitId = permit(projectId, "대기배출시설 설치허가");
        stack = point(STACK, "AIR", permitId);
        outfall = point(OUTFALL, "WATER", permitId);
        call(kim, json(post("/api/emission-points/" + stack + "/limits"),
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"effectiveFrom\":\"2026-01-01\"}")).andExpect(status().isCreated());
        reports = "/api/projects/" + projectId + "/permit-items/" + permitId + "/compliance-reports";
        files = 0;
    }

    @Test
    void the_draft_lists_cycles_values_and_corrective_tasks_of_the_period_and_is_saved_as_a_document() throws Exception {
        long obligation = obligation(); // monthly, first due 2026-01-31
        jdbc.update("INSERT INTO revision_implementation (suggestion_id, obligation_id, note) " // ④ row, not built through a revision here
                + "SELECT suggestion_id, id, '측정 주기 변경 현장 공지' FROM permit_obligation WHERE id = ?", obligation);
        measure(stack, "2025-12-20", "CO", "15", "ppm"); // before the period (and before the limit)
        measure(stack, "2026-01-03", "CO", "12", "ppm");
        long wrong = measure(stack, "2026-01-02", "CO", "20", "ppm");
        call(kim, json(delete("/api/measurements/" + wrong), "{\"reason\":\"오입력\"}")).andExpect(status().isNoContent());
        measure(stack, "2026-01-04", "CO", "31", "ppm"); // 초과 → 조치 업무, due 2026-01-12
        measure(outfall, "2026-01-05", "BOD", "8.5", "mg/L"); // no limit entered

        complete(openTask("OBLIGATION"), "{}"); // cycle 1, in time → cycle 2 due 2026-02-28
        complete(openTask("CORRECTIVE"), "{\"note\":\"필터 교체\"}");
        clock.advance(Duration.ofDays(56)); // 2026-03-02
        kim = login("kim@a.com");
        complete(openTask("OBLIGATION"), "{}"); // cycle 2, late → cycle 3 due 2026-03-28
        clock.advance(Duration.ofDays(30)); // 2026-04-01: cycle 3 is overdue
        kim = login("kim@a.com");

        String query = "?from=2026-01-01&to=2026-04-01";
        Map<String, Object> preview = JsonPath.read(call(kim, get(reports + "/preview" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$");
        assertThat(preview.get("fileName")).isEqualTo("이행보고서초안_대기배출시설 설치허가_20260101-20260401.xlsx");
        assertThat(sheetNames(preview)).containsExactly("개요", "의무사항 이행 내역", "측정 결과", "초과 및 조치", "현장 반영");
        assertThat(rows(preview, 0)).contains(List.of("프로젝트", "광명 자원회수시설"), List.of("시설유형", "소각시설"),
                List.of("용량", "300 톤/일"), List.of("기간", "2026-01-01 ~ 2026-04-01"),
                List.of("포함 배출구", STACK + ", " + OUTFALL), List.of("생성일", "2026-04-01"), List.of("생성자", "김에이"));
        assertThat(rows(preview, 1)).containsExactly(List.of("허가조건 제4호", "굴뚝 배출가스 월 1회 측정", "매월",
                3, 1, 1, 1, 0, "2026-01-05, 2026-03-02", "증빙1.pdf, 증빙3.pdf"));
        assertThat(rows(preview, 2)).containsExactlyInAnyOrder( // the deleted value and the one before the period are out
                List.of(STACK, "일산화탄소", "2026-01-03", 12, "ppm", 30, 40.0, "정상", ""),
                List.of(STACK, "일산화탄소", "2026-01-04", 31, "ppm", 30, 103.3, "초과", ""),
                List.of(OUTFALL, "BOD", "2026-01-05", 8.5, "mg/L", "", "", "한도 없음", ""));
        assertThat(rows(preview, 3)).containsExactly(List.of(STACK, "일산화탄소", "2026-01-04", 31, 30, "ppm",
                "[초과] 소각로 1호기 굴뚝 일산화탄소 31 > 30 ppm", "완료", "2026-01-12", "2026-01-05", "필터 교체"));
        assertThat(rows(preview, 4)).containsExactly(List.of("허가조건 제4호", "굴뚝 배출가스 월 1회 측정", "", "", "", "측정 주기 변경 현장 공지"));
        assertThat(documentNames()).doesNotContain((String) preview.get("fileName")); // the preview saves nothing

        long documentId = id(call(kim, json(post(reports), "{\"from\":\"2026-01-01\",\"to\":\"2026-04-01\"}"))
                .andExpect(status().isCreated()), "$.documentId");
        call(kim, get("/api/projects/" + projectId + "/documents")) // 문서 tab, newest first
                .andExpect(jsonPath("$[0].id").value(documentId))
                .andExpect(jsonPath("$[0].fileName").value(preview.get("fileName")))
                .andExpect(jsonPath("$[0].contentType").value(XLSX))
                .andExpect(jsonPath("$[0].permitItemId").value(permitId))
                .andExpect(jsonPath("$[0].uploadedBy").value("김에이"));

        try (XSSFWorkbook wb = open(documentId)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(5);
            for (int s = 0; s < 5; s++) {
                var sheet = wb.getSheetAt(s);
                assertThat(sheet.getSheetName()).isEqualTo(sheetNames(preview).get(s));
                assertThat(text(sheet.getRow(0), 0)).startsWith("초안 — 제출 전 법정 서식 확인");
                assertThat(cells(sheet.getRow(1))).isEqualTo(JsonPath.read(preview, "$.sheets[" + s + "].headers"));
                assertThat(sheet.getLastRowNum() - 1).isEqualTo(rows(preview, s).size()); // rows after the note and the header
            }
            assertThat(text(wb.getSheet("측정 결과").getRow(0), 0)).contains("법령 원문 대조 전 초안"); // units are unverified
            assertThat(text(wb.getSheet("초과 및 조치").getRow(0), 0)).contains("법령 원문 대조 전 초안");
            assertThat(cells(wb.getSheet("의무사항 이행 내역").getRow(2))).containsExactly("허가조건 제4호", "굴뚝 배출가스 월 1회 측정",
                    "매월", 3.0, 1.0, 1.0, 1.0, 0.0, "2026-01-05, 2026-03-02", "증빙1.pdf, 증빙3.pdf");
            List<List<Object>> measured = new ArrayList<>();
            wb.getSheet("측정 결과").forEach(r -> measured.add(cells(r)));
            assertThat(measured).contains(List.of(STACK, "일산화탄소", "2026-01-04", 31.0, "ppm", 30.0, 103.3, "초과", ""));
            // a name that starts like a formula is kept as text behind an apostrophe, never as a formula cell
            assertThat(measured).contains(List.of("'" + OUTFALL, "BOD", "2026-01-05", 8.5, "mg/L", "", "", "한도 없음", ""));
            assertThat(cells(wb.getSheet("초과 및 조치").getRow(2))).contains("완료", "2026-01-05", "필터 교체");
            wb.forEach(sheet -> sheet.forEach(r -> r.forEach(c -> assertThat(c.getCellType()).isNotEqualTo(CellType.FORMULA))));
        }
    }

    @Test
    void a_shorter_period_or_fewer_points_leaves_the_rest_out() throws Exception {
        obligation();
        measure(stack, "2026-01-03", "CO", "12", "ppm");
        measure(outfall, "2026-01-05", "BOD", "8.5", "mg/L");
        complete(openTask("OBLIGATION"), "{}"); // cycle 2 is due 2026-02-28, after the period below

        call(kim, get(reports + "/preview?from=2026-01-04&to=2026-02-15&emissionPointIds=" + outfall))
                .andExpect(jsonPath("$.sheets[0].rows[?(@[0]=='포함 배출구')][1]").value(OUTFALL))
                .andExpect(jsonPath("$.sheets[1].rows[0][3]").value(1)) // only the cycle due 2026-01-31
                .andExpect(jsonPath("$.sheets[1].rows[0][4]").value(1))
                .andExpect(jsonPath("$.sheets[2].rows.length()").value(1))
                .andExpect(jsonPath("$.sheets[2].rows[0][1]").value("BOD"));

        // a point of another permit item cannot be included
        long otherPoint = point("보일러 굴뚝", "AIR", permit(projectId, "폐기물처리시설 설치승인"));
        call(kim, get(reports + "/preview?from=2026-01-01&to=2026-02-15&emissionPointIds=" + otherPoint))
                .andExpect(status().isUnprocessableEntity());
        call(kim, get(reports + "/preview?from=2026-02-15&to=2026-01-01")).andExpect(status().isBadRequest());
        call(kim, json(post(reports), "{\"from\":\"2026-01-01\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void an_empty_period_still_gives_five_sheets_with_headers_only() throws Exception {
        measure(stack, "2026-01-03", "CO", "12", "ppm");

        long documentId = id(call(kim, json(post(reports), "{\"from\":\"2024-01-01\",\"to\":\"2024-06-30\"}"))
                .andExpect(status().isCreated()), "$.documentId");
        try (XSSFWorkbook wb = open(documentId)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(5);
            for (int s = 1; s < 5; s++) {
                assertThat(wb.getSheetAt(s).getLastRowNum()).isEqualTo(1); // the draft note and the header row
                assertThat(text(wb.getSheetAt(s).getRow(0), 0)).startsWith("초안 — 제출 전 법정 서식 확인");
            }
            assertThat(cells(wb.getSheet("측정 결과").getRow(1)))
                    .containsExactly("배출구", "항목", "측정일", "값", "단위", "한도", "한도 대비 %", "판정", "비고");
        }
    }

    @Test
    void only_project_members_get_a_report_and_only_for_a_permit_of_that_project() throws Exception {
        for (String outsider : List.of(login("lee@a.com"), login("park@b.com"))) { // same company, other company
            call(outsider, get(reports + "/preview?from=2026-01-01&to=2026-01-31")).andExpect(status().isForbidden());
            call(outsider, json(post(reports), "{\"from\":\"2026-01-01\",\"to\":\"2026-01-31\"}")).andExpect(status().isForbidden());
        }
        assertThat(documentNames()).isEmpty();

        // park's own project cannot reach A's permit item through its own path
        String park = login("park@b.com");
        long parkProject = id(call(park, json(post("/api/projects"), """
                {"name":"B 하수처리장","facilityType":"SEWAGE","location":"부산"}""")));
        String crossed = "/api/projects/" + parkProject + "/permit-items/" + permitId + "/compliance-reports";
        call(park, get(crossed + "/preview?from=2026-01-01&to=2026-01-31")).andExpect(status().isNotFound());
        call(park, json(post(crossed), "{\"from\":\"2026-01-01\",\"to\":\"2026-01-31\"}")).andExpect(status().isNotFound());
        assertThat(documentNames()).isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private long permit(long project, String name) throws Exception {
        return id(call(kim, json(post("/api/projects/" + project + "/permits"),
                "{\"name\":\"" + name + "\",\"category\":\"FACILITY\",\"status\":\"COMPLETED\"}")));
    }

    private long point(String name, String medium, long permit) throws Exception {
        return id(call(kim, json(post("/api/projects/" + projectId + "/emission-points"),
                "{\"medium\":\"%s\",\"name\":\"%s\",\"permitItemId\":%d}".formatted(medium, name, permit))).andExpect(status().isCreated()));
    }

    private long measure(long point, String day, String pollutant, String value, String unit) throws Exception {
        return id(call(kim, json(post("/api/emission-points/" + point + "/measurements"), """
                {"rows":[{"measuredOn":"%s","pollutant":"%s","value":%s,"unit":"%s"}]}"""
                .formatted(day, pollutant, value, unit))).andExpect(status().isCreated()), "$[0].id");
    }

    /** An approved monthly obligation of the permit, first due 2026-01-31. */
    private long obligation() throws Exception {
        long suggestion = id(call(kim, json(post("/api/projects/" + projectId + "/suggestions"), """
                {"permitItemId":%d,"payload":{"citation_raw":"허가조건 제4호","summary":"굴뚝 배출가스 월 1회 측정",
                 "requirement_text":"소각로 굴뚝 배출가스를 월 1회 이상 측정할 것",
                 "type":"MEASURE_SURVEY","category":"AIR_QUALITY","frequency":"MONTHLY"}}""".formatted(permitId)))
                .andExpect(status().isCreated()));
        return id(call(kim, json(post("/api/suggestions/" + suggestion + "/accept"), "{\"firstDueDate\":\"2026-01-31\",\"version\":0}"))
                .andExpect(status().isOk()), "$.appliedEntityId");
    }

    private long openTask(String kind) throws Exception {
        return id(call(kim, get("/api/projects/" + projectId + "/tasks")), "$[?(@.kind=='" + kind + "' && @.status=='OPEN')].id");
    }

    /** Attaches a new evidence file (증빙N.pdf) and completes the task. */
    private void complete(long task, String body) throws Exception {
        long document = id(call(kim, multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents")
                .file(new MockMultipartFile("file", "증빙" + ++files + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes())))
                .andExpect(status().isCreated()));
        call(kim, json(post("/api/tasks/" + task + "/evidence"), "{\"documentId\":" + document + "}")).andExpect(status().isOk());
        call(kim, json(post("/api/tasks/" + task + "/complete"), body)).andExpect(status().isOk());
    }

    private List<String> documentNames() {
        return jdbc.queryForList("SELECT file_name FROM document WHERE file_name LIKE '이행보고서초안%'", String.class);
    }

    private XSSFWorkbook open(long documentId) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(
                call(kim, get("/api/projects/" + projectId + "/permits/" + permitId + "/documents/" + documentId))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
    }

    private static List<String> sheetNames(Map<String, Object> preview) {
        return JsonPath.read(preview, "$.sheets[*].name");
    }

    private static List<List<Object>> rows(Map<String, Object> preview, int sheet) {
        return JsonPath.read(preview, "$.sheets[" + sheet + "].rows");
    }

    private static String text(Row row, int cell) {
        return row.getCell(cell).getStringCellValue();
    }

    private static List<Object> cells(Row row) {
        List<Object> values = new ArrayList<>();
        for (Cell c : row) {
            values.add(c.getCellType() == CellType.NUMERIC ? (Object) c.getNumericCellValue() : c.getStringCellValue());
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
        return id(r, "$.id");
    }

    /** A number, or the single match of a filter expression. */
    private static long id(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
        return ((Number) (v instanceof List<?> l ? l.get(0) : v)).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
