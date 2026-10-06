package com.datn.notification.config;
import com.datn.notification.delivery.DeliveryWorker;
import com.datn.notification.scheduling.SchedulingJobs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import java.time.Clock;

@Configuration
public class SchedulingConfig {
    /** All scheduling decisions read this clock (UTC instants); tests substitute a controllable one. */
    @Bean public Clock clock(){return Clock.systemUTC();}

    /** Pollers for durable jobs and deliveries; disabled in tests, which drive runDue() directly. */
    @Configuration @EnableScheduling @ConditionalOnProperty(name="notification.workers.enabled",havingValue="true",matchIfMissing=true)
    static class Workers {
        private final SchedulingJobs jobs;private final DeliveryWorker deliveries;
        Workers(SchedulingJobs jobs,DeliveryWorker deliveries){this.jobs=jobs;this.deliveries=deliveries;}
        @Scheduled(fixedDelayString="${notification.jobs.poll-ms:5000}") void jobs(){while(jobs.runDue()>0){}}
        @Scheduled(fixedDelayString="${notification.deliveries.poll-ms:5000}") void deliveries(){while(deliveries.runDue()>0){}}
    }
}
