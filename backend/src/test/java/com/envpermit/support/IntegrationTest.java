package com.envpermit.support;

import com.envpermit.core.document.DocumentStorage;
import com.envpermit.law.LawApiClient;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.ExecutorServiceAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for API tests: real PostgreSQL 16 (Testcontainers, shared across tests) + fixed clock
 * + in-memory document storage (replaces S3, so tests need no MinIO) + LLM fixtures, extraction run inline.
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

    // extraction runs inline, so a test sees the finished run right after the 202
    @TestBean(name = "extractionExecutor", methodName = "com.envpermit.support.IntegrationTest#inlineExecutor")
    protected ExecutorService extractionExecutor;
    @Autowired protected FixtureLlmClient llm;

    static DocumentStorage memoryStorage() {
        return new InMemoryDocumentStorage();
    }

    static ExecutorService inlineExecutor() {
        return new ExecutorServiceAdapter(new SyncTaskExecutor());
    }

    @BeforeEach
    void resetClock() {
        clock.set(START);
        llm.reset();
    }

    /**
     * Empties every app table. Keeps Flyway history, the migration-seeded law catalog
     * (only laws added by search are removed) and the seeded derivation rule set. Call from a test's @BeforeEach.
     */
    protected void truncateAll() {
        String tables = String.join(",", jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename NOT IN ('flyway_schema_history', 'law_catalog', 'derivation_rule', 'ruleset_state')",
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
        FixtureLlmClient fixtureLlm() {
            return new FixtureLlmClient();
        }

        @Bean
        @Primary
        LawApiClient fixtureLawApi() {
            return new FixtureLawApi();
        }
    }
}
