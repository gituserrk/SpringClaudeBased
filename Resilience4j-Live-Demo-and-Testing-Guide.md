# Resilience4j — Live Demo & Full Testing Guide
## Spring Boot Microservices — Employee + Department Service

---

## Active Configuration (Live-Demo Tuned)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      department-service:
        sliding-window-size: 4          # evaluate last 4 calls
        minimum-number-of-calls: 2      # only 2 calls needed before evaluating
        failure-rate-threshold: 50      # open if >50% of calls fail
        wait-duration-in-open-state: 10s # wait 10s before going HALF-OPEN
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

> ⚡ **Why these values for live demo?**
> - Circuit opens after just **2 failed calls** (not 5–10)
> - Recovery happens in **10 seconds** (not 30s)
> - Full open → half-open → closed cycle visible in **under a minute**

---

## Circuit Breaker — State Machine

```
                    ┌─────────────────────────────────────┐
                    │  failure rate > 50%                  │
                    │  (after min 2 calls)                 │
         ┌──────────▼──────────┐                          │
    ───► │       CLOSED        │──────────────────────────►│
         │  (normal, passes    │                           │
         │   all requests)     │              ┌────────────▼────────────┐
         └─────────────────────┘              │         OPEN            │
                    ▲                         │  (short-circuits all    │
                    │                         │   requests immediately) │
          trial     │                         └────────────┬────────────┘
          succeeds  │                                      │
                    │                         wait 10s     │
         ┌──────────┴──────────┐              (wait-duration-in-open-state)
         │     HALF-OPEN       │◄─────────────────────────┘
         │  (allows 1 trial    │
         │   request through)  │──────────────────────────►  trial fails
         └─────────────────────┘                              → back to OPEN
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

## PART 1 — Live Demo Script

> 🎤 **Presenter tip:** Keep this open alongside Postman. Walk through each step out loud.

---

### Prerequisites — Start All Services

Start services in this order:

```powershell
# Terminal 1 — Service Registry
cd service-registry && mvn spring-boot:run

# Terminal 2 — Department Service
cd department-service && mvn spring-boot:run

# Terminal 3 — Employee Service
cd employee-service && mvn spring-boot:run

# Terminal 4 — API Gateway
cd api-gateway && mvn spring-boot:run
```

Wait ~15 seconds for all services to register with Eureka.

---

### DEMO STEP 1 — Show everything works normally

**Say to audience:** *"Let's confirm the system is healthy — circuit breaker is CLOSED."*

**Request:**
```
GET http://localhost:8082/employees/1
```

**Expected — real department data returned:**
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
    "departmentName": "Engineering",
    "departmentCode": "ENG-01"
  }
}
```

**Check circuit breaker state:**
```
GET http://localhost:8082/actuator/circuitbreakers
```

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

### DEMO STEP 2 — Kill department-service

**Say to audience:** *"Now I'll simulate a real outage — department service goes down."*

```powershell
# Stop department-service (Ctrl+C in Terminal 2, or kill the process)
```

> ⏳ **Wait ~8 seconds** for Eureka registry + LoadBalancer cache to expire.

---

### DEMO STEP 3 — Trip the circuit breaker (just 2 calls!)

**Say to audience:** *"Watch — only 2 failed calls are needed to open the circuit."*

**Send this request TWICE:**
```
GET http://localhost:8082/employees/1
```

**What happens internally each time:**
```
Request → CircuitBreaker (CLOSED) → Retry (3 attempts × 500ms) → Connection refused
                                                                         ↓
                                                         CB records 1 FAILURE
```

Each call takes ~1.5s (3 retries × 500ms). After 2 calls → 100% failure rate → **CB OPENS**.

**Expected response (graceful fallback — same for both):**
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

### DEMO STEP 4 — Show CB is OPEN and short-circuiting

**Say to audience:** *"Circuit is now OPEN. Any further requests are blocked instantly — no retries, no HTTP calls."*

```
GET http://localhost:8082/actuator/circuitbreakers
```

```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "OPEN",
      "failedCalls": 2,
      "failureRate": "100.0%",
      "bufferedCalls": 2
    }
  }
}
```

**Now hit the employee endpoint one more time:**
```
GET http://localhost:8082/employees/1
```

> ⚡ Response comes back **instantly** — no 1.5s retry delay. CB short-circuits and returns the fallback without making any HTTP call.

---

### DEMO STEP 5 — Wait 10 seconds → HALF-OPEN

**Say to audience:** *"After 10 seconds, the circuit moves to HALF-OPEN and allows one trial request through."*

> ⏳ **Wait 10 seconds.**

```
GET http://localhost:8082/actuator/circuitbreakers
```

```json
{
  "circuitBreakers": {
    "department-service": {
      "state": "HALF_OPEN"
    }
  }
}
```

---

### DEMO STEP 6 — Bring department-service back and recover

**Say to audience:** *"Service is back. One successful trial call closes the circuit."*

```powershell
# Restart department-service in Terminal 2
cd department-service && mvn spring-boot:run
```

> ⏳ **Wait ~8 seconds** for Eureka registration.

**Send 1 request:**
```
GET http://localhost:8082/employees/1
```

Real department data is returned → CB transitions to **CLOSED**.

**Confirm:**
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

---

### Demo Quick Reference Card

| Step | Action | Time |
|------|--------|------|
| 1 | Verify CLOSED state | ~10s |
| 2 | Stop department-service + wait 8s | ~10s |
| 3 | Send 2 requests → CB opens | ~5s |
| 4 | Show OPEN + instant fallback | ~15s |
| 5 | Wait 10s → HALF-OPEN | 10s |
| 6 | Restart service + wait 8s + 1 call → CLOSED | ~20s |
| **Total** | | **~70 seconds** |

---

## PART 2 — Full Testing Guide (All Patterns)

---

### Pattern 1 — Circuit Breaker

Already covered in the demo above. Summary:

| State     | What happens on a request              | How to enter                              |
|-----------|----------------------------------------|-------------------------------------------|
| CLOSED    | Normal — request goes through          | Starting state / after recovery           |
| OPEN      | Instant fallback — no HTTP call made   | 2+ failed requests with >50% failure rate |
| HALF-OPEN | 1 trial request allowed through        | Automatically after 10s in OPEN           |

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/circuitbreakers
```

---

### Pattern 2 — Retry

**What it does:** Automatically retries a failed HTTP call up to `max-attempts` times before giving up.

**Config:**
```yaml
retry:
  instances:
    department-service:
      max-attempts: 3
      wait-duration: 500ms
```

**How to observe:**

1. Stop department-service
2. Send one request:
   ```
   GET http://localhost:8082/employees/1
   ```
3. Watch **employee-service logs** — you'll see 3 connection attempts before fallback:
   ```
   Retry attempt 1 for 'department-service'
   Retry attempt 2 for 'department-service'
   Retry attempt 3 for 'department-service'
   Fallback triggered
   ```
4. Response takes **~1.5 seconds** (3 × 500ms waits)

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/retries
```

---

### Pattern 3 — Rate Limiter

**What it does:** Caps requests at `limit-for-period` per `limit-refresh-period`. Excess requests are rejected immediately.

**Config:**
```yaml
ratelimiter:
  instances:
    department-service:
      limit-for-period: 20
      limit-refresh-period: 1s
      timeout-duration: 0ms    # don't wait — reject immediately
```

**How to test:**

Send **21+ requests within 1 second** (use Postman Runner or a load tool):
```
GET http://localhost:8082/employees/1  (×21 rapid fire)
```

- First 20 → succeed (or hit retry/CB as normal)
- 21st onward → rejected instantly with:
  ```
  503 Service Unavailable
  RateLimiter 'department-service' does not permit further calls
  ```

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/ratelimiters
```

---

### Pattern 4 — Bulkhead

**What it does:** Limits concurrent calls to `max-concurrent-calls`. Requests beyond the limit are rejected after `max-wait-duration`.

**Config:**
```yaml
bulkhead:
  instances:
    department-service:
      max-concurrent-calls: 10
      max-wait-duration: 10ms
```

**How to test:**

Send **11+ concurrent requests** simultaneously (use Postman with parallel runners or a load tool):
```
GET http://localhost:8082/employees/1  (×11 simultaneously)
```

- First 10 → processed concurrently
- 11th onward → rejected after 10ms with:
  ```
  503 Service Unavailable
  Bulkhead 'department-service' is full and does not permit further calls
  ```

**Actuator endpoint:**
```
GET http://localhost:8082/actuator/health
```
_(Bulkhead metrics appear under the health details section)_

---

### All Actuator Endpoints

| Pattern        | Endpoint                                          |
|----------------|---------------------------------------------------|
| Circuit Breaker | `GET /actuator/circuitbreakers`                  |
| Retry          | `GET /actuator/retries`                           |
| Rate Limiter   | `GET /actuator/ratelimiters`                      |
| All health     | `GET /actuator/health`                            |

> All endpoints are on **employee-service** (port 8082).

---

### Why Fallback Must Be on @CircuitBreaker (Not @Retry)

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

*Generated: 2026-05-24 | Project: SpringClaudeBased | Settings tuned for live demo*
