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
 * Q클래스에서 얻습니다. {@code MethodFacts.newTypes()} 와 {@code calls()} 의 owner 에
 * 나타난 타입 중 {@code EntityPathBase<T>} 를 상속한 것을 찾아 {@code T} 를 엔티티로
 * 씁니다. {@code ConstructorExpression<T>} 를 상속한 DTO 프로젝션 Q클래스는 상위 타입이
 * 다르므로 자동으로 걸러집니다.
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
