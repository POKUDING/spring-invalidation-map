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

    private final FakeProgramModel program = FakeProgramModel.create().withEntity(Trip.class);
    private final ClassRepository classes = new ClassRepository(program);
    private final EntityIndex entities = new EntityIndex(classes, Set.of(TRIP));
    private final RepositoryIndex repositories = new RepositoryIndex(program, classes);
    private final QuerydslResolver resolver = new QuerydslResolver();

    @Test
    void resolve_callerReferencesEntityQClass_reportsEntity() {
        // 콜백으로 넘기는 callee 는 selectFrom() 본문의 실제 호출과 무관한 임의의 호출입니다
        // (owner 가 QTrip 도 EntityPathBase 도 아닙니다). 리졸버가 callee 자체가 아니라
        // caller 가 참조한 Q클래스로 판정한다는 사실이 이래야 드러납니다. callee.owner() 를
        // 들여다보는 잘못된 구현이었다면 owner 가 QTrip 이 아닌 이 호출에는 반응하지 않아
        // 이 단정이 깨집니다.
        assertThat(resolveIn("selectFrom",
            new MethodRef("java/lang/String", "trim", "()Ljava/lang/String;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_callerReferencesProjectionQClass_returnsEmpty() {
        // QTripDto 는 ConstructorExpression<Trip> 을 상속합니다. 타입 인자가 엔티티(Trip)
        // 이므로, EntityPathBase 상속 여부를 실제로 확인하지 않고 "타입 인자가 엔티티인가"만
        // 보는 구현에서는 이 단정이 깨집니다.
        assertThat(resolveIn("projection",
            new MethodRef("java/lang/String", "trim", "()Ljava/lang/String;"))).isEmpty();
    }

    @Test
    void resolve_deleteEntryPoint_isWrite() {
        assertThat(resolveIn("selectFrom",
            new MethodRef("com/querydsl/core/types/dsl/EntityPathBase", "delete", "()V")))
            .get()
            .satisfies(access -> assertThat(access.kind()).isEqualTo(AccessKind.WRITE));
    }

    private Optional<EntityAccess> resolveIn(String callerMethodName, MethodRef callee) {
        MethodRef callerRef = program.ref(QuerydslRepository.class, callerMethodName);
        var caller = classes.methodFacts(callerRef).orElseThrow();
        return resolver.resolve(callee, StubResolutionContext.of(
            classes, entities, repositories, caller, false, false));
    }
}
