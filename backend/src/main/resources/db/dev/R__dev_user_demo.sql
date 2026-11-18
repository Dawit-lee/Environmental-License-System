-- Local dev only: the public read-only demo login (core.auth.DemoReadOnly blocks its writes). All names are fictional.
--   demo@envpermit.local / demo1234!  (public on purpose), 회사 A USER, plain member of every project dev@ is in.
-- Off unless DEMO_USER_ENABLED=true (application-dev.yml). Flyway reruns this when that value changes;
-- turning it off disables an existing demo login. Projects dev@ joins later are added on the next rerun.
DO $$
DECLARE
    u_dev  BIGINT := (SELECT id FROM users WHERE email = 'dev@envpermit.local');
    u_demo BIGINT;
BEGIN
    IF '${demo_user_enabled}' <> 'true' THEN
        UPDATE users SET disabled_at = now() WHERE email = 'demo@envpermit.local' AND disabled_at IS NULL;
        RETURN;
    END IF;
    IF u_dev IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO users (email, password_hash, name, role, company_id)
    SELECT 'demo@envpermit.local', crypt('demo1234!', gen_salt('bf', 10)), '데모 사용자', 'USER', company_id
    FROM users WHERE id = u_dev
    ON CONFLICT (email) DO UPDATE SET disabled_at = NULL
    RETURNING id INTO u_demo;

    INSERT INTO project_member (project_id, user_id)
    SELECT project_id, u_demo FROM project_member WHERE user_id = u_dev
    ON CONFLICT DO NOTHING;
END $$;
