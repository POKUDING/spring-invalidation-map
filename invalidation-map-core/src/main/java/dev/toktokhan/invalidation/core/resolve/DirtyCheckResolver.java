package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Optional;

/**
 * 엔티티 변경자 호출을 쓰기로 바꿉니다. 설계 문서 4.2절 5번, 4.3절입니다.
 *
 * <p>{@code save()} 를 명시적으로 부르지 않고 {@code @Transactional} 안에서 엔티티 메서드만
 * 호출하는 코드를 잡습니다. 이 판정이 없으면 그런 프로젝트에서 쓰기가 통째로 누락됩니다.
 */
public final class DirtyCheckResolver implements EntityResolver {

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (context.state() == null) {
            return Optional.empty();
        }
        if (!context.state().inTransaction() || context.state().readOnlyTransaction()) {
            return Optional.empty();
        }
        if (!context.entities().isMutator(callee)) {
            return Optional.empty();
        }
        return Optional.of(EntityAccess.of(callee.owner(), AccessKind.WRITE));
    }
}
