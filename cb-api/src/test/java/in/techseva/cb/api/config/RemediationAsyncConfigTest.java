package in.techseva.cb.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves @EnableAsync + the "remediationExecutor" bean actually dispatch
 * work off-thread through a real Spring proxy -- not a self-invocation
 * that silently no-ops the annotation. Calling an @Async method directly
 * via `new` (as most unit tests in this codebase do for speed) can never
 * catch this class of bug, since @Async only takes effect through Spring's
 * AOP proxy; RemediationRunController itself was deliberately restructured
 * to avoid a same-class @Async self-invocation (see
 * RemediationRunService.startRun's Javadoc) specifically because of this.
 */
class RemediationAsyncConfigTest {

    @Component
    static class AsyncProbe {
        @Async("remediationExecutor")
        public void runAsync(AtomicReference<String> threadNameOut, CountDownLatch done) {
            threadNameOut.set(Thread.currentThread().getName());
            done.countDown();
        }
    }

    @Configuration
    static class TestConfig {
        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    @Test
    void asyncMethod_runsOnRemediationExecutorThread_notTheCallingThread() throws InterruptedException {
        try (var ctx = new AnnotationConfigApplicationContext(RemediationAsyncConfig.class, TestConfig.class)) {
            AsyncProbe probe = ctx.getBean(AsyncProbe.class);
            String callingThread = Thread.currentThread().getName();

            var threadNameOut = new AtomicReference<String>();
            var done = new CountDownLatch(1);
            probe.runAsync(threadNameOut, done);

            boolean completed = done.await(5, TimeUnit.SECONDS);

            assertThat(completed).as("async method should have run and counted down").isTrue();
            assertThat(threadNameOut.get())
                    .as("must run on the named executor's thread, not the caller's")
                    .isNotEqualTo(callingThread)
                    .startsWith("cb-remediation-");
        }
    }
}
