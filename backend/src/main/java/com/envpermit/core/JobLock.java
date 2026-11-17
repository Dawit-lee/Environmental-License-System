package com.envpermit.core;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M7: keeps a scheduled job to one server at a time (a minimal ShedLock on the job_lock table, DB time only).
 * The winner holds a lease for at most {@code atMost} (frees the job if a server dies mid-run) and at least
 * {@code atLeast} (so a server whose cron fires a few seconds later does not run a short daily job again).
 */
@Component
public class JobLock {

    private static final Logger log = LoggerFactory.getLogger(JobLock.class);

    private final JdbcTemplate jdbc;
    private final String owner = UUID.randomUUID().toString();

    public JobLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this server ran the job, false if another server holds the lock */
    public boolean run(String name, Duration atMost, Duration atLeast, Runnable job) {
        jdbc.update("INSERT INTO job_lock (name, locked_until) VALUES (?, now()) ON CONFLICT DO NOTHING", name);
        int got = jdbc.update("UPDATE job_lock SET locked_until = now() + make_interval(secs => ?), locked_by = ? "
                + "WHERE name = ? AND locked_until <= now()", atMost.toSeconds(), owner, name);
        if (got == 0) {
            log.info("{}: another server holds the lock, skipping", name);
            return false;
        }
        long started = System.nanoTime();
        try {
            job.run();
        } finally {
            long held = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            jdbc.update("UPDATE job_lock SET locked_until = now() + make_interval(secs => ?) WHERE name = ? AND locked_by = ?",
                    Math.max(0, atLeast.toSeconds() - held), name, owner);
        }
        return true;
    }
}
