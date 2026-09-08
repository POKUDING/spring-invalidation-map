package dev.toktokhan.invalidation.core.fixture.service;

/**
 * {@code touch} 를 선언합니다. 하위 클래스 {@link TransactionalWorker} 는 이 메서드를
 * 오버라이드하지 않으므로, {@code resolveMethod} 로 찾은 {@code MethodFacts} 의
 * {@code ref().owner()} 는 항상 이 클래스입니다 — 실제 호출의 수신 타입인
 * {@link TransactionalWorker} 가 아닙니다.
 */
public abstract class AbstractTransactionalWorker {

    void doWork(String title) {
        touch();
    }

    void touch() {
    }
}
