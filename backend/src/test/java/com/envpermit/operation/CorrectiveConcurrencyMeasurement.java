package com.envpermit.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.IntegrationTest;
import com.envpermit.support.Measure;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Measurement (docs/measurements/CONTRIBUTIONS.md §3): N exceeding values saved at the same moment for one
 * emission point and item → how many corrective tasks open (expected 1)?
 * "잠금 있음" is the real API. "잠금 없음" is a control built here: the same insert and the same
 * {@link ExceedanceAlerts#raise} in one transaction, without the controller's {@code SELECT ... FOR UPDATE}.
 */
@Tag("measurement")
class CorrectiveConcurrencyMeasurement extends IntegrationTest {

    static final int N = 50, ROUNDS = 5;

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ExceedanceAlerts alerts;
    @Autowired TransactionTemplate tx;

    String kim;
    long kimId;
    long projectId;
    long stack;
    long limitId;

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long a = companies.save(new Company("A환경")).getId();
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, a)).getId();
        kim = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"kim@a.com\",\"password\":\"pw\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        projectId = id(send("/api/projects", """
                {"name":"가온 자원회수시설","facilityType":"INCINERATION","location":"가온"}"""));
        long permit = id(send("/api/projects/" + projectId + "/permits", """
                {"name":"대기배출시설 설치허가","category":"FACILITY","status":"COMPLETED"}"""));
        stack = id(send("/api/projects/" + projectId + "/emission-points",
                "{\"medium\":\"AIR\",\"name\":\"소각로 1호기 굴뚝\",\"permitItemId\":" + permit + "}"));
        limitId = id(send("/api/emission-points/" + stack + "/limits",
                "{\"pollutant\":\"CO\",\"limitValue\":30,\"effectiveFrom\":\"2026-01-01\"}"));
    }

    @Test
    void concurrent_exceedances_open_one_task() throws Exception {
        Measure.log("## 3. 조치 업무 중복 방지 — 같은 배출구·항목에 초과 측정값 %d건 동시 저장, %d회 반복 (PostgreSQL 16, HikariCP 풀 10)", N, ROUNDS);
        Measure.log("| 구성 | 회차별 열린 조치 업무 수 | 저장된 측정값 | 회차 소요 |");
        String body = "{\"rows\":[{\"measuredOn\":\"2026-01-05\",\"pollutant\":\"CO\",\"value\":45,\"unit\":\"ppm\"}]}";
        List<Integer> locked = new ArrayList<>();
        long[] lockedNanos = new long[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            reset();
            lockedNanos[r] = together(() -> {
                int status = mvc.perform(post("/api/emission-points/" + stack + "/measurements")
                        .header("Authorization", "Bearer " + kim).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
                assertThat(status).isEqualTo(201);
                return null;
            });
            locked.add(openTasks());
            assertThat(measurements()).isEqualTo(N);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM task_event WHERE type = 'MEASUREMENT_ADDED'", Integer.class))
                    .isEqualTo(N - 1); // the other 49 values are appended to the one task
        }
        Measure.log("| 잠금 있음 (실제 API, 배출구 행 FOR UPDATE) | %s | %d건씩 | %s |", locked, N, Measure.millis(lockedNanos));

        List<Integer> unlocked = new ArrayList<>();
        long[] unlockedNanos = new long[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            reset();
            unlockedNanos[r] = together(() -> tx.execute(s -> {
                Long m = jdbc.queryForObject("INSERT INTO measurement (emission_point_id, pollutant, measured_on, value, unit, "
                        + "below_detection, source, limit_id, limit_value, warn_ratio, level, entered_by, entered_at) "
                        + "VALUES (?, 'CO', '2026-01-05', 45, 'ppm', false, 'MANUAL', ?, 30, 0.80, 'EXCEEDED', ?, ?) RETURNING id",
                        Long.class, stack, limitId, kimId, Timestamp.from(clock.instant()));
                alerts.raise(projectId, stack, List.of(m), kimId);
                return null;
            }));
            unlocked.add(openTasks());
            assertThat(measurements()).isEqualTo(N);
        }
        Measure.log("| 잠금 없음 (대조군: 같은 저장·알림 코드, 행 잠금만 제외) | %s | %d건씩 | %s |", unlocked, N, Measure.millis(unlockedNanos));
        Measure.log("잠금 있음: %d회 모두 조치 업무 1건. 잠금 없음: 평균 %.1f건 (최소 %d ~ 최대 %d)", ROUNDS,
                unlocked.stream().mapToInt(Integer::intValue).average().orElse(0),
                unlocked.stream().mapToInt(Integer::intValue).min().orElse(0), unlocked.stream().mapToInt(Integer::intValue).max().orElse(0));
        assertThat(locked).containsOnly(1);
    }

    /** N threads released by one latch; returns the wall time until all are done. */
    private long together(Callable<Object> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch ready = new CountDownLatch(N), go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return work.call();
            }));
        }
        ready.await();
        long t0 = System.nanoTime();
        go.countDown();
        for (Future<Object> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        long nanos = System.nanoTime() - t0;
        pool.shutdown();
        return nanos;
    }

    private void reset() {
        jdbc.execute("TRUNCATE measurement, obligation_task, notification, deadline_event RESTART IDENTITY CASCADE");
    }

    private int openTasks() {
        return jdbc.queryForObject("SELECT count(*) FROM obligation_task WHERE kind = 'CORRECTIVE' AND status = 'OPEN'", Integer.class);
    }

    private int measurements() {
        return jdbc.queryForObject("SELECT count(*) FROM measurement", Integer.class);
    }

    private String send(String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + kim).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
