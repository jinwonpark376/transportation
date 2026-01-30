# 부하 테스트 & 모니터링 가이드

## 🎯 목표

1. **실제 병목 지점 발견**: 어디서 터지는지 확인 (DB? Redis? Thread Pool?)
2. **모니터링 시각화**: Prometheus + Grafana로 실시간 확인
3. **튜닝 실험**: 인덱스, 커넥션 풀, 스레드 풀 조정 효과 확인

---

## 📦 사전 준비

### Docker Desktop 설치 필요
- [Docker Desktop 다운로드](https://www.docker.com/products/docker-desktop/)

---

## 🚀 실행 방법

### 1. 인프라 시작 (MySQL, Redis, Prometheus, Grafana)

```bash
# 프로젝트 루트에서 실행
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f
```

### 2. 각 서비스 접속 확인

| 서비스 | URL | 용도 |
|--------|-----|------|
| MySQL | localhost:3306 | 데이터베이스 |
| Redis | localhost:6379 | 분산 락 |
| Prometheus | http://localhost:9090 | 메트릭 수집 |
| Grafana | http://localhost:3000 | 시각화 (admin/admin) |

### 3. Spring Boot 애플리케이션 시작

```bash
./gradlew bootRun
```

### 4. Actuator 메트릭 확인

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus 메트릭
curl http://localhost:8080/actuator/prometheus
```

---

## 📊 Grafana 대시보드

### 접속
1. http://localhost:3000 접속
2. 로그인: admin / admin
3. 좌측 메뉴 → Dashboards → Transportation 폴더

### 핵심 모니터링 패널

#### 🔴 Application
- **Request Rate (RPS)**: 초당 요청 수
- **Response Time (p99)**: 99% 응답 시간
- **Error Rate**: 에러 비율

#### 🗄️ HikariCP (DB Connection Pool)
- **Active vs Idle**: 활성/유휴 커넥션 수
- **Acquire Time**: 커넥션 획득 시간 (⚠️ 이게 튀면 DB 병목)
- **Pending**: 대기 중인 요청 (⚠️ 이게 쌓이면 풀 부족)

#### 📊 MySQL
- **QPS**: 초당 쿼리 수
- **Connections**: 연결 수
- **Slow Queries**: 느린 쿼리 (⚠️ 인덱스 확인 필요)

#### 🔴 Redis
- **Commands/sec**: 초당 명령 수
- **Memory Usage**: 메모리 사용량
- **Connected Clients**: 클라이언트 연결 수

#### ⚡ Custom (예약 시스템)
- **Reservation TPS**: 예약 성공/실패 추이
- **Lock Acquire Time**: 락 획득 시간 (⚠️ 경합 지표)
- **Rate Limit Rejections**: 거부된 요청 (Local vs Distributed)

---

## 🧪 부하 테스트 실행

### Testcontainers 기반 테스트 (권장)

Docker가 실행 중이면 자동으로 MySQL/Redis 컨테이너 생성

```bash
# 모든 부하 테스트 실행
./gradlew test --tests "RealisticLoadTest" -i

# 특정 시나리오만 실행
./gradlew test --tests "RealisticLoadTest.scenario1*" -i
./gradlew test --tests "RealisticLoadTest.scenario4*" -i  # 병목 탐색
./gradlew test --tests "RealisticLoadTest.scenario5*" -i  # 스파이크
```

### 테스트 시나리오 설명

| 시나리오 | 설명 | 예상 병목 |
|---------|------|----------|
| scenario1 | 동일 시간대 100건 → 10대 차량 | Rate Limiter |
| scenario2 | 겹치는 시간대 500건 | Rate Limiter + DB |
| scenario3 | 핫스팟 (80%가 2대에 집중) | 특정 리소스 락 경합 |
| scenario4 | 점진적 증가 (50~2000건) | **병목 지점 탐색** |
| scenario5 | 스파이크 (1초에 1000건) | Thread Pool + Connection Pool |

### 결과 리포트 위치

```
build/reports/bottleneck-test-report.txt
```

---

## 🔧 튜닝 실험

### 1. DB Connection Pool 조정

`application.yml`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20   # 기본값, 늘려보기: 30, 50
      connection-timeout: 3000  # 줄여보기: 1000 (빠른 실패)
```

**확인 포인트**: Grafana에서 `hikaricp_connections_pending` 확인

### 2. Thread Pool 조정

```yaml
server:
  tomcat:
    threads:
      max: 200    # 늘려보기: 300, 400
      min-spare: 20
```

### 3. Redis 분산 락 타임아웃

`DistributedRateLimiter.java`:
```java
private static final long WAIT_TIME = 0;  // 변경해보기: 100, 500
```

**주의**: WAIT_TIME > 0이면 데드락 위험 증가

### 4. MySQL 인덱스 확인

```sql
-- 느린 쿼리 확인
SHOW GLOBAL STATUS LIKE 'Slow_queries';

-- 실행 계획 확인
EXPLAIN SELECT * FROM reservation 
WHERE vehicle_id = 1 
  AND status IN ('CREATED', 'IN_PROGRESS')
  AND start_time < '2024-01-01' 
  AND end_time > '2024-01-01';
```

---

## 🚨 병목 증상 & 해결책

### 증상 1: `hikaricp_connections_pending` 급증
**원인**: DB 커넥션 풀 부족  
**해결**: `maximum-pool-size` 증가 또는 쿼리 최적화

### 증상 2: `lock.acquire` p99 급증
**원인**: 동일 리소스 락 경합  
**해결**: 시간 슬롯 세분화, 락 범위 축소

### 증상 3: `rate.limit.rejected` 증가
**원인**: Rate Limiter가 요청 거부  
**해결**: 정상 동작 (동시성 제어 작동 중)

### 증상 4: MySQL `Slow_queries` 증가
**원인**: 인덱스 미사용 또는 풀 스캔  
**해결**: EXPLAIN 분석 후 인덱스 추가

### 증상 5: Redis 응답 지연
**원인**: Redis 과부하 또는 네트워크  
**해결**: Circuit Breaker 확인, Redis 스케일업

---

## 📁 파일 구조

```
transportation/
├── docker-compose.yml           # 인프라 정의
├── docker/
│   ├── grafana/
│   │   ├── dashboards/          # 대시보드 JSON
│   │   └── provisioning/        # 데이터소스 설정
│   ├── mysql/
│   │   └── init.sql             # MySQL 초기화
│   └── prometheus/
│       └── prometheus.yml       # 수집 대상 설정
├── src/main/java/.../
│   └── config/
│       └── MetricsConfig.java   # 커스텀 메트릭 정의
└── src/test/java/.../loadtest/
    └── RealisticLoadTest.java   # 부하 테스트
```

---

## 🛑 정리

```bash
# 모든 컨테이너 중지 및 삭제
docker-compose down

# 볼륨까지 삭제 (데이터 초기화)
docker-compose down -v
```
