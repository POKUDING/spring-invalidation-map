package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * {@code Trip → TripLeg → LegPoint} 손자 사슬의 끝입니다.
 *
 * <p>연관 확장이 한 단계뿐이면 {@code Trip} 을 읽는 엔드포인트가 이 엔티티를 놓칩니다.
 * 응답에는 실리는데 무효화 대상에서 빠지므로 화면에 오래된 값이 남습니다.
 */
@Entity
public class LegPoint {

    @Id
    private Long id;

    private String label;
}
