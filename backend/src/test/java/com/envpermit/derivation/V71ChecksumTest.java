package com.envpermit.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.envpermit.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V71 source_ref was renamed in place; an existing DB needs the one-line checksum update in deploy/oracle/README.md. */
class V71ChecksumTest extends IntegrationTest {

    static final int OLD_CHECKSUM = -1223746793; // V71 as applied before the rename
    static final int NEW_CHECKSUM = -768518334;
    static final String RENAMED = "wiki/소각시설-인허가-사례.md";

    @Autowired JdbcConnectionDetails db;

    @Test
    void existing_db_passes_after_the_checksum_update_and_v280_rewrites_the_old_ref() {
        jdbc.execute("DROP DATABASE IF EXISTS v71fix");
        jdbc.execute("CREATE DATABASE v71fix");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                db.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/v71fix$1"), db.getUsername(), db.getPassword());
        JdbcTemplate t = new JdbcTemplate(ds);
        flyway(ds).migrate();
        assertThat(t.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '71'", Integer.class))
                .isEqualTo(NEW_CHECKSUM);
        assertThat(t.queryForObject("SELECT count(*) FROM derivation_rule WHERE source_ref LIKE ?", Long.class,
                RENAMED + "%")).isEqualTo(4);

        // existing DB: old V71 checksum, old-style ref (fictional prefix, same shape) and V280 not yet run
        t.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '71'", OLD_CHECKSUM);
        t.update("DELETE FROM flyway_schema_history WHERE version = '280'");
        t.update("UPDATE derivation_rule SET source_ref = replace(source_ref, ?, 'wiki/가상-소각시설-인허가.md')", RENAMED);
        assertThatThrownBy(() -> flyway(ds).migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("71");

        t.update("UPDATE flyway_schema_history SET checksum = " + NEW_CHECKSUM + " WHERE version = '71'");
        flyway(ds).migrate();
        flyway(ds).migrate(); // rerun is a no-op
        assertThat(t.queryForObject("SELECT count(*) FROM derivation_rule WHERE source_ref LIKE ?", Long.class,
                RENAMED + "%")).isEqualTo(4);
        assertThat(t.queryForObject("SELECT count(*) FROM derivation_rule WHERE source_ref LIKE '%가상-%'", Long.class))
                .isZero();
    }

    static Flyway flyway(DriverManagerDataSource ds) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load();
    }
}
