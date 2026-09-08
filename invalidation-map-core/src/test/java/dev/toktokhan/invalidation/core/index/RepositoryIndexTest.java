package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.repo.TripRepositoryCustom;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import org.junit.jupiter.api.Test;

class RepositoryIndexTest {

    private static final String REPO = MethodRefs.internalNameOf(TripJpaRepository.class);
    private static final String FRAGMENT = MethodRefs.internalNameOf(TripRepositoryCustom.class);
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);

    private final FakeProgramModel program = FakeProgramModel.create()
        .withRepositoryEntity(TripJpaRepository.class, Trip.class)
        // 런타임 구현은 프래그먼트 인터페이스도 같은 엔티티로 등록해야 합니다.
        .withRepositoryEntity(TripRepositoryCustom.class, Trip.class);
    private final RepositoryIndex repositories =
        new RepositoryIndex(program, new ClassRepository(program));

    @Test
    void entityFor_springDataRepository_resolvesEntity() {
        assertThat(repositories.entityFor(REPO)).contains(TRIP);
    }

    @Test
    void entityFor_fragmentInterface_resolvesEntity() {
        assertThat(repositories.entityFor(FRAGMENT)).contains(TRIP);
    }

    @Test
    void entityFor_notARepository_returnsEmpty() {
        assertThat(repositories.entityFor("java/lang/String")).isEmpty();
    }

    @Test
    void accessKindOf_findPrefix_isRead() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "findByTitle",
            "(Ljava/lang/String;)Ljava/util/Optional;"))).contains(AccessKind.READ);
    }

    @Test
    void accessKindOf_countPrefix_isRead() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "countByTitle",
            "(Ljava/lang/String;)J"))).contains(AccessKind.READ);
    }

    @Test
    void accessKindOf_savePrefix_isWrite() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "save",
            "(Ljava/lang/Object;)Ljava/lang/Object;"))).contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_modifyingAnnotationBeatsReadPrefix_isWrite() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "readAndRewrite",
            "(Ljava/lang/Long;Ljava/lang/String;)I"))).contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_unknownPrefix_returnsEmptySoWalkerCanDescend() {
        assertThat(repositories.accessKindOf(new MethodRef(FRAGMENT, "upsert",
            "(Ljava/lang/Long;Ljava/lang/String;)V"))).isEmpty();
    }

    @Test
    void accessKindOf_modifyingDeclaredOnSuperInterface_resolvesThroughHierarchy_isWrite() {
        // wipe 는 READ/WRITE 어느 접두어에도 맞지 않고, @Modifying 은 TripJpaRepository 가
        // 아니라 상위 인터페이스 ArchivableJpaOperations 에 선언돼 있습니다. hasModifying 이
        // methodFacts(직접 선언만) 대신 resolveMethod(상위 타입까지)를 써야만 통과합니다.
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "wipe",
            "(Ljava/lang/Long;)V"))).contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_saveAndFlushPrefix_isWrite() {
        // saveAndFlush 는 JpaRepository 에 선언돼 있고 이름이 save 로 시작합니다.
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "saveAndFlush",
            "(Ljava/lang/Object;)Ljava/lang/Object;"))).contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_deleteAllInBatchPrefix_isWrite() {
        // deleteAllInBatch 는 JpaRepository 에 선언돼 있고 이름이 delete 로 시작합니다.
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "deleteAllInBatch", "()V")))
            .contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_existsByIdPrefix_isRead() {
        // existsById 는 CrudRepository 에 선언돼 있고 이름이 exists 로 시작합니다.
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "existsById",
            "(Ljava/lang/Object;)Z"))).contains(AccessKind.READ);
    }

    @Test
    void queryOf_methodWithQueryAnnotation_returnsQueryText() {
        assertThat(repositories.queryOf(new MethodRef(REPO, "findByLeg",
            "(Ljava/lang/Long;)Ljava/util/List;")))
            .contains("select t from Trip t join t.legs l where l.id = :legId");
    }

    @Test
    void queryOf_methodWithoutQueryAnnotation_returnsEmpty() {
        assertThat(repositories.queryOf(new MethodRef(REPO, "findByTitle",
            "(Ljava/lang/String;)Ljava/util/Optional;"))).isEmpty();
    }

    @Test
    void isNativeQuery_nativeQueryFlagSet_isTrue() {
        assertThat(repositories.isNativeQuery(new MethodRef(REPO, "findAllNative",
            "()Ljava/util/List;"))).isTrue();
    }

    @Test
    void isNativeQuery_jpqlQuery_isFalse() {
        assertThat(repositories.isNativeQuery(new MethodRef(REPO, "findByLeg",
            "(Ljava/lang/Long;)Ljava/util/List;"))).isFalse();
    }
}
