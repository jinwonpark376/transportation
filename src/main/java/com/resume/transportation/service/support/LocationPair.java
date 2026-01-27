package com.resume.transportation.service.support;

import com.resume.transportation.enums.Location;

public record LocationPair(Location from, Location to) {
    public static LocationPair of(Location from, Location to) {
        return new LocationPair(from, to);
    }
}
