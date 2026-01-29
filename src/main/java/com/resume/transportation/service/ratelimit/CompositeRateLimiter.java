package com.resume.transportation.service.ratelimit;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 레이어드 방어 전략을 적용한 복합 Rate Limiter
 *
 * Layer 1: Local Semaphore (서버별) - 대부분의 요청 필터링
 * Layer 2: Redis 분산 락 (전역) - 서버 간 조율
 *
 * Semaphore를 먼저 통과해야 Redis에 접근하므로,
 * Redis 부하도 최소화된다.
 */
@Component
public class CompositeRateLimiter {

    private final ResourceRateLimiter localRateLimiter;
    private final DistributedRateLimiter distributedRateLimiter;

    public CompositeRateLimiter(
            ResourceRateLimiter localRateLimiter,
            DistributedRateLimiter distributedRateLimiter
    ) {
        this.localRateLimiter = localRateLimiter;
        this.distributedRateLimiter = distributedRateLimiter;
    }

    /**
     * 차량 + 디스패처에 대한 복합 락 획득
     *
     * @return 락 해제에 필요한 컨텍스트
     */
    public CompositeContext acquire(Long vehicleId, Long dispatcherId, LocalDateTime startTime, LocalDateTime endTime) {
        Set<String> localSlots = new HashSet<>();
        DistributedRateLimiter.LockContext vehicleLock = null;
        DistributedRateLimiter.LockContext dispatcherLock = null;

        try {
            // ============================================
            // Layer 1: Local Semaphore (빠른 필터링)
            // ============================================
            Set<String> vehicleLocalSlots = localRateLimiter.tryAcquireForVehicle(
                    vehicleId, startTime, endTime
            );
            localSlots.addAll(vehicleLocalSlots);

            Set<String> dispatcherLocalSlots = localRateLimiter.tryAcquireForDispatcher(
                    dispatcherId, startTime, endTime
            );
            localSlots.addAll(dispatcherLocalSlots);

            // ============================================
            // Layer 2: Redis 분산 락 (전역 조율)
            // ============================================
            vehicleLock = distributedRateLimiter.tryAcquireForVehicle(
                    vehicleId, startTime, endTime
            );

            dispatcherLock = distributedRateLimiter.tryAcquireForDispatcher(
                    dispatcherId, startTime, endTime
            );

            return new CompositeContext(localSlots, vehicleLock, dispatcherLock);

        } catch (Exception e) {
            // 실패 시 이미 획득한 락들 정리
            releasePartial(localSlots, vehicleLock, dispatcherLock);
            throw e;
        }
    }

    /**
     * 획득한 모든 락 해제
     */
    public void release(CompositeContext context) {
        if (context == null) return;

        releasePartial(
                context.localSlots(),
                context.vehicleDistributedLock(),
                context.dispatcherDistributedLock()
        );
    }

    private void releasePartial(
            Set<String> localSlots,
            DistributedRateLimiter.LockContext vehicleLock,
            DistributedRateLimiter.LockContext dispatcherLock
    ) {
        // Local 락 해제
        if (localSlots != null && !localSlots.isEmpty()) {
            localRateLimiter.release(localSlots);
        }

        // 분산 락 해제
        if (vehicleLock != null) {
            distributedRateLimiter.release(vehicleLock);
        }
        if (dispatcherLock != null) {
            distributedRateLimiter.release(dispatcherLock);
        }
    }

    /**
     * 복합 락 컨텍스트
     */
    public record CompositeContext(
            Set<String> localSlots,
            DistributedRateLimiter.LockContext vehicleDistributedLock,
            DistributedRateLimiter.LockContext dispatcherDistributedLock
    ) {}
}
