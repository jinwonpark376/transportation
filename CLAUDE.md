# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Transportation Service - A real-time vehicle reservation system demonstrating concurrent request handling. Models large-scale event transportation (e.g., Asian Games) where VIP/athlete reservations require strict data consistency with 10,000+ concurrent requests.

**Core Challenge**: Handle massive concurrent reservation requests (same vehicle, overlapping times) with only 1 success guaranteed while maintaining 30,000+ TPS throughput.

## Build & Test Commands

```bash
# Build
./gradlew build

# Run application (requires Docker services)
./gradlew bootRun

# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "*RateLimiterTest*"
./gradlew test --tests "*ConcurrencyTest*"

# Run load tests (uses Testcontainers)
./gradlew test --tests "RealisticLoadTest" -i
```

## Infrastructure Setup

```bash
# Start MySQL, Redis, Prometheus, Grafana
docker-compose up -d

# Access points after startup:
# - Application: http://localhost:8080
# - Grafana: http://localhost:3000 (admin/admin)
# - Prometheus: http://localhost:9090
```

## Architecture

### 3-Layer Defense Strategy for Concurrency

```
POST /api/reservations
    │
    ├─ Layer 1: ResourceRateLimiter (Local Semaphore)
    │   └─ ConcurrentHashMap<SlotKey, Semaphore(1)>
    │   └─ Key format: "VEHICLE_1_2026-01-29_SLOT_09"
    │
    ├─ Layer 2: DistributedRateLimiter (Redis RLock)
    │   └─ Redisson RLock with CircuitBreaker fallback
    │
    └─ Layer 3: Insert-Then-Validate Pattern
        └─ INSERT → overlap check → DELETE on failure
```

Result: 1000 concurrent requests → 1 DB INSERT (99.9% load reduction)

### Key Source Locations

- **Entry Point**: `ReservationController.java` - POST /api/reservations
- **Business Logic**: `ReservationService.createReservation()` - orchestrates all layers
- **Rate Limiting**: `service/ratelimit/` - ResourceRateLimiter, DistributedRateLimiter, CircuitBreaker
- **Persistence**: `ReservationPersistenceService.java` - REQUIRES_NEW transaction isolation
- **Entities**: `entity/` - Reservation (with @Version optimistic lock), Vehicle, User

### Design Patterns

**Insert-Then-Validate**: Traditional validate-then-insert has timing gaps. This system: INSERT + COMMIT → overlap check → DELETE on failure. DB guarantees atomicity.

**Circuit Breaker**: Redis failure → fallback to local semaphore. States: CLOSED → OPEN (30s) → HALF_OPEN → CLOSED.

## Tech Stack

- Java 21, Spring Boot 4.0.2, Spring Data JPA
- MySQL 8.0 (prod) / H2 (test)
- Redis + Redisson 3.40.2 (distributed locking)
- Micrometer + Prometheus + Grafana (monitoring)
- Testcontainers (integration tests)

## Key Endpoints

- `POST /api/reservations` - Create reservation
- `GET /api/reservations/status` - System status (circuit breaker, semaphores)
- `GET /actuator/prometheus` - Metrics endpoint

## Configuration

- Main config: `src/main/resources/application.yml`
- Test config: `src/test/resources/application.yml` (H2 + loadtest profile)
- HikariCP: 20 max connections, 3s timeout
- Tomcat: 200 threads, 8192 max connections

## Load Testing

```bash
# Shell script for load testing
./scripts/load-test.sh 1000 50  # 1000 requests, 50 concurrency

# Monitor in Grafana during tests
```

Benchmark: 500,000 concurrent requests with 0% error rate at 30,000+ TPS.
