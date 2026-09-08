package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.RenamedEntity;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JpqlEntityExtractorTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);
    private static final String RENAMED = MethodRefs.internalNameOf(RenamedEntity.class);

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());
    private final EntityIndex entities = new EntityIndex(classes,
        Set.of(TRIP, LEG, MethodRefs.internalNameOf(Coordinate.class), RENAMED));

    @Test
    void entities_selectFrom_resolvesFromTarget() {
        assertThat(extract("select t from Trip t where t.id = :id")).containsExactly(TRIP);
    }

    @Test
    void entities_joinOnAssociationPath_resolvesJoinTarget() {
        assertThat(extract("select t from Trip t join t.legs l where l.id = :id"))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }

    @Test
    void entities_updateStatement_resolvesTarget() {
        assertThat(extract("update Trip t set t.title = :title")).containsExactly(TRIP);
    }

    @Test
    void entities_deleteFrom_resolvesTarget() {
        assertThat(extract("delete from TripLeg l where l.id = :id")).containsExactly(LEG);
    }

    @Test
    void entities_unknownEntityName_isIgnored() {
        assertThat(extract("select x from Nowhere x")).isEmpty();
    }

    @Test
    void entities_unresolvableAssociationPath_isIgnored() {
        assertThat(extract("select t from Trip t join t.missing m")).containsExactly(TRIP);
    }

    @Test
    void entities_customEntityAnnotationName_resolvesByAnnotationNameNotClassName() {
        // RenamedEntity 의 클래스명이 아니라 @Entity(name = "LegacyBooking") 값으로 찾습니다.
        // entityByName 이 클래스명만 색인하면 이 토큰은 풀리지 않고 결과가 비게 됩니다.
        assertThat(extract("select r from LegacyBooking r where r.id = :id")).containsExactly(RENAMED);
    }

    @Test
    void kindOf_selectStatement_isRead() {
        assertThat(JpqlEntityExtractor.kindOf("  SELECT t FROM Trip t ")).isEqualTo(AccessKind.READ);
    }

    @Test
    void kindOf_updateStatement_isWrite() {
        assertThat(JpqlEntityExtractor.kindOf("update Trip t set t.title = :t"))
            .isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_deleteStatement_isWrite() {
        assertThat(JpqlEntityExtractor.kindOf("DELETE FROM Trip t")).isEqualTo(AccessKind.WRITE);
    }

    private Set<String> extract(String jpql) {
        return JpqlEntityExtractor.entities(jpql, entities, classes);
    }
}
