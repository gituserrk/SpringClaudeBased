package org.modmed.employee.client;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.modmed.employee.dto.DepartmentDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for Department Service.
 *
 * Resilience4j AOP order (outermost → innermost):
 *   CircuitBreaker → Retry → RateLimiter → Bulkhead → HTTP call
 *
 * The fallback must be on @CircuitBreaker (not @Retry) so that failures from
 * exhausted retries propagate up to the CB and are recorded as failures.
 * If @Retry had the fallback, it would return a successful response to the CB,
 * which would record it as a success — and the CB would never open.
 */
@Slf4j
@Component
public class DepartmentClient {

    private final RestTemplate restTemplate;
    private final String departmentServiceUrl;

    public DepartmentClient(RestTemplate restTemplate,
                            @Value("${department.service.url}") String departmentServiceUrl) {
        this.restTemplate = restTemplate;
        this.departmentServiceUrl = departmentServiceUrl;
    }

    // ── Main method — full resilience stack ──────────────────────────────────

    @CircuitBreaker(name = "department-service", fallbackMethod = "circuitBreakerFallback")
    @Retry(name = "department-service")
    @RateLimiter(name = "department-service", fallbackMethod = "rateLimitFallback")
    @Bulkhead(name = "department-service", fallbackMethod = "bulkheadFallback")
    public DepartmentDto fetchDepartment(Long departmentId) {
        String url = departmentServiceUrl + "/departments/" + departmentId;
        log.info("Fetching department id={}", departmentId);
        return restTemplate.getForObject(url, DepartmentDto.class);
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
