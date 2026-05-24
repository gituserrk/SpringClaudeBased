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
 *   Retry → CircuitBreaker → RateLimiter → Bulkhead → HTTP call
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

    @Retry(name = "department-service", fallbackMethod = "retryFallback")
    @CircuitBreaker(name = "department-service")
    @RateLimiter(name = "department-service", fallbackMethod = "rateLimitFallback")
    @Bulkhead(name = "department-service", fallbackMethod = "bulkheadFallback")
    public DepartmentDto fetchDepartment(Long departmentId) {
        String url = departmentServiceUrl + "/departments/" + departmentId;
        log.info("Fetching department id={}", departmentId);
        return restTemplate.getForObject(url, DepartmentDto.class);
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    // Called when all retries are exhausted (network error, 5xx, CB open)
    public DepartmentDto retryFallback(Long departmentId, Exception ex) {
        log.warn("Department service unavailable for id={}: {}", departmentId, ex.getMessage());
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
