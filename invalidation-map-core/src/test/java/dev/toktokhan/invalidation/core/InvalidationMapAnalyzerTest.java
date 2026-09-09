package dev.toktokhan.invalidation.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.web.TripController;
import dev.toktokhan.invalidation.core.resolve.DirtyCheckResolver;
import dev.toktokhan.invalidation.core.resolve.EntityManagerResolver;
import dev.toktokhan.invalidation.core.resolve.JpaRepositoryResolver;
import dev.toktokhan.invalidation.core.resolve.QuerydslResolver;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class InvalidationMapAnalyzerTest {

    private static final String BASE = "dev/toktokhan/invalidation/core/fixture";
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);
    private static final String COORDINATE = MethodRefs.internalNameOf(Coordinate.class);
    private static final String TRIP_JPA_REPOSITORY = MethodRefs.internalNameOf(TripJpaRepository.class);
    private static final String BRIDGE_FIRST_HANDLER =
        "dev/toktokhan/invalidation/core/fixture/synthetic/BridgeFirstHandler";
    private static final String READS_ENTITIES_DESCRIPTOR =
        "Ldev/toktokhan/invalidation/core/annotation/ReadsEntities;";

    private final FakeProgramModel program = FakeProgramModel.create()
        .withEndpoint("GET", "/trips/{title}", TripController.class, "read", String.class)
        .withEndpoint("PUT", "/trips/{title}", TripController.class, "rename", Trip.class, String.class)
        .withEndpoint("POST", "/trips/publish", TripController.class, "publish")
        .withEndpoint("GET", "/trips/ping", TripController.class, "ping")
        .withEndpoint("POST", "/trips/persist", TripController.class, "persistViaEntityManager")
        .withEndpoint("POST", "/trips/persist-opaque", TripController.class, "persistOpaqueAndRead",
            String.class, Object.class)
        .withEndpoint("GET", "/trips/health", TripController.class, "health")
        .withEndpoint("GET", "/trips/hint/{title}", TripController.class, "readWithHint", String.class)
        .withEndpoint("PUT", "/trips/override/{title}", TripController.class, "writeWithOverride",
            Trip.class, String.class)
        .withEndpoint("GET", "/trips/covariant/{title}", TripController.class,
            "readWithCovariantReturn", String.class)
        .withRepositoryEntity(TripJpaRepository.class, Trip.class)
        .withEntity(TripLeg.class)
        .withEntity(Coordinate.class)
        .withEventListener(TripEventListeners.class, "onTripEvent");

    private final InvalidationMapAnalyzer analyzer = new InvalidationMapAnalyzer();

    @Test
    void analyze_entityManagerPersist_reportsEntityAsWrite() {
        EndpointEntities entities = analyze(options(false))
            .forHandler(program.ref(TripController.class, "persistViaEntityManager")).orElseThrow();

        assertThat(entities.writes()).containsExactly(TRIP);
        assertThat(entities.resolved()).isTrue();
    }

    @Test
    void analyze_entityManagerPersistWithUnknownArgument_marksEndpointUnresolved() {
        // 이 엔드포인트에는 해석된 읽기(Trip)가 있어 "엔티티 접근을 찾지 못했습니다" 사유는
        // 붙지 않습니다. 특정하지 못한 persist 자리를 따로 표시하지 않으면 그 쓰기가 아무
        // 표시 없이 사라집니다(설계 문서 4.4절이 금지하는 방향).
        EndpointEntities entities = analyze(options(false))
            .forHandler(program.ref(TripController.class, "persistOpaqueAndRead",
                String.class, Object.class)).orElseThrow();

        assertThat(entities.reads()).containsExactly(TRIP);
        assertThat(entities.resolved()).isFalse();
        assertThat(entities.unresolved()).anySatisfy(reason -> assertThat(reason)
            .contains("엔티티를 특정하지 못했습니다").contains("persist"));
    }

    @Test
    void analyze_repositoryRead_reportsEntityAsRead() {
        // 연관 확장을 끄고 기초 분석 결과만 봅니다. 확장은 다음 테스트에서 따로 검증합니다.
        EndpointEntities entities = analyze(options(false)).forHandler(readRef()).orElseThrow();

        assertThat(entities.reads()).containsExactly(TRIP);
        assertThat(entities.writes()).isEmpty();
        // reads 는 채워지고 writes 만 비어 있어도 "엔티티 접근을 찾지 못했습니다" 사유가 붙으면
        // 안 됩니다. 그 사유는 reads·writes 가 둘 다 비었을 때만 붙어야 합니다.
        assertThat(entities.resolved()).isTrue();
    }

    @Test
    void analyze_readAssociationsExpanded_addsOneStepTargets() {
        EndpointEntities entities = analyze(options(true)).forHandler(readRef()).orElseThrow();

        assertThat(entities.reads()).containsExactlyInAnyOrder(TRIP, LEG, COORDINATE);
    }

    @Test
    void analyze_writeAssociationsNotExpanded_keepsWritesNarrow() {
        // expandReadAssociations 를 켠 상태로 확인합니다. writes 에도 잘못 적용되는 구현이라면
        // Trip 의 연관인 TripLeg·Coordinate 가 여기 섞여 들어와 실패합니다.
        EndpointEntities entities = analyze(options(true)).forHandler(renameRef()).orElseThrow();

        assertThat(entities.writes()).containsExactly(TRIP);
    }

    @Test
    void analyze_mutatorInTransaction_reportsWrite() {
        EndpointEntities entities = analyze(options(false)).forHandler(renameRef()).orElseThrow();

        assertThat(entities.writes()).containsExactly(TRIP);
        assertThat(entities.reads()).isEmpty();
        // writes 는 채워지고 reads 만 비어 있어도 미해결 사유가 붙으면 안 됩니다.
        assertThat(entities.resolved()).isTrue();
    }

    @Test
    void analyze_eventListenerReached_includesListenerEntities() {
        // publish() 는 이벤트를 발행할 뿐 리포지토리를 직접 부르지 않습니다. Trip 이 나오려면
        // 워커가 발행 지점을 지나 TripEventListeners#onTripEvent 본문까지 도달해야 합니다.
        EndpointEntities entities = analyze(options(true)).forHandler(publishRef()).orElseThrow();

        assertThat(entities.writes()).containsExactly(TRIP);
        assertThat(entities.reads()).isEmpty();
    }

    @Test
    void analyze_noEntityAccess_marksUnresolved() {
        EndpointEntities entities = analyze(options(true)).forHandler(pingRef()).orElseThrow();

        assertThat(entities.reads()).isEmpty();
        assertThat(entities.writes()).isEmpty();
        assertThat(entities.resolved()).isFalse();
        assertThat(entities.unresolved()).containsExactly("엔티티 접근을 찾지 못했습니다");
    }

    @Test
    void analyze_ignoredHandler_isAbsentFromMap() {
        InvalidationMap map = analyze(options(true));

        assertThat(map.forHandler(healthRef())).isEmpty();
        assertThat(map.byHandler()).doesNotContainKey(healthRef());
    }

    @Test
    void analyze_readsEntitiesOnSupertype_isFound() {
        // 어노테이션은 TripController#readWithHint 자신이 아니라 상위 타입인 TripEndpoints
        // 인터페이스에 붙어 있습니다. 상위 타입 탐색 경로 자체를 봅니다.
        EndpointEntities entities = analyze(options(false)).forHandler(readWithHintRef()).orElseThrow();

        assertThat(entities.reads()).containsExactlyInAnyOrder(TRIP, COORDINATE);
    }

    @Test
    void analyze_readsEntitiesWithoutOverride_addsToAnalysisResult() {
        // override 를 안 쓰면 분석 결과(Trip)에 어노테이션 값(Coordinate)이 더해집니다.
        // 연관 확장은 끄고 병합 자체만 봅니다.
        EndpointEntities entities = analyze(options(false)).forHandler(readWithHintRef()).orElseThrow();

        assertThat(entities.reads()).containsExactlyInAnyOrder(TRIP, COORDINATE);
        assertThat(entities.writes()).isEmpty();
    }

    @Test
    void analyze_readsEntitiesOnCovariantReturnSupertype_isFound() {
        // TripEndpoints#readWithCovariantReturn 은 Object 를 반환하지만 TripController 는
        // Trip 으로 좁혀 재정의합니다(공변 반환). 반환 타입이 다르면 디스크립터 전체가
        // 달라지므로, 어노테이션 탐색이 이름·파라미터만 비교해야 이 어노테이션을 찾습니다.
        EndpointEntities entities = analyze(options(false))
            .forHandler(readWithCovariantReturnRef()).orElseThrow();

        assertThat(entities.reads()).containsExactlyInAnyOrder(TRIP, COORDINATE);
    }

    @Test
    void analyze_readsEntitiesOnOwnMethodWithBridgeFirst_isStillFound() {
        // 공변 반환 재정의를 컴파일하면 컴파일러가 synthetic 브릿지 메서드를 실제 메서드와
        // 함께 만듭니다 — 이름·파라미터가 같고 반환 타입만 다른 메서드 쌍이 같은 클래스에
        // 생깁니다. 이 저장소의 javac 는 실제 메서드를 브릿지보다 먼저 배치하지만(위
        // readWithCovariantReturn 테스트가 통과하는 이유), 그 순서는 JVMS 에 없는 컴파일러
        // 구현 세부사항이라 javac 로 컴파일한 픽스처로는 "브릿지가 먼저 오면 annotationOn
        // 이 어떻게 반응하는가" 를 결정적으로 재현할 수 없습니다 — 어느 javac 버전을 쓰든
        // 우리가 순서를 고를 수 없기 때문입니다. 그래서 ASM ClassWriter 로 브릿지를 실제
        // 메서드보다 먼저 visitMethod 하는 클래스를 직접 합성해, 컴파일러 버전과 무관하게
        // 이 경로를 결정적으로 재현합니다. 어노테이션은 실제 메서드에만 붙입니다 — 브리프가
        // 지원을 약속하는 "핸들러 자신" 탐색 경로를 검증합니다(인터페이스가 아님).
        byte[] classBytes = synthesizeBridgeFirstClass();
        MethodRef handler = new MethodRef(BRIDGE_FIRST_HANDLER, "read",
            "(Ljava/lang/String;)L" + TRIP + ";");
        ProgramModel synthetic = new SingleClassProgramModel(
            List.of(new Endpoint("GET", "/synthetic", handler)),
            Map.of(BRIDGE_FIRST_HANDLER, classBytes));

        EndpointEntities entities = analyzer.analyze(synthetic, options(false))
            .forHandler(handler).orElseThrow();

        assertThat(entities.reads()).containsExactly(COORDINATE);
    }

    @Test
    void analyze_overrideAnnotation_replacesAnalysisResult() {
        // 브리프 표는 이 기대값을 {Trip} 으로 적었지만, 픽스처(TripController#writeWithOverride)는
        // @WritesEntities(value = TripLeg.class, override = true) 를 씁니다. 분석이 실제로
        // 찾아내는 엔티티는 Trip(더티체킹으로 trip.reset(...) 이 rename·setDistance 를 호출)인데,
        // 기대값을 Trip 으로 두면 override 의 "대체" 와 기본값인 "추가" 를 구분할 수 없습니다.
        // 그래서 기대값을 픽스처와 일치하는 {TripLeg} 로 둡니다.
        EndpointEntities entities = analyze(options(true)).forHandler(writeWithOverrideRef()).orElseThrow();

        assertThat(entities.writes()).containsExactly(LEG);
        assertThat(entities.reads()).isEmpty();
    }

    @Test
    void analyze_outputSetsAreSorted_producesStableOrder() {
        InvalidationMap first = analyze(options(true));
        InvalidationMap second = analyze(options(true));

        List<String> firstReads = new ArrayList<>(first.forHandler(readRef()).orElseThrow().reads());
        List<String> secondReads = new ArrayList<>(second.forHandler(readRef()).orElseThrow().reads());
        assertThat(firstReads).containsExactlyElementsOf(secondReads);
        // 두 번 실행한 결과가 같다는 것만으로는 정렬을 증명하지 못합니다(결정적이기만 해도
        // 통과합니다). 정렬 자체를 직접 확인합니다.
        assertThat(firstReads).containsExactly(COORDINATE, TRIP, LEG);
    }

    @Test
    void analyze_unreadableClass_becomesUnresolvedReason() {
        // TripJpaRepository 를 읽지 못하게 만듭니다. read/readWithHint 가 이 클래스를 건드리므로
        // ClassRepository.unreadableClasses() 가 채워지고, 그 사유는 이 클래스와 무관한 rename
        // 에도 전역으로 붙어야 합니다(설계: "ASM 이 읽지 못한 클래스는 전체에 영향을 줍니다").
        ProgramModel broken = new ThrowingClassBytesProgramModel(program, TRIP_JPA_REPOSITORY);

        EndpointEntities entities = analyzer.analyze(broken, options(true))
            .forHandler(renameRef()).orElseThrow();

        assertThat(entities.resolved()).isFalse();
        // rename 자체는 다른 미해결 사유가 없으므로(writes={Trip} 로 채워짐), 원소 개수를
        // 먼저 고정해 여분이 섞여도 통과하는 단정을 막습니다.
        assertThat(entities.unresolved()).singleElement()
            .asString().contains("클래스를 읽지 못했습니다");
    }

    @Test
    void analyze_multipleUnresolvedReasons_areSortedTogether() {
        // publish 핸들러에 nodeBudget=1 을 주면 TripController#publish 프레임까지만 방문하고
        // TripService#publish 본문(이벤트 발행 지점)으로는 못 내려갑니다. reads/writes 가
        // 모두 비고("엔티티 접근을 찾지 못했습니다") 예산도 초과("호출 사슬이 노드 예산 1
        // 을 넘었습니다") 해 사유가 둘 붙습니다. 정렬된 순서로 나오는지 확인합니다 —
        // analyzeEndpoint 안의 unresolved.sort(...) 를 지우면 이 단정이 깨집니다.
        //
        // 전역 unreadable-클래스 경로(ThrowingClassBytesProgramModel)를 대신 썼다면 안 됩니다
        // — 그 경로는 withReasons 에서 다시 정렬하므로, analyzeEndpoint 자체의 정렬 한 줄이
        // 지워져도 그 재정렬에 가려 이 테스트가 통과해 버립니다.
        AnalyzerOptions tinyBudget = new AnalyzerOptions(List.of(BASE), 1, true);

        EndpointEntities entities = analyzer.analyze(program, tinyBudget)
            .forHandler(publishRef()).orElseThrow();

        assertThat(entities.unresolved()).containsExactly(
            "엔티티 접근을 찾지 못했습니다", "호출 사슬이 노드 예산 1 을 넘었습니다");
    }

    @Test
    void analyze_nodeBudgetExceeded_becomesUnresolvedReason() {
        AnalyzerOptions tinyBudget = new AnalyzerOptions(List.of(BASE), 1, true);

        EndpointEntities entities = analyzer.analyze(program, tinyBudget)
            .forHandler(readRef()).orElseThrow();

        assertThat(entities.resolved()).isFalse();
        // read 의 reads 는 예산 초과와 무관하게 {Trip} 으로 채워지므로(CallGraphWalker 가
        // 예산을 확인하기 전에 시작 프레임의 호출들을 방문자에게 먼저 넘김), 이 케이스의
        // 미해결 사유는 예산 사유 단 하나입니다.
        assertThat(entities.unresolved()).containsExactly("호출 사슬이 노드 예산 1 을 넘었습니다");
    }

    @Test
    void analyze_handlerMappedToMultiplePaths_appearsOnceInMap() {
        // 한 핸들러에 경로/HTTP 메서드가 여러 개 붙어도(InvalidationMap javadoc 이 드는
        // 설계 근거) 결과 맵 항목은 하나여야 합니다.
        FakeProgramModel multiPath = FakeProgramModel.create()
            .withEndpoint("GET", "/a", TripController.class, "ping")
            .withEndpoint("POST", "/b", TripController.class, "ping");

        InvalidationMap map = analyzer.analyze(multiPath, options(true));

        assertThat(map.byHandler()).hasSize(1);
    }

    @Test
    void resolverOrder_isFixedByDesignDoc42_firstMatchWinsChainOrder() {
        // 네 리졸버가 callee.owner() 기준으로 서로소라 지금은 순서가 결과를 바꾸지
        // 않습니다(행동 기반 테스트로는 이 계약을 지킬 수 없음). 그래서 상수 자체를
        // 단정합니다 — 재배열하거나 겹치는 리졸버가 끼어들면 이 테스트가 즉시 깨집니다.
        assertThat(InvalidationMapAnalyzer.resolverOrder()).containsExactly(
            JpaRepositoryResolver.class, EntityManagerResolver.class,
            QuerydslResolver.class, DirtyCheckResolver.class);
    }

    private InvalidationMap analyze(AnalyzerOptions options) {
        return analyzer.analyze(program, options);
    }

    private static AnalyzerOptions options(boolean expandReadAssociations) {
        return new AnalyzerOptions(List.of(BASE), 20_000, expandReadAssociations);
    }

    private MethodRef readRef() {
        return program.ref(TripController.class, "read", String.class);
    }

    private MethodRef renameRef() {
        return program.ref(TripController.class, "rename", Trip.class, String.class);
    }

    private MethodRef publishRef() {
        return program.ref(TripController.class, "publish");
    }

    private MethodRef pingRef() {
        return program.ref(TripController.class, "ping");
    }

    private MethodRef healthRef() {
        return program.ref(TripController.class, "health");
    }

    private MethodRef readWithHintRef() {
        return program.ref(TripController.class, "readWithHint", String.class);
    }

    private MethodRef writeWithOverrideRef() {
        return program.ref(TripController.class, "writeWithOverride", Trip.class, String.class);
    }

    private MethodRef readWithCovariantReturnRef() {
        return program.ref(TripController.class, "readWithCovariantReturn", String.class);
    }

    /**
     * 브릿지 메서드를 실제 메서드보다 먼저 {@code visitMethod} 하는 클래스를 직접 합성합니다.
     *
     * <p>{@code read(String)} 의 실제 메서드는 {@code Trip} 을 반환하고
     * {@code @ReadsEntities(Coordinate.class)} 를 갖습니다. 브릿지는 상위 타입의 소거된
     * 시그니처를 흉내 내(반환 타입 {@code Object}) 이름·파라미터가 실제 메서드와 같지만,
     * 실제로 어떤 상위 타입을 구현하지는 않습니다 — {@code annotationOn} 이 상속 관계가
     * 아니라 같은 클래스 안의 메서드 목록 순서만으로 후보를 고르는지 보는 것이 목적이라
     * 상속 관계 자체는 필요 없습니다.
     */
    private static byte[] synthesizeBridgeFirstClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BRIDGE_FIRST_HANDLER, null,
            "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        // 브릿지를 먼저 방문합니다. 어노테이션은 없습니다.
        MethodVisitor bridge = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC,
            "read", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        bridge.visitCode();
        bridge.visitInsn(Opcodes.ACONST_NULL);
        bridge.visitInsn(Opcodes.ARETURN);
        bridge.visitMaxs(1, 2);
        bridge.visitEnd();

        // 실제 메서드를 나중에 방문합니다. 어노테이션은 여기에만 붙습니다.
        MethodVisitor real = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "read", "(Ljava/lang/String;)L" + TRIP + ";", null, null);
        AnnotationVisitor annotation = real.visitAnnotation(READS_ENTITIES_DESCRIPTOR, true);
        AnnotationVisitor value = annotation.visitArray("value");
        value.visit(null, Type.getObjectType(COORDINATE));
        value.visitEnd();
        annotation.visitEnd();
        real.visitCode();
        real.visitInsn(Opcodes.ACONST_NULL);
        real.visitInsn(Opcodes.ARETURN);
        real.visitMaxs(1, 2);
        real.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * {@code classesByName} 에 등록된 바이트만 내주는 최소 {@link ProgramModel} 입니다.
     * {@link #synthesizeBridgeFirstClass()} 처럼 실제 컴파일 산출물이 아닌, 직접 합성한
     * 클래스를 분석기에 먹이는 데 씁니다.
     */
    private record SingleClassProgramModel(List<Endpoint> endpoints, Map<String, byte[]> classesByName)
        implements ProgramModel {

        @Override
        public Optional<byte[]> classBytes(String internalName) {
            return Optional.ofNullable(classesByName.get(internalName));
        }

        @Override
        public Optional<String> entityFor(String repositoryInternalName) {
            return Optional.empty();
        }

        @Override
        public Set<String> implementationsOf(String interfaceInternalName) {
            return Set.of();
        }

        @Override
        public Set<String> entities() {
            return Set.of();
        }

        @Override
        public Set<MethodRef> eventListeners() {
            return Set.of();
        }
    }

    /**
     * 지정한 클래스 하나만 읽기를 실패시키는 {@link ProgramModel} 래퍼입니다.
     * {@code ClassRepository.facts} 가 {@code RuntimeException} 을 잡아 사유로 남기는 경로를
     * 검증하는 데 씁니다.
     */
    private record ThrowingClassBytesProgramModel(ProgramModel delegate, String brokenInternalName)
        implements ProgramModel {

        @Override
        public List<Endpoint> endpoints() {
            return delegate.endpoints();
        }

        @Override
        public Optional<byte[]> classBytes(String internalName) {
            if (internalName.equals(brokenInternalName)) {
                throw new UncheckedIOException(new IOException("의도적으로 깨진 클래스입니다: " + internalName));
            }
            return delegate.classBytes(internalName);
        }

        @Override
        public Optional<String> entityFor(String repositoryInternalName) {
            return delegate.entityFor(repositoryInternalName);
        }

        @Override
        public Set<String> implementationsOf(String interfaceInternalName) {
            return delegate.implementationsOf(interfaceInternalName);
        }

        @Override
        public Set<String> entities() {
            return delegate.entities();
        }

        @Override
        public Set<MethodRef> eventListeners() {
            return delegate.eventListeners();
        }
    }
}
