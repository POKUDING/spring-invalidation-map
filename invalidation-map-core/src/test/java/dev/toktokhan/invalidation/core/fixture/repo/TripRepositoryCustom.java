package dev.toktokhan.invalidation.core.fixture.repo;

/** Spring Data 프래그먼트 인터페이스입니다. 메서드명이 어느 접두어에도 맞지 않습니다. */
public interface TripRepositoryCustom {

    void upsert(Long id, String title);
}
