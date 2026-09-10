package dev.toktokhan.invalidation.springboot.app;

/**
 * pirl-spring 의 {@code SlotInstanceRepositoryCustom} 과 같은 이름의 프래그먼트
 * 인터페이스입니다. {@link SlotInstanceRepository} 가 이를 합성합니다.
 */
public interface SlotInstanceRepositoryCustom {

    void touch(Long id);
}
