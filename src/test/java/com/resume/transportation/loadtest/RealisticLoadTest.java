package com.resume.transportation.loadtest;

import com.resume.transportation.entity.User;
import com.resume.transportation.entity.Vehicle;
import com.resume.transportation.enums.Location;
import com.resume.transportation.enums.UserRole;
import com.resume.transportation.enums.VehicleStatus;
import com.resume.transportation.repository.ReservationRepository;
import com.resume.transportation.repository.UserRepository;
import com.resume.transportation.repository.VehicleRepository;
import com.resume.transportation.service.ReservationService;
import com.resume.transportation.service.command.CreateReservationCommand;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현실적인 부하 테스트
 * 
 * 기존 테스트 문제점:
 * - 각 스레드가 독립 리소스 사용 → 경합 없음
 * 
 * 이 테스트의 특징:
 * - 제한된 리소스(차량 10대, 디스패처 20명)에 동시 요청
 * - 동일 시간대 경합 유발
 * - 실제 MySQL + Redis 사용 (Testcontainers)
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("loadtest")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealisticLoadTest {

    // ============================================
    // Testcontainers
    // ============================================
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("transportation")
            .withUsername("test")
            .withPassword("test")
            .withCommand(
                    "--max_connections=300",
                    "--innodb_buffer_pool_size=128M"
            );

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ============================================
    // Dependencies
    // ============================================
    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private UserRepository userRepository;

    // ============================================
    // Test Fixtures - 제한된 리소스
    // ============================================
    private static final int VEHICLE_COUNT = 10;      // 차량 10대만
    private static final int DISPATCHER_COUNT = 20;   // 디스패처 20명만
    
    private List<Vehicle> vehicles;
    private List<User> dispatchers;
    private User operator;

    private final StringBuilder reportBuilder = new StringBuilder();

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        // 제한된 리소스 생성
        operator = userRepository.save(new User(UserRole.OPERATOR, "Operator"));
        
        vehicles = new ArrayList<>();
        for (int i = 0; i < VEHICLE_COUNT; i++) {
            vehicles.add(vehicleRepository.save(
                    new Vehicle(Location.AIRPORT, VehicleStatus.IDLE)
            ));
        }

        dispatchers = new ArrayList<>();
        for (int i = 0; i < DISPATCHER_COUNT; i++) {
            dispatchers.add(userRepository.save(
                    new User(UserRole.VOLUNTEER, "Dispatcher" + i)
            ));
        }
    }

    /**
     * 시나리오 1: 동일 차량 + 동일 시간대 경합
     * 
     * 10대의 차량에 100개의 동시 요청
     * → 각 차량당 10개 요청이 경합
     * → 이론적으로 10개만 성공해야 함
     */
    @Test
    @Order(1)
    @DisplayName("시나리오1: 동일 시간대 차량 경합 (100 요청 → 10대 차량)")
    void scenario1_sameTimeSlotContention() throws Exception {
        int requestCount = 100;
        LocalDateTime baseTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0);
        
        LoadTestResult result = runLoadTest(
                "Scenario1_SameTimeSlot",
                requestCount,
                index -> {
                    // 10대 차량에 순환 배정 → 각 차량당 10개 요청
                    Vehicle vehicle = vehicles.get(index % VEHICLE_COUNT);
                    User dispatcher = dispatchers.get(index % DISPATCHER_COUNT);
                    
                    return new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            baseTime,                    // 모두 같은 시작 시간!
                            baseTime.plusHours(2)
                    );
                }
        );

        // 검증: 차량 10대이므로 최대 10개 성공
        assertThat(result.successCount).isLessThanOrEqualTo(VEHICLE_COUNT);
        System.out.println(result.report);
    }

    /**
     * 시나리오 2: 겹치는 시간대 경합 (더 현실적)
     * 
     * 예약이 서로 겹치는 시간대로 요청
     * 09:00-11:00, 09:30-11:30, 10:00-12:00 ...
     */
    @Test
    @Order(2)
    @DisplayName("시나리오2: 겹치는 시간대 경합 (500 요청)")
    void scenario2_overlappingTimeSlots() throws Exception {
        int requestCount = 500;
        LocalDateTime baseTime = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0);
        
        LoadTestResult result = runLoadTest(
                "Scenario2_OverlappingTime",
                requestCount,
                index -> {
                    Vehicle vehicle = vehicles.get(index % VEHICLE_COUNT);
                    User dispatcher = dispatchers.get(index % DISPATCHER_COUNT);
                    
                    // 15분씩 offset → 2시간 예약이므로 8개 예약이 겹침
                    int offsetMinutes = (index % 8) * 15;
                    LocalDateTime startTime = baseTime.plusMinutes(offsetMinutes);
                    
                    return new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            startTime,
                            startTime.plusHours(2)
                    );
                }
        );

        System.out.println(result.report);
    }

    /**
     * 시나리오 3: 핫스팟 테스트 (특정 차량에 집중)
     * 
     * 80%의 요청이 특정 2대 차량에 집중
     * → 실제 인기 차량 시나리오
     */
    @Test
    @Order(3)
    @DisplayName("시나리오3: 핫스팟 (80% 요청이 2대 차량에 집중)")
    void scenario3_hotspotVehicles() throws Exception {
        int requestCount = 500;
        LocalDateTime baseTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0);
        Random random = new Random(42);
        
        LoadTestResult result = runLoadTest(
                "Scenario3_Hotspot",
                requestCount,
                index -> {
                    // 80% 확률로 차량 0 또는 1 선택 (핫스팟)
                    int vehicleIndex = random.nextDouble() < 0.8 
                            ? random.nextInt(2)     // 핫스팟: 차량 0, 1
                            : random.nextInt(VEHICLE_COUNT);  // 나머지 차량
                    
                    Vehicle vehicle = vehicles.get(vehicleIndex);
                    User dispatcher = dispatchers.get(index % DISPATCHER_COUNT);
                    
                    // 다양한 시간대
                    int hourOffset = index % 8;
                    LocalDateTime startTime = baseTime.plusHours(hourOffset);
                    
                    return new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            startTime,
                            startTime.plusHours(2)
                    );
                }
        );

        System.out.println(result.report);
    }

    /**
     * 시나리오 4: 점진적 부하 증가 (병목 지점 탐색)
     */
    @Test
    @Order(4)
    @DisplayName("시나리오4: 점진적 부하 증가 (병목 지점 탐색)")
    void scenario4_findBottleneck() throws Exception {
        int[] loadLevels = {50, 100, 200, 500, 1000, 2000};
        
        StringBuilder fullReport = new StringBuilder();
        fullReport.append("\n========================================\n");
        fullReport.append("   병목 지점 탐색 테스트\n");
        fullReport.append("   리소스: 차량 ").append(VEHICLE_COUNT).append("대, 디스패처 ").append(DISPATCHER_COUNT).append("명\n");
        fullReport.append("========================================\n\n");

        for (int requestCount : loadLevels) {
            // 각 라운드 전 정리
            reservationRepository.deleteAll();
            
            LocalDateTime baseTime = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0);
            
            LoadTestResult result = runLoadTest(
                    "Bottleneck_" + requestCount,
                    requestCount,
                    index -> {
                        Vehicle vehicle = vehicles.get(index % VEHICLE_COUNT);
                        User dispatcher = dispatchers.get(index % DISPATCHER_COUNT);
                        
                        // 다양한 시간대로 분산
                        int slotIndex = index / (VEHICLE_COUNT * 2);  // 20개 요청마다 다른 시간대
                        LocalDateTime startTime = baseTime.plusHours(slotIndex % 12);
                        
                        return new CreateReservationCommand(
                                operator.getId(),
                                vehicle.getId(),
                                dispatcher.getId(),
                                Location.AIRPORT,
                                Location.HOTEL,
                                startTime,
                                startTime.plusHours(2)
                        );
                    }
            );

            fullReport.append(String.format("부하 %5d건: 성공=%4d, 실패=%4d, TPS=%7.2f, p99=%6.2fms, 에러율=%.1f%%\n",
                    requestCount,
                    result.successCount,
                    result.failCount,
                    result.tps,
                    result.p99Latency,
                    result.errorRate
            ));

            // 에러율 50% 이상이면 중단
            if (result.errorRate >= 50) {
                fullReport.append("\n⚠️ 에러율 50% 초과 - 병목 지점 도달\n");
                break;
            }
        }

        fullReport.append("\n========================================\n");
        
        // 파일 저장
        Path reportPath = Path.of("build/reports/bottleneck-test-report.txt");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, fullReport.toString());
        
        System.out.println(fullReport);
    }

    /**
     * 시나리오 5: 스파이크 테스트 (갑작스러운 부하)
     */
    @Test
    @Order(5)
    @DisplayName("시나리오5: 스파이크 테스트 (1초 내 1000건)")
    void scenario5_spikeTest() throws Exception {
        int requestCount = 1000;
        LocalDateTime baseTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0);
        
        reservationRepository.deleteAll();
        
        // 모든 스레드가 동시에 시작하도록 Barrier 사용
        CyclicBarrier barrier = new CyclicBarrier(requestCount + 1);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(requestCount, 200));
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        ConcurrentHashMap<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch latch = new CountDownLatch(requestCount);

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    barrier.await();  // 모든 스레드가 준비될 때까지 대기
                    
                    long start = System.nanoTime();
                    
                    Vehicle vehicle = vehicles.get(index % VEHICLE_COUNT);
                    User dispatcher = dispatchers.get(index % DISPATCHER_COUNT);
                    int slotIndex = index / 50;
                    LocalDateTime startTime = baseTime.plusHours(slotIndex % 12);
                    
                    CreateReservationCommand cmd = new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            startTime,
                            startTime.plusHours(2)
                    );

                    reservationService.createReservation(cmd);
                    successCount.incrementAndGet();
                    
                    long latency = (System.nanoTime() - start) / 1_000_000;
                    latencies.add(latency);
                    
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    String errorType = e.getClass().getSimpleName();
                    errorTypes.computeIfAbsent(errorType, k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 스레드 시작 신호
        long startTime = System.currentTimeMillis();
        barrier.await();
        
        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        
        long duration = System.currentTimeMillis() - startTime;

        // 결과 출력
        StringBuilder report = new StringBuilder();
        report.append("\n========================================\n");
        report.append("   스파이크 테스트 결과\n");
        report.append("========================================\n");
        report.append(String.format("총 요청: %d건\n", requestCount));
        report.append(String.format("성공: %d건\n", successCount.get()));
        report.append(String.format("실패: %d건\n", failCount.get()));
        report.append(String.format("소요 시간: %dms\n", duration));
        report.append(String.format("TPS: %.2f\n", (double) successCount.get() / (duration / 1000.0)));
        report.append(String.format("에러율: %.2f%%\n", (double) failCount.get() / requestCount * 100));
        report.append("\n에러 유형별 분포:\n");
        errorTypes.forEach((type, count) -> 
                report.append(String.format("  - %s: %d건\n", type, count.get()))
        );
        
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            report.append(String.format("\n응답 시간:\n"));
            report.append(String.format("  - p50: %dms\n", latencies.get(latencies.size() / 2)));
            report.append(String.format("  - p95: %dms\n", latencies.get((int)(latencies.size() * 0.95))));
            report.append(String.format("  - p99: %dms\n", latencies.get((int)(latencies.size() * 0.99))));
            report.append(String.format("  - max: %dms\n", latencies.get(latencies.size() - 1)));
        }
        
        System.out.println(report);
    }

    // ============================================
    // Helper Methods
    // ============================================
    
    private LoadTestResult runLoadTest(
            String testName,
            int requestCount,
            java.util.function.IntFunction<CreateReservationCommand> commandGenerator
    ) throws Exception {
        
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(requestCount, 200));
        CountDownLatch latch = new CountDownLatch(requestCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        ConcurrentHashMap<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            executor.submit(() -> {
                long requestStart = System.nanoTime();
                try {
                    CreateReservationCommand cmd = commandGenerator.apply(index);
                    reservationService.createReservation(cmd);
                    successCount.incrementAndGet();
                    
                    long latency = (System.nanoTime() - requestStart) / 1_000_000;
                    latencies.add(latency);
                    totalLatency.addAndGet(latency);
                    
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    String errorType = categorizeError(e);
                    errorTypes.computeIfAbsent(errorType, k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        long duration = System.currentTimeMillis() - startTime;
        long actualReservations = reservationRepository.count();

        // 결과 계산
        double tps = (double) successCount.get() / (duration / 1000.0);
        double errorRate = (double) failCount.get() / requestCount * 100;
        double avgLatency = successCount.get() > 0 ? (double) totalLatency.get() / successCount.get() : 0;
        
        double p99Latency = 0;
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            p99Latency = latencies.get((int)(latencies.size() * 0.99));
        }

        // 리포트 생성
        StringBuilder report = new StringBuilder();
        report.append("\n========================================\n");
        report.append("   ").append(testName).append(" 결과\n");
        report.append("========================================\n");
        report.append(String.format("요청: %d건 | 성공: %d | 실패: %d\n", requestCount, successCount.get(), failCount.get()));
        report.append(String.format("실제 예약 수: %d건\n", actualReservations));
        report.append(String.format("소요 시간: %dms | TPS: %.2f\n", duration, tps));
        report.append(String.format("평균 응답시간: %.2fms | p99: %.2fms\n", avgLatency, p99Latency));
        report.append(String.format("에러율: %.2f%%\n", errorRate));
        
        if (!errorTypes.isEmpty()) {
            report.append("\n에러 분류:\n");
            errorTypes.entrySet().stream()
                    .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                    .forEach(e -> report.append(String.format("  - %s: %d건\n", e.getKey(), e.getValue().get())));
        }
        
        return new LoadTestResult(
                successCount.get(),
                failCount.get(),
                tps,
                avgLatency,
                p99Latency,
                errorRate,
                report.toString()
        );
    }

    private String categorizeError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        if (message.contains("rate") || message.contains("처리 중인 요청")) {
            return "RateLimit";
        } else if (message.contains("이미 예약") || message.contains("overlap")) {
            return "Overlap";
        } else if (message.contains("위치") || message.contains("location")) {
            return "LocationMismatch";
        } else if (message.contains("timeout") || message.contains("timed out")) {
            return "Timeout";
        } else if (message.contains("connection") || message.contains("pool")) {
            return "ConnectionPool";
        } else if (message.contains("deadlock")) {
            return "Deadlock";
        } else {
            return e.getClass().getSimpleName();
        }
    }

    record LoadTestResult(
            int successCount,
            int failCount,
            double tps,
            double avgLatency,
            double p99Latency,
            double errorRate,
            String report
    ) {}
}
