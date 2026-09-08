package dev.toktokhan.invalidation.core;

import java.util.Set;

/**
 * 호출 지점 하나가 만들어내는 엔티티 접근입니다.
 *
 * @param entities internal name 집합
 * @param kind     이 호출의 접근 방향
 */
public record EntityAccess(Set<String> entities, AccessKind kind) {

    public static EntityAccess of(String entity, AccessKind kind) {
        return new EntityAccess(Set.of(entity), kind);
    }
}
