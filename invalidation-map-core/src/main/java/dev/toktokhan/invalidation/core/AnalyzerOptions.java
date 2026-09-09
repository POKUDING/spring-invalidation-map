package dev.toktokhan.invalidation.core;

import java.util.List;

/**
 * @param basePackages            사슬을 따라 내려갈 패키지의 internal name 접두어
 * @param nodeBudget              엔드포인트 하나가 방문할 수 있는 최대 메서드 수
 * @param expandReadAssociations  읽기 집합을 연관 한 단계로 넓힐지
 */
public record AnalyzerOptions(List<String> basePackages, int nodeBudget,
    boolean expandReadAssociations) {
}
