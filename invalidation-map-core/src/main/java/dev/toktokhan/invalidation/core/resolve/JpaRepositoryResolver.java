package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Spring Data 리포지토리 호출을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절의 1~2번입니다.
 */
public final class JpaRepositoryResolver implements EntityResolver {

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        Optional<String> repositoryEntity = context.repositories().entityFor(callee.owner());
        if (repositoryEntity.isEmpty()) {
            return Optional.empty();
        }

        Set<String> found = new LinkedHashSet<>();
        found.add(repositoryEntity.get());

        Optional<String> query = context.repositories().queryOf(callee);
        Optional<AccessKind> declaredKind = context.repositories().accessKindOf(callee);

        if (query.isEmpty()) {
            // 방향을 정할 수 없으면 미루고, 워커가 본문으로 내려가게 합니다.
            return declaredKind.map(kind ->
                new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind));
        }

        String queryText = query.get();
        AccessKind kind;
        if (context.repositories().isNativeQuery(callee)) {
            found.addAll(SqlTableExtractor.entities(queryText, context.entities()));
            kind = declaredKind.orElseGet(() -> SqlTableExtractor.kindOf(queryText));
        } else {
            found.addAll(JpqlEntityExtractor.entities(queryText, context.entities(), context.classes()));
            kind = declaredKind.orElseGet(() -> JpqlEntityExtractor.kindOf(queryText));
        }
        return Optional.of(new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind));
    }
}
