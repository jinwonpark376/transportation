package com.resume.transportation.service.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 간단한 Circuit Breaker 구현
 *
 * 상태:
 * - CLOSED: 정상 동작, 요청을 외부 서비스로 전달
 * - OPEN: 차단 상태, 요청을 즉시 실패 처리 (또는 Fallback)
 * - HALF_OPEN: 복구 시도 중, 일부 요청만 외부 서비스로 전달
 */
public class CircuitBreaker {

    private final String name;
    private final int failureThreshold;
    private final Duration openTimeout;
    private final Duration halfOpenTimeout;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    /**
     * Half-Open 상태에서 CLOSED로 전환하기 위해 필요한 연속 성공 횟수
     */
    private static final int HALF_OPEN_SUCCESS_THRESHOLD = 3;

    public enum State {
        CLOSED,     // 정상 - 모든 요청 통과
        OPEN,       // 차단 - 모든 요청 Fallback
        HALF_OPEN   // 복구 시도 - 일부만 통과
    }

    public CircuitBreaker(String name, int failureThreshold, Duration openTimeout) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openTimeout = openTimeout;
        this.halfOpenTimeout = openTimeout.dividedBy(2);
    }

    /**
     * 요청 실행 가능 여부 확인 및 상태 전이
     *
     * @return true면 요청 실행, false면 Fallback 사용
     */
    public boolean allowRequest() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                // 타임아웃 경과 시 HALF_OPEN으로 전이
                if (isOpenTimeoutExpired()) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        successCount.set(0);
                        System.out.printf("[CircuitBreaker:%s] OPEN → HALF_OPEN (복구 시도 시작)%n", name);
                    }
                    return true;  // HALF_OPEN에서 요청 허용
                }
                return false;  // 아직 OPEN 상태, Fallback 사용

            case HALF_OPEN:
                return true;  // 복구 시도를 위해 요청 허용

            default:
                return true;
        }
    }

    /**
     * 요청 성공 기록
     */
    public void recordSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            int currentSuccess = successCount.incrementAndGet();
            if (currentSuccess >= HALF_OPEN_SUCCESS_THRESHOLD) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    failureCount.set(0);
                    System.out.printf("[CircuitBreaker:%s] HALF_OPEN → CLOSED (복구 완료)%n", name);
                }
            }
        } else if (currentState == State.CLOSED) {
            // 연속 실패 카운트 리셋
            failureCount.set(0);
        }
    }

    /**
     * 요청 실패 기록
     */
    public void recordFailure() {
        lastFailureTime.set(Instant.now());
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // HALF_OPEN에서 실패 → 다시 OPEN
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(Instant.now());
                System.out.printf("[CircuitBreaker:%s] HALF_OPEN → OPEN (복구 실패)%n", name);
            }
        } else if (currentState == State.CLOSED) {
            int currentFailures = failureCount.incrementAndGet();
            if (currentFailures >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt.set(Instant.now());
                    System.out.printf("[CircuitBreaker:%s] CLOSED → OPEN (연속 %d회 실패)%n", name, currentFailures);
                }
            }
        }
    }

    private boolean isOpenTimeoutExpired() {
        Instant opened = openedAt.get();
        return opened != null && Instant.now().isAfter(opened.plus(openTimeout));
    }

    /**
     * 현재 상태 조회
     */
    public State getState() {
        // 상태 조회 시에도 OPEN → HALF_OPEN 전이 체크
        if (state.get() == State.OPEN && isOpenTimeoutExpired()) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return state.get();
    }

    /**
     * 현재 실패 카운트
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Circuit Breaker 이름
     */
    public String getName() {
        return name;
    }

    /**
     * 강제 리셋 (테스트용)
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        openedAt.set(null);
        lastFailureTime.set(null);
    }
}
