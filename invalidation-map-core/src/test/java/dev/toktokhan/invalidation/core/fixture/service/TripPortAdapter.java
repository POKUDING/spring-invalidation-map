package dev.toktokhan.invalidation.core.fixture.service;

/** 워커가 여기까지 닿아야 합니다. */
public class TripPortAdapter implements TripPort {

    @Override
    public void store(String title) {
        deepest(title);
    }

    void deepest(String title) {
    }
}
