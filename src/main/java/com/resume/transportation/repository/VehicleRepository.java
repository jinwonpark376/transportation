package com.resume.transportation.repository;

import com.resume.transportation.entity.Vehicle;
import com.resume.transportation.enums.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    @Query("""
                select v.baseLocation
                from Vehicle v
                where v.id = :vehicleId
            """)
    Location findBaseLocation(@Param("vehicleId") Long vehicleId);
}
