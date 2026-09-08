package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntityIndexTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());
    private final EntityIndex entities = new EntityIndex(classes, Set.of(
        TRIP,
        MethodRefs.internalNameOf(TripLeg.class),
        MethodRefs.internalNameOf(Coordinate.class)));

    @Test
    void isMutator_methodWritesOwnField_isTrue() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "rename", "(Ljava/lang/String;)V"))).isTrue();
    }

    @Test
    void isMutator_methodCallsAnotherMutator_isTrue() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "reset", "(Ljava/lang/String;)V"))).isTrue();
    }

    @Test
    void isMutator_readOnlyMethod_isFalse() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "title", "()Ljava/lang/String;"))).isFalse();
    }

    @Test
    void isMutator_staticFactory_isFalse() {
        assertThat(entities.isMutator(
            new MethodRef(TRIP, "create", "(Ljava/lang/String;)L" + TRIP + ";"))).isFalse();
    }

    @Test
    void isMutator_constructor_isFalse() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "<init>", "()V"))).isFalse();
    }

    @Test
    void isMutator_mutatorInheritedFromMappedSuperclass_isTrueAndAttributedToOwner() {
        // 호출 지점의 owner 는 정적 수신 타입인 Trip 입니다. 본문은 BaseRecord 에 있습니다.
        assertThat(entities.isMutator(new MethodRef(TRIP, "markDeleted", "()V"))).isTrue();
    }

    @Test
    void isMutator_methodOnNonEntityClass_isFalse() {
        assertThat(entities.isMutator(
            new MethodRef("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;"))).isFalse();
    }

    @Test
    void associationsOf_collectionAssociation_reportsElementEntity() {
        assertThat(entities.associationsOf(TRIP))
            .contains(MethodRefs.internalNameOf(TripLeg.class));
    }

    @Test
    void associationsOf_embeddedAssociation_reportsEmbeddableType() {
        assertThat(entities.associationsOf(TRIP))
            .contains(MethodRefs.internalNameOf(Coordinate.class));
    }

    @Test
    void associationsOf_nonEntity_reportsNothing() {
        assertThat(entities.associationsOf("java/lang/String")).isEmpty();
    }

    @Test
    void entityForTable_explicitTableAnnotation_resolvesEntity() {
        assertThat(entities.entityForTable("trip_log")).contains(TRIP);
    }

    @Test
    void entityForTable_noTableAnnotation_resolvesBySnakeCaseDefault() {
        assertThat(entities.entityForTable("trip_leg"))
            .contains(MethodRefs.internalNameOf(TripLeg.class));
    }

    @Test
    void entityForTable_noTableAnnotation_alsoResolvesByJpaDefault() {
        assertThat(entities.entityForTable("TripLeg"))
            .contains(MethodRefs.internalNameOf(TripLeg.class));
    }

    @Test
    void entityForTable_unknownTable_returnsEmpty() {
        assertThat(entities.entityForTable("nowhere")).isEmpty();
    }
}
