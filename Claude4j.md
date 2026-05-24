You are a Senior Java Microservices Architect.

I already have an existing Spring Boot microservices application.
I want to integrate Resilience4j into the project using production-grade best practices.

Project Details:
- Java Version: 17
- Spring Boot Version: [YOUR_VERSION]
- Build Tool: Maven
- Architecture: Microservices
- REST APIs already exist
- External service calls are made using RestTemplate/WebClient/FeignClient
- Existing layers:
  Controller -> Service -> Connector/Client Layer

Your task is to enhance the existing application with Resilience4j.

Requirements:
1. Add Maven dependencies required for:
    - Circuit Breaker
    - Retry
    - Rate Limiter
    - Bulkhead
    - Time Limiter
    - Spring Boot Actuator

2. Show complete pom.xml dependency additions.

3. Configure Resilience4j in application.yml with:
    - circuit breaker configs
    - retry configs
    - rate limiter configs
    - bulkhead configs
    - timeout configs
    - actuator exposure

4. Apply annotations on external API calls:
    - @CircuitBreaker
    - @Retry
    - @RateLimiter
    - @Bulkhead
    - @TimeLimiter

5. Add fallback methods for all failures.

6. Explain:
    - CLOSED, OPEN, HALF_OPEN states
    - retry behavior
    - bulkhead isolation
    - rate limiting
    - timeout handling

7. Provide:
    - Controller code
    - Service code
    - Connector/Client code
    - Exception handling
    - Logging strategy

8. Show realistic production examples:
    - Payment service failure
    - Notification service timeout
    - External API slowness
    - Temporary downstream outage

9. Add monitoring support:
    - Spring Boot Actuator endpoints
    - health indicators
    - metrics endpoints

10. Include:
- curl commands for testing
- Postman test scenarios
- failure simulation examples

11. Add JUnit 5 integration tests using:
- Mockito
- SpringBootTest

12. Explain best practices:
- when to use retry vs circuit breaker
- avoiding retry storms
- choosing timeout values
- handling fallback responses
- bulkhead sizing strategy

13. Show folder structure after integration.

14. Refactor the code cleanly without breaking existing APIs.

15. Follow enterprise coding standards:
- SOLID principles
- clean architecture
- centralized configs
- reusable resilience policies

Generate complete working code with explanations.