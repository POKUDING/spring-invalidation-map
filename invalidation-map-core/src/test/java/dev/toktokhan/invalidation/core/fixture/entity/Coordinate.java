package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Embeddable;

@Embeddable
public class Coordinate {

    private double latitude;

    public double latitude() {
        return latitude;
    }
}
