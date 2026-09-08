package dev.toktokhan.invalidation.core.fixture.service;

import dev.toktokhan.invalidation.core.fixture.event.TripArchivedEvent;
import dev.toktokhan.invalidation.core.fixture.event.TripCompletedEvent;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

public class TripService {

    private TripPort port;
    private ApplicationEventPublisher publisher;

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
}
