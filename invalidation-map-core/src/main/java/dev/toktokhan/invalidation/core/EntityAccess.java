package dev.toktokhan.invalidation.core;

import java.util.Set;

/**
 * 호출 지점 하나가 만들어내는 엔티티 접근입니다.
 *
 * <p>엔티티 집합이 빈 값은 "이 호출은 내 담당인데 엔티티를 특정하지 못했다"는 뜻입니다
 * ({@link #unidentified}). {@code Optional.empty()}("내 담당이 아니다")와 다릅니다 —
 * 분석기는 이것을 미해결 사유로 남기고, 조용히 버리지 않습니다(설계 문서 4.4절).
 *
 * @param entities internal name 집합. 빈 값은 특정하지 못했다는 표시입니다
 * @param kind     이 호출의 접근 방향
 */
public record EntityAccess(Set<String> entities, AccessKind kind) {

    public static EntityAccess of(String entity, AccessKind kind) {
        return new EntityAccess(Set.of(entity), kind);
    }

    /**
     * 담당은 맞지만 엔티티를 특정하지 못한 접근입니다. 방향({@code kind})은 알고 있으므로
     * 함께 남겨, 미해결 사유가 읽기 자리인지 쓰기 자리인지 구분되게 합니다.
     */
    public static EntityAccess unidentified(AccessKind kind) {
        return new EntityAccess(Set.of(), kind);
    }

    /** 엔티티를 특정했는지입니다. */
    public boolean isIdentified() {
        return !entities.isEmpty();
    }
}
