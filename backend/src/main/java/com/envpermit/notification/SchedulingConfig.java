package com.envpermit.notification;

import java.time.Instant;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled jobs; those that must not run twice take a core.JobLock (M7). Off in tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    /**
     * Catch up after a restart; the job never repeats a reminder or a mail, so this is safe.
     * M8: on the scheduler thread, so a slow SMTP server does not hold up startup (and the health check).
     */
    @Bean
    ApplicationRunner notificationCatchUp(NotificationJob job, TaskScheduler scheduler) {
        return args -> scheduler.schedule(job::daily, Instant.now());
    }
}
