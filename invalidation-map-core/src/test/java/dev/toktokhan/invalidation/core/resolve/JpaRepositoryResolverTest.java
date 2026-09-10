package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.repo.TripRepositoryCustom;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.StubResolutionContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JpaRepositoryResolverTest {

    private static final String REPO = MethodRefs.internalNameOf(TripJpaRepository.class);
    private static final String FRAGMENT = MethodRefs.internalNameOf(TripRepositoryCustom.class);
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);

    private final FakeProgramModel program = FakeProgramModel.create()
        .withRepositoryEntity(TripJpaRepository.class, Trip.class)
        .withRepositoryEntity(TripRepositoryCustom.class, Trip.class)
        .withEntity(TripLeg.class);
    private final ClassRepository classes = new ClassRepository(program);
    private final EntityIndex entities = new EntityIndex(classes, Set.of(TRIP, LEG));
    private final RepositoryIndex repositories = new RepositoryIndex(program, classes);
    private final JpaRepositoryResolver resolver = new JpaRepositoryResolver();

    @Test
    void resolve_findMethod_reportsRepositoryEntityAsRead() {
        assertThat(resolve(new MethodRef(REPO, "findByTitle", "(Ljava/lang/String;)Ljava/util/Optional;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_saveMethod_reportsWrite() {
        assertThat(resolve(new MethodRef(REPO, "save", "(Ljava/lang/Object;)Ljava/lang/Object;")))
            .get()
            .satisfies(access -> assertThat(access.kind()).isEqualTo(AccessKind.WRITE));
    }

    @Test
    void resolve_queryWithJoin_addsJoinedEntity() {
        assertThat(resolve(new MethodRef(REPO, "findByLeg", "(Ljava/lang/Long;)Ljava/util/List;")))
            .get()
            .satisfies(access -> assertThat(access.entities()).containsExactlyInAnyOrder(TRIP, LEG));
    }

    @Test
    void resolve_modifyingQuery_isWrite() {
        assertThat(resolve(new MethodRef(REPO, "readAndRewrite",
            "(Ljava/lang/Long;Ljava/lang/String;)I")))
            .get()
            .satisfies(access -> assertThat(access.kind()).isEqualTo(AccessKind.WRITE));
    }

    @Test
    void resolve_nativeQuery_resolvesTableNames() {
        assertThat(resolve(new MethodRef(REPO, "findAllNative", "()Ljava/util/List;")))
            .get()
            .satisfies(access -> assertThat(access.entities()).contains(TRIP));
    }

    @Test
    void resolve_unknownPrefixWithoutQuery_returnsEmptySoWalkerCanDescend() {
        assertThat(resolve(new MethodRef(FRAGMENT, "upsert",
            "(Ljava/lang/Long;Ljava/lang/String;)V"))).isEmpty();
    }

    @Test
    void resolve_notARepository_returnsEmpty() {
        assertThat(resolve(new MethodRef("java/lang/String", "trim", "()Ljava/lang/String;"))).isEmpty();
    }

    private Optional<EntityAccess> resolve(MethodRef callee) {
        return resolver.resolve(callee, new StubResolutionContext(
            classes, entities, repositories, null));
    }
}
