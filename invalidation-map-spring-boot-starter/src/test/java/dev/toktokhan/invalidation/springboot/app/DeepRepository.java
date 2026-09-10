package dev.toktokhan.invalidation.springboot.app;

/**
 * pirl-spring 은 지금 이 패턴을 쓰지 않지만, {@code @NoRepositoryBean} 베이스 리포지토리는
 * Spring Data 표준 관용구입니다. {@link DeepFragment} 를 직접 선언하지 않고
 * {@link DeepBaseRepository} 를 통해서만 상속받습니다 — {@code getInterfaces()} 가 직접
 * 선언만 돌려주므로, 리포지토리 인터페이스의 상위 인터페이스 계층을 훑지 않으면
 * {@code entityFor(DeepFragment)} 가 이 구조에서 빈 값이 됩니다(리뷰 라운드 2, R1).
 */
public interface DeepRepository extends DeepBaseRepository {
}
