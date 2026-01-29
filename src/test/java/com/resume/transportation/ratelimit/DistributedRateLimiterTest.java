package com.resume.transportation.ratelimit;

import com.resume.transportation.service.ratelimit.CircuitBreaker;
import com.resume.transportation.service.ratelimit.DistributedRateLimiter;
import com.resume.transportation.service.ratelimit.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(EmbeddedRedisConfig.class)
class DistributedRateLimiterTest {

    @Autowired
    private DistributedRateLimiter rateLimiter;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        // 테스트 전 Redis 초기화 및 Circuit Breaker 리셋
        redissonClient.getKeys().flushall();
        rateLimiter.resetCircuit();
    }

    @Test
    @DisplayName("첫 번째 요청은 분산 락을 획득할 수 있다")
    void firstRequestShouldAcquireLock() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // when
        DistributedRateLimiter.LockContext context = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // then
        assertThat(context).isNotNull();
        assertThat(context.isSkipped()).isFalse();
        assertThat(context.locks()).isNotEmpty();

        // cleanup
        rateLimiter.release(context);
    }

    @Test
    @DisplayName("동일 리소스+시간대에 두 번째 요청은 실패한다")
    void secondRequestOnSameSlotShouldFail() throws Exception {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // 첫 번째 요청 - 성공
        DistributedRateLimiter.LockContext firstContext = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // when & then - 다른 스레드에서 두 번째 요청은 실패
        // (Redisson RLock은 재진입을 지원하므로 같은 스레드에서는 성공함)
        AtomicReference<Exception> caught = new AtomicReference<>();
        Thread otherThread = new Thread(() -> {
            try {
                rateLimiter.tryAcquireForVehicle(vehicleId, start, end);
            } catch (RateLimitExceededException e) {
                caught.set(e);
            }
        });
        otherThread.start();
        otherThread.join();

        assertThat(caught.get())
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("distributed");

        // cleanup
        rateLimiter.release(firstContext);
    }

    @Test
    @DisplayName("락 해제 후 다시 획득 가능하다")
    void canAcquireAfterRelease() {
        // given
        Long vehicleId = 1L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // 첫 번째 요청 후 해제
        DistributedRateLimiter.LockContext firstContext = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);
        rateLimiter.release(firstContext);

        // when - 다시 획득 시도
        DistributedRateLimiter.LockContext secondContext = rateLimiter.tryAcquireForVehicle(vehicleId, start, end);

        // then - 성공
        assertThat(secondContext).isNotNull();
        assertThat(secondContext.locks()).isNotEmpty();

        // cleanup
        rateLimiter.release(secondContext);
    }

    @Test
    @DisplayName("다른 차량은 같은 시간대에 락 획득 가능하다")
    void differentVehicleCanAcquireSameTimeSlot() {
        // given
        Long vehicleId1 = 1L;
        Long vehicleId2 = 2L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 15, 11, 0);

        // when
        DistributedRateLimiter.LockContext context1 = rateLimiter.tryAcquireForVehicle(vehicleId1, start, end);
        DistributedRateLimiter.LockContext context2 = rateLimiter.tryAcquireForVehicle(vehicleId2, start, end);

        // then - 둘 다 성공
        assertThat(context1.locks()).isNotEmpty();
        assertThat(context2.locks()).isNotEmpty();

        // cleanup
        rateLimiter.release(context1);
        rateLimiter.release(context2);
    }

    @Test
    @DisplayName("동시에 100개 요청 시 1개만 성공한다 (분산 환경 시뮬레이션)")
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

                    DistributedRateLimiter.LockContext context = 
                            rateLimiter.tryAcquireForVehicle(vehicleId, start, end);
                    successCount.incrementAndGet();

                    // 잠시 유지 후 해제
                    Thread.sleep(50);
                    rateLimiter.release(context);

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
        System.out.printf("Redisson 분산 락 테스트 - 성공: %d, 실패: %d%n", successCount.get(), failCount.get());
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("시간대가 겹치는 요청도 실패한다")
    void overlappingTimeSlotShouldFail() throws Exception {
        // given
        Long vehicleId = 1L;
        LocalDateTime start1 = LocalDateTime.of(2024, 1, 15, 10, 0);
        LocalDateTime end1 = LocalDateTime.of(2024, 1, 15, 11, 0);

        LocalDateTime start2 = LocalDateTime.of(2024, 1, 15, 10, 30);  // 10시 슬롯과 겹침
        LocalDateTime end2 = LocalDateTime.of(2024, 1, 15, 11, 30);

        // 첫 번째 요청 - 성공
        DistributedRateLimiter.LockContext firstContext = rateLimiter.tryAcquireForVehicle(vehicleId, start1, end1);

        // when & then - 다른 스레드에서 겹치는 두 번째 요청은 실패
        AtomicReference<Exception> caught = new AtomicReference<>();
        Thread otherThread = new Thread(() -> {
            try {
                rateLimiter.tryAcquireForVehicle(vehicleId, start2, end2);
            } catch (RateLimitExceededException e) {
                caught.set(e);
            }
        });
        otherThread.start();
        otherThread.join();

        assertThat(caught.get()).isInstanceOf(RateLimitExceededException.class);

        // cleanup
        rateLimiter.release(firstContext);
    }

    @Test
    @DisplayName("Circuit Breaker 초기 상태는 CLOSED이다")
    void circuitBreakerInitialStateIsClosed() {
        assertThat(rateLimiter.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Circuit Breaker 리셋이 동작한다")
    void circuitBreakerResetWorks() {
        // given - 정상 상태 확인
        assertThat(rateLimiter.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // when - 리셋
        rateLimiter.resetCircuit();

        // then - 여전히 CLOSED
        assertThat(rateLimiter.getCircuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
