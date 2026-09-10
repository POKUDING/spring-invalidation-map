package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.scan.MethodFacts;

/**
 * 호출 지점을 만난 순간의 상태입니다.
 *
 * @param caller              그 호출을 담고 있는 메서드. 문자열 상수를 보려면 필요합니다
 * @param inTransaction       {@code @Transactional} 경계 안인지
 * @param readOnlyTransaction 사슬 전체가 읽기 전용 트랜잭션인지
 */
public record WalkState(MethodFacts caller, boolean inTransaction, boolean readOnlyTransaction) {
}
