package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.event.TripArchivedEvent;
import dev.toktokhan.invalidation.core.fixture.event.TripCompletedEvent;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import org.junit.jupiter.api.Test;

class ListenerIndexTest {

    private final FakeProgramModel program = FakeProgramModel.create()
        .withEventListener(TripEventListeners.class, "onTripEvent")
        .withEventListener(TripEventListeners.class, "onString")
        .withEventListener(TripEventListeners.class, "onArchivedByClasses");
    private final ListenerIndex listeners =
        new ListenerIndex(new ClassRepository(program), program.eventListeners());

    @Test
    void listenersFor_listenerParameterIsSupertype_matches() {
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .anySatisfy(ref -> assertThat(ref.name()).isEqualTo("onTripEvent"));
    }

    @Test
    void listenersFor_unrelatedEventType_doesNotMatch() {
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .noneSatisfy(ref -> assertThat(ref.name()).isEqualTo("onString"));
    }

    @Test
    void listenersFor_eventWithNoListener_returnsEmpty() {
        assertThat(listeners.listenersFor("java/lang/Integer")).isEmpty();
    }

    @Test
    void listenersFor_methodNotAnnotated_isNotRegistered() {
        // notAListener 는 program 에 등록하지 않았으므로 어느 이벤트에도 걸리지 않습니다.
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .noneSatisfy(ref -> assertThat(ref.name()).isEqualTo("notAListener"));
    }

    @Test
    void listenersFor_classesAttributeOnAnnotation_matchesDeclaredType() {
        // onArchivedByClasses 는 파라미터가 Object 이고, @TransactionalEventListener(classes=...)
        // 로만 이벤트 타입을 선언합니다. classes 속성을 읽어야 걸립니다.
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripArchivedEvent.class)))
            .anySatisfy(ref -> assertThat(ref.name()).isEqualTo("onArchivedByClasses"));
    }

    @Test
    void listenersFor_classesAttributePresent_parameterTypeFallbackIsNotAlsoApplied() {
        // onArchivedByClasses 의 파라미터 타입(Object)은 모든 타입의 상위 타입입니다.
        // classes 속성이 있는데도 파라미터 타입 폴백을 같이 적용하면, 관련 없는
        // TripCompletedEvent 에도 이 리스너가 걸리는 과잉이 생깁니다.
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .noneSatisfy(ref -> assertThat(ref.name()).isEqualTo("onArchivedByClasses"));
    }
}
