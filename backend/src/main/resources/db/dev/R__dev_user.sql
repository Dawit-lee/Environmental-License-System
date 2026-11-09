-- Local dev only (runs with the "dev" profile). Login: dev@envpermit.local / dev1234!
INSERT INTO users (email, password_hash, name, role)
VALUES ('dev@envpermit.local', '$2a$10$LmSvVrFIpIK00B6QLKhF9uj7n.mYazuToeyq6LsET0JtD8qcZMz3S', '개발자', 'ADMIN')
ON CONFLICT (email) DO NOTHING;
