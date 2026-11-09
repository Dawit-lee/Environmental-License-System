package com.envpermit;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EnvPermitApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnvPermitApplication.class, args);
    }

    /** The only source of "now". Never call Instant.now()/LocalDate.now() without it; tests replace it. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
