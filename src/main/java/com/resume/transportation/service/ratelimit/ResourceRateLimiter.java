package com.resume.transportation.service.ratelimit;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 리소스(차량/디스패처) + 시간 슬롯 기반 Rate Limiter
 *
 * 동일 리소스의 겹치는 시간대에 대해 동시 요청 수를 제한하여
 * DB 부하를 줄이고 불필요한 선점-삭제 사이클을 방지한다.
 */
@Component
public class ResourceRateLimiter {

    /**
     * 리소스별 Semaphore 저장소
     * Key: "VEHICLE_{id}_SLOT_{hour}" 또는 "DISPATCHER_{id}_SLOT_{hour}"
     */
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /**
     * 동시 요청 허용 수 (리소스+슬롯당)
     * 1로 설정하면 완전 직렬화, 높이면 어느 정도 동시성 허용
     */
    private static final int PERMITS_PER_SLOT = 1;

    /**
     * 차량 + 시간 슬롯에 대한 permit 획득 시도
     *
     * @return 획득한 슬롯 키 Set (작업 완료 후 release에 사용)
     * @throws RateLimitExceededException permit 획득 실패 시
     */
    public Set<String> tryAcquireForVehicle(Long vehicleId, LocalDateTime startTime, LocalDateTime endTime) {
        return tryAcquire("VEHICLE", vehicleId, startTime, endTime);
    }

    /**
     * 디스패처 + 시간 슬롯에 대한 permit 획득 시도
     */
    public Set<String> tryAcquireForDispatcher(Long dispatcherId, LocalDateTime startTime, LocalDateTime endTime) {
        return tryAcquire("DISPATCHER", dispatcherId, startTime, endTime);
    }

    /**
     * 획득한 permit 반환
     */
    public void release(Set<String> slotKeys) {
        for (String key : slotKeys) {
            Semaphore semaphore = semaphores.get(key);
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }

    private Set<String> tryAcquire(String resourceType, Long resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        Set<String> slotKeys = generateSlotKeys(resourceType, resourceId, startTime, endTime);
        Set<String> acquiredKeys = ConcurrentHashMap.newKeySet();

        try {
            for (String key : slotKeys) {
                Semaphore semaphore = semaphores.computeIfAbsent(key, k -> new Semaphore(PERMITS_PER_SLOT));

                if (!semaphore.tryAcquire()) {
                    // 하나라도 실패하면 이미 획득한 것들 반환 후 예외
                    release(acquiredKeys);
                    throw new RateLimitExceededException(
                            String.format("%s %d의 해당 시간대에 이미 처리 중인 요청이 있습니다.", resourceType, resourceId)
                    );
                }
                acquiredKeys.add(key);
            }
            return acquiredKeys;
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            // 예상치 못한 에러 시 정리
            release(acquiredKeys);
            throw e;
        }
    }

    /**
     * 시간 범위를 1시간 단위 슬롯으로 변환하여 키 생성
     *
     * 예: 10:30 ~ 12:15 → SLOT_10, SLOT_11, SLOT_12
     */
    private Set<String> generateSlotKeys(String resourceType, Long resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        Set<String> keys = ConcurrentHashMap.newKeySet();

        int startHour = startTime.getHour();
        int endHour = endTime.getHour();

        // 분이 0이 아니면 해당 시간 슬롯도 포함
        // 예: 12:00 정각에 끝나면 12시 슬롯은 불필요
        if (endTime.getMinute() == 0 && endTime.getSecond() == 0) {
            endHour--;
        }

        // 날짜가 다른 경우도 고려 (일단 같은 날 가정, 필요시 확장)
        String datePrefix = startTime.toLocalDate().toString();

        for (int hour = startHour; hour <= endHour; hour++) {
            String key = String.format("%s_%d_%s_SLOT_%02d", resourceType, resourceId, datePrefix, hour);
            //VEHICLE_1_2026-01-29_9:00
            keys.add(key);
        }

        return keys;
    }

    /**
     * 테스트/모니터링용: 현재 관리 중인 semaphore 수
     */
    public int getActiveSemaphoreCount() {
        return semaphores.size();
    }

    /**
     * 오래된 슬롯 정리 (스케줄러에서 호출)
     * 과거 날짜의 슬롯은 더 이상 필요 없음
     */
    public void cleanupExpiredSlots(LocalDateTime before) {
        String cutoffDate = before.toLocalDate().toString();
        semaphores.keySet().removeIf(key -> {
            // 키에서 날짜 부분 추출하여 비교
            // 형식: RESOURCE_ID_DATE_SLOT_HH
            String[] parts = key.split("_");
            if (parts.length >= 3) {
                String dateInKey = parts[2];
                return dateInKey.compareTo(cutoffDate) < 0;
            }
            return false;
        });
    }
}
