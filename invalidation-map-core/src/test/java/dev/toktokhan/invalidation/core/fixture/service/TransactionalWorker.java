package dev.toktokhan.invalidation.core.fixture.service;

import org.springframework.transaction.annotation.Transactional;

/**
 * 클래스 레벨 {@code @Transactional} 을 선언하지만 {@code doWork}/{@code touch} 를
 * 오버라이드하지 않습니다. 이 어노테이션 조회가 선언 클래스({@link AbstractTransactionalWorker})
 * 가 아니라 수신 타입(이 클래스)에서 시작해야 상속된 메서드도 트랜잭션 안으로 잡힙니다.
 */
@Transactional
public class TransactionalWorker extends AbstractTransactionalWorker {
}
