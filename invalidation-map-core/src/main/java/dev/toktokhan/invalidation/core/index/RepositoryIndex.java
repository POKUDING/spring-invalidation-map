package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data 리포지토리에 대한 판정입니다. 엔티티 매핑과 접근 방향, {@code @Query} 문자열입니다.
 */
public final class RepositoryIndex {

    private static final String QUERY = "Lorg/springframework/data/jpa/repository/Query;";
    private static final String MODIFYING = "Lorg/springframework/data/jpa/repository/Modifying;";

    private static final List<String> WRITE_PREFIXES = List.of(
        "save", "delete", "remove", "insert", "update", "persist", "merge", "flush");
    private static final List<String> READ_PREFIXES = List.of(
        "find", "read", "get", "query", "search", "stream", "exists", "count");

    private final ProgramModel program;
    private final ClassRepository classes;

    public RepositoryIndex(ProgramModel program, ClassRepository classes) {
        this.program = program;
        this.classes = classes;
    }

    public Optional<String> entityFor(String repositoryInternalName) {
        return program.entityFor(repositoryInternalName);
    }

    /**
     * 접근 방향입니다. 판정할 수 없으면 빈 값입니다.
     *
     * <p>빈 값은 "모른다"는 뜻이며, 워커가 메서드 본문으로 내려가 실제 접근을 찾도록 합니다.
     * 임의로 한쪽을 고르면 쓰기를 놓치거나 과잉이 심해집니다.
     */
    public Optional<AccessKind> accessKindOf(MethodRef ref) {
        if (hasModifying(ref)) {
            return Optional.of(AccessKind.WRITE);
        }
        String name = ref.name();
        if (startsWithAny(name, WRITE_PREFIXES)) {
            return Optional.of(AccessKind.WRITE);
        }
        if (startsWithAny(name, READ_PREFIXES)) {
            return Optional.of(AccessKind.READ);
        }
        return Optional.empty();
    }

    public Optional<String> queryOf(MethodRef ref) {
        return queryAnnotation(ref).flatMap(values -> values.string("value"))
            .filter(text -> !text.isBlank());
    }

    public boolean isNativeQuery(MethodRef ref) {
        return queryAnnotation(ref).map(values -> values.bool("nativeQuery", false)).orElse(false);
    }

    private boolean hasModifying(MethodRef ref) {
        return classes.resolveMethod(ref).filter(facts -> facts.hasAnnotation(MODIFYING)).isPresent();
    }

    private Optional<AnnotationValues> queryAnnotation(MethodRef ref) {
        return classes.resolveMethod(ref)
            .map(MethodFacts::annotations)
            .map(annotations -> annotations.get(QUERY));
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        return prefixes.stream().anyMatch(name::startsWith);
    }
}
