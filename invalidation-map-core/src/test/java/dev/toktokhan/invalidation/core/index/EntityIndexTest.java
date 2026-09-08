package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Account;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.HTTPServer;
import dev.toktokhan.invalidation.core.fixture.entity.Member;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.entity.Waypoint;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityIndexTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());

    // Set.of 는 JVM 기동마다 순회 순서가 달라집니다. 테이블명 충돌 판정은 순회 순서에
    // 영향받지 않지만(EntityIndex 가 내부에서 정렬합니다), 생성자 입력 자체는 결정적인
    // 순서를 보존하는 컬렉션을 씁니다. Member 를 Account 보다 먼저 두는 순서는
    // entityForTable_explicitTableCollidesWithAnotherEntitysDefault_explicitWins 가
    // 수정 전 코드에서 재현되도록 고른 순서입니다.
    private final EntityIndex entities = new EntityIndex(classes, new LinkedHashSet<>(List.of(
        TRIP,
        MethodRefs.internalNameOf(TripLeg.class),
        MethodRefs.internalNameOf(Coordinate.class),
        MethodRefs.internalNameOf(Waypoint.class),
        MethodRefs.internalNameOf(HTTPServer.class),
        MethodRefs.internalNameOf(Member.class),
        MethodRefs.internalNameOf(Account.class))));

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
    void associationsOf_fieldWithoutAssociationAnnotation_excludesThatFieldsType() {
        assertThat(entities.associationsOf(TRIP))
            .doesNotContain(MethodRefs.internalNameOf(Waypoint.class));
    }

    @Test
    void associationsOf_associationTargetingNonEntityType_reportsNothing() {
        assertThat(entities.associationsOf(TRIP)).doesNotContain("java/lang/String");
    }

    @Test
    void associationsOf_selfReferencingAssociation_excludesItself() {
        assertThat(entities.associationsOf(TRIP)).doesNotContain(TRIP);
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

    @Test
    void entityForTable_consecutiveUppercaseSimpleName_resolvesBySpringNamingStrategy() {
        // 순진한 snake_case 는 "h_t_t_p_server" 를 등록하지만 실제 Spring Boot 기본
        // 테이블명은 "httpserver" 입니다(SpringPhysicalNamingStrategy 는 연속된 대문자
        // 사이에 밑줄을 넣지 않습니다).
        assertThat(entities.entityForTable("httpserver"))
            .contains(MethodRefs.internalNameOf(HTTPServer.class));
    }

    @Test
    void entityForTable_explicitTableCollidesWithAnotherEntitysDefault_explicitWins() {
        // Account 는 @Table(name = "member") 를 명시하고, Member 는 @Table 이 없어 기본값
        // "member" 를 추정합니다. 개발자가 선언한 Account 의 명시값이 이겨야 합니다.
        assertThat(entities.entityForTable("member"))
            .contains(MethodRefs.internalNameOf(Account.class));
    }
}
