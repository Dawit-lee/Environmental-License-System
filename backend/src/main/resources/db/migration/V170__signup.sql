-- Self signup: a company's invite code (admins share it), and join requests waiting for a company admin.
-- The default covers rows inserted by SQL (dev seed); the app makes its own codes (Company.newCode).
ALTER TABLE company ADD COLUMN invite_code VARCHAR(16) NOT NULL UNIQUE
    DEFAULT upper(substr(md5(random()::text || clock_timestamp()::text), 1, 10));

-- Kept apart from users so a pending applicant cannot log in. Approval creates the user.
CREATE TABLE join_request (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id    BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    reject_reason VARCHAR(500),
    ip            VARCHAR(45)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    decided_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    decided_at    TIMESTAMPTZ
);
-- one open request per email
CREATE UNIQUE INDEX join_request_pending_email_idx ON join_request (email) WHERE status = 'PENDING';
CREATE INDEX join_request_company_idx ON join_request (company_id, status);
