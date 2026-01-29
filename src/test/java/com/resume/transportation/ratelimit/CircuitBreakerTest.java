package com.resume.transportation.ratelimit;

import com.resume.transportation.service.ratelimit.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // 3회 실패 시 OPEN, 1초 후 HALF_OPEN
        circuitBreaker = new CircuitBreaker("test", 3, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("초기 상태는 CLOSED이다")
    void initialStateIsClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("연속 실패 횟수 미만이면 CLOSED 유지")
    void staysClosedBelowThreshold() {
        // 2회 실패 (임계값 3 미만)
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("연속 실패가 임계값에 도달하면 OPEN으로 전이")
    void opensAfterThresholdFailures() {
        // 3회 연속 실패
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("OPEN 상태에서는 요청을 거부한다")
    void rejectsRequestsWhenOpen() {
        // OPEN 상태로 만들기
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        // 요청 거부 확인
        assertThat(circuitBreaker.allowRequest()).isFalse();
        assertThat(circuitBreaker.allowRequest()).isFalse();
        assertThat(circuitBreaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("성공하면 실패 카운트가 리셋된다")
    void successResetsFailureCount() {
        // 2회 실패
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getFailureCount()).isEqualTo(2);

        // 성공
        circuitBreaker.recordSuccess();
        assertThat(circuitBreaker.getFailureCount()).isEqualTo(0);

        // 다시 2회 실패해도 CLOSED 유지
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("OPEN 타임아웃 후 HALF_OPEN으로 전이")
    void transitionsToHalfOpenAfterTimeout() throws InterruptedException {
        // OPEN 상태로 만들기
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 타임아웃 대기 (1초 + 여유)
        Thread.sleep(1100);

        // HALF_OPEN으로 전이되어 요청 허용
        assertThat(circuitBreaker.allowRequest()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("HALF_OPEN에서 연속 성공 시 CLOSED로 복귀")
    void closesAfterSuccessesInHalfOpen() throws InterruptedException {
        // OPEN → HALF_OPEN
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        Thread.sleep(1100);
        circuitBreaker.allowRequest();  // HALF_OPEN 전이 트리거

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 3회 연속 성공 (HALF_OPEN_SUCCESS_THRESHOLD = 3)
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN에서 실패 시 다시 OPEN으로")
    void reopensAfterFailureInHalfOpen() throws InterruptedException {
        // OPEN → HALF_OPEN
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        Thread.sleep(1100);
        circuitBreaker.allowRequest();  // HALF_OPEN 전이 트리거

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 실패 발생
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("reset()으로 초기 상태로 복귀")
    void resetRestoresInitialState() {
        // OPEN 상태로 만들기
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 리셋
        circuitBreaker.reset();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getFailureCount()).isEqualTo(0);
        assertThat(circuitBreaker.allowRequest()).isTrue();
    }
}
