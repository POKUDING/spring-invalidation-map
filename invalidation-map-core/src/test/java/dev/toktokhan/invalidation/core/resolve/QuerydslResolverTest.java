package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.repo.QuerydslRepository;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.StubResolutionContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuerydslResolverTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String QTRIP = MethodRefs.internalNameOf(QuerydslRepository.QTrip.class);

    /** 게이트만 통과시키는 합성 QueryDSL API 호출입니다. selectFrom() 본문에는 실제로 없습니다. */
    private static final MethodRef QUERYDSL_API_CALL = new MethodRef(
        "com/querydsl/jpa/impl/JPAQueryFactory", "select",
        "([Lcom/querydsl/core/types/Expression;)Lcom/querydsl/jpa/impl/JPAQuery;");

    private final FakeProgramModel program = FakeProgramModel.create().withEntity(Trip.class);
    private final ClassRepository classes = new ClassRepository(program);
    private final EntityIndex entities = new EntityIndex(classes, Set.of(TRIP));
    private final RepositoryIndex repositories = new RepositoryIndex(program, classes);
    private final QuerydslResolver resolver = new QuerydslResolver();

    @Test
    void resolve_callerReferencesEntityQClass_reportsEntity() {
        // callee 는 selectFrom() 본문에 실제로 있는 호출입니다(QTrip.toString()). owner 가
        // QTrip 이고 QTrip 이 EntityPathBase 를 상속하므로 게이트를 통과합니다. 엔티티
        // 자체는 callee.owner() 가 아니라 caller 가 참조한 Q클래스 전체에서 얻습니다 —
        // toString() 이라는 이름 자체는 엔티티 판정과 무관합니다.
        assertThat(resolveIn("selectFrom",
            new MethodRef(QTRIP, "toString", "()Ljava/lang/String;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_calleeIsQuerydslApiSurface_reportsEntity() {
        // owner 가 QTrip 자신이 아니라 com/querydsl/ 패키지의 API(JPAQueryFactory 등)여도
        // 게이트를 통과해야 합니다. Q클래스 owner 만 인정하는 구현에서는 이 단정이 깨집니다.
        assertThat(resolveIn("selectFrom", QUERYDSL_API_CALL))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_callerReferencesProjectionQClass_returnsEmpty() {
        // callee 는 게이트를 통과하는 합성 QueryDSL API 호출입니다 — 이 테스트가 검증하려는
        // 것은 게이트가 아니라 엔티티 추출이 QTripDto 를 걸러내는지이므로, 게이트 자체가
        // 먼저 걸려 버리는 callee 를 쓰면(예: QTripDto 소유의 호출) 엔티티 추출 로직을 아예
        // 타지 않아 이 단정이 무의미해집니다. QTripDto 는 ConstructorExpression<Trip> 을
        // 상속합니다. 타입 인자가 엔티티(Trip)이므로, EntityPathBase 상속 여부를 실제로
        // 확인하지 않고 "타입 인자가 엔티티인가"만 보는 구현에서는 이 단정이 깨집니다.
        assertThat(resolveIn("projection", QUERYDSL_API_CALL)).isEmpty();
    }

    @Test
    void resolve_callerReferencesQClassButCalleeIsEntityMutator_returnsEmpty() {
        // renameIfStale() 은 QTrip 참조와 trip.rename(...) 호출을 한 메서드 안에 함께
        // 담은 픽스처입니다. rename 호출의 owner(Trip)는 QueryDSL API 패키지도 아니고
        // EntityPathBase 를 상속하지도 않으므로 게이트에 걸려 미해결로 남아야 합니다.
        // 게이트가 없으면(caller 의 QTrip 참조만으로 반응하면) 이 호출 지점까지 QueryDSL
        // 접근으로 잘못 판정되어, 체인 뒤쪽의 DirtyCheckResolver 가 이 호출을 영원히
        // 보지 못합니다(먼저 값을 돌려준 리졸버가 이기므로).
        assertThat(resolveIn("renameIfStale",
            new MethodRef(TRIP, "rename", "(Ljava/lang/String;)V"))).isEmpty();
    }

    @Test
    void resolve_deleteEntryPoint_isWrite() {
        assertThat(resolveIn("selectFrom",
            new MethodRef("com/querydsl/core/types/dsl/EntityPathBase", "delete", "()V")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    private Optional<EntityAccess> resolveIn(String callerMethodName, MethodRef callee) {
        MethodRef callerRef = program.ref(QuerydslRepository.class, callerMethodName);
        var caller = classes.methodFacts(callerRef).orElseThrow();
        return resolver.resolve(callee, StubResolutionContext.of(
            classes, entities, repositories, caller, false, false));
    }
}
