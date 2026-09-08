package dev.toktokhan.invalidation.core;

import java.util.List;
import java.util.Set;

/**
 * 엔드포인트 하나의 분석 결과입니다.
 *
 * @param unresolved 판정하지 못한 자리의 사유입니다. 비어 있지 않으면 소비자는 보수적으로
 *                   다뤄야 합니다
 */
public record EndpointEntities(Set<String> reads, Set<String> writes, List<String> unresolved) {

    public boolean resolved() {
        return unresolved.isEmpty();
    }

    public boolean isEmpty() {
        return reads.isEmpty() && writes.isEmpty();
    }
}
