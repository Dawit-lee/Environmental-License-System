package com.envpermit.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.envpermit.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/** H1: the dev seed (db/dev) takes its login password from DEV_SEED_PASSWORD and never resets an existing one. */
class DevSeedTest extends IntegrationTest {

    @Autowired JdbcConnectionDetails db;
    @Autowired PasswordEncoder encoder;

    @Test
    void seed_logins_use_the_configured_password_and_a_rerun_keeps_it() {
        jdbc.execute("DROP DATABASE IF EXISTS devseed");
        jdbc.execute("CREATE DATABASE devseed");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                db.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/devseed$1"), db.getUsername(), db.getPassword());
        migrate(ds, "seed-pw-1");
        JdbcTemplate seeded = new JdbcTemplate(ds);
        List<String> emails = List.of("dev@envpermit.local", "kim@envpermit.local", "other@envpermit.local",
                "lee@envpermit.local", "choi@envpermit.local");
        for (String email : emails) {
            String hash = seeded.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
            assertThat(encoder.matches("seed-pw-1", hash)).as(email).isTrue();
            assertThat(encoder.matches("dev1234!", hash)).as(email).isFalse();
        }

        seeded.update("UPDATE users SET password_hash = ? WHERE email = 'dev@envpermit.local'", encoder.encode("rotated"));
        seeded.update("UPDATE flyway_schema_history SET checksum = 0 WHERE script LIKE 'R__dev_user%'"); // force a rerun
        migrate(ds, "seed-pw-2");
        assertThat(encoder.matches("rotated", seeded.queryForObject(
                "SELECT password_hash FROM users WHERE email = 'dev@envpermit.local'", String.class))).isTrue();
        assertThat(encoder.matches("seed-pw-1", seeded.queryForObject(
                "SELECT password_hash FROM users WHERE email = 'kim@envpermit.local'", String.class))).isTrue();
    }

    @Test
    void demo_login_is_created_only_when_enabled_as_a_plain_member_of_dev_projects_and_disabled_when_turned_off() {
        jdbc.execute("DROP DATABASE IF EXISTS devseed");
        jdbc.execute("CREATE DATABASE devseed");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                db.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/devseed$1"), db.getUsername(), db.getPassword());
        JdbcTemplate seeded = new JdbcTemplate(ds);
        migrate(ds, "seed-pw-1", "false");
        assertThat(seeded.queryForObject("SELECT count(*) FROM users WHERE email = 'demo@envpermit.local'", Long.class))
                .isZero();

        migrate(ds, "seed-pw-1", "true"); // a changed placeholder reruns the repeatable seed
        Map<String, Object> demo = seeded.queryForMap("SELECT id, password_hash, role, company_id, disabled_at FROM users "
                + "WHERE email = 'demo@envpermit.local'");
        assertThat(encoder.matches("demo1234!", (String) demo.get("password_hash"))).isTrue();
        assertThat(demo.get("role")).isEqualTo("USER");
        assertThat(demo.get("disabled_at")).isNull();
        assertThat(demo.get("company_id")).isEqualTo(seeded.queryForObject(
                "SELECT company_id FROM users WHERE email = 'dev@envpermit.local'", Long.class));
        assertThat(seeded.queryForList("SELECT project_id FROM project_member WHERE user_id = ? AND NOT is_admin "
                        + "AND NOT can_self_approve AND NOT can_bulk_approve ORDER BY 1", Long.class, demo.get("id")))
                .isEqualTo(seeded.queryForList("SELECT project_id FROM project_member m JOIN users u ON u.id = m.user_id "
                        + "WHERE u.email = 'dev@envpermit.local' ORDER BY 1", Long.class))
                .isNotEmpty();

        migrate(ds, "seed-pw-1", "false");
        assertThat(seeded.queryForObject("SELECT disabled_at IS NOT NULL FROM users WHERE email = 'demo@envpermit.local'",
                Boolean.class)).isTrue();
    }

    private static void migrate(DriverManagerDataSource ds, String password) {
        migrate(ds, password, "false");
    }

    private static void migrate(DriverManagerDataSource ds, String password, String demo) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/dev")
                .placeholders(Map.of("dev_seed_password", password, "demo_user_enabled", demo)).load().migrate();
    }
}
