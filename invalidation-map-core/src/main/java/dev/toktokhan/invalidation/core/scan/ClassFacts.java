package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @param signature 클래스 제네릭 시그니처. 없으면 null
 *                  (예: {@code Lcom/querydsl/core/types/dsl/EntityPathBase<Lcom/example/Run;>;})
 */
public record ClassFacts(
    String internalName,
    String superName,
    List<String> interfaces,
    String signature,
    Map<String, AnnotationValues> annotations,
    List<FieldFacts> fields,
    List<MethodFacts> methods
) {
    public boolean hasAnnotation(String annotationDescriptor) {
        return annotations.containsKey(annotationDescriptor);
    }

    public AnnotationValues annotation(String annotationDescriptor) {
        return annotations.getOrDefault(annotationDescriptor, AnnotationValues.EMPTY);
    }

    public Optional<MethodFacts> method(MethodRef ref) {
        return methods.stream().filter(candidate -> candidate.ref().equals(ref)).findFirst();
    }

    /** 오버로드가 있으면 첫 번째를 돌려줍니다. 테스트와 진단용입니다. */
    public Optional<MethodFacts> methodNamed(String name) {
        return methods.stream().filter(candidate -> candidate.ref().name().equals(name)).findFirst();
    }
}
