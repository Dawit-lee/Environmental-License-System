package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.envpermit.support.Measure;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Measurement (docs/measurements/CONTRIBUTIONS.md §6): time and memory of the compliance report near the
 * 5,000-row cap, and whether text that a spreadsheet would run as a formula comes back as a plain text cell.
 * "Dangerous" is the OWASP CSV-injection list (first character = + - @ TAB CR), not {@link ComplianceReport#safe}.
 */
@Tag("measurement")
class XlsxExportMeasurement extends IntegrationTest {

    static final LocalDate FROM = LocalDate.parse("2020-01-01"), TO = LocalDate.parse("2040-01-01");
    static final int RUNS = 5;

    @Autowired ComplianceReport report;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;

    String kim;
    long kimId;
    long projectId;
    long permitId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a)).getId();
        kim = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"kim@a.com\",\"password\":\"pw\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        projectId = id(send("/api/projects", """
                {"name":"가온 자원회수시설","facilityType":"INCINERATION","location":"가온"}"""));
        permitId = id(send("/api/projects/" + projectId + "/permits", """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}"""));
    }

    @Test
    void time_and_memory_near_the_row_cap() throws Exception {
        long point = point("소각로 1호기 굴뚝");
        Measure.log("## 6-a. 이행 보고서 XLSX 생성 — 측정 결과 시트 N행, 조회(load) + 파일 생성(xlsx), 준비 1회 뒤 %d회 (테스트 JVM 힙 최대 %dMB)",
                RUNS, Runtime.getRuntime().maxMemory() >> 20);
        Measure.log("| 측정 결과 행 수 | 결과 | 조회+생성 시간 | 그중 파일 생성 | 생성 중 할당량 | 힙 최대 사용량(상한 추정) | 파일 크기 | 다시 열어 센 행 수 |");
        int inserted = 0;
        for (int n : new int[] {1_000, 4_999, 5_000}) {
            jdbc.update("INSERT INTO measurement (emission_point_id, pollutant, measured_on, value, unit, below_detection, source, "
                    + "limit_value, warn_ratio, level, entered_by, entered_at) SELECT ?, 'CO', DATE '2020-01-01' + g::int, 10 + g % 20, "
                    + "'ppm', false, 'MANUAL', 30, 0.80, 'NORMAL', ?, now() FROM generate_series(?, ?) g", point, kimId, inserted + 1, n);
            inserted = n;
            report.xlsx(load()); // warm-up
            long[] total = new long[RUNS], write = new long[RUNS];
            long allocated = 0, peak = 0;
            byte[] file = null;
            for (int r = 0; r < RUNS; r++) {
                System.gc();
                long before = heapUsed();
                heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
                long alloc0 = allocatedBytes();
                long t0 = System.nanoTime();
                ComplianceReport.Report loaded = load();
                long t1 = System.nanoTime();
                file = report.xlsx(loaded);
                total[r] = System.nanoTime() - t0;
                write[r] = System.nanoTime() - t1;
                allocated = Math.max(allocated, allocatedBytes() - alloc0);
                // sum of each pool's own peak: an upper bound, the pools need not peak at the same moment
                peak = Math.max(peak, heapPools().stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum() - before);
            }
            int rows;
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
                rows = wb.getSheet("측정 결과").getLastRowNum() + 1 - 2; // note row + header row
            }
            Measure.log("| %,d행 | 생성됨 | %s | %s | %.1fMB | %.1fMB | %.1fKB | %,d행 |", n, Measure.millis(total), Measure.millis(write),
                    allocated / 1048576.0, peak / 1048576.0, file.length / 1024.0, rows);
            assertThat(rows).isEqualTo(n);
        }
        jdbc.update("INSERT INTO measurement (emission_point_id, pollutant, measured_on, value, unit, below_detection, source, "
                + "limit_value, warn_ratio, level, entered_by, entered_at) VALUES (?, 'CO', '2039-01-01', 10, 'ppm', false, 'MANUAL', "
                + "30, 0.80, 'NORMAL', ?, now())", point, kimId);
        long t0 = System.nanoTime();
        assertThatThrownBy(this::load).isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        Measure.log("| 5,001행 | 거절(422, 파일을 만들지 않음) | %.1fms | - | - | - | - | - |", (System.nanoTime() - t0) / 1e6);
    }

    record Sample(String group, String text) {
    }

    @Test
    void formula_like_text_stays_text() throws Exception {
        List<Sample> samples = new ArrayList<>();
        List<String> bodies = List.of("1+1", "SUM(A1:A9)", "HYPERLINK(\"http://example.com\",\"확인\")", "cmd|'/c calc'!A1", "A1*2",
                "IMPORTXML(\"http://example.com\",\"//a\")", "2+5+cmd|' /C calc'!A0", "DDE(\"cmd\";\"/C calc\";\"!A0\")", "10%", "굴뚝 1호");
        for (String first : List.of("=", "+", "-", "@", "\t", "\r")) {
            bodies.forEach(b -> samples.add(new Sample("위험", first + b)));
        }
        for (String name : List.of("소각로 1호기 굴뚝", "소각로 2호기 굴뚝", "A-1 방류구", "굴뚝(=주배출구)", "1+1 배출구", "kim@a.com 담당 배출구",
                "보일러 #2", "방류구 (-) 측", "건조시설 배출구", "저장조 A", "TMS 굴뚝", "3호기+4호기 공용", "혐기성 소화조", "No.1 Stack",
                "침출수 처리시설 방류구", "악취방지시설 출구", "매립가스 포집시설", "파쇄시설 집진기", "선별시설 집진기", "100% 예비 굴뚝")) {
            samples.add(new Sample("일반", name));
        }
        for (String first : List.of(" ", "＝", "\n", "\"", "'")) { // not on the list, but close to it
            bodies.subList(0, 4).forEach(b -> samples.add(new Sample("변형", first + "=" + b)));
        }
        for (int i = 0; i < samples.size(); i++) { // the value column carries the sample index
            long p = jdbc.queryForObject("INSERT INTO emission_point (project_id, permit_item_id, medium, name) VALUES (?, ?, 'AIR', ?) "
                    + "RETURNING id", Long.class, projectId, permitId, samples.get(i).text());
            jdbc.update("INSERT INTO measurement (emission_point_id, pollutant, measured_on, value, unit, below_detection, source, "
                    + "limit_value, warn_ratio, level, entered_by, entered_at) VALUES (?, 'CO', '2026-01-05', ?, 'ppm', false, 'MANUAL', "
                    + "30, 0.80, 'NORMAL', ?, now())", p, i, kimId);
        }

        int[] textCell = new int[3], prefixed = new int[3], same = new int[3], total = new int[3]; // 위험, 일반, 변형
        List<String> groups = List.of("위험", "일반", "변형");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(report.xlsx(load())))) {
            Sheet sheet = wb.getSheet("측정 결과");
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                Sample s = samples.get((int) row.getCell(3).getNumericCellValue());
                Cell cell = row.getCell(0);
                int g = groups.indexOf(s.group());
                total[g]++;
                if (cell.getCellType() == CellType.STRING) {
                    textCell[g]++;
                    String v = cell.getStringCellValue();
                    if (v.equals(s.text())) {
                        same[g]++;
                    } else if (v.startsWith("'")) {
                        prefixed[g]++;
                    }
                }
            }
        }
        Measure.log("## 6-b. 수식으로 시작하는 값 — 배출구 이름 표본 %d건을 보고서로 내보낸 뒤 파일을 다시 열어 셀 종류 확인", samples.size());
        Measure.log("| 표본 | 건수 | 문자 셀(수식 셀 아님) | 앞에 ' 를 붙여 저장 | 원래 값 그대로 |");
        Measure.log("| 위험: 첫 글자가 = + - @ 탭 CR (OWASP 목록) | %d | %s | %s | %d |", total[0], Measure.pct(textCell[0], total[0]),
                Measure.pct(prefixed[0], total[0]), same[0]);
        Measure.log("| 일반 이름 (기호가 중간에 있음) | %d | %s | %d | %s |", total[1], Measure.pct(textCell[1], total[1]), prefixed[1],
                Measure.pct(same[1], total[1]));
        Measure.log("| 변형: 공백·전각 ＝·줄바꿈·따옴표 뒤에 = | %d | %s | %s | %d |", total[2], Measure.pct(textCell[2], total[2]),
                Measure.pct(prefixed[2], total[2]), same[2]);
        assertThat(total[0] + total[1] + total[2]).isEqualTo(samples.size());
    }

    private ComplianceReport.Report load() {
        return report.load(projectId, permitId, FROM, TO, null, kimId);
    }

    private long point(String name) throws Exception {
        return id(send("/api/projects/" + projectId + "/emission-points",
                "{\"medium\":\"AIR\",\"name\":\"" + name + "\",\"permitItemId\":" + permitId + "}"));
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    private String send(String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + kim).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
