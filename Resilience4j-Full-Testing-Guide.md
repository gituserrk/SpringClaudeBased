# Resilience4j — Full Testing Guide
## Spring Boot Microservices — Employee + Department Service

---

## Active Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      department-service:
        sliding-window-size: 4          # evaluate last 4 calls
        minimum-number-of-calls: 2      # only 2 calls needed before evaluating
        failure-rate-threshold: 50      # open if >50% of calls fail
        wait-duration-in-open-state: 10s
        register-health-indicator: true

  retry:
    instances:
      department-service:
        max-attempts: 3                 # retry 3 times before giving up
        wait-duration: 500ms            # wait 500ms between retries

  ratelimiter:
    instances:
      department-service:
        limit-for-period: 20            # max 20 requests per second
        limit-refresh-period: 1s
        timeout-duration: 0ms

  bulkhead:
    instances:
      department-service:
        max-concurrent-calls: 10        # max 10 concurrent calls
        max-wait-duration: 10ms
```

---

## Resilience4j Aspect Order

Requests flow through layers **outermost → innermost**:

```
CircuitBreaker → Retry → RateLimiter → Bulkhead → HTTP call
```

This means:
- **RateLimiter / Bulkhead** reject before any HTTP call is made
- **Retry** retries the HTTP call up to `max-attempts` times
- **CircuitBreaker** sees the final outcome — success or failure — after all retries are exhausted

---

## Pattern 1 — Circuit Breaker

### What it does
Monitors the failure rate of calls to department-service. If the rate exceeds the threshold, it **opens** the circuit and returns a fallback instantly — no HTTP call, no retries.

### Configuration
```yaml
sliding-window-size: 4      # look at last 4 calls
minimum-number-of-calls: 2  # start evaluating after 2 calls
failure-rate-threshold: 50  # open if >50% fail
wait-duration-in-open-state: 10s
```

### Setup
1. Start all services (service-registry, department-service, employee-service, api-gateway)
2. Confirm department-service is reachable

### Test Steps

**Step 1 — Verify CLOSED state:**
```
GET http://localhost:8082/actuator/circuitbreakers
```
```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "CLOSED",
      "failedCalls": 0,
      "failureRate": "0.0%"
    }
  }
}
```

**Step 2 — Stop department-service. Wait 8 seconds.**

**Step 3 — Send 2 requests to trip the circuit:**
```
GET http://localhost:8082/employees/1   (×2)
```

Expected fallback response:
```json
{
  "employee": { "id": 1, "firstName": "John", ... },
  "department": {
    "departmentName": "Department temporarily unavailable",
    "departmentCode": "N/A"
  }
}
```

**Step 4 — Verify OPEN state:**
```
GET http://localhost:8082/actuator/circuitbreakers
```
```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "OPEN",
      "failedCalls": 2,
      "failureRate": "100.0%"
    }
  }
}
```

**Step 5 — Hit endpoint again → instant fallback (no retry delay):**
```
GET http://localhost:8082/employees/1
```
> Response is instant — CB short-circuits before any HTTP call.

**Step 6 — Wait 10 seconds → HALF-OPEN:**
```
GET http://localhost:8082/actuator/circuitbreakers
```
```json
{ "state": "HALF_OPEN" }
```

**Step 7 — Restart department-service. Wait 8 seconds. Send 1 request → CLOSED:**
```
GET http://localhost:8082/employees/1
```
Real department data returns. CB is CLOSED again.

### State Summary

| State     | What happens                         | How to enter                        |
|-----------|--------------------------------------|-------------------------------------|
| CLOSED    | Normal — request goes through        | Start / after recovery              |
| OPEN      | Instant fallback — no HTTP call      | 2 failed calls with >50% fail rate  |
| HALF-OPEN | 1 trial request allowed through      | Automatically after 10s in OPEN     |

---

## Pattern 2 — Retry

### What it does
Automatically retries a failed HTTP call up to `max-attempts` times before propagating the exception to the circuit breaker.

### Configuration
```yaml
retry:
  instances:
    department-service:
      max-attempts: 3
      wait-duration: 500ms
```

### Test Steps

**Step 1 — Stop department-service.**

**Step 2 — Send one request:**
```
GET http://localhost:8082/employees/1
```

**Step 3 — Watch employee-service logs:**
```
Retry attempt 1 for 'department-service' — connection refused
Retry attempt 2 for 'department-service' — connection refused
Retry attempt 3 for 'department-service' — connection refused
Fallback triggered
```

**What to observe:**
- Total response time is **~1.5 seconds** (3 attempts × 500ms wait)
- Compare with OPEN circuit response which is **instant** — shows the value of CB on top of retry

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/retries
```

### Expected Retry Metrics
```json
{
  "retries": {
    "department-service": {
      "failedCallsWithRetryAttempt": 1,
      "failedCallsWithoutRetryAttempt": 0,
      "successfulCallsWithRetryAttempt": 0,
      "successfulCallsWithoutRetryAttempt": 0
    }
  }
}
```

---

## Pattern 3 — Rate Limiter

### What it does
Caps the number of calls allowed in a time window. Requests exceeding the limit are rejected immediately — they never reach the HTTP layer.

### Configuration
```yaml
ratelimiter:
  instances:
    department-service:
      limit-for-period: 20      # max 20 calls per window
      limit-refresh-period: 1s  # window refreshes every 1 second
      timeout-duration: 0ms     # don't wait — reject immediately
```

### Test Steps

**Step 1 — Ensure department-service is running.**

**Step 2 — Send 21+ requests within 1 second** (use Postman Runner set to 21 iterations with no delay, or a load tool):
```
GET http://localhost:8082/employees/1   (×21 rapid fire)
```

**Expected results:**
- Requests 1–20 → `200 OK` with employee + department data
- Request 21+ → `503 Service Unavailable`
  ```
  RateLimiter 'department-service' does not permit further calls
  ```

**Step 3 — Wait 1 second → limit resets. Next 20 calls succeed again.**

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/ratelimiters
```

### Expected Rate Limiter Metrics
```json
{
  "rateLimiters": {
    "department-service": {
      "availablePermissions": 0,
      "numberOfWaitingThreads": 0
    }
  }
}
```

---

## Pattern 4 — Bulkhead

### What it does
Limits the number of **concurrent** calls. If more than `max-concurrent-calls` are in-flight at the same time, additional calls are rejected after `max-wait-duration`.

### Configuration
```yaml
bulkhead:
  instances:
    department-service:
      max-concurrent-calls: 10   # allow 10 simultaneous calls
      max-wait-duration: 10ms    # reject after 10ms if slot not free
```

### Test Steps

**Step 1 — Ensure department-service is running.**

**Step 2 — Send 11+ simultaneous requests** (use Postman with parallel runners or a load testing tool like Apache JMeter / Gatling):
```
GET http://localhost:8082/employees/1   (×11 simultaneously)
```

**Expected results:**
- First 10 concurrent → processed normally → `200 OK`
- 11th concurrent → waits 10ms → rejected with `503 Service Unavailable`
  ```
  Bulkhead 'department-service' is full and does not permit further calls
  ```

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/health
```

### Note on Bulkhead vs Rate Limiter

| | Rate Limiter | Bulkhead |
|---|---|---|
| Limits | Total calls per time window | Concurrent calls at once |
| Scenario | Burst traffic protection | Thread exhaustion protection |
| Example | Max 20 req/sec | Max 10 in-flight at once |

---

## All Actuator Endpoints

| Pattern         | Endpoint                                |
|-----------------|-----------------------------------------|
| Circuit Breaker | `GET /actuator/circuitbreakers`         |
| Retry           | `GET /actuator/retries`                 |
| Rate Limiter    | `GET /actuator/ratelimiters`            |
| All health      | `GET /actuator/health`                  |

> All endpoints are on **employee-service** (port 8082).

---

## Why Fallback Must Be on @CircuitBreaker (Not @Retry)

Resilience4j wraps in this order: `CircuitBreaker → Retry → HTTP call`

**❌ Wrong — fallback on @Retry:**
```
CB → Retry (3 attempts fail) → retryFallback() ← returns success
                                       ↑
               CB sees SUCCESS → failedCalls = 0 → CB never opens
```

**✅ Correct — fallback on @CircuitBreaker:**
```
CB → Retry (3 attempts fail) → exception propagates up to CB
                                       ↑
               CB records FAILURE → after 2 requests → CB OPENS → fallback returned
```

---

## Full Test Sequence Summary

| # | Pattern | Action | Expected |
|---|---------|--------|----------|
| 1 | Circuit Breaker | Stop dept-service → send 2 calls | CB opens; fallback returned |
| 2 | Circuit Breaker | Hit endpoint while OPEN | Instant fallback (no retry delay) |
| 3 | Circuit Breaker | Wait 10s → restart service → send 1 call | CB closes; real data returned |
| 4 | Retry | Stop dept-service → send 1 call | 3 retry attempts in logs; ~1.5s response time |
| 5 | Rate Limiter | Send 21 rapid calls | First 20 OK; 21st rejected with 503 |
| 6 | Bulkhead | Send 11 concurrent calls | First 10 OK; 11th rejected with 503 |

---

*Generated: 2026-05-24 | Project: SpringClaudeBased*
