package dev.toktokhan.invalidation.core;

import java.util.Map;
import java.util.Optional;

/**
 * 핸들러 메서드에서 그 엔드포인트의 엔티티 집합으로 가는 표입니다.
 *
 * <p>경로가 아니라 핸들러 메서드로 키를 잡습니다. 한 핸들러에 경로나 HTTP 메서드가 여러 개
 * 붙은 매핑에서도 항목이 갈라지지 않고, 스타터가 {@code HandlerMethod} 로 바로 찾습니다.
 */
public record InvalidationMap(Map<MethodRef, EndpointEntities> byHandler) {

    public static InvalidationMap empty() {
        return new InvalidationMap(Map.of());
    }

    public Optional<EndpointEntities> forHandler(MethodRef handler) {
        return Optional.ofNullable(byHandler.get(handler));
    }
}
