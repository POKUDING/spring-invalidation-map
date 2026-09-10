package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * QueryDSL 사용 지점을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절 4번입니다.
 *
 * <p>엔티티는 호출 지점의 owner 나 인자 타입이 아니라, 호출을 담은 메서드가 참조한
 * Q클래스에서 얻습니다. {@code MethodFacts.newTypes()} 와 {@code calls()} 의 owner,
 * {@code referencedFieldOwners()}, {@code referencedFieldTypes()} 에 나타난 타입 중
 * {@code EntityPathBase<T>} 를 상속한
 * 것을 찾아 {@code T} 를 엔티티로 씁니다. {@code ConstructorExpression<T>} 를 상속한 DTO
 * 프로젝션 Q클래스는 상위 타입이 다르므로 자동으로 걸러집니다.
 *
 * <p><b>{@code referencedFieldOwners()} 가 필요한 이유:</b> QueryDSL Q클래스는 보통
 * {@code new QTrip()} 으로 만들지 않고, 코드 생성기가 만들어 둔 {@code public static
 * final} 기본 인스턴스(예: {@code QTrip.trip}, static import 로 {@code trip} 처럼 쓰는
 * 관용구)를 그대로 참조합니다. 이 경우 {@code NEW} 명령이 아예 없어 {@code newTypes()} 에
 * 잡히지 않고, Q클래스 자신에 선언된 메서드를 직접 호출하지도 않는 코드(예: {@code
 * qEntity.field.eq(...)} 처럼 필드의 타입인 {@code BooleanPath} 등을 호출하는 경우)라면
 * {@code calls()} 의 owner 에도 Q클래스가 나타나지 않습니다. 실측(Task 12, pirl-spring
 * {@code ClassInfoRepositoryImpl.findAllVisibleAtForV1} — {@code jpaQueryFactory
 * .selectFrom(classInfo).leftJoin(classInfo.classPhotoList).fetchJoin().where(...)}
 * 처럼 정적 인스턴스 {@code classInfo} 를 필드로만 참조): 이 라이브러리의 기존 픽스처
 * ({@code QuerydslRepository.selectFrom()})는 {@code new QTrip()} 을 쓰는 방식이라 이
 * 경로가 지금까지 드러나지 않았지만, QueryDSL 코드 생성기 자신이 권장하는 기본 관용구가
 * 바로 정적 인스턴스 참조이므로 실제 프로젝트에서는 이쪽이 오히려 흔합니다. {@code
 * GETSTATIC}/{@code GETFIELD} 로 읽은 필드의 선언 타입({@code referencedFieldOwners()})도
 * 함께 봐야 이 관용구를 놓치지 않습니다.
 *
 * <p><b>{@code referencedFieldTypes()} 까지 필요한 이유:</b> {@code visitFieldInsn} 의
 * {@code owner} 는 필드를 <b>선언한</b> 타입입니다. {@code QTrip.trip} 은 {@code QTrip}
 * 자신이 그 정적 필드를 선언하므로 {@code referencedFieldOwners()} 로 잡히지만,
 * {@code private final QTrip held = QTrip.trip;} 처럼 리포지토리가 Q클래스를 자기
 * 인스턴스 필드로 들고 {@code this.held} 로 쓰면 {@code owner} 는 그 리포지토리
 * 클래스입니다 — Q클래스를 읽는 {@code GETSTATIC} 은 생성자에만 있고 쿼리 메서드에는
 * 없습니다. 이 경우 Q클래스는 그 필드의 <b>타입</b>에만 남으므로, 필드 타입까지 봐야
 * 엔티티를 놓치지 않습니다. 여기서도 걸러내는 책임은 이 리졸버에 있습니다 —
 * {@code typeArgumentOfSupertype(type, EntityPathBase)} 로 Q클래스만 남기므로 무관한
 * 필드 타입이 섞여도 엔티티 오보로 이어지지 않습니다.
 *
 * <p><b>다만 어느 호출 지점에 반응할지는 {@code callee} 로 게이트를 겁니다.</b> owner 가
 * {@code com/querydsl/} 패키지이거나(QueryDSL API 표면 — {@code JPAQueryFactory},
 * {@code JPAQuery}, Q클래스 자신 등) owner 자신이 {@code EntityPathBase} 를 상속한
 * Q클래스일 때만 매칭합니다. 이 게이트가 없으면 caller 안에 Q클래스 참조가 있다는
 * 사실만으로 그 메서드의 **다른 모든 호출**(예: 더티체킹 대상 엔티티 변경자 호출)까지
 * 가로채, 체인에서 뒤에 있는 {@link DirtyCheckResolver} 가 그 호출 지점을 영원히 보지
 * 못하게 됩니다(첫 값을 돌려준 리졸버가 이깁니다). 게이트를 걸어도 엔티티 추출 자체는
 * caller 전체를 스캔하므로, QueryDSL 호출을 헬퍼 메서드로 감싼 코드도 그대로
 * 동작합니다 — 워커가 헬퍼 본문으로 내려가면 그 안에서 QueryDSL owner 를 가진 실제
 * 호출을 다시 만나기 때문입니다.
 */
public final class QuerydslResolver implements EntityResolver {

    private static final String QUERYDSL_PACKAGE_PREFIX = "com/querydsl/";
    private static final String ENTITY_PATH_BASE = "com/querydsl/core/types/dsl/EntityPathBase";
    private static final Set<String> WRITE_ENTRY_POINTS = Set.of("update", "delete");

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (!isQuerydslCall(callee, context)) {
            return Optional.empty();
        }
        if (context.state() == null || context.state().caller() == null) {
            return Optional.empty();
        }
        Set<String> referencedTypes = new LinkedHashSet<>(context.state().caller().newTypes());
        context.state().caller().calls().forEach(call -> referencedTypes.add(call.owner()));
        referencedTypes.addAll(context.state().caller().referencedFieldOwners());
        referencedTypes.addAll(context.state().caller().referencedFieldTypes());

        Set<String> found = new LinkedHashSet<>();
        for (String type : referencedTypes) {
            context.classes().typeArgumentOfSupertype(type, ENTITY_PATH_BASE)
                .filter(context.entities()::isEntity)
                .ifPresent(found::add);
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kindOf(callee)));
    }

    /**
     * 이 호출 지점이 QueryDSL 호출인지 판정합니다. caller 가 Q클래스를 참조한다는 사실만으로는
     * 부족합니다 — 그 메서드 안의 QueryDSL 과 무관한 다른 호출까지 가로채 버립니다.
     */
    private static boolean isQuerydslCall(MethodRef callee, ResolutionContext context) {
        if (callee.owner().startsWith(QUERYDSL_PACKAGE_PREFIX)) {
            return true;
        }
        return context.classes().typeArgumentOfSupertype(callee.owner(), ENTITY_PATH_BASE).isPresent();
    }

    /** QueryDSL 진입점이 update / delete 가 아니면 읽기입니다. */
    private static AccessKind kindOf(MethodRef callee) {
        return WRITE_ENTRY_POINTS.contains(callee.name()) ? AccessKind.WRITE : AccessKind.READ;
    }
}
