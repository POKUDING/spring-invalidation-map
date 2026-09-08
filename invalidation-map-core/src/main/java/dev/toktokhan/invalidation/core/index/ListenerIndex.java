package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이벤트 타입에서 그것을 듣는 리스너 메서드를 찾습니다.
 *
 * <p>{@code publishEvent} 는 직접 호출이 아니므로 호출 사슬이 끊깁니다. 이 색인이 그 자리를
 * 이어붙입니다. 리스너 파라미터가 상위 타입인 경우를 반드시 처리해야 합니다.
 */
public final class ListenerIndex {

    private static final String EVENT_LISTENER =
        "Lorg/springframework/context/event/EventListener;";
    private static final String TRANSACTIONAL_EVENT_LISTENER =
        "Lorg/springframework/transaction/event/TransactionalEventListener;";

    private final ClassRepository classes;
    /** 리스너 메서드 -> 그 리스너가 받는 이벤트 타입들 */
    private final Map<MethodRef, Set<String>> acceptedTypes = new ConcurrentHashMap<>();
    private final Map<String, Set<MethodRef>> cache = new ConcurrentHashMap<>();

    public ListenerIndex(ClassRepository classes, Set<MethodRef> listeners) {
        this.classes = classes;
        for (MethodRef listener : listeners) {
            acceptedTypes.put(listener, acceptedTypesOf(listener));
        }
    }

    /** 이 이벤트 타입을 듣는 리스너입니다. 파라미터가 상위 타입인 경우도 포함합니다. */
    public Set<MethodRef> listenersFor(String eventInternalName) {
        return cache.computeIfAbsent(eventInternalName, event -> {
            Set<MethodRef> matched = new LinkedHashSet<>();
            acceptedTypes.forEach((listener, accepted) -> {
                for (String candidate : accepted) {
                    if (classes.isSubtypeOf(event, candidate)) {
                        matched.add(listener);
                        return;
                    }
                }
            });
            return Collections.unmodifiableSet(new LinkedHashSet<>(matched));
        });
    }

    /**
     * 리스너가 받는 이벤트 타입입니다.
     *
     * <p>어노테이션의 {@code classes} 속성이 있으면 그것을 씁니다. 없으면 첫 파라미터 타입입니다.
     */
    private Set<String> acceptedTypesOf(MethodRef listener) {
        Set<String> declared = new LinkedHashSet<>();
        classes.resolveMethod(listener).ifPresent(facts -> {
            declared.addAll(facts.annotation(EVENT_LISTENER).strings("classes"));
            declared.addAll(facts.annotation(TRANSACTIONAL_EVENT_LISTENER).strings("classes"));
            declared.addAll(facts.annotation(EVENT_LISTENER).strings("value"));
            declared.addAll(facts.annotation(TRANSACTIONAL_EVENT_LISTENER).strings("value"));
        });
        if (!declared.isEmpty()) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(declared));
        }
        return Collections.unmodifiableSet(
            new LinkedHashSet<>(firstParameterType(listener.descriptor())));
    }

    /** 디스크립터의 첫 파라미터가 객체 타입이면 그 internal name 을 돌려줍니다. */
    private static List<String> firstParameterType(String descriptor) {
        int start = descriptor.indexOf('(');
        int end = descriptor.indexOf(')');
        if (start < 0 || end < 0) {
            return List.of();
        }
        String parameters = descriptor.substring(start + 1, end);
        if (!parameters.startsWith("L")) {
            return List.of();
        }
        int semicolon = parameters.indexOf(';');
        return semicolon < 0 ? List.of() : List.of(parameters.substring(1, semicolon));
    }
}
