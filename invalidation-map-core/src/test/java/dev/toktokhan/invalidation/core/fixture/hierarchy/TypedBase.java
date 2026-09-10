package dev.toktokhan.invalidation.core.fixture.hierarchy;

/** 제네릭 상위 타입의 타입 인자를 뽑는 대상입니다. */
public abstract class TypedBase<T> {

    public abstract T load();

    /**
     * {@link TypedChild} 가 오버라이드하지 않는 메서드입니다.
     * resolveMethod 가 선언 클래스에 없으면 상위 타입에서 찾는지 검증하는 데 씁니다.
     */
    public void touch() {
    }
}
