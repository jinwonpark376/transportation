package com.resume.transportation.service;

import com.resume.transportation.entity.Reservation;
import com.resume.transportation.enums.Location;
import com.resume.transportation.repository.ReservationRepository;
import com.resume.transportation.repository.UserRepository;
import com.resume.transportation.repository.VehicleRepository;
import com.resume.transportation.service.command.CreateReservationCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationService {
    private final ReservationRepository reservationRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final TravelTimeService travelTimeService;
    private final ReservationPersistenceService persistenceService;

    /**
     * 선점 후 검증 방식의 예약 생성
     *
     * 1. 기본 유효성 검증 (위치, 이동시간)
     * 2. INSERT 및 커밋 (선점) - 새로운 트랜잭션으로 즉시 커밋
     * 3. overlap 검증 - 실패 시 삭제
     */
    public Reservation createReservation(CreateReservationCommand cmd) {

        // 1️⃣ 해당 시간 기준 위치 검증 (Vehicle)
        Location vehicleLocationAtStart =
                findLastLocation(
                        reservationRepository.findVehicleLastLocation(
                                cmd.vehicleId(), cmd.startTime(),
                                PageRequest.of(0, 1)
                        ),
                        vehicleRepository.findBaseLocation(cmd.vehicleId())
                );

        if (vehicleLocationAtStart != cmd.fromLocation()) {
            throw new IllegalStateException("차량 위치가 출발지와 다릅니다.");
        }

        // 2️⃣ 해당 시간 기준 위치 검증 (Dispatcher)
        Location dispatcherLocationAtStart =
                findLastLocation(
                        reservationRepository.findDispatcherLastLocation(
                                cmd.dispatcherId(), cmd.startTime(),
                                PageRequest.of(0, 1)
                        ),
                        userRepository.findBaseLocation(cmd.dispatcherId())
                );

        if (dispatcherLocationAtStart != cmd.fromLocation()) {
            throw new IllegalStateException("디스패처 위치가 출발지와 다릅니다.");
        }

        // 3️⃣ 이동 시간 최소 조건 검증
        int requiredMinutes =
                travelTimeService.getRequiredMinutes(
                        cmd.fromLocation(), cmd.toLocation()
                );

        long actualMinutes =
                Duration.between(cmd.startTime(), cmd.endTime()).toMinutes();

        if (actualMinutes < requiredMinutes) {
            throw new IllegalStateException("이동 시간 부족");
        }

        // 4️⃣ 예약 생성 및 저장 (선점) - 별도 트랜잭션으로 즉시 커밋
        Reservation reservation = persistenceService.insertReservation(cmd);

        // 5️⃣ 선점 성공 후 overlap 검증
        // 자기 자신을 제외하고 시간이 겹치는 예약이 있는지 확인
        try {
            if (reservationRepository.existsVehicleOverlapExcluding(
                    cmd.vehicleId(), reservation.getId(), cmd.startTime(), cmd.endTime())) {
                throw new IllegalStateException("차량이 해당 시간에 이미 예약되어 있습니다.");
            }

            if (reservationRepository.existsDispatcherOverlapExcluding(
                    cmd.dispatcherId(), reservation.getId(), cmd.startTime(), cmd.endTime())) {
                throw new IllegalStateException("디스패처가 해당 시간에 이미 배정되어 있습니다.");
            }
        } catch (IllegalStateException e) {
            // overlap 발견 → 선점했던 예약 삭제
            persistenceService.deleteReservation(reservation.getId());
            throw e;
        }

        return reservation;
    }

    private Location findLastLocation(List<Location> history, Location base) {
        return history.isEmpty() ? base : history.get(0);
    }
}
