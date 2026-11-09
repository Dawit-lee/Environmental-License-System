package com.envpermit.notification;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Single server, so plain @Scheduled (see ARCHITECTURE); add ShedLock when scaling out. Off in tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    /** Catch up after a restart; the job never repeats a reminder or a mail, so this is safe. */
    @Bean
    ApplicationRunner notificationCatchUp(NotificationJob job) {
        return args -> job.daily();
    }
}
