package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code EntityManager} 호출을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절 3번입니다.
 *
 * <p>쿼리 문자열은 호출 인자이지만 바이트코드에서 스택을 추적하지 않으므로, 호출을 담은
 * 메서드의 문자열 상수 전체를 후보로 씁니다. 한 메서드에 쿼리가 여러 개면 모두 반영되어
 * 과잉이 됩니다. 오차 방향 원칙에 맞습니다.
 *
 * <p>{@code persist}/{@code merge}/{@code remove} 는 디스크립터가 {@code (Ljava/lang/Object;)V}
 * 라 파라미터 정적 타입에서 엔티티를 알 수 없고, {@code find}/{@code getReference} 는 첫
 * 인자의 {@code Class} 리터럴을 스택에서 읽어야만 판정할 수 있어 이 리졸버는 손대지
 * 않습니다. 두 경우 모두 빈 값을 돌려줘 워커가 본문으로 내려가게 합니다.
 */
public final class EntityManagerResolver implements EntityResolver {

    private static final String ENTITY_MANAGER = "jakarta/persistence/EntityManager";

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (!isEntityManager(callee.owner(), context)) {
            return Optional.empty();
        }
        if (context.state() == null || context.state().caller() == null) {
            return Optional.empty();
        }

        boolean nativeQuery = callee.name().equals("createNativeQuery");
        boolean jpql = callee.name().equals("createQuery");
        if (!nativeQuery && !jpql) {
            return Optional.empty();
        }

        Set<String> found = new LinkedHashSet<>();
        AccessKind kind = AccessKind.READ;
        boolean matched = false;
        for (String candidate : context.state().caller().stringConstants()) {
            Set<String> resolved = nativeQuery
                ? SqlTableExtractor.entities(candidate, context.entities())
                : JpqlEntityExtractor.entities(candidate, context.entities(), context.classes());
            if (resolved.isEmpty()) {
                continue;
            }
            matched = true;
            found.addAll(resolved);
            AccessKind candidateKind = nativeQuery
                ? SqlTableExtractor.kindOf(candidate)
                : JpqlEntityExtractor.kindOf(candidate);
            if (candidateKind == AccessKind.WRITE) {
                kind = AccessKind.WRITE;
            }
        }
        if (!matched) {
            return Optional.empty();
        }
        return Optional.of(new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind));
    }

    private static boolean isEntityManager(String owner, ResolutionContext context) {
        return owner.equals(ENTITY_MANAGER) || context.classes().isSubtypeOf(owner, ENTITY_MANAGER);
    }
}
