package com.resume.transportation.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer 메트릭 설정
 * Prometheus + Grafana로 시각화
 */
@Configuration
public class MetricsConfig {

    /**
     * 예약 생성 성공 카운터
     */
    @Bean
    public Counter reservationCreateCounter(MeterRegistry registry) {
        return Counter.builder("reservation.create")
                .description("Total successful reservation creations")
                .tag("type", "success")
                .register(registry);
    }

    /**
     * 예약 생성 실패 카운터
     */
    @Bean
    public Counter reservationFailedCounter(MeterRegistry registry) {
        return Counter.builder("reservation.create.failed")
                .description("Total failed reservation creations")
                .tag("type", "failed")
                .register(registry);
    }

    /**
     * 락 획득 타이머
     */
    @Bean
    public Timer lockAcquireTimer(MeterRegistry registry) {
        return Timer.builder("lock.acquire")
                .description("Time to acquire locks")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(registry);
    }

    /**
     * Rate Limit 거부 카운터 (Local)
     */
    @Bean
    public Counter rateLimitLocalRejectedCounter(MeterRegistry registry) {
        return Counter.builder("rate.limit.rejected")
                .description("Rate limit rejections")
                .tag("type", "local")
                .register(registry);
    }

    /**
     * Rate Limit 거부 카운터 (Distributed)
     */
    @Bean
    public Counter rateLimitDistributedRejectedCounter(MeterRegistry registry) {
        return Counter.builder("rate.limit.rejected")
                .description("Rate limit rejections")
                .tag("type", "distributed")
                .register(registry);
    }

    /**
     * DB 쿼리 타이머 (Overlap 체크)
     */
    @Bean
    public Timer dbOverlapCheckTimer(MeterRegistry registry) {
        return Timer.builder("db.overlap.check")
                .description("Time to check overlap in DB")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * Circuit Breaker 상태 게이지
     */
    @Bean
    public io.micrometer.core.instrument.Gauge circuitBreakerGauge(
            MeterRegistry registry,
            com.resume.transportation.service.ratelimit.DistributedRateLimiter limiter
    ) {
        return io.micrometer.core.instrument.Gauge.builder("circuit.breaker.state", limiter, l -> {
            var state = l.getCircuitState();
            return switch (state) {
                case CLOSED -> 0;
                case OPEN -> 1;
                case HALF_OPEN -> 2;
            };
        })
        .description("Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
        .register(registry);
    }
}
