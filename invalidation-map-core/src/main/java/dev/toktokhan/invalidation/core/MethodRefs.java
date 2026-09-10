package dev.toktokhan.invalidation.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.objectweb.asm.Type;

/**
 * 리플렉션 객체를 {@link MethodRef} 와 internal name 으로 바꿉니다.
 *
 * <p>디스크립터 계산을 코어에 두어, 스타터가 ASM 을 클래스패스에 두지 않아도
 * {@code HandlerMethod} 에서 {@link MethodRef} 를 만들 수 있게 합니다.
 */
public final class MethodRefs {

    private MethodRefs() {
    }

    public static MethodRef of(Method method) {
        return new MethodRef(
            internalNameOf(method.getDeclaringClass()),
            method.getName(),
            Type.getMethodDescriptor(method));
    }

    /** 선언 클래스를 직접 지정합니다. 프록시 대신 실제 클래스를 넣을 때 씁니다. */
    public static MethodRef of(Class<?> declaringClass, Method method) {
        return new MethodRef(
            internalNameOf(declaringClass),
            method.getName(),
            Type.getMethodDescriptor(method));
    }

    public static String internalNameOf(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static String fqcnOf(String internalName) {
        return internalName.replace('/', '.');
    }

    /**
     * 메서드 디스크립터의 파라미터 중 참조 타입의 internal name 입니다. 기본 타입은
     * 빠지고, 배열은 원소 타입을 따라 내려갑니다. 선언 순서를 유지합니다.
     */
    public static List<String> parameterTypesOf(String methodDescriptor) {
        List<String> found = new ArrayList<>();
        for (Type parameter : Type.getArgumentTypes(methodDescriptor)) {
            objectTypeOf(parameter).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /**
     * 메서드 디스크립터의 반환 타입이 참조 타입이면 그 internal name 입니다.
     * {@code void} 와 기본 타입은 빈 값입니다.
     */
    public static Optional<String> returnTypeOf(String methodDescriptor) {
        return objectTypeOf(Type.getReturnType(methodDescriptor));
    }

    private static Optional<String> objectTypeOf(Type type) {
        Type current = type;
        while (current.getSort() == Type.ARRAY) {
            current = current.getElementType();
        }
        return current.getSort() == Type.OBJECT
            ? Optional.of(current.getInternalName())
            : Optional.empty();
    }

    public static String simpleNameOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String name = slash < 0 ? internalName : internalName.substring(slash + 1);
        int dollar = name.lastIndexOf('$');
        return dollar < 0 ? name : name.substring(dollar + 1);
    }
}
