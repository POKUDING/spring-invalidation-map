package dev.toktokhan.invalidation.core.fixture.web;

import dev.toktokhan.invalidation.core.annotation.ReadsEntities;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;

/**
 * 문서 어노테이션을 인터페이스에 붙이는 이 저장소의 컨트롤러 관례를 재현하는 픽스처입니다.
 *
 * <p>어노테이션 탐색이 구현 클래스 자신뿐 아니라 상위 타입도 훑는지 검증하는 데 씁니다.
 * {@link TripController#readWithHint} 는 이 인터페이스를 구현하지만 어노테이션은 여기,
 * 인터페이스 메서드에만 붙어 있습니다.
 */
public interface TripEndpoints {

    @ReadsEntities(Coordinate.class)
    Object readWithHint(String title);

    /**
     * 공변 반환 재정의를 검증하는 픽스처입니다. 여기서는 {@code Object} 를 반환하지만
     * {@link TripController} 는 더 좁은 타입({@code Trip})으로 재정의합니다. 반환 타입이
     * 다르면 JVM 디스크립터 전체가 달라지므로, 어노테이션 탐색이 이름·파라미터만 보고
     * 반환 타입을 무시해야 이 어노테이션을 놓치지 않습니다.
     */
    @ReadsEntities(Coordinate.class)
    Object readWithCovariantReturn(String title);
}
