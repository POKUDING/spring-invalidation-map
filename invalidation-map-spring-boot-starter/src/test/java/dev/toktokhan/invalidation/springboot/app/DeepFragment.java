package dev.toktokhan.invalidation.springboot.app;

/**
 * {@link DeepBaseRepository} 가 직접 선언하고 {@link DeepRepository} 는 상속만 하는
 * 프래그먼트 인터페이스입니다. {@code entityFor} 가 리포지토리 인터페이스의 상위 인터페이스
 * 계층까지 전이적으로 훑는지 검증하는 데 씁니다(리뷰 라운드 2, R1).
 */
public interface DeepFragment {

    void noop(Long id);
}
