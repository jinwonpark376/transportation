package com.resume.transportation.entity;

import com.resume.transportation.enums.Location;
import com.resume.transportation.enums.VehicleStatus;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "vehicle",
        indexes = {
                @Index(name = "idx_vehicle_status", columnList = "status"),
                @Index(name = "idx_vehicle_location", columnList = "baseLocation")
        })
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Location baseLocation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleStatus status; // IDLE, MOVING

    protected Vehicle() {
    }

    public Vehicle(Location baseLocation, VehicleStatus status) {
        this.baseLocation = baseLocation;
        this.status = status;
    }

    public void moveTo(Location location) {
        this.baseLocation = location;
        this.status = VehicleStatus.MOVING;
    }

    public void standby(Location location) {
        this.baseLocation = location;
        this.status = VehicleStatus.IDLE;
    }
}
