package com.resume.transportation.controller;

import com.resume.transportation.entity.Reservation;
import com.resume.transportation.service.ReservationService;
import com.resume.transportation.service.command.CreateReservationCommand;
import com.resume.transportation.service.ratelimit.CircuitBreaker;
import com.resume.transportation.service.ratelimit.DistributedRateLimiter;
import com.resume.transportation.service.ratelimit.ResourceRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {
    
    private final ReservationService reservationService;
    private final ResourceRateLimiter localRateLimiter;
    private final DistributedRateLimiter distributedRateLimiter;
    
    /**
     * 예약 생성
     */
    @PostMapping
    public ResponseEntity<?> createReservation(@RequestBody CreateReservationCommand command) {
        try {
            Reservation reservation = reservationService.createReservation(command);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "reservationId", reservation.getId(),
                    "message", "예약 생성 성공"
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "failed",
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "error", "예약 생성 실패: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 시스템 상태 조회 (모니터링용)
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Circuit Breaker 상태
        CircuitBreaker.State circuitState = distributedRateLimiter.getCircuitState();
        status.put("circuitBreaker", Map.of(
                "state", circuitState.name(),
                "description", switch (circuitState) {
                    case CLOSED -> "정상 - Redis 사용 중";
                    case OPEN -> "차단 - Fallback 모드";
                    case HALF_OPEN -> "복구 시도 중";
                }
        ));
        
        // Local Semaphore 상태
        status.put("localRateLimiter", Map.of(
                "activeSemaphores", localRateLimiter.getActiveSemaphoreCount()
        ));
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * Circuit Breaker 강제 리셋 (운영용)
     */
    @PostMapping("/circuit/reset")
    public ResponseEntity<String> resetCircuit() {
        distributedRateLimiter.resetCircuit();
        return ResponseEntity.ok("Circuit Breaker 리셋 완료");
    }
}
