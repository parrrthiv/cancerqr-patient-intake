package com.oncology.intake.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Async + uncaught exception handling.
 *
 * Replaces the bare {@code @EnableAsync} on the main application class so that:
 *  - errors thrown out of {@code @Async} methods are logged at ERROR level
 *    (instead of being silently swallowed by the default handler)
 *  - each error increments {@code async.errors{method=...}} so it shows up at
 *    {@code /api/actuator/metrics/async.errors}.
 *
 * Without this, an exception in (say) {@code ConversationService.processTextMessage}
 * dispatched via @Async vanishes — no log, no alert, no counter.
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    private final MeterRegistry meterRegistry;

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            String label = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
            log.error("Uncaught async exception in {}", label, ex);
            meterRegistry.counter("async.errors", "method", label).increment();
        };
    }
}
