package in.techseva.cb.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * cb-api had no @Async support before RemediationRunService -- without
 * @EnableAsync, its @Async annotation would silently no-op and run
 * synchronously on the request thread, blocking POST /api/v1/remediation-runs
 * for the job's full duration (default 1200s timeout) instead of returning
 * immediately with a runId.
 */
@Configuration
@EnableAsync
public class RemediationAsyncConfig {

    @Bean("remediationExecutor")
    public Executor remediationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("cb-remediation-");
        executor.initialize();
        return executor;
    }
}
