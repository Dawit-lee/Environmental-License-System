package com.envpermit.support;

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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for API tests: real PostgreSQL 16 (Testcontainers, shared across tests) + fixed clock.
 * Each test starts at {@link #START}; move time with {@code clock.advance(...)}.
 */
@SpringBootTest(properties = "app.jwt.secret=test-secret-test-secret-test-secret-0123456789")
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

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(START, ZoneId.of("Asia/Seoul"));
        }
    }
}
