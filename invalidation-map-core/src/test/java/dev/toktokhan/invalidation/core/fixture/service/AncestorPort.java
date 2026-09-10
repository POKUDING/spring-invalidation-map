package dev.toktokhan.invalidation.core.fixture.service;

/**
 * {@link AncestorBase} 가 구현하고 {@link AncestorImpl} 은 오버라이드 없이 상속만 하는
 * 인터페이스입니다. {@code CallGraphWalkerTest} 가 "후보 자신은 읽히지만 그 메서드가
 * 선언된 상위 클래스를 못 읽는" 경우를 재현하는 데 씁니다.
 */
public interface AncestorPort {

    void store(String title);
}
