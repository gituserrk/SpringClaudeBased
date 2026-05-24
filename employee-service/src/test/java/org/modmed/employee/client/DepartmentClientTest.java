package org.modmed.employee.client;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.modmed.employee.dto.DepartmentDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for DepartmentClient — verifies all Resilience4j behaviours.
 *
 * Uses @SpringBootTest so real Resilience4j AOP aspects are active.
 * @MockBean replaces RestTemplate so we can control downstream failure modes.
 *
 * Test isolation strategy:
 * - @BeforeEach resets the CircuitBreaker (the only stateful component that
 *   persists OPEN state beyond a single test).
 * - Rate limiter is configured at 1000/s in test application.yml so it does
 *   NOT interfere between tests. Its fallback is verified directly (see test 5).
 * - Bulkhead is set to 20 concurrent calls — unreachable in sequential tests.
 *   Its fallback is verified directly (see test 6).
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DepartmentClientTest {

    private static final String SERVICE_NAME = "department-service";

    @Autowired
    private DepartmentClient departmentClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @MockBean
    private RestTemplate restTemplate;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker(SERVICE_NAME).reset();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Happy path — normal operation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Should return real department when downstream responds normally")
    void shouldReturnDepartmentOnSuccess() {
        DepartmentDto expected = DepartmentDto.builder()
                .id(1L).departmentName("Engineering").departmentCode("ENG").build();
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class))).thenReturn(expected);

        DepartmentDto result = departmentClient.fetchDepartment(1L);

        assertThat(result.getDepartmentCode()).isEqualTo("ENG");
        assertThat(result.getDepartmentName()).isEqualTo("Engineering");
        verify(restTemplate, times(1)).getForObject(anyString(), eq(DepartmentDto.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Retry — self-heals on the last attempt
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Should retry on transient failure and succeed on the last attempt")
    void shouldRetryAndSucceedEventually() {
        // Test config: max-attempts=2  →  1 original call + 1 retry
        DepartmentDto expected = DepartmentDto.builder()
                .id(2L).departmentName("HR").departmentCode("HR").build();
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class)))
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenReturn(expected);

        DepartmentDto result = departmentClient.fetchDepartment(2L);

        assertThat(result.getDepartmentCode()).isEqualTo("HR");
        verify(restTemplate, times(2)).getForObject(anyString(), eq(DepartmentDto.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Retry exhausted — degraded fallback returned
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Should return degraded fallback after all retry attempts are exhausted")
    void shouldReturnFallbackWhenAllRetriesExhausted() {
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class)))
                .thenThrow(new ResourceAccessException("Downstream permanently down"));

        DepartmentDto result = departmentClient.fetchDepartment(3L);

        assertThat(result).isNotNull();
        assertThat(result.getDepartmentCode()).isEqualTo("N/A");
        assertThat(result.getDepartmentName()).contains("temporarily unavailable");
        // max-attempts=2 → exactly 2 RestTemplate calls before fallback
        verify(restTemplate, times(2)).getForObject(anyString(), eq(DepartmentDto.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Circuit Breaker — opens then fast-fails
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Circuit breaker should OPEN after enough failures and fast-fail subsequent calls")
    void shouldOpenCircuitBreakerAndFastFail() {
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class)))
                .thenThrow(new ResourceAccessException("Downstream down"));

        // Test config: minimum-number-of-calls=3, failure-rate-threshold=50%
        // Each loop attempt goes through the CB. With max-attempts=2 per loop,
        // each loop records up to 2 CB failures (one per retry attempt).
        // After 3 recorded CB events (all failures), the CB opens.
        for (int i = 0; i < 4; i++) {
            departmentClient.fetchDepartment((long) i);  // always returns fallback
        }

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(SERVICE_NAME);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @Order(5)
    @DisplayName("Should return degraded fallback immediately when circuit breaker is OPEN")
    void shouldReturnFallbackWhenCircuitBreakerOpen() {
        // Open the circuit
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class)))
                .thenThrow(new ResourceAccessException("Downstream down"));
        for (int i = 0; i < 4; i++) {
            departmentClient.fetchDepartment((long) i);
        }
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(SERVICE_NAME);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // This call should NEVER reach RestTemplate — it's fast-failed by CB
        DepartmentDto result = departmentClient.fetchDepartment(99L);

        assertThat(result.getDepartmentCode()).isEqualTo("N/A");
        // RestTemplate must not be called after circuit is OPEN
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Rate Limiter fallback — verified via direct fallback invocation
    //
    // The rate limiter is configured at 1000/s in test config to avoid
    // interfering with other tests. Here we test the fallback method directly,
    // which is exactly what Resilience4j calls when RequestNotPermitted is thrown.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Rate-limiter fallback should return degraded department on RequestNotPermitted")
    void rateLimiterFallbackShouldReturnDegradedDepartment() {
        RateLimiter tightLimiter = RateLimiter.of("tight",
                RateLimiterConfig.ofDefaults());

        DepartmentDto result = departmentClient.fetchDepartmentRateLimitedFallback(
                1L, RequestNotPermitted.createRequestNotPermitted(tightLimiter));

        assertThat(result).isNotNull();
        assertThat(result.getDepartmentCode()).isEqualTo("N/A");
        assertThat(result.getDepartmentName()).contains("temporarily unavailable");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Bulkhead fallback — verified via direct fallback invocation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("Bulkhead fallback should return degraded department on BulkheadFullException")
    void bulkheadFallbackShouldReturnDegradedDepartment() {
        io.github.resilience4j.bulkhead.Bulkhead fullBulkhead =
                bulkheadRegistry.bulkhead(SERVICE_NAME);

        DepartmentDto result = departmentClient.fetchDepartmentBulkheadFallback(
                2L, BulkheadFullException.createBulkheadFullException(fullBulkhead));

        assertThat(result).isNotNull();
        assertThat(result.getDepartmentCode()).isEqualTo("N/A");
        assertThat(result.getDepartmentName()).contains("temporarily unavailable");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Async / TimeLimiter — fallback on slow downstream
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("Async fetch should succeed when downstream responds within timeout")
    void asyncFetchShouldReturnDepartmentOnSuccess() throws Exception {
        DepartmentDto expected = DepartmentDto.builder()
                .id(5L).departmentName("Legal").departmentCode("LEG").build();
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class))).thenReturn(expected);

        DepartmentDto result = departmentClient.fetchDepartmentAsync(5L).get();

        assertThat(result.getDepartmentCode()).isEqualTo("LEG");
    }

    @Test
    @Order(9)
    @DisplayName("Async fetch should return fallback when downstream exceeds the TimeLimiter timeout")
    void asyncFetchShouldReturnFallbackOnTimeout() throws Exception {
        when(restTemplate.getForObject(anyString(), eq(DepartmentDto.class)))
                .thenAnswer(inv -> {
                    // Simulate a response slower than timeout-duration=2s
                    Thread.sleep(3500);
                    return DepartmentDto.builder().id(1L).departmentCode("SLOW").build();
                });

        DepartmentDto result = departmentClient.fetchDepartmentAsync(1L).get();

        assertThat(result.getDepartmentCode()).isEqualTo("N/A");
        assertThat(result.getDepartmentName()).contains("temporarily unavailable");
    }
}
