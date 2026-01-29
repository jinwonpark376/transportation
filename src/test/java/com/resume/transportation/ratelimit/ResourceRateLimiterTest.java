package com.resume.transportation.ratelimit;

import com.resume.transportation.service.ratelimit.RateLimitExceededException;
import com.resume.transportation.service.ratelimit.ResourceRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceRateLimiterTest {

    private ResourceRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new ResourceRateLimiter();
    }

    @Test
    @DisplayName("첫 번째 요청은 permit을 획득할 수 있다")
    void firstRequestShouldAcquirePermit() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // when
        Set<String> slots = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // then
        assertThat(slots).isNotEmpty();

        // cleanup
        rateLimiter.release(slots);
    }

    @Test
    @DisplayName("동일 리소스+시간대에 두 번째 요청은 실패한다")
    void secondRequestOnSameSlotShouldFail() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // 첫 번째 요청 - 성공
        Set<String> firstSlots = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // when & then - 두 번째 요청은 실패
        assertThatThrownBy(() -> rateLimiter.tryAcquireForVehicle(vehicleId, start, end))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("VEHICLE")
                .hasMessageContaining("이미 처리 중인 요청");

        // cleanup
        rateLimiter.release(firstSlots);
    }

    @Test
    @DisplayName("시간대가 겹치는 요청도 실패한다")
    void overlappingTimeSlotShouldFail() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start1 = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end1 = LocalDateTime.of(2024, 1, 15, 11, 0);

        LocalDateTime start2 = LocalDateTime.of(2024, 1, 15, 10, 30);  // 10시 슬롯과 겹침
        LocalDateTime end2 = LocalDateTime.of(2024, 1, 15, 11, 30);

        // 첫 번째 요청 - 성공
        Set<String> firstSlots = rateLimiter.tryAcquireForVehicle(vehicleId, start1, end1);

        // when & then - 겹치는 두 번째 요청은 실패
        assertThatThrownBy(() -> rateLimiter.tryAcquireForVehicle(vehicleId, start2, end2))
                .isInstanceOf(RateLimitExceededException.class);

        // cleanup
        rateLimiter.release(firstSlots);
    }

    @Test
    @DisplayName("다른 차량은 같은 시간대에 요청 가능하다")
    void differentVehicleCanRequestSameTimeSlot() {
        // given
        Long vehicleId1 = 1L;
        Long vehicleId2 = 2L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // when
        Set<String> slots1 = rateLimiter.tryAcquireForVehicle(vehicleId1, start, end);
        Set<String> slots2 = rateLimiter.tryAcquireForVehicle(vehicleId2, start, end);

        // then - 둘 다 성공
        assertThat(slots1).isNotEmpty();
        assertThat(slots2).isNotEmpty();

        // cleanup
        rateLimiter.release(slots1);
        rateLimiter.release(slots2);
    }

    @Test
    @DisplayName("같은 차량이라도 겹치지 않는 시간대는 요청 가능하다")
    void sameVehicleCanRequestNonOverlappingTimeSlot() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start1 = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end1 = LocalDateTime.of(2024, 1, 15, 11, 0);

        LocalDateTime start2 = LocalDateTime.of(2024, 1, 15, 14, 0);  // 겹치지 않음
        LocalDateTime end2 = LocalDateTime.of(2024, 1, 15, 15, 0);

        // when
        Set<String> slots1 = rateLimiter.tryAcquireForVehicle(vehicleId, start1, end1);
        Set<String> slots2 = rateLimiter.tryAcquireForVehicle(vehicleId, start2, end2);

        // then - 둘 다 성공
        assertThat(slots1).isNotEmpty();
        assertThat(slots2).isNotEmpty();

        // cleanup
        rateLimiter.release(slots1);
        rateLimiter.release(slots2);
    }

    @Test
    @DisplayName("permit 반환 후 다시 획득 가능하다")
    void canAcquireAfterRelease() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // 첫 번째 요청 후 반환
        Set<String> firstSlots = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);
        rateLimiter.release(firstSlots);

        // when - 다시 획득 시도
        Set<String> secondSlots = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // then - 성공
        assertThat(secondSlots).isNotEmpty();

        // cleanup
        rateLimiter.release(secondSlots);
    }

    @Test
    @DisplayName("동시에 100개 요청 시 1개만 성공한다")
    void onlyOneSucceedsUnderConcurrency() throws InterruptedException {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();  // 모든 스레드가 동시에 시작

                    Set<String> slots = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);
                    successCount.incrementAndGet();

                    // 잠시 유지 후 반환
                    Thread.sleep(10);
                    rateLimiter.release(slots);

                } catch (RateLimitExceededException e) {
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();  // 동시 시작!
        doneLatch.await();
        executor.shutdown();

        // then
        System.out.printf("성공: %d, 실패: %d%n", successCount.get(), failCount.get());
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("여러 시간 슬롯에 걸친 예약도 올바르게 처리된다")
    void multipleTimeSlotsAreHandledCorrectly() {
        // given
        Long vehicleId = 1L;
        // 10시 ~ 13시 (3개 슬롯: 10, 11, 12)
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 13, 0);

        // when
        Set<String> slots = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // then - 3개 슬롯이 잠김
        assertThat(slots).hasSize(3);

        // 11시 슬롯과 겹치는 요청은 실패
        LocalDateTime start2 = LocalDateTime.of(2024, 1, 15, 11, 0);
        LocalDateTime end2 = LocalDateTime.of(2024, 1, 15, 12, 0);
        assertThatThrownBy(() -> rateLimiter.tryAcquireForVehicle(vehicleId, start2, end2))
                .isInstanceOf(RateLimitExceededException.class);

        // cleanup
        rateLimiter.release(slots);
    }

    @Test
    @DisplayName("디스패처도 동일한 로직으로 동작한다")
    void dispatcherRateLimitingWorks() {
        // given
        Long dispatcherId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // 첫 번째 요청 - 성공
        Set<String> firstSlots = rateLimiter.tryAcquireForDispatcher(dispatcherId, start, end);
        assertThat(firstSlots).isNotEmpty();

        // when & then - 두 번째 요청은 실패
        assertThatThrownBy(() -> rateLimiter.tryAcquireForDispatcher(dispatcherId, start, end))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("DISPATCHER");

        // cleanup
        rateLimiter.release(firstSlots);
    }
}
