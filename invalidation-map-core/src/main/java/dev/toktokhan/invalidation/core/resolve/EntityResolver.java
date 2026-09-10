package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Optional;

/**
 * 호출 지점을 엔티티 접근으로 바꿉니다.
 *
 * <p>빈 값은 "이 호출은 내 담당이 아니다" 또는 "판정할 수 없다"는 뜻입니다. 어느 쪽이든
 * 워커가 그 메서드 본문으로 내려가 실제 접근을 찾습니다. 임의로 방향을 정하지 않습니다.
 */
public interface EntityResolver {

    Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context);
}
