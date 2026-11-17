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

    private static void migrate(DriverManagerDataSource ds, String password) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/dev")
                .placeholders(Map.of("dev_seed_password", password)).load().migrate();
    }
}
