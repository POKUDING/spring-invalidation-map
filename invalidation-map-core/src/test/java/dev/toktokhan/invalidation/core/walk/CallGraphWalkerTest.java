package dev.toktokhan.invalidation.core.walk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.fixture.service.AbstractTransactionalWorker;
import dev.toktokhan.invalidation.core.fixture.service.TripPort;
import dev.toktokhan.invalidation.core.fixture.service.TripPortAdapter;
import dev.toktokhan.invalidation.core.fixture.service.TripService;
import dev.toktokhan.invalidation.core.fixture.service.WideTripPort;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CallGraphWalkerTest {

    private static final String BASE = "dev/toktokhan/invalidation/core/fixture";

    private final FakeProgramModel program = FakeProgramModel.create()
        .withImplementation(TripPort.class, TripPortAdapter.class)
        // TripPortAdapter 는 WideTripPort 를 실제로 구현하지 않습니다(store 만 있고 close 는
        // 없음). implementationsOf 가 과잉 등록한 후보를 워커가 안전하게 거르는지 확인하는
        // 배선입니다 — walk_implementationMissingCalledMethod_doesNotReportUnresolved 참고.
        .withImplementation(WideTripPort.class, TripPortAdapter.class)
        .withEventListener(TripEventListeners.class, "onTripEvent")
        .withEventListener(TripEventListeners.class, "onArchivedByClasses");
    private final ClassRepository classes = new ClassRepository(program);
    private final CallGraphWalker walker = new CallGraphWalker(
        classes, program, new ListenerIndex(classes, program.eventListeners()),
        List.of(BASE), 20_000);

    private final List<MethodRef> seen = new ArrayList<>();
    private final Map<MethodRef, WalkState> stateAt = new LinkedHashMap<>();
    private final WalkVisitor visitor = (callee, state) -> {
        seen.add(callee);
        stateAt.put(callee, state);
    };

    @Test
    void walk_callThroughInterface_reachesImplementationBody() {
        walker.walk(ref("write"), visitor);
        assertThat(seen).contains(program.ref(TripPortAdapter.class, "deepest"));
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
        assertThat(seen).contains(program.ref(TripEventListeners.class, "onTripEvent"));
    }

    @Test
    void walk_publishPojoEvent_reachesListenerNotExtendingApplicationEvent() {
        // TripArchivedEvent 는 ApplicationEvent 를 상속하지 않는 순수 POJO 입니다.
        // 이벤트 후보를 ApplicationEvent 하위로 걸러내는 구현이라면 이 리스너까지 닿지 못합니다.
        walker.walk(ref("publishArchivedEvent"), visitor);
        assertThat(seen).contains(program.ref(TripEventListeners.class, "onArchivedByClasses"));
    }

    @Test
    void walk_publishEventWithoutNewInstruction_reportsUnresolved() {
        // republishEvent 는 파라미터로 받은 이벤트를 그대로 재발행합니다. NEW 명령이 없어
        // newTypes 가 비므로 이벤트 타입을 식별하지 못합니다. 이 한계를 조용히 넘기지 않고
        // unresolved 에 남겨야 합니다.
        WalkResult result = walker.walk(ref("republishEvent"), visitor);
        assertThat(result.unresolved()).anySatisfy(reason -> assertThat(reason).contains("republishEvent"));
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
        WalkResult result = walker.walk(ref("insideLambda"), visitor);
        // JDK 호출은 방문 목록에 있지만 본문으로 내려가지 않습니다.
        assertThat(seen).anySatisfy(callee -> assertThat(callee.owner()).startsWith("java/"));
        // 기준 패키지 밖으로 내려가면 JDK 호출 그래프가 통째로 딸려 들어와 방문 수가 폭발합니다.
        // insideLambda 의 정상 방문 집합은 insideLambda, lambda$insideLambda$0, TripPort.store,
        // TripPortAdapter.store, deepest 다섯 개뿐이므로 10 이면 충분히 여유가 있습니다.
        assertThat(result.visitedMethods()).isLessThan(10);
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void walk_sameMethodReachedWithDifferentTransactionStates_reportsEachStateSeparately() {
        // write(트랜잭션) 를 먼저, noTransaction(트랜잭션 밖) 을 나중에 부릅니다. 방문 표시가
        // 상태를 무시하면 스택(LIFO)상 나중에 push 된 noTransaction 쪽이 먼저 처리되어
        // deepest 를 tx=false 로 선점하고, 나중에 처리되는 write 쪽 상태(tx=true)는 버려집니다.
        walker.walk(ref("mixedOrder"), visitor);
        MethodRef deepest = program.ref(TripPortAdapter.class, "deepest");
        assertThat(stateAt.get(deepest).inTransaction()).isTrue();
    }

    @Test
    void walk_implementationMissingCalledMethod_doesNotReportUnresolved() {
        // implementationsOf(WideTripPort) 가 돌려주는 TripPortAdapter 에는 close() 가
        // 없습니다. 자연스러운 호출 대상(WideTripPort.close 자신, 추상 선언이라 문제없이
        // resolve 됨)은 이미 있으므로, 존재하지 않는 후보 하나 때문에 전체가 미해결로
        // 잡히면 안 됩니다. 이 단정이 실패하면 실제로 "본문을 읽을 수 없습니다:
        // .../TripPortAdapter.close()..." 가 나옵니다 — SpringProgramModel 이 리포지토리
        // 인터페이스 하나에 프래그먼트 구현체 여러 개를 걸 때 정확히 이 모양으로
        // 재현됩니다(스타터의 apiDocs_resolvedTrue_isOmitted 참고).
        WalkResult result = walker.walk(ref("closeWidely"), visitor);
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void walk_classLevelTransactionalDeclaredOnReceiverType_marksInheritedMethodInTransaction() {
        // TransactionalWorker#doWork 는 상위 클래스(AbstractTransactionalWorker)에 선언돼
        // 있어 resolveMethod 가 돌려주는 MethodFacts.ref().owner() 는 그 상위 클래스입니다.
        // 클래스 레벨 @Transactional 조회가 그 선언 클래스에서만 이뤄지면(수신 타입인
        // TransactionalWorker 를 보지 않으면) 이 어노테이션을 찾지 못합니다.
        walker.walk(ref("callTransactionalWorker"), visitor);
        MethodRef touch = program.ref(AbstractTransactionalWorker.class, "touch");
        assertThat(stateAt.get(touch).inTransaction()).isTrue();
    }

    private MethodRef ref(String methodName) {
        return program.ref(TripService.class, methodName);
    }

    private MethodRef storeOnPort() {
        return new MethodRef(MethodRefs.internalNameOf(TripPort.class), "store",
            "(Ljava/lang/String;)V");
    }
}
