package com.resume.transportation.controller;

import com.resume.transportation.service.ReservationService;
import com.resume.transportation.service.command.CreateReservationCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {
    
    private final ReservationService reservationService;
    
    @PostMapping
    public ResponseEntity<String> createReservation(@RequestBody CreateReservationCommand command) {
        try {
            reservationService.createReservation(command);
            return ResponseEntity.ok("예약 생성 성공");
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("예약 생성 실패: " + e.getMessage());
        }
    }
}
