package dev.toktokhan.invalidation.core;

/**
 * 메서드 하나를 가리키는 식별자입니다.
 *
 * @param owner      선언 클래스의 ASM internal name (예: {@code com/example/run/Run})
 * @param name       메서드명
 * @param descriptor JVM 메서드 디스크립터 (예: {@code (Ljava/lang/String;)V})
 */
public record MethodRef(String owner, String name, String descriptor) {

    @Override
    public String toString() {
        return owner + "." + name + descriptor;
    }
}
