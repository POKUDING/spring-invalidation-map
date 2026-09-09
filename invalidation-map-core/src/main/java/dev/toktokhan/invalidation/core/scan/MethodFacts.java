package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * 메서드 본문에서 뽑아낸 사실입니다.
 *
 * @param calls               호출한 메서드. 바이트코드 순서를 유지합니다
 * @param newTypes            {@code NEW} 로 생성한 타입의 internal name
 * @param stringConstants     {@code LDC} 로 실린 문자열 상수
 * @param writtenOwnFields    {@code PUTFIELD} 로 값을 쓴 자기 클래스 필드명
 * @param referencedFieldOwners {@code GETSTATIC}/{@code GETFIELD} 로 읽은 필드를 선언한
 *                              타입의 internal name. QueryDSL Q클래스는 보통 {@code new}
 *                              로 만들지 않고 코드 생성기가 만든 {@code public static final}
 *                              기본 인스턴스(예: {@code QTrip.trip})를 그대로 참조합니다 —
 *                              이 경우 {@code newTypes} 에는 아무것도 잡히지 않고 이 필드로만
 *                              드러납니다({@link dev.toktokhan.invalidation.core.resolve.QuerydslResolver}
 *                              참고)
 * @param lambdaBodies        {@code INVOKEDYNAMIC} 부트스트랩 인자가 가리키는 메서드
 */
public record MethodFacts(
    MethodRef ref,
    int access,
    List<MethodRef> calls,
    List<String> newTypes,
    List<String> stringConstants,
    Set<String> writtenOwnFields,
    Set<String> referencedFieldOwners,
    Map<String, AnnotationValues> annotations,
    List<MethodRef> lambdaBodies
) {
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    /**
     * 컴파일러가 만든 브릿지 메서드인지입니다. 공변 반환 재정의(반환 타입을 좁힌
     * 오버라이드)를 컴파일하면, 상위 타입의 소거된 시그니처를 만족시키는 브릿지 메서드가
     * 실제 메서드와 별도로 생깁니다 — 이름·파라미터가 같고 반환 타입만 다릅니다.
     * {@code ACC_SYNTHETIC} 까지 함께 걸러내면 안 됩니다. 람다 본문처럼 {@code lambdaBodies}
     * 가 가리키는 synthetic 메서드도 걸러져 워커가 그 본문을 찾지 못하게 됩니다.
     */
    public boolean isBridge() {
        return (access & Opcodes.ACC_BRIDGE) != 0;
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
