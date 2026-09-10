package dev.toktokhan.invalidation.core.fixture.service;

/**
 * {@link AncestorPort#store} 를 실제로 구현하는 추상 베이스 클래스입니다.
 * {@link AncestorImpl} 이 이를 오버라이드 없이 상속만 합니다. {@code CallGraphWalkerTest}
 * 가 이 클래스의 바이트만 못 읽게 감싸, "구현체 후보 자신은 읽히지만 그 메서드가 선언된
 * 상위 클래스를 못 읽는" 경우를 재현합니다.
 */
public abstract class AncestorBase implements AncestorPort {

    @Override
    public void store(String title) {
    }
}
