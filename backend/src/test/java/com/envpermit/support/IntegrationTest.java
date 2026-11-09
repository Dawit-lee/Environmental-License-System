package com.envpermit.support;

import com.envpermit.core.document.DocumentStorage;
import com.envpermit.law.LawApiClient;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for API tests: real PostgreSQL 16 (Testcontainers, shared across tests) + fixed clock
 * + in-memory document storage (replaces S3, so tests need no MinIO).
 * Each test starts at {@link #START}; move time with {@code clock.advance(...)}.
 */
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-0123456789",
        "app.scheduling=false"}) // tests run jobs by hand
@AutoConfigureMockMvc
@Import(IntegrationTest.Config.class)
public abstract class IntegrationTest {

    public static final Instant START = Instant.parse("2026-01-05T00:00:00Z");

    // started once per JVM; Ryuk removes it after the run
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected TestClock clock;
    @Autowired protected JdbcTemplate jdbc;
    @TestBean(methodName = "com.envpermit.support.IntegrationTest#memoryStorage")
    protected DocumentStorage storage;

    static DocumentStorage memoryStorage() {
        return new InMemoryDocumentStorage();
    }

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    /**
     * Empties every app table. Keeps Flyway history and the migration-seeded law catalog
     * (only laws added by search are removed). Call from a test's @BeforeEach.
     */
    protected void truncateAll() {
        String tables = String.join(",", jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename NOT IN ('flyway_schema_history', 'law_catalog')",
                String.class));
        jdbc.execute("TRUNCATE " + tables + " RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM law_catalog WHERE source <> 'SEED'");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(START, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        @Primary
        InMemoryMailer inMemoryMailer() {
            return new InMemoryMailer();
        }

        @Bean
        @Primary
        LawApiClient fixtureLawApi() {
            return new FixtureLawApi();
        }
    }
}
