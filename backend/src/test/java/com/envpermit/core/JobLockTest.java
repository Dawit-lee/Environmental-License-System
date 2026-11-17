package com.envpermit.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.support.IntegrationTest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M7: a second server (another JobLock) skips a job while the first holds it, and runs it once the lease ends. */
class JobLockTest extends IntegrationTest {

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM job_lock");
    }

    @Test
    void only_one_server_runs_the_job_at_a_time() {
        JobLock a = new JobLock(jdbc);
        JobLock b = new JobLock(jdbc);
        AtomicInteger runs = new AtomicInteger();

        boolean ranA = a.run("t", Duration.ofMinutes(5), Duration.ZERO,
                () -> assertThat(b.run("t", Duration.ofMinutes(5), Duration.ZERO, runs::incrementAndGet)).isFalse());

        assertThat(ranA).isTrue();
        assertThat(runs).hasValue(0);
        assertThat(b.run("t", Duration.ofMinutes(5), Duration.ZERO, runs::incrementAndGet)).isTrue(); // released
        assertThat(runs).hasValue(1);
    }

    @Test
    void at_least_keeps_a_short_daily_job_from_running_twice() {
        JobLock a = new JobLock(jdbc);
        JobLock b = new JobLock(jdbc);
        assertThat(a.run("daily", Duration.ofHours(1), Duration.ofMinutes(10), () -> { })).isTrue();
        assertThat(b.run("daily", Duration.ofHours(1), Duration.ofMinutes(10), () -> { })).isFalse();
    }

    @Test
    void a_failing_job_still_releases_the_lock() {
        JobLock a = new JobLock(jdbc);
        try {
            a.run("boom", Duration.ofMinutes(5), Duration.ZERO, () -> {
                throw new IllegalStateException("x");
            });
        } catch (IllegalStateException expected) {
            // the job's own error reaches the caller
        }
        assertThat(new JobLock(jdbc).run("boom", Duration.ofMinutes(5), Duration.ZERO, () -> { })).isTrue();
    }

}
