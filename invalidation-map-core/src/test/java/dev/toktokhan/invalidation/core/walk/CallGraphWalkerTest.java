package dev.toktokhan.invalidation.core.walk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.fixture.service.TripPort;
import dev.toktokhan.invalidation.core.fixture.service.TripPortAdapter;
import dev.toktokhan.invalidation.core.fixture.service.TripService;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class CallGraphWalkerTest {

    private static final String BASE = "dev/toktokhan/invalidation/core/fixture";

    private final FakeProgramModel program = FakeProgramModel.create()
        .withImplementation(TripPort.class, TripPortAdapter.class)
        .withEventListener(TripEventListeners.class, "onTripEvent")
        .withEventListener(TripEventListeners.class, "onArchivedByClasses");
    private final ClassRepository classes = new ClassRepository(program);
    private final CallGraphWalker walker = new CallGraphWalker(
        classes, program, new ListenerIndex(classes, program.eventListeners()),
        List.of(BASE), 20_000);

    private final List<MethodRef> seen = new ArrayList<>();
    private final Map<MethodRef, WalkState> stateAt = new ConcurrentHashMap<>();
    private final WalkVisitor visitor = (callee, state) -> {
        seen.add(callee);
        stateAt.put(callee, state);
    };

    @Test
    void walk_callThroughInterface_reachesImplementationBody() {
        walker.walk(ref("write"), visitor);
        assertThat(seen).anySatisfy(callee -> {
            assertThat(callee.owner()).isEqualTo(MethodRefs.internalNameOf(TripPortAdapter.class));
            assertThat(callee.name()).isEqualTo("deepest");
        });
    }

    @Test
    void walk_transactionalMethod_marksInTransaction() {
        walker.walk(ref("write"), visitor);
        assertThat(stateAt.get(storeOnPort()).inTransaction()).isTrue();
    }

    @Test
    void walk_nonTransactionalMethod_marksOutsideTransaction() {
        walker.walk(ref("noTransaction"), visitor);
        assertThat(stateAt.get(storeOnPort()).inTransaction()).isFalse();
    }

    @Test
    void walk_readOnlyTransaction_marksReadOnly() {
        walker.walk(ref("readOnlyWrite"), visitor);
        assertThat(stateAt.get(storeOnPort()).readOnlyTransaction()).isTrue();
    }

    @Test
    void walk_writableInnerInsideReadOnlyOuter_marksWritable() {
        walker.walk(ref("readOnlyOuter"), visitor);
        assertThat(stateAt.get(storeOnPort()).readOnlyTransaction()).isFalse();
    }

    @Test
    void walk_readOnlyInnerInsideWritableOuter_staysWritable() {
        // REQUIRED 전파로 쓰기 트랜잭션에 참여하면 readOnly 힌트가 무시될 수 있으므로
        // 억제하지 않습니다. 억제하면 쓰기를 놓칩니다.
        walker.walk(ref("writableOuter"), visitor);
        assertThat(stateAt.get(storeOnPort()).readOnlyTransaction()).isFalse();
    }

    @Test
    void walk_callInsideLambda_isFollowed() {
        walker.walk(ref("insideLambda"), visitor);
        assertThat(seen).contains(storeOnPort());
    }

    @Test
    void walk_publishEvent_followsListenerBody() {
        walker.walk(ref("publish"), visitor);
        assertThat(seen).anySatisfy(callee ->
            assertThat(callee.owner()).isEqualTo(MethodRefs.internalNameOf(TripEventListeners.class)));
    }

    @Test
    void walk_publishPojoEvent_reachesListenerNotExtendingApplicationEvent() {
        // TripArchivedEvent 는 ApplicationEvent 를 상속하지 않는 순수 POJO 입니다.
        // 이벤트 후보를 ApplicationEvent 하위로 걸러내는 구현이라면 이 리스너까지 닿지 못합니다.
        walker.walk(ref("publishArchivedEvent"), visitor);
        assertThat(seen).anySatisfy(callee -> {
            assertThat(callee.owner()).isEqualTo(MethodRefs.internalNameOf(TripEventListeners.class));
            assertThat(callee.name()).isEqualTo("onArchivedByClasses");
        });
    }

    @Test
    void walk_recursiveCalls_terminates() {
        WalkResult result = walker.walk(ref("loopA"), visitor);
        assertThat(result.budgetExceeded()).isFalse();
        assertThat(result.visitedMethods()).isLessThan(10);
    }

    @Test
    void walk_budgetTooSmall_reportsBudgetExceeded() {
        CallGraphWalker tiny = new CallGraphWalker(
            classes, program, new ListenerIndex(classes, program.eventListeners()),
            List.of(BASE), 1);
        WalkResult result = tiny.walk(ref("write"), visitor);
        assertThat(result.budgetExceeded()).isTrue();
        // write() 에서 뻗어나가는 그래프는 예산 1 보다 훨씬 큽니다. budgetExceeded() 만 보면
        // 검사 위치가 visited.size() >= nodeBudget 이든 > nodeBudget 이든 결국 true 가 되어
        // 버립니다(그래프가 예산보다 훨씬 크므로 어느 쪽이든 결국 넘칩니다). 정확히 예산만큼
        // 방문하고 멈췄는지(visitedMethods() == 1)를 함께 확인해야 검사 위치의 오차가 드러납니다.
        assertThat(result.visitedMethods()).isEqualTo(1);
    }

    @Test
    void walk_bodyOutsideBasePackages_isVisitedButNotDescended() {
        walker.walk(ref("insideLambda"), visitor);
        // JDK 호출은 방문 목록에 있지만 본문으로 내려가지 않습니다.
        assertThat(seen).anySatisfy(callee -> assertThat(callee.owner()).startsWith("java/"));
        assertThat(walker.walk(ref("insideLambda"), visitor).unresolved())
            .noneSatisfy(reason -> assertThat(reason).contains("java/util"));
    }

    private MethodRef ref(String methodName) {
        return program.ref(TripService.class, methodName);
    }

    private MethodRef storeOnPort() {
        return new MethodRef(MethodRefs.internalNameOf(TripPort.class), "store",
            "(Ljava/lang/String;)V");
    }
}
