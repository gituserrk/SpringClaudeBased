# Circuit Breaker Testing Guide
## Spring Boot Microservices — Employee + Department Service

---

## How Circuit Breaker Works — State Machine

```
                    ┌─────────────────────────────────────┐
                    │  failure rate > 50%                  │
                    │  (after min 5 calls)                 │
         ┌──────────▼──────────┐                          │
    ───► │       CLOSED        │──────────────────────────►│
         │  (normal, passes    │                           │
         │   all requests)     │              ┌────────────▼────────────┐
         └─────────────────────┘              │         OPEN            │
                    ▲                         │  (short-circuits all    │
                    │                         │   requests immediately) │
          trial     │                         └────────────┬────────────┘
          succeeds  │                                      │
                    │                         wait 30s     │
         ┌──────────┴──────────┐              (wait-duration-in-open-state)
         │     HALF-OPEN       │◄─────────────────────────┘
         │  (allows 1 trial    │
         │   request through)  │──────────────────────────►  trial fails
         └─────────────────────┘                              → back to OPEN
```

---

## Circuit Breaker Configuration (application.yml)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      department-service:
        sliding-window-size: 10          # evaluate last 10 calls
        minimum-number-of-calls: 5       # need at least 5 calls before evaluating
        failure-rate-threshold: 50       # open if >50% of calls fail
        wait-duration-in-open-state: 30s # wait 30s before going HALF-OPEN
        register-health-indicator: true
  retry:
    instances:
      department-service:
        max-attempts: 3                  # retry 3 times before giving up
        wait-duration: 500ms             # wait 500ms between retries
```

---

## Step-by-Step Postman Testing

### STEP 1 — Confirm CB is CLOSED (normal state)

**Request:**
```
GET http://localhost:8082/actuator/circuitbreakers
```

**Expected Response:**
```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "CLOSED",
      "failedCalls": 0,
      "failureRate": "0.0%",
      "bufferedCalls": 0
    }
  }
}
```

---

### STEP 2 — Stop department-service

Run in terminal:
```powershell
docker stop springclaudebased-department-service-1
```

**Wait 8 seconds** for Eureka + LoadBalancer cache to expire.

---

### STEP 3 — Hit employee API 5 times to accumulate failures

**Request (send 5 times):**
```
GET http://localhost:8082/employees/1
```

**What happens internally on each request:**
- Retry attempts 3 HTTP calls → all fail (connection refused)
- Exception propagates up to CircuitBreaker
- CB records 1 failure

After 5 requests → 5 failures, 100% failure rate > 50% threshold → CB opens.

**Expected Response (graceful fallback — same for all 5):**
```json
{
  "employee": {
    "id": 1,
    "firstName": "John",
    "lastName": "Doe",
    "email": "john.doe@modmed.com",
    "departmentId": 1
  },
  "department": {
    "id": 1,
    "departmentName": "Department temporarily unavailable",
    "departmentCode": "N/A"
  }
}
```

---

### STEP 4 — Confirm CB is OPEN

**Request:**
```
GET http://localhost:8082/actuator/circuitbreakers
```

**Expected Response:**
```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "OPEN",
      "failedCalls": 5,
      "failureRate": "100.0%",
      "bufferedCalls": 5
    }
  }
}
```

**Now hit GET /employees/1 again** — response comes back INSTANTLY.
CB short-circuits and returns fallback WITHOUT making any HTTP call or retry.

---

### STEP 5 — Wait 30 seconds → CB goes HALF-OPEN

After `wait-duration-in-open-state: 30s`, CB automatically moves to HALF-OPEN.

**Request:**
```
GET http://localhost:8082/actuator/circuitbreakers
```

**Expected Response:**
```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "HALF_OPEN"
    }
  }
}
```

CB now allows 1 trial request through:
- If department-service is still DOWN  → trial fails → CB goes back to OPEN
- If department-service is back UP     → trial succeeds → CB goes back to CLOSED

---

### STEP 6 — Bring department-service back up

Run in terminal:
```powershell
docker start springclaudebased-department-service-1
```

**Wait 8 seconds**, then send 1 request:
```
GET http://localhost:8082/employees/1
```

CB allows this trial request through. It succeeds → CB transitions to CLOSED.
Real department data is returned again.

---

### STEP 7 — Confirm CB is back to CLOSED

**Request:**
```
GET http://localhost:8082/actuator/circuitbreakers
```

**Expected Response:**
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

---

## Full Postman Request Order

| # | Action | Request |
|---|--------|---------|
| 1 | Check initial state | GET http://localhost:8082/actuator/circuitbreakers |
| 2 | Stop department-service + wait 8s | (terminal command) |
| 3 | Hit employee API (×5) | GET http://localhost:8082/employees/1 |
| 4 | Confirm CB is OPEN | GET http://localhost:8082/actuator/circuitbreakers |
| 5 | Hit employee API again | GET http://localhost:8082/employees/1 (instant fallback) |
| 6 | Wait 30s | (wait for HALF-OPEN) |
| 7 | Check HALF-OPEN state | GET http://localhost:8082/actuator/circuitbreakers |
| 8 | Start department-service + wait 8s | (terminal command) |
| 9 | Send 1 trial request | GET http://localhost:8082/employees/1 |
| 10 | Confirm CB is CLOSED again | GET http://localhost:8082/actuator/circuitbreakers |

---

## State Summary Table

| State     | What happens on a request              | How to get there                        |
|-----------|----------------------------------------|-----------------------------------------|
| CLOSED    | Normal — request goes through          | Starting state / after recovery         |
| OPEN      | Instant fallback — no HTTP call made   | 5+ failed requests with >50% fail rate  |
| HALF-OPEN | 1 trial request allowed through        | Automatically after 30s in OPEN state   |

---

## Why the Fallback Must Be on @CircuitBreaker (Not @Retry)

Resilience4j default aspect order (outermost → innermost):
```
CircuitBreaker → Retry → RateLimiter → Bulkhead → HTTP call
```

**WRONG (old code):** Fallback on @Retry
```
CB → Retry (3 attempts fail) → retryFallback() returns success
                                        ↑
                        CB sees SUCCESS → failedCalls = 0 → never opens ❌
```

**CORRECT (fixed code):** Fallback on @CircuitBreaker
```
CB → Retry (3 attempts fail) → exception propagates to CB
                                        ↑
                        CB records FAILURE → after 5 requests → CB OPENS ✅
```

---

*Generated: 2026-05-24 | Project: SpringClaudeBased*
