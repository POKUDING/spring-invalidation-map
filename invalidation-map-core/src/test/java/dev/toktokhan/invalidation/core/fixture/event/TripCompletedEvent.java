package dev.toktokhan.invalidation.core.fixture.event;

public class TripCompletedEvent extends TripEvent {

    public TripCompletedEvent(Object source) {
        super(source);
    }

    /**
     * 이벤트를 만드는 정적 팩터리입니다. 이 팩터리를 쓰는 발행 메서드에는 {@code NEW
     * TripCompletedEvent} 명령이 없고, 호출 디스크립터의 반환 타입에만 이벤트 타입이
     * 나타납니다.
     */
    public static TripCompletedEvent of(Object source) {
        return new TripCompletedEvent(source);
    }
}
