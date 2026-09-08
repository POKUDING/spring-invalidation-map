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
 * <p>호출 지점의 owner 나 인자 타입이 아니라, 호출을 담은 메서드가 참조한 Q클래스에서
 * 엔티티를 얻습니다. {@code MethodFacts.newTypes()} 와 {@code calls()} 의 owner 에 나타난
 * 타입 중 {@code EntityPathBase<T>} 를 상속한 것을 찾아 {@code T} 를 엔티티로 씁니다.
 * {@code ConstructorExpression<T>} 를 상속한 DTO 프로젝션 Q클래스는 상위 타입이 다르므로
 * 자동으로 걸러집니다.
 */
public final class QuerydslResolver implements EntityResolver {

    private static final String ENTITY_PATH_BASE = "com/querydsl/core/types/dsl/EntityPathBase";
    private static final Set<String> WRITE_ENTRY_POINTS = Set.of("update", "delete");

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
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

    /** QueryDSL 진입점이 update / delete 가 아니면 읽기입니다. */
    private static AccessKind kindOf(MethodRef callee) {
        return WRITE_ENTRY_POINTS.contains(callee.name()) ? AccessKind.WRITE : AccessKind.READ;
    }
}
