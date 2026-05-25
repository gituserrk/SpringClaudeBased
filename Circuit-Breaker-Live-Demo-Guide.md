# Circuit Breaker — Live Demo Guide
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

## Prerequisites — Start All Services

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

## STEP 1 — Show everything works normally

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

## STEP 2 — Kill department-service

**Say to audience:** *"Now I'll simulate a real outage — department service goes down."*

```powershell
# Stop department-service (Ctrl+C in Terminal 2, or kill the process)
```

> ⏳ **Wait ~8 seconds** for Eureka registry + LoadBalancer cache to expire.

---

## STEP 3 — Trip the circuit breaker (just 2 calls!)

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

## STEP 4 — Show CB is OPEN and short-circuiting

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

**Hit the employee endpoint one more time:**
```
GET http://localhost:8082/employees/1
```

> ⚡ Response comes back **instantly** — no 1.5s retry delay. CB short-circuits and returns the fallback without making any HTTP call.

---

## STEP 5 — Wait 10 seconds → HALF-OPEN

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

## STEP 6 — Bring department-service back and recover

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

## Quick Reference Card

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

## State Summary Table

| State     | What happens on a request              | How to enter                              |
|-----------|----------------------------------------|-------------------------------------------|
| CLOSED    | Normal — request goes through          | Starting state / after recovery           |
| OPEN      | Instant fallback — no HTTP call made   | 2+ failed requests with >50% failure rate |
| HALF-OPEN | 1 trial request allowed through        | Automatically after 10s in OPEN           |

---

*Generated: 2026-05-24 | Project: SpringClaudeBased | Settings tuned for live demo*
