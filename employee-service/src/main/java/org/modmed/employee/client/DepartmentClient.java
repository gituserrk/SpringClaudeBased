package org.modmed.employee.client;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.modmed.employee.dto.DepartmentDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for Department Service.
 *
 * Resilience4j AOP order (outermost → innermost):
 *   CircuitBreaker → RateLimiter → Bulkhead → HTTP call (with programmatic Retry inside)
 *
 * Retry is applied programmatically inside fetchDepartment rather than via @Retry
 * annotation. In Resilience4j 2.x Spring Boot, @Retry without a fallbackMethod uses
 * retry.executeCheckedSupplier() in a code path that does not trigger retries when
 * stacked with other annotated aspects. Programmatic retry is reliable and keeps the
 * retry event listeners working correctly.
 */
@Slf4j
@Component
public class DepartmentClient {

    private final RestTemplate restTemplate;
    private final RetryRegistry retryRegistry;
    private final String departmentServiceUrl;

    public DepartmentClient(RestTemplate restTemplate,
                            RetryRegistry retryRegistry,
                            @Value("${department.service.url}") String departmentServiceUrl) {
        this.restTemplate = restTemplate;
        this.retryRegistry = retryRegistry;
        this.departmentServiceUrl = departmentServiceUrl;
    }

    // ── Main method — full resilience stack ──────────────────────────────────

    @CircuitBreaker(name = "department-service", fallbackMethod = "circuitBreakerFallback")
    @RateLimiter(name = "department-service", fallbackMethod = "rateLimitFallback")
    @Bulkhead(name = "department-service", fallbackMethod = "bulkheadFallback")
    public DepartmentDto fetchDepartment(Long departmentId) {
        String url = departmentServiceUrl + "/departments/" + departmentId;
        Retry retry = retryRegistry.retry("department-service");
        try {
            return retry.executeCheckedSupplier(() -> {
                log.info("Fetching department id={}", departmentId);
                return restTemplate.getForObject(url, DepartmentDto.class);
            });
        } catch (Throwable t) {
            // Re-throw as unchecked so @CircuitBreaker records the failure
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    // Called when CB is OPEN or all retries are exhausted (exception reaches CB)
    public DepartmentDto circuitBreakerFallback(Long departmentId, Exception ex) {
        log.warn("Circuit breaker fallback for department id={}: {}", departmentId, ex.getMessage());
        return unavailableDepartment(departmentId);
    }

    // Called when too many requests per second
    public DepartmentDto rateLimitFallback(Long departmentId, RequestNotPermitted ex) {
        log.warn("Rate limit exceeded for department id={}: {}", departmentId, ex.getMessage());
        return unavailableDepartment(departmentId);
    }

    // Called when too many concurrent calls
    public DepartmentDto bulkheadFallback(Long departmentId, BulkheadFullException ex) {
        log.warn("Bulkhead full for department id={}: {}", departmentId, ex.getMessage());
        return unavailableDepartment(departmentId);
    }

    // ── Degraded response ─────────────────────────────────────────────────────

    private DepartmentDto unavailableDepartment(Long departmentId) {
        return DepartmentDto.builder()
                .id(departmentId)
                .departmentName("Department temporarily unavailable")
                .departmentCode("N/A")
                .build();
    }
}
