package dev.toktokhan.invalidation.core.support;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.ProgramModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 테스트용 ProgramModel 입니다. 클래스 바이트는 테스트 클래스패스에서 읽고,
 * 나머지 사실은 빌더로 직접 넣습니다.
 */
public final class FakeProgramModel implements ProgramModel {

    private final List<Endpoint> endpoints = new ArrayList<>();
    private final Map<String, String> repositoryEntities = new LinkedHashMap<>();
    private final Map<String, Set<String>> implementations = new LinkedHashMap<>();
    private final Set<String> entities = new LinkedHashSet<>();
    private final Set<MethodRef> eventListeners = new LinkedHashSet<>();

    private FakeProgramModel() {
    }

    public static FakeProgramModel create() {
        return new FakeProgramModel();
    }

    public FakeProgramModel withEndpoint(String httpMethod, String path, Class<?> handlerType,
        String methodName, Class<?>... parameterTypes) {
        endpoints.add(new Endpoint(httpMethod, path,
            MethodRefs.of(findMethod(handlerType, methodName, parameterTypes))));
        return this;
    }

    public FakeProgramModel withRepositoryEntity(Class<?> repositoryType, Class<?> entityType) {
        repositoryEntities.put(MethodRefs.internalNameOf(repositoryType),
            MethodRefs.internalNameOf(entityType));
        entities.add(MethodRefs.internalNameOf(entityType));
        return this;
    }

    public FakeProgramModel withImplementation(Class<?> interfaceType, Class<?> implementationType) {
        implementations
            .computeIfAbsent(MethodRefs.internalNameOf(interfaceType), key -> new LinkedHashSet<>())
            .add(MethodRefs.internalNameOf(implementationType));
        return this;
    }

    /**
     * 클래스 바이트를 구할 수 없는 구현체를 등록합니다. 실제 {@code Class} 대신 임의의
     * 내부 이름을 그대로 씁니다 — {@code classBytes} 가 그 이름에 대해 (예외가 아니라)
     * 빈 값을 돌려주는 상황(클래스로더가 그 리소스를 찾지 못함)을 재현하는 데 씁니다.
     */
    public FakeProgramModel withUnreadableImplementation(Class<?> interfaceType,
        String implementationInternalName) {
        implementations
            .computeIfAbsent(MethodRefs.internalNameOf(interfaceType), key -> new LinkedHashSet<>())
            .add(implementationInternalName);
        return this;
    }

    public FakeProgramModel withEntity(Class<?> entityType) {
        entities.add(MethodRefs.internalNameOf(entityType));
        return this;
    }

    public FakeProgramModel withEventListener(Class<?> type, String methodName) {
        eventListeners.add(MethodRefs.of(findMethod(type, methodName)));
        return this;
    }

    @Override
    public List<Endpoint> endpoints() {
        return List.copyOf(endpoints);
    }

    @Override
    public Optional<byte[]> classBytes(String internalName) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> entityFor(String repositoryInternalName) {
        return Optional.ofNullable(repositoryEntities.get(repositoryInternalName));
    }

    @Override
    public Set<String> implementationsOf(String interfaceInternalName) {
        return implementations.getOrDefault(interfaceInternalName, Set.of());
    }

    @Override
    public Set<String> entities() {
        // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        return Collections.unmodifiableSet(new LinkedHashSet<>(entities));
    }

    @Override
    public Set<MethodRef> eventListeners() {
        // Set.copyOf 는 JVM 기동마다 순회 순서가 달라집니다. 삽입 순서를 보존합니다.
        return Collections.unmodifiableSet(new LinkedHashSet<>(eventListeners));
    }

    public MethodRef ref(Class<?> type, String methodName, Class<?>... parameterTypes) {
        return MethodRefs.of(findMethod(type, methodName, parameterTypes));
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        if (parameterTypes.length > 0) {
            try {
                return type.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("메서드를 찾을 수 없습니다: " + type.getName() + "#" + name, e);
            }
        }
        return Arrays.stream(type.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "메서드를 찾을 수 없습니다: " + type.getName() + "#" + name));
    }
}
