package dev.toktokhan.invalidation.core.fixture.event;

import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class TripEventListeners {

    /** 파라미터가 상위 타입입니다. TripCompletedEvent 발행에도 걸려야 합니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripEvent(TripEvent event) {
    }

    /** 관련 없는 이벤트를 듣습니다. 걸리면 안 됩니다. */
    @EventListener
    public void onString(String event) {
    }

    /**
     * 파라미터가 없고 어노테이션의 classes 속성으로 이벤트 타입을 지정하는 형태입니다.
     * 파라미터 타입(Object)은 모든 타입의 상위 타입이므로, classes 속성을 무시하고
     * 파라미터 타입으로 대체하면 관련 없는 이벤트에도 걸리는 과잉이 생깁니다.
     */
    @TransactionalEventListener(classes = TripArchivedEvent.class)
    public void onArchivedByClasses(Object event) {
    }

    /** 리스너가 아닙니다. */
    public void notAListener(TripEvent event) {
    }
}
