package com.resume.transportation.service;

import com.resume.transportation.enums.Location;
import com.resume.transportation.service.support.LocationPair;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class TravelTimeService {

    private static final Map<LocationPair, Integer> TRAVEL_TIME_MAP =
            Map.of(
                    LocationPair.of(Location.AIRPORT, Location.HOTEL), 60,
                    LocationPair.of(Location.HOTEL, Location.VENUE_ONE), 30,
                    LocationPair.of(Location.VENUE_ONE, Location.VENUE_TWO), 20
            );

    public int getRequiredMinutes(Location from, Location to) {
        return TRAVEL_TIME_MAP.getOrDefault(
                LocationPair.of(from, to),
                Integer.MAX_VALUE // 이동 불가
        );
    }
}
