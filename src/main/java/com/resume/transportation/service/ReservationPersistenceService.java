package com.resume.transportation.service;

import com.resume.transportation.entity.Reservation;
import com.resume.transportation.entity.User;
import com.resume.transportation.entity.Vehicle;
import com.resume.transportation.repository.ReservationRepository;
import com.resume.transportation.repository.UserRepository;
import com.resume.transportation.repository.VehicleRepository;
import com.resume.transportation.service.command.CreateReservationCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 저장/삭제를 별도 트랜잭션으로 처리하는 서비스
 * REQUIRES_NEW를 사용하여 즉시 커밋되도록 함
 */
@Service
@RequiredArgsConstructor
public class ReservationPersistenceService {

    private final ReservationRepository reservationRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;

    /**
     * 별도 트랜잭션으로 예약 INSERT (즉시 커밋되어 다른 트랜잭션에서 볼 수 있음)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation insertReservation(CreateReservationCommand cmd) {
        Vehicle vehicle = vehicleRepository.getReferenceById(cmd.vehicleId());
        User dispatcher = userRepository.getReferenceById(cmd.dispatcherId());
        User operator = userRepository.getReferenceById(cmd.operatorId());

        Reservation reservation = Reservation.create(
                vehicle,
                dispatcher,
                operator,
                cmd.fromLocation(),
                cmd.toLocation(),
                cmd.startTime(),
                cmd.endTime()
        );

        return reservationRepository.saveAndFlush(reservation);
    }

    /**
     * 별도 트랜잭션으로 예약 삭제
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteReservation(Long reservationId) {
        reservationRepository.deleteById(reservationId);
    }
}
