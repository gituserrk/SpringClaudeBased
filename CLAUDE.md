# Spring Boot Microservices Employee Management System

Simple Spring Boot microservices learning project using Employee and Department services.

---

# Objective

Build a beginner-friendly microservices application using:

- Spring Boot 3.5.x
- Java 17
- Embedded Tomcat
- Maven
- Eureka Service Registry
- API Gateway
- REST communication

The system should demonstrate:
- Service discovery
- API Gateway routing
- Inter-service communication
- DTO aggregation
- Layered architecture

---

# Project Structure

```text
.
├── service-registry/
├── api-gateway/
├── employee-service/
├── department-service/
├── common-lib/
└── pom.xml
```

---

# Microservices

## 1. Service Registry

Responsibilities:
- Eureka server
- Service registration
- Service discovery

Runs on:
- Port 8761

---

## 2. API Gateway

Responsibilities:
- Centralized routing
- Forward requests to services
- Hide internal service URLs

Runs on:
- Port 9090

Routes:
- /employees/**
- /departments/**

---

## 3. Department Service

Responsibilities:
- Manage department information
- CRUD operations for departments

Runs on:
- Port 8081

Entity:
- Department
    - id
    - departmentName
    - departmentCode

Database:
- H2 in-memory database

---

## 4. Employee Service

Responsibilities:
- Manage employee information
- Fetch department details from Department Service
- Aggregate employee + department response

Runs on:
- Port 8082

Entity:
- Employee
    - id
    - firstName
    - lastName
    - email
    - departmentId

Communication:
- REST call to Department Service

Expected API:
- GET /employees/{id}

Expected Response:
- Employee details
- Department details

---

# Coding Standards

- Use layered architecture:
    - Controller
    - Service
    - Repository
    - DTO
    - Entity

- No field injection
- Use constructor injection
- No `any`-style generic coding
- Use Lombok where appropriate
- Use proper exception handling
- Use ResponseEntity for APIs

---

# Technical Requirements

- Spring Boot version: 3.5.x
- Java version: 17
- Maven project
- Embedded Tomcat only
- No Docker
- No Kubernetes
- No AWS
- No external config server
- Local development setup only

---

# Communication Pattern

- Employee Service calls Department Service
- Use RestTemplate or OpenFeign
- Eureka-based service discovery

 ---

# Future Enhancements

Phase 2:
- OpenFeign
- MySQL
- Docker

Phase 3:
- Resilience4j
- Config Server
- Kafka

Phase 4:
- Security with Keycloak

---

# Goal

This project should help developers understand:
- Core microservices concepts
- Spring Cloud fundamentals
- Service-to-service communication
- API Gateway pattern
- Service discovery using Eureka