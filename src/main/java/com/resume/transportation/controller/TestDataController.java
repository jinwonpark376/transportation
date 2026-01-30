package com.resume.transportation.controller;

import com.resume.transportation.entity.User;
import com.resume.transportation.entity.Vehicle;
import com.resume.transportation.enums.Location;
import com.resume.transportation.enums.UserRole;
import com.resume.transportation.enums.VehicleStatus;
import com.resume.transportation.repository.ReservationRepository;
import com.resume.transportation.repository.UserRepository;
import com.resume.transportation.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 테스트 데이터 관리 컨트롤러
 * 부하 테스트 전 데이터 셋업용
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestDataController {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;

    /**
     * 테스트 데이터 초기화
     * POST /api/test/setup?vehicles=10&dispatchers=20
     */
    @PostMapping("/setup")
    public ResponseEntity<Map<String, Object>> setupTestData(
            @RequestParam(defaultValue = "10") int vehicles,
            @RequestParam(defaultValue = "20") int dispatchers
    ) {
        // 기존 데이터 삭제
        reservationRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        // Operator 생성
        User operator = userRepository.save(new User(UserRole.OPERATOR, "TestOperator"));

        // 차량 생성
        List<Long> vehicleIds = new ArrayList<>();
        for (int i = 0; i < vehicles; i++) {
            Vehicle vehicle = vehicleRepository.save(
                    new Vehicle(Location.AIRPORT, VehicleStatus.IDLE)
            );
            vehicleIds.add(vehicle.getId());
        }

        // 디스패처 생성
        List<Long> dispatcherIds = new ArrayList<>();
        for (int i = 0; i < dispatchers; i++) {
            User dispatcher = userRepository.save(
                    new User(UserRole.VOLUNTEER, "Dispatcher" + i)
            );
            dispatcherIds.add(dispatcher.getId());
        }

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "operatorId", operator.getId(),
                "vehicleIds", vehicleIds,
                "dispatcherIds", dispatcherIds,
                "message", String.format("차량 %d대, 디스패처 %d명 생성 완료", vehicles, dispatchers)
        ));
    }

    /**
     * 모든 예약 삭제
     */
    @DeleteMapping("/reservations")
    public ResponseEntity<Map<String, Object>> clearReservations() {
        long count = reservationRepository.count();
        reservationRepository.deleteAll();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "deletedCount", count,
                "message", "모든 예약 삭제 완료"
        ));
    }

    /**
     * 현재 데이터 통계
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "vehicles", vehicleRepository.count(),
                "users", userRepository.count(),
                "reservations", reservationRepository.count()
        ));
    }
}
