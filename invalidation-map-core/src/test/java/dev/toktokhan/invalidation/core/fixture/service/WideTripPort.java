package dev.toktokhan.invalidation.core.fixture.service;

/**
 * {@link TripPort} 보다 메서드가 많은 인터페이스입니다. {@link TripPortAdapter} 는
 * {@code store} 만 있고 {@code close} 는 없어 이 인터페이스를 실제로 구현하지 않습니다.
 *
 * <p>{@code CallGraphWalkerTest} 가 {@code implementationsOf(WideTripPort)} 에
 * {@link TripPortAdapter} 를 (실제 구현 관계 없이) 등록해, 워커가 존재하지 않는 메서드
 * 후보를 만나도 "본문을 읽을 수 없습니다" 를 잘못 보고하지 않는지 확인하는 데 씁니다.
 */
public interface WideTripPort {

    void store(String title);

    void close();
}
