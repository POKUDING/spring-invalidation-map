package dev.toktokhan.invalidation.springboot.app;

/**
 * {@link DeepFragment} 의 프래그먼트 구현체입니다. 클래스 이름을 리포지토리 인터페이스
 * ({@link DeepRepository}) 이름 + {@code Impl} 로 지었습니다 — 레거시 이름 규칙입니다. 이
 * 규칙에서는 {@code getFragments()} 의 signatureContributor 가 이 구현체 클래스 자체를
 * 돌려주므로, {@code entityFor(DeepFragment)} 는 프래그먼트 루프가 아니라
 * {@code buildRepositoryIndex} 의 상위 인터페이스 전이 순회로만 채워집니다.
 */
public class DeepRepositoryImpl implements DeepFragment {

    @Override
    public void noop(Long id) {
        // 이 픽스처는 entityFor/implementationsOf 의 인터페이스 대응만 검증합니다.
        // 어떤 테스트도 이 메서드를 실제로 호출하지 않습니다.
    }
}
