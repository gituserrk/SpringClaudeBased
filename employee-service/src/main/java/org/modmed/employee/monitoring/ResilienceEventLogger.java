package org.modmed.employee.monitoring;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Observability layer — logs all Resilience4j state transitions and events.
 *
 * <h3>Why a dedicated event logger?</h3>
 * <p>Resilience4j publishes granular events through internal event publishers.
 * Attaching listeners here keeps all observability concerns in one place
 * (SRP) without cluttering {@code DepartmentClient}.</p>
 *
 * <h3>What gets logged</h3>
 * <ul>
 *   <li>Circuit Breaker state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)</li>
 *   <li>Individual call successes, failures, and ignored exceptions</li>
 *   <li>Retry attempts and exhaustions</li>
 *   <li>Rate limiter permits acquired and denied</li>
 *   <li>Bulkhead call permissions granted and rejected</li>
 * </ul>
 *
 * <h3>Production tip</h3>
 * <p>In production, emit these as structured log events (JSON) and ship to your
 * APM (Datadog, Splunk, ELK). The state-change events are especially valuable
 * as alert triggers: CLOSED → OPEN means a downstream service just degraded.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class
ResilienceEventLogger {

    private static final String SERVICE = "department-service";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    @PostConstruct
    public void registerEventListeners() {
        registerCircuitBreakerListeners();
        registerRetryListeners();
        registerRateLimiterListeners();
        registerBulkheadListeners();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Circuit Breaker events
    // ─────────────────────────────────────────────────────────────────────────

    private void registerCircuitBreakerListeners() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(SERVICE);

        // ── State transitions ─────────────────────────────────────────────────
        cb.getEventPublisher()
                .onStateTransition(event -> {
                    CircuitBreaker.State from = event.getStateTransition().getFromState();
                    CircuitBreaker.State to   = event.getStateTransition().getToState();
                    log.warn("[CB:{}] State transition: {} → {} | " +
                                    "failure-rate={}% slow-call-rate={}%",
                            SERVICE, from, to,
                            String.format("%.1f", cb.getMetrics().getFailureRate()),
                            String.format("%.1f", cb.getMetrics().getSlowCallRate()));
                })
                // ── Call outcomes ─────────────────────────────────────────────
                .onSuccess(event ->
                        log.debug("[CB:{}] SUCCESS duration={}ms",
                                SERVICE, event.getElapsedDuration().toMillis()))
                .onError(event ->
                        log.warn("[CB:{}] FAILURE duration={}ms cause={}: {}",
                                SERVICE, event.getElapsedDuration().toMillis(),
                                event.getThrowable().getClass().getSimpleName(),
                                event.getThrowable().getMessage()))
                .onCallNotPermitted(event ->
                        log.warn("[CB:{}] CALL_NOT_PERMITTED — circuit is OPEN", SERVICE))
                .onIgnoredError(event ->
                        log.debug("[CB:{}] IGNORED exception: {}",
                                SERVICE, event.getThrowable().getClass().getSimpleName()))
                .onSlowCallRateExceeded(event ->
                        log.warn("[CB:{}] SLOW_CALL_RATE exceeded: {}%",
                                SERVICE, String.format("%.1f", event.getSlowCallRate())))
                .onFailureRateExceeded(event ->
                        log.warn("[CB:{}] FAILURE_RATE exceeded: {}%",
                                SERVICE, String.format("%.1f", event.getFailureRate())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Retry events
    // ─────────────────────────────────────────────────────────────────────────

    private void registerRetryListeners() {
        retryRegistry.retry(SERVICE).getEventPublisher()
                .onRetry(event ->
                        log.warn("[RETRY:{}] Attempt #{} after failure: {}",
                                SERVICE, event.getNumberOfRetryAttempts(),
                                event.getLastThrowable() != null
                                        ? event.getLastThrowable().getMessage() : "n/a"))
                .onSuccess(event ->
                        log.info("[RETRY:{}] Succeeded after {} attempt(s)",
                                SERVICE, event.getNumberOfRetryAttempts()))
                .onError(event ->
                        log.error("[RETRY:{}] All {} attempts exhausted. Last error: {}",
                                SERVICE, event.getNumberOfRetryAttempts(),
                                event.getLastThrowable() != null
                                        ? event.getLastThrowable().getMessage() : "n/a"))
                .onIgnoredError(event ->
                        log.debug("[RETRY:{}] Ignored exception — NOT retrying: {}",
                                SERVICE, event.getLastThrowable().getClass().getSimpleName()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Rate Limiter events
    // ─────────────────────────────────────────────────────────────────────────

    private void registerRateLimiterListeners() {
        rateLimiterRegistry.rateLimiter(SERVICE).getEventPublisher()
                .onSuccess(event ->
                        log.debug("[RL:{}] Permit ACQUIRED", SERVICE))
                .onFailure(event ->
                        log.warn("[RL:{}] Permit DENIED — rate limit exceeded. " +
                                        "Available permits: {}",
                                SERVICE,
                                rateLimiterRegistry.rateLimiter(SERVICE)
                                        .getMetrics().getAvailablePermissions()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Bulkhead events
    // ─────────────────────────────────────────────────────────────────────────

    private void registerBulkheadListeners() {
        bulkheadRegistry.bulkhead(SERVICE).getEventPublisher()
                .onCallPermitted(event ->
                        log.debug("[BH:{}] Call PERMITTED. " +
                                        "Available slots: {}",
                                SERVICE,
                                bulkheadRegistry.bulkhead(SERVICE)
                                        .getMetrics().getAvailableConcurrentCalls()))
                .onCallRejected(event ->
                        log.warn("[BH:{}] Call REJECTED — bulkhead full. " +
                                        "Max concurrent={}, available={}",
                                SERVICE,
                                bulkheadRegistry.bulkhead(SERVICE)
                                        .getBulkheadConfig().getMaxConcurrentCalls(),
                                bulkheadRegistry.bulkhead(SERVICE)
                                        .getMetrics().getAvailableConcurrentCalls()))
                .onCallFinished(event ->
                        log.debug("[BH:{}] Call FINISHED", SERVICE));
    }
}
