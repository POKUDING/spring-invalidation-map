package dev.toktokhan.invalidation.core.fixture.service;

/**
 * {@code CallGraphWalkerTest} 가 클래스 바이트를 구할 수 없는 구현체를 재현하는 데 쓰는
 * 인터페이스입니다. 등록된 유일한 구현체 이름({@code GhostPortAdapter})은 실제로
 * 컴파일된 적이 없어 클래스로더가 그 이름의 {@code .class} 리소스를 찾지 못합니다 —
 * 핫리로드·멀티 클래스로더 환경에서 실제 구현체를 로드한 클래스로더와 색인을 만든
 * 클래스로더가 달라지는 상황을 흉내 냅니다.
 */
public interface GhostPort {

    void vanish();
}
