package com.resume.transportation.entity;

import com.resume.transportation.enums.Location;
import com.resume.transportation.enums.ReservationStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "reservation",
        indexes = {
                @Index(name = "idx_reservation_vehicle_status",
                        columnList = "vehicle_id, status"),
                @Index(name = "idx_reservation_from_to",
                        columnList = "fromLocation, toLocation"),
                @Index(name = "idx_reservation_updated_at",
                        columnList = "updatedAt"),
                @Index(name = "idx_reservation_vehicle_time",
                        columnList = "vehicle_id, status, startTime, endTime"),
                @Index(name = "idx_reservation_dispatcher_time",
                        columnList = "dispatcher_id, status, startTime, endTime")
        }
//        uniqueConstraints = {
//                @UniqueConstraint(
//                        name = "uk_vehicle_active_from",
//                        columnNames = {"vehicle_id", "from_location", "status"}
//                )
//        }
)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() {
        return id;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private User operator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispatcher_id", nullable = false)
    private User dispatcher;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Location fromLocation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Location toLocation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    /**
     * 낙관적 락
     */
    @Version
    private Long version;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected Reservation() {
    }

    public static Reservation create(
            Vehicle vehicle,
            User dispatcher,
            User operator,
            Location fromLocation,
            Location toLocation,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        Reservation reservation = new Reservation();
        reservation.vehicle = vehicle;
        reservation.dispatcher = dispatcher;
        reservation.operator = operator;
        reservation.fromLocation = fromLocation;
        reservation.toLocation = toLocation;
        reservation.startTime = startTime;
        reservation.endTime = endTime;
        reservation.status = ReservationStatus.CREATED;
        reservation.updatedAt = LocalDateTime.now();
        return reservation;
    }

    public Reservation(Vehicle vehicle,
                       Location fromLocation,
                       Location toLocation) {
        this.vehicle = vehicle;
        this.fromLocation = fromLocation;
        this.toLocation = toLocation;
        this.status = ReservationStatus.CREATED;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateDestination(Location toLocation) {
        this.toLocation = toLocation;
        this.updatedAt = LocalDateTime.now();
    }

    public void changeStatus(ReservationStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
}
