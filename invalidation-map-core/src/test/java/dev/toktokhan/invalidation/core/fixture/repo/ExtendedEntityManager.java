package dev.toktokhan.invalidation.core.fixture.repo;

import jakarta.persistence.EntityManager;

/**
 * {@code EntityManager} 를 상속한 서브타입 픽스처입니다.
 *
 * <p>{@code EntityManagerResolver.isEntityManager} 가 owner 리터럴 비교뿐 아니라
 * {@code ClassRepository.isSubtypeOf} 로 서브타입도 인정하는지 검증하는 데 씁니다.
 */
public interface ExtendedEntityManager extends EntityManager {
}
