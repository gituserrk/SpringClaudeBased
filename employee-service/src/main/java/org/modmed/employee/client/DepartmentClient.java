package org.modmed.employee.client;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.modmed.employee.dto.DepartmentDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;

/**
 * Connector / Client layer for Department Service.
 *
 * All outbound HTTP calls to department-service are funnelled through this class.
 * Resilience4j annotations are applied ONLY here, keeping the Service layer clean.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  AOP execution order (Resilience4j default aspect priorities)   │
 * │                                                                 │
 * │  Retry (outermost)                                              │
 * │    └─ CircuitBreaker                                            │
 * │         └─ RateLimiter                                          │
 * │              └─ Bulkhead (semaphore)                            │
 * │                   └─ TimeLimiter (async only)                   │
 * │                        └─ RestTemplate HTTP call                │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Key design decisions:
 * • @Retry carries the fallbackMethod — it is the LAST RESORT after all
 *   retries are exhausted, giving the retry loop a chance to self-heal first.
 * • @CircuitBreaker has NO fallbackMethod so that transient failures bubble
 *   up to @Retry for retry attempts before giving up.
 * • @RateLimiter and @Bulkhead own their fallbacks because their exceptions
 *   (RequestNotPermitted, BulkheadFullException) are in @Retry's
 *   ignoreExceptions — retrying a saturated or rate-limited resource is
 *   wasteful and makes the problem worse.
 * • @TimeLimiter is shown on a separate async method; synchronous timeout
 *   is handled at the RestTemplate socket level (see RestTemplateConfig).
 */
@Slf4j
@Component
public class DepartmentClient {

    private static final String SERVICE_NAME = "department-service";

    private final RestTemplate restTemplate;
    private final String departmentServiceUrl;

    public DepartmentClient(RestTemplate restTemplate,
                            @Value("${department.service.url}") String departmentServiceUrl) {
        this.restTemplate = restTemplate;
        this.departmentServiceUrl = departmentServiceUrl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary method — synchronous fetch with full resilience stack
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Circuit Breaker states explained:
     *
     *  CLOSED    — normal operation; calls pass through; failure rate tracked.
     *  OPEN      — circuit tripped; ALL calls fast-fail immediately with
     *               CallNotPermittedException; wait-duration-in-open-state
     *               elapses before transitioning to HALF_OPEN.
     *  HALF_OPEN — probe state; only permitted-number-of-calls-in-half-open-state
     *               calls are allowed through. If they succeed → CLOSED.
     *               If they fail → back to OPEN.
     *
     * Retry behavior:
     *  • max-attempts=3: 1 original call + up to 2 retries.
     *  • Exponential back-off: 500ms → 1s → 2s (capped at 5s).
     *  • Retries ONLY on transient network/IO errors.
     *  • CallNotPermittedException / BulkheadFullException / RequestNotPermitted
     *    are in ignoreExceptions — Retry calls fallback immediately for those.
     *
     * Bulkhead (semaphore) isolation:
     *  • Limits concurrent in-flight calls to max-concurrent-calls=10.
     *  • Prevents the department-service slowdown from exhausting all threads
     *    in the employee-service — other endpoints remain responsive.
     *
     * Rate Limiter:
     *  • Allows limit-for-period=20 calls per second.
     *  • Excess calls get RequestNotPermitted immediately (timeout-duration=0).
     *  • Protects department-service from being overwhelmed by a burst.
     */
    @Retry(name = SERVICE_NAME, fallbackMethod = "fetchDepartmentFallback")
    @CircuitBreaker(name = SERVICE_NAME)
    @RateLimiter(name = SERVICE_NAME, fallbackMethod = "fetchDepartmentRateLimitedFallback")
    @Bulkhead(name = SERVICE_NAME, type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "fetchDepartmentBulkheadFallback")
    public DepartmentDto fetchDepartment(Long departmentId) {
        String url = departmentServiceUrl + "/departments/" + departmentId;
        log.info("Calling department-service GET {}", url);
        DepartmentDto result = restTemplate.getForObject(url, DepartmentDto.class);
        log.info("Received department: {}", result);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Async method — demonstrates @TimeLimiter
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * TimeLimiter:
     *  • Cancels the CompletableFuture if execution exceeds timeout-duration=2s.
     *  • Works in tandem with CircuitBreaker: a timeout counts as a failure for
     *    the circuit breaker's sliding window.
     *  • Use this variant when the caller can handle CompletableFuture.
     */
    @TimeLimiter(name = SERVICE_NAME, fallbackMethod = "fetchDepartmentAsyncFallback")
    @CircuitBreaker(name = SERVICE_NAME, fallbackMethod = "fetchDepartmentAsyncFallback")
    public CompletableFuture<DepartmentDto> fetchDepartmentAsync(Long departmentId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = departmentServiceUrl + "/departments/" + departmentId;
            log.info("Async calling department-service GET {}", url);
            return restTemplate.getForObject(url, DepartmentDto.class);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback methods for fetchDepartment (synchronous)
    //
    // Resilience4j matches the MOST SPECIFIC exception type first.
    // Signature rules: same return type, same params, + Exception as last param.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by @Retry after all retry attempts are exhausted.
     * Represents: network failure, HTTP 5xx, connection refused, etc.
     */
    public DepartmentDto fetchDepartmentFallback(Long departmentId, Exception ex) {
        log.error("[FALLBACK] Retry exhausted for department id={}. Cause: {} - {}",
                departmentId, ex.getClass().getSimpleName(), ex.getMessage());
        return unavailableDepartment(departmentId);
    }

    /**
     * Called when @CircuitBreaker is OPEN (CallNotPermittedException).
     * The circuit is open because too many failures occurred recently.
     * Note: this fallback is on @Retry (outermost), so it receives the
     * CallNotPermittedException after Retry fast-fails it (ignoreExceptions).
     */
    public DepartmentDto fetchDepartmentFallback(Long departmentId, CallNotPermittedException ex) {
        log.warn("[FALLBACK] Circuit OPEN for department id={}. Service is unhealthy, fast-failing.",
                departmentId);
        return unavailableDepartment(departmentId);
    }

    /**
     * Called by @RateLimiter when call rate exceeds the limit.
     * Returned immediately — no retry, as there are no free permits.
     */
    public DepartmentDto fetchDepartmentRateLimitedFallback(Long departmentId, RequestNotPermitted ex) {
        log.warn("[FALLBACK] Rate limit EXCEEDED for department id={}. Too many concurrent requests.",
                departmentId);
        return unavailableDepartment(departmentId);
    }

    /**
     * Called by @Bulkhead when all concurrent call slots are taken.
     * Returned immediately — no retry, as adding more concurrent calls
     * would worsen the overload.
     */
    public DepartmentDto fetchDepartmentBulkheadFallback(Long departmentId, BulkheadFullException ex) {
        log.warn("[FALLBACK] Bulkhead FULL for department id={}. Max concurrent calls reached.",
                departmentId);
        return unavailableDepartment(departmentId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback methods for fetchDepartmentAsync
    // Must return CompletableFuture<DepartmentDto> to match the method signature.
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<DepartmentDto> fetchDepartmentAsyncFallback(Long departmentId, Exception ex) {
        log.error("[ASYNC FALLBACK] department id={} failed. Cause: {} - {}",
                departmentId, ex.getClass().getSimpleName(), ex.getMessage());
        return CompletableFuture.completedFuture(unavailableDepartment(departmentId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Degraded response — returned during any resilience event
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a safe placeholder so the API caller always gets a complete
     * EmployeeResponse even when the department service is down.
     * The departmentCode field signals the specific failure mode for observability.
     */
    private DepartmentDto unavailableDepartment(Long departmentId) {
        return DepartmentDto.builder()
                .id(departmentId)
                .departmentName("Department information temporarily unavailable")
                .departmentCode("N/A")
                .build();
    }
}
