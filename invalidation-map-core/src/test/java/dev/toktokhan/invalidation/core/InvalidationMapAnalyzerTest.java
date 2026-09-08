package dev.toktokhan.invalidation.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.web.TripController;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvalidationMapAnalyzerTest {

    private static final String BASE = "dev/toktokhan/invalidation/core/fixture";
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);
    private static final String COORDINATE = MethodRefs.internalNameOf(Coordinate.class);
    private static final String TRIP_JPA_REPOSITORY = MethodRefs.internalNameOf(TripJpaRepository.class);

    private final FakeProgramModel program = FakeProgramModel.create()
        .withEndpoint("GET", "/trips/{title}", TripController.class, "read", String.class)
        .withEndpoint("PUT", "/trips/{title}", TripController.class, "rename", Trip.class, String.class)
        .withEndpoint("POST", "/trips/publish", TripController.class, "publish")
        .withEndpoint("GET", "/trips/ping", TripController.class, "ping")
        .withEndpoint("GET", "/trips/health", TripController.class, "health")
        .withEndpoint("GET", "/trips/hint/{title}", TripController.class, "readWithHint", String.class)
        .withEndpoint("PUT", "/trips/override/{title}", TripController.class, "writeWithOverride",
            Trip.class, String.class)
        .withRepositoryEntity(TripJpaRepository.class, Trip.class)
        .withEntity(TripLeg.class)
        .withEntity(Coordinate.class)
        .withEventListener(TripEventListeners.class, "onTripEvent");

    private final InvalidationMapAnalyzer analyzer = new InvalidationMapAnalyzer();

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
    void analyze_readsEntitiesAnnotation_addsToAnalysisResult() {
        // 어노테이션은 TripController#readWithHint 자신이 아니라 상위 타입인 TripEndpoints
        // 인터페이스에 붙어 있습니다. 연관 확장은 끄고 annotation 병합만 봅니다.
        EndpointEntities entities = analyze(options(false)).forHandler(readWithHintRef()).orElseThrow();

        assertThat(entities.reads()).containsExactlyInAnyOrder(TRIP, COORDINATE);
        assertThat(entities.writes()).isEmpty();
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
        assertThat(entities.unresolved())
            .anySatisfy(reason -> assertThat(reason).contains("클래스를 읽지 못했습니다"));
    }

    @Test
    void analyze_nodeBudgetExceeded_becomesUnresolvedReason() {
        AnalyzerOptions tinyBudget = new AnalyzerOptions(List.of(BASE), 1, true);

        EndpointEntities entities = analyzer.analyze(program, tinyBudget)
            .forHandler(readRef()).orElseThrow();

        assertThat(entities.resolved()).isFalse();
        assertThat(entities.unresolved())
            .anySatisfy(reason -> assertThat(reason).contains("노드 예산"));
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
