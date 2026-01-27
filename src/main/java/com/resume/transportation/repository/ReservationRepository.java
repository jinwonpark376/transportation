package com.resume.transportation.repository;

import com.resume.transportation.entity.Reservation;
import com.resume.transportation.enums.Location;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @Query("""
                select count(r) > 0
                from Reservation r
                where r.vehicle.id = :vehicleId
                  and r.status in ('CREATED','IN_PROGRESS')
                  and r.startTime < :endTime
                  and r.endTime > :startTime
            """)
    boolean existsVehicleOverlap(
            @Param("vehicleId") Long vehicleId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query("""
                select count(r) > 0
                from Reservation r
                where r.dispatcher.id = :dispatcherId
                  and r.status in ('CREATED','IN_PROGRESS')
                  and r.startTime < :endTime
                  and r.endTime > :startTime
            """)
    boolean existsDispatcherOverlap(
            @Param("dispatcherId") Long dispatcherId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /* ===============================
       특정 시점 기준 마지막 위치 조회
       =============================== */

    @Query("""
                select r.toLocation
                from Reservation r
                where r.vehicle.id = :vehicleId
                  and r.endTime <= :time
                order by r.endTime desc
            """)
    List<Location> findVehicleLastLocation(
            @Param("vehicleId") Long vehicleId,
            @Param("time") LocalDateTime time,
            Pageable pageable
    );

    @Query("""
                select r.toLocation
                from Reservation r
                where r.dispatcher.id = :dispatcherId
                  and r.endTime <= :time
                order by r.endTime desc
            """)
    List<Location> findDispatcherLastLocation(
            @Param("dispatcherId") Long dispatcherId,
            @Param("time") LocalDateTime time,
            Pageable pageable
    );

    /* ===============================
       선점 후 검증용: 자기보다 먼저 생성된 예약 중 overlap 체크
       - id < excludeId 조건으로 먼저 INSERT된 예약만 확인
       - 가장 먼저 INSERT된 예약(가장 작은 ID)만 성공
       =============================== */

    @Query("""
                select count(r) > 0
                from Reservation r
                where r.vehicle.id = :vehicleId
                  and r.id < :excludeId
                  and r.status in ('CREATED','IN_PROGRESS')
                  and r.startTime < :endTime
                  and r.endTime > :startTime
            """)
    boolean existsVehicleOverlapExcluding(
            @Param("vehicleId") Long vehicleId,
            @Param("excludeId") Long excludeId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query("""
                select count(r) > 0
                from Reservation r
                where r.dispatcher.id = :dispatcherId
                  and r.id < :excludeId
                  and r.status in ('CREATED','IN_PROGRESS')
                  and r.startTime < :endTime
                  and r.endTime > :startTime
            """)
    boolean existsDispatcherOverlapExcluding(
            @Param("dispatcherId") Long dispatcherId,
            @Param("excludeId") Long excludeId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
