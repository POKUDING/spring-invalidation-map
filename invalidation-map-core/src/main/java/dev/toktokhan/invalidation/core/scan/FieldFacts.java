package dev.toktokhan.invalidation.core.scan;

import java.util.Map;

/**
 * @param descriptor 필드 타입 디스크립터 (예: {@code Ljava/util/List;})
 * @param signature  제네릭 시그니처. 없으면 null (예: {@code Ljava/util/List<Lcom/example/RunPartner;>;})
 */
public record FieldFacts(
    String name,
    String descriptor,
    String signature,
    Map<String, AnnotationValues> annotations
) {
    public boolean hasAnnotation(String annotationDescriptor) {
        return annotations.containsKey(annotationDescriptor);
    }
}
