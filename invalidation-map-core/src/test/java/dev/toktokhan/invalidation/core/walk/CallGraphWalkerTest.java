package dev.toktokhan.invalidation.core.walk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.fixture.service.AbstractTransactionalWorker;
import dev.toktokhan.invalidation.core.fixture.service.AncestorBase;
import dev.toktokhan.invalidation.core.fixture.service.AncestorImpl;
import dev.toktokhan.invalidation.core.fixture.service.AncestorPort;
import dev.toktokhan.invalidation.core.fixture.service.GhostPort;
import dev.toktokhan.invalidation.core.fixture.service.TripPort;
import dev.toktokhan.invalidation.core.fixture.service.TripPortAdapter;
import dev.toktokhan.invalidation.core.fixture.service.TripService;
import dev.toktokhan.invalidation.core.fixture.service.WideTripPort;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.HidingProgramModel;
import dev.toktokhan.invalidation.core.support.SyntheticClassProgramModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CallGraphWalkerTest {

    private static final String BASE = "dev/toktokhan/invalidation/core/fixture";
    private static final String JTA_SERVICE = BASE + "/synthetic/JtaService";

    private final FakeProgramModel program = FakeProgramModel.create()
        .withImplementation(TripPort.class, TripPortAdapter.class)
        // TripPortAdapter 는 WideTripPort 를 실제로 구현하지 않습니다(store 만 있고 close 는
        // 없음). implementationsOf 가 과잉 등록한 후보를 워커가 안전하게 거르는지 확인하는
        // 배선입니다 — walk_implementationMissingCalledMethod_doesNotReportUnresolved 참고.
        .withImplementation(WideTripPort.class, TripPortAdapter.class)
        // GhostPortAdapter 는 실제로 컴파일된 적 없는 이름입니다 — 클래스 바이트를 구할 수
        // 없습니다. walk_implementationClassCannotBeRead_reportsUnresolved 참고.
        .withUnreadableImplementation(GhostPort.class, BASE + "/service/GhostPortAdapter")
        // AncestorImpl 자신은 정상적으로 읽힙니다. walk_implementationAncestorCannotBeRead_
        // reportsUnresolved 가 이 테스트 전용 ClassRepository 에서만 AncestorBase(실제로
        // store() 를 구현하는 상위 클래스)를 못 읽게 감쌉니다.
        .withImplementation(AncestorPort.class, AncestorImpl.class)
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

    /**
     * {@code descendTargets} 의 조건 {@code resolveMethod(candidate).isPresent() ||
     * !isFullyReadable(implementation)} 중 뒤쪽({@code !isFullyReadable}) 이 거짓이 되는
     * 경우입니다 — {@code TripPortAdapter} 자신과 그 상위 타입({@code TripPort}) 이 모두
     * 읽히므로 {@code isFullyReadable} 은 참이고, {@code close()} 가 어디에도 없으니
     * {@code resolveMethod} 도 실패해 전체 조건이 거짓입니다(안전하게 걸러짐).
     *
     * <p>{@code resolveMethod(candidate).isPresent()} 쪽(정상적으로 구현을 찾는 경로)은
     * 이 테스트가 아니라 {@link #walk_callThroughInterface_reachesImplementationBody} 와
     * {@link #walk_sameMethodReachedWithDifferentTransactionStates_reportsEachStateSeparately}
     * 가 지킵니다 — {@code TripPortAdapter.store()} 가 실제로 존재해 워커가 그 본문
     * ({@code deepest()})까지 내려가는 것을 확인하기 때문입니다. 이 파일의 테스트 셋을
     * 세 갈래로 나누면: 이 테스트(사슬 전체가 읽혔고 메서드 없음 → 거름),
     * {@link #walk_implementationClassCannotBeRead_reportsUnresolved}(후보 자신을 못
     * 읽음 → 안 거름), {@link #walk_implementationAncestorCannotBeRead_reportsUnresolved}
     * (후보 자신은 읽히지만 상위 타입을 못 읽음 → 안 거름)입니다(재검토 라운드2가 "두
     * 테스트가 정확히 두 분기를 지킨다"는 서술이 부정확하다고 지적해 바로잡았습니다).
     */
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
    void walk_implementationClassCannotBeRead_reportsUnresolved() {
        // GhostPortAdapter 는 실제로 컴파일된 적 없는 이름입니다 — 클래스 바이트를 구할 수
        // 없습니다(program.classBytes 가 예외 없이 빈 값을 돌려줌). 이 후보가 이 호출과
        // 무관한지 판단할 수 없으므로, 메서드가 없어서 걸러지는 경우(위 테스트)와 달리
        // 조용히 넘어가면 안 됩니다 — resolveMethod 실패의 두 원인(메서드 없음 vs 클래스
        // 자체를 못 읽음)을 구분하지 못하면 이 접근도 조용히 사라집니다(라운드 2 재검토가
        // 발견한 회귀, TempGhostCandidateProbe 로 재현됨).
        WalkResult result = walker.walk(ref("vanish"), visitor);
        assertThat(result.unresolved()).anySatisfy(
            reason -> assertThat(reason).contains("GhostPortAdapter"));
    }

    @Test
    void walk_implementationAncestorCannotBeRead_reportsUnresolved() {
        // AncestorImpl(구현체 후보 자신)은 정상적으로 읽히지만, store() 를 실제로
        // 구현하는 상위 클래스(AncestorBase)는 이 테스트에서만 못 읽게 감쌉니다.
        // isFullyReadable 이 후보 자신의 가독성만 봤다면(라운드2 수정) 이 경우를
        // "사슬 전체를 읽었는데 메서드가 없음"(안전, 무관)으로 오분류해 조용히
        // 걸렀을 것입니다 — resolveMethod 가 실제로 훑는 범위(후보 자신 + 상위 타입
        // 전체)와 어긋나기 때문입니다. 라운드3 재검토가 발견한 회귀이며, isFullyReadable
        // 이 supertypesOf 전체를 확인하도록 고쳐서 막았습니다.
        ClassRepository hidingAncestorBase = new ClassRepository(
            new HidingProgramModel(program, MethodRefs.internalNameOf(AncestorBase.class)));
        CallGraphWalker walkerWithHiddenAncestor = new CallGraphWalker(hidingAncestorBase, program,
            new ListenerIndex(hidingAncestorBase, program.eventListeners()), List.of(BASE), 20_000);

        WalkResult result = walkerWithHiddenAncestor.walk(ref("storeViaAncestor"), visitor);
        assertThat(result.unresolved()).isNotEmpty();
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

    @Test
    void walk_publishFactoryCreatedEvent_reachesListener() {
        // publishFromFactory 는 이벤트를 정적 팩터리에서 받아 발행하면서, 같은 메서드가
        // 이벤트와 무관한 StringBuilder 도 NEW 합니다. 이벤트 후보를 newTypes() 로만 잡으면
        // 후보는 StringBuilder 뿐이라 리스너로 이어지지 않습니다. 팩터리 호출 디스크립터의
        // 반환 타입까지 후보로 봐야 닿습니다.
        walker.walk(ref("publishFromFactory"), visitor);
        assertThat(seen).contains(program.ref(TripEventListeners.class, "onTripEvent"));
    }

    @Test
    void walk_publishFieldHeldEvent_reachesListener() {
        // 이벤트는 GETFIELD 로 읽은 필드의 선언 타입에만 나타납니다.
        walker.walk(ref("publishHeldEvent"), visitor);
        assertThat(seen).contains(program.ref(TripEventListeners.class, "onArchivedByClasses"));
    }

    @Test
    void walk_publishParameterTypedEvent_reachesListener() {
        // 이벤트는 발행 메서드 자신의 파라미터 타입에만 나타납니다.
        walker.walk(ref("republishTypedEvent"), visitor);
        assertThat(seen).contains(program.ref(TripEventListeners.class, "onTripEvent"));
    }

    @Test
    void walk_publishUnknownEventWithUnrelatedNew_reportsUnresolved() {
        // republishUnknownWithUnrelatedNew 는 Object 파라미터로 받은 이벤트를 발행하면서
        // 무관한 객체를 NEW 합니다. newTypes() 가 비지 않으므로 "NEW 가 아예 없음" 가드는
        // 이 경우를 놓쳐, 후보는 엉뚱한 타입뿐인데 미해결 표시도 붙지 않습니다 —
        // 리스너 사슬이 조용히 빠집니다.
        WalkResult result = walker.walk(ref("republishUnknownWithUnrelatedNew"), visitor);
        assertThat(result.unresolved()).anySatisfy(reason ->
            assertThat(reason).contains("republishUnknownWithUnrelatedNew"));
    }

    @Test
    void walk_publishIdentifiedEvent_doesNotReportUnresolved() {
        // 대조군입니다. 이벤트 타입을 식별한 발행은 미해결 사유를 만들면 안 됩니다 —
        // 모든 publishEvent 에 무조건 사유를 붙이는 구현은 여기서 걸립니다.
        WalkResult result = walker.walk(ref("publish"), visitor);
        assertThat(result.unresolved()).noneSatisfy(reason ->
            assertThat(reason).contains("이벤트 타입을 식별하지 못했습니다"));
    }

    @Test
    void walk_basePackagePrefixWithoutPathBoundary_doesNotDescend() {
        // 기준 패키지를 ".../fixture/serv" 로 둡니다 — ".../fixture/service" 의 접두사이지만
        // 경로 경계가 아닙니다. 단순 startsWith 로 비교하는 구현은 이 이름을 기준 패키지
        // 안으로 잘못 보고 구현체 본문까지 내려갑니다.
        CallGraphWalker offBoundary = new CallGraphWalker(
            classes, program, new ListenerIndex(classes, program.eventListeners()),
            List.of(BASE + "/serv"), 20_000);

        WalkResult result = offBoundary.walk(ref("write"), visitor);

        assertThat(seen).doesNotContain(program.ref(TripPortAdapter.class, "deepest"));
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void walk_basePackageExactMatch_descends() {
        // 대조군입니다. 기준 패키지가 호출 대상의 이름과 정확히 같아도 내려가야 합니다 —
        // 경계 검사를 "접두사 다음이 / 여야 한다" 로만 두고 정확히 같은 경우를 빼먹으면
        // 그 이름 자체로 지정한 기준이 통째로 무동작이 됩니다.
        //
        // 단정 대상이 storeOnPort() 가 아니라 구현체 본문의 호출인 이유: 워커는 만난 호출
        // 지점을 기준 패키지와 무관하게 전부 방문자에게 넘기므로(onCall), storeOnPort() 는
        // 내려가지 않아도 seen 에 들어옵니다. 실제로 내려갔는지는 구현체 본문에서만
        // 보이는 호출로 확인해야 합니다.
        CallGraphWalker exact = new CallGraphWalker(
            classes, program, new ListenerIndex(classes, program.eventListeners()),
            List.of(MethodRefs.internalNameOf(TripPort.class)), 20_000);

        exact.walk(ref("write"), visitor);

        assertThat(seen).contains(program.ref(TripPortAdapter.class, "deepest"));
    }

    /**
     * JTA 의 {@code @Transactional} 도 트랜잭션 경계로 인정하는지 확인합니다.
     *
     * <p>이 자리는 지금까지 어떤 테스트도 밟지 않았습니다(뮤테이션으로 확인: 워커의
     * 디스크립터 목록에서 {@code Ljakarta/transaction/Transactional;} 을 지워도 전체가
     * GREEN 이었습니다). 원장은 "테스트 클래스패스에 jakarta.transaction-api 가 없어 새
     * 의존성이 필요하다" 는 이유로 미뤘지만, 새 의존성은 필요하지 않습니다 — 코어는
     * 어노테이션을 디스크립터 문자열로만 다루므로 ASM 으로 그 문자열을 직접 써 넣은
     * 클래스를 합성하면 같은 경로를 밟습니다. 이 저장소는 이미 브릿지 메서드 순서를
     * 재현할 때 같은 기법을 씁니다.
     */
    @Test
    void walk_jakartaTransactionalAnnotation_marksInTransaction() {
        MethodRef handler = new MethodRef(JTA_SERVICE, "store", "(Ljava/lang/String;)V");
        ProgramModel withSynthetic = new SyntheticClassProgramModel(
            program, Map.of(JTA_SERVICE, synthesizeJtaTransactionalClass()));
        ClassRepository syntheticClasses = new ClassRepository(withSynthetic);
        CallGraphWalker syntheticWalker = new CallGraphWalker(syntheticClasses, withSynthetic,
            new ListenerIndex(syntheticClasses, withSynthetic.eventListeners()),
            List.of(BASE), 20_000);

        syntheticWalker.walk(handler, visitor);

        assertThat(stateAt.get(storeOnPort()).inTransaction()).isTrue();
    }

    /**
     * {@code @jakarta.transaction.Transactional} 이 붙은 메서드 하나를 가진 클래스를
     * 합성합니다. 본문은 {@link TripPort#store} 를 부르므로, 그 호출 지점의 워커 상태로
     * 트랜잭션 판정이 드러납니다.
     */
    private static byte[] synthesizeJtaTransactionalClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, JTA_SERVICE, null, "java/lang/Object", null);

        MethodVisitor store = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "store", "(Ljava/lang/String;)V", null, null);
        store.visitAnnotation("Ljakarta/transaction/Transactional;", true).visitEnd();
        store.visitCode();
        store.visitInsn(Opcodes.ACONST_NULL);
        store.visitVarInsn(Opcodes.ALOAD, 1);
        store.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            MethodRefs.internalNameOf(TripPort.class), "store", "(Ljava/lang/String;)V", true);
        store.visitInsn(Opcodes.RETURN);
        store.visitMaxs(2, 2);
        store.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private MethodRef ref(String methodName) {
        return program.ref(TripService.class, methodName);
    }

    private MethodRef storeOnPort() {
        return new MethodRef(MethodRefs.internalNameOf(TripPort.class), "store",
            "(Ljava/lang/String;)V");
    }
}
