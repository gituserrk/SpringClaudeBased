package org.modmed.employee.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Resilience4j Policy Reference — Documentation class.
 *
 * <p><b>This class does NOT register Spring beans.</b>
 * Spring Boot's Resilience4j auto-configuration reads all settings from
 * {@code application.yml} (resilience4j.* keys). This class documents
 * every policy decision with the Java API equivalents so developers can
 * understand <em>why</em> each value was chosen.</p>
 *
 * <h2>Why YAML over programmatic beans?</h2>
 * <ul>
 *   <li>YAML is externalised — production, staging, and local dev can have
 *       different thresholds without recompiling.</li>
 *   <li>Spring Boot's {@code @ConditionalOnMissingBean} auto-configuration
 *       back-fills all registry beans automatically — no boilerplate needed.</li>
 *   <li>Named instances ({@code instances.department-service.*}) override the
 *       default config, giving fine-grained per-service control.</li>
 * </ul>
 *
 * <h2>AOP execution order (Resilience4j default priorities)</h2>
 * <pre>
 *   @Retry (outermost, priority=Ordered.LOWEST_PRECEDENCE - 3)
 *     └─ @CircuitBreaker   (priority=Ordered.LOWEST_PRECEDENCE - 2)
 *          └─ @RateLimiter (priority=Ordered.LOWEST_PRECEDENCE - 1)
 *               └─ @Bulkhead (Semaphore, priority=Ordered.LOWEST_PRECEDENCE)
 *                    └─ @TimeLimiter (async only)
 *                         └─ RestTemplate HTTP call
 * </pre>
 *
 * <h2>SOLID principles applied</h2>
 * <ul>
 *   <li><b>SRP</b>  — each resilience pattern handles one concern.</li>
 *   <li><b>OCP</b>  — new service configs extend YAML without modifying this.</li>
 *   <li><b>DIP</b>  — {@code DepartmentClient} depends on R4j annotations,
 *       not concrete config classes.</li>
 *   <li><b>ISP</b>  — each annotation targets a single method boundary.</li>
 * </ul>
 */
@SuppressWarnings("unused") // Reference class — not instantiated by Spring
public final class Resilience4jConfig {

    private Resilience4jConfig() { /* utility class */ }

    // ─────────────────────────────────────────────────────────────────────────
    //  CIRCUIT BREAKER  (application.yml: resilience4j.circuitbreaker.*)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reference: equivalent Java API for the YAML circuit-breaker config.
     *
     * <pre>
     *                       ┌──────────────────────────────────┐
     *  All calls pass ─────▶│           CLOSED                 │
     *  through normally     │  sliding window: last 10 calls   │
     *                       │  failure rate ≥ 50 % → OPEN       │
     *                       └──────────────┬───────────────────┘
     *                                      │ ≥50% failures
     *                                      ▼
     *                       ┌──────────────────────────────────┐
     *  All calls fast- ────▶│             OPEN                 │
     *  fail immediately     │  wait 30 s → auto → HALF_OPEN   │
     *  (CallNotPermitted)   └──────────────┬───────────────────┘
     *                                      │ after 30s
     *                                      ▼
     *                       ┌──────────────────────────────────┐
     *  3 probe calls ──────▶│          HALF_OPEN               │
     *  allowed through      │  all succeed → CLOSED            │
     *                       │  any failure → OPEN again        │
     *                       └──────────────────────────────────┘
     * </pre>
     *
     * <b>Slow-call handling</b>: calls taking {@code >3s} count as failures
     * and contribute to {@code slow-call-rate-threshold=80%}.
     * This detects a "slow but not erroring" downstream before it causes
     * thread exhaustion.
     */
    static CircuitBreakerConfig circuitBreakerReference() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ResourceAccessException.class, IOException.class, TimeoutException.class)
                .ignoreExceptions(org.modmed.employee.exception.EmployeeNotFoundException.class)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RETRY  (application.yml: resilience4j.retry.*)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reference: equivalent Java API for the YAML retry config.
     *
     * <h3>When to retry vs when NOT to retry</h3>
     * <table>
     *   <tr><th>Retry ✅</th><th>Do NOT retry ❌</th></tr>
     *   <tr><td>Connection refused (service restarting)</td><td>CB OPEN</td></tr>
     *   <tr><td>Network timeout / packet loss</td><td>Bulkhead full</td></tr>
     *   <tr><td>HTTP 503 (temporary overload)</td><td>Rate limit exceeded</td></tr>
     *   <tr><td>DNS resolution blip</td><td>HTTP 4xx (client error)</td></tr>
     * </table>
     *
     * <h3>Avoiding retry storms</h3>
     * <ul>
     *   <li>Exponential back-off: 500 ms → 1 s → 2 s spreads load over time.</li>
     *   <li>Keep {@code max-attempts} ≤ 3 — circuit breaker is the backstop,
     *       not a high retry count.</li>
     *   <li>Adding jitter (random ± offset) in high-concurrency environments
     *       prevents all clients retrying at the same instant.</li>
     * </ul>
     */
    static RetryConfig retryReference() {
        return RetryConfig.custom()
                .maxAttempts(3)   // 1 original + 2 retries
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2.0))
                .retryExceptions(ResourceAccessException.class, IOException.class,
                        ConnectException.class, TimeoutException.class)
                .ignoreExceptions(
                        io.github.resilience4j.circuitbreaker.CallNotPermittedException.class,
                        io.github.resilience4j.bulkhead.BulkheadFullException.class,
                        io.github.resilience4j.ratelimiter.RequestNotPermitted.class)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RATE LIMITER  (application.yml: resilience4j.ratelimiter.*)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reference: equivalent Java API for the YAML rate-limiter config.
     *
     * <h3>Token-bucket behaviour</h3>
     * <pre>
     *   Every 1 second:  refill 20 tokens (limit-for-period)
     *   Each call:       consume 1 token
     *   No tokens left:  return RequestNotPermitted immediately (timeout=0ms)
     * </pre>
     *
     * <h3>Choosing the right limit</h3>
     * <ul>
     *   <li>Measure the downstream's observed max throughput (via metrics).</li>
     *   <li>Set the limit to ~70–80% of that to leave headroom.</li>
     *   <li>Use {@code timeout=0ms} to fail-fast rather than queue goroutines.</li>
     * </ul>
     */
    static RateLimiterConfig rateLimiterReference() {
        return RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BULKHEAD (Semaphore)  (application.yml: resilience4j.bulkhead.*)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reference: equivalent Java API for the YAML bulkhead config.
     *
     * <h3>What bulkhead isolation prevents</h3>
     * <pre>
     *   Without bulkhead:
     *     All 200 Tomcat threads → DeptService (slow)
     *     → ALL other endpoints starve → entire employee-service goes down
     *
     *   With bulkhead (max=10):
     *     10 threads → DeptService (slow)
     *     11th concurrent call → BulkheadFullException → fallback immediately
     *     → Other 190 threads serve remaining endpoints normally
     * </pre>
     *
     * <h3>Bulkhead sizing (Little's Law)</h3>
     * <pre>
     *   L = λ × W
     *   where:  L = max concurrent calls  (what we're setting)
     *           λ = expected requests/sec
     *           W = avg downstream latency (seconds)
     *
     *   Example: 5 RPS × 2s avg latency = 10 concurrent slots needed
     * </pre>
     */
    static BulkheadConfig bulkheadReference() {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ofMillis(10))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TIME LIMITER  (application.yml: resilience4j.timelimiter.*)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reference: equivalent Java API for the YAML time-limiter config.
     *
     * <h3>Three layers of timeout — set in this order</h3>
     * <pre>
     *   connect-timeout (RestTemplateConfig)    ← TCP connection timeout
     *       connect ≤ read ≤ TimeLimiter ≤ Gateway timeout
     *   read-timeout (RestTemplateConfig)       ← waiting for first response byte
     *   timeout-duration (@TimeLimiter)         ← total async task wall-clock time
     * </pre>
     *
     * <h3>Choosing timeout values</h3>
     * <ul>
     *   <li>Start with p99 latency of the downstream + 20% buffer.</li>
     *   <li>Never exceed your SLA minus processing overhead.</li>
     *   <li>A timeout in the circuit breaker's
     *       {@code record-exceptions} counts as a failure,
     *       so timeouts drive the CB state machine automatically.</li>
     * </ul>
     */
    static TimeLimiterConfig timeLimiterReference() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2))
                .cancelRunningFuture(true)
                .build();
    }
}
