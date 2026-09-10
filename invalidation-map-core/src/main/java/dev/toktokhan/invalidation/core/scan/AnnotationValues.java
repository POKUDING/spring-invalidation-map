package dev.toktokhan.invalidation.core.scan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 어노테이션 하나의 속성 값입니다.
 *
 * <p>클래스 값은 ASM {@code Type} 이 아니라 internal name 문자열로 저장됩니다.
 * 코어 전체가 internal name 을 사용하기 때문입니다.
 */
public record AnnotationValues(Map<String, Object> values) {

    public static final AnnotationValues EMPTY = new AnnotationValues(Map.of());

    public Optional<String> string(String name) {
        Object value = values.get(name);
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    public boolean bool(String name, boolean defaultValue) {
        Object value = values.get(name);
        return value instanceof Boolean flag ? flag : defaultValue;
    }

    /**
     * 문자열 배열과 클래스 배열을 모두 문자열 목록으로 돌려줍니다.
     * 값이 배열이 아닌 단일 값이면 한 원소 목록으로 돌려줍니다.
     */
    public List<String> strings(String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return value instanceof String text ? List.of(text) : List.of();
    }
}
