package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * 메서드 본문에서 뽑아낸 사실입니다.
 *
 * @param calls            호출한 메서드. 바이트코드 순서를 유지합니다
 * @param newTypes         {@code NEW} 로 생성한 타입의 internal name
 * @param stringConstants  {@code LDC} 로 실린 문자열 상수
 * @param writtenOwnFields {@code PUTFIELD} 로 값을 쓴 자기 클래스 필드명
 * @param lambdaBodies     {@code INVOKEDYNAMIC} 부트스트랩 인자가 가리키는 메서드
 */
public record MethodFacts(
    MethodRef ref,
    int access,
    List<MethodRef> calls,
    List<String> newTypes,
    List<String> stringConstants,
    Set<String> writtenOwnFields,
    Map<String, AnnotationValues> annotations,
    List<MethodRef> lambdaBodies
) {
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public boolean isConstructor() {
        return ref.name().equals("<init>") || ref.name().equals("<clinit>");
    }

    public boolean hasAnnotation(String annotationDescriptor) {
        return annotations.containsKey(annotationDescriptor);
    }

    public AnnotationValues annotation(String annotationDescriptor) {
        return annotations.getOrDefault(annotationDescriptor, AnnotationValues.EMPTY);
    }
}
