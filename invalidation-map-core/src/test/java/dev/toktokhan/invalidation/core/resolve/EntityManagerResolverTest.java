package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.repo.EntityManagerRepository;
import dev.toktokhan.invalidation.core.fixture.repo.ExtendedEntityManager;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.StubResolutionContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntityManagerResolverTest {

    private static final String ENTITY_MANAGER = "jakarta/persistence/EntityManager";
    private static final String EXTENDED_ENTITY_MANAGER = MethodRefs.internalNameOf(ExtendedEntityManager.class);
    private static final String REPO = MethodRefs.internalNameOf(EntityManagerRepository.class);
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);

    private final FakeProgramModel program = FakeProgramModel.create()
        .withEntity(Trip.class)
        .withEntity(TripLeg.class);
    private final ClassRepository classes = new ClassRepository(program);
    private final EntityIndex entities = new EntityIndex(classes, Set.of(TRIP, LEG));
    private final RepositoryIndex repositories = new RepositoryIndex(program, classes);
    private final EntityManagerResolver resolver = new EntityManagerResolver();

    @Test
    void resolve_createNativeQueryInsert_isWriteOnMappedEntity() {
        assertThat(resolveIn("upsert",
            new MethodRef(ENTITY_MANAGER, "createNativeQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    @Test
    void resolve_createQueryUpdate_isWrite() {
        assertThat(resolveIn("bulkRename",
            new MethodRef(ENTITY_MANAGER, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    @Test
    void resolve_createQuerySelect_isRead() {
        assertThat(resolveIn("load",
            new MethodRef(ENTITY_MANAGER, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_persistInCallerWithUnrelatedQueryStrings_usesTypeCandidatesNotQueryStrings() {
        // upsert 의 네이티브 SQL 은 Trip 으로 풀리지만, persist 는 쿼리 문자열이 아니라
        // 타입 후보(NEW·파라미터·클래스 리터럴·필드 타입)로 판정해야 합니다. upsert 에는
        // 그런 후보가 없으므로 특정할 수 없다고 표시되어야 합니다 — 쿼리 문자열을 재활용해
        // Trip 을 돌려주면 persist 하지도 않은 엔티티를 쓰기로 보고하게 됩니다.
        Optional<EntityAccess> access = resolveIn("upsert",
            new MethodRef(ENTITY_MANAGER, "persist", "(Ljava/lang/Object;)V"));
        assertThat(access).isPresent();
        assertThat(access.get().isIdentified()).isFalse();
    }

    @Test
    void resolve_notEntityManager_returnsEmpty() {
        assertThat(resolveIn("upsert",
            new MethodRef("java/lang/String", "trim", "()Ljava/lang/String;"))).isEmpty();
    }

    @Test
    void resolve_callerWithMultipleQueryStrings_unionsAllResolvedEntities() {
        // 한 메서드가 쿼리 문자열을 두 개 담고 있으면(Trip, TripLeg 각각) 그 방향과 무관하게
        // 둘 다 결과에 반영되어야 합니다(과잉 방향). 첫 문자열만 쓰는 구현에서는 TripLeg 가
        // 빠져 이 단정이 깨집니다.
        assertThat(resolveIn("queryTwoEntities",
            new MethodRef(ENTITY_MANAGER, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;")))
            .contains(new EntityAccess(Set.of(TRIP, LEG), AccessKind.READ));
    }

    @Test
    void resolve_callerWithMixedReadAndWriteQueries_promotesToWrite() {
        // 후보 중 하나(select)는 READ, 다른 하나(update)는 WRITE 입니다. 하나라도 WRITE 면
        // 전체가 WRITE 로 승격되어야 합니다. 마지막 후보만 반영하거나 첫 후보의 방향을
        // 그대로 쓰는 구현에서는 이 단정이 깨집니다.
        assertThat(resolveIn("queryMixedDirections",
            new MethodRef(ENTITY_MANAGER, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;")))
            .contains(new EntityAccess(Set.of(TRIP, LEG), AccessKind.WRITE));
    }

    @Test
    void resolve_entityManagerSubtype_isTreatedAsEntityManager() {
        // owner 가 jakarta/persistence/EntityManager 리터럴이 아니라 그 서브타입
        // (ExtendedEntityManager)이어도 isSubtypeOf 로 인정되어야 합니다. owner 리터럴
        // equals 만 하는 구현에서는 이 단정이 깨집니다.
        assertThat(resolveIn("load",
            new MethodRef(EXTENDED_ENTITY_MANAGER, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_persistNewEntity_isWriteOnThatEntity() {
        // persist 의 디스크립터는 (Ljava/lang/Object;)V 라 호출 지점만으로는 엔티티를 알 수
        // 없습니다. 호출을 담은 메서드가 NEW 로 만든 타입에서 후보를 얻어야 합니다.
        assertThat(resolveIn("persistNew", persist()))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    @Test
    void resolve_persistParameterEntity_isWriteOnThatEntity() {
        // NEW 도 클래스 리터럴도 없고, 엔티티는 호출을 담은 메서드의 파라미터 타입에만
        // 나타납니다.
        assertThat(resolveIn("persistParameter", persist()))
            .contains(new EntityAccess(Set.of(LEG), AccessKind.WRITE));
    }

    @Test
    void resolve_persistFieldEntity_isWriteOnThatEntity() {
        // 엔티티는 GETFIELD 로 읽은 필드의 선언 타입에만 나타납니다.
        assertThat(resolveIn("persistField", persist()))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    @Test
    void resolve_mergeAndRemove_areWrites() {
        assertThat(resolveIn("mergeAndRemove",
            new MethodRef(ENTITY_MANAGER, "merge", "(Ljava/lang/Object;)Ljava/lang/Object;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
        assertThat(resolveIn("mergeAndRemove",
            new MethodRef(ENTITY_MANAGER, "remove", "(Ljava/lang/Object;)V")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    @Test
    void resolve_findWithClassLiteral_isReadOnThatEntity() {
        // em.find(Trip.class, id) 의 첫 인자는 LDC 로 실린 클래스 상수입니다.
        assertThat(resolveIn("findById",
            new MethodRef(ENTITY_MANAGER, "find",
                "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_getReferenceWithClassLiteral_isReadOnThatEntity() {
        assertThat(resolveIn("reference",
            new MethodRef(ENTITY_MANAGER, "getReference",
                "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;")))
            .contains(new EntityAccess(Set.of(LEG), AccessKind.READ));
    }

    @Test
    void resolve_persistWithNoEntityTypeInCaller_isUnidentifiedWrite() {
        // 엔티티 타입이 호출을 담은 메서드의 어디에도 나타나지 않습니다. 빈 Optional 로
        // 조용히 버리면 분석기가 아무 표시도 남기지 않으므로(4.4 원칙 위반), 엔티티 없는
        // EntityAccess 로 "담당은 맞지만 특정하지 못했다"를 알려야 합니다.
        Optional<EntityAccess> access = resolveIn("persistOpaque", persist());
        assertThat(access).isPresent();
        assertThat(access.get().isIdentified()).isFalse();
        assertThat(access.get().kind()).isEqualTo(AccessKind.WRITE);
    }

    @Test
    void resolve_createQueryWithNoResolvableString_isUnidentifiedRead() {
        // 쿼리 문자열을 이 메서드에서 찾을 수 없습니다(Criteria API 나 외부에서 받은 쿼리).
        // 판정 불가이므로 조용히 버리지 않고 미해결로 드러나야 합니다.
        Optional<EntityAccess> access = resolveIn("queryUnresolvable",
            new MethodRef(ENTITY_MANAGER, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;"));
        assertThat(access).isPresent();
        assertThat(access.get().isIdentified()).isFalse();
    }

    @Test
    void resolve_unhandledEntityManagerMethod_returnsEmpty() {
        // flush/clear 처럼 엔티티를 지목하지 않는 호출은 이 리졸버의 담당이 아닙니다.
        // 미해결로 표시하면 모든 EntityManager 사용 지점이 소음을 만듭니다.
        assertThat(resolveIn("persistNew", new MethodRef(ENTITY_MANAGER, "flush", "()V"))).isEmpty();
    }

    private static MethodRef persist() {
        return new MethodRef(ENTITY_MANAGER, "persist", "(Ljava/lang/Object;)V");
    }

    private Optional<EntityAccess> resolveIn(String callerMethodName, MethodRef callee) {
        MethodRef callerRef = program.ref(EntityManagerRepository.class, callerMethodName);
        var caller = classes.methodFacts(callerRef).orElseThrow();
        return resolver.resolve(callee, StubResolutionContext.of(
            classes, entities, repositories, caller, false, false));
    }
}
