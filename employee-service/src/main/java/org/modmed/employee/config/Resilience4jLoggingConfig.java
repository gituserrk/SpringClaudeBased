package org.modmed.employee.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class Resilience4jLoggingConfig {

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public Resilience4jLoggingConfig(RetryRegistry retryRegistry,
                                     CircuitBreakerRegistry circuitBreakerRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @PostConstruct
    public void configureEvents() {

        // ── Log actual retry config so we can verify it matches application.yml ──
        var retry = retryRegistry.retry("department-service");
        log.info("Retry 'department-service' config — maxAttempts: {}",
                retry.getRetryConfig().getMaxAttempts());

        // ── Retry events ─────────────────────────────────────────────────────────
        retry.getEventPublisher()
                .onRetry(e -> log.warn("Retry attempt {} for '{}'",
                        e.getNumberOfRetryAttempts(), e.getName()))
                .onSuccess(e -> log.info("Retry succeeded after {} attempt(s) for '{}'",
                        e.getNumberOfRetryAttempts(), e.getName()))
                .onError(e -> log.error("All retry attempts exhausted for '{}': {}",
                        e.getName(),
                        e.getLastThrowable() != null ? e.getLastThrowable().getMessage() : "unknown error"));

        // ── Circuit breaker state transitions ─────────────────────────────────────
        circuitBreakerRegistry.circuitBreaker("department-service").getEventPublisher()
                .onStateTransition(e -> log.warn("Circuit breaker '{}': {} → {}",
                        e.getCircuitBreakerName(),
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()))
                .onCallNotPermitted(e -> log.warn("Circuit breaker '{}' is OPEN — call rejected",
                        e.getCircuitBreakerName()));
    }
}
