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
}
