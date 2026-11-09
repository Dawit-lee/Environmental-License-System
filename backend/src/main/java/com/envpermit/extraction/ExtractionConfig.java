package com.envpermit.extraction;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ExtractionConfig {

    static final String EXECUTOR = "extractionExecutor";

    /** Extraction runs off the request thread. Two at a time keeps API spend and memory (PDF bytes) bounded. */
    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    ExecutorService extractionExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
