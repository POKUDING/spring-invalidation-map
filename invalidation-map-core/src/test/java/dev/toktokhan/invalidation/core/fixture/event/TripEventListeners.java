package dev.toktokhan.invalidation.core.fixture.event;

import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class TripEventListeners {

    private TripJpaRepository repository;

    /**
     * 파라미터가 상위 타입입니다. TripCompletedEvent 발행에도 걸려야 합니다.
     *
     * <p>리포지토리 저장 호출이 있어야 분석기가 이벤트를 지나 도달한 리스너의 엔티티 접근을
     * 찾아냅니다. 본문이 비어 있으면 어떤 엔티티도 나오지 않아 그 검증이 성립하지 않습니다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripEvent(TripEvent event) {
        repository.save(new Trip());
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
