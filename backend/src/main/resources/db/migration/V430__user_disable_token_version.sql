-- M2: a company master can disable a user; bumping token_version revokes every access/refresh token of that user (logout, disable).
ALTER TABLE users ADD COLUMN disabled_at   TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
