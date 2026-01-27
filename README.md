# 🚐 Transportation Service

> 실시간 교통 예약 서비스의 **동시성 제어**와 **고성능 처리**를 검증하기 위한 프로젝트

## 📋 프로젝트 개요

아시안게임과 같은 대규모 국제 행사에서 VIP/선수단 이동을 위한 차량 예약 시스템을 모델링했습니다. 
동일한 차량/디스패처에 대해 동시에 여러 예약 요청이 발생하는 **Race Condition** 상황에서 
**데이터 정합성**을 보장하면서 **높은 처리량(TPS)**을 달성하는 것이 핵심 목표입니다.

### 🎯 핵심 도전 과제

1. **동시성 문제**: 10개 이상의 동시 요청 중 오직 1개만 성공해야 함
2. **시간 겹침 검증**: 기존 예약과 시간이 겹치는 예약 차단
3. **위치 연속성 검증**: 차량/디스패처의 이전 도착지 = 다음 출발지
4. **고성능**: 독립적인 예약은 최대 **30,000+ TPS** 처리

---

## 🏗️ 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    ReservationController                     │
│                     POST /api/reservations                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    ReservationService                        │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ 1. 위치 검증 (Vehicle, Dispatcher)                   │    │
│  │ 2. 이동 시간 검증 (TravelTimeService)                │    │
│  │ 3. 선점: INSERT + 즉시 COMMIT (REQUIRES_NEW)        │    │
│  │ 4. 검증: Overlap 체크                                │    │
│  │ 5. 실패 시 롤백: DELETE                              │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              ReservationPersistenceService                   │
│               @Transactional(REQUIRES_NEW)                   │
│          • insertReservation() - 선점                        │
│          • deleteReservation() - 롤백                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  ReservationRepository                       │
│  • existsVehicleOverlapExcluding()                          │
│  • existsDispatcherOverlapExcluding()                       │
│  • findVehicleLastLocation()                                 │
│  • findDispatcherLastLocation()                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔑 핵심 동시성 전략: "선점 후 검증" 패턴

### 문제 상황

```
Thread A: 검증 → (통과) → INSERT
Thread B: 검증 → (통과) → INSERT   ← 둘 다 성공하면 안됨!
```

기존의 "검증 후 삽입" 방식은 검증과 삽입 사이의 Gap에서 Race Condition 발생

### 해결 방법

```
Thread A: INSERT(ID=1) → COMMIT → Overlap검증(ID<1 없음) → 성공
Thread B: INSERT(ID=2) → COMMIT → Overlap검증(ID<2 있음=ID:1) → 실패 → DELETE
```

1. **먼저 INSERT하고 즉시 COMMIT** (`REQUIRES_NEW` 트랜잭션)
2. **자신보다 먼저 INSERT된 예약**(`id < excludeId`)과 Overlap 검증
3. 실패 시 **자신의 예약 삭제**

### 왜 이 방식인가?

| 방식 | 장점 | 단점 |
|------|------|------|
| **비관적 락** | 확실한 보장 | 락 경합으로 성능 저하 |
| **낙관적 락** | 높은 동시성 | 충돌 시 재시도 필요 |
| **선점 후 검증** ✅ | DB 레벨 원자성 보장, 재시도 불필요 | 실패 시 DELETE 비용 |

---

## 📁 프로젝트 구조

```
src/
├── main/java/com/resume/transportation/
│   ├── controller/
│   │   └── ReservationController.java      # REST API 엔드포인트
│   │
│   ├── entity/
│   │   ├── Reservation.java                # 예약 (낙관적 락 @Version)
│   │   ├── Vehicle.java                    # 차량
│   │   └── User.java                       # 운영자/디스패처
│   │
│   ├── enums/
│   │   ├── Location.java                   # 장소 (공항, 호텔, 경기장 등)
│   │   ├── ReservationStatus.java          # CREATED, IN_PROGRESS, DONE
│   │   ├── VehicleStatus.java              # IDLE, MOVING
│   │   └── UserRole.java                   # OPERATOR, VOLUNTEER
│   │
│   ├── repository/
│   │   ├── ReservationRepository.java      # Overlap 검증 쿼리
│   │   ├── VehicleRepository.java
│   │   └── UserRepository.java
│   │
│   └── service/
│       ├── ReservationService.java         # 예약 비즈니스 로직
│       ├── ReservationPersistenceService.java  # REQUIRES_NEW 트랜잭션
│       ├── TravelTimeService.java          # 이동 시간 계산
│       ├── command/
│       │   └── CreateReservationCommand.java   # 예약 생성 커맨드
│       └── support/
│           └── LocationPair.java           # 출발지-도착지 쌍
│
└── test/java/com/resume/transportation/
    ├── concurrency/
    │   └── OptimisticLockConcurrencyTest.java  # 동시성 테스트
    └── repository/
        └── OverlapLogicTest.java               # 시간 겹침 로직 테스트
```

---

## 🧪 테스트 시나리오

### 1. 동시성 테스트 (OptimisticLockConcurrencyTest)

#### 테스트 1: 동일 시간대 동시 예약
```java
@Test
@DisplayName("동시에 같은 차량, 같은 시간대 예약 시 하나만 성공해야 한다")
void testConcurrentReservationWithInsertThenValidate()
```
- **시나리오**: 10개 스레드가 동시에 동일 차량, 동일 시간대 예약
- **기대 결과**: 성공 1건, 실패 9건
- **검증**: `assertThat(reservationRepository.count()).isEqualTo(1)`

#### 테스트 2: 겹치는 시간대 동시 예약
```java
@Test  
@DisplayName("동시에 같은 차량, 겹치는 시간대 예약 시 하나만 성공해야 한다")
void testConcurrentReservationWithOverlappingTime()
```
- **시나리오**: 10개 스레드가 10분씩 offset된 시간대로 예약 (모두 겹침)
- **기대 결과**: 가장 먼저 INSERT된 1건만 성공

#### 테스트 3: 독립 예약 처리량
```java
@Test
@DisplayName("동시에 같은 차량, 겹치지 않는 시간대 예약 시 모두 성공")
void testConcurrentReservationWithNonOverlappingTime()
```
- **시나리오**: 5개 스레드가 겹치지 않는 시간대로 예약
- **기대 결과**: 5건 모두 성공

### 2. 부하 테스트 결과

#### TPS 측정 테스트
```java
@Test
@DisplayName("부하 테스트: TPS 측정")
void testThroughput()
```
- 100개 독립 요청 동시 처리
- 평균 응답시간: ~1ms

#### 스트레스 테스트 결과

| 부하 (건) | 성공 | 실패 | 소요시간 | TPS | 평균 응답시간 |
|-----------|------|------|----------|-----|--------------|
| 100 | 100 | 0 | 106ms | **943** | 1.06ms |
| 500 | 500 | 0 | 178ms | **2,809** | 0.36ms |
| 1,000 | 1,000 | 0 | 192ms | **5,208** | 0.19ms |
| 2,000 | 2,000 | 0 | 264ms | **7,576** | 0.13ms |
| 5,000 | 5,000 | 0 | 567ms | **8,818** | 0.11ms |
| 10,000 | 10,000 | 0 | 793ms | **12,610** | 0.08ms |
| 20,000 | 20,000 | 0 | 1,135ms | **17,621** | 0.06ms |
| 500,000 | 500,000 | 0 | 16,230ms | **30,807** | 0.03ms |

> ✅ **50만 건 동시 요청에서도 에러율 0%, TPS 30,000+ 달성**

### 3. 시간 겹침 로직 테스트 (OverlapLogicTest)

```java
// SQL 조건: r.startTime < :endTime AND r.endTime > :startTime
```

| 케이스 | 기존 예약 | 새 예약 | 결과 |
|--------|-----------|---------|------|
| 완전히 겹침 | 10:00~12:00 | 10:30~11:30 | ✅ 겹침 |
| 앞부분만 겹침 | 10:00~12:00 | 09:00~11:00 | ✅ 겹침 |
| 뒷부분만 겹침 | 10:00~12:00 | 11:00~13:00 | ✅ 겹침 |
| 완전히 감싸기 | 10:00~12:00 | 09:00~13:00 | ✅ 겹침 |
| 완전히 이전 | 10:00~12:00 | 08:00~09:00 | ❌ 안겹침 |
| 완전히 이후 | 10:00~12:00 | 13:00~14:00 | ❌ 안겹침 |
| 경계 일치 (시작) | 10:00~12:00 | 12:00~13:00 | ❌ 안겹침 |
| 경계 일치 (종료) | 10:00~12:00 | 09:00~10:00 | ❌ 안겹침 |

---

## 🗃️ 데이터 모델

### Reservation (예약)

```java
@Entity
public class Reservation {
    @Id @GeneratedValue
    private Long id;
    
    @ManyToOne private Vehicle vehicle;      // 예약된 차량
    @ManyToOne private User dispatcher;      // 배정된 디스패처
    @ManyToOne private User operator;        // 요청한 운영자
    
    @Enumerated private Location fromLocation;   // 출발지
    @Enumerated private Location toLocation;     // 도착지
    @Enumerated private ReservationStatus status;
    
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    
    @Version
    private Long version;  // 낙관적 락
}
```

### 핵심 인덱스

```java
@Index(name = "idx_reservation_vehicle_time",
       columnList = "vehicle_id, status, startTime, endTime")

@Index(name = "idx_reservation_dispatcher_time", 
       columnList = "dispatcher_id, status, startTime, endTime")
```

---

## 🔍 핵심 쿼리

### Overlap 검증 쿼리

```sql
SELECT COUNT(r) > 0
FROM Reservation r
WHERE r.vehicle.id = :vehicleId
  AND r.id < :excludeId           -- 자기보다 먼저 INSERT된 예약만
  AND r.status IN ('CREATED','IN_PROGRESS')
  AND r.startTime < :endTime      -- 시간 겹침 조건
  AND r.endTime > :startTime
```

- `r.id < :excludeId`: **순서 보장** - 먼저 INSERT된 예약이 우선권

### 위치 히스토리 조회

```sql
SELECT r.toLocation
FROM Reservation r
WHERE r.vehicle.id = :vehicleId
  AND r.endTime <= :time
ORDER BY r.endTime DESC
LIMIT 1
```

---

## 🚀 실행 방법

### 요구사항
- Java 21+
- Gradle 9.2+

### 테스트 실행

```bash
# 전체 테스트
./gradlew test

# 동시성 테스트만
./gradlew test --tests "*ConcurrencyTest*"

# 스트레스 테스트 (메모리 조절)
./gradlew test -Dtest.maxHeap=4g --tests "*testStressUntilBreak*"
```

### 테스트 리포트
- `build/reports/tests/test/index.html` - JUnit 리포트
- `build/reports/stress-test-report.txt` - 스트레스 테스트 상세 결과

---

## 🛠️ 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 4.0 |
| ORM | Spring Data JPA |
| Database | H2 (In-Memory) |
| Build | Gradle Kotlin DSL |
| Test | JUnit 5, AssertJ |

---

## 📚 학습 포인트

1. **REQUIRES_NEW 트랜잭션**: 중첩 트랜잭션으로 즉시 커밋
2. **선점 후 검증 패턴**: 락 없이 동시성 제어
3. **시간 겹침 로직**: `start < end AND end > start`
4. **인덱스 최적화**: 복합 인덱스로 쿼리 성능 확보
5. **낙관적 락 (@Version)**: 업데이트 충돌 감지

---

## 🔜 향후 개선 사항

- [ ] Redis를 활용한 분산 락 적용
- [ ] 실제 DB(PostgreSQL)에서의 성능 테스트
- [ ] API Rate Limiting 적용
- [ ] 예약 취소/변경 기능 추가
- [ ] 이벤트 소싱 패턴 적용 고려

---

## 📝 License

This project is for portfolio purposes.
