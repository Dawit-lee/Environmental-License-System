package com.envpermit.law;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.FixtureLawApi;
import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.IntegrationTest;
import com.envpermit.support.Measure;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Measurement (docs/measurements/CONTRIBUTIONS.md §5): the amendment batch on the recorded 2026-10-01 day
 * (폐기물관리법 시행규칙, MST 290881, 공포 10-01, only 제10조 changed) with N permit items linked in eight ways.
 * The expected level of each way is what the design rule (§6) says for it, written in {@link #WAYS};
 * the measurement never calls {@link ImpactRule} to get it.
 */
@Tag("measurement")
class LawBatchMeasurement extends IntegrationTest {

    static final String ACT = "001771", RULE = "008567", OTHER = "001823"; // 폐기물관리법 / 시행규칙 / 건축법
    static final int PROJECTS = 50;

    /** how permit item i (i mod 8) is linked → expected impact level, null = no alert */
    static final List<String[]> WAYS = List.of(
            new String[] {"개정 법령, 조문 미지정", "DIRECT"},
            new String[] {"개정 법령, 바뀐 조문(제10조)", "DIRECT"},
            new String[] {"개정 법령, 다른 조문(제11조)", "REFERENCE"},
            new String[] {"상위 법률(폐기물관리법)에 연결", "DIRECT"},
            new String[] {"관계없는 법령(건축법)", null},
            new String[] {"개정 법령이지만 완료된 인허가", null},
            new String[] {"공포일 뒤에 만든 연결", null},
            new String[] {"다른 조문 + 상위 법률(둘 다 연결)", "DIRECT"});

    @Autowired AmendmentBatch batch;
    @Autowired LawApiClient api;
    @Autowired InMemoryMailer mailer;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;

    List<Long> projectIds;

    @BeforeEach
    void seed() {
        truncateAll();
        ((FixtureLawApi) api).reset();
        mailer.clear();
        long company = companies.save(new Company("A환경")).getId();
        jdbc.update("INSERT INTO project (company_id, name, facility_type, location, status) "
                + "SELECT ?, '합성 시설 ' || g, 'INCINERATION', '가온', 'IN_PROGRESS' FROM generate_series(1, ?) g", company, PROJECTS);
        projectIds = jdbc.queryForList("SELECT id FROM project ORDER BY id", Long.class);
        for (int p = 0; p < PROJECTS; p++) { // one 책임자 per project, ten people in all
            if (p < 10) {
                users.save(new User("pm" + p + "@a.com", "x", "책임" + p, User.Role.USER, company));
            }
            jdbc.update("INSERT INTO project_member (project_id, user_id, is_admin) "
                    + "SELECT ?, id, true FROM users WHERE email = ?", projectIds.get(p), "pm" + p % 10 + "@a.com");
        }
        clock.set(LocalDate.parse("2026-10-02").atTime(6, 0).atZone(clock.getZone()).toInstant());
    }

    @AfterEach
    void restoreSeed() {
        catalogAt(290881L, "2026-10-01", "2026-10-02"); // V31 values; other tests read them
    }

    @Test
    void impact_of_one_amendment_on_n_permit_items() {
        Measure.log("## 5. 법령 개정 배치 1회 실행 — 법제처 응답 픽스처(2026-10-01, 폐기물관리법 시행규칙 제10조 개정), 프로젝트 %d개, 인허가 항목 N건을 8가지 방식으로 연결", PROJECTS);
        Measure.log("| 인허가 항목 | 정답과 일치한 항목 | 생성된 영향(직접/참고) | 1회 실행 시간 | 재실행 시 새 영향 | 재실행 시 메일 | 첫 실행 메일 |");
        for (int n : new int[] {1_000, 10_000}) {
            int runs = n <= 1_000 ? 5 : 3;
            permits(n);
            long[] nanos = new long[runs];
            for (int r = 0; r < runs; r++) {
                jdbc.execute("TRUNCATE law_amendment, deadline_event, notification RESTART IDENTITY CASCADE");
                mailer.clear();
                catalogAt(289271L, "2026-08-31", "2026-09-18"); // the version before the amendment
                cursor("2026-09-30");
                long t0 = System.nanoTime();
                batch.run();
                nanos[r] = System.nanoTime() - t0;
            }
            Map<String, int[]> byWay = new LinkedHashMap<>(); // way → matched, total
            Map<Long, String> got = new LinkedHashMap<>();
            jdbc.query("SELECT p.name, i.level FROM permit_item p LEFT JOIN permit_law_impact i ON i.permit_item_id = p.id ORDER BY p.id",
                    rs -> {
                        String[] way = WAYS.get(Integer.parseInt(rs.getString("name").substring(7)) % WAYS.size());
                        int[] t = byWay.computeIfAbsent(way[0] + " → " + (way[1] == null ? "알림 없음" : way[1]), k -> new int[2]);
                        t[1]++;
                        if (java.util.Objects.equals(way[1], rs.getString("level"))) {
                            t[0]++;
                        }
                    });
            int matched = byWay.values().stream().mapToInt(t -> t[0]).sum();
            int direct = count("SELECT count(*) FROM permit_law_impact WHERE level = 'DIRECT'");
            int reference = count("SELECT count(*) FROM permit_law_impact WHERE level = 'REFERENCE'");
            int mails = mailer.sent().size();

            cursor("2026-09-30"); // the same day again
            mailer.clear();
            batch.run();
            int newImpacts = count("SELECT count(*) FROM permit_law_impact") - direct - reference;
            Measure.log("| %,d건 | %s | %,d건 (%,d / %,d) | %s | %d건 | %d통 | %d통 (책임자 10명) |", n, Measure.pct(matched, n),
                    direct + reference, direct, reference, Measure.millis(nanos), newImpacts, mailer.sent().size(), mails);
            byWay.forEach((way, t) -> Measure.log("  - %,d건 기준 · %s: %s", n, way, Measure.pct(t[0], t[1])));
            assertThat(count("SELECT count(*) FROM law_amendment")).isEqualTo(1);
            jdbc.execute("TRUNCATE permit_item, law_amendment, deadline_event RESTART IDENTITY CASCADE");
        }
    }

    /** Permit item i is named "합성 인허가 i" and linked the way WAYS.get(i mod 8) says. */
    private void permits(int n) {
        long act = law(ACT), rule = law(RULE), other = law(OTHER);
        jdbc.update("INSERT INTO permit_item (project_id, name, category, status) SELECT (ARRAY[" + join(projectIds)
                + "])[1 + g % ?], '합성 인허가 ' || g, 'FACILITY', CASE WHEN g % 8 = 5 THEN 'COMPLETED' ELSE 'IN_PROGRESS' END "
                + "FROM generate_series(0, ?) g", PROJECTS, n - 1);
        Timestamp before = Timestamp.valueOf("2026-09-20 09:00:00"), after = Timestamp.valueOf("2026-10-02 09:00:00");
        List<Object[]> links = new ArrayList<>();
        jdbc.query("SELECT id, name FROM permit_item ORDER BY id", rs -> {
            long id = rs.getLong(1);
            switch (Integer.parseInt(rs.getString(2).substring(7)) % 8) {
                case 0, 5 -> links.add(new Object[] {id, rule, null, before});
                case 1 -> links.add(new Object[] {id, rule, "제10조", before});
                case 2 -> links.add(new Object[] {id, rule, "제11조", before});
                case 3 -> links.add(new Object[] {id, act, null, before});
                case 4 -> links.add(new Object[] {id, other, null, before});
                case 6 -> links.add(new Object[] {id, rule, "제10조", after});
                default -> {
                    links.add(new Object[] {id, rule, "제11조", before});
                    links.add(new Object[] {id, act, "제29조", before});
                }
            }
        });
        jdbc.batchUpdate("INSERT INTO permit_law_link (permit_item_id, law_catalog_id, article_no, source, created_at) "
                + "VALUES (?, ?, ?, 'MANUAL', ?)", links);
    }

    private long law(String lawId) {
        return jdbc.queryForObject("SELECT id FROM law_catalog WHERE law_id = ?", Long.class, lawId);
    }

    private static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void cursor(String ymd) {
        jdbc.update("INSERT INTO batch_cursor (job_name, last_success_date, consecutive_failures) VALUES (?, ?, 0) "
                + "ON CONFLICT (job_name) DO UPDATE SET last_success_date = EXCLUDED.last_success_date",
                AmendmentBatch.JOB, LocalDate.parse(ymd));
    }

    private void catalogAt(long mst, String promulgated, String enforced) {
        jdbc.update("UPDATE law_catalog SET last_mst = ?, last_promulgated_on = ?, last_enforced_on = ? WHERE law_id = ?",
                mst, LocalDate.parse(promulgated), LocalDate.parse(enforced), RULE);
    }
}
