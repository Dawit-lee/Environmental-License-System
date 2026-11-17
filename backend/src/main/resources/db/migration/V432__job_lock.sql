-- M7: one row per scheduled job; a server runs the job only while it holds the lease (core.JobLock).
CREATE TABLE job_lock (
    name         VARCHAR(64) PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    locked_by    VARCHAR(100)
);
