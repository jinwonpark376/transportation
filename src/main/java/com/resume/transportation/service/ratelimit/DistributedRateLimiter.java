package com.resume.transportation.service.ratelimit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 기반 분산 Rate Limiter (Circuit Breaker 적용)
 * Redisson의 RLock을 사용하여:
 * - 자동 Watchdog: 락 보유 중 TTL 자동 연장 (기본 30초마다)
 * - 안전한 해제: 본인 락만 해제
 * - 재진입 지원: 같은 스레드에서 여러 번 획득 가능
 * Redis 장애 시 Circuit Breaker가 동작하여 Fallback 처리
 */
@Component
public class DistributedRateLimiter {

    private final RedissonClient redissonClient;
    private final CircuitBreaker circuitBreaker;

    /**
     * 락 키 접두사
     */
    private static final String LOCK_PREFIX = "reservation:lock:";

    /**
     * 락 획득 대기 시간 (0 = 즉시 실패, 대기 안 함)
     */
    private static final long WAIT_TIME = 0;

    /**
     * 락 보유 시간 (-1 = Watchdog 사용, 자동 연장)
     */
    private static final long LEASE_TIME = -1;

    /**
     * Circuit Breaker 설정
     */
    private static final int FAILURE_THRESHOLD = 5;
    private static final java.time.Duration OPEN_TIMEOUT = java.time.Duration.ofSeconds(30);

    public DistributedRateLimiter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.circuitBreaker = new CircuitBreaker("Redisson-DistributedLock", FAILURE_THRESHOLD, OPEN_TIMEOUT);
    }

    /**
     * 차량 + 시간 슬롯에 대한 분산 락 획득 시도
     */
    public LockContext tryAcquireForVehicle(Long vehicleId, LocalDateTime startTime, LocalDateTime endTime) {
        return tryAcquire("VEHICLE", vehicleId, startTime, endTime);
    }

    /**
     * 디스패처 + 시간 슬롯에 대한 분산 락 획득 시도
     */
    public LockContext tryAcquireForDispatcher(Long dispatcherId, LocalDateTime startTime, LocalDateTime endTime) {
        return tryAcquire("DISPATCHER", dispatcherId, startTime, endTime);
    }

    /**
     * 획득한 락 해제
     */
    public void release(LockContext context) {
        if (context == null || context.isSkipped() || context.locks().isEmpty()) {
            return;
        }

        for (RLock lock : context.locks()) {
            try {
                // isHeldByCurrentThread(): 현재 스레드가 락을 보유 중인지 확인
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                // 해제 실패는 Watchdog 만료로 자동 해제되므로 로그만
                System.err.printf("[DistributedRateLimiter] 락 해제 실패 (자동 만료 예정): %s%n", e.getMessage());
            }
        }
    }

    private LockContext tryAcquire(String resourceType, Long resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        // Circuit Breaker 체크
        if (!circuitBreaker.allowRequest()) {
            System.out.printf("[DistributedRateLimiter] Circuit OPEN - Redis 스킵 (Fallback 모드)%n");
            return LockContext.createSkipped();
        }

        Set<String> slotKeys = generateSlotKeys(resourceType, resourceId, startTime, endTime);
        List<RLock> acquiredLocks = new ArrayList<>();

        try {
            for (String slotKey : slotKeys) {
                String lockKey = LOCK_PREFIX + slotKey;
                RLock lock = redissonClient.getLock(lockKey);

                // tryLock(waitTime, leaseTime, unit)
                // waitTime=0: 즉시 실패 (대기 안 함)
                // leaseTime=-1: Watchdog 활성화 (자동 연장)
                boolean acquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);

                if (acquired) {
                    acquiredLocks.add(lock);
                } else {
                    // 하나라도 실패하면 이미 획득한 락들 해제
                    release(new LockContext(acquiredLocks, false));
                    throw new RateLimitExceededException(
                            String.format("%s %d의 해당 시간대에 이미 처리 중인 요청이 있습니다. (distributed)", resourceType, resourceId)
                    );
                }
            }

            circuitBreaker.recordSuccess();
            return new LockContext(acquiredLocks, false);

        } catch (RateLimitExceededException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(new LockContext(acquiredLocks, false));
            circuitBreaker.recordFailure();
            System.err.printf("[DistributedRateLimiter] 락 획득 중 인터럽트: %s%n", e.getMessage());
            return LockContext.createSkipped();
        } catch (Exception e) {
            release(new LockContext(acquiredLocks, false));
            circuitBreaker.recordFailure();
            System.err.printf("[DistributedRateLimiter] Redis 오류 - Fallback 모드로 전환: %s%n", e.getMessage());
            return LockContext.createSkipped();
        }
    }

    private Set<String> generateSlotKeys(String resourceType, Long resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        Set<String> keys = ConcurrentHashMap.newKeySet();

        int startHour = startTime.getHour();
        int endHour = endTime.getHour();

        if (endTime.getMinute() == 0 && endTime.getSecond() == 0) {
            endHour--;
        }

        String datePrefix = startTime.toLocalDate().toString();

        for (int hour = startHour; hour <= endHour; hour++) {
            String key = String.format("%s:%d:%s:SLOT:%02d", resourceType, resourceId, datePrefix, hour);
            keys.add(key);
        }

        return keys;
    }

    /**
     * Circuit Breaker 상태 조회
     */
    public CircuitBreaker.State getCircuitState() {
        return circuitBreaker.getState();
    }

    /**
     * Circuit Breaker 리셋
     */
    public void resetCircuit() {
        circuitBreaker.reset();
    }

    /**
     * 락 컨텍스트 - Redisson RLock 리스트를 보관
     */
    public record LockContext(
            List<RLock> locks,
            boolean skipped
    ) {
        public static LockContext createSkipped() {
            return new LockContext(new ArrayList<>(), true);
        }

        public boolean isSkipped() {
            return skipped;
        }
    }
}
