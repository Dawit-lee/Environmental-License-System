package com.envpermit.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.support.InMemoryMailer;
import com.envpermit.support.IntegrationTest;
import com.envpermit.support.Measure;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Measurement (docs/measurements/CONTRIBUTIONS.md §4): the daily D-90/60/30/7 job over N deadline events.
 * 100 projects of 3 members each; event i is due in (i mod 120) days, even i has an assignee (one reminder),
 * odd i has none (every member is reminded). The expected count is this arithmetic, not the job's own query.
 */
@Tag("measurement")
class NotificationBatchMeasurement extends IntegrationTest {

    static final int PROJECTS = 100, USERS = 20;

    @Autowired NotificationJob job;
    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired InMemoryMailer mailer;

    List<Long> userIds = new ArrayList<>();
    List<Long> projectIds;

    @BeforeEach
    void seed() {
        truncateAll();
        mailer.clear();
        long company = companies.save(new Company("A환경")).getId();
        for (int u = 0; u < USERS; u++) {
            userIds.add(users.save(new User("u" + u + "@a.com", "x", "담당" + u, User.Role.USER, company)).getId());
        }
        jdbc.update("INSERT INTO project (company_id, name, facility_type, location, status) "
                + "SELECT ?, '합성 시설 ' || g, 'INCINERATION', '가온', 'IN_PROGRESS' FROM generate_series(1, ?) g", company, PROJECTS);
        projectIds = jdbc.queryForList("SELECT id FROM project ORDER BY id", Long.class);
        for (int p = 0; p < PROJECTS; p++) {
            for (int k = 0; k < 3; k++) {
                jdbc.update("INSERT INTO project_member (project_id, user_id, is_admin) VALUES (?, ?, ?)",
                        projectIds.get(p), userIds.get((p + k) % USERS), k == 0);
            }
        }
    }

    @Test
    void one_daily_run() {
        Measure.log("## 4-a. 기한 알림 배치 1회 실행 — 프로젝트 %d개(구성원 3명씩), 기한 이벤트 N건, 기한은 오늘+0~119일에 고르게", PROJECTS);
        Measure.log("| 기한 이벤트 | 기대 알림(산술) | 생성된 알림 | 단계별(D-7/30/60/90) | 1회 실행 시간 | 같은 날 재실행 시 새 알림 | 재실행 시간 | 요약 메일 | 메일 재실행 |");
        for (int n : new int[] {1_000, 10_000}) {
            int runs = n <= 1_000 ? 5 : 3;
            LocalDate today = LocalDate.now(clock);
            events(n, today, 0, 120);
            int expected = 0;
            int[] byOffset = new int[4]; // 7, 30, 60, 90
            for (int i = 1; i <= n; i++) {
                int daysLeft = i % 120;
                if (daysLeft <= 90) {
                    int recipients = i % 2 == 0 ? 1 : 3;
                    expected += recipients;
                    byOffset[daysLeft <= 7 ? 0 : daysLeft <= 30 ? 1 : daysLeft <= 60 ? 2 : 3] += recipients;
                }
            }
            long[] nanos = new long[runs];
            for (int r = 0; r < runs; r++) {
                jdbc.execute("TRUNCATE notification RESTART IDENTITY");
                long t0 = System.nanoTime();
                job.createDueNotifications();
                nanos[r] = System.nanoTime() - t0;
            }
            int created = count("SELECT count(*) FROM notification");
            List<Integer> offsets = jdbc.queryForList("SELECT count(*) FROM notification GROUP BY offset_days ORDER BY offset_days", Integer.class);
            long t0 = System.nanoTime();
            job.createDueNotifications();
            long again = System.nanoTime() - t0;
            int afterRerun = count("SELECT count(*) FROM notification");

            job.sendDigests();
            int mails = mailer.sent().size();
            job.sendDigests();
            int mailsAgain = mailer.sent().size() - mails;
            Measure.log("| %,d건 | %,d건 (%s) | %,d건 | %s | %s | %d건 | %.1fms | %d통 (알림 받은 사용자 %d명) | %d통 |", n, expected,
                    java.util.Arrays.toString(byOffset), created, offsets, Measure.millis(nanos), afterRerun - created, again / 1e6,
                    mails, count("SELECT count(DISTINCT user_id) FROM notification"), mailsAgain);
            assertThat(created).isEqualTo(expected);
            assertThat(afterRerun).isEqualTo(created);
            mailer.clear();
            jdbc.execute("TRUNCATE notification, deadline_event RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void every_day_for_125_days() {
        int n = 1_000, days = 125;
        events(n, LocalDate.now(clock), 100, 20); // due in 100..119 days: every event passes all four stages
        int recipients = 0;
        for (int i = 1; i <= n; i++) {
            recipients += i % 2 == 0 ? 1 : 3;
        }
        long t0 = System.nanoTime();
        for (int d = 0; d < days; d++) {
            job.createDueNotifications();
            clock.advance(Duration.ofDays(1));
        }
        long nanos = System.nanoTime() - t0;
        int created = count("SELECT count(*) FROM notification");
        int complete = count("SELECT count(*) FROM (SELECT 1 FROM notification GROUP BY deadline_event_id, user_id "
                + "HAVING array_agg(offset_days ORDER BY offset_days) = ARRAY[7, 30, 60, 90]) x");
        Measure.log("## 4-b. 기한 알림 배치를 %d일간 매일 실행 — 기한 이벤트 %,d건(받는 사람 %,d명), 기한은 시작일+100~119일", days, n, recipients);
        Measure.log("기대 알림 %,d건(받는 사람 × 4단계) 중 생성 %,d건, D-90/60/30/7을 한 번씩만 받은 (이벤트, 사용자) 쌍: %s, %d회 실행 총 %.1f초",
                recipients * 4, created, Measure.pct(complete, recipients), days, nanos / 1e9);
        assertThat(created).isEqualTo(recipients * 4);
    }

    /** Event i (1..n): project i mod 100, due today + offset + (i mod span) days; even i is assigned to that project's first member. */
    private void events(int n, LocalDate today, int offset, int span) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            int p = i % PROJECTS;
            rows.add(new Object[] {(long) i, projectIds.get(p), "합성 인허가 " + i + " 만료", today.plusDays(offset + i % span),
                    i % 2 == 0 ? userIds.get(p % USERS) : null});
        }
        jdbc.batchUpdate("INSERT INTO deadline_event (kind, target_id, project_id, title, due_date, assignee_id) "
                + "VALUES ('PERMIT_EXPIRY', ?, ?, ?, ?, ?)", rows);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
