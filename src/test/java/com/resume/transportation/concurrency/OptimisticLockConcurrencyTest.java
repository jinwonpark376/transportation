package com.resume.transportation.concurrency;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OptimisticLockConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private UserRepository userRepository;

    private Vehicle vehicle;
    private User operator;
    private User dispatcher1;
    private User dispatcher2;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        // 테스트 데이터 준비
        vehicle = vehicleRepository.save(new Vehicle(Location.AIRPORT, VehicleStatus.IDLE));
        operator = userRepository.save(new User(UserRole.OPERATOR, "Operator1"));
        dispatcher1 = userRepository.save(new User(UserRole.VOLUNTEER, "Dispatcher1"));
        dispatcher2 = userRepository.save(new User(UserRole.VOLUNTEER, "Dispatcher2"));
    }

    @Test
    @DisplayName("동시에 같은 차량, 같은 시간대 예약 시 하나만 성공해야 한다 (선점 후 검증)")
    void testConcurrentReservationWithInsertThenValidate() throws InterruptedException {
        // Given
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        LocalDateTime startTime = LocalDateTime.now().plusHours(1);
        LocalDateTime endTime = startTime.plusHours(2);

        // When: 10개 쓰레드가 동시에 같은 차량, 같은 시간대 예약 시도
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    CreateReservationCommand command = new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher1.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            startTime,
                            endTime
                    );

                    reservationService.createReservation(command);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    System.out.println("예약 실패: " + e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + failCount.get());
        System.out.println("총 예약 수: " + reservationRepository.count());

        // 실제로 생성된 예약은 1개여야 함
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("동시에 같은 차량, 겹치는 시간대 예약 시 하나만 성공해야 한다 (9시-11시 vs 10시-12시)")
    void testConcurrentReservationWithOverlappingTime() throws InterruptedException {
        // Given
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        LocalDateTime baseTime = LocalDateTime.now().plusHours(1);

        // When: 10개 쓰레드가 겹치는 시간대로 예약 시도
        // 모든 예약이 baseTime ~ baseTime+2시간 범위와 겹치도록 offset을 10분으로 설정
        // 쓰레드 0: baseTime ~ baseTime+2시간
        // 쓰레드 9: baseTime+90분 ~ baseTime+3시간30분
        // 2시간(120분) 예약이므로 10분씩 offset하면 모든 예약이 서로 겹침
        for (int i = 0; i < threadCount; i++) {
            final int offset = i * 10; // 10분씩 offset (2시간 예약이므로 모두 겹침)
            executorService.execute(() -> {
                try {
                    LocalDateTime startTime = baseTime.plusMinutes(offset);
                    LocalDateTime endTime = startTime.plusHours(2);

                    CreateReservationCommand command = new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher1.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            startTime,
                            endTime
                    );

                    reservationService.createReservation(command);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    System.out.println("예약 실패: " + e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + failCount.get());
        System.out.println("총 예약 수: " + reservationRepository.count());

        // 시간이 겹치므로 1개만 성공해야 함
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("동시에 같은 차량, 겹치지 않는 시간대 예약 시 모두 성공")
    void testConcurrentReservationWithNonOverlappingTime() throws InterruptedException {
        // Given
        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When: 겹치지 않는 시간대로 예약
        // 0-2시, 3-5시, 6-8시, 9-11시, 12-14시
        for (int i = 0; i < threadCount; i++) {
            final int hour = i * 3;
            executorService.execute(() -> {
                try {
                    LocalDateTime startTime = LocalDateTime.now().plusHours(hour + 1);
                    LocalDateTime endTime = startTime.plusHours(2);

                    CreateReservationCommand command = new CreateReservationCommand(
                            operator.getId(),
                            vehicle.getId(),
                            dispatcher1.getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            startTime,
                            endTime
                    );

                    reservationService.createReservation(command);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    System.err.println("예약 실패: " + e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + failCount.get());
        System.out.println("총 예약 수: " + reservationRepository.count());

        // 시간이 겹치지 않으므로 모두 성공해야 함
        assertThat(reservationRepository.count()).isEqualTo(threadCount);
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    /// TPS !!
    @Test
    @DisplayName("부하 테스트: TPS 측정")
    void testThroughput() throws InterruptedException {
        // Given
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 각 스레드별로 독립적인 차량과 디스패처 생성 (위치 충돌 방지)
        Vehicle[] vehicles = new Vehicle[threadCount];
        User[] dispatchers = new User[threadCount];
        for (int i = 0; i < threadCount; i++) {
            vehicles[i] = vehicleRepository.save(new Vehicle(Location.AIRPORT, VehicleStatus.IDLE));
            dispatchers[i] = userRepository.save(new User(UserRole.VOLUNTEER, "Dispatcher" + i));
        }

        long startTime = System.currentTimeMillis();

        // When: 100개 요청 동시 발생 (각각 독립적인 차량/디스패처 사용)
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.execute(() -> {
                try {
                    LocalDateTime requestStartTime = LocalDateTime.now().plusHours(1);
                    LocalDateTime requestEndTime = requestStartTime.plusHours(2);

                    CreateReservationCommand command = new CreateReservationCommand(
                            operator.getId(),
                            vehicles[index].getId(),
                            dispatchers[index].getId(),
                            Location.AIRPORT,
                            Location.HOTEL,
                            requestStartTime,
                            requestEndTime
                    );

                    reservationService.createReservation(command);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        double tps = (double) threadCount / (duration / 1000.0);

        // Then
        System.out.println("=== 성능 측정 결과 ===");
        System.out.println("총 요청 수: " + threadCount);
        System.out.println("성공: " + successCount.get());
        System.out.println("실패: " + failCount.get());
        System.out.println("총 예약 수: " + reservationRepository.count());
        System.out.println("소요 시간: " + duration + "ms");
        System.out.println("TPS: " + String.format("%.2f", tps));
        System.out.println("평균 응답 시간: " + String.format("%.2f", (double) duration / threadCount) + "ms");

        // 모든 예약이 성공해야 함 (충돌 없음)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(reservationRepository.count()).isEqualTo(threadCount);
    }

    //TODO -- 구조가 너무 단순해서 병목이 안생긴다.
    @Test
    @DisplayName("스트레스 테스트: 부하를 점진적으로 높여 병목 지점 찾기")
    void testStressUntilBreak() throws InterruptedException, java.io.IOException {
        int[] loadLevels = {100, 500, 1000, 2000, 5000, 10000, 20000, 500000};

        StringBuilder report = new StringBuilder();
        report.append("=== 스트레스 테스트 시작 ===\n\n");

        for (int threadCount : loadLevels) {
            // 각 라운드 전 데이터 정리
            reservationRepository.deleteAll();
            vehicleRepository.deleteAll();
            userRepository.deleteAll();

            // operator는 공용
            User testOperator = userRepository.save(new User(UserRole.OPERATOR, "Operator"));

            // 각 스레드별 독립 차량/디스패처 생성
            Vehicle[] vehicles = new Vehicle[threadCount];
            User[] dispatchers = new User[threadCount];
            for (int i = 0; i < threadCount; i++) {
                vehicles[i] = vehicleRepository.save(new Vehicle(Location.AIRPORT, VehicleStatus.IDLE));
                dispatchers[i] = userRepository.save(new User(UserRole.VOLUNTEER, "D" + i));
            }

            ExecutorService executorService = Executors.newFixedThreadPool(Math.min(threadCount, 200)); // 최대 200 스레드
            CountDownLatch latch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicInteger timeoutCount = new AtomicInteger(0);
            AtomicInteger connectionErrorCount = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executorService.execute(() -> {
                    try {
                        LocalDateTime requestStartTime = LocalDateTime.now().plusHours(1);
                        LocalDateTime requestEndTime = requestStartTime.plusHours(2);

                        CreateReservationCommand command = new CreateReservationCommand(
                                testOperator.getId(),
                                vehicles[index].getId(),
                                dispatchers[index].getId(),
                                Location.AIRPORT,
                                Location.HOTEL,
                                requestStartTime,
                                requestEndTime
                        );

                        reservationService.createReservation(command);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        if (msg.contains("timeout") || msg.contains("timed out")) {
                            timeoutCount.incrementAndGet();
                        } else if (msg.contains("connection") || msg.contains("pool")) {
                            connectionErrorCount.incrementAndGet();
                        }
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executorService.shutdown();

            long duration = System.currentTimeMillis() - startTime;
            double tps = (double) successCount.get() / (duration / 1000.0);
            double errorRate = (double) failCount.get() / threadCount * 100;
            long actualReservations = reservationRepository.count();

            report.append("--- 부하: ").append(threadCount).append("건 ---\n");
            report.append("성공: ").append(successCount.get()).append(" / 실패: ").append(failCount.get()).append("\n");
            report.append("  - 타임아웃: ").append(timeoutCount.get()).append("\n");
            report.append("  - 커넥션 에러: ").append(connectionErrorCount.get()).append("\n");
            report.append("실제 예약 수: ").append(actualReservations).append("\n");
            report.append("소요 시간: ").append(duration).append("ms\n");
            report.append("TPS: ").append(String.format("%.2f", tps)).append("\n");
            report.append("에러율: ").append(String.format("%.2f", errorRate)).append("%\n");
            report.append("평균 응답시간: ").append(String.format("%.2f", (double) duration / threadCount)).append("ms\n\n");

            // 에러율 50% 이상이면 중단
            if (errorRate >= 50) {
                report.append("⚠️ 에러율 50% 초과 - 테스트 중단\n");
                report.append("병목 지점: 약 ").append(threadCount).append("건 동시 요청\n");
                break;
            }
        }

        report.append("=== 스트레스 테스트 완료 ===\n");

        // 파일로 저장
        java.nio.file.Path reportPath = java.nio.file.Path.of("build/reports/stress-test-report.txt");
        java.nio.file.Files.createDirectories(reportPath.getParent());
        java.nio.file.Files.writeString(reportPath, report.toString());

        // 콘솔에도 출력
        System.out.println(report);

        // assertion으로 결과 강제 출력
        assertThat(report.toString()).contains("스트레스 테스트 완료");
    }
}
