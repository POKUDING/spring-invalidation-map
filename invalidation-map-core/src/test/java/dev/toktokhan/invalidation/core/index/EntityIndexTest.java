package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Account;
import dev.toktokhan.invalidation.core.fixture.entity.Booking;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.HTTPServer;
import dev.toktokhan.invalidation.core.fixture.entity.Member;
import dev.toktokhan.invalidation.core.fixture.entity.RenamedEntity;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.entity.Waypoint;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityIndexTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);
    private static final String ACCOUNT = MethodRefs.internalNameOf(Account.class);
    private static final String MEMBER = MethodRefs.internalNameOf(Member.class);
    private static final String RENAMED = MethodRefs.internalNameOf(RenamedEntity.class);

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());

    // Set.of 는 JVM 기동마다 순회 순서가 달라집니다. buildTableIndex 는 내부에서 이름을
    // 정렬하고, 충돌한 후보는 이제 양쪽 다 등록하므로(entitiesForTable) 이 순서가 결과를
    // 바꾸지 않습니다. 그래도 다른 단정들의 재현성을 위해 결정적인 순서를 보존하는
    // 컬렉션을 그대로 씁니다.
    private final EntityIndex entities = new EntityIndex(classes, new LinkedHashSet<>(List.of(
        TRIP,
        LEG,
        MethodRefs.internalNameOf(Coordinate.class),
        MethodRefs.internalNameOf(Waypoint.class),
        MethodRefs.internalNameOf(HTTPServer.class),
        MEMBER,
        ACCOUNT,
        MethodRefs.internalNameOf(Booking.class),
        RENAMED)));

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
    void isMutator_subclassMethodCallsInheritedMutator_isTrue() {
        // archive() 는 Trip 이 선언하지만, 안에서 부르는 markDeleted() 는 BaseRecord 가
        // 선언합니다. javac 은 이 호출의 owner 를 정적 수신 타입인 Trip 으로 기록합니다.
        assertThat(entities.isMutator(new MethodRef(TRIP, "archive", "()V"))).isTrue();
    }

    @Test
    void isMutator_methodCallsInheritedMutatorThroughUnannotatedIntermediateClass_isTrue() {
        // Booking -> ArchivableRecord(어노테이션 없음) -> BaseRecord(@MappedSuperclass).
        // archive() 는 ArchivableRecord 가 선언하고, 그 안에서 부르는 markDeleted() 의
        // owner 도 ArchivableRecord 자신입니다. ArchivableRecord 는 엔티티도
        // @MappedSuperclass 도 아니라서 holdsState 가 거짓인 경로를 지나가지만, 그래도
        // 상속받은 변경자를 부르는 자기 선언 메서드는 잡혀야 합니다.
        assertThat(entities.isMutator(new MethodRef(
            MethodRefs.internalNameOf(Booking.class), "archive", "()V"))).isTrue();
    }

    @Test
    void associationsOf_collectionAndEmbeddedAssociations_reportsExactlyThoseEntities() {
        assertThat(entities.associationsOf(TRIP)).containsExactlyInAnyOrder(
            MethodRefs.internalNameOf(TripLeg.class),
            MethodRefs.internalNameOf(Coordinate.class));
    }

    @Test
    void associationsOf_nonEntity_reportsNothing() {
        assertThat(entities.associationsOf("java/lang/String")).isEmpty();
    }

    @Test
    void associationsOf_fieldWithoutAssociationAnnotation_excludesThatFieldsType() {
        assertThat(entities.associationsOf(TRIP))
            .doesNotContain(MethodRefs.internalNameOf(Waypoint.class));
    }

    @Test
    void associationsOf_associationTargetingNonEntityType_excludesThatTarget() {
        assertThat(entities.associationsOf(TRIP)).doesNotContain("java/lang/String");
    }

    @Test
    void associationsOf_selfReferencingAssociation_excludesItself() {
        assertThat(entities.associationsOf(TRIP)).doesNotContain(TRIP);
    }

    @Test
    void entitiesForTable_explicitTableAnnotation_resolvesEntity() {
        assertThat(entities.entitiesForTable("trip_log")).containsExactly(TRIP);
    }

    @Test
    void entitiesForTable_noTableAnnotation_resolvesBySnakeCaseDefault() {
        assertThat(entities.entitiesForTable("trip_leg")).containsExactly(LEG);
    }

    @Test
    void entitiesForTable_noTableAnnotation_alsoResolvesByJpaDefault() {
        assertThat(entities.entitiesForTable("TripLeg")).containsExactly(LEG);
    }

    @Test
    void entitiesForTable_unknownTable_returnsEmpty() {
        assertThat(entities.entitiesForTable("nowhere")).isEmpty();
    }

    @Test
    void entitiesForTable_consecutiveUppercaseSimpleName_resolvesBySpringNamingStrategy() {
        // 순진한 snake_case 는 "h_t_t_p_server" 를 등록하지만 실제 Spring Boot 기본
        // 테이블명은 "httpserver" 입니다(SpringPhysicalNamingStrategy 는 연속된 대문자
        // 사이에 밑줄을 넣지 않습니다).
        assertThat(entities.entitiesForTable("httpserver"))
            .containsExactly(MethodRefs.internalNameOf(HTTPServer.class));
    }

    @Test
    void entitiesForTable_explicitTableCollidesWithAnotherEntitysDefault_returnsBothEntities() {
        // Account 는 @Table(name = "member") 를 명시하고, Member 는 @Table 이 없어 기본값
        // "member" 를 추정합니다. 누락을 금지하는 전역 원칙에 따라 한쪽이 다른 쪽을 밀어내지
        // 않고 둘 다 결과에 담겨야 합니다. 이 중 Account 의 명시값은 반드시 포함돼야 합니다.
        assertThat(entities.entitiesForTable("member")).containsExactlyInAnyOrder(ACCOUNT, MEMBER);
    }

    @Test
    void entityByName_simpleClassName_resolvesEntity() {
        assertThat(entities.entityByName("Trip")).contains(TRIP);
    }

    @Test
    void entityByName_customEntityAnnotationName_resolvesEntity() {
        // RenamedEntity 는 @Entity(name = "LegacyBooking") 이라 클래스명이 아니라 이 값으로
        // 찾아야 합니다.
        assertThat(entities.entityByName("LegacyBooking")).contains(RENAMED);
    }

    @Test
    void entityByName_unknownName_returnsEmpty() {
        assertThat(entities.entityByName("Nowhere")).isEmpty();
    }

    @Test
    void associationTargets_collectionAssociationField_resolvesElementType() {
        assertThat(entities.associationTargets(TRIP, "legs")).containsExactly(LEG);
    }

    @Test
    void associationTargets_fieldWithoutAssociationAnnotation_returnsEmpty() {
        assertThat(entities.associationTargets(TRIP, "nextStop")).isEmpty();
    }

    @Test
    void associationTargets_unknownField_returnsEmpty() {
        assertThat(entities.associationTargets(TRIP, "missing")).isEmpty();
    }
}
