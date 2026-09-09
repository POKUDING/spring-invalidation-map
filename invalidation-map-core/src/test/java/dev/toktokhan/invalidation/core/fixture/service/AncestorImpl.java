package dev.toktokhan.invalidation.core.fixture.service;

/**
 * {@link AncestorBase#store} 를 오버라이드 없이 그대로 상속합니다. {@code
 * implementationsOf(AncestorPort)} 의 유일한 등록된 구현체이며, 이 클래스 자신의 바이트는
 * 정상적으로 읽힙니다 — 못 읽는 것은 실제로 {@code store} 를 선언한 {@link AncestorBase}
 * 입니다.
 */
public class AncestorImpl extends AncestorBase {
}
