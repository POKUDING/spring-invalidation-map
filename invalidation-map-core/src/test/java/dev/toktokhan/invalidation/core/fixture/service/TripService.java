package dev.toktokhan.invalidation.core.fixture.service;

import dev.toktokhan.invalidation.core.fixture.event.TripArchivedEvent;
import dev.toktokhan.invalidation.core.fixture.event.TripCompletedEvent;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

public class TripService {

    private TripPort port;
    private ApplicationEventPublisher publisher;
    private TransactionalWorker transactionalWorker;

    @Transactional
    public void write(String title) {
        port.store(title);
    }

    @Transactional(readOnly = true)
    public void readOnlyWrite(String title) {
        port.store(title);
    }

    /** 읽기 전용 안에서 쓰기 가능을 선언합니다. 쓰기 가능으로 봐야 합니다. */
    @Transactional(readOnly = true)
    public void readOnlyOuter(String title) {
        writableInner(title);
    }

    @Transactional(readOnly = false)
    public void writableInner(String title) {
        port.store(title);
    }

    /** 쓰기 트랜잭션 안의 읽기 전용 선언입니다. 억제되지 않아야 합니다. */
    @Transactional
    public void writableOuter(String title) {
        readOnlyInner(title);
    }

    @Transactional(readOnly = true)
    public void readOnlyInner(String title) {
        port.store(title);
    }

    /** 람다 본문 안의 호출도 따라가야 합니다. */
    public void insideLambda(List<String> titles) {
        titles.forEach(title -> port.store(title));
    }

    /** 이벤트를 발행합니다. 리스너로 이어붙여야 합니다. */
    @Transactional
    public void publish() {
        publisher.publishEvent(new TripCompletedEvent(this));
    }

    /**
     * ApplicationEvent 를 상속하지 않는 POJO 이벤트를 발행합니다.
     *
     * <p>이벤트 후보를 ApplicationEvent 하위로 걸러내는 구현이라면 이 호출은 리스너로
     * 이어붙지 않습니다. Spring 4.2 부터 임의의 객체가 이벤트가 될 수 있으므로 걸러내면
     * 안 됩니다.
     */
    @Transactional
    public void publishArchivedEvent() {
        publisher.publishEvent(new TripArchivedEvent());
    }

    /**
     * 파라미터로 받은 이벤트를 그대로 재발행합니다. 이 메서드 본문에는 {@code NEW} 명령이
     * 없으므로(이벤트는 호출한 쪽에서 만들었습니다) 이벤트 타입을 식별하지 못합니다.
     */
    public void republishEvent(Object event) {
        publisher.publishEvent(event);
    }

    /** 순환 호출입니다. 종료해야 합니다. */
    public void loopA() {
        loopB();
    }

    public void loopB() {
        loopA();
    }

    /** 트랜잭션 밖입니다. */
    public void noTransaction(String title) {
        port.store(title);
    }

    /**
     * 트랜잭션 메서드({@code write})와 비트랜잭션 메서드({@code noTransaction})가 같은
     * 공통 경로({@code port.store} → {@code deepest})로 내려갑니다. 방문 표시가 트랜잭션
     * 상태를 무시하면 둘 중 먼저 처리된 쪽의 상태만 남고 나머지는 버려집니다.
     */
    public void mixedOrder(String title) {
        write(title);
        noTransaction(title);
    }

    /**
     * 클래스 레벨 {@code @Transactional} 이 상속된 메서드에도 적용되는지 확인합니다.
     * {@link TransactionalWorker#doWork} 는 오버라이드 없이 {@link AbstractTransactionalWorker}
     * 에 선언돼 있으므로, 어노테이션 조회가 선언 클래스가 아니라 수신 타입에서 시작해야 합니다.
     */
    public void callTransactionalWorker(String title) {
        transactionalWorker.doWork(title);
    }
}
