package com.resume.transportation.service.command;

import com.resume.transportation.enums.Location;

import java.time.LocalDateTime;

public record CreateReservationCommand(
        Long operatorId,
        Long vehicleId,
        Long dispatcherId,
        Location fromLocation,
        Location toLocation,
        LocalDateTime startTime,
        LocalDateTime endTime
) {
}
