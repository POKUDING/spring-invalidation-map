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
    void resolve_persistWithObjectDescriptor_returnsEmpty() {
        // upsert 를 caller 로 써도 그 안에 진짜 createQuery/createNativeQuery 가 있는지와
        // 무관하게, persist 자체는 (Ljava/lang/Object;)V 디스크립터라 엔티티를 특정할 수
        // 없으므로 항상 미해결이어야 합니다.
        assertThat(resolveIn("upsert",
            new MethodRef(ENTITY_MANAGER, "persist", "(Ljava/lang/Object;)V"))).isEmpty();
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

    private Optional<EntityAccess> resolveIn(String callerMethodName, MethodRef callee) {
        MethodRef callerRef = program.ref(EntityManagerRepository.class, callerMethodName);
        var caller = classes.methodFacts(callerRef).orElseThrow();
        return resolver.resolve(callee, StubResolutionContext.of(
            classes, entities, repositories, caller, false, false));
    }
}
